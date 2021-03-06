/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.scm.block;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.utils.MetadataKeyFilters;
import org.apache.hadoop.utils.MetadataStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_CONTAINER_METADATA_DIRS;
import static org.apache.hadoop.scm.ScmConfigKeys.OZONE_SCM_BLOCK_DELETION_MAX_RETRY;

/**
 * Tests for DeletedBlockLog.
 */
public class TestDeletedBlockLog {

  private static DeletedBlockLogImpl deletedBlockLog;
  private OzoneConfiguration conf;
  private File testDir;

  @Before
  public void setup() throws Exception {
    testDir = GenericTestUtils.getTestDir(
        TestDeletedBlockLog.class.getSimpleName());
    conf = new OzoneConfiguration();
    conf.setInt(OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 20);
    conf.set(OZONE_CONTAINER_METADATA_DIRS, testDir.getAbsolutePath());
    deletedBlockLog = new DeletedBlockLogImpl(conf);
  }

  @After
  public void tearDown() throws Exception {
    deletedBlockLog.close();
    FileUtils.deleteDirectory(testDir);
  }

  private Map<String, List<String>> generateData(int dataSize) {
    Map<String, List<String>> blockMap = new HashMap<>();
    Random random = new Random(1);
    for (int i = 0; i < dataSize; i++) {
      String containerName = "container-" + UUID.randomUUID().toString();
      List<String> blocks = new ArrayList<>();
      int blockSize = random.nextInt(30) + 1;
      for (int j = 0; j < blockSize; j++)  {
        blocks.add("block-" + UUID.randomUUID().toString());
      }
      blockMap.put(containerName, blocks);
    }
    return blockMap;
  }

  @Test
  public void testGetTransactions() throws Exception {
    List<DeletedBlocksTransaction> blocks =
        deletedBlockLog.getTransactions(30);
    Assert.assertEquals(0, blocks.size());

    // Creates 40 TX in the log.
    for (Map.Entry<String, List<String>> entry : generateData(40).entrySet()){
      deletedBlockLog.addTransaction(entry.getKey(), entry.getValue());
    }

    // Get first 30 TXs.
    blocks = deletedBlockLog.getTransactions(30);
    Assert.assertEquals(30, blocks.size());
    for (int i = 0; i < 30; i++) {
      Assert.assertEquals(i + 1, blocks.get(i).getTxID());
    }

    // Get another 30 TXs.
    // The log only 10 left, so this time it will only return 10 TXs.
    blocks = deletedBlockLog.getTransactions(30);
    Assert.assertEquals(10, blocks.size());
    for (int i = 30; i < 40; i++) {
      Assert.assertEquals(i + 1, blocks.get(i - 30).getTxID());
    }

    // Get another 50 TXs.
    // By now the position should have moved to the beginning,
    // this call will return all 40 TXs.
    blocks = deletedBlockLog.getTransactions(50);
    Assert.assertEquals(40, blocks.size());
    for (int i = 0; i < 40; i++) {
      Assert.assertEquals(i + 1, blocks.get(i).getTxID());
    }
    List<Long> txIDs = new ArrayList<>();
    for (DeletedBlocksTransaction block : blocks) {
      txIDs.add(block.getTxID());
    }
    deletedBlockLog.commitTransactions(txIDs);
  }

  @Test
  public void testIncrementCount() throws Exception {
    int maxRetry = conf.getInt(OZONE_SCM_BLOCK_DELETION_MAX_RETRY, 20);

    // Create 30 TXs in the log.
    for (Map.Entry<String, List<String>> entry : generateData(30).entrySet()){
      deletedBlockLog.addTransaction(entry.getKey(), entry.getValue());
    }

    // This will return all TXs, total num 30.
    List<DeletedBlocksTransaction> blocks =
        deletedBlockLog.getTransactions(40);
    List<Long> txIDs = blocks.stream().map(DeletedBlocksTransaction::getTxID)
        .collect(Collectors.toList());

    for (int i = 0; i < maxRetry; i++) {
      deletedBlockLog.incrementCount(txIDs);
    }

    // Increment another time so it exceed the maxRetry.
    // On this call, count will be set to -1 which means TX eventually fails.
    deletedBlockLog.incrementCount(txIDs);
    blocks = deletedBlockLog.getTransactions(40);
    for (DeletedBlocksTransaction block : blocks) {
      Assert.assertEquals(-1, block.getCount());
    }

    // If all TXs are failed, getTransactions call will always return nothing.
    blocks = deletedBlockLog.getTransactions(40);
    Assert.assertEquals(blocks.size(), 0);
  }

  @Test
  public void testCommitTransactions() throws Exception {
    for (Map.Entry<String, List<String>> entry : generateData(50).entrySet()){
      deletedBlockLog.addTransaction(entry.getKey(), entry.getValue());
    }
    List<DeletedBlocksTransaction> blocks =
        deletedBlockLog.getTransactions(20);
    List<Long> txIDs = new ArrayList<>();
    for (DeletedBlocksTransaction block : blocks) {
      txIDs.add(block.getTxID());
    }
    // Add an invalid txID.
    txIDs.add(70L);
    deletedBlockLog.commitTransactions(txIDs);
    blocks = deletedBlockLog.getTransactions(50);
    Assert.assertEquals(30, blocks.size());
  }

  @Test
  public void testRandomOperateTransactions() throws Exception {
    Random random = new Random();
    int added = 0, committed = 0;
    List<DeletedBlocksTransaction> blocks = new ArrayList<>();
    List<Long> txIDs = new ArrayList<>();
    byte[] latestTxid = DFSUtil.string2Bytes("#LATEST_TXID#");
    MetadataKeyFilters.MetadataKeyFilter avoidLatestTxid =
        (preKey, currentKey, nextKey) ->
            !Arrays.equals(latestTxid, currentKey);
    MetadataStore store = deletedBlockLog.getDeletedStore();
    // Randomly add/get/commit/increase transactions.
    for (int i = 0; i < 100; i++) {
      int state = random.nextInt(4);
      if (state == 0) {
        for (Map.Entry<String, List<String>> entry :
            generateData(10).entrySet()){
          deletedBlockLog.addTransaction(entry.getKey(), entry.getValue());
        }
        added += 10;
      } else if (state == 1) {
        blocks = deletedBlockLog.getTransactions(20);
        txIDs = new ArrayList<>();
        for (DeletedBlocksTransaction block : blocks) {
          txIDs.add(block.getTxID());
        }
        deletedBlockLog.incrementCount(txIDs);
      } else if (state == 2) {
        txIDs = new ArrayList<>();
        for (DeletedBlocksTransaction block : blocks) {
          txIDs.add(block.getTxID());
        }
        blocks = new ArrayList<>();
        committed += txIDs.size();
        deletedBlockLog.commitTransactions(txIDs);
      } else {
        // verify the number of added and committed.
        List<Map.Entry<byte[], byte[]>> result =
            store.getRangeKVs(null, added, avoidLatestTxid);
        Assert.assertEquals(added, result.size() + committed);
      }
    }
  }

  @Test
  public void testPersistence() throws Exception {
    for (Map.Entry<String, List<String>> entry : generateData(50).entrySet()){
      deletedBlockLog.addTransaction(entry.getKey(), entry.getValue());
    }
    // close db and reopen it again to make sure
    // transactions are stored persistently.
    deletedBlockLog.close();
    deletedBlockLog = new DeletedBlockLogImpl(conf);
    List<DeletedBlocksTransaction> blocks =
        deletedBlockLog.getTransactions(10);
    List<Long> txIDs = new ArrayList<>();
    for (DeletedBlocksTransaction block : blocks) {
      txIDs.add(block.getTxID());
    }
    deletedBlockLog.commitTransactions(txIDs);
    blocks = deletedBlockLog.getTransactions(10);
    Assert.assertEquals(10, blocks.size());
  }
}
