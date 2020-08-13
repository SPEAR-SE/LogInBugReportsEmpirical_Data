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
package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.io.erasurecode.CodecUtil;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.io.erasurecode.rawcoder.RawErasureEncoder;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.Progressable;
import org.apache.htrace.Sampler;
import org.apache.htrace.Trace;
import org.apache.htrace.TraceScope;

import com.google.common.base.Preconditions;


/**
 * This class supports writing files in striped layout and erasure coded format.
 * Each stripe contains a sequence of cells.
 */
@InterfaceAudience.Private
public class DFSStripedOutputStream extends DFSOutputStream {
  static class MultipleBlockingQueue<T> {
    private final List<BlockingQueue<T>> queues;

    MultipleBlockingQueue(int numQueue, int queueSize) {
      queues = new ArrayList<>(numQueue);
      for (int i = 0; i < numQueue; i++) {
        queues.add(new LinkedBlockingQueue<T>(queueSize));
      }
    }

    boolean isEmpty() {
      for(int i = 0; i < queues.size(); i++) {
        if (!queues.get(i).isEmpty()) {
          return false;
        }
      }
      return true;
    }

    int numQueues() {
      return queues.size();
    }

    void offer(int i, T object) {
      final boolean b = queues.get(i).offer(object);
      Preconditions.checkState(b, "Failed to offer " + object
          + " to queue, i=" + i);
    }

    T take(int i) throws InterruptedIOException {
      try {
        return queues.get(i).take();
      } catch(InterruptedException ie) {
        throw DFSUtil.toInterruptedIOException("take interrupted, i=" + i, ie);
      }
    }

    T poll(int i) {
      return queues.get(i).poll();
    }

    T peek(int i) {
      return queues.get(i).peek();
    }
  }

  /** Coordinate the communication between the streamers. */
  class Coordinator {
    private final MultipleBlockingQueue<LocatedBlock> followingBlocks;
    private final MultipleBlockingQueue<ExtendedBlock> endBlocks;

    private final MultipleBlockingQueue<LocatedBlock> newBlocks;
    private final MultipleBlockingQueue<ExtendedBlock> updateBlocks;

    Coordinator(final DfsClientConf conf, final int numDataBlocks,
        final int numAllBlocks) {
      followingBlocks = new MultipleBlockingQueue<>(numAllBlocks, 1);
      endBlocks = new MultipleBlockingQueue<>(numDataBlocks, 1);

      newBlocks = new MultipleBlockingQueue<>(numAllBlocks, 1);
      updateBlocks = new MultipleBlockingQueue<>(numAllBlocks, 1);
    }

    MultipleBlockingQueue<LocatedBlock> getFollowingBlocks() {
      return followingBlocks;
    }

    MultipleBlockingQueue<LocatedBlock> getNewBlocks() {
      return newBlocks;
    }

    MultipleBlockingQueue<ExtendedBlock> getUpdateBlocks() {
      return updateBlocks;
    }

    StripedDataStreamer getStripedDataStreamer(int i) {
      return DFSStripedOutputStream.this.getStripedDataStreamer(i);
    }

    void offerEndBlock(int i, ExtendedBlock block) {
      endBlocks.offer(i, block);
    }

    ExtendedBlock takeEndBlock(int i) throws InterruptedIOException {
      return endBlocks.take(i);
    }

    boolean hasAllEndBlocks() {
      for(int i = 0; i < endBlocks.numQueues(); i++) {
        if (endBlocks.peek(i) == null) {
          return false;
        }
      }
      return true;
    }

    void setBytesEndBlock(int i, long newBytes, ExtendedBlock block) {
      ExtendedBlock b = endBlocks.peek(i);
      if (b == null) {
        // streamer just has failed, put end block and continue
        b = block;
        offerEndBlock(i, b);
      }
      b.setNumBytes(newBytes);
    }

    /** @return a block representing the entire block group. */
    ExtendedBlock getBlockGroup() {
      final StripedDataStreamer s0 = getStripedDataStreamer(0);
      final ExtendedBlock b0 = s0.getBlock();
      if (b0 == null) {
        return null;
      }

      final boolean atBlockGroupBoundary = s0.getBytesCurBlock() == 0 && b0.getNumBytes() > 0;
      final ExtendedBlock block = new ExtendedBlock(b0);
      long numBytes = b0.getNumBytes();
      for (int i = 1; i < numDataBlocks; i++) {
        final StripedDataStreamer si = getStripedDataStreamer(i);
        final ExtendedBlock bi = si.getBlock();
        if (bi != null && bi.getGenerationStamp() > block.getGenerationStamp()) {
          block.setGenerationStamp(bi.getGenerationStamp());
        }
        numBytes += atBlockGroupBoundary? bi.getNumBytes(): si.getBytesCurBlock();
      }
      block.setNumBytes(numBytes);
      if (LOG.isDebugEnabled()) {
        LOG.debug("getBlockGroup: " + block + ", numBytes=" + block.getNumBytes());
      }
      return block;
    }
  }

  /** Buffers for writing the data and parity cells of a stripe. */
  class CellBuffers {
    private final ByteBuffer[] buffers;
    private final byte[][] checksumArrays;

    CellBuffers(int numParityBlocks) throws InterruptedException{
      if (cellSize % bytesPerChecksum != 0) {
        throw new HadoopIllegalArgumentException("Invalid values: "
            + DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY + " (="
            + bytesPerChecksum + ") must divide cell size (=" + cellSize + ").");
      }

      checksumArrays = new byte[numParityBlocks][];
      final int size = getChecksumSize() * (cellSize / bytesPerChecksum);
      for (int i = 0; i < checksumArrays.length; i++) {
        checksumArrays[i] = new byte[size];
      }

      buffers = new ByteBuffer[numAllBlocks];
      for (int i = 0; i < buffers.length; i++) {
        buffers[i] = ByteBuffer.wrap(byteArrayManager.newByteArray(cellSize));
      }
    }

    private ByteBuffer[] getBuffers() {
      return buffers;
    }

    byte[] getChecksumArray(int i) {
      return checksumArrays[i - numDataBlocks];
    }

    private int addTo(int i, byte[] b, int off, int len) {
      final ByteBuffer buf = buffers[i];
      final int pos = buf.position() + len;
      Preconditions.checkState(pos <= cellSize);
      buf.put(b, off, len);
      return pos;
    }

    private void clear() {
      for (int i = 0; i< numAllBlocks; i++) {
        buffers[i].clear();
        if (i >= numDataBlocks) {
          Arrays.fill(buffers[i].array(), (byte) 0);
        }
      }
    }

    private void release() {
      for (int i = 0; i < numAllBlocks; i++) {
        byteArrayManager.release(buffers[i].array());
      }
    }

    private void flipDataBuffers() {
      for (int i = 0; i < numDataBlocks; i++) {
        buffers[i].flip();
      }
    }
  }

  private final Coordinator coordinator;
  private final CellBuffers cellBuffers;
  private final RawErasureEncoder encoder;
  private final List<StripedDataStreamer> streamers;
  private final DFSPacket[] currentPackets; // current Packet of each streamer

  /** Size of each striping cell, must be a multiple of bytesPerChecksum */
  private final int cellSize;
  private final int numAllBlocks;
  private final int numDataBlocks;

  @Override
  ExtendedBlock getBlock() {
    return coordinator.getBlockGroup();
  }

  /** Construct a new output stream for creating a file. */
  DFSStripedOutputStream(DFSClient dfsClient, String src, HdfsFileStatus stat,
                         EnumSet<CreateFlag> flag, Progressable progress,
                         DataChecksum checksum, String[] favoredNodes)
                         throws IOException {
    super(dfsClient, src, stat, flag, progress, checksum, favoredNodes);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Creating DFSStripedOutputStream for " + src);
    }

    final ErasureCodingPolicy ecPolicy = stat.getErasureCodingPolicy();
    final int numParityBlocks = ecPolicy.getNumParityUnits();
    cellSize = ecPolicy.getCellSize();
    numDataBlocks = ecPolicy.getNumDataUnits();
    numAllBlocks = numDataBlocks + numParityBlocks;

    encoder = CodecUtil.createRSRawEncoder(dfsClient.getConfiguration(),
        numDataBlocks, numParityBlocks);

    coordinator = new Coordinator(dfsClient.getConf(),
        numDataBlocks, numAllBlocks);
    try {
      cellBuffers = new CellBuffers(numParityBlocks);
    } catch (InterruptedException ie) {
      throw DFSUtil.toInterruptedIOException(
          "Failed to create cell buffers", ie);
    }

    List<StripedDataStreamer> s = new ArrayList<>(numAllBlocks);
    for (short i = 0; i < numAllBlocks; i++) {
      StripedDataStreamer streamer = new StripedDataStreamer(stat,
          dfsClient, src, progress, checksum, cachingStrategy, byteArrayManager,
          favoredNodes, i, coordinator);
      s.add(streamer);
    }
    streamers = Collections.unmodifiableList(s);
    currentPackets = new DFSPacket[streamers.size()];
    setCurrentStreamer(0);
  }

  StripedDataStreamer getStripedDataStreamer(int i) {
    return streamers.get(i);
  }

  int getCurrentIndex() {
    return getCurrentStreamer().getIndex();
  }

  private synchronized StripedDataStreamer getCurrentStreamer() {
    return (StripedDataStreamer)streamer;
  }

  private synchronized StripedDataStreamer setCurrentStreamer(int newIdx)
      throws IOException {
    // backup currentPacket for current streamer
    int oldIdx = streamers.indexOf(streamer);
    if (oldIdx >= 0) {
      currentPackets[oldIdx] = currentPacket;
    }

    streamer = streamers.get(newIdx);
    currentPacket = currentPackets[newIdx];
    adjustChunkBoundary();

    return getCurrentStreamer();
  }

  /**
   * Encode the buffers, i.e. compute parities.
   *
   * @param buffers data buffers + parity buffers
   */
  private static void encode(RawErasureEncoder encoder, int numData,
      ByteBuffer[] buffers) {
    final ByteBuffer[] dataBuffers = new ByteBuffer[numData];
    final ByteBuffer[] parityBuffers = new ByteBuffer[buffers.length - numData];
    System.arraycopy(buffers, 0, dataBuffers, 0, dataBuffers.length);
    System.arraycopy(buffers, numData, parityBuffers, 0, parityBuffers.length);

    encoder.encode(dataBuffers, parityBuffers);
  }


  private void checkStreamers() throws IOException {
    int count = 0;
    for(StripedDataStreamer s : streamers) {
      if (!s.isFailed()) {
        if (s.getBlock() != null) {
          s.getErrorState().initExternalError();
        }
        count++;
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("checkStreamers: " + streamers);
      LOG.debug("count=" + count);
    }
    if (count < numDataBlocks) {
      throw new IOException("Failed: the number of remaining blocks = "
          + count + " < the number of data blocks = " + numDataBlocks);
    }
  }

  private void handleStreamerFailure(String err,
                                     Exception e) throws IOException {
    LOG.warn("Failed: " + err + ", " + this, e);
    getCurrentStreamer().setFailed(true);
    checkStreamers();
    currentPacket = null;
  }

  @Override
  protected synchronized void writeChunk(byte[] bytes, int offset, int len,
      byte[] checksum, int ckoff, int cklen) throws IOException {
    final int index = getCurrentIndex();
    final StripedDataStreamer current = getCurrentStreamer();
    final int pos = cellBuffers.addTo(index, bytes, offset, len);
    final boolean cellFull = pos == cellSize;

    final long oldBytes = current.getBytesCurBlock();
    if (!current.isFailed()) {
      try {
        super.writeChunk(bytes, offset, len, checksum, ckoff, cklen);
      } catch(Exception e) {
        handleStreamerFailure("offset=" + offset + ", length=" + len, e);
      }
    }

    if (current.isFailed()) {
      final long newBytes = oldBytes + len;
      coordinator.setBytesEndBlock(index, newBytes, current.getBlock());
      current.setBytesCurBlock(newBytes);
    }

    // Two extra steps are needed when a striping cell is full:
    // 1. Forward the current index pointer
    // 2. Generate parity packets if a full stripe of data cells are present
    if (cellFull) {
      int next = index + 1;
      //When all data cells in a stripe are ready, we need to encode
      //them and generate some parity cells. These cells will be
      //converted to packets and put to their DataStreamer's queue.
      if (next == numDataBlocks) {
        cellBuffers.flipDataBuffers();
        writeParityCells();
        next = 0;
      }
      setCurrentStreamer(next);
    }
  }

  private int stripeDataSize() {
    return numDataBlocks * cellSize;
  }

  @Override
  public void hflush() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void hsync() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected synchronized void start() {
    for (StripedDataStreamer streamer : streamers) {
      streamer.start();
    }
  }

  @Override
  synchronized void abort() throws IOException {
    if (isClosed()) {
      return;
    }
    for (StripedDataStreamer streamer : streamers) {
      streamer.getLastException().set(new IOException("Lease timeout of "
          + (dfsClient.getConf().getHdfsTimeout()/1000) +
          " seconds expired."));
    }
    closeThreads(true);
    dfsClient.endFileLease(fileId);
  }

  @Override
  boolean isClosed() {
    if (closed) {
      return true;
    }
    for(StripedDataStreamer s : streamers) {
      if (!s.streamerClosed()) {
        return false;
      }
    }
    return true;
  }

  @Override
  protected void closeThreads(boolean force) throws IOException {
    final MultipleIOException.Builder b = new MultipleIOException.Builder();
    try {
      for (StripedDataStreamer streamer : streamers) {
        try {
          streamer.close(force);
          streamer.join();
          streamer.closeSocket();
        } catch (Exception e) {
          try {
            handleStreamerFailure("force=" + force, e);
          } catch (IOException ioe) {
            b.add(ioe);
          }
        } finally {
          streamer.setSocketToNull();
        }
      }
    } finally {
      setClosed();
    }
    final IOException ioe = b.build();
    if (ioe != null) {
      throw ioe;
    }
  }

  /**
   * Simply add bytesCurBlock together. Note that this result is not accurately
   * the size of the block group.
   */
  private long getCurrentSumBytes() {
    long sum = 0;
    for (int i = 0; i < numDataBlocks; i++) {
      sum += streamers.get(i).getBytesCurBlock();
    }
    return sum;
  }

  private void writeParityCellsForLastStripe() throws IOException {
    final long currentBlockGroupBytes = getCurrentSumBytes();
    if (currentBlockGroupBytes % stripeDataSize() == 0) {
      return;
    }

    final int firstCellSize =
        (int)(getStripedDataStreamer(0).getBytesCurBlock() % cellSize);
    final int parityCellSize = firstCellSize > 0 && firstCellSize < cellSize?
        firstCellSize : cellSize;
    final ByteBuffer[] buffers = cellBuffers.getBuffers();

    for (int i = 0; i < numAllBlocks; i++) {
      // Pad zero bytes to make all cells exactly the size of parityCellSize
      // If internal block is smaller than parity block, pad zero bytes.
      // Also pad zero bytes to all parity cells
      final int position = buffers[i].position();
      assert position <= parityCellSize : "If an internal block is smaller" +
          " than parity block, then its last cell should be small than last" +
          " parity cell";
      for (int j = 0; j < parityCellSize - position; j++) {
        buffers[i].put((byte) 0);
      }
      buffers[i].flip();
    }

    writeParityCells();
  }

  void writeParityCells() throws IOException {
    final ByteBuffer[] buffers = cellBuffers.getBuffers();
    //encode the data cells
    encode(encoder, numDataBlocks, buffers);
    for (int i = numDataBlocks; i < numAllBlocks; i++) {
      writeParity(i, buffers[i], cellBuffers.getChecksumArray(i));
    }
    cellBuffers.clear();
  }

  void writeParity(int index, ByteBuffer buffer, byte[] checksumBuf
      ) throws IOException {
    final StripedDataStreamer current = setCurrentStreamer(index);
    final int len = buffer.limit();

    final long oldBytes = current.getBytesCurBlock();
    if (!current.isFailed()) {
      try {
        DataChecksum sum = getDataChecksum();
        sum.calculateChunkedSums(buffer.array(), 0, len, checksumBuf, 0);
        for (int i = 0; i < len; i += sum.getBytesPerChecksum()) {
          int chunkLen = Math.min(sum.getBytesPerChecksum(), len - i);
          int ckOffset = i / sum.getBytesPerChecksum() * getChecksumSize();
          super.writeChunk(buffer.array(), i, chunkLen, checksumBuf, ckOffset,
              getChecksumSize());
        }
      } catch(Exception e) {
        handleStreamerFailure("oldBytes=" + oldBytes + ", len=" + len, e);
      }
    }

    if (current.isFailed()) {
      final long newBytes = oldBytes + len;
      current.setBytesCurBlock(newBytes);
    }
  }

  @Override
  void setClosed() {
    super.setClosed();
    for (int i = 0; i < numAllBlocks; i++) {
      streamers.get(i).release();
    }
    cellBuffers.release();
  }

  @Override
  protected synchronized void closeImpl() throws IOException {
    if (isClosed()) {
      final MultipleIOException.Builder b = new MultipleIOException.Builder();
      for(int i = 0; i < streamers.size(); i++) {
        final StripedDataStreamer si = getStripedDataStreamer(i);
        try {
          si.getLastException().check(true);
        } catch (IOException e) {
          b.add(e);
        }
      }
      final IOException ioe = b.build();
      if (ioe != null) {
        throw ioe;
      }
      return;
    }

    try {
      // flush from all upper layers
      try {
        flushBuffer();
        // if the last stripe is incomplete, generate and write parity cells
        writeParityCellsForLastStripe();
        enqueueAllCurrentPackets();
      } catch(Exception e) {
        handleStreamerFailure("closeImpl", e);
      }

      for (int i = 0; i < numAllBlocks; i++) {
        final StripedDataStreamer s = setCurrentStreamer(i);
        if (!s.isFailed()) {
          try {
            if (s.getBytesCurBlock() > 0) {
              setCurrentPacketToEmpty();
            }
            // flush all data to Datanode
            flushInternal();
          } catch(Exception e) {
            handleStreamerFailure("closeImpl", e);
          }
        }
      }

      closeThreads(false);
      final ExtendedBlock lastBlock = coordinator.getBlockGroup();
      TraceScope scope = Trace.startSpan("completeFile", Sampler.NEVER);
      try {
        completeFile(lastBlock);
      } finally {
        scope.close();
      }
      dfsClient.endFileLease(fileId);
    } catch (ClosedChannelException ignored) {
    } finally {
      setClosed();
    }
  }

  private void enqueueAllCurrentPackets() throws IOException {
    int idx = streamers.indexOf(getCurrentStreamer());
    for(int i = 0; i < streamers.size(); i++) {
      setCurrentStreamer(i);
      if (currentPacket != null) {
        enqueueCurrentPacket();
      }
    }
    setCurrentStreamer(idx);
  }
}
