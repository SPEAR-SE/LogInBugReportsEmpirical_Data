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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException; 
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
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.DF;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.mapred.pipes.Submitter;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsException;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.Updater;
import org.apache.hadoop.metrics.jvm.JvmMetrics;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.net.NetUtils;
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
 *******************************************************/
public class TaskTracker 
             implements MRConstants, TaskUmbilicalProtocol, Runnable {
  static final long WAIT_FOR_DONE = 3 * 1000;
  private int httpPort;

  static enum State {NORMAL, STALE, INTERRUPTED, DENIED}

  public static final Log LOG =
    LogFactory.getLog("org.apache.hadoop.mapred.TaskTracker");

  private boolean running = true;

  private LocalDirAllocator localDirAllocator;
  String taskTrackerName;
  String localHostname;
  InetSocketAddress jobTrackAddr;
    
  InetSocketAddress taskReportAddress;

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
    
  Map<String, TaskInProgress> tasks = new HashMap<String, TaskInProgress>();
  /**
   * Map from taskId -> TaskInProgress.
   */
  Map<String, TaskInProgress> runningTasks = null;
  Map<String, RunningJob> runningJobs = null;
  volatile int mapTotal = 0;
  volatile int reduceTotal = 0;
  boolean justStarted = true;
    
  //dir -> DF
  Map<String, DF> localDirsDf = new HashMap<String, DF>();
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
  private int maxCurrentMapTasks;
  private int maxCurrentReduceTasks;
  private int failures;
  private int finishedCount[] = new int[1];
  private MapEventsFetcherThread mapEventsFetcher;
  int workerThreads;
  /**
   * the minimum interval between jobtracker polls
   */
  private static final int MIN_POLL_INTERVAL = 5000;
  private volatile int heartbeatInterval = HEARTBEAT_INTERVAL_MIN;
  /**
   * Number of maptask completion events locations to poll for at one time
   */  
  private int probe_sample_size = 50;
    
  private ShuffleServerMetrics shuffleServerMetrics;
  /** This class contains the methods that should be used for metrics-reporting
   * the specific metrics for shuffle. The TaskTracker is actually a server for
   * the shuffle and hence the name ShuffleServerMetrics.
   */
  private class ShuffleServerMetrics implements Updater {
    private MetricsRecord shuffleMetricsRecord = null;
    private int serverHandlerBusy = 0;
    private long outputBytes = 0;
    private int failedOutputs = 0;
    private int successOutputs = 0;
    ShuffleServerMetrics(JobConf conf) {
      MetricsContext context = MetricsUtil.getContext("mapred");
      shuffleMetricsRecord = 
                           MetricsUtil.createRecord(context, "shuffleOutput");
      this.shuffleMetricsRecord.setTag("sessionId", conf.getSessionId());
      context.registerUpdater(this);
    }
    synchronized void serverHandlerBusy() {
      ++serverHandlerBusy;
    }
    synchronized void serverHandlerFree() {
      --serverHandlerBusy;
    }
    synchronized void outputBytes(long bytes) {
      outputBytes += bytes;
    }
    synchronized void failedOutput() {
      ++failedOutputs;
    }
    synchronized void successOutput() {
      ++successOutputs;
    }
    public void doUpdates(MetricsContext unused) {
      synchronized (this) {
        if (workerThreads != 0) {
          shuffleMetricsRecord.setMetric("shuffle_handler_busy_percent", 
              100*((float)serverHandlerBusy/workerThreads));
        } else {
          shuffleMetricsRecord.setMetric("shuffle_handler_busy_percent", 0);
        }
        shuffleMetricsRecord.incrMetric("shuffle_output_bytes", 
                                        outputBytes);
        shuffleMetricsRecord.incrMetric("shuffle_failed_outputs", 
                                        failedOutputs);
        shuffleMetricsRecord.incrMetric("shuffle_success_outputs", 
                                        successOutputs);
        outputBytes = 0;
        failedOutputs = 0;
        successOutputs = 0;
      }
      shuffleMetricsRecord.update();
    }
  }
  public class TaskTrackerMetrics implements Updater {
    private MetricsRecord metricsRecord = null;
    private int numCompletedTasks = 0;
    private int timedoutTasks = 0;
    private int tasksFailedPing = 0;
      
    TaskTrackerMetrics() {
      JobConf conf = getJobConf();
      String sessionId = conf.getSessionId();
      // Initiate Java VM Metrics
      JvmMetrics.init("TaskTracker", sessionId);
      // Create a record for Task Tracker metrics
      MetricsContext context = MetricsUtil.getContext("mapred");
      metricsRecord = MetricsUtil.createRecord(context, "tasktracker");
      metricsRecord.setTag("sessionId", sessionId);
      context.registerUpdater(this);
    }
      
    synchronized void completeTask() {
      ++numCompletedTasks;
    }
    
    synchronized void timedoutTask() {
      ++timedoutTasks;
    }
    
    synchronized void taskFailedPing() {
      ++tasksFailedPing;
    }
    
    /**
     * Since this object is a registered updater, this method will be called
     * periodically, e.g. every 5 seconds.
     */  
    public void doUpdates(MetricsContext unused) {
      synchronized (this) {
        if (metricsRecord != null) {
          metricsRecord.setMetric("maps_running", mapTotal);
          metricsRecord.setMetric("reduces_running", reduceTotal);
          metricsRecord.setMetric("mapTaskSlots", (short)maxCurrentMapTasks);
          metricsRecord.setMetric("reduceTaskSlots", 
                                       (short)maxCurrentReduceTasks);
          metricsRecord.incrMetric("tasks_completed", numCompletedTasks);
          metricsRecord.incrMetric("tasks_failed_timeout", timedoutTasks);
          metricsRecord.incrMetric("tasks_failed_ping", tasksFailedPing);
        }
        numCompletedTasks = 0;
        timedoutTasks = 0;
        tasksFailedPing = 0;
      }
      metricsRecord.update();
    }
  }
    
  private TaskTrackerMetrics myMetrics = null;

  public TaskTrackerMetrics getTaskTrackerMetrics() {
    return myMetrics;
  }
  
  /**
   * A list of tips that should be cleaned up.
   */
  private BlockingQueue<TaskTrackerAction> tasksToCleanup = 
    new LinkedBlockingQueue<TaskTrackerAction>();
    
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
                purgeTask(tip, false);
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
        rJob.tasks = new HashSet<TaskInProgress>();
        rJob.jobFile = localJobFile;
        runningJobs.put(jobId, rJob);
      } else {
        rJob = runningJobs.get(jobId);
      }
      synchronized (rJob) {
        rJob.tasks.add(tip);
      }
      runningJobs.notify(); //notify the fetcher thread
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
    this.runningTasks = new TreeMap<String, TaskInProgress>();
    this.runningJobs = new TreeMap<String, RunningJob>();
    this.mapTotal = 0;
    this.reduceTotal = 0;
    this.acceptNewTasks = true;
    this.status = null;

    this.minSpaceStart = this.fConf.getLong("mapred.local.dir.minspacestart", 0L);
    this.minSpaceKill = this.fConf.getLong("mapred.local.dir.minspacekill", 0L);
    int numCopiers = this.fConf.getInt("mapred.reduce.parallel.copies", 5);
    //tweak the probe sample size (make it a function of numCopiers)
    probe_sample_size = Math.max(numCopiers*5, 50);
    
    
    this.myMetrics = new TaskTrackerMetrics();
    
    // bind address
    String address = 
      this.fConf.get("mapred.task.tracker.report.bindAddress", "127.0.0.1:0");
    InetSocketAddress socAddr = NetUtils.createSocketAddr(address);
    String bindAddress = socAddr.getHostName();
    int tmpPort = socAddr.getPort();

    // RPC initialization
    int max = maxCurrentMapTasks > maxCurrentReduceTasks ? 
                       maxCurrentMapTasks : maxCurrentReduceTasks;
    this.taskReportServer =
      RPC.getServer(this, bindAddress, tmpPort, max, false, this.fConf);
    this.taskReportServer.start();

    // get the assigned address
    this.taskReportAddress = taskReportServer.getListenerAddress();
    this.fConf.set("mapred.task.tracker.report.bindAddress",
        taskReportAddress.getHostName() + ":" + taskReportAddress.getPort());
    LOG.info("TaskTracker up at: " + this.taskReportAddress);

    this.taskTrackerName = "tracker_" + localHostname + ":" + taskReportAddress;
    LOG.info("Starting tracker " + taskTrackerName);

    // Clear out temporary files that might be lying around
    DistributedCache.purgeCache(this.fConf);
    this.mapOutputFile.cleanupStorage();
    this.justStarted = true;

    this.jobClient = (InterTrackerProtocol) 
      RPC.waitForProxy(InterTrackerProtocol.class,
                       InterTrackerProtocol.versionID, 
                       jobTrackAddr, this.fConf);
        
    this.running = true;
    // start the thread that will fetch map task completion events
    this.mapEventsFetcher = new MapEventsFetcherThread();
    mapEventsFetcher.setDaemon(true);
    mapEventsFetcher.setName(
                             "Map-events fetcher for all reduce tasks " + "on " + 
                             taskTrackerName);
    mapEventsFetcher.start();
  }
    
  // Object on wait which MapEventsFetcherThread is going to wait.
  private Object waitingOn = new Object();

  private class MapEventsFetcherThread extends Thread {

    private List <FetchStatus> reducesInShuffle() {
      List <FetchStatus> fList = new ArrayList<FetchStatus>();
      for (Map.Entry <String, RunningJob> item : runningJobs.entrySet()) {
        RunningJob rjob = item.getValue();
        String jobId = item.getKey();
        FetchStatus f;
        synchronized (rjob) {
          f = rjob.getFetchStatus();
          for (TaskInProgress tip : rjob.tasks) {
            Task task = tip.getTask();
            if (!task.isMapTask()) {
              if (((ReduceTask)task).getPhase() == 
                  TaskStatus.Phase.SHUFFLE) {
                if (rjob.getFetchStatus() == null) {
                  //this is a new job; we start fetching its map events
                  f = new FetchStatus(jobId, 
                                      ((ReduceTask)task).getNumMaps());
                  rjob.setFetchStatus(f);
                }
                f = rjob.getFetchStatus();
                fList.add(f);
                break; //no need to check any more tasks belonging to this
              }
            }
          }
        }
      }
      //at this point, we have information about for which of
      //the running jobs do we need to query the jobtracker for map 
      //outputs (actually map events).
      return fList;
    }
      
    public void run() {
      LOG.info("Starting thread: " + getName());
        
      while (true) {
        try {
          List <FetchStatus> fList = null;
          synchronized (runningJobs) {
            while (((fList = reducesInShuffle()).size()) == 0) {
              try {
                runningJobs.wait();
              } catch (InterruptedException e) {
                LOG.info("Shutting down: " + getName());
                return;
              }
            }
          }
          // now fetch all the map task events for all the reduce tasks
          // possibly belonging to different jobs
          for (FetchStatus f : fList) {
            try {
              f.fetchMapCompletionEvents();
              long startWait;
              long endWait;
              // polling interval is heartbeat interval
              int waitTime = heartbeatInterval;
              // Thread will wait for a minumum of MIN_POLL_INTERVAL, 
              // if it is notified before that, notification will be ignored. 
              int minWait = MIN_POLL_INTERVAL;
              synchronized (waitingOn) {
                try {
                  while (true) {
                    startWait = System.currentTimeMillis();
                    waitingOn.wait(waitTime);
                    endWait = System.currentTimeMillis();
                    int diff = (int)(endWait - startWait);
                    if (diff >= minWait) {
                      break;
                    }
                    minWait = minWait - diff;
                    waitTime = minWait;
                  }
                } catch (InterruptedException ie) {
                  LOG.info("Shutting down: " + getName());
                  return;
                }
              }
            } catch (Exception e) {
              LOG.warn(
                       "Ignoring exception that fetch for map completion" +
                       " events threw for " + f.jobId + " threw: " +
                       StringUtils.stringifyException(e)); 
            }
          }
        } catch (Exception e) {
          LOG.info("Ignoring exception "  + e.getMessage());
        }
      }
    } 
  }

  private class FetchStatus {
    /** The next event ID that we will start querying the JobTracker from*/
    private IntWritable fromEventId;
    /** This is the cache of map events for a given job */ 
    private List<TaskCompletionEvent> allMapEvents;
    /** What jobid this fetchstatus object is for*/
    private String jobId;
     
    public FetchStatus(String jobId, int numMaps) {
      this.fromEventId = new IntWritable(0);
      this.jobId = jobId;
      this.allMapEvents = new ArrayList<TaskCompletionEvent>(numMaps);
    }
      
    public TaskCompletionEvent[] getMapEvents(int fromId, int max) {
        
      TaskCompletionEvent[] mapEvents = 
        TaskCompletionEvent.EMPTY_ARRAY;
      boolean notifyFetcher = false; 
      synchronized (allMapEvents) {
        if (allMapEvents.size() > fromId) {
          int actualMax = Math.min(max, (allMapEvents.size() - fromId));
          List <TaskCompletionEvent> eventSublist = 
            allMapEvents.subList(fromId, actualMax + fromId);
          mapEvents = eventSublist.toArray(mapEvents);
        } else {
          // Notify Fetcher thread. 
          notifyFetcher = true;
        }
      }
      if (notifyFetcher) {
        synchronized (waitingOn) {
          waitingOn.notify();
        }
      }
      return mapEvents;
    }
      
    public void fetchMapCompletionEvents() throws IOException {
      List <TaskCompletionEvent> recentMapEvents = 
        queryJobTracker(fromEventId, jobId, jobClient);
      synchronized (allMapEvents) {
        allMapEvents.addAll(recentMapEvents);
      }
    }
  }

  private LocalDirAllocator lDirAlloc = 
                              new LocalDirAllocator("mapred.local.dir");

  // intialize the job directory
  private void localizeJob(TaskInProgress tip) throws IOException {
    Path localJarFile = null;
    Task t = tip.getTask();
    String jobId = t.getJobId();
    String jobFile = t.getJobFile();
    // Get sizes of JobFile and JarFile
    // sizes are -1 if they are not present.
    FileSystem fileSystem = FileSystem.get(fConf);
    FileStatus status[] = fileSystem.listStatus(new Path(jobFile).getParent());
    long jarFileSize = -1;
    long jobFileSize = -1;
    for(FileStatus stat : status) {
      if (stat.getPath().toString().contains("job.xml")) {
        jobFileSize = stat.getLen();
      } else {
        jobFileSize = -1;
      }
      if (stat.getPath().toString().contains("job.jar")) {
        jarFileSize = stat.getLen();
      } else {
        jarFileSize = -1;
      }
    }
    // Here we check for double the size of jobfile to accommodate for
    // localize task file and we check four times the size of jarFileSize to 
    // accommodate for unjarring the jar file in work directory 
    Path localJobFile = lDirAlloc.getLocalPathForWrite((getJobCacheSubdir()
                                    + Path.SEPARATOR + jobId 
                                    + Path.SEPARATOR + "job.xml"),
                                    2 * jobFileSize + 5 * jarFileSize, fConf);
    RunningJob rjob = addTaskToJob(jobId, localJobFile, tip);
    synchronized (rjob) {
      if (!rjob.localized) {
  
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
        
        // create the 'work' directory
        File workDir = new File(new File(localJobFile.toString()).getParent(),
                                "work");
        if (!workDir.mkdirs()) {
          if (!workDir.isDirectory()) {
            throw new IOException("Mkdirs failed to create " + workDir.toString());
          }
        }
        
        // unjar the job.jar files in workdir
        String jarFile = localJobConf.getJar();
        if (jarFile != null) {
          localJarFile = new Path(jobDir,"job.jar");
          fs.copyToLocalFile(new Path(jarFile), localJarFile);
          localJobConf.setJar(localJarFile.toString());
          OutputStream out = localFs.create(localJobFile);
          try {
            localJobConf.write(out);
          } finally {
            out.close();
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
        tip.taskStatus.setRunState(TaskStatus.State.FAILED);
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
    TreeMap<String, TaskInProgress> tasksToClose =
      new TreeMap<String, TaskInProgress>();
    tasksToClose.putAll(tasks);
    for (TaskInProgress tip : tasksToClose.values()) {
      tip.jobHasFinished(false);
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
        
    // Shutdown the fetcher thread
    this.mapEventsFetcher.interrupt();
  }

  /**
   * Handles deprecated "mapred.tasktracker.tasks.maximum" 
   * @param newMax new max values specified through 
   * mapred.tasktracker.map.tasks.maximum or 
   * mapred.tasktracker.reduce.tasks.maximum
   * @param oldMax old max value specified through 
   * mapred.tasktracker.tasks.maximum
   * @param def default value if max tasks not specified at all.
   * @return new value supercedes old value. If both new and old values 
   * are not set, default value is returned.
   */
  private int handleDeprecatedMaxTasks(String newMax, 
                                       String oldMax,
                                       int def) {
    try {
      if (oldMax != null ) {
        LOG.warn("mapred.tasktracker.tasks.maximum is deprecated. Use " +
                 "mapred.tasktracker.map.tasks.maximum and " +
                 "mapred.tasktracker.reduce.tasks.maximum instead.");
        return Integer.parseInt(oldMax);
      }
      if (newMax != null) {
        return Integer.parseInt(newMax);
      }
    } catch (NumberFormatException ne) {
      return def;
    }
    return def;  
  }
  
  /**
   * Start with the local machine name, and the default JobTracker
   */
  public TaskTracker(JobConf conf) throws IOException {
    maxCurrentMapTasks = handleDeprecatedMaxTasks(
                           conf.get("mapred.tasktracker.map.tasks.maximum"),
                           conf.get("mapred.tasktracker.tasks.maximum"), 2);
    maxCurrentReduceTasks = handleDeprecatedMaxTasks(
                         conf.get("mapred.tasktracker.reduce.tasks.maximum"),
                         conf.get("mapred.tasktracker.tasks.maximum"), 2);
    this.fConf = conf;
    this.jobTrackAddr = JobTracker.getAddress(conf);
    this.mapOutputFile = new MapOutputFile();
    this.mapOutputFile.setConf(conf);
    String infoAddr = conf.get("mapred.task.tracker.http.bindAddress", 
                                "0.0.0.0:50060");
    InetSocketAddress infoSocAddr = NetUtils.createSocketAddr(infoAddr);
    String httpBindAddress = infoSocAddr.getHostName();
    int httpPort = infoSocAddr.getPort();
    this.server = new StatusHttpServer(
                        "task", httpBindAddress, httpPort, httpPort == 0);
    workerThreads = conf.getInt("tasktracker.http.threads", 40);
    this.shuffleServerMetrics = new ShuffleServerMetrics(fConf);
    server.setThreads(1, workerThreads);
    // let the jsp pages get to the task tracker, config, and other relevant
    // objects
    FileSystem local = FileSystem.getLocal(conf);
    this.localDirAllocator = new LocalDirAllocator("mapred.local.dir");
    server.setAttribute("task.tracker", this);
    server.setAttribute("local.file.system", local);
    server.setAttribute("conf", conf);
    server.setAttribute("log", LOG);
    server.setAttribute("localDirAllocator", localDirAllocator);
    server.setAttribute("shuffleServerMetrics", shuffleServerMetrics);
    server.addServlet("mapOutput", "/mapOutput", MapOutputServlet.class);
    server.addServlet("taskLog", "/tasklog", TaskLogServlet.class);
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
  
  /** Return the port at which the tasktracker bound to */
  public synchronized InetSocketAddress getTaskTrackerReportAddress() {
    return taskReportAddress;
  }
    
  /** Queries the job tracker for a set of outputs ready to be copied
   * @param fromEventId the first event ID we want to start from, this is
   * modified by the call to this method
   * @param jobClient the job tracker
   * @return a set of locations to copy outputs from
   * @throws IOException
   */  
  private List<TaskCompletionEvent> queryJobTracker(IntWritable fromEventId,
                                                    String jobId,
                                                    InterTrackerProtocol jobClient)
    throws IOException {

    TaskCompletionEvent t[] = jobClient.getTaskCompletionEvents(
                                                                jobId,
                                                                fromEventId.get(),
                                                                probe_sample_size);
    //we are interested in map task completion events only. So store
    //only those
    List <TaskCompletionEvent> recentMapEvents = 
      new ArrayList<TaskCompletionEvent>();
    for (int i = 0; i < t.length; i++) {
      if (t[i].isMap) {
        recentMapEvents.add(t[i]);
      }
    }
    fromEventId.set(fromEventId.get() + t.length);
    return recentMapEvents;
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

        long waitTime = heartbeatInterval - (now - lastHeartbeat);
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
        if(LOG.isDebugEnabled()) {
          LOG.debug("Got heartbeatResponse from JobTracker with responseId: " + 
                    heartbeatResponse.getResponseId() + " and " + 
                    ((actions != null) ? actions.length : 0) + " actions");
        }
        if (reinitTaskTracker(actions)) {
          return State.STALE;
        }
            
        lastHeartbeat = now;
        // resetting heartbeat interval from the response.
        heartbeatInterval = heartbeatResponse.getHeartbeatInterval();
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
        synchronized (this) {
          jobClient.reportTaskTrackerError(taskTrackerName, 
                                           "DiskErrorException", msg);
        }
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

  private long previousUpdate = 0;

  /**
   * Build and transmit the heart beat to the JobTracker
   * @return false if the tracker was unknown
   * @throws IOException
   */
  private HeartbeatResponse transmitHeartBeat() throws IOException {
    // Send Counters in the status once every COUNTER_UPDATE_INTERVAL
    long now = System.currentTimeMillis();
    boolean sendCounters;
    if (now > (previousUpdate + COUNTER_UPDATE_INTERVAL)) {
      sendCounters = true;
      previousUpdate = now;
    }
    else {
      sendCounters = false;
    }

    // 
    // Check if the last heartbeat got through... 
    // if so then build the heartbeat information for the JobTracker;
    // else resend the previous status information.
    //
    if (status == null) {
      synchronized (this) {
        status = new TaskTrackerStatus(taskTrackerName, localHostname, 
                                       httpPort, 
                                       cloneAndResetRunningTaskStatuses(
                                         sendCounters), 
                                       failures, 
                                       maxCurrentMapTasks,
                                       maxCurrentReduceTasks); 
      }
    } else {
      LOG.info("Resending 'status' to '" + jobTrackAddr.getHostName() +
               "' with reponseId '" + heartbeatResponseId);
    }
      
    //
    // Check if we should ask for a new Task
    //
    boolean askForNewTask;
    long localMinSpaceStart;
    synchronized (this) {
      askForNewTask = (mapTotal < maxCurrentMapTasks || 
                       reduceTotal < maxCurrentReduceTasks) &&
                      acceptNewTasks; 
      localMinSpaceStart = minSpaceStart;
    }
    if (askForNewTask) {
      checkLocalDirs(fConf.getLocalDirs());
      askForNewTask = enoughFreeSpace(localMinSpaceStart);
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
      
      // Clear transient status information which should only
      // be sent once to the JobTracker
      for (TaskInProgress tip: runningTasks.values()) {
        tip.getStatus().clearStatus();
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
          String msg = 
            "Task " + tip.getTask().getTaskId() + " failed to report status for " 
            + (timeSinceLastReport / 1000) + " seconds. Killing!";
          LOG.info(tip.getTask().getTaskId() + ": " + msg);
          ReflectionUtils.logThreadInfo(LOG, "lost task", 30);
          tip.reportDiagnosticInfo(msg);
          myMetrics.timedoutTask();
          purgeTask(tip, true);
        }
      }
    }
  }

  /**
   * The task tracker is done with this job, so we need to clean up.
   * @param action The action with the job
   * @throws IOException
   */
  private synchronized void purgeJob(KillJobAction action) throws IOException {
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
          tip.jobHasFinished(false);
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
   * @param wasFailure did the task fail or was it killed?
   */
  private void purgeTask(TaskInProgress tip, boolean wasFailure) 
  throws IOException {
    if (tip != null) {
      LOG.info("About to purge task: " + tip.getTask().getTaskId());
        
      // Remove the task from running jobs, 
      // removing the job if it's the last task
      removeTaskFromJob(tip.getTask().getJobId(), tip);
      tip.jobHasFinished(wasFailure);
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
    long localMinSpaceKill;
    synchronized(this){
      localMinSpaceKill = minSpaceKill;  
    }
    if (!enoughFreeSpace(localMinSpaceKill)) {
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
          purgeTask(killMe, false);
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
    for (Iterator it = runningTasks.values().iterator(); it.hasNext();) {
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
        df = localDirsDf.get(localDirs[i]);
      } else {
        df = new DF(new File(localDirs[i]), fConf);
        localDirsDf.put(localDirs[i], df);
      }

      if (df.getAvailable() > minSpace)
        return true;
    }

    return false;
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
        tip.kill(true);
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
          while (running && !staleState && !shuttingDown && !denied) {
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
    long lastProgressReport;
    StringBuffer diagnosticInfo = new StringBuffer();
    private TaskRunner runner;
    volatile boolean done = false;
    boolean wasKilled = false;
    private JobConf defaultJobConf;
    private JobConf localJobConf;
    private boolean keepFailedTaskFiles;
    private boolean alwaysKeepTaskFiles;
    private TaskStatus taskStatus; 
    private long taskTimeout;
    private String debugCommand;
        
    /**
     */
    public TaskInProgress(Task task, JobConf conf) {
      this.task = task;
      this.lastProgressReport = System.currentTimeMillis();
      this.defaultJobConf = conf;
      localJobConf = null;
      taskStatus = TaskStatus.createTaskStatus(task.isMapTask(), task.getTaskId(), 
                                               0.0f, 
                                               TaskStatus.State.UNASSIGNED, 
                                               diagnosticInfo.toString(), 
                                               "initializing",  
                                               getName(), 
                                               task.isMapTask()? TaskStatus.Phase.MAP:
                                               TaskStatus.Phase.SHUFFLE,
                                               task.getCounters()); 
      taskTimeout = (10 * 60 * 1000);
    }
        
    private void localizeTask(Task task) throws IOException{
      Path localTaskDir = 
        lDirAlloc.getLocalPathForWrite((TaskTracker.getJobCacheSubdir() + 
                    Path.SEPARATOR + task.getJobId() + Path.SEPARATOR +
                    task.getTaskId()), defaultJobConf );
      FileSystem localFs = FileSystem.getLocal(fConf);
      
      // create symlink for ../work if it already doesnt exist
      String workDir = lDirAlloc.getLocalPathToRead(
                         TaskTracker.getJobCacheSubdir() 
                         + Path.SEPARATOR + task.getJobId() 
                         + Path.SEPARATOR  
                         + "work", defaultJobConf).toString();
      String link = localTaskDir.getParent().toString() 
                      + Path.SEPARATOR + "work";
      File flink = new File(link);
      if (!flink.exists())
        FileUtil.symLink(workDir, link);
      
      // create the working-directory of the task 
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
        alwaysKeepTaskFiles = 
          Pattern.matches(keepPattern, task.getTaskId());
      } else {
        alwaysKeepTaskFiles = false;
      }
      if (task.isMapTask()) {
        debugCommand = localJobConf.getMapDebugScript();
      } else {
        debugCommand = localJobConf.getReduceDebugScript();
      }
    }
        
    /**
     */
    public Task getTask() {
      return task;
    }

    public synchronized void setJobConf(JobConf lconf){
      this.localJobConf = lconf;
      keepFailedTaskFiles = localJobConf.getKeepFailedTaskFiles();
      taskTimeout = localJobConf.getLong("mapred.task.timeout", 
                                         10 * 60 * 1000);
    }
        
    public synchronized JobConf getJobConf() {
      return localJobConf;
    }
        
    /**
     */
    public synchronized TaskStatus getStatus() {
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
      this.taskStatus.setRunState(TaskStatus.State.RUNNING);
      this.runner = task.createRunner(TaskTracker.this);
      this.runner.start();
      this.taskStatus.setStartTime(System.currentTimeMillis());
    }

    /**
     * The task is reporting its progress
     */
    public synchronized void reportProgress(TaskStatus taskStatus) 
    {
      LOG.info(task.getTaskId() + " " + taskStatus.getProgress() + 
          "% " + taskStatus.getStateString());
      
      if (this.done || 
          this.taskStatus.getRunState() != TaskStatus.State.RUNNING) {
        //make sure we ignore progress messages after a task has 
        //invoked TaskUmbilicalProtocol.done() or if the task has been
        //KILLED/FAILED
        LOG.info(task.getTaskId() + " Ignoring status-update since " +
                 ((this.done) ? "task is 'done'" : 
                                ("runState: " + this.taskStatus.getRunState()))
                 ); 
        return;
      }
      
      this.taskStatus.statusUpdate(taskStatus);
      this.lastProgressReport = System.currentTimeMillis();
    }

    /**
     */
    public long getLastProgressReport() {
      return lastProgressReport;
    }

    /**
     */
    public TaskStatus.State getRunState() {
      return taskStatus.getRunState();
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
      this.taskStatus.setRunState(TaskStatus.State.COMMIT_PENDING);
      this.taskStatus.setProgress(1.0f);
      this.taskStatus.setFinishTime(System.currentTimeMillis());
      this.done = true;
      
      LOG.info("Task " + task.getTaskId() + " is done.");
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
      while (!done && (System.currentTimeMillis() - start < WAIT_FOR_DONE)) {
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
          taskStatus.setRunState(TaskStatus.State.COMMIT_PENDING);
        } else {
          if (!wasKilled) {
            failures += 1;
            taskStatus.setRunState(TaskStatus.State.FAILED);
            // call the script here for the failed tasks.
            if (debugCommand != null) {
              String taskStdout ="";
              String taskStderr ="";
              String taskSyslog ="";
              String jobConf = task.getJobFile();
              try {
                // get task's stdout file 
                taskStdout = FileUtil.makeShellPath(TaskLog.getTaskLogFile
                                  (task.getTaskId(), TaskLog.LogName.STDOUT));
                // get task's stderr file 
                taskStderr = FileUtil.makeShellPath(TaskLog.getTaskLogFile
                                  (task.getTaskId(), TaskLog.LogName.STDERR));
                // get task's syslog file 
                taskSyslog = FileUtil.makeShellPath(TaskLog.getTaskLogFile
                                  (task.getTaskId(), TaskLog.LogName.SYSLOG));
              } catch(IOException e){
                LOG.warn("Exception finding task's stdout/err/syslog files");
              }
              File workDir = new File(task.getJobFile()).getParentFile();
              // Build the command  
              File stdout = TaskLog.getTaskLogFile(task.getTaskId(),
                                                   TaskLog.LogName.DEBUGOUT);
              // add pipes program as argument if it exists.
              String program ="";
              String executable = Submitter.getExecutable(localJobConf);
              if ( executable != null) {
            	try {
            	  program = new URI(executable).getFragment();
            	} catch (URISyntaxException ur) {
            	  LOG.warn("Problem in the URI fragment for pipes executable");
            	}	  
              }
              String [] debug = debugCommand.split(" ");
              Vector<String> vargs = new Vector<String>();
              for (String component : debug) {
                vargs.add(component);
              }
              vargs.add(taskStdout);
              vargs.add(taskStderr);
              vargs.add(taskSyslog);
              vargs.add(jobConf);
              vargs.add(program);
              try {
                List<String>  wrappedCommand = TaskLog.captureDebugOut
                                                          (vargs, stdout);
                // run the script.
                try {
                  runScript(wrappedCommand, workDir);
                } catch (IOException ioe) {
                  LOG.warn("runScript failed with: " + StringUtils.
                                                      stringifyException(ioe));
                }
              } catch(IOException e) {
                LOG.warn("Error in preparing wrapped debug command");
              }

              // add all lines of debug out to diagnostics
              try {
                int num = localJobConf.getInt("mapred.debug.out.lines", -1);
                addDiagnostics(FileUtil.makeShellPath(stdout),num,"DEBUG OUT");
              } catch(IOException ioe) {
                LOG.warn("Exception in add diagnostics!");
              }
            }
          } else {
            taskStatus.setRunState(TaskStatus.State.KILLED);
          }
          taskStatus.setProgress(0.0f);
        }
        this.taskStatus.setFinishTime(System.currentTimeMillis());
        needCleanup = (taskStatus.getRunState() == TaskStatus.State.FAILED || 
                       taskStatus.getRunState() == TaskStatus.State.KILLED);
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
     * Runs the script given in args
     * @param args script name followed by its argumnets
     * @param dir current working directory.
     * @throws IOException
     */
    public void runScript(List<String> args, File dir) throws IOException {
      ShellCommandExecutor shexec = 
              new ShellCommandExecutor(args.toArray(new String[0]), dir);
      shexec.execute();
      int exitCode = shexec.getExitCode();
      if (exitCode != 0) {
        throw new IOException("Task debug script exit with nonzero status of " 
                              + exitCode + ".");
      }
    }

    /**
     * Add last 'num' lines of the given file to the diagnostics.
     * if num =-1, all the lines of file are added to the diagnostics.
     * @param file The file from which to collect diagnostics.
     * @param num The number of lines to be sent to diagnostics.
     * @param tag The tag is printed before the diagnostics are printed. 
     */
    public void addDiagnostics(String file, int num, String tag) {
      RandomAccessFile rafile = null;
      try {
        rafile = new RandomAccessFile(file,"r");
        int no_lines =0;
        String line = null;
        StringBuffer tail = new StringBuffer();
        tail.append("\n-------------------- "+tag+"---------------------\n");
        String[] lines = null;
        if (num >0) {
          lines = new String[num];
        }
        while ((line = rafile.readLine()) != null) {
          no_lines++;
          if (num >0) {
            if (no_lines <= num) {
              lines[no_lines-1] = line;
            }
            else { // shift them up
              for (int i=0; i<num-1; ++i) {
                lines[i] = lines[i+1];
              }
              lines[num-1] = line;
            }
          }
          else if (num == -1) {
            tail.append(line); 
            tail.append("\n");
          }
        }
        int n = no_lines > num ?num:no_lines;
        if (num >0) {
          for (int i=0;i<n;i++) {
            tail.append(lines[i]);
            tail.append("\n");
          }
        }
        if(n!=0)
          reportDiagnosticInfo(tail.toString());
      } catch (FileNotFoundException fnfe){
        LOG.warn("File "+file+ " not found");
      } catch (IOException ioe){
        LOG.warn("Error reading file "+file);
      } finally {
         try {
           if (rafile != null) {
             rafile.close();
           }
         } catch (IOException ioe) {
           LOG.warn("Error closing file "+file);
         }
      }
    }
    
    /**
     * We no longer need anything from this task, as the job has
     * finished.  If the task is still running, kill it and clean up.
     * 
     * @param wasFailure did the task fail, as opposed to was it killed by
     *                   the framework
     */
    public void jobHasFinished(boolean wasFailure) throws IOException {
      // Kill the task if it is still running
      synchronized(this){
        if (getRunState() == TaskStatus.State.RUNNING) {
          kill(wasFailure);
        }
      }
      
      // Cleanup on the finished task
      cleanup();
    }

    /**
     * Something went wrong and the task must be killed.
     * @param wasFailure was it a failure (versus a kill request)?
     */
    public synchronized void kill(boolean wasFailure) throws IOException {
      if (taskStatus.getRunState() == TaskStatus.State.RUNNING) {
        wasKilled = true;
        if (wasFailure) {
          failures += 1;
        }
        runner.kill();
        taskStatus.setRunState((wasFailure) ? 
                                  TaskStatus.State.FAILED : 
                                  TaskStatus.State.KILLED);
      } else if (taskStatus.getRunState() == TaskStatus.State.UNASSIGNED) {
        if (wasFailure) {
          failures += 1;
          taskStatus.setRunState(TaskStatus.State.FAILED);
        } else {
          taskStatus.setRunState(TaskStatus.State.KILLED);
        }
      }
    }

    /**
     * The map output has been lost.
     */
    private synchronized void mapOutputLost(String failure
                                           ) throws IOException {
      //The check for COMMIT_PENDING should actually be a check for SUCCESS
      //however for that, we have to introduce another Action type from the
      //JT to the TT (SuccessTaskAction in the lines of KillTaskAction).
      if (taskStatus.getRunState() == TaskStatus.State.COMMIT_PENDING) {
        // change status to failure
        LOG.info("Reporting output lost:"+task.getTaskId());
        taskStatus.setRunState(TaskStatus.State.FAILED);
        taskStatus.setProgress(0.0f);
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
        synchronized (this){
          if (alwaysKeepTaskFiles ||
              (taskStatus.getRunState() == TaskStatus.State.FAILED && 
               keepFailedTaskFiles)) {
            return;
          }
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
    TaskInProgress tip = tasks.get(taskid);
    if (tip != null) {
      return tip.getTask();
    } else {
      return null;
    }
  }

  /**
   * Called periodically to report Task progress, from 0.0 to 1.0.
   */
  public synchronized boolean statusUpdate(String taskid, 
                                              TaskStatus taskStatus) 
  throws IOException {
    TaskInProgress tip = tasks.get(taskid);
    if (tip != null) {
      tip.reportProgress(taskStatus);
      return true;
    } else {
      LOG.warn("Progress from unknown child task: "+taskid);
      return false;
    }
  }

  /**
   * Called when the task dies before completion, and we want to report back
   * diagnostic info
   */
  public synchronized void reportDiagnosticInfo(String taskid, String info) throws IOException {
    TaskInProgress tip = tasks.get(taskid);
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
    TaskInProgress tip = tasks.get(taskid);
    if (tip != null) {
      tip.reportDone();
    } else {
      LOG.warn("Unknown child task done: "+taskid+". Ignored.");
    }
  }


  /** 
   * A reduce-task failed to shuffle the map-outputs. Kill the task.
   */  
  public synchronized void shuffleError(String taskId, String message) 
  throws IOException { 
    LOG.fatal("Task: " + taskId + " - Killed due to Shuffle Failure: " + message);
    TaskInProgress tip = runningTasks.get(taskId);
    tip.reportDiagnosticInfo("Shuffle Error: " + message);
    purgeTask(tip, true);
  }

  /** 
   * A child task had a local filesystem error. Kill the task.
   */  
  public synchronized void fsError(String taskId, String message) 
  throws IOException {
    LOG.fatal("Task: " + taskId + " - Killed due to FSError: " + message);
    TaskInProgress tip = runningTasks.get(taskId);
    tip.reportDiagnosticInfo("FSError: " + message);
    purgeTask(tip, true);
  }

  public TaskCompletionEvent[] getMapCompletionEvents(
                                                      String jobId, int fromEventId, int maxLocs) throws IOException {
      
    TaskCompletionEvent[]mapEvents = TaskCompletionEvent.EMPTY_ARRAY;
    RunningJob rjob;
    synchronized (runningJobs) {
      rjob = runningJobs.get(jobId);          
      if (rjob != null) {
        synchronized (rjob) {
          FetchStatus f = rjob.getFetchStatus();
          if (f != null) {
            mapEvents = f.getMapEvents(fromEventId, maxLocs);
          }
        }
      }
    }
    return mapEvents;
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
      tip = tasks.get(taskid);
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
    TaskInProgress tip = tasks.get(taskid);
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
    FetchStatus f;
    RunningJob(String jobid, Path jobFile) {
      this.jobid = jobid;
      localized = false;
      tasks = new HashSet<TaskInProgress>();
      this.jobFile = jobFile;
      keepJobFiles = false;
    }
      
    Path getJobFile() {
      return jobFile;
    }
      
    String getJobId() {
      return jobid;
    }
      
    void setFetchStatus(FetchStatus f) {
      this.f = f;
    }
      
    FetchStatus getFetchStatus() {
      return f;
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
      String host = args[0];
      int port = Integer.parseInt(args[1]);
      InetSocketAddress address = new InetSocketAddress(host, port);
      String taskid = args[2];
      //set a very high idle timeout so that the connection is never closed
      defaultConf.setInt("ipc.client.connection.maxidletime", 60*60*1000);
      TaskUmbilicalProtocol umbilical =
        (TaskUmbilicalProtocol)RPC.getProxy(TaskUmbilicalProtocol.class,
                                            TaskUmbilicalProtocol.versionID,
                                            address,
                                            defaultConf);
            
      Task task = umbilical.getTask(taskid);
      JobConf job = new JobConf(task.getJobFile());
      TaskLog.cleanup(job.getInt("mapred.userlog.retain.hours", 24));
      task.setConf(job);
          
      defaultConf.addResource(new Path(task.getJobFile()));
      
      // Initiate Java VM metrics
      JvmMetrics.init(task.getPhase().toString(), job.getSessionId());

      try {
        // use job-specified working directory
        FileSystem.get(job).setWorkingDirectory(job.getWorkingDirectory());
        task.run(job, umbilical);             // run the task
      } catch (FSError e) {
        LOG.fatal("FSError from child", e);
        umbilical.fsError(taskid, e.getMessage());
      } catch (Throwable throwable) {
        LOG.warn("Error running child", throwable);
        // Report back any failures, for diagnostic purposes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        throwable.printStackTrace(new PrintStream(baos));
        umbilical.reportDiagnosticInfo(taskid, baos.toString());
      } finally {
        MetricsContext metricsContext = MetricsUtil.getContext("mapred");
        metricsContext.close();
        // Shutting down log4j of the child-vm... 
        // This assumes that on return from Task.run() 
        // there is no more logging done.
        LogManager.shutdown();
      }
    }
  }

  /**
   * Get the name for this task tracker.
   * @return the string like "tracker_mymachine:50010"
   */
  String getName() {
    return taskTrackerName;
  }
    
  private synchronized List<TaskStatus> cloneAndResetRunningTaskStatuses(
                                          boolean sendCounters) {
    List<TaskStatus> result = new ArrayList<TaskStatus>(runningTasks.size());
    for(TaskInProgress tip: runningTasks.values()) {
      TaskStatus status = tip.getStatus();
      status.setIncludeCounters(sendCounters);
      // send counters for finished or failed tasks.
      if (status.getRunState() != TaskStatus.State.RUNNING) {
        status.setIncludeCounters(true);
      }
      result.add((TaskStatus)status.clone());
      status.clearStatus();
    }
    return result;
  }
  /**
   * Get the list of tasks that will be reported back to the 
   * job tracker in the next heartbeat cycle.
   * @return a copy of the list of TaskStatus objects
   */
  synchronized List<TaskStatus> getRunningTaskStatuses() {
    List<TaskStatus> result = new ArrayList<TaskStatus>(runningTasks.size());
    for(TaskInProgress tip: runningTasks.values()) {
      result.add(tip.getStatus());
    }
    return result;
  }

  /**
   * Get the list of stored tasks on this task tracker.
   * @return
   */
  synchronized List<TaskStatus> getNonRunningTasks() {
    List<TaskStatus> result = new ArrayList<TaskStatus>(tasks.size());
    for(Map.Entry<String, TaskInProgress> task: tasks.entrySet()) {
      if (!runningTasks.containsKey(task.getKey())) {
        result.add(task.getValue().getStatus());
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
   */
  private static void checkLocalDirs(String[] localDirs) 
    throws DiskErrorException {
    boolean writable = false;
        
    if (localDirs != null) {
      for (int i = 0; i < localDirs.length; i++) {
        try {
          DiskChecker.checkDir(new File(localDirs[i]));
          writable = true;
        } catch(DiskErrorException e) {
          LOG.warn("Task Tracker local " + e.getMessage());
        }
      }
    }

    if (!writable)
      throw new DiskErrorException(
                                   "all local directories are not writable");
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
    StringUtils.startupShutdownMessage(TaskTracker.class, argv, LOG);
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
    } catch (Throwable e) {
      LOG.error("Can not start task tracker because "+
                StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }
    
  /**
   * This class is used in TaskTracker's Jetty to serve the map outputs
   * to other nodes.
   */
  public static class MapOutputServlet extends HttpServlet {
    private static final int MAX_BYTES_TO_READ = 64 * 1024;
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
      // true iff IOException was caused by attempt to access input
      boolean isInputException = true;
      OutputStream outStream = null;
      FSDataInputStream indexIn = null;
      FSDataInputStream mapOutputIn = null;
      
      ShuffleServerMetrics shuffleMetrics = (ShuffleServerMetrics)
                                      context.getAttribute("shuffleServerMetrics");
      try {
        shuffleMetrics.serverHandlerBusy();
        outStream = response.getOutputStream();
        JobConf conf = (JobConf) context.getAttribute("conf");
        LocalDirAllocator lDirAlloc = 
          (LocalDirAllocator)context.getAttribute("localDirAllocator");
        FileSystem fileSys = 
          (FileSystem) context.getAttribute("local.file.system");

        // Index file
        Path indexFileName = lDirAlloc.getLocalPathToRead(
            mapId+"/file.out.index", conf);
        
        // Map-output file
        Path mapOutputFileName = lDirAlloc.getLocalPathToRead(
            mapId+"/file.out", conf);

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
          
        //set the custom "Map-Output-Length" http header to 
        //the actual number of bytes being transferred
        response.setHeader(MAP_OUTPUT_LENGTH, Long.toString(partLength));

        //use the same buffersize as used for reading the data from disk
        response.setBufferSize(MAX_BYTES_TO_READ);
        
        /**
         * Read the data from the sigle map-output file and
         * send it to the reducer.
         */
        //open the map-output file
        mapOutputIn = fileSys.open(mapOutputFileName);
        //seek to the correct offset for the reduce
        mapOutputIn.seek(startOffset);
          
        long totalRead = 0;
        int len = mapOutputIn.read(buffer, 0,
                                   partLength < MAX_BYTES_TO_READ 
                                   ? (int)partLength : MAX_BYTES_TO_READ);
        while (len > 0) {
          try {
            shuffleMetrics.outputBytes(len);
            outStream.write(buffer, 0, len);
            outStream.flush();
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
        shuffleMetrics.failedOutput();
        throw ie;
      } finally {
        if (indexIn != null) {
          indexIn.close();
        }
        if (mapOutputIn != null) {
          mapOutputIn.close();
        }
        shuffleMetrics.serverHandlerFree();
      }
      outStream.close();
      shuffleMetrics.successOutput();
    }
  }
}
