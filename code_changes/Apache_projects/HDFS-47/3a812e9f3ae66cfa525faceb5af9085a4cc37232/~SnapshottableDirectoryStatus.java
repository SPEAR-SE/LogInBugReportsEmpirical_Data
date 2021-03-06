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
package org.apache.hadoop.hdfs.protocol;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSUtil;

/**
 * Metadata about a snapshottable directory
 */
public class SnapshottableDirectoryStatus
    implements Comparable<SnapshottableDirectoryStatus> {
  /** Basic information of the snapshottable directory */
  private HdfsFileStatus dirStatus;
  
  /** Number of snapshots that have been taken*/
  private int snapshotNumber;
  
  /** Number of snapshots allowed. */
  private int snapshotQuota;
  
  /** Full path of the parent. */
  private byte[] parentFullPath;
  
  public SnapshottableDirectoryStatus(long modification_time, long access_time,
      FsPermission permission, String owner, String group, byte[] localName,
      long inodeId,
      int snapshotNumber, int snapshotQuota, byte[] parentFullPath) {
    this.dirStatus = new HdfsFileStatus(0, true, 0, 0, modification_time,
        access_time, permission, owner, group, null, localName, inodeId);
    this.snapshotNumber = snapshotNumber;
    this.snapshotQuota = snapshotQuota;
    this.parentFullPath = parentFullPath;
  }

  /**
   * @return Number of snapshots that have been taken for the directory
   */
  public int getSnapshotNumber() {
    return snapshotNumber;
  }

  /**
   * @return Number of snapshots allowed for the directory
   */
  public int getSnapshotQuota() {
    return snapshotQuota;
  }
  
  /**
   * @return Full path of the parent
   */
  public byte[] getParentFullPath() {
    return parentFullPath;
  }

  /**
   * @return The basic information of the directory
   */
  public HdfsFileStatus getDirStatus() {
    return dirStatus;
  }
  
  /**
   * @return Full path of the file
   */
  public Path getFullPath() {
    String parentFullPathStr = (parentFullPath == null || parentFullPath.length == 0) ? null
        : DFSUtil.bytes2String(parentFullPath);
    return parentFullPathStr == null ? new Path(dirStatus.getLocalName())
        : new Path(parentFullPathStr, dirStatus.getLocalName());
  }
  
  /**
   * Print a list of {@link SnapshottableDirectoryStatus} out to a given stream.
   * @param stats The list of {@link SnapshottableDirectoryStatus}
   * @param out The given stream for printing.
   */
  public static void print(SnapshottableDirectoryStatus[] stats, 
      PrintStream out) {
    if (stats == null || stats.length == 0) {
      out.println();
      return;
    }
    int maxRepl = 0, maxLen = 0, maxOwner = 0, maxGroup = 0;
    int maxSnapshotNum = 0, maxSnapshotQuota = 0;
    for (SnapshottableDirectoryStatus status : stats) {
      maxRepl = maxLength(maxRepl, status.dirStatus.getReplication());
      maxLen = maxLength(maxLen, status.dirStatus.getLen());
      maxOwner = maxLength(maxOwner, status.dirStatus.getOwner());
      maxGroup = maxLength(maxGroup, status.dirStatus.getGroup());
      maxSnapshotNum = maxLength(maxSnapshotNum, status.snapshotNumber);
      maxSnapshotQuota = maxLength(maxSnapshotQuota, status.snapshotQuota);
    }
    
    StringBuilder fmt = new StringBuilder();
    fmt.append("%s%s "); // permission string
    fmt.append("%"  + maxRepl  + "s ");
    fmt.append((maxOwner > 0) ? "%-" + maxOwner + "s " : "%s");
    fmt.append((maxGroup > 0) ? "%-" + maxGroup + "s " : "%s");
    fmt.append("%"  + maxLen   + "s ");
    fmt.append("%s "); // mod time
    fmt.append("%"  + maxSnapshotNum  + "s ");
    fmt.append("%"  + maxSnapshotQuota  + "s ");
    fmt.append("%s"); // path
    
    String lineFormat = fmt.toString();
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
         
    for (SnapshottableDirectoryStatus status : stats) {
      String line = String.format(lineFormat, "d", 
          status.dirStatus.getPermission(),
          status.dirStatus.getReplication(),
          status.dirStatus.getOwner(),
          status.dirStatus.getGroup(),
          String.valueOf(status.dirStatus.getLen()),
          dateFormat.format(new Date(status.dirStatus.getModificationTime())),
          status.snapshotNumber, status.snapshotQuota, 
          status.getFullPath().toString()
      );
      out.println(line);
    }
  }

  private static int maxLength(int n, Object value) {
    return Math.max(n, String.valueOf(value).length());
  }

  @Override
  public int compareTo(SnapshottableDirectoryStatus that) {
    int d = DFSUtil.compareBytes(this.parentFullPath, that.parentFullPath);
    return d != 0? d
        : DFSUtil.compareBytes(this.dirStatus.getLocalNameInBytes(),
            that.dirStatus.getLocalNameInBytes());
  }
}
