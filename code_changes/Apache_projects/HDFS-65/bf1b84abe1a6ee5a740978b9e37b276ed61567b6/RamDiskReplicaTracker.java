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

import com.google.common.base.Preconditions;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.File;

@InterfaceAudience.Private
@InterfaceStability.Unstable
public abstract class RamDiskReplicaTracker {

  FsDatasetImpl fsDataset;

  static class RamDiskReplica implements Comparable<RamDiskReplica>  {
    private final String bpid;
    private final long blockId;
    private File savedBlockFile;
    private File savedMetaFile;

    /**
     * RAM_DISK volume that holds the original replica.
     */
    final FsVolumeSpi ramDiskVolume;

    /**
     * Persistent volume that holds or will hold the saved replica.
     */
    FsVolumeImpl lazyPersistVolume;

    RamDiskReplica(final String bpid, final long blockId,
                   final FsVolumeImpl ramDiskVolume) {
      this.bpid = bpid;
      this.blockId = blockId;
      this.ramDiskVolume = ramDiskVolume;
      lazyPersistVolume = null;
      savedMetaFile = null;
      savedBlockFile = null;
    }

    long getBlockId() {
      return blockId;
    }

    String getBlockPoolId() {
      return bpid;
    }

    FsVolumeImpl getLazyPersistVolume() {
      return lazyPersistVolume;
    }

    void setLazyPersistVolume(FsVolumeImpl volume) {
      Preconditions.checkState(!volume.isTransientStorage());
      this.lazyPersistVolume = volume;
    }

    File getSavedBlockFile() {
      return savedBlockFile;
    }

    File getSavedMetaFile() {
      return savedMetaFile;
    }

    /**
     * Record the saved meta and block files on the given volume.
     *
     * @param files Meta and block files, in that order.
     */
    void recordSavedBlockFiles(File[] files) {
      this.savedMetaFile = files[0];
      this.savedBlockFile = files[1];
    }

    @Override
    public int hashCode() {
      return bpid.hashCode() ^ (int) blockId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (other == null || getClass() != other.getClass()) {
        return false;
      }

      RamDiskReplica otherState = (RamDiskReplica) other;
      return (otherState.bpid.equals(bpid) && otherState.blockId == blockId);
    }

    // Delete the saved meta and block files. Failure to delete can be
    // ignored, the directory scanner will retry the deletion later.
    void deleteSavedFiles() {
      try {
        if (savedBlockFile != null) {
          savedBlockFile.delete();
          savedBlockFile = null;
        }

        if (savedMetaFile != null) {
          savedMetaFile.delete();
          savedMetaFile = null;
        }
      } catch (Throwable t) {
        // Ignore any exceptions.
      }
    }

    @Override
    public int compareTo(RamDiskReplica other) {
      int bpidResult = bpid.compareTo(other.bpid);
      if (bpidResult == 0)
        if (blockId == other.blockId) {
          return 0;
        } else if (blockId < other.blockId) {
          return -1;
        } else {
          return 1;
        }
      return bpidResult;
    }

    @Override
    public String toString() {
      return "[BlockPoolID=" + bpid + "; BlockId=" + blockId + "]";
    }
  }

  /**
   * Get an instance of the configured RamDiskReplicaTracker based on the
   * the configuration property
   * {@link org.apache.hadoop.hdfs.DFSConfigKeys#DFS_DATANODE_RAM_DISK_REPLICA_TRACKER_KEY}.
   *
   * @param conf the configuration to be used
   * @param dataset the FsDataset object.
   * @return an instance of RamDiskReplicaTracker
   */
  static RamDiskReplicaTracker getInstance(final Configuration conf,
                                           final FsDatasetImpl fsDataset) {
    final Class<? extends RamDiskReplicaTracker> trackerClass = conf.getClass(
        DFSConfigKeys.DFS_DATANODE_RAM_DISK_REPLICA_TRACKER_KEY,
        DFSConfigKeys.DFS_DATANODE_RAM_DISK_REPLICA_TRACKER_DEFAULT,
        RamDiskReplicaTracker.class);
    final RamDiskReplicaTracker tracker = ReflectionUtils.newInstance(
        trackerClass, conf);
    tracker.initialize(fsDataset);
    return tracker;
  }

  void initialize(final FsDatasetImpl fsDataset) {
    this.fsDataset = fsDataset;
  }

  /**
   * Start tracking a new finalized replica on RAM disk.
   *
   * @param transientVolume RAM disk volume that stores the replica.
   */
  abstract void addReplica(final String bpid, final long blockId,
                           final FsVolumeImpl transientVolume);

  /**
   * Invoked when a replica is opened by a client. This may be used as
   * a heuristic by the eviction scheme.
   */
  abstract void touch(final String bpid, final long blockId);

  /**
   * Get the next replica to write to persistent storage.
   */
  abstract RamDiskReplica dequeueNextReplicaToPersist();

  /**
   * Invoked if a replica that was previously dequeued for persistence
   * could not be successfully persisted. Add it back so it can be retried
   * later.
   */
  abstract void reenqueueReplicaNotPersisted(
      final RamDiskReplica ramDiskReplica);

  /**
   * Invoked when the Lazy persist operation is started by the DataNode.
   * @param checkpointVolume
   */
  abstract void recordStartLazyPersist(
      final String bpid, final long blockId, FsVolumeImpl checkpointVolume);

  /**
   * Invoked when the Lazy persist operation is complete.
   *
   * @param savedFiles The saved meta and block files, in that order.
   */
  abstract void recordEndLazyPersist(
      final String bpid, final long blockId, final File[] savedFiles);

  /**
   * Return a candidate replica to remove from RAM Disk. The exact replica
   * to be returned may depend on the eviction scheme utilized.
   *
   * @return
   */
  abstract RamDiskReplica getNextCandidateForEviction();

  /**
   * Return the number of replicas pending persistence to disk.
   */
  abstract int numReplicasNotPersisted();

  /**
   * Discard all state we are tracking for the given replica.
   */
  abstract void discardReplica(
      final String bpid, final long blockId,
      boolean deleteSavedCopies);

  void discardReplica(RamDiskReplica replica, boolean deleteSavedCopies) {
    discardReplica(replica.getBlockPoolId(), replica.getBlockId(), deleteSavedCopies);
  }
}
