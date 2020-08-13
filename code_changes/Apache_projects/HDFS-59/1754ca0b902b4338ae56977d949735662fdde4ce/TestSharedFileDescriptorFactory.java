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
package org.apache.hadoop.io.nativeio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;

public class TestSharedFileDescriptorFactory {
  static final Log LOG = LogFactory.getLog(TestSharedFileDescriptorFactory.class);

  private static final File TEST_BASE =
      new File(System.getProperty("test.build.data", "/tmp"));

  @Test(timeout=10000)
  public void testReadAndWrite() throws Exception {
    Assume.assumeTrue(NativeIO.isAvailable());
    Assume.assumeTrue(SystemUtils.IS_OS_UNIX);
    File path = new File(TEST_BASE, "testReadAndWrite");
    path.mkdirs();
    SharedFileDescriptorFactory factory =
        new SharedFileDescriptorFactory("woot_", path.getAbsolutePath());
    FileInputStream inStream =
        factory.createDescriptor("testReadAndWrite", 4096);
    FileOutputStream outStream = new FileOutputStream(inStream.getFD());
    outStream.write(101);
    inStream.getChannel().position(0);
    Assert.assertEquals(101, inStream.read());
    inStream.close();
    outStream.close();
    FileUtil.fullyDelete(path);
  }

  static private void createTempFile(String path) throws Exception {
    FileOutputStream fos = new FileOutputStream(path);
    fos.write(101);
    fos.close();
  }
  
  @Test(timeout=10000)
  public void testCleanupRemainders() throws Exception {
    Assume.assumeTrue(NativeIO.isAvailable());
    Assume.assumeTrue(SystemUtils.IS_OS_UNIX);
    File path = new File(TEST_BASE, "testCleanupRemainders");
    path.mkdirs();
    String remainder1 = path.getAbsolutePath() + 
        Path.SEPARATOR + "woot2_remainder1";
    String remainder2 = path.getAbsolutePath() +
        Path.SEPARATOR + "woot2_remainder2";
    createTempFile(remainder1);
    createTempFile(remainder2);
    new SharedFileDescriptorFactory("woot2_", path.getAbsolutePath());
    // creating the SharedFileDescriptorFactory should have removed 
    // the remainders
    Assert.assertFalse(new File(remainder1).exists());
    Assert.assertFalse(new File(remainder2).exists());
    FileUtil.fullyDelete(path);
  }
}