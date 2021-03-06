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
package org.apache.hadoop.hdfs.server.namenode;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.junit.Assert.*;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.Writable;

import java.net.URI;
import java.io.IOException;

public class TestGenericJournalConf {
  /** 
   * Test that an exception is thrown if a journal class doesn't exist
   * in the configuration 
   */
  @Test(expected=IllegalArgumentException.class)
  public void testNotConfigured() throws Exception {
    MiniDFSCluster cluster = null;
    Configuration conf = new Configuration();

    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
             "dummy://test");
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test that an exception is thrown if a journal class doesn't
   * exist in the classloader.
   */
  @Test(expected=IllegalArgumentException.class)
  public void testClassDoesntExist() throws Exception {
    MiniDFSCluster cluster = null;
    Configuration conf = new Configuration();

    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_PLUGIN_PREFIX + ".dummy",
             "org.apache.hadoop.nonexistent");
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
             "dummy://test");

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test that a implementation of JournalManager without a 
   * (Configuration,URI) constructor throws an exception
   */
  @Test
  public void testBadConstructor() throws Exception {
    MiniDFSCluster cluster = null;
    Configuration conf = new Configuration();
    
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_PLUGIN_PREFIX + ".dummy",
             BadConstructorJournalManager.class.getName());
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
             "dummy://test");
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
      fail("Should have failed before this point");
    } catch (IllegalArgumentException iae) {
      if (!iae.getMessage().contains("Unable to construct journal")) {
        fail("Should have failed with unable to construct exception");
      }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test that a dummy implementation of JournalManager can
   * be initialized on startup
   */
  @Test
  public void testDummyJournalManager() throws Exception {
    MiniDFSCluster cluster = null;
    Configuration conf = new Configuration();

    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_PLUGIN_PREFIX + ".dummy",
             DummyJournalManager.class.getName());
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
             "dummy://test");
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_CHECKED_VOLUMES_MINIMUM_KEY, 0);
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      cluster.waitActive();
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  public static class DummyJournalManager implements JournalManager {
    public DummyJournalManager(Configuration conf, URI u) {}
    
    @Override
    public EditLogOutputStream startLogSegment(long txId) throws IOException {
      return mock(EditLogOutputStream.class);
    }
    
    @Override
    public void finalizeLogSegment(long firstTxId, long lastTxId)
        throws IOException {
      // noop
    }

    @Override
    public EditLogInputStream getInputStream(long fromTxnId, boolean inProgressOk)
        throws IOException {
      return null;
    }

    @Override
    public long getNumberOfTransactions(long fromTxnId, boolean inProgressOk)
        throws IOException {
      return 0;
    }

    @Override
    public void setOutputBufferCapacity(int size) {}

    @Override
    public void purgeLogsOlderThan(long minTxIdToKeep)
        throws IOException {}

    @Override
    public void recoverUnfinalizedSegments() throws IOException {}

    @Override
    public void close() throws IOException {}
  }

  public static class BadConstructorJournalManager extends DummyJournalManager {
    public BadConstructorJournalManager() {
      super(null, null);
    }
  }
}
