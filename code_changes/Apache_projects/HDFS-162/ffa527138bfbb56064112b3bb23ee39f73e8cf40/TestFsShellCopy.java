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

package org.apache.hadoop.fs;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestFsShellCopy {  
  static Configuration conf;
  static FsShell shell; 
  static LocalFileSystem lfs;
  static Path testRootDir, srcPath, dstPath;
  
  @BeforeClass
  public static void setup() throws Exception {
    conf = new Configuration();
    shell = new FsShell(conf);
    lfs = FileSystem.getLocal(conf);
    testRootDir = lfs.makeQualified(new Path(
        System.getProperty("test.build.data","test/build/data"),
        "testShellCopy"));
    
    lfs.mkdirs(testRootDir);    
    srcPath = new Path(testRootDir, "srcFile");
    dstPath = new Path(testRootDir, "dstFile");
  }
  
  @Before
  public void prepFiles() throws Exception {
    lfs.setVerifyChecksum(true);
    lfs.setWriteChecksum(true);
    
    lfs.delete(srcPath, true);
    lfs.delete(dstPath, true);
    FSDataOutputStream out = lfs.create(srcPath);
    out.writeChars("hi");
    out.close();
    assertTrue(lfs.exists(lfs.getChecksumFile(srcPath)));
  }

  @Test
  public void testCopyNoCrc() throws Exception {
    shellRun(0, "-get", srcPath.toString(), dstPath.toString());
    checkPath(dstPath, false);
  }

  @Test
  public void testCopyCrc() throws Exception {
    shellRun(0, "-get", "-crc", srcPath.toString(), dstPath.toString());
    checkPath(dstPath, true);
  }

  
  @Test
  public void testCorruptedCopyCrc() throws Exception {
    FSDataOutputStream out = lfs.getRawFileSystem().create(srcPath);
    out.writeChars("bang");
    out.close();
    shellRun(1, "-get", srcPath.toString(), dstPath.toString());
  }

  @Test
  public void testCorruptedCopyIgnoreCrc() throws Exception {
    shellRun(0, "-get", "-ignoreCrc", srcPath.toString(), dstPath.toString());
    checkPath(dstPath, false);
  }

  private void checkPath(Path p, boolean expectChecksum) throws IOException {
    assertTrue(lfs.exists(p));
    boolean hasChecksum = lfs.exists(lfs.getChecksumFile(p));
    assertEquals(expectChecksum, hasChecksum);
  }

  private void shellRun(int n, String ... args) throws Exception {
    assertEquals(n, shell.run(args));
  }
  
  @Test
  public void testCopyFileFromLocal() throws Exception {
    Path testRoot = new Path(testRootDir, "testPutFile");
    lfs.delete(testRoot, true);
    lfs.mkdirs(testRoot);

    Path targetDir = new Path(testRoot, "target");    
    Path filePath = new Path(testRoot, new Path("srcFile"));
    lfs.create(filePath).close();
    checkPut(filePath, targetDir);
  }

  @Test
  public void testCopyDirFromLocal() throws Exception {
    Path testRoot = new Path(testRootDir, "testPutDir");
    lfs.delete(testRoot, true);
    lfs.mkdirs(testRoot);
    
    Path targetDir = new Path(testRoot, "target");    
    Path dirPath = new Path(testRoot, new Path("srcDir"));
    lfs.mkdirs(dirPath);
    lfs.create(new Path(dirPath, "srcFile")).close();
    checkPut(dirPath, targetDir);
  }
  
  private void checkPut(Path srcPath, Path targetDir)
  throws Exception {
    lfs.delete(targetDir, true);
    lfs.mkdirs(targetDir);    
    lfs.setWorkingDirectory(targetDir);

    final Path dstPath = new Path("path");
    final Path childPath = new Path(dstPath, "childPath");
    lfs.setWorkingDirectory(targetDir);
    
    // copy to new file, then again
    prepPut(dstPath, false, false);
    checkPut(0, srcPath, dstPath);
    if (lfs.isFile(srcPath)) {
      checkPut(1, srcPath, dstPath);
    } else { // directory works because it copies into the dir
      // clear contents so the check won't think there are extra paths
      prepPut(dstPath, true, true);
      checkPut(0, srcPath, dstPath);
    }

    // copy to non-existent subdir
    prepPut(childPath, false, false);
    checkPut(1, srcPath, dstPath);

    // copy into dir, then with another name
    prepPut(dstPath, true, true);
    checkPut(0, srcPath, dstPath);
    prepPut(childPath, true, true);
    checkPut(0, srcPath, childPath);

    // try to put to pwd with existing dir
    prepPut(targetDir, true, true);
    checkPut(0, srcPath, null);
    prepPut(targetDir, true, true);
    checkPut(0, srcPath, new Path("."));

    // try to put to pwd with non-existent cwd
    prepPut(dstPath, false, true);
    lfs.setWorkingDirectory(dstPath);
    checkPut(1, srcPath, null);
    prepPut(dstPath, false, true);
    checkPut(1, srcPath, new Path("."));
  }

  private void prepPut(Path dst, boolean create,
                       boolean isDir) throws IOException {
    lfs.delete(dst, true);
    assertFalse(lfs.exists(dst));
    if (create) {
      if (isDir) {
        lfs.mkdirs(dst);
        assertTrue(lfs.isDirectory(dst));
      } else {
        lfs.mkdirs(new Path(dst.getName()));
        lfs.create(dst).close();
        assertTrue(lfs.isFile(dst));
      }
    }
  }
  
  private void checkPut(int exitCode, Path src, Path dest) throws Exception {
    String argv[] = null;
    if (dest != null) {
      argv = new String[]{ "-put", src.toString(), pathAsString(dest) };
    } else {
      argv = new String[]{ "-put", src.toString() };
      dest = new Path(Path.CUR_DIR);
    }
    
    Path target;
    if (lfs.exists(dest)) {
      if (lfs.isDirectory(dest)) {
        target = new Path(pathAsString(dest), src.getName());
      } else {
        target = dest;
      }
    } else {
      target = new Path(lfs.getWorkingDirectory(), dest);
    }
    boolean targetExists = lfs.exists(target);
    Path parent = lfs.makeQualified(target).getParent();
    
    System.out.println("COPY src["+src.getName()+"] -> ["+dest+"] as ["+target+"]");
    String lsArgv[] = new String[]{ "-ls", "-R", pathAsString(parent) };
    shell.run(lsArgv);
    
    int gotExit = shell.run(argv);
    
    System.out.println("copy exit:"+gotExit);
    lsArgv = new String[]{ "-ls", "-R", pathAsString(parent) };
    shell.run(lsArgv);
    
    if (exitCode == 0) {
      assertTrue(lfs.exists(target));
      assertTrue(lfs.isFile(src) == lfs.isFile(target));
      assertEquals(1, lfs.listStatus(lfs.makeQualified(target).getParent()).length);      
    } else {
      assertEquals(targetExists, lfs.exists(target));
    }
    assertEquals(exitCode, gotExit);
  }
  
  // path handles "." rather oddly
  private String pathAsString(Path p) {
    String s = (p == null) ? Path.CUR_DIR : p.toString();
    return s.isEmpty() ? Path.CUR_DIR : s;
  }
}
