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

import java.net.URL;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.junit.Test;

public class TestHAWebUI {

  /**
   * Tests that the web UI of the name node provides a link to browse the file
   * system only in active state
   * 
   */
  @Test
  public void testLinkToBrowseFilesystem() throws Exception {
    Configuration conf = new Configuration();

    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .nnTopology(MiniDFSNNTopology.simpleHATopology()).numDataNodes(0)
        .build();
    try {
      cluster.waitActive();

      cluster.transitionToActive(0);
      String pageContents = DFSTestUtil.urlGet(new URL("http://localhost:"
          + NameNode.getHttpAddress(cluster.getConfiguration(0)).getPort()
          + "/dfshealth.jsp"));
      assertTrue(pageContents.contains("Browse the filesystem"));

      cluster.transitionToStandby(0);
      pageContents = DFSTestUtil.urlGet(new URL("http://localhost:"
          + NameNode.getHttpAddress(cluster.getConfiguration(0)).getPort()
          + "/dfshealth.jsp"));
      assertFalse(pageContents.contains("Browse the filesystem"));

      cluster.transitionToActive(0);
      pageContents = DFSTestUtil.urlGet(new URL("http://localhost:"
          + NameNode.getHttpAddress(cluster.getConfiguration(0)).getPort()
          + "/dfshealth.jsp"));
      assertTrue(pageContents.contains("Browse the filesystem"));

    } finally {
      cluster.shutdown();
    }
  }
}
