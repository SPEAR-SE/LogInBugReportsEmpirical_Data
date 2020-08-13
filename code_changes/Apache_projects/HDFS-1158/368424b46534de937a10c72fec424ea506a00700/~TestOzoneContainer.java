/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.ozoneimpl;

import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;
import org.apache.hadoop.scm.XceiverClient;
import org.apache.hadoop.scm.XceiverClientRatis;
import org.apache.hadoop.scm.XceiverClientSpi;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.ratis.rpc.RpcType;
import org.apache.ratis.rpc.SupportedRpcType;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests ozone containers.
 */
public class TestOzoneContainer {
  /**
   * Set the timeout for every test.
   */
  @Rule
  public Timeout testTimeout = new Timeout(300000);

  @Test
  public void testCreateOzoneContainer() throws Exception {
    String containerName = OzoneUtils.getRequestID();
    OzoneConfiguration conf = new OzoneConfiguration();
    URL p = conf.getClass().getResource("");
    String path = p.getPath().concat(
        TestOzoneContainer.class.getSimpleName());
    path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
    conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);
    OzoneContainer container = null;
    MiniOzoneCluster cluster = null;
    try {
      cluster = new MiniOzoneCluster.Builder(conf)
          .setRandomContainerPort(false)
          .setHandlerType("distributed").build();
      // We don't start Ozone Container via data node, we will do it
      // independently in our test path.
      Pipeline pipeline = ContainerTestHelper.createSingleNodePipeline(
          containerName);
      conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
          pipeline.getLeader().getContainerPort());
      container = new OzoneContainer(conf);
      container.start();

      XceiverClient client = new XceiverClient(pipeline, conf);
      client.connect();
      ContainerProtos.ContainerCommandRequestProto request =
          ContainerTestHelper.getCreateContainerRequest(containerName);
      ContainerProtos.ContainerCommandResponseProto response =
          client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));
    } finally {
      if (container != null) {
        container.stop();
      }
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testOzoneContainerViaDataNodeRatisGrpc() throws Exception {
    runTestOzoneContainerViaDataNodeRatis(SupportedRpcType.GRPC, 1);
    runTestOzoneContainerViaDataNodeRatis(SupportedRpcType.GRPC, 3);
  }

  @Test
  public void testOzoneContainerViaDataNodeRatisNetty() throws Exception {
    runTestOzoneContainerViaDataNodeRatis(SupportedRpcType.NETTY, 1);
    runTestOzoneContainerViaDataNodeRatis(SupportedRpcType.NETTY, 3);
  }

  private static void runTestOzoneContainerViaDataNodeRatis(
      RpcType rpc, int numNodes) throws Exception {
    ContainerTestHelper.LOG.info("runTestOzoneContainerViaDataNodeRatis(rpc="
        + rpc + ", numNodes=" + numNodes);

    final String containerName = OzoneUtils.getRequestID();
    final Pipeline pipeline = ContainerTestHelper.createPipeline(
        containerName, numNodes);
    final OzoneConfiguration conf = initOzoneConfiguration(pipeline);
    ContainerTestHelper.initRatisConf(rpc, pipeline, conf);

    final MiniOzoneCluster cluster = new MiniOzoneCluster.Builder(conf)
        .setHandlerType("local")
        .numDataNodes(pipeline.getMachines().size())
        .build();
    cluster.waitOzoneReady();
    final XceiverClientSpi client = XceiverClientRatis.newXceiverClientRatis(
        pipeline, conf);

    try {
      runTestOzoneContainerViaDataNode(containerName, pipeline, client);
    } finally {
      cluster.shutdown();
    }
  }

  private static OzoneConfiguration initOzoneConfiguration(Pipeline pipeline) {
    final OzoneConfiguration conf = new OzoneConfiguration();
    conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
        pipeline.getLeader().getContainerPort());

    setOzoneLocalStorageRoot(conf);
    return conf;
  }

  private static void setOzoneLocalStorageRoot(OzoneConfiguration conf) {
    URL p = conf.getClass().getResource("");
    String path = p.getPath().concat(TestOzoneContainer.class.getSimpleName());
    path += conf.getTrimmed(
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
    conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);
  }

  @Test
  public void testOzoneContainerViaDataNode() throws Exception {
    MiniOzoneCluster cluster = null;
    try {
      String containerName = OzoneUtils.getRequestID();
      OzoneConfiguration conf = new OzoneConfiguration();
      setOzoneLocalStorageRoot(conf);

      // Start ozone container Via Datanode create.

      Pipeline pipeline =
          ContainerTestHelper.createSingleNodePipeline(containerName);
      conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
          pipeline.getLeader().getContainerPort());

      cluster = new MiniOzoneCluster.Builder(conf)
          .setRandomContainerPort(false)
          .setHandlerType("distributed").build();

      // This client talks to ozone container via datanode.
      XceiverClient client = new XceiverClient(pipeline, conf);

      runTestOzoneContainerViaDataNode(containerName, pipeline, client);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  static void runTestOzoneContainerViaDataNode(
      String containerName, Pipeline pipeline, XceiverClientSpi client)
      throws Exception {
    try {
      client.connect();

      // Create container
      ContainerProtos.ContainerCommandRequestProto request =
          ContainerTestHelper.getCreateContainerRequest(containerName);
      ContainerProtos.ContainerCommandResponseProto response =
          client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Write Chunk
      final String keyName = OzoneUtils.getRequestID();
      ContainerProtos.ContainerCommandRequestProto writeChunkRequest =
          ContainerTestHelper.getWriteChunkRequest(pipeline, containerName,
              keyName, 1024);

      response = client.sendCommand(writeChunkRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Read Chunk
      request = ContainerTestHelper.getReadChunkRequest(writeChunkRequest
          .getWriteChunk());

      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Put Key
      ContainerProtos.ContainerCommandRequestProto putKeyRequest =
          ContainerTestHelper.getPutKeyRequest(writeChunkRequest
              .getWriteChunk());


      response = client.sendCommand(putKeyRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Get Key
      request = ContainerTestHelper.getKeyRequest(putKeyRequest.getPutKey());
      response = client.sendCommand(request);
      ContainerTestHelper.verifyGetKey(request, response);


      // Delete Key
      request =
          ContainerTestHelper.getDeleteKeyRequest(putKeyRequest.getPutKey());
      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      //Delete Chunk
      request = ContainerTestHelper.getDeleteChunkRequest(writeChunkRequest
          .getWriteChunk());

      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      //Update an existing container
      Map<String, String> containerUpdate = new HashMap<String, String>();
      containerUpdate.put("container_updated_key", "container_updated_value");
      ContainerProtos.ContainerCommandRequestProto updateRequest1 =
          ContainerTestHelper.getUpdateContainerRequest(
              containerName, containerUpdate);
      ContainerProtos.ContainerCommandResponseProto updateResponse1 =
          client.sendCommand(updateRequest1);
      Assert.assertNotNull(updateResponse1);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS,
          response.getResult());

      //Update an non-existing container
      ContainerProtos.ContainerCommandRequestProto updateRequest2 =
          ContainerTestHelper.getUpdateContainerRequest(
              "non_exist_container", containerUpdate);
      ContainerProtos.ContainerCommandResponseProto updateResponse2 =
          client.sendCommand(updateRequest2);
      Assert.assertEquals(ContainerProtos.Result.CONTAINER_NOT_FOUND,
          updateResponse2.getResult());
    } finally {
      if (client != null) {
        client.close();
      }
    }
  }

  @Test
  public void testBothGetandPutSmallFile() throws Exception {
    MiniOzoneCluster cluster = null;
    XceiverClient client = null;
    try {
      String keyName = OzoneUtils.getRequestID();
      String containerName = OzoneUtils.getRequestID();
      OzoneConfiguration conf = new OzoneConfiguration();
      URL p = conf.getClass().getResource("");
      String path = p.getPath().concat(
          TestOzoneContainer.class.getSimpleName());
      path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
          OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
      conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);

      // Start ozone container Via Datanode create.

      Pipeline pipeline =
          ContainerTestHelper.createSingleNodePipeline(containerName);
      conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
          pipeline.getLeader().getContainerPort());

      cluster = new MiniOzoneCluster.Builder(conf)
          .setRandomContainerPort(false)
          .setHandlerType("distributed").build();

      // This client talks to ozone container via datanode.
      client = new XceiverClient(pipeline, conf);
      client.connect();

      // Create container
      ContainerProtos.ContainerCommandRequestProto request =
          ContainerTestHelper.getCreateContainerRequest(containerName);
      ContainerProtos.ContainerCommandResponseProto response =
          client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));


      ContainerProtos.ContainerCommandRequestProto smallFileRequest =
          ContainerTestHelper.getWriteSmallFileRequest(pipeline, containerName,
              keyName, 1024);


      response = client.sendCommand(smallFileRequest);
      Assert.assertNotNull(response);
      Assert.assertTrue(smallFileRequest.getTraceID()
          .equals(response.getTraceID()));

      ContainerProtos.ContainerCommandRequestProto getSmallFileRequest =
          ContainerTestHelper.getReadSmallFileRequest(smallFileRequest
              .getPutSmallFile().getKey());
      response = client.sendCommand(getSmallFileRequest);
      Assert.assertArrayEquals(
          smallFileRequest.getPutSmallFile().getData().toByteArray(),
          response.getGetSmallFile().getData().getData().toByteArray());
    } finally {
      if (client != null) {
        client.close();
      }
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testCloseContainer() throws Exception {
    MiniOzoneCluster cluster = null;
    XceiverClient client = null;
    try {

      String keyName = OzoneUtils.getRequestID();
      String containerName = OzoneUtils.getRequestID();
      OzoneConfiguration conf = new OzoneConfiguration();
      URL p = conf.getClass().getResource("");
      String path = p.getPath().concat(
          TestOzoneContainer.class.getSimpleName());
      path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
          OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
      conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);

      // Start ozone container Via Datanode create.

      Pipeline pipeline =
          ContainerTestHelper.createSingleNodePipeline(containerName);
      conf.setInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
          pipeline.getLeader().getContainerPort());

      cluster = new MiniOzoneCluster.Builder(conf)
          .setRandomContainerPort(false)
          .setHandlerType("distributed").build();

      // This client talks to ozone container via datanode.
      client = new XceiverClient(pipeline, conf);
      client.connect();



      // Create container
      ContainerProtos.ContainerCommandRequestProto request =
          ContainerTestHelper.getCreateContainerRequest(containerName);
      ContainerProtos.ContainerCommandResponseProto response =
          client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      ContainerProtos.ContainerCommandRequestProto writeChunkRequest =
          ContainerTestHelper.getWriteChunkRequest(pipeline, containerName,
              keyName, 1024);

      // Write Chunk before closing
      response = client.sendCommand(writeChunkRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS,
          response.getResult());
      Assert.assertTrue(writeChunkRequest.getTraceID().equals(response
          .getTraceID()));


      ContainerProtos.ContainerCommandRequestProto putKeyRequest =
          ContainerTestHelper.getPutKeyRequest(writeChunkRequest
              .getWriteChunk());
      // Put key before closing.
      response = client.sendCommand(putKeyRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS,
          response.getResult());
      Assert.assertTrue(
          putKeyRequest.getTraceID().equals(response.getTraceID()));

      // Close the contianer.
      request = ContainerTestHelper.getCloseContainer(pipeline);
      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));


      // Assert that none of the write  operations are working after close.

      // Write chunks should fail now.

      response = client.sendCommand(writeChunkRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.CLOSED_CONTAINER_IO,
          response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Read chunk must work on a closed container.
      request = ContainerTestHelper.getReadChunkRequest(writeChunkRequest
          .getWriteChunk());
      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.SUCCESS, response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));


      // Put key will fail on a closed container.
      response = client.sendCommand(putKeyRequest);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.CLOSED_CONTAINER_IO,
          response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));

      // Get key must work on the closed container.
      request = ContainerTestHelper.getKeyRequest(putKeyRequest.getPutKey());
      response = client.sendCommand(request);
      ContainerTestHelper.verifyGetKey(request, response);

      // Delete Key must fail on a closed container.
      request =
          ContainerTestHelper.getDeleteKeyRequest(putKeyRequest.getPutKey());
      response = client.sendCommand(request);
      Assert.assertNotNull(response);
      Assert.assertEquals(ContainerProtos.Result.CLOSED_CONTAINER_IO,
          response.getResult());
      Assert.assertTrue(request.getTraceID().equals(response.getTraceID()));
    } finally {
      if (client != null) {
        client.close();
      }
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}
