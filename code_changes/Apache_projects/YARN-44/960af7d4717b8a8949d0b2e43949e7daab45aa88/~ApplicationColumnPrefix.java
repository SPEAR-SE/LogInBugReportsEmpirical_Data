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
package org.apache.hadoop.yarn.server.timelineservice.storage.application;

import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnFamily;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnHelper;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.GenericConverter;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TypedBufferedMutator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.LongConverter;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ValueConverter;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.Attribute;

/**
 * Identifies partially qualified columns for the application table.
 */
public enum ApplicationColumnPrefix implements ColumnPrefix<ApplicationTable> {

  /**
   * To store TimelineEntity getIsRelatedToEntities values.
   */
  IS_RELATED_TO(ApplicationColumnFamily.INFO, "s"),

  /**
   * To store TimelineEntity getRelatesToEntities values.
   */
  RELATES_TO(ApplicationColumnFamily.INFO, "r"),

  /**
   * To store TimelineEntity info values.
   */
  INFO(ApplicationColumnFamily.INFO, "i"),

  /**
   * Lifecycle events for an application
   */
  EVENT(ApplicationColumnFamily.INFO, "e"),

  /**
   * Config column stores configuration with config key as the column name.
   */
  CONFIG(ApplicationColumnFamily.CONFIGS, null),

  /**
   * Metrics are stored with the metric name as the column name.
   */
  METRIC(ApplicationColumnFamily.METRICS, null,
      LongConverter.getInstance());

  private final ColumnHelper<ApplicationTable> column;
  private final ColumnFamily<ApplicationTable> columnFamily;

  /**
   * Can be null for those cases where the provided column qualifier is the
   * entire column name.
   */
  private final String columnPrefix;
  private final byte[] columnPrefixBytes;

  /**
   * Private constructor, meant to be used by the enum definition.
   *
   * @param columnFamily that this column is stored in.
   * @param columnPrefix for this column.
   */
  private ApplicationColumnPrefix(ColumnFamily<ApplicationTable> columnFamily,
      String columnPrefix) {
    this(columnFamily, columnPrefix, GenericConverter.getInstance());
  }

  /**
   * Private constructor, meant to be used by the enum definition.
   *
   * @param columnFamily that this column is stored in.
   * @param columnPrefix for this column.
   * @param converter used to encode/decode values to be stored in HBase for
   * this column prefix.
   */
  private ApplicationColumnPrefix(ColumnFamily<ApplicationTable> columnFamily,
      String columnPrefix, ValueConverter converter) {
    column = new ColumnHelper<ApplicationTable>(columnFamily, converter);
    this.columnFamily = columnFamily;
    this.columnPrefix = columnPrefix;
    if (columnPrefix == null) {
      this.columnPrefixBytes = null;
    } else {
      // Future-proof by ensuring the right column prefix hygiene.
      this.columnPrefixBytes =
          Bytes.toBytes(Separator.SPACE.encode(columnPrefix));
    }
  }

  /**
   * @return the column name value
   */
  private String getColumnPrefix() {
    return columnPrefix;
  }

  @Override
  public byte[] getColumnPrefixBytes(byte[] qualifierPrefix) {
    return ColumnHelper.getColumnQualifier(
        this.columnPrefixBytes, qualifierPrefix);
  }

  @Override
  public byte[] getColumnPrefixBytes(String qualifierPrefix) {
    return ColumnHelper.getColumnQualifier(
        this.columnPrefixBytes, qualifierPrefix);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix
   * #store(byte[],
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.
   * TypedBufferedMutator, java.lang.String, java.lang.Long, java.lang.Object)
   */
  public void store(byte[] rowKey,
      TypedBufferedMutator<ApplicationTable> tableMutator, byte[] qualifier,
      Long timestamp, Object inputValue, Attribute... attributes)
      throws IOException {

    // Null check
    if (qualifier == null) {
      throw new IOException("Cannot store column with null qualifier in "
          + tableMutator.getName().getNameAsString());
    }

    byte[] columnQualifier = getColumnPrefixBytes(qualifier);

    column.store(rowKey, tableMutator, columnQualifier, timestamp, inputValue,
        attributes);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix
   * #store(byte[],
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.
   * TypedBufferedMutator, java.lang.String, java.lang.Long, java.lang.Object)
   */
  public void store(byte[] rowKey,
      TypedBufferedMutator<ApplicationTable> tableMutator, String qualifier,
      Long timestamp, Object inputValue, Attribute...attributes)
      throws IOException {

    // Null check
    if (qualifier == null) {
      throw new IOException("Cannot store column with null qualifier in "
          + tableMutator.getName().getNameAsString());
    }

    byte[] columnQualifier = getColumnPrefixBytes(qualifier);

    column.store(rowKey, tableMutator, columnQualifier, timestamp, inputValue,
        attributes);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix
   * #readResult(org.apache.hadoop.hbase.client.Result, java.lang.String)
   */
  public Object readResult(Result result, String qualifier) throws IOException {
    byte[] columnQualifier =
        ColumnHelper.getColumnQualifier(this.columnPrefixBytes, qualifier);
    return column.readResult(result, columnQualifier);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix
   * #readResults(org.apache.hadoop.hbase.client.Result)
   */
  public Map<String, Object> readResults(Result result) throws IOException {
    return column.readResults(result, columnPrefixBytes);
  }

  /**
   * @param result from which to read columns
   * @return the latest values of columns in the column family. The column
   *         qualifier is returned as a list of parts, each part a byte[]. This
   *         is to facilitate returning byte arrays of values that were not
   *         Strings. If they can be treated as Strings, you should use
   *         {@link #readResults(Result)} instead.
   * @throws IOException
   */
  public Map<?, Object> readResultsHavingCompoundColumnQualifiers(Result result)
      throws IOException {
    return column.readResultsHavingCompoundColumnQualifiers(result,
        columnPrefixBytes);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix
   * #readResultsWithTimestamps(org.apache.hadoop.hbase.client.Result)
   */
  public <V> NavigableMap<String, NavigableMap<Long, V>>
      readResultsWithTimestamps(Result result) throws IOException {
    return column.readResultsWithTimestamps(result, columnPrefixBytes);
  }

  /**
   * Retrieve an {@link ApplicationColumnPrefix} given a name, or null if there
   * is no match. The following holds true: {@code columnFor(x) == columnFor(y)}
   * if and only if {@code x.equals(y)} or {@code (x == y == null)}
   *
   * @param columnPrefix Name of the column to retrieve
   * @return the corresponding {@link ApplicationColumnPrefix} or null
   */
  public static final ApplicationColumnPrefix columnFor(String columnPrefix) {

    // Match column based on value, assume column family matches.
    for (ApplicationColumnPrefix acp : ApplicationColumnPrefix.values()) {
      // Find a match based only on name.
      if (acp.getColumnPrefix().equals(columnPrefix)) {
        return acp;
      }
    }

    // Default to null
    return null;
  }

  /**
   * Retrieve an {@link ApplicationColumnPrefix} given a name, or null if there
   * is no match. The following holds true:
   * {@code columnFor(a,x) == columnFor(b,y)} if and only if
   * {@code (x == y == null)} or {@code a.equals(b) & x.equals(y)}
   *
   * @param columnFamily The columnFamily for which to retrieve the column.
   * @param columnPrefix Name of the column to retrieve
   * @return the corresponding {@link ApplicationColumnPrefix} or null if both
   *         arguments don't match.
   */
  public static final ApplicationColumnPrefix columnFor(
      ApplicationColumnFamily columnFamily, String columnPrefix) {

    // TODO: needs unit test to confirm and need to update javadoc to explain
    // null prefix case.

    for (ApplicationColumnPrefix acp : ApplicationColumnPrefix.values()) {
      // Find a match based column family and on name.
      if (acp.columnFamily.equals(columnFamily)
          && (((columnPrefix == null) && (acp.getColumnPrefix() == null)) || (acp
              .getColumnPrefix().equals(columnPrefix)))) {
        return acp;
      }
    }

    // Default to null
    return null;
  }

}
