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
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;

/**
 * Represent an {@link INodeFile} that is snapshotted.
 * Note that snapshot files are represented by {@link INodeFileSnapshot}.
 */
@InterfaceAudience.Private
public class INodeFileWithLink extends INodeFile implements FileWithLink {
  private FileWithLink next;

  public INodeFileWithLink(INodeFile f) {
    super(f);
    setNext(this);
  }

  @Override
  public Pair<INodeFileWithLink, INodeFileSnapshot> createSnapshotCopy() {
    return new Pair<INodeFileWithLink, INodeFileSnapshot>(this,
        new INodeFileSnapshot(this));
  }

  @SuppressWarnings("unchecked")
  @Override
  public <N extends INodeFile & FileWithLink> N getNext() {
    return (N)next;
  }

  @Override
  public <N extends INodeFile & FileWithLink> void setNext(N next) {
    this.next = next;
  }

  @Override
  public <N extends INodeFile & FileWithLink> void insert(N inode) {
    inode.setNext(this.getNext());
    this.setNext(inode);
  }

  @Override
  public short getBlockReplication() {
    return Util.getBlockReplication(this);
  }

  @Override
  public int collectSubtreeBlocksAndClear(BlocksMapUpdateInfo info) {
    if (next == null || next == this) {
      // this is the only remaining inode.
      return super.collectSubtreeBlocksAndClear(info);
    } else {
      return Util.collectSubtreeBlocksAndClear(this, info);
    }
  }
}
