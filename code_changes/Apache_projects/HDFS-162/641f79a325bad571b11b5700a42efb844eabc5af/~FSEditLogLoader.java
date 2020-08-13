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

import static org.apache.hadoop.hdfs.server.common.Util.now;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumMap;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LayoutVersion;
import org.apache.hadoop.hdfs.protocol.LayoutVersion.Feature;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.AddCloseOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.CancelDelegationTokenOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.ClearNSQuotaOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.ConcatDeleteOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.DeleteOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.GetDelegationTokenOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.MkdirOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.ReassignLeaseOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.RenameOldOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.RenameOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.RenewDelegationTokenOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetGenstampOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetNSQuotaOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetOwnerOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetPermissionsOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetQuotaOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SetReplicationOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.SymlinkOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.TimesOp;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp.UpdateMasterKeyOp;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager.Lease;
import org.apache.hadoop.hdfs.util.Holder;
import org.apache.hadoop.io.IOUtils;

import com.google.common.base.Joiner;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public class FSEditLogLoader {
  private final FSNamesystem fsNamesys;
  private long maxGenStamp = 0;

  public FSEditLogLoader(FSNamesystem fsNamesys) {
    this.fsNamesys = fsNamesys;
  }
  
  /**
   * Load an edit log, and apply the changes to the in-memory structure
   * This is where we apply edits that we've been writing to disk all
   * along.
   */
  long loadFSEdits(EditLogInputStream edits, long expectedStartingTxId)
      throws IOException {
    long numEdits = 0;
    int logVersion = edits.getVersion();

    fsNamesys.writeLock();
    try {
      long startTime = now();
      numEdits = loadEditRecords(logVersion, edits, false, 
                                 expectedStartingTxId);
      FSImage.LOG.info("Edits file " + edits.getName() 
          + " of size " + edits.length() + " edits # " + numEdits 
          + " loaded in " + (now()-startTime)/1000 + " seconds.");
    } finally {
      fsNamesys.setBlockTotal();
      
      // Delay the notification of genstamp updates until after
      // setBlockTotal() above. Otherwise, we will mark blocks
      // as "safe" before they've been incorporated in the expected
      // totalBlocks and threshold for SafeMode -- triggering an
      // assertion failure and/or exiting safemode too early!
      fsNamesys.notifyGenStampUpdate(maxGenStamp);
      
      edits.close();
      fsNamesys.writeUnlock();
    }
    
    return numEdits;
  }

  long loadEditRecords(int logVersion, EditLogInputStream in, boolean closeOnExit,
                      long expectedStartingTxId)
      throws IOException, EditLogInputException {
    FSDirectory fsDir = fsNamesys.dir;
    long numEdits = 0;

    EnumMap<FSEditLogOpCodes, Holder<Integer>> opCounts =
      new EnumMap<FSEditLogOpCodes, Holder<Integer>>(FSEditLogOpCodes.class);

    fsNamesys.writeLock();
    fsDir.writeLock();

    long recentOpcodeOffsets[] = new long[4];
    Arrays.fill(recentOpcodeOffsets, -1);

    try {
      long txId = expectedStartingTxId - 1;

      try {
        while (true) {
          FSEditLogOp op;
          try {
            if ((op = in.readOp()) == null) {
              break;
            }
          } catch (IOException ioe) {
            String errorMessage = formatEditLogReplayError(in, recentOpcodeOffsets);
            FSImage.LOG.error(errorMessage);
            throw new EditLogInputException(errorMessage,
                ioe, numEdits);
          }
          recentOpcodeOffsets[(int)(numEdits % recentOpcodeOffsets.length)] =
            in.getPosition();
          if (LayoutVersion.supports(Feature.STORED_TXIDS, logVersion)) {
            long thisTxId = op.txid;
            if (thisTxId != txId + 1) {
              throw new IOException("Expected transaction ID " +
                  (txId + 1) + " but got " + thisTxId);
            }
            txId = thisTxId;
          }

          incrOpCount(op.opCode, opCounts);
          try {
            applyEditLogOp(op, fsDir, logVersion);
          } catch (Throwable t) {
            // Catch Throwable because in the case of a truly corrupt edits log, any
            // sort of error might be thrown (NumberFormat, NullPointer, EOF, etc.)
            String errorMessage = formatEditLogReplayError(in, recentOpcodeOffsets);
            FSImage.LOG.error(errorMessage);
            throw new IOException(errorMessage, t);
          }
          numEdits++;
        }
      } catch (IOException ex) {
        check203UpgradeFailure(logVersion, ex);
      } finally {
        if(closeOnExit)
          in.close();
      }
    } finally {
      fsDir.writeUnlock();
      fsNamesys.writeUnlock();
      if (FSImage.LOG.isDebugEnabled()) {
        dumpOpCounts(opCounts);
      }
    }
    return numEdits;
  }
  
  @SuppressWarnings("deprecation")
  private void applyEditLogOp(FSEditLogOp op, FSDirectory fsDir,
      int logVersion) throws IOException {
    switch (op.opCode) {
    case OP_ADD: {
      AddCloseOp addCloseOp = (AddCloseOp)op;

      // See if the file already exists (persistBlocks call)
      INodeFile oldFile = getINodeFile(fsDir, addCloseOp.path);
      if (oldFile == null) { // this is OP_ADD on a new file
        // versions > 0 support per file replication
        // get name and replication
        final short replication  = fsNamesys.getBlockManager(
            ).adjustReplication(addCloseOp.replication);
        PermissionStatus permissions = fsNamesys.getUpgradePermission();
        if (addCloseOp.permissions != null) {
          permissions = addCloseOp.permissions;
        }
        long blockSize = addCloseOp.blockSize;
        
        if (FSNamesystem.LOG.isDebugEnabled()) {
          FSNamesystem.LOG.debug(op.opCode + ": " + addCloseOp.path +
              " numblocks : " + addCloseOp.blocks.length +
              " clientHolder " + addCloseOp.clientName +
              " clientMachine " + addCloseOp.clientMachine);
        }

        // Older versions of HDFS does not store the block size in inode.
        // If the file has more than one block, use the size of the
        // first block as the blocksize. Otherwise use the default
        // block size.
        if (-8 <= logVersion && blockSize == 0) {
          if (addCloseOp.blocks.length > 1) {
            blockSize = addCloseOp.blocks[0].getNumBytes();
          } else {
            long first = ((addCloseOp.blocks.length == 1)?
                addCloseOp.blocks[0].getNumBytes(): 0);
            blockSize = Math.max(fsNamesys.getDefaultBlockSize(), first);
          }
        }

        // TODO: We should do away with this add-then-replace dance.

        // add to the file tree
        INodeFile node = (INodeFile)fsDir.unprotectedAddFile(
            addCloseOp.path, permissions,
            replication, addCloseOp.mtime,
            addCloseOp.atime, blockSize);

        fsNamesys.prepareFileForWrite(addCloseOp.path, node,
            addCloseOp.clientName, addCloseOp.clientMachine, null);
      } else { // This is OP_ADD on an existing file
        if (!oldFile.isUnderConstruction()) {
          // This is a call to append() on an already-closed file.
          fsNamesys.prepareFileForWrite(addCloseOp.path, oldFile,
              addCloseOp.clientName, addCloseOp.clientMachine, null);
          oldFile = getINodeFile(fsDir, addCloseOp.path);
        }
        
        updateBlocks(fsDir, addCloseOp, oldFile);
      }
      break;
    }
    case OP_CLOSE: {
      AddCloseOp addCloseOp = (AddCloseOp)op;
      
      INodeFile oldFile = getINodeFile(fsDir, addCloseOp.path);
      if (oldFile == null) {
        throw new IOException("Operation trying to close non-existent file " +
            addCloseOp.path);
      }
      
      // Update in-memory data structures
      updateBlocks(fsDir, addCloseOp, oldFile);

      // Now close the file
      INodeFileUnderConstruction ucFile = (INodeFileUnderConstruction) oldFile;
      // TODO: we could use removeLease(holder, path) here, but OP_CLOSE
      // doesn't seem to serialize the holder... unclear why!
      fsNamesys.leaseManager.removeLeaseWithPrefixPath(addCloseOp.path);
      INodeFile newFile = ucFile.convertToInodeFile();
      fsDir.replaceNode(addCloseOp.path, ucFile, newFile);
      break;
    }
    case OP_SET_REPLICATION: {
      SetReplicationOp setReplicationOp = (SetReplicationOp)op;
      short replication = fsNamesys.getBlockManager().adjustReplication(
          setReplicationOp.replication);
      fsDir.unprotectedSetReplication(setReplicationOp.path,
                                      replication, null);
      break;
    }
    case OP_CONCAT_DELETE: {
      ConcatDeleteOp concatDeleteOp = (ConcatDeleteOp)op;
      fsDir.unprotectedConcat(concatDeleteOp.trg, concatDeleteOp.srcs,
          concatDeleteOp.timestamp);
      break;
    }
    case OP_RENAME_OLD: {
      RenameOldOp renameOp = (RenameOldOp)op;
      HdfsFileStatus dinfo = fsDir.getFileInfo(renameOp.dst, false);
      fsDir.unprotectedRenameTo(renameOp.src, renameOp.dst,
                                renameOp.timestamp);
      fsNamesys.unprotectedChangeLease(renameOp.src, renameOp.dst, dinfo);
      break;
    }
    case OP_DELETE: {
      DeleteOp deleteOp = (DeleteOp)op;
      fsDir.unprotectedDelete(deleteOp.path, deleteOp.timestamp);
      break;
    }
    case OP_MKDIR: {
      MkdirOp mkdirOp = (MkdirOp)op;
      PermissionStatus permissions = fsNamesys.getUpgradePermission();
      if (mkdirOp.permissions != null) {
        permissions = mkdirOp.permissions;
      }

      fsDir.unprotectedMkdir(mkdirOp.path, permissions,
                             mkdirOp.timestamp);
      break;
    }
    case OP_SET_GENSTAMP: {
      SetGenstampOp setGenstampOp = (SetGenstampOp)op;
      fsNamesys.setGenerationStamp(setGenstampOp.genStamp);
      break;
    }
    case OP_SET_PERMISSIONS: {
      SetPermissionsOp setPermissionsOp = (SetPermissionsOp)op;
      fsDir.unprotectedSetPermission(setPermissionsOp.src,
                                     setPermissionsOp.permissions);
      break;
    }
    case OP_SET_OWNER: {
      SetOwnerOp setOwnerOp = (SetOwnerOp)op;
      fsDir.unprotectedSetOwner(setOwnerOp.src, setOwnerOp.username,
                                setOwnerOp.groupname);
      break;
    }
    case OP_SET_NS_QUOTA: {
      SetNSQuotaOp setNSQuotaOp = (SetNSQuotaOp)op;
      fsDir.unprotectedSetQuota(setNSQuotaOp.src,
                                setNSQuotaOp.nsQuota,
                                HdfsConstants.QUOTA_DONT_SET);
      break;
    }
    case OP_CLEAR_NS_QUOTA: {
      ClearNSQuotaOp clearNSQuotaOp = (ClearNSQuotaOp)op;
      fsDir.unprotectedSetQuota(clearNSQuotaOp.src,
                                HdfsConstants.QUOTA_RESET,
                                HdfsConstants.QUOTA_DONT_SET);
      break;
    }

    case OP_SET_QUOTA:
      SetQuotaOp setQuotaOp = (SetQuotaOp)op;
      fsDir.unprotectedSetQuota(setQuotaOp.src,
                                setQuotaOp.nsQuota,
                                setQuotaOp.dsQuota);
      break;

    case OP_TIMES: {
      TimesOp timesOp = (TimesOp)op;

      fsDir.unprotectedSetTimes(timesOp.path,
                                timesOp.mtime,
                                timesOp.atime, true);
      break;
    }
    case OP_SYMLINK: {
      SymlinkOp symlinkOp = (SymlinkOp)op;
      fsDir.unprotectedSymlink(symlinkOp.path, symlinkOp.value,
                               symlinkOp.mtime, symlinkOp.atime,
                               symlinkOp.permissionStatus);
      break;
    }
    case OP_RENAME: {
      RenameOp renameOp = (RenameOp)op;

      HdfsFileStatus dinfo = fsDir.getFileInfo(renameOp.dst, false);
      fsDir.unprotectedRenameTo(renameOp.src, renameOp.dst,
                                renameOp.timestamp, renameOp.options);
      fsNamesys.unprotectedChangeLease(renameOp.src, renameOp.dst, dinfo);
      break;
    }
    case OP_GET_DELEGATION_TOKEN: {
      GetDelegationTokenOp getDelegationTokenOp
        = (GetDelegationTokenOp)op;

      fsNamesys.getDelegationTokenSecretManager()
        .addPersistedDelegationToken(getDelegationTokenOp.token,
                                     getDelegationTokenOp.expiryTime);
      break;
    }
    case OP_RENEW_DELEGATION_TOKEN: {
      RenewDelegationTokenOp renewDelegationTokenOp
        = (RenewDelegationTokenOp)op;
      fsNamesys.getDelegationTokenSecretManager()
        .updatePersistedTokenRenewal(renewDelegationTokenOp.token,
                                     renewDelegationTokenOp.expiryTime);
      break;
    }
    case OP_CANCEL_DELEGATION_TOKEN: {
      CancelDelegationTokenOp cancelDelegationTokenOp
        = (CancelDelegationTokenOp)op;
      fsNamesys.getDelegationTokenSecretManager()
          .updatePersistedTokenCancellation(
              cancelDelegationTokenOp.token);
      break;
    }
    case OP_UPDATE_MASTER_KEY: {
      UpdateMasterKeyOp updateMasterKeyOp = (UpdateMasterKeyOp)op;
      fsNamesys.getDelegationTokenSecretManager()
        .updatePersistedMasterKey(updateMasterKeyOp.key);
      break;
    }
    case OP_REASSIGN_LEASE: {
      ReassignLeaseOp reassignLeaseOp = (ReassignLeaseOp)op;

      Lease lease = fsNamesys.leaseManager.getLease(
          reassignLeaseOp.leaseHolder);
      INodeFileUnderConstruction pendingFile =
          (INodeFileUnderConstruction) fsDir.getFileINode(
              reassignLeaseOp.path);
      fsNamesys.reassignLeaseInternal(lease,
          reassignLeaseOp.path, reassignLeaseOp.newHolder, pendingFile);
      break;
    }
    case OP_START_LOG_SEGMENT:
    case OP_END_LOG_SEGMENT: {
      // no data in here currently.
      break;
    }
    case OP_DATANODE_ADD:
    case OP_DATANODE_REMOVE:
      break;
    default:
      throw new IOException("Invalid operation read " + op.opCode);
    }
  }
  
  private static String formatEditLogReplayError(EditLogInputStream in,
      long recentOpcodeOffsets[]) {
    StringBuilder sb = new StringBuilder();
    sb.append("Error replaying edit log at offset " + in.getPosition());
    if (recentOpcodeOffsets[0] != -1) {
      Arrays.sort(recentOpcodeOffsets);
      sb.append("\nRecent opcode offsets:");
      for (long offset : recentOpcodeOffsets) {
        if (offset != -1) {
          sb.append(' ').append(offset);
        }
      }
    }
    return sb.toString();
  }
  
  private static INodeFile getINodeFile(FSDirectory fsDir, String path)
      throws IOException {
    INode inode = fsDir.getINode(path);
    if (inode != null) {
      if (!(inode instanceof INodeFile)) {
        throw new IOException("Operation trying to get non-file " + path);
      }
    }
    return (INodeFile)inode;
  }
  
  /**
   * Update in-memory data structures with new block information.
   * @throws IOException
   */
  private void updateBlocks(FSDirectory fsDir, AddCloseOp addCloseOp,
      INodeFile file) throws IOException {
    
    // Update the salient file attributes.
    file.setAccessTime(addCloseOp.atime);
    file.setModificationTimeForce(addCloseOp.mtime);
    
    // Update its block list
    BlockInfo[] oldBlocks = file.getBlocks();
    
    // Are we only updating the last block's gen stamp.
    boolean isGenStampUpdate = oldBlocks.length == addCloseOp.blocks.length;
    
    // First, update blocks in common
    for (int i = 0; i < oldBlocks.length && i < addCloseOp.blocks.length; i++) {
      BlockInfo oldBlock = oldBlocks[i];
      Block newBlock = addCloseOp.blocks[i];
      
      boolean isLastBlock = i == addCloseOp.blocks.length - 1;
      if (oldBlock.getBlockId() != newBlock.getBlockId() ||
          (oldBlock.getGenerationStamp() != newBlock.getGenerationStamp() && 
              !(isGenStampUpdate && isLastBlock))) {
        throw new IOException("Mismatched block IDs or generation stamps, " + 
            "attempting to replace block " + oldBlock + " with " + newBlock +
            " as block # " + i + "/" + addCloseOp.blocks.length + " of " +
            addCloseOp.path);
      }
      
      oldBlock.setNumBytes(newBlock.getNumBytes());
      oldBlock.setGenerationStamp(newBlock.getGenerationStamp());
      
      if (oldBlock instanceof BlockInfoUnderConstruction &&
          (!isLastBlock || addCloseOp.opCode == FSEditLogOpCodes.OP_CLOSE)) {
        fsNamesys.getBlockManager().forceCompleteBlock(
            (INodeFileUnderConstruction)file,
            (BlockInfoUnderConstruction)oldBlock);
      }
    }
    
    if (addCloseOp.blocks.length < oldBlocks.length) {
      // We're removing a block from the file, e.g. abandonBlock(...)
      if (!file.isUnderConstruction()) {
        throw new IOException("Trying to remove a block from file " +
            addCloseOp.path + " which is not under construction.");
      }
      if (addCloseOp.blocks.length != oldBlocks.length - 1) {
        throw new IOException("Trying to remove more than one block from file "
            + addCloseOp.path);
      }
      fsDir.unprotectedRemoveBlock(addCloseOp.path,
          (INodeFileUnderConstruction)file, oldBlocks[oldBlocks.length - 1]);
    } else if (addCloseOp.blocks.length > oldBlocks.length) {
      // We're adding blocks
      for (int i = oldBlocks.length; i < addCloseOp.blocks.length; i++) {
        Block newBlock = addCloseOp.blocks[i];
        BlockInfo newBI;
        if (addCloseOp.opCode == FSEditLogOpCodes.OP_ADD){
          newBI = new BlockInfoUnderConstruction(
              newBlock, file.getReplication());
        } else {
          // OP_CLOSE should add finalized blocks. This code path
          // is only executed when loading edits written by prior
          // versions of Hadoop. Current versions always log
          // OP_ADD operations as each block is allocated.
          newBI = new BlockInfo(newBlock, file.getReplication());
        }
        fsNamesys.getBlockManager().addINode(newBI, file);
        file.addBlock(newBI);
      }
    }
    
    // Record the max genstamp seen
    for (Block b : addCloseOp.blocks) {
      maxGenStamp = Math.max(maxGenStamp, b.getGenerationStamp());
    }
  }

  private static void dumpOpCounts(
      EnumMap<FSEditLogOpCodes, Holder<Integer>> opCounts) {
    StringBuilder sb = new StringBuilder();
    sb.append("Summary of operations loaded from edit log:\n  ");
    Joiner.on("\n  ").withKeyValueSeparator("=").appendTo(sb, opCounts);
    FSImage.LOG.debug(sb.toString());
  }

  private void incrOpCount(FSEditLogOpCodes opCode,
      EnumMap<FSEditLogOpCodes, Holder<Integer>> opCounts) {
    Holder<Integer> holder = opCounts.get(opCode);
    if (holder == null) {
      holder = new Holder<Integer>(1);
      opCounts.put(opCode, holder);
    } else {
      holder.held++;
    }
  }

  /**
   * Throw appropriate exception during upgrade from 203, when editlog loading
   * could fail due to opcode conflicts.
   */
  private void check203UpgradeFailure(int logVersion, IOException ex)
      throws IOException {
    // 0.20.203 version version has conflicting opcodes with the later releases.
    // The editlog must be emptied by restarting the namenode, before proceeding
    // with the upgrade.
    if (Storage.is203LayoutVersion(logVersion)
        && logVersion != HdfsConstants.LAYOUT_VERSION) {
      String msg = "During upgrade failed to load the editlog version "
          + logVersion + " from release 0.20.203. Please go back to the old "
          + " release and restart the namenode. This empties the editlog "
          + " and saves the namespace. Resume the upgrade after this step.";
      throw new IOException(msg, ex);
    } else {
      throw ex;
    }
  }
  
  /**
   * Return the number of valid transactions in the stream. If the stream is
   * truncated during the header, returns a value indicating that there are
   * 0 valid transactions. This reads through the stream but does not close
   * it.
   * @throws IOException if the stream cannot be read due to an IO error (eg
   *                     if the log does not exist)
   */
  static EditLogValidation validateEditLog(EditLogInputStream in) {
    long lastPos = 0;
    long firstTxId = HdfsConstants.INVALID_TXID;
    long lastTxId = HdfsConstants.INVALID_TXID;
    long numValid = 0;
    try {
      FSEditLogOp op = null;
      while (true) {
        lastPos = in.getPosition();
        if ((op = in.readOp()) == null) {
          break;
        }
        if (firstTxId == HdfsConstants.INVALID_TXID) {
          firstTxId = op.txid;
        }
        if (lastTxId == HdfsConstants.INVALID_TXID
            || op.txid == lastTxId + 1) {
          lastTxId = op.txid;
        } else {
          FSImage.LOG.error("Out of order txid found. Found " + op.txid 
                            + ", expected " + (lastTxId + 1));
          break;
        }
        numValid++;
      }
    } catch (Throwable t) {
      // Catch Throwable and not just IOE, since bad edits may generate
      // NumberFormatExceptions, AssertionErrors, OutOfMemoryErrors, etc.
      FSImage.LOG.debug("Caught exception after reading " + numValid +
          " ops from " + in + " while determining its valid length.", t);
    }
    return new EditLogValidation(lastPos, firstTxId, lastTxId);
  }
  
  static class EditLogValidation {
    private long validLength;
    private long startTxId;
    private long endTxId;
     
    EditLogValidation(long validLength, 
                      long startTxId, long endTxId) {
      this.validLength = validLength;
      this.startTxId = startTxId;
      this.endTxId = endTxId;
    }
    
    long getValidLength() { return validLength; }
    
    long getStartTxId() { return startTxId; }
    
    long getEndTxId() { return endTxId; }
    
    long getNumTransactions() { 
      if (endTxId == HdfsConstants.INVALID_TXID
          || startTxId == HdfsConstants.INVALID_TXID) {
        return 0;
      }
      return (endTxId - startTxId) + 1;
    }
  }

  /**
   * Stream wrapper that keeps track of the current stream position.
   */
  public static class PositionTrackingInputStream extends FilterInputStream {
    private long curPos = 0;
    private long markPos = -1;

    public PositionTrackingInputStream(InputStream is) {
      super(is);
    }

    public int read() throws IOException {
      int ret = super.read();
      if (ret != -1) curPos++;
      return ret;
    }

    public int read(byte[] data) throws IOException {
      int ret = super.read(data);
      if (ret > 0) curPos += ret;
      return ret;
    }

    public int read(byte[] data, int offset, int length) throws IOException {
      int ret = super.read(data, offset, length);
      if (ret > 0) curPos += ret;
      return ret;
    }

    public void mark(int limit) {
      super.mark(limit);
      markPos = curPos;
    }

    public void reset() throws IOException {
      if (markPos == -1) {
        throw new IOException("Not marked!");
      }
      super.reset();
      curPos = markPos;
      markPos = -1;
    }

    public long getPos() {
      return curPos;
    }
  }

}
