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

package org.apache.hadoop.yarn.logaggregation;

import static org.junit.Assert.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.Test;

public class TestLogDumper {
  @Test
  public void testFailResultCodes() throws Exception {
    Configuration conf = new YarnConfiguration();
    conf.setClass("fs.file.impl", LocalFileSystem.class, FileSystem.class);
    LogDumper dumper = new LogDumper();
    dumper.setConf(conf);
    
    // verify dumping a non-existent application's logs returns a failure code
    int exitCode = dumper.run( new String[] {
        "-applicationId", "application_0_0" } );
    assertTrue("Should return an error code", exitCode != 0);
    
    // verify dumping a non-existent container log is a failure code 
    exitCode = dumper.dumpAContainersLogs("application_0_0", "container_0_0",
        "nonexistentnode:1234", "nobody");
    assertTrue("Should return an error code", exitCode != 0);
  }
}
