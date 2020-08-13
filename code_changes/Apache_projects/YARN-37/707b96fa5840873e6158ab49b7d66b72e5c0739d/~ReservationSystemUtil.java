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

package org.apache.hadoop.yarn.server.resourcemanager.reservation;

import org.apache.hadoop.yarn.api.records.ReservationRequest;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.util.resource.Resources;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple helper class for static methods used to transform across
 * common formats in tests
 */
public final class ReservationSystemUtil {

  private ReservationSystemUtil() {
    // not called
  }

  public static Resource toResource(ReservationRequest request) {
    Resource resource = Resources.multiply(request.getCapability(),
        (float) request.getNumContainers());
    return resource;
  }

  public static Map<ReservationInterval, Resource> toResources(
      Map<ReservationInterval, ReservationRequest> allocations) {
    Map<ReservationInterval, Resource> resources =
        new HashMap<ReservationInterval, Resource>();
    for (Map.Entry<ReservationInterval, ReservationRequest> entry :
        allocations.entrySet()) {
      resources.put(entry.getKey(),
          toResource(entry.getValue()));
    }
    return resources;
  }
}
