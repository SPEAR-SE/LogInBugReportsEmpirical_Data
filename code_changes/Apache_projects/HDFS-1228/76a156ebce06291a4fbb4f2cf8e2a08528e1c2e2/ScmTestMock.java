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

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.ozone.protocol.StorageContainerDatanodeProtocol;
import org.apache.hadoop.ozone.protocol.VersionResponse;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMHeartbeatResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMCommandResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.ReportState;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMNodeReport;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKResponseProto;
import org.apache.hadoop.ozone.scm.VersionInfo;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SCM RPC mock class.
 */
public class ScmTestMock implements StorageContainerDatanodeProtocol {
  private int rpcResponseDelay;
  private AtomicInteger heartbeatCount = new AtomicInteger(0);
  private AtomicInteger rpcCount = new AtomicInteger(0);
  private ReportState reportState;
  private AtomicInteger containerReportsCount = new AtomicInteger(0);
  private AtomicInteger closedContainerCount = new AtomicInteger(0);

  /**
   * Returns the number of heartbeats made to this class.
   *
   * @return int
   */
  public int getHeartbeatCount() {
    return heartbeatCount.get();
  }

  /**
   * Returns the number of RPC calls made to this mock class instance.
   *
   * @return - Number of RPC calls serviced by this class.
   */
  public int getRpcCount() {
    return rpcCount.get();
  }

  /**
   * Gets the RPC response delay.
   *
   * @return delay in milliseconds.
   */
  public int getRpcResponseDelay() {
    return rpcResponseDelay;
  }

  /**
   * Sets the RPC response delay.
   *
   * @param rpcResponseDelay - delay in milliseconds.
   */
  public void setRpcResponseDelay(int rpcResponseDelay) {
    this.rpcResponseDelay = rpcResponseDelay;
  }

  /**
   * Returns the number of container reports server has seen.
   * @return int
   */
  public int getContainerReportsCount() {
    return containerReportsCount.get();
  }

  /**
   * Returns the number of closed containers that have been reported so far.
   * @return - count of closed containers.
   */
  public int getClosedContainerCount() {
    return closedContainerCount.get();
  }

  /**
   * Returns SCM version.
   *
   * @return Version info.
   */
  @Override
  public StorageContainerDatanodeProtocolProtos.SCMVersionResponseProto
      getVersion(StorageContainerDatanodeProtocolProtos
      .SCMVersionRequestProto unused) throws IOException {
    rpcCount.incrementAndGet();
    sleepIfNeeded();
    VersionInfo versionInfo = VersionInfo.getLatestVersion();
    return VersionResponse.newBuilder()
        .setVersion(versionInfo.getVersion())
        .addValue(VersionInfo.DESCRIPTION_KEY, versionInfo.getDescription())
        .build().getProtobufMessage();
  }

  private void sleepIfNeeded() {
    if (getRpcResponseDelay() > 0) {
      try {
        Thread.sleep(getRpcResponseDelay());
      } catch (InterruptedException ex) {
        // Just ignore this exception.
      }
    }
  }

  /**
   * Used by data node to send a Heartbeat.
   *
   * @param datanodeID - Datanode ID.
   * @param nodeReport - node report.
   * @return - SCMHeartbeatResponseProto
   * @throws IOException
   */
  @Override
  public StorageContainerDatanodeProtocolProtos.SCMHeartbeatResponseProto
      sendHeartbeat(DatanodeID datanodeID, SCMNodeReport nodeReport,
      ReportState reportState) throws IOException {
    rpcCount.incrementAndGet();
    heartbeatCount.incrementAndGet();
    this.reportState = reportState;
    sleepIfNeeded();
    List<SCMCommandResponseProto>
        cmdResponses = new LinkedList<>();
    return SCMHeartbeatResponseProto.newBuilder().addAllCommands(cmdResponses)
        .build();
  }

  /**
   * Register Datanode.
   *
   * @param datanodeID - DatanodID.
   * @param scmAddresses - List of SCMs this datanode is configured to
   * communicate.
   * @return SCM Command.
   */
  @Override
  public StorageContainerDatanodeProtocolProtos
      .SCMRegisteredCmdResponseProto register(DatanodeID datanodeID,
      String[] scmAddresses) throws IOException {
    rpcCount.incrementAndGet();
    sleepIfNeeded();
    return StorageContainerDatanodeProtocolProtos
        .SCMRegisteredCmdResponseProto
        .newBuilder().setClusterID(UUID.randomUUID().toString())
        .setDatanodeUUID(datanodeID.getDatanodeUuid()).setErrorCode(
            StorageContainerDatanodeProtocolProtos
                .SCMRegisteredCmdResponseProto.ErrorCode.success).build();
  }

  /**
   * Send a container report.
   *
   * @param reports -- Container report
   * @return HeartbeatResponse.nullcommand.
   * @throws IOException
   */
  @Override
  public SCMHeartbeatResponseProto
      sendContainerReport(StorageContainerDatanodeProtocolProtos
      .ContainerReportsProto reports) throws IOException {
    Preconditions.checkNotNull(reports);
    containerReportsCount.incrementAndGet();
    closedContainerCount.addAndGet(reports.getReportsCount());
    List<SCMCommandResponseProto>
        cmdResponses = new LinkedList<>();
    return SCMHeartbeatResponseProto.newBuilder().addAllCommands(cmdResponses)
        .build();
  }

  @Override
  public ContainerBlocksDeletionACKResponseProto sendContainerBlocksDeletionACK(
      ContainerBlocksDeletionACKProto request) throws IOException {
    return ContainerBlocksDeletionACKResponseProto
        .newBuilder().getDefaultInstanceForType();
  }

  public ReportState getReportState() {
    return this.reportState;
  }
}
