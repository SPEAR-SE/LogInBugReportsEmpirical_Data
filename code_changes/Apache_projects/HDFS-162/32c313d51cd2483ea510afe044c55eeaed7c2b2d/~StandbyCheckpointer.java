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
package org.apache.hadoop.hdfs.server.namenode.ha;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PrivilegedAction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ha.ServiceFailedException;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HAUtil;
import org.apache.hadoop.hdfs.server.namenode.CheckpointConf;
import org.apache.hadoop.hdfs.server.namenode.FSImage;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.SaveNamespaceCancelledException;
import org.apache.hadoop.hdfs.server.namenode.TransferFsImage;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import static org.apache.hadoop.hdfs.server.common.Util.now;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * Thread which runs inside the NN when it's in Standby state,
 * periodically waking up to take a checkpoint of the namespace.
 * When it takes a checkpoint, it saves it to its local
 * storage and then uploads it to the remote NameNode.
 */
@InterfaceAudience.Private
public class StandbyCheckpointer {
  private static final Log LOG = LogFactory.getLog(StandbyCheckpointer.class);
  private static final long PREVENT_AFTER_CANCEL_MS = 2*60*1000L;
  private final CheckpointConf checkpointConf;
  private final FSNamesystem namesystem;
  private long lastCheckpointTime;
  private final CheckpointerThread thread;
  private String activeNNAddress;
  private InetSocketAddress myNNAddress;
  
  // Keep track of how many checkpoints were canceled.
  // This is for use in tests.
  private static int canceledCount = 0;
  
  public StandbyCheckpointer(Configuration conf, FSNamesystem ns) {
    this.namesystem = ns;
    this.checkpointConf = new CheckpointConf(conf); 
    this.thread = new CheckpointerThread();

    setNameNodeAddresses(conf);
  }

  /**
   * Determine the address of the NN we are checkpointing
   * as well as our own HTTP address from the configuration.
   */
  private void setNameNodeAddresses(Configuration conf) {
    // Look up our own address.
    String myAddrString = DFSUtil.getInfoServer(null, conf, true);

    // Look up the active node's address
    Configuration confForActive = HAUtil.getConfForOtherNode(conf);
    activeNNAddress = DFSUtil.getInfoServer(null, confForActive, true);
    
    
    // Sanity-check.
    Preconditions.checkArgument(checkAddress(activeNNAddress),
        "Bad address for active NN: %s", activeNNAddress);
    Preconditions.checkArgument(checkAddress(myAddrString),
        "Bad address for standby NN: %s", myAddrString);
    myNNAddress = NetUtils.createSocketAddr(myAddrString);
  }
  
  /**
   * Ensure that the given address is valid and has a port
   * specified.
   */
  private boolean checkAddress(String addrStr) {
    InetSocketAddress addr = NetUtils.createSocketAddr(addrStr);
    return addr.getPort() != 0;
  }

  public void start() {
    LOG.info("Starting standby checkpoint thread...\n" +
        "Checkpointing active NN at " + activeNNAddress + "\n" +
        "Serving checkpoints at " + myNNAddress);
    thread.start();
  }
  
  public void stop() throws IOException {
    thread.setShouldRun(false);
    thread.interrupt();
    try {
      thread.join();
    } catch (InterruptedException e) {
      LOG.warn("Edit log tailer thread exited with an exception");
      throw new IOException(e);
    }
  }

  private void doCheckpoint() throws InterruptedException, IOException {
    long txid;
    
    namesystem.writeLockInterruptibly();
    try {
      assert namesystem.getEditLog().isOpenForRead() :
        "Standby Checkpointer should only attempt a checkpoint when " +
        "NN is in standby mode, but the edit logs are in an unexpected state";
      
      FSImage img = namesystem.getFSImage();
      
      long prevCheckpointTxId = img.getStorage().getMostRecentCheckpointTxId();
      long thisCheckpointTxId = img.getLastAppliedOrWrittenTxId();
      assert thisCheckpointTxId >= prevCheckpointTxId;
      if (thisCheckpointTxId == prevCheckpointTxId) {
        LOG.info("A checkpoint was triggered but the Standby Node has not " +
            "received any transactions since the last checkpoint at txid " +
            thisCheckpointTxId + ". Skipping...");
        return;
      }
      
      img.saveNamespace(namesystem);
      txid = img.getStorage().getMostRecentCheckpointTxId();
      assert txid == thisCheckpointTxId : "expected to save checkpoint at txid=" +
        thisCheckpointTxId + " but instead saved at txid=" + txid;
    } finally {
      namesystem.writeUnlock();
    }
    
    // Upload the saved checkpoint back to the active
    TransferFsImage.uploadImageFromStorage(
        activeNNAddress, myNNAddress,
        namesystem.getFSImage().getStorage(), txid);
  }
  
  /**
   * Cancel any checkpoint that's currently being made,
   * and prevent any new checkpoints from starting for the next
   * minute or so.
   */
  public void cancelAndPreventCheckpoints() throws ServiceFailedException {
    try {
      thread.preventCheckpointsFor(PREVENT_AFTER_CANCEL_MS);
      // TODO: there is a really narrow race here if we are just
      // about to start a checkpoint - this won't cancel it!
      namesystem.getFSImage().cancelSaveNamespace(
          "About to exit standby state");
    } catch (InterruptedException e) {
      throw new ServiceFailedException(
          "Interrupted while trying to cancel checkpoint");
    }
  }
  
  @VisibleForTesting
  static int getCanceledCount() {
    return canceledCount;
  }

  private long countUncheckpointedTxns() {
    FSImage img = namesystem.getFSImage();
    return img.getLastAppliedOrWrittenTxId() -
      img.getStorage().getMostRecentCheckpointTxId();
  }

  private class CheckpointerThread extends Thread {
    private volatile boolean shouldRun = true;
    private volatile long preventCheckpointsUntil = 0;

    private CheckpointerThread() {
      super("Standby State Checkpointer");
    }
    
    private void setShouldRun(boolean shouldRun) {
      this.shouldRun = shouldRun;
    }

    @Override
    public void run() {
      // We have to make sure we're logged in as far as JAAS
      // is concerned, in order to use kerberized SSL properly.
      // This code copied from SecondaryNameNode - TODO: refactor
      // to a utility function.
      if (UserGroupInformation.isSecurityEnabled()) {
        UserGroupInformation ugi = null;
        try { 
          ugi = UserGroupInformation.getLoginUser();
        } catch (IOException e) {
          LOG.error("Exception while getting login user", e);
          Runtime.getRuntime().exit(-1);
        }
        ugi.doAs(new PrivilegedAction<Object>() {
          @Override
          public Object run() {
            doWork();
            return null;
          }
        });
      } else {
        doWork();
      }
    }

    /**
     * Prevent checkpoints from occurring for some time period
     * in the future. This is used when preparing to enter active
     * mode. We need to not only cancel any concurrent checkpoint,
     * but also prevent any checkpoints from racing to start just
     * after the cancel call.
     * 
     * @param delayMs the number of MS for which checkpoints will be
     * prevented
     */
    private void preventCheckpointsFor(long delayMs) {
      preventCheckpointsUntil = now() + delayMs;
    }

    private void doWork() {
      // Reset checkpoint time so that we don't always checkpoint
      // on startup.
      lastCheckpointTime = now();
      while (shouldRun) {
        try {
          Thread.sleep(1000 * checkpointConf.getCheckPeriod());
        } catch (InterruptedException ie) {
        }
        if (!shouldRun) {
          break;
        }
        try {
          // We may have lost our ticket since last checkpoint, log in again, just in case
          if (UserGroupInformation.isSecurityEnabled()) {
            UserGroupInformation.getCurrentUser().reloginFromKeytab();
          }
          
          long now = now();
          long uncheckpointed = countUncheckpointedTxns();
          long secsSinceLast = (now - lastCheckpointTime)/1000;
          
          boolean needCheckpoint = false;
          if (uncheckpointed >= checkpointConf.getTxnCount()) {
            LOG.info("Triggering checkpoint because there have been " + 
                uncheckpointed + " txns since the last checkpoint, which " +
                "exceeds the configured threshold " +
                checkpointConf.getTxnCount());
            needCheckpoint = true;
          } else if (secsSinceLast >= checkpointConf.getPeriod()) {
            LOG.info("Triggering checkpoint because it has been " +
                secsSinceLast + " seconds since the last checkpoint, which " +
                "exceeds the configured interval " + checkpointConf.getPeriod());
            needCheckpoint = true;
          }
          if (needCheckpoint && now < preventCheckpointsUntil) {
            LOG.info("But skipping this checkpoint since we are about to failover!");
            canceledCount++;
          } else if (needCheckpoint) {
            doCheckpoint();
            lastCheckpointTime = now;
          }
        } catch (SaveNamespaceCancelledException ce) {
          LOG.info("Checkpoint was cancelled: " + ce.getMessage());
          canceledCount++;
        } catch (InterruptedException ie) {
          // Probably requested shutdown.
          continue;
        } catch (Throwable t) {
          LOG.error("Exception in doCheckpoint", t);
        }
      }
    }
  }
}
