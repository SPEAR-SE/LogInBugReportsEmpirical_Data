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

import org.apache.commons.logging.*;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.dfs.BlocksWithLocations.BlockWithLocations;
import org.apache.hadoop.util.*;
import org.apache.hadoop.mapred.StatusHttpServer;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.Server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.Map.Entry;
import java.text.SimpleDateFormat;

/***************************************************
 * FSNamesystem does the actual bookkeeping work for the
 * DataNode.
 *
 * It tracks several important tables.
 *
 * 1)  valid fsname --> blocklist  (kept on disk, logged)
 * 2)  Set of all valid blocks (inverted #1)
 * 3)  block --> machinelist (kept in memory, rebuilt dynamically from reports)
 * 4)  machine --> blocklist (inverted #2)
 * 5)  LRU cache of updated-heartbeat machines
 ***************************************************/
class FSNamesystem implements FSConstants {
  public static final Log LOG = LogFactory.getLog("org.apache.hadoop.fs.FSNamesystem");

  //
  // Stores the correct file name hierarchy
  //
  FSDirectory dir;

  //
  // Stores the block-->datanode(s) map.  Updated only in response
  // to client-sent information.
  // Mapping: Block -> { INode, datanodes, self ref } 
  //
  BlocksMap blocksMap = new BlocksMap();
    
  /**
   * Stores the datanode -> block map.  
   * <p>
   * Done by storing a set of {@link DatanodeDescriptor} objects, sorted by 
   * storage id. In order to keep the storage map consistent it tracks 
   * all storages ever registered with the namenode.
   * A descriptor corresponding to a specific storage id can be
   * <ul> 
   * <li>added to the map if it is a new storage id;</li>
   * <li>updated with a new datanode started as a replacement for the old one 
   * with the same storage id; and </li>
   * <li>removed if and only if an existing datanode is restarted to serve a
   * different storage id.</li>
   * </ul> <br>
   * The list of the {@link DatanodeDescriptor}s in the map is checkpointed
   * in the namespace image file. Only the {@link DatanodeInfo} part is 
   * persistent, the list of blocks is restored from the datanode block
   * reports. 
   * <p>
   * Mapping: StorageID -> DatanodeDescriptor
   */
  Map<String, DatanodeDescriptor> datanodeMap = 
    new TreeMap<String, DatanodeDescriptor>();

  //
  // Keeps a Collection for every named machine containing
  // blocks that have recently been invalidated and are thought to live
  // on the machine in question.
  // Mapping: StorageID -> ArrayList<Block>
  //
  private Map<String, Collection<Block>> recentInvalidateSets = 
    new TreeMap<String, Collection<Block>>();

  //
  // Keeps a TreeSet for every named node.  Each treeset contains
  // a list of the blocks that are "extra" at that location.  We'll
  // eventually remove these extras.
  // Mapping: StorageID -> TreeSet<Block>
  //
  private Map<String, Collection<Block>> excessReplicateMap = 
    new TreeMap<String, Collection<Block>>();

  //
  // Stats on overall usage
  //
  long totalCapacity = 0L, totalUsed=0L, totalRemaining = 0L;

  // total number of connections per live datanode
  int totalLoad = 0;


  //
  // For the HTTP browsing interface
  //
  StatusHttpServer infoServer;
  int infoPort;
  String infoBindAddress;
  Date startTime;
    
  //
  Random r = new Random();

  /**
   * Stores a set of DatanodeDescriptor objects.
   * This is a subset of {@link #datanodeMap}, containing nodes that are 
   * considered alive.
   * The {@link HeartbeatMonitor} periodically checks for outdated entries,
   * and removes them from the list.
   */
  ArrayList<DatanodeDescriptor> heartbeats = new ArrayList<DatanodeDescriptor>();

  //
  // Store set of Blocks that need to be replicated 1 or more times.
  // We also store pending replication-orders.
  // Set of: Block
  //
  private UnderReplicatedBlocks neededReplications = new UnderReplicatedBlocks();
  private PendingReplicationBlocks pendingReplications;

  //
  // Used for handling lock-leases
  // Mapping: leaseHolder -> Lease
  //
  private Map<StringBytesWritable, Lease> leases = new TreeMap<StringBytesWritable, Lease>();
  // Set of: Lease
  private SortedSet<Lease> sortedLeases = new TreeSet<Lease>();

  //
  // Threaded object that checks to see if we have been
  // getting heartbeats from all clients. 
  //
  Daemon hbthread = null;   // HeartbeatMonitor thread
  Daemon lmthread = null;   // LeaseMonitor thread
  Daemon smmthread = null;  // SafeModeMonitor thread
  Daemon replthread = null;  // Replication thread
  volatile boolean fsRunning = true;
  long systemStart = 0;

  //  The maximum number of replicates we should allow for a single block
  private int maxReplication;
  //  How many outgoing replication streams a given node should have at one time
  private int maxReplicationStreams;
  // MIN_REPLICATION is how many copies we need in place or else we disallow the write
  private int minReplication;
  // Default replication
  private int defaultReplication;
  // heartbeatRecheckInterval is how often namenode checks for expired datanodes
  private long heartbeatRecheckInterval;
  // heartbeatExpireInterval is how long namenode waits for datanode to report
  // heartbeat
  private long heartbeatExpireInterval;
  //replicationRecheckInterval is how often namenode checks for new replication work
  private long replicationRecheckInterval;
  //decommissionRecheckInterval is how often namenode checks if a node has finished decommission
  private long decommissionRecheckInterval;
  // default block size of a file
  private long defaultBlockSize = 0;
  private int replIndex = 0; // last datanode used for replication work
  static int REPL_WORK_PER_ITERATION = 32; // max percent datanodes per iteration

  public static FSNamesystem fsNamesystemObject;
  private String localMachine;
  private int port;
  private SafeModeInfo safeMode;  // safe mode information
  private Host2NodesMap host2DataNodeMap = new Host2NodesMap();
    
  // datanode networktoplogy
  NetworkTopology clusterMap = new NetworkTopology();
  // for block replicas placement
  ReplicationTargetChooser replicator;

  private HostsFileReader hostsReader; 
  private Daemon dnthread = null;

  // can fs-image be rolled?
  volatile private CheckpointStates ckptState = CheckpointStates.START; 

  private static final SimpleDateFormat DATE_FORM =
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");


  /**
   * dirs is a list of directories where the filesystem directory state 
   * is stored
   */
  public FSNamesystem(String hostname,
                      int port,
                      NameNode nn, Configuration conf) throws IOException {
    fsNamesystemObject = this;
    setConfigurationParameters(conf);

    this.localMachine = hostname;
    this.port = port;
    this.dir = new FSDirectory(this, conf);
    StartupOption startOpt = NameNode.getStartupOption(conf);
    this.dir.loadFSImage(getNamespaceDirs(conf), startOpt);
    this.safeMode = new SafeModeInfo(conf);
    setBlockTotal();
    pendingReplications = new PendingReplicationBlocks(
                            conf.getInt("dfs.replication.pending.timeout.sec", 
                                        -1) * 1000);
    this.hbthread = new Daemon(new HeartbeatMonitor());
    this.lmthread = new Daemon(new LeaseMonitor());
    this.replthread = new Daemon(new ReplicationMonitor());
    hbthread.start();
    lmthread.start();
    replthread.start();
    this.systemStart = now();
    this.startTime = new Date(systemStart); 
        
    this.hostsReader = new HostsFileReader(conf.get("dfs.hosts",""),
                                           conf.get("dfs.hosts.exclude",""));
    this.dnthread = new Daemon(new DecommissionedMonitor());
    dnthread.start();

    this.infoPort = conf.getInt("dfs.info.port", 50070);
    this.infoBindAddress = conf.get("dfs.info.bindAddress", "0.0.0.0");
    this.infoServer = new StatusHttpServer("dfs", infoBindAddress, infoPort, false);
    this.infoServer.setAttribute("name.system", this);
    this.infoServer.setAttribute("name.node", nn);
    this.infoServer.setAttribute("name.conf", conf);
    this.infoServer.addServlet("fsck", "/fsck", FsckServlet.class);
    this.infoServer.addServlet("getimage", "/getimage", GetImageServlet.class);
    this.infoServer.addServlet("listPaths", "/listPaths/*", ListPathsServlet.class);
    this.infoServer.addServlet("data", "/data/*", FileDataServlet.class);
    this.infoServer.start();
        
    // The web-server port can be ephemeral... ensure we have the correct info
    this.infoPort = this.infoServer.getPort();
    conf.setInt("dfs.info.port", this.infoPort); 
    LOG.info("Web-server up at: " + conf.get("dfs.info.port"));
  }

  static Collection<File> getNamespaceDirs(Configuration conf) {
    String[] dirNames = conf.getStrings("dfs.name.dir");
    if (dirNames == null)
      dirNames = new String[] {"/tmp/hadoop/dfs/name"};
    Collection<File> dirs = new ArrayList<File>(dirNames.length);
    for(int idx = 0; idx < dirNames.length; idx++) {
      dirs.add(new File(dirNames[idx]));
    }
    return dirs;
  }

  /**
   * dirs is a list of directories where the filesystem directory state 
   * is stored
   */
  FSNamesystem(FSImage fsImage, Configuration conf) throws IOException {
    fsNamesystemObject = this;
    setConfigurationParameters(conf);
    this.dir = new FSDirectory(fsImage, this, conf);
  }

  /**
   * Initializes some of the members from configuration
   */
  private void setConfigurationParameters(Configuration conf) 
                                          throws IOException {
    this.replicator = new ReplicationTargetChooser(
                         conf.getBoolean("dfs.replication.considerLoad", true),
                         this,
                         clusterMap);
    this.defaultReplication = conf.getInt("dfs.replication", 3);
    this.maxReplication = conf.getInt("dfs.replication.max", 512);
    this.minReplication = conf.getInt("dfs.replication.min", 1);
    if (minReplication <= 0)
      throw new IOException(
                            "Unexpected configuration parameters: dfs.replication.min = " 
                            + minReplication
                            + " must be greater than 0");
    if (maxReplication >= (int)Short.MAX_VALUE)
      throw new IOException(
                            "Unexpected configuration parameters: dfs.replication.max = " 
                            + maxReplication + " must be less than " + (Short.MAX_VALUE));
    if (maxReplication < minReplication)
      throw new IOException(
                            "Unexpected configuration parameters: dfs.replication.min = " 
                            + minReplication
                            + " must be less than dfs.replication.max = " 
                            + maxReplication);
    this.maxReplicationStreams = conf.getInt("dfs.max-repl-streams", 2);
    long heartbeatInterval = conf.getLong("dfs.heartbeat.interval", 3) * 1000;
    this.heartbeatRecheckInterval = conf.getInt(
        "heartbeat.recheck.interval", 5 * 60 * 1000); // 5 minutes
    this.heartbeatExpireInterval = 2 * heartbeatRecheckInterval +
      10 * heartbeatInterval;
    this.replicationRecheckInterval = 3 * 1000; //  3 second
    this.decommissionRecheckInterval = conf.getInt(
                                                   "dfs.namenode.decommission.interval",
                                                   5 * 60 * 1000);    
    this.defaultBlockSize = conf.getLong("dfs.block.size", DEFAULT_BLOCK_SIZE);
  }

  /** Return the FSNamesystem object
   * 
   */
  public static FSNamesystem getFSNamesystem() {
    return fsNamesystemObject;
  } 

  NamespaceInfo getNamespaceInfo() {
    return new NamespaceInfo(dir.fsImage.getNamespaceID(),
                             dir.fsImage.getCTime(),
                             getDistributedUpgradeVersion());
  }

  /** Close down this filesystem manager.
   * Causes heartbeat and lease daemons to stop; waits briefly for
   * them to finish, but a short timeout returns control back to caller.
   */
  public void close() {
    fsRunning = false;
    try {
      if (pendingReplications != null) pendingReplications.stop();
      if (infoServer != null) infoServer.stop();
      if (hbthread != null) hbthread.interrupt();
      if (replthread != null) replthread.interrupt();
      if (dnthread != null) dnthread.interrupt();
      if (smmthread != null) smmthread.interrupt();
    } catch (InterruptedException ie) {
    } finally {
      // using finally to ensure we also wait for lease daemon
      try {
        if (lmthread != null) {
          lmthread.interrupt();
          lmthread.join(3000);
        }
      } catch (InterruptedException ie) {
      } finally {
        try {
          dir.close();
        } catch (IOException ex) {
          // do nothing
        }
      }
    }
  }

  /**
   * Dump all metadata into specified file
   */
  void metaSave(String filename) throws IOException {
    File file = new File(System.getProperty("hadoop.log.dir"), 
                         filename);
    PrintWriter out = new PrintWriter(new BufferedWriter(
                                                         new FileWriter(file, true)));
 

    //
    // Dump contents of neededReplication
    //
    synchronized (neededReplications) {
      out.println("Metasave: Blocks waiting for replication: " + 
                  neededReplications.size());
      if (neededReplications.size() > 0) {
        for (Iterator<Block> it = neededReplications.iterator(); 
             it.hasNext();) {
          Block block = it.next();
          out.print(block);
          for (Iterator<DatanodeDescriptor> jt = blocksMap.nodeIterator(block);
               jt.hasNext();) {
            DatanodeDescriptor node = jt.next();
            out.print(" " + node + " : ");
          }
          out.println("");
        }
      }
    }

    //
    // Dump blocks from pendingReplication
    //
    pendingReplications.metaSave(out);

    //
    // Dump blocks that are waiting to be deleted
    //
    dumpRecentInvalidateSets(out);

    //
    // Dump all datanodes
    //
    datanodeDump(out);

    out.flush();
    out.close();
  }

  long getDefaultBlockSize() {
    return defaultBlockSize;
  }
    
  /* get replication factor of a block */
  private int getReplication(Block block) {
    INodeFile fileINode = blocksMap.getINode(block);
    if (fileINode == null) { // block does not belong to any file
      return 0;
    }
    assert !fileINode.isDirectory() : "Block cannot belong to a directory.";
    return fileINode.getReplication();
  }

  /* updates a block in under replication queue */
  synchronized void updateNeededReplications(Block block,
                        int curReplicasDelta, int expectedReplicasDelta) {
    NumberReplicas repl = countNodes(block);
    int curExpectedReplicas = getReplication(block);
    neededReplications.update(block, 
                              repl.liveReplicas(), 
                              repl.decommissionedReplicas(),
                              curExpectedReplicas,
                              curReplicasDelta, expectedReplicasDelta);
  }

  /**
   * Used only during DFS upgrade for block level CRCs (HADOOP-1134).
   * This returns information for a given blocks that includes:
   * <li> full path name for the file that contains the block.
   * <li> offset of first byte of the block.
   * <li> file length and length of the block.
   * <li> all block locations for the crc file (".file.crc").
   * <li> replication for crc file.
   * When replicas is true, it includes replicas of the block.
   */
  public synchronized BlockCrcInfo blockCrcInfo(
                           Block block,
                           BlockCrcUpgradeObjectNamenode namenodeUpgradeObj,
                           boolean replicas) {
    BlockCrcInfo crcInfo = new BlockCrcInfo();
    crcInfo.status = BlockCrcInfo.STATUS_ERROR;
    
    INodeFile fileINode = blocksMap.getINode(block);
    if ( fileINode == null || fileINode.isDirectory() ) {
      // Most probably reason is that this block does not exist
      if (blocksMap.getStoredBlock(block) == null) {
        crcInfo.status = BlockCrcInfo.STATUS_UNKNOWN_BLOCK;
      } else {
        LOG.warn("getBlockCrcInfo(): Could not find file for " + block);
      }
      return crcInfo;
    }

    crcInfo.fileName = "localName:" + fileINode.getLocalName();
    
    // Find the offset and length for this block.
    Block[] fileBlocks = fileINode.getBlocks();
    crcInfo.blockLen = -1;
    if ( fileBlocks != null ) {
      for ( Block b:fileBlocks ) {
        if ( block.equals(b) ) {
          crcInfo.blockLen = b.getNumBytes();
        }
        if ( crcInfo.blockLen < 0 ) {
          crcInfo.startOffset += b.getNumBytes();
        }
        crcInfo.fileSize += b.getNumBytes();
      }
    }

    if ( crcInfo.blockLen < 0 ) {
      LOG.warn("blockCrcInfo(): " + block + 
               " could not be found in blocks for " + crcInfo.fileName);
      return crcInfo;
    }
    
    String fileName = fileINode.getLocalName();    
    if ( fileName.startsWith(".") && fileName.endsWith(".crc") ) {
      crcInfo.status = BlockCrcInfo.STATUS_CRC_BLOCK;
      return crcInfo;
    }

    if (replicas) {
      // include block replica locations, instead of crcBlocks
      crcInfo.blockLocationsIncluded = true;
      
      DatanodeInfo[] dnInfo = new DatanodeInfo[blocksMap.numNodes(block)];
      Iterator<DatanodeDescriptor> it = blocksMap.nodeIterator(block);
      for (int i=0; it != null && it.hasNext(); i++ ) {
        dnInfo[i] = new DatanodeInfo(it.next());
      }
      crcInfo.blockLocations = new LocatedBlock(block, dnInfo, 
                                                crcInfo.startOffset);
    } else {

      //Find CRC file
      BlockCrcUpgradeObjectNamenode.INodeMapEntry entry =
                                namenodeUpgradeObj.getINodeMapEntry(fileINode);
      
      if (entry == null || entry.parent == null) {
        LOG.warn("Could not find parent INode for " + fileName + "  " + block);
        return crcInfo;
      }
      
      crcInfo.fileName = entry.getAbsoluteName();
      
      String crcName = "." + fileName + ".crc";
      INode iNode = entry.getParentINode().getChild(crcName);
      if (iNode == null || iNode.isDirectory()) {
        // Should we log this?
        crcInfo.status = BlockCrcInfo.STATUS_NO_CRC_DATA;
        return crcInfo;
      }

      INodeFile crcINode = (INodeFile)iNode;
      Block[] blocks = crcINode.getBlocks();
      if ( blocks == null )  {
        LOG.warn("getBlockCrcInfo(): could not find blocks for crc file for " +
                 crcInfo.fileName);
        return crcInfo;
      }

      crcInfo.crcBlocks = new LocatedBlock[ blocks.length ];
      for (int i=0; i<blocks.length; i++) {
        DatanodeInfo[] dnArr = new DatanodeInfo[ blocksMap.numNodes(blocks[i]) ];
        int idx = 0;
        for (Iterator<DatanodeDescriptor> it = blocksMap.nodeIterator(blocks[i]); 
        it.hasNext();) { 
          dnArr[ idx++ ] = it.next();
        }
        crcInfo.crcBlocks[i] = new LocatedBlock(blocks[i], dnArr);
      }

      crcInfo.crcReplication = crcINode.getReplication();
    }
    
    crcInfo.status = BlockCrcInfo.STATUS_DATA_BLOCK;
    return crcInfo;
  }
  
  /////////////////////////////////////////////////////////
  //
  // These methods are called by secondary namenodes
  //
  /////////////////////////////////////////////////////////
  /**
   * return a list of blocks & their locations on <code>datanode</code> whose
   * total size is <code>size</code>
   * 
   * @param datanode on which blocks are located
   * @parm size total size of blocks
   */
  synchronized BlocksWithLocations getBlocks(DatanodeID datanode, long size)
      throws IOException {
    DatanodeDescriptor node = getDatanode(datanode);
    if (node == null) {
      NameNode.stateChangeLog.warn("BLOCK* NameSystem.getBlocks: "
          + "Asking for blocks from an unrecorded node " + datanode.getName());
      throw new IllegalArgumentException(
          "Unexpected exception.  Got getBlocks message for datanode " + 
          datanode.getName() + ", but there is no info for it");
    }

    int numBlocks = node.numBlocks();
    if(numBlocks == 0) {
      return new BlocksWithLocations(new BlockWithLocations[0]);
    }
    Iterator<Block> iter = node.getBlockIterator();
    int startBlock = r.nextInt(numBlocks); // starting from a random block
    // skip blocks
    for(int i=0; i<startBlock; i++) {
      iter.next();
    }
    List<BlockWithLocations> results = new ArrayList<BlockWithLocations>();
    long totalSize = 0;
    while(totalSize<size && iter.hasNext()) {
      totalSize += addBlock(iter.next(), results);
    }
    if(totalSize<size) {
      iter = node.getBlockIterator(); // start from the beginning
      for(int i=0; i<startBlock&&totalSize<size; i++) {
        totalSize += addBlock(iter.next(), results);
      }
    }
    
    return new BlocksWithLocations(
        results.toArray(new BlockWithLocations[results.size()]));
  }
  
  /* Get all valid locations of the block & add the block to results
   * return the length of the added block; 0 if the block is not added
   */
  private long addBlock(Block block, List<BlockWithLocations> results) {
    ArrayList<String> machineSet =
      new ArrayList<String>(blocksMap.numNodes(block));
    for(Iterator<DatanodeDescriptor> it = 
      blocksMap.nodeIterator(block); it.hasNext();) {
      String storageID = it.next().getStorageID();
      // filter invalidate replicas
      Collection<Block> blocks = recentInvalidateSets.get(storageID); 
      if(blocks==null || !blocks.contains(block)) {
        machineSet.add(storageID);
      }
    }
    if(machineSet.size() == 0) {
      return 0;
    } else {
      results.add(new BlockWithLocations(block, 
          machineSet.toArray(new String[machineSet.size()])));
      return block.getNumBytes();
    }
  }

  /////////////////////////////////////////////////////////
  //
  // These methods are called by HadoopFS clients
  //
  /////////////////////////////////////////////////////////
  /**
   * Get block locations within the specified range.
   * 
   * @see ClientProtocol#open(String, long, long)
   * @see ClientProtocol#getBlockLocations(String, long, long)
   */
  LocatedBlocks getBlockLocations(String clientMachine,
                                  String src, 
                                  long offset, 
                                  long length
                                  ) throws IOException {
    if (offset < 0) {
      throw new IOException("Negative offset is not supported. File: " + src );
    }
    if (length < 0) {
      throw new IOException("Negative length is not supported. File: " + src );
    }

    DatanodeDescriptor client = null;
    LocatedBlocks blocks =  getBlockLocations(dir.getFileINode(src), 
                                              offset, length, 
                                              Integer.MAX_VALUE);
    if (blocks == null) {
      return null;
    }
    client = host2DataNodeMap.getDatanodeByHost(clientMachine);
    for (Iterator<LocatedBlock> it = blocks.getLocatedBlocks().iterator();
         it.hasNext();) {
      LocatedBlock block = it.next();
      clusterMap.pseudoSortByDistance(client, 
                                (DatanodeDescriptor[])(block.getLocations()));
    }
    return blocks;
  }
  
  private synchronized LocatedBlocks getBlockLocations(INodeFile inode, 
                                                       long offset, 
                                                       long length,
                                                       int nrBlocksToReturn) {
    if(inode == null) {
      return null;
    }
    Block[] blocks = inode.getBlocks();
    if (blocks == null) {
      return null;
    }
    if (blocks.length == 0) {
      return new LocatedBlocks(inode, new ArrayList<LocatedBlock>(blocks.length));
    }
    List<LocatedBlock> results;
    results = new ArrayList<LocatedBlock>(blocks.length);

    int curBlk = 0;
    long curPos = 0, blkSize = 0;
    int nrBlocks = (blocks[0].getNumBytes() == 0) ? 0 : blocks.length;
    for (curBlk = 0; curBlk < nrBlocks; curBlk++) {
      blkSize = blocks[curBlk].getNumBytes();
      assert blkSize > 0 : "Block of size 0";
      if (curPos + blkSize > offset) {
        break;
      }
      curPos += blkSize;
    }
    
    if (nrBlocks > 0 && curBlk == nrBlocks)   // offset >= end of file
      return null;
    
    long endOff = offset + length;
    
    do {
      // get block locations
      int numNodes = blocksMap.numNodes(blocks[curBlk]);
      DatanodeDescriptor[] machineSet = new DatanodeDescriptor[numNodes];
      if (numNodes > 0) {
        numNodes = 0;
        for(Iterator<DatanodeDescriptor> it = 
            blocksMap.nodeIterator(blocks[curBlk]); it.hasNext();) {
          machineSet[numNodes++] = it.next();
        }
      }
      results.add(new LocatedBlock(blocks[curBlk], machineSet, curPos));
      curPos += blocks[curBlk].getNumBytes();
      curBlk++;
    } while (curPos < endOff 
          && curBlk < blocks.length 
          && results.size() < nrBlocksToReturn);
    
    return new LocatedBlocks(inode, results);
  }

  /**
   * Set replication for an existing file.
   * 
   * The NameNode sets new replication and schedules either replication of 
   * under-replicated data blocks or removal of the eccessive block copies 
   * if the blocks are over-replicated.
   * 
   * @see ClientProtocol#setReplication(String, short)
   * @param src file name
   * @param replication new replication
   * @return true if successful; 
   *         false if file does not exist or is a directory
   */
  public boolean setReplication(String src, short replication) 
                                throws IOException {
    boolean status = setReplicationInternal(src, replication);
    getEditLog().logSync();
    return status;
  }

  private synchronized boolean setReplicationInternal(String src, 
                                             short replication
                                             ) throws IOException {
    if (isInSafeMode())
      throw new SafeModeException("Cannot set replication for " + src, safeMode);
    verifyReplication(src, replication, null);

    int[] oldReplication = new int[1];
    Block[] fileBlocks;
    fileBlocks = dir.setReplication(src, replication, oldReplication);
    if (fileBlocks == null)  // file not found or is a directory
      return false;
    int oldRepl = oldReplication[0];
    if (oldRepl == replication) // the same replication
      return true;

    // update needReplication priority queues
    LOG.info("Increasing replication for file " + src 
             + ". New replication is " + replication);
    for(int idx = 0; idx < fileBlocks.length; idx++)
      updateNeededReplications(fileBlocks[idx], 0, replication-oldRepl);
      
    if (oldRepl > replication) {  
      // old replication > the new one; need to remove copies
      LOG.info("Reducing replication for file " + src 
               + ". New replication is " + replication);
      for(int idx = 0; idx < fileBlocks.length; idx++)
        proccessOverReplicatedBlock(fileBlocks[idx], replication, null, null);
    }
    return true;
  }
    
  public long getPreferredBlockSize(String filename) throws IOException {
    return dir.getPreferredBlockSize(filename);
  }
    
  /**
   * Check whether the replication parameter is within the range
   * determined by system configuration.
   */
  private void verifyReplication(String src, 
                                 short replication, 
                                 String clientName 
                                 ) throws IOException {
    String text = "file " + src 
      + ((clientName != null) ? " on client " + clientName : "")
      + ".\n"
      + "Requested replication " + replication;

    if (replication > maxReplication)
      throw new IOException(text + " exceeds maximum " + maxReplication);
      
    if (replication < minReplication)
      throw new IOException( 
                            text + " is less than the required minimum " + minReplication);
  }

  void startFile(String src, String holder, String clientMachine, 
                 boolean overwrite, short replication, long blockSize
                ) throws IOException {
    startFileInternal(src, holder, clientMachine, overwrite,
                      replication, blockSize);
    getEditLog().logSync();
  }

  /**
   * The client would like to create a new block for the indicated
   * filename.  Return an array that consists of the block, plus a set 
   * of machines.  The first on this list should be where the client 
   * writes data.  Subsequent items in the list must be provided in
   * the connection to the first datanode.
   * Return an array that consists of the block, plus a set
   * of machines
   * @throws IOException if the filename is invalid
   *         {@link FSDirectory#isValidToCreate(String)}.
   */
  synchronized void startFileInternal(String src, 
                                              String holder, 
                                              String clientMachine, 
                                              boolean overwrite,
                                              short replication,
                                              long blockSize
                                             	) throws IOException {
    NameNode.stateChangeLog.debug("DIR* NameSystem.startFile: file "
                                  +src+" for "+holder+" at "+clientMachine);
    if (isInSafeMode())
      throw new SafeModeException("Cannot create file" + src, safeMode);
    if (!isValidName(src)) {
      throw new IOException("Invalid file name: " + src);      	  
    }
    try {
      INode myFile = dir.getFileINode(src);
      if (myFile != null && myFile.isUnderConstruction()) {
        INodeFileUnderConstruction pendingFile = (INodeFileUnderConstruction) myFile;
        //
        // If the file is under construction , then it must be in our
        // leases. Find the appropriate lease record.
        //
        Lease lease = getLease(holder);
        //
        // We found the lease for this file. And surprisingly the original
        // holder is trying to recreate this file. This should never occur.
        //
        if (lease != null) {
          throw new AlreadyBeingCreatedException(
                                                 "failed to create file " + src + " for " + holder +
                                                 " on client " + clientMachine + 
                                                 " because current leaseholder is trying to recreate file.");
        }
        //
        // Find the original holder.
        //
        lease = getLease(pendingFile.getClientName());
        if (lease == null) {
          throw new AlreadyBeingCreatedException(
                                                 "failed to create file " + src + " for " + holder +
                                                 " on client " + clientMachine + 
                                                 " because pendingCreates is non-null but no leases found.");
        }
        //
        // If the original holder has not renewed in the last SOFTLIMIT 
        // period, then reclaim all resources and allow this request 
        // to proceed. Otherwise, prevent this request from creating file.
        //
        if (lease.expiredSoftLimit()) {
          synchronized (sortedLeases) {
            lease.releaseLocks();
            removeLease(lease.getHolder());
            LOG.info("startFile: Removing lease " + lease + " ");
            if (!sortedLeases.remove(lease)) {
              LOG.error("startFile: Unknown failure trying to remove " + lease + 
                        " from lease set.");
            }
          }
        } else {
          throw new AlreadyBeingCreatedException(
                                                 "failed to create file " + src + " for " + holder +
                                                 " on client " + clientMachine + 
                                                 ", because this file is already being created by " +
                                                 pendingFile.getClientName() + 
                                                 " on " + pendingFile.getClientMachine());
        }
      }

      try {
        verifyReplication(src, replication, clientMachine);
      } catch(IOException e) {
        throw new IOException("failed to create "+e.getMessage());
      }
      if (!dir.isValidToCreate(src)) {
        if (overwrite) {
          delete(src);
        } else {
          throw new IOException("failed to create file " + src 
                                +" on client " + clientMachine
                                +" either because the filename is invalid or the file exists");
        }
      }

      DatanodeDescriptor clientNode = 
        host2DataNodeMap.getDatanodeByHost(clientMachine);

      synchronized (sortedLeases) {
        Lease lease = getLease(holder);
        if (lease == null) {
          lease = new Lease(holder);
          putLease(holder, lease);
          sortedLeases.add(lease);
        } else {
          sortedLeases.remove(lease);
          lease.renew();
          sortedLeases.add(lease);
        }
        lease.startedCreate(src);
      }

      //
      // Now we can add the name to the filesystem. This file has no
      // blocks associated with it.
      //
      INode newNode = dir.addFile(src, replication, blockSize,
                                  holder, 
                                  clientMachine, 
                                  clientNode);
      if (newNode == null) {
        throw new IOException("DIR* NameSystem.startFile: " +
                              "Unable to add file to namespace.");
      }
    } catch (IOException ie) {
      NameNode.stateChangeLog.warn("DIR* NameSystem.startFile: "
                                   +ie.getMessage());
      throw ie;
    }

    NameNode.stateChangeLog.debug("DIR* NameSystem.startFile: "
                                  +"add "+src+" to namespace for "+holder);
  }

  /**
   * The client would like to obtain an additional block for the indicated
   * filename (which is being written-to).  Return an array that consists
   * of the block, plus a set of machines.  The first on this list should
   * be where the client writes data.  Subsequent items in the list must
   * be provided in the connection to the first datanode.
   *
   * Make sure the previous blocks have been reported by datanodes and
   * are replicated.  Will return an empty 2-elt array if we want the
   * client to "try again later".
   */
  public LocatedBlock getAdditionalBlock(String src, 
                                         String clientName
                                         ) throws IOException {
    long fileLength, blockSize;
    int replication;
    DatanodeDescriptor clientNode = null;
    Block newBlock = null;

    NameNode.stateChangeLog.debug("BLOCK* NameSystem.getAdditionalBlock: file "
                                  +src+" for "+clientName);

    synchronized (this) {
      if (isInSafeMode()) {
        throw new SafeModeException("Cannot add block to " + src, safeMode);
      }

      //
      // make sure that we still have the lease on this file
      //
      INodeFile iFile = dir.getFileINode(src);
      if (iFile == null || !iFile.isUnderConstruction()) {
        throw new LeaseExpiredException("No lease on " + src);
      }
      INodeFileUnderConstruction pendingFile = (INodeFileUnderConstruction) iFile;
      if (!pendingFile.getClientName().equals(clientName)) {
        throw new LeaseExpiredException("Lease mismatch on " + src + " owned by "
                                        + pendingFile.getClientName()
                                        + " and appended by " + clientName);
      }

      //
      // If we fail this, bad things happen!
      //
      if (!checkFileProgress(pendingFile, false)) {
        throw new NotReplicatedYetException("Not replicated yet:" + src);
      }
      fileLength = pendingFile.computeContentsLength();
      blockSize = pendingFile.getPreferredBlockSize();
      clientNode = pendingFile.getClientNode();
      replication = (int)pendingFile.getReplication();
      newBlock = allocateBlock(src, pendingFile);
    }

    DatanodeDescriptor targets[] = replicator.chooseTarget(replication,
                                                           clientNode,
                                                           null,
                                                           blockSize);
    if (targets.length < this.minReplication) {
      // if we could not find any targets, remove this block from file
      synchronized (this) {
        INodeFile iFile = dir.getFileINode(src);
        if (iFile != null && iFile.isUnderConstruction()) {
          INodeFileUnderConstruction pendingFile = (INodeFileUnderConstruction)iFile;
          if (pendingFile.getClientName().equals(clientName)) {
            dir.removeBlock(src, pendingFile, newBlock);
          }
        }
      }
      throw new IOException("File " + src + " could only be replicated to " +
                            targets.length + " nodes, instead of " +
                            minReplication);
    }
        
    // Create next block
    return new LocatedBlock(newBlock, targets, fileLength);
  }

  /**
   * The client would like to let go of the given block
   */
  public synchronized boolean abandonBlock(Block b, String src) throws IOException {
    //
    // Remove the block from the pending creates list
    //
    NameNode.stateChangeLog.debug("BLOCK* NameSystem.abandonBlock: "
                                  +b.getBlockName()+"of file "+src);
    INode file = dir.getFileINode(src);
    if (file != null) {
      dir.removeBlock(src, file, b);
    }
    NameNode.stateChangeLog.debug("BLOCK* NameSystem.abandonBlock: "
                                    + b.getBlockName()
                                    + " is removed from pendingCreates");
    return true;
  }

  /**
   * Abandon the entire file in progress
   */
  public synchronized void abandonFileInProgress(String src, 
                                                 String holder
                                                 ) throws IOException {
    NameNode.stateChangeLog.debug("DIR* NameSystem.abandonFileInProgress:" + src);
    synchronized (sortedLeases) {
      // find the lease
      Lease lease = getLease(holder);
      if (lease != null) {
        // remove the file from the lease
        if (lease.completedCreate(src)) {
          // if we found the file in the lease, remove it from pendingCreates
          internalReleaseCreate(src, holder);
        } else {
          LOG.info("Attempt by " + holder + 
                   " to release someone else's create lock on " + src);
        }
      } else {
        LOG.info("Attempt to release a lock from an unknown lease holder "
                 + holder + " for " + src);
      }
    }
  }

  /**
   * The FSNamesystem will already know the blocks that make up the file.
   * Before we return, we make sure that all the file's blocks have 
   * been reported by datanodes and are replicated correctly.
   */
  public int completeFile(String src, String holder) throws IOException {
    int status = completeFileInternal(src, holder);
    getEditLog().logSync();
    return status;
  }

  private synchronized int completeFileInternal(String src, 
                                                String holder) throws IOException {
    NameNode.stateChangeLog.debug("DIR* NameSystem.completeFile: " + src + " for " + holder);
    if (isInSafeMode())
      throw new SafeModeException("Cannot complete file " + src, safeMode);
    INode iFile = dir.getFileINode(src);
    INodeFileUnderConstruction pendingFile = null;
    Block[] fileBlocks = null;

    if (iFile != null && iFile.isUnderConstruction()) {
      pendingFile = (INodeFileUnderConstruction) iFile;
      fileBlocks =  dir.getFileBlocks(src);
    }
    if (fileBlocks == null ) {    
      NameNode.stateChangeLog.warn("DIR* NameSystem.completeFile: "
                                   + "failed to complete " + src
                                   + " because dir.getFileBlocks() is null " + 
                                   " and pendingFile is " + 
                                   ((pendingFile == null) ? "null" : 
                                     ("from " + pendingFile.getClientMachine()))
                                  );                      
      return OPERATION_FAILED;
    } else if (!checkFileProgress(pendingFile, true)) {
      return STILL_WAITING;
    }
        
    // The file is no longer pending.
    // Create permanent INode, update blockmap
    INodeFile newFile = pendingFile.convertToInodeFile();
    dir.replaceNode(src, pendingFile, newFile);

    // persist block allocations for this file
    dir.persistBlocks(src, newFile);

    NameNode.stateChangeLog.debug("DIR* NameSystem.completeFile: " + src
                                  + " blocklist persisted");

    synchronized (sortedLeases) {
      Lease lease = getLease(holder);
      if (lease != null) {
        lease.completedCreate(src);
        if (!lease.hasLocks()) {
          removeLease(holder);
          sortedLeases.remove(lease);
        }
      }
    }

    //
    // REMIND - mjc - this should be done only after we wait a few secs.
    // The namenode isn't giving datanodes enough time to report the
    // replicated blocks that are automatically done as part of a client
    // write.
    //

    // Now that the file is real, we need to be sure to replicate
    // the blocks.
    int numExpectedReplicas = pendingFile.getReplication();
    Block[] pendingBlocks = pendingFile.getBlocks();
    int nrBlocks = pendingBlocks.length;
    for (int i = 0; i < nrBlocks; i++) {
      // filter out containingNodes that are marked for decommission.
      NumberReplicas number = countNodes(pendingBlocks[i]);
      if (number.liveReplicas() < numExpectedReplicas) {
        neededReplications.add(pendingBlocks[i], 
                               number.liveReplicas(), 
                               number.decommissionedReplicas,
                               numExpectedReplicas);
      }
    }
    return COMPLETE_SUCCESS;
  }

  static Random randBlockId = new Random();
    
  /**
   * Allocate a block at the given pending filename
   */
  private Block allocateBlock(String src, INode file) throws IOException {
    Block b = null;
    do {
      b = new Block(FSNamesystem.randBlockId.nextLong(), 0);
    } while (isValidBlock(b));
    b = dir.addBlock(src, file, b);
    NameNode.stateChangeLog.info("BLOCK* NameSystem.allocateBlock: "
                                 +src+ ". "+b.getBlockName());
    return b;
  }

  /**
   * Check that the indicated file's blocks are present and
   * replicated.  If not, return false. If checkall is true, then check
   * all blocks, otherwise check only penultimate block.
   */
  synchronized boolean checkFileProgress(INodeFile v, boolean checkall) {
    if (checkall) {
      //
      // check all blocks of the file.
      //
      for (Block block: v.getBlocks()) {
        if (blocksMap.numNodes(block) < this.minReplication) {
          return false;
        }
      }
    } else {
      //
      // check the penultimate block of this file
      //
      Block b = v.getPenultimateBlock();
      if (b != null) {
        if (blocksMap.numNodes(b) < this.minReplication) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Adds block to list of blocks which will be invalidated on 
   * specified datanode.
   */
  private void addToInvalidates(Block b, DatanodeInfo n) {
    Collection<Block> invalidateSet = recentInvalidateSets.get(n.getStorageID());
    if (invalidateSet == null) {
      invalidateSet = new ArrayList<Block>();
      recentInvalidateSets.put(n.getStorageID(), invalidateSet);
    }
    invalidateSet.add(b);
  }

  /**
   * dumps the contents of recentInvalidateSets
   */
  private synchronized void dumpRecentInvalidateSets(PrintWriter out) {
    Collection<Collection<Block>> values = recentInvalidateSets.values();
    Iterator<Map.Entry<String,Collection<Block>>> it = 
      recentInvalidateSets.entrySet().iterator();
    if (values.size() == 0) {
      out.println("Metasave: Blocks waiting deletion: 0");
      return;
    }
    out.println("Metasave: Blocks waiting deletion from " +
                values.size() + " datanodes.");
    while (it.hasNext()) {
      Map.Entry<String,Collection<Block>> entry = it.next();
      String storageId = entry.getKey();
      DatanodeDescriptor node = datanodeMap.get(storageId);
      Collection<Block> blklist = entry.getValue();
      if (blklist.size() > 0) {
        out.print(node.getName());
        for (Iterator jt = blklist.iterator(); jt.hasNext();) {
          Block block = (Block) jt.next();
          out.print(" " + block); 
        }
        out.println("");
      }
    }
  }

  /**
   * Invalidates the given block on the given datanode.
   */
  public synchronized void invalidateBlock(Block blk, DatanodeInfo dn)
    throws IOException {
    NameNode.stateChangeLog.info("DIR* NameSystem.invalidateBlock: " 
                                 + blk.getBlockName() + " on " 
                                 + dn.getName());
    if (isInSafeMode()) {
      throw new SafeModeException("Cannot invalidate block " + blk.getBlockName(), safeMode);
    }

    // Check how many copies we have of the block.  If we have at least one
    // copy on a live node, then we can delete it. 
    int count = countNodes(blk).liveReplicas();
    if (count > 1) {
      addToInvalidates(blk, dn);
      removeStoredBlock(blk, getDatanode(dn));
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.invalidateBlocks: "
                                   + blk.getBlockName() + " on " 
                                   + dn.getName() + " listed for deletion.");
    } else {
      NameNode.stateChangeLog.info("BLOCK* NameSystem.invalidateBlocks: "
                                   + blk.getBlockName() + " on " 
                                   + dn.getName() + " is the only copy and was not deleted.");
    }
  }

  ////////////////////////////////////////////////////////////////
  // Here's how to handle block-copy failure during client write:
  // -- As usual, the client's write should result in a streaming
  // backup write to a k-machine sequence.
  // -- If one of the backup machines fails, no worries.  Fail silently.
  // -- Before client is allowed to close and finalize file, make sure
  // that the blocks are backed up.  Namenode may have to issue specific backup
  // commands to make up for earlier datanode failures.  Once all copies
  // are made, edit namespace and return to client.
  ////////////////////////////////////////////////////////////////

  public boolean renameTo(String src, String dst) throws IOException {
    boolean status = renameToInternal(src, dst);
    getEditLog().logSync();
    return status;
  }

  /**
   * Change the indicated filename.
   */
  public synchronized boolean renameToInternal(String src, String dst) throws IOException {
    NameNode.stateChangeLog.debug("DIR* NameSystem.renameTo: " + src + " to " + dst);
    if (isInSafeMode())
      throw new SafeModeException("Cannot rename " + src, safeMode);
    if (!isValidName(dst)) {
      throw new IOException("Invalid name: " + dst);
    }
    return dir.renameTo(src, dst);
  }

  /**
   * Remove the indicated filename from the namespace.  This may
   * invalidate some blocks that make up the file.
   */
  public boolean delete(String src) throws IOException {
    boolean status = deleteInternal(src, true);
    getEditLog().logSync();
    return status;
  }

  /**
   * An internal delete function that does not enforce safe mode
   */
  boolean deleteInSafeMode(String src) throws IOException {
    boolean status = deleteInternal(src, false);
    getEditLog().logSync();
    return status;
  }
  /**
   * Remove the indicated filename from the namespace.  This may
   * invalidate some blocks that make up the file.
   */
  private synchronized boolean deleteInternal(String src, 
                                              boolean enforceSafeMode) 
                                              throws IOException {
    NameNode.stateChangeLog.debug("DIR* NameSystem.delete: " + src);
    if (enforceSafeMode && isInSafeMode())
      throw new SafeModeException("Cannot delete " + src, safeMode);
    Block deletedBlocks[] = dir.delete(src);
    if (deletedBlocks != null) {
      for (int i = 0; i < deletedBlocks.length; i++) {
        Block b = deletedBlocks[i];
                
        for (Iterator<DatanodeDescriptor> it = 
               blocksMap.nodeIterator(b); it.hasNext();) {
          DatanodeDescriptor node = it.next();
          addToInvalidates(b, node);
          NameNode.stateChangeLog.info("BLOCK* NameSystem.delete: "
                                        + b.getBlockName() + " is added to invalidSet of " 
                                        + node.getName());
        }
      }
    }

    return (deletedBlocks != null);
  }

  /**
   * Return whether the given filename exists
   */
  public boolean exists(String src) {
    if (dir.getFileBlocks(src) != null || dir.isDir(src)) {
      return true;
    } else {
      return false;
    }
  }

  /**
   * Whether the given name is a directory
   */
  public boolean isDir(String src) {
    return dir.isDir(src);
  }

  /* Get the file info for a specific file.
   * @param src The string representation of the path to the file
   * @throws IOException if file does not exist
   * @return object containing information regarding the file
   */
  DFSFileInfo getFileInfo(String src) throws IOException {
    return dir.getFileInfo(src);
  }

  /**
   * Whether the pathname is valid.  Currently prohibits relative paths, 
   * and names which contain a ":" or "/" 
   */
  static boolean isValidName(String src) {
      
    // Path must be absolute.
    if (!src.startsWith(Path.SEPARATOR)) {
      return false;
    }
      
    // Check for ".." "." ":" "/"
    StringTokenizer tokens = new StringTokenizer(src, Path.SEPARATOR);
    while(tokens.hasMoreTokens()) {
      String element = tokens.nextToken();
      if (element.equals("..") || 
          element.equals(".")  ||
          (element.indexOf(":") >= 0)  ||
          (element.indexOf("/") >= 0)) {
        return false;
      }
    }
    return true;
  }
  /**
   * Create all the necessary directories
   */
  public boolean mkdirs(String src) throws IOException {
    boolean status = mkdirsInternal(src);
    getEditLog().logSync();
    return status;
  }
    
  /**
   * Create all the necessary directories
   */
  private synchronized boolean mkdirsInternal(String src) throws IOException {
    boolean    success;
    NameNode.stateChangeLog.debug("DIR* NameSystem.mkdirs: " + src);
    if (isInSafeMode())
      throw new SafeModeException("Cannot create directory " + src, safeMode);
    if (!isValidName(src)) {
      throw new IOException("Invalid directory name: " + src);
    }
    success = dir.mkdirs(src, now());
    if (!success) {
      throw new IOException("Invalid directory name: " + src);
    }
    return success;
  }

  /* Get the size of the specified directory subtree.
   * @param src The string representation of the path
   * @throws IOException if path does not exist
   * @return size in bytes
   */
  long getContentLength(String src) throws IOException {
    return dir.getContentLength(src);
  }

  /************************************************************
   * A Lease governs all the locks held by a single client.
   * For each client there's a corresponding lease, whose
   * timestamp is updated when the client periodically
   * checks in.  If the client dies and allows its lease to
   * expire, all the corresponding locks can be released.
   *************************************************************/
  class Lease implements Comparable<Lease> {
    private StringBytesWritable holder;
    private long lastUpdate;
    private Collection<StringBytesWritable> locks = new TreeSet<StringBytesWritable>();
    private Collection<StringBytesWritable> creates = new TreeSet<StringBytesWritable>();

    public Lease(String holder) throws IOException {
      this.holder = new StringBytesWritable(holder);
      renew();
    }
    public void renew() {
      this.lastUpdate = now();
    }
    /**
     * Returns true if the Hard Limit Timer has expired
     */
    public boolean expiredHardLimit() {
      if (now() - lastUpdate > LEASE_HARDLIMIT_PERIOD) {
        return true;
      }
      return false;
    }
    /**
     * Returns true if the Soft Limit Timer has expired
     */
    public boolean expiredSoftLimit() {
      if (now() - lastUpdate > LEASE_SOFTLIMIT_PERIOD) {
        return true;
      }
      return false;
    }
    public void obtained(String src) throws IOException {
      locks.add(new StringBytesWritable(src));
    }
    public void released(String src) throws IOException {
      locks.remove(new StringBytesWritable(src));
    }
    public void startedCreate(String src) throws IOException {
      creates.add(new StringBytesWritable(src));
    }
    public boolean completedCreate(String src) throws IOException {
      return creates.remove(new StringBytesWritable(src));
    }
    public boolean hasLocks() {
      return (locks.size() + creates.size()) > 0;
    }
    public void releaseLocks() throws IOException {
      String holderStr = holder.getString();
      locks.clear();
      for (Iterator<StringBytesWritable> it = creates.iterator(); it.hasNext();)
        internalReleaseCreate(it.next().getString(), holderStr);
      creates.clear();
    }

    /**
     */
    public String toString() {
      return "[Lease.  Holder: " + holder.toString() + ", heldlocks: " +
        locks.size() + ", pendingcreates: " + creates.size() + "]";
    }

    /**
     */
    public int compareTo(Lease o) {
      Lease l1 = this;
      Lease l2 = o;
      long lu1 = l1.lastUpdate;
      long lu2 = l2.lastUpdate;
      if (lu1 < lu2) {
        return -1;
      } else if (lu1 > lu2) {
        return 1;
      } else {
        return l1.holder.compareTo(l2.holder);
      }
    }

    public boolean equals(Object o) {
      if (!(o instanceof Lease)) {
        return false;
      }
      Lease obj = (Lease) o;
      if (lastUpdate == obj.lastUpdate &&
          holder.equals(obj.holder)) {
        return true;
      }
      return false;
    }

    public int hashCode() {
      return holder.hashCode();
    }
    
    String getHolder() throws IOException {
      return holder.getString();
    }
  }
  
  /******************************************************
   * LeaseMonitor checks for leases that have expired,
   * and disposes of them.
   ******************************************************/
  class LeaseMonitor implements Runnable {
    public void run() {
      try {
        while (fsRunning) {
          synchronized (FSNamesystem.this) {
            synchronized (sortedLeases) {
              Lease top;
              while ((sortedLeases.size() > 0) &&
                     ((top = sortedLeases.first()) != null)) {
                if (top.expiredHardLimit()) {
                  top.releaseLocks();
                  leases.remove(top.holder);
                  LOG.info("Removing lease " + top + ", leases remaining: " + sortedLeases.size());
                  if (!sortedLeases.remove(top)) {
                    LOG.info("Unknown failure trying to remove " + top + " from lease set.");
                  }
                } else {
                  break;
                }
              }
            }
          }
          try {
            Thread.sleep(2000);
          } catch (InterruptedException ie) {
          }
        }
      } catch (Exception e) {
        FSNamesystem.LOG.error(StringUtils.stringifyException(e));
      }
    }
  }
  
  private Lease getLease(String holder) throws IOException {
    return leases.get(new StringBytesWritable(holder));
  }
  
  private void putLease(String holder, Lease lease) throws IOException {
    leases.put(new StringBytesWritable(holder), lease);
  }
  
  private void removeLease(String holder) throws IOException {
    leases.remove(new StringBytesWritable(holder));
  }

  /**
   * Move a file that is being written to be immutable.
   * @param src The filename
   * @param holder The datanode that was creating the file
   */
  private void internalReleaseCreate(String src, String holder) throws IOException {
    INodeFile iFile = dir.getFileINode(src);
    if (iFile == null) {
      NameNode.stateChangeLog.warn("DIR* NameSystem.internalReleaseCreate: "
                                   + "attempt to release a create lock on "
                                   + src + " file does not exist.");
      return;
    }
    if (!iFile.isUnderConstruction()) {
      NameNode.stateChangeLog.warn("DIR* NameSystem.internalReleaseCreate: "
                                   + "attempt to release a create lock on "
                                   + src + " but file is already closed.");
      return;
    }
    INodeFileUnderConstruction pendingFile = (INodeFileUnderConstruction) iFile;

    // The last block that was allocated migth not have been used by the
    // client. In this case, the size of the last block would be 0. A fsck
    // will report this block as a missing block because no datanodes have it.
    // Delete this block.
    Block[] blocks = pendingFile.getBlocks();
    if (blocks != null && blocks.length > 1) {
      Block last = blocks[blocks.length - 1];
      if (last.getNumBytes() == 0) {
          pendingFile.removeBlock(last);
      }
    }

    // The file is no longer pending.
    // Create permanent INode, update blockmap
    INodeFile newFile = pendingFile.convertToInodeFile();
    dir.replaceNode(src, pendingFile, newFile);

    // persist block allocations for this file
    dir.persistBlocks(src, newFile);
  
    NameNode.stateChangeLog.debug("DIR* NameSystem.internalReleaseCreate: " + 
                                  src + " is no longer written to by " + 
                                  holder);
  }

  /**
   * Renew the lease(s) held by the given client
   */
  public void renewLease(String holder) throws IOException {
    synchronized (sortedLeases) {
      if (isInSafeMode())
        throw new SafeModeException("Cannot renew lease for " + holder, safeMode);
      Lease lease = getLease(holder);
      if (lease != null) {
        sortedLeases.remove(lease);
        lease.renew();
        sortedLeases.add(lease);
      }
    }
  }

  /**
   * Get a listing of all files at 'src'.  The Object[] array
   * exists so we can return file attributes (soon to be implemented)
   */
  public DFSFileInfo[] getListing(String src) {
    return dir.getListing(src);
  }

  /////////////////////////////////////////////////////////
  //
  // These methods are called by datanodes
  //
  /////////////////////////////////////////////////////////
  /**
   * Register Datanode.
   * <p>
   * The purpose of registration is to identify whether the new datanode
   * serves a new data storage, and will report new data block copies,
   * which the namenode was not aware of; or the datanode is a replacement
   * node for the data storage that was previously served by a different
   * or the same (in terms of host:port) datanode.
   * The data storages are distinguished by their storageIDs. When a new
   * data storage is reported the namenode issues a new unique storageID.
   * <p>
   * Finally, the namenode returns its namespaceID as the registrationID
   * for the datanodes. 
   * namespaceID is a persistent attribute of the name space.
   * The registrationID is checked every time the datanode is communicating
   * with the namenode. 
   * Datanodes with inappropriate registrationID are rejected.
   * If the namenode stops, and then restarts it can restore its 
   * namespaceID and will continue serving the datanodes that has previously
   * registered with the namenode without restarting the whole cluster.
   * 
   * @see DataNode#register()
   */
  public synchronized void registerDatanode(DatanodeRegistration nodeReg,
                                            String networkLocation
                                            ) throws IOException {

    if (!verifyNodeRegistration(nodeReg)) {
      throw new DisallowedDatanodeException(nodeReg);
    }

    String dnAddress = Server.getRemoteAddress();
    if (dnAddress == null) {
      //Mostly not called inside an RPC.
      throw new IOException("Could not find remote address for " +
                            "registration from " + nodeReg.getName());
    }      

    String hostName = nodeReg.getHost();
      
    // update the datanode's name with ip:port
    DatanodeID dnReg = new DatanodeID(dnAddress + ":" + nodeReg.getPort(),
                                      nodeReg.getStorageID(),
                                      nodeReg.getInfoPort());
    nodeReg.updateRegInfo(dnReg);
      
    NameNode.stateChangeLog.info(
                                 "BLOCK* NameSystem.registerDatanode: "
                                 + "node registration from " + nodeReg.getName()
                                 + " storage " + nodeReg.getStorageID());

    DatanodeDescriptor nodeS = datanodeMap.get(nodeReg.getStorageID());
    DatanodeDescriptor nodeN = host2DataNodeMap.getDatanodeByName(nodeReg.getName());
      
    if (nodeN != null && nodeN != nodeS) {
      NameNode.LOG.info("BLOCK* NameSystem.registerDatanode: "
                        + "node from name: " + nodeN.getName());
      // nodeN previously served a different data storage, 
      // which is not served by anybody anymore.
      removeDatanode(nodeN);
      // physically remove node from datanodeMap
      wipeDatanode(nodeN);
      nodeN = null;
    }

    if (nodeS != null) {
      if (nodeN == nodeS) {
        // The same datanode has been just restarted to serve the same data 
        // storage. We do not need to remove old data blocks, the delta will
        // be calculated on the next block report from the datanode
        NameNode.stateChangeLog.debug("BLOCK* NameSystem.registerDatanode: "
                                      + "node restarted.");
      } else {
        // nodeS is found
        /* The registering datanode is a replacement node for the existing 
          data storage, which from now on will be served by a new node.
          If this message repeats, both nodes might have same storageID 
          by (insanely rare) random chance. User needs to restart one of the
          nodes with its data cleared (or user can just remove the StorageID
          value in "VERSION" file under the data directory of the datanode,
          but this is might not work if VERSION file format has changed 
       */        
        NameNode.stateChangeLog.info( "BLOCK* NameSystem.registerDatanode: "
                                      + "node " + nodeS.getName()
                                      + " is replaced by " + nodeReg.getName() + 
                                      " with the same storageID " +
                                      nodeReg.getStorageID());
      }
      // update cluster map
      clusterMap.remove(nodeS);
      nodeS.updateRegInfo(nodeReg);
      nodeS.setNetworkLocation(networkLocation);
      clusterMap.add(nodeS);
      nodeS.setHostName(hostName);
        
      // also treat the registration message as a heartbeat
      synchronized(heartbeats) {
        if( !heartbeats.contains(nodeS)) {
          heartbeats.add(nodeS);
          //update its timestamp
          nodeS.updateHeartbeat(0L, 0L, 0L, 0);
          nodeS.isAlive = true;
        }
      }
      return;
    } 

    // this is a new datanode serving a new data storage
    if (nodeReg.getStorageID().equals("")) {
      // this data storage has never been registered
      // it is either empty or was created by pre-storageID version of DFS
      nodeReg.storageID = newStorageID();
      NameNode.stateChangeLog.debug(
                                    "BLOCK* NameSystem.registerDatanode: "
                                    + "new storageID " + nodeReg.getStorageID() + " assigned.");
    }
    // register new datanode
    DatanodeDescriptor nodeDescr 
      = new DatanodeDescriptor(nodeReg, networkLocation, hostName);
    unprotectedAddDatanode(nodeDescr);
    clusterMap.add(nodeDescr);
      
    // also treat the registration message as a heartbeat
    synchronized(heartbeats) {
      heartbeats.add(nodeDescr);
      nodeDescr.isAlive = true;
      // no need to update its timestamp
      // because its is done when the descriptor is created
    }
    return;
  }
    
  /**
   * Get registrationID for datanodes based on the namespaceID.
   * 
   * @see #registerDatanode(DatanodeRegistration,String)
   * @see FSImage#newNamespaceID()
   * @return registration ID
   */
  public String getRegistrationID() {
    return Storage.getRegistrationID(dir.fsImage);
  }
    
  /**
   * Generate new storage ID.
   * 
   * @return unique storage ID
   * 
   * Note: that collisions are still possible if somebody will try 
   * to bring in a data storage from a different cluster.
   */
  private String newStorageID() {
    String newID = null;
    while(newID == null) {
      newID = "DS" + Integer.toString(r.nextInt());
      if (datanodeMap.get(newID) != null)
        newID = null;
    }
    return newID;
  }
    
  private boolean isDatanodeDead(DatanodeDescriptor node) {
    return (node.getLastUpdate() <
            (now() - heartbeatExpireInterval));
  }
    
  void setDatanodeDead(DatanodeID nodeID) throws IOException {
    DatanodeDescriptor node = getDatanode(nodeID);
    node.setLastUpdate(0);
  }

  /**
   * The given node has reported in.  This method should:
   * 1) Record the heartbeat, so the datanode isn't timed out
   * 2) Adjust usage stats for future block allocation
   * 
   * If a substantial amount of time passed since the last datanode 
   * heartbeat then request an immediate block report.  
   * 
   * @return true if registration is required or false otherwise.
   * @throws IOException
   */
  public boolean gotHeartbeat(DatanodeID nodeID,
                              long capacity,
                              long dfsUsed,
                              long remaining,
                              int xceiverCount,
                              int xmitsInProgress,
                              Object[] xferResults,
                              Object deleteList[]
                              ) throws IOException {
    synchronized (heartbeats) {
      synchronized (datanodeMap) {
        DatanodeDescriptor nodeinfo;
        try {
          nodeinfo = getDatanode(nodeID);
          if (nodeinfo == null) {
            return true;
          }
        } catch(UnregisteredDatanodeException e) {
          return true;
        }
          
        // Check if this datanode should actually be shutdown instead. 
        if (shouldNodeShutdown(nodeinfo)) {
          setDatanodeDead(nodeinfo);
          throw new DisallowedDatanodeException(nodeinfo);
        }

        if (!nodeinfo.isAlive) {
          return true;
        } else {
          updateStats(nodeinfo, false);
          nodeinfo.updateHeartbeat(capacity, dfsUsed, remaining, xceiverCount);
          updateStats(nodeinfo, true);
          //
          // Extract pending replication work or block invalidation
          // work from the datanode descriptor
          //
          nodeinfo.getReplicationSets(this.maxReplicationStreams - 
                                      xmitsInProgress, xferResults); 
          if (xferResults[0] == null) {
            nodeinfo.getInvalidateBlocks(FSConstants.BLOCK_INVALIDATE_CHUNK,
                                         deleteList);
          }
          return false;
        }
      }
    }
  }

  private void updateStats(DatanodeDescriptor node, boolean isAdded) {
    //
    // The statistics are protected by the heartbeat lock
    //
    assert(Thread.holdsLock(heartbeats));
    if (isAdded) {
      totalCapacity += node.getCapacity();
      totalUsed += node.getDfsUsed();
      totalRemaining += node.getRemaining();
      totalLoad += node.getXceiverCount();
    } else {
      totalCapacity -= node.getCapacity();
      totalUsed -= node.getDfsUsed();
      totalRemaining -= node.getRemaining();
      totalLoad -= node.getXceiverCount();
    }
  }
  /**
   * Periodically calls heartbeatCheck().
   */
  class HeartbeatMonitor implements Runnable {
    /**
     */
    public void run() {
      while (fsRunning) {
        try {
          heartbeatCheck();
        } catch (Exception e) {
          FSNamesystem.LOG.error(StringUtils.stringifyException(e));
        }
        try {
          Thread.sleep(heartbeatRecheckInterval);
        } catch (InterruptedException ie) {
        }
      }
    }
  }

  /**
   * Periodically calls computeReplicationWork().
   */
  class ReplicationMonitor implements Runnable {
    public void run() {
      while (fsRunning) {
        try {
          computeDatanodeWork();
          processPendingReplications();
          Thread.sleep(replicationRecheckInterval);
        } catch (InterruptedException ie) {
        } catch (IOException ie) {
          LOG.warn("ReplicationMonitor thread received exception. " + ie);
        } catch (Throwable t) {
          LOG.warn("ReplicationMonitor thread received Runtime exception. " + t);
          Runtime.getRuntime().exit(-1);
        }
      }
    }
  }

  /**
   * Look at a few datanodes and compute any replication work that 
   * can be scheduled on them. The datanode will be infomed of this
   * work at the next heartbeat.
   */
  void computeDatanodeWork() throws IOException {
    int numiter = 0;
    int foundwork = 0;
    int hsize = 0;
    int lastReplIndex = -1;

    while (true) {
      DatanodeDescriptor node = null;

      //
      // pick the datanode that was the last one in the
      // previous invocation of this method.
      //
      synchronized (heartbeats) {
        hsize = heartbeats.size();
        if (numiter++ >= hsize) {
          // no change in replIndex.
          if (lastReplIndex >= 0) {
            //next time, start after where the last replication was scheduled
            replIndex = lastReplIndex;
          }
          break;
        }
        if (replIndex >= hsize) {
          replIndex = 0;
        }
        node = heartbeats.get(replIndex);
        replIndex++;
      }

      //
      // Is there replication work to be computed for this datanode?
      //
      int precomputed = node.getNumberOfBlocksToBeReplicated();
      int needed = this.maxReplicationStreams - precomputed;
      boolean doReplication = false;
      boolean doInvalidation = false;
      if (needed > 0) {
        //
        // Compute replication work and store work into the datanode
        //
        Object replsets[] = pendingTransfers(node, needed);
        if (replsets != null) {
          doReplication = true;
          addBlocksToBeReplicated(node, (Block[])replsets[0], 
                                  (DatanodeDescriptor[][])replsets[1]);
          lastReplIndex = replIndex;
        }
      }
      if (!doReplication) {
        //
        // Determine if block deletion is pending for this datanode
        //
        Block blocklist[] = blocksToInvalidate(node);
        if (blocklist != null) {
          doInvalidation = true;
          addBlocksToBeInvalidated(node, blocklist);
        }
      }
      if (doReplication || doInvalidation) {
        //
        // If we have already computed work for a predefined
        // number of datanodes in this iteration, then relax
        //
        if (foundwork > ((hsize * REPL_WORK_PER_ITERATION)/100)) {
          break;
        }
        foundwork++;
      } 
    }
  }

  /**
   * If there were any replication requests that timed out, reap them
   * and put them back into the neededReplication queue
   */
  void processPendingReplications() {
    Block[] timedOutItems = pendingReplications.getTimedOutBlocks();
    if (timedOutItems != null) {
      synchronized (this) {
        for (int i = 0; i < timedOutItems.length; i++) {
          NumberReplicas num = countNodes(timedOutItems[i]);
          neededReplications.add(timedOutItems[i], 
                                 num.liveReplicas(),
                                 num.decommissionedReplicas(),
                                 getReplication(timedOutItems[i]));
        }
      }
    }
  }

  /**
   * Add more replication work for this datanode.
   */
  synchronized void addBlocksToBeReplicated(DatanodeDescriptor node, 
                                            Block[] blocklist,
                                            DatanodeDescriptor[][] targets) 
    throws IOException {
    //
    // Find the datanode with the FSNamesystem lock held.
    //
    DatanodeDescriptor n = getDatanode(node);
    if (n != null) {
      n.addBlocksToBeReplicated(blocklist, targets);
    }
  }

  /**
   * Add more block invalidation work for this datanode.
   */
  synchronized void addBlocksToBeInvalidated(DatanodeDescriptor node, 
                                             Block[] blocklist) throws IOException {
    //
    // Find the datanode with the FSNamesystem lock held.
    //
    DatanodeDescriptor n = getDatanode(node);
    if (n != null) {
      n.addBlocksToBeInvalidated(blocklist);
    }
  }

  /**
   * remove a datanode descriptor
   * @param nodeID datanode ID
   */
  synchronized public void removeDatanode(DatanodeID nodeID) 
    throws IOException {
    DatanodeDescriptor nodeInfo = getDatanode(nodeID);
    if (nodeInfo != null) {
      removeDatanode(nodeInfo);
    } else {
      NameNode.stateChangeLog.warn("BLOCK* NameSystem.removeDatanode: "
                                   + nodeID.getName() + " does not exist");
    }
  }
  
  /**
   * remove a datanode descriptor
   * @param nodeInfo datanode descriptor
   */
  private void removeDatanode(DatanodeDescriptor nodeInfo) {
    synchronized (heartbeats) {
      if (nodeInfo.isAlive) {
        updateStats(nodeInfo, false);
        heartbeats.remove(nodeInfo);
        nodeInfo.isAlive = false;
      }
    }

    for (Iterator<Block> it = nodeInfo.getBlockIterator(); it.hasNext();) {
      removeStoredBlock(it.next(), nodeInfo);
    }
    unprotectedRemoveDatanode(nodeInfo);
    clusterMap.remove(nodeInfo);
  }

  void unprotectedRemoveDatanode(DatanodeDescriptor nodeDescr) {
    nodeDescr.resetBlocks();
    NameNode.stateChangeLog.debug(
                                  "BLOCK* NameSystem.unprotectedRemoveDatanode: "
                                  + nodeDescr.getName() + " is out of service now.");
  }
    
  void unprotectedAddDatanode(DatanodeDescriptor nodeDescr) {
    /* To keep host2DataNodeMap consistent with datanodeMap,
       remove  from host2DataNodeMap the datanodeDescriptor removed
       from datanodeMap before adding nodeDescr to host2DataNodeMap.
    */
    host2DataNodeMap.remove(
                            datanodeMap.put(nodeDescr.getStorageID(), nodeDescr));
    host2DataNodeMap.add(nodeDescr);
      
    NameNode.stateChangeLog.debug(
                                  "BLOCK* NameSystem.unprotectedAddDatanode: "
                                  + "node " + nodeDescr.getName() + " is added to datanodeMap.");
  }

  /**
   * Physically remove node from datanodeMap.
   * 
   * @param nodeID node
   */
  void wipeDatanode(DatanodeID nodeID) throws IOException {
    String key = nodeID.getStorageID();
    host2DataNodeMap.remove(datanodeMap.remove(key));
    NameNode.stateChangeLog.debug(
                                  "BLOCK* NameSystem.wipeDatanode: "
                                  + nodeID.getName() + " storage " + key 
                                  + " is removed from datanodeMap.");
  }

  FSImage getFSImage() {
    return dir.fsImage;
  }

  FSEditLog getEditLog() {
    return getFSImage().getEditLog();
  }

  /**
   * Check if there are any expired heartbeats, and if so,
   * whether any blocks have to be re-replicated.
   * While removing dead datanodes, make sure that only one datanode is marked
   * dead at a time within the synchronized section. Otherwise, a cascading
   * effect causes more datanodes to be declared dead.
   */
  void heartbeatCheck() {
    boolean allAlive = false;
    while (!allAlive) {
      boolean foundDead = false;
      DatanodeID nodeID = null;

      // locate the first dead node.
      synchronized(heartbeats) {
        for (Iterator<DatanodeDescriptor> it = heartbeats.iterator();
             it.hasNext();) {
          DatanodeDescriptor nodeInfo = it.next();
          if (isDatanodeDead(nodeInfo)) {
            foundDead = true;
            nodeID = nodeInfo;
            break;
          }
        }
      }

      // acquire the fsnamesystem lock, and then remove the dead node.
      if (foundDead) {
        synchronized (this) {
          synchronized(heartbeats) {
            synchronized (datanodeMap) {
              DatanodeDescriptor nodeInfo = null;
              try {
                nodeInfo = getDatanode(nodeID);
              } catch (IOException e) {
                nodeInfo = null;
              }
              if (nodeInfo != null && isDatanodeDead(nodeInfo)) {
                NameNode.stateChangeLog.info("BLOCK* NameSystem.heartbeatCheck: "
                                             + "lost heartbeat from " + nodeInfo.getName());
                removeDatanode(nodeInfo);
              }
            }
          }
        }
      }
      allAlive = !foundDead;
    }
  }
    
  /**
   * The given node is reporting all its blocks.  Use this info to 
   * update the (machine-->blocklist) and (block-->machinelist) tables.
   */
  public synchronized Block[] processReport(DatanodeID nodeID, 
                                            Block newReport[]
                                            ) throws IOException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.processReport: "
                                    +"from "+nodeID.getName()+" "+newReport.length+" blocks");
    }
    DatanodeDescriptor node = getDatanode(nodeID);
    if (node == null) {
      throw new IOException("ProcessReport from unregisterted node: "
                            + nodeID.getName());
    }

    // Check if this datanode should actually be shutdown instead.
    if (shouldNodeShutdown(node)) {
      setDatanodeDead(node);
      throw new DisallowedDatanodeException(node);
    }

    //
    // Modify the (block-->datanode) map, according to the difference
    // between the old and new block report.
    //
    Collection<Block> toAdd = new LinkedList<Block>();
    Collection<Block> toRemove = new LinkedList<Block>();
    node.reportDiff(blocksMap, newReport, toAdd, toRemove);
        
    for (Block b : toRemove) {
      removeStoredBlock(b, node);
    }
    for (Block b : toAdd) {
      addStoredBlock(b, node, null);
    }
        
    //
    // We've now completely updated the node's block report profile.
    // We now go through all its blocks and find which ones are invalid,
    // no longer pending, or over-replicated.
    //
    // (Note it's not enough to just invalidate blocks at lease expiry 
    // time; datanodes can go down before the client's lease on 
    // the failed file expires and miss the "expire" event.)
    //
    // This function considers every block on a datanode, and thus
    // should only be invoked infrequently.
    //
    Collection<Block> obsolete = new ArrayList<Block>();
    for (Iterator<Block> it = node.getBlockIterator(); it.hasNext();) {
      Block b = it.next();

      // 
      // A block report can only send BLOCK_INVALIDATE_CHUNK number of
      // blocks to be deleted. If there are more blocks to be deleted, 
      // they are added to recentInvalidateSets and will be sent out
      // thorugh succeeding heartbeat responses.
      //
      if (!isValidBlock(b)) {
        if (obsolete.size() > FSConstants.BLOCK_INVALIDATE_CHUNK) {
          addToInvalidates(b, node);
        } else {
          obsolete.add(b);
        }
        NameNode.stateChangeLog.debug("BLOCK* NameSystem.processReport: "
                                      +"ask "+nodeID.getName()+" to delete "+b.getBlockName());
      }
    }
    return obsolete.toArray(new Block[obsolete.size()]);
  }

  /**
   * Modify (block-->datanode) map.  Remove block from set of 
   * needed replications if this takes care of the problem.
   * @return the block that is stored in blockMap.
   */
  synchronized Block addStoredBlock(Block block, 
                                    DatanodeDescriptor node,
                                    DatanodeDescriptor delNodeHint) {
        
    INodeFile fileINode = blocksMap.getINode(block);
    int replication = (fileINode != null) ?  fileINode.getReplication() : 
      defaultReplication;
    boolean added = blocksMap.addNode(block, node, replication);
        
    Block storedBlock = blocksMap.getStoredBlock(block); //extra look up!
    if (storedBlock != null && block != storedBlock) {
      if (block.getNumBytes() > 0) {
        long cursize = storedBlock.getNumBytes();
        if (cursize == 0) {
          storedBlock.setNumBytes(block.getNumBytes());
        } else if (cursize != block.getNumBytes()) {
          LOG.warn("Inconsistent size for block " + block + 
                   " reported from " + node.getName() + 
                   " current size is " + cursize +
                   " reported size is " + block.getNumBytes());
          // Accept this block even if there is a problem with its
          // size. Clients should detect data corruption because of
          // CRC mismatch.
        }
      }
      block = storedBlock;
    }
        
    int curReplicaDelta = 0;
        
    if (added) {
      curReplicaDelta = 1;
      // 
      // At startup time, because too many new blocks come in
      // they take up lots of space in the log file. 
      // So, we log only when namenode is out of safemode.
      //
      if (!isInSafeMode()) {
        NameNode.stateChangeLog.info("BLOCK* NameSystem.addStoredBlock: "
                                      +"blockMap updated: "+node.getName()+" is added to "+block.getBlockName());
      }
    } else {
      NameNode.stateChangeLog.warn("BLOCK* NameSystem.addStoredBlock: "
                                   + "Redundant addStoredBlock request received for " 
                                   + block.getBlockName() + " on " + node.getName());
    }

    //
    // if file is being actively written to, then do not check 
    // replication-factor here. It will be checked when the file is closed.
    //
    if (fileINode == null || fileINode.isUnderConstruction()) {
      return block;
    }
        
    // filter out containingNodes that are marked for decommission.
    NumberReplicas num = countNodes(block);
    int numCurrentReplica = num.liveReplicas()
      + pendingReplications.getNumReplicas(block);
        
    // check whether safe replication is reached for the block
    // only if it is a part of a files
    incrementSafeBlockCount(numCurrentReplica);
 
    // handle underReplication/overReplication
    short fileReplication = fileINode.getReplication();
    if (numCurrentReplica >= fileReplication) {
      neededReplications.remove(block, numCurrentReplica, 
                                num.decommissionedReplicas, fileReplication);
    } else {
      updateNeededReplications(block, curReplicaDelta, 0);
    }
    if (numCurrentReplica > fileReplication) {
      proccessOverReplicatedBlock(block, fileReplication, node, delNodeHint);
    }
    return block;
  }
    
  /**
   * Find how many of the containing nodes are "extra", if any.
   * If there are any extras, call chooseExcessReplicates() to
   * mark them in the excessReplicateMap.
   */
  private void proccessOverReplicatedBlock(Block block, short replication, 
      DatanodeDescriptor addedNode, DatanodeDescriptor delNodeHint) {
    if(addedNode == delNodeHint) {
      delNodeHint = null;
    }
    Collection<DatanodeDescriptor> nonExcess = new ArrayList<DatanodeDescriptor>();
    for (Iterator<DatanodeDescriptor> it = blocksMap.nodeIterator(block); 
         it.hasNext();) {
      DatanodeDescriptor cur = it.next();
      Collection<Block> excessBlocks = excessReplicateMap.get(cur.getStorageID());
      if (excessBlocks == null || !excessBlocks.contains(block)) {
        if (!cur.isDecommissionInProgress() && !cur.isDecommissioned()) {
          nonExcess.add(cur);
        }
      }
    }
    chooseExcessReplicates(nonExcess, block, replication, 
        addedNode, delNodeHint);    
  }

  /**
   * We want "replication" replicates for the block, but we now have too many.  
   * In this method, copy enough nodes from 'srcNodes' into 'dstNodes' such that:
   *
   * srcNodes.size() - dstNodes.size() == replication
   *
   * We pick node that make sure that replicas are spread across racks and
   * also try hard to pick one with least free space.
   * The algorithm is first to pick a node with least free space from nodes
   * that are on a rack holding more than one replicas of the block.
   * So removing such a replica won't remove a rack. 
   * If no such a node is available,
   * then pick a node with least free space
   */
  void chooseExcessReplicates(Collection<DatanodeDescriptor> nonExcess, 
                              Block b, short replication,
                              DatanodeDescriptor addedNode,
                              DatanodeDescriptor delNodeHint) {
    // first form a rack to datanodes map and
    HashMap<String, ArrayList<DatanodeDescriptor>> rackMap =
      new HashMap<String, ArrayList<DatanodeDescriptor>>();
    for (Iterator<DatanodeDescriptor> iter = nonExcess.iterator();
         iter.hasNext();) {
      DatanodeDescriptor node = iter.next();
      String rackName = node.getNetworkLocation();
      ArrayList<DatanodeDescriptor> datanodeList = rackMap.get(rackName);
      if(datanodeList==null) {
        datanodeList = new ArrayList<DatanodeDescriptor>();
      }
      datanodeList.add(node);
      rackMap.put(rackName, datanodeList);
    }
    
    // split nodes into two sets
    // priSet contains nodes on rack with more than one replica
    // remains contains the remaining nodes
    ArrayList<DatanodeDescriptor> priSet = new ArrayList<DatanodeDescriptor>();
    ArrayList<DatanodeDescriptor> remains = new ArrayList<DatanodeDescriptor>();
    for( Iterator<Entry<String, ArrayList<DatanodeDescriptor>>> iter = 
      rackMap.entrySet().iterator(); iter.hasNext(); ) {
      Entry<String, ArrayList<DatanodeDescriptor>> rackEntry = iter.next();
      ArrayList<DatanodeDescriptor> datanodeList = rackEntry.getValue(); 
      if( datanodeList.size() == 1 ) {
        remains.add(datanodeList.get(0));
      } else {
        priSet.addAll(datanodeList);
      }
    }
    
    // pick one node to delete that favors the delete hint
    // otherwise pick one with least space from priSet if it is not empty
    // otherwise one node with least space from remains
    while (nonExcess.size() - replication > 0) {
      DatanodeInfo cur = null;
      long minSpace = Long.MAX_VALUE;

      // check if we can del delNodeHint
      if( delNodeHint !=null && (priSet.contains(delNodeHint) ||
          (addedNode != null && !priSet.contains(addedNode))) ) {
          cur = delNodeHint;
      } else { // regular excessive replica removal
        Iterator<DatanodeDescriptor> iter = 
          priSet.isEmpty() ? remains.iterator() : priSet.iterator();
          while( iter.hasNext() ) {
            DatanodeDescriptor node = iter.next();
            long free = node.getRemaining();

            if (minSpace > free) {
              minSpace = free;
              cur = node;
            }
          }
      }

      // adjust rackmap, priSet, and remains
      String rack = cur.getNetworkLocation();
      ArrayList<DatanodeDescriptor> datanodes = rackMap.get(rack);
      datanodes.remove(cur);
      if(datanodes.isEmpty()) {
        rackMap.remove(rack);
      }
      if( priSet.remove(cur) ) {
        if (datanodes.size() == 1) {
          priSet.remove(datanodes.get(0));
          remains.add(datanodes.get(0));
        }
      } else {
        remains.remove(cur);
      }

      nonExcess.remove(cur);

      Collection<Block> excessBlocks = excessReplicateMap.get(cur.getStorageID());
      if (excessBlocks == null) {
        excessBlocks = new TreeSet<Block>();
        excessReplicateMap.put(cur.getStorageID(), excessBlocks);
      }
      excessBlocks.add(b);
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.chooseExcessReplicates: "
                                    +"("+cur.getName()+", "+b.getBlockName()+") is added to excessReplicateMap");

      //
      // The 'excessblocks' tracks blocks until we get confirmation
      // that the datanode has deleted them; the only way we remove them
      // is when we get a "removeBlock" message.  
      //
      // The 'invalidate' list is used to inform the datanode the block 
      // should be deleted.  Items are removed from the invalidate list
      // upon giving instructions to the namenode.
      //
      Collection<Block> invalidateSet = recentInvalidateSets.get(cur.getStorageID());
      if (invalidateSet == null) {
        invalidateSet = new ArrayList<Block>();
        recentInvalidateSets.put(cur.getStorageID(), invalidateSet);
      }
      invalidateSet.add(b);
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.chooseExcessReplicates: "
                                    +"("+cur.getName()+", "+b.getBlockName()+") is added to recentInvalidateSets");
    }
  }

  /**
   * Modify (block-->datanode) map.  Possibly generate 
   * replication tasks, if the removed block is still valid.
   */
  synchronized void removeStoredBlock(Block block, DatanodeDescriptor node) {
    NameNode.stateChangeLog.debug("BLOCK* NameSystem.removeStoredBlock: "
                                  +block.getBlockName() + " from "+node.getName());
    if (!blocksMap.removeNode(block, node)) {
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.removeStoredBlock: "
                                    +block.getBlockName()+" has already been removed from node "+node);
      return;
    }
        
    decrementSafeBlockCount(block);
    //
    // It's possible that the block was removed because of a datanode
    // failure.  If the block is still valid, check if replication is
    // necessary.  In that case, put block on a possibly-will-
    // be-replicated list.
    //
    INode fileINode = blocksMap.getINode(block);
    if (fileINode != null) {
      updateNeededReplications(block, -1, 0);
    }

    //
    // We've removed a block from a node, so it's definitely no longer
    // in "excess" there.
    //
    Collection<Block> excessBlocks = excessReplicateMap.get(node.getStorageID());
    if (excessBlocks != null) {
      excessBlocks.remove(block);
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.removeStoredBlock: "
                                    +block.getBlockName()+" is removed from excessBlocks");
      if (excessBlocks.size() == 0) {
        excessReplicateMap.remove(node.getStorageID());
      }
    }
  }

  /**
   * The given node is reporting that it received a certain block.
   */
  public synchronized void blockReceived(DatanodeID nodeID,  
                                         Block block,
                                         String delHint
                                         ) throws IOException {
    DatanodeDescriptor node = getDatanode(nodeID);
    if (node == null) {
      NameNode.stateChangeLog.warn("BLOCK* NameSystem.blockReceived: "
                                   + block.getBlockName() + " is received from an unrecorded node " 
                                   + nodeID.getName());
      throw new IllegalArgumentException(
                                         "Unexpected exception.  Got blockReceived message from node " 
                                         + block.getBlockName() + ", but there is no info for it");
    }
        
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("BLOCK* NameSystem.blockReceived: "
                                    +block.getBlockName()+" is received from " + nodeID.getName());
    }

    // Check if this datanode should actually be shutdown instead.
    if (shouldNodeShutdown(node)) {
      setDatanodeDead(node);
      throw new DisallowedDatanodeException(node);
    }

    // get the deletion hint node
    DatanodeDescriptor delHintNode = null;
    if(delHint!=null && delHint.length()!=0) {
      delHintNode = datanodeMap.get(delHint);
      if(delHintNode == null) {
        NameNode.stateChangeLog.warn("BLOCK* NameSystem.blockReceived: "
            + block.getBlockName()
            + " is expected to be removed from an unrecorded node " 
            + delHint);
      }
    }

    //
    // Modify the blocks->datanode map and node's map.
    // 
    addStoredBlock(block, node, delHintNode );
    pendingReplications.remove(block);
  }

  /**
   * Total raw bytes including non-dfs used space.
   */
  public long totalCapacity() {
    synchronized (heartbeats) {
      return totalCapacity;
    }
  }

  /**
   * Total used space by data nodes
   */
  public long totalDfsUsed() {
    synchronized(heartbeats){
      return totalUsed;
    }
  }
  /**
   * Total non-used raw bytes.
   */
  public long totalRemaining() {
    synchronized (heartbeats) {
      return totalRemaining;
    }
  }

  /**
   * Total number of connections.
   */
  public int totalLoad() {
    synchronized (heartbeats) {
      return totalLoad;
    }
  }

  private synchronized ArrayList<DatanodeDescriptor> getDatanodeListForReport(
                                                      DatanodeReportType type) {                  
    
    boolean listLiveNodes = type == DatanodeReportType.ALL ||
                            type == DatanodeReportType.LIVE;
    boolean listDeadNodes = type == DatanodeReportType.ALL ||
                            type == DatanodeReportType.DEAD;

    HashMap<String, String> mustList = new HashMap<String, String>();
    
    if (listDeadNodes) {
      //first load all the nodes listed in include and exclude files.
      for (Iterator<String> it = hostsReader.getHosts().iterator(); 
           it.hasNext();) {
        mustList.put(it.next(), "");
      }
      for (Iterator<String> it = hostsReader.getExcludedHosts().iterator(); 
           it.hasNext();) {
        mustList.put(it.next(), "");
      }
    }
   
    ArrayList<DatanodeDescriptor> nodes = null;
    
    synchronized (datanodeMap) {
      nodes = new ArrayList<DatanodeDescriptor>(datanodeMap.size() + 
                                                mustList.size());
      
      for(Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator(); 
                                                               it.hasNext();) {
        DatanodeDescriptor dn = it.next();
        boolean isDead = isDatanodeDead(dn);
        if ( (isDead && listDeadNodes) || (!isDead && listLiveNodes) ) {
          nodes.add(dn);
        }
        //Remove any form of the this datanode in include/exclude lists.
        mustList.remove(dn.getName());
        mustList.remove(dn.getHost());
        mustList.remove(dn.getHostName());
      }
    }
    
    if (listDeadNodes) {
      for (Iterator<String> it = mustList.keySet().iterator(); it.hasNext();) {
        DatanodeDescriptor dn = 
            new DatanodeDescriptor(new DatanodeID(it.next(), "", 0));
        dn.setLastUpdate(0);
        nodes.add(dn);
      }
    }
    
    return nodes;
  }

  public synchronized DatanodeInfo[] datanodeReport( DatanodeReportType type ) {

    ArrayList<DatanodeDescriptor> results = getDatanodeListForReport(type);
    DatanodeInfo[] arr = new DatanodeInfo[results.size()];
    for (int i=0; i<arr.length; i++) {
      arr[i] = new DatanodeInfo(results.get(i));
    }
    return arr;
  }
    
  /**
   */
  public synchronized void DFSNodesStatus(ArrayList<DatanodeDescriptor> live, 
                                          ArrayList<DatanodeDescriptor> dead) {

    ArrayList<DatanodeDescriptor> results = 
                            getDatanodeListForReport(DatanodeReportType.ALL);    
    for(Iterator<DatanodeDescriptor> it = results.iterator(); it.hasNext();) {
      DatanodeDescriptor node = it.next();
      if (isDatanodeDead(node))
        dead.add(node);
      else
        live.add(node);
    }
  }

  /**
   * Prints information about all datanodes.
   */
  private synchronized void datanodeDump(PrintWriter out) {
    synchronized (datanodeMap) {
      out.println("Metasave: Number of datanodes: " + datanodeMap.size());
      for(Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator(); it.hasNext();) {
        DatanodeDescriptor node = it.next();
        out.println(node.dumpDatanode());
      }
    }
  }

  /**
   * Start decommissioning the specified datanode. 
   */
  private void startDecommission (DatanodeDescriptor node) 
    throws IOException {

    if (!node.isDecommissionInProgress() && !node.isDecommissioned()) {
      LOG.info("Start Decommissioning node " + node.name);
      node.startDecommission();
      //
      // all the blocks that reside on this node have to be 
      // replicated.
      Iterator<Block> decommissionBlocks = node.getBlockIterator();
      while(decommissionBlocks.hasNext()) {
        Block block = decommissionBlocks.next();
        updateNeededReplications(block, -1, 0);
      }
    }
  }

  /**
   * Stop decommissioning the specified datanodes.
   */
  public void stopDecommission (DatanodeDescriptor node) 
    throws IOException {
    LOG.info("Stop Decommissioning node " + node.name);
    node.stopDecommission();
  }

  /** 
   */
  public DatanodeInfo getDataNodeInfo(String name) {
    return datanodeMap.get(name);
  }
  /** 
   */
  public String getDFSNameNodeMachine() {
    return localMachine;
  }
  /**
   */ 
  public int getDFSNameNodePort() {
    return port;
  }
  /**
   */
  public Date getStartTime() {
    return startTime;
  }
    
  short getMaxReplication()     { return (short)maxReplication; }
  short getMinReplication()     { return (short)minReplication; }
  short getDefaultReplication() { return (short)defaultReplication; }
    
  /////////////////////////////////////////////////////////
  //
  // These methods are called by the Namenode system, to see
  // if there is any work for a given datanode.
  //
  /////////////////////////////////////////////////////////

  /**
   * Check if there are any recently-deleted blocks a datanode should remove.
   */
  public synchronized Block[] blocksToInvalidate(DatanodeID nodeID) {
    // Ask datanodes to perform block delete  
    // only if safe mode is off.
    if (isInSafeMode())
      return null;
       
    Collection<Block> invalidateSet = recentInvalidateSets.remove(
                                                                  nodeID.getStorageID());
 
    if (invalidateSet == null) {
      return null;
    }

    Iterator<Block> it = null;
    int sendNum = invalidateSet.size();
    int origSize = sendNum;
    ArrayList<Block> sendBlock = new ArrayList<Block>(sendNum);

    //
    // calculate the number of blocks that we send in one message
    //
    if (sendNum > FSConstants.BLOCK_INVALIDATE_CHUNK) {
      sendNum =  FSConstants.BLOCK_INVALIDATE_CHUNK;
    }
    //
    // Copy the first chunk into sendBlock
    //
    for (it = invalidateSet.iterator(); sendNum > 0; sendNum--) {
      assert(it.hasNext());
      sendBlock.add(it.next());
      it.remove();
    }

    //
    // If we could not send everything in this message, reinsert this item
    // into the collection.
    //
    if (it.hasNext()) {
      assert(origSize > FSConstants.BLOCK_INVALIDATE_CHUNK);
      recentInvalidateSets.put(nodeID.getStorageID(), invalidateSet);
    }
        
    if (NameNode.stateChangeLog.isInfoEnabled()) {
      StringBuffer blockList = new StringBuffer();
      for (int i = 0; i < sendBlock.size(); i++) {
        blockList.append(' ');
        Block block = sendBlock.get(i);
        blockList.append(block.getBlockName());
      }
      NameNode.stateChangeLog.info("BLOCK* NameSystem.blockToInvalidate: "
                                   +"ask "+nodeID.getName()+" to delete " + blockList);
    }
    return sendBlock.toArray(new Block[sendBlock.size()]);
  }


  /**
   * A immutable object that stores the number of live replicas and
   * the number of decommissined Replicas.
   */
  static class NumberReplicas {
    private int liveReplicas;
    private int decommissionedReplicas;

    NumberReplicas(int live, int decommissioned) {
      liveReplicas = live;
      decommissionedReplicas = decommissioned;
    }

    int liveReplicas() {
      return liveReplicas;
    }
    int decommissionedReplicas() {
      return decommissionedReplicas;
    }
  } 

  /*
   * Counts the number of nodes in the given list into active and
   * decommissioned counters.
   */
  private NumberReplicas countNodes(Iterator<DatanodeDescriptor> nodeIter) {
    int count = 0;
    int live = 0;
    while ( nodeIter.hasNext() ) {
      DatanodeDescriptor node = nodeIter.next();
      if (node.isDecommissionInProgress() || node.isDecommissioned()) {
        count++;
      }
      else {
        live++;
      }
    }
    return new NumberReplicas(live, count);
  }

  /** return the number of nodes that are live and decommissioned. */
  private NumberReplicas countNodes(Block b) {
    return countNodes(blocksMap.nodeIterator(b));
  }

  /** Returns a newly allocated list of all nodes. Returns a count of
  * live and decommissioned nodes. */
  ArrayList<DatanodeDescriptor> containingNodeList(Block b, NumberReplicas[] numReplicas) {
    ArrayList<DatanodeDescriptor> nodeList = 
      new ArrayList<DatanodeDescriptor>();
    int count = 0;
    int live = 0;
    for(Iterator<DatanodeDescriptor> it = blocksMap.nodeIterator(b);
        it.hasNext();) {
      DatanodeDescriptor node = it.next();
      if (!node.isDecommissionInProgress() && !node.isDecommissioned()) {
        live++;
      }
      else {
        count++;
      }
      nodeList.add(node);
    }
    if (numReplicas != null) {
      numReplicas[0] = new NumberReplicas(live, count);
    }
    return nodeList;
  }
  /*
   * Return true if there are any blocks on this node that have not
   * yet reached their replication factor. Otherwise returns false.
   */
  private boolean isReplicationInProgress(DatanodeDescriptor srcNode) {
    boolean status = false;
    Iterator<Block> decommissionBlocks = srcNode.getBlockIterator();
    while(decommissionBlocks.hasNext()) {
      Block block = decommissionBlocks.next();
      INode fileINode = blocksMap.getINode(block);

      if (fileINode != null) {
        NumberReplicas num = countNodes(block);
        int curReplicas = num.liveReplicas();
        int curExpectedReplicas = getReplication(block);
        if (curExpectedReplicas > curReplicas) {
          status = true;
          if (!neededReplications.contains(block) &&
            pendingReplications.getNumReplicas(block) == 0) {
            //
            // These blocks have been reported from the datanode
            // after the startDecommission method has been executed. These
            // blocks were in flight when the decommission was started.
            //
            neededReplications.update(block, 
                                      curReplicas,
                                      num.decommissionedReplicas(),
                                      curExpectedReplicas,
                                      -1, 0);
          }
        }
      }
    }
    return status;
  }

  /**
   * Change, if appropriate, the admin state of a datanode to 
   * decommission completed. Return true if decommission is complete.
   */
  private boolean checkDecommissionStateInternal(DatanodeDescriptor node) {
    //
    // Check to see if all blocks in this decommisioned
    // node has reached their target replication factor.
    //
    if (node.isDecommissionInProgress()) {
      if (!isReplicationInProgress(node)) {
        node.setDecommissioned();
        LOG.info("Decommission complete for node " + node.name);
      }
    }
    if (node.isDecommissioned()) {
      return true;
    }
    return false;
  }

  /**
   * Return with a list of Block/DataNodeInfo sets, indicating
   * where various Blocks should be copied, ASAP.
   *
   * The Array that we return consists of two objects:
   * The 1st elt is an array of Blocks.
   * The 2nd elt is a 2D array of DatanodeDescriptor objs, identifying the
   *     target sequence for the Block at the appropriate index.
   *
   */
  public synchronized Object[] pendingTransfers(DatanodeID srcNode,
                                                int needed) {
    // Ask datanodes to perform block replication  
    // only if safe mode is off.
    if (isInSafeMode())
      return null;
    
    synchronized (neededReplications) {
      Object results[] = null;

      if (neededReplications.size() > 0) {
        //
        // Go through all blocks that need replications. See if any
        // are present at the current node. If so, ask the node to
        // replicate them.
        //
        List<Block> replicateBlocks = new ArrayList<Block>();
        List<NumberReplicas> numCurrentReplicas = new ArrayList<NumberReplicas>();
        List<DatanodeDescriptor[]> replicateTargetSets;
        replicateTargetSets = new ArrayList<DatanodeDescriptor[]>();
        NumberReplicas[] allReplicas = new NumberReplicas[1];
        for (Iterator<Block> it = neededReplications.iterator(); it.hasNext();) {
          if (needed <= 0) {
            break;
          }
          Block block = it.next();
          long blockSize = block.getNumBytes();
          INodeFile fileINode = blocksMap.getINode(block);
          if (fileINode == null) { // block does not belong to any file
            it.remove();
          } else {
            List<DatanodeDescriptor> containingNodes = 
              containingNodeList(block, allReplicas);
            Collection<Block> excessBlocks = excessReplicateMap.get(
                                                                    srcNode.getStorageID());

            // srcNode must contain the block, and the block must
            // not be scheduled for removal on that node
            if (containingNodes.contains(srcNode)
                && (excessBlocks == null || !excessBlocks.contains(block))) {
              int numCurrentReplica = allReplicas[0].liveReplicas() +
                pendingReplications.getNumReplicas(block);
              NumberReplicas repl = new NumberReplicas(numCurrentReplica,
                                        allReplicas[0].decommissionedReplicas()); 
              if (numCurrentReplica >= fileINode.getReplication()) {
                it.remove();
              } else {
                DatanodeDescriptor targets[] = replicator.chooseTarget(
                                                                       Math.min(fileINode.getReplication() - numCurrentReplica,
                                                                                needed),
                                                                       datanodeMap.get(srcNode.getStorageID()),
                                                                       containingNodes, null, blockSize);
                if (targets.length > 0) {
                  // Build items to return
                  replicateBlocks.add(block);
                  numCurrentReplicas.add(repl);
                  replicateTargetSets.add(targets);
                  needed -= targets.length;
                }
              }
            }
          }
        }

        //
        // Move the block-replication into a "pending" state.
        // The reason we use 'pending' is so we can retry
        // replications that fail after an appropriate amount of time.
        // (REMIND - mjc - this timer is not yet implemented.)
        //
        if (replicateBlocks.size() > 0) {
          int i = 0;
          for (Iterator<Block> it = replicateBlocks.iterator(); it.hasNext(); i++) {
            Block block = it.next();
            DatanodeDescriptor targets[] = replicateTargetSets.get(i);
            int numCurrentReplica = numCurrentReplicas.get(i).liveReplicas();
            int numExpectedReplica = blocksMap.getINode(block).getReplication(); 
            if (numCurrentReplica + targets.length >= numExpectedReplica) {
              neededReplications.remove(
                                        block, 
                                        numCurrentReplica, 
                                        numCurrentReplicas.get(i).decommissionedReplicas(),
                                        numExpectedReplica);
              pendingReplications.add(block, targets.length);
              NameNode.stateChangeLog.debug(
                                            "BLOCK* NameSystem.pendingTransfer: "
                                            + block.getBlockName()
                                            + " is removed from neededReplications to pendingReplications");
            }

            if (NameNode.stateChangeLog.isInfoEnabled()) {
              StringBuffer targetList = new StringBuffer("datanode(s)");
              for (int k = 0; k < targets.length; k++) {
                targetList.append(' ');
                targetList.append(targets[k].getName());
              }
              NameNode.stateChangeLog.info(
                                           "BLOCK* NameSystem.pendingTransfer: " + "ask "
                                           + srcNode.getName() + " to replicate "
                                           + block.getBlockName() + " to " + targetList);
              NameNode.stateChangeLog.debug(
                                            "BLOCK* neededReplications = " + neededReplications.size()
                                            + " pendingReplications = " + pendingReplications.size());
            }
          }

          //
          // Build returned objects from above lists
          //
          DatanodeDescriptor targetMatrix[][] = 
            new DatanodeDescriptor[replicateTargetSets.size()][];
          for (i = 0; i < targetMatrix.length; i++) {
            targetMatrix[i] = replicateTargetSets.get(i);
          }

          results = new Object[2];
          results[0] = replicateBlocks.toArray(new Block[replicateBlocks.size()]);
          results[1] = targetMatrix;
        }
      }
      return results;
    }
  }
  
  // Keeps track of which datanodes are allowed to connect to the namenode.
  private boolean inHostsList(DatanodeID node) {
    Set<String> hostsList = hostsReader.getHosts();
    return (hostsList.isEmpty() || 
            hostsList.contains(node.getName()) || 
            hostsList.contains(node.getHost()) ||
            ((node instanceof DatanodeInfo) && 
             hostsList.contains(((DatanodeInfo)node).getHostName())));
  }


  private boolean inExcludedHostsList(DatanodeID node) {
    Set<String> excludeList = hostsReader.getExcludedHosts();
    return (excludeList.contains(node.getName()) ||
            excludeList.contains(node.getHost()) ||
            ((node instanceof DatanodeInfo) && 
             excludeList.contains(((DatanodeInfo)node).getHostName())));
  }

  /**
   * Rereads the files to update the hosts and exclude lists.  It
   * checks if any of the hosts have changed states:
   * 1. Added to hosts  --> no further work needed here.
   * 2. Removed from hosts --> mark AdminState as decommissioned. 
   * 3. Added to exclude --> start decommission.
   * 4. Removed from exclude --> stop decommission.
   */
  void refreshNodes() throws IOException {
    hostsReader.refresh();
    synchronized (this) {
      for (Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator();
           it.hasNext();) {
        DatanodeDescriptor node = it.next();
        // Check if not include.
        if (!inHostsList(node)) {
          node.setDecommissioned();  // case 2.
        } else {
          if (inExcludedHostsList(node)) {
            if (!node.isDecommissionInProgress() && 
                !node.isDecommissioned()) {
              startDecommission(node);   // case 3.
            }
          } else {
            if (node.isDecommissionInProgress() || 
                node.isDecommissioned()) {
              stopDecommission(node);   // case 4.
            } 
          }
        }
      }
    } 
      
  }
    

  /**
   * Checks if the node is not on the hosts list.  If it is not, then
   * it will be ignored.  If the node is in the hosts list, but is also 
   * on the exclude list, then it will be decommissioned.
   * Returns FALSE if node is rejected for registration. 
   * Returns TRUE if node is registered (including when it is on the 
   * exclude list and is being decommissioned). 
   */
  public synchronized boolean verifyNodeRegistration(DatanodeRegistration nodeReg) 
    throws IOException {
    if (!inHostsList(nodeReg)) {
      return false;    
    }
    if (inExcludedHostsList(nodeReg)) {
      DatanodeDescriptor node = getDatanode(nodeReg);
      if (!checkDecommissionStateInternal(node)) {
        startDecommission(node);
      }
    } 
    return true;
  }
    
  /**
   * Checks if the Admin state bit is DECOMMISSIONED.  If so, then 
   * we should shut it down. 
   * 
   * Returns true if the node should be shutdown.
   */
  private boolean shouldNodeShutdown(DatanodeDescriptor node) {
    return (node.isDecommissioned());
  }

  /**
   * Check if any of the nodes being decommissioned has finished 
   * moving all its datablocks to another replica. This is a loose
   * heuristic to determine when a decommission is really over.
   */
  public synchronized void decommissionedDatanodeCheck() {
    for (Iterator<DatanodeDescriptor> it = datanodeMap.values().iterator();
         it.hasNext();) {
      DatanodeDescriptor node = it.next();  
      checkDecommissionStateInternal(node);
    }
  }
    
  /**
   * Periodically calls decommissionedDatanodeCheck().
   */
  class DecommissionedMonitor implements Runnable {
        
    public void run() {
      while (fsRunning) {
        try {
          decommissionedDatanodeCheck();
        } catch (Exception e) {
          FSNamesystem.LOG.info(StringUtils.stringifyException(e));
        }
        try {
          Thread.sleep(decommissionRecheckInterval);
        } catch (InterruptedException ie) {
        }
      }
    }
  }
    
  /**
   * Get data node by storage ID.
   * 
   * @param nodeID
   * @return DatanodeDescriptor or null if the node is not found.
   * @throws IOException
   */
  public DatanodeDescriptor getDatanode(DatanodeID nodeID) throws IOException {
    UnregisteredDatanodeException e = null;
    DatanodeDescriptor node = datanodeMap.get(nodeID.getStorageID());
    if (node == null) 
      return null;
    if (!node.getName().equals(nodeID.getName())) {
      e = new UnregisteredDatanodeException(nodeID, node);
      NameNode.stateChangeLog.fatal("BLOCK* NameSystem.getDatanode: "
                                    + e.getLocalizedMessage());
      throw e;
    }
    return node;
  }
    
  /** Stop at and return the datanode at index (used for content browsing)*/
  private DatanodeDescriptor getDatanodeByIndex(int index) {
    int i = 0;
    for (DatanodeDescriptor node : datanodeMap.values()) {
      if (i == index) {
        return node;
      }
      i++;
    }
    return null;
  }
    
  public String randomDataNode() {
    int size = datanodeMap.size();
    int index = 0;
    if (size != 0) {
      index = r.nextInt(size);
      for(int i=0; i<size; i++) {
        DatanodeDescriptor d = getDatanodeByIndex(index);
        if (d != null && !d.isDecommissioned() && !isDatanodeDead(d) &&
            !d.isDecommissionInProgress()) {
          return d.getHost() + ":" + d.getInfoPort();
        }
        index = (index + 1) % size;
      }
    }
    return null;
  }
    
  public int getNameNodeInfoPort() {
    return infoPort;
  }

  /**
   * SafeModeInfo contains information related to the safe mode.
   * <p>
   * An instance of {@link SafeModeInfo} is created when the name node
   * enters safe mode.
   * <p>
   * During name node startup {@link SafeModeInfo} counts the number of
   * <em>safe blocks</em>, those that have at least the minimal number of
   * replicas, and calculates the ratio of safe blocks to the total number
   * of blocks in the system, which is the size of
   * {@link FSNamesystem#blocksMap}. When the ratio reaches the
   * {@link #threshold} it starts the {@link SafeModeMonitor} daemon in order
   * to monitor whether the safe mode extension is passed. Then it leaves safe
   * mode and destroys itself.
   * <p>
   * If safe mode is turned on manually then the number of safe blocks is
   * not tracked because the name node is not intended to leave safe mode
   * automatically in the case.
   *
   * @see ClientProtocol#setSafeMode(FSConstants.SafeModeAction)
   * @see SafeModeMonitor
   */
  class SafeModeInfo {
    // configuration fields
    /** Safe mode threshold condition %.*/
    private double threshold;
    /** Safe mode extension after the threshold. */
    private int extension;
    /** Min replication required by safe mode. */
    private int safeReplication;
      
    // internal fields
    /** Time when threshold was reached.
     * 
     * <br>-1 safe mode is off
     * <br> 0 safe mode is on, but threshold is not reached yet 
     */
    private long reached = -1;  
    /** Total number of blocks. */
    int blockTotal; 
    /** Number of safe blocks. */
    private int blockSafe;
      
    /**
     * Creates SafeModeInfo when the name node enters
     * automatic safe mode at startup.
     *  
     * @param conf configuration
     */
    SafeModeInfo(Configuration conf) {
      this.threshold = conf.getFloat("dfs.safemode.threshold.pct", 0.95f);
      this.extension = conf.getInt("dfs.safemode.extension", 0);
      this.safeReplication = conf.getInt("dfs.replication.min", 1);
      this.blockTotal = 0; 
      this.blockSafe = 0;
    }

    /**
     * Creates SafeModeInfo when safe mode is entered manually.
     *
     * The {@link #threshold} is set to 1.5 so that it could never be reached.
     * {@link #blockTotal} is set to -1 to indicate that safe mode is manual.
     * 
     * @see SafeModeInfo
     */
    private SafeModeInfo() {
      this.threshold = 1.5f;  // this threshold can never be riched
      this.extension = 0;
      this.safeReplication = Short.MAX_VALUE + 1; // more than maxReplication
      this.blockTotal = -1;
      this.blockSafe = -1;
      this.reached = -1;
      enter();
    }
      
    /**
     * Check if safe mode is on.
     * @return true if in safe mode
     */
    synchronized boolean isOn() {
      try {
        assert isConsistent() : " SafeMode: Inconsistent filesystem state: "
          + "Total num of blocks, active blocks, or "
          + "total safe blocks don't match.";
      } catch(IOException e) {
        System.err.print(StringUtils.stringifyException(e));
      }
      return this.reached >= 0;
    }
      
    /**
     * Enter safe mode.
     */
    void enter() {
      if (reached != 0)
        NameNode.stateChangeLog.info(
                                     "STATE* SafeModeInfo.enter: " + "Safe mode is ON.\n" 
                                     + getTurnOffTip());
      this.reached = 0;
    }
      
    /**
     * Leave safe mode.
     * Switch to manual safe mode if distributed upgrade is required.
     */
    synchronized void leave(boolean checkForUpgrades) {
      if(checkForUpgrades) {
        // verify whether a distributed upgrade needs to be started
        boolean needUpgrade = false;
        try {
          needUpgrade = startDistributedUpgradeIfNeeded();
        } catch(IOException e) {
          FSNamesystem.LOG.error(StringUtils.stringifyException(e));
        }
        if(needUpgrade) {
          // switch to manual safe mode
          safeMode = new SafeModeInfo();
          NameNode.stateChangeLog.info("STATE* SafeModeInfo.leave: " 
                                      + "Safe mode is ON.\n" + getTurnOffTip()); 
          return;
        }
      }
      if (reached >= 0)
        NameNode.stateChangeLog.info(
                                     "STATE* SafeModeInfo.leave: " + "Safe mode is OFF."); 
      reached = -1;
      safeMode = null;
      NameNode.stateChangeLog.info("STATE* Network topology has "
                                   +clusterMap.getNumOfRacks()+" racks and "
                                   +clusterMap.getNumOfLeaves()+ " datanodes");
      NameNode.stateChangeLog.info("STATE* UnderReplicatedBlocks has "
                                   +neededReplications.size()+" blocks");
    }
      
    /** 
     * Safe mode can be turned off iff 
     * the threshold is reached and 
     * the extension time have passed.
     * @return true if can leave or false otherwise.
     */
    synchronized boolean canLeave() {
      if (reached == 0)
        return false;
      if (now() - reached < extension)
        return false;
      return !needEnter();
    }
      
    /** 
     * There is no need to enter safe mode 
     * if DFS is empty or {@link #threshold} == 0
     */
    boolean needEnter() {
      return getSafeBlockRatio() < threshold;
    }
      
    /**
     * Ratio of the number of safe blocks to the total number of blocks 
     * to be compared with the threshold.
     */
    private float getSafeBlockRatio() {
      return (blockTotal == 0 ? 1 : (float)blockSafe/blockTotal);
    }
      
    /**
     * Check and trigger safe mode if needed. 
     */
    private void checkMode() {
      if (needEnter()) {
        enter();
        return;
      }
      // the threshold is reached
      if (!isOn() ||                           // safe mode is off
          extension <= 0 || threshold <= 0) {  // don't need to wait
        this.leave(true); // leave safe mode
        return;
      }
      if (reached > 0)  // threshold has already been reached before
        return;
      // start monitor
      reached = now();
      smmthread = new Daemon(new SafeModeMonitor());
      smmthread.start();
    }
      
    /**
     * Set total number of blocks.
     */
    synchronized void setBlockTotal(int total) {
      this.blockTotal = total; 
      checkMode();
    }
      
    /**
     * Increment number of safe blocks if current block has 
     * reached minimal replication.
     * @param replication current replication 
     */
    synchronized void incrementSafeBlockCount(short replication) {
      if ((int)replication == safeReplication)
        this.blockSafe++;
      checkMode();
    }
      
    /**
     * Decrement number of safe blocks if current block has 
     * fallen below minimal replication.
     * @param replication current replication 
     */
    synchronized void decrementSafeBlockCount(short replication) {
      if (replication == safeReplication-1)
        this.blockSafe--;
      checkMode();
    }
      
    /**
     * Check if safe mode was entered manually or at startup.
     */
    boolean isManual() {
      return blockTotal == -1;
    }
      
    /**
     * A tip on how safe mode is to be turned off: manually or automatically.
     */
    String getTurnOffTip() {
      return (isManual() ?  getDistributedUpgradeState() ?
        "Safe mode will be turned off automatically upon completion of " + 
        "the distributed upgrade: upgrade progress = " + 
        getDistributedUpgradeStatus() + "%" :
        "Use \"hadoop dfs -safemode leave\" to turn safe mode off." :
        "Safe mode will be turned off automatically.");
    }
      
    /**
     * Returns printable state of the class.
     */
    public String toString() {
      String resText = "Current safe block ratio = " 
        + getSafeBlockRatio() 
        + ". Target threshold = " + threshold
        + ". Minimal replication = " + safeReplication + ".";
      if (reached > 0) 
        resText += " Threshold was reached " + new Date(reached) + ".";
      return resText;
    }
      
    /**
     * Checks consistency of the class state.
     * This is costly and currently called only in assert.
     */
    boolean isConsistent() throws IOException {
      if (blockTotal == -1 && blockSafe == -1) {
        return true; // manual safe mode
      }
      int activeBlocks = blocksMap.size();
      for(Iterator<Collection<Block>> it = 
            recentInvalidateSets.values().iterator(); it.hasNext();) {
        activeBlocks -= it.next().size();
      }
      return (blockTotal == activeBlocks) ||
        (blockSafe >= 0 && blockSafe <= blockTotal);
    }
  }
    
  /**
   * Periodically check whether it is time to leave safe mode.
   * This thread starts when the threshold level is reached.
   *
   */
  class SafeModeMonitor implements Runnable {
    /** interval in msec for checking safe mode: {@value} */
    private static final long recheckInterval = 1000;
      
    /**
     */
    public void run() {
      while (fsRunning && !safeMode.canLeave()) {
        try {
          Thread.sleep(recheckInterval);
        } catch (InterruptedException ie) {
        }
      }
      // leave safe mode an stop the monitor
      safeMode.leave(true);
      smmthread = null;
    }
  }
    
  /**
   * Current system time.
   * @return current time in msec.
   */
  static long now() {
    return System.currentTimeMillis();
  }
    
  /**
   * Check whether the name node is in safe mode.
   * @return true if safe mode is ON, false otherwise
   */
  boolean isInSafeMode() {
    if (safeMode == null)
      return false;
    return safeMode.isOn();
  }
    
  /**
   * Increment number of blocks that reached minimal replication.
   * @param replication current replication 
   */
  void incrementSafeBlockCount(int replication) {
    if (safeMode == null)
      return;
    safeMode.incrementSafeBlockCount((short)replication);
  }

  /**
   * Decrement number of blocks that reached minimal replication.
   */
  void decrementSafeBlockCount(Block b) {
    if (safeMode == null) // mostly true
      return;
    safeMode.decrementSafeBlockCount((short)countNodes(b).liveReplicas());
  }

  /**
   * Set the total number of blocks in the system. 
   */
  void setBlockTotal() {
    if (safeMode == null)
      return;
    safeMode.setBlockTotal(blocksMap.size());
  }

  /**
   * Enter safe mode manually.
   * @throws IOException
   */
  synchronized void enterSafeMode() throws IOException {
    if (isInSafeMode()) {
      NameNode.stateChangeLog.info(
                                   "STATE* FSNamesystem.enterSafeMode: " + "Safe mode is already ON."); 
      return;
    }
    safeMode = new SafeModeInfo();
  }
    
  /**
   * Leave safe mode.
   * @throws IOException
   */
  synchronized void leaveSafeMode(boolean checkForUpgrades) throws IOException {
    if (!isInSafeMode()) {
      NameNode.stateChangeLog.info(
                                   "STATE* FSNamesystem.leaveSafeMode: " + "Safe mode is already OFF."); 
      return;
    }
    if(getDistributedUpgradeState())
      throw new SafeModeException("Distributed upgrade is in progress",
                                  safeMode);
    safeMode.leave(checkForUpgrades);
  }
    
  String getSafeModeTip() {
    if (!isInSafeMode())
      return "";
    return safeMode.getTurnOffTip();
  }

  long getEditLogSize() throws IOException {
    return getEditLog().getEditLogSize();
  }

  synchronized long rollEditLog() throws IOException {
    if (isInSafeMode()) {
      throw new SafeModeException("Checkpoint not created",
                                  safeMode);
    }
    LOG.info("Roll Edit Log from " + Server.getRemoteAddress());
    getEditLog().rollEditLog();
    ckptState = CheckpointStates.ROLLED_EDITS;
    return getEditLog().getFsEditTime();
  }

  synchronized void rollFSImage() throws IOException {
    LOG.info("Roll FSImage from " + Server.getRemoteAddress());
    if (isInSafeMode()) {
      throw new SafeModeException("Checkpoint not created",
                                  safeMode);
    }
    if (ckptState != CheckpointStates.UPLOAD_DONE) {
      throw new IOException("Cannot roll fsImage before rolling edits log.");
    }
    dir.fsImage.rollFSImage();
    ckptState = CheckpointStates.START;
  }

  File getFsEditName() throws IOException {
    return getEditLog().getFsEditName();
  }

  /*
   * This is called just before a new checkpoint is uploaded to the
   * namenode.
   */
  synchronized void validateCheckpointUpload(long token) throws IOException {
    if (ckptState != CheckpointStates.ROLLED_EDITS) {
      throw new IOException("Namenode is not expecting an new image " +
                             ckptState);
    } 
    // verify token
    long modtime = getEditLog().getFsEditTime();
    if (token != modtime) {
      throw new IOException("Namenode has an edit log with timestamp of " +
                            DATE_FORM.format(new Date(modtime)) +
                            " but new checkpoint was created using editlog " +
                            " with timestamp " + 
                            DATE_FORM.format(new Date(token)) + 
                            ". Checkpoint Aborted.");
    }
    ckptState = CheckpointStates.UPLOAD_START;
  }

  /*
   * This is called when a checkpoint upload finishes successfully.
   */
  synchronized void checkpointUploadDone() {
    ckptState = CheckpointStates.UPLOAD_DONE;
  }

  /**
   * Returns whether the given block is one pointed-to by a file.
   */
  private boolean isValidBlock(Block b) {
    return (blocksMap.getINode(b) != null);
  }

  // Distributed upgrade manager
  UpgradeManagerNamenode upgradeManager = new UpgradeManagerNamenode();

  UpgradeStatusReport distributedUpgradeProgress(UpgradeAction action 
                                                 ) throws IOException {
    return upgradeManager.distributedUpgradeProgress(action);
  }

  UpgradeCommand processDistributedUpgradeCommand(UpgradeCommand comm) throws IOException {
    return upgradeManager.processUpgradeCommand(comm);
  }

  int getDistributedUpgradeVersion() {
    return upgradeManager.getUpgradeVersion();
  }

  UpgradeCommand getDistributedUpgradeCommand() throws IOException {
    return upgradeManager.getBroadcastCommand();
  }

  boolean getDistributedUpgradeState() {
    return upgradeManager.getUpgradeState();
  }

  short getDistributedUpgradeStatus() {
    return upgradeManager.getUpgradeStatus();
  }

  boolean startDistributedUpgradeIfNeeded() throws IOException {
    return upgradeManager.startUpgrade();
  }
}
