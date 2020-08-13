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
package org.apache.hadoop.hdfs.server.namenode.sps;

import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.StoragePolicySatisfierMode;
import org.apache.hadoop.hdfs.server.protocol.BlocksStorageMoveAttemptFinished;

/**
 * An interface for SPSService, which exposes life cycle and processing APIs.
 *
 * @param <T>
 *          is identifier of inode or full path name of inode. Internal sps will
 *          use the file inodeId for the block movement. External sps will use
 *          file string path representation for the block movement.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public interface SPSService<T> {

  /**
   * Initializes the helper services.
   *
   * @param ctxt
   *          - context is an helper service to provide communication channel
   *          between NN and SPS
   * @param fileCollector
   *          - a helper service for scanning the files under a given directory
   *          id
   * @param handler
   *          - a helper service for moving the blocks
   * @param blkMovementListener
   *          - listener to know about block movement attempt completion
   */
  void init(Context<T> ctxt, FileCollector<T> fileCollector,
      BlockMoveTaskHandler handler, BlockMovementListener blkMovementListener);

  /**
   * Starts the SPS service. Make sure to initialize the helper services before
   * invoking this method.
   *
   * @param reconfigStart
   *          - to indicate whether the SPS startup requested from
   *          reconfiguration service
   * @param spsMode sps service mode
   */
  void start(boolean reconfigStart, StoragePolicySatisfierMode spsMode);

  /**
   * Stops the SPS service gracefully. Timed wait to stop storage policy
   * satisfier daemon threads.
   */
  void stopGracefully();

  /**
   * Stops the SPS service.
   *
   * @param forceStop
   *          true represents to clear all the sps path's hint, false otherwise.
   */
  void stop(boolean forceStop);

  /**
   * Check whether StoragePolicySatisfier is running.
   *
   * @return true if running
   */
  boolean isRunning();

  /**
   * Adds the Item information(file etc) to processing queue.
   *
   * @param itemInfo
   *          file info object for which need to satisfy the policy
   */
  void addFileToProcess(ItemInfo<T> itemInfo, boolean scanCompleted);

  /**
   * Adds all the Item information(file etc) to processing queue.
   *
   * @param startPath
   *          - directory/file, on which SPS was called.
   * @param itemInfoList
   *          - list of item infos
   * @param scanCompleted
   *          - whether the scanning of directory fully done with itemInfoList
   */
  void addAllFilesToProcess(T startPath, List<ItemInfo<T>> itemInfoList,
      boolean scanCompleted);

  /**
   * @return current processing queue size.
   */
  int processingQueueSize();

  /**
   * Clear inodeId present in the processing queue.
   */
  void clearQueue(T spsPath);

  /**
   * @return the configuration.
   */
  Configuration getConf();

  /**
   * Marks the scanning of directory if finished.
   *
   * @param spsPath
   *          - satisfier path
   */
  void markScanCompletedForPath(T spsPath);

  /**
   * Notify the details of storage movement attempt finished blocks.
   *
   * @param moveAttemptFinishedBlks
   *          - array contains all the blocks that are attempted to move
   */
  void notifyStorageMovementAttemptFinishedBlks(
      BlocksStorageMoveAttemptFinished moveAttemptFinishedBlks);
}
