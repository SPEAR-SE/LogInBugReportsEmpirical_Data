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

package org.apache.hadoop.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.junit.Test;
import org.mockito.Mockito;

/**
 * Test cases for IOUtils.java
 */
public class TestIOUtils {
  private static final String TEST_FILE_NAME = "test_file";
  
  @Test
  public void testCopyBytesShouldCloseStreamsWhenCloseIsTrue() throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[1]);
    IOUtils.copyBytes(inputStream, outputStream, 1, true);
    Mockito.verify(inputStream, Mockito.atLeastOnce()).close();
    Mockito.verify(outputStream, Mockito.atLeastOnce()).close();
  }

  @Test
  public void testCopyBytesShouldCloseInputSteamWhenOutputStreamCloseThrowsException()
      throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[1]);
    Mockito.doThrow(new IOException()).when(outputStream).close();
    try{
      IOUtils.copyBytes(inputStream, outputStream, 1, true);
    } catch (IOException e) {
    }
    Mockito.verify(inputStream, Mockito.atLeastOnce()).close();
    Mockito.verify(outputStream, Mockito.atLeastOnce()).close();
  }

  @Test
  public void testCopyBytesShouldNotCloseStreamsWhenCloseIsFalse()
      throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[1]);
    IOUtils.copyBytes(inputStream, outputStream, 1, false);
    Mockito.verify(inputStream, Mockito.atMost(0)).close();
    Mockito.verify(outputStream, Mockito.atMost(0)).close();
  }
  
  @Test
  public void testCopyBytesWithCountShouldCloseStreamsWhenCloseIsTrue()
      throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[4096], 0, 1);
    IOUtils.copyBytes(inputStream, outputStream, (long) 1, true);
    Mockito.verify(inputStream, Mockito.atLeastOnce()).close();
    Mockito.verify(outputStream, Mockito.atLeastOnce()).close();
  }

  @Test
  public void testCopyBytesWithCountShouldNotCloseStreamsWhenCloseIsFalse()
      throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[4096], 0, 1);
    IOUtils.copyBytes(inputStream, outputStream, (long) 1, false);
    Mockito.verify(inputStream, Mockito.atMost(0)).close();
    Mockito.verify(outputStream, Mockito.atMost(0)).close();
  }

  @Test
  public void testCopyBytesWithCountShouldThrowOutTheStreamClosureExceptions()
      throws Exception {
    InputStream inputStream = Mockito.mock(InputStream.class);
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    Mockito.doReturn(-1).when(inputStream).read(new byte[4096], 0, 1);
    Mockito.doThrow(new IOException("Exception in closing the stream")).when(
        outputStream).close();
    try {
      IOUtils.copyBytes(inputStream, outputStream, (long) 1, true);
      fail("Should throw out the exception");
    } catch (IOException e) {
      assertEquals("Not throwing the expected exception.",
          "Exception in closing the stream", e.getMessage());
    }
    Mockito.verify(inputStream, Mockito.atLeastOnce()).close();
    Mockito.verify(outputStream, Mockito.atLeastOnce()).close();
  }
  
  @Test
  public void testWriteFully() throws IOException {
    final int INPUT_BUFFER_LEN = 10000;
    final int HALFWAY = 1 + (INPUT_BUFFER_LEN / 2);
    byte[] input = new byte[INPUT_BUFFER_LEN];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte)(i & 0xff);
    }
    byte[] output = new byte[input.length];
    
    try {
      RandomAccessFile raf = new RandomAccessFile(TEST_FILE_NAME, "rw");
      FileChannel fc = raf.getChannel();
      ByteBuffer buf = ByteBuffer.wrap(input);
      IOUtils.writeFully(fc, buf);
      raf.seek(0);
      raf.read(output);
      for (int i = 0; i < input.length; i++) {
        assertEquals(input[i], output[i]);
      }
      buf.rewind();
      IOUtils.writeFully(fc, buf, HALFWAY);
      for (int i = 0; i < HALFWAY; i++) {
        assertEquals(input[i], output[i]);
      }
      raf.seek(0);
      raf.read(output);
      for (int i = HALFWAY; i < input.length; i++) {
        assertEquals(input[i - HALFWAY], output[i]);
      }
    } finally {
      File f = new File(TEST_FILE_NAME);
      if (f.exists()) {
        f.delete();
      }
    }
  }
}
