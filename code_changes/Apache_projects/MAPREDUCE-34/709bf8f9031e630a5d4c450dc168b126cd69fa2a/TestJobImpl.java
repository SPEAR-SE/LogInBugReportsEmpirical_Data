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

package org.apache.hadoop.mapreduce.v2.app.job.impl;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.JobACL;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.impl.JobImpl;
import org.apache.hadoop.mapreduce.v2.app.job.impl.JobImpl.JobNoTasksCompletedTransition;
import org.apache.hadoop.mapreduce.v2.app.job.Job;
import org.apache.hadoop.mapreduce.v2.app.job.Task;
import org.apache.hadoop.mapreduce.v2.api.records.JobState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.app.metrics.MRAppMetrics;
import org.apache.hadoop.mapreduce.v2.app.MRApp;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.event.EventHandler;
import org.junit.Test;
import org.junit.Assert;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.any;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;


/**
 * Tests various functions of the JobImpl class
 */
public class TestJobImpl {
  
  @Test
  public void testJobNoTasksTransition() { 
    JobNoTasksCompletedTransition trans = new JobNoTasksCompletedTransition();
    JobImpl mockJob = mock(JobImpl.class);

    // Force checkJobCompleteSuccess to return null
    Task mockTask = mock(Task.class);
    Map<TaskId, Task> tasks = new HashMap<TaskId, Task>();
    tasks.put(mockTask.getID(), mockTask);
    mockJob.tasks = tasks;

    when(mockJob.getState()).thenReturn(JobState.ERROR);
    JobEvent mockJobEvent = mock(JobEvent.class);
    JobState state = trans.transition(mockJob, mockJobEvent);
    Assert.assertEquals("Incorrect state returned from JobNoTasksCompletedTransition",
        JobState.ERROR, state);
  }

  @Test
  public void testCheckJobCompleteSuccess() {
    
    JobImpl mockJob = mock(JobImpl.class);
    mockJob.tasks = new HashMap<TaskId, Task>();
    OutputCommitter mockCommitter = mock(OutputCommitter.class);
    EventHandler mockEventHandler = mock(EventHandler.class);
    JobContext mockJobContext = mock(JobContext.class);
    
    when(mockJob.getCommitter()).thenReturn(mockCommitter);
    when(mockJob.getEventHandler()).thenReturn(mockEventHandler);
    when(mockJob.getJobContext()).thenReturn(mockJobContext);
    doNothing().when(mockJob).setFinishTime();
    doNothing().when(mockJob).logJobHistoryFinishedEvent();
    when(mockJob.finished(any(JobState.class))).thenReturn(JobState.SUCCEEDED);

    try {
      doNothing().when(mockCommitter).commitJob(any(JobContext.class));
    } catch (IOException e) {
      // commitJob stubbed out, so this can't happen
    }
    doNothing().when(mockEventHandler).handle(any(JobHistoryEvent.class));
    Assert.assertNotNull("checkJobCompleteSuccess incorrectly returns null " +
      "for successful job",
      JobImpl.checkJobCompleteSuccess(mockJob));
    Assert.assertEquals("checkJobCompleteSuccess returns incorrect state",
        JobImpl.checkJobCompleteSuccess(mockJob), JobState.SUCCEEDED);    
  }

  @Test
  public void testCheckJobCompleteSuccessFailed() {
    JobImpl mockJob = mock(JobImpl.class);

    // Make the completedTasks not equal the getTasks()
    Task mockTask = mock(Task.class);
    Map<TaskId, Task> tasks = new HashMap<TaskId, Task>();
    tasks.put(mockTask.getID(), mockTask);
    mockJob.tasks = tasks;
    
    try {
      // Just in case the code breaks and reaches these calls
      OutputCommitter mockCommitter = mock(OutputCommitter.class);
      EventHandler mockEventHandler = mock(EventHandler.class);
      doNothing().when(mockCommitter).commitJob(any(JobContext.class));
      doNothing().when(mockEventHandler).handle(any(JobHistoryEvent.class));
    } catch (IOException e) {
      e.printStackTrace();    
    }
    Assert.assertNull("checkJobCompleteSuccess incorrectly returns not-null " +
      "for unsuccessful job",
      JobImpl.checkJobCompleteSuccess(mockJob));
  }


  public static void main(String[] args) throws Exception {
    TestJobImpl t = new TestJobImpl();
    t.testJobNoTasksTransition();
    t.testCheckJobCompleteSuccess();
    t.testCheckJobCompleteSuccessFailed();
  }

  @Test
  public void testCheckAccess() {
    // Create two unique users
    String user1 = System.getProperty("user.name");
    String user2 = user1 + "1234";
    UserGroupInformation ugi1 = UserGroupInformation.createRemoteUser(user1);
    UserGroupInformation ugi2 = UserGroupInformation.createRemoteUser(user2);

    // Create the job
    JobID jobID = JobID.forName("job_1234567890000_0001");
    JobId jobId = TypeConverter.toYarn(jobID);

    // Setup configuration access only to user1 (owner)
    Configuration conf1 = new Configuration();
    conf1.setBoolean(MRConfig.MR_ACLS_ENABLED, true);
    conf1.set(MRJobConfig.JOB_ACL_VIEW_JOB, "");

    // Verify access
    JobImpl job1 = new JobImpl(jobId, null, conf1, null, null, null, null, null,
        null, null, null, true, null, 0, null);
    Assert.assertTrue(job1.checkAccess(ugi1, JobACL.VIEW_JOB));
    Assert.assertFalse(job1.checkAccess(ugi2, JobACL.VIEW_JOB));

    // Setup configuration access to the user1 (owner) and user2
    Configuration conf2 = new Configuration();
    conf2.setBoolean(MRConfig.MR_ACLS_ENABLED, true);
    conf2.set(MRJobConfig.JOB_ACL_VIEW_JOB, user2);

    // Verify access
    JobImpl job2 = new JobImpl(jobId, null, conf2, null, null, null, null, null,
        null, null, null, true, null, 0, null);
    Assert.assertTrue(job2.checkAccess(ugi1, JobACL.VIEW_JOB));
    Assert.assertTrue(job2.checkAccess(ugi2, JobACL.VIEW_JOB));

    // Setup configuration access with security enabled and access to all
    Configuration conf3 = new Configuration();
    conf3.setBoolean(MRConfig.MR_ACLS_ENABLED, true);
    conf3.set(MRJobConfig.JOB_ACL_VIEW_JOB, "*");

    // Verify access
    JobImpl job3 = new JobImpl(jobId, null, conf3, null, null, null, null, null,
        null, null, null, true, null, 0, null);
    Assert.assertTrue(job3.checkAccess(ugi1, JobACL.VIEW_JOB));
    Assert.assertTrue(job3.checkAccess(ugi2, JobACL.VIEW_JOB));

    // Setup configuration access without security enabled
    Configuration conf4 = new Configuration();
    conf4.setBoolean(MRConfig.MR_ACLS_ENABLED, false);
    conf4.set(MRJobConfig.JOB_ACL_VIEW_JOB, "");

    // Verify access
    JobImpl job4 = new JobImpl(jobId, null, conf4, null, null, null, null, null,
        null, null, null, true, null, 0, null);
    Assert.assertTrue(job4.checkAccess(ugi1, JobACL.VIEW_JOB));
    Assert.assertTrue(job4.checkAccess(ugi2, JobACL.VIEW_JOB));
  }
}
