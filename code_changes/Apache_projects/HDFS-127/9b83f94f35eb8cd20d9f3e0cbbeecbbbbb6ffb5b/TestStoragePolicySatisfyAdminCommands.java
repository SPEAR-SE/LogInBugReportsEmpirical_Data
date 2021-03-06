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
package org.apache.hadoop.hdfs.tools;

import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test StoragePolicySatisfy admin commands.
 */
public class TestStoragePolicySatisfyAdminCommands {
  private static final short REPL = 1;
  private static final int SIZE = 128;

  private Configuration conf = null;
  private MiniDFSCluster cluster = null;
  private DistributedFileSystem dfs = null;

  @Before
  public void clusterSetUp() throws IOException, URISyntaxException {
    conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_STORAGE_POLICY_SATISFIER_ENABLED_KEY,
        true);
    StorageType[][] newtypes = new StorageType[][] {
        {StorageType.ARCHIVE, StorageType.DISK}};
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(REPL)
        .storageTypes(newtypes).build();
    cluster.waitActive();
    dfs = cluster.getFileSystem();
  }

  @After
  public void clusterShutdown() throws IOException{
    if(dfs != null) {
      dfs.close();
      dfs = null;
    }
    if(cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  @Test(timeout = 30000)
  public void testStoragePolicySatisfierCommand() throws Exception {
    final String file = "/testStoragePolicySatisfierCommand";
    DFSTestUtil.createFile(dfs, new Path(file), SIZE, REPL, 0);

    final StoragePolicyAdmin admin = new StoragePolicyAdmin(conf);
    DFSTestUtil.toolRun(admin, "-getStoragePolicy -path " + file, 0,
        "The storage policy of " + file + " is unspecified");

    DFSTestUtil.toolRun(admin,
        "-setStoragePolicy -path " + file + " -policy COLD", 0,
        "Set storage policy COLD on " + file.toString());

    DFSTestUtil.toolRun(admin, "-satisfyStoragePolicy -path " + file, 0,
        "Scheduled blocks to move based on the current storage policy on "
            + file.toString());

    DFSTestUtil.waitExpectedStorageType(file, StorageType.ARCHIVE, 1, 30000,
        dfs);
  }

  @Test(timeout = 30000)
  public void testIsSatisfierRunningCommand() throws Exception {
    final String file = "/testIsSatisfierRunningCommand";
    DFSTestUtil.createFile(dfs, new Path(file), SIZE, REPL, 0);
    final StoragePolicyAdmin admin = new StoragePolicyAdmin(conf);
    DFSTestUtil.toolRun(admin, "-isSatisfierRunning", 0, "yes");

    cluster.getNameNode().reconfigureProperty(
        DFSConfigKeys.DFS_STORAGE_POLICY_SATISFIER_ENABLED_KEY, "false");
    cluster.waitActive();

    DFSTestUtil.toolRun(admin, "-isSatisfierRunning", 0, "no");

    // Test with unnecessary args
    DFSTestUtil.toolRun(admin, "-isSatisfierRunning status", 1,
        "Can't understand arguments: ");
  }

  @Test(timeout = 90000)
  public void testSatisfyStoragePolicyCommandWithWaitOption()
      throws Exception {
    final String file = "/testSatisfyStoragePolicyCommandWithWaitOption";
    DFSTestUtil.createFile(dfs, new Path(file), SIZE, REPL, 0);

    final StoragePolicyAdmin admin = new StoragePolicyAdmin(conf);

    DFSTestUtil.toolRun(admin, "-setStoragePolicy -path " + file
        + " -policy COLD", 0, "Set storage policy COLD on " + file.toString());

    DFSTestUtil.toolRun(admin, "-satisfyStoragePolicy -w -path " + file, 0,
        "Waiting for satisfy the policy");

    DFSTestUtil.waitExpectedStorageType(file, StorageType.ARCHIVE, 1, 30000,
        dfs);
  }
}
