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

package org.apache.hadoop.ozone.scm;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.ozone.scm.node.SCMNodeManager;

import java.util.Map;

/**
 *
 * This is the JMX management interface for scm information.
 */
@InterfaceAudience.Private
public interface SCMMXBean {

  /**
   * Get the number of data nodes that in all states,
   * valid states are defined by {@link SCMNodeManager.NODESTATE}.
   *
   * @return A state to number of nodes that in this state mapping
   */
  public Map<String, Integer> getNodeCount();

  /**
   * Get the SCM RPC server port that used to listen to datanode requests.
   * @return SCM datanode RPC server port
   */
  public String getDatanodeRpcPort();

  /**
   * Get the SCM RPC server port that used to listen to client requests.
   * @return SCM client RPC server port
   */
  public String getClientRpcPort();
}
