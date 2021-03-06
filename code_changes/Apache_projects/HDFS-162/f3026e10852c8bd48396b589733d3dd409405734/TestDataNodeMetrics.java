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
package org.apache.hadoop.hdfs.server.datanode;

import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import static org.apache.hadoop.test.MetricsAsserts.*;

import junit.framework.TestCase;

public class TestDataNodeMetrics extends TestCase {
  
  public void testDataNodeMetrics() throws Exception {
    Configuration conf = new HdfsConfiguration();
    SimulatedFSDataset.setFactory(conf);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    try {
      FileSystem fs = cluster.getFileSystem();
      final long LONG_FILE_LEN = Integer.MAX_VALUE+1L; 
      DFSTestUtil.createFile(fs, new Path("/tmp.txt"),
          LONG_FILE_LEN, (short)1, 1L);
      List<DataNode> datanodes = cluster.getDataNodes();
      assertEquals(datanodes.size(), 1);
      DataNode datanode = datanodes.get(0);
      MetricsRecordBuilder rb = getMetrics(datanode.getMetrics().name());
      assertCounter("BytesWritten", LONG_FILE_LEN, rb);
    } finally {
      if (cluster != null) {cluster.shutdown();}
    }
  }
}
