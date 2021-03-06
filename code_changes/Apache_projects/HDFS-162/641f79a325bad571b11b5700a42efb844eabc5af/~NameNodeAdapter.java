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
package org.apache.hadoop.hdfs.server.namenode;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenSecretManager;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.MkdirOp;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager.Lease;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem.SafeModeInfo;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.HeartbeatResponse;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.ipc.StandbyException;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.test.GenericTestUtils;
import org.mockito.Mockito;

/**
 * This is a utility class to expose NameNode functionality for unit tests.
 */
public class NameNodeAdapter {
  /**
   * Get the namesystem from the namenode
   */
  public static FSNamesystem getNamesystem(NameNode namenode) {
    return namenode.getNamesystem();
  }

  /**
   * Get block locations within the specified range.
   */
  public static LocatedBlocks getBlockLocations(NameNode namenode,
      String src, long offset, long length) throws IOException {
    return namenode.getNamesystem().getBlockLocations(
        src, offset, length, false, true);
  }
  
  public static HdfsFileStatus getFileInfo(NameNode namenode, String src,
      boolean resolveLink) throws AccessControlException, UnresolvedLinkException,
        StandbyException {
    return namenode.getNamesystem().getFileInfo(src, resolveLink);
  }
  
  public static boolean mkdirs(NameNode namenode, String src,
      PermissionStatus permissions, boolean createParent)
      throws UnresolvedLinkException, IOException {
    return namenode.getNamesystem().mkdirs(src, permissions, createParent);
  }
  
  public static void saveNamespace(NameNode namenode)
      throws AccessControlException, IOException {
    namenode.getNamesystem().saveNamespace();
  }
  
  public static void enterSafeMode(NameNode namenode, boolean resourcesLow)
      throws IOException {
    namenode.getNamesystem().enterSafeMode(resourcesLow);
  }
  
  public static void leaveSafeMode(NameNode namenode, boolean checkForUpgrades)
      throws SafeModeException {
    namenode.getNamesystem().leaveSafeMode(checkForUpgrades);
  }
  
  public static void abortEditLogs(NameNode nn) {
    FSEditLog el = nn.getFSImage().getEditLog();
    el.abortCurrentLogSegment();
  }
  
  /**
   * Get the internal RPC server instance.
   * @return rpc server
   */
  public static Server getRpcServer(NameNode namenode) {
    return ((NameNodeRpcServer)namenode.getRpcServer()).clientRpcServer;
  }

  public static DelegationTokenSecretManager getDtSecretManager(
      final FSNamesystem ns) {
    return ns.getDelegationTokenSecretManager();
  }

  public static HeartbeatResponse sendHeartBeat(DatanodeRegistration nodeReg,
      DatanodeDescriptor dd, FSNamesystem namesystem) throws IOException {
    return namesystem.handleHeartbeat(nodeReg, dd.getCapacity(), 
        dd.getDfsUsed(), dd.getRemaining(), dd.getBlockPoolUsed(), 0, 0, 0);
  }

  public static boolean setReplication(final FSNamesystem ns,
      final String src, final short replication) throws IOException {
    return ns.setReplication(src, replication);
  }
  
  public static LeaseManager getLeaseManager(final FSNamesystem ns) {
    return ns.leaseManager;
  }

  /** Set the softLimit and hardLimit of client lease periods. */
  public static void setLeasePeriod(final FSNamesystem namesystem, long soft, long hard) {
    getLeaseManager(namesystem).setLeasePeriod(soft, hard);
    namesystem.leaseManager.triggerMonitorCheckNow();
  }

  public static String getLeaseHolderForPath(NameNode namenode, String path) {
    return namenode.getNamesystem().leaseManager.getLeaseByPath(path).getHolder();
  }

  /**
   * @return the timestamp of the last renewal of the given lease,
   *   or -1 in the case that the lease doesn't exist.
   */
  public static long getLeaseRenewalTime(NameNode nn, String path) {
    LeaseManager lm = nn.getNamesystem().leaseManager;
    Lease l = lm.getLeaseByPath(path);
    if (l == null) {
      return -1;
    }
    return l.getLastUpdate();
  }

  /**
   * Return the datanode descriptor for the given datanode.
   */
  public static DatanodeDescriptor getDatanode(final FSNamesystem ns,
      DatanodeID id) throws IOException {
    ns.readLock();
    try {
      return ns.getBlockManager().getDatanodeManager().getDatanode(id);
    } finally {
      ns.readUnlock();
    }
  }
  
  /**
   * Return the FSNamesystem stats
   */
  public static long[] getStats(final FSNamesystem fsn) {
    return fsn.getStats();
  }
  
  public static ReentrantReadWriteLock spyOnFsLock(FSNamesystem fsn) {
    ReentrantReadWriteLock spy = Mockito.spy(fsn.getFsLockForTests());
    fsn.setFsLockForTests(spy);
    return spy;
  }

  public static FSImage spyOnFsImage(NameNode nn1) {
    FSImage spy = Mockito.spy(nn1.getNamesystem().dir.fsImage);
    nn1.getNamesystem().dir.fsImage = spy;
    return spy;
  }
  
  public static String getMkdirOpPath(FSEditLogOp op) {
    if (op.opCode == FSEditLogOpCodes.OP_MKDIR) {
      return ((MkdirOp) op).path;
    } else {
      return null;
    }
  }
  
  /**
   * @return the number of blocks marked safe by safemode, or -1
   * if safemode is not running.
   */
  public static int getSafeModeSafeBlocks(NameNode nn) {
    SafeModeInfo smi = nn.getNamesystem().getSafeModeInfoForTests();
    if (smi == null) {
      return -1;
    }
    return smi.blockSafe;
  }
  
  /**
   * @return true if safemode is not running, or if safemode has already
   * initialized the replication queues
   */
  public static boolean safeModeInitializedReplQueues(NameNode nn) {
    SafeModeInfo smi = nn.getNamesystem().getSafeModeInfoForTests();
    if (smi == null) {
      return true;
    }
    return smi.initializedReplQueues;
  }
}
