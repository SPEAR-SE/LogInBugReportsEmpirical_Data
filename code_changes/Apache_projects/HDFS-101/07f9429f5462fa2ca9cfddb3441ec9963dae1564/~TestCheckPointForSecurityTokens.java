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
package org.apache.hadoop.hdfs.server.namenode;

import junit.framework.Assert;
import java.io.*;
import java.net.URI;
import java.util.Collection;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.FSConstants.SafeModeAction;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.junit.Test;

/**
 * This class tests the creation and validation of a checkpoint.
 */
public class TestCheckPointForSecurityTokens {
  static final long seed = 0xDEADBEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  static final int numDatanodes = 3;
  short replication = 3;

  NameNode startNameNode( Configuration conf,
                          String imageDirs,
                          String editsDirs,
                          StartupOption start) throws IOException {
    conf.set(DFSConfigKeys.FS_DEFAULT_NAME_KEY, "hdfs://localhost:0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "0.0.0.0:0");  
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, imageDirs);
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY, editsDirs);
    String[] args = new String[]{start.getName()};
    NameNode nn = NameNode.createNameNode(args, conf);
    Assert.assertTrue(nn.isInSafeMode());
    return nn;
  }

  /**
   * Tests save namepsace.
   */
  @Test
  public void testSaveNamespace() throws IOException {
    MiniDFSCluster cluster = null;
    DistributedFileSystem fs = null;
    try {
      Configuration conf = new HdfsConfiguration();
      cluster = new MiniDFSCluster(conf, numDatanodes, true, null);
      cluster.waitActive();
      fs = (DistributedFileSystem)(cluster.getFileSystem());
      FSNamesystem namesystem = cluster.getNamesystem();
      namesystem.getDelegationTokenSecretManager().startThreads();
      String renewer = UserGroupInformation.getLoginUser().getUserName();
      Token<DelegationTokenIdentifier> token = namesystem
          .getDelegationToken(new Text(renewer)); 
      
      // Saving image without safe mode should fail
      DFSAdmin admin = new DFSAdmin(conf);
      String[] args = new String[]{"-saveNamespace"};

      // verify that the edits file is NOT empty
      Collection<URI> editsDirs = cluster.getNameEditsDirs();
      for(URI uri : editsDirs) {
        File ed = new File(uri.getPath());
        Assert.assertTrue(new File(ed, "current/edits").length() > Integer.SIZE/Byte.SIZE);
      }

      // Saving image in safe mode should succeed
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      try {
        admin.run(args);
      } catch(Exception e) {
        throw new IOException(e.getMessage());
      }
      // verify that the edits file is empty
      for(URI uri : editsDirs) {
        File ed = new File(uri.getPath());
        Assert.assertTrue(new File(ed, "current/edits").length() == Integer.SIZE/Byte.SIZE);
      }

      // restart cluster and verify file exists
      cluster.shutdown();
      cluster = null;

      cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
      cluster.waitActive();
      //Should be able to renew & cancel the delegation token after cluster restart
      try {
        cluster.getNamesystem().renewDelegationToken(token);
        cluster.getNamesystem().cancelDelegationToken(token);
      } catch (IOException e) {
        Assert.fail("Could not renew or cancel the token");
      }
    } finally {
      if(fs != null) fs.close();
      if(cluster!= null) cluster.shutdown();
    }
  }
}
