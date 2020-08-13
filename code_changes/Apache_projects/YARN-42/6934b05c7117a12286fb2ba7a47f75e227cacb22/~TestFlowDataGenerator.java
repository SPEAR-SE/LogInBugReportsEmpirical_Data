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

package org.apache.hadoop.yarn.server.timelineservice.storage.flow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntityType;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric.Type;
import org.apache.hadoop.yarn.server.metrics.ApplicationMetricsConstants;

/**
 * Generates the data/entities for the FlowRun and FlowActivity Tables
 */
class TestFlowDataGenerator {

  private final static String metric1 = "MAP_SLOT_MILLIS";
  private final static String metric2 = "HDFS_BYTES_READ";


  static TimelineEntity getEntityMetricsApp1() {
    TimelineEntity entity = new TimelineEntity();
    String id = "flowRunMetrics_test";
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setId(id);
    entity.setType(type);
    long cTime = 1425016501000L;
    entity.setCreatedTime(cTime);

    // add metrics
    Set<TimelineMetric> metrics = new HashSet<>();
    TimelineMetric m1 = new TimelineMetric();
    m1.setId(metric1);
    Map<Long, Number> metricValues = new HashMap<Long, Number>();
    long ts = System.currentTimeMillis();
    metricValues.put(ts - 100000, 2L);
    metricValues.put(ts - 80000, 40L);
    m1.setType(Type.TIME_SERIES);
    m1.setValues(metricValues);
    metrics.add(m1);

    TimelineMetric m2 = new TimelineMetric();
    m2.setId(metric2);
    metricValues = new HashMap<Long, Number>();
    ts = System.currentTimeMillis();
    metricValues.put(ts - 100000, 31L);
    metricValues.put(ts - 80000, 57L);
    m2.setType(Type.TIME_SERIES);
    m2.setValues(metricValues);
    metrics.add(m2);

    entity.addMetrics(metrics);
    return entity;
  }

  static TimelineEntity getEntityMetricsApp2() {
    TimelineEntity entity = new TimelineEntity();
    String id = "flowRunMetrics_test";
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setId(id);
    entity.setType(type);
    long cTime = 1425016501000L;
    entity.setCreatedTime(cTime);
    // add metrics
    Set<TimelineMetric> metrics = new HashSet<>();
    TimelineMetric m1 = new TimelineMetric();
    m1.setId(metric1);
    Map<Long, Number> metricValues = new HashMap<Long, Number>();
    long ts = System.currentTimeMillis();
    metricValues.put(ts - 100000, 5L);
    metricValues.put(ts - 80000, 101L);
    m1.setType(Type.TIME_SERIES);
    m1.setValues(metricValues);
    metrics.add(m1);
    entity.addMetrics(metrics);
    return entity;
  }

  static TimelineEntity getEntity1() {
    TimelineEntity entity = new TimelineEntity();
    String id = "flowRunHello";
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setId(id);
    entity.setType(type);
    long cTime = 20000000000000L;
    long mTime = 1425026901000L;
    entity.setCreatedTime(cTime);
    entity.setModifiedTime(mTime);
    // add metrics
    Set<TimelineMetric> metrics = new HashSet<>();
    TimelineMetric m1 = new TimelineMetric();
    m1.setId(metric1);
    Map<Long, Number> metricValues = new HashMap<Long, Number>();
    long ts = System.currentTimeMillis();
    metricValues.put(ts - 120000, 100000000L);
    metricValues.put(ts - 100000, 200000000L);
    metricValues.put(ts - 80000, 300000000L);
    metricValues.put(ts - 60000, 400000000L);
    metricValues.put(ts - 40000, 50000000000L);
    metricValues.put(ts - 20000, 60000000000L);
    m1.setType(Type.TIME_SERIES);
    m1.setValues(metricValues);
    metrics.add(m1);
    entity.addMetrics(metrics);

    TimelineEvent event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.CREATED_EVENT_TYPE);
    long expTs = 1436512802000L;
    event.setTimestamp(expTs);
    String expKey = "foo_event";
    Object expVal = "test";
    event.addInfo(expKey, expVal);
    entity.addEvent(event);

    event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.FINISHED_EVENT_TYPE);
    event.setTimestamp(1436512801000L);
    event.addInfo(expKey, expVal);
    entity.addEvent(event);

    return entity;
  }

  static TimelineEntity getEntityGreaterStartTime(long startTs) {
    TimelineEntity entity = new TimelineEntity();
    entity.setCreatedTime(startTs);
    entity.setId("flowRunHello with greater start time");
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setType(type);
    TimelineEvent event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.CREATED_EVENT_TYPE);
    long endTs = 1439379885000L;
    event.setTimestamp(endTs);
    String expKey = "foo_event_greater";
    String expVal = "test_app_greater";
    event.addInfo(expKey, expVal);
    entity.addEvent(event);
    return entity;
  }

  static TimelineEntity getEntityMaxEndTime(long endTs) {
    TimelineEntity entity = new TimelineEntity();
    entity.setId("flowRunHello Max End time");
    entity.setType(TimelineEntityType.YARN_APPLICATION.toString());
    TimelineEvent event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.FINISHED_EVENT_TYPE);
    event.setTimestamp(endTs);
    String expKey = "foo_even_max_ finished";
    String expVal = "test_app_max_finished";
    event.addInfo(expKey, expVal);
    entity.addEvent(event);
    return entity;
  }

  static TimelineEntity getEntityMinStartTime(long startTs) {
    TimelineEntity entity = new TimelineEntity();
    String id = "flowRunHelloMInStartTime";
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setId(id);
    entity.setType(type);
    entity.setCreatedTime(startTs);
    TimelineEvent event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.CREATED_EVENT_TYPE);
    event.setTimestamp(System.currentTimeMillis());
    entity.addEvent(event);
    return entity;
  }


  static TimelineEntity getFlowApp1() {
    TimelineEntity entity = new TimelineEntity();
    String id = "flowActivity_test";
    String type = TimelineEntityType.YARN_APPLICATION.toString();
    entity.setId(id);
    entity.setType(type);
    long cTime = 1425016501000L;
    entity.setCreatedTime(cTime);

    TimelineEvent event = new TimelineEvent();
    event.setId(ApplicationMetricsConstants.CREATED_EVENT_TYPE);
    long expTs = 1436512802000L;
    event.setTimestamp(expTs);
    String expKey = "foo_event";
    Object expVal = "test";
    event.addInfo(expKey, expVal);
    entity.addEvent(event);

    return entity;
  }

}
