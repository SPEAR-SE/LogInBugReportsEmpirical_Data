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
package org.apache.hadoop.ha;

import static org.junit.Assert.*;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.ha.SshFenceByTcpPort.Args;
import org.apache.log4j.Level;
import org.junit.Assume;
import org.junit.Test;

public class TestSshFenceByTcpPort {

  static {
    ((Log4JLogger)SshFenceByTcpPort.LOG).getLogger().setLevel(Level.ALL);
  }
  
  private String TEST_FENCING_ARG = System.getProperty(
      "test.TestSshFenceByTcpPort.arg", "localhost");
  private final String TEST_KEYFILE = System.getProperty(
      "test.TestSshFenceByTcpPort.key");

  @Test(timeout=20000)
  public void testFence() throws BadFencingConfigurationException {
    Assume.assumeTrue(isConfigured());
    Configuration conf = new Configuration();
    conf.set(SshFenceByTcpPort.CONF_IDENTITIES_KEY, TEST_KEYFILE);
    FileSystem.setDefaultUri(conf, "localhost:8020");
    SshFenceByTcpPort fence = new SshFenceByTcpPort();
    fence.setConf(conf);
    assertTrue(fence.tryFence(TEST_FENCING_ARG));
  }

  /**
   * Test connecting to a host which definitely won't respond.
   * Make sure that it times out and returns false, but doesn't throw
   * any exception
   */
  @Test(timeout=20000)
  public void testConnectTimeout() throws BadFencingConfigurationException {
    Configuration conf = new Configuration();
    conf.setInt(SshFenceByTcpPort.CONF_CONNECT_TIMEOUT_KEY, 3000);
    SshFenceByTcpPort fence = new SshFenceByTcpPort();
    fence.setConf(conf);
    // Connect to Google's DNS server - not running ssh!
    assertFalse(fence.tryFence("8.8.8.8, 1234"));
  }
  
  @Test
  public void testArgsParsing() throws BadFencingConfigurationException {
    Args args = new SshFenceByTcpPort.Args("foo@bar.com:1234, 5678");
    assertEquals("foo", args.user);
    assertEquals("bar.com", args.host);
    assertEquals(1234, args.sshPort);
    assertEquals(5678, args.targetPort);

    args = new SshFenceByTcpPort.Args("foo@bar.com, 1234");
    assertEquals("foo", args.user);
    assertEquals("bar.com", args.host);
    assertEquals(22, args.sshPort);
    assertEquals(1234, args.targetPort);
    
    args = new SshFenceByTcpPort.Args("bar.com, 1234");
    assertEquals(System.getProperty("user.name"), args.user);
    assertEquals("bar.com", args.host);
    assertEquals(22, args.sshPort);
    assertEquals(1234, args.targetPort);
    
    args = new SshFenceByTcpPort.Args("bar.com:1234, 12345");
    assertEquals(System.getProperty("user.name"), args.user);
    assertEquals("bar.com", args.host);
    assertEquals(1234, args.sshPort);
    assertEquals(12345, args.targetPort);
    
    args = new SshFenceByTcpPort.Args("bar, 8020");
    assertEquals(8020, args.targetPort);    
  }
  
  @Test
  public void testBadArgsParsing() throws BadFencingConfigurationException {
    assertBadArgs(null);
    assertBadArgs("");
    assertBadArgs("bar.com:");
    assertBadArgs("bar.com:x");
    assertBadArgs("foo.com, x");
    assertBadArgs("foo.com,");
    assertBadArgs("foo.com, ");
  }
  
  private void assertBadArgs(String argStr) {
    try {
      new Args(argStr);
      fail("Did not fail on bad args: " + argStr);
    } catch (BadFencingConfigurationException e) {
      // expected
    }
  }

  private boolean isConfigured() {
    return (TEST_FENCING_ARG != null && !TEST_FENCING_ARG.isEmpty()) &&
      (TEST_KEYFILE != null && !TEST_KEYFILE.isEmpty());
  }
}
