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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.server.timelineservice.storage.TimelineReader.Field;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.BaseTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityTable;

import com.google.common.base.Preconditions;

/**
 * Timeline entity reader for generic entities that are stored in the entity
 * table.
 */
class GenericEntityReader extends TimelineEntityReader {
  private static final EntityTable ENTITY_TABLE = new EntityTable();
  private static final Log LOG = LogFactory.getLog(GenericEntityReader.class);

  protected static final long DEFAULT_BEGIN_TIME = 0L;
  protected static final long DEFAULT_END_TIME = Long.MAX_VALUE;

  /**
   * Used to look up the flow context.
   */
  private final AppToFlowTable appToFlowTable = new AppToFlowTable();

  public GenericEntityReader(String userId, String clusterId,
      String flowId, Long flowRunId, String appId, String entityType,
      Long limit, Long createdTimeBegin, Long createdTimeEnd,
      Long modifiedTimeBegin, Long modifiedTimeEnd,
      Map<String, Set<String>> relatesTo, Map<String, Set<String>> isRelatedTo,
      Map<String, Object> infoFilters, Map<String, String> configFilters,
      Set<String> metricFilters, Set<String> eventFilters,
      EnumSet<Field> fieldsToRetrieve, boolean sortedKeys) {
    super(userId, clusterId, flowId, flowRunId, appId, entityType, limit,
        createdTimeBegin, createdTimeEnd, modifiedTimeBegin, modifiedTimeEnd,
        relatesTo, isRelatedTo, infoFilters, configFilters, metricFilters,
        eventFilters, fieldsToRetrieve, sortedKeys);
  }

  public GenericEntityReader(String userId, String clusterId,
      String flowId, Long flowRunId, String appId, String entityType,
      String entityId, EnumSet<Field> fieldsToRetrieve) {
    super(userId, clusterId, flowId, flowRunId, appId, entityType, entityId,
        fieldsToRetrieve);
  }

  /**
   * Uses the {@link EntityTable}.
   */
  protected BaseTable<?> getTable() {
    return ENTITY_TABLE;
  }

  protected FlowContext lookupFlowContext(String clusterId, String appId,
      Configuration hbaseConf, Connection conn) throws IOException {
    byte[] rowKey = AppToFlowRowKey.getRowKey(clusterId, appId);
    Get get = new Get(rowKey);
    Result result = appToFlowTable.getResult(hbaseConf, conn, get);
    if (result != null && !result.isEmpty()) {
      return new FlowContext(
          AppToFlowColumn.FLOW_ID.readResult(result).toString(),
          ((Number)AppToFlowColumn.FLOW_RUN_ID.readResult(result)).longValue());
    } else {
       throw new IOException(
           "Unable to find the context flow ID and flow run ID for clusterId=" +
           clusterId + ", appId=" + appId);
    }
  }

  protected static class FlowContext {
    protected final String flowId;
    protected final Long flowRunId;
    public FlowContext(String flowId, Long flowRunId) {
      this.flowId = flowId;
      this.flowRunId = flowRunId;
    }
  }

  @Override
  protected void validateParams() {
    Preconditions.checkNotNull(userId, "userId shouldn't be null");
    Preconditions.checkNotNull(clusterId, "clusterId shouldn't be null");
    Preconditions.checkNotNull(appId, "appId shouldn't be null");
    Preconditions.checkNotNull(entityType, "entityType shouldn't be null");
    if (singleEntityRead) {
      Preconditions.checkNotNull(entityId, "entityId shouldn't be null");
    }
  }

  @Override
  protected void augmentParams(Configuration hbaseConf, Connection conn)
      throws IOException {
    // In reality both should be null or neither should be null
    if (flowId == null || flowRunId == null) {
      FlowContext context =
          lookupFlowContext(clusterId, appId, hbaseConf, conn);
      flowId = context.flowId;
      flowRunId = context.flowRunId;
    }
    if (fieldsToRetrieve == null) {
      fieldsToRetrieve = EnumSet.noneOf(Field.class);
    }
    if (!singleEntityRead) {
      if (limit == null || limit < 0) {
        limit = TimelineReader.DEFAULT_LIMIT;
      }
      if (createdTimeBegin == null) {
        createdTimeBegin = DEFAULT_BEGIN_TIME;
      }
      if (createdTimeEnd == null) {
        createdTimeEnd = DEFAULT_END_TIME;
      }
      if (modifiedTimeBegin == null) {
        modifiedTimeBegin = DEFAULT_BEGIN_TIME;
      }
      if (modifiedTimeEnd == null) {
        modifiedTimeEnd = DEFAULT_END_TIME;
      }
    }
  }

  @Override
  protected Result getResult(Configuration hbaseConf, Connection conn)
      throws IOException {
    byte[] rowKey =
        EntityRowKey.getRowKey(clusterId, userId, flowId, flowRunId, appId,
            entityType, entityId);
    Get get = new Get(rowKey);
    get.setMaxVersions(Integer.MAX_VALUE);
    return table.getResult(hbaseConf, conn, get);
  }

  @Override
  protected ResultScanner getResults(Configuration hbaseConf,
      Connection conn) throws IOException {
    // Scan through part of the table to find the entities belong to one app
    // and one type
    Scan scan = new Scan();
    scan.setRowPrefixFilter(EntityRowKey.getRowKeyPrefix(
        clusterId, userId, flowId, flowRunId, appId, entityType));
    scan.setMaxVersions(Integer.MAX_VALUE);
    return table.getResultScanner(hbaseConf, conn, scan);
  }

  @Override
  protected TimelineEntity parseEntity(Result result) throws IOException {
    if (result == null || result.isEmpty()) {
      return null;
    }
    TimelineEntity entity = new TimelineEntity();
    String entityType = EntityColumn.TYPE.readResult(result).toString();
    entity.setType(entityType);
    String entityId = EntityColumn.ID.readResult(result).toString();
    entity.setId(entityId);

    // fetch created time
    Number createdTime = (Number)EntityColumn.CREATED_TIME.readResult(result);
    entity.setCreatedTime(createdTime.longValue());
    if (!singleEntityRead && (entity.getCreatedTime() < createdTimeBegin ||
        entity.getCreatedTime() > createdTimeEnd)) {
      return null;
    }

    // fetch modified time
    Number modifiedTime = (Number)EntityColumn.MODIFIED_TIME.readResult(result);
    entity.setModifiedTime(modifiedTime.longValue());
    if (!singleEntityRead && (entity.getModifiedTime() < modifiedTimeBegin ||
        entity.getModifiedTime() > modifiedTimeEnd)) {
      return null;
    }

    // fetch is related to entities
    boolean checkIsRelatedTo = isRelatedTo != null && isRelatedTo.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.IS_RELATED_TO) || checkIsRelatedTo) {
      readRelationship(entity, result, EntityColumnPrefix.IS_RELATED_TO, true);
      if (checkIsRelatedTo && !TimelineStorageUtils.matchRelations(
          entity.getIsRelatedToEntities(), isRelatedTo)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.IS_RELATED_TO)) {
        entity.getIsRelatedToEntities().clear();
      }
    }

    // fetch relates to entities
    boolean checkRelatesTo = relatesTo != null && relatesTo.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.RELATES_TO) || checkRelatesTo) {
      readRelationship(entity, result, EntityColumnPrefix.RELATES_TO, false);
      if (checkRelatesTo && !TimelineStorageUtils.matchRelations(
          entity.getRelatesToEntities(), relatesTo)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.RELATES_TO)) {
        entity.getRelatesToEntities().clear();
      }
    }

    // fetch info
    boolean checkInfo = infoFilters != null && infoFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.INFO) || checkInfo) {
      readKeyValuePairs(entity, result, EntityColumnPrefix.INFO, false);
      if (checkInfo &&
          !TimelineStorageUtils.matchFilters(entity.getInfo(), infoFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.INFO)) {
        entity.getInfo().clear();
      }
    }

    // fetch configs
    boolean checkConfigs = configFilters != null && configFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.CONFIGS) || checkConfigs) {
      readKeyValuePairs(entity, result, EntityColumnPrefix.CONFIG, true);
      if (checkConfigs && !TimelineStorageUtils.matchFilters(
          entity.getConfigs(), configFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.CONFIGS)) {
        entity.getConfigs().clear();
      }
    }

    // fetch events
    boolean checkEvents = eventFilters != null && eventFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.EVENTS) || checkEvents) {
      readEvents(entity, result, false);
      if (checkEvents && !TimelineStorageUtils.matchEventFilters(
          entity.getEvents(), eventFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.EVENTS)) {
        entity.getEvents().clear();
      }
    }

    // fetch metrics
    boolean checkMetrics = metricFilters != null && metricFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.METRICS) || checkMetrics) {
      readMetrics(entity, result, EntityColumnPrefix.METRIC);
      if (checkMetrics && !TimelineStorageUtils.matchMetricFilters(
          entity.getMetrics(), metricFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.METRICS)) {
        entity.getMetrics().clear();
      }
    }
    return entity;
  }

  /**
   * Helper method for reading relationship.
   */
  protected <T> void readRelationship(
      TimelineEntity entity, Result result, ColumnPrefix<T> prefix,
      boolean isRelatedTo) throws IOException {
    // isRelatedTo and relatesTo are of type Map<String, Set<String>>
    Map<String, Object> columns = prefix.readResults(result);
    for (Map.Entry<String, Object> column : columns.entrySet()) {
      for (String id : Separator.VALUES.splitEncoded(
          column.getValue().toString())) {
        if (isRelatedTo) {
          entity.addIsRelatedToEntity(column.getKey(), id);
        } else {
          entity.addRelatesToEntity(column.getKey(), id);
        }
      }
    }
  }

  /**
   * Helper method for reading key-value pairs for either info or config.
   */
  protected <T> void readKeyValuePairs(
      TimelineEntity entity, Result result, ColumnPrefix<T> prefix,
      boolean isConfig) throws IOException {
    // info and configuration are of type Map<String, Object or String>
    Map<String, Object> columns = prefix.readResults(result);
    if (isConfig) {
      for (Map.Entry<String, Object> column : columns.entrySet()) {
        entity.addConfig(column.getKey(), column.getValue().toString());
      }
    } else {
      entity.addInfo(columns);
    }
  }

  /**
   * Read events from the entity table or the application table. The column name
   * is of the form "eventId=timestamp=infoKey" where "infoKey" may be omitted
   * if there is no info associated with the event.
   *
   * See {@link EntityTable} and {@link ApplicationTable} for a more detailed
   * schema description.
   */
  protected void readEvents(TimelineEntity entity, Result result,
      boolean isApplication) throws IOException {
    Map<String, TimelineEvent> eventsMap = new HashMap<>();
    Map<?, Object> eventsResult = isApplication ?
        ApplicationColumnPrefix.EVENT.
            readResultsHavingCompoundColumnQualifiers(result) :
        EntityColumnPrefix.EVENT.
            readResultsHavingCompoundColumnQualifiers(result);
    for (Map.Entry<?, Object> eventResult : eventsResult.entrySet()) {
      byte[][] karr = (byte[][])eventResult.getKey();
      // the column name is of the form "eventId=timestamp=infoKey"
      if (karr.length == 3) {
        String id = Bytes.toString(karr[0]);
        long ts = TimelineStorageUtils.invertLong(Bytes.toLong(karr[1]));
        String key = Separator.VALUES.joinEncoded(id, Long.toString(ts));
        TimelineEvent event = eventsMap.get(key);
        if (event == null) {
          event = new TimelineEvent();
          event.setId(id);
          event.setTimestamp(ts);
          eventsMap.put(key, event);
        }
        // handle empty info
        String infoKey = karr[2].length == 0 ? null : Bytes.toString(karr[2]);
        if (infoKey != null) {
          event.addInfo(infoKey, eventResult.getValue());
        }
      } else {
        LOG.warn("incorrectly formatted column name: it will be discarded");
        continue;
      }
    }
    Set<TimelineEvent> eventsSet = new HashSet<>(eventsMap.values());
    entity.addEvents(eventsSet);
  }
}
