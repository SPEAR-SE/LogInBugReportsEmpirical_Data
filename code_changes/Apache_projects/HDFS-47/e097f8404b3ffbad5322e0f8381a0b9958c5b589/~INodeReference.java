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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;

import com.google.common.base.Preconditions;

/**
 * An anonymous reference to an inode.
 *
 * This class and its subclasses are used to support multiple access paths.
 * A file/directory may have multiple access paths when it is stored in some
 * snapshots and it is renamed/moved to other locations.
 * 
 * For example,
 * (1) Support we have /abc/foo, say the inode of foo is inode(id=1000,name=foo)
 * (2) create snapshot s0 for /abc
 * (3) mv /abc/foo /xyz/bar, i.e. inode(id=1000,name=...) is renamed from "foo"
 *     to "bar" and its parent becomes /xyz.
 * 
 * Then, /xyz/bar and /abc/.snapshot/s0/foo are two different access paths to
 * the same inode, inode(id=1000,name=bar).
 *
 * With references, we have the following
 * - /abc has a child ref(id=1001,name=foo).
 * - /xyz has a child ref(id=1002) 
 * - Both ref(id=1001,name=foo) and ref(id=1002) point to another reference,
 *   ref(id=1003,count=2).
 * - Finally, ref(id=1003,count=2) points to inode(id=1000,name=bar).
 * 
 * Note 1: For a reference without name, e.g. ref(id=1002), it uses the name
 *         of the referred inode.
 * Note 2: getParent() always returns the parent in the current state, e.g.
 *         inode(id=1000,name=bar).getParent() returns /xyz but not /abc.
 */
public abstract class INodeReference extends INode {
  /**
   * Try to remove the given reference and then return the reference count.
   * If the given inode is not a reference, return -1;
   */
  public static int tryRemoveReference(INode inode) {
    if (!inode.isReference()) {
      return -1;
    }
    return removeReference(inode.asReference());
  }

  /**
   * Remove the given reference and then return the reference count.
   * If the referred inode is not a WithCount, return -1;
   */
  private static int removeReference(INodeReference ref) {
    final INode referred = ref.getReferredINode();
    if (!(referred instanceof WithCount)) {
      return -1;
    }
    
    WithCount wc = (WithCount) referred;
    wc.removeReference(ref);
    return wc.getReferenceCount();
  }

  private INode referred;
  
  public INodeReference(INode parent, INode referred) {
    super(parent);
    this.referred = referred;
  }

  public final INode getReferredINode() {
    return referred;
  }

  public final void setReferredINode(INode referred) {
    this.referred = referred;
  }
  
  @Override
  public final boolean isReference() {
    return true;
  }
  
  @Override
  public final INodeReference asReference() {
    return this;
  }

  @Override
  public final boolean isFile() {
    return referred.isFile();
  }
  
  @Override
  public final INodeFile asFile() {
    return referred.asFile();
  }
  
  @Override
  public final boolean isDirectory() {
    return referred.isDirectory();
  }
  
  @Override
  public final INodeDirectory asDirectory() {
    return referred.asDirectory();
  }
  
  @Override
  public final boolean isSymlink() {
    return referred.isSymlink();
  }
  
  @Override
  public final INodeSymlink asSymlink() {
    return referred.asSymlink();
  }

  @Override
  public byte[] getLocalNameBytes() {
    return referred.getLocalNameBytes();
  }

  @Override
  public void setLocalName(byte[] name) {
    referred.setLocalName(name);
  }

  @Override
  public final long getId() {
    return referred.getId();
  }
  
  @Override
  public final PermissionStatus getPermissionStatus(Snapshot snapshot) {
    return referred.getPermissionStatus(snapshot);
  }
  
  @Override
  public final String getUserName(Snapshot snapshot) {
    return referred.getUserName(snapshot);
  }
  
  @Override
  final void setUser(String user) {
    referred.setUser(user);
  }
  
  @Override
  public final String getGroupName(Snapshot snapshot) {
    return referred.getGroupName(snapshot);
  }
  
  @Override
  final void setGroup(String group) {
    referred.setGroup(group);
  }
  
  @Override
  public final FsPermission getFsPermission(Snapshot snapshot) {
    return referred.getFsPermission(snapshot);
  }
  
  @Override
  void setPermission(FsPermission permission) {
    referred.setPermission(permission);
  }
  
  @Override
  public final long getModificationTime(Snapshot snapshot) {
    return referred.getModificationTime(snapshot);
  }
  
  @Override
  public final INode updateModificationTime(long mtime, Snapshot latest)
      throws QuotaExceededException {
    return referred.updateModificationTime(mtime, latest);
  }
  
  @Override
  public final void setModificationTime(long modificationTime) {
    referred.setModificationTime(modificationTime);
  }
  
  @Override
  public final long getAccessTime(Snapshot snapshot) {
    return referred.getAccessTime(snapshot);
  }
  
  @Override
  public final void setAccessTime(long accessTime) {
    referred.setAccessTime(accessTime);
  }

  @Override
  final INode recordModification(Snapshot latest) throws QuotaExceededException {
    referred.recordModification(latest);
    // reference is never replaced 
    return this;
  }

  @Override
  public Quota.Counts cleanSubtree(Snapshot snapshot, Snapshot prior,
      BlocksMapUpdateInfo collectedBlocks, final List<INode> removedINodes)
      throws QuotaExceededException {
    return referred.cleanSubtree(snapshot, prior, collectedBlocks,
        removedINodes);
  }

  @Override
  public final void destroyAndCollectBlocks(
      BlocksMapUpdateInfo collectedBlocks, final List<INode> removedINodes) {
    if (removeReference(this) <= 0) {
      referred.destroyAndCollectBlocks(collectedBlocks, removedINodes);
    }
  }

  @Override
  public final Content.CountsMap computeContentSummary(Content.CountsMap countsMap) {
    return referred.computeContentSummary(countsMap);
  }

  @Override
  public final Content.Counts computeContentSummary(Content.Counts counts) {
    return referred.computeContentSummary(counts);
  }

  @Override
  public Quota.Counts computeQuotaUsage(Quota.Counts counts, boolean useCache,
      int lastSnapshotId) {
    return referred.computeQuotaUsage(counts, useCache, lastSnapshotId);
  }
  
  @Override
  public final INode getSnapshotINode(Snapshot snapshot) {
    return referred.getSnapshotINode(snapshot);
  }

  @Override
  public final long getNsQuota() {
    return referred.getNsQuota();
  }

  @Override
  public final long getDsQuota() {
    return referred.getDsQuota();
  }
  
  @Override
  public final void clear() {
    super.clear();
    referred = null;
  }

  @Override
  public void dumpTreeRecursively(PrintWriter out, StringBuilder prefix,
      final Snapshot snapshot) {
    super.dumpTreeRecursively(out, prefix, snapshot);
    if (this instanceof DstReference) {
      out.print(", dstSnapshotId=" + ((DstReference) this).dstSnapshotId);
    }
    if (this instanceof WithCount) {
      out.print(", count=" + ((WithCount)this).getReferenceCount());
    }
    out.println();
    
    final StringBuilder b = new StringBuilder();
    for(int i = 0; i < prefix.length(); i++) {
      b.append(' ');
    }
    b.append("->");
    getReferredINode().dumpTreeRecursively(out, b, snapshot);
  }
  
  public int getDstSnapshotId() {
    return Snapshot.INVALID_ID;
  }
  
  /** An anonymous reference with reference count. */
  public static class WithCount extends INodeReference {
    
    private final List<WithName> withNameList = new ArrayList<WithName>();
    
    public WithCount(INodeReference parent, INode referred) {
      super(parent, referred);
      Preconditions.checkArgument(!referred.isReference());
      referred.setParentReference(this);
    }
    
    public int getReferenceCount() {
      int count = withNameList.size();
      if (getParentReference() != null) {
        count++;
      }
      return count;
    }

    /** Increment and then return the reference count. */
    public void addReference(INodeReference ref) {
      if (ref instanceof WithName) {
        withNameList.add((WithName) ref);
      } else if (ref instanceof DstReference) {
        setParentReference(ref);
      }
    }

    /** Decrement and then return the reference count. */
    public void removeReference(INodeReference ref) {
      if (ref instanceof WithName) {
        Iterator<INodeReference.WithName> iter = withNameList.iterator();
        while (iter.hasNext()) {
          if (iter.next() == ref) {
            iter.remove();
            break;
          }
        }
      } else if (ref == getParentReference()) {
        setParent(null);
      }
    }
    
    @Override
    public final void addSpaceConsumed(long nsDelta, long dsDelta,
        boolean verify, int snapshotId) throws QuotaExceededException {
      INodeReference parentRef = getParentReference();
      if (parentRef != null) {
        parentRef.addSpaceConsumed(nsDelta, dsDelta, verify, snapshotId);
      }
      addSpaceConsumedToRenameSrc(nsDelta, dsDelta, verify, snapshotId);
    }
    
    @Override
    public final void addSpaceConsumedToRenameSrc(long nsDelta, long dsDelta,
        boolean verify, int snapshotId) throws QuotaExceededException {
      if (snapshotId != Snapshot.INVALID_ID) {
        // sort withNameList based on the lastSnapshotId
        Collections.sort(withNameList, new Comparator<WithName>() {
          @Override
          public int compare(WithName w1, WithName w2) {
            return w1.lastSnapshotId - w2.lastSnapshotId;
          }
        });
        for (INodeReference.WithName withName : withNameList) {
          if (withName.getLastSnapshotId() >= snapshotId) {
            withName.addSpaceConsumed(nsDelta, dsDelta, verify, snapshotId);
            break;
          }
        }
      }
    }
  }
  
  /** A reference with a fixed name. */
  public static class WithName extends INodeReference {

    private final byte[] name;

    /**
     * The id of the last snapshot in the src tree when this WithName node was 
     * generated. When calculating the quota usage of the referred node, only 
     * the files/dirs existing when this snapshot was taken will be counted for 
     * this WithName node and propagated along its ancestor path.
     */
    private final int lastSnapshotId;
    
    public WithName(INodeDirectory parent, WithCount referred, byte[] name,
        int lastSnapshotId) {
      super(parent, referred);
      this.name = name;
      this.lastSnapshotId = lastSnapshotId;
      referred.addReference(this);
    }

    @Override
    public final byte[] getLocalNameBytes() {
      return name;
    }

    @Override
    public final void setLocalName(byte[] name) {
      throw new UnsupportedOperationException("Cannot set name: " + getClass()
          + " is immutable.");
    }
    
    public int getLastSnapshotId() {
      return lastSnapshotId;
    }
    
    @Override
    public final Quota.Counts computeQuotaUsage(Quota.Counts counts,
        boolean useCache, int lastSnapshotId) {
      Preconditions.checkState(lastSnapshotId == Snapshot.INVALID_ID
          || this.lastSnapshotId <= lastSnapshotId);
      final INode referred = this.getReferredINode().asReference()
          .getReferredINode();
      // we cannot use cache for the referred node since its cached quota may
      // have already been updated by changes in the current tree
      return referred.computeQuotaUsage(counts, false, this.lastSnapshotId);
    }
    
    @Override
    public Quota.Counts cleanSubtree(Snapshot snapshot, Snapshot prior,
        BlocksMapUpdateInfo collectedBlocks, List<INode> removedINodes)
        throws QuotaExceededException {
      Quota.Counts counts = getReferredINode().cleanSubtree(snapshot, prior,
          collectedBlocks, removedINodes);
      INodeReference ref = getReferredINode().getParentReference();
      if (ref != null) {
        ref.addSpaceConsumed(-counts.get(Quota.NAMESPACE),
            -counts.get(Quota.DISKSPACE), true, Snapshot.INVALID_ID);
      }
      return counts;
    }
  }
  
  public static class DstReference extends INodeReference {
    /**
     * Record the latest snapshot of the dst subtree before the rename. For
     * later operations on the moved/renamed files/directories, if the latest
     * snapshot is after this dstSnapshot, changes will be recorded to the
     * latest snapshot. Otherwise changes will be recorded to the snapshot
     * belonging to the src of the rename.
     * 
     * {@link Snapshot#INVALID_ID} means no dstSnapshot (e.g., src of the
     * first-time rename).
     */
    private final int dstSnapshotId;
    
    @Override
    public final int getDstSnapshotId() {
      return dstSnapshotId;
    }
    
    public DstReference(INodeDirectory parent, WithCount referred,
        final int dstSnapshotId) {
      super(parent, referred);
      this.dstSnapshotId = dstSnapshotId;
      referred.addReference(this);
    }
    
    @Override
    public Quota.Counts cleanSubtree(Snapshot snapshot, Snapshot prior,
        BlocksMapUpdateInfo collectedBlocks, List<INode> removedINodes)
        throws QuotaExceededException {
      Quota.Counts counts = getReferredINode().cleanSubtree(snapshot, prior,
          collectedBlocks, removedINodes);
      if (snapshot != null) {
        // also need to update quota usage along the corresponding WithName node
        WithCount wc = (WithCount) getReferredINode();
        wc.addSpaceConsumedToRenameSrc(-counts.get(Quota.NAMESPACE),
            -counts.get(Quota.DISKSPACE), true, snapshot.getId());
      }
      return counts;
    }
  }
}