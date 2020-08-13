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

package org.apache.hadoop.mapred;

import java.io.IOException;

import java.io.*;
import java.net.*;

import org.apache.hadoop.fs.InMemoryFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.ReduceTask.ReduceCopier.ShuffleClientMetrics;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.conf.*;

/** The location of a map output file, as passed to a reduce task via the
 * {@link InterTrackerProtocol}. */ 
class MapOutputLocation implements Writable, MRConstants {

  static {                                      // register a ctor
    WritableFactories.setFactory
      (MapOutputLocation.class,
       new WritableFactory() {
         public Writable newInstance() { return new MapOutputLocation(); }
       });
  }

  private String mapTaskId;
  private int mapId;
  private String host;
  private int port;
  private String jobId;

  /** RPC constructor **/
  public MapOutputLocation() {
  }

  /** Construct a location. */
  public MapOutputLocation(String jobId, String mapTaskId, int mapId, 
                           String host, int port) {
    this.jobId = jobId;
    this.mapTaskId = mapTaskId;
    this.mapId = mapId;
    this.host = host;
    this.port = port;
  }

  /** The map task id. */
  public String getMapTaskId() { return mapTaskId; }
  
  /**
   * Get the map's id number.
   * @return The numeric id for this map
   */
  public int getMapId() {
    return mapId;
  }

  /** The host the task completed on. */
  public String getHost() { return host; }

  /** The port listening for {@link MapOutputProtocol} connections. */
  public int getPort() { return port; }

  public void write(DataOutput out) throws IOException {
    out.writeUTF(jobId);
    out.writeUTF(mapTaskId);
    out.writeInt(mapId);
    out.writeUTF(host);
    out.writeInt(port);
  }

  public void readFields(DataInput in) throws IOException {
    this.jobId = in.readUTF();
    this.mapTaskId = in.readUTF();
    this.mapId = in.readInt();
    this.host = in.readUTF();
    this.port = in.readInt();
  }

  public String toString() {
    return "http://" + host + ":" + port + "/mapOutput?job=" + jobId +
           "&map=" + mapTaskId;
  }
  
  /**
   * Get the map output into a local file (either in the inmemory fs or on the 
   * local fs) from the remote server.
   * We use the file system so that we generate checksum files on the data.
   * @param inMemFileSys the inmemory filesystem to write the file to
   * @param localFileSys the local filesystem to write the file to
   * @param shuffleMetrics the metrics context
   * @param localFilename the filename to write the data into
   * @param lDirAlloc the LocalDirAllocator object
   * @param conf the Configuration object
   * @param reduce the reduce id to get for
   * @param timeout number of ms for connection and read timeout
   * @return the path of the file that got created
   * @throws IOException when something goes wrong
   */
  public Path getFile(InMemoryFileSystem inMemFileSys,
                      FileSystem localFileSys,
                      ShuffleClientMetrics shuffleMetrics,
                      Path localFilename, 
                      LocalDirAllocator lDirAlloc,
                      Configuration conf, int reduce,
                      int timeout, Progressable progressable) 
  throws IOException, InterruptedException {
    boolean good = false;
    long totalBytes = 0;
    FileSystem fileSys = localFileSys;
    Thread currentThread = Thread.currentThread();
    URL path = new URL(toString() + "&reduce=" + reduce);
    try {
      URLConnection connection = path.openConnection();
      if (timeout > 0) {
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
      }
      InputStream input = connection.getInputStream();
      OutputStream output = null;
      
      //We will put a file in memory if it meets certain criteria:
      //1. The size of the file should be less than 25% of the total inmem fs
      //2. There is space available in the inmem fs
      
      long length = Long.parseLong(connection.getHeaderField(MAP_OUTPUT_LENGTH));
      long inMemFSSize = inMemFileSys.getFSSize();
      long checksumLength = (int)inMemFileSys.getChecksumFileLength(
                                                  localFilename, length);
      
      boolean createInMem = false; 
      if (inMemFSSize > 0)  
        createInMem = (((float)(length + checksumLength) / inMemFSSize <= 
                        MAX_INMEM_FILESIZE_FRACTION) && 
                       inMemFileSys.reserveSpaceWithCheckSum(localFilename, length));
      if (createInMem) {
        fileSys = inMemFileSys;
      }
      else {
        //now hit the localFS to find out a suitable location for the output
        localFilename = lDirAlloc.getLocalPathForWrite(
            localFilename.toUri().getPath(), length + checksumLength, conf);
      }
      
      output = fileSys.create(localFilename);
      try {  
        try {
          byte[] buffer = new byte[64 * 1024];
          if (currentThread.isInterrupted()) {
            throw new InterruptedException();
          }
          int len = input.read(buffer);
          while (len > 0) {
            totalBytes += len;
            shuffleMetrics.inputBytes(len);
            output.write(buffer, 0 , len);
            if (currentThread.isInterrupted()) {
              throw new InterruptedException();
            }
            // indicate we're making progress
            progressable.progress();
            len = input.read(buffer);
          }
        } finally {
          output.close();
        }
      } finally {
        input.close();
      }
      good = (totalBytes == length);
      if (!good) {
        throw new IOException("Incomplete map output received for " + path +
                              " (" + totalBytes + " instead of " + length + ")"
                              );
      }
    } finally {
      if (!good) {
        try {
          fileSys.delete(localFilename, true);
          totalBytes = 0;
        } catch (Throwable th) {
          // IGNORED because we are cleaning up
        }
      }
    }
    return fileSys.makeQualified(localFilename);
  }

}
