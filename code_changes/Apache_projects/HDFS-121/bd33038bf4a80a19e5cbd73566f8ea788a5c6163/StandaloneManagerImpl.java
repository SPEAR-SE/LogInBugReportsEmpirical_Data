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
package org.apache.hadoop.ozone.scm.pipelines.standalone;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos;
import org.apache.hadoop.ozone.scm.container.placement.algorithms.ContainerPlacementPolicy;
import org.apache.hadoop.ozone.scm.node.NodeManager;
import org.apache.hadoop.ozone.scm.pipelines.PipelineManager;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;

import java.io.IOException;
import java.util.List;

/**
 * Standalone Manager Impl to prove that pluggable interface
 * works with current tests.
 */
public class StandaloneManagerImpl implements PipelineManager {
  private final NodeManager nodeManager;
  private final ContainerPlacementPolicy placementPolicy;
  private final long containerSize;

  /**
   * Constructor for Standalone Node Manager Impl.
   * @param nodeManager - Node Manager.
   * @param placementPolicy - Placement Policy
   * @param containerSize - Container Size.
   */
  public StandaloneManagerImpl(NodeManager nodeManager,
      ContainerPlacementPolicy placementPolicy, long containerSize) {
    this.nodeManager = nodeManager;
    this.placementPolicy = placementPolicy;
    this.containerSize =  containerSize;
  }

  /**
   * Translates a list of nodes, ordered such that the first is the leader, into
   * a corresponding {@link Pipeline} object.
   *
   * @param nodes - list of datanodes on which we will allocate the container.
   * The first of the list will be the leader node.
   * @param containerName container name
   * @return pipeline corresponding to nodes
   */
  private static Pipeline newPipelineFromNodes(final List<DatanodeID> nodes,
      final String containerName) {
    Preconditions.checkNotNull(nodes);
    Preconditions.checkArgument(nodes.size() > 0);
    String leaderId = nodes.get(0).getDatanodeUuid();
    Pipeline pipeline = new Pipeline(leaderId);
    for (DatanodeID node : nodes) {
      pipeline.addMember(node);
    }

    // The default state of a pipeline is operational, so not setting
    // explicit state here.

    pipeline.setContainerName(containerName);
    return pipeline;
  }

  /**
   * This function is called by the Container Manager while allocating a new
   * container. The client specifies what kind of replication pipeline is needed
   * and based on the replication type in the request appropriate Interface is
   * invoked.
   *
   * @param containerName Name of the container
   * @param replicationFactor - Replication Factor
   * @return a Pipeline.
   */
  @Override
  public Pipeline getPipeline(String containerName, OzoneProtos
      .ReplicationFactor replicationFactor) throws IOException {
    List<DatanodeID> datanodes = placementPolicy.chooseDatanodes(
        replicationFactor.getNumber(), containerSize);
    return newPipelineFromNodes(datanodes, containerName);
  }

  /**
   * Creates a pipeline from a specified set of Nodes.
   *
   * @param pipelineID - Name of the pipeline
   * @param datanodes - The list of datanodes that make this pipeline.
   */
  @Override
  public void createPipeline(String pipelineID, List<DatanodeID> datanodes) {
    //return newPipelineFromNodes(datanodes, pipelineID);
  }

  /**
   * Close the  pipeline with the given clusterId.
   *
   * @param pipelineID
   */
  @Override
  public void closePipeline(String pipelineID) throws IOException {

  }

  /**
   * list members in the pipeline .
   *
   * @param pipelineID
   * @return the datanode
   */
  @Override
  public List<DatanodeID> getMembers(String pipelineID) throws IOException {
    return null;
  }

  /**
   * Update the datanode list of the pipeline.
   *
   * @param pipelineID
   * @param newDatanodes
   */
  @Override
  public void updatePipeline(String pipelineID, List<DatanodeID>
      newDatanodes) throws IOException {

  }
}
