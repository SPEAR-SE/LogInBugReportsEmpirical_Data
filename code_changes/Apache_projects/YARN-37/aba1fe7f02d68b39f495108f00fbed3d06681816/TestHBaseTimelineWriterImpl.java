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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric.Type;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityTable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @throws Exception
 */
public class TestHBaseTimelineWriterImpl {

  private static HBaseTestingUtility util;

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    util = new HBaseTestingUtility();
    util.startMiniCluster();
    createSchema();
  }

  private static void createSchema() throws IOException {
    new EntityTable()
        .createTable(util.getHBaseAdmin(), util.getConfiguration());
  }

  @Test
  public void testWriteEntityToHBase() throws Exception {
    TimelineEntities te = new TimelineEntities();
    TimelineEntity entity = new TimelineEntity();
    String id = "hello";
    String type = "world";
    entity.setId(id);
    entity.setType(type);
    Long cTime = 1425016501000L;
    Long mTime = 1425026901000L;
    entity.setCreatedTime(cTime);
    entity.setModifiedTime(mTime);

    // add the isRelatedToEntity info
    String key = "task";
    String value = "is_related_to_entity_id_here";
    Set<String> isRelatedToSet = new HashSet<String>();
    isRelatedToSet.add(value);
    Map<String, Set<String>> isRelatedTo = new HashMap<String, Set<String>>();
    isRelatedTo.put(key, isRelatedToSet);
    entity.setIsRelatedToEntities(isRelatedTo);

    // add the relatesTo info
    key = "container";
    value = "relates_to_entity_id_here";
    Set<String> relatesToSet = new HashSet<String>();
    relatesToSet.add(value);
    value = "relates_to_entity_id_here_Second";
    relatesToSet.add(value);
    Map<String, Set<String>> relatesTo = new HashMap<String, Set<String>>();
    relatesTo.put(key, relatesToSet);
    entity.setRelatesToEntities(relatesTo);

    // add some config entries
    Map<String, String> conf = new HashMap<String, String>();
    conf.put("config_param1", "value1");
    conf.put("config_param2", "value2");
    entity.addConfigs(conf);

    // add metrics
    Set<TimelineMetric> metrics = new HashSet<>();
    TimelineMetric m1 = new TimelineMetric();
    m1.setId("MAP_SLOT_MILLIS");
    Map<Long, Number> metricValues = new HashMap<Long, Number>();
    long ts = System.currentTimeMillis();
    metricValues.put(ts - 120000, 100000000);
    metricValues.put(ts - 100000, 200000000);
    metricValues.put(ts - 80000, 300000000);
    metricValues.put(ts - 60000, 400000000);
    metricValues.put(ts - 40000, 50000000000L);
    metricValues.put(ts - 20000, 60000000000L);
    m1.setType(Type.TIME_SERIES);
    m1.setValues(metricValues);
    metrics.add(m1);
    entity.addMetrics(metrics);

    te.addEntity(entity);

    HBaseTimelineWriterImpl hbi = null;
    try {
      Configuration c1 = util.getConfiguration();
      hbi = new HBaseTimelineWriterImpl(c1);
      hbi.init(c1);
      String cluster = "cluster1";
      String user = "user1";
      String flow = "some_flow_name";
      String flowVersion = "AB7822C10F1111";
      long runid = 1002345678919L;
      String appName = "some app name";
      hbi.write(cluster, user, flow, flowVersion, runid, appName, te);
      hbi.stop();

      // scan the table and see that entity exists
      Scan s = new Scan();
      byte[] startRow =
          EntityRowKey.getRowKeyPrefix(cluster, user, flow, runid, appName);
      s.setStartRow(startRow);
      s.setMaxVersions(Integer.MAX_VALUE);
      Connection conn = ConnectionFactory.createConnection(c1);
      ResultScanner scanner = new EntityTable().getResultScanner(c1, conn, s);

      int rowCount = 0;
      int colCount = 0;
      for (Result result : scanner) {
        if (result != null && !result.isEmpty()) {
          rowCount++;
          colCount += result.size();
          byte[] row1 = result.getRow();
          assertTrue(isRowKeyCorrect(row1, cluster, user, flow, runid, appName,
              entity));

          // check info column family
          String id1 = EntityColumn.ID.readResult(result).toString();
          assertEquals(id, id1);

          String type1 = EntityColumn.TYPE.readResult(result).toString();
          assertEquals(type, type1);

          Number val = (Number) EntityColumn.CREATED_TIME.readResult(result);
          Long cTime1 = val.longValue();
          assertEquals(cTime1, cTime);

          val = (Number) EntityColumn.MODIFIED_TIME.readResult(result);
          Long mTime1 = val.longValue();
          assertEquals(mTime1, mTime);

          // Remember isRelatedTo is of type Map<String, Set<String>>
          for (String isRelatedToKey : isRelatedTo.keySet()) {
            Object isRelatedToValue =
                EntityColumnPrefix.IS_RELATED_TO.readResult(result,
                    isRelatedToKey);
            String compoundValue = isRelatedToValue.toString();
            // id7?id9?id6
            Set<String> isRelatedToValues =
                new HashSet<String>(
                    Separator.VALUES.splitEncoded(compoundValue));
            assertEquals(isRelatedTo.get(isRelatedToKey).size(),
                isRelatedToValues.size());
            for (String v : isRelatedTo.get(isRelatedToKey)) {
              assertTrue(isRelatedToValues.contains(v));
            }
          }

          // RelatesTo
          for (String relatesToKey : relatesTo.keySet()) {
            String compoundValue =
                EntityColumnPrefix.RELATES_TO.readResult(result, relatesToKey)
                    .toString();
            // id3?id4?id5
            Set<String> relatesToValues =
                new HashSet<String>(
                    Separator.VALUES.splitEncoded(compoundValue));
            assertEquals(relatesTo.get(relatesToKey).size(),
                relatesToValues.size());
            for (String v : relatesTo.get(relatesToKey)) {
              assertTrue(relatesToValues.contains(v));
            }
          }

          // Configuration
          Map<String, Object> configColumns =
              EntityColumnPrefix.CONFIG.readResults(result);
          assertEquals(conf.size(), configColumns.size());
          for (String configItem : conf.keySet()) {
            assertEquals(conf.get(configItem), configColumns.get(configItem));
          }

          NavigableMap<String, NavigableMap<Long, Number>> metricsResult =
              EntityColumnPrefix.METRIC.readTimeseriesResults(result);

          NavigableMap<Long, Number> metricMap = metricsResult.get(m1.getId());
          // We got metrics back
          assertNotNull(metricMap);
          // Same number of metrics as we wrote
          assertEquals(metricValues.entrySet().size(), metricMap.entrySet()
              .size());

          // Iterate over original metrics and confirm that they are present
          // here.
          for (Entry<Long, Number> metricEntry : metricValues.entrySet()) {
            assertEquals(metricEntry.getValue(),
                metricMap.get(metricEntry.getKey()));
          }
        }
      }
      assertEquals(1, rowCount);
      assertEquals(15, colCount);

    } finally {
      hbi.stop();
      hbi.close();
    }

    // Somewhat of a hack, not a separate test in order not to have to deal with
    // test case order exectution.
    testAdditionalEntity();
  }

  private boolean isRowKeyCorrect(byte[] rowKey, String cluster, String user,
      String flow, Long runid, String appName, TimelineEntity te) {

    byte[][] rowKeyComponents = Separator.QUALIFIERS.split(rowKey, -1);

    assertTrue(rowKeyComponents.length == 7);
    assertEquals(user, Bytes.toString(rowKeyComponents[0]));
    assertEquals(cluster, Bytes.toString(rowKeyComponents[1]));
    assertEquals(flow, Bytes.toString(rowKeyComponents[2]));
    assertEquals(EntityRowKey.invert(runid), Bytes.toLong(rowKeyComponents[3]));
    assertEquals(appName, Bytes.toString(rowKeyComponents[4]));
    assertEquals(te.getType(), Bytes.toString(rowKeyComponents[5]));
    assertEquals(te.getId(), Bytes.toString(rowKeyComponents[6]));
    return true;
  }

  private void testAdditionalEntity() throws IOException {
    TimelineEvent event = new TimelineEvent();
    event.setId("foo_event_id");
    event.setTimestamp(System.currentTimeMillis());
    event.addInfo("foo_event", "test");

    final TimelineEntity entity = new TimelineEntity();
    entity.setId("attempt_1329348432655_0001_m_000008_18");
    entity.setType("FOO_ATTEMPT");

    TimelineEntities entities = new TimelineEntities();
    entities.addEntity(entity);

    HBaseTimelineWriterImpl hbi = null;
    try {
      Configuration c1 = util.getConfiguration();
      hbi = new HBaseTimelineWriterImpl(c1);
      hbi.init(c1);
      String cluster = "cluster2";
      String user = "user2";
      String flow = "other_flow_name";
      String flowVersion = "1111F01C2287BA";
      long runid = 1009876543218L;
      String appName = "some app name";
      hbi.write(cluster, user, flow, flowVersion, runid, appName, entities);
      hbi.stop();
      // scan the table and see that entity exists
      Scan s = new Scan();
      byte[] startRow =
          EntityRowKey.getRowKeyPrefix(cluster, user, flow, runid, appName);
      s.setStartRow(startRow);
      s.setMaxVersions(Integer.MAX_VALUE);
      Connection conn = ConnectionFactory.createConnection(c1);
      ResultScanner scanner = new EntityTable().getResultScanner(c1, conn, s);

      int rowCount = 0;
      for (Result result : scanner) {
        if (result != null && !result.isEmpty()) {
          rowCount++;
        }
      }
      assertEquals(1, rowCount);

    } finally {
      hbi.stop();
      hbi.close();
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    util.shutdownMiniCluster();
  }
}
