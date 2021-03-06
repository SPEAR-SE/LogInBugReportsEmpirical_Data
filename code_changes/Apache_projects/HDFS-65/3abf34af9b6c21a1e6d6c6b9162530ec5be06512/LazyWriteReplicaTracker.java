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


import com.google.common.collect.TreeMultimap;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;

import java.io.File;
import java.util.*;

class LazyWriteReplicaTracker {

  enum State {
    IN_MEMORY,
    LAZY_PERSIST_IN_PROGRESS,
    LAZY_PERSIST_COMPLETE,
  }

  static class ReplicaState implements Comparable<ReplicaState> {

    final String bpid;
    final long blockId;
    State state;

    /**
     * transient storage volume that holds the original replica.
     */
    final FsVolumeSpi transientVolume;

    /**
     * Persistent volume that holds or will hold the saved replica.
     */
    FsVolumeImpl lazyPersistVolume;
    File savedMetaFile;
    File savedBlockFile;

    ReplicaState(final String bpid, final long blockId, FsVolumeSpi transientVolume) {
      this.bpid = bpid;
      this.blockId = blockId;
      this.transientVolume = transientVolume;
      state = State.IN_MEMORY;
      lazyPersistVolume = null;
      savedMetaFile = null;
      savedBlockFile = null;
    }

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
    public String toString() {
      return "[Bpid=" + bpid + ";blockId=" + blockId + "]";
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

      ReplicaState otherState = (ReplicaState) other;
      return (otherState.bpid.equals(bpid) && otherState.blockId == blockId);
    }

    @Override
    public int compareTo(ReplicaState other) {
      if (blockId == other.blockId) {
        return 0;
      } else if (blockId < other.blockId) {
        return -1;
      } else {
        return 1;
      }
    }
  }

  final FsDatasetImpl fsDataset;

  /**
   * Map of blockpool ID to map of blockID to ReplicaInfo.
   */
  final Map<String, Map<Long, ReplicaState>> replicaMaps;

  /**
   * Queue of replicas that need to be written to disk.
   * Stale entries are GC'd by dequeueNextReplicaToPersist.
   */
  final Queue<ReplicaState> replicasNotPersisted;

  /**
   * Queue of replicas in the order in which they were persisted.
   * We'll dequeue them in the same order.
   * We can improve the eviction scheme later.
   * Stale entries are GC'd by getNextCandidateForEviction.
   */
  final Queue<ReplicaState> replicasPersisted;

  LazyWriteReplicaTracker(final FsDatasetImpl fsDataset) {
    this.fsDataset = fsDataset;
    replicaMaps = new HashMap<String, Map<Long, ReplicaState>>();
    replicasNotPersisted = new LinkedList<ReplicaState>();
    replicasPersisted = new LinkedList<ReplicaState>();
  }

  synchronized void addReplica(String bpid, long blockId,
                               final FsVolumeSpi transientVolume) {
    Map<Long, ReplicaState> map = replicaMaps.get(bpid);
    if (map == null) {
      map = new HashMap<Long, ReplicaState>();
      replicaMaps.put(bpid, map);
    }
    ReplicaState replicaState = new ReplicaState(bpid, blockId, transientVolume);
    map.put(blockId, replicaState);
    replicasNotPersisted.add(replicaState);
  }

  synchronized void recordStartLazyPersist(
      final String bpid, final long blockId, FsVolumeImpl checkpointVolume) {
    Map<Long, ReplicaState> map = replicaMaps.get(bpid);
    ReplicaState replicaState = map.get(blockId);
    replicaState.state = State.LAZY_PERSIST_IN_PROGRESS;
    replicaState.lazyPersistVolume = checkpointVolume;
  }

  synchronized void recordEndLazyPersist(
      final String bpid, final long blockId,
      final File savedMetaFile, final File savedBlockFile) {
    Map<Long, ReplicaState> map = replicaMaps.get(bpid);
    ReplicaState replicaState = map.get(blockId);

    if (replicaState == null) {
      throw new IllegalStateException("Unknown replica bpid=" +
          bpid + "; blockId=" + blockId);
    }
    replicaState.state = State.LAZY_PERSIST_COMPLETE;
    replicaState.savedMetaFile = savedMetaFile;
    replicaState.savedBlockFile = savedBlockFile;

    if (replicasNotPersisted.peek() == replicaState) {
      // Common case.
      replicasNotPersisted.remove();
    } else {
      // Should never occur in practice as lazy writer always persists
      // the replica at the head of the queue before moving to the next
      // one.
      replicasNotPersisted.remove(replicaState);
    }

    replicasPersisted.add(replicaState);
  }

  synchronized ReplicaState dequeueNextReplicaToPersist() {
    while (replicasNotPersisted.size() != 0) {
      ReplicaState replicaState = replicasNotPersisted.remove();
      Map<Long, ReplicaState> replicaMap = replicaMaps.get(replicaState.bpid);

      if (replicaMap != null && replicaMap.get(replicaState.blockId) != null) {
        return replicaState;
      }

      // The replica no longer exists, look for the next one.
    }
    return null;
  }

  synchronized void reenqueueReplicaNotPersisted(final ReplicaState replicaState) {
    replicasNotPersisted.add(replicaState);
  }

  synchronized void reenqueueReplicaPersisted(final ReplicaState replicaState) {
    replicasPersisted.add(replicaState);
  }

  synchronized int numReplicasNotPersisted() {
    return replicasNotPersisted.size();
  }

  synchronized ReplicaState getNextCandidateForEviction() {
    while (replicasPersisted.size() != 0) {
      ReplicaState replicaState = replicasPersisted.remove();
      Map<Long, ReplicaState> replicaMap = replicaMaps.get(replicaState.bpid);

      if (replicaMap != null && replicaMap.get(replicaState.blockId) != null) {
        return replicaState;
      }

      // The replica no longer exists, look for the next one.
    }
    return null;
  }

  void discardReplica(ReplicaState replicaState, boolean deleteSavedCopies) {
    discardReplica(replicaState.bpid, replicaState.blockId, deleteSavedCopies);
  }

  /**
   * Discard any state we are tracking for the given replica. This could mean
   * the block is either deleted from the block space or the replica is no longer
   * on transient storage.
   *
   * @param deleteSavedCopies true if we should delete the saved copies on
   *                          persistent storage. This should be set by the
   *                          caller when the block is no longer needed.
   */
  synchronized void discardReplica(
      final String bpid, final long blockId,
      boolean deleteSavedCopies) {
    Map<Long, ReplicaState> map = replicaMaps.get(bpid);

    if (map == null) {
      return;
    }

    ReplicaState replicaState = map.get(blockId);

    if (replicaState == null) {
      return;
    }

    if (deleteSavedCopies) {
      replicaState.deleteSavedFiles();
    }
    map.remove(blockId);
  }
}
