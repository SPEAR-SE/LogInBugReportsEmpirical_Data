/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.apache.hadoop.util.ExitUtil.terminate;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.CacheDirective;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.CachedBlocksList.Type;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.CacheManager;
import org.apache.hadoop.hdfs.server.namenode.CachePool;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.util.GSet;
import org.apache.hadoop.util.Time;

import com.google.common.base.Preconditions;

/**
 * Scans the namesystem, scheduling blocks to be cached as appropriate.
 *
 * The CacheReplicationMonitor does a full scan when the NameNode first
 * starts up, and at configurable intervals afterwards.
 */
@InterfaceAudience.LimitedPrivate({"HDFS"})
public class CacheReplicationMonitor extends Thread implements Closeable {

  private static final Log LOG =
      LogFactory.getLog(CacheReplicationMonitor.class);

  private final FSNamesystem namesystem;

  private final BlockManager blockManager;

  private final CacheManager cacheManager;

  private final GSet<CachedBlock, CachedBlock> cachedBlocks;

  /**
   * Pseudorandom number source
   */
  private final Random random = new Random();

  /**
   * The interval at which we scan the namesystem for caching changes.
   */
  private final long intervalMs;

  /**
   * The CacheReplicationMonitor (CRM) lock. Used to synchronize starting and
   * waiting for rescan operations.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Notifies the scan thread that an immediate rescan is needed.
   */
  private final Condition doRescan = lock.newCondition();

  /**
   * Notifies waiting threads that a rescan has finished.
   */
  private final Condition scanFinished = lock.newCondition();

  /**
   * Whether there are pending CacheManager operations that necessitate a
   * CacheReplicationMonitor rescan. Protected by the CRM lock.
   */
  private boolean needsRescan = true;

  /**
   * Whether we are currently doing a rescan. Protected by the CRM lock.
   */
  private boolean isScanning = false;

  /**
   * The number of rescans completed. Used to wait for scans to finish.
   * Protected by the CacheReplicationMonitor lock.
   */
  private long scanCount = 0;

  /**
   * True if this monitor should terminate. Protected by the CRM lock.
   */
  private boolean shutdown = false;

  /**
   * The monotonic time at which the current scan started.
   */
  private long startTimeMs;

  /**
   * Mark status of the current scan.
   */
  private boolean mark = false;

  /**
   * Cache directives found in the previous scan.
   */
  private int scannedDirectives;

  /**
   * Blocks found in the previous scan.
   */
  private long scannedBlocks;

  public CacheReplicationMonitor(FSNamesystem namesystem,
      CacheManager cacheManager, long intervalMs) {
    this.namesystem = namesystem;
    this.blockManager = namesystem.getBlockManager();
    this.cacheManager = cacheManager;
    this.cachedBlocks = cacheManager.getCachedBlocks();
    this.intervalMs = intervalMs;
  }

  @Override
  public void run() {
    startTimeMs = 0;
    LOG.info("Starting CacheReplicationMonitor with interval " +
             intervalMs + " milliseconds");
    try {
      long curTimeMs = Time.monotonicNow();
      while (true) {
        // Not all of the variables accessed here need the CRM lock, but take
        // it anyway for simplicity
        lock.lock();
        try {
          while (true) {
            if (shutdown) {
              LOG.info("Shutting down CacheReplicationMonitor");
              return;
            }
            if (needsRescan) {
              LOG.info("Rescanning because of pending operations");
              break;
            }
            long delta = (startTimeMs + intervalMs) - curTimeMs;
            if (delta <= 0) {
              LOG.info("Rescanning after " + (curTimeMs - startTimeMs) +
                  " milliseconds");
              break;
            }
            doRescan.await(delta, TimeUnit.MILLISECONDS);
            curTimeMs = Time.monotonicNow();
          }
        } finally {
          lock.unlock();
        }
        // Mark scan as started, clear needsRescan
        lock.lock();
        try {
          isScanning = true;
          needsRescan = false;
        } finally {
          lock.unlock();
        }
        startTimeMs = curTimeMs;
        mark = !mark;
        rescan();
        curTimeMs = Time.monotonicNow();
        // Retake the CRM lock to update synchronization-related variables
        lock.lock();
        try {
          isScanning = false;
          scanCount++;
          scanFinished.signalAll();
        } finally {
          lock.unlock();
        }
        LOG.info("Scanned " + scannedDirectives + " directive(s) and " +
            scannedBlocks + " block(s) in " + (curTimeMs - startTimeMs) + " " +
            "millisecond(s).");
      }
    } catch (Throwable t) {
      LOG.fatal("Thread exiting", t);
      terminate(1, t);
    }
  }

  /**
   * Similar to {@link CacheReplicationMonitor#waitForRescan()}, except it only
   * waits if there are pending operations that necessitate a rescan as
   * indicated by {@link #setNeedsRescan()}.
   * <p>
   * Note that this call may release the FSN lock, so operations before and
   * after are not necessarily atomic.
   */
  public void waitForRescanIfNeeded() {
    lock.lock();
    try {
      if (!needsRescan) {
        return;
      }
    } finally {
      lock.unlock();
    }
    waitForRescan();
  }

  /**
   * Waits for a rescan to complete. This doesn't guarantee consistency with
   * pending operations, only relative recency, since it will not force a new
   * rescan if a rescan is already underway.
   * <p>
   * Note that this call will release the FSN lock, so operations before and
   * after are not atomic.
   */
  public void waitForRescan() {
    // Drop the FSN lock temporarily and retake it after we finish waiting
    // Need to handle both the read lock and the write lock
    boolean retakeWriteLock = false;
    if (namesystem.hasWriteLock()) {
      namesystem.writeUnlock();
      retakeWriteLock = true;
    } else if (namesystem.hasReadLock()) {
      namesystem.readUnlock();
    } else {
      // Expected to have at least one of the locks
      Preconditions.checkState(false,
          "Need to be holding either the read or write lock");
    }
    // try/finally for retaking FSN lock
    try {
      lock.lock();
      // try/finally for releasing CRM lock
      try {
        // If no scan is already ongoing, mark the CRM as dirty and kick
        if (!isScanning) {
          needsRescan = true;
          doRescan.signal();
        }
        // Wait until the scan finishes and the count advances
        final long startCount = scanCount;
        while (startCount >= scanCount) {
          try {
            scanFinished.await();
          } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for CacheReplicationMonitor"
                + " rescan", e);
            break;
          }
        }
      } finally {
        lock.unlock();
      }
    } finally {
      if (retakeWriteLock) {
        namesystem.writeLock();
      } else {
        namesystem.readLock();
      }
    }
  }

  /**
   * Indicates to the CacheReplicationMonitor that there have been CacheManager
   * changes that require a rescan.
   */
  public void setNeedsRescan() {
    lock.lock();
    try {
      this.needsRescan = true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Shut down and join the monitor thread.
   */
  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (shutdown) return;
      shutdown = true;
      doRescan.signalAll();
      scanFinished.signalAll();
    } finally {
      lock.unlock();
    }
    try {
      if (this.isAlive()) {
        this.join(60000);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void rescan() {
    scannedDirectives = 0;
    scannedBlocks = 0;
    namesystem.writeLock();
    try {
      resetStatistics();
      rescanCacheDirectives();
      rescanCachedBlockMap();
      blockManager.getDatanodeManager().resetLastCachingDirectiveSentTime();
    } finally {
      namesystem.writeUnlock();
    }
  }

  private void resetStatistics() {
    for (CachePool pool: cacheManager.getCachePools()) {
      pool.resetStatistics();
    }
    for (CacheDirective directive: cacheManager.getCacheDirectives()) {
      directive.resetStatistics();
    }
  }

  /**
   * Scan all CacheDirectives.  Use the information to figure out
   * what cache replication factor each block should have.
   */
  private void rescanCacheDirectives() {
    FSDirectory fsDir = namesystem.getFSDirectory();
    final long now = new Date().getTime();
    for (CacheDirective directive : cacheManager.getCacheDirectives()) {
      // Reset the directive's statistics
      directive.resetStatistics();
      // Skip processing this entry if it has expired
      if (LOG.isTraceEnabled()) {
        LOG.trace("Directive expiry is at " + directive.getExpiryTime());
      }
      if (directive.getExpiryTime() > 0 && directive.getExpiryTime() <= now) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping directive id " + directive.getId()
              + " because it has expired (" + directive.getExpiryTime() + ">="
              + now + ")");
        }
        continue;
      }
      scannedDirectives++;
      String path = directive.getPath();
      INode node;
      try {
        node = fsDir.getINode(path);
      } catch (UnresolvedLinkException e) {
        // We don't cache through symlinks
        continue;
      }
      if (node == null)  {
        if (LOG.isDebugEnabled()) {
          LOG.debug("No inode found at " + path);
        }
      } else if (node.isDirectory()) {
        INodeDirectory dir = node.asDirectory();
        ReadOnlyList<INode> children = dir.getChildrenList(null);
        for (INode child : children) {
          if (child.isFile()) {
            rescanFile(directive, child.asFile());
          }
        }
      } else if (node.isFile()) {
        rescanFile(directive, node.asFile());
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Ignoring non-directory, non-file inode " + node +
                    " found at " + path);
        }
      }
    }
  }
  
  /**
   * Apply a CacheDirective to a file.
   * 
   * @param directive The CacheDirective to apply.
   * @param file The file.
   */
  private void rescanFile(CacheDirective directive, INodeFile file) {
    BlockInfo[] blockInfos = file.getBlocks();

    // Increment the "needed" statistics
    directive.addFilesNeeded(1);
    // We don't cache UC blocks, don't add them to the total here
    long neededTotal = file.computeFileSizeNotIncludingLastUcBlock() *
        directive.getReplication();
    directive.addBytesNeeded(neededTotal);

    // The pool's bytesNeeded is incremented as we scan. If the demand
    // thus far plus the demand of this file would exceed the pool's limit,
    // do not cache this file.
    CachePool pool = directive.getPool();
    if (pool.getBytesNeeded() > pool.getLimit()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Skipping directive id %d file %s because "
            + "limit of pool %s would be exceeded (%d > %d)",
            directive.getId(),
            file.getFullPathName(),
            pool.getPoolName(),
            pool.getBytesNeeded(),
            pool.getLimit()));
      }
      return;
    }

    long cachedTotal = 0;
    for (BlockInfo blockInfo : blockInfos) {
      if (!blockInfo.getBlockUCState().equals(BlockUCState.COMPLETE)) {
        // We don't try to cache blocks that are under construction.
        continue;
      }
      Block block = new Block(blockInfo.getBlockId());
      CachedBlock ncblock = new CachedBlock(block.getBlockId(),
          directive.getReplication(), mark);
      CachedBlock ocblock = cachedBlocks.get(ncblock);
      if (ocblock == null) {
        cachedBlocks.put(ncblock);
      } else {
        // Update bytesUsed using the current replication levels.
        // Assumptions: we assume that all the blocks are the same length
        // on each datanode.  We can assume this because we're only caching
        // blocks in state COMMITTED.
        // Note that if two directives are caching the same block(s), they will
        // both get them added to their bytesCached.
        List<DatanodeDescriptor> cachedOn =
            ocblock.getDatanodes(Type.CACHED);
        long cachedByBlock = Math.min(cachedOn.size(),
            directive.getReplication()) * blockInfo.getNumBytes();
        cachedTotal += cachedByBlock;

        if (mark != ocblock.getMark()) {
          // Mark hasn't been set in this scan, so update replication and mark.
          ocblock.setReplicationAndMark(directive.getReplication(), mark);
        } else {
          // Mark already set in this scan.  Set replication to highest value in
          // any CacheDirective that covers this file.
          ocblock.setReplicationAndMark((short)Math.max(
              directive.getReplication(), ocblock.getReplication()), mark);
        }
      }
    }
    // Increment the "cached" statistics
    directive.addBytesCached(cachedTotal);
    if (cachedTotal == neededTotal) {
      directive.addFilesCached(1);
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("Directive " + directive.getId() + " is caching " +
          file.getFullPathName() + ": " + cachedTotal + "/" + neededTotal +
          " bytes");
    }
  }

  /**
   * Scan through the cached block map.
   * Any blocks which are under-replicated should be assigned new Datanodes.
   * Blocks that are over-replicated should be removed from Datanodes.
   */
  private void rescanCachedBlockMap() {
    for (Iterator<CachedBlock> cbIter = cachedBlocks.iterator();
        cbIter.hasNext(); ) {
      scannedBlocks++;
      CachedBlock cblock = cbIter.next();
      List<DatanodeDescriptor> pendingCached =
          cblock.getDatanodes(Type.PENDING_CACHED);
      List<DatanodeDescriptor> cached =
          cblock.getDatanodes(Type.CACHED);
      List<DatanodeDescriptor> pendingUncached =
          cblock.getDatanodes(Type.PENDING_UNCACHED);
      // Remove nodes from PENDING_UNCACHED if they were actually uncached.
      for (Iterator<DatanodeDescriptor> iter = pendingUncached.iterator();
          iter.hasNext(); ) {
        DatanodeDescriptor datanode = iter.next();
        if (!cblock.isInList(datanode.getCached())) {
          datanode.getPendingUncached().remove(cblock);
          iter.remove();
        }
      }
      // If the block's mark doesn't match with the mark of this scan, that
      // means that this block couldn't be reached during this scan.  That means
      // it doesn't need to be cached any more.
      int neededCached = (cblock.getMark() != mark) ?
          0 : cblock.getReplication();
      int numCached = cached.size();
      if (numCached >= neededCached) {
        // If we have enough replicas, drop all pending cached.
        for (Iterator<DatanodeDescriptor> iter = pendingCached.iterator();
            iter.hasNext(); ) {
          DatanodeDescriptor datanode = iter.next();
          datanode.getPendingCached().remove(cblock);
          iter.remove();
        }
      }
      if (numCached < neededCached) {
        // If we don't have enough replicas, drop all pending uncached.
        for (Iterator<DatanodeDescriptor> iter = pendingUncached.iterator();
            iter.hasNext(); ) {
          DatanodeDescriptor datanode = iter.next();
          datanode.getPendingUncached().remove(cblock);
          iter.remove();
        }
      }
      int neededUncached = numCached -
          (pendingUncached.size() + neededCached);
      if (neededUncached > 0) {
        addNewPendingUncached(neededUncached, cblock, cached,
            pendingUncached);
      } else {
        int additionalCachedNeeded = neededCached -
            (numCached + pendingCached.size());
        if (additionalCachedNeeded > 0) {
          addNewPendingCached(additionalCachedNeeded, cblock, cached,
              pendingCached);
        }
      }
      if ((neededCached == 0) &&
          pendingUncached.isEmpty() &&
          pendingCached.isEmpty()) {
        // we have nothing more to do with this block.
        cbIter.remove();
      }
    }
  }

  /**
   * Add new entries to the PendingUncached list.
   *
   * @param neededUncached   The number of replicas that need to be uncached.
   * @param cachedBlock      The block which needs to be uncached.
   * @param cached           A list of DataNodes currently caching the block.
   * @param pendingUncached  A list of DataNodes that will soon uncache the
   *                         block.
   */
  private void addNewPendingUncached(int neededUncached,
      CachedBlock cachedBlock, List<DatanodeDescriptor> cached,
      List<DatanodeDescriptor> pendingUncached) {
    if (!cacheManager.isActive()) {
      return;
    }
    // Figure out which replicas can be uncached.
    LinkedList<DatanodeDescriptor> possibilities =
        new LinkedList<DatanodeDescriptor>();
    for (DatanodeDescriptor datanode : cached) {
      if (!pendingUncached.contains(datanode)) {
        possibilities.add(datanode);
      }
    }
    while (neededUncached > 0) {
      if (possibilities.isEmpty()) {
        LOG.warn("Logic error: we're trying to uncache more replicas than " +
            "actually exist for " + cachedBlock);
        return;
      }
      DatanodeDescriptor datanode =
        possibilities.remove(random.nextInt(possibilities.size()));
      pendingUncached.add(datanode);
      boolean added = datanode.getPendingUncached().add(cachedBlock);
      assert added;
      neededUncached--;
    }
  }
  
  /**
   * Add new entries to the PendingCached list.
   *
   * @param neededCached     The number of replicas that need to be cached.
   * @param cachedBlock      The block which needs to be cached.
   * @param cached           A list of DataNodes currently caching the block.
   * @param pendingCached    A list of DataNodes that will soon cache the
   *                         block.
   */
  private void addNewPendingCached(int neededCached,
      CachedBlock cachedBlock, List<DatanodeDescriptor> cached,
      List<DatanodeDescriptor> pendingCached) {
    if (!cacheManager.isActive()) {
      return;
    }
    // To figure out which replicas can be cached, we consult the
    // blocksMap.  We don't want to try to cache a corrupt replica, though.
    BlockInfo blockInfo = blockManager.
          getStoredBlock(new Block(cachedBlock.getBlockId()));
    if (blockInfo == null) {
      LOG.debug("Not caching block " + cachedBlock + " because it " +
          "was deleted from all DataNodes.");
      return;
    }
    if (!blockInfo.isComplete()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not caching block " + cachedBlock + " because it " +
            "is not yet complete.");
      }
      return;
    }
    List<DatanodeDescriptor> possibilities = new LinkedList<DatanodeDescriptor>();
    int numReplicas = blockInfo.getCapacity();
    Collection<DatanodeDescriptor> corrupt =
        blockManager.getCorruptReplicas(blockInfo);
    for (int i = 0; i < numReplicas; i++) {
      DatanodeDescriptor datanode = blockInfo.getDatanode(i);
      if ((datanode != null) && 
          ((!pendingCached.contains(datanode)) &&
          ((corrupt == null) || (!corrupt.contains(datanode))))) {
        possibilities.add(datanode);
      }
    }
    while (neededCached > 0) {
      if (possibilities.isEmpty()) {
        LOG.warn("We need " + neededCached + " more replica(s) than " +
            "actually exist to provide a cache replication of " +
            cachedBlock.getReplication() + " for " + cachedBlock);
        return;
      }
      DatanodeDescriptor datanode =
          possibilities.remove(random.nextInt(possibilities.size()));
      if (LOG.isDebugEnabled()) {
        LOG.debug("AddNewPendingCached: datanode " + datanode + 
            " will now cache block " + cachedBlock);
      }
      pendingCached.add(datanode);
      boolean added = datanode.getPendingCached().add(cachedBlock);
      assert added;
      neededCached--;
    }
  }
}
