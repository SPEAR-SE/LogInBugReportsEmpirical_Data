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

package org.apache.hadoop.yarn.server.applicationhistoryservice;

import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptReport;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerReport;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationAttemptState;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvent;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.metrics.AppAttemptMetricsConstants;
import org.apache.hadoop.yarn.server.metrics.ApplicationMetricsConstants;
import org.apache.hadoop.yarn.server.metrics.ContainerMetricsConstants;
import org.apache.hadoop.yarn.server.security.ApplicationACLsManager;
import org.apache.hadoop.yarn.server.timeline.MemoryTimelineStore;
import org.apache.hadoop.yarn.server.timeline.TimelineDataManager;
import org.apache.hadoop.yarn.server.timeline.TimelineStore;
import org.apache.hadoop.yarn.server.timeline.security.TimelineACLsManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestApplicationHistoryManagerOnTimelineStore {

  private static final int SCALE = 5;
  private static TimelineStore store;

  private ApplicationHistoryManagerOnTimelineStore historyManager;
  private UserGroupInformation callerUGI;
  private Configuration conf;

  @BeforeClass
  public static void prepareStore() throws Exception {
    store = new MemoryTimelineStore();
    prepareTimelineStore(store);
  }

  @Before
  public void setup() throws Exception {
    // Only test the ACLs of the generic history
    TimelineACLsManager aclsManager = new TimelineACLsManager(new YarnConfiguration());
    TimelineDataManager dataManager =
        new TimelineDataManager(store, aclsManager);
    ApplicationACLsManager appAclsManager = new ApplicationACLsManager(conf);
    historyManager =
        new ApplicationHistoryManagerOnTimelineStore(dataManager, appAclsManager);
    historyManager.init(conf);
    historyManager.start();
  }

  @After
  public void tearDown() {
    if (historyManager != null) {
      historyManager.stop();
    }
  }

  @Parameters
  public static Collection<Object[]> callers() {
    // user1 is the owner
    // user2 is the authorized user
    // user3 is the unauthorized user
    // admin is the admin acl
    return Arrays.asList(
        new Object[][] { { "" }, { "user1" }, { "user2" }, { "user3" }, { "admin" } });
  }

  public TestApplicationHistoryManagerOnTimelineStore(String caller) {
    conf = new YarnConfiguration();
    if (!caller.equals("")) {
      callerUGI = UserGroupInformation.createRemoteUser(caller, AuthMethod.SIMPLE);
      conf.setBoolean(YarnConfiguration.YARN_ACL_ENABLE, true);
      conf.set(YarnConfiguration.YARN_ADMIN_ACL, "admin");
    }
  }

  private static void prepareTimelineStore(TimelineStore store)
      throws Exception {
    for (int i = 1; i <= SCALE; ++i) {
      TimelineEntities entities = new TimelineEntities();
      ApplicationId appId = ApplicationId.newInstance(0, i);
      if (i == 2) {
        entities.addEntity(createApplicationTimelineEntity(appId, true));
      } else {
        entities.addEntity(createApplicationTimelineEntity(appId, false));
      }
      store.put(entities);
      for (int j = 1; j <= SCALE; ++j) {
        entities = new TimelineEntities();
        ApplicationAttemptId appAttemptId =
            ApplicationAttemptId.newInstance(appId, j);
        entities.addEntity(createAppAttemptTimelineEntity(appAttemptId));
        store.put(entities);
        for (int k = 1; k <= SCALE; ++k) {
          entities = new TimelineEntities();
          ContainerId containerId = ContainerId.newInstance(appAttemptId, k);
          entities.addEntity(createContainerEntity(containerId));
          store.put(entities);
        }
      }
    }
  }

  @Test
  public void testGetApplicationReport() throws Exception {
    for (int i = 1; i <= 2; ++i) {
      final ApplicationId appId = ApplicationId.newInstance(0, i);
      ApplicationReport app;
      if (callerUGI == null) {
        app = historyManager.getApplication(appId);
      } else {
        app =
            callerUGI.doAs(new PrivilegedExceptionAction<ApplicationReport> () {
          @Override
          public ApplicationReport run() throws Exception {
            return historyManager.getApplication(appId);
          }
        });
      }
      Assert.assertNotNull(app);
      Assert.assertEquals(appId, app.getApplicationId());
      Assert.assertEquals("test app", app.getName());
      Assert.assertEquals("test app type", app.getApplicationType());
      Assert.assertEquals("user1", app.getUser());
      Assert.assertEquals("test queue", app.getQueue());
      Assert.assertEquals(Integer.MAX_VALUE + 2L, app.getStartTime());
      Assert.assertEquals(Integer.MAX_VALUE + 3L, app.getFinishTime());
      Assert.assertTrue(Math.abs(app.getProgress() - 1.0F) < 0.0001);
      // App 2 doesn't have the ACLs, such that the default ACLs " " will be used.
      // Nobody except admin and owner has access to the details of the app.
      if ((i ==  1 && callerUGI != null &&
          callerUGI.getShortUserName().equals("user3")) ||
          (i ==  2 && callerUGI != null &&
          (callerUGI.getShortUserName().equals("user2") ||
              callerUGI.getShortUserName().equals("user3")))) {
        Assert.assertEquals(ApplicationAttemptId.newInstance(appId, -1),
            app.getCurrentApplicationAttemptId());
        Assert.assertEquals(null, app.getHost());
        Assert.assertEquals(-1, app.getRpcPort());
        Assert.assertEquals(null, app.getTrackingUrl());
        Assert.assertEquals(null, app.getOriginalTrackingUrl());
        Assert.assertEquals(null, app.getDiagnostics());
      } else {
        Assert.assertEquals(ApplicationAttemptId.newInstance(appId, 1),
            app.getCurrentApplicationAttemptId());
        Assert.assertEquals("test host", app.getHost());
        Assert.assertEquals(-100, app.getRpcPort());
        Assert.assertEquals("test tracking url", app.getTrackingUrl());
        Assert.assertEquals("test original tracking url",
            app.getOriginalTrackingUrl());
        Assert.assertEquals("test diagnostics info", app.getDiagnostics());
      }
      Assert.assertEquals(FinalApplicationStatus.UNDEFINED,
          app.getFinalApplicationStatus());
      Assert.assertEquals(YarnApplicationState.FINISHED,
          app.getYarnApplicationState());
    }
  }

  @Test
  public void testGetApplicationAttemptReport() throws Exception {
    final ApplicationAttemptId appAttemptId =
        ApplicationAttemptId.newInstance(ApplicationId.newInstance(0, 1), 1);
    ApplicationAttemptReport appAttempt;
    if (callerUGI == null) {
      appAttempt = historyManager.getApplicationAttempt(appAttemptId);
    } else {
      try {
        appAttempt =
            callerUGI.doAs(new PrivilegedExceptionAction<ApplicationAttemptReport> () {
          @Override
          public ApplicationAttemptReport run() throws Exception {
            return historyManager.getApplicationAttempt(appAttemptId);
          }
        });
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          // The exception is expected
          Assert.fail();
        }
      } catch (UndeclaredThrowableException e) {
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          if (e.getCause().getMessage().contains(
              "does not have privilage to see this application")) {
            // The exception is expected
            return;
          }
        }
        throw e;
      }
    }
    Assert.assertNotNull(appAttempt);
    Assert.assertEquals(appAttemptId, appAttempt.getApplicationAttemptId());
    Assert.assertEquals(ContainerId.newInstance(appAttemptId, 1),
        appAttempt.getAMContainerId());
    Assert.assertEquals("test host", appAttempt.getHost());
    Assert.assertEquals(-100, appAttempt.getRpcPort());
    Assert.assertEquals("test tracking url", appAttempt.getTrackingUrl());
    Assert.assertEquals("test original tracking url",
        appAttempt.getOriginalTrackingUrl());
    Assert.assertEquals("test diagnostics info", appAttempt.getDiagnostics());
    Assert.assertEquals(YarnApplicationAttemptState.FINISHED,
        appAttempt.getYarnApplicationAttemptState());
  }

  @Test
  public void testGetContainerReport() throws Exception {
    final ContainerId containerId =
        ContainerId.newInstance(ApplicationAttemptId.newInstance(
            ApplicationId.newInstance(0, 1), 1), 1);
    ContainerReport container;
    if (callerUGI == null) {
      container = historyManager.getContainer(containerId);
    } else {
      try {
        container =
            callerUGI.doAs(new PrivilegedExceptionAction<ContainerReport> () {
          @Override
          public ContainerReport run() throws Exception {
            return historyManager.getContainer(containerId);
          }
        });
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          // The exception is expected
          Assert.fail();
        }
      } catch (UndeclaredThrowableException e) {
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          if (e.getCause().getMessage().contains(
              "does not have privilage to see this application")) {
            // The exception is expected
            return;
          }
        }
        throw e;
      }
    }
    Assert.assertNotNull(container);
    Assert.assertEquals(Integer.MAX_VALUE + 1L, container.getCreationTime());
    Assert.assertEquals(Integer.MAX_VALUE + 2L, container.getFinishTime());
    Assert.assertEquals(Resource.newInstance(-1, -1),
        container.getAllocatedResource());
    Assert.assertEquals(NodeId.newInstance("test host", -100),
        container.getAssignedNode());
    Assert.assertEquals(Priority.UNDEFINED, container.getPriority());
    Assert
        .assertEquals("test diagnostics info", container.getDiagnosticsInfo());
    Assert.assertEquals(ContainerState.COMPLETE, container.getContainerState());
    Assert.assertEquals(-1, container.getContainerExitStatus());
    Assert.assertEquals("http://0.0.0.0:8188/applicationhistory/logs/" +
        "test host:-100/container_0_0001_01_000001/"
        + "container_0_0001_01_000001/user1", container.getLogUrl());
  }

  @Test
  public void testGetApplications() throws Exception {
    Collection<ApplicationReport> apps =
        historyManager.getAllApplications().values();
    Assert.assertNotNull(apps);
    Assert.assertEquals(SCALE, apps.size());
  }

  @Test
  public void testGetApplicationAttempts() throws Exception {
    final ApplicationId appId = ApplicationId.newInstance(0, 1);
    Collection<ApplicationAttemptReport> appAttempts;
    if (callerUGI == null) {
      appAttempts = historyManager.getApplicationAttempts(appId).values();
    } else {
      try {
        appAttempts = callerUGI.doAs(
            new PrivilegedExceptionAction<Collection<ApplicationAttemptReport>> () {
          @Override
          public Collection<ApplicationAttemptReport> run() throws Exception {
            return historyManager.getApplicationAttempts(appId).values();
          }
        });
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          // The exception is expected
          Assert.fail();
        }
      } catch (UndeclaredThrowableException e) {
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          if (e.getCause().getMessage().contains(
              "does not have privilage to see this application")) {
            // The exception is expected
            return;
          }
        }
        throw e;
      }
    }
    Assert.assertNotNull(appAttempts);
    Assert.assertEquals(SCALE, appAttempts.size());
  }

  @Test
  public void testGetContainers() throws Exception {
    final ApplicationAttemptId appAttemptId =
        ApplicationAttemptId.newInstance(ApplicationId.newInstance(0, 1), 1);
    Collection<ContainerReport> containers;
    if (callerUGI == null) {
      containers = historyManager.getContainers(appAttemptId).values();
    } else {
      try {
        containers = callerUGI.doAs(
            new PrivilegedExceptionAction<Collection<ContainerReport>> () {
          @Override
          public Collection<ContainerReport> run() throws Exception {
            return historyManager.getContainers(appAttemptId).values();
          }
        });
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          // The exception is expected
          Assert.fail();
        }
      } catch (UndeclaredThrowableException e) {
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          if (e.getCause().getMessage().contains(
              "does not have privilage to see this application")) {
            // The exception is expected
            return;
          }
        }
        throw e;
      }
    }
    Assert.assertNotNull(containers);
    Assert.assertEquals(SCALE, containers.size());
  }

  @Test
  public void testGetAMContainer() throws Exception {
    final ApplicationAttemptId appAttemptId =
        ApplicationAttemptId.newInstance(ApplicationId.newInstance(0, 1), 1);
    ContainerReport container;
    if (callerUGI == null) {
      container = historyManager.getAMContainer(appAttemptId);
    } else {
      try {
        container =
            callerUGI.doAs(new PrivilegedExceptionAction<ContainerReport> () {
          @Override
          public ContainerReport run() throws Exception {
            return historyManager.getAMContainer(appAttemptId);
          }
        });
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          // The exception is expected
          Assert.fail();
        }
      } catch (UndeclaredThrowableException e) {
        if (callerUGI != null && callerUGI.getShortUserName().equals("user3")) {
          if (e.getCause().getMessage().contains(
              "does not have privilage to see this application")) {
            // The exception is expected
            return;
          }
        }
        throw e;
      }
    }
    Assert.assertNotNull(container);
    Assert.assertEquals(appAttemptId, container.getContainerId()
        .getApplicationAttemptId());
  }

  private static TimelineEntity createApplicationTimelineEntity(
      ApplicationId appId, boolean emptyACLs) {
    TimelineEntity entity = new TimelineEntity();
    entity.setEntityType(ApplicationMetricsConstants.ENTITY_TYPE);
    entity.setEntityId(appId.toString());
    entity.addPrimaryFilter(
        TimelineStore.SystemFilter.ENTITY_OWNER.toString(), "yarn");
    Map<String, Object> entityInfo = new HashMap<String, Object>();
    entityInfo.put(ApplicationMetricsConstants.NAME_ENTITY_INFO, "test app");
    entityInfo.put(ApplicationMetricsConstants.TYPE_ENTITY_INFO,
        "test app type");
    entityInfo.put(ApplicationMetricsConstants.USER_ENTITY_INFO, "user1");
    entityInfo.put(ApplicationMetricsConstants.QUEUE_ENTITY_INFO, "test queue");
    entityInfo.put(ApplicationMetricsConstants.SUBMITTED_TIME_ENTITY_INFO,
        Integer.MAX_VALUE + 1L);
    if (emptyACLs) {
      entityInfo.put(ApplicationMetricsConstants.APP_VIEW_ACLS_ENTITY_INFO, "");
    } else {
      entityInfo.put(ApplicationMetricsConstants.APP_VIEW_ACLS_ENTITY_INFO,
          "user2");
    }
    entity.setOtherInfo(entityInfo);
    TimelineEvent tEvent = new TimelineEvent();
    tEvent.setEventType(ApplicationMetricsConstants.CREATED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 2L);
    entity.addEvent(tEvent);
    tEvent = new TimelineEvent();
    tEvent.setEventType(
        ApplicationMetricsConstants.FINISHED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 3L);
    Map<String, Object> eventInfo = new HashMap<String, Object>();
    eventInfo.put(ApplicationMetricsConstants.DIAGNOSTICS_INFO_EVENT_INFO,
        "test diagnostics info");
    eventInfo.put(ApplicationMetricsConstants.FINAL_STATUS_EVENT_INFO,
        FinalApplicationStatus.UNDEFINED.toString());
    eventInfo.put(ApplicationMetricsConstants.STATE_EVENT_INFO,
        YarnApplicationState.FINISHED.toString());
    eventInfo.put(ApplicationMetricsConstants.LATEST_APP_ATTEMPT_EVENT_INFO,
        ApplicationAttemptId.newInstance(appId, 1));
    tEvent.setEventInfo(eventInfo);
    entity.addEvent(tEvent);
    return entity;
  }

  private static TimelineEntity createAppAttemptTimelineEntity(
      ApplicationAttemptId appAttemptId) {
    TimelineEntity entity = new TimelineEntity();
    entity.setEntityType(AppAttemptMetricsConstants.ENTITY_TYPE);
    entity.setEntityId(appAttemptId.toString());
    entity.addPrimaryFilter(AppAttemptMetricsConstants.PARENT_PRIMARY_FILTER,
        appAttemptId.getApplicationId().toString());
    entity.addPrimaryFilter(
        TimelineStore.SystemFilter.ENTITY_OWNER.toString(), "yarn");
    TimelineEvent tEvent = new TimelineEvent();
    tEvent.setEventType(AppAttemptMetricsConstants.REGISTERED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 1L);
    Map<String, Object> eventInfo = new HashMap<String, Object>();
    eventInfo.put(AppAttemptMetricsConstants.TRACKING_URL_EVENT_INFO,
        "test tracking url");
    eventInfo.put(AppAttemptMetricsConstants.ORIGINAL_TRACKING_URL_EVENT_INFO,
        "test original tracking url");
    eventInfo.put(AppAttemptMetricsConstants.HOST_EVENT_INFO, "test host");
    eventInfo.put(AppAttemptMetricsConstants.RPC_PORT_EVENT_INFO, -100);
    eventInfo.put(AppAttemptMetricsConstants.MASTER_CONTAINER_EVENT_INFO,
        ContainerId.newInstance(appAttemptId, 1));
    tEvent.setEventInfo(eventInfo);
    entity.addEvent(tEvent);
    tEvent = new TimelineEvent();
    tEvent.setEventType(AppAttemptMetricsConstants.FINISHED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 2L);
    eventInfo = new HashMap<String, Object>();
    eventInfo.put(AppAttemptMetricsConstants.TRACKING_URL_EVENT_INFO,
        "test tracking url");
    eventInfo.put(AppAttemptMetricsConstants.ORIGINAL_TRACKING_URL_EVENT_INFO,
        "test original tracking url");
    eventInfo.put(AppAttemptMetricsConstants.DIAGNOSTICS_INFO_EVENT_INFO,
        "test diagnostics info");
    eventInfo.put(AppAttemptMetricsConstants.FINAL_STATUS_EVENT_INFO,
        FinalApplicationStatus.UNDEFINED.toString());
    eventInfo.put(AppAttemptMetricsConstants.STATE_EVENT_INFO,
        YarnApplicationAttemptState.FINISHED.toString());
    tEvent.setEventInfo(eventInfo);
    entity.addEvent(tEvent);
    return entity;
  }

  private static TimelineEntity createContainerEntity(ContainerId containerId) {
    TimelineEntity entity = new TimelineEntity();
    entity.setEntityType(ContainerMetricsConstants.ENTITY_TYPE);
    entity.setEntityId(containerId.toString());
    entity.addPrimaryFilter(ContainerMetricsConstants.PARENT_PRIMARIY_FILTER,
        containerId.getApplicationAttemptId().toString());
    entity.addPrimaryFilter(
        TimelineStore.SystemFilter.ENTITY_OWNER.toString(), "yarn");
    Map<String, Object> entityInfo = new HashMap<String, Object>();
    entityInfo.put(ContainerMetricsConstants.ALLOCATED_MEMORY_ENTITY_INFO, -1);
    entityInfo.put(ContainerMetricsConstants.ALLOCATED_VCORE_ENTITY_INFO, -1);
    entityInfo.put(ContainerMetricsConstants.ALLOCATED_HOST_ENTITY_INFO,
        "test host");
    entityInfo.put(ContainerMetricsConstants.ALLOCATED_PORT_ENTITY_INFO, -100);
    entityInfo
        .put(ContainerMetricsConstants.ALLOCATED_PRIORITY_ENTITY_INFO, -1);
    entity.setOtherInfo(entityInfo);
    TimelineEvent tEvent = new TimelineEvent();
    tEvent.setEventType(ContainerMetricsConstants.CREATED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 1L);
    entity.addEvent(tEvent);
    ;
    tEvent = new TimelineEvent();
    tEvent.setEventType(ContainerMetricsConstants.FINISHED_EVENT_TYPE);
    tEvent.setTimestamp(Integer.MAX_VALUE + 2L);
    Map<String, Object> eventInfo = new HashMap<String, Object>();
    eventInfo.put(ContainerMetricsConstants.DIAGNOSTICS_INFO_EVENT_INFO,
        "test diagnostics info");
    eventInfo.put(ContainerMetricsConstants.EXIT_STATUS_EVENT_INFO, -1);
    eventInfo.put(ContainerMetricsConstants.STATE_EVENT_INFO,
        ContainerState.COMPLETE.toString());
    tEvent.setEventInfo(eventInfo);
    entity.addEvent(tEvent);
    return entity;
  }
}
