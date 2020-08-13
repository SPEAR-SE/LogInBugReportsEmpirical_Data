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
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Test cases that the HA configuration is reasonably validated and
 * interpreted in various places. These should be proper unit tests
 * which don't start daemons.
 */
public class TestHAConfiguration {
  private static final String NSID = "ns1";
  private static String HOST_A = "1.2.3.1";
  private static String HOST_B = "1.2.3.2";

  private FSNamesystem fsn = Mockito.mock(FSNamesystem.class);
  private Configuration conf = new Configuration();

  @Test
  public void testCheckpointerValidityChecks() throws Exception {
    try {
      new StandbyCheckpointer(conf, fsn);
      fail("Bad config did not throw an error");
    } catch (IllegalArgumentException iae) {
      GenericTestUtils.assertExceptionContains(
          "Invalid URI for NameNode address", iae);
    }
  }
  
  @Test
  public void testGetOtherNNHttpAddress() {
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES, NSID);    
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICE_ID, NSID);
    conf.set(DFSUtil.addKeySuffixes(
        DFSConfigKeys.DFS_HA_NAMENODES_KEY, NSID),
        "nn1,nn2");    
    conf.set(DFSConfigKeys.DFS_HA_NAMENODE_ID_KEY, "nn1");
    conf.set(DFSUtil.addKeySuffixes(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY,
            NSID, "nn1"),
        HOST_A + ":12345");
    conf.set(DFSUtil.addKeySuffixes(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY,
            NSID, "nn2"),
        HOST_B + ":12345");
    NameNode.initializeGenericKeys(conf, NSID, "nn1");

    // Since we didn't configure the HTTP address, and the default is
    // 0.0.0.0, it should substitute the address from the RPC configuratoin
    // above.
    StandbyCheckpointer checkpointer = new StandbyCheckpointer(conf, fsn);
    assertEquals(HOST_B + ":" + DFSConfigKeys.DFS_NAMENODE_HTTP_PORT_DEFAULT,
        checkpointer.getActiveNNAddress());
  }
}
