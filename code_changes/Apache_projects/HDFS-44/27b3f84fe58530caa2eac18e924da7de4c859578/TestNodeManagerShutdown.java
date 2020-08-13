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

package org.apache.hadoop.yarn.server.nodemanager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;

import junit.framework.Assert;

import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerStatusRequest;
import org.apache.hadoop.yarn.api.protocolrecords.StartContainerRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.nodemanager.DefaultContainerExecutor;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.ContainerManagerImpl;
import org.apache.hadoop.yarn.server.nodemanager.metrics.NodeManagerMetrics;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestNodeManagerShutdown {
  static final File basedir =
      new File("target", TestNodeManagerShutdown.class.getName());
  static final File tmpDir = new File(basedir, "tmpDir");
  static final File logsDir = new File(basedir, "logs");
  static final File remoteLogsDir = new File(basedir, "remotelogs");
  static final File nmLocalDir = new File(basedir, "nm0");
  static final File processStartFile = new File(tmpDir, "start_file.txt")
    .getAbsoluteFile();

  static final RecordFactory recordFactory = RecordFactoryProvider
      .getRecordFactory(null);
  static final String user = "nobody";
  private FileContext localFS;
  private ContainerId cId;
  private CyclicBarrier syncBarrier = new CyclicBarrier(2);

  @Before
  public void setup() throws UnsupportedFileSystemException {
    localFS = FileContext.getLocalFSFileContext();
    tmpDir.mkdirs();
    logsDir.mkdirs();
    remoteLogsDir.mkdirs();
    nmLocalDir.mkdirs();

    // Construct the Container-id
    cId = createContainerId();
  }
  
  @After
  public void tearDown() throws IOException, InterruptedException {
    localFS.delete(new Path(basedir.getPath()), true);
  }
  
  @Test
  public void testKillContainersOnShutdown() throws IOException {
    NodeManager nm = getNodeManager();
    nm.init(createNMConfig());
    nm.start();
    startContainers(nm);
    
    final int MAX_TRIES=20;
    int numTries = 0;
    while (!processStartFile.exists() && numTries < MAX_TRIES) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException ex) {ex.printStackTrace();}
      numTries++;
    }
    
    nm.stop();
    
    // Now verify the contents of the file.  Script generates a message when it
    // receives a sigterm so we look for that.  We cannot perform this check on
    // Windows, because the process is not notified when killed by winutils.
    // There is no way for the process to trap and respond.  Instead, we can
    // verify that the job object with ID matching container ID no longer exists.
    if (Shell.WINDOWS) {
      Assert.assertFalse("Process is still alive!",
        DefaultContainerExecutor.containerIsAlive(cId.toString()));
    } else {
      BufferedReader reader =
          new BufferedReader(new FileReader(processStartFile));

      boolean foundSigTermMessage = false;
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          break;
        }
        if (line.contains("SIGTERM")) {
          foundSigTermMessage = true;
          break;
        }
      }
      Assert.assertTrue("Did not find sigterm message", foundSigTermMessage);
      reader.close();
    }
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void testKillContainersOnResync() throws IOException, InterruptedException {
    NodeManager nm = new TestNodeManager();
    YarnConfiguration conf = createNMConfig();
    nm.init(conf);
    nm.start();
    startContainers(nm);

    assert ((TestNodeManager) nm).getNMRegistrationCount() == 1;
    nm.getNMDispatcher().getEventHandler().
        handle( new NodeManagerEvent(NodeManagerEventType.RESYNC));
    try {
      syncBarrier.await();
    } catch (BrokenBarrierException e) {
    }
    assert ((TestNodeManager) nm).getNMRegistrationCount() == 2;
  }

  private void startContainers(NodeManager nm) throws IOException {
    ContainerManagerImpl containerManager = nm.getContainerManager();
    File scriptFile = createUnhaltingScriptFile();
    
    ContainerLaunchContext containerLaunchContext =
        recordFactory.newRecordInstance(ContainerLaunchContext.class);
    Container mockContainer = mock(Container.class);
    when(mockContainer.getId()).thenReturn(cId);

    containerLaunchContext.setUser(user);

    URL localResourceUri =
        ConverterUtils.getYarnUrlFromPath(localFS
            .makeQualified(new Path(scriptFile.getAbsolutePath())));
    LocalResource localResource =
        recordFactory.newRecordInstance(LocalResource.class);
    localResource.setResource(localResourceUri);
    localResource.setSize(-1);
    localResource.setVisibility(LocalResourceVisibility.APPLICATION);
    localResource.setType(LocalResourceType.FILE);
    localResource.setTimestamp(scriptFile.lastModified());
    String destinationFile = "dest_file";
    Map<String, LocalResource> localResources = 
        new HashMap<String, LocalResource>();
    localResources.put(destinationFile, localResource);
    containerLaunchContext.setLocalResources(localResources);
    containerLaunchContext.setUser(containerLaunchContext.getUser());
    List<String> commands = Arrays.asList(Shell.getRunScriptCommand(scriptFile));
    containerLaunchContext.setCommands(commands);
    Resource resource = BuilderUtils.newResource(1024, 1);
    when(mockContainer.getResource()).thenReturn(resource);
    StartContainerRequest startRequest =
        recordFactory.newRecordInstance(StartContainerRequest.class);
    startRequest.setContainerLaunchContext(containerLaunchContext);
    startRequest.setContainer(mockContainer);
    containerManager.startContainer(startRequest);
    
    GetContainerStatusRequest request =
        recordFactory.newRecordInstance(GetContainerStatusRequest.class);
        request.setContainerId(cId);
    ContainerStatus containerStatus =
        containerManager.getContainerStatus(request).getStatus();
    Assert.assertEquals(ContainerState.RUNNING, containerStatus.getState());
  }
  
  private ContainerId createContainerId() {
    ApplicationId appId = recordFactory.newRecordInstance(ApplicationId.class);
    appId.setClusterTimestamp(0);
    appId.setId(0);
    ApplicationAttemptId appAttemptId = 
        recordFactory.newRecordInstance(ApplicationAttemptId.class);
    appAttemptId.setApplicationId(appId);
    appAttemptId.setAttemptId(1);
    ContainerId containerId = 
        recordFactory.newRecordInstance(ContainerId.class);
    containerId.setApplicationAttemptId(appAttemptId);
    return containerId;
  }
  
  private YarnConfiguration createNMConfig() {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setInt(YarnConfiguration.NM_PMEM_MB, 5*1024); // 5GB
    conf.set(YarnConfiguration.NM_ADDRESS, "127.0.0.1:12345");
    conf.set(YarnConfiguration.NM_LOCALIZER_ADDRESS, "127.0.0.1:12346");
    conf.set(YarnConfiguration.NM_LOG_DIRS, logsDir.getAbsolutePath());
    conf.set(YarnConfiguration.NM_REMOTE_APP_LOG_DIR, remoteLogsDir.getAbsolutePath());
    conf.set(YarnConfiguration.NM_LOCAL_DIRS, nmLocalDir.getAbsolutePath());
    return conf;
  }
  
  /**
   * Creates a script to run a container that will run forever unless
   * stopped by external means.
   */
  private File createUnhaltingScriptFile() throws IOException {
    File scriptFile = Shell.appendScriptExtension(tmpDir, "scriptFile");
    PrintWriter fileWriter = new PrintWriter(scriptFile);
    if (Shell.WINDOWS) {
      fileWriter.println("@echo \"Running testscript for delayed kill\"");
      fileWriter.println("@echo \"Writing pid to start file\"");
      fileWriter.println("@echo " + cId + ">> " + processStartFile);
      fileWriter.println("@pause");
    } else {
      fileWriter.write("#!/bin/bash\n\n");
      fileWriter.write("echo \"Running testscript for delayed kill\"\n");
      fileWriter.write("hello=\"Got SIGTERM\"\n");
      fileWriter.write("umask 0\n");
      fileWriter.write("trap \"echo $hello >> " + processStartFile +
        "\" SIGTERM\n");
      fileWriter.write("echo \"Writing pid to start file\"\n");
      fileWriter.write("echo $$ >> " + processStartFile + "\n");
      fileWriter.write("while true; do\ndate >> /dev/null;\n done\n");
    }

    fileWriter.close();
    return scriptFile;
  }

  private NodeManager getNodeManager() {
    return new NodeManager() {
      @Override
      protected NodeStatusUpdater createNodeStatusUpdater(Context context,
          Dispatcher dispatcher, NodeHealthCheckerService healthChecker) {
        MockNodeStatusUpdater myNodeStatusUpdater = new MockNodeStatusUpdater(
            context, dispatcher, healthChecker, metrics);
        return myNodeStatusUpdater;
      }
    };
  }

  class TestNodeManager extends NodeManager {

    private int registrationCount = 0;

    @Override
    protected NodeStatusUpdater createNodeStatusUpdater(Context context,
        Dispatcher dispatcher, NodeHealthCheckerService healthChecker) {
      return new TestNodeStatusUpdaterImpl(context, dispatcher,
          healthChecker, metrics);
    }

    public int getNMRegistrationCount() {
      return registrationCount;
    }

    class TestNodeStatusUpdaterImpl extends MockNodeStatusUpdater {

      public TestNodeStatusUpdaterImpl(Context context, Dispatcher dispatcher,
          NodeHealthCheckerService healthChecker, NodeManagerMetrics metrics) {
        super(context, dispatcher, healthChecker, metrics);
      }

      @Override
      protected void registerWithRM() throws YarnRemoteException {
        super.registerWithRM();
        registrationCount++;
      }

      @Override
      protected void rebootNodeStatusUpdater() {
        ConcurrentMap<ContainerId, org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container> containers =
            getNMContext().getContainers();
        // ensure that containers are empty before restart nodeStatusUpdater
        Assert.assertTrue(containers.isEmpty());
        super.rebootNodeStatusUpdater();
        try {
          syncBarrier.await();
        } catch (InterruptedException e) {
        } catch (BrokenBarrierException e) {
        }
      }
    }
  }
}
