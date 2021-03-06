/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.scm.node;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.ozone.scm.container.ContainerMapping;
import org.apache.hadoop.ozone.scm.container.placement.algorithms.ContainerPlacementPolicy;
import org.apache.hadoop.ozone.scm.container.placement.algorithms.SCMContainerPlacementCapacity;
import org.apache.hadoop.scm.ScmConfigKeys;
import org.apache.hadoop.scm.client.ScmClient;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.PathUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.apache.hadoop.scm.ScmConfigKeys.OZONE_SCM_DB_CACHE_SIZE_DEFAULT;
import static org.apache.hadoop.scm.ScmConfigKeys.OZONE_SCM_DB_CACHE_SIZE_MB;
import static org.apache.hadoop.ozone.scm.node.NodeManager.NODESTATE.HEALTHY;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test for different container placement policy.
 */
public class TestContainerPlacement {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Returns a new copy of Configuration.
   *
   * @return Config
   */
  OzoneConfiguration getConf() {
    return new OzoneConfiguration();
  }

  /**
   * Creates a NodeManager.
   *
   * @param config - Config for the node manager.
   * @return SCNNodeManager
   * @throws IOException
   */

  SCMNodeManager createNodeManager(OzoneConfiguration config)
      throws IOException {
    SCMNodeManager nodeManager = new SCMNodeManager(config,
        UUID.randomUUID().toString());
    assertFalse("Node manager should be in chill mode",
        nodeManager.isOutOfNodeChillMode());
    return nodeManager;
  }

  ContainerMapping createContainerManager(Configuration config,
      NodeManager scmNodeManager) throws IOException {
    final int cacheSize = config.getInt(OZONE_SCM_DB_CACHE_SIZE_MB,
        OZONE_SCM_DB_CACHE_SIZE_DEFAULT);
    return new ContainerMapping(config, scmNodeManager, cacheSize);

  }

  /**
   * Test capacity based container placement policy with node reports.
   *
   * @throws IOException
   * @throws InterruptedException
   * @throws TimeoutException
   */
  @Test
  public void testContainerPlacementCapacity() throws IOException,
      InterruptedException, TimeoutException {
    OzoneConfiguration conf = getConf();
    final int nodeCount = 4;
    final long capacity = 10L * OzoneConsts.GB;
    final long used = 2L * OzoneConsts.GB;
    final long remaining = capacity - used;

    final File testDir = PathUtils.getTestDir(
        TestContainerPlacement.class);
    conf.set(OzoneConfigKeys.OZONE_CONTAINER_METADATA_DIRS,
        testDir.getAbsolutePath());
    conf.setClass(ScmConfigKeys.OZONE_SCM_CONTAINER_PLACEMENT_IMPL_KEY,
        SCMContainerPlacementCapacity.class, ContainerPlacementPolicy.class);

    SCMNodeManager nodeManager = createNodeManager(conf);
    ContainerMapping containerManager =
        createContainerManager(conf, nodeManager);
    List<DatanodeID> datanodes =
        SCMTestUtils.getRegisteredDatanodeIDs(nodeManager, nodeCount);
    try {
      for (DatanodeID datanodeID : datanodes) {
        StorageContainerDatanodeProtocolProtos.SCMNodeReport.Builder nrb =
            StorageContainerDatanodeProtocolProtos.SCMNodeReport.newBuilder();
        StorageContainerDatanodeProtocolProtos.SCMStorageReport.Builder srb =
            StorageContainerDatanodeProtocolProtos.SCMStorageReport
                .newBuilder();
        srb.setStorageUuid(UUID.randomUUID().toString());
        srb.setCapacity(capacity).setScmUsed(used).
            setRemaining(remaining).build();
        nodeManager.sendHeartbeat(datanodeID,
            nrb.addStorageReport(srb).build());
      }

      GenericTestUtils.waitFor(() -> nodeManager.waitForHeartbeatProcessed(),
          100, 4 * 1000);
      assertEquals(nodeCount, nodeManager.getNodeCount(HEALTHY));
      assertEquals(capacity * nodeCount,
          (long) nodeManager.getStats().getCapacity().get());
      assertEquals(used * nodeCount,
          (long) nodeManager.getStats().getScmUsed().get());
      assertEquals(remaining * nodeCount,
          (long) nodeManager.getStats().getRemaining().get());

      assertTrue(nodeManager.isOutOfNodeChillMode());

      String container1 = UUID.randomUUID().toString();
      Pipeline pipeline1 = containerManager.allocateContainer(container1,
          ScmClient.ReplicationFactor.THREE);
      assertEquals(3, pipeline1.getMachines().size());

      final long newUsed = 7L * OzoneConsts.GB;
      final long newRemaining = capacity - newUsed;

      for (DatanodeID datanodeID : datanodes) {
        StorageContainerDatanodeProtocolProtos.SCMNodeReport.Builder nrb =
            StorageContainerDatanodeProtocolProtos.SCMNodeReport.newBuilder();
        StorageContainerDatanodeProtocolProtos.SCMStorageReport.Builder srb =
            StorageContainerDatanodeProtocolProtos.SCMStorageReport
                .newBuilder();
        srb.setStorageUuid(UUID.randomUUID().toString());
        srb.setCapacity(capacity).setScmUsed(newUsed).
            setRemaining(newRemaining).build();
        nodeManager.sendHeartbeat(datanodeID,
            nrb.addStorageReport(srb).build());
      }

      GenericTestUtils.waitFor(() -> nodeManager.getStats().getRemaining()
              .get() == nodeCount * newRemaining,
          100, 4 * 1000);

      thrown.expect(IOException.class);
      thrown.expectMessage(
          startsWith("Unable to find enough nodes that meet the space " +
              "requirement in healthy node set."));
      String container2 = UUID.randomUUID().toString();
      containerManager.allocateContainer(container2,
          ScmClient.ReplicationFactor.THREE);
    } finally {
      IOUtils.closeQuietly(containerManager);
      IOUtils.closeQuietly(nodeManager);
      FileUtil.fullyDelete(testDir);
    }
  }
}
