/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.container.common;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ozone.container.common.helpers.ContainerReport;
import org.apache.hadoop.ozone.container.common.statemachine
    .DatanodeStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine
    .EndpointStateMachine;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.ozone.container.common.states.endpoint
    .HeartbeatEndpointTask;
import org.apache.hadoop.ozone.container.common.states.endpoint
    .RegisterEndpointTask;
import org.apache.hadoop.ozone.container.common.states.endpoint
    .VersionEndpointTask;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerNodeIDProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMHeartbeatResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMNodeReport;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMRegisteredCmdResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMStorageReport;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMVersionResponseProto;
import org.apache.hadoop.ozone.scm.VersionInfo;
import org.apache.hadoop.test.PathUtils;
import org.apache.hadoop.util.Time;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.internal.matchers.LessOrEqual;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY;
import static org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ReportState.states
    .noContainerReports;

/**
 * Tests the endpoints.
 */
public class TestEndPoint {
  private static InetSocketAddress serverAddress;
  private static RPC.Server scmServer;
  private static ScmTestMock scmServerImpl;
  private static File testDir;
  private static StorageContainerDatanodeProtocolProtos.ReportState
      defaultReportState;

  @AfterClass
  public static void tearDown() throws Exception {
    if (scmServer != null) {
      scmServer.stop();
    }
    FileUtil.fullyDelete(testDir);
  }

  @BeforeClass
  public static void setUp() throws Exception {
    serverAddress = SCMTestUtils.getReuseableAddress();
    scmServerImpl = new ScmTestMock();
    scmServer = SCMTestUtils.startScmRpcServer(SCMTestUtils.getConf(),
        scmServerImpl, serverAddress, 10);
    testDir = PathUtils.getTestDir(TestEndPoint.class);
    defaultReportState = StorageContainerDatanodeProtocolProtos.ReportState.
        newBuilder().setState(noContainerReports).setCount(0).build();
  }

  @Test
  /**
   * This test asserts that we are able to make a version call to SCM server
   * and gets back the expected values.
   */
  public void testGetVersion() throws Exception {
    try (EndpointStateMachine rpcEndPoint =
             SCMTestUtils.createEndpoint(SCMTestUtils.getConf(),
                 serverAddress, 1000)) {
      SCMVersionResponseProto responseProto = rpcEndPoint.getEndPoint()
          .getVersion(null);
      Assert.assertNotNull(responseProto);
      Assert.assertEquals(responseProto.getKeys(0).getKey(),
          VersionInfo.DESCRIPTION_KEY);
      Assert.assertEquals(responseProto.getKeys(0).getValue(),
          VersionInfo.getLatestVersion().getDescription());
    }
  }

  @Test
  /**
   * We make getVersion RPC call, but via the VersionEndpointTask which is
   * how the state machine would make the call.
   */
  public void testGetVersionTask() throws Exception {
    Configuration conf = SCMTestUtils.getConf();
    try (EndpointStateMachine rpcEndPoint = SCMTestUtils.createEndpoint(conf,
        serverAddress, 1000)) {
      rpcEndPoint.setState(EndpointStateMachine.EndPointStates.GETVERSION);
      VersionEndpointTask versionTask = new VersionEndpointTask(rpcEndPoint,
          conf);
      EndpointStateMachine.EndPointStates newState = versionTask.call();

      // if version call worked the endpoint should automatically move to the
      // next state.
      Assert.assertEquals(EndpointStateMachine.EndPointStates.REGISTER,
          newState);

      // Now rpcEndpoint should remember the version it got from SCM
      Assert.assertNotNull(rpcEndPoint.getVersion());
    }
  }

  @Test
  /**
   * This test makes a call to end point where there is no SCM server. We
   * expect that versionTask should be able to handle it.
   */
  public void testGetVersionToInvalidEndpoint() throws Exception {
    Configuration conf = SCMTestUtils.getConf();
    InetSocketAddress nonExistentServerAddress = SCMTestUtils
        .getReuseableAddress();
    try (EndpointStateMachine rpcEndPoint = SCMTestUtils.createEndpoint(conf,
        nonExistentServerAddress, 1000)) {
      rpcEndPoint.setState(EndpointStateMachine.EndPointStates.GETVERSION);
      VersionEndpointTask versionTask = new VersionEndpointTask(rpcEndPoint,
          conf);
      EndpointStateMachine.EndPointStates newState = versionTask.call();

      // This version call did NOT work, so endpoint should remain in the same
      // state.
      Assert.assertEquals(EndpointStateMachine.EndPointStates.GETVERSION,
          newState);
    }
  }

  @Test
  /**
   * This test makes a getVersionRPC call, but the DummyStorageServer is
   * going to respond little slowly. We will assert that we are still in the
   * GETVERSION state after the timeout.
   */
  public void testGetVersionAssertRpcTimeOut() throws Exception {
    final long rpcTimeout = 1000;
    final long tolerance = 100;
    Configuration conf = SCMTestUtils.getConf();

    try (EndpointStateMachine rpcEndPoint = SCMTestUtils.createEndpoint(conf,
        serverAddress, (int) rpcTimeout)) {
      rpcEndPoint.setState(EndpointStateMachine.EndPointStates.GETVERSION);
      VersionEndpointTask versionTask = new VersionEndpointTask(rpcEndPoint,
          conf);

      scmServerImpl.setRpcResponseDelay(1500);
      long start = Time.monotonicNow();
      EndpointStateMachine.EndPointStates newState = versionTask.call();
      long end = Time.monotonicNow();
      scmServerImpl.setRpcResponseDelay(0);
      Assert.assertThat(end - start, new LessOrEqual<>(rpcTimeout + tolerance));
      Assert.assertEquals(EndpointStateMachine.EndPointStates.GETVERSION,
          newState);
    }
  }

  @Test
  public void testRegister() throws Exception {
    String[] scmAddressArray = new String[1];
    scmAddressArray[0] = serverAddress.toString();
    DatanodeID nodeToRegister = SCMTestUtils.getDatanodeID();
    try (EndpointStateMachine rpcEndPoint =
             SCMTestUtils.createEndpoint(
                 SCMTestUtils.getConf(), serverAddress, 1000)) {
      SCMRegisteredCmdResponseProto responseProto = rpcEndPoint.getEndPoint()
          .register(nodeToRegister, scmAddressArray);
      Assert.assertNotNull(responseProto);
      Assert.assertEquals(responseProto.getDatanodeUUID(),
          nodeToRegister.getDatanodeUuid());
      Assert.assertNotNull(responseProto.getClusterID());
    }
  }

  private EndpointStateMachine registerTaskHelper(InetSocketAddress scmAddress,
      int rpcTimeout, boolean clearContainerID) throws Exception {
    Configuration conf = SCMTestUtils.getConf();
    EndpointStateMachine rpcEndPoint =
        SCMTestUtils.createEndpoint(conf,
            scmAddress, rpcTimeout);
    rpcEndPoint.setState(EndpointStateMachine.EndPointStates.REGISTER);
    RegisterEndpointTask endpointTask =
        new RegisterEndpointTask(rpcEndPoint, conf);
    if (!clearContainerID) {
      ContainerNodeIDProto containerNodeID = ContainerNodeIDProto.newBuilder()
          .setClusterID(UUID.randomUUID().toString())
          .setDatanodeID(SCMTestUtils.getDatanodeID().getProtoBufMessage())
          .build();
      endpointTask.setContainerNodeIDProto(containerNodeID);
    }
    endpointTask.call();
    return rpcEndPoint;
  }

  @Test
  public void testRegisterTask() throws Exception {
    try (EndpointStateMachine rpcEndpoint =
             registerTaskHelper(serverAddress, 1000, false)) {
      // Successful register should move us to Heartbeat state.
      Assert.assertEquals(EndpointStateMachine.EndPointStates.HEARTBEAT,
          rpcEndpoint.getState());
    }
  }

  @Test
  public void testRegisterToInvalidEndpoint() throws Exception {
    InetSocketAddress address = SCMTestUtils.getReuseableAddress();
    try (EndpointStateMachine rpcEndpoint =
             registerTaskHelper(address, 1000, false)) {
      Assert.assertEquals(EndpointStateMachine.EndPointStates.REGISTER,
          rpcEndpoint.getState());
    }
  }

  @Test
  public void testRegisterNoContainerID() throws Exception {
    InetSocketAddress address = SCMTestUtils.getReuseableAddress();
    try (EndpointStateMachine rpcEndpoint =
             registerTaskHelper(address, 1000, true)) {
      // No Container ID, therefore we tell the datanode that we would like to
      // shutdown.
      Assert.assertEquals(EndpointStateMachine.EndPointStates.SHUTDOWN,
          rpcEndpoint.getState());
    }
  }

  @Test
  public void testRegisterRpcTimeout() throws Exception {
    final long rpcTimeout = 1000;
    final long tolerance = 200;
    scmServerImpl.setRpcResponseDelay(1500);
    long start = Time.monotonicNow();
    registerTaskHelper(serverAddress, 1000, false).close();
    long end = Time.monotonicNow();
    scmServerImpl.setRpcResponseDelay(0);
    Assert.assertThat(end - start, new LessOrEqual<>(rpcTimeout + tolerance));
  }

  @Test
  public void testHeartbeat() throws Exception {
    DatanodeID dataNode = SCMTestUtils.getDatanodeID();
    try (EndpointStateMachine rpcEndPoint =
             SCMTestUtils.createEndpoint(SCMTestUtils.getConf(),
                 serverAddress, 1000)) {
      SCMNodeReport.Builder nrb = SCMNodeReport.newBuilder();
      SCMStorageReport.Builder srb = SCMStorageReport.newBuilder();
      srb.setStorageUuid(UUID.randomUUID().toString());
      srb.setCapacity(2000).setScmUsed(500).setRemaining(1500).build();
      nrb.addStorageReport(srb);
      SCMHeartbeatResponseProto responseProto = rpcEndPoint.getEndPoint()
          .sendHeartbeat(dataNode, nrb.build(), defaultReportState);
      Assert.assertNotNull(responseProto);
      Assert.assertEquals(0, responseProto.getCommandsCount());
    }
  }

  private void heartbeatTaskHelper(InetSocketAddress scmAddress,
      int rpcTimeout) throws Exception {
    Configuration conf = SCMTestUtils.getConf();
    conf.set(DFS_DATANODE_DATA_DIR_KEY, testDir.getAbsolutePath());

    // Create a datanode state machine for stateConext used by endpoint task
    try (DatanodeStateMachine stateMachine = new DatanodeStateMachine(conf);
        EndpointStateMachine rpcEndPoint = SCMTestUtils.createEndpoint(conf,
            scmAddress, rpcTimeout)) {
    ContainerNodeIDProto containerNodeID = ContainerNodeIDProto.newBuilder()
        .setClusterID(UUID.randomUUID().toString())
        .setDatanodeID(SCMTestUtils.getDatanodeID().getProtoBufMessage())
        .build();
    rpcEndPoint.setState(EndpointStateMachine.EndPointStates.HEARTBEAT);

    final StateContext stateContext = new StateContext(conf,
        DatanodeStateMachine.DatanodeStates.RUNNING,
        stateMachine);

    HeartbeatEndpointTask endpointTask =
        new HeartbeatEndpointTask(rpcEndPoint, conf, stateContext);
    endpointTask.setContainerNodeIDProto(containerNodeID);
    endpointTask.call();
    Assert.assertNotNull(endpointTask.getContainerNodeIDProto());

    Assert.assertEquals(EndpointStateMachine.EndPointStates.HEARTBEAT,
        rpcEndPoint.getState());
    }
  }

  @Test
  public void testHeartbeatTask() throws Exception {
    heartbeatTaskHelper(serverAddress, 1000);
  }

  @Test
  public void testHeartbeatTaskToInvalidNode() throws Exception {
    InetSocketAddress invalidAddress = SCMTestUtils.getReuseableAddress();
    heartbeatTaskHelper(invalidAddress, 1000);
  }

  @Test
  public void testHeartbeatTaskRpcTimeOut() throws Exception {
    final long rpcTimeout = 1000;
    final long tolerance = 200;
    scmServerImpl.setRpcResponseDelay(1500);
    long start = Time.monotonicNow();
    InetSocketAddress invalidAddress = SCMTestUtils.getReuseableAddress();
    heartbeatTaskHelper(invalidAddress, 1000);
    long end = Time.monotonicNow();
    scmServerImpl.setRpcResponseDelay(0);
    Assert.assertThat(end - start, new LessOrEqual<>(rpcTimeout + tolerance));
  }

  /**
   * Returns a new container report.
   * @return
   */
  ContainerReport getRandomContainerReport() {
    return new ContainerReport(UUID.randomUUID().toString()
        ,DigestUtils.sha256Hex("Random"));
  }

  /**
   * Creates dummy container reports.
   * @param count - The number of closed containers to create.
   * @return ContainerReportsProto
   */
  StorageContainerDatanodeProtocolProtos.ContainerReportsProto
      createDummyContainerReports(int count) {
    StorageContainerDatanodeProtocolProtos.ContainerReportsProto.Builder
        reportsBuilder = StorageContainerDatanodeProtocolProtos
        .ContainerReportsProto.newBuilder();
    for (int x = 0; x < count; x++) {
      reportsBuilder.addReports(getRandomContainerReport()
          .getProtoBufMessage());
    }
    reportsBuilder.setType(StorageContainerDatanodeProtocolProtos
        .ContainerReportsProto.reportType.fullReport);
    return reportsBuilder.build();
  }

  /**
   * Tests that rpcEndpoint sendContainerReport works as expected.
   * @throws Exception
   */
  @Test
  public void testContainerReportSend() throws Exception {
    final int count = 1000;
    try (EndpointStateMachine rpcEndPoint =
             SCMTestUtils.createEndpoint(SCMTestUtils.getConf(),
                 serverAddress, 1000)) {
      SCMHeartbeatResponseProto responseProto = rpcEndPoint
          .getEndPoint().sendContainerReport(createDummyContainerReports(
              count));
      Assert.assertNotNull(responseProto);
    }
    Assert.assertEquals(1, scmServerImpl.getContainerReportsCount());
    Assert.assertEquals(count, scmServerImpl.getClosedContainerCount());
  }
}
