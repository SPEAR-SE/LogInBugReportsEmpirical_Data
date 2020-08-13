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

import org.apache.hadoop.io.*;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ipc.*;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.dfs.DistributedFileSystem.DiskStatus;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.*;

import org.apache.commons.logging.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.ByteBuffer;

import javax.net.SocketFactory;
import javax.security.auth.login.LoginException;

/********************************************************
 * DFSClient can connect to a Hadoop Filesystem and 
 * perform basic file tasks.  It uses the ClientProtocol
 * to communicate with a NameNode daemon, and connects 
 * directly to DataNodes to read/write block data.
 *
 * Hadoop DFS users should obtain an instance of 
 * DistributedFileSystem, which uses DFSClient to handle
 * filesystem tasks.
 *
 ********************************************************/
class DFSClient implements FSConstants {
  public static final Log LOG = LogFactory.getLog("org.apache.hadoop.fs.DFSClient");
  static final int MAX_BLOCK_ACQUIRE_FAILURES = 3;
  private static final int TCP_WINDOW_SIZE = 128 * 1024; // 128 KB
  ClientProtocol namenode;
  volatile boolean clientRunning = true;
  Random r = new Random();
  String clientName;
  Daemon leaseChecker;
  private Configuration conf;
  private long defaultBlockSize;
  private short defaultReplication;
  private SocketFactory socketFactory;
  private int socketTimeout;
    
  /**
   * A map from name -> DFSOutputStream of files that are currently being
   * written by this client.
   */
  private TreeMap<String, OutputStream> pendingCreates =
    new TreeMap<String, OutputStream>();
    
  static ClientProtocol createNamenode(
      InetSocketAddress nameNodeAddr, Configuration conf)
    throws IOException {
    RetryPolicy timeoutPolicy = RetryPolicies.exponentialBackoffRetry(
        5, 200, TimeUnit.MILLISECONDS);
    RetryPolicy createPolicy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(
        5, LEASE_SOFTLIMIT_PERIOD, TimeUnit.MILLISECONDS);
    
    Map<Class<? extends Exception>,RetryPolicy> remoteExceptionToPolicyMap =
      new HashMap<Class<? extends Exception>, RetryPolicy>();
    remoteExceptionToPolicyMap.put(AlreadyBeingCreatedException.class, createPolicy);

    Map<Class<? extends Exception>,RetryPolicy> exceptionToPolicyMap =
      new HashMap<Class<? extends Exception>, RetryPolicy>();
    exceptionToPolicyMap.put(RemoteException.class, 
        RetryPolicies.retryByRemoteException(
            RetryPolicies.TRY_ONCE_THEN_FAIL, remoteExceptionToPolicyMap));
    exceptionToPolicyMap.put(SocketTimeoutException.class, timeoutPolicy);
    RetryPolicy methodPolicy = RetryPolicies.retryByException(
        RetryPolicies.TRY_ONCE_THEN_FAIL, exceptionToPolicyMap);
    Map<String,RetryPolicy> methodNameToPolicyMap = new HashMap<String,RetryPolicy>();
    
    methodNameToPolicyMap.put("open", methodPolicy);
    methodNameToPolicyMap.put("setReplication", methodPolicy);
    methodNameToPolicyMap.put("abandonBlock", methodPolicy);
    methodNameToPolicyMap.put("abandonFileInProgress", methodPolicy);
    methodNameToPolicyMap.put("reportBadBlocks", methodPolicy);
    methodNameToPolicyMap.put("exists", methodPolicy);
    methodNameToPolicyMap.put("isDir", methodPolicy);
    methodNameToPolicyMap.put("getListing", methodPolicy);
    methodNameToPolicyMap.put("getHints", methodPolicy);
    methodNameToPolicyMap.put("renewLease", methodPolicy);
    methodNameToPolicyMap.put("getStats", methodPolicy);
    methodNameToPolicyMap.put("getDatanodeReport", methodPolicy);
    methodNameToPolicyMap.put("getPreferredBlockSize", methodPolicy);
    methodNameToPolicyMap.put("getEditLogSize", methodPolicy);
    methodNameToPolicyMap.put("complete", methodPolicy);
    methodNameToPolicyMap.put("getEditLogSize", methodPolicy);
    methodNameToPolicyMap.put("create", methodPolicy);

    UserGroupInformation userInfo;
    try {
      userInfo = UnixUserGroupInformation.login(conf);
    } catch (LoginException e) {
      throw new IOException(e.getMessage());
    }

    return (ClientProtocol) RetryProxy.create(ClientProtocol.class,
        RPC.getProxy(ClientProtocol.class,
            ClientProtocol.versionID, nameNodeAddr, userInfo, conf,
            NetUtils.getSocketFactory(conf, ClientProtocol.class)),
        methodNameToPolicyMap);
  }
        
  /** 
   * Create a new DFSClient connected to the given namenode server.
   */
  public DFSClient(InetSocketAddress nameNodeAddr, Configuration conf)
    throws IOException {
    this.conf = conf;
    this.socketTimeout = conf.getInt("dfs.socket.timeout", 
                                     FSConstants.READ_TIMEOUT);
    this.socketFactory = NetUtils.getSocketFactory(conf, ClientProtocol.class);
    this.namenode = createNamenode(nameNodeAddr, conf);
    String taskId = conf.get("mapred.task.id");
    if (taskId != null) {
      this.clientName = "DFSClient_" + taskId; 
    } else {
      this.clientName = "DFSClient_" + r.nextInt();
    }
    defaultBlockSize = conf.getLong("dfs.block.size", DEFAULT_BLOCK_SIZE);
    defaultReplication = (short) conf.getInt("dfs.replication", 3);
    this.leaseChecker = new Daemon(new LeaseChecker());
    this.leaseChecker.start();
  }

  private void checkOpen() throws IOException {
    if (!clientRunning) {
      IOException result = new IOException("Filesystem closed");
      throw result;
    }
  }
    
  /**
   * Close the file system, abadoning all of the leases and files being
   * created.
   */
  public void close() throws IOException {
    // synchronize in here so that we don't need to change the API
    synchronized (this) {
      checkOpen();
      synchronized (pendingCreates) {
        Iterator file_itr = pendingCreates.keySet().iterator();
        while (file_itr.hasNext()) {
          String name = (String) file_itr.next();
          try {
            namenode.abandonFileInProgress(name, clientName);
          } catch (IOException ie) {
            System.err.println("Exception abandoning create lock on " + name);
            ie.printStackTrace();
          }
        }
        pendingCreates.clear();
      }
      this.clientRunning = false;
      try {
        leaseChecker.join();
      } catch (InterruptedException ie) {
      }
    }
  }

  /**
   * Get the default block size for this cluster
   * @return the default block size in bytes
   */
  public long getDefaultBlockSize() {
    return defaultBlockSize;
  }
    
  public long getBlockSize(String f) throws IOException {
    try {
      return namenode.getPreferredBlockSize(f);
    } catch (IOException ie) {
      LOG.warn("Problem getting block size: " + 
          StringUtils.stringifyException(ie));
      throw ie;
    }
  }

  /**
   * Report corrupt blocks that were discovered by the client.
   */
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    namenode.reportBadBlocks(blocks);
  }
  
  public short getDefaultReplication() {
    return defaultReplication;
  }
    
  /**
   * Get hints about the location of the indicated block(s).
   * 
   * getHints() returns a list of hostnames that store data for
   * a specific file region.  It returns a set of hostnames for 
   * every block within the indicated region.
   *
   * This function is very useful when writing code that considers
   * data-placement when performing operations.  For example, the
   * MapReduce system tries to schedule tasks on the same machines
   * as the data-block the task processes. 
   */
  public String[][] getHints(String src, long start, long length) 
    throws IOException {
    LocatedBlocks blocks = namenode.getBlockLocations(src, start, length);
    if (blocks == null) {
      return new String[0][];
    }
    int nrBlocks = blocks.locatedBlockCount();
    String[][] hints = new String[nrBlocks][];
    int idx = 0;
    for (LocatedBlock blk : blocks.getLocatedBlocks()) {
      assert idx < nrBlocks : "Incorrect index";
      DatanodeInfo[] locations = blk.getLocations();
      hints[idx] = new String[locations.length];
      for (int hCnt = 0; hCnt < locations.length; hCnt++) {
        hints[idx][hCnt] = locations[hCnt].getHostName();
      }
      idx++;
    }
    return hints;
  }

  public DFSInputStream open(String src) throws IOException {
    return open(src, conf.getInt("io.file.buffer.size", 4096));
  }
  /**
   * Create an input stream that obtains a nodelist from the
   * namenode, and then reads from all the right places.  Creates
   * inner subclass of InputStream that does the right out-of-band
   * work.
   */
  public DFSInputStream open(String src, int buffersize) throws IOException {
    checkOpen();
    //    Get block info from namenode
    return new DFSInputStream(src, buffersize);
  }

  /**
   * Create a new dfs file and return an output stream for writing into it. 
   * 
   * @param src stream name
   * @param overwrite do not check for file existence if true
   * @return output stream
   * @throws IOException
   */
  public OutputStream create(String src, 
                             boolean overwrite
                             ) throws IOException {
    return create(src, overwrite, defaultReplication, defaultBlockSize, null);
  }
    
  /**
   * Create a new dfs file and return an output stream for writing into it
   * with write-progress reporting. 
   * 
   * @param src stream name
   * @param overwrite do not check for file existence if true
   * @return output stream
   * @throws IOException
   */
  public OutputStream create(String src, 
                             boolean overwrite,
                             Progressable progress
                             ) throws IOException {
    return create(src, overwrite, defaultReplication, defaultBlockSize, null);
  }
    
  /**
   * Create a new dfs file with the specified block replication 
   * and return an output stream for writing into the file.  
   * 
   * @param src stream name
   * @param overwrite do not check for file existence if true
   * @param replication block replication
   * @return output stream
   * @throws IOException
   */
  public OutputStream create(String src, 
                             boolean overwrite, 
                             short replication,
                             long blockSize
                             ) throws IOException {
    return create(src, overwrite, replication, blockSize, null);
  }

  
  /**
   * Create a new dfs file with the specified block replication 
   * with write-progress reporting and return an output stream for writing
   * into the file.  
   * 
   * @param src stream name
   * @param overwrite do not check for file existence if true
   * @param replication block replication
   * @return output stream
   * @throws IOException
   */
  public OutputStream create(String src, 
                             boolean overwrite, 
                             short replication,
                             long blockSize,
                             Progressable progress
                             ) throws IOException {
    return create(src, overwrite, replication, blockSize, progress,
        conf.getInt("io.file.buffer.size", 4096));
  }
  /**
   * Call
   * {@link #create(String,FsPermission,boolean,short,long,Progressable,int)}
   * with default permission.
   * @see FsPermission#getDefault()
   */
  public OutputStream create(String src,
      boolean overwrite,
      short replication,
      long blockSize,
      Progressable progress,
      int buffersize
      ) throws IOException {
    return create(src, FsPermission.getDefault(),
        overwrite, replication, blockSize, progress, buffersize);
  }
  /**
   * Create a new dfs file with the specified block replication 
   * with write-progress reporting and return an output stream for writing
   * into the file.  
   * 
   * @param src stream name
   * @param permission The permission of the directory being created.
   * If permission == null, use {@link FsPermission#getDefault()}.
   * @param overwrite do not check for file existence if true
   * @param replication block replication
   * @return output stream
   * @throws IOException
   * @see ClientProtocol#create(String, FsPermission, String, boolean, short, long)
   */
  public OutputStream create(String src, 
                             FsPermission permission,
                             boolean overwrite, 
                             short replication,
                             long blockSize,
                             Progressable progress,
                             int buffersize
                             ) throws IOException {
    checkOpen();
    if (permission == null) {
      permission = FsPermission.getDefault();
    }
    FsPermission masked = permission.applyUMask(FsPermission.getUMask(conf));
    LOG.debug(src + ": masked=" + masked);
    OutputStream result = new DFSOutputStream(src, masked,
        overwrite, replication, blockSize, progress, buffersize);
    synchronized (pendingCreates) {
      pendingCreates.put(src.toString(), result);
    }
    return result;
  }
  /**
   * Set replication for an existing file.
   * 
   * @see ClientProtocol#setReplication(String, short)
   * @param replication
   * @throws IOException
   * @return true is successful or false if file does not exist 
   */
  public boolean setReplication(String src, 
                                short replication
                                ) throws IOException {
    return namenode.setReplication(src, replication);
  }

  /**
   * Rename file or directory.
   * See {@link ClientProtocol#rename(String, String)}. 
   */
  public boolean rename(String src, String dst) throws IOException {
    checkOpen();
    return namenode.rename(src, dst);
  }

  /**
   * Delete file or directory.
   * See {@link ClientProtocol#delete(String)}. 
   */
  public boolean delete(String src) throws IOException {
    checkOpen();
    return namenode.delete(src);
  }

  /**
   */
  public boolean exists(String src) throws IOException {
    checkOpen();
    return namenode.exists(src);
  }

  /**
   */
  public boolean isDirectory(String src) throws IOException {
    checkOpen();
    return namenode.isDir(src);
  }

  /**
   */
  public DFSFileInfo[] listPaths(String src) throws IOException {
    checkOpen();
    return namenode.getListing(src);
  }

  public DFSFileInfo getFileInfo(String src) throws IOException {
    checkOpen();
    return namenode.getFileInfo(src);
  }

  public DiskStatus getDiskStatus() throws IOException {
    long rawNums[] = namenode.getStats();
    return new DiskStatus(rawNums[0], rawNums[1], rawNums[2]);
  }
  /**
   */
  public long totalRawCapacity() throws IOException {
    long rawNums[] = namenode.getStats();
    return rawNums[0];
  }

  /**
   */
  public long totalRawUsed() throws IOException {
    long rawNums[] = namenode.getStats();
    return rawNums[1];
  }

  public DatanodeInfo[] datanodeReport(DatanodeReportType type)
  throws IOException {
    return namenode.getDatanodeReport(type);
  }
    
  /**
   * Enter, leave or get safe mode.
   * See {@link ClientProtocol#setSafeMode(FSConstants.SafeModeAction)} 
   * for more details.
   * 
   * @see ClientProtocol#setSafeMode(FSConstants.SafeModeAction)
   */
  public boolean setSafeMode(SafeModeAction action) throws IOException {
    return namenode.setSafeMode(action);
  }

  /**
   * Refresh the hosts and exclude files.  (Rereads them.)
   * See {@link ClientProtocol#refreshNodes()} 
   * for more details.
   * 
   * @see ClientProtocol#refreshNodes()
   */
  public void refreshNodes() throws IOException {
    namenode.refreshNodes();
  }

  /**
   * Dumps DFS data structures into specified file.
   * See {@link ClientProtocol#metaSave(String)} 
   * for more details.
   * 
   * @see ClientProtocol#metaSave(String)
   */
  public void metaSave(String pathname) throws IOException {
    namenode.metaSave(pathname);
  }
    
  /**
   * @see ClientProtocol#finalizeUpgrade()
   */
  public void finalizeUpgrade() throws IOException {
    namenode.finalizeUpgrade();
  }

  /**
   * @see ClientProtocol#distributedUpgradeProgress(FSConstants.UpgradeAction)
   */
  public UpgradeStatusReport distributedUpgradeProgress(UpgradeAction action
                                                        ) throws IOException {
    return namenode.distributedUpgradeProgress(action);
  }

  /**
   */
  public boolean mkdirs(String src) throws IOException {
    checkOpen();
    return namenode.mkdirs(src, null);
  }

  /**
   * Create a directory (or hierarchy of directories) with the given
   * name and permission.
   *
   * @param src The path of the directory being created
   * @param permission The permission of the directory being created.
   * If permission == null, use {@link FsPermission#getDefault()}.
   * @return True if the operation success.
   * @see ClientProtocol#mkdirs(String, FsPermission)
   */
  public boolean mkdirs(String src, FsPermission permission)throws IOException{
    checkOpen();
    if (permission == null) {
      permission = FsPermission.getDefault();
    }
    FsPermission masked = permission.applyUMask(FsPermission.getUMask(conf));
    LOG.debug(src + ": masked=" + masked);
    return namenode.mkdirs(src, masked);
  }

  /**
   * Retrieves the total size of all files and directories under
   * the specified path.
   * 
   * @param src
   * @throws IOException
   * @return the number of bytes in the subtree rooted at src
   */
  public long getContentLength(String src
                               ) throws IOException {
    return namenode.getContentLength(src);
  }

  /**
   * Pick the best node from which to stream the data.
   * Entries in <i>nodes</i> are already in the priority order
   */
  private DatanodeInfo bestNode(DatanodeInfo nodes[], 
                                AbstractMap<DatanodeInfo, DatanodeInfo> deadNodes)
                                throws IOException {
    if (nodes != null) { 
      for (int i = 0; i < nodes.length; i++) {
        if (!deadNodes.containsKey(nodes[i])) {
          return nodes[i];
        }
      }
    }
    throw new IOException("No live nodes contain current block");
  }

  /***************************************************************
   * Periodically check in with the namenode and renew all the leases
   * when the lease period is half over.
   ***************************************************************/
  class LeaseChecker implements Runnable {
    /**
     */
    public void run() {
      long lastRenewed = 0;
      while (clientRunning) {
        if (System.currentTimeMillis() - lastRenewed > (LEASE_SOFTLIMIT_PERIOD / 2)) {
          try {
            if (pendingCreates.size() > 0)
              namenode.renewLease(clientName);
            lastRenewed = System.currentTimeMillis();
          } catch (IOException ie) {
            String err = StringUtils.stringifyException(ie);
            LOG.warn("Problem renewing lease for " + clientName +
                     ": " + err);
          }
        }
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
        }
      }
    }
  }

  /** Utility class to encapsulate data node info and its ip address. */
  private static class DNAddrPair {
    DatanodeInfo info;
    InetSocketAddress addr;
    DNAddrPair(DatanodeInfo info, InetSocketAddress addr) {
      this.info = info;
      this.addr = addr;
    }
  }

  /** This is a wrapper around connection to datadone
   * and understands checksum, offset etc
   */
  static class BlockReader extends FSInputChecker {

    private DataInputStream in;
    private DataChecksum checksum;
    private long lastChunkOffset = -1;
    private long lastChunkLen = -1;

    private long startOffset;
    private long firstChunkOffset;
    private int bytesPerChecksum;
    private int checksumSize;
    private boolean gotEOS = false;
    
    byte[] skipBuf = null;
    
    /* FSInputChecker interface */
    
    /* same interface as inputStream java.io.InputStream#read()
     * used by DFSInputStream#read()
     * This violates one rule when there is a checksum error:
     * "Read should not modify user buffer before successful read"
     * because it first reads the data to user buffer and then checks
     * the checksum.
     */
    @Override
    public synchronized int read(byte[] buf, int off, int len) 
                                 throws IOException {
      
      //for the first read, skip the extra bytes at the front.
      if (lastChunkLen < 0 && startOffset > firstChunkOffset && len > 0) {
        // Skip these bytes. But don't call this.skip()!
        int toSkip = (int)(startOffset - firstChunkOffset);
        if ( skipBuf == null ) {
          skipBuf = new byte[bytesPerChecksum];
        }
        if ( super.read(skipBuf, 0, toSkip) != toSkip ) {
          // should never happen
          throw new IOException("Could not skip required number of bytes");
        }
      }
      
      return super.read(buf, off, len);
    }

    @Override
    public synchronized long skip(long n) throws IOException {
      /* How can we make sure we don't throw a ChecksumException, at least
       * in majority of the cases?. This one throws. */  
      if ( skipBuf == null ) {
        skipBuf = new byte[bytesPerChecksum]; 
      }

      long nSkipped = 0;
      while ( nSkipped < n ) {
        int toSkip = (int)Math.min(n-nSkipped, skipBuf.length);
        int ret = read(skipBuf, 0, toSkip);
        if ( ret <= 0 ) {
          return nSkipped;
        }
        nSkipped += ret;
      }
      return nSkipped;
    }

    @Override
    public int read() throws IOException {
      throw new IOException("read() is not expected to be invoked. " +
                            "Use read(buf, off, len) instead.");
    }
    
    @Override
    public boolean seekToNewSource(long targetPos) throws IOException {
      /* Checksum errors are handled outside the BlockReader. 
       * DFSInputStream does not always call 'seekToNewSource'. In the 
       * case of pread(), it just tries a different replica without seeking.
       */ 
      return false;
    }
    
    @Override
    public void seek(long pos) throws IOException {
      throw new IOException("Seek() is not supported in BlockInputChecker");
    }

    @Override
    protected long getChunkPosition(long pos) {
      throw new RuntimeException("getChunkPosition() is not supported, " +
                                 "since seek is not required");
    }
    
    @Override
    protected synchronized int readChunk(long pos, byte[] buf, int offset, 
                                         int len, byte[] checksumBuf) 
                                         throws IOException {
      // Read one chunk.
      
      if ( gotEOS ) {
        if ( startOffset < 0 ) {
          //This is mainly for debugging. can be removed.
          throw new IOException( "BlockRead: already got EOS or an error" );
        }
        startOffset = -1;
        return -1;
      }
      
      // Read one DATA_CHUNK.
      long chunkOffset = lastChunkOffset;
      if ( lastChunkLen > 0 ) {
        chunkOffset += lastChunkLen;
      }
      
      if ( (pos + firstChunkOffset) != chunkOffset ) {
        throw new IOException("Mismatch in pos : " + pos + " + " + 
                              firstChunkOffset + " != " + chunkOffset);
      }

      // The chunk is transmitted as one packet. Read packet headers.
      int packetLen = in.readInt();
      long offsetInBlock = in.readLong();
      long seqno = in.readLong();
      boolean lastPacketInBlock = in.readBoolean();
      LOG.debug("DFSClient readChunk got seqno " + seqno +
                " offsetInBlock " + offsetInBlock +
                " lastPacketInBlock " + lastPacketInBlock +
                " packetLen " + packetLen);

      int chunkLen = in.readInt();
      
      // Sanity check the lengths
      if ( chunkLen < 0 || chunkLen > bytesPerChecksum ||
          ( lastChunkLen >= 0 && // prev packet exists
              ( (chunkLen > 0 && lastChunkLen != bytesPerChecksum) ||
                  chunkOffset != (lastChunkOffset + lastChunkLen) ) ) ) {
        throw new IOException("BlockReader: error in chunk's offset " +
                              "or length (" + chunkOffset + ":" +
                              chunkLen + ")");
      }

      if ( chunkLen > 0 ) {
        // len should be >= chunkLen
        IOUtils.readFully(in, buf, offset, chunkLen);
      }
      
      if ( checksumSize > 0 ) {
        IOUtils.readFully(in, checksumBuf, 0, checksumSize);
      }

      lastChunkOffset = chunkOffset;
      lastChunkLen = chunkLen;
      
      if ( chunkLen == 0 ) {
        gotEOS = true;
        return -1;
      }
      
      return chunkLen;
    }
    
    private BlockReader( String file, long blockId, DataInputStream in, 
                         DataChecksum checksum, long startOffset,
                         long firstChunkOffset ) {
      super(new Path("/blk_" + blockId + ":of:" + file)/*too non path-like?*/,
            1, (checksum.getChecksumSize() > 0) ? checksum : null, 
            checksum.getBytesPerChecksum(),
            checksum.getChecksumSize());
      
      this.in = in;
      this.checksum = checksum;
      this.startOffset = Math.max( startOffset, 0 );

      this.firstChunkOffset = firstChunkOffset;
      lastChunkOffset = firstChunkOffset;
      lastChunkLen = -1;

      bytesPerChecksum = this.checksum.getBytesPerChecksum();
      checksumSize = this.checksum.getChecksumSize();
    }

    /** Java Doc required */
    static BlockReader newBlockReader( Socket sock, String file, long blockId, 
                                       long startOffset, long len,
                                       int bufferSize)
                                       throws IOException {
      
      // in and out will be closed when sock is closed (by the caller)
      DataOutputStream out = new DataOutputStream(
                       new BufferedOutputStream(sock.getOutputStream()));

      //write the header.
      out.writeShort( DATA_TRANFER_VERSION );
      out.write( OP_READ_BLOCK );
      out.writeLong( blockId );
      out.writeLong( startOffset );
      out.writeLong( len );
      out.flush();

      //
      // Get bytes in block, set streams
      //

      DataInputStream in = new DataInputStream(
                   new BufferedInputStream(sock.getInputStream(), bufferSize));
      
      if ( in.readShort() != OP_STATUS_SUCCESS ) {
        throw new IOException("Got error in response to OP_READ_BLOCK " +
                              "for file " + file + 
                              " for block " + blockId);
      }
      DataChecksum checksum = DataChecksum.newDataChecksum( in );
      //Warning when we get CHECKSUM_NULL?
      
      // Read the first chunk offset.
      long firstChunkOffset = in.readLong();
      
      if ( firstChunkOffset < 0 || firstChunkOffset > startOffset ||
          firstChunkOffset >= (startOffset + checksum.getBytesPerChecksum())) {
        throw new IOException("BlockReader: error in first chunk offset (" +
                              firstChunkOffset + ") startOffset is " + 
                              startOffset + " for file " + file);
      }

      return new BlockReader( file, blockId, in, checksum,
                              startOffset, firstChunkOffset );
    }

    @Override
    public synchronized void close() throws IOException {
      startOffset = -1;
      checksum = null;
      // in will be closed when its Socket is closed.
    }
    
    /** kind of like readFully(). Only reads as much as possible.
     * And allows use of protected readFully().
     */
    int readAll(byte[] buf, int offset, int len) throws IOException {
      return readFully(this, buf, offset, len);
    }
    
    /* When the reader reaches end of a block and there are no checksum
     * errors, we send OP_STATUS_CHECKSUM_OK to datanode to inform that 
     * checksum was verified and there was no error.
     */ 
    void checksumOk(Socket sock) {
      try {
        OutputStream out = sock.getOutputStream();
        byte buf[] = { (OP_STATUS_CHECKSUM_OK >>> 8) & 0xff,
                       (OP_STATUS_CHECKSUM_OK) & 0xff };
        out.write(buf);
        out.flush();
      } catch (IOException e) {
        // its ok not to be able to send this.
        LOG.debug("Could not write to datanode " + sock.getInetAddress() +
                  ": " + e.getMessage());
      }
    }
  }
    
  /****************************************************************
   * DFSInputStream provides bytes from a named file.  It handles 
   * negotiation of the namenode and various datanodes as necessary.
   ****************************************************************/
  class DFSInputStream extends FSInputStream {
    private Socket s = null;
    private boolean closed = false;

    private String src;
    private long prefetchSize = 10 * defaultBlockSize;
    private BlockReader blockReader;
    private LocatedBlocks locatedBlocks = null;
    private DatanodeInfo currentNode = null;
    private Block currentBlock = null;
    private long pos = 0;
    private long blockEnd = -1;
    /* XXX Use of CocurrentHashMap is temp fix. Need to fix 
     * parallel accesses to DFSInputStream (through ptreads) properly */
    private ConcurrentHashMap<DatanodeInfo, DatanodeInfo> deadNodes = 
               new ConcurrentHashMap<DatanodeInfo, DatanodeInfo>();
    private int buffersize = 1;
    
    private byte[] oneByteBuf = new byte[1]; // used for 'int read()'
    
    void addToDeadNodes(DatanodeInfo dnInfo) {
      deadNodes.put(dnInfo, dnInfo);
    }
    
    /**
     */
    public DFSInputStream(String src, int buffersize) throws IOException {
      this.buffersize = buffersize;
      this.src = src;
      prefetchSize = conf.getLong("dfs.read.prefetch.size", prefetchSize);
      openInfo();
      blockReader = null;
    }

    /**
     * Grab the open-file info from namenode
     */
    synchronized void openInfo() throws IOException {
      LocatedBlocks newInfo = namenode.open(src, 0, prefetchSize);

      if (locatedBlocks != null) {
        Iterator<LocatedBlock> oldIter = locatedBlocks.getLocatedBlocks().iterator();
        Iterator<LocatedBlock> newIter = newInfo.getLocatedBlocks().iterator();
        while (oldIter.hasNext() && newIter.hasNext()) {
          if (! oldIter.next().getBlock().equals(newIter.next().getBlock())) {
            throw new IOException("Blocklist for " + src + " has changed!");
          }
        }
      }
      this.locatedBlocks = newInfo;
      this.currentNode = null;
    }
    
    public synchronized long getFileLength() {
      return (locatedBlocks == null) ? 0 : locatedBlocks.getFileLength();
    }

    /**
     * Returns the datanode from which the stream is currently reading.
     */
    public DatanodeInfo getCurrentDatanode() {
      return currentNode;
    }

    /**
     * Returns the block containing the target position. 
     */
    public Block getCurrentBlock() {
      return currentBlock;
    }

    /**
     * Return collection of blocks that has already been located.
     */
    synchronized List<LocatedBlock> getAllBlocks() throws IOException {
      return getBlockRange(0, this.getFileLength());
    }

    /**
     * Get block at the specified position.
     * Fetch it from the namenode if not cached.
     * 
     * @param offset
     * @return located block
     * @throws IOException
     */
    private LocatedBlock getBlockAt(long offset) throws IOException {
      assert (locatedBlocks != null) : "locatedBlocks is null";
      // search cached blocks first
      int targetBlockIdx = locatedBlocks.findBlock(offset);
      if (targetBlockIdx < 0) { // block is not cached
        targetBlockIdx = LocatedBlocks.getInsertIndex(targetBlockIdx);
        // fetch more blocks
        LocatedBlocks newBlocks;
        newBlocks = namenode.getBlockLocations(src, offset, prefetchSize);
        assert (newBlocks != null) : "Could not find target position " + offset;
        locatedBlocks.insertRange(targetBlockIdx, newBlocks.getLocatedBlocks());
      }
      LocatedBlock blk = locatedBlocks.get(targetBlockIdx);
      // update current position
      this.pos = offset;
      this.blockEnd = blk.getStartOffset() + blk.getBlockSize() - 1;
      this.currentBlock = blk.getBlock();
      return blk;
    }

    /**
     * Get blocks in the specified range.
     * Fetch them from the namenode if not cached.
     * 
     * @param offset
     * @param length
     * @return consequent segment of located blocks
     * @throws IOException
     */
    private synchronized List<LocatedBlock> getBlockRange(long offset, 
                                                          long length) 
                                                        throws IOException {
      assert (locatedBlocks != null) : "locatedBlocks is null";
      List<LocatedBlock> blockRange = new ArrayList<LocatedBlock>();
      // search cached blocks first
      int blockIdx = locatedBlocks.findBlock(offset);
      if (blockIdx < 0) { // block is not cached
        blockIdx = LocatedBlocks.getInsertIndex(blockIdx);
      }
      long remaining = length;
      long curOff = offset;
      while(remaining > 0) {
        LocatedBlock blk = null;
        if(blockIdx < locatedBlocks.locatedBlockCount())
          blk = locatedBlocks.get(blockIdx);
        if (blk == null || curOff < blk.getStartOffset()) {
          LocatedBlocks newBlocks;
          newBlocks = namenode.getBlockLocations(src, curOff, remaining);
          locatedBlocks.insertRange(blockIdx, newBlocks.getLocatedBlocks());
          continue;
        }
        assert curOff >= blk.getStartOffset() : "Block not found";
        blockRange.add(blk);
        long bytesRead = blk.getStartOffset() + blk.getBlockSize() - curOff;
        remaining -= bytesRead;
        curOff += bytesRead;
        blockIdx++;
      }
      return blockRange;
    }

    /**
     * Open a DataInputStream to a DataNode so that it can be read from.
     * We get block ID and the IDs of the destinations at startup, from the namenode.
     */
    private synchronized DatanodeInfo blockSeekTo(long target) throws IOException {
      if (target >= getFileLength()) {
        throw new IOException("Attempted to read past end of file");
      }

      if ( blockReader != null ) {
        blockReader.close(); 
        blockReader = null;
      }
      
      if (s != null) {
        s.close();
        s = null;
      }

      //
      // Compute desired block
      //
      LocatedBlock targetBlock = getBlockAt(target);
      assert (target==this.pos) : "Wrong postion " + pos + " expect " + target;
      long offsetIntoBlock = target - targetBlock.getStartOffset();

      //
      // Connect to best DataNode for desired Block, with potential offset
      //
      DatanodeInfo chosenNode = null;
      while (s == null) {
        DNAddrPair retval = chooseDataNode(targetBlock);
        chosenNode = retval.info;
        InetSocketAddress targetAddr = retval.addr;

        try {
          s = socketFactory.createSocket();
          s.connect(targetAddr, socketTimeout);
          s.setSoTimeout(socketTimeout);
          Block blk = targetBlock.getBlock();
          
          blockReader = BlockReader.newBlockReader(s, src, blk.getBlockId(), 
                                                   offsetIntoBlock,
                                                   (blk.getNumBytes() - 
                                                    offsetIntoBlock),
                                                   buffersize);
          return chosenNode;
        } catch (IOException ex) {
          // Put chosen node into dead list, continue
          LOG.debug("Failed to connect to " + targetAddr + ":" 
                    + StringUtils.stringifyException(ex));
          addToDeadNodes(chosenNode);
          if (s != null) {
            try {
              s.close();
            } catch (IOException iex) {
            }                        
          }
          s = null;
        }
      }
      return chosenNode;
    }

    /**
     * Close it down!
     */
    @Override
    public synchronized void close() throws IOException {
      checkOpen();
      if (closed) {
        throw new IOException("Stream closed");
      }

      if ( blockReader != null ) {
        blockReader.close();
        blockReader = null;
      }
      
      if (s != null) {
        s.close();
        s = null;
      }
      super.close();
      closed = true;
    }

    @Override
    public synchronized int read() throws IOException {
      int ret = read( oneByteBuf, 0, 1 );
      return ( ret <= 0 ) ? -1 : (oneByteBuf[0] & 0xff);
    }

    /* This is a used by regular read() and handles ChecksumExceptions.
     * name readBuffer() is chosen to imply similarity to readBuffer() in
     * ChecksuFileSystem
     */ 
    private synchronized int readBuffer(byte buf[], int off, int len) 
                                                    throws IOException {
      IOException ioe;
 
      while (true) {
        // retry as many times as seekToNewSource allows.
        try {
          return blockReader.read(buf, off, len);
        } catch ( ChecksumException ce ) {
          LOG.warn("Found Checksum error for " + currentBlock + " from " +
                   currentNode.getName() + " at " + ce.getPos());          
          reportChecksumFailure(src, currentBlock, currentNode);
          ioe = ce;
        } catch ( IOException e ) {
          LOG.warn("Exception while reading from " + currentBlock +
                   " of " + src + " from " + currentNode + ": " +
                   StringUtils.stringifyException(e));
          ioe = e;
        }
        addToDeadNodes(currentNode);
        if (!seekToNewSource(pos)) {
            throw ioe;
        }
      }
    }

    /**
     * Read the entire buffer.
     */
    @Override
    public synchronized int read(byte buf[], int off, int len) throws IOException {
      checkOpen();
      if (closed) {
        throw new IOException("Stream closed");
      }
      if (pos < getFileLength()) {
        int retries = 2;
        while (retries > 0) {
          try {
            if (pos > blockEnd) {
              currentNode = blockSeekTo(pos);
            }
            int realLen = Math.min(len, (int) (blockEnd - pos + 1));
            int result = readBuffer(buf, off, realLen);
            
            if (result >= 0) {
              pos += result;
              if ( pos > blockEnd ) {
                blockReader.checksumOk(s);
              }
            } else {
              // got a EOS from reader though we expect more data on it.
              throw new IOException("Unexpected EOS from the reader");
            }
            return result;
          } catch (ChecksumException ce) {
            throw ce;            
          } catch (IOException e) {
            if (retries == 1) {
              LOG.warn("DFS Read: " + StringUtils.stringifyException(e));
            }
            blockEnd = -1;
            if (currentNode != null) { addToDeadNodes(currentNode); }
            if (--retries == 0) {
              throw e;
            }
          }
        }
      }
      return -1;
    }

        
    private DNAddrPair chooseDataNode(LocatedBlock block)
      throws IOException {
      int failures = 0;
      while (true) {
        DatanodeInfo[] nodes = block.getLocations();
        try {
          DatanodeInfo chosenNode = bestNode(nodes, deadNodes);
          InetSocketAddress targetAddr = 
                            NetUtils.createSocketAddr(chosenNode.getName());
          return new DNAddrPair(chosenNode, targetAddr);
        } catch (IOException ie) {
          String blockInfo = block.getBlock() + " file=" + src;
          if (failures >= MAX_BLOCK_ACQUIRE_FAILURES) {
            throw new IOException("Could not obtain block: " + blockInfo);
          }
          
          if (nodes == null || nodes.length == 0) {
            LOG.info("No node available for block: " + blockInfo);
          }
          LOG.info("Could not obtain block " + block.getBlock() + " from any node:  " + ie);
          try {
            Thread.sleep(3000);
          } catch (InterruptedException iex) {
          }
          deadNodes.clear(); //2nd option is to remove only nodes[blockId]
          openInfo();
          failures++;
          continue;
        }
      }
    } 
        
    private void fetchBlockByteRange(LocatedBlock block, long start,
                                     long end, byte[] buf, int offset) throws IOException {
      //
      // Connect to best DataNode for desired Block, with potential offset
      //
      Socket dn = null;
      int numAttempts = block.getLocations().length;
      IOException ioe = null;
      
      while (dn == null && numAttempts-- > 0 ) {
        DNAddrPair retval = chooseDataNode(block);
        DatanodeInfo chosenNode = retval.info;
        InetSocketAddress targetAddr = retval.addr;
            
        try {
          dn = socketFactory.createSocket();
          dn.connect(targetAddr, socketTimeout);
          dn.setSoTimeout(socketTimeout);
              
          int len = (int) (end - start + 1);
              
          BlockReader reader = 
            BlockReader.newBlockReader(dn, src, block.getBlock().getBlockId(),
                                       start, len, buffersize);
          int nread = reader.readAll(buf, offset, len);
          if (nread != len) {
            throw new IOException("truncated return from reader.read(): " +
                                  "excpected " + len + ", got " + nread);
          }
          return;
        } catch (ChecksumException e) {
          ioe = e;
          LOG.warn("fetchBlockByteRange(). Got a checksum exception for " +
                   src + " at " + block.getBlock() + ":" + 
                   e.getPos() + " from " + chosenNode.getName());
          reportChecksumFailure(src, block.getBlock(), chosenNode);
        } catch (IOException e) {
          ioe = e;
          LOG.warn("Failed to connect to " + targetAddr + 
                   " for file " + src + 
                   " for block " + block.getBlock().getBlockId() + ":"  +
                   StringUtils.stringifyException(e));
        } 
        // Put chosen node into dead list, continue
        addToDeadNodes(chosenNode);
        if (dn != null) {
          try {
            dn.close();
          } catch (IOException iex) {
          }
          dn = null;
        }
      }
      throw (ioe == null) ? new IOException("Could not read data") : ioe;
    }

    /**
     * Read bytes starting from the specified position.
     * 
     * @param position start read from this position
     * @param buffer read buffer
     * @param offset offset into buffer
     * @param length number of bytes to read
     * 
     * @return actual number of bytes read
     */
    @Override
    public int read(long position, byte[] buffer, int offset, int length)
      throws IOException {
      // sanity checks
      checkOpen();
      if (closed) {
        throw new IOException("Stream closed");
      }
      long filelen = getFileLength();
      if ((position < 0) || (position >= filelen)) {
        return -1;
      }
      int realLen = length;
      if ((position + length) > filelen) {
        realLen = (int)(filelen - position);
      }
      
      // determine the block and byte range within the block
      // corresponding to position and realLen
      List<LocatedBlock> blockRange = getBlockRange(position, realLen);
      int remaining = realLen;
      for (LocatedBlock blk : blockRange) {
        long targetStart = position - blk.getStartOffset();
        long bytesToRead = Math.min(remaining, blk.getBlockSize() - targetStart);
        fetchBlockByteRange(blk, targetStart, 
                            targetStart + bytesToRead - 1, buffer, offset);
        remaining -= bytesToRead;
        position += bytesToRead;
        offset += bytesToRead;
      }
      assert remaining == 0 : "Wrong number of bytes read.";
      return realLen;
    }
     
    @Override
    public long skip(long n) throws IOException {
      if ( n > 0 ) {
        long curPos = getPos();
        long fileLen = getFileLength();
        if( n+curPos > fileLen ) {
          n = fileLen - curPos;
        }
        seek(curPos+n);
        return n;
      }
      return n < 0 ? -1 : 0;
    }

    /**
     * Seek to a new arbitrary location
     */
    @Override
    public synchronized void seek(long targetPos) throws IOException {
      if (targetPos > getFileLength()) {
        throw new IOException("Cannot seek after EOF");
      }
      boolean done = false;
      if (pos <= targetPos && targetPos <= blockEnd) {
        //
        // If this seek is to a positive position in the current
        // block, and this piece of data might already be lying in
        // the TCP buffer, then just eat up the intervening data.
        //
        int diff = (int)(targetPos - pos);
        if (diff <= TCP_WINDOW_SIZE) {
          pos += blockReader.skip(diff);
          if (pos == targetPos) {
            done = true;
          }
        }
      }
      if (!done) {
        pos = targetPos;
        blockEnd = -1;
      }
    }

    /**
     * Seek to given position on a node other than the current node.  If
     * a node other than the current node is found, then returns true. 
     * If another node could not be found, then returns false.
     */
    @Override
    public synchronized boolean seekToNewSource(long targetPos) throws IOException {
      boolean markedDead = deadNodes.containsKey(currentNode);
      addToDeadNodes(currentNode);
      DatanodeInfo oldNode = currentNode;
      DatanodeInfo newNode = blockSeekTo(targetPos);
      if (!markedDead) {
        /* remove it from deadNodes. blockSeekTo could have cleared 
         * deadNodes and added currentNode again. Thats ok. */
        deadNodes.remove(oldNode);
      }
      if (!oldNode.getStorageID().equals(newNode.getStorageID())) {
        currentNode = newNode;
        return true;
      } else {
        return false;
      }
    }
        
    /**
     */
    @Override
    public synchronized long getPos() throws IOException {
      return pos;
    }

    /**
     */
    @Override
    public synchronized int available() throws IOException {
      if (closed) {
        throw new IOException("Stream closed");
      }
      return (int) (getFileLength() - pos);
    }

    /**
     * We definitely don't support marks
     */
    @Override
    public boolean markSupported() {
      return false;
    }
    @Override
    public void mark(int readLimit) {
    }
    @Override
    public void reset() throws IOException {
      throw new IOException("Mark/reset not supported");
    }
  }
    
  static class DFSDataInputStream extends FSDataInputStream {
    DFSDataInputStream(DFSInputStream in)
      throws IOException {
      super(in);
    }
      
    /**
     * Returns the datanode from which the stream is currently reading.
     */
    public DatanodeInfo getCurrentDatanode() {
      return ((DFSInputStream)in).getCurrentDatanode();
    }
      
    /**
     * Returns the block containing the target position. 
     */
    public Block getCurrentBlock() {
      return ((DFSInputStream)in).getCurrentBlock();
    }

    /**
     * Return collection of blocks that has already been located.
     */
    synchronized List<LocatedBlock> getAllBlocks() throws IOException {
      return ((DFSInputStream)in).getAllBlocks();
    }

  }

  /****************************************************************
   * DFSOutputStream creates files from a stream of bytes.
   *
   * The client application writes data that is cached internally by
   * this stream. Data is broken up into packets, each packet is
   * typically 64K in size. A packet comprises of chunks. Each chunk
   * is typically 512 bytes and has an associated checksum with it.
   *
   * When a client application fills up the currentPacket, it is
   * enqueued into dataQueue.  The DataStreamer thread picks up
   * packets from the dataQueue, sends it to the first datanode in
   * the pipeline and moves it from the dataQueue to the ackQueue.
   * The ResponseProcessor receives acks from the datanodes. When an
   * successful ack for a packet is received from all datanodes, the
   * ResponseProcessor removes the corresponding packet from the
   * ackQueue.
   *
   * In case of error, all outstanding packets and moved from
   * ackQueue. A new pipeline is setup by eliminating the bad
   * datanode from the original pipeline. The DataStreamer now
   * starts sending packets from the dataQueue.
  ****************************************************************/
  class DFSOutputStream extends FSOutputSummer {
    private Socket s;
    boolean closed = false;
  
    private String src;
    private DataOutputStream blockStream;
    private DataInputStream blockReplyStream;
    private Block block;
    private long blockSize;
    private int buffersize;
    private DataChecksum checksum;
    private LinkedList<Packet> dataQueue = new LinkedList<Packet>();
    private LinkedList<Packet> ackQueue = new LinkedList<Packet>();
    private Packet currentPacket = null;
    private int maxPackets = 80; // each packet 64K, total 5MB
    // private int maxPackets = 1000; // each packet 64K, total 64MB
    private DataStreamer streamer;
    private ResponseProcessor response = null;
    private long currentSeqno = 0;
    private long bytesCurBlock = 0; // bytes writen in current block
    private int packetSize = 0;
    private int chunksPerPacket = 0;
    private int chunksPerBlock = 0;
    private int chunkSize = 0;
    private DatanodeInfo[] nodes = null; // list of targets for current block
    private volatile boolean hasError = false;
    private volatile int errorIndex = 0;
    private IOException lastException = new IOException("Stream closed.");
    private long artificialSlowdown = 0;

    private class Packet {
      ByteBuffer buffer;
      long    seqno;               // sequencenumber of buffer in block
      long    offsetInBlock;       // offset in block
      boolean lastPacketInBlock;   // is this the last packet in block?
      int     numChunks;           // number of chunks currently in packet
  
      // create a new packet
      Packet(int size, long offsetInBlock) {
        buffer = ByteBuffer.allocate(size);
        buffer.clear();
        this.lastPacketInBlock = false;
        this.numChunks = 0;
        this.offsetInBlock = offsetInBlock;
        this.seqno = currentSeqno;
        currentSeqno++;
      }
  
      // writes len bytes from offset off in inarray into
      // this packet.
      // 
      void write(byte[] inarray, int off, int len) {
        buffer.put(inarray, off, len);
      }
  
      // writes an integer into this packet. 
      //
      void  writeInt(int value) {
       buffer.putInt(value);
      }
    }
  
    //
    // The DataStreamer class is responsible for sending data packets to the
    // datanodes in the pipeline. It retrieves a new blockid and block locations
    // from the namenode, and starts streaming packets to the pipeline of
    // Datanodes. Every packet has a sequence number associated with
    // it. When all the packets for a block are sent out and acks for each
    // if them are received, the DataStreamer closes the current block.
    //
    private class DataStreamer extends Thread {

      private volatile boolean closed = false;
  
      public void run() {

        while (!closed && clientRunning) {

          // if the Responder encountered an error, shutdown Responder
          if (hasError && response != null) {
            try {
              response.close();
              response.join();
              response = null;
            } catch (InterruptedException  e) {
            }
          }

          Packet one = null;
          synchronized (dataQueue) {

            // process IO errors if any
            processDatanodeError();

            // wait for a packet to be sent.
            while (!closed && !hasError && clientRunning 
                   && dataQueue.size() == 0) {
              try {
                dataQueue.wait(1000);
              } catch (InterruptedException  e) {
              }
            }
            if (closed || hasError || dataQueue.size() == 0 || !clientRunning) {
              continue;
            }

            try {
              // get packet to be sent.
              one = dataQueue.getFirst();
              int len = one.buffer.limit();
  
              // get new block from namenode.
              if (blockStream == null) {
                LOG.debug("Allocating new block");
                nodes = nextBlockOutputStream(src); 
                this.setName("DataStreamer for file " + src +
                             " block " + block);
                response = new ResponseProcessor(nodes);
                response.start();
              }

              // user bytes from 'position' to 'limit'.
              byte[] arr = one.buffer.array();
              if (one.offsetInBlock >= blockSize) {
                throw new IOException("BlockSize " + blockSize +
                                      " is smaller than data size. " +
                                      " Offset of packet in block " + 
                                      one.offsetInBlock +
                                      " Aborting file " + src);
              }

              // move packet from dataQueue to ackQueue
              dataQueue.removeFirst();
              dataQueue.notifyAll();
              synchronized (ackQueue) {
                ackQueue.addLast(one);
                ackQueue.notifyAll();
              } 
  
              // write out data to remote datanode
              blockStream.writeInt(len); // size of this packet
              blockStream.writeLong(one.offsetInBlock); // data offset in block
              blockStream.writeLong(one.seqno); // sequence num of packet
              blockStream.writeBoolean(one.lastPacketInBlock); 
              blockStream.write(arr, 0, len);
              if (one.lastPacketInBlock) {
                blockStream.writeInt(0); // indicate end-of-block 
              }
              blockStream.flush();
              LOG.debug("DataStreamer block " + block +
                        " wrote packet seqno:" + one.seqno +
                        " size:" + len + 
                        " offsetInBlock:" + one.offsetInBlock + 
                        " lastPacketInBlock:" + one.lastPacketInBlock);
            } catch (IOException e) {
              LOG.warn("DataStreamer Exception: " + e);
			  hasError = true;
		    }
	      }

          if (closed || hasError || !clientRunning) {
            continue;
          }

          // Is this block full?
          if (one.lastPacketInBlock) {
            synchronized (ackQueue) {
              while (!hasError && ackQueue.size() != 0 && clientRunning) {
                try {
                  ackQueue.wait();   // wait for acks to arrive from datanodes
                } catch (InterruptedException  e) {
                }
              }
            }
            LOG.debug("Closing old block " + block);
            this.setName("DataStreamer for file " + src);

            response.close();        // ignore all errors in Response
            try {
              response.join();
              response = null;
            } catch (InterruptedException  e) {
            }

            if (closed || hasError || !clientRunning) {
              continue;
            }

            synchronized (dataQueue) {
              try {
                blockStream.close();
                blockReplyStream.close();
              } catch (IOException e) {
              }
              nodes = null;
              response = null;
              blockStream = null;
              blockReplyStream = null;
            }
          }
          if (progress != null) { progress.progress(); }

          // This is used by unit test to trigger race conditions.
          if (artificialSlowdown != 0 && clientRunning) {
            try { 
              Thread.sleep(artificialSlowdown); 
            } catch (InterruptedException e) {}
          }
		}
	  }

      // shutdown thread
      void close() {
        closed = true;
        synchronized (dataQueue) {
          dataQueue.notifyAll();
        }
        synchronized (ackQueue) {
          ackQueue.notifyAll();
        }
        this.interrupt();
      }
	}
		  
    //
	// Processes reponses from the datanodes.  A packet is removed 
	// from the ackQueue when its response arrives.
	//
    private class ResponseProcessor extends Thread {

      private volatile boolean closed = false;
      private DatanodeInfo[] targets = null;
      private boolean lastPacketInBlock = false;

      ResponseProcessor (DatanodeInfo[] targets) {
        this.targets = targets;
      }

	  public void run() {

        this.setName("ResponseProcessor for block " + block);
  
        while (!closed && clientRunning && !lastPacketInBlock) {
		    // process responses from datanodes.
		    try {
			  // verify seqno from datanode
              int numTargets = -1;
			  long seqno = blockReplyStream.readLong();
              LOG.debug("DFSClient received ack for seqno " + seqno);
              if (seqno == -1) {
                continue;
              } else if (seqno == -2) {
                // no nothing
              } else {
			    Packet one = null;
			    synchronized (ackQueue) {
			      one = ackQueue.getFirst();
			    }
			    if (one.seqno != seqno) {
			      throw new IOException("Responseprocessor: Expecting seqno " + 
                                        " for block " + block +
			                            one.seqno + " but received " + seqno);
			    }
                lastPacketInBlock = one.lastPacketInBlock;
              }

              // processes response status from all datanodes.
              for (int i = 0; i < targets.length && clientRunning; i++) {
                short reply = blockReplyStream.readShort();
                if (reply != OP_STATUS_SUCCESS) {
                  errorIndex = i; // first bad datanode
                  throw new IOException("Bad response " + reply +
                                        " for block " + block +
                                        " from datanode " + 
                                        targets[i].getName());
                }
              }

              synchronized (ackQueue) {
                ackQueue.removeFirst();
                ackQueue.notifyAll();
              }
            } catch (Exception e) {
              if (!closed) {
                hasError = true;
                LOG.warn("DFSOutputStream ResponseProcessor exception " + 
                         " for block " + block +
                          StringUtils.stringifyException(e));
                closed = true;
              }
            }

            synchronized (dataQueue) {
              dataQueue.notifyAll();
            }
            synchronized (ackQueue) {
              ackQueue.notifyAll();
            }
          }
        }

        void close() {
          closed = true;
          this.interrupt();
        }
      }

    // If this stream has encountered any errors so far, shutdown 
    // threads and mark stream as closed.
    //
    private void processDatanodeError() {
      if (!hasError) {
        return;
      }
      if (response != null) {
        LOG.info("Error Recovery for block " + block +
                 " waiting for responder to exit. ");
        return;
      }
      String msg = "Error Recovery for block " + block +
                   " bad datanode[" + errorIndex + "]";
      if (nodes != null) {
        msg += " " + nodes[errorIndex].getName();
      }
      LOG.warn(msg);

      if (blockStream != null) {
        try {
          blockStream.close();
          blockReplyStream.close();
        } catch (IOException e) {
        }
      }
      blockStream = null;
      blockReplyStream = null;

      // move packets from ack queue to front of the data queue
      synchronized (ackQueue) {
        dataQueue.addAll(0, ackQueue);
        ackQueue.clear();
      }

      boolean success = false;
      while (!success && clientRunning) {
        if (nodes == null) {
          lastException = new IOException("Could not get block locations. " +
                                          "Aborting...");
          closed = true;
          streamer.close();
          return;
        }
        StringBuilder pipelineMsg = new StringBuilder();
        for (int j = 0; j < nodes.length; j++) {
          pipelineMsg.append(nodes[j].getName());
          if (j < nodes.length - 1) {
            pipelineMsg.append(", ");
          }
        }
        String pipeline = pipelineMsg.toString();
        if (nodes.length <= 1) {
          lastException = new IOException("All datanodes " +
                                          pipeline + " are bad. Aborting...");
          closed = true;
          streamer.close();
          return;
        }
        LOG.warn("Error Recovery for block " + block +
                 " in pipeline " + pipeline + 
                 ": bad datanode " + nodes[errorIndex].getName());

        // remove bad datanode from list of datanodes.
        //
        DatanodeInfo[] newnodes =  new DatanodeInfo[nodes.length-1];
        for (int i = 0; i < errorIndex; i++) {
          newnodes[i] = nodes[i];
        }
        for (int i = errorIndex; i < (nodes.length-1); i++) {
          newnodes[i] = nodes[i+1];
        }
        nodes = newnodes;

        // setup new pipeline
        hasError = false;
        errorIndex = 0;
        success = createBlockOutputStream(nodes, src, true);
      }

      response = new ResponseProcessor(nodes);
      response.start();
    }

    private void isClosed() throws IOException {
      if (closed) {
        throw lastException;
      }
    }

    //
    // returns the list of targets, if any, that is being currently used.
    //
    DatanodeInfo[] getPipeline() {
      synchronized (dataQueue) {
        if (nodes == null) {
          return null;
        }
        DatanodeInfo[] value = new DatanodeInfo[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
          value[i] = nodes[i];
        }
        return value;
      }
    }

    private Progressable progress;
    /**
     * Create a new output stream to the given DataNode.
     * @see ClientProtocol#create(String, FsPermission, String, boolean, short, long)
     */
    public DFSOutputStream(String src, FsPermission masked,
                           boolean overwrite,
                           short replication, long blockSize,
                           Progressable progress,
                           int buffersize
                           ) throws IOException {
      super(new CRC32(), conf.getInt("io.bytes.per.checksum", 512), 4);
      this.src = src;
      this.blockSize = blockSize;
      this.buffersize = buffersize;
      this.progress = progress;
      if (progress != null) {
        LOG.debug("Set non-null progress callback on DFSOutputStream "+src);
      }
      
      int bytesPerChecksum = conf.getInt( "io.bytes.per.checksum", 512); 
      if ( bytesPerChecksum < 1 || blockSize % bytesPerChecksum != 0) {
        throw new IOException("io.bytes.per.checksum(" + bytesPerChecksum +
                              ") and blockSize(" + blockSize + 
                              ") do not match. " + "blockSize should be a " +
                              "multiple of io.bytes.per.checksum");
                              
      }
      checksum = DataChecksum.newDataChecksum(DataChecksum.CHECKSUM_CRC32, 
                                              bytesPerChecksum);
      // A maximum of 128 chunks per packet, i.e. 64K packet size.
      chunkSize = bytesPerChecksum + 2 * SIZE_OF_INTEGER; // user data & checksum
      chunksPerBlock = (int)(blockSize / bytesPerChecksum);
      chunksPerPacket = Math.min(chunksPerBlock, 128);
      packetSize = chunkSize * chunksPerPacket;

      namenode.create(
          src, masked, clientName, overwrite, replication, blockSize);
      streamer = new DataStreamer();
      streamer.start();
    }
  
    /**
     * Open a DataOutputStream to a DataNode so that it can be written to.
     * This happens when a file is created and each time a new block is allocated.
     * Must get block ID and the IDs of the destinations from the namenode.
     * Returns the list of target datanodes.
     */
    private DatanodeInfo[] nextBlockOutputStream(String client) throws IOException {
      LocatedBlock lb = null;
      boolean retry = false;
      DatanodeInfo[] nodes;
      int count = conf.getInt("dfs.client.block.write.retries", 3);
      boolean success;
      do {
        hasError = false;
        errorIndex = 0;
        retry = false;
        nodes = null;
        success = false;
                
        long startTime = System.currentTimeMillis();
        lb = locateFollowingBlock(startTime);
        block = lb.getBlock();
        nodes = lb.getLocations();
  
        //
        // Connect to first DataNode in the list.
        //
        success = createBlockOutputStream(nodes, client, false);

        if (!success) {
          LOG.info("Abandoning block " + block);
          namenode.abandonBlock(block, src, clientName);

          // Connection failed.  Let's wait a little bit and retry
          retry = true;
          try {
            if (System.currentTimeMillis() - startTime > 5000) {
              LOG.info("Waiting to find target node: " + nodes[0].getName());
            }
            Thread.sleep(6000);
          } catch (InterruptedException iex) {
          }
        }
      } while (retry && --count >= 0);

      if (!success) {
        throw new IOException("Unable to create new block.");
      }
      return nodes;
    }

    // connects to the first datanode in the pipeline
    // Returns true if success, otherwise return failure.
    //
    private boolean createBlockOutputStream(DatanodeInfo[] nodes, String client,
                    boolean recoveryFlag) {
      String firstBadLink = "";
      if (LOG.isDebugEnabled()) {
        for (int i = 0; i < nodes.length; i++) {
          LOG.debug("pipeline = " + nodes[i].getName());
        }
      }
      try {
        LOG.debug("Connecting to " + nodes[0].getName());
        InetSocketAddress target = NetUtils.createSocketAddr(nodes[0].getName());
        s = socketFactory.createSocket();
        int timeoutValue = 3000 * nodes.length + socketTimeout;
        s.connect(target, timeoutValue);
        s.setSoTimeout(timeoutValue);
        s.setSendBufferSize(DEFAULT_DATA_SOCKET_SIZE);
        LOG.debug("Send buf size " + s.getSendBufferSize());

        //
        // Xmit header info to datanode
        //
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), buffersize));
        blockReplyStream = new DataInputStream(s.getInputStream());

        out.writeShort( DATA_TRANFER_VERSION );
        out.write( OP_WRITE_BLOCK );
        out.writeLong( block.getBlockId() );
        out.writeInt( nodes.length );
        out.writeBoolean( recoveryFlag );       // recovery flag
        Text.writeString( out, client );
        out.writeInt( nodes.length - 1 );
        for (int i = 1; i < nodes.length; i++) {
          nodes[i].write(out);
        }
        checksum.writeHeader( out );
        out.flush();

        // receive ack for connect
        firstBadLink = Text.readString(blockReplyStream);
        if (firstBadLink.length() != 0) {
          throw new IOException("Bad connect ack with firstBadLink " + firstBadLink);
        }

        blockStream = out;
        return true;     // success

      } catch (IOException ie) {

        LOG.info("Exception in createBlockOutputStream " + ie);

        // find the datanode that matches
        if (firstBadLink.length() != 0) {
          for (int i = 0; i < nodes.length; i++) {
            if (nodes[i].getName().equals(firstBadLink)) {
              errorIndex = i;
              break;
            }
          }
        }
        hasError = true;
        blockReplyStream = null;
        return false;  // error
      }
    }
  
    private LocatedBlock locateFollowingBlock(long start
                                              ) throws IOException {     
      int retries = 5;
      long sleeptime = 400;
      while (true) {
        long localstart = System.currentTimeMillis();
        while (true) {
          try {
            return namenode.addBlock(src.toString(), clientName);
          } catch (RemoteException e) {
            if (--retries == 0 && 
                !NotReplicatedYetException.class.getName().
                equals(e.getClassName())) {
              throw e;
            } else {
              LOG.info(StringUtils.stringifyException(e));
              if (System.currentTimeMillis() - localstart > 5000) {
                LOG.info("Waiting for replication for " + 
                         (System.currentTimeMillis() - localstart)/1000 + 
                         " seconds");
              }
              try {
                LOG.warn("NotReplicatedYetException sleeping " + src +
                          " retries left " + retries);
                Thread.sleep(sleeptime);
                sleeptime *= 2;
              } catch (InterruptedException ie) {
              }
            }                
          }
        }
      } 
    }
  
    // @see FSOutputSummer#writeChunk()
    @Override
    protected void writeChunk(byte[] b, int offset, int len, byte[] checksum) 
                                                          throws IOException {
      checkOpen();
      isClosed();
  
      int cklen = checksum.length;
      int bytesPerChecksum = this.checksum.getBytesPerChecksum(); 
      if (len > bytesPerChecksum) {
        throw new IOException("writeChunk() buffer size is " + len +
                              " is larger than supported  bytesPerChecksum " +
                              bytesPerChecksum);
      }
      if (checksum.length != this.checksum.getChecksumSize()) {
        throw new IOException("writeChunk() checksum size is supposed to be " +
                              this.checksum.getChecksumSize() + 
                              " but found to be " + checksum.length);
      }
      if (len + cklen + SIZE_OF_INTEGER > chunkSize) {
        throw new IOException("writeChunk() found data of size " +
                              (len + cklen + 4) +
                              " that cannot be larger than chukSize " + 
                              chunkSize);
      }

      synchronized (dataQueue) {
  
        // If queue is full, then wait till we can create  enough space
        while (!closed && dataQueue.size() + ackQueue.size()  > maxPackets) {
          try {
            dataQueue.wait();
          } catch (InterruptedException  e) {
          }
        }
        isClosed();
  
        if (currentPacket == null) {
          currentPacket = new Packet(packetSize, bytesCurBlock);
        }

        currentPacket.writeInt(len);
        currentPacket.write(b, offset, len);
        currentPacket.write(checksum, 0, cklen);
        currentPacket.numChunks++;
        bytesCurBlock += len;

        // If packet is full, enqueue it for transmission
        //
        if (currentPacket.numChunks == chunksPerPacket ||
            bytesCurBlock == chunksPerBlock * bytesPerChecksum) {
          LOG.debug("DFSClient writeChunk packet full seqno " + currentPacket.seqno);
          currentPacket.buffer.flip();
          //
          // if we allocated a new packet because we encountered a block
          // boundary, reset bytesCurBlock.
          //
          if (bytesCurBlock == chunksPerBlock * bytesPerChecksum) {
            currentPacket.lastPacketInBlock = true;
            bytesCurBlock = 0;
          }
          dataQueue.addLast(currentPacket);
          dataQueue.notifyAll();
          currentPacket = null;
        }
      }
      //LOG.debug("DFSClient writeChunk with length " + len +
      //          " checksum length " + cklen);
    }
  
    /**
     * Waits till all existing data is flushed and
     * confirmations received from datanodes.
     */
    @Override
    public synchronized void flush() throws IOException {
      checkOpen();
      isClosed();
  
      while (!closed) {
        synchronized (dataQueue) {
          isClosed();
          //
          // if there is data in the current buffer, send it across
          //
          if (currentPacket != null) {
            currentPacket.buffer.flip();
            dataQueue.addLast(currentPacket);
            dataQueue.notifyAll();
            currentPacket = null;
          }

          // wait for all buffers to be flushed to datanodes
          if (!closed && dataQueue.size() != 0) {
            try {
              dataQueue.wait();
            } catch (InterruptedException e) {
            }
            continue;
          }
        }

        // wait for all acks to be received back from datanodes
        synchronized (ackQueue) {
          if (!closed && ackQueue.size() != 0) {
            try {
              ackQueue.wait();
            } catch (InterruptedException e) {
            }
            continue;
          }
        }

        // acquire both the locks and verify that we are
        // *really done*. In the case of error recovery, 
        // packets might move back from ackQueue to dataQueue.
        //
        synchronized (dataQueue) {
          synchronized (ackQueue) {
            if (dataQueue.size() + ackQueue.size() == 0) {
              break;       // we are done
            }
          }
        }
      }
    }
  
    private void internalClose() throws IOException {
      // Clean up any resources that might be held.
      closed = true;
      
      synchronized (pendingCreates) {
        pendingCreates.remove(src);
      }
      
      if (s != null) {
        s.close();
        s = null;
      }
    }
    
    /**
     * Closes this output stream and releases any system 
     * resources associated with this stream.
     */
    @Override
    public synchronized void close() throws IOException {
      checkOpen();
      isClosed();

      try {
        flushBuffer();       // flush from all upper layers
      
        // Mark that this packet is the last packet in block.
        // If there are no outstanding packets and the last packet
        // was not the last one in the current block, then create a
        // packet with empty payload.
        synchronized (dataQueue) {
          if (currentPacket == null && bytesCurBlock != 0) {
            currentPacket = new Packet(packetSize, bytesCurBlock);
            currentPacket.writeInt(0); // one chunk with empty contents
          }
          if (currentPacket != null) { 
            currentPacket.lastPacketInBlock = true;
          }
        }

        flush();             // flush all data to Datanodes
        closed = true;
 
        // wait for threads to finish processing
        streamer.close();

        synchronized (dataQueue) {
          if (response != null) {
            response.close();
          }
          if (blockStream != null) {
            blockStream.writeInt(0); // indicate end-of-block to datanode
            blockStream.close();
            blockReplyStream.close();
          }
          if (s != null) {
            s.close();
            s = null;
          }
        }

        // wait for threads to exit
        if (response != null) {
          response.join();
        }
        streamer.join();
        streamer = null;
        blockStream = null;
        blockReplyStream = null;
        response = null;

        long localstart = System.currentTimeMillis();
        boolean fileComplete = false;
        while (!fileComplete) {
          fileComplete = namenode.complete(src.toString(), clientName);
          if (!fileComplete) {
            try {
              Thread.sleep(400);
              if (System.currentTimeMillis() - localstart > 5000) {
                LOG.info("Could not complete file " + src + " retrying...");
              }
            } catch (InterruptedException ie) {
            }
          }
        }
      } catch (InterruptedException e) {
        throw new IOException("Failed to shutdown response thread");
      } finally {
        internalClose();
      }
    }

    void setArtificialSlowdown(long period) {
      artificialSlowdown = period;
    }

    void setChunksPerPacket(int value) {
      chunksPerPacket = Math.min(chunksPerPacket, value);
      packetSize = chunkSize * chunksPerPacket;
    }
  }

  void reportChecksumFailure(String file, Block blk, DatanodeInfo dn) {
    DatanodeInfo [] dnArr = { dn };
    LocatedBlock [] lblocks = { new LocatedBlock(blk, dnArr) };
    reportChecksumFailure(file, lblocks);
  }
    
  // just reports checksum failure and ignores any exception during the report.
  void reportChecksumFailure(String file, LocatedBlock lblocks[]) {
    try {
      reportBadBlocks(lblocks);
    } catch (IOException ie) {
      LOG.info("Found corruption while reading " + file 
               + ".  Error repairing corrupt blocks.  Bad blocks remain. " 
               + StringUtils.stringifyException(ie));
    }
  }
}
