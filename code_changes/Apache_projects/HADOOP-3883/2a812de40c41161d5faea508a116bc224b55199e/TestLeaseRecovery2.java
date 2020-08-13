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
package org.apache.hadoop.dfs;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Level;

public class TestLeaseRecovery2 extends junit.framework.TestCase {
  {
    ((Log4JLogger)DataNode.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)LeaseManager.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)FSNamesystem.LOG).getLogger().setLevel(Level.ALL);
  }

  static final int BLOCK_SIZE = 64;
  static final int FILE_SIZE = 1024;
  static final short REPLICATION_NUM = (short)3;
  static final Random RANDOM = new Random();
  static byte[] buffer = new byte[FILE_SIZE];

  static void checkMetaInfo(Block b, InterDatanodeProtocol idp
      ) throws IOException {
    TestInterDatanodeProtocol.checkMetaInfo(b, idp, null);
  }
  
  static int min(Integer... x) {
    int m = x[0];
    for(int i = 1; i < x.length; i++) {
      if (x[i] < m) {
        m = x[i];
      }
    }
    return m;
  }

  /**
   */
  public void testBlockSynchronization() throws Exception {
    final long softLease = 1000;
    final long hardLease = 60 * 60 *1000;
    final short repl = 3;
    Configuration conf = new Configuration();
    conf.setLong("dfs.block.size", BLOCK_SIZE);
    conf.setInt("io.bytes.per.checksum", 16);
    MiniDFSCluster cluster = null;
    byte[] actual = new byte[FILE_SIZE];

    try {
      cluster = new MiniDFSCluster(conf, 5, true, null);
      cluster.waitActive();

      //create a file
      DistributedFileSystem dfs = (DistributedFileSystem)cluster.getFileSystem();
      // create a random file name
      String filestr = "/foo" + RANDOM.nextInt();
      Path filepath = new Path(filestr);
      FSDataOutputStream stm = dfs.create(filepath, true,
                                 dfs.getConf().getInt("io.file.buffer.size", 4096),
                                 (short)repl, (long)BLOCK_SIZE);
      assertTrue(dfs.dfs.exists(filestr));

      // write random number of bytes into it.
      int size = RANDOM.nextInt(FILE_SIZE);
      stm.write(buffer, 0, size);

      // sync file
      stm.sync();

      // set the soft limit to be 1 second so that the
      // namenode triggers lease recovery on next attempt to write-for-open.
      cluster.setLeasePeriod(softLease, hardLease);

      // try to re-open the file before closing the previous handle. This
      // should fail but will trigger lease recovery.
      String oldClientName = dfs.dfs.clientName;
      dfs.dfs.clientName += "_1";
      while (true) {
        try {
          FSDataOutputStream newstm = dfs.create(filepath, false,
            dfs.getConf().getInt("io.file.buffer.size", 4096),
            (short)repl, (long)BLOCK_SIZE);
          assertTrue("Creation of an existing file should never succeed.", false);
        } catch (IOException e) {
          if (e.getMessage().contains("file exists")) {
            break;
          }
          e.printStackTrace();
        }
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
      }
      System.out.println("Lease for file " +  filepath + " is recovered. " +
                         "validating its contents now...");

      // revert back  client identity
      dfs.dfs.clientName = oldClientName;

      // verify that file-size matches
      assertTrue("File should be " + size + " bytes, but is actually " +
                 " found to be " + dfs.getFileStatus(filepath).getLen() +
                 " bytes",
                 dfs.getFileStatus(filepath).getLen() == size);

      // verify that there is enough data to read.
      System.out.println("File size is good. Now validating sizes from datanodes...");
      FSDataInputStream stmin = dfs.open(filepath);
      stmin.readFully(0, actual, 0, size);
      stmin.close();
    }
    finally {
      try {
        if (cluster != null) {cluster.shutdown();}
      } catch (Exception e) {
        // ignore
      }
    }
  }
}
