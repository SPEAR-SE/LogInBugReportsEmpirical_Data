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
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineWriteResponse;
import org.apache.hadoop.yarn.server.timeline.GenericObjectMapper;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineStorageUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TypedBufferedMutator;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.AggregationCompactionDimension;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.Attribute;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.AggregationOperation;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowRunTable;

/**
 * This implements a hbase based backend for storing the timeline entity
 * information.
 * It writes to multiple tables at the backend
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class HBaseTimelineWriterImpl extends AbstractService implements
    TimelineWriter {

  private static final Log LOG = LogFactory
      .getLog(HBaseTimelineWriterImpl.class);

  private Connection conn;
  private TypedBufferedMutator<EntityTable> entityTable;
  private TypedBufferedMutator<AppToFlowTable> appToFlowTable;
  private TypedBufferedMutator<ApplicationTable> applicationTable;
  private TypedBufferedMutator<FlowActivityTable> flowActivityTable;
  private TypedBufferedMutator<FlowRunTable> flowRunTable;

  public HBaseTimelineWriterImpl() {
    super(HBaseTimelineWriterImpl.class.getName());
  }

  public HBaseTimelineWriterImpl(Configuration conf) throws IOException {
    super(conf.get("yarn.application.id",
        HBaseTimelineWriterImpl.class.getName()));
  }

  /**
   * initializes the hbase connection to write to the entity table
   */
  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    Configuration hbaseConf = HBaseConfiguration.create(conf);
    conn = ConnectionFactory.createConnection(hbaseConf);
    entityTable = new EntityTable().getTableMutator(hbaseConf, conn);
    appToFlowTable = new AppToFlowTable().getTableMutator(hbaseConf, conn);
    applicationTable = new ApplicationTable().getTableMutator(hbaseConf, conn);
    flowRunTable = new FlowRunTable().getTableMutator(hbaseConf, conn);
    flowActivityTable = new FlowActivityTable().getTableMutator(hbaseConf, conn);
  }

  /**
   * Stores the entire information in TimelineEntities to the timeline store.
   */
  @Override
  public TimelineWriteResponse write(String clusterId, String userId,
      String flowName, String flowVersion, long flowRunId, String appId,
      TimelineEntities data) throws IOException {

    TimelineWriteResponse putStatus = new TimelineWriteResponse();
    for (TimelineEntity te : data.getEntities()) {

      // a set can have at most 1 null
      if (te == null) {
        continue;
      }

      // if the entity is the application, the destination is the application
      // table
      boolean isApplication = TimelineStorageUtils.isApplicationEntity(te);
      byte[] rowKey = isApplication ?
          ApplicationRowKey.getRowKey(clusterId, userId, flowName, flowRunId,
              appId) :
          EntityRowKey.getRowKey(clusterId, userId, flowName, flowRunId, appId,
              te.getType(), te.getId());

      storeInfo(rowKey, te, flowVersion, isApplication);
      storeEvents(rowKey, te.getEvents(), isApplication);
      storeConfig(rowKey, te.getConfigs(), isApplication);
      storeMetrics(rowKey, te.getMetrics(), isApplication);
      storeRelations(rowKey, te, isApplication);

      if (isApplication) {
        if (TimelineStorageUtils.isApplicationCreated(te)) {
          onApplicationCreated(clusterId, userId, flowName, flowVersion,
              flowRunId, appId, te);
        }
        // if it's an application entity, store metrics
        storeFlowMetricsAppRunning(clusterId, userId, flowName, flowRunId,
            appId, te);
        // if application has finished, store it's finish time and write final
        // values
        // of all metrics
        if (TimelineStorageUtils.isApplicationFinished(te)) {
          onApplicationFinished(clusterId, userId, flowName, flowVersion,
              flowRunId, appId, te);
        }
      }
    }
    return putStatus;
  }

  private void onApplicationCreated(String clusterId, String userId,
      String flowName, String flowVersion, long flowRunId, String appId,
      TimelineEntity te) throws IOException {
    // store in App to flow table
    storeInAppToFlowTable(clusterId, userId, flowName, flowRunId, appId, te);
    // store in flow run table
    storeAppCreatedInFlowRunTable(clusterId, userId, flowName, flowVersion,
        flowRunId, appId, te);
    // store in flow activity table
    storeInFlowActivityTable(clusterId, userId, flowName, flowVersion,
        flowRunId, appId, te);
  }

  /*
   * updates the {@link FlowActivityTable} with the Application TimelineEntity
   * information
   */
  private void storeInFlowActivityTable(String clusterId, String userId,
      String flowName, String flowVersion, long flowRunId, String appId,
      TimelineEntity te) throws IOException {
    byte[] rowKey = FlowActivityRowKey.getRowKey(clusterId, userId, flowName);
    byte[] qualifier = GenericObjectMapper.write(flowRunId);
    FlowActivityColumnPrefix.RUN_ID.store(rowKey, flowActivityTable, qualifier,
        null, flowVersion,
        AggregationCompactionDimension.APPLICATION_ID.getAttribute(appId));
  }

  /*
   * updates the {@link FlowRunTable} with Application Created information
   */
  private void storeAppCreatedInFlowRunTable(String clusterId, String userId,
      String flowName, String flowVersion, long flowRunId, String appId,
      TimelineEntity te) throws IOException {
    byte[] rowKey = FlowRunRowKey.getRowKey(clusterId, userId, flowName,
        flowRunId);
    FlowRunColumn.MIN_START_TIME.store(rowKey, flowRunTable, null,
        te.getCreatedTime(),
        AggregationCompactionDimension.APPLICATION_ID.getAttribute(appId));
  }

  private void storeInAppToFlowTable(String clusterId, String userId,
      String flowName, long flowRunId, String appId, TimelineEntity te)
      throws IOException {
    byte[] rowKey = AppToFlowRowKey.getRowKey(clusterId, appId);
    AppToFlowColumn.FLOW_ID.store(rowKey, appToFlowTable, null, flowName);
    AppToFlowColumn.FLOW_RUN_ID.store(rowKey, appToFlowTable, null, flowRunId);
    AppToFlowColumn.USER_ID.store(rowKey, appToFlowTable, null, userId);
  }

  /*
   * updates the {@link FlowRunTable} and {@link FlowActivityTable} when an
   * application has finished
   */
  private void onApplicationFinished(String clusterId, String userId,
      String flowName, String flowVersion, long flowRunId, String appId,
      TimelineEntity te) throws IOException {
    // store in flow run table
    storeAppFinishedInFlowRunTable(clusterId, userId, flowName, flowRunId,
        appId, te);

    // indicate in the flow activity table that the app has finished
    storeInFlowActivityTable(clusterId, userId, flowName, flowVersion,
        flowRunId, appId, te);
  }

  /*
   * Update the {@link FlowRunTable} with Application Finished information
   */
  private void storeAppFinishedInFlowRunTable(String clusterId, String userId,
      String flowName, long flowRunId, String appId, TimelineEntity te)
      throws IOException {
    byte[] rowKey = FlowRunRowKey.getRowKey(clusterId, userId, flowName,
        flowRunId);
    Attribute attributeAppId = AggregationCompactionDimension.APPLICATION_ID
        .getAttribute(appId);
    FlowRunColumn.MAX_END_TIME.store(rowKey, flowRunTable, null,
        TimelineStorageUtils.getApplicationFinishedTime(te), attributeAppId);

    // store the final value of metrics since application has finished
    Set<TimelineMetric> metrics = te.getMetrics();
    if (metrics != null) {
      storeFlowMetrics(rowKey, metrics, attributeAppId,
          AggregationOperation.SUM_FINAL.getAttribute());
    }
  }

  /*
   * Updates the {@link FlowRunTable} with Application Metrics
   */
  private void storeFlowMetricsAppRunning(String clusterId, String userId,
      String flowName, long flowRunId, String appId, TimelineEntity te)
      throws IOException {
    Set<TimelineMetric> metrics = te.getMetrics();
    if (metrics != null) {
      byte[] rowKey = FlowRunRowKey.getRowKey(clusterId, userId, flowName,
          flowRunId);
      storeFlowMetrics(rowKey, metrics,
          AggregationCompactionDimension.APPLICATION_ID.getAttribute(appId));
    }
  }

  private void storeFlowMetrics(byte[] rowKey, Set<TimelineMetric> metrics,
      Attribute... attributes) throws IOException {
    for (TimelineMetric metric : metrics) {
      String metricColumnQualifier = metric.getId();
      Map<Long, Number> timeseries = metric.getValues();
      for (Map.Entry<Long, Number> timeseriesEntry : timeseries.entrySet()) {
        Long timestamp = timeseriesEntry.getKey();
        FlowRunColumnPrefix.METRIC.store(rowKey, flowRunTable,
            metricColumnQualifier, timestamp, timeseriesEntry.getValue(),
            attributes);
      }
    }
  }

  private void storeRelations(byte[] rowKey, TimelineEntity te,
      boolean isApplication) throws IOException {
    if (isApplication) {
      storeRelations(rowKey, te.getIsRelatedToEntities(),
          ApplicationColumnPrefix.IS_RELATED_TO, applicationTable);
      storeRelations(rowKey, te.getRelatesToEntities(),
          ApplicationColumnPrefix.RELATES_TO, applicationTable);
    } else {
      storeRelations(rowKey, te.getIsRelatedToEntities(),
          EntityColumnPrefix.IS_RELATED_TO, entityTable);
      storeRelations(rowKey, te.getRelatesToEntities(),
          EntityColumnPrefix.RELATES_TO, entityTable);
    }
  }

  /**
   * Stores the Relations from the {@linkplain TimelineEntity} object
   */
  private <T> void storeRelations(byte[] rowKey,
      Map<String, Set<String>> connectedEntities,
      ColumnPrefix<T> columnPrefix, TypedBufferedMutator<T> table)
          throws IOException {
    for (Map.Entry<String, Set<String>> connectedEntity : connectedEntities
        .entrySet()) {
      // id3?id4?id5
      String compoundValue =
          Separator.VALUES.joinEncoded(connectedEntity.getValue());
      columnPrefix.store(rowKey, table, connectedEntity.getKey(), null,
          compoundValue);
    }
  }

  /**
   * Stores information from the {@linkplain TimelineEntity} object
   */
  private void storeInfo(byte[] rowKey, TimelineEntity te, String flowVersion,
      boolean isApplication) throws IOException {

    if (isApplication) {
      ApplicationColumn.ID.store(rowKey, applicationTable, null, te.getId());
      ApplicationColumn.CREATED_TIME.store(rowKey, applicationTable, null,
          te.getCreatedTime());
      ApplicationColumn.MODIFIED_TIME.store(rowKey, applicationTable, null,
          te.getModifiedTime());
      ApplicationColumn.FLOW_VERSION.store(rowKey, applicationTable, null,
          flowVersion);
      Map<String, Object> info = te.getInfo();
      if (info != null) {
        for (Map.Entry<String, Object> entry : info.entrySet()) {
          ApplicationColumnPrefix.INFO.store(rowKey, applicationTable,
              entry.getKey(), null, entry.getValue());
        }
      }
    } else {
      EntityColumn.ID.store(rowKey, entityTable, null, te.getId());
      EntityColumn.TYPE.store(rowKey, entityTable, null, te.getType());
      EntityColumn.CREATED_TIME.store(rowKey, entityTable, null,
          te.getCreatedTime());
      EntityColumn.MODIFIED_TIME.store(rowKey, entityTable, null,
          te.getModifiedTime());
      EntityColumn.FLOW_VERSION.store(rowKey, entityTable, null, flowVersion);
      Map<String, Object> info = te.getInfo();
      if (info != null) {
        for (Map.Entry<String, Object> entry : info.entrySet()) {
          EntityColumnPrefix.INFO.store(rowKey, entityTable, entry.getKey(),
              null, entry.getValue());
        }
      }
    }
  }

  /**
   * stores the config information from {@linkplain TimelineEntity}
   */
  private void storeConfig(byte[] rowKey, Map<String, String> config,
      boolean isApplication) throws IOException {
    if (config == null) {
      return;
    }
    for (Map.Entry<String, String> entry : config.entrySet()) {
      if (isApplication) {
        ApplicationColumnPrefix.CONFIG.store(rowKey, applicationTable,
          entry.getKey(), null, entry.getValue());
      } else {
        EntityColumnPrefix.CONFIG.store(rowKey, entityTable, entry.getKey(),
          null, entry.getValue());
      }
    }
  }

  /**
   * stores the {@linkplain TimelineMetric} information from the
   * {@linkplain TimelineEvent} object
   */
  private void storeMetrics(byte[] rowKey, Set<TimelineMetric> metrics,
      boolean isApplication) throws IOException {
    if (metrics != null) {
      for (TimelineMetric metric : metrics) {
        String metricColumnQualifier = metric.getId();
        Map<Long, Number> timeseries = metric.getValues();
        for (Map.Entry<Long, Number> timeseriesEntry : timeseries.entrySet()) {
          Long timestamp = timeseriesEntry.getKey();
          if (isApplication) {
            ApplicationColumnPrefix.METRIC.store(rowKey, applicationTable,
              metricColumnQualifier, timestamp, timeseriesEntry.getValue());
          } else {
            EntityColumnPrefix.METRIC.store(rowKey, entityTable,
              metricColumnQualifier, timestamp, timeseriesEntry.getValue());
          }
        }
      }
    }
  }

  /**
   * Stores the events from the {@linkplain TimelineEvent} object
   */
  private void storeEvents(byte[] rowKey, Set<TimelineEvent> events,
      boolean isApplication) throws IOException {
    if (events != null) {
      for (TimelineEvent event : events) {
        if (event != null) {
          String eventId = event.getId();
          if (eventId != null) {
            long eventTimestamp = event.getTimestamp();
            // if the timestamp is not set, use the current timestamp
            if (eventTimestamp == TimelineEvent.INVALID_TIMESTAMP) {
              LOG.warn("timestamp is not set for event " + eventId +
                  "! Using the current timestamp");
              eventTimestamp = System.currentTimeMillis();
            }
            byte[] columnQualifierFirst =
                Bytes.toBytes(Separator.VALUES.encode(eventId));
            byte[] columnQualifierWithTsBytes = Separator.VALUES.
                join(columnQualifierFirst, Bytes.toBytes(
                    TimelineStorageUtils.invertLong(eventTimestamp)));
            Map<String, Object> eventInfo = event.getInfo();
            if ((eventInfo == null) || (eventInfo.size() == 0)) {
              // add separator since event key is empty
              byte[] compoundColumnQualifierBytes =
                  Separator.VALUES.join(columnQualifierWithTsBytes,
                      null);
              if (isApplication) {
                ApplicationColumnPrefix.EVENT.store(rowKey, applicationTable,
                    compoundColumnQualifierBytes, null,
                      TimelineStorageUtils.EMPTY_BYTES);
              } else {
                EntityColumnPrefix.EVENT.store(rowKey, entityTable,
                    compoundColumnQualifierBytes, null,
                      TimelineStorageUtils.EMPTY_BYTES);
              }
            } else {
              for (Map.Entry<String, Object> info : eventInfo.entrySet()) {
                // eventId?infoKey
                byte[] compoundColumnQualifierBytes =
                    Separator.VALUES.join(columnQualifierWithTsBytes,
                        Bytes.toBytes(info.getKey()));
                if (isApplication) {
                  ApplicationColumnPrefix.EVENT.store(rowKey, applicationTable,
                    compoundColumnQualifierBytes, null, info.getValue());
                } else {
                  EntityColumnPrefix.EVENT.store(rowKey, entityTable,
                    compoundColumnQualifierBytes, null, info.getValue());
                }
              } // for info: eventInfo
            }
          }
        }
      } // event : events
    }
  }

  @Override
  public TimelineWriteResponse aggregate(TimelineEntity data,
      TimelineAggregationTrack track) throws IOException {
    return null;
  }

  @Override
  public void flush() throws IOException {
    // flush all buffered mutators
    entityTable.flush();
    appToFlowTable.flush();
    applicationTable.flush();
    flowRunTable.flush();
    flowActivityTable.flush();
  }

  /**
   * close the hbase connections The close APIs perform flushing and release any
   * resources held
   */
  @Override
  protected void serviceStop() throws Exception {
    if (entityTable != null) {
      LOG.info("closing the entity table");
      // The close API performs flushing and releases any resources held
      entityTable.close();
    }
    if (appToFlowTable != null) {
      LOG.info("closing the app_flow table");
      // The close API performs flushing and releases any resources held
      appToFlowTable.close();
    }
    if (applicationTable != null) {
      LOG.info("closing the application table");
      applicationTable.close();
    }
    if (flowRunTable != null) {
      LOG.info("closing the flow run table");
      // The close API performs flushing and releases any resources held
      flowRunTable.close();
    }
    if (flowActivityTable != null) {
      LOG.info("closing the flowActivityTable table");
      // The close API performs flushing and releases any resources held
      flowActivityTable.close();
    }
    if (conn != null) {
      LOG.info("closing the hbase Connection");
      conn.close();
    }
    super.serviceStop();
  }

}