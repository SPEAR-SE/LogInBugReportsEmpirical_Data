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

package org.apache.hadoop.ozone.container.transport.server;

import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.ozone.container.interfaces.ContainerDispatcher;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class TestContainerServer {

  @Test
  public void testPipeline() {
    EmbeddedChannel channel = new EmbeddedChannel(new XceiverServerHandler(
        new TestContainerDispatcher()));
    ContainerCommandRequestProto request = ContainerCommandRequestProto
        .getDefaultInstance();
    channel.writeInbound(request);
    Assert.assertTrue(channel.finish());
    ContainerCommandResponseProto response = channel.readOutbound();
    Assert.assertTrue(
        ContainerCommandResponseProto.getDefaultInstance().equals(response));
    channel.close();
  }

  private class TestContainerDispatcher implements ContainerDispatcher {
    /**
     * Dispatches commands to container layer.
     *
     * @param msg - Command Request
     * @return Command Response
     * @throws IOException
     */
    @Override
    public ContainerCommandResponseProto
    dispatch(ContainerCommandRequestProto msg) throws IOException {
      return ContainerCommandResponseProto.getDefaultInstance();
    }
  }
}
