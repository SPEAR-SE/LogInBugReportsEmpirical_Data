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

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.namenode.NameNode;

import java.util.ArrayList;
import java.util.List;

import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState.COMPLETE;

/**
 * Represents the under construction feature of a Block.
 * This is usually the last block of a file opened for write or append.
 */
public class BlockUnderConstructionFeature {
  private BlockUCState blockUCState;
  private static final ReplicaUnderConstruction[] NO_REPLICAS =
      new ReplicaUnderConstruction[0];

  /**
   * Block replicas as assigned when the block was allocated.
   */
  private ReplicaUnderConstruction[] replicas = NO_REPLICAS;

  /**
   * Index of the primary data node doing the recovery. Useful for log
   * messages.
   */
  private int primaryNodeIndex = -1;

  /**
   * The new generation stamp, which this block will have
   * after the recovery succeeds. Also used as a recovery id to identify
   * the right recovery if any of the abandoned recoveries re-appear.
   */
  private long blockRecoveryId = 0;

  /**
   * The block source to use in the event of copy-on-write truncate.
   */
  private Block truncateBlock;

  public BlockUnderConstructionFeature(Block blk,
      BlockUCState state, DatanodeStorageInfo[] targets) {
    assert getBlockUCState() != COMPLETE :
        "BlockUnderConstructionFeature cannot be in COMPLETE state";
    this.blockUCState = state;
    setExpectedLocations(blk, targets);
  }

  /** Set expected locations */
  public void setExpectedLocations(Block block, DatanodeStorageInfo[] targets) {
    int numLocations = targets == null ? 0 : targets.length;
    this.replicas = new ReplicaUnderConstruction[numLocations];
    for(int i = 0; i < numLocations; i++) {
      replicas[i] = new ReplicaUnderConstruction(block, targets[i],
          ReplicaState.RBW);
    }
  }

  /**
   * Create array of expected replica locations
   * (as has been assigned by chooseTargets()).
   */
  public DatanodeStorageInfo[] getExpectedStorageLocations() {
    int numLocations = getNumExpectedLocations();
    DatanodeStorageInfo[] storages = new DatanodeStorageInfo[numLocations];
    for (int i = 0; i < numLocations; i++) {
      storages[i] = replicas[i].getExpectedStorageLocation();
    }
    return storages;
  }

  /** Get the number of expected locations */
  public int getNumExpectedLocations() {
    return replicas.length;
  }

  /**
   * Return the state of the block under construction.
   * @see BlockUCState
   */
  public BlockUCState getBlockUCState() {
    return blockUCState;
  }

  void setBlockUCState(BlockUCState s) {
    blockUCState = s;
  }

  public long getBlockRecoveryId() {
    return blockRecoveryId;
  }

  /** Get recover block */
  public Block getTruncateBlock() {
    return truncateBlock;
  }

  public void setTruncateBlock(Block recoveryBlock) {
    this.truncateBlock = recoveryBlock;
  }

  /**
   * Set {@link #blockUCState} to {@link BlockUCState#COMMITTED}.
   */
  void commit() {
    blockUCState = BlockUCState.COMMITTED;
  }

  List<ReplicaUnderConstruction> getStaleReplicas(long genStamp) {
    List<ReplicaUnderConstruction> staleReplicas = new ArrayList<>();
    // Remove replicas with wrong gen stamp. The replica list is unchanged.
    for (ReplicaUnderConstruction r : replicas) {
      if (genStamp != r.getGenerationStamp()) {
        staleReplicas.add(r);
      }
    }
    return staleReplicas;
  }

  /**
   * Initialize lease recovery for this block.
   * Find the first alive data-node starting from the previous primary and
   * make it primary.
   */
  public void initializeBlockRecovery(BlockInfo blockInfo, long recoveryId) {
    setBlockUCState(BlockUCState.UNDER_RECOVERY);
    blockRecoveryId = recoveryId;
    if (replicas.length == 0) {
      NameNode.blockStateChangeLog.warn("BLOCK*" +
          " BlockUnderConstructionFeature.initializeBlockRecovery:" +
          " No blocks found, lease removed.");
      // sets primary node index and return.
      primaryNodeIndex = -1;
      return;
    }
    boolean allLiveReplicasTriedAsPrimary = true;
    for (ReplicaUnderConstruction replica : replicas) {
      // Check if all replicas have been tried or not.
      if (replica.isAlive()) {
        allLiveReplicasTriedAsPrimary = allLiveReplicasTriedAsPrimary
            && replica.getChosenAsPrimary();
      }
    }
    if (allLiveReplicasTriedAsPrimary) {
      // Just set all the replicas to be chosen whether they are alive or not.
      for (ReplicaUnderConstruction replica : replicas) {
        replica.setChosenAsPrimary(false);
      }
    }
    long mostRecentLastUpdate = 0;
    ReplicaUnderConstruction primary = null;
    primaryNodeIndex = -1;
    for (int i = 0; i < replicas.length; i++) {
      // Skip alive replicas which have been chosen for recovery.
      if (!(replicas[i].isAlive() && !replicas[i].getChosenAsPrimary())) {
        continue;
      }
      final ReplicaUnderConstruction ruc = replicas[i];
      final long lastUpdate = ruc.getExpectedStorageLocation()
          .getDatanodeDescriptor().getLastUpdateMonotonic();
      if (lastUpdate > mostRecentLastUpdate) {
        primaryNodeIndex = i;
        primary = ruc;
        mostRecentLastUpdate = lastUpdate;
      }
    }
    if (primary != null) {
      primary.getExpectedStorageLocation().getDatanodeDescriptor()
          .addBlockToBeRecovered(blockInfo);
      primary.setChosenAsPrimary(true);
      NameNode.blockStateChangeLog.debug(
          "BLOCK* {} recovery started, primary={}", this, primary);
    }
  }

  /** Add the reported replica if it is not already in the replica list. */
  void addReplicaIfNotPresent(DatanodeStorageInfo storage,
      Block reportedBlock, ReplicaState rState) {
    if (replicas.length == 0) {
      replicas = new ReplicaUnderConstruction[1];
      replicas[0] = new ReplicaUnderConstruction(reportedBlock, storage,
          rState);
    } else {
      for (int i = 0; i < replicas.length; i++) {
        DatanodeStorageInfo expected =
            replicas[i].getExpectedStorageLocation();
        if (expected == storage) {
          replicas[i].setGenerationStamp(reportedBlock.getGenerationStamp());
          return;
        } else if (expected != null && expected.getDatanodeDescriptor() ==
            storage.getDatanodeDescriptor()) {
          // The Datanode reported that the block is on a different storage
          // than the one chosen by BlockPlacementPolicy. This can occur as
          // we allow Datanodes to choose the target storage. Update our
          // state by removing the stale entry and adding a new one.
          replicas[i] = new ReplicaUnderConstruction(reportedBlock, storage,
              rState);
          return;
        }
      }
      ReplicaUnderConstruction[] newReplicas =
          new ReplicaUnderConstruction[replicas.length + 1];
      System.arraycopy(replicas, 0, newReplicas, 0, replicas.length);
      newReplicas[newReplicas.length - 1] = new ReplicaUnderConstruction(
          reportedBlock, storage, rState);
      replicas = newReplicas;
    }
  }

  @Override
  public String toString() {
    final StringBuilder b = new StringBuilder(100);
    appendUCParts(b);
    return b.toString();
  }

  private void appendUCParts(StringBuilder sb) {
    sb.append("{UCState=").append(blockUCState)
      .append(", truncateBlock=").append(truncateBlock)
      .append(", primaryNodeIndex=").append(primaryNodeIndex)
      .append(", replicas=[");
    int i = 0;
    for (ReplicaUnderConstruction r : replicas) {
      r.appendStringTo(sb);
      if (++i < replicas.length) {
        sb.append(", ");
      }
    }
    sb.append("]}");
  }

  public void appendUCPartsConcise(StringBuilder sb) {
    sb.append("replicas=");
    int i = 0;
    for (ReplicaUnderConstruction r : replicas) {
      sb.append(r.getExpectedStorageLocation().getDatanodeDescriptor());
      if (++i < replicas.length) {
        sb.append(", ");
      }
    }
  }
}
