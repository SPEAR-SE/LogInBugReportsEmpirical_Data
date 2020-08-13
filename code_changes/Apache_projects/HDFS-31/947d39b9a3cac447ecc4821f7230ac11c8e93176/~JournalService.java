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
package org.apache.hadoop.hdfs.server.journalservice;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.NameNodeProxies;
import org.apache.hadoop.hdfs.protocol.UnregisteredNodeException;
import org.apache.hadoop.hdfs.protocol.proto.JournalProtocolProtos.JournalProtocolService;
import org.apache.hadoop.hdfs.protocol.proto.JournalSyncProtocolProtos.JournalSyncProtocolService;
import org.apache.hadoop.hdfs.protocolPB.JournalProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.JournalProtocolServerSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.JournalSyncProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.JournalSyncProtocolServerSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.JournalSyncProtocolTranslatorPB;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NamenodeRole;
import org.apache.hadoop.hdfs.server.protocol.FenceResponse;
import org.apache.hadoop.hdfs.server.protocol.FencedException;
import org.apache.hadoop.hdfs.server.protocol.JournalInfo;
import org.apache.hadoop.hdfs.server.protocol.JournalServiceProtocols;
import org.apache.hadoop.hdfs.server.protocol.JournalSyncProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLogManifest;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.BlockingService;

/**
 * This class interfaces with the namenode using {@link JournalProtocol} over
 * RPC. It has two modes: <br>
 * <ul>
 * <li>Mode where an RPC.Server is provided from outside, on which it
 * {@link JournalProtocol} is registered. The RPC.Server stop and start is
 * managed outside by the application.</li>
 * <li>Stand alone mode where an RPC.Server is started and managed by the
 * JournalListener.</li>
 * </ul>
 * 
 * The received journal operations are sent to a listener over callbacks. The
 * listener implementation can handle the callbacks based on the application
 * requirement.
 */
public class JournalService implements JournalServiceProtocols {
  public static final Log LOG = LogFactory.getLog(JournalService.class.getName());

  private final InetSocketAddress nnAddress;
  private NamenodeRegistration registration;
  private final NamenodeProtocol namenode;
  private final StateHandler stateHandler = new StateHandler();
  private final RPC.Server rpcServer;
  private long epoch = 0;
  private String fencerInfo;

  private final Journal journal;
  private final JournalListener listener;

  enum State {
    /** The service is initialized and ready to start. */
    INIT(false, false),
    /**
     * RPC server is started.
     * The service is ready to receive requests from namenode.
     */
    STARTED(false, false),
    /** The service is fenced by a namenode and waiting for roll. */
    WAITING_FOR_ROLL(false, true),
    /**
     * The existing log is syncing with another source
     * and it accepts journal from Namenode.
     */
    SYNCING(true, true),
    /** The existing log is in sync and it accepts journal from Namenode. */
    IN_SYNC(true, true),
    /** The service is stopped. */
    STOPPED(false, false);

    final boolean isJournalAllowed;
    final boolean isStartLogSegmentAllowed;
    
    State(boolean isJournalAllowed, boolean isStartLogSegmentAllowed) {
      this.isJournalAllowed = isJournalAllowed;
      this.isStartLogSegmentAllowed = isStartLogSegmentAllowed;
    }
  }
  
  static class StateHandler {
    State current = State.INIT;
    
    synchronized void start() {
      if (current != State.INIT) {
        throw new IllegalStateException("Service cannot be started in "
            + current + " state.");
      }
      current = State.STARTED;
    }

    synchronized void waitForRoll() {
      if (current != State.STARTED) {
        throw new IllegalStateException("Cannot wait-for-roll in " + current
            + " state.");
      }
      current = State.WAITING_FOR_ROLL;
    }

    synchronized void startLogSegment() {
      if (current == State.WAITING_FOR_ROLL) {
        current = State.SYNCING;
      }
    }

    synchronized void isStartLogSegmentAllowed() throws IOException {
      if (!current.isStartLogSegmentAllowed) {
        throw new IOException("Cannot start log segment in " + current
            + " state.");
      }
    }

    synchronized void isJournalAllowed() throws IOException {
      if (!current.isJournalAllowed) {
        throw new IOException("Cannot journal in " + current + " state.");
      }
    }

    synchronized boolean isStopped() {
      if (current == State.STOPPED) {
        LOG.warn("Ignore stop request since the service is in " + current
            + " state.");
        return true;
      }
      current = State.STOPPED;
      return false;
    }
  }
  
  /**
   * Constructor to create {@link JournalService} where an RPC server is
   * created by this service.
   * @param conf Configuration
   * @param nnAddr host:port for the active Namenode's RPC server
   * @param serverAddress address to start RPC server to receive
   *          {@link JournalProtocol} requests. This can be null, if
   *          {@code server} is a valid server that is managed out side this
   *          service.
   * @param listener call-back interface to listen to journal activities
   * @throws IOException on error
   */
  JournalService(Configuration conf, InetSocketAddress nnAddr,
      InetSocketAddress serverAddress, JournalListener listener)
      throws IOException {
    this.nnAddress = nnAddr;
    this.listener = listener;
    this.journal = new Journal(conf);
    this.namenode = NameNodeProxies.createNonHAProxy(conf, nnAddr,
        NamenodeProtocol.class, UserGroupInformation.getCurrentUser(), true)
        .getProxy();
    this.rpcServer = createRpcServer(conf, serverAddress, this);
  }
  
  Journal getJournal() {
    return journal;
  }

  synchronized NamenodeRegistration getRegistration() {
    if (!journal.isFormatted()) {
      throw new IllegalStateException("Journal is not formatted.");
    }

    if (registration == null) {
      registration = new NamenodeRegistration(
          NetUtils.getHostPortString(rpcServer.getListenerAddress()), "",
          journal.getStorage(), NamenodeRole.BACKUP);
    }
    return registration;
  }

  /**
   * Start the service.
   */
  public void start() {
    stateHandler.start();

    // Start the RPC server
    LOG.info("Starting journal service rpc server");
    rpcServer.start();

    for (boolean registered = false, handshakeComplete = false;;) {
      try {
        // Perform handshake
        if (!handshakeComplete) {
          handshake();
          handshakeComplete = true;
          LOG.info("handshake completed");
        }

        // Register with the namenode
        if (!registered) {
          registerWithNamenode();
          registered = true;
          LOG.info("Registration completed");
          break;
        }
      } catch (IOException ioe) {
        LOG.warn("Encountered exception ", ioe);
      } catch (Exception e) {
        LOG.warn("Encountered exception ", e);
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException ie) {
        LOG.warn("Encountered exception ", ie);
      }
    }

    stateHandler.waitForRoll();
    try {
      namenode.rollEditLog();
    } catch (IOException e) {
      LOG.warn("Encountered exception ", e);
    }
  }

  /**
   * Stop the service. For application with RPC Server managed outside, the
   * RPC Server must be stopped the application.
   */
  public void stop() throws IOException {
    if (!stateHandler.isStopped()) {
      rpcServer.stop();
      journal.close();
    }
  }

  @Override
  public void journal(JournalInfo journalInfo, long epoch, long firstTxnId,
      int numTxns, byte[] records) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Received journal " + firstTxnId + " " + numTxns);
    }
    stateHandler.isJournalAllowed();
    verify(epoch, journalInfo);
    listener.journal(this, firstTxnId, numTxns, records);
  }

  @Override
  public void startLogSegment(JournalInfo journalInfo, long epoch, long txid)
      throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Received startLogSegment " + txid);
    }
    stateHandler.isStartLogSegmentAllowed();
    verify(epoch, journalInfo);
    listener.startLogSegment(this, txid);
    stateHandler.startLogSegment();
  }

  @Override
  public FenceResponse fence(JournalInfo journalInfo, long epoch,
      String fencerInfo) throws IOException {
    LOG.info("Fenced by " + fencerInfo + " with epoch " + epoch);
   
    // It is the first fence if the journal is not formatted, 
    if (!journal.isFormatted()) {
      journal.format(journalInfo.getNamespaceId(), journalInfo.getClusterId());
    }
    verifyFence(epoch, fencerInfo);
    verify(journalInfo.getNamespaceId(), journalInfo.getClusterId());
    long previousEpoch = epoch;
    this.epoch = epoch;
    this.fencerInfo = fencerInfo;
    
    // TODO:HDFS-3092 set lastTransId and inSync
    return new FenceResponse(previousEpoch, 0, false);
  }

  @Override
  public RemoteEditLogManifest getEditLogManifest(JournalInfo journalInfo,
      long sinceTxId) throws IOException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Received getEditLogManifest " + sinceTxId);
    }
    if (!journal.isFormatted()) {
      throw new IOException("This journal service is not formatted.");
    }
    verify(journalInfo.getNamespaceId(), journalInfo.getClusterId());

    //journal has only one storage directory
    return journal.getRemoteEditLogs(sinceTxId);
  }

  /** Create an RPC server. */
  private static RPC.Server createRpcServer(Configuration conf,
      InetSocketAddress address, JournalServiceProtocols impl) throws IOException {
    
    RPC.setProtocolEngine(conf, JournalProtocolPB.class,
        ProtobufRpcEngine.class);
    JournalProtocolServerSideTranslatorPB xlator = 
        new JournalProtocolServerSideTranslatorPB(impl);
    BlockingService service = 
        JournalProtocolService.newReflectiveBlockingService(xlator);
    
    JournalSyncProtocolServerSideTranslatorPB syncXlator = 
        new JournalSyncProtocolServerSideTranslatorPB(impl);   
    BlockingService syncService = 
        JournalSyncProtocolService.newReflectiveBlockingService(syncXlator);
    
    RPC.Server rpcServer = RPC.getServer(JournalProtocolPB.class, service,
        address.getHostName(), address.getPort(), 1, false, conf, null);
    DFSUtil.addPBProtocol(conf, JournalSyncProtocolPB.class, syncService,
        rpcServer);
    
    return rpcServer;
  }
  
  private void verifyEpoch(long e) throws FencedException {
    if (epoch != e) {
      String errorMsg = "Epoch " + e + " is not valid. "
          + "Resource has already been fenced by " + fencerInfo
          + " with epoch " + epoch;
      LOG.warn(errorMsg);
      throw new FencedException(errorMsg);
    }
  }
  
  private void verifyFence(long e, String fencer) throws FencedException {
    if (e <= epoch) {
      String errorMsg = "Epoch " + e + " from fencer " + fencer
          + " is not valid. " + "Resource has already been fenced by "
          + fencerInfo + " with epoch " + epoch;
      LOG.warn(errorMsg);
      throw new FencedException(errorMsg);
    }
  }
  
  /** 
   * Verifies a journal request
   */
  private void verify(int nsid, String clusid) throws IOException {
    String errorMsg = null;
    final NamenodeRegistration reg = getRegistration(); 

    if (nsid != reg.getNamespaceID()) {
      errorMsg = "Invalid namespaceID in journal request - expected "
          + reg.getNamespaceID() + " actual " + nsid;
      LOG.warn(errorMsg);
      throw new UnregisteredNodeException(errorMsg);
    }
    if ((clusid == null)
        || (!clusid.equals(reg.getClusterID()))) {
      errorMsg = "Invalid clusterId in journal request - incoming "
          + clusid + " expected " + reg.getClusterID();
      LOG.warn(errorMsg);
      throw new UnregisteredNodeException(errorMsg);
    }
  }
  
  /** 
   * Verifies a journal request
   */
  private void verify(long e, JournalInfo journalInfo) throws IOException {
    verifyEpoch(e);
    verify(journalInfo.getNamespaceId(), journalInfo.getClusterId());
  }
  
  /**
   * Register this service with the active namenode.
   */
  private void registerWithNamenode() throws IOException {
    NamenodeRegistration nnReg = namenode.register(getRegistration());
    String msg = null;
    if(nnReg == null) { // consider as a rejection
      msg = "Registration rejected by " + nnAddress;
    } else if(!nnReg.isRole(NamenodeRole.NAMENODE)) {
      msg = " Name-node " + nnAddress + " is not active";
    }
    if(msg != null) {
      LOG.error(msg);
      throw new IOException(msg); // stop the node
    }
  }
  
  private void handshake() throws IOException {
    NamespaceInfo nsInfo = namenode.versionRequest();
    listener.verifyVersion(this, nsInfo);

    // If this is the first initialization of journal service, then storage
    // directory will be setup. Otherwise, nsid and clusterid has to match with
    // the info saved in the edits dir.
    if (!journal.isFormatted()) {
      journal.format(nsInfo.getNamespaceID(), nsInfo.getClusterID());
    } else {
      verify(nsInfo.getNamespaceID(), nsInfo.getClusterID());
    }
  }

  @VisibleForTesting
  long getEpoch() {
    return epoch;
  }
  
  public static JournalSyncProtocol createProxyWithJournalSyncProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi)
      throws IOException {
    RPC.setProtocolEngine(conf, JournalSyncProtocolPB.class,
        ProtobufRpcEngine.class);
    Object proxy = RPC.getProxy(JournalSyncProtocolPB.class,
        RPC.getProtocolVersion(JournalSyncProtocolPB.class), address, ugi,
        conf, NetUtils.getDefaultSocketFactory(conf), 30000);

    return new JournalSyncProtocolTranslatorPB((JournalSyncProtocolPB) proxy);
  }
}
