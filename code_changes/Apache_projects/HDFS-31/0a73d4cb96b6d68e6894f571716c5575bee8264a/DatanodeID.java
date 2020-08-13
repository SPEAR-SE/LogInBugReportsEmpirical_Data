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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;

/**
 * This class represents the primary identifier for a Datanode.
 * Datanodes are identified by how they can be contacted (hostname
 * and ports) and their storage ID, a unique number that associates
 * the Datanodes blocks with a particular Datanode.
 *
 * {@link DatanodeInfo#getName()} should be used to get the network
 * location (for topology) of a datanode, instead of using
 * {@link DatanodeID#getXferAddr()} here. Helpers are defined below
 * for each context in which a DatanodeID is used.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class DatanodeID implements WritableComparable<DatanodeID> {
  public static final DatanodeID[] EMPTY_ARRAY = {}; 

  protected String ipAddr;     // IP address
  protected String hostName;   // hostname
  protected String storageID;  // unique per cluster storageID
  protected int xferPort;      // data streaming port
  protected int infoPort;      // info server port
  protected int ipcPort;       // IPC server port

  /** Equivalent to DatanodeID(""). */
  public DatanodeID() {this("");}

  /** Equivalent to DatanodeID(ipAddr, "", -1, -1, -1). */
  public DatanodeID(String ipAddr) {this(ipAddr, "", "", -1, -1, -1);}

  /** Equivalent to DatanodeID(ipAddr, "", xferPort, -1, -1). */
  public DatanodeID(String ipAddr, int xferPort) {this(ipAddr, "", "", xferPort, -1, -1);}

  /**
   * DatanodeID copy constructor
   * 
   * @param from
   */
  public DatanodeID(DatanodeID from) {
    this(from.getIpAddr(),
        from.getHostName(),
        from.getStorageID(),
        from.getXferPort(),
        from.getInfoPort(),
        from.getIpcPort());
  }
  
  /**
   * Create DatanodeID
   * @param ipAddr IP
   * @param hostName hostname
   * @param storageID data storage ID
   * @param xferPort data transfer port
   * @param infoPort info server port 
   * @param ipcPort ipc server port
   */
  public DatanodeID(String ipAddr, String hostName, String storageID,
      int xferPort, int infoPort, int ipcPort) {
    this.ipAddr = ipAddr;
    this.hostName = hostName;
    this.storageID = storageID;
    this.xferPort = xferPort;
    this.infoPort = infoPort;
    this.ipcPort = ipcPort;
  }
  
  public void setIpAddr(String ipAddr) {
    this.ipAddr = ipAddr;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }

  public void setXferPort(int xferPort) {
    this.xferPort = xferPort;
  }

  public void setInfoPort(int infoPort) {
    this.infoPort = infoPort;
  }
  
  public void setIpcPort(int ipcPort) {
    this.ipcPort = ipcPort;
  }

  public void setStorageID(String storageID) {
    this.storageID = storageID;
  }

  /**
   * @return ipAddr;
   */
  public String getIpAddr() {
    return ipAddr;
  }

  /**
   * @return hostname
   */
  public String getHostName() {
    return hostName;
  }

  /**
   * @return IP:xferPort string
   */
  public String getXferAddr() {
    return ipAddr + ":" + xferPort;
  }

  /**
   * @return IP:ipcPort string
   */
  public String getIpcAddr() {
    return ipAddr + ":" + ipcPort;
  }

  /**
   * @return IP:infoPort string
   */
  public String getInfoAddr() {
    return ipAddr + ":" + infoPort;
  }

  /**
   * @return hostname:xferPort
   */
  public String getXferAddrWithHostname() {
    return hostName + ":" + xferPort;
  }

  /**
   * @return data storage ID.
   */
  public String getStorageID() {
    return storageID;
  }

  /**
   * @return xferPort (the port for data streaming)
   */
  public int getXferPort() {
    return xferPort;
  }

  /**
   * @return infoPort (the port at which the HTTP server bound to)
   */
  public int getInfoPort() {
    return infoPort;
  }

  /**
   * @return ipcPort (the port at which the IPC server bound to)
   */
  public int getIpcPort() {
    return ipcPort;
  }

  public boolean equals(Object to) {
    if (this == to) {
      return true;
    }
    if (!(to instanceof DatanodeID)) {
      return false;
    }
    return (getXferAddr().equals(((DatanodeID)to).getXferAddr()) &&
            storageID.equals(((DatanodeID)to).getStorageID()));
  }
  
  public int hashCode() {
    return getXferAddr().hashCode()^ storageID.hashCode();
  }
  
  public String toString() {
    return getXferAddr();
  }
  
  /**
   * Update fields when a new registration request comes in.
   * Note that this does not update storageID.
   */
  public void updateRegInfo(DatanodeID nodeReg) {
    ipAddr = nodeReg.getIpAddr();
    hostName = nodeReg.getHostName();
    xferPort = nodeReg.getXferPort();
    infoPort = nodeReg.getInfoPort();
    ipcPort = nodeReg.getIpcPort();
  }
    
  /**
   * Compare based on data transfer address.
   *
   * @param that
   * @return as specified by Comparable
   */
  public int compareTo(DatanodeID that) {
    return getXferAddr().compareTo(that.getXferAddr());
  }

  @Override
  public void write(DataOutput out) throws IOException {
    Text.writeString(out, ipAddr);
    Text.writeString(out, hostName);
    Text.writeString(out, storageID);
    out.writeShort(xferPort);
    out.writeShort(infoPort);
    out.writeShort(ipcPort);
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    ipAddr = Text.readString(in);
    hostName = Text.readString(in);
    storageID = Text.readString(in);
    // The port read could be negative, if the port is a large number (more
    // than 15 bits in storage size (but less than 16 bits).
    // So chop off the first two bytes (and hence the signed bits) before 
    // setting the field.
    xferPort = in.readShort() & 0x0000ffff;
    infoPort = in.readShort() & 0x0000ffff;
    ipcPort = in.readShort() & 0x0000ffff;
  }
}
