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

package org.apache.hadoop.mapred;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

@SuppressWarnings("deprecation")
public class TestLocalDistributedCacheManager {

  private static FileSystem mockfs; 
  
  public static class MockFileSystem extends FilterFileSystem {
    public MockFileSystem() {
      super(mockfs);
    }
  }
  
  private File localDir;
  
  private static void delete(File file) throws IOException {
    if (file.getAbsolutePath().length() < 5) { 
      throw new IllegalArgumentException(
          "Path [" + file + "] is too short, not deleting");
    }
    if (file.exists()) {  
      if (file.isDirectory()) {
        File[] children = file.listFiles();
        if (children != null) {
          for (File child : children) {
            delete(child);
          } 
        } 
      } 
      if (!file.delete()) {
        throw new RuntimeException(
          "Could not delete path [" + file + "]");
      }
    }
  }
  
  @Before
  public void setup() throws Exception {
    mockfs = mock(FileSystem.class);
    localDir = new File(System.getProperty("test.build.dir", "target/test-dir"),
        TestLocalDistributedCacheManager.class.getName());
    delete(localDir);
    localDir.mkdirs();
  }
  
  @After
  public void cleanup() throws Exception {
    delete(localDir);
  }
  
  @SuppressWarnings("rawtypes")
  @Test
  public void testDownload() throws Exception {
    JobConf conf = new JobConf();
    conf.setClass("fs.mock.impl", MockFileSystem.class, FileSystem.class);
    
    URI mockBase = new URI("mock://test-nn1/");
    when(mockfs.getUri()).thenReturn(mockBase);
    Path working = new Path("mock://test-nn1/user/me/");
    when(mockfs.getWorkingDirectory()).thenReturn(working);
    when(mockfs.resolvePath(any(Path.class))).thenAnswer(new Answer<Path>() {
      @Override
      public Path answer(InvocationOnMock args) throws Throwable {
        return (Path) args.getArguments()[0];
      }
    });

    final URI file = new URI("mock://test-nn1/user/me/file.txt#link");
    final Path filePath = new Path(file);
    File link = new File("link");
    
    when(mockfs.getFileStatus(any(Path.class))).thenAnswer(new Answer<FileStatus>() {
      @Override
      public FileStatus answer(InvocationOnMock args) throws Throwable {
        Path p = (Path)args.getArguments()[0];
        if("file.txt".equals(p.getName())) {
         return new FileStatus(201, false, 1, 500, 101, 101, 
             FsPermission.getDefault(), "me", "me", filePath);
        }  else {
          throw new FileNotFoundException(p+" not supported by mocking");
        }
      }
    });
    
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock args) throws Throwable {
        //Ignored boolean overwrite = (Boolean) args.getArguments()[0];
        Path src = (Path)args.getArguments()[1];
        Path dst = (Path)args.getArguments()[2];
        if("file.txt".equals(src.getName())) {
          File f = new File(dst.toUri().getPath());
          FileWriter writer = new FileWriter(f);
          try {
            writer.append("This is a test file\n");
          } finally {
            if(writer != null) writer.close();
          }
        } else {
          throw new FileNotFoundException(src+" not supported by mocking");
        }
        return null;
      }
    }).when(mockfs).copyToLocalFile(anyBoolean(), any(Path.class), any(Path.class));
    
    DistributedCache.addCacheFile(file, conf);
    conf.set(MRJobConfig.CACHE_FILE_TIMESTAMPS, "101");
    conf.set(MRJobConfig.CACHE_FILES_SIZES, "201");
    conf.set(MRJobConfig.CACHE_FILE_VISIBILITIES, "false");
    conf.set(MRConfig.LOCAL_DIR, localDir.getAbsolutePath());
    conf.set(MRJobConfig.CACHE_SYMLINK, "yes");
    LocalDistributedCacheManager manager = new LocalDistributedCacheManager();
    try {
      manager.setup(conf);
      assertTrue(link.exists());
    } finally {
      manager.close();
    }
    assertFalse(link.exists());
  }
  
  @SuppressWarnings("rawtypes")
  @Test
  public void testEmptyDownload() throws Exception {
    JobConf conf = new JobConf();
    conf.setClass("fs.mock.impl", MockFileSystem.class, FileSystem.class);
    
    URI mockBase = new URI("mock://test-nn1/");
    when(mockfs.getUri()).thenReturn(mockBase);
    Path working = new Path("mock://test-nn1/user/me/");
    when(mockfs.getWorkingDirectory()).thenReturn(working);
    when(mockfs.resolvePath(any(Path.class))).thenAnswer(new Answer<Path>() {
      @Override
      public Path answer(InvocationOnMock args) throws Throwable {
        return (Path) args.getArguments()[0];
      }
    });
    
    when(mockfs.getFileStatus(any(Path.class))).thenAnswer(new Answer<FileStatus>() {
      @Override
      public FileStatus answer(InvocationOnMock args) throws Throwable {
        Path p = (Path)args.getArguments()[0];
        throw new FileNotFoundException(p+" not supported by mocking");
      }
    });
    
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock args) throws Throwable {
        //Ignored boolean overwrite = (Boolean) args.getArguments()[0];
        Path src = (Path)args.getArguments()[1];
        throw new FileNotFoundException(src+" not supported by mocking");
      }
    }).when(mockfs).copyToLocalFile(anyBoolean(), any(Path.class), any(Path.class));
    
    conf.set(MRJobConfig.CACHE_FILES, "");
    conf.set(MRConfig.LOCAL_DIR, localDir.getAbsolutePath());
    conf.set(MRJobConfig.CACHE_SYMLINK, "yes");
    LocalDistributedCacheManager manager = new LocalDistributedCacheManager();
    try {
      manager.setup(conf);
    } finally {
      manager.close();
    }
  }
  
  
  @SuppressWarnings("rawtypes")
  @Test
  public void testDuplicateDownload() throws Exception {
    JobConf conf = new JobConf();
    conf.setClass("fs.mock.impl", MockFileSystem.class, FileSystem.class);
    
    URI mockBase = new URI("mock://test-nn1/");
    when(mockfs.getUri()).thenReturn(mockBase);
    Path working = new Path("mock://test-nn1/user/me/");
    when(mockfs.getWorkingDirectory()).thenReturn(working);
    when(mockfs.resolvePath(any(Path.class))).thenAnswer(new Answer<Path>() {
      @Override
      public Path answer(InvocationOnMock args) throws Throwable {
        return (Path) args.getArguments()[0];
      }
    });

    final URI file = new URI("mock://test-nn1/user/me/file.txt#link");
    final Path filePath = new Path(file);
    File link = new File("link");
    
    when(mockfs.getFileStatus(any(Path.class))).thenAnswer(new Answer<FileStatus>() {
      @Override
      public FileStatus answer(InvocationOnMock args) throws Throwable {
        Path p = (Path)args.getArguments()[0];
        if("file.txt".equals(p.getName())) {
         return new FileStatus(201, false, 1, 500, 101, 101, 
             FsPermission.getDefault(), "me", "me", filePath);
        }  else {
          throw new FileNotFoundException(p+" not supported by mocking");
        }
      }
    });
    
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock args) throws Throwable {
        //Ignored boolean overwrite = (Boolean) args.getArguments()[0];
        Path src = (Path)args.getArguments()[1];
        Path dst = (Path)args.getArguments()[2];
        if("file.txt".equals(src.getName())) {
          File f = new File(dst.toUri().getPath());
          FileWriter writer = new FileWriter(f);
          try {
            writer.append("This is a test file\n");
          } finally {
            if(writer != null) writer.close();
          }
        } else {
          throw new FileNotFoundException(src+" not supported by mocking");
        }
        return null;
      }
    }).when(mockfs).copyToLocalFile(anyBoolean(), any(Path.class), any(Path.class));
    
    DistributedCache.addCacheFile(file, conf);
    DistributedCache.addCacheFile(file, conf);
    conf.set(MRJobConfig.CACHE_FILE_TIMESTAMPS, "101,101");
    conf.set(MRJobConfig.CACHE_FILES_SIZES, "201,201");
    conf.set(MRJobConfig.CACHE_FILE_VISIBILITIES, "false,false");
    conf.set(MRConfig.LOCAL_DIR, localDir.getAbsolutePath());
    conf.set(MRJobConfig.CACHE_SYMLINK, "yes");
    LocalDistributedCacheManager manager = new LocalDistributedCacheManager();
    try {
      manager.setup(conf);
      assertTrue(link.exists());
    } finally {
      manager.close();
    }
    assertFalse(link.exists());
  }
}
