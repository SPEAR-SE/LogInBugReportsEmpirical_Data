/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.client.io;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos.Result;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos.ReplicationType;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos.ReplicationFactor;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyArgs;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyInfo;
import org.apache.hadoop.ozone.ksm.helpers.KsmKeyLocationInfo;
import org.apache.hadoop.ozone.ksm.helpers.OpenKeySession;
import org.apache.hadoop.ozone.ksm.protocolPB.KeySpaceManagerProtocolClientSideTranslatorPB;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerLocationProtocolProtos.NotifyObjectCreationStageRequestProto;
import org.apache.hadoop.scm.XceiverClientManager;
import org.apache.hadoop.scm.XceiverClientSpi;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.scm.container.common.helpers
    .StorageContainerException;
import org.apache.hadoop.scm.protocolPB
    .StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.scm.storage.ChunkOutputStream;
import org.apache.hadoop.scm.storage.ContainerProtocolCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.apache.hadoop.ozone.OzoneConfigKeys
    .OZONE_OUTPUT_STREAM_BUFFER_SIZE_DEFAULT;

/**
 * Maintaining a list of ChunkInputStream. Write based on offset.
 *
 * Note that this may write to multiple containers in one write call. In case
 * that first container succeeded but later ones failed, the succeeded writes
 * are not rolled back.
 *
 * TODO : currently not support multi-thread access.
 */
public class ChunkGroupOutputStream extends OutputStream {

  private static final Logger LOG =
      LoggerFactory.getLogger(ChunkGroupOutputStream.class);

  // array list's get(index) is O(1)
  private final ArrayList<ChunkOutputStreamEntry> streamEntries;
  private int currentStreamIndex;
  private long byteOffset;
  private final KeySpaceManagerProtocolClientSideTranslatorPB ksmClient;
  private final
      StorageContainerLocationProtocolClientSideTranslatorPB scmClient;
  private final KsmKeyArgs keyArgs;
  private final int openID;
  private final XceiverClientManager xceiverClientManager;
  private final int chunkSize;
  private final String requestID;
  private final long streamBufferSize;

  /**
   * A constructor for testing purpose only.
   */
  @VisibleForTesting
  public ChunkGroupOutputStream() {
    streamEntries = new ArrayList<>();
    ksmClient = null;
    scmClient = null;
    keyArgs = null;
    openID = -1;
    xceiverClientManager = null;
    chunkSize = 0;
    requestID = null;
    streamBufferSize = OZONE_OUTPUT_STREAM_BUFFER_SIZE_DEFAULT * OzoneConsts.MB;
  }

  /**
   * For testing purpose only. Not building output stream from blocks, but
   * taking from externally.
   *
   * @param outputStream
   * @param length
   */
  @VisibleForTesting
  public synchronized void addStream(OutputStream outputStream, long length) {
    streamEntries.add(new ChunkOutputStreamEntry(outputStream, length));
  }

  @VisibleForTesting
  public List<ChunkOutputStreamEntry> getStreamEntries() {
    return streamEntries;
  }

  /**
   * Chunkoutput stream, making this package visible since this can be
   * created only via builder.
   * @param handler  - Open Key state.
   * @param xceiverClientManager - Communication Manager.
   * @param scmClient - SCM protocol Client.
   * @param ksmClient - KSM Protocol client
   * @param chunkSize - Chunk Size - I/O
   * @param requestId - Seed for trace ID generation.
   * @param factor - Replication factor
   * @param type - Replication Type - RATIS/Standalone etc.
   * @param maxBufferSize - Maximum stream buffer Size.
   * @throws IOException - Throws this exception if there is an error.
   */
  ChunkGroupOutputStream(
      OpenKeySession handler, XceiverClientManager xceiverClientManager,
      StorageContainerLocationProtocolClientSideTranslatorPB scmClient,
      KeySpaceManagerProtocolClientSideTranslatorPB ksmClient,
      int chunkSize, String requestId, ReplicationFactor factor,
      ReplicationType type, long maxBufferSize) throws IOException {
    this.streamEntries = new ArrayList<>();
    this.currentStreamIndex = 0;
    this.byteOffset = 0;
    this.ksmClient = ksmClient;
    this.scmClient = scmClient;
    KsmKeyInfo info = handler.getKeyInfo();
    this.keyArgs = new KsmKeyArgs.Builder()
        .setVolumeName(info.getVolumeName())
        .setBucketName(info.getBucketName())
        .setKeyName(info.getKeyName())
        .setType(type)
        .setFactor(factor)
        .setDataSize(info.getDataSize()).build();
    this.openID = handler.getId();
    this.xceiverClientManager = xceiverClientManager;
    this.chunkSize = chunkSize;
    this.requestID = requestId;
    LOG.debug("Expecting open key with one block, but got" +
        info.getKeyLocationVersions().size());
    this.streamBufferSize = maxBufferSize;
  }

  /**
   * When a key is opened, it is possible that there are some blocks already
   * allocated to it for this open session. In this case, to make use of these
   * blocks, we need to add these blocks to stream entries. But, a key's version
   * also includes blocks from previous versions, we need to avoid adding these
   * old blocks to stream entries, because these old blocks should not be picked
   * for write. To do this, the following method checks that, only those
   * blocks created in this particular open version are added to stream entries.
   *
   * @param version the set of blocks that are pre-allocated.
   * @param openVersion the version corresponding to the pre-allocation.
   * @throws IOException
   */
  public void addPreallocateBlocks(KsmKeyLocationInfoGroup version,
      long openVersion) throws IOException {
    // server may return any number of blocks, (0 to any)
    // only the blocks allocated in this open session (block createVersion
    // equals to open session version)
    for (KsmKeyLocationInfo subKeyInfo : version.getLocationList()) {
      if (subKeyInfo.getCreateVersion() == openVersion) {
        checkKeyLocationInfo(subKeyInfo);
      }
    }
  }

  private void checkKeyLocationInfo(KsmKeyLocationInfo subKeyInfo)
      throws IOException {
    String containerKey = subKeyInfo.getBlockID();
    String containerName = subKeyInfo.getContainerName();
    Pipeline pipeline = scmClient.getContainer(containerName);
    XceiverClientSpi xceiverClient =
        xceiverClientManager.acquireClient(pipeline);
    // create container if needed
    if (subKeyInfo.getShouldCreateContainer()) {
      try {
        ContainerProtocolCalls.createContainer(xceiverClient, requestID);
        scmClient.notifyObjectCreationStage(
            NotifyObjectCreationStageRequestProto.Type.container,
            containerName,
            NotifyObjectCreationStageRequestProto.Stage.complete);
      } catch (StorageContainerException ex) {
        if (ex.getResult().equals(Result.CONTAINER_EXISTS)) {
          //container already exist, this should never happen
          LOG.debug("Container {} already exists.", containerName);
        } else {
          LOG.error("Container creation failed for {}.", containerName, ex);
          throw ex;
        }
      }
    }
    streamEntries.add(new ChunkOutputStreamEntry(containerKey,
        keyArgs.getKeyName(), xceiverClientManager, xceiverClient, requestID,
        chunkSize, subKeyInfo.getLength(), this.streamBufferSize));
  }


  @VisibleForTesting
  public long getByteOffset() {
    return byteOffset;
  }


  @Override
  public synchronized void write(int b) throws IOException {
    if (streamEntries.size() <= currentStreamIndex) {
      Preconditions.checkNotNull(ksmClient);
      // allocate a new block, if a exception happens, log an error and
      // throw exception to the caller directly, and the write fails.
      try {
        allocateNewBlock(currentStreamIndex);
      } catch (IOException ioe) {
        LOG.error("Allocate block fail when writing.");
        throw ioe;
      }
    }
    ChunkOutputStreamEntry entry = streamEntries.get(currentStreamIndex);
    entry.write(b);
    if (entry.getRemaining() <= 0) {
      currentStreamIndex += 1;
    }
    byteOffset += 1;
  }

  /**
   * Try to write the bytes sequence b[off:off+len) to streams.
   *
   * NOTE: Throws exception if the data could not fit into the remaining space.
   * In which case nothing will be written.
   * TODO:May need to revisit this behaviour.
   *
   * @param b byte data
   * @param off starting offset
   * @param len length to write
   * @throws IOException
   */
  @Override
  public synchronized void write(byte[] b, int off, int len)
      throws IOException {
    if (b == null) {
      throw new NullPointerException();
    }
    if ((off < 0) || (off > b.length) || (len < 0) ||
        ((off + len) > b.length) || ((off + len) < 0)) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return;
    }
    int succeededAllocates = 0;
    while (len > 0) {
      if (streamEntries.size() <= currentStreamIndex) {
        Preconditions.checkNotNull(ksmClient);
        // allocate a new block, if a exception happens, log an error and
        // throw exception to the caller directly, and the write fails.
        try {
          allocateNewBlock(currentStreamIndex);
          succeededAllocates += 1;
        } catch (IOException ioe) {
          LOG.error("Try to allocate more blocks for write failed, already " +
              "allocated " + succeededAllocates + " blocks for this write.");
          throw ioe;
        }
      }
      // in theory, this condition should never violate due the check above
      // still do a sanity check.
      Preconditions.checkArgument(currentStreamIndex < streamEntries.size());
      ChunkOutputStreamEntry current = streamEntries.get(currentStreamIndex);
      int writeLen = Math.min(len, (int)current.getRemaining());
      current.write(b, off, writeLen);
      if (current.getRemaining() <= 0) {
        currentStreamIndex += 1;
      }
      len -= writeLen;
      off += writeLen;
      byteOffset += writeLen;
    }
  }

  /**
   * Contact KSM to get a new block. Set the new block with the index (e.g.
   * first block has index = 0, second has index = 1 etc.)
   *
   * The returned block is made to new ChunkOutputStreamEntry to write.
   *
   * @param index the index of the block.
   * @throws IOException
   */
  private void allocateNewBlock(int index) throws IOException {
    KsmKeyLocationInfo subKeyInfo = ksmClient.allocateBlock(keyArgs, openID);
    checkKeyLocationInfo(subKeyInfo);
  }

  @Override
  public synchronized void flush() throws IOException {
    for (int i = 0; i <= currentStreamIndex; i++) {
      streamEntries.get(i).flush();
    }
  }

  /**
   * Commit the key to KSM, this will add the blocks as the new key blocks.
   *
   * @throws IOException
   */
  @Override
  public synchronized void close() throws IOException {
    for (ChunkOutputStreamEntry entry : streamEntries) {
      if (entry != null) {
        entry.close();
      }
    }
    if (keyArgs != null) {
      // in test, this could be null
      keyArgs.setDataSize(byteOffset);
      ksmClient.commitKey(keyArgs, openID);
    } else {
      LOG.warn("Closing ChunkGroupOutputStream, but key args is null");
    }
  }

  /**
   * Builder class of ChunkGroupOutputStream.
   */
  public static class Builder {
    private OpenKeySession openHandler;
    private XceiverClientManager xceiverManager;
    private StorageContainerLocationProtocolClientSideTranslatorPB scmClient;
    private KeySpaceManagerProtocolClientSideTranslatorPB ksmClient;
    private int chunkSize;
    private String requestID;
    private ReplicationType type;
    private ReplicationFactor factor;
    private long streamBufferSize;

    public Builder setHandler(OpenKeySession handler) {
      this.openHandler = handler;
      return this;
    }

    public Builder setXceiverClientManager(XceiverClientManager manager) {
      this.xceiverManager = manager;
      return this;
    }

    public Builder setScmClient(
        StorageContainerLocationProtocolClientSideTranslatorPB client) {
      this.scmClient = client;
      return this;
    }

    public Builder setKsmClient(
        KeySpaceManagerProtocolClientSideTranslatorPB client) {
      this.ksmClient = client;
      return this;
    }

    public Builder setChunkSize(int size) {
      this.chunkSize = size;
      return this;
    }

    public Builder setRequestID(String id) {
      this.requestID = id;
      return this;
    }

    public Builder setType(ReplicationType replicationType) {
      this.type = replicationType;
      return this;
    }

    public Builder setFactor(ReplicationFactor replicationFactor) {
      this.factor = replicationFactor;
      return this;
    }

    public Builder setStreamBufferSize(long blockSize) {
      this.streamBufferSize = blockSize;
      return this;
    }

    public ChunkGroupOutputStream build() throws IOException {
      Preconditions.checkNotNull(openHandler);
      Preconditions.checkNotNull(xceiverManager);
      Preconditions.checkNotNull(scmClient);
      Preconditions.checkNotNull(ksmClient);
      Preconditions.checkState(chunkSize > 0);
      Preconditions.checkState(StringUtils.isNotEmpty(requestID));
      Preconditions
          .checkState(streamBufferSize > 0 && streamBufferSize > chunkSize);

      return new ChunkGroupOutputStream(openHandler, xceiverManager, scmClient,
          ksmClient, chunkSize, requestID, factor, type, streamBufferSize);
    }
  }

  private static class ChunkOutputStreamEntry extends OutputStream {
    private OutputStream outputStream;
    private final String containerKey;
    private final String key;
    private final XceiverClientManager xceiverClientManager;
    private final XceiverClientSpi xceiverClient;
    private final String requestId;
    private final int chunkSize;
    // total number of bytes that should be written to this stream
    private final long length;
    // the current position of this stream 0 <= currentPosition < length
    private long currentPosition;
    private long streamBufferSize; // Max block size.

    ChunkOutputStreamEntry(String containerKey, String key,
        XceiverClientManager xceiverClientManager,
        XceiverClientSpi xceiverClient, String requestId, int chunkSize,
        long length, long streamBufferSize) {
      this.outputStream = null;
      this.containerKey = containerKey;
      this.key = key;
      this.xceiverClientManager = xceiverClientManager;
      this.xceiverClient = xceiverClient;
      this.requestId = requestId;
      this.chunkSize = chunkSize;

      this.length = length;
      this.currentPosition = 0;
      this.streamBufferSize = streamBufferSize;
    }

    /**
     * For testing purpose, taking a some random created stream instance.
     * @param  outputStream a existing writable output stream
     * @param  length the length of data to write to the stream
     */
    ChunkOutputStreamEntry(OutputStream outputStream, long length) {
      this.outputStream = outputStream;
      this.containerKey = null;
      this.key = null;
      this.xceiverClientManager = null;
      this.xceiverClient = null;
      this.requestId = null;
      this.chunkSize = -1;

      this.length = length;
      this.currentPosition = 0;
      this.streamBufferSize =
          OZONE_OUTPUT_STREAM_BUFFER_SIZE_DEFAULT * OzoneConsts.MB;
    }

    long getLength() {
      return length;
    }

    long getRemaining() {
      return length - currentPosition;
    }

    private synchronized void checkStream() {
      if (this.outputStream == null) {
        this.outputStream = new ChunkOutputStream(containerKey,
            key, xceiverClientManager, xceiverClient,
            requestId, chunkSize, Ints.checkedCast(streamBufferSize));
      }
    }

    @Override
    public void write(int b) throws IOException {
      checkStream();
      outputStream.write(b);
      this.currentPosition += 1;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      checkStream();
      outputStream.write(b, off, len);
      this.currentPosition += len;
    }

    @Override
    public void flush() throws IOException {
      if (this.outputStream != null) {
        this.outputStream.flush();
      }
    }

    @Override
    public void close() throws IOException {
      if (this.outputStream != null) {
        this.outputStream.close();
      }
    }
  }
}
