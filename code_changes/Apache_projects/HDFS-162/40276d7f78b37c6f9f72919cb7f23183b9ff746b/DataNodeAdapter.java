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

import java.io.IOException;

import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.mockito.Mockito;

import com.google.common.base.Preconditions;

/**
 * WARNING!! This is TEST ONLY class: it never has to be used
 * for ANY development purposes.
 * 
 * This is a utility class to expose DataNode functionality for
 * unit and functional tests.
 */
public class DataNodeAdapter {
  /**
   * Fetch a copy of ReplicaInfo from a datanode by block id
   * @param dn datanode to retrieve a replicainfo object from
   * @param bpid Block pool Id
   * @param blkId id of the replica's block
   * @return copy of ReplicaInfo object @link{FSDataset#fetchReplicaInfo}
   */
  public static ReplicaInfo fetchReplicaInfo (final DataNode dn,
                                              final String bpid,
                                              final long blkId) {
    return ((FSDataset)dn.data).fetchReplicaInfo(bpid, blkId);
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

  public static long getPendingAsyncDeletions(DataNode dn) {
    FSDataset fsd = (FSDataset)dn.getFSDataset();
    return fsd.asyncDiskService.countPendingDeletions();
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
}
