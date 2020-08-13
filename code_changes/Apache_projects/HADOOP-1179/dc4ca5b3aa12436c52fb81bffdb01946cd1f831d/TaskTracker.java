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
 package org.apache.hadoop.mapred;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.DF;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsException;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.RunJar;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.log4j.LogManager;

/*******************************************************
 * TaskTracker is a process that starts and tracks MR Tasks
 * in a networked environment.  It contacts the JobTracker
 * for Task assignments and reporting results.
 *
 * @author Mike Cafarella
 *******************************************************/
public class TaskTracker 
             implements MRConstants, TaskUmbilicalProtocol, Runnable {
    static final long WAIT_FOR_DONE = 3 * 1000;
    private int httpPort;

    static enum State {NORMAL, STALE, INTERRUPTED, DENIED}

    public static final Log LOG =
    LogFactory.getLog("org.apache.hadoop.mapred.TaskTracker");

    private boolean running = true;

    String taskTrackerName;
    String localHostname;
    InetSocketAddress jobTrackAddr;
    
    String taskReportBindAddress;
    int taskReportPort;

    Server taskReportServer = null;
    InterTrackerProtocol jobClient;
    
    // last heartbeat response recieved
    short heartbeatResponseId = -1;

    /*
     * This is the last 'status' report sent by this tracker to the JobTracker.
     * 
     * If the rpc call succeeds, this 'status' is cleared-out by this tracker;
     * indicating that a 'fresh' status report be generated; in the event the
     * rpc calls fails for whatever reason, the previous status report is sent
     * again.
     */
    TaskTrackerStatus status = null;
    
    StatusHttpServer server = null;
    
    boolean shuttingDown = false;
    
    Map<String, TaskInProgress> tasks = new HashMap();
    /**
     * Map from taskId -> TaskInProgress.
     */
    Map<String, TaskInProgress> runningTasks = null;
    Map<String, RunningJob> runningJobs = null;
    int mapTotal = 0;
    int reduceTotal = 0;
    boolean justStarted = true;
    
    //dir -> DF
    Map localDirsDf = new HashMap();
    long minSpaceStart = 0;
    //must have this much space free to start new tasks
    boolean acceptNewTasks = true;
    long minSpaceKill = 0;
    //if we run under this limit, kill one task
    //and make sure we never receive any new jobs
    //until all the old tasks have been cleaned up.
    //this is if a machine is so full it's only good
    //for serving map output to the other nodes

    static Random r = new Random();
    FileSystem fs = null;
    private static final String SUBDIR = "taskTracker";
    private static final String CACHEDIR = "archive";
    private static final String JOBCACHE = "jobcache";
    private JobConf fConf;
    private MapOutputFile mapOutputFile;
    private int maxCurrentTasks;
    private int failures;
    private int finishedCount[] = new int[1];
    
    private class TaskTrackerMetrics {
      private MetricsRecord metricsRecord = null;
      
      TaskTrackerMetrics() {
          MetricsContext context = MetricsUtil.getContext("mapred");
          metricsRecord = MetricsUtil.createRecord(context, "tasktracker");
      }
      
      synchronized void completeTask() {
        if (metricsRecord != null) {
          metricsRecord.incrMetric("tasks_completed", 1);
          metricsRecord.setMetric("maps_running", mapTotal);
          metricsRecord.setMetric("reduces_running", reduceTotal);
          metricsRecord.update();
        }
      }
    }
    
    private TaskTrackerMetrics myMetrics = null;

    /**
     * A list of tips that should be cleaned up.
     */
    private BlockingQueue<TaskTrackerAction> tasksToCleanup = 
      new LinkedBlockingQueue();
    
    /**
     * A daemon-thread that pulls tips off the list of things to cleanup.
     */
    private Thread taskCleanupThread = 
      new Thread(new Runnable() {
        public void run() {
          while (true) {
            try {
              TaskTrackerAction action = tasksToCleanup.take();
              if (action instanceof KillJobAction) {
                purgeJob((KillJobAction) action);
              } else if (action instanceof KillTaskAction) {
                TaskInProgress tip;
                KillTaskAction killAction = (KillTaskAction) action;
                synchronized (TaskTracker.this) {
                  tip = tasks.get(killAction.getTaskId());
                }
                LOG.info("Received KillTaskAction for task: " + 
                         killAction.getTaskId());
                purgeTask(tip);
              } else {
                LOG.error("Non-delete action given to cleanup thread: "
                          + action);
              }
            } catch (Throwable except) {
              LOG.warn(StringUtils.stringifyException(except));
            }
          }
        }
      }, "taskCleanup");
    {
      taskCleanupThread.setDaemon(true);
      taskCleanupThread.start();
    }
    
    private RunningJob addTaskToJob(String jobId, 
                                    Path localJobFile,
                                    TaskInProgress tip) {
      synchronized (runningJobs) {
        RunningJob rJob = null;
        if (!runningJobs.containsKey(jobId)) {
          rJob = new RunningJob(jobId, localJobFile);
          rJob.localized = false;
          rJob.tasks = new HashSet();
          rJob.jobFile = localJobFile;
          runningJobs.put(jobId, rJob);
        } else {
          rJob = runningJobs.get(jobId);
        }
        rJob.tasks.add(tip);
        return rJob;
      }
    }

    private void removeTaskFromJob(String jobId, TaskInProgress tip) {
      synchronized (runningJobs) {
        RunningJob rjob = runningJobs.get(jobId);
        if (rjob == null) {
          LOG.warn("Unknown job " + jobId + " being deleted.");
        } else {
          synchronized (rjob) {
            rjob.tasks.remove(tip);
            if (rjob.tasks.isEmpty()) {
              runningJobs.remove(jobId);
            }
          }
        }
      }
    }

    static String getCacheSubdir() {
      return TaskTracker.SUBDIR + Path.SEPARATOR + TaskTracker.CACHEDIR;
    }

    static String getJobCacheSubdir() {
      return TaskTracker.SUBDIR + Path.SEPARATOR + TaskTracker.JOBCACHE;
    }
    
    public long getProtocolVersion(String protocol, 
                                   long clientVersion) throws IOException {
      if (protocol.equals(TaskUmbilicalProtocol.class.getName())) {
        return TaskUmbilicalProtocol.versionID;
      } else {
        throw new IOException("Unknown protocol for task tracker: " +
                              protocol);
      }
    }
    
    /**
     * Do the real constructor work here.  It's in a separate method
     * so we can call it again and "recycle" the object after calling
     * close().
     */
    synchronized void initialize() throws IOException {
        // use configured nameserver & interface to get local hostname
        this.localHostname =
          DNS.getDefaultHost
          (fConf.get("mapred.tasktracker.dns.interface","default"),
           fConf.get("mapred.tasktracker.dns.nameserver","default"));
 
        //check local disk
        checkLocalDirs(this.fConf.getLocalDirs());
        fConf.deleteLocalFiles(SUBDIR);

        // Clear out state tables
        this.tasks.clear();
        this.runningTasks = new TreeMap();
        this.runningJobs = new TreeMap();
        this.mapTotal = 0;
        this.reduceTotal = 0;
        this.acceptNewTasks = true;
        this.status = null;
        
        this.minSpaceStart = this.fConf.getLong("mapred.local.dir.minspacestart", 0L);
        this.minSpaceKill = this.fConf.getLong("mapred.local.dir.minspacekill", 0L);
        
        
        this.myMetrics = new TaskTrackerMetrics();
        
        // port numbers
        this.taskReportPort = this.fConf.getInt("mapred.task.tracker.report.port", 50050);
        // bind address
        this.taskReportBindAddress = this.fConf.get("mapred.task.tracker.report.bindAddress", "0.0.0.0");

        // RPC initialization
        while (true) {
            try {
                this.taskReportServer = RPC.getServer(this, this.taskReportBindAddress, this.taskReportPort, maxCurrentTasks, false, this.fConf);
                this.taskReportServer.start();
                break;
            } catch (BindException e) {
                LOG.info("Could not open report server at " + this.taskReportPort + ", trying new port");
                this.taskReportPort++;
            }
        
        }
        this.taskTrackerName = "tracker_" + 
                               localHostname + ":" + taskReportPort;
        LOG.info("Starting tracker " + taskTrackerName);

        // Clear out temporary files that might be lying around
        this.mapOutputFile.cleanupStorage();
        this.justStarted = true;

        this.jobClient = (InterTrackerProtocol) 
                          RPC.waitForProxy(InterTrackerProtocol.class,
                                           InterTrackerProtocol.versionID, 
                                           jobTrackAddr, this.fConf);
        
        this.running = true;
    }
        
    // intialize the job directory
    private void localizeJob(TaskInProgress tip) throws IOException {
      Path localJarFile = null;
      Task t = tip.getTask();
      String jobId = t.getJobId();
      Path localJobFile = new Path(fConf.getLocalPath(getJobCacheSubdir()), 
                                   jobId + Path.SEPARATOR + "job.xml");
      RunningJob rjob = addTaskToJob(jobId, localJobFile, tip);
      synchronized (rjob) {
        if (!rjob.localized) {
          localJarFile = new Path(fConf.getLocalPath(getJobCacheSubdir()), 
                                  jobId + Path.SEPARATOR + "job.jar");
  
          String jobFile = t.getJobFile();
          FileSystem localFs = FileSystem.getLocal(fConf);
          // this will happen on a partial execution of localizeJob.
          // Sometimes the job.xml gets copied but copying job.jar
          // might throw out an exception
          // we should clean up and then try again
          Path jobDir = localJobFile.getParent();
          if (localFs.exists(jobDir)){
            localFs.delete(jobDir);
            boolean b = localFs.mkdirs(jobDir);
            if (!b)
              throw new IOException("Not able to create job directory "
                  + jobDir.toString());
          }
          fs.copyToLocalFile(new Path(jobFile), localJobFile);
          JobConf localJobConf = new JobConf(localJobFile);
          String jarFile = localJobConf.getJar();
          if (jarFile != null) {
            fs.copyToLocalFile(new Path(jarFile), localJarFile);
            localJobConf.setJar(localJarFile.toString());
            OutputStream out = localFs.create(localJobFile);
            try {
              localJobConf.write(out);
            } finally {
              out.close();
            }

            // also unjar the job.jar files in workdir
            File workDir = new File(
                                    new File(localJobFile.toString()).getParent(),
                                    "work");
            if (!workDir.mkdirs()) {
              if (!workDir.isDirectory()) {
                throw new IOException("Mkdirs failed to create " + workDir.toString());
              }
            }
            RunJar.unJar(new File(localJarFile.toString()), workDir);
          }
          rjob.keepJobFiles = ((localJobConf.getKeepTaskFilesPattern() != null) ||
				 localJobConf.getKeepFailedTaskFiles());
          rjob.localized = true;
        }
      }
      launchTaskForJob(tip, new JobConf(rjob.jobFile)); 
    }
    
    private void launchTaskForJob(TaskInProgress tip, JobConf jobConf) throws IOException{
      synchronized (tip) {
      try {
        tip.setJobConf(jobConf);
        tip.launchTask();
      } catch (Throwable ie) {
        tip.runstate = TaskStatus.State.FAILED;
        try {
          tip.cleanup();
        } catch (Throwable ie2) {
          // Ignore it, we are just trying to cleanup.
        }
        String error = StringUtils.stringifyException(ie);
        tip.reportDiagnosticInfo(error);
        LOG.info(error);
      }
      }
     }
    
    public synchronized void shutdown() throws IOException {
          shuttingDown = true;
          close();
          if (this.server != null) {
            try {
                LOG.info("Shutting down StatusHttpServer");
                this.server.stop();
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
          }
      }
    /**
     * Close down the TaskTracker and all its components.  We must also shutdown
     * any running tasks or threads, and cleanup disk space.  A new TaskTracker
     * within the same process space might be restarted, so everything must be
     * clean.
     */
    public synchronized void close() throws IOException {
        //
        // Kill running tasks.  Do this in a 2nd vector, called 'tasksToClose',
        // because calling jobHasFinished() may result in an edit to 'tasks'.
        //
        TreeMap tasksToClose = new TreeMap();
        tasksToClose.putAll(tasks);
        for (Iterator it = tasksToClose.values().iterator(); it.hasNext(); ) {
            TaskInProgress tip = (TaskInProgress) it.next();
            tip.jobHasFinished();
        }

        // Shutdown local RPC servers.  Do them
        // in parallel, as RPC servers can take a long
        // time to shutdown.  (They need to wait a full
        // RPC timeout, which might be 10-30 seconds.)
        new Thread("RPC shutdown") {
            public void run() {
                if (taskReportServer != null) {
                    taskReportServer.stop();
                    taskReportServer = null;
                }
            }
        }.start();

        this.running = false;
        
        // Clear local storage
        this.mapOutputFile.cleanupStorage();
    }

    /**
     * Start with the local machine name, and the default JobTracker
     */
    public TaskTracker(JobConf conf) throws IOException {
      maxCurrentTasks = conf.getInt("mapred.tasktracker.tasks.maximum", 2);
      this.fConf = conf;
      this.jobTrackAddr = JobTracker.getAddress(conf);
      this.mapOutputFile = new MapOutputFile();
      this.mapOutputFile.setConf(conf);
      int httpPort = conf.getInt("tasktracker.http.port", 50060);
      String httpBindAddress = conf.get("tasktracker.http.bindAddress", "0.0.0.0");
      this.server = new StatusHttpServer("task", httpBindAddress, httpPort, true);
      int workerThreads = conf.getInt("tasktracker.http.threads", 40);
      server.setThreads(1, workerThreads);
      // let the jsp pages get to the task tracker, config, and other relevant
      // objects
      FileSystem local = FileSystem.getLocal(conf);
      server.setAttribute("task.tracker", this);
      server.setAttribute("local.file.system", local);
      server.setAttribute("conf", conf);
      server.setAttribute("log", LOG);
      server.addServlet("mapOutput", "/mapOutput", MapOutputServlet.class);
      server.start();
      this.httpPort = server.getPort();
      initialize();
    }

    /**
     * The connection to the JobTracker, used by the TaskRunner 
     * for locating remote files.
     */
    public InterTrackerProtocol getJobClient() {
      return jobClient;
    }
        
    /**Return the DFS filesystem
     */
    public FileSystem getFileSystem(){
      return fs;
    }
    
    /**
     * Main service loop.  Will stay in this loop forever.
     */
    State offerService() throws Exception {
        long lastHeartbeat = 0;
        this.fs = FileSystem.getNamed(jobClient.getFilesystemName(), this.fConf);

        while (running && !shuttingDown) {
          try {
            long now = System.currentTimeMillis();

            long waitTime = HEARTBEAT_INTERVAL - (now - lastHeartbeat);
            if (waitTime > 0) {
              // sleeps for the wait time, wakes up if a task is finished.
              synchronized(finishedCount) {
                if (finishedCount[0] == 0) {
                  finishedCount.wait(waitTime);
                }
                finishedCount[0] = 0;
              }
            }

            // Send the heartbeat and process the jobtracker's directives
            HeartbeatResponse heartbeatResponse = transmitHeartBeat();
            TaskTrackerAction[] actions = heartbeatResponse.getActions();
            LOG.debug("Got heartbeatResponse from JobTracker with responseId: " + 
                    heartbeatResponse.getResponseId() + " and " + 
                    ((actions != null) ? actions.length : 0) + " actions");
            
            if (reinitTaskTracker(actions)) {
              return State.STALE;
            }
            
            lastHeartbeat = now;
            justStarted = false;
            if (actions != null){ 
              for(TaskTrackerAction action: actions) {
                if (action instanceof LaunchTaskAction) {
                  startNewTask((LaunchTaskAction) action);
                } else {
                  tasksToCleanup.put(action);
                }
              }
            }
            markUnresponsiveTasks();
            killOverflowingTasks();
            
            //we've cleaned up, resume normal operation
            if (!acceptNewTasks && isIdle()) {
                acceptNewTasks=true;
            }
          } catch (InterruptedException ie) {
            LOG.info("Interrupted. Closing down.");
            return State.INTERRUPTED;
          } catch (DiskErrorException de) {
            String msg = "Exiting task tracker for disk error:\n" +
                         StringUtils.stringifyException(de);
            LOG.error(msg);
            jobClient.reportTaskTrackerError(taskTrackerName, 
                    "DiskErrorException", msg);
            return State.STALE;
          } catch (RemoteException re) {
            String reClass = re.getClassName();
            if (DisallowedTaskTrackerException.class.getName().equals(reClass)) {
              LOG.info("Tasktracker disallowed by JobTracker.");
              return State.DENIED;
            }
          } catch (Exception except) {
            String msg = "Caught exception: " + 
                         StringUtils.stringifyException(except);
            LOG.error(msg);
          }
        }

        return State.NORMAL;
    }

    /**
     * Build and transmit the heart beat to the JobTracker
     * @return false if the tracker was unknown
     * @throws IOException
     */
    private HeartbeatResponse transmitHeartBeat() throws IOException {
      // 
      // Check if the last heartbeat got through... 
      // if so then build the heartbeat information for the JobTracker;
      // else resend the previous status information.
      //
      if (status == null) {
        List<TaskStatus> taskReports = 
          new ArrayList<TaskStatus>(runningTasks.size());
        synchronized (this) {
          for (TaskInProgress tip: runningTasks.values()) {
            taskReports.add(tip.createStatus());
          }
        }
        status = 
          new TaskTrackerStatus(taskTrackerName, localHostname, 
                  httpPort, taskReports, 
                  failures); 
      } else {
        LOG.info("Resending 'status' to '" + jobTrackAddr.getHostName() +
                "' with reponseId '" + heartbeatResponseId);
      }
      
      //
      // Check if we should ask for a new Task
      //
      boolean askForNewTask = false; 
      if ((mapTotal < maxCurrentTasks || reduceTotal < maxCurrentTasks) &&
              acceptNewTasks) {
        checkLocalDirs(fConf.getLocalDirs());
        
        if (enoughFreeSpace(minSpaceStart)) {
          askForNewTask = true;
        }
      }
      
      //
      // Xmit the heartbeat
      //
      HeartbeatResponse heartbeatResponse = jobClient.heartbeat(status, 
              justStarted, askForNewTask, 
              heartbeatResponseId);
      
      //
      // The heartbeat got through successfully!
      //
      heartbeatResponseId = heartbeatResponse.getResponseId();
      
      synchronized (this) {
        for (TaskStatus taskStatus : status.getTaskReports()) {
          if (taskStatus.getRunState() != TaskStatus.State.RUNNING) {
            if (taskStatus.getIsMap()) {
              mapTotal--;
            } else {
              reduceTotal--;
            }
            try {
              myMetrics.completeTask();
            } catch (MetricsException me) {
              LOG.warn("Caught: " + StringUtils.stringifyException(me));
            }
            runningTasks.remove(taskStatus.getTaskId());
          }
        }
      }

      // Force a rebuild of 'status' on the next iteration
      status = null;                                

      return heartbeatResponse;
    }

    /**
     * Check if the jobtracker directed a 'reset' of the tasktracker.
     * 
     * @param actions the directives of the jobtracker for the tasktracker.
     * @return <code>true</code> if tasktracker is to be reset, 
     *         <code>false</code> otherwise.
     */
    private boolean reinitTaskTracker(TaskTrackerAction[] actions) {
      if (actions != null) {
        for (TaskTrackerAction action : actions) {
          if (action.getActionId() == 
            TaskTrackerAction.ActionType.REINIT_TRACKER) {
            LOG.info("Recieved RenitTrackerAction from JobTracker");
            return true;
          }
        }
      }
      return false;
    }
    
    /**
     * Kill any tasks that have not reported progress in the last X seconds.
     */
    private synchronized void markUnresponsiveTasks() throws IOException {
      long now = System.currentTimeMillis();
      for (TaskInProgress tip: runningTasks.values()) {
        if (tip.getRunState() == TaskStatus.State.RUNNING) {
          // Check the per-job timeout interval for tasks;
          // an interval of '0' implies it is never timed-out
          long jobTaskTimeout = tip.getTaskTimeout();
          if (jobTaskTimeout == 0) {
            continue;
          }
          
          // Check if the task has not reported progress for a 
          // time-period greater than the configured time-out
          long timeSinceLastReport = now - tip.getLastProgressReport();
          if (timeSinceLastReport > jobTaskTimeout && !tip.wasKilled) {
            String msg = "Task failed to report status for " +
            (timeSinceLastReport / 1000) + 
            " seconds. Killing.";
            LOG.info(tip.getTask().getTaskId() + ": " + msg);
            ReflectionUtils.logThreadInfo(LOG, "lost task", 30);
            tip.reportDiagnosticInfo(msg);
            purgeTask(tip);
          }
        }
      }
    }

    /**
     * The task tracker is done with this job, so we need to clean up.
     * @param action The action with the job
     * @throws IOException
     */
    private void purgeJob(KillJobAction action) throws IOException {
      String jobId = action.getJobId();
      LOG.info("Received 'KillJobAction' for job: " + jobId);
      RunningJob rjob = null;
      synchronized (runningJobs) {
        rjob = runningJobs.get(jobId);
      }
      
      if (rjob == null) {
        LOG.warn("Unknown job " + jobId + " being deleted.");
      } else {
        synchronized (rjob) {            
          // Add this tips of this job to queue of tasks to be purged 
          for (TaskInProgress tip : rjob.tasks) {
            tip.jobHasFinished();
          }
          // Delete the job directory for this  
          // task if the job is done/failed
          if (!rjob.keepJobFiles){
            fConf.deleteLocalFiles(SUBDIR + Path.SEPARATOR + JOBCACHE + 
                                   Path.SEPARATOR +  rjob.getJobId());
	  }
          // Remove this job 
          rjob.tasks.clear();
        }
      }

      synchronized(runningJobs) {
        runningJobs.remove(jobId);
      }
    }      
    
    
    /**
     * Remove the tip and update all relevant state.
     * 
     * @param tip {@link TaskInProgress} to be removed.
     * @param purgeJobFiles <code>true</code> if the job files are to be
     *                      purged, <code>false</code> otherwise.
     */
    private void purgeTask(TaskInProgress tip) throws IOException {
      if (tip != null) {
        LOG.info("About to purge task: " + tip.getTask().getTaskId());
        
        // Remove the task from running jobs, 
        // removing the job if it's the last task
        removeTaskFromJob(tip.getTask().getJobId(), tip);
        tip.jobHasFinished();
      }
    }

    /** Check if we're dangerously low on disk space
     * If so, kill jobs to free up space and make sure
     * we don't accept any new tasks
     * Try killing the reduce jobs first, since I believe they
     * use up most space
     * Then pick the one with least progress
     */
    private void killOverflowingTasks() throws IOException {
      if (!enoughFreeSpace(minSpaceKill)) {
        acceptNewTasks=false; 
        //we give up! do not accept new tasks until
        //all the ones running have finished and they're all cleared up
        synchronized (this) {
          TaskInProgress killMe = findTaskToKill();

          if (killMe!=null) {
            String msg = "Tasktracker running out of space." +
                         " Killing task.";
            LOG.info(killMe.getTask().getTaskId() + ": " + msg);
            killMe.reportDiagnosticInfo(msg);
            purgeTask(killMe);
          }
        }
      }
    }
    
    /**
     * Pick a task to kill to free up space
     * @return the task to kill or null, if one wasn't found
     */
    private TaskInProgress findTaskToKill() {
      TaskInProgress killMe = null;
      for (Iterator it = runningTasks.values().iterator(); it.hasNext(); ) {
        TaskInProgress tip = (TaskInProgress) it.next();
        if ((tip.getRunState() == TaskStatus.State.RUNNING) &&
            !tip.wasKilled) {
                
          if (killMe == null) {
            killMe = tip;

          } else if (!tip.getTask().isMapTask()) {
            //reduce task, give priority
            if (killMe.getTask().isMapTask() || 
                (tip.getTask().getProgress().get() < 
                 killMe.getTask().getProgress().get())) {

              killMe = tip;
            }

          } else if (killMe.getTask().isMapTask() &&
                     tip.getTask().getProgress().get() < 
                     killMe.getTask().getProgress().get()) {
            //map task, only add if the progress is lower

            killMe = tip;
          }
        }
      }
      return killMe;
    }
    
    /**
     * Check if all of the local directories have enough
     * free space
     * 
     * If not, do not try to get a new task assigned 
     * @return
     * @throws IOException 
     */
    private boolean enoughFreeSpace(long minSpace) throws IOException {
      if (minSpace == 0) {
        return true;
      }
      String[] localDirs = fConf.getLocalDirs();
      for (int i = 0; i < localDirs.length; i++) {
        DF df = null;
        if (localDirsDf.containsKey(localDirs[i])) {
          df = (DF) localDirsDf.get(localDirs[i]);
        } else {
          df = new DF(new File(localDirs[i]), fConf);
          localDirsDf.put(localDirs[i], df);
        }

        if (df.getAvailable() < minSpace)
          return false;
      }

      return true;
    }
    
    /**
     * Start a new task.
     * All exceptions are handled locally, so that we don't mess up the
     * task tracker.
     */
    private void startNewTask(LaunchTaskAction action) {
      Task t = action.getTask();
      LOG.info("LaunchTaskAction: " + t.getTaskId());
      TaskInProgress tip = new TaskInProgress(t, this.fConf);
      synchronized (this) {
        tasks.put(t.getTaskId(), tip);
        runningTasks.put(t.getTaskId(), tip);
        boolean isMap = t.isMapTask();
        if (isMap) {
          mapTotal++;
        } else {
          reduceTotal++;
        }
      }
      try {
    	  localizeJob(tip);
      } catch (Throwable e) {
        String msg = ("Error initializing " + tip.getTask().getTaskId() + 
                      ":\n" + StringUtils.stringifyException(e));
        LOG.warn(msg);
        tip.reportDiagnosticInfo(msg);
        try {
          tip.killAndCleanup(true);
        } catch (IOException ie2) {
          LOG.info("Error cleaning up " + tip.getTask().getTaskId() + ":\n" +
                   StringUtils.stringifyException(ie2));          
        }
        
        // Careful! 
        // This might not be an 'Exception' - don't handle 'Error' here!
        if (e instanceof Error) {
          throw ((Error) e);
        }
      }
    }
    
    /**
     * The server retry loop.  
     * This while-loop attempts to connect to the JobTracker.  It only 
     * loops when the old TaskTracker has gone bad (its state is
     * stale somehow) and we need to reinitialize everything.
     */
    public void run() {
        try {
            boolean denied = false;
            while (running && !shuttingDown && !denied) {
                boolean staleState = false;
                try {
                    // This while-loop attempts reconnects if we get network errors
                    while (running && ! staleState && !shuttingDown && !denied) {
                        try {
                            State osState = offerService();
                            if (osState == State.STALE) {
                                staleState = true;
                            } else if (osState == State.DENIED) {
                                denied = true;
                            }
                        } catch (Exception ex) {
                            if (!shuttingDown) {
                                LOG.info("Lost connection to JobTracker [" +
                                        jobTrackAddr + "].  Retrying...", ex);
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException ie) {
                                }
                            }
                        }
                    }
                } finally {
                    close();
                }
                if (shuttingDown) { return; }
                LOG.warn("Reinitializing local state");
                initialize();
            }
            if (denied) {
                shutdown();
            }
        } catch (IOException iex) {
            LOG.error("Got fatal exception while reinitializing TaskTracker: " +
                      StringUtils.stringifyException(iex));
            return;
        }
    }
    
    ///////////////////////////////////////////////////////
    // TaskInProgress maintains all the info for a Task that
    // lives at this TaskTracker.  It maintains the Task object,
    // its TaskStatus, and the TaskRunner.
    ///////////////////////////////////////////////////////
    class TaskInProgress {
        Task task;
        float progress;
        volatile TaskStatus.State runstate;
        long lastProgressReport;
        StringBuffer diagnosticInfo = new StringBuffer();
        private TaskRunner runner;
        volatile boolean done = false;
        boolean wasKilled = false;
        private JobConf defaultJobConf;
        private JobConf localJobConf;
        private boolean keepFailedTaskFiles;
        private boolean alwaysKeepTaskFiles;
        private TaskStatus taskStatus ; 
        private boolean keepJobFiles;
        private long taskTimeout;
        
        /**
         */
        public TaskInProgress(Task task, JobConf conf) {
            this.task = task;
            this.progress = 0.0f;
            this.runstate = TaskStatus.State.UNASSIGNED;
            this.lastProgressReport = System.currentTimeMillis();
            this.defaultJobConf = conf;
            localJobConf = null;
            taskStatus = new TaskStatus(task.getTaskId(), 
                task.isMapTask(),
                progress, runstate, 
                diagnosticInfo.toString(), 
                "initializing",  
                 getName(), task.isMapTask()? TaskStatus.Phase.MAP:
                   TaskStatus.Phase.SHUFFLE,
                 task.getCounters()); 
            keepJobFiles = false;
            taskTimeout = (10 * 60 * 1000);
        }
        
        private void localizeTask(Task task) throws IOException{
            Path localTaskDir =
              new Path(this.defaultJobConf.getLocalPath(TaskTracker.getJobCacheSubdir()), 
                (task.getJobId() + Path.SEPARATOR + task.getTaskId()));
           FileSystem localFs = FileSystem.getLocal(fConf);
           if (!localFs.mkdirs(localTaskDir)) {
             throw new IOException("Mkdirs failed to create " + localTaskDir.toString());
           }
           Path localTaskFile = new Path(localTaskDir, "job.xml");
           task.setJobFile(localTaskFile.toString());
           localJobConf.set("mapred.local.dir",
                    fConf.get("mapred.local.dir"));
            
           localJobConf.set("mapred.task.id", task.getTaskId());
           keepFailedTaskFiles = localJobConf.getKeepFailedTaskFiles();
           task.localizeConfiguration(localJobConf);
           OutputStream out = localFs.create(localTaskFile);
           try {
             localJobConf.write(out);
           } finally {
             out.close();
           }
            task.setConf(localJobConf);
            String keepPattern = localJobConf.getKeepTaskFilesPattern();
            if (keepPattern != null) {
                keepJobFiles = true;
                alwaysKeepTaskFiles = 
                Pattern.matches(keepPattern, task.getTaskId());
            } else {
              alwaysKeepTaskFiles = false;
            }
        }
        
        /**
         */
        public Task getTask() {
            return task;
        }

        public void setJobConf(JobConf lconf){
            this.localJobConf = lconf;
            keepFailedTaskFiles = localJobConf.getKeepFailedTaskFiles();
            taskTimeout = localJobConf.getLong("mapred.task.timeout", 
                                                10 * 60 * 1000);
        }
        
        public JobConf getJobConf() {
          return localJobConf;
        }
        
        /**
         */
        public synchronized TaskStatus createStatus() {
          taskStatus.setProgress(progress);
          taskStatus.setRunState(runstate);
          taskStatus.setDiagnosticInfo(diagnosticInfo.toString());
          
          if (diagnosticInfo.length() > 0) {
              diagnosticInfo = new StringBuffer();
          }
          return taskStatus;
        }

        /**
         * Kick off the task execution
         */
        public synchronized void launchTask() throws IOException {
            localizeTask(task);
            this.runstate = TaskStatus.State.RUNNING;
            this.runner = task.createRunner(TaskTracker.this);
            this.runner.start();
            this.taskStatus.setStartTime(System.currentTimeMillis());
        }

        /**
         * The task is reporting its progress
         */
        public synchronized void reportProgress(float p, String state, 
                                                TaskStatus.Phase newPhase,
                                                Counters counters) 
        {
            LOG.info(task.getTaskId()+" "+p+"% "+state);
            this.progress = p;
            this.runstate = TaskStatus.State.RUNNING;
            this.lastProgressReport = System.currentTimeMillis();
            TaskStatus.Phase oldPhase = taskStatus.getPhase() ;
            if( oldPhase != newPhase ){
              // sort phase started
              if( newPhase == TaskStatus.Phase.SORT ){
                this.taskStatus.setShuffleFinishTime(System.currentTimeMillis());
              }else if( newPhase == TaskStatus.Phase.REDUCE){
                this.taskStatus.setSortFinishTime(System.currentTimeMillis());
              }
              this.taskStatus.setPhase(newPhase);
            }
            this.taskStatus.setStateString(state);
            this.taskStatus.setCounters(counters);
        }

        /**
         */
        public long getLastProgressReport() {
            return lastProgressReport;
        }

        /**
         */
        public TaskStatus.State getRunState() {
            return runstate;
        }

        /**
         * The task's configured timeout.
         * 
         * @return the task's configured timeout.
         */
        public long getTaskTimeout() {
          return taskTimeout;
        }
        
        /**
         * The task has reported some diagnostic info about its status
         */
        public synchronized void reportDiagnosticInfo(String info) {
            this.diagnosticInfo.append(info);
        }

        /**
         * The task is reporting that it's done running
         */
        public synchronized void reportDone() {
            LOG.info("Task " + task.getTaskId() + " is done.");
            this.progress = 1.0f;
            this.taskStatus.setFinishTime(System.currentTimeMillis());
            this.done = true;
        }

        /**
         * The task has actually finished running.
         */
        public void taskFinished() {
            long start = System.currentTimeMillis();

            //
            // Wait until task reports as done.  If it hasn't reported in,
            // wait for a second and try again.
            //
            while (! done && (System.currentTimeMillis() - start < WAIT_FOR_DONE)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                }
            }

            //
            // Change state to success or failure, depending on whether
            // task was 'done' before terminating
            //
            boolean needCleanup = false;
            synchronized (this) {
              if (done) {
                  runstate = TaskStatus.State.SUCCEEDED;
              } else {
                  if (!wasKilled) {
                    failures += 1;
                    runstate = TaskStatus.State.FAILED;
                  } else {
                    runstate = TaskStatus.State.KILLED;
                  }
                  progress = 0.0f;
              }
              this.taskStatus.setFinishTime(System.currentTimeMillis());
              needCleanup = (runstate == TaskStatus.State.FAILED) |
                            (runstate == TaskStatus.State.KILLED);
            }

            //
            // If the task has failed, or if the task was killAndCleanup()'ed,
            // we should clean up right away.  We only wait to cleanup
            // if the task succeeded, and its results might be useful
            // later on to downstream job processing.
            //
            if (needCleanup) {
                try {
                    cleanup();
                } catch (IOException ie) {
                }
            }
        }

        /**
         * We no longer need anything from this task, as the job has
         * finished.  If the task is still running, kill it (and clean up
         */
        public void jobHasFinished() throws IOException {
          boolean killTask = false;  
          synchronized(this){
              killTask = (getRunState() == TaskStatus.State.RUNNING);
              if (killTask) {
                killAndCleanup(false);
              }
          }
          if (!killTask) {
            cleanup();
          }
          if (keepJobFiles)
            return;
              
          synchronized(this){
              // Delete temp directory in case any task used PhasedFileSystem.
              try{
                String systemDir = task.getConf().get("mapred.system.dir");
                Path taskTempDir = new Path(systemDir + "/" + 
                    task.getJobId() + "/" + task.getTipId() + "/" + task.getTaskId());
                if( fs.exists(taskTempDir)){
                  fs.delete(taskTempDir) ;
                }
              }catch(IOException e){
                LOG.warn("Error in deleting reduce temporary output",e); 
              }
            }
        }

        /**
         * Something went wrong and the task must be killed.
         * @param wasFailure was it a failure (versus a kill request)?
         */
        public synchronized void killAndCleanup(boolean wasFailure
                                                ) throws IOException {
            if (runstate == TaskStatus.State.RUNNING) {
                wasKilled = true;
                if (wasFailure) {
                  failures += 1;
                }
                runner.kill();
                runstate = TaskStatus.State.KILLED;
            } else if (runstate == TaskStatus.State.UNASSIGNED) {
              if (wasFailure) {
                failures += 1;
                runstate = TaskStatus.State.FAILED;
              } else {
                runstate = TaskStatus.State.KILLED;
              }
            }
        }

        /**
         * The map output has been lost.
         */
        public synchronized void mapOutputLost(String failure
                                               ) throws IOException {
            if (runstate == TaskStatus.State.SUCCEEDED) {
              LOG.info("Reporting output lost:"+task.getTaskId());
              runstate = TaskStatus.State.FAILED;    // change status to failure
              progress = 0.0f;
              reportDiagnosticInfo("Map output lost, rescheduling: " + 
                                   failure);
              runningTasks.put(task.getTaskId(), this);
              mapTotal++;
            } else {
              LOG.warn("Output already reported lost:"+task.getTaskId());
            }
        }

        /**
         * We no longer need anything from this task.  Either the 
         * controlling job is all done and the files have been copied
         * away, or the task failed and we don't need the remains.
         * Any calls to cleanup should not lock the tip first.
         * cleanup does the right thing- updates tasks in Tasktracker
         * by locking tasktracker first and then locks the tip.
         */
        void cleanup() throws IOException {
            String taskId = task.getTaskId();
            LOG.debug("Cleaning up " + taskId);
            synchronized (TaskTracker.this) {
               tasks.remove(taskId);
               if (alwaysKeepTaskFiles ||
                   (runstate == TaskStatus.State.FAILED && 
                       keepFailedTaskFiles)) {
                 return;
               }
            }
            synchronized (this) {
              try {
                if (runner != null) {
                  runner.close();
                }
                defaultJobConf.deleteLocalFiles(SUBDIR + Path.SEPARATOR + 
                                                JOBCACHE + Path.SEPARATOR + 
                                                task.getJobId() + 
                                                Path.SEPARATOR + taskId);
              } catch (Throwable ie) {
                LOG.info("Error cleaning up task runner: " + 
                         StringUtils.stringifyException(ie));
              }
            }
        }
        
        public boolean equals(Object obj) {
          return (obj instanceof TaskInProgress) &&
                 task.getTaskId().equals
                   (((TaskInProgress) obj).getTask().getTaskId());
        }
        
        public int hashCode() {
          return task.getTaskId().hashCode();
        }
    }

    
    // ///////////////////////////////////////////////////////////////
    // TaskUmbilicalProtocol
    /////////////////////////////////////////////////////////////////
    /**
     * Called upon startup by the child process, to fetch Task data.
     */
    public synchronized Task getTask(String taskid) throws IOException {
        TaskInProgress tip = (TaskInProgress) tasks.get(taskid);
        if (tip != null) {
            return (Task) tip.getTask();
        } else {
            return null;
        }
    }

    /**
     * Called periodically to report Task progress, from 0.0 to 1.0.
     */
    public synchronized void progress(String taskid, float progress, 
                                      String state, 
                                      TaskStatus.Phase phase,
                                      Counters counters
                                      ) throws IOException {
        TaskInProgress tip = (TaskInProgress) tasks.get(taskid);
        if (tip != null) {
          tip.reportProgress(progress, state, phase, counters);
        } else {
          LOG.warn("Progress from unknown child task: "+taskid+". Ignored.");
        }
    }

    /**
     * Called when the task dies before completion, and we want to report back
     * diagnostic info
     */
    public synchronized void reportDiagnosticInfo(String taskid, String info) throws IOException {
        TaskInProgress tip = (TaskInProgress) tasks.get(taskid);
        if (tip != null) {
          tip.reportDiagnosticInfo(info);
        } else {
          LOG.warn("Error from unknown child task: "+taskid+". Ignored.");
        }
    }

    /** Child checking to see if we're alive.  Normally does nothing.*/
    public synchronized boolean ping(String taskid) throws IOException {
      return tasks.get(taskid) != null;
    }

    /**
     * The task is done.
     */
    public synchronized void done(String taskid) throws IOException {
        TaskInProgress tip = (TaskInProgress) tasks.get(taskid);
        if (tip != null) {
          tip.reportDone();
        } else {
          LOG.warn("Unknown child task done: "+taskid+". Ignored.");
        }
    }

    /** A child task had a local filesystem error.  Exit, so that no future
     * jobs are accepted. */
    public synchronized void fsError(String message) throws IOException {
      LOG.fatal("FSError, exiting: "+ message);
      running = false;
    }

    /////////////////////////////////////////////////////
    //  Called by TaskTracker thread after task process ends
    /////////////////////////////////////////////////////
    /**
     * The task is no longer running.  It may not have completed successfully
     */
    void reportTaskFinished(String taskid) {
        TaskInProgress tip;
        synchronized (this) {
          tip = (TaskInProgress) tasks.get(taskid);
        }
        if (tip != null) {
          tip.taskFinished();
          synchronized(finishedCount) {
              finishedCount[0]++;
              finishedCount.notify();
          }
        } else {
          LOG.warn("Unknown child task finshed: "+taskid+". Ignored.");
        }
    }

    /**
     * A completed map task's output has been lost.
     */
    public synchronized void mapOutputLost(String taskid,
                                           String errorMsg) throws IOException {
        TaskInProgress tip = (TaskInProgress) tasks.get(taskid);
        if (tip != null) {
          tip.mapOutputLost(errorMsg);
        } else {
          LOG.warn("Unknown child with bad map output: "+taskid+". Ignored.");
        }
    }
    
    /**
     *  The datastructure for initializing a job
     */
    static class RunningJob{
      private String jobid; 
      private Path jobFile;
      // keep this for later use
      Set<TaskInProgress> tasks;
      boolean localized;
      boolean keepJobFiles;
      RunningJob(String jobid, Path jobFile) {
        this.jobid = jobid;
        localized = false;
        tasks = new HashSet();
        this.jobFile = jobFile;
        keepJobFiles = false;
      }
      
      Path getJobFile() {
        return jobFile;
      }
      
      String getJobId() {
        return jobid;
      }
    }

    /** 
     * The main() for child processes. 
     */
    public static class Child {
        public static void main(String[] args) throws Throwable {
          //LogFactory.showTime(false);
          LOG.debug("Child starting");

          JobConf defaultConf = new JobConf();
          int port = Integer.parseInt(args[0]);
          String taskid = args[1];
          TaskUmbilicalProtocol umbilical =
            (TaskUmbilicalProtocol)RPC.getProxy(TaskUmbilicalProtocol.class,
                                                TaskUmbilicalProtocol.versionID,
                                                new InetSocketAddress(port), 
                                                defaultConf);
            
          Task task = umbilical.getTask(taskid);
          JobConf job = new JobConf(task.getJobFile());
          task.setConf(job);
          
          defaultConf.addFinalResource(new Path(task.getJobFile()));

          startPinging(umbilical, taskid);        // start pinging parent

          try {
            // use job-specified working directory
            FileSystem.get(job).setWorkingDirectory(job.getWorkingDirectory());
            task.run(job, umbilical);             // run the task
          } catch (FSError e) {
            LOG.fatal("FSError from child", e);
            umbilical.fsError(e.getMessage());
          } catch (Throwable throwable) {
              LOG.warn("Error running child", throwable);
              // Report back any failures, for diagnostic purposes
              ByteArrayOutputStream baos = new ByteArrayOutputStream();
              throwable.printStackTrace(new PrintStream(baos));
              umbilical.reportDiagnosticInfo(taskid, baos.toString());
          } finally {
            // Shutting down log4j of the child-vm... 
            // This assumes that on return from Task.run() 
            // there is no more logging done.
            LogManager.shutdown();
          }
        }

        /** Periodically ping parent and exit when this fails.*/
        private static void startPinging(final TaskUmbilicalProtocol umbilical,
                                         final String taskid) {
          Thread thread = new Thread(new Runnable() {
              public void run() {
                final int MAX_RETRIES = 3;
                int remainingRetries = MAX_RETRIES;
                while (true) {
                  try {
                    if (!umbilical.ping(taskid)) {
                      LOG.warn("Parent died.  Exiting "+taskid);
                      System.exit(66);
                    }
                    remainingRetries = MAX_RETRIES;
                  } catch (Throwable t) {
                    String msg = StringUtils.stringifyException(t);
                    LOG.info("Ping exception: " + msg);
                    remainingRetries -=1;
                    if (remainingRetries == 0) {
                      ReflectionUtils.logThreadInfo(LOG, "ping exception", 0);
                      LOG.warn("Last retry, killing "+taskid);
                      System.exit(65);
                    }
                  }
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                  }
                }
              }
            }, "Pinger for "+taskid);
          thread.setDaemon(true);
          thread.start();
        }
    }

    /**
     * Get the name for this task tracker.
     * @return the string like "tracker_mymachine:50010"
     */
    String getName() {
      return taskTrackerName;
    }
    
    /**
     * Get the list of tasks that will be reported back to the 
     * job tracker in the next heartbeat cycle.
     * @return a copy of the list of TaskStatus objects
     */
    synchronized List<TaskStatus> getRunningTaskStatuses() {
      List<TaskStatus> result = new ArrayList(runningTasks.size());
      for(TaskInProgress tip: runningTasks.values()) {
        result.add(tip.createStatus());
      }
      return result;
    }

    /**
     * Get the list of stored tasks on this task tracker.
     * @return
     */
    synchronized List<TaskStatus> getNonRunningTasks() {
      List<TaskStatus> result = new ArrayList(tasks.size());
      for(Map.Entry<String, TaskInProgress> task: tasks.entrySet()) {
        if (!runningTasks.containsKey(task.getKey())) {
          result.add(task.getValue().createStatus());
        }
      }
      return result;
    }

    /**
     * Get the default job conf for this tracker.
     */
    JobConf getJobConf() {
      return fConf;
    }
    
    /**
     * Check if the given local directories
     * (and parent directories, if necessary) can be created.
     * @param localDirs where the new TaskTracker should keep its local files.
     * @throws DiskErrorException if all local directories are not writable
     * @author hairong
     */
    private static void checkLocalDirs( String[] localDirs ) 
            throws DiskErrorException {
        boolean writable = false;
        
        if( localDirs != null ) {
            for (int i = 0; i < localDirs.length; i++) {
                try {
                    DiskChecker.checkDir( new File(localDirs[i]) );
                    writable = true;
                } catch( DiskErrorException e ) {
                    LOG.warn("Task Tracker local " + e.getMessage() );
                }
            }
        }

        if( !writable )
            throw new DiskErrorException( 
                    "all local directories are not writable" );
    }
    
    /**
     * Is this task tracker idle?
     * @return has this task tracker finished and cleaned up all of its tasks?
     */
    public synchronized boolean isIdle() {
      return tasks.isEmpty() && tasksToCleanup.isEmpty();
    }
    
    /**
     * Start the TaskTracker, point toward the indicated JobTracker
     */
    public static void main(String argv[]) throws Exception {
        if (argv.length != 0) {
            System.out.println("usage: TaskTracker");
            System.exit(-1);
        }
        try {
          JobConf conf=new JobConf();
          // enable the server to track time spent waiting on locks
          ReflectionUtils.setContentionTracing
              (conf.getBoolean("tasktracker.contention.tracking", false));
          new TaskTracker(conf).run();
        } catch ( Throwable e ) {
          LOG.error( "Can not start task tracker because "+
                     StringUtils.stringifyException(e) );
          System.exit(-1);
        }
    }
    
    /**
     * This class is used in TaskTracker's Jetty to serve the map outputs
     * to other nodes.
     * @author Owen O'Malley
     */
    public static class MapOutputServlet extends HttpServlet {
      private final int MAX_BYTES_TO_READ = 64 * 1024;
      public void doGet(HttpServletRequest request, 
                        HttpServletResponse response
                       ) throws ServletException, IOException {
        String mapId = request.getParameter("map");
        String reduceId = request.getParameter("reduce");
        if (mapId == null || reduceId == null) {
          throw new IOException("map and reduce parameters are required");
        }
        ServletContext context = getServletContext();
        int reduce = Integer.parseInt(reduceId);
        byte[] buffer = new byte[MAX_BYTES_TO_READ];
        OutputStream outStream = response.getOutputStream();
        JobConf conf = (JobConf) context.getAttribute("conf");
        FileSystem fileSys = 
          (FileSystem) context.getAttribute("local.file.system");

        // Index file
        Path indexFileName = conf.getLocalPath(mapId+"/file.out.index");
        FSDataInputStream indexIn = null;
         
        // Map-output file
        Path mapOutputFileName = conf.getLocalPath(mapId+"/file.out"); 
        FSDataInputStream mapOutputIn = null;
        
        // true iff IOException was caused by attempt to access input
        boolean isInputException = true;
        
        try {
          /**
           * Read the index file to get the information about where
           * the map-output for the given reducer is available. 
           */
          //open index file
          indexIn = fileSys.open(indexFileName);

          //seek to the correct offset for the given reduce
          indexIn.seek(reduce * 16);
          
          //read the offset and length of the partition data
          long startOffset = indexIn.readLong();
          long partLength = indexIn.readLong();

          indexIn.close();
          indexIn = null;
          
          //set the content-length header
          response.setContentLength((int) partLength);

          /**
           * Read the data from the sigle map-output file and
           * send it to the reducer.
           */
          //open the map-output file
          mapOutputIn = fileSys.open(mapOutputFileName);
          //seek to the correct offset for the reduce
          mapOutputIn.seek(startOffset);
          
          int totalRead = 0;
          int len = mapOutputIn.read(buffer, 0,
                               partLength < MAX_BYTES_TO_READ 
                               ? (int)partLength : MAX_BYTES_TO_READ);
          while (len > 0) {
            try {
              outStream.write(buffer, 0, len);
            } catch (IOException ie) {
              isInputException = false;
              throw ie;
            }
            totalRead += len;
            if (totalRead == partLength) break;
            len = mapOutputIn.read(buffer, 0, 
                    (partLength - totalRead) < MAX_BYTES_TO_READ
                     ? (int)(partLength - totalRead) : MAX_BYTES_TO_READ);
          }
        } catch (IOException ie) {
          TaskTracker tracker = 
            (TaskTracker) context.getAttribute("task.tracker");
          Log log = (Log) context.getAttribute("log");
          String errorMsg = ("getMapOutput(" + mapId + "," + reduceId + 
                             ") failed :\n"+
                             StringUtils.stringifyException(ie));
          log.warn(errorMsg);
          if (isInputException) {
            tracker.mapOutputLost(mapId, errorMsg);
          }
          response.sendError(HttpServletResponse.SC_GONE, errorMsg);
          throw ie;
        } finally {
          if (indexIn != null) {
            indexIn.close();
          }
          if (mapOutputIn != null) {
            mapOutputIn.close();
          }
        }
        outStream.close();
      }
    }
}
