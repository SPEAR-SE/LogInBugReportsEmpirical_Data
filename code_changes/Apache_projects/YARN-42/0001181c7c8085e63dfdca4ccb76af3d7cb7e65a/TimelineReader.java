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

package org.apache.hadoop.yarn.server.timelineservice.storage;

import java.io.IOException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.yarn.api.records.timelineservice.FlowActivityEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.FlowRunEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.server.timelineservice.reader.filter.TimelineFilterList;
import org.apache.hadoop.yarn.server.timelineservice.reader.filter.TimelinePrefixFilter;

/** ATSv2 reader interface. */
@Private
@Unstable
public interface TimelineReader extends Service {

  /**
   * Default limit for {@link #getEntities}.
   */
  long DEFAULT_LIMIT = 100;

  /**
   * Possible fields to retrieve for {@link #getEntities} and
   * {@link #getEntity}.
   */
  public enum Field {
    ALL,
    EVENTS,
    INFO,
    METRICS,
    CONFIGS,
    RELATES_TO,
    IS_RELATED_TO
  }

  /**
   * <p>The API to fetch the single entity given the entity identifier in the
   * scope of the given context.</p>
   *
   * @param userId
   *    Context user Id(optional).
   * @param clusterId
   *    Context cluster Id(mandatory).
   * @param flowName
   *    Context flow Id (optional).
   * @param flowRunId
   *    Context flow run Id (optional).
   * @param appId
   *    Context app Id (mandatory)
   * @param entityType
   *    Entity type (mandatory)
   * @param entityId
   *    Entity Id (mandatory)
   * @param confsToRetrieve
   *    Used for deciding which configs to return in response. This is
   *    represented as a {@link TimelineFilterList} object containing
   *    {@link TimelinePrefixFilter} objects. These can either be exact config
   *    keys' or prefixes which are then compared against config keys' to decide
   *    configs to return in response.
   * @param metricsToRetrieve
   *    Used for deciding which metrics to return in response. This is
   *    represented as a {@link TimelineFilterList} object containing
   *    {@link TimelinePrefixFilter} objects. These can either be exact metric
   *    ids' or prefixes which are then compared against metric ids' to decide
   *    metrics to return in response.
   * @param fieldsToRetrieve
   *    Specifies which fields of the entity object to retrieve(optional), see
   *    {@link Field}. If null, retrieves 4 fields namely entity id,
   *    entity type and entity created time. All fields will be returned if
   *    {@link Field#ALL} is specified.
   * @return a {@link TimelineEntity} instance or null. The entity will
   *    contain the metadata plus the given fields to retrieve.
   *    If entityType is YARN_FLOW_RUN, entity returned is of type
   *    {@link FlowRunEntity}.
   *    For all other entity types, entity returned is of type
   *    {@link TimelineEntity}.
   * @throws IOException
   */
  TimelineEntity getEntity(String userId, String clusterId, String flowName,
      Long flowRunId, String appId, String entityType, String entityId,
      TimelineFilterList confsToRetrieve, TimelineFilterList metricsToRetrieve,
      EnumSet<Field> fieldsToRetrieve) throws IOException;

  /**
   * <p>The API to search for a set of entities of the given the entity type in
   * the scope of the given context which matches the given predicates. The
   * predicates include the created time window, limit to number of entities to
   * be returned, and the entities can be filtered by checking whether they
   * contain the given info/configs entries in the form of key/value pairs,
   * given metrics in the form of metricsIds and its relation with metric
   * values, given events in the form of the Ids, and whether they relate to/are
   * related to other entities. For those parameters which have multiple
   * entries, the qualified entity needs to meet all or them.</p>
   *
   * @param userId
   *    Context user Id(optional).
   * @param clusterId
   *    Context cluster Id(mandatory).
   * @param flowName
   *    Context flow Id (optional).
   * @param flowRunId
   *    Context flow run Id (optional).
   * @param appId
   *    Context app Id (mandatory)
   * @param entityType
   *    Entity type (mandatory)
   * @param limit
   *    A limit on the number of entities to return (optional). If null or <=0,
   *    defaults to {@link #DEFAULT_LIMIT}.
   * @param createdTimeBegin
   *    Matched entities should not be created before this timestamp (optional).
   *    If null or <=0, defaults to 0.
   * @param createdTimeEnd
   *    Matched entities should not be created after this timestamp (optional).
   *    If null or <=0, defaults to {@link Long#MAX_VALUE}.
   * @param relatesTo
   *    Matched entities should relate to given entities (optional).
   * @param isRelatedTo
   *    Matched entities should be related to given entities (optional).
   * @param infoFilters
   *    Matched entities should have exact matches to the given info represented
   *    as key-value pairs (optional). If null or empty, the filter is not
   *    applied.
   * @param configFilters
   *    Matched entities should have exact matches to the given configs
   *    represented as key-value pairs (optional). If null or empty, the filter
   *    is not applied.
   * @param metricFilters
   *    Matched entities should contain the given metrics (optional). If null
   *    or empty, the filter is not applied.
   * @param eventFilters
   *    Matched entities should contain the given events (optional). If null
   *    or empty, the filter is not applied.
   * @param confsToRetrieve
   *    Used for deciding which configs to return in response. This is
   *    represented as a {@link TimelineFilterList} object containing
   *    {@link TimelinePrefixFilter} objects. These can either be exact config
   *    keys' or prefixes which are then compared against config keys' to decide
   *    configs(inside entities) to return in response. This should not be
   *    confused with configFilters which is used to decide which entities to
   *    return instead.
   * @param metricsToRetrieve
   *    Used for deciding which metrics to return in response. This is
   *    represented as a {@link TimelineFilterList} object containing
   *    {@link TimelinePrefixFilter} objects. These can either be exact metric
   *    ids' or prefixes which are then compared against metric ids' to decide
   *    metrics(inside entities) to return in response. This should not be
   *    confused with metricFilters which is used to decide which entities to
   *    return instead.
   * @param fieldsToRetrieve
   *    Specifies which fields of the entity object to retrieve(optional), see
   *    {@link Field}. If null, retrieves 4 fields namely entity id,
   *    entity type and entity created time. All fields will be returned if
   *    {@link Field#ALL} is specified.
   * @return A set of {@link TimelineEntity} instances of the given entity type
   *    in the given context scope which matches the given predicates
   *    ordered by created time, descending. Each entity will only contain the
   *    metadata(id, type and created time) plus the given fields to retrieve.
   *    If entityType is YARN_FLOW_ACTIVITY, entities returned are of type
   *    {@link FlowActivityEntity}.
   *    If entityType is YARN_FLOW_RUN, entities returned are of type
   *    {@link FlowRunEntity}.
   *    For all other entity types, entities returned are of type
   *    {@link TimelineEntity}.
   * @throws IOException
   */
  Set<TimelineEntity> getEntities(String userId, String clusterId,
      String flowName, Long flowRunId, String appId, String entityType,
      Long limit, Long createdTimeBegin, Long createdTimeEnd,
      Map<String, Set<String>> relatesTo, Map<String, Set<String>> isRelatedTo,
      Map<String, Object> infoFilters, Map<String, String> configFilters,
      Set<String>  metricFilters, Set<String> eventFilters,
      TimelineFilterList confsToRetrieve, TimelineFilterList metricsToRetrieve,
      EnumSet<Field> fieldsToRetrieve) throws IOException;
}