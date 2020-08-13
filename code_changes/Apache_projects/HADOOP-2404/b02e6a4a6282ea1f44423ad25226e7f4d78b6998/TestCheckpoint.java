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

import junit.framework.TestCase;
import java.io.*;
import java.util.Collection;
import java.util.Random;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.FSImage.NameNodeFile;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * This class tests the creation and validation of a checkpoint.
 */
public class TestCheckpoint extends TestCase {
  static final long seed = 0xDEADBEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  static final int numDatanodes = 3;
  short replication = 3;

  private void writeFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, (long)blockSize);
    byte[] buffer = new byte[fileSize];
    Random rand = new Random(seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }
  
  
  private void checkFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    assertTrue(fileSys.exists(name));
    int replication = fileSys.getFileStatus(name).getReplication();
    assertEquals("replication for " + name, repl, replication);
    //We should probably test for more of the file properties.    
  }
  
  private void cleanupFile(FileSystem fileSys, Path name)
    throws IOException {
    assertTrue(fileSys.exists(name));
    fileSys.delete(name);
    assertTrue(!fileSys.exists(name));
  }

  /**
   * put back the old namedir
   */
  private void resurrectNameDir(File namedir) 
    throws IOException {
    String parentdir = namedir.getParent();
    String name = namedir.getName();
    File oldname =  new File(parentdir, name + ".old");
    if (!oldname.renameTo(namedir)) {
      assertTrue(false);
    }
  }

  /**
   * remove one namedir
   */
  private void removeOneNameDir(File namedir) 
    throws IOException {
    String parentdir = namedir.getParent();
    String name = namedir.getName();
    File newname =  new File(parentdir, name + ".old");
    if (!namedir.renameTo(newname)) {
      assertTrue(false);
    }
  }

  /*
   * Verify that namenode does not startup if one namedir is bad.
   */
  private void testNamedirError(Configuration conf, Collection<File> namedirs) 
    throws IOException {
    System.out.println("Starting testNamedirError");
    MiniDFSCluster cluster = null;

    if (namedirs.size() <= 1) {
      return;
    }
    
    //
    // Remove one namedir & Restart cluster. This should fail.
    //
    File first = namedirs.iterator().next();
    removeOneNameDir(first);
    try {
      cluster = new MiniDFSCluster(conf, 0, false, null);
      cluster.shutdown();
      assertTrue(false);
    } catch (Throwable t) {
      // no nothing
    }
    resurrectNameDir(first); // put back namedir
  }

  /*
   * Simulate namenode crashing after rolling edit log.
   */
  private void testSecondaryNamenodeError1(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 1");
    Path file1 = new Path("checkpointxx.dat");
    MiniDFSCluster cluster = new MiniDFSCluster(conf, numDatanodes, 
                                                false, null);
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after rolling the
      // edit log.
      //
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.initializeErrorSimulationEvent(2);
      secondary.setErrorSimulation(0);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);      
      } catch (IOException e) {
      }
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 2");
    cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
    cluster.waitActive();
    // Also check that the edits file is empty here
    // and that temporary checkpoint files are gone.
    FSImage image = cluster.getNameNode().getFSImage();
    int nrDirs = image.getNumStorageDirs();
    for(int idx = 0; idx < nrDirs; idx++) {
      assertFalse(image.getImageFile(idx, NameNodeFile.IMAGE_NEW).exists());
      assertFalse(image.getEditNewFile(idx).exists());
      File edits = image.getEditFile(idx);
      assertTrue(edits.exists()); // edits should exist and be empty
      long editsLen = -1;
      RandomAccessFile eF = null;
      try {
        eF = new RandomAccessFile(edits, "r");
        editsLen = eF.length();
      } finally {
        if(eF != null)
          eF.close();
      }
      assertTrue(editsLen == Integer.SIZE/Byte.SIZE);
    }
    
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /*
   * Simulate a namenode crash after uploading new image
   */
  private void testSecondaryNamenodeError2(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 21");
    Path file1 = new Path("checkpointyy.dat");
    MiniDFSCluster cluster = new MiniDFSCluster(conf, numDatanodes, 
                                                false, null);
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after uploading the new fsimage.
      //
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.initializeErrorSimulationEvent(2);
      secondary.setErrorSimulation(1);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);      
      } catch (IOException e) {
      }
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 22");
    cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /*
   * Simulate a secondary namenode crash after rolling the edit log.
   */
  private void testSecondaryNamenodeError3(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 31");
    Path file1 = new Path("checkpointzz.dat");
    MiniDFSCluster cluster = new MiniDFSCluster(conf, numDatanodes, 
                                                false, null);
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after rolling the edit log.
      //
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.initializeErrorSimulationEvent(2);
      secondary.setErrorSimulation(0);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);      
      } catch (IOException e) {
      }
      secondary.shutdown(); // secondary namenode crash!

      // start new instance of secondary and verify that 
      // a new rollEditLog suceedes inspite of the fact that 
      // edits.new already exists.
      //
      secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();  // this should work correctly
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the twice-rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 32");
    cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /**
   * Tests checkpoint in DFS.
   */
  public void testCheckpoint() throws IOException {
    Path file1 = new Path("checkpoint.dat");
    Path file2 = new Path("checkpoint2.dat");
    Collection<File> namedirs = null;

    Configuration conf = new Configuration();
    conf.set("dfs.secondary.http.address", "0.0.0.0:0");
    replication = (short)conf.getInt("dfs.replication", 3);  
    MiniDFSCluster cluster = new MiniDFSCluster(conf, numDatanodes, true, null);
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();

    try {
      //
      // verify that 'format' really blew away all pre-existing files
      //
      assertTrue(!fileSys.exists(file1));
      assertTrue(!fileSys.exists(file2));
      namedirs = cluster.getNameDirs();

      //
      // Create file1
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);

      //
      // Take a checkpoint
      //
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file1 still exist.
    //
    cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    try {
      // check that file1 still exists
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);

      // create new file file2
      writeFile(fileSys, file2, replication);
      checkFile(fileSys, file2, replication);

      //
      // Take a checkpoint
      //
      SecondaryNameNode secondary = new SecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file2 exists and
    // file1 does not exist.
    //
    cluster = new MiniDFSCluster(conf, numDatanodes, false, null);
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    assertTrue(!fileSys.exists(file1));

    try {
      // verify that file2 exists
      checkFile(fileSys, file2, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    // file2 is left behind.

    testSecondaryNamenodeError1(conf);
    testSecondaryNamenodeError2(conf);
    testSecondaryNamenodeError3(conf);
    testNamedirError(conf, namedirs);
  }
}
