/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.scm.container;

import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.ozone.protocol.proto.OzoneProtos;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.scm.exceptions.SCMException;
import org.apache.hadoop.ozone.scm.node.NodeManager;
import org.apache.hadoop.ozone.scm.pipelines.PipelineSelector;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.utils.MetadataKeyFilters.KeyPrefixFilter;
import org.apache.hadoop.utils.MetadataKeyFilters.MetadataKeyFilter;
import org.apache.hadoop.utils.MetadataStore;
import org.apache.hadoop.utils.MetadataStoreBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.hadoop.ozone.OzoneConsts.CONTAINER_DB;

/**
 * Mapping class contains the mapping from a name to a pipeline mapping. This is
 * used by SCM when allocating new locations and when looking up a key.
 */
public class ContainerMapping implements Mapping {
  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerMapping.class);

  private final NodeManager nodeManager;
  private final long cacheSize;
  private final Lock lock;
  private final Charset encoding = Charset.forName("UTF-8");
  private final MetadataStore containerStore;
  private final PipelineSelector pipelineSelector;

  /**
   * Constructs a mapping class that creates mapping between container names and
   * pipelines.
   *
   * @param nodeManager - NodeManager so that we can get the nodes that are
   * healthy to place new containers.
   * @param cacheSizeMB - Amount of memory reserved for the LSM tree to cache
   * its nodes. This is passed to LevelDB and this memory is allocated in Native
   * code space. CacheSize is specified in MB.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public ContainerMapping(final Configuration conf,
      final NodeManager nodeManager, final int cacheSizeMB) throws IOException {
    this.nodeManager = nodeManager;
    this.cacheSize = cacheSizeMB;

    File metaDir = OzoneUtils.getScmMetadirPath(conf);

    // Write the container name to pipeline mapping.
    File containerDBPath = new File(metaDir, CONTAINER_DB);
    containerStore = MetadataStoreBuilder.newBuilder()
        .setConf(conf)
        .setDbFile(containerDBPath)
        .setCacheSize(this.cacheSize * OzoneConsts.MB)
        .build();

    this.lock = new ReentrantLock();
    this.pipelineSelector = new PipelineSelector(nodeManager, conf);
  }



  /**
   * Returns the Pipeline from the container name.
   *
   * @param containerName - Name
   * @return - Pipeline that makes up this container.
   */
  @Override
  public Pipeline getContainer(final String containerName) throws IOException {
    Pipeline pipeline;
    lock.lock();
    try {
      byte[] pipelineBytes =
          containerStore.get(containerName.getBytes(encoding));
      if (pipelineBytes == null) {
        throw new SCMException("Specified key does not exist. key : " +
            containerName, SCMException.ResultCodes.FAILED_TO_FIND_CONTAINER);
      }
      pipeline = Pipeline.getFromProtoBuf(
          OzoneProtos.Pipeline.PARSER.parseFrom(pipelineBytes));
      return pipeline;
    } finally {
      lock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<Pipeline> listContainer(String startName,
      String prefixName, int count)
      throws IOException {
    List<Pipeline> pipelineList = new ArrayList<>();
    lock.lock();
    try {
      if (containerStore.isEmpty()) {
        throw new IOException("No container exists in current db");
      }
      MetadataKeyFilter prefixFilter = new KeyPrefixFilter(prefixName);
      byte[] startKey = startName == null ?
          null : DFSUtil.string2Bytes(startName);
      List<Map.Entry<byte[], byte[]>> range =
          containerStore.getRangeKVs(startKey, count, prefixFilter);

      // Transform the values into the pipelines.
      for (Map.Entry<byte[], byte[]> entry : range) {
        Pipeline pipeline = Pipeline.getFromProtoBuf(
            OzoneProtos.Pipeline.PARSER.parseFrom(entry.getValue()));
        pipelineList.add(pipeline);
      }
    } finally {
      lock.unlock();
    }
    return pipelineList;
  }

  /**
   * Allocates a new container.
   *
   * @param containerName - Name of the container.
   * @param replicationFactor - replication factor of the container.
   * @return - Pipeline that makes up this container.
   * @throws IOException - Exception
   */
  @Override
  public Pipeline allocateContainer(OzoneProtos.ReplicationType type,
      OzoneProtos.ReplicationFactor replicationFactor,
      final String containerName) throws IOException {
    Preconditions.checkNotNull(containerName);
    Preconditions.checkState(!containerName.isEmpty());
    Pipeline pipeline = null;
    if (!nodeManager.isOutOfNodeChillMode()) {
      throw new SCMException("Unable to create container while in chill mode",
          SCMException.ResultCodes.CHILL_MODE_EXCEPTION);
    }

    lock.lock();
    try {
      byte[] pipelineBytes =
          containerStore.get(containerName.getBytes(encoding));
      if (pipelineBytes != null) {
        throw new SCMException("Specified container already exists. key : " +
            containerName, SCMException.ResultCodes.CONTAINER_EXISTS);
      }
      pipeline = pipelineSelector.getReplicationPipeline(type,
          replicationFactor, containerName);
      containerStore.put(containerName.getBytes(encoding),
          pipeline.getProtobufMessage().toByteArray());
    } finally {
      lock.unlock();
    }
    return pipeline;
  }

  /**
   * Deletes a container from SCM.
   *
   * @param containerName - Container name
   * @throws IOException if container doesn't exist or container store failed to
   *                     delete the specified key.
   */
  @Override
  public void deleteContainer(String containerName) throws IOException {
    lock.lock();
    try {
      byte[] dbKey = containerName.getBytes(encoding);
      byte[] pipelineBytes =
          containerStore.get(dbKey);
      if (pipelineBytes == null) {
        throw new SCMException("Failed to delete container "
            + containerName + ", reason : container doesn't exist.",
            SCMException.ResultCodes.FAILED_TO_FIND_CONTAINER);
      }
      containerStore.delete(dbKey);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes this stream and releases any system resources associated with it. If
   * the stream is already closed then invoking this method has no effect.
   * <p>
   * <p> As noted in {@link AutoCloseable#close()}, cases where the close may
   * fail require careful attention. It is strongly advised to relinquish the
   * underlying resources and to internally <em>mark</em> the {@code Closeable}
   * as closed, prior to throwing the {@code IOException}.
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    if (containerStore != null) {
      containerStore.close();
    }
  }
}
