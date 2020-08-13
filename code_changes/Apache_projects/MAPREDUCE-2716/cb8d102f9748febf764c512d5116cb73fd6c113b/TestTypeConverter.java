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
package org.apache.hadoop.mapreduce;

import junit.framework.Assert;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationState;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationIdPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationReportPBImpl;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.Test;

public class TestTypeConverter {
  @Test
  public void testFromYarn() throws Exception {
    int appStartTime = 612354;
    ApplicationState state = ApplicationState.RUNNING;
    ApplicationId applicationId = new ApplicationIdPBImpl();
    ApplicationReportPBImpl applicationReport = new ApplicationReportPBImpl();
    applicationReport.setApplicationId(applicationId);
    applicationReport.setState(state);
    applicationReport.setStartTime(appStartTime);
    applicationReport.setUser("TestTypeConverter-user");
    JobStatus jobStatus = TypeConverter.fromYarn(applicationReport, "dummy-jobfile");
    Assert.assertEquals(appStartTime, jobStatus.getStartTime());
    Assert.assertEquals(state.toString(), jobStatus.getState().toString());
  }

  @Test
  public void testFromYarnApplicationReport() {
    ApplicationId mockAppId = mock(ApplicationId.class);
    when(mockAppId.getClusterTimestamp()).thenReturn(12345L);
    when(mockAppId.getId()).thenReturn(6789);

    ApplicationReport mockReport = mock(ApplicationReport.class);
    when(mockReport.getTrackingUrl()).thenReturn("dummy-tracking-url");
    when(mockReport.getApplicationId()).thenReturn(mockAppId);
    when(mockReport.getState()).thenReturn(ApplicationState.KILLED);
    when(mockReport.getUser()).thenReturn("dummy-user");
    when(mockReport.getQueue()).thenReturn("dummy-queue");
    String jobFile = "dummy-path/job.xml";
    JobStatus status = TypeConverter.fromYarn(mockReport, jobFile);
    Assert.assertNotNull("fromYarn returned null status", status);
    Assert.assertEquals("jobFile set incorrectly", "dummy-path/job.xml", status.getJobFile());
    Assert.assertEquals("queue set incorrectly", "dummy-queue", status.getQueue());
    Assert.assertEquals("trackingUrl set incorrectly", "dummy-tracking-url", status.getTrackingUrl());
    Assert.assertEquals("user set incorrectly", "dummy-user", status.getUsername());
    Assert.assertEquals("schedulingInfo set incorrectly", "dummy-tracking-url", status.getSchedulingInfo());
    Assert.assertEquals("jobId set incorrectly", 6789, status.getJobID().getId());
    Assert.assertEquals("state set incorrectly", JobStatus.State.KILLED, status.getState());
  }
}
