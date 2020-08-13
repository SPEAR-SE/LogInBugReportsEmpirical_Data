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
package org.apache.hadoop.hdfs;

import java.io.File;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.qjournal.MiniJournalCluster;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.junit.Assert;
import org.junit.Test;

/**
 * This class tests rollback for rolling upgrade.
 */
public class TestRollingUpgradeRollback {

  private static final int NUM_JOURNAL_NODES = 3;
  private static final String JOURNAL_ID = "myjournal";

  private static boolean fileExists(List<File> files) {
    for (File file : files) {
      if (file.exists()) {
        return true;
      }
    }
    return false;
  }

  private void checkNNStorage(NNStorage storage, long imageTxId,
      long trashEndTxId) {
    List<File> finalizedEdits = storage.getFiles(
        NNStorage.NameNodeDirType.EDITS,
        NNStorage.getFinalizedEditsFileName(1, imageTxId));
    Assert.assertTrue(fileExists(finalizedEdits));
    List<File> inprogressEdits = storage.getFiles(
        NNStorage.NameNodeDirType.EDITS,
        NNStorage.getInProgressEditsFileName(imageTxId + 1));
    // For rollback case we will have an inprogress file for future transactions
    Assert.assertTrue(fileExists(inprogressEdits));
    if (trashEndTxId > 0) {
      List<File> trashedEdits = storage.getFiles(
          NNStorage.NameNodeDirType.EDITS,
          NNStorage.getFinalizedEditsFileName(imageTxId + 1, trashEndTxId)
              + ".trash");
      Assert.assertTrue(fileExists(trashedEdits));
    }
    String imageFileName = trashEndTxId > 0 ? NNStorage
        .getImageFileName(imageTxId) : NNStorage
        .getRollbackImageFileName(imageTxId);
    List<File> imageFiles = storage.getFiles(
        NNStorage.NameNodeDirType.IMAGE, imageFileName);
    Assert.assertTrue(fileExists(imageFiles));
  }

  private void checkJNStorage(File dir, long discardStartTxId,
      long discardEndTxId) {
    File finalizedEdits = new File(dir, NNStorage.getFinalizedEditsFileName(1,
        discardStartTxId - 1));
    Assert.assertTrue(finalizedEdits.exists());
    File trashEdits = new File(dir, NNStorage.getFinalizedEditsFileName(
        discardStartTxId, discardEndTxId) + ".trash");
    Assert.assertTrue(trashEdits.exists());
  }

  @Test
  public void testRollbackCommand() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;
    final Path foo = new Path("/foo");
    final Path bar = new Path("/bar");
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();

      final DistributedFileSystem dfs = cluster.getFileSystem();
      final DFSAdmin dfsadmin = new DFSAdmin(conf);
      dfs.mkdirs(foo);

      // start rolling upgrade
      Assert.assertEquals(0,
          dfsadmin.run(new String[] { "-rollingUpgrade", "start" }));
      // create new directory
      dfs.mkdirs(bar);

      // check NNStorage
      NNStorage storage = cluster.getNamesystem().getFSImage().getStorage();
      checkNNStorage(storage, 3, -1); // (startSegment, mkdir, endSegment) 
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }

    NameNode nn = null;
    try {
      nn = NameNode.createNameNode(new String[] { "-rollingUpgrade",
          "rollback" }, conf);
      // make sure /foo is still there, but /bar is not
      INode fooNode = nn.getNamesystem().getFSDirectory()
          .getINode4Write(foo.toString());
      Assert.assertNotNull(fooNode);
      INode barNode = nn.getNamesystem().getFSDirectory()
          .getINode4Write(bar.toString());
      Assert.assertNull(barNode);

      // check the details of NNStorage
      NNStorage storage = nn.getNamesystem().getFSImage().getStorage();
      // (startSegment, upgrade marker, mkdir, endSegment)
      checkNNStorage(storage, 3, 7);
    } finally {
      if (nn != null) {
        nn.stop();
        nn.join();
      }
    }
  }

  @Test
  public void testRollbackWithQJM() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    MiniJournalCluster mjc = null;
    MiniDFSCluster cluster = null;
    final Path foo = new Path("/foo");
    final Path bar = new Path("/bar");

    try {
      mjc = new MiniJournalCluster.Builder(conf).numJournalNodes(
          NUM_JOURNAL_NODES).build();
      conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY, mjc
          .getQuorumJournalURI(JOURNAL_ID).toString());
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();

      DistributedFileSystem dfs = cluster.getFileSystem();
      final DFSAdmin dfsadmin = new DFSAdmin(conf);
      dfs.mkdirs(foo);

      // start rolling upgrade
      Assert.assertEquals(0,
          dfsadmin.run(new String[] { "-rollingUpgrade", "start" }));
      // create new directory
      dfs.mkdirs(bar);
      dfs.close();

      // rollback
      cluster.restartNameNode("-rollingUpgrade", "rollback");
      // make sure /foo is still there, but /bar is not
      dfs = cluster.getFileSystem();
      Assert.assertTrue(dfs.exists(foo));
      Assert.assertFalse(dfs.exists(bar));

      // check storage in JNs
      for (int i = 0; i < NUM_JOURNAL_NODES; i++) {
        File dir = mjc.getCurrentDir(0, JOURNAL_ID);
        // segments:(startSegment, mkdir, endSegment), (startSegment, upgrade
        // marker, mkdir, endSegment)
        checkJNStorage(dir, 4, 7);
      }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
      if (mjc != null) {
        mjc.shutdown();
      }
    }
  }

  /**
   * TODO: Test rollback scenarios where StandbyNameNode does checkpoints during
   * rolling upgrade.
   */
  
  // TODO: rollback could not succeed in all JN
}