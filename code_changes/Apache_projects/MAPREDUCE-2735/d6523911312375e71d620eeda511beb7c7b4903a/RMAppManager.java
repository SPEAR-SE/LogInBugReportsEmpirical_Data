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
package org.apache.hadoop.yarn.server.resourcemanager;

import java.io.IOException;
import java.util.List;
import java.util.LinkedList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.security.ApplicationTokenIdentifier;
import org.apache.hadoop.yarn.security.ApplicationTokenSecretManager;
import org.apache.hadoop.yarn.security.client.ClientToAMSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.ApplicationsStore.ApplicationStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRejectedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.util.StringUtils;

/**
 * This class manages the list of applications for the resource manager. 
 */
public class RMAppManager implements EventHandler<RMAppManagerEvent> {

  private static final Log LOG = LogFactory.getLog(RMAppManager.class);

  private int completedAppsMax = RMConfig.DEFAULT_EXPIRE_APPLICATIONS_COMPLETED_MAX;
  private LinkedList<ApplicationId> completedApps = new LinkedList<ApplicationId>();

  private final RMContext rmContext;
  private final ClientToAMSecretManager clientToAMSecretManager;
  private final ApplicationMasterService masterService;
  private final YarnScheduler scheduler;
  private Configuration conf;

  public RMAppManager(RMContext context, ClientToAMSecretManager 
      clientToAMSecretManager, YarnScheduler scheduler, 
      ApplicationMasterService masterService, Configuration conf) {
    this.rmContext = context;
    this.scheduler = scheduler;
    this.clientToAMSecretManager = clientToAMSecretManager;
    this.masterService = masterService;
    this.conf = conf;
    setCompletedAppsMax(conf.getInt(
        RMConfig.EXPIRE_APPLICATIONS_COMPLETED_MAX,
        RMConfig.DEFAULT_EXPIRE_APPLICATIONS_COMPLETED_MAX));
  }

  /**
   *  This class is for logging the application summary.
   */
  static class ApplicationSummary {
    static final Log LOG = LogFactory.getLog(ApplicationSummary.class);

    // Escape sequences 
    static final char EQUALS = '=';
    static final char[] charsToEscape =
      {StringUtils.COMMA, EQUALS, StringUtils.ESCAPE_CHAR};

    static class SummaryBuilder {
      final StringBuilder buffer = new StringBuilder();

      // A little optimization for a very common case
      SummaryBuilder add(String key, long value) {
        return _add(key, Long.toString(value));
      }

      <T> SummaryBuilder add(String key, T value) {
        return _add(key, StringUtils.escapeString(String.valueOf(value),
                    StringUtils.ESCAPE_CHAR, charsToEscape));
      }

      SummaryBuilder add(SummaryBuilder summary) {
        if (buffer.length() > 0) buffer.append(StringUtils.COMMA);
        buffer.append(summary.buffer);
        return this;
      }

      SummaryBuilder _add(String key, String value) {
        if (buffer.length() > 0) buffer.append(StringUtils.COMMA);
        buffer.append(key).append(EQUALS).append(value);
        return this;
      }

      @Override public String toString() {
        return buffer.toString();
      }
    }

    /**
     * create a summary of the application's runtime.
     * 
     * @param app {@link RMApp} whose summary is to be created, cannot
     *            be <code>null</code>.
     */
    public static SummaryBuilder createAppSummary(RMApp app) {
      String trackingUrl = "N/A";
      String host = "N/A";
      RMAppAttempt attempt = app.getCurrentAppAttempt();
      if (attempt != null) {
        trackingUrl = attempt.getTrackingUrl();
        host = attempt.getHost();
      }
      SummaryBuilder summary = new SummaryBuilder()
          .add("appId", app.getApplicationId())
          .add("name", app.getName())
          .add("user", app.getUser())
          .add("queue", app.getQueue())
          .add("state", app.getState())
          .add("trackingUrl", trackingUrl)
          .add("appMasterHost", host)
          .add("startTime", app.getStartTime())
          .add("finishTime", app.getFinishTime());
      return summary;
    }

    /**
     * Log a summary of the application's runtime.
     * 
     * @param app {@link RMApp} whose summary is to be logged
     */
    public static void logAppSummary(RMApp app) {
      if (app != null) {
        LOG.info(createAppSummary(app));
      }
    }
  }

  protected void setCompletedAppsMax(int max) {
    this.completedAppsMax = max;
  }

  protected synchronized int getCompletedAppsListSize() {
    return this.completedApps.size(); 
  }

  protected synchronized void addCompletedApp(ApplicationId appId) {
    if (appId == null) {
      LOG.error("RMAppManager received completed appId of null, skipping");
    } else {
      completedApps.add(appId);  
    }
  };

  /*
   * check to see if hit the limit for max # completed apps kept
   */
  protected synchronized void checkAppNumCompletedLimit() {
    while (completedApps.size() > this.completedAppsMax) {
      ApplicationId removeId = completedApps.remove();  
      LOG.info("Application should be expired, max # apps"
          + " met. Removing app: " + removeId); 
      rmContext.getRMApps().remove(removeId);
    }
  }

  protected void submitApplication(ApplicationSubmissionContext submissionContext) {
    ApplicationId applicationId = submissionContext.getApplicationId();
    RMApp application = null;
    try {
      String clientTokenStr = null;
      String user = UserGroupInformation.getCurrentUser().getShortUserName();
      if (UserGroupInformation.isSecurityEnabled()) {
        Token<ApplicationTokenIdentifier> clientToken = new 
            Token<ApplicationTokenIdentifier>(
            new ApplicationTokenIdentifier(applicationId),
            this.clientToAMSecretManager);
        clientTokenStr = clientToken.encodeToUrlString();
        LOG.debug("Sending client token as " + clientTokenStr);
      }
      submissionContext.setQueue(submissionContext.getQueue() == null
          ? "default" : submissionContext.getQueue());
      submissionContext.setApplicationName(submissionContext
          .getApplicationName() == null ? "N/A" : submissionContext
          .getApplicationName());
      ApplicationStore appStore = rmContext.getApplicationsStore()
          .createApplicationStore(submissionContext.getApplicationId(),
          submissionContext);
      application = new RMAppImpl(applicationId, rmContext,
          this.conf, submissionContext.getApplicationName(), user,
          submissionContext.getQueue(), submissionContext, clientTokenStr,
          appStore, rmContext.getAMLivelinessMonitor(), this.scheduler,
          this.masterService);

      if (rmContext.getRMApps().putIfAbsent(applicationId, application) != null) {
        LOG.info("Application with id " + applicationId + 
            " is already present! Cannot add a duplicate!");
        // don't send event through dispatcher as it will be handled by app already
        // present with this id.
        application.handle(new RMAppRejectedEvent(applicationId,
            "Application with this id is already present! Cannot add a duplicate!"));
      } else {
        this.rmContext.getDispatcher().getEventHandler().handle(
            new RMAppEvent(applicationId, RMAppEventType.START));
      }
    } catch (IOException ie) {
        LOG.info("RMAppManager submit application exception", ie);
        if (application != null) {
          this.rmContext.getDispatcher().getEventHandler().handle(
              new RMAppRejectedEvent(applicationId, ie.getMessage()));
        }
    }
  }

  @Override
  public void handle(RMAppManagerEvent event) {
    ApplicationId appID = event.getApplicationId();
    LOG.debug("RMAppManager processing event for " 
        + appID + " of type " + event.getType());
    switch(event.getType()) {
      case APP_COMPLETED: 
      {
        addCompletedApp(appID);
        ApplicationSummary.logAppSummary(rmContext.getRMApps().get(appID));
        checkAppNumCompletedLimit(); 
      } 
      break;
      case APP_SUBMIT:
      {
        ApplicationSubmissionContext submissionContext = 
            ((RMAppManagerSubmitEvent)event).getSubmissionContext();        
        submitApplication(submissionContext);
      }
      break;
      default:
        LOG.error("Invalid eventtype " + event.getType() + ". Ignoring!");
      }
  }
}
