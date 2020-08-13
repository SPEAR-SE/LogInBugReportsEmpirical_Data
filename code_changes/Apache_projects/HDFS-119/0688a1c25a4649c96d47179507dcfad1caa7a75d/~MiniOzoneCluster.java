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
package org.apache.hadoop.ozone;

import java.io.File;
import java.util.Optional;
import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.ksm.KSMConfigKeys;
import org.apache.hadoop.ozone.ksm.KeySpaceManager;
import org.apache.hadoop.scm.ScmConfigKeys;
import org.apache.hadoop.scm.protocolPB
    .StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.scm.protocolPB.StorageContainerLocationProtocolPB;
import org.apache.hadoop.ozone.scm.StorageContainerManager;
import org.apache.hadoop.ozone.scm.node.SCMNodeManager;
import org.apache.hadoop.ozone.web.client.OzoneClient;
import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;

/**
 * MiniOzoneCluster creates a complete in-process Ozone cluster suitable for
 * running tests.  The cluster consists of a StorageContainerManager, Namenode
 * and multiple DataNodes.  This class subclasses {@link MiniDFSCluster} for
 * convenient reuse of logic for starting DataNodes.
 */
@InterfaceAudience.Private
public final class MiniOzoneCluster extends MiniDFSCluster
    implements Closeable {
  private static final Logger LOG =
      LoggerFactory.getLogger(MiniOzoneCluster.class);
  private static final String USER_AUTH = "hdfs";

  private final OzoneConfiguration conf;
  private final StorageContainerManager scm;
  private final KeySpaceManager ksm;
  private final Path tempPath;

  /**
   * Creates a new MiniOzoneCluster.
   *
   * @param builder cluster builder
   * @param scm     StorageContainerManager, already running
   * @throws IOException if there is an I/O error
   */
  private MiniOzoneCluster(Builder builder, StorageContainerManager scm,
                           KeySpaceManager ksm)
      throws IOException {
    super(builder);
    this.conf = builder.conf;
    this.scm = scm;
    this.ksm = ksm;
    tempPath = Paths.get(builder.getPath(), builder.getRunID());
  }

  @Override
  protected void setupDatanodeAddress(
      int i, Configuration dnConf, boolean setupHostsFile,
      boolean checkDnAddrConf) throws IOException {
    super.setupDatanodeAddress(i, dnConf, setupHostsFile, checkDnAddrConf);

    final boolean useRatis = dnConf.getBoolean(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_ENABLED_KEY,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_ENABLED_DEFAULT);
    if (!useRatis) {
      return;
    }
    final String address = ContainerTestHelper.createLocalAddress();
    setConf(i, dnConf, OzoneConfigKeys.DFS_CONTAINER_RATIS_SERVER_ID,
        address);
    setConf(i, dnConf, OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
        String.valueOf(NetUtils.createSocketAddr(address).getPort()));
    setConf(i, dnConf, OzoneConfigKeys.DFS_CONTAINER_RATIS_DATANODE_STORAGE_DIR,
        getInstanceStorageDir(i, -1).getCanonicalPath());
  }

  static void setConf(int i, Configuration conf, String key, String value) {
    conf.set(key, value);
    LOG.info("dn{}: set {} = {}", i, key, value);
  }

  @Override
  public void close() {
    shutdown();
    try {
      FileUtils.deleteDirectory(tempPath.toFile());
    } catch (IOException e) {
      String errorMessage = "Cleaning up metadata directories failed." + e;
      assertFalse(errorMessage, true);
    }

    try {
      final String localStorage =
          conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
              OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
      FileUtils.deleteDirectory(new File(localStorage));
    } catch (IOException e) {
      LOG.error("Cleaning up local storage failed", e);
    }
  }

  @Override
  public void shutdown() {
    super.shutdown();
    LOG.info("Shutting down the Mini Ozone Cluster");

    if (ksm != null) {
      LOG.info("Shutting down the keySpaceManager");
      ksm.stop();
      ksm.join();
    }

    if (scm != null) {
      LOG.info("Shutting down the StorageContainerManager");
      scm.stop();
      scm.join();
    }
  }

  public StorageContainerManager getStorageContainerManager() {
    return this.scm;
  }

  public KeySpaceManager getKeySpaceManager() {
    return this.ksm;
  }

  /**
   * Creates an {@link OzoneClient} connected to this cluster's REST service.
   * Callers take ownership of the client and must close it when done.
   *
   * @return OzoneClient connected to this cluster's REST service
   * @throws OzoneException if Ozone encounters an error creating the client
   */
  public OzoneClient createOzoneClient() throws OzoneException {
    Preconditions.checkState(!getDataNodes().isEmpty(),
        "Cannot create OzoneClient if the cluster has no DataNodes.");
    // An Ozone request may originate at any DataNode, so pick one at random.
    int dnIndex = new Random().nextInt(getDataNodes().size());
    String uri = String.format("http://127.0.0.1:%d",
        getDataNodes().get(dnIndex).getInfoPort());
    LOG.info("Creating Ozone client to DataNode {} with URI {} and user {}",
        dnIndex, uri, USER_AUTH);
    try {
      return new OzoneClient(uri, USER_AUTH);
    } catch (URISyntaxException e) {
      // We control the REST service URI, so it should never be invalid.
      throw new IllegalStateException("Unexpected URISyntaxException", e);
    }
  }

  /**
   * Creates an RPC proxy connected to this cluster's StorageContainerManager
   * for accessing container location information.  Callers take ownership of
   * the proxy and must close it when done.
   *
   * @return RPC proxy for accessing container location information
   * @throws IOException if there is an I/O error
   */
  public StorageContainerLocationProtocolClientSideTranslatorPB
      createStorageContainerLocationClient() throws IOException {
    long version = RPC.getProtocolVersion(
        StorageContainerLocationProtocolPB.class);
    InetSocketAddress address = scm.getClientRpcAddress();
    LOG.info(
        "Creating StorageContainerLocationProtocol RPC client with address {}",
        address);
    return new StorageContainerLocationProtocolClientSideTranslatorPB(
        RPC.getProxy(StorageContainerLocationProtocolPB.class, version,
            address, UserGroupInformation.getCurrentUser(), conf,
            NetUtils.getDefaultSocketFactory(conf),
            Client.getRpcTimeout(conf)));
  }

  /**
   * Waits for the Ozone cluster to be ready for processing requests.
   */
  public void waitOzoneReady() throws TimeoutException, InterruptedException {
    GenericTestUtils.waitFor(() -> {
      final int healthy = scm.getNodeCount(SCMNodeManager.NODESTATE.HEALTHY);
      final boolean isReady = healthy >= numDataNodes;
      LOG.info("{}. Got {} of {} DN Heartbeats.",
            isReady? "Cluster is ready" : "Waiting for cluster to be ready",
            healthy, numDataNodes);
      return isReady;
    }, 1000, 60 * 1000); //wait for 1 min.
  }

  /**
   * Waits for SCM to be out of Chill Mode. Many tests can be run iff we are out
   * of Chill mode.
   *
   * @throws TimeoutException
   * @throws InterruptedException
   */
  public void waitTobeOutOfChillMode() throws TimeoutException,
      InterruptedException {
    GenericTestUtils.waitFor(() -> {
      if (scm.getScmNodeManager().isOutOfNodeChillMode()) {
        return true;
      }
      LOG.info("Waiting for cluster to be ready. No datanodes found");
      return false;
    }, 100, 45000);
  }

  public void waitForHeartbeatProcessed() throws TimeoutException,
      InterruptedException {
    GenericTestUtils.waitFor(() ->
            scm.getScmNodeManager().waitForHeartbeatProcessed(), 100,
        4 * 1000);
    GenericTestUtils.waitFor(() ->
            scm.getScmNodeManager().getStats().getCapacity().get() > 0, 100,
        4 * 1000);
  }

  /**
   * Builder for configuring the MiniOzoneCluster to run.
   */
  public static class Builder
      extends MiniDFSCluster.Builder {

    private final OzoneConfiguration conf;
    private static final int DEFAULT_HB_SECONDS = 1;
    private static final int DEFAULT_PROCESSOR_MS = 100;
    private final String path;
    private final UUID runID;
    private Optional<String> ozoneHandlerType = java.util.Optional.empty();
    private Optional<Boolean> enableTrace = Optional.of(false);
    private Optional<Integer> hbSeconds = Optional.empty();
    private Optional<Integer> hbProcessorInterval = Optional.empty();
    private Optional<String> scmMetadataDir = Optional.empty();
    private Boolean ozoneEnabled = true;
    private Boolean waitForChillModeFinish = true;
    private Boolean randomContainerPort = true;

    /**
     * Creates a new Builder.
     *
     * @param conf configuration
     */
    public Builder(OzoneConfiguration conf) {
      super(conf);
      this.conf = conf;

      path = GenericTestUtils.getTempPath(
          MiniOzoneCluster.class.getSimpleName() +
          UUID.randomUUID().toString());
      runID = UUID.randomUUID();
    }

    public Builder setRandomContainerPort(boolean randomPort) {
      this.randomContainerPort = randomPort;
      return this;
    }

    @Override
    public Builder numDataNodes(int val) {
      super.numDataNodes(val);
      return this;
    }

    @Override
    public Builder storageCapacities(long[] capacities) {
      super.storageCapacities(capacities);
      return this;
    }

    public Builder setHandlerType(String handler) {
      ozoneHandlerType = Optional.of(handler);
      return this;
    }

    public Builder setTrace(Boolean trace) {
      enableTrace = Optional.of(trace);
      return this;
    }

    public Builder setSCMHBInterval(int seconds) {
      hbSeconds = Optional.of(seconds);
      return this;
    }

    public Builder setSCMHeartbeatProcessingInterval(int milliseconds) {
      hbProcessorInterval = Optional.of(milliseconds);
      return this;
    }

    public Builder setSCMMetadataDir(String scmMetadataDirPath) {
      scmMetadataDir = Optional.of(scmMetadataDirPath);
      return this;
    }

    public Builder disableOzone() {
      ozoneEnabled = false;
      return this;
    }

    public Builder doNotwaitTobeOutofChillMode() {
      waitForChillModeFinish = false;
      return this;
    }

    public String getPath() {
      return path;
    }

    public String getRunID() {
      return runID.toString();
    }

    @Override
    public MiniOzoneCluster build() throws IOException {


      configureHandler();
      configureTrace();
      configureSCMheartbeat();
      configScmMetadata();

      conf.set(ScmConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY, "127.0.0.1:0");
      conf.set(ScmConfigKeys.OZONE_SCM_BLOCK_CLIENT_ADDRESS_KEY, "127.0.0.1:0");
      conf.set(ScmConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY, "127.0.0.1:0");
      conf.set(KSMConfigKeys.OZONE_KSM_ADDRESS_KEY, "127.0.0.1:0");

      // Use random ports for ozone containers in mini cluster,
      // in order to launch multiple container servers per node.
      conf.setBoolean(OzoneConfigKeys.DFS_CONTAINER_IPC_RANDOM_PORT,
          randomContainerPort);

      StorageContainerManager scm = new StorageContainerManager(conf);
      scm.start();

      KeySpaceManager ksm = new KeySpaceManager(conf);
      ksm.start();

      String addressString =  scm.getDatanodeRpcAddress().getHostString() +
          ":" + scm.getDatanodeRpcAddress().getPort();
      conf.setStrings(ScmConfigKeys.OZONE_SCM_NAMES, addressString);

      MiniOzoneCluster cluster = new MiniOzoneCluster(this, scm, ksm);
      try {
        cluster.waitOzoneReady();
        if (waitForChillModeFinish) {
          cluster.waitTobeOutOfChillMode();
        }
        cluster.waitForHeartbeatProcessed();
      } catch (Exception e) {
        // A workaround to propagate MiniOzoneCluster failures without
        // changing the method signature (which would require cascading
        // changes to hundreds of unrelated HDFS tests).
        throw new IOException("Failed to start MiniOzoneCluster", e);
      }
      return cluster;
    }

    private void configScmMetadata() throws IOException {


      if (scmMetadataDir.isPresent()) {
        // if user specifies a path in the test, it is assumed that user takes
        // care of creating and cleaning up that directory after the tests.
        conf.set(OzoneConfigKeys.OZONE_CONTAINER_METADATA_DIRS,
            scmMetadataDir.get());
        return;
      }

      // If user has not specified a path, create a UUID for this miniCluster
      // and create SCM under that directory.
      Path scmPath = Paths.get(path, runID.toString(), "scm");
      Files.createDirectories(scmPath);
      conf.set(OzoneConfigKeys.OZONE_CONTAINER_METADATA_DIRS, scmPath
          .toString());

      // TODO : Fix this, we need a more generic mechanism to map
      // different datanode ID for different datanodes when we have lots of
      // datanodes in the cluster.
      conf.setStrings(ScmConfigKeys.OZONE_SCM_DATANODE_ID,
          scmPath.toString() + "/datanode.id");
    }

    private void configureHandler() {
      conf.setBoolean(OzoneConfigKeys.OZONE_ENABLED, this.ozoneEnabled);
      if (!ozoneHandlerType.isPresent()) {
        throw new IllegalArgumentException(
            "The Ozone handler type must be specified.");
      } else {
        conf.set(OzoneConfigKeys.OZONE_HANDLER_TYPE_KEY, ozoneHandlerType.get());
      }
    }

    private void configureTrace() {
      if (enableTrace.isPresent()) {
        conf.setBoolean(OzoneConfigKeys.OZONE_TRACE_ENABLED_KEY,
            enableTrace.get());
        GenericTestUtils.setLogLevel(org.apache.log4j.Logger.getRootLogger(),
            Level.ALL);
      }
      GenericTestUtils.setLogLevel(org.apache.log4j.Logger.getRootLogger(),
          Level.INFO);
    }

    private void configureSCMheartbeat() {
      if (hbSeconds.isPresent()) {
        conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS,
            hbSeconds.get());

      } else {
        conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS,
            DEFAULT_HB_SECONDS);
      }

      if (hbProcessorInterval.isPresent()) {
        conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS,
            hbProcessorInterval.get());
      } else {
        conf.setInt(ScmConfigKeys.OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS,
            DEFAULT_PROCESSOR_MS);
      }

    }
  }
}
