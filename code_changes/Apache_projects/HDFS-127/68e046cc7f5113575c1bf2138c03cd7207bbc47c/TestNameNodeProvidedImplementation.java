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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Iterator;
import java.util.Random;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerTestUtil;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeStatistics;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeStorageInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.ProvidedStorageMap;
import org.apache.hadoop.hdfs.server.common.blockaliasmap.BlockAliasMap;
import org.apache.hadoop.hdfs.server.common.blockaliasmap.impl.TextFileRegionAliasMap;
import org.apache.hadoop.hdfs.server.datanode.DataNode;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY;

import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.impl.FsVolumeImpl;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.net.NodeBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.net.NodeBase.PATH_SEPARATOR_STR;
import static org.junit.Assert.*;

public class TestNameNodeProvidedImplementation {

  @Rule public TestName name = new TestName();
  public static final Logger LOG =
      LoggerFactory.getLogger(TestNameNodeProvidedImplementation.class);

  final Random r = new Random();
  final File fBASE = new File(MiniDFSCluster.getBaseDirectory());
  final Path BASE = new Path(fBASE.toURI().toString());
  final Path NAMEPATH = new Path(BASE, "providedDir");
  final Path NNDIRPATH = new Path(BASE, "nnDir");
  final Path BLOCKFILE = new Path(NNDIRPATH, "blocks.csv");
  final String SINGLEUSER = "usr1";
  final String SINGLEGROUP = "grp1";
  private final int numFiles = 10;
  private final String filePrefix = "file";
  private final String fileSuffix = ".dat";
  private final int baseFileLen = 1024;
  private long providedDataSize = 0;

  Configuration conf;
  MiniDFSCluster cluster;

  @Before
  public void setSeed() throws Exception {
    if (fBASE.exists() && !FileUtil.fullyDelete(fBASE)) {
      throw new IOException("Could not fully delete " + fBASE);
    }
    long seed = r.nextLong();
    r.setSeed(seed);
    System.out.println(name.getMethodName() + " seed: " + seed);
    conf = new HdfsConfiguration();
    conf.set(SingleUGIResolver.USER, SINGLEUSER);
    conf.set(SingleUGIResolver.GROUP, SINGLEGROUP);

    conf.set(DFSConfigKeys.DFS_PROVIDER_STORAGEUUID,
        DFSConfigKeys.DFS_PROVIDER_STORAGEUUID_DEFAULT);
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_PROVIDED_ENABLED, true);

    conf.setClass(DFSConfigKeys.DFS_PROVIDED_ALIASMAP_CLASS,
        TextFileRegionAliasMap.class, BlockAliasMap.class);
    conf.set(DFSConfigKeys.DFS_PROVIDED_ALIASMAP_TEXT_WRITE_PATH,
        BLOCKFILE.toString());
    conf.set(DFSConfigKeys.DFS_PROVIDED_ALIASMAP_TEXT_READ_PATH,
        BLOCKFILE.toString());
    conf.set(DFSConfigKeys.DFS_PROVIDED_ALIASMAP_TEXT_DELIMITER, ",");

    conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR_PROVIDED,
        new File(NAMEPATH.toUri()).toString());
    File imageDir = new File(NAMEPATH.toUri());
    if (!imageDir.exists()) {
      LOG.info("Creating directory: " + imageDir);
      imageDir.mkdirs();
    }

    File nnDir = new File(NNDIRPATH.toUri());
    if (!nnDir.exists()) {
      nnDir.mkdirs();
    }

    // create 10 random files under BASE
    for (int i=0; i < numFiles; i++) {
      File newFile = new File(
          new Path(NAMEPATH, filePrefix + i + fileSuffix).toUri());
      if(!newFile.exists()) {
        try {
          LOG.info("Creating " + newFile.toString());
          newFile.createNewFile();
          Writer writer = new OutputStreamWriter(
              new FileOutputStream(newFile.getAbsolutePath()), "utf-8");
          for(int j=0; j < baseFileLen*i; j++) {
            writer.write("0");
          }
          writer.flush();
          writer.close();
          providedDataSize += newFile.length();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  @After
  public void shutdown() throws Exception {
    try {
      if (cluster != null) {
        cluster.shutdown(true, true);
      }
    } finally {
      cluster = null;
    }
  }

  void createImage(TreeWalk t, Path out,
      Class<? extends BlockResolver> blockIdsClass) throws Exception {
    createImage(t, out, blockIdsClass, "");
  }

  void createImage(TreeWalk t, Path out,
      Class<? extends BlockResolver> blockIdsClass, String clusterID)
      throws Exception {
    ImageWriter.Options opts = ImageWriter.defaults();
    opts.setConf(conf);
    opts.output(out.toString())
        .blocks(TextFileRegionAliasMap.class)
        .blockIds(blockIdsClass)
        .clusterID(clusterID);
    try (ImageWriter w = new ImageWriter(opts)) {
      for (TreePath e : t) {
        w.accept(e);
      }
    }
  }

  void startCluster(Path nspath, int numDatanodes,
      StorageType[] storageTypes,
      StorageType[][] storageTypesPerDatanode,
      boolean doFormat)
      throws IOException {
    conf.set(DFS_NAMENODE_NAME_DIR_KEY, nspath.toString());

    if (storageTypesPerDatanode != null) {
      cluster = new MiniDFSCluster.Builder(conf)
          .format(doFormat)
          .manageNameDfsDirs(doFormat)
          .numDataNodes(numDatanodes)
          .storageTypes(storageTypesPerDatanode)
          .build();
    } else if (storageTypes != null) {
      cluster = new MiniDFSCluster.Builder(conf)
          .format(doFormat)
          .manageNameDfsDirs(doFormat)
          .numDataNodes(numDatanodes)
          .storagesPerDatanode(storageTypes.length)
          .storageTypes(storageTypes)
          .build();
    } else {
      cluster = new MiniDFSCluster.Builder(conf)
          .format(doFormat)
          .manageNameDfsDirs(doFormat)
          .numDataNodes(numDatanodes)
          .build();
    }
    cluster.waitActive();
  }

  @Test(timeout=20000)
  public void testLoadImage() throws Exception {
    final long seed = r.nextLong();
    LOG.info("NAMEPATH: " + NAMEPATH);
    createImage(new RandomTreeWalk(seed), NNDIRPATH, FixedBlockResolver.class);
    startCluster(NNDIRPATH, 0,
        new StorageType[] {StorageType.PROVIDED, StorageType.DISK}, null,
        false);

    FileSystem fs = cluster.getFileSystem();
    for (TreePath e : new RandomTreeWalk(seed)) {
      FileStatus rs = e.getFileStatus();
      Path hp = new Path(rs.getPath().toUri().getPath());
      assertTrue(fs.exists(hp));
      FileStatus hs = fs.getFileStatus(hp);
      assertEquals(rs.getPath().toUri().getPath(),
                   hs.getPath().toUri().getPath());
      assertEquals(rs.getPermission(), hs.getPermission());
      assertEquals(rs.getLen(), hs.getLen());
      assertEquals(SINGLEUSER, hs.getOwner());
      assertEquals(SINGLEGROUP, hs.getGroup());
      assertEquals(rs.getAccessTime(), hs.getAccessTime());
      assertEquals(rs.getModificationTime(), hs.getModificationTime());
    }
  }

  @Test(timeout=30000)
  public void testProvidedReporting() throws Exception {
    conf.setClass(ImageWriter.Options.UGI_CLASS,
        SingleUGIResolver.class, UGIResolver.class);
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    int numDatanodes = 10;
    startCluster(NNDIRPATH, numDatanodes,
        new StorageType[] {StorageType.PROVIDED, StorageType.DISK}, null,
        false);
    long diskCapacity = 1000;
    // set the DISK capacity for testing
    for (DataNode dn: cluster.getDataNodes()) {
      for (FsVolumeSpi ref : dn.getFSDataset().getFsVolumeReferences()) {
        if (ref.getStorageType() == StorageType.DISK) {
          ((FsVolumeImpl) ref).setCapacityForTesting(diskCapacity);
        }
      }
    }
    // trigger heartbeats to update the capacities
    cluster.triggerHeartbeats();
    Thread.sleep(10000);
    // verify namenode stats
    FSNamesystem namesystem = cluster.getNameNode().getNamesystem();
    DatanodeStatistics dnStats = namesystem.getBlockManager()
        .getDatanodeManager().getDatanodeStatistics();

    // total capacity reported includes only the local volumes and
    // not the provided capacity
    assertEquals(diskCapacity * numDatanodes, namesystem.getTotal());

    // total storage used should be equal to the totalProvidedStorage
    // no capacity should be remaining!
    assertEquals(providedDataSize, dnStats.getProvidedCapacity());
    assertEquals(providedDataSize, namesystem.getProvidedCapacityTotal());
    assertEquals(providedDataSize, dnStats.getStorageTypeStats()
        .get(StorageType.PROVIDED).getCapacityTotal());
    assertEquals(providedDataSize, dnStats.getStorageTypeStats()
        .get(StorageType.PROVIDED).getCapacityUsed());

    // verify datanode stats
    for (DataNode dn: cluster.getDataNodes()) {
      for (StorageReport report : dn.getFSDataset()
          .getStorageReports(namesystem.getBlockPoolId())) {
        if (report.getStorage().getStorageType() == StorageType.PROVIDED) {
          assertEquals(providedDataSize, report.getCapacity());
          assertEquals(providedDataSize, report.getDfsUsed());
          assertEquals(providedDataSize, report.getBlockPoolUsed());
          assertEquals(0, report.getNonDfsUsed());
          assertEquals(0, report.getRemaining());
        }
      }
    }

    DFSClient client = new DFSClient(new InetSocketAddress("localhost",
            cluster.getNameNodePort()), cluster.getConfiguration(0));
    BlockManager bm = namesystem.getBlockManager();
    for (int fileId = 0; fileId < numFiles; fileId++) {
      String filename = "/" + filePrefix + fileId + fileSuffix;
      LocatedBlocks locatedBlocks = client.getLocatedBlocks(
          filename, 0, baseFileLen);
      for (LocatedBlock locatedBlock : locatedBlocks.getLocatedBlocks()) {
        BlockInfo blockInfo =
            bm.getStoredBlock(locatedBlock.getBlock().getLocalBlock());
        Iterator<DatanodeStorageInfo> storagesItr = blockInfo.getStorageInfos();

        DatanodeStorageInfo info = storagesItr.next();
        assertEquals(StorageType.PROVIDED, info.getStorageType());
        DatanodeDescriptor dnDesc = info.getDatanodeDescriptor();
        // check the locations that are returned by FSCK have the right name
        assertEquals(ProvidedStorageMap.ProvidedDescriptor.NETWORK_LOCATION
            + PATH_SEPARATOR_STR + ProvidedStorageMap.ProvidedDescriptor.NAME,
            NodeBase.getPath(dnDesc));
        // no DatanodeStorageInfos should remain
        assertFalse(storagesItr.hasNext());
      }
    }
  }

  @Test(timeout=500000)
  public void testDefaultReplication() throws Exception {
    int targetReplication = 2;
    conf.setInt(FixedBlockMultiReplicaResolver.REPLICATION, targetReplication);
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockMultiReplicaResolver.class);
    // make the last Datanode with only DISK
    startCluster(NNDIRPATH, 3, null,
        new StorageType[][] {
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.DISK}},
        false);
    // wait for the replication to finish
    Thread.sleep(50000);

    FileSystem fs = cluster.getFileSystem();
    int count = 0;
    for (TreePath e : new FSTreeWalk(NAMEPATH, conf)) {
      FileStatus rs = e.getFileStatus();
      Path hp = removePrefix(NAMEPATH, rs.getPath());
      LOG.info("hp " + hp.toUri().getPath());
      //skip HDFS specific files, which may have been created later on.
      if (hp.toString().contains("in_use.lock")
          || hp.toString().contains("current")) {
        continue;
      }
      e.accept(count++);
      assertTrue(fs.exists(hp));
      FileStatus hs = fs.getFileStatus(hp);

      if (rs.isFile()) {
        BlockLocation[] bl = fs.getFileBlockLocations(
            hs.getPath(), 0, hs.getLen());
        int i = 0;
        for(; i < bl.length; i++) {
          int currentRep = bl[i].getHosts().length;
          assertEquals(targetReplication , currentRep);
        }
      }
    }
  }


  static Path removePrefix(Path base, Path walk) {
    Path wpath = new Path(walk.toUri().getPath());
    Path bpath = new Path(base.toUri().getPath());
    Path ret = new Path("/");
    while (!(bpath.equals(wpath) || "".equals(wpath.getName()))) {
      ret = "".equals(ret.getName())
        ? new Path("/", wpath.getName())
        : new Path(new Path("/", wpath.getName()),
                   new Path(ret.toString().substring(1)));
      wpath = wpath.getParent();
    }
    if (!bpath.equals(wpath)) {
      throw new IllegalArgumentException(base + " not a prefix of " + walk);
    }
    return ret;
  }

  @Test(timeout=30000)
  public void testBlockRead() throws Exception {
    conf.setClass(ImageWriter.Options.UGI_CLASS,
        FsUGIResolver.class, UGIResolver.class);
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    startCluster(NNDIRPATH, 3,
        new StorageType[] {StorageType.PROVIDED, StorageType.DISK}, null,
        false);
    FileSystem fs = cluster.getFileSystem();
    Thread.sleep(2000);
    int count = 0;
    // read NN metadata, verify contents match
    for (TreePath e : new FSTreeWalk(NAMEPATH, conf)) {
      FileStatus rs = e.getFileStatus();
      Path hp = removePrefix(NAMEPATH, rs.getPath());
      LOG.info("hp " + hp.toUri().getPath());
      //skip HDFS specific files, which may have been created later on.
      if(hp.toString().contains("in_use.lock")
          || hp.toString().contains("current")) {
        continue;
      }
      e.accept(count++);
      assertTrue(fs.exists(hp));
      FileStatus hs = fs.getFileStatus(hp);
      assertEquals(hp.toUri().getPath(), hs.getPath().toUri().getPath());
      assertEquals(rs.getPermission(), hs.getPermission());
      assertEquals(rs.getOwner(), hs.getOwner());
      assertEquals(rs.getGroup(), hs.getGroup());

      if (rs.isFile()) {
        assertEquals(rs.getLen(), hs.getLen());
        try (ReadableByteChannel i = Channels.newChannel(
              new FileInputStream(new File(rs.getPath().toUri())))) {
          try (ReadableByteChannel j = Channels.newChannel(
                fs.open(hs.getPath()))) {
            ByteBuffer ib = ByteBuffer.allocate(4096);
            ByteBuffer jb = ByteBuffer.allocate(4096);
            while (true) {
              int il = i.read(ib);
              int jl = j.read(jb);
              if (il < 0 || jl < 0) {
                assertEquals(il, jl);
                break;
              }
              ib.flip();
              jb.flip();
              int cmp = Math.min(ib.remaining(), jb.remaining());
              for (int k = 0; k < cmp; ++k) {
                assertEquals(ib.get(), jb.get());
              }
              ib.compact();
              jb.compact();
            }

          }
        }
      }
    }
  }

  private BlockLocation[] createFile(Path path, short replication,
      long fileLen, long blockLen) throws IOException {
    FileSystem fs = cluster.getFileSystem();
    //create a sample file that is not provided
    DFSTestUtil.createFile(fs, path, false, (int) blockLen,
        fileLen, blockLen, replication, 0, true);
    return fs.getFileBlockLocations(path, 0, fileLen);
  }

  @Test(timeout=30000)
  public void testClusterWithEmptyImage() throws IOException {
    // start a cluster with 2 datanodes without any provided storage
    startCluster(NNDIRPATH, 2, null,
        new StorageType[][] {
            {StorageType.DISK},
            {StorageType.DISK}},
        true);
    assertTrue(cluster.isClusterUp());
    assertTrue(cluster.isDataNodeUp());

    BlockLocation[] locations = createFile(new Path("/testFile1.dat"),
        (short) 2, 1024*1024, 1024*1024);
    assertEquals(1, locations.length);
    assertEquals(2, locations[0].getHosts().length);
  }

  private DatanodeInfo[] getAndCheckBlockLocations(DFSClient client,
      String filename, long fileLen, long expectedBlocks, int expectedLocations)
      throws IOException {
    LocatedBlocks locatedBlocks = client.getLocatedBlocks(filename, 0, fileLen);
    // given the start and length in the above call,
    // only one LocatedBlock in LocatedBlocks
    assertEquals(expectedBlocks, locatedBlocks.getLocatedBlocks().size());
    LocatedBlock locatedBlock = locatedBlocks.getLocatedBlocks().get(0);
    assertEquals(expectedLocations, locatedBlock.getLocations().length);
    return locatedBlock.getLocations();
  }

  /**
   * Tests setting replication of provided files.
   * @throws Exception
   */
  @Test(timeout=30000)
  public void testSetReplicationForProvidedFiles() throws Exception {
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    // 10 Datanodes with both DISK and PROVIDED storage
    startCluster(NNDIRPATH, 10,
        new StorageType[]{
            StorageType.PROVIDED, StorageType.DISK},
        null,
        false);

    String filename = "/" + filePrefix + (numFiles - 1) + fileSuffix;
    Path file = new Path(filename);
    FileSystem fs = cluster.getFileSystem();

    // set the replication to 4, and test that the file has
    // the required replication.
    short newReplication = 4;
    LOG.info("Setting replication of file {} to {}", filename, newReplication);
    fs.setReplication(file, newReplication);
    DFSTestUtil.waitForReplication((DistributedFileSystem) fs,
        file, newReplication, 10000);
    DFSClient client = new DFSClient(new InetSocketAddress("localhost",
        cluster.getNameNodePort()), cluster.getConfiguration(0));
    getAndCheckBlockLocations(client, filename, baseFileLen, 1, newReplication);

    // set the replication back to 1
    newReplication = 1;
    LOG.info("Setting replication of file {} back to {}",
        filename, newReplication);
    fs.setReplication(file, newReplication);
    // defaultReplication number of replicas should be returned
    int defaultReplication = conf.getInt(DFSConfigKeys.DFS_REPLICATION_KEY,
        DFSConfigKeys.DFS_REPLICATION_DEFAULT);
    DFSTestUtil.waitForReplication((DistributedFileSystem) fs,
        file, (short) defaultReplication, 10000);
    getAndCheckBlockLocations(client, filename, baseFileLen, 1,
        defaultReplication);
  }

  @Test(timeout=30000)
  public void testProvidedDatanodeFailures() throws Exception {
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
            FixedBlockResolver.class);
    startCluster(NNDIRPATH, 3, null,
        new StorageType[][] {
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.DISK}},
        false);

    DataNode providedDatanode1 = cluster.getDataNodes().get(0);
    DataNode providedDatanode2 = cluster.getDataNodes().get(1);

    DFSClient client = new DFSClient(new InetSocketAddress("localhost",
            cluster.getNameNodePort()), cluster.getConfiguration(0));

    if (numFiles >= 1) {
      String filename = "/" + filePrefix + (numFiles - 1) + fileSuffix;
      // 2 locations returned as there are 2 PROVIDED datanodes
      DatanodeInfo[] dnInfos =
          getAndCheckBlockLocations(client, filename, baseFileLen, 1, 2);
      //the location should be one of the provided DNs available
      assertTrue(
          dnInfos[0].getDatanodeUuid().equals(
              providedDatanode1.getDatanodeUuid())
          || dnInfos[0].getDatanodeUuid().equals(
              providedDatanode2.getDatanodeUuid()));

      //stop the 1st provided datanode
      MiniDFSCluster.DataNodeProperties providedDNProperties1 =
          cluster.stopDataNode(0);

      //make NameNode detect that datanode is down
      BlockManagerTestUtil.noticeDeadDatanode(
          cluster.getNameNode(),
          providedDatanode1.getDatanodeId().getXferAddr());

      //should find the block on the 2nd provided datanode
      dnInfos = getAndCheckBlockLocations(client, filename, baseFileLen, 1, 1);
      assertEquals(providedDatanode2.getDatanodeUuid(),
          dnInfos[0].getDatanodeUuid());

      // stop the 2nd provided datanode
      MiniDFSCluster.DataNodeProperties providedDNProperties2 =
          cluster.stopDataNode(0);
      // make NameNode detect that datanode is down
      BlockManagerTestUtil.noticeDeadDatanode(
          cluster.getNameNode(),
          providedDatanode2.getDatanodeId().getXferAddr());
      getAndCheckBlockLocations(client, filename, baseFileLen, 1, 0);

      //restart the provided datanode
      cluster.restartDataNode(providedDNProperties1, true);
      cluster.waitActive();

      //should find the block on the 1st provided datanode now
      dnInfos = getAndCheckBlockLocations(client, filename, baseFileLen, 1, 1);
      //not comparing UUIDs as the datanode can now have a different one.
      assertEquals(providedDatanode1.getDatanodeId().getXferAddr(),
          dnInfos[0].getXferAddr());
    }
  }

  @Test(timeout=300000)
  public void testTransientDeadDatanodes() throws Exception {
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
            FixedBlockResolver.class);
    // 3 Datanodes, 2 PROVIDED and other DISK
    startCluster(NNDIRPATH, 3, null,
        new StorageType[][] {
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.DISK}},
        false);

    DataNode providedDatanode = cluster.getDataNodes().get(0);
    for (int i= 0; i < numFiles; i++) {
      // expect to have 2 locations as we have 2 provided Datanodes.
      verifyFileLocation(i, 2);
      // NameNode thinks the datanode is down
      BlockManagerTestUtil.noticeDeadDatanode(
          cluster.getNameNode(),
          providedDatanode.getDatanodeId().getXferAddr());
      cluster.waitActive();
      cluster.triggerHeartbeats();
      Thread.sleep(1000);
      verifyFileLocation(i, 2);
    }
  }

  @Test(timeout=30000)
  public void testNamenodeRestart() throws Exception {
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    // 3 Datanodes, 2 PROVIDED and other DISK
    startCluster(NNDIRPATH, 3, null,
        new StorageType[][] {
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.DISK}},
        false);

    verifyFileLocation(numFiles - 1, 2);
    cluster.restartNameNodes();
    cluster.waitActive();
    verifyFileLocation(numFiles - 1, 2);
  }

  /**
   * verify that the specified file has a valid provided location.
   * @param fileIndex the index of the file to verify.
   * @throws Exception
   */
  private void verifyFileLocation(int fileIndex, int replication)
      throws Exception {
    DFSClient client = new DFSClient(
        new InetSocketAddress("localhost", cluster.getNameNodePort()),
        cluster.getConfiguration(0));
    if (fileIndex < numFiles && fileIndex >= 0) {
      String filename = filePrefix + fileIndex + fileSuffix;
      File file = new File(new Path(NAMEPATH, filename).toUri());
      long fileLen = file.length();
      long blockSize = conf.getLong(FixedBlockResolver.BLOCKSIZE,
          FixedBlockResolver.BLOCKSIZE_DEFAULT);
      long numLocatedBlocks =
          fileLen == 0 ? 1 : (long) Math.ceil(fileLen * 1.0 / blockSize);
      getAndCheckBlockLocations(client, "/" + filename, fileLen,
          numLocatedBlocks, replication);
    }
  }

  @Test(timeout=30000)
  public void testSetClusterID() throws Exception {
    String clusterID = "PROVIDED-CLUSTER";
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class, clusterID);
    // 2 Datanodes, 1 PROVIDED and other DISK
    startCluster(NNDIRPATH, 2, null,
        new StorageType[][] {
            {StorageType.PROVIDED, StorageType.DISK},
            {StorageType.DISK}},
        false);
    NameNode nn = cluster.getNameNode();
    assertEquals(clusterID, nn.getNamesystem().getClusterId());
  }

  @Test(timeout=30000)
  public void testNumberOfProvidedLocations() throws Exception {
    // set default replication to 4
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 4);
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    // start with 4 PROVIDED location
    startCluster(NNDIRPATH, 4,
        new StorageType[]{
            StorageType.PROVIDED, StorageType.DISK},
        null,
        false);
    int expectedLocations = 4;
    for (int i = 0; i < numFiles; i++) {
      verifyFileLocation(i, expectedLocations);
    }
    // stop 2 datanodes, one after the other and verify number of locations.
    for (int i = 1; i <= 2; i++) {
      DataNode dn = cluster.getDataNodes().get(0);
      cluster.stopDataNode(0);
      // make NameNode detect that datanode is down
      BlockManagerTestUtil.noticeDeadDatanode(cluster.getNameNode(),
          dn.getDatanodeId().getXferAddr());

      expectedLocations = 4 - i;
      for (int j = 0; j < numFiles; j++) {
        verifyFileLocation(j, expectedLocations);
      }
    }
  }

  @Test(timeout=30000)
  public void testNumberOfProvidedLocationsManyBlocks() throws Exception {
    // increase number of blocks per file to at least 10 blocks per file
    conf.setLong(FixedBlockResolver.BLOCKSIZE, baseFileLen/10);
    // set default replication to 4
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 4);
    createImage(new FSTreeWalk(NAMEPATH, conf), NNDIRPATH,
        FixedBlockResolver.class);
    // start with 4 PROVIDED location
    startCluster(NNDIRPATH, 4,
        new StorageType[]{
            StorageType.PROVIDED, StorageType.DISK},
        null,
        false);
    int expectedLocations = 4;
    for (int i = 0; i < numFiles; i++) {
      verifyFileLocation(i, expectedLocations);
    }
  }
}
