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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.io.File;

import junit.framework.TestCase;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.log4j.Level;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.util.ToolRunner;

/**
 * A JUnit test for doing fsck
 */
public class TestFsck extends TestCase {
  static String runFsck(Configuration conf, String... path) throws Exception {
    PrintStream oldOut = System.out;
    ByteArrayOutputStream bStream = new ByteArrayOutputStream();
    PrintStream newOut = new PrintStream(bStream, true);
    System.setOut(newOut);
    ((Log4JLogger)PermissionChecker.LOG).getLogger().setLevel(Level.ALL);
    assertEquals(0, ToolRunner.run(new DFSck(conf), path));
    ((Log4JLogger)PermissionChecker.LOG).getLogger().setLevel(Level.INFO);
    System.setOut(oldOut);
    return bStream.toString();
  }

  /** do fsck */
  public void testFsck() throws Exception {
    DFSTestUtil util = new DFSTestUtil("TestFsck", 20, 3, 8*1024);
    MiniDFSCluster cluster = null;
    FileSystem fs = null;
    try {
      Configuration conf = new Configuration();
      cluster = new MiniDFSCluster(conf, 4, true, null);
      fs = cluster.getFileSystem();
      util.createFiles(fs, "/srcdat");
      String outStr = runFsck(conf, "/");
      assertTrue(-1 != outStr.indexOf("HEALTHY"));
      System.out.println(outStr);
      if (fs != null) {try{fs.close();} catch(Exception e){}}
      cluster.shutdown();
      
      // restart the cluster; bring up namenode but not the data nodes
      cluster = new MiniDFSCluster(conf, 0, false, null);
      outStr = runFsck(conf, "/");
      // expect the result is corrupt
      assertTrue(outStr.contains("CORRUPT"));
      System.out.println(outStr);
      
      // bring up data nodes & cleanup cluster
      cluster.startDataNodes(conf, 4, true, null, null);
      cluster.waitActive();
      cluster.waitClusterUp();
      fs = cluster.getFileSystem();
      util.cleanup(fs, "/srcdat");
    } finally {
      if (fs != null) {try{fs.close();} catch(Exception e){}}
      if (cluster != null) { cluster.shutdown(); }
    }
  }

  public void testFsckNonExistent() throws Exception {
    DFSTestUtil util = new DFSTestUtil("TestFsck", 20, 3, 8*1024);
    MiniDFSCluster cluster = null;
    FileSystem fs = null;
    try {
      Configuration conf = new Configuration();
      cluster = new MiniDFSCluster(conf, 4, true, null);
      fs = cluster.getFileSystem();
      util.createFiles(fs, "/srcdat");
      String outStr = runFsck(conf, "/non-existent");
      assertEquals(-1, outStr.indexOf("HEALTHY"));
      System.out.println(outStr);
      util.cleanup(fs, "/srcdat");
    } finally {
      if (fs != null) {try{fs.close();} catch(Exception e){}}
      if (cluster != null) { cluster.shutdown(); }
    }
  }

  public void testFsckMove() throws Exception {
    DFSTestUtil util = new DFSTestUtil("TestFsck", 5, 3, 8*1024);
    MiniDFSCluster cluster = null;
    FileSystem fs = null;
    try {
      Configuration conf = new Configuration();
      conf.set("dfs.blockreport.intervalMsec", Integer.toString(30));
      cluster = new MiniDFSCluster(conf, 4, true, null);
      String topDir = "/srcdat";
      fs = cluster.getFileSystem();
      cluster.waitActive();
      util.createFiles(fs, topDir);
      String outStr = runFsck(conf, "/");
      assertTrue(outStr.contains("HEALTHY"));
      
      // Corrupt a block by deleting it
      String[] fileNames = util.getFileNames(topDir);
      DFSClient dfsClient = new DFSClient(new InetSocketAddress("localhost",
                                          cluster.getNameNodePort()), conf);
      String block = dfsClient.namenode.
                      getBlockLocations(fileNames[0], 0, Long.MAX_VALUE).
                      get(0).getBlock().toString();
      File baseDir = new File(System.getProperty("test.build.data"),"dfs/data");
      for (int i=0; i<8; i++) {
        File blockFile = new File(baseDir, "data" +(i+1)+ "/current/" + block);
        if(blockFile.exists()) {
          assertTrue(blockFile.delete());
        }
      }

      //Sleep for a while until block reports are sent to discover corruption
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
      }
      
      // We excpect the filesystem to be corrupted
      outStr = runFsck(conf, "/");
      assertTrue(outStr.contains("CORRUPT"));
      
      // Fix the filesystem by moving corrupted files to lost+found
      outStr = runFsck(conf, "/", "-move");
      assertTrue(outStr.contains("CORRUPT"));
      
      // Check to make sure we have healthy filesystem
      outStr = runFsck(conf, "/");
      assertTrue(outStr.contains("HEALTHY")); 
      util.cleanup(fs, topDir);
      if (fs != null) {try{fs.close();} catch(Exception e){}}
      cluster.shutdown();
    } finally {
      if (fs != null) {try{fs.close();} catch(Exception e){}}
      if (cluster != null) { cluster.shutdown(); }
    }
  }
}
