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
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class DatanodeID implements WritableComparable<DatanodeID> {
  public static final DatanodeID[] EMPTY_ARRAY = {}; 

  protected String name;       // IP:port (data transfer port)
  protected String hostName;   // hostname
  protected String storageID;  // unique per cluster storageID
  protected int infoPort;      // info server port
  protected int ipcPort;       // IPC server port

  /** Equivalent to DatanodeID(""). */
  public DatanodeID() {this("");}

  /** Equivalent to DatanodeID(nodeName, "", -1, -1). */
  public DatanodeID(String nodeName) {this(nodeName, "", "", -1, -1);}

  /**
   * DatanodeID copy constructor
   * 
   * @param from
   */
  public DatanodeID(DatanodeID from) {
    this(from.getName(),
        from.getHostName(),
        from.getStorageID(),
        from.getInfoPort(),
        from.getIpcPort());
  }
  
  /**
   * Create DatanodeID
   * @param node IP:port
   * @param hostName hostname
   * @param storageID data storage ID
   * @param infoPort info server port 
   * @param ipcPort ipc server port
   */
  public DatanodeID(String name, String hostName,
      String storageID, int infoPort, int ipcPort) {
    this.name = name;
    this.hostName = hostName;
    this.storageID = storageID;
    this.infoPort = infoPort;
    this.ipcPort = ipcPort;
  }
  
  public void setName(String name) {
    this.name = name;
  }

  public void setHostName(String hostName) {
    this.hostName = hostName;
  }

  public void setInfoPort(int infoPort) {
    this.infoPort = infoPort;
  }
  
  public void setIpcPort(int ipcPort) {
    this.ipcPort = ipcPort;
  }
  
  /**
   * @return hostname:portNumber.
   */
  public String getName() {
    return name;
  }

  /**
   * @return hostname
   */
  public String getHostName() {
    return (hostName == null || hostName.length() == 0) ? getHost() : hostName;
  }

  /**
   * @return data storage ID.
   */
  public String getStorageID() {
    return this.storageID;
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

  /**
   * sets the data storage ID.
   */
  public void setStorageID(String storageID) {
    this.storageID = storageID;
  }

  /**
   * @return hostname and no :portNumber.
   */
  public String getHost() {
    int colon = name.indexOf(":");
    if (colon < 0) {
      return name;
    } else {
      return name.substring(0, colon);
    }
  }
  
  public int getPort() {
    int colon = name.indexOf(":");
    if (colon < 0) {
      return 50010; // default port.
    }
    return Integer.parseInt(name.substring(colon+1));
  }

  public boolean equals(Object to) {
    if (this == to) {
      return true;
    }
    if (!(to instanceof DatanodeID)) {
      return false;
    }
    return (name.equals(((DatanodeID)to).getName()) &&
            storageID.equals(((DatanodeID)to).getStorageID()));
  }
  
  public int hashCode() {
    return name.hashCode()^ storageID.hashCode();
  }
  
  public String toString() {
    return name;
  }
  
  /**
   * Update fields when a new registration request comes in.
   * Note that this does not update storageID.
   */
  public void updateRegInfo(DatanodeID nodeReg) {
    name = nodeReg.getName();
    hostName = nodeReg.getHostName();
    infoPort = nodeReg.getInfoPort();
    ipcPort = nodeReg.getIpcPort();
  }
    
  /** Comparable.
   * Basis of compare is the String name (host:portNumber) only.
   * @param that
   * @return as specified by Comparable.
   */
  public int compareTo(DatanodeID that) {
    return name.compareTo(that.getName());
  }

  /////////////////////////////////////////////////
  // Writable
  /////////////////////////////////////////////////
  @Override
  public void write(DataOutput out) throws IOException {
    Text.writeString(out, name);
    Text.writeString(out, hostName);
    Text.writeString(out, storageID);
    out.writeShort(infoPort);
    out.writeShort(ipcPort);
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    name = Text.readString(in);
    hostName = Text.readString(in);
    storageID = Text.readString(in);
    // The port read could be negative, if the port is a large number (more
    // than 15 bits in storage size (but less than 16 bits).
    // So chop off the first two bytes (and hence the signed bits) before 
    // setting the field.
    this.infoPort = in.readShort() & 0x0000ffff;
    this.ipcPort = in.readShort() & 0x0000ffff;
  }
}
