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
package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.common.FileRegion;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.common.blockaliasmap.BlockAliasMap;
import org.apache.hadoop.hdfs.server.common.blockaliasmap.impl.TextFileRegionAliasMap;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInPipeline;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInfo;
import org.apache.hadoop.hdfs.server.datanode.DirectoryScanner.ReportCompiler;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.hdfs.server.datanode.checker.VolumeCheckResult;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.FileIoProvider;
import org.apache.hadoop.hdfs.server.datanode.ReplicaBuilder;
import org.apache.hadoop.util.Timer;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.util.AutoCloseableLock;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectReader;
import org.codehaus.jackson.map.ObjectWriter;

import com.google.common.annotations.VisibleForTesting;

import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Time;

/**
 * This class is used to create provided volumes.
 */
public class ProvidedVolumeImpl extends FsVolumeImpl {

  static class ProvidedBlockPoolSlice {
    private ProvidedVolumeImpl providedVolume;

    private BlockAliasMap<FileRegion> aliasMap;
    private Configuration conf;
    private String bpid;
    private ReplicaMap bpVolumeMap;

    ProvidedBlockPoolSlice(String bpid, ProvidedVolumeImpl volume,
        Configuration conf) {
      this.providedVolume = volume;
      bpVolumeMap = new ReplicaMap(new AutoCloseableLock());
      Class<? extends BlockAliasMap> fmt =
          conf.getClass(DFSConfigKeys.DFS_PROVIDED_ALIASMAP_CLASS,
              TextFileRegionAliasMap.class, BlockAliasMap.class);
      aliasMap = ReflectionUtils.newInstance(fmt, conf);
      this.conf = conf;
      this.bpid = bpid;
      bpVolumeMap.initBlockPool(bpid);
      LOG.info("Created alias map using class: " + aliasMap.getClass());
    }

    BlockAliasMap<FileRegion> getBlockAliasMap() {
      return aliasMap;
    }

    @VisibleForTesting
    void setFileRegionProvider(BlockAliasMap<FileRegion> blockAliasMap) {
      this.aliasMap = blockAliasMap;
    }

    public void getVolumeMap(ReplicaMap volumeMap,
        RamDiskReplicaTracker ramDiskReplicaMap, FileSystem remoteFS)
        throws IOException {
      BlockAliasMap.Reader<FileRegion> reader = aliasMap.getReader(null);
      if (reader == null) {
        LOG.warn("Got null reader from BlockAliasMap " + aliasMap
            + "; no blocks will be populated");
        return;
      }
      Iterator<FileRegion> iter = reader.iterator();
      while (iter.hasNext()) {
        FileRegion region = iter.next();
        if (region.getBlockPoolId() != null
            && region.getBlockPoolId().equals(bpid)
            && containsBlock(providedVolume.baseURI,
                region.getPath().toUri())) {
          ReplicaInfo newReplica = new ReplicaBuilder(ReplicaState.FINALIZED)
              .setBlockId(region.getBlock().getBlockId())
              .setURI(region.getPath().toUri())
              .setOffset(region.getOffset())
              .setLength(region.getBlock().getNumBytes())
              .setGenerationStamp(region.getBlock().getGenerationStamp())
              .setFsVolume(providedVolume)
              .setConf(conf)
              .setRemoteFS(remoteFS)
              .build();
          ReplicaInfo oldReplica =
              volumeMap.get(bpid, newReplica.getBlockId());
          if (oldReplica == null) {
            volumeMap.add(bpid, newReplica);
            bpVolumeMap.add(bpid, newReplica);
          } else {
            throw new IOException("A block with id " + newReplica.getBlockId()
                + " already exists in the volumeMap");
          }
        }
      }
    }

    public boolean isEmpty() {
      return bpVolumeMap.replicas(bpid).size() == 0;
    }

    public void shutdown(BlockListAsLongs blocksListsAsLongs) {
      //nothing to do!
    }

    public void compileReport(LinkedList<ScanInfo> report,
        ReportCompiler reportCompiler)
            throws IOException, InterruptedException {
      /* refresh the aliasMap and return the list of blocks found.
       * the assumption here is that the block ids in the external
       * block map, after the refresh, are consistent with those
       * from before the refresh, i.e., for blocks which did not change,
       * the ids remain the same.
       */
      aliasMap.refresh();
      BlockAliasMap.Reader<FileRegion> reader = aliasMap.getReader(null);
      if (reader == null) {
        LOG.warn("Got null reader from BlockAliasMap " + aliasMap
            + "; no blocks will be populated in scan report");
        return;
      }
      Iterator<FileRegion> iter = reader.iterator();
      while(iter.hasNext()) {
        reportCompiler.throttle();
        FileRegion region = iter.next();
        if (region.getBlockPoolId().equals(bpid)) {
          LOG.info("Adding ScanInfo for blkid " +
              region.getBlock().getBlockId());
          report.add(new ScanInfo(region.getBlock().getBlockId(), null, null,
              providedVolume, region, region.getLength()));
        }
      }
    }
  }

  private URI baseURI;
  private final Map<String, ProvidedBlockPoolSlice> bpSlices =
      new ConcurrentHashMap<String, ProvidedBlockPoolSlice>();

  private ProvidedVolumeDF df;
  //the remote FileSystem to which this ProvidedVolume points to.
  private FileSystem remoteFS;

  ProvidedVolumeImpl(FsDatasetImpl dataset, String storageID,
      StorageDirectory sd, FileIoProvider fileIoProvider,
      Configuration conf) throws IOException {
    super(dataset, storageID, sd, fileIoProvider, conf);
    assert getStorageLocation().getStorageType() == StorageType.PROVIDED:
      "Only provided storages must use ProvidedVolume";

    baseURI = getStorageLocation().getUri();
    Class<? extends ProvidedVolumeDF> dfClass =
        conf.getClass(DFSConfigKeys.DFS_PROVIDER_DF_CLASS,
            DefaultProvidedVolumeDF.class, ProvidedVolumeDF.class);
    df = ReflectionUtils.newInstance(dfClass, conf);
    remoteFS = FileSystem.get(baseURI, conf);
  }

  @Override
  public String[] getBlockPoolList() {
    return bpSlices.keySet().toArray(new String[bpSlices.keySet().size()]);
  }

  @Override
  public long getCapacity() {
    if (configuredCapacity < 0) {
      return df.getCapacity();
    }
    return configuredCapacity;
  }

  @Override
  public long getDfsUsed() throws IOException {
    return df.getSpaceUsed();
  }

  @Override
  long getBlockPoolUsed(String bpid) throws IOException {
    if (bpSlices.containsKey(bpid)) {
      return df.getBlockPoolUsed(bpid);
    } else {
      throw new IOException("block pool " + bpid + " is not found");
    }
  }

  @Override
  public long getAvailable() throws IOException {
    return df.getAvailable();
  }

  @Override
  long getActualNonDfsUsed() throws IOException {
    return df.getSpaceUsed();
  }

  @Override
  public long getNonDfsUsed() throws IOException {
    return 0L;
  }

  @Override
  public URI getBaseURI() {
    return baseURI;
  }

  @Override
  public File getFinalizedDir(String bpid) throws IOException {
    return null;
  }

  @Override
  public void reserveSpaceForReplica(long bytesToReserve) {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public void releaseReservedSpace(long bytesToRelease) {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  private static final ObjectWriter WRITER =
      new ObjectMapper().writerWithDefaultPrettyPrinter();
  private static final ObjectReader READER =
      new ObjectMapper().reader(ProvidedBlockIteratorState.class);

  private static class ProvidedBlockIteratorState {
    ProvidedBlockIteratorState() {
      iterStartMs = Time.now();
      lastSavedMs = iterStartMs;
      atEnd = false;
      lastBlockId = -1;
    }

    // The wall-clock ms since the epoch at which this iterator was last saved.
    @JsonProperty
    private long lastSavedMs;

    // The wall-clock ms since the epoch at which this iterator was created.
    @JsonProperty
    private long iterStartMs;

    @JsonProperty
    private boolean atEnd;

    //The id of the last block read when the state of the iterator is saved.
    //This implementation assumes that provided blocks are returned
    //in sorted order of the block ids.
    @JsonProperty
    private long lastBlockId;
  }

  private class ProviderBlockIteratorImpl
      implements FsVolumeSpi.BlockIterator {

    private String bpid;
    private String name;
    private BlockAliasMap<FileRegion> blockAliasMap;
    private Iterator<FileRegion> blockIterator;
    private ProvidedBlockIteratorState state;

    ProviderBlockIteratorImpl(String bpid, String name,
        BlockAliasMap<FileRegion> blockAliasMap) {
      this.bpid = bpid;
      this.name = name;
      this.blockAliasMap = blockAliasMap;
      rewind();
    }

    @Override
    public void close() throws IOException {
      //No action needed
    }

    @Override
    public ExtendedBlock nextBlock() throws IOException {
      if (null == blockIterator || !blockIterator.hasNext()) {
        return null;
      }
      FileRegion nextRegion = null;
      while (null == nextRegion && blockIterator.hasNext()) {
        FileRegion temp = blockIterator.next();
        if (temp.getBlock().getBlockId() < state.lastBlockId) {
          continue;
        }
        if (temp.getBlockPoolId().equals(bpid)) {
          nextRegion = temp;
        }
      }
      if (null == nextRegion) {
        return null;
      }
      state.lastBlockId = nextRegion.getBlock().getBlockId();
      return new ExtendedBlock(bpid, nextRegion.getBlock());
    }

    @Override
    public boolean atEnd() {
      return blockIterator != null ? !blockIterator.hasNext(): true;
    }

    @Override
    public void rewind() {
      BlockAliasMap.Reader<FileRegion> reader = null;
      try {
        reader = blockAliasMap.getReader(null);
      } catch (IOException e) {
        LOG.warn("Exception in getting reader from provided alias map");
      }
      if (reader != null) {
        blockIterator = reader.iterator();
      } else {
        blockIterator = null;
      }
      state = new ProvidedBlockIteratorState();
    }

    @Override
    public void save() throws IOException {
      //We do not persist the state of this iterator anywhere, locally.
      //We just re-scan provided volumes as necessary.
      state.lastSavedMs = Time.now();
    }

    @Override
    public void setMaxStalenessMs(long maxStalenessMs) {
      //do not use max staleness
    }

    @Override
    public long getIterStartMs() {
      return state.iterStartMs;
    }

    @Override
    public long getLastSavedMs() {
      return state.lastSavedMs;
    }

    @Override
    public String getBlockPoolId() {
      return bpid;
    }

    public void load() throws IOException {
      //on load, we just rewind the iterator for provided volumes.
      rewind();
      LOG.trace("load({}, {}): loaded iterator {}: {}", getStorageID(),
          bpid, name, WRITER.writeValueAsString(state));
    }
  }

  @Override
  public BlockIterator newBlockIterator(String bpid, String name) {
    return new ProviderBlockIteratorImpl(bpid, name,
        bpSlices.get(bpid).getBlockAliasMap());
  }

  @Override
  public BlockIterator loadBlockIterator(String bpid, String name)
      throws IOException {
    ProviderBlockIteratorImpl iter = new ProviderBlockIteratorImpl(bpid, name,
        bpSlices.get(bpid).getBlockAliasMap());
    iter.load();
    return iter;
  }

  @Override
  ReplicaInfo addFinalizedBlock(String bpid, Block b,
      ReplicaInfo replicaInfo, long bytesReserved) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public VolumeCheckResult check(VolumeCheckContext ignored)
      throws DiskErrorException {
    return VolumeCheckResult.HEALTHY;
  }

  @Override
  void getVolumeMap(ReplicaMap volumeMap,
      final RamDiskReplicaTracker ramDiskReplicaMap)
          throws IOException {
    LOG.info("Creating volumemap for provided volume " + this);
    for(ProvidedBlockPoolSlice s : bpSlices.values()) {
      s.getVolumeMap(volumeMap, ramDiskReplicaMap, remoteFS);
    }
  }

  private ProvidedBlockPoolSlice getProvidedBlockPoolSlice(String bpid)
      throws IOException {
    ProvidedBlockPoolSlice bp = bpSlices.get(bpid);
    if (bp == null) {
      throw new IOException("block pool " + bpid + " is not found");
    }
    return bp;
  }

  @Override
  void getVolumeMap(String bpid, ReplicaMap volumeMap,
      final RamDiskReplicaTracker ramDiskReplicaMap)
          throws IOException {
    getProvidedBlockPoolSlice(bpid).getVolumeMap(volumeMap, ramDiskReplicaMap,
        remoteFS);
  }

  @VisibleForTesting
  BlockAliasMap<FileRegion> getBlockFormat(String bpid) throws IOException {
    return getProvidedBlockPoolSlice(bpid).getBlockAliasMap();
  }

  @Override
  public String toString() {
    return this.baseURI.toString();
  }

  @Override
  void addBlockPool(String bpid, Configuration conf) throws IOException {
    addBlockPool(bpid, conf, null);
  }

  @Override
  void addBlockPool(String bpid, Configuration conf, Timer timer)
      throws IOException {
    LOG.info("Adding block pool " + bpid +
        " to volume with id " + getStorageID());
    ProvidedBlockPoolSlice bp;
    bp = new ProvidedBlockPoolSlice(bpid, this, conf);
    bpSlices.put(bpid, bp);
  }

  void shutdown() {
    if (cacheExecutor != null) {
      cacheExecutor.shutdown();
    }
    Set<Entry<String, ProvidedBlockPoolSlice>> set = bpSlices.entrySet();
    for (Entry<String, ProvidedBlockPoolSlice> entry : set) {
      entry.getValue().shutdown(null);
    }
  }

  @Override
  void shutdownBlockPool(String bpid, BlockListAsLongs blocksListsAsLongs) {
    ProvidedBlockPoolSlice bp = bpSlices.get(bpid);
    if (bp != null) {
      bp.shutdown(blocksListsAsLongs);
    }
    bpSlices.remove(bpid);
  }

  @Override
  boolean isBPDirEmpty(String bpid) throws IOException {
    return getProvidedBlockPoolSlice(bpid).isEmpty();
  }

  @Override
  void deleteBPDirectories(String bpid, boolean force) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public LinkedList<ScanInfo> compileReport(String bpid,
      LinkedList<ScanInfo> report, ReportCompiler reportCompiler)
          throws InterruptedException, IOException {
    LOG.info("Compiling report for volume: " + this + " bpid " + bpid);
    //get the report from the appropriate block pool.
    if(bpSlices.containsKey(bpid)) {
      bpSlices.get(bpid).compileReport(report, reportCompiler);
    }
    return report;
  }

  @Override
  public ReplicaInPipeline append(String bpid, ReplicaInfo replicaInfo,
      long newGS, long estimateBlockLen) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public ReplicaInPipeline createRbw(ExtendedBlock b) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public ReplicaInPipeline convertTemporaryToRbw(ExtendedBlock b,
      ReplicaInfo temp) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public ReplicaInPipeline createTemporary(ExtendedBlock b)
      throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public ReplicaInPipeline updateRURCopyOnTruncate(ReplicaInfo rur,
      String bpid, long newBlockId, long recoveryId, long newlength)
          throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public ReplicaInfo moveBlockToTmpLocation(ExtendedBlock block,
      ReplicaInfo replicaInfo, int smallBufferSize,
      Configuration conf) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  @Override
  public File[] copyBlockToLazyPersistLocation(String bpId, long blockId,
      long genStamp, ReplicaInfo replicaInfo, int smallBufferSize,
      Configuration conf) throws IOException {
    throw new UnsupportedOperationException(
        "ProvidedVolume does not yet support writes");
  }

  private static URI getAbsoluteURI(URI uri) {
    if (!uri.isAbsolute()) {
      // URI is not absolute implies it is for a local file
      // normalize the URI
      return StorageLocation.normalizeFileURI(uri);
    } else {
      return uri;
    }
  }
  /**
   * @param volumeURI URI of the volume
   * @param blockURI URI of the block
   * @return true if the {@code blockURI} can belong to the volume or both URIs
   * are null.
   */
  @VisibleForTesting
  public static boolean containsBlock(URI volumeURI, URI blockURI) {
    if (volumeURI == null && blockURI == null){
      return true;
    }
    if (volumeURI == null || blockURI == null) {
      return false;
    }
    volumeURI = getAbsoluteURI(volumeURI);
    blockURI = getAbsoluteURI(blockURI);
    return !volumeURI.relativize(blockURI).equals(blockURI);
  }

  @VisibleForTesting
  void setFileRegionProvider(String bpid,
      BlockAliasMap<FileRegion> blockAliasMap) throws IOException {
    ProvidedBlockPoolSlice bp = bpSlices.get(bpid);
    if (bp == null) {
      throw new IOException("block pool " + bpid + " is not found");
    }
    bp.setFileRegionProvider(blockAliasMap);
  }
}
