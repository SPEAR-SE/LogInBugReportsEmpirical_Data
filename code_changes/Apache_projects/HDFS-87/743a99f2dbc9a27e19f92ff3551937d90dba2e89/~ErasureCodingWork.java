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
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.util.StripedBlockUtil;
import org.apache.hadoop.net.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ErasureCodingWork extends BlockReconstructionWork {
  private final byte[] liveBlockIndicies;
  private final String blockPoolId;

  public ErasureCodingWork(String blockPoolId, BlockInfo block,
      BlockCollection bc,
      DatanodeDescriptor[] srcNodes,
      List<DatanodeDescriptor> containingNodes,
      List<DatanodeStorageInfo> liveReplicaStorages,
      int additionalReplRequired,
      int priority, byte[] liveBlockIndicies) {
    super(block, bc, srcNodes, containingNodes,
        liveReplicaStorages, additionalReplRequired, priority);
    this.blockPoolId = blockPoolId;
    this.liveBlockIndicies = liveBlockIndicies;
    BlockManager.LOG.debug("Creating an ErasureCodingWork to {} reconstruct ",
        block);
  }

  byte[] getLiveBlockIndicies() {
    return liveBlockIndicies;
  }

  @Override
  void chooseTargets(BlockPlacementPolicy blockplacement,
      BlockStoragePolicySuite storagePolicySuite,
      Set<Node> excludedNodes) {
    // TODO: new placement policy for EC considering multiple writers
    DatanodeStorageInfo[] chosenTargets = blockplacement.chooseTarget(
        getBc().getName(), getAdditionalReplRequired(), getSrcNodes()[0],
        getLiveReplicaStorages(), false, excludedNodes,
        getBlock().getNumBytes(),
        storagePolicySuite.getPolicy(getBc().getStoragePolicyID()));
    setTargets(chosenTargets);
  }

  /**
   * @return true if the current source nodes cover all the internal blocks.
   * I.e., we only need to have more racks.
   */
  private boolean hasAllInternalBlocks() {
    final BlockInfoStriped block = (BlockInfoStriped) getBlock();
    if (getSrcNodes().length < block.getRealTotalBlockNum()) {
      return false;
    }
    BitSet bitSet = new BitSet(block.getTotalBlockNum());
    for (byte index : liveBlockIndicies) {
      bitSet.set(index);
    }
    for (int i = 0; i < block.getRealDataBlockNum(); i++) {
      if (!bitSet.get(i)) {
        return false;
      }
    }
    for (int i = block.getDataBlockNum(); i < block.getTotalBlockNum(); i++) {
      if (!bitSet.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * We have all the internal blocks but not enough racks. Thus we do not need
   * to do decoding but only simply make an extra copy of an internal block. In
   * this scenario, use this method to choose the source datanode for simple
   * replication.
   * @return The index of the source datanode.
   */
  private int chooseSource4SimpleReplication() {
    Map<String, List<Integer>> map = new HashMap<>();
    for (int i = 0; i < getSrcNodes().length; i++) {
      final String rack = getSrcNodes()[i].getNetworkLocation();
      List<Integer> dnList = map.get(rack);
      if (dnList == null) {
        dnList = new ArrayList<>();
        map.put(rack, dnList);
      }
      dnList.add(i);
    }
    List<Integer> max = null;
    for (Map.Entry<String, List<Integer>> entry : map.entrySet()) {
      if (max == null || entry.getValue().size() > max.size()) {
        max = entry.getValue();
      }
    }
    assert max != null;
    return max.get(0);
  }

  @Override
  void addTaskToDatanode() {
    assert getTargets().length > 0;
    BlockInfoStriped stripedBlk = (BlockInfoStriped) getBlock();

    // if we already have all the internal blocks, but not enough racks,
    // we only need to replicate one internal block to a new rack
    if (hasAllInternalBlocks()) {
      int sourceIndex = chooseSource4SimpleReplication();
      final byte blockIndex = liveBlockIndicies[sourceIndex];
      final DatanodeDescriptor source = getSrcNodes()[sourceIndex];
      final long internBlkLen = StripedBlockUtil.getInternalBlockLength(
          stripedBlk.getNumBytes(), stripedBlk.getCellSize(),
          stripedBlk.getDataBlockNum(), blockIndex);
      final Block targetBlk = new Block(
          stripedBlk.getBlockId() + blockIndex, internBlkLen,
          stripedBlk.getGenerationStamp());
      source.addBlockToBeReplicated(targetBlk, getTargets());
      if (BlockManager.LOG.isDebugEnabled()) {
        BlockManager.LOG.debug("Add replication task from source {} to " +
            "targets {} for EC block {}", source, Arrays.toString(getTargets()),
            targetBlk);
      }
    } else {
      getTargets()[0].getDatanodeDescriptor().addBlockToBeErasureCoded(
          new ExtendedBlock(blockPoolId, stripedBlk),
          getSrcNodes(), getTargets(), getLiveBlockIndicies(),
          stripedBlk.getErasureCodingPolicy());
    }
  }
}
