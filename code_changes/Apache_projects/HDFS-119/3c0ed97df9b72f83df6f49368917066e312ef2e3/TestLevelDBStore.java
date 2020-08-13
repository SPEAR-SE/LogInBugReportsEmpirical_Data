/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.utils.LevelDBKeyFilters.KeyPrefixFilter;
import org.apache.hadoop.utils.LevelDBKeyFilters.LevelDBKeyFilter;
import org.apache.hadoop.utils.LevelDBStore;
import org.junit.Rule;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Assert;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Test class for {@link org.apache.hadoop.utils.LevelDBStore}.
 */
public class TestLevelDBStore {

  private LevelDBStore store;
  private File testDir;

  private final static int MAX_GETRANGE_LENGTH = 100;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void init() throws IOException {
    testDir = GenericTestUtils.getTestDir(getClass().getSimpleName());
    store = new LevelDBStore(testDir, true);

    // Add 20 entries.
    // {a0 : a-value0} to {a9 : a-value9}
    // {b0 : a-value0} to {b0 : b-value9}
    for (int i=0; i<10; i++) {
      store.put(getBytes("a" + i), getBytes("a-value" + i));
      store.put(getBytes("b" + i), getBytes("b-value" + i));
    }
  }

  @After
  public void cleanup() throws IOException {
    store.close();
    store.destroy();
    FileUtils.deleteDirectory(testDir);
  }

  private byte[] getBytes(String str) {
    return DFSUtilClient.string2Bytes(str);
  }

  private String getString(byte[] bytes) {
    return DFSUtilClient.bytes2String(bytes);
  }

  @Test
  public void testGetRangeKVs() throws IOException {
    List<Map.Entry<byte[], byte[]>> result = null;

    // Set empty startKey will return values from beginning.
    result = store.getRangeKVs(null, 5);
    Assert.assertEquals(5, result.size());
    Assert.assertEquals("a-value2", getString(result.get(2).getValue()));

    // Returns max available entries after a valid startKey.
    result = store.getRangeKVs(getBytes("b0"), MAX_GETRANGE_LENGTH);
    Assert.assertEquals(10, result.size());
    Assert.assertEquals("b0", getString(result.get(0).getKey()));
    Assert.assertEquals("b-value0", getString(result.get(0).getValue()));
    result = store.getRangeKVs(getBytes("b0"), 5);
    Assert.assertEquals(5, result.size());

    // Both startKey and count are honored.
    result = store.getRangeKVs(getBytes("a9"), 2);
    Assert.assertEquals(2, result.size());
    Assert.assertEquals("a9", getString(result.get(0).getKey()));
    Assert.assertEquals("a-value9", getString(result.get(0).getValue()));
    Assert.assertEquals("b0", getString(result.get(1).getKey()));
    Assert.assertEquals("b-value0", getString(result.get(1).getValue()));

    // Filter keys by prefix.
    // It should returns all "b*" entries.
    LevelDBKeyFilter filter1 = new KeyPrefixFilter("b");
    result = store.getRangeKVs(null, 100, filter1);
    Assert.assertEquals(10, result.size());
    Assert.assertTrue(result.stream().allMatch(entry ->
        new String(entry.getKey()).startsWith("b")
    ));
    result = store.getRangeKVs(null, 3, filter1);
    Assert.assertEquals(3, result.size());
    result = store.getRangeKVs(getBytes("b3"), 1, filter1);
    Assert.assertEquals("b-value3", getString(result.get(0).getValue()));

    // Define a customized filter that filters keys by suffix.
    // Returns all "*2" entries.
    LevelDBKeyFilter filter2 = (preKey, currentKey, nextKey)
        -> getString(currentKey).endsWith("2");
    result = store.getRangeKVs(null, MAX_GETRANGE_LENGTH, filter2);
    Assert.assertEquals(2, result.size());
    Assert.assertEquals("a2", getString(result.get(0).getKey()));
    Assert.assertEquals("b2", getString(result.get(1).getKey()));
    result = store.getRangeKVs(null, 1, filter2);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("a2", getString(result.get(0).getKey()));

    // Apply multiple filters.
    result = store.getRangeKVs(null, MAX_GETRANGE_LENGTH, filter1, filter2);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("b2", getString(result.get(0).getKey()));
    Assert.assertEquals("b-value2", getString(result.get(0).getValue()));

    // If filter is null, no effect.
    result = store.getRangeKVs(null, 1, null);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("a0", getString(result.get(0).getKey()));
  }

  @Test
  public void testGetRangeLength() throws IOException {
    List<Map.Entry<byte[], byte[]>> result = null;

    result = store.getRangeKVs(null, 0);
    Assert.assertEquals(0, result.size());

    result = store.getRangeKVs(null, 1);
    Assert.assertEquals(1, result.size());

    // Count less than zero is invalid.
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Invalid count given");
    store.getRangeKVs(null, -1);
  }

  @Test
  public void testInvalidStartKey() throws IOException {
    // If startKey is invalid, throws an invalid key exception.
    expectedException.expect(IOException.class);
    expectedException.expectMessage("Invalid start key");
    store.getRangeKVs(getBytes("unknownKey"), MAX_GETRANGE_LENGTH);
  }
}
