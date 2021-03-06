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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.server.namenode.FSImage;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;


public class TestStandbyCheckpoints {
  private static final int NUM_DIRS_IN_LOG = 200000;
  private MiniDFSCluster cluster;
  private NameNode nn0, nn1;
  private FileSystem fs;

  @Before
  public void setupCluster() throws Exception {
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_CHECK_PERIOD_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_TXNS_KEY, 5);
    conf.setInt(DFSConfigKeys.DFS_HA_TAILEDITS_PERIOD_KEY, 1);

    MiniDFSNNTopology topology = new MiniDFSNNTopology()
      .addNameservice(new MiniDFSNNTopology.NSConf("ns1")
        .addNN(new MiniDFSNNTopology.NNConf("nn1").setHttpPort(10001))
        .addNN(new MiniDFSNNTopology.NNConf("nn2").setHttpPort(10002)));
    
    cluster = new MiniDFSCluster.Builder(conf)
      .nnTopology(topology)
      .numDataNodes(0)
      .build();
    cluster.waitActive();
    
    nn0 = cluster.getNameNode(0);
    nn1 = cluster.getNameNode(1);
    fs = HATestUtil.configureFailoverFs(cluster, conf);

    cluster.transitionToActive(0);
  }
  
  @After
  public void shutdownCluster() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testSBNCheckpoints() throws Exception {
    doEdits(0, 10);
    
    HATestUtil.waitForStandbyToCatchUp(nn0, nn1);
    // Once the standby catches up, it should notice that it needs to
    // do a checkpoint and save one to its local directories.
    HATestUtil.waitForCheckpoint(cluster, 1, ImmutableList.of(0, 12));
    
    // It should also upload it back to the active.
    HATestUtil.waitForCheckpoint(cluster, 0, ImmutableList.of(0, 12));
  }

  /**
   * Test for the case when both of the NNs in the cluster are
   * in the standby state, and thus are both creating checkpoints
   * and uploading them to each other.
   * In this circumstance, they should receive the error from the
   * other node indicating that the other node already has a
   * checkpoint for the given txid, but this should not cause
   * an abort, etc.
   */
  @Test
  public void testBothNodesInStandbyState() throws Exception {
    doEdits(0, 10);
    
    cluster.transitionToStandby(0);

    // Transitioning to standby closed the edit log on the active,
    // so the standby will catch up. Then, both will be in standby mode
    // with enough uncheckpointed txns to cause a checkpoint, and they
    // will each try to take a checkpoint and upload to each other.
    HATestUtil.waitForCheckpoint(cluster, 1, ImmutableList.of(0, 12));
    HATestUtil.waitForCheckpoint(cluster, 0, ImmutableList.of(0, 12));
    
    assertEquals(12, nn0.getNamesystem().getFSImage()
        .getMostRecentCheckpointTxId());
    assertEquals(12, nn1.getNamesystem().getFSImage()
        .getMostRecentCheckpointTxId());
    
    List<File> dirs = Lists.newArrayList();
    dirs.addAll(FSImageTestUtil.getNameNodeCurrentDirs(cluster, 0));
    dirs.addAll(FSImageTestUtil.getNameNodeCurrentDirs(cluster, 1));
    // TODO: this failed once because it caught a ckpt file -- maybe
    // this is possible if one of the NNs is really fast and the other is slow?
    // need to loop this to suss out the race.
    FSImageTestUtil.assertParallelFilesAreIdentical(dirs, ImmutableSet.<String>of());
  }
  
  /**
   * Test for the case when the SBN is configured to checkpoint based
   * on a time period, but no transactions are happening on the
   * active. Thus, it would want to save a second checkpoint at the
   * same txid, which is a no-op. This test makes sure this doesn't
   * cause any problem.
   */
  @Test
  public void testCheckpointWhenNoNewTransactionsHappened()
      throws Exception {
    // Checkpoint as fast as we can, in a tight loop.
    cluster.getConfiguration(1).setInt(
        DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_PERIOD_KEY, 0);
    cluster.restartNameNode(1);
    nn1 = cluster.getNameNode(1);
 
    FSImage spyImage1 = NameNodeAdapter.spyOnFsImage(nn1);
    
    // We shouldn't save any checkpoints at txid=0
    Thread.sleep(1000);
    Mockito.verify(spyImage1, Mockito.never())
      .saveNamespace((FSNamesystem) Mockito.anyObject());
 
    // Roll the primary and wait for the standby to catch up
    HATestUtil.waitForStandbyToCatchUp(nn0, nn1);
    Thread.sleep(2000);
    
    // We should make exactly one checkpoint at this new txid. 
    Mockito.verify(spyImage1, Mockito.times(1))
      .saveNamespace((FSNamesystem) Mockito.anyObject());       
  }
  
  /**
   * Test cancellation of ongoing checkpoints when failover happens
   * mid-checkpoint. 
   */
  @Test
  public void testCheckpointCancellation() throws Exception {
    cluster.transitionToStandby(0);
    
    // Create an edit log in the shared edits dir with a lot
    // of mkdirs operations. This is solely so that the image is
    // large enough to take a non-trivial amount of time to load.
    // (only ~15MB)
    URI sharedUri = cluster.getSharedEditsDir(0, 1);
    File sharedDir = new File(sharedUri.getPath(), "current");
    File tmpDir = new File(MiniDFSCluster.getBaseDirectory(),
        "testCheckpointCancellation-tmp");
    FSImageTestUtil.createAbortedLogWithMkdirs(tmpDir, NUM_DIRS_IN_LOG,
        3);
    String fname = NNStorage.getInProgressEditsFileName(3); 
    new File(tmpDir, fname).renameTo(new File(sharedDir, fname));

    // Checkpoint as fast as we can, in a tight loop.
    cluster.getConfiguration(1).setInt(
        DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_PERIOD_KEY, 0);
    cluster.restartNameNode(1);
    nn1 = cluster.getNameNode(1);

    cluster.transitionToActive(0);    
    
    for (int i = 0; i < 10; i++) {
      
      doEdits(i*10, i*10 + 10);
      cluster.transitionToStandby(0);
      cluster.transitionToActive(1);
      cluster.transitionToStandby(1);
      cluster.transitionToActive(0);
    }
    
    assertTrue(StandbyCheckpointer.getCanceledCount() > 0);
  }

  private void doEdits(int start, int stop) throws IOException {
    for (int i = start; i < stop; i++) {
      Path p = new Path("/test" + i);
      fs.mkdirs(p);
    }
  }

}
