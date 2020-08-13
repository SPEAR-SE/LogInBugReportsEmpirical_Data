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
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.zip.CRC32;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Command;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.dfs.FSConstants.StartupOption;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This tests data transfer protocol handling in the Datanode. It sends
 * various forms of wrong data and verifies that Datanode handles it well.
 * 
 * This test uses the following two file from src/test/.../dfs directory :
 *   1) hadoop-12-dfs-dir.tgz : contains the tar of 
 *   2) hadoop-12-dfs-dir.txt : checksums that are compared in this test.
 */
public class TestDFSUpgradeFromImage extends TestCase {
  
  private static final Log LOG = LogFactory.getLog(
                    "org.apache.hadoop.dfs.TestDFSUpgradeFromImage");
  
  int numDataNodes = 4;
  
  private static class ReferenceFileInfo {
    String path;
    long checksum;
  }
  
  LinkedList<ReferenceFileInfo> refList = new LinkedList<ReferenceFileInfo>();
  Iterator<ReferenceFileInfo> refIter;
  
  boolean printChecksum = false;
  
  protected void setUp() throws IOException {
    String tarFile = System.getProperty("test.cache.data") + 
                     "/hadoop-12-dfs-dir.tgz";
    String dataDir = System.getProperty("test.build.data");
    File dfsDir = new File(dataDir, "dfs");
    if ( dfsDir.exists() && !FileUtil.fullyDelete(dfsDir) ) {
      throw new IOException("Could not delete dfs directory '" + dfsDir + "'");
    }
    String cmd = "gzip -dc '" + FileUtil.makeShellPath(tarFile) + "' | (cd '" +
                 FileUtil.makeShellPath(dataDir) + "' ; tar -xf -)";
    LOG.info("Unpacking the tar file. Cmd : " + cmd);
    String[] shellCmd = { "bash", "-c", cmd };
    Command.execCommand(shellCmd);
    
    //Now read the reference info
    
    BufferedReader reader = new BufferedReader( 
                        new FileReader(System.getProperty("test.cache.data") +
                                       "/hadoop-12-dfs-dir.txt"));
    String line;
    while ( (line = reader.readLine()) != null ) {
      
      line = line.trim();
      if (line.length() <= 0 || line.startsWith("#")) {
        continue;
      }
      String[] arr = line.split("\\s+\t\\s+");
      if (arr.length < 1) {
        continue;
      }
      if (arr[0].equals("printChecksums")) {
        printChecksum = true;
        break;
      }
      if (arr.length < 2) {
        continue;
      }
      ReferenceFileInfo info = new ReferenceFileInfo();
      info.path = arr[0];
      info.checksum = Long.parseLong(arr[1]);
      refList.add(info);
    }
    reader.close();
  }

  private void verifyChecksum(String path, long checksum) throws IOException {
    if ( refIter == null ) {
      refIter = refList.iterator();
    }
    
    if ( printChecksum ) {
      LOG.info("CRC info for reference file : " + path + " \t " + checksum);
    } else {
      if ( !refIter.hasNext() ) {
        throw new IOException("Checking checksum for " + path +
                              "Not enough elements in the refList");
      }
      ReferenceFileInfo info = refIter.next();
      // The paths are expected to be listed in the same order 
      // as they are traversed here.
      assertEquals(info.path, path);
      assertEquals("Checking checksum for " + path, info.checksum, checksum);
    }
  }
  
  CRC32 overallChecksum = new CRC32();
  
  private void verifyDir(DFSClient client, String dir) 
                                           throws IOException {
    
    DFSFileInfo[] fileArr = client.listPaths(new UTF8(dir));
    TreeMap<String, Boolean> fileMap = new TreeMap<String, Boolean>();
    
    for(DFSFileInfo file : fileArr) {
      String path = file.getPath();
      fileMap.put(path, Boolean.valueOf(file.isDir()));
    }
    
    for(Iterator<String> it = fileMap.keySet().iterator(); it.hasNext();) {
      String path = it.next();
      boolean isDir = fileMap.get(path);
      
      overallChecksum.update(path.getBytes());
      
      if ( isDir ) {
        verifyDir(client, path);
      } else {
        // this is not a directory. Checksum the file data.
        CRC32 fileCRC = new CRC32();
        FSInputStream in = client.open(new UTF8(path));
        byte[] buf = new byte[4096];
        int nRead = 0;
        while ( (nRead = in.read(buf, 0, buf.length)) > 0 ) {
          fileCRC.update(buf, 0, nRead);
        }
        
        verifyChecksum(path, fileCRC.getValue());
      }
    }
  }
  
  private void verifyFileSystem(DFSClient client) throws IOException {
  
    verifyDir(client, "/");
    
    verifyChecksum("overallCRC", overallChecksum.getValue());
    
    if ( printChecksum ) {
      throw new IOException("Checksums are written to log as requested. " +
                            "Throwing this exception to force an error " +
                            "for this test.");
    }
  }
  
  public void testUpgradeFromImage() throws IOException {
    
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = new MiniDFSCluster(0, conf, numDataNodes, false,
                                                true, StartupOption.UPGRADE,
                                                null);
    cluster.waitActive();
    DFSClient dfsClient = new DFSClient(new InetSocketAddress("localhost", 
                                                  cluster.getNameNodePort()),
                                        conf);
    //Safemode will be off only after upgrade is complete. Wait for it.
    while ( dfsClient.setSafeMode(FSConstants.SafeModeAction.SAFEMODE_GET) ) {
      LOG.info("Waiting for SafeMode to be OFF.");
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {}
    }

    verifyFileSystem(dfsClient);
  }
}
