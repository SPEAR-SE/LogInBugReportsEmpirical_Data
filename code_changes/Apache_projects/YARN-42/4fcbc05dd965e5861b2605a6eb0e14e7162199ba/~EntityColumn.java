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
package org.apache.hadoop.yarn.server.timelineservice.storage.entity;

import java.io.IOException;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Column;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnFamily;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnHelper;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TypedBufferedMutator;
import org.apache.hadoop.yarn.server.timelineservice.storage.flow.Attribute;

/**
 * Identifies fully qualified columns for the {@link EntityTable}.
 */
public enum EntityColumn implements Column<EntityTable> {

  /**
   * Identifier for the entity.
   */
  ID(EntityColumnFamily.INFO, "id"),

  /**
   * The type of entity
   */
  TYPE(EntityColumnFamily.INFO, "type"),

  /**
   * When the entity was created.
   */
  CREATED_TIME(EntityColumnFamily.INFO, "created_time"),

  /**
   * When it was modified.
   */
  MODIFIED_TIME(EntityColumnFamily.INFO, "modified_time"),

  /**
   * The version of the flow that this entity belongs to.
   */
  FLOW_VERSION(EntityColumnFamily.INFO, "flow_version");

  private final ColumnHelper<EntityTable> column;
  private final ColumnFamily<EntityTable> columnFamily;
  private final String columnQualifier;
  private final byte[] columnQualifierBytes;

  EntityColumn(ColumnFamily<EntityTable> columnFamily,
      String columnQualifier) {
    this.columnFamily = columnFamily;
    this.columnQualifier = columnQualifier;
    // Future-proof by ensuring the right column prefix hygiene.
    this.columnQualifierBytes =
        Bytes.toBytes(Separator.SPACE.encode(columnQualifier));
    this.column = new ColumnHelper<EntityTable>(columnFamily);
  }

  /**
   * @return the column name value
   */
  private String getColumnQualifier() {
    return columnQualifier;
  }

  public void store(byte[] rowKey,
      TypedBufferedMutator<EntityTable> tableMutator, Long timestamp,
      Object inputValue, Attribute... attributes) throws IOException {
    column.store(rowKey, tableMutator, columnQualifierBytes, timestamp,
        inputValue, attributes);
  }

  public Object readResult(Result result) throws IOException {
    return column.readResult(result, columnQualifierBytes);
  }

  /**
   * Retrieve an {@link EntityColumn} given a name, or null if there is no
   * match. The following holds true: {@code columnFor(x) == columnFor(y)} if
   * and only if {@code x.equals(y)} or {@code (x == y == null)}
   *
   * @param columnQualifier Name of the column to retrieve
   * @return the corresponding {@link EntityColumn} or null
   */
  public static final EntityColumn columnFor(String columnQualifier) {

    // Match column based on value, assume column family matches.
    for (EntityColumn ec : EntityColumn.values()) {
      // Find a match based only on name.
      if (ec.getColumnQualifier().equals(columnQualifier)) {
        return ec;
      }
    }

    // Default to null
    return null;
  }

  /**
   * Retrieve an {@link EntityColumn} given a name, or null if there is no
   * match. The following holds true: {@code columnFor(a,x) == columnFor(b,y)}
   * if and only if {@code a.equals(b) & x.equals(y)} or
   * {@code (x == y == null)}
   *
   * @param columnFamily The columnFamily for which to retrieve the column.
   * @param name Name of the column to retrieve
   * @return the corresponding {@link EntityColumn} or null if both arguments
   *         don't match.
   */
  public static final EntityColumn columnFor(EntityColumnFamily columnFamily,
      String name) {

    for (EntityColumn ec : EntityColumn.values()) {
      // Find a match based column family and on name.
      if (ec.columnFamily.equals(columnFamily)
          && ec.getColumnQualifier().equals(name)) {
        return ec;
      }
    }

    // Default to null
    return null;
  }

}
