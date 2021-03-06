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

package org.apache.hadoop.yarn.server.timelineservice.storage.common;

import java.io.IOException;

import org.apache.hadoop.hbase.util.Bytes;

/**
 * Encodes a value by interpreting it as a Long and converting it to bytes and
 * decodes a set of bytes as a Long.
 */
public final class LongConverter implements NumericValueConverter {
  private static final LongConverter INSTANCE = new LongConverter();

  private LongConverter() {
  }

  public static LongConverter getInstance() {
    return INSTANCE;
  }

  @Override
  public byte[] encodeValue(Object value) throws IOException {
    if (!TimelineStorageUtils.isIntegralValue(value)) {
      throw new IOException("Expected integral value");
    }
    return Bytes.toBytes(((Number)value).longValue());
  }

  @Override
  public Object decodeValue(byte[] bytes) throws IOException {
    if (bytes == null) {
      return null;
    }
    return Bytes.toLong(bytes);
  }

  /**
   * Compares two numbers as longs. If either number is null, it will be taken
   * as 0.
   * @param num1
   * @param num2
   * @return -1 if num1 is less than num2, 0 if num1 is equal to num2 and 1 if
   * num1 is greater than num2.
   */
  @Override
  public int compare(Number num1, Number num2) {
    return Long.compare((num1 == null) ? 0L : num1.longValue(),
        (num2 == null) ? 0L : num2.longValue());
  }

  @Override
  public Number add(Number num1, Number num2, Number...numbers) {
    long sum = ((num1 == null) ? 0L : num1.longValue()) +
        ((num2 == null) ? 0L : num2.longValue());
    for (Number num : numbers) {
      sum = sum + ((num == null) ? 0L : num.longValue());
    }
    return sum;
  }
}
