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
package org.apache.hadoop.hdfs.server.balancer;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.EnumSet;

import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HAUtil;
import org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Daemon;

import com.google.common.collect.Lists;

/**
 * The class provides utilities for {@link Balancer} to access a NameNode
 */
@InterfaceAudience.Private
class NameNodeConnector {
  private static final Log LOG = Balancer.LOG;
  private static final Path BALANCER_ID_PATH = new Path("/system/balancer.id");

  final InetSocketAddress namenodeAddress;
  final String blockpoolID;

  final NamenodeProtocol namenode;
  final ClientProtocol client;
  final FileSystem fs;
  final OutputStream out;

  private final boolean isBlockTokenEnabled;
  private boolean shouldRun;
  private long keyUpdaterInterval;
  private BlockTokenSecretManager blockTokenSecretManager;
  private Daemon keyupdaterthread; // AccessKeyUpdater thread

  NameNodeConnector(Collection<InetSocketAddress> haNNs,
      Configuration conf) throws IOException {
    this.namenodeAddress = Lists.newArrayList(haNNs).get(0);
    URI nameNodeUri = NameNode.getUri(this.namenodeAddress);
    NamenodeProtocol failoverNamenode = (NamenodeProtocol) HAUtil
        .createFailoverProxy(conf, nameNodeUri, NamenodeProtocol.class);
    if (null != failoverNamenode) {
      this.namenode = failoverNamenode;
    } else {
      this.namenode = DFSUtil.createNNProxyWithNamenodeProtocol(
          this.namenodeAddress, conf, UserGroupInformation.getCurrentUser());
    }
    ClientProtocol failOverClient = (ClientProtocol) HAUtil
        .createFailoverProxy(conf, nameNodeUri, ClientProtocol.class);
    if (null != failOverClient) {
      this.client = failOverClient;
    } else {
      this.client = DFSUtil.createNamenode(conf);
    }
    this.fs = FileSystem.get(nameNodeUri, conf);

    final NamespaceInfo namespaceinfo = namenode.versionRequest();
    this.blockpoolID = namespaceinfo.getBlockPoolID();

    final ExportedBlockKeys keys = namenode.getBlockKeys();
    this.isBlockTokenEnabled = keys.isBlockTokenEnabled();
    if (isBlockTokenEnabled) {
      long blockKeyUpdateInterval = keys.getKeyUpdateInterval();
      long blockTokenLifetime = keys.getTokenLifetime();
      LOG.info("Block token params received from NN: keyUpdateInterval="
          + blockKeyUpdateInterval / (60 * 1000) + " min(s), tokenLifetime="
          + blockTokenLifetime / (60 * 1000) + " min(s)");
      this.blockTokenSecretManager = new BlockTokenSecretManager(false,
          blockKeyUpdateInterval, blockTokenLifetime);
      this.blockTokenSecretManager.setKeys(keys);
      /*
       * Balancer should sync its block keys with NN more frequently than NN
       * updates its block keys
       */
      this.keyUpdaterInterval = blockKeyUpdateInterval / 4;
      LOG.info("Balancer will update its block keys every "
          + keyUpdaterInterval / (60 * 1000) + " minute(s)");
      this.keyupdaterthread = new Daemon(new BlockKeyUpdater());
      this.shouldRun = true;
      this.keyupdaterthread.start();
    }

    // Check if there is another balancer running.
    // Exit if there is another one running.
    out = checkAndMarkRunningBalancer(); 
    if (out == null) {
      throw new IOException("Another balancer is running");
    }
  }

  /** Get an access token for a block. */
  Token<BlockTokenIdentifier> getAccessToken(ExtendedBlock eb
      ) throws IOException {
    if (!isBlockTokenEnabled) {
      return BlockTokenSecretManager.DUMMY_TOKEN;
    } else {
      return blockTokenSecretManager.generateToken(null, eb,
          EnumSet.of(BlockTokenSecretManager.AccessMode.REPLACE,
          BlockTokenSecretManager.AccessMode.COPY));
    }
  }

  /* The idea for making sure that there is no more than one balancer
   * running in an HDFS is to create a file in the HDFS, writes the IP address
   * of the machine on which the balancer is running to the file, but did not
   * close the file until the balancer exits. 
   * This prevents the second balancer from running because it can not
   * creates the file while the first one is running.
   * 
   * This method checks if there is any running balancer and 
   * if no, mark yes if no.
   * Note that this is an atomic operation.
   * 
   * Return null if there is a running balancer; otherwise the output stream
   * to the newly created file.
   */
  private OutputStream checkAndMarkRunningBalancer() throws IOException {
    try {
      final DataOutputStream out = fs.create(BALANCER_ID_PATH);
      out.writeBytes(InetAddress.getLocalHost().getHostName());
      out.flush();
      return out;
    } catch(RemoteException e) {
      if(AlreadyBeingCreatedException.class.getName().equals(e.getClassName())){
        return null;
      } else {
        throw e;
      }
    }
  }

  /** Close the connection. */
  void close() {
    shouldRun = false;
    try {
      if (keyupdaterthread != null) {
        keyupdaterthread.interrupt();
      }
    } catch(Exception e) {
      LOG.warn("Exception shutting down access key updater thread", e);
    }

    // close the output file
    IOUtils.closeStream(out); 
    if (fs != null) {
      try {
        fs.delete(BALANCER_ID_PATH, true);
      } catch(IOException ioe) {
        LOG.warn("Failed to delete " + BALANCER_ID_PATH, ioe);
      }
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[namenodeAddress=" + namenodeAddress
        + ", id=" + blockpoolID
        + "]";
  }

  /**
   * Periodically updates access keys.
   */
  class BlockKeyUpdater implements Runnable {
    public void run() {
      while (shouldRun) {
        try {
          blockTokenSecretManager.setKeys(namenode.getBlockKeys());
        } catch (Exception e) {
          LOG.error("Failed to set keys", e);
        }
        try {
          Thread.sleep(keyUpdaterInterval);
        } catch (InterruptedException ie) {
        }
      }
    }
  }
}