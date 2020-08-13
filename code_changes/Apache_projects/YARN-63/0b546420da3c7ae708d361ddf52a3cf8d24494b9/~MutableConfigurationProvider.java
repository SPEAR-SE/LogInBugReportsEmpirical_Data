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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.webapp.dao.SchedConfUpdateInfo;

import java.io.IOException;

/**
 * Interface for allowing changing scheduler configurations.
 */
public interface MutableConfigurationProvider {

  /**
   * Apply transactions which were not committed.
   * @throws IOException if recovery fails
   */
  void recoverConf() throws IOException;

  /**
   * Update the scheduler configuration with the provided key value pairs.
   * @param user User issuing the request
   * @param confUpdate Key-value pairs for configurations to be updated.
   * @throws IOException if scheduler could not be reinitialized
   */
  void mutateConfiguration(UserGroupInformation user, SchedConfUpdateInfo
      confUpdate) throws IOException;

}
