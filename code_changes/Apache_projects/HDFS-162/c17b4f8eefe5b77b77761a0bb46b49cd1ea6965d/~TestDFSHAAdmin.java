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

package org.apache.hadoop.hdfs.tools;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.ha.HAServiceProtocol;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.ha.HealthCheckFailedException;
import org.apache.hadoop.ha.NodeFencer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

public class TestDFSHAAdmin {
  private static final Log LOG = LogFactory.getLog(TestDFSHAAdmin.class);
  
  private DFSHAAdmin tool;
  private ByteArrayOutputStream errOutBytes = new ByteArrayOutputStream();
  private String errOutput;
  private HAServiceProtocol mockProtocol;
  
  private static final String NSID = "ns1";
  private static String HOST_A = "1.2.3.1";
  private static String HOST_B = "1.2.3.2";

  private HdfsConfiguration getHAConf() {
    HdfsConfiguration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES, NSID);    
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICE_ID, NSID);
    conf.set(DFSUtil.addKeySuffixes(
        DFSConfigKeys.DFS_HA_NAMENODES_KEY, NSID), "nn1,nn2");    
    conf.set(DFSConfigKeys.DFS_HA_NAMENODE_ID_KEY, "nn1");
    conf.set(DFSUtil.addKeySuffixes(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, NSID, "nn1"),
        HOST_A + ":12345");
    conf.set(DFSUtil.addKeySuffixes(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, NSID, "nn2"),
        HOST_B + ":12345");
    return conf;
  }

  @Before
  public void setup() throws IOException {
    mockProtocol = Mockito.mock(HAServiceProtocol.class);
    when(mockProtocol.readyToBecomeActive()).thenReturn(true);
    tool = new DFSHAAdmin() {
      @Override
      protected HAServiceProtocol getProtocol(String serviceId) throws IOException {
        getServiceAddr(serviceId);
        return mockProtocol;
      }
    };
    tool.setConf(getHAConf());
    tool.setErrOut(new PrintStream(errOutBytes));
  }

  private void assertOutputContains(String string) {
    if (!errOutput.contains(string)) {
      fail("Expected output to contain '" + string + "' but was:\n" +
          errOutput);
    }
  }
  
  @Test
  public void testNameserviceOption() throws Exception {
    assertEquals(-1, runTool("-ns"));
    assertOutputContains("Missing nameservice ID");
    assertEquals(-1, runTool("-ns", "ns1"));
    assertOutputContains("Missing command");
    // "ns1" isn't defined but we check this lazily and help doesn't use the ns
    assertEquals(0, runTool("-ns", "ns1", "-help", "transitionToActive"));
    assertOutputContains("Transitions the service into Active");
  }

  @Test
  public void testNamenodeResolution() throws Exception {
    assertEquals(0, runTool("-getServiceState", "nn1"));
    Mockito.verify(mockProtocol).getServiceState();
    assertEquals(-1, runTool("-getServiceState", "undefined"));
    assertOutputContains(
        "Unable to determine service address for namenode 'undefined'");
  }

  @Test
  public void testHelp() throws Exception {
    assertEquals(-1, runTool("-help"));
    assertEquals(0, runTool("-help", "transitionToActive"));
    assertOutputContains("Transitions the service into Active");
  }
  
  @Test
  public void testTransitionToActive() throws Exception {
    assertEquals(0, runTool("-transitionToActive", "nn1"));
    Mockito.verify(mockProtocol).transitionToActive();
  }

  @Test
  public void testTransitionToStandby() throws Exception {
    assertEquals(0, runTool("-transitionToStandby", "nn1"));
    Mockito.verify(mockProtocol).transitionToStandby();
  }

  @Test
  public void testFailoverWithNoFencerConfigured() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    assertEquals(-1, runTool("-failover", "nn1", "nn2"));
  }

  @Test
  public void testFailoverWithFencerConfigured() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(0, runTool("-failover", "nn1", "nn2"));
  }

  @Test
  public void testFailoverWithFencerAndNameservice() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(0, runTool("-ns", "ns1", "-failover", "nn1", "nn2"));
  }

  @Test
  public void testFailoverWithFencerConfiguredAndForce() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(0, runTool("-failover", "nn1", "nn2", "--forcefence"));
  }

  @Test
  public void testFailoverWithForceActive() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(0, runTool("-failover", "nn1", "nn2", "--forceactive"));
  }

  @Test
  public void testFailoverWithInvalidFenceArg() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(-1, runTool("-failover", "nn1", "nn2", "notforcefence"));
  }

  @Test
  public void testFailoverWithFenceButNoFencer() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    assertEquals(-1, runTool("-failover", "nn1", "nn2", "--forcefence"));
  }

  @Test
  public void testFailoverWithFenceAndBadFencer() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "foobar!");
    tool.setConf(conf);
    assertEquals(-1, runTool("-failover", "nn1", "nn2", "--forcefence"));
  }

  @Test
  public void testForceFenceOptionListedBeforeArgs() throws Exception {
    Mockito.doReturn(HAServiceState.STANDBY).when(mockProtocol).getServiceState();
    HdfsConfiguration conf = getHAConf();
    conf.set(NodeFencer.CONF_METHODS_KEY, "shell(true)");
    tool.setConf(conf);
    assertEquals(0, runTool("-failover", "--forcefence", "nn1", "nn2"));
  }

  @Test
  public void testGetServiceState() throws Exception {
    assertEquals(0, runTool("-getServiceState", "nn1"));
    Mockito.verify(mockProtocol).getServiceState();
  }

  @Test
  public void testCheckHealth() throws Exception {
    assertEquals(0, runTool("-checkHealth", "nn1"));
    Mockito.verify(mockProtocol).monitorHealth();
    
    Mockito.doThrow(new HealthCheckFailedException("fake health check failure"))
      .when(mockProtocol).monitorHealth();
    assertEquals(-1, runTool("-checkHealth", "nn1"));
    assertOutputContains("Health check failed: fake health check failure");
  }

  private Object runTool(String ... args) throws Exception {
    errOutBytes.reset();
    LOG.info("Running: DFSHAAdmin " + Joiner.on(" ").join(args));
    int ret = tool.run(args);
    errOutput = new String(errOutBytes.toByteArray(), Charsets.UTF_8);
    LOG.info("Output:\n" + errOutput);
    return ret;
  }
}
