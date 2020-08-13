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
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineDataToRetrieve;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineEntityFilters;
import org.apache.hadoop.yarn.server.timelineservice.reader.TimelineReaderContext;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.BaseTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix;

/**
 * The base class for reading and deserializing timeline entities from the
 * HBase storage. Different types can be defined for different types of the
 * entities that are being requested.
 */
public abstract class TimelineEntityReader {
  private static final Log LOG = LogFactory.getLog(TimelineEntityReader.class);

  protected final boolean singleEntityRead;
  private TimelineReaderContext context;
  private TimelineDataToRetrieve dataToRetrieve;
  // used only for multiple entity read mode
  private TimelineEntityFilters filters;

  /**
   * Main table the entity reader uses.
   */
  protected BaseTable<?> table;

  /**
   * Specifies whether keys for this table are sorted in a manner where entities
   * can be retrieved by created time. If true, it will be sufficient to collect
   * the first results as specified by the limit. Otherwise all matched entities
   * will be fetched and then limit applied.
   */
  private boolean sortedKeys = false;

  /**
   * Instantiates a reader for multiple-entity reads.
   */
  protected TimelineEntityReader(TimelineReaderContext ctxt,
      TimelineEntityFilters entityFilters, TimelineDataToRetrieve toRetrieve,
      boolean sortedKeys) {
    this.singleEntityRead = false;
    this.sortedKeys = sortedKeys;
    this.context = ctxt;
    this.dataToRetrieve = toRetrieve;
    this.filters = entityFilters;

    this.table = getTable();
  }

  /**
   * Instantiates a reader for single-entity reads.
   */
  protected TimelineEntityReader(TimelineReaderContext ctxt,
      TimelineDataToRetrieve toRetrieve) {
    this.singleEntityRead = true;
    this.context = ctxt;
    this.dataToRetrieve = toRetrieve;

    this.table = getTable();
  }

  /**
   * Creates a {@link FilterList} based on fields, confs and metrics to
   * retrieve. This filter list will be set in Scan/Get objects to trim down
   * results fetched from HBase back-end storage.
   * @return a {@link FilterList} object.
   */
  protected abstract FilterList constructFilterListBasedOnFields();

  protected TimelineReaderContext getContext() {
    return context;
  }

  protected TimelineDataToRetrieve getDataToRetrieve() {
    return dataToRetrieve;
  }

  protected TimelineEntityFilters getFilters() {
    return filters;
  }

  /**
   * Reads and deserializes a single timeline entity from the HBase storage.
   */
  public TimelineEntity readEntity(Configuration hbaseConf, Connection conn)
      throws IOException {
    validateParams();
    augmentParams(hbaseConf, conn);

    FilterList filterList = constructFilterListBasedOnFields();
    Result result = getResult(hbaseConf, conn, filterList);
    if (result == null || result.isEmpty()) {
      // Could not find a matching row.
      LOG.info("Cannot find matching entity of type " +
          context.getEntityType());
      return null;
    }
    return parseEntity(result);
  }

  /**
   * Reads and deserializes a set of timeline entities from the HBase storage.
   * It goes through all the results available, and returns the number of
   * entries as specified in the limit in the entity's natural sort order.
   */
  public Set<TimelineEntity> readEntities(Configuration hbaseConf,
      Connection conn) throws IOException {
    validateParams();
    augmentParams(hbaseConf, conn);

    NavigableSet<TimelineEntity> entities = new TreeSet<>();
    FilterList filterList = constructFilterListBasedOnFields();
    ResultScanner results = getResults(hbaseConf, conn, filterList);
    try {
      for (Result result : results) {
        TimelineEntity entity = parseEntity(result);
        if (entity == null) {
          continue;
        }
        entities.add(entity);
        if (!sortedKeys) {
          if (entities.size() > filters.getLimit()) {
            entities.pollLast();
          }
        } else {
          if (entities.size() == filters.getLimit()) {
            break;
          }
        }
      }
      return entities;
    } finally {
      results.close();
    }
  }

  /**
   * Returns the main table to be used by the entity reader.
   */
  protected abstract BaseTable<?> getTable();

  /**
   * Validates the required parameters to read the entities.
   */
  protected abstract void validateParams();

  /**
   * Sets certain parameters to defaults if the values are not provided.
   */
  protected abstract void augmentParams(Configuration hbaseConf,
      Connection conn) throws IOException;

  /**
   * Fetches a {@link Result} instance for a single-entity read.
   *
   * @return the {@link Result} instance or null if no such record is found.
   */
  protected abstract Result getResult(Configuration hbaseConf, Connection conn,
      FilterList filterList) throws IOException;

  /**
   * Fetches a {@link ResultScanner} for a multi-entity read.
   */
  protected abstract ResultScanner getResults(Configuration hbaseConf,
      Connection conn, FilterList filterList) throws IOException;

  /**
   * Given a {@link Result} instance, deserializes and creates a
   * {@link TimelineEntity}.
   *
   * @return the {@link TimelineEntity} instance, or null if the {@link Result}
   * is null or empty.
   */
  protected abstract TimelineEntity parseEntity(Result result)
      throws IOException;

  /**
   * Helper method for reading and deserializing {@link TimelineMetric} objects
   * using the specified column prefix. The timeline metrics then are added to
   * the given timeline entity.
   */
  protected void readMetrics(TimelineEntity entity, Result result,
      ColumnPrefix<?> columnPrefix) throws IOException {
    NavigableMap<String, NavigableMap<Long, Number>> metricsResult =
        columnPrefix.readResultsWithTimestamps(result);
    for (Map.Entry<String, NavigableMap<Long, Number>> metricResult:
        metricsResult.entrySet()) {
      TimelineMetric metric = new TimelineMetric();
      metric.setId(metricResult.getKey());
      // Simply assume that if the value set contains more than 1 elements, the
      // metric is a TIME_SERIES metric, otherwise, it's a SINGLE_VALUE metric
      metric.setType(metricResult.getValue().size() > 1 ?
          TimelineMetric.Type.TIME_SERIES : TimelineMetric.Type.SINGLE_VALUE);
      metric.addValues(metricResult.getValue());
      entity.addMetric(metric);
    }
  }
}
