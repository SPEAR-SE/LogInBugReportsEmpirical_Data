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
package org.apache.hadoop.dfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.Random;
import java.lang.Math;
import java.nio.ByteBuffer;

import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.FSConstants.CheckpointStates;
import org.apache.hadoop.dfs.FSConstants.StartupOption;
import org.apache.hadoop.dfs.FSConstants.NodeType;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.dfs.BlocksMap.BlockInfo;

/**
 * FSImage handles checkpointing and logging of the namespace edits.
 * 
 */
class FSImage extends Storage {

  private static final SimpleDateFormat DATE_FORM =
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

  //
  // The filenames used for storing the images
  //
  enum NameNodeFile {
    IMAGE     ("fsimage"),
    TIME      ("fstime"),
    EDITS     ("edits"),
    IMAGE_NEW ("fsimage.ckpt"),
    EDITS_NEW ("edits.new");
    
    private String fileName = null;
    private NameNodeFile(String name) {this.fileName = name;}
    String getName() {return fileName;}
  }
  
  protected long checkpointTime = -1L;
  private FSEditLog editLog = null;
  private boolean isUpgradeFinalized = false;
  /**
   * Directories for importing an image from a checkpoint.
   */
  private Collection<File> checkpointDirs;

  /**
   * Can fs-image be rolled?
   */
  volatile private CheckpointStates ckptState = CheckpointStates.START; 

  /**
   * Used for saving the image to disk
   */
  static private final FsPermission fileperm = new FsPermission((short)0);
  static private final byte[] separator = INode.string2Bytes("/");
  static private byte[] byteStore = null;

  /**
   */
  FSImage() {
    super(NodeType.NAME_NODE);
    this.editLog = new FSEditLog(this);
  }

  /**
   */
  FSImage(Collection<File> fsDirs) throws IOException {
    this();
    setStorageDirectories(fsDirs);
  }

  FSImage(StorageInfo storageInfo) {
    super(NodeType.NAME_NODE, storageInfo);
  }

  /**
   * Represents an Image (image and edit file).
   */
  FSImage(File imageDir) throws IOException {
    this();
    ArrayList<File> dirs = new ArrayList<File>(1);
    dirs.add(imageDir);
    setStorageDirectories(dirs);
  }
  
  void setStorageDirectories(Collection<File> fsDirs) throws IOException {
    this.storageDirs = new ArrayList<StorageDirectory>(fsDirs.size());
    for(Iterator<File> it = fsDirs.iterator(); it.hasNext();)
      this.addStorageDir(new StorageDirectory(it.next()));
  }

  void setCheckpointDirectories(Collection<File> dirs) {
    checkpointDirs = dirs;
  }

  /**
   */
  File getImageFile(int imageDirIdx, NameNodeFile type) {
    return getImageFile(getStorageDir(imageDirIdx), type);
  }
  
  static File getImageFile(StorageDirectory sd, NameNodeFile type) {
    return new File(sd.getCurrentDir(), type.getName());
  }
  
  File getEditFile(int idx) {
    return getImageFile(idx, NameNodeFile.EDITS);
  }
  
  File getEditNewFile(int idx) {
    return getImageFile(idx, NameNodeFile.EDITS_NEW);
  }

  File[] getFileNames(NameNodeFile type) {
    File[] list = new File[getNumStorageDirs()];
    int i=0;
    for(StorageDirectory sd : storageDirs) {
      list[i++] = getImageFile(sd, type);
    }
    return list;
  }

  File[] getImageFiles() {
    return getFileNames(NameNodeFile.IMAGE);
  }

  File[] getEditsFiles() {
    return getFileNames(NameNodeFile.EDITS);
  }

  File[] getTimeFiles() {
    return getFileNames(NameNodeFile.TIME);
  }

  /**
   * Analyze storage directories.
   * Recover from previous transitions if required. 
   * Perform fs state transition if necessary depending on the namespace info.
   * Read storage info. 
   * 
   * @param dataDirs
   * @param startOpt startup option
   * @throws IOException
   * @return true if the image needs to be saved or false otherwise
   */
  boolean recoverTransitionRead(Collection<File> dataDirs,
                             StartupOption startOpt
                             ) throws IOException {
    assert startOpt != StartupOption.FORMAT : 
      "NameNode formatting should be performed before reading the image";

    if(startOpt == StartupOption.IMPORT 
        && (checkpointDirs == null || checkpointDirs.isEmpty()))
      throw new IOException("Cannot import image from a checkpoint. "
                          + "\"fs.checkpoint.dir\" is not set." );

    // 1. For each data directory calculate its state and 
    // check whether all is consistent before transitioning.
    this.storageDirs = new ArrayList<StorageDirectory>(dataDirs.size());
    AbstractList<StorageState> dataDirStates = 
      new ArrayList<StorageState>(dataDirs.size());
    boolean isFormatted = false;
    for(Iterator<File> it = dataDirs.iterator(); it.hasNext();) {
      File dataDir = it.next();
      StorageDirectory sd = new StorageDirectory(dataDir);
      StorageState curState;
      try {
        curState = sd.analyzeStorage(startOpt);
        // sd is locked but not opened
        switch(curState) {
        case NON_EXISTENT:
          // name-node fails if any of the configured storage dirs are missing
          throw new InconsistentFSStateException(sd.root,
                                                 "storage directory does not exist or is not accessible.");
        case NOT_FORMATTED:
          break;
        case NORMAL:
          break;
        default:  // recovery is possible
          sd.doRecover(curState);      
        }
        if (curState != StorageState.NOT_FORMATTED 
            && startOpt != StartupOption.ROLLBACK) {
          sd.read(); // read and verify consistency with other directories
          isFormatted = true;
        }
        if (startOpt == StartupOption.IMPORT && isFormatted)
          // import of a checkpoint is allowed only into empty image directories
          throw new IOException("Cannot import image from a checkpoint. " 
              + " NameNode already contains an image in " + sd.root);
      } catch (IOException ioe) {
        sd.unlock();
        throw ioe;
      }
      // add to the storage list
      addStorageDir(sd);
      dataDirStates.add(curState);
    }

    if (dataDirs.size() == 0)  // none of the data dirs exist
      throw new IOException(
        "All specified directories are not accessible or do not exist.");
    if (!isFormatted && startOpt != StartupOption.ROLLBACK 
                     && startOpt != StartupOption.IMPORT)
      throw new IOException("NameNode is not formatted.");
    if (layoutVersion < LAST_PRE_UPGRADE_LAYOUT_VERSION) {
      checkVersionUpgradable(layoutVersion);
    }
    if (startOpt != StartupOption.UPGRADE
          && layoutVersion < LAST_PRE_UPGRADE_LAYOUT_VERSION
          && layoutVersion != FSConstants.LAYOUT_VERSION)
        throw new IOException(
                          "\nFile system image contains an old layout version " + layoutVersion
                          + ".\nAn upgrade to version " + FSConstants.LAYOUT_VERSION
                          + " is required.\nPlease restart NameNode with -upgrade option.");
    // check whether distributed upgrade is reguired and/or should be continued
    verifyDistributedUpgradeProgress(startOpt);

    // 2. Format unformatted dirs.
    this.checkpointTime = 0L;
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      StorageState curState = dataDirStates.get(idx);
      switch(curState) {
      case NON_EXISTENT:
        assert false : StorageState.NON_EXISTENT + " state cannot be here";
      case NOT_FORMATTED:
        LOG.info("Storage directory " + sd.root + " is not formatted.");
        LOG.info("Formatting ...");
        sd.clearDirectory(); // create empty currrent dir
        break;
      default:
        break;
      }
    }

    // 3. Do transitions
    switch(startOpt) {
    case UPGRADE:
      doUpgrade();
      return false; // upgrade saved image already
    case IMPORT:
      doImportCheckpoint();
      return true;
    case ROLLBACK:
      doRollback();
      break;
    case REGULAR:
      // just load the image
    }
    return loadFSImage();
  }

  private void doUpgrade() throws IOException {
    if(getDistributedUpgradeState()) {
      // only distributed upgrade need to continue
      // don't do version upgrade
      this.loadFSImage();
      initializeDistributedUpgrade();
      return;
    }
    // Upgrade is allowed only if there are 
    // no previous fs states in any of the directories
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      if (sd.getPreviousDir().exists())
        throw new InconsistentFSStateException(sd.root,
                                               "previous fs state should not exist during upgrade. "
                                               + "Finalize or rollback first.");
    }

    // load the latest image
    this.loadFSImage();

    // Do upgrade for each directory
    long oldCTime = this.getCTime();
    this.cTime = FSNamesystem.now();  // generate new cTime for the state
    int oldLV = this.getLayoutVersion();
    this.layoutVersion = FSConstants.LAYOUT_VERSION;
    this.checkpointTime = FSNamesystem.now();
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      LOG.info("Upgrading image directory " + sd.root 
               + ".\n   old LV = " + oldLV
               + "; old CTime = " + oldCTime
               + ".\n   new LV = " + this.getLayoutVersion()
               + "; new CTime = " + this.getCTime());
      File curDir = sd.getCurrentDir();
      File prevDir = sd.getPreviousDir();
      File tmpDir = sd.getPreviousTmp();
      assert curDir.exists() : "Current directory must exist.";
      assert !prevDir.exists() : "prvious directory must not exist.";
      assert !tmpDir.exists() : "prvious.tmp directory must not exist.";
      // rename current to tmp
      rename(curDir, tmpDir);
      // save new image
      if (!curDir.mkdir())
        throw new IOException("Cannot create directory " + curDir);
      saveFSImage(getImageFile(sd, NameNodeFile.IMAGE));
      editLog.createEditLogFile(getImageFile(sd, NameNodeFile.EDITS));
      // write version and time files
      sd.write();
      // rename tmp to previous
      rename(tmpDir, prevDir);
      isUpgradeFinalized = false;
      LOG.info("Upgrade of " + sd.root + " is complete.");
    }
    initializeDistributedUpgrade();
    editLog.open();
  }

  private void doRollback() throws IOException {
    // Rollback is allowed only if there is 
    // a previous fs states in at least one of the storage directories.
    // Directories that don't have previous state do not rollback
    boolean canRollback = false;
    FSImage prevState = new FSImage();
    prevState.layoutVersion = FSConstants.LAYOUT_VERSION;
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      File prevDir = sd.getPreviousDir();
      if (!prevDir.exists()) {  // use current directory then
        LOG.info("Storage directory " + sd.root
                 + " does not contain previous fs state.");
        sd.read(); // read and verify consistency with other directories
        continue;
      }
      StorageDirectory sdPrev = prevState.new StorageDirectory(sd.root);
      sdPrev.read(sdPrev.getPreviousVersionFile());  // read and verify consistency of the prev dir
      canRollback = true;
    }
    if (!canRollback)
      throw new IOException("Cannot rollback. " 
                            + "None of the storage directories contain previous fs state.");

    // Now that we know all directories are going to be consistent
    // Do rollback for each directory containing previous state
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      File prevDir = sd.getPreviousDir();
      if (!prevDir.exists())
        continue;

      LOG.info("Rolling back storage directory " + sd.root 
               + ".\n   new LV = " + prevState.getLayoutVersion()
               + "; new CTime = " + prevState.getCTime());
      File tmpDir = sd.getRemovedTmp();
      assert !tmpDir.exists() : "removed.tmp directory must not exist.";
      // rename current to tmp
      File curDir = sd.getCurrentDir();
      assert curDir.exists() : "Current directory must exist.";
      rename(curDir, tmpDir);
      // rename previous to current
      rename(prevDir, curDir);

      // delete tmp dir
      deleteDir(tmpDir);
      LOG.info("Rollback of " + sd.root + " is complete.");
    }
    isUpgradeFinalized = true;
    // check whether name-node can start in regular mode
    verifyDistributedUpgradeProgress(StartupOption.REGULAR);
  }

  private void doFinalize(StorageDirectory sd) throws IOException {
    File prevDir = sd.getPreviousDir();
    if (!prevDir.exists()) { // already discarded
      LOG.info("Directory " + prevDir + " does not exist.");
      LOG.info("Finalize upgrade for " + sd.root + " is not required.");
      return;
    }
    LOG.info("Finalizing upgrade for storage directory " 
             + sd.root + "."
             + (getLayoutVersion()==0 ? "" :
                   "\n   cur LV = " + this.getLayoutVersion()
                   + "; cur CTime = " + this.getCTime()));
    assert sd.getCurrentDir().exists() : "Current directory must exist.";
    final File tmpDir = sd.getFinalizedTmp();
    // rename previous to tmp and remove
    rename(prevDir, tmpDir);
    deleteDir(tmpDir);
    isUpgradeFinalized = true;
    LOG.info("Finalize upgrade for " + sd.root + " is complete.");
  }

  /**
   * Load image from a checkpoint directory and save it into the current one.
   * @throws IOException
   */
  void doImportCheckpoint() throws IOException {
    FSImage ckptImage = new FSImage();
    FSNamesystem fsNamesys = FSNamesystem.getFSNamesystem();
    // replace real image with the checkpoint image
    FSImage realImage = fsNamesys.getFSImage();
    assert realImage == this;
    fsNamesys.dir.fsImage = ckptImage;
    // load from the checkpoint dirs
    try {
      ckptImage.recoverTransitionRead(checkpointDirs, StartupOption.REGULAR);
    } finally {
      ckptImage.close();
    }
    // return back the real image
    realImage.setStorageInfo(ckptImage);
    fsNamesys.dir.fsImage = realImage;
    // and save it
    saveFSImage();
  }

  void finalizeUpgrade() throws IOException {
    for(int idx = 0; idx < getNumStorageDirs(); idx++)
      doFinalize(getStorageDir(idx));
  }

  boolean isUpgradeFinalized() {
    return isUpgradeFinalized;
  }

  protected void getFields(Properties props, 
                           StorageDirectory sd 
                           ) throws IOException {
    super.getFields(props, sd);
    if (layoutVersion == 0)
      throw new IOException("NameNode directory " 
                            + sd.root + " is not formatted.");
    String sDUS, sDUV;
    sDUS = props.getProperty("distributedUpgradeState"); 
    sDUV = props.getProperty("distributedUpgradeVersion");
    setDistributedUpgradeState(
        sDUS == null? false : Boolean.parseBoolean(sDUS),
        sDUV == null? getLayoutVersion() : Integer.parseInt(sDUV));
    this.checkpointTime = readCheckpointTime(sd);
  }

  long readCheckpointTime(StorageDirectory sd) throws IOException {
    File timeFile = getImageFile(sd, NameNodeFile.TIME);
    long timeStamp = 0L;
    if (timeFile.exists() && timeFile.canRead()) {
      DataInputStream in = new DataInputStream(new FileInputStream(timeFile));
      try {
        timeStamp = in.readLong();
      } finally {
        in.close();
      }
    }
    return timeStamp;
  }

  /**
   * Write last checkpoint time and version file into the storage directory.
   * 
   * The version file should always be written last.
   * Missing or corrupted version file indicates that 
   * the checkpoint is not valid.
   * 
   * @param sd storage directory
   * @throws IOException
   */
  protected void setFields(Properties props, 
                           StorageDirectory sd 
                           ) throws IOException {
    super.setFields(props, sd);
    boolean uState = getDistributedUpgradeState();
    int uVersion = getDistributedUpgradeVersion();
    if(uState && uVersion != getLayoutVersion()) {
      props.setProperty("distributedUpgradeState", Boolean.toString(uState));
      props.setProperty("distributedUpgradeVersion", Integer.toString(uVersion)); 
    }
    writeCheckpointTime(sd);
  }

  /**
   * Write last checkpoint time into a separate file.
   * 
   * @param sd
   * @throws IOException
   */
  void writeCheckpointTime(StorageDirectory sd) throws IOException {
    if (checkpointTime < 0L)
      return; // do not write negative time
    File timeFile = getImageFile(sd, NameNodeFile.TIME);
    if (timeFile.exists()) { timeFile.delete(); }
    DataOutputStream out = new DataOutputStream(
                                                new FileOutputStream(timeFile));
    try {
      out.writeLong(checkpointTime);
    } finally {
      out.close();
    }
  }

  /**
   * Record new checkpoint time in order to
   * distinguish healthy directories from the removed ones.
   * 
   * @return -1 if successful, or the index of the failed storage directory.
   */
  int incrementCheckpointTime() {
    this.checkpointTime++;
    // Write new checkpoint time.
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      try {
        StorageDirectory sd = getStorageDir(idx);
        writeCheckpointTime(sd);
      } catch(IOException e) { 
        return idx;
      }
    }
    return -1;
  }

  /**
   * If there is an IO Error on any log operations, remove that
   * directory from the list of directories.
   */
  void processIOError(int index) {
    assert(index >= 0 && index < getNumStorageDirs());
    storageDirs.remove(index);
  }

  FSEditLog getEditLog() {
    return editLog;
  }

  boolean isConversionNeeded(StorageDirectory sd) throws IOException {
    File oldImageDir = new File(sd.root, "image");
    if (!oldImageDir.exists())
      throw new InconsistentFSStateException(sd.root,
          oldImageDir + " does not exist.");
    // check the layout version inside the image file
    File oldF = new File(oldImageDir, "fsimage");
    RandomAccessFile oldFile = new RandomAccessFile(oldF, "rws");
    try {
      oldFile.seek(0);
      int odlVersion = oldFile.readInt();
      if (odlVersion < LAST_PRE_UPGRADE_LAYOUT_VERSION)
        return false;
    } finally {
      oldFile.close();
    }
    return true;
  }
  
  //
  // Atomic move sequence, to recover from interrupted checkpoint
  //
  void recoverInterruptedCheckpoint(StorageDirectory sd) throws IOException {
    File curFile = getImageFile(sd, NameNodeFile.IMAGE);
    File ckptFile = getImageFile(sd, NameNodeFile.IMAGE_NEW);

    //
    // If we were in the midst of a checkpoint
    //
    if (ckptFile.exists()) {
      if (getImageFile(sd, NameNodeFile.EDITS_NEW).exists()) {
        //
        // checkpointing migth have uploaded a new
        // merged image, but we discard it here because we are
        // not sure whether the entire merged image was uploaded
        // before the namenode crashed.
        //
        if (!ckptFile.delete()) {
          throw new IOException("Unable to delete " + ckptFile);
        }
      } else {
        //
        // checkpointing was in progress when the namenode
        // shutdown. The fsimage.ckpt was created and the edits.new
        // file was moved to edits. We complete that checkpoint by
        // moving fsimage.new to fsimage. There is no need to 
        // update the fstime file here. renameTo fails on Windows
        // if the destination file already exists.
        //
        if (!ckptFile.renameTo(curFile)) {
          curFile.delete();
          if (!ckptFile.renameTo(curFile)) {
            throw new IOException("Unable to rename " + ckptFile +
                                  " to " + curFile);
          }
        }
      }
    }
  }

  /**
   * Choose latest image from one of the directories,
   * load it and merge with the edits from that directory.
   * 
   * @return whether the image should be saved
   * @throws IOException
   */
  boolean loadFSImage() throws IOException {
    // Now check all curFiles and see which is the newest
    long latestCheckpointTime = Long.MIN_VALUE;
    StorageDirectory latestSD = null;
    boolean needToSave = false;
    isUpgradeFinalized = true;
    for (int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      recoverInterruptedCheckpoint(sd);
      if (!sd.getVersionFile().exists()) {
        needToSave |= true;
        continue; // some of them might have just been formatted
      }
      assert getImageFile(sd, NameNodeFile.IMAGE).exists() :
        "Image file must exist.";
      checkpointTime = readCheckpointTime(sd);
      if (latestCheckpointTime < checkpointTime) {
        latestCheckpointTime = checkpointTime;
        latestSD = sd;
      }
      if (checkpointTime <= 0L)
        needToSave |= true;
      // set finalized flag
      isUpgradeFinalized = isUpgradeFinalized && !sd.getPreviousDir().exists();
    }
    assert latestSD != null : "Latest storage directory was not determined.";

    //
    // Load in bits
    //
    latestSD.read();
    needToSave |= loadFSImage(getImageFile(latestSD, NameNodeFile.IMAGE));

    //
    // read in the editlog from the same directory from
    // which we read in the image
    //
    needToSave |= (loadFSEdits(latestSD) > 0);
    
    return needToSave;
  }

  /**
   * Load in the filesystem imagefrom file. It's a big list of
   * filenames and blocks.  Return whether we should
   * "re-save" and consolidate the edit-logs
   */
  boolean loadFSImage(File curFile) throws IOException {
    assert this.getLayoutVersion() < 0 : "Negative layout version is expected.";
    assert curFile != null : "curFile is null";

    FSNamesystem fsNamesys = FSNamesystem.getFSNamesystem();
    FSDirectory fsDir = fsNamesys.dir;

    //
    // Load in bits
    //
    boolean needToSave = true;
    DataInputStream in = new DataInputStream(
                                             new BufferedInputStream(
                                                                     new FileInputStream(curFile)));
    try {
      /*
       * Note: Remove any checks for version earlier than 
       * Storage.LAST_UPGRADABLE_LAYOUT_VERSION since we should never get 
       * to here with older images.
       */
      
      /*
       * TODO we need to change format of the image file
       * it should not contain version and namespace fields
       */
      // read image version: first appeared in version -1
      int imgVersion = in.readInt();
      // read namespaceID: first appeared in version -2
      this.namespaceID = in.readInt();

      // read number of files
      int numFiles = 0;
      numFiles = in.readInt();

      this.layoutVersion = imgVersion;
      // read in the last generation stamp.
      if (imgVersion <= -12) {
        long genstamp = in.readLong();
        fsNamesys.setGenerationStamp(genstamp); 
      }

      needToSave = (imgVersion != FSConstants.LAYOUT_VERSION);

      // read file info
      short replication = FSNamesystem.getFSNamesystem().getDefaultReplication();
      for (int i = 0; i < numFiles; i++) {
        UTF8 name = new UTF8();
        long modificationTime = 0;
        long blockSize = 0;
        name.readFields(in);
        replication = in.readShort();
        replication = FSEditLog.adjustReplication(replication);
        modificationTime = in.readLong();
        if (imgVersion <= -8) {
          blockSize = in.readLong();
        }
        int numBlocks = in.readInt();
        Block blocks[] = null;

        // for older versions, a blocklist of size 0
        // indicates a directory.
        if ((-9 <= imgVersion && numBlocks > 0) ||
            (imgVersion < -9 && numBlocks >= 0)) {
          blocks = new Block[numBlocks];
          for (int j = 0; j < numBlocks; j++) {
            blocks[j] = new Block();
            blocks[j].readFields(in);
          }
        }
        // Older versions of HDFS does not store the block size in inode.
        // If the file has more than one block, use the size of the 
        // first block as the blocksize. Otherwise use the default block size.
        //
        if (-8 <= imgVersion && blockSize == 0) {
          if (numBlocks > 1) {
            blockSize = blocks[0].getNumBytes();
          } else {
            long first = ((numBlocks == 1) ? blocks[0].getNumBytes(): 0);
            blockSize = Math.max(fsNamesys.getDefaultBlockSize(), first);
          }
        }
        PermissionStatus permissions = fsNamesys.getUpgradePermission();
        if (imgVersion <= -11) {
          permissions = PermissionStatus.read(in);
        }
        fsDir.unprotectedAddFile(name.toString(), permissions,
            blocks, replication, modificationTime, blockSize);
      }
      
      // load datanode info
      this.loadDatanodes(imgVersion, in);

      // load Files Under Construction
      this.loadFilesUnderConstruction(imgVersion, in, fsNamesys);
    } finally {
      in.close();
    }
    
    return needToSave;
  }

  /**
   * Load and merge edits from two edits files
   * 
   * @param sd storage directory
   * @return number of edits loaded
   * @throws IOException
   */
  int loadFSEdits(StorageDirectory sd) throws IOException {
    int numEdits = 0;
    numEdits = editLog.loadFSEdits(getImageFile(sd, NameNodeFile.EDITS));
    File editsNew = getImageFile(sd, NameNodeFile.EDITS_NEW);
    if (editsNew.exists()) 
      numEdits += editLog.loadFSEdits(editsNew);
    return numEdits;
  }

  /**
   * Save the contents of the FS image to the file.
   */
  void saveFSImage(File newFile) throws IOException {
    FSNamesystem fsNamesys = FSNamesystem.getFSNamesystem();
    FSDirectory fsDir = fsNamesys.dir;
    //
    // Write out data
    //
    DataOutputStream out = new DataOutputStream(
                                                new BufferedOutputStream(
                                                                         new FileOutputStream(newFile)));
    try {
      out.writeInt(FSConstants.LAYOUT_VERSION);
      out.writeInt(namespaceID);
      out.writeInt(fsDir.rootDir.numItemsInTree() - 1);
      out.writeLong(fsNamesys.getGenerationStamp());
      byteStore = new byte[4*FSConstants.MAX_PATH_LENGTH];
      ByteBuffer strbuf = ByteBuffer.wrap(byteStore);
      saveImage(strbuf, 0, fsDir.rootDir, out);
      fsNamesys.saveFilesUnderConstruction(out);
      byteStore = null;
      strbuf = null;
    } finally {
      out.close();
    }
  }

  /**
   * Save the contents of the FS image
   * and create empty edits.
   */
  void saveFSImage() throws IOException {
    editLog.createNewIfMissing();
    for (int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      saveFSImage(getImageFile(sd, NameNodeFile.IMAGE_NEW));
      editLog.createEditLogFile(getImageFile(sd, NameNodeFile.EDITS));
      File editsNew = getImageFile(sd, NameNodeFile.EDITS_NEW);
      if (editsNew.exists()) 
        editLog.createEditLogFile(editsNew);
    }
    ckptState = CheckpointStates.UPLOAD_DONE;
    rollFSImage();
  }

  /**
   * Generate new namespaceID.
   * 
   * namespaceID is a persistent attribute of the namespace.
   * It is generated when the namenode is formatted and remains the same
   * during the life cycle of the namenode.
   * When a datanodes register they receive it as the registrationID,
   * which is checked every time the datanode is communicating with the 
   * namenode. Datanodes that do not 'know' the namespaceID are rejected.
   * 
   * @return new namespaceID
   */
  private int newNamespaceID() {
    Random r = new Random();
    r.setSeed(FSNamesystem.now());
    int newID = 0;
    while(newID == 0)
      newID = r.nextInt(0x7FFFFFFF);  // use 31 bits only
    return newID;
  }

  /** Create new dfs name directory.  Caution: this destroys all files
   * in this filesystem. */
  void format(StorageDirectory sd) throws IOException {
    sd.clearDirectory(); // create currrent dir
    sd.lock();
    try {
      saveFSImage(getImageFile(sd, NameNodeFile.IMAGE));
      editLog.createEditLogFile(getImageFile(sd, NameNodeFile.EDITS));
      sd.write();
    } finally {
      sd.unlock();
    }
    LOG.info("Storage directory " + sd.root 
             + " has been successfully formatted.");
  }

  public void format() throws IOException {
    this.layoutVersion = FSConstants.LAYOUT_VERSION;
    this.namespaceID = newNamespaceID();
    this.cTime = 0L;
    this.checkpointTime = FSNamesystem.now();
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      format(sd);
    }
  }

  /**
   * Save file tree image starting from the given root.
   */
  private static void saveImage(ByteBuffer parentPrefix, 
                                int prefixLength,
                                INode inode, 
                                DataOutputStream out) throws IOException {
    int newPrefixLength = prefixLength;
    if (inode.getParent() != null) {
      parentPrefix.put(separator).put(inode.getLocalNameBytes());
      newPrefixLength += separator.length + inode.getLocalNameBytes().length;
      out.writeShort(newPrefixLength);
      out.write(byteStore, 0, newPrefixLength);
      if (!inode.isDirectory()) {  // write file inode
        INodeFile fileINode = (INodeFile)inode;
        out.writeShort(fileINode.getReplication());
        out.writeLong(inode.getModificationTime());
        out.writeLong(fileINode.getPreferredBlockSize());
        Block[] blocks = fileINode.getBlocks();
        out.writeInt(blocks.length);
        for (Block blk : blocks)
          blk.write(out);
        fileperm.fromShort(fileINode.getFsPermissionShort());
        PermissionStatus.write(out, fileINode.getUserName(),
                               fileINode.getGroupName(),
                               fileperm);
        parentPrefix.position(prefixLength);
        return;
      }
      // write directory inode
      out.writeShort(0);  // replication
      out.writeLong(inode.getModificationTime());
      out.writeLong(0);   // preferred block size
      out.writeInt(-1);    // # of blocks
      fileperm.fromShort(inode.getFsPermissionShort());
      PermissionStatus.write(out, inode.getUserName(),
                             inode.getGroupName(),
                             fileperm);
    }
    if (((INodeDirectory)inode).getChildrenRaw() != null) {
      for(INode child : ((INodeDirectory)inode).getChildren()) {
        saveImage(parentPrefix, newPrefixLength, child, out);
      }
    }
    parentPrefix.position(prefixLength);
  }

  void loadDatanodes(int version, DataInputStream in) throws IOException {
    if (version > -3) // pre datanode image version
      return;
    if (version <= -12) {
      return; // new versions do not store the datanodes any more.
    }
    int size = in.readInt();
    for(int i = 0; i < size; i++) {
      DatanodeImage nodeImage = new DatanodeImage();
      nodeImage.readFields(in);
      // We don't need to add these descriptors any more.
    }
  }

  private void loadFilesUnderConstruction(int version, DataInputStream in, 
                                  FSNamesystem fs) throws IOException {

    FSDirectory fsDir = fs.dir;
    if (version > -13) // pre lease image version
      return;
    int size = in.readInt();

    for (int i = 0; i < size; i++) {
      INodeFileUnderConstruction cons = readINodeUnderConstruction(in);

      // verify that file exists in namespace
      String path = cons.getLocalName();
      INode old = fsDir.getFileINode(path);
      if (old == null) {
        throw new IOException("Found lease for non-existent file " + path);
      }
      if (old.isDirectory()) {
        throw new IOException("Found lease for directory " + path);
      }
      INodeFile oldnode = (INodeFile) old;
      fsDir.replaceNode(path, oldnode, cons);
      fs.addLease(path, cons.getClientName()); 
    }
  }

  // Helper function that reads in an INodeUnderConstruction
  // from the input stream
  //
  static INodeFileUnderConstruction readINodeUnderConstruction(
                            DataInputStream in) throws IOException {
    UTF8 src = new UTF8();
    src.readFields(in);
    byte[] name = src.getBytes();
    short blockReplication = in.readShort();
    long modificationTime = in.readLong();
    long preferredBlockSize = in.readLong();
    int numBlocks = in.readInt();
    BlockInfo[] blocks = new BlockInfo[numBlocks];
    Block blk = new Block();
    for (int i = 0; i < numBlocks; i++) {
      blk.readFields(in);
      blocks[i] = new BlockInfo(blk, blockReplication);
    }
    PermissionStatus perm = PermissionStatus.read(in);
    UTF8 clientName = new UTF8();
    clientName.readFields(in);
    UTF8 clientMachine = new UTF8();
    clientMachine.readFields(in);

    int numLocs = in.readInt();
    DatanodeDescriptor[] locations = new DatanodeDescriptor[numLocs];
    for (int i = 0; i < numLocs; i++) {
      locations[i] = new DatanodeDescriptor();
      locations[i].readFields(in);
    }

    return new INodeFileUnderConstruction(name, 
                                          blockReplication, 
                                          modificationTime,
                                          preferredBlockSize,
                                          blocks,
                                          perm,
                                          clientName.toString(),
                                          clientMachine.toString(),
                                          null,
                                          locations);

  }

  // Helper function that writes an INodeUnderConstruction
  // into the input stream
  //
  static void writeINodeUnderConstruction(DataOutputStream out,
                                           INodeFileUnderConstruction cons,
                                           String path) 
                                           throws IOException {
    new UTF8(path).write(out);
    out.writeShort(cons.getReplication());
    out.writeLong(cons.getModificationTime());
    out.writeLong(cons.getPreferredBlockSize());
    int nrBlocks = cons.getBlocks().length;
    out.writeInt(nrBlocks);
    for (int i = 0; i < nrBlocks; i++) {
      cons.getBlocks()[i].write(out);
    }
    cons.getPermissionStatus().write(out);
    new UTF8(cons.getClientName()).write(out);
    new UTF8(cons.getClientMachine()).write(out);

    int numLocs = cons.getLastBlockLocations().length;
    out.writeInt(numLocs);
    for (int i = 0; i < numLocs; i++) {
      cons.getLastBlockLocations()[i].write(out);
    }
  }

  /**
   * Moves fsimage.ckpt to fsImage and edits.new to edits
   * Reopens the new edits file.
   */
  void rollFSImage() throws IOException {
    if (ckptState != CheckpointStates.UPLOAD_DONE) {
      throw new IOException("Cannot roll fsImage before rolling edits log.");
    }
    //
    // First, verify that edits.new and fsimage.ckpt exists in all
    // checkpoint directories.
    //
    if (!editLog.existsNew()) {
      throw new IOException("New Edits file does not exist");
    }
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      File ckpt = getImageFile(sd, NameNodeFile.IMAGE_NEW);
      if (!ckpt.exists()) {
        throw new IOException("Checkpoint file " + ckpt +
                              " does not exist");
      }
    }
    editLog.purgeEditLog(); // renamed edits.new to edits

    //
    // Renames new image
    //
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      File ckpt = getImageFile(sd, NameNodeFile.IMAGE_NEW);
      File curFile = getImageFile(sd, NameNodeFile.IMAGE);
      // renameTo fails on Windows if the destination file 
      // already exists.
      if (!ckpt.renameTo(curFile)) {
        curFile.delete();
        if (!ckpt.renameTo(curFile)) {
          editLog.processIOError(idx);
          idx--;
        }
      }
    }

    //
    // Updates the fstime file and write version file
    //
    this.layoutVersion = FSConstants.LAYOUT_VERSION;
    this.checkpointTime = FSNamesystem.now();
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      StorageDirectory sd = getStorageDir(idx);
      try {
        sd.write();
      } catch (IOException e) {
        LOG.error("Cannot write file " + sd.root, e);
        editLog.processIOError(idx);
        idx--;
      }
    }
    ckptState = CheckpointStates.START;
  }

  CheckpointSignature rollEditLog() throws IOException {
    getEditLog().rollEditLog();
    ckptState = CheckpointStates.ROLLED_EDITS;
    return new CheckpointSignature(this);
  }

  /**
   * This is called just before a new checkpoint is uploaded to the
   * namenode.
   */
  void validateCheckpointUpload(CheckpointSignature sig) throws IOException {
    if (ckptState != CheckpointStates.ROLLED_EDITS) {
      throw new IOException("Namenode is not expecting an new image " +
                             ckptState);
    } 
    // verify token
    long modtime = getEditLog().getFsEditTime();
    if (sig.editsTime != modtime) {
      throw new IOException("Namenode has an edit log with timestamp of " +
                            DATE_FORM.format(new Date(modtime)) +
                            " but new checkpoint was created using editlog " +
                            " with timestamp " + 
                            DATE_FORM.format(new Date(sig.editsTime)) + 
                            ". Checkpoint Aborted.");
    }
    sig.validateStorageInfo(this);
    ckptState = CheckpointStates.UPLOAD_START;
  }

  /**
   * This is called when a checkpoint upload finishes successfully.
   */
  synchronized void checkpointUploadDone() {
    ckptState = CheckpointStates.UPLOAD_DONE;
  }

  void close() throws IOException {
    getEditLog().close();
    unlockAll();
  }

  /**
   * Return the name of the image file.
   */
  File getFsImageName() {
    return getImageFile(0, NameNodeFile.IMAGE);
  }

  File getFsEditName() throws IOException {
    return getEditLog().getFsEditName();
  }

  File getFsTimeName() {
    return getImageFile(0, NameNodeFile.TIME);
  }

  /**
   * Return the name of the image file that is uploaded by periodic
   * checkpointing.
   */
  File[] getFsImageNameCheckpoint() {
    File[] list = new File[getNumStorageDirs()];
    for(int i = 0; i < getNumStorageDirs(); i++) {
      list[i] = getImageFile(getStorageDir(i), NameNodeFile.IMAGE_NEW);
    }
    return list;
  }

  /**
   * DatanodeImage is used to store persistent information
   * about datanodes into the fsImage.
   */
  static class DatanodeImage implements WritableComparable {
    DatanodeDescriptor              node;

    DatanodeImage() {
      node = new DatanodeDescriptor();
    }

    DatanodeImage(DatanodeDescriptor from) {
      node = from;
    }

    /** 
     * Returns the underlying Datanode Descriptor
     */
    DatanodeDescriptor getDatanodeDescriptor() { 
      return node; 
    }

    public int compareTo(Object o) {
      return node.compareTo(o);
    }

    public boolean equals(Object o) {
      return node.equals(o);
    }

    public int hashCode() {
      return node.hashCode();
    }

    /////////////////////////////////////////////////
    // Writable
    /////////////////////////////////////////////////
    /**
     * Public method that serializes the information about a
     * Datanode to be stored in the fsImage.
     */
    public void write(DataOutput out) throws IOException {
      new DatanodeID(node).write(out);
      out.writeLong(node.getCapacity());
      out.writeLong(node.getRemaining());
      out.writeLong(node.getLastUpdate());
      out.writeInt(node.getXceiverCount());
    }

    /**
     * Public method that reads a serialized Datanode
     * from the fsImage.
     */
    public void readFields(DataInput in) throws IOException {
      DatanodeID id = new DatanodeID();
      id.readFields(in);
      long capacity = in.readLong();
      long remaining = in.readLong();
      long lastUpdate = in.readLong();
      int xceiverCount = in.readInt();

      // update the DatanodeDescriptor with the data we read in
      node.updateRegInfo(id);
      node.setStorageID(id.getStorageID());
      node.setCapacity(capacity);
      node.setRemaining(remaining);
      node.setLastUpdate(lastUpdate);
      node.setXceiverCount(xceiverCount);
    }
  }

  protected void corruptPreUpgradeStorage(File rootDir) throws IOException {
    File oldImageDir = new File(rootDir, "image");
    if (!oldImageDir.exists())
      if (!oldImageDir.mkdir())
        throw new IOException("Cannot create directory " + oldImageDir);
    File oldImage = new File(oldImageDir, "fsimage");
    if (!oldImage.exists())
      // recreate old image file to let pre-upgrade versions fail
      if (!oldImage.createNewFile())
        throw new IOException("Cannot create file " + oldImage);
    RandomAccessFile oldFile = new RandomAccessFile(oldImage, "rws");
    // write new version into old image file
    try {
      writeCorruptedData(oldFile);
    } finally {
      oldFile.close();
    }
  }

  private boolean getDistributedUpgradeState() {
    return FSNamesystem.getFSNamesystem().getDistributedUpgradeState();
  }

  private int getDistributedUpgradeVersion() {
    return FSNamesystem.getFSNamesystem().getDistributedUpgradeVersion();
  }

  private void setDistributedUpgradeState(boolean uState, int uVersion) {
    FSNamesystem.getFSNamesystem().upgradeManager.setUpgradeState(uState, uVersion);
  }

  private void verifyDistributedUpgradeProgress(StartupOption startOpt
                                                ) throws IOException {
    if(startOpt == StartupOption.ROLLBACK || startOpt == StartupOption.IMPORT)
      return;
    UpgradeManager um = FSNamesystem.getFSNamesystem().upgradeManager;
    assert um != null : "FSNameSystem.upgradeManager is null.";
    if(startOpt != StartupOption.UPGRADE) {
      if(um.getUpgradeState())
        throw new IOException(
                    "\n   Previous distributed upgrade was not completed. "
                  + "\n   Please restart NameNode with -upgrade option.");
      if(um.getDistributedUpgrades() != null)
        throw new IOException("\n   Distributed upgrade for NameNode version " 
          + um.getUpgradeVersion() + " to current LV " + FSConstants.LAYOUT_VERSION
          + " is required.\n   Please restart NameNode with -upgrade option.");
    }
  }

  private void initializeDistributedUpgrade() throws IOException {
    UpgradeManagerNamenode um = FSNamesystem.getFSNamesystem().upgradeManager;
    if(! um.initializeUpgrade())
      return;
    // write new upgrade state into disk
    FSNamesystem.getFSNamesystem().getFSImage().writeAll();
    NameNode.LOG.info("\n   Distributed upgrade for NameNode version " 
        + um.getUpgradeVersion() + " to current LV " 
        + FSConstants.LAYOUT_VERSION + " is initialized.");
  }

  static Collection<File> getCheckpointDirs(Configuration conf,
                                            String defaultName) {
    Collection<String> dirNames = conf.getStringCollection("fs.checkpoint.dir");
    if (dirNames.size() == 0 && defaultName != null) {
      dirNames.add(defaultName);
    }
    Collection<File> dirs = new ArrayList<File>(dirNames.size());
    for(String name : dirNames) {
      dirs.add(new File(name));
    }
    return dirs;
  }
}
