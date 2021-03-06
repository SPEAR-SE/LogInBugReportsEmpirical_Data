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
package org.apache.hadoop.yarn.server.timelineservice.storage.reader;

import java.io.IOException;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.yarn.api.records.timelineservice.FlowActivityEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.FlowRunEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineDataToRetrieve;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineEntityFilters;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineReaderContext;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.BaseTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.FlowActivityTable;

import com.google.common.base.Preconditions;

/**
 * Timeline entity reader for flow activity entities that are stored in the
 * flow activity table.
 */
class FlowActivityEntityReader extends TimelineEntityReader {
  private static final FlowActivityTable FLOW_ACTIVITY_TABLE =
      new FlowActivityTable();

  public FlowActivityEntityReader(TimelineReaderContext ctxt,
      TimelineEntityFilters entityFilters, TimelineDataToRetrieve toRetrieve) {
    super(ctxt, entityFilters, toRetrieve, true);
  }

  public FlowActivityEntityReader(TimelineReaderContext ctxt,
      TimelineDataToRetrieve toRetrieve) {
    super(ctxt, toRetrieve);
  }

  /**
   * Uses the {@link FlowActivityTable}.
   */
  @Override
  protected BaseTable<?> getTable() {
    return FLOW_ACTIVITY_TABLE;
  }

  @Override
  protected void validateParams() {
    Preconditions.checkNotNull(getContext().getClusterId(),
        "clusterId shouldn't be null");
  }

  @Override
  protected void augmentParams(Configuration hbaseConf, Connection conn)
      throws IOException {
  }

  @Override
  protected FilterList constructFilterListBasedOnFields() {
    return null;
  }

  @Override
  protected Result getResult(Configuration hbaseConf, Connection conn,
      FilterList filterList) throws IOException {
    throw new UnsupportedOperationException(
        "we don't support a single entity query");
  }

  @Override
  protected ResultScanner getResults(Configuration hbaseConf,
      Connection conn, FilterList filterList) throws IOException {
    Scan scan = new Scan();
    String clusterId = getContext().getClusterId();
    if (getFilters().getCreatedTimeBegin() == 0L &&
        getFilters().getCreatedTimeEnd() == Long.MAX_VALUE) {
       // All records have to be chosen.
      scan.setRowPrefixFilter(FlowActivityRowKey.getRowKeyPrefix(clusterId));
    } else {
      scan.setStartRow(
          FlowActivityRowKey.getRowKeyPrefix(clusterId,
              getFilters().getCreatedTimeEnd()));
      scan.setStopRow(
          FlowActivityRowKey.getRowKeyPrefix(clusterId,
              (getFilters().getCreatedTimeBegin() <= 0 ? 0 :
              (getFilters().getCreatedTimeBegin() - 1))));
    }
    // use the page filter to limit the result to the page size
    // the scanner may still return more than the limit; therefore we need to
    // read the right number as we iterate
    scan.setFilter(new PageFilter(getFilters().getLimit()));
    return table.getResultScanner(hbaseConf, conn, scan);
  }

  @Override
  protected TimelineEntity parseEntity(Result result) throws IOException {
    FlowActivityRowKey rowKey = FlowActivityRowKey.parseRowKey(result.getRow());

    long time = rowKey.getDayTimestamp();
    String user = rowKey.getUserId();
    String flowName = rowKey.getFlowName();

    FlowActivityEntity flowActivity = new FlowActivityEntity(
        getContext().getClusterId(), time, user, flowName);
    // set the id
    flowActivity.setId(flowActivity.getId());
    // get the list of run ids along with the version that are associated with
    // this flow on this day
    Map<String, Object> runIdsMap =
        FlowActivityColumnPrefix.RUN_ID.readResults(result);
    for (Map.Entry<String, Object> e : runIdsMap.entrySet()) {
      Long runId = Long.valueOf(e.getKey());
      String version = (String)e.getValue();
      FlowRunEntity flowRun = new FlowRunEntity();
      flowRun.setUser(user);
      flowRun.setName(flowName);
      flowRun.setRunId(runId);
      flowRun.setVersion(version);
      // set the id
      flowRun.setId(flowRun.getId());
      flowActivity.addFlowRun(flowRun);
    }

    return flowActivity;
  }
}
