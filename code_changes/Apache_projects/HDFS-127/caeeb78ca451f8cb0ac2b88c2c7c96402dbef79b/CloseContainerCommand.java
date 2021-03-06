/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.protocol.commands;

import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMCloseContainerCmdResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.Type;
import static org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos.Type.closeContainerCommand;

/**
 * Asks datanode to close a container.
 */
public class CloseContainerCommand
    extends SCMCommand<SCMCloseContainerCmdResponseProto> {

  private String containerName;

  public CloseContainerCommand(String containerName) {
    this.containerName = containerName;
  }

  /**
   * Returns the type of this command.
   *
   * @return Type
   */
  @Override
  public Type getType() {
    return closeContainerCommand;
  }

  /**
   * Gets the protobuf message of this object.
   *
   * @return A protobuf message.
   */
  @Override
  public byte[] getProtoBufMessage() {
    return getProto().toByteArray();
  }

  public SCMCloseContainerCmdResponseProto getProto() {
    return SCMCloseContainerCmdResponseProto.newBuilder()
        .setContainerName(containerName).build();
  }

  public static CloseContainerCommand getFromProtobuf(
      SCMCloseContainerCmdResponseProto closeContainerProto) {
    Preconditions.checkNotNull(closeContainerProto);
    return new CloseContainerCommand(closeContainerProto.getContainerName());

  }

  public String getContainerName() {
    return containerName;
  }
}
