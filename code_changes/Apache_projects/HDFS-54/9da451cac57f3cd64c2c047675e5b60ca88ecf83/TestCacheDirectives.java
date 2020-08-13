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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CACHEREPORT_INTERVAL_MSEC_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_MAX_LOCKED_MEMORY_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_CACHING_ENABLED_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_PATH_BASED_CACHE_REFRESH_INTERVAL_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.InvalidRequestException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveEntry;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveInfo;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveStats;
import org.apache.hadoop.hdfs.protocol.CachePoolEntry;
import org.apache.hadoop.hdfs.protocol.CachePoolInfo;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveInfo.Expiration;
import org.apache.hadoop.hdfs.server.blockmanagement.CacheReplicationMonitor;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.CachedBlocksList.Type;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.io.nativeio.NativeIO.POSIX.CacheManipulator;
import org.apache.hadoop.io.nativeio.NativeIO.POSIX.NoMlockCacheManipulator;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.GSet;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Supplier;

public class TestCacheDirectives {
  static final Log LOG = LogFactory.getLog(TestCacheDirectives.class);

  private static final UserGroupInformation unprivilegedUser =
      UserGroupInformation.createRemoteUser("unprivilegedUser");

  static private Configuration conf;
  static private MiniDFSCluster cluster;
  static private DistributedFileSystem dfs;
  static private NamenodeProtocols proto;
  static private CacheManipulator prevCacheManipulator;

  static {
    EditLogFileOutputStream.setShouldSkipFsyncForTesting(false);
  }

  @Before
  public void setup() throws Exception {
    conf = new HdfsConfiguration();
    // set low limits here for testing purposes
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_LIST_CACHE_POOLS_NUM_RESPONSES, 2);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_LIST_CACHE_DIRECTIVES_NUM_RESPONSES, 2);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    dfs = cluster.getFileSystem();
    proto = cluster.getNameNodeRpc();
    prevCacheManipulator = NativeIO.POSIX.getCacheManipulator();
    NativeIO.POSIX.setCacheManipulator(new NoMlockCacheManipulator());
    LogManager.getLogger(CacheReplicationMonitor.class).setLevel(Level.TRACE);
  }

  @After
  public void teardown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
    // Restore the original CacheManipulator
    NativeIO.POSIX.setCacheManipulator(prevCacheManipulator);
  }

  @Test(timeout=60000)
  public void testBasicPoolOperations() throws Exception {
    final String poolName = "pool1";
    CachePoolInfo info = new CachePoolInfo(poolName).
        setOwnerName("bob").setGroupName("bobgroup").
        setMode(new FsPermission((short)0755)).setWeight(150);

    // Add a pool
    dfs.addCachePool(info);

    // Do some bad addCachePools
    try {
      dfs.addCachePool(info);
      fail("added the pool with the same name twice");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("pool1 already exists", ioe);
    }
    try {
      dfs.addCachePool(new CachePoolInfo(""));
      fail("added empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.addCachePool(null);
      fail("added null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }
    try {
      proto.addCachePool(new CachePoolInfo(""));
      fail("added empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.addCachePool(null);
      fail("added null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }

    // Modify the pool
    info.setOwnerName("jane").setGroupName("janegroup")
        .setMode(new FsPermission((short)0700)).setWeight(314);
    dfs.modifyCachePool(info);

    // Do some invalid modify pools
    try {
      dfs.modifyCachePool(new CachePoolInfo("fool"));
      fail("modified non-existent cache pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("fool does not exist", ioe);
    }
    try {
      dfs.modifyCachePool(new CachePoolInfo(""));
      fail("modified empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.modifyCachePool(null);
      fail("modified null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }
    try {
      proto.modifyCachePool(new CachePoolInfo(""));
      fail("modified empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.modifyCachePool(null);
      fail("modified null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("CachePoolInfo is null", ioe);
    }

    // Remove the pool
    dfs.removeCachePool(poolName);
    // Do some bad removePools
    try {
      dfs.removeCachePool("pool99");
      fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("Cannot remove " +
          "non-existent cache pool", ioe);
    }
    try {
      dfs.removeCachePool(poolName);
      fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("Cannot remove " +
          "non-existent cache pool", ioe);
    }
    try {
      dfs.removeCachePool("");
      fail("removed empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      dfs.removeCachePool(null);
      fail("removed null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.removeCachePool("");
      fail("removed empty pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }
    try {
      proto.removeCachePool(null);
      fail("removed null pool");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("invalid empty cache pool name",
          ioe);
    }

    info = new CachePoolInfo("pool2");
    dfs.addCachePool(info);
  }

  @Test(timeout=60000)
  public void testCreateAndModifyPools() throws Exception {
    String poolName = "pool1";
    String ownerName = "abc";
    String groupName = "123";
    FsPermission mode = new FsPermission((short)0755);
    int weight = 150;
    dfs.addCachePool(new CachePoolInfo(poolName).
        setOwnerName(ownerName).setGroupName(groupName).
        setMode(mode).setWeight(weight));
    
    RemoteIterator<CachePoolEntry> iter = dfs.listCachePools();
    CachePoolInfo info = iter.next().getInfo();
    assertEquals(poolName, info.getPoolName());
    assertEquals(ownerName, info.getOwnerName());
    assertEquals(groupName, info.getGroupName());

    ownerName = "def";
    groupName = "456";
    mode = new FsPermission((short)0700);
    weight = 151;
    dfs.modifyCachePool(new CachePoolInfo(poolName).
        setOwnerName(ownerName).setGroupName(groupName).
        setMode(mode).setWeight(weight));

    iter = dfs.listCachePools();
    info = iter.next().getInfo();
    assertEquals(poolName, info.getPoolName());
    assertEquals(ownerName, info.getOwnerName());
    assertEquals(groupName, info.getGroupName());
    assertEquals(mode, info.getMode());
    assertEquals(Integer.valueOf(weight), info.getWeight());

    dfs.removeCachePool(poolName);
    iter = dfs.listCachePools();
    assertFalse("expected no cache pools after deleting pool", iter.hasNext());

    proto.listCachePools(null);

    try {
      proto.removeCachePool("pool99");
      fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("Cannot remove non-existent",
          ioe);
    }
    try {
      proto.removeCachePool(poolName);
      fail("expected to get an exception when " +
          "removing a non-existent pool.");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains("Cannot remove non-existent",
          ioe);
    }

    iter = dfs.listCachePools();
    assertFalse("expected no cache pools after deleting pool", iter.hasNext());
  }

  private static void validateListAll(
      RemoteIterator<CacheDirectiveEntry> iter,
      Long... ids) throws Exception {
    for (Long id: ids) {
      assertTrue("Unexpectedly few elements", iter.hasNext());
      assertEquals("Unexpected directive ID", id,
          iter.next().getInfo().getId());
    }
    assertFalse("Unexpectedly many list elements", iter.hasNext());
  }

  private static long addAsUnprivileged(
      final CacheDirectiveInfo directive) throws Exception {
    return unprivilegedUser
        .doAs(new PrivilegedExceptionAction<Long>() {
          @Override
          public Long run() throws IOException {
            DistributedFileSystem myDfs =
                (DistributedFileSystem) FileSystem.get(conf);
            return myDfs.addCacheDirective(directive);
          }
        });
  }

  @Test(timeout=60000)
  public void testAddRemoveDirectives() throws Exception {
    proto.addCachePool(new CachePoolInfo("pool1").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool2").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool3").
        setMode(new FsPermission((short)0777)));
    proto.addCachePool(new CachePoolInfo("pool4").
        setMode(new FsPermission((short)0)));

    CacheDirectiveInfo alpha = new CacheDirectiveInfo.Builder().
        setPath(new Path("/alpha")).
        setPool("pool1").
        build();
    CacheDirectiveInfo beta = new CacheDirectiveInfo.Builder().
        setPath(new Path("/beta")).
        setPool("pool2").
        build();
    CacheDirectiveInfo delta = new CacheDirectiveInfo.Builder().
        setPath(new Path("/delta")).
        setPool("pool1").
        build();

    long alphaId = addAsUnprivileged(alpha);
    long alphaId2 = addAsUnprivileged(alpha);
    assertFalse("Expected to get unique directives when re-adding an "
        + "existing CacheDirectiveInfo",
        alphaId == alphaId2);
    long betaId = addAsUnprivileged(beta);

    try {
      addAsUnprivileged(new CacheDirectiveInfo.Builder().
          setPath(new Path("/unicorn")).
          setPool("no_such_pool").
          build());
      fail("expected an error when adding to a non-existent pool.");
    } catch (InvalidRequestException ioe) {
      GenericTestUtils.assertExceptionContains("Unknown pool", ioe);
    }

    try {
      addAsUnprivileged(new CacheDirectiveInfo.Builder().
          setPath(new Path("/blackhole")).
          setPool("pool4").
          build());
      fail("expected an error when adding to a pool with " +
          "mode 0 (no permissions for anyone).");
    } catch (AccessControlException e) {
      GenericTestUtils.
          assertExceptionContains("Permission denied while accessing pool", e);
    }

    try {
      addAsUnprivileged(new CacheDirectiveInfo.Builder().
          setPath(new Path("/illegal:path/")).
          setPool("pool1").
          build());
      fail("expected an error when adding a malformed path " +
          "to the cache directives.");
    } catch (IllegalArgumentException e) {
      GenericTestUtils.assertExceptionContains("is not a valid DFS filename", e);
    }

    try {
      addAsUnprivileged(new CacheDirectiveInfo.Builder().
          setPath(new Path("/emptypoolname")).
          setReplication((short)1).
          setPool("").
          build());
      fail("expected an error when adding a cache " +
          "directive with an empty pool name.");
    } catch (InvalidRequestException e) {
      GenericTestUtils.assertExceptionContains("Invalid empty pool name", e);
    }

    long deltaId = addAsUnprivileged(delta);

    // We expect the following to succeed, because DistributedFileSystem
    // qualifies the path.
    long relativeId = addAsUnprivileged(
        new CacheDirectiveInfo.Builder().
            setPath(new Path("relative")).
            setPool("pool1").
            build());

    RemoteIterator<CacheDirectiveEntry> iter;
    iter = dfs.listCacheDirectives(null);
    validateListAll(iter, alphaId, alphaId2, betaId, deltaId, relativeId );
    iter = dfs.listCacheDirectives(
        new CacheDirectiveInfo.Builder().setPool("pool3").build());
    assertFalse(iter.hasNext());
    iter = dfs.listCacheDirectives(
        new CacheDirectiveInfo.Builder().setPool("pool1").build());
    validateListAll(iter, alphaId, alphaId2, deltaId, relativeId );
    iter = dfs.listCacheDirectives(
        new CacheDirectiveInfo.Builder().setPool("pool2").build());
    validateListAll(iter, betaId);

    dfs.removeCacheDirective(betaId);
    iter = dfs.listCacheDirectives(
        new CacheDirectiveInfo.Builder().setPool("pool2").build());
    assertFalse(iter.hasNext());

    try {
      dfs.removeCacheDirective(betaId);
      fail("expected an error when removing a non-existent ID");
    } catch (InvalidRequestException e) {
      GenericTestUtils.assertExceptionContains("No directive with ID", e);
    }

    try {
      proto.removeCacheDirective(-42l);
      fail("expected an error when removing a negative ID");
    } catch (InvalidRequestException e) {
      GenericTestUtils.assertExceptionContains(
          "Invalid negative ID", e);
    }
    try {
      proto.removeCacheDirective(43l);
      fail("expected an error when removing a non-existent ID");
    } catch (InvalidRequestException e) {
      GenericTestUtils.assertExceptionContains("No directive with ID", e);
    }

    dfs.removeCacheDirective(alphaId);
    dfs.removeCacheDirective(alphaId2);
    dfs.removeCacheDirective(deltaId);

    dfs.modifyCacheDirective(new CacheDirectiveInfo.Builder().
        setId(relativeId).
        setReplication((short)555).
        build());
    iter = dfs.listCacheDirectives(null);
    assertTrue(iter.hasNext());
    CacheDirectiveInfo modified = iter.next().getInfo();
    assertEquals(relativeId, modified.getId().longValue());
    assertEquals((short)555, modified.getReplication().shortValue());
    dfs.removeCacheDirective(relativeId);
    iter = dfs.listCacheDirectives(null);
    assertFalse(iter.hasNext());

    // Verify that PBCDs with path "." work correctly
    CacheDirectiveInfo directive =
        new CacheDirectiveInfo.Builder().setPath(new Path("."))
            .setPool("pool1").build();
    long id = dfs.addCacheDirective(directive);
    dfs.modifyCacheDirective(new CacheDirectiveInfo.Builder(
        directive).setId(id).setReplication((short)2).build());
    dfs.removeCacheDirective(id);
  }

  @Test(timeout=60000)
  public void testCacheManagerRestart() throws Exception {
    cluster.shutdown();
    cluster = null;
    HdfsConfiguration conf = createCachingConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();

    cluster.waitActive();
    DistributedFileSystem dfs = cluster.getFileSystem();

    // Create and validate a pool
    final String pool = "poolparty";
    String groupName = "partygroup";
    FsPermission mode = new FsPermission((short)0777);
    int weight = 747;
    dfs.addCachePool(new CachePoolInfo(pool)
        .setGroupName(groupName)
        .setMode(mode)
        .setWeight(weight));
    RemoteIterator<CachePoolEntry> pit = dfs.listCachePools();
    assertTrue("No cache pools found", pit.hasNext());
    CachePoolInfo info = pit.next().getInfo();
    assertEquals(pool, info.getPoolName());
    assertEquals(groupName, info.getGroupName());
    assertEquals(mode, info.getMode());
    assertEquals(weight, (int)info.getWeight());
    assertFalse("Unexpected # of cache pools found", pit.hasNext());
  
    // Create some cache entries
    int numEntries = 10;
    String entryPrefix = "/party-";
    long prevId = -1;
    final Date expiry = new Date();
    for (int i=0; i<numEntries; i++) {
      prevId = dfs.addCacheDirective(
          new CacheDirectiveInfo.Builder().
            setPath(new Path(entryPrefix + i)).setPool(pool).
            setExpiration(
                CacheDirectiveInfo.Expiration.newAbsolute(expiry.getTime())).
            build());
    }
    RemoteIterator<CacheDirectiveEntry> dit
        = dfs.listCacheDirectives(null);
    for (int i=0; i<numEntries; i++) {
      assertTrue("Unexpected # of cache entries: " + i, dit.hasNext());
      CacheDirectiveInfo cd = dit.next().getInfo();
      assertEquals(i+1, cd.getId().longValue());
      assertEquals(entryPrefix + i, cd.getPath().toUri().getPath());
      assertEquals(pool, cd.getPool());
    }
    assertFalse("Unexpected # of cache directives found", dit.hasNext());
  
    // Restart namenode
    cluster.restartNameNode();
  
    // Check that state came back up
    pit = dfs.listCachePools();
    assertTrue("No cache pools found", pit.hasNext());
    info = pit.next().getInfo();
    assertEquals(pool, info.getPoolName());
    assertEquals(pool, info.getPoolName());
    assertEquals(groupName, info.getGroupName());
    assertEquals(mode, info.getMode());
    assertEquals(weight, (int)info.getWeight());
    assertFalse("Unexpected # of cache pools found", pit.hasNext());
  
    dit = dfs.listCacheDirectives(null);
    for (int i=0; i<numEntries; i++) {
      assertTrue("Unexpected # of cache entries: " + i, dit.hasNext());
      CacheDirectiveInfo cd = dit.next().getInfo();
      assertEquals(i+1, cd.getId().longValue());
      assertEquals(entryPrefix + i, cd.getPath().toUri().getPath());
      assertEquals(pool, cd.getPool());
      assertEquals(expiry.getTime(), cd.getExpiration().getMillis());
    }
    assertFalse("Unexpected # of cache directives found", dit.hasNext());

    long nextId = dfs.addCacheDirective(
          new CacheDirectiveInfo.Builder().
            setPath(new Path("/foobar")).setPool(pool).build());
    assertEquals(prevId + 1, nextId);
  }

  /**
   * Wait for the NameNode to have an expected number of cached blocks
   * and replicas.
   * @param nn NameNode
   * @param expectedCachedBlocks
   * @param expectedCachedReplicas
   * @throws Exception
   */
  private static void waitForCachedBlocks(NameNode nn,
      final int expectedCachedBlocks, final int expectedCachedReplicas,
      final String logString) throws Exception {
    final FSNamesystem namesystem = nn.getNamesystem();
    final CacheManager cacheManager = namesystem.getCacheManager();
    LOG.info("Waiting for " + expectedCachedBlocks + " blocks with " +
             expectedCachedReplicas + " replicas.");
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        int numCachedBlocks = 0, numCachedReplicas = 0;
        namesystem.readLock();
        try {
          GSet<CachedBlock, CachedBlock> cachedBlocks =
              cacheManager.getCachedBlocks();
          if (cachedBlocks != null) {
            for (Iterator<CachedBlock> iter = cachedBlocks.iterator();
                iter.hasNext(); ) {
              CachedBlock cachedBlock = iter.next();
              numCachedBlocks++;
              numCachedReplicas += cachedBlock.getDatanodes(Type.CACHED).size();
            }
          }
        } finally {
          namesystem.readUnlock();
        }
        if ((numCachedBlocks == expectedCachedBlocks) && 
            (numCachedReplicas == expectedCachedReplicas)) {
          return true;
        } else {
          LOG.info(logString + " cached blocks: have " + numCachedBlocks +
              " / " + expectedCachedBlocks + ".  " +
              "cached replicas: have " + numCachedReplicas +
              " / " + expectedCachedReplicas);
          return false;
        }
      }
    }, 500, 60000);
  }

  private static void waitForCachedStats(final DistributedFileSystem dfs,
      final long targetFilesAffected, final long targetBytesNeeded,
        final long targetBytesCached,
          final CacheDirectiveInfo filter, final String infoString)
            throws Exception {
      LOG.info("Polling listDirectives{" + 
          ((filter == null) ? "ALL" : filter.toString()) +
          " for " + targetFilesAffected + " targetFilesAffected, " +
          targetBytesNeeded + " targetBytesNeeded, " +
          targetBytesCached + " targetBytesCached");
      GenericTestUtils.waitFor(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
          RemoteIterator<CacheDirectiveEntry> iter = null;
          CacheDirectiveEntry entry = null;
          try {
            iter = dfs.listCacheDirectives(filter);
            entry = iter.next();
          } catch (IOException e) {
            fail("got IOException while calling " +
                "listCacheDirectives: " + e.getMessage());
          }
          Assert.assertNotNull(entry);
          CacheDirectiveStats stats = entry.getStats();
          if ((targetFilesAffected == stats.getFilesAffected()) &&
              (targetBytesNeeded == stats.getBytesNeeded()) &&
              (targetBytesCached == stats.getBytesCached())) {
            return true;
          } else {
            LOG.info(infoString + ": filesAffected: " + 
              stats.getFilesAffected() + "/" + targetFilesAffected +
              ", bytesNeeded: " +
                stats.getBytesNeeded() + "/" + targetBytesNeeded +
              ", bytesCached: " + 
                stats.getBytesCached() + "/" + targetBytesCached);
            return false;
          }
        }
      }, 500, 60000);
  }

  private static void checkNumCachedReplicas(final DistributedFileSystem dfs,
      final List<Path> paths, final int expectedBlocks,
      final int expectedReplicas)
      throws Exception {
    int numCachedBlocks = 0;
    int numCachedReplicas = 0;
    for (Path p: paths) {
      final FileStatus f = dfs.getFileStatus(p);
      final long len = f.getLen();
      final long blockSize = f.getBlockSize();
      // round it up to full blocks
      final long numBlocks = (len + blockSize - 1) / blockSize;
      BlockLocation[] locs = dfs.getFileBlockLocations(p, 0, len);
      assertEquals("Unexpected number of block locations for path " + p,
          numBlocks, locs.length);
      for (BlockLocation l: locs) {
        if (l.getCachedHosts().length > 0) {
          numCachedBlocks++;
        }
        numCachedReplicas += l.getCachedHosts().length;
      }
    }
    LOG.info("Found " + numCachedBlocks + " of " + expectedBlocks + " blocks");
    LOG.info("Found " + numCachedReplicas + " of " + expectedReplicas
        + " replicas");
    assertEquals("Unexpected number of cached blocks", expectedBlocks,
        numCachedBlocks);
    assertEquals("Unexpected number of cached replicas", expectedReplicas,
        numCachedReplicas);
  }

  private static final long BLOCK_SIZE = 512;
  private static final int NUM_DATANODES = 4;

  // Most Linux installs will allow non-root users to lock 64KB.
  private static final long CACHE_CAPACITY = 64 * 1024 / NUM_DATANODES;

  private static HdfsConfiguration createCachingConf() {
    HdfsConfiguration conf = new HdfsConfiguration();
    conf.setLong(DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    conf.setLong(DFS_DATANODE_MAX_LOCKED_MEMORY_KEY, CACHE_CAPACITY);
    conf.setLong(DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setBoolean(DFS_NAMENODE_CACHING_ENABLED_KEY, true);
    conf.setLong(DFS_CACHEREPORT_INTERVAL_MSEC_KEY, 1000);
    conf.setLong(DFS_NAMENODE_PATH_BASED_CACHE_REFRESH_INTERVAL_MS, 1000);
    return conf;
  }

  @Test(timeout=120000)
  public void testWaitForCachedReplicas() throws Exception {
    HdfsConfiguration conf = createCachingConf();
    FileSystemTestHelper helper = new FileSystemTestHelper();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      final NameNode namenode = cluster.getNameNode();
      GenericTestUtils.waitFor(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
          return ((namenode.getNamesystem().getCacheCapacity() ==
              (NUM_DATANODES * CACHE_CAPACITY)) &&
                (namenode.getNamesystem().getCacheUsed() == 0));
        }
      }, 500, 60000);

      NamenodeProtocols nnRpc = namenode.getRpcServer();
      Path rootDir = helper.getDefaultWorkingDirectory(dfs);
      // Create the pool
      final String pool = "friendlyPool";
      nnRpc.addCachePool(new CachePoolInfo("friendlyPool"));
      // Create some test files
      final int numFiles = 2;
      final int numBlocksPerFile = 2;
      final List<String> paths = new ArrayList<String>(numFiles);
      for (int i=0; i<numFiles; i++) {
        Path p = new Path(rootDir, "testCachePaths-" + i);
        FileSystemTestHelper.createFile(dfs, p, numBlocksPerFile,
            (int)BLOCK_SIZE);
        paths.add(p.toUri().getPath());
      }
      // Check the initial statistics at the namenode
      waitForCachedBlocks(namenode, 0, 0, "testWaitForCachedReplicas:0");
      // Cache and check each path in sequence
      int expected = 0;
      for (int i=0; i<numFiles; i++) {
        CacheDirectiveInfo directive =
            new CacheDirectiveInfo.Builder().
              setPath(new Path(paths.get(i))).
              setPool(pool).
              build();
        nnRpc.addCacheDirective(directive);
        expected += numBlocksPerFile;
        waitForCachedBlocks(namenode, expected, expected,
            "testWaitForCachedReplicas:1");
      }
      // Uncache and check each path in sequence
      RemoteIterator<CacheDirectiveEntry> entries =
          nnRpc.listCacheDirectives(0, null);
      for (int i=0; i<numFiles; i++) {
        CacheDirectiveEntry entry = entries.next();
        nnRpc.removeCacheDirective(entry.getInfo().getId());
        expected -= numBlocksPerFile;
        waitForCachedBlocks(namenode, expected, expected,
            "testWaitForCachedReplicas:2");
      }
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout=120000)
  public void testAddingCacheDirectiveInfosWhenCachingIsDisabled()
      throws Exception {
    HdfsConfiguration conf = createCachingConf();
    conf.setBoolean(DFS_NAMENODE_CACHING_ENABLED_KEY, false);
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      // Create the pool
      String pool = "pool1";
      namenode.getRpcServer().addCachePool(new CachePoolInfo(pool));
      // Create some test files
      final int numFiles = 2;
      final int numBlocksPerFile = 2;
      final List<String> paths = new ArrayList<String>(numFiles);
      for (int i=0; i<numFiles; i++) {
        Path p = new Path("/testCachePaths-" + i);
        FileSystemTestHelper.createFile(dfs, p, numBlocksPerFile,
            (int)BLOCK_SIZE);
        paths.add(p.toUri().getPath());
      }
      // Check the initial statistics at the namenode
      waitForCachedBlocks(namenode, 0, 0,
          "testAddingCacheDirectiveInfosWhenCachingIsDisabled:0");
      // Cache and check each path in sequence
      int expected = 0;
      for (int i=0; i<numFiles; i++) {
        CacheDirectiveInfo directive =
            new CacheDirectiveInfo.Builder().
              setPath(new Path(paths.get(i))).
              setPool(pool).
              build();
        dfs.addCacheDirective(directive);
        waitForCachedBlocks(namenode, expected, 0,
          "testAddingCacheDirectiveInfosWhenCachingIsDisabled:1");
      }
      Thread.sleep(20000);
      waitForCachedBlocks(namenode, expected, 0,
          "testAddingCacheDirectiveInfosWhenCachingIsDisabled:2");
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout=120000)
  public void testWaitForCachedReplicasInDirectory() throws Exception {
    HdfsConfiguration conf = createCachingConf();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      // Create the pool
      final String pool = "friendlyPool";
      dfs.addCachePool(new CachePoolInfo(pool));
      // Create some test files
      final List<Path> paths = new LinkedList<Path>();
      paths.add(new Path("/foo/bar"));
      paths.add(new Path("/foo/baz"));
      paths.add(new Path("/foo2/bar2"));
      paths.add(new Path("/foo2/baz2"));
      dfs.mkdir(new Path("/foo"), FsPermission.getDirDefault());
      dfs.mkdir(new Path("/foo2"), FsPermission.getDirDefault());
      final int numBlocksPerFile = 2;
      for (Path path : paths) {
        FileSystemTestHelper.createFile(dfs, path, numBlocksPerFile,
            (int)BLOCK_SIZE, (short)3, false);
      }
      waitForCachedBlocks(namenode, 0, 0,
          "testWaitForCachedReplicasInDirectory:0");
      // cache entire directory
      long id = dfs.addCacheDirective(
            new CacheDirectiveInfo.Builder().
              setPath(new Path("/foo")).
              setReplication((short)2).
              setPool(pool).
              build());
      waitForCachedBlocks(namenode, 4, 8,
          "testWaitForCachedReplicasInDirectory:1");
      // Verify that listDirectives gives the stats we want.
      waitForCachedStats(dfs, 2,
          8 * BLOCK_SIZE, 8 * BLOCK_SIZE,
          new CacheDirectiveInfo.Builder().
              setPath(new Path("/foo")).
              build(),
          "testWaitForCachedReplicasInDirectory:2");
      long id2 = dfs.addCacheDirective(
            new CacheDirectiveInfo.Builder().
              setPath(new Path("/foo/bar")).
              setReplication((short)4).
              setPool(pool).
              build());
      // wait for an additional 2 cached replicas to come up
      waitForCachedBlocks(namenode, 4, 10,
          "testWaitForCachedReplicasInDirectory:3");
      // the directory directive's stats are unchanged
      waitForCachedStats(dfs, 2,
          8 * BLOCK_SIZE, 8 * BLOCK_SIZE,
          new CacheDirectiveInfo.Builder().
              setPath(new Path("/foo")).
              build(),
          "testWaitForCachedReplicasInDirectory:4");
      // verify /foo/bar's stats
      waitForCachedStats(dfs, 1,
          4 * numBlocksPerFile * BLOCK_SIZE,
          // only 3 because the file only has 3 replicas, not 4 as requested.
          3 * numBlocksPerFile * BLOCK_SIZE,
          new CacheDirectiveInfo.Builder().
              setPath(new Path("/foo/bar")).
              build(),
          "testWaitForCachedReplicasInDirectory:5");
      // remove and watch numCached go to 0
      dfs.removeCacheDirective(id);
      dfs.removeCacheDirective(id2);
      waitForCachedBlocks(namenode, 0, 0,
          "testWaitForCachedReplicasInDirectory:6");
    } finally {
      cluster.shutdown();
    }
  }

  /**
   * Tests stepping the cache replication factor up and down, checking the
   * number of cached replicas and blocks as well as the advertised locations.
   * @throws Exception
   */
  @Test(timeout=120000)
  public void testReplicationFactor() throws Exception {
    HdfsConfiguration conf = createCachingConf();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();

    try {
      cluster.waitActive();
      DistributedFileSystem dfs = cluster.getFileSystem();
      NameNode namenode = cluster.getNameNode();
      // Create the pool
      final String pool = "friendlyPool";
      dfs.addCachePool(new CachePoolInfo(pool));
      // Create some test files
      final List<Path> paths = new LinkedList<Path>();
      paths.add(new Path("/foo/bar"));
      paths.add(new Path("/foo/baz"));
      paths.add(new Path("/foo2/bar2"));
      paths.add(new Path("/foo2/baz2"));
      dfs.mkdir(new Path("/foo"), FsPermission.getDirDefault());
      dfs.mkdir(new Path("/foo2"), FsPermission.getDirDefault());
      final int numBlocksPerFile = 2;
      for (Path path : paths) {
        FileSystemTestHelper.createFile(dfs, path, numBlocksPerFile,
            (int)BLOCK_SIZE, (short)3, false);
      }
      waitForCachedBlocks(namenode, 0, 0, "testReplicationFactor:0");
      checkNumCachedReplicas(dfs, paths, 0, 0);
      // cache directory
      long id = dfs.addCacheDirective(
          new CacheDirectiveInfo.Builder().
            setPath(new Path("/foo")).
            setReplication((short)1).
            setPool(pool).
            build());
      waitForCachedBlocks(namenode, 4, 4, "testReplicationFactor:1");
      checkNumCachedReplicas(dfs, paths, 4, 4);
      // step up the replication factor
      for (int i=2; i<=3; i++) {
        dfs.modifyCacheDirective(
            new CacheDirectiveInfo.Builder().
            setId(id).
            setReplication((short)i).
            build());
        waitForCachedBlocks(namenode, 4, 4*i, "testReplicationFactor:2");
        checkNumCachedReplicas(dfs, paths, 4, 4*i);
      }
      // step it down
      for (int i=2; i>=1; i--) {
        dfs.modifyCacheDirective(
            new CacheDirectiveInfo.Builder().
            setId(id).
            setReplication((short)i).
            build());
        waitForCachedBlocks(namenode, 4, 4*i, "testReplicationFactor:3");
        checkNumCachedReplicas(dfs, paths, 4, 4*i);
      }
      // remove and watch numCached go to 0
      dfs.removeCacheDirective(id);
      waitForCachedBlocks(namenode, 0, 0, "testReplicationFactor:4");
      checkNumCachedReplicas(dfs, paths, 0, 0);
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout=60000)
  public void testListCachePoolPermissions() throws Exception {
    final UserGroupInformation myUser = UserGroupInformation
        .createRemoteUser("myuser");
    final DistributedFileSystem myDfs = 
        (DistributedFileSystem)DFSTestUtil.getFileSystemAs(myUser, conf);
    final String poolName = "poolparty";
    dfs.addCachePool(new CachePoolInfo(poolName)
        .setMode(new FsPermission((short)0700)));
    // Should only see partial info
    RemoteIterator<CachePoolEntry> it = myDfs.listCachePools();
    CachePoolInfo info = it.next().getInfo();
    assertFalse(it.hasNext());
    assertEquals("Expected pool name", poolName, info.getPoolName());
    assertNull("Unexpected owner name", info.getOwnerName());
    assertNull("Unexpected group name", info.getGroupName());
    assertNull("Unexpected mode", info.getMode());
    assertNull("Unexpected weight", info.getWeight());
    // Modify the pool so myuser is now the owner
    dfs.modifyCachePool(new CachePoolInfo(poolName)
        .setOwnerName(myUser.getShortUserName())
        .setWeight(99));
    // Should see full info
    it = myDfs.listCachePools();
    info = it.next().getInfo();
    assertFalse(it.hasNext());
    assertEquals("Expected pool name", poolName, info.getPoolName());
    assertEquals("Mismatched owner name", myUser.getShortUserName(),
        info.getOwnerName());
    assertNotNull("Expected group name", info.getGroupName());
    assertEquals("Mismatched mode", (short) 0700,
        info.getMode().toShort());
    assertEquals("Mismatched weight", 99, (int)info.getWeight());
  }

  @Test(timeout=60000)
  public void testExpiry() throws Exception {
    HdfsConfiguration conf = createCachingConf();
    MiniDFSCluster cluster =
      new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();
    try {
      DistributedFileSystem dfs = cluster.getFileSystem();
      String pool = "pool1";
      dfs.addCachePool(new CachePoolInfo(pool));
      Path p = new Path("/mypath");
      DFSTestUtil.createFile(dfs, p, BLOCK_SIZE*2, (short)2, 0x999);
      // Expire after test timeout
      Date start = new Date();
      Date expiry = DateUtils.addSeconds(start, 120);
      final long id = dfs.addCacheDirective(new CacheDirectiveInfo.Builder()
          .setPath(p)
          .setPool(pool)
          .setExpiration(CacheDirectiveInfo.Expiration.newAbsolute(expiry))
          .setReplication((short)2)
          .build());
      waitForCachedBlocks(cluster.getNameNode(), 2, 4, "testExpiry:1");
      // Change it to expire sooner
      dfs.modifyCacheDirective(new CacheDirectiveInfo.Builder().setId(id)
          .setExpiration(Expiration.newRelative(0)).build());
      waitForCachedBlocks(cluster.getNameNode(), 0, 0, "testExpiry:2");
      RemoteIterator<CacheDirectiveEntry> it = dfs.listCacheDirectives(null);
      CacheDirectiveEntry ent = it.next();
      assertFalse(it.hasNext());
      Date entryExpiry = new Date(ent.getInfo().getExpiration().getMillis());
      assertTrue("Directive should have expired",
          entryExpiry.before(new Date()));
      // Change it back to expire later
      dfs.modifyCacheDirective(new CacheDirectiveInfo.Builder().setId(id)
          .setExpiration(Expiration.newRelative(120000)).build());
      waitForCachedBlocks(cluster.getNameNode(), 2, 4, "testExpiry:3");
      it = dfs.listCacheDirectives(null);
      ent = it.next();
      assertFalse(it.hasNext());
      entryExpiry = new Date(ent.getInfo().getExpiration().getMillis());
      assertTrue("Directive should not have expired",
          entryExpiry.after(new Date()));
      // Verify that setting a negative TTL throws an error
      try {
        dfs.modifyCacheDirective(new CacheDirectiveInfo.Builder().setId(id)
            .setExpiration(Expiration.newRelative(-1)).build());
      } catch (InvalidRequestException e) {
        GenericTestUtils
            .assertExceptionContains("Cannot set a negative expiration", e);
      }
    } finally {
      cluster.shutdown();
    }
  }
}
