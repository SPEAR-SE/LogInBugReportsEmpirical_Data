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
package org.apache.hadoop.hdfs.server.datanode;

import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.protocol.ReplicaRecoveryInfo;

/**
 * This class is used for provided replicas that are finalized.
 */
public class FinalizedProvidedReplica extends ProvidedReplica {

  public FinalizedProvidedReplica(long blockId, URI fileURI,
      long fileOffset, long blockLen, long genStamp,
      FsVolumeSpi volume, Configuration conf, FileSystem remoteFS) {
    super(blockId, fileURI, fileOffset, blockLen, genStamp, volume, conf,
        remoteFS);
  }

  @Override
  public ReplicaState getState() {
    return ReplicaState.FINALIZED;
  }

  @Override
  public long getBytesOnDisk() {
    return getNumBytes();
  }

  @Override
  public long getVisibleLength() {
    return getNumBytes(); //all bytes are visible
  }

  @Override  // Object
  public boolean equals(Object o) {
    return super.equals(o);
  }

  @Override  // Object
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public String toString() {
    return super.toString();
  }

  @Override
  public ReplicaInfo getOriginalReplica() {
    throw new UnsupportedOperationException("Replica of type " + getState() +
        " does not support getOriginalReplica");
  }

  @Override
  public long getRecoveryID() {
    throw new UnsupportedOperationException("Replica of type " + getState() +
        " does not support getRecoveryID");
  }

  @Override
  public void setRecoveryID(long recoveryId) {
    throw new UnsupportedOperationException("Replica of type " + getState() +
        " does not support setRecoveryID");
  }

  @Override
  public ReplicaRecoveryInfo createInfo() {
    throw new UnsupportedOperationException("Replica of type " + getState() +
        " does not support createInfo");
  }
}
