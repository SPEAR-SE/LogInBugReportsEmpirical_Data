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
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import org.apache.hadoop.yarn.api.records.Resource;

class CSQueueUtils {
  
  public static void checkMaxCapacity(String queueName, 
      float capacity, float maximumCapacity) {
    if (maximumCapacity < 0.0f || maximumCapacity > 1.0f || 
        maximumCapacity < capacity) {
      throw new IllegalArgumentException(
          "Illegal value  of maximumCapacity " + maximumCapacity + 
          " used in call to setMaxCapacity for queue " + queueName);
    }
    if (maximumCapacity < capacity) {
      throw new IllegalArgumentException(
          "Illegal call to setMaxCapacity. " +
          "Queue '" + queueName + "' has " +
          "capacity (" + capacity + ") greater than " + 
          "maximumCapacity (" + maximumCapacity + ")" );
    }
  }

  public static float computeAbsoluteMaximumCapacity(
      float maximumCapacity, CSQueue parent) {
    float parentAbsMaxCapacity = 
        (parent == null) ? 1.0f : parent.getAbsoluteMaximumCapacity();
    return (parentAbsMaxCapacity * maximumCapacity);
  }

  public static int computeMaxActiveApplications(Resource clusterResource,
      float maxAMResourcePercent, float absoluteCapacity) {
    return 
        Math.max(
            (int)((clusterResource.getMemory() / (float)LeafQueue.DEFAULT_AM_RESOURCE) * 
                   maxAMResourcePercent * absoluteCapacity), 
            1);
  }

  public static int computeMaxActiveApplicationsPerUser(
      int maxActiveApplications, int userLimit, float userLimitFactor) {
    return (int)(maxActiveApplications * (userLimit / 100.0f) * userLimitFactor);
  }
  
}
