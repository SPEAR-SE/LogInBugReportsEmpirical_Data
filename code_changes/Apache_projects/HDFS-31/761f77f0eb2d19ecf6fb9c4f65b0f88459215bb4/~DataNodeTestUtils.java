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

package org.apache.hadoop.hdfs.server.datanode;

import java.io.File;
import java.io.IOException;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.mockito.Mockito;

import com.google.common.base.Preconditions;

/**
 * Utility class for accessing package-private DataNode information during tests.
 *
 */
public class DataNodeTestUtils {  
  public static DatanodeRegistration 
  getDNRegistrationForBP(DataNode dn, String bpid) throws IOException {
    return dn.getDNRegistrationForBP(bpid);
  }

  public static void setHeartbeatsDisabledForTests(DataNode dn,
      boolean heartbeatsDisabledForTests) {
    dn.setHeartbeatsDisabledForTests(heartbeatsDisabledForTests);
  }

  public static void triggerDeletionReport(DataNode dn) throws IOException {
    for (BPOfferService bpos : dn.getAllBpOs()) {
      bpos.triggerDeletionReportForTests();
    }
  }

  public static void triggerHeartbeat(DataNode dn) throws IOException {
    for (BPOfferService bpos : dn.getAllBpOs()) {
      bpos.triggerHeartbeatForTests();
    }
  }
  
  public static void triggerBlockReport(DataNode dn) throws IOException {
    for (BPOfferService bpos : dn.getAllBpOs()) {
      bpos.triggerBlockReportForTests();
    }
  }
  
  /**
   * Insert a Mockito spy object between the given DataNode and
   * the given NameNode. This can be used to delay or wait for
   * RPC calls on the datanode->NN path.
   */
  public static DatanodeProtocolClientSideTranslatorPB spyOnBposToNN(
      DataNode dn, NameNode nn) {
    String bpid = nn.getNamesystem().getBlockPoolId();
    
    BPOfferService bpos = null;
    for (BPOfferService thisBpos : dn.getAllBpOs()) {
      if (thisBpos.getBlockPoolId().equals(bpid)) {
        bpos = thisBpos;
        break;
      }
    }
    Preconditions.checkArgument(bpos != null,
        "No such bpid: %s", bpid);
    
    BPServiceActor bpsa = null;
    for (BPServiceActor thisBpsa : bpos.getBPServiceActors()) {
      if (thisBpsa.getNNSocketAddress().equals(nn.getServiceRpcAddress())) {
        bpsa = thisBpsa;
        break;
      }
    }
    Preconditions.checkArgument(bpsa != null,
      "No service actor to NN at %s", nn.getServiceRpcAddress());

    DatanodeProtocolClientSideTranslatorPB origNN = bpsa.getNameNodeProxy();
    DatanodeProtocolClientSideTranslatorPB spy = Mockito.spy(origNN);
    bpsa.setNameNode(spy);
    return spy;
  }

  /**
   * This method is used for testing. 
   * Examples are adding and deleting blocks directly.
   * The most common usage will be when the data node's storage is simulated.
   * 
   * @return the fsdataset that stores the blocks
   */
  public static FsDatasetSpi<?> getFSDataset(DataNode dn) {
    return dn.getFSDataset();
  }

  public static FSDataset getFsDatasetImpl(DataNode dn) {
    return (FSDataset)dn.getFSDataset();
  }

  public static File getFile(DataNode dn, String bpid, long bid) {
    return getFsDatasetImpl(dn).getFile(bpid, bid);
  }

  public static File getBlockFile(DataNode dn, String bpid, Block b
      ) throws IOException {
    return getFsDatasetImpl(dn).getBlockFile(bpid, b);
  }

  public static boolean unlinkBlock(DataNode dn, ExtendedBlock block, int numLinks
      ) throws IOException {
    return getFsDatasetImpl(dn).getReplicaInfo(block).unlinkBlock(numLinks);
  }

  public static long getPendingAsyncDeletions(DataNode dn) {
    return getFsDatasetImpl(dn).asyncDiskService.countPendingDeletions();
  }

  /**
   * Fetch a copy of ReplicaInfo from a datanode by block id
   * @param dn datanode to retrieve a replicainfo object from
   * @param bpid Block pool Id
   * @param blkId id of the replica's block
   * @return copy of ReplicaInfo object @link{FSDataset#fetchReplicaInfo}
   */
  public static ReplicaInfo fetchReplicaInfo(final DataNode dn,
      final String bpid, final long blkId) {
    return getFsDatasetImpl(dn).fetchReplicaInfo(bpid, blkId);
  }
}
