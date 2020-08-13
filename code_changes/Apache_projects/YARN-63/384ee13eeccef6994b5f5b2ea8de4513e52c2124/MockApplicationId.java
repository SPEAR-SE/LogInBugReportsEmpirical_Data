/*
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

package org.apache.slider.server.appmaster.model.mock;

import org.apache.hadoop.yarn.api.records.ApplicationId;

/**
 * Mock app id.
 */
public class MockApplicationId extends ApplicationId {

  private int id;
  private long clusterTimestamp;

  public MockApplicationId() {
  }

  public MockApplicationId(int id) {
    this.id = id;
  }

  public MockApplicationId(int id, long clusterTimestamp) {
    this.id = id;
    this.clusterTimestamp = clusterTimestamp;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void setId(int id) {
    this.id = id;
  }

  @Override
  public long getClusterTimestamp() {
    return clusterTimestamp;
  }

  @Override
  public void setClusterTimestamp(long clusterTimestamp) {
    this.clusterTimestamp = clusterTimestamp;
  }

  @Override
  public void build() {

  }
}
