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
package org.apache.hadoop.ozone.container.common.helpers;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.impl.ChunkManagerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;

/**
 * Set of utility functions used by the chunk Manager.
 */
public final class ChunkUtils {

  /* Never constructed. */
  private ChunkUtils() {
  }

  /**
   * Checks if we are getting a request to overwrite an existing range of
   * chunk.
   *
   * @param chunkFile - File
   * @param chunkInfo - Buffer to write
   * @return bool
   */
  public static boolean isOverWriteRequested(File chunkFile, ChunkInfo
      chunkInfo) {

    if (!chunkFile.exists()) {
      return false;
    }

    long offset = chunkInfo.getOffset();
    return offset < chunkFile.length();
  }

  /**
   * Overwrite is permitted if an only if the user explicitly asks for it. We
   * permit this iff the key/value pair contains a flag called
   * [OverWriteRequested, true].
   *
   * @param chunkInfo - Chunk info
   * @return true if the user asks for it.
   */
  public static boolean isOverWritePermitted(ChunkInfo chunkInfo) {
    String overWrite = chunkInfo.getMetadata().get(OzoneConsts.CHUNK_OVERWRITE);
    return (overWrite != null) &&
        (!overWrite.isEmpty()) &&
        (Boolean.valueOf(overWrite));
  }

  /**
   * Validates chunk data and returns a file object to Chunk File that we are
   * expected to write data to.
   *
   * @param pipeline - pipeline.
   * @param data     - container data.
   * @param info     - chunk info.
   * @return File
   * @throws IOException
   */
  public static File validateChunk(Pipeline pipeline, ContainerData data,
                                   ChunkInfo info) throws IOException {

    Logger log = LoggerFactory.getLogger(ChunkManagerImpl.class);

    File chunkFile = getChunkFile(pipeline, data, info);
    if (ChunkUtils.isOverWriteRequested(chunkFile, info)) {
      if (!ChunkUtils.isOverWritePermitted(info)) {
        log.error("Rejecting write chunk request. Chunk overwrite " +
            "without explicit request. {}", info.toString());
        throw new IOException("Rejecting write chunk request. OverWrite " +
            "flag required." + info.toString());
      }
    }
    return chunkFile;
  }

  /**
   * Validates that Path to chunk file exists.
   *
   * @param pipeline - Container Info.
   * @param data     - Container Data
   * @param info     - Chunk info
   * @return - File.
   * @throws IOException
   */
  public static File getChunkFile(Pipeline pipeline, ContainerData data,
                                  ChunkInfo info) throws IOException {

    Logger log = LoggerFactory.getLogger(ChunkManagerImpl.class);
    if (data == null) {
      log.error("Invalid container Name: {}", pipeline.getContainerName());
      throw new IOException("Unable to find the container Name: " +
          pipeline.getContainerName());
    }

    File dataDir = ContainerUtils.getDataDirectory(data).toFile();
    if (!dataDir.exists()) {
      log.error("Unable to find the data directory: {}", dataDir);
      throw new IOException("Unable to find the data directory: " + dataDir);
    }

    return dataDir.toPath().resolve(info.getChunkName()).toFile();

  }

  /**
   * Writes the data in chunk Info to the specified location in the chunkfile.
   *
   * @param chunkFile - File to write data to.
   * @param chunkInfo - Data stream to write.
   * @throws IOException
   */
  public static void writeData(File chunkFile, ChunkInfo chunkInfo,
                               byte[] data)
      throws IOException, ExecutionException, InterruptedException,
      NoSuchAlgorithmException {

    Logger log = LoggerFactory.getLogger(ChunkManagerImpl.class);
    if (data.length != chunkInfo.getLen()) {
      String err = String.format("data array does not match the length " +
              "specified. DataLen: %d Byte Array: %d",
          chunkInfo.getLen(), data.length);
      log.error(err);
      throw new IOException(err);
    }

    AsynchronousFileChannel file = null;
    FileLock lock = null;

    try {
      file =
          AsynchronousFileChannel.open(chunkFile.toPath(),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.SPARSE,
              StandardOpenOption.SYNC);
      lock = file.lock().get();
      if (!chunkInfo.getChecksum().isEmpty()) {
        verifyChecksum(chunkInfo, data, log);
      }
      int size = file.write(ByteBuffer.wrap(data), chunkInfo.getOffset()).get();
      if(size != data.length) {
        log.error("Invalid write size found. Size:{}  Expected: {} " , size,
            data.length);
        throw new IOException("Invalid write size found. Size: " + size
            + " Expected: " + data.length);
      }
    } finally {
      if (lock != null) {
        lock.release();
      }
      if (file != null) {
        IOUtils.closeStream(file);
      }
    }
  }

  /**
   * Verifies the checksum of a chunk against the data buffer.
   *
   * @param chunkInfo - Chunk Info.
   * @param data      - data buffer
   * @param log       - log
   * @throws NoSuchAlgorithmException
   * @throws IOException
   */
  private static void verifyChecksum(ChunkInfo chunkInfo, byte[] data, Logger
      log) throws NoSuchAlgorithmException, IOException {
    MessageDigest sha = MessageDigest.getInstance(OzoneConsts.FILE_HASH);
    sha.update(data);
    if (!Hex.encodeHexString(sha.digest()).equals(
        chunkInfo.getChecksum())) {
      log.error("Checksum mismatch. Provided: {} , computed: {}",
          chunkInfo.getChecksum(), DigestUtils.sha256Hex(sha.digest()));
      throw new IOException("Checksum mismatch. Provided: " +
          chunkInfo.getChecksum() + " , computed: " +
          DigestUtils.sha256Hex(sha.digest()));
    }
  }

  /**
   * Reads data from an existing chunk file.
   *
   * @param chunkFile - file where data lives.
   * @param data      - chunk defintion.
   * @return ByteBuffer
   * @throws IOException
   * @throws ExecutionException
   * @throws InterruptedException
   */
  public static ByteBuffer readData(File chunkFile, ChunkInfo data) throws
      IOException, ExecutionException, InterruptedException,
      NoSuchAlgorithmException {
    Logger log = LoggerFactory.getLogger(ChunkManagerImpl.class);

    if (!chunkFile.exists()) {
      log.error("Unable to find the chunk file. chunk info : {}",
          data.toString());
      throw new IOException("Unable to find the chunk file. chunk info " +
          data.toString());
    }

    AsynchronousFileChannel file = null;
    FileLock lock = null;
    try {
      file =
          AsynchronousFileChannel.open(chunkFile.toPath(),
              StandardOpenOption.READ);
      lock = file.lock(data.getOffset(), data.getLen(), true).get();

      ByteBuffer buf = ByteBuffer.allocate((int) data.getLen());
      file.read(buf, data.getOffset()).get();

      if (data.getChecksum() != null && !data.getChecksum().isEmpty()) {
        verifyChecksum(data, buf.array(), log);
      }

      return buf;
    } finally {
      if (lock != null) {
        lock.release();
      }
      if (file != null) {
        IOUtils.closeStream(file);
      }
    }
  }

  /**
   * Returns a CreateContainer Response. This call is used by create and delete
   * containers which have null success responses.
   *
   * @param msg Request
   * @return Response.
   */
  public static ContainerProtos.ContainerCommandResponseProto
      getChunkResponse(ContainerProtos.ContainerCommandRequestProto msg) {
    return ContainerUtils.getContainerResponse(msg);
  }

  /**
   * Gets a response to the read chunk calls.
   * @param msg - Msg
   * @param data  - Data
   * @param info  - Info
   * @return    Response.
   */
  public static ContainerProtos.ContainerCommandResponseProto
      getReadChunkResponse(ContainerProtos.ContainerCommandRequestProto msg,
                           byte[] data, ChunkInfo info) {
    Preconditions.checkNotNull(msg);

    ContainerProtos.ReadChunkReponseProto.Builder response =
        ContainerProtos.ReadChunkReponseProto.newBuilder();
    response.setChunkData(info.getProtoBufMessage());
    response.setData(ByteString.copyFrom(data));
    response.setPipeline(msg.getReadChunk().getPipeline());

    ContainerProtos.ContainerCommandResponseProto.Builder builder =
        ContainerUtils.getContainerResponse(msg, ContainerProtos.Result
            .SUCCESS, "");
    builder.setReadChunk(response);
    return builder.build();
  }
}
