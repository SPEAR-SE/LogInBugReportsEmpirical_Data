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

package org.apache.hadoop.yarn.server.timelineservice.reader;

import org.apache.hadoop.yarn.server.timelineservice.TimelineContext;

/**
 * Encapsulates fields necessary to make a query in timeline reader.
 */
public class TimelineReaderContext extends TimelineContext {

  private String entityType;
  private String entityId;
  public TimelineReaderContext(String clusterId, String userId, String flowName,
      Long flowRunId, String appId, String entityType, String entityId) {
    super(clusterId, userId, flowName, flowRunId, appId);
    this.entityType = entityType;
    this.entityId = entityId;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + ((entityId == null) ? 0 : entityId.hashCode());
    result =
        prime * result + ((entityType == null) ? 0 : entityType.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!super.equals(obj)) {
      return false;
    }
    TimelineReaderContext other = (TimelineReaderContext) obj;
    if (entityId == null) {
      if (other.entityId != null) {
        return false;
      }
    } else if (!entityId.equals(other.entityId)) {
      return false;
    }
    if (entityType == null) {
      if (other.entityType != null) {
        return false;
      }
    } else if (!entityType.equals(other.entityType)) {
      return false;
    }
    return true;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String type) {
    this.entityType = type;
  }

  public String getEntityId() {
    return entityId;
  }

  public void setEntityId(String id) {
    this.entityId = id;
  }
}