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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.JobStatusChangeEvent.EventType;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.conf.Configuration;


public class TestCapacityScheduler extends TestCase {

  static final Log LOG =
      LogFactory.getLog(org.apache.hadoop.mapred.TestCapacityScheduler.class);

  private static int jobCounter;
  
  /**
   * Test class that removes the asynchronous nature of job initialization.
   * 
   * The run method is a dummy which just waits for completion. It is
   * expected that test code calls the main method, initializeJobs, directly
   * to trigger initialization.
   */
  class ControlledJobInitializer extends 
                              JobInitializationPoller.JobInitializationThread {
    
    boolean stopRunning;
    
    public ControlledJobInitializer(JobInitializationPoller p) {
      p.super();
    }
    
    @Override
    public void run() {
      while (!stopRunning) {
        try {
          synchronized(this) {
            this.wait();  
          }
        } catch (InterruptedException ie) {
          break;
        }
      }
    }
    
    void stopRunning() {
      stopRunning = true;
    }
  }
  
  /**
   * Test class that removes the asynchronous nature of job initialization.
   * 
   * The run method is a dummy which just waits for completion. It is
   * expected that test code calls the main method, selectJobsToInitialize,
   * directly to trigger initialization.
   * 
   * The class also creates the test worker thread objects of type 
   * ControlledJobInitializer instead of the objects of the actual class
   */
  class ControlledInitializationPoller extends JobInitializationPoller {
    
    private boolean stopRunning;
    private ArrayList<ControlledJobInitializer> workers;
    
    public ControlledInitializationPoller(JobQueuesManager mgr,
                                          CapacitySchedulerConf rmConf,
                                          Set<String> queues) {
      super(mgr, rmConf, queues);
    }
    
    @Override
    public void run() {
      // don't do anything here.
      while (!stopRunning) {
        try {
          synchronized (this) {
            this.wait();
          }
        } catch (InterruptedException ie) {
          break;
        }
      }
    }
    
    @Override
    JobInitializationThread createJobInitializationThread() {
      ControlledJobInitializer t = new ControlledJobInitializer(this);
      if (workers == null) {
        workers = new ArrayList<ControlledJobInitializer>();
      }
      workers.add(t);
      return t;
    }

    @Override
    void selectJobsToInitialize() {
      super.cleanUpInitializedJobsList();
      super.selectJobsToInitialize();
      for (ControlledJobInitializer t : workers) {
        t.initializeJobs();
      }
    }
    
    void stopRunning() {
      stopRunning = true;
      for (ControlledJobInitializer t : workers) {
        t.stopRunning();
        t.interrupt();
      }
    }
  }

  private ControlledInitializationPoller controlledInitializationPoller;

  static class FakeJobInProgress extends JobInProgress {
    
    private FakeTaskTrackerManager taskTrackerManager;
    private int mapTaskCtr;
    private int redTaskCtr;
    private Set<TaskInProgress> mapTips = 
      new HashSet<TaskInProgress>();
    private Set<TaskInProgress> reduceTips = 
      new HashSet<TaskInProgress>();
    
    public FakeJobInProgress(JobID jId, JobConf jobConf,
        FakeTaskTrackerManager taskTrackerManager, String user) {
      super(jId, jobConf);
      this.taskTrackerManager = taskTrackerManager;
      this.startTime = System.currentTimeMillis();
      this.status = new JobStatus(jId, 0f, 0f, JobStatus.PREP);
      this.status.setJobPriority(JobPriority.NORMAL);
      this.status.setStartTime(startTime);
      if (null == jobConf.getQueueName()) {
        this.profile = new JobProfile(user, jId, 
            null, null, null);
      }
      else {
        this.profile = new JobProfile(user, jId, 
            null, null, null, jobConf.getQueueName());
      }
      mapTaskCtr = 0;
      redTaskCtr = 0;
      super.setMaxVirtualMemoryForTask(jobConf.getMaxVirtualMemoryForTask());
      super.setMaxPhysicalMemoryForTask(jobConf.getMaxPhysicalMemoryForTask());
    }
    
    @Override
    public synchronized void initTasks() throws IOException {
      getStatus().setRunState(JobStatus.RUNNING);
    }

    @Override
    public Task obtainNewMapTask(final TaskTrackerStatus tts, int clusterSize,
        int ignored) throws IOException {
      if (mapTaskCtr == numMapTasks) return null;
      TaskAttemptID attemptId = getTaskAttemptID(true);
      Task task = new MapTask("", attemptId, 0, "", new BytesWritable()) {
        @Override
        public String toString() {
          return String.format("%s on %s", getTaskID(), tts.getTrackerName());
        }
      };
      taskTrackerManager.startTask(tts.getTrackerName(), task);
      runningMapTasks++;
      // create a fake TIP and keep track of it
      mapTips.add(new FakeTaskInProgress(getJobID(), 
          getJobConf(), task, true, this));
      return task;
    }
    
    @Override
    public Task obtainNewReduceTask(final TaskTrackerStatus tts,
        int clusterSize, int ignored) throws IOException {
      if (redTaskCtr == numReduceTasks) return null;
      TaskAttemptID attemptId = getTaskAttemptID(false);
      Task task = new ReduceTask("", attemptId, 0, 10) {
        @Override
        public String toString() {
          return String.format("%s on %s", getTaskID(), tts.getTrackerName());
        }
      };
      taskTrackerManager.startTask(tts.getTrackerName(), task);
      runningReduceTasks++;
      // create a fake TIP and keep track of it
      reduceTips.add(new FakeTaskInProgress(getJobID(), 
          getJobConf(), task, false, this));
      return task;
    }
    
    public void mapTaskFinished() {
      runningMapTasks--;
      finishedMapTasks++;
    }
    
    public void reduceTaskFinished() {
      runningReduceTasks--;
      finishedReduceTasks++;
    }
    
    private TaskAttemptID getTaskAttemptID(boolean isMap) {
      JobID jobId = getJobID();
      TaskType t = TaskType.REDUCE;
      if (isMap) {
        t = TaskType.MAP;
      }
      return new TaskAttemptID(jobId.getJtIdentifier(),
          jobId.getId(), t, (isMap)?++mapTaskCtr: ++redTaskCtr, 0);
    }
    
    @Override
    Set<TaskInProgress> getNonLocalRunningMaps() {
      return (Set<TaskInProgress>)mapTips;
    }
    @Override
    Set<TaskInProgress> getRunningReduces() {
      return (Set<TaskInProgress>)reduceTips;
    }
    
  }
  
  static class FakeFailingJobInProgress extends FakeJobInProgress {

    public FakeFailingJobInProgress(JobID id, JobConf jobConf,
        FakeTaskTrackerManager taskTrackerManager, String user) {
      super(id, jobConf, taskTrackerManager, user);
    }
    
    @Override
    public synchronized void initTasks() throws IOException {
      throw new IOException("Failed Initalization");
    }
    
    @Override
    synchronized void fail() {
      this.status.setRunState(JobStatus.FAILED);
    }
  }
  
  static class FakeTaskInProgress extends TaskInProgress {
    private boolean isMap;
    private FakeJobInProgress fakeJob;
    private TreeMap<TaskAttemptID, String> activeTasks;
    private TaskStatus taskStatus;
    FakeTaskInProgress(JobID jId, JobConf jobConf, Task t, 
        boolean isMap, FakeJobInProgress job) {
      super(jId, "", new JobClient.RawSplit(), null, jobConf, job, 0);
      this.isMap = isMap;
      this.fakeJob = job;
      activeTasks = new TreeMap<TaskAttemptID, String>();
      activeTasks.put(t.getTaskID(), "tt");
      // create a fake status for a task that is running for a bit
      this.taskStatus = TaskStatus.createTaskStatus(isMap);
      taskStatus.setProgress(0.5f);
      taskStatus.setRunState(TaskStatus.State.RUNNING);
    }
    
    @Override
    TreeMap<TaskAttemptID, String> getActiveTasks() {
      return activeTasks;
    }
    @Override
    public TaskStatus getTaskStatus(TaskAttemptID taskid) {
      // return a status for a task that has run a bit
      return taskStatus;
    }
    @Override
    boolean killTask(TaskAttemptID taskId, boolean shouldFail) {
      if (isMap) {
        fakeJob.mapTaskFinished();
      }
      else {
        fakeJob.reduceTaskFinished();
      }
      return true;
    }
  }
  
  static class FakeQueueManager extends QueueManager {
    private Set<String> queues = null;
    FakeQueueManager() {
      super(new Configuration());
    }
    void setQueues(Set<String> queues) {
      this.queues = queues;
    }
    public synchronized Set<String> getQueues() {
      return queues;
    }
  }
  
  static class FakeTaskTrackerManager implements TaskTrackerManager {
    int maps = 0;
    int reduces = 0;
    int maxMapTasksPerTracker = 2;
    int maxReduceTasksPerTracker = 1;
    long ttExpiryInterval = 10 * 60 * 1000L; // default interval
    List<JobInProgressListener> listeners =
      new ArrayList<JobInProgressListener>();
    FakeQueueManager qm = new FakeQueueManager();
    
    private Map<String, TaskTrackerStatus> trackers =
      new HashMap<String, TaskTrackerStatus>();
    private Map<String, TaskStatus> taskStatuses = 
      new HashMap<String, TaskStatus>();
    private Map<JobID, JobInProgress> jobs =
        new HashMap<JobID, JobInProgress>();

    public FakeTaskTrackerManager() {
      this(2, 2, 1);
    }

    public FakeTaskTrackerManager(int numTaskTrackers,
        int maxMapTasksPerTracker, int maxReduceTasksPerTracker) {
      this.maxMapTasksPerTracker = maxMapTasksPerTracker;
      this.maxReduceTasksPerTracker = maxReduceTasksPerTracker;
      for (int i = 1; i < numTaskTrackers + 1; i++) {
        String ttName = "tt" + i;
        trackers.put(ttName, new TaskTrackerStatus(ttName, ttName + ".host", i,
            new ArrayList<TaskStatus>(), 0, maxMapTasksPerTracker,
            maxReduceTasksPerTracker));
      }
    }
    
    public void addTaskTracker(String ttName) {
      trackers.put(ttName, new TaskTrackerStatus(ttName, ttName + ".host", 1,
          new ArrayList<TaskStatus>(), 0,
          maxMapTasksPerTracker, maxReduceTasksPerTracker));
    }
    
    public ClusterStatus getClusterStatus() {
      int numTrackers = trackers.size();
      return new ClusterStatus(numTrackers, 0,
          ttExpiryInterval, maps, reduces,
          numTrackers * maxMapTasksPerTracker,
          numTrackers * maxReduceTasksPerTracker,
          JobTracker.State.RUNNING);
    }

    public int getNumberOfUniqueHosts() {
      return 0;
    }

    public int getNextHeartbeatInterval() {
      return MRConstants.HEARTBEAT_INTERVAL_MIN;
    }

    @Override
    public void killJob(JobID jobid) throws IOException {
      JobInProgress job = jobs.get(jobid);
      finalizeJob(job, JobStatus.KILLED);
      job.kill();
    }

    public void removeJob(JobID jobid) {
      jobs.remove(jobid);
    }
    
    @Override
    public JobInProgress getJob(JobID jobid) {
      return jobs.get(jobid);
    }

    Collection<JobInProgress> getJobs() {
      return jobs.values();
    }

    public Collection<TaskTrackerStatus> taskTrackers() {
      return trackers.values();
    }


    public void addJobInProgressListener(JobInProgressListener listener) {
      listeners.add(listener);
    }

    public void removeJobInProgressListener(JobInProgressListener listener) {
      listeners.remove(listener);
    }
    
    public void submitJob(JobInProgress job) throws IOException {
      jobs.put(job.getJobID(), job);
      for (JobInProgressListener listener : listeners) {
        listener.jobAdded(job);
      }
    }
    
    public TaskTrackerStatus getTaskTracker(String trackerID) {
      return trackers.get(trackerID);
    }
    
    public void startTask(String taskTrackerName, final Task t) {
      if (t.isMapTask()) {
        maps++;
      } else {
        reduces++;
      }
      TaskStatus status = new TaskStatus() {
        @Override
        public TaskAttemptID getTaskID() {
          return t.getTaskID();
        }

        @Override
        public boolean getIsMap() {
          return t.isMapTask();
        }
      };
      taskStatuses.put(t.getTaskID().toString(), status);
      status.setRunState(TaskStatus.State.RUNNING);
      trackers.get(taskTrackerName).getTaskReports().add(status);
    }
    
    public void finishTask(String taskTrackerName, String tipId, 
        FakeJobInProgress j) {
      TaskStatus status = taskStatuses.get(tipId);
      if (status.getIsMap()) {
        maps--;
        j.mapTaskFinished();
      } else {
        reduces--;
        j.reduceTaskFinished();
      }
      status.setRunState(TaskStatus.State.SUCCEEDED);
    }
    
    void finalizeJob(FakeJobInProgress fjob) {
      finalizeJob(fjob, JobStatus.SUCCEEDED);
    }

    void finalizeJob(JobInProgress fjob, int state) {
      // take a snapshot of the status before changing it
      JobStatus oldStatus = (JobStatus)fjob.getStatus().clone();
      fjob.getStatus().setRunState(state);
      JobStatus newStatus = (JobStatus)fjob.getStatus().clone();
      JobStatusChangeEvent event = 
        new JobStatusChangeEvent (fjob, EventType.RUN_STATE_CHANGED, oldStatus, 
                                  newStatus);
      for (JobInProgressListener listener : listeners) {
        listener.jobUpdated(event);
      }
    }
    
    public void setPriority(FakeJobInProgress fjob, JobPriority priority) {
      // take a snapshot of the status before changing it
      JobStatus oldStatus = (JobStatus)fjob.getStatus().clone();
      fjob.setPriority(priority);
      JobStatus newStatus = (JobStatus)fjob.getStatus().clone();
      JobStatusChangeEvent event = 
        new JobStatusChangeEvent (fjob, EventType.PRIORITY_CHANGED, oldStatus, 
                                  newStatus);
      for (JobInProgressListener listener : listeners) {
        listener.jobUpdated(event);
      }
    }
    
    public void setStartTime(FakeJobInProgress fjob, long start) {
      // take a snapshot of the status before changing it
      JobStatus oldStatus = (JobStatus)fjob.getStatus().clone();
      
      fjob.startTime = start; // change the start time of the job
      fjob.status.setStartTime(start); // change the start time of the jobstatus
      
      JobStatus newStatus = (JobStatus)fjob.getStatus().clone();
      
      JobStatusChangeEvent event = 
        new JobStatusChangeEvent (fjob, EventType.START_TIME_CHANGED, oldStatus,
                                  newStatus);
      for (JobInProgressListener listener : listeners) {
        listener.jobUpdated(event);
      }
    }
    
    void addQueues(String[] arr) {
      Set<String> queues = new HashSet<String>();
      for (String s: arr) {
        queues.add(s);
      }
      qm.setQueues(queues);
    }
    
    public QueueManager getQueueManager() {
      return qm;
    }
  }
  
  // represents a fake queue configuration info
  static class FakeQueueInfo {
    String queueName;
    float gc;
    boolean supportsPrio;
    int ulMin;

    public FakeQueueInfo(String queueName, float gc, boolean supportsPrio, int ulMin) {
      this.queueName = queueName;
      this.gc = gc;
      this.supportsPrio = supportsPrio;
      this.ulMin = ulMin;
    }
  }
  
  static class FakeResourceManagerConf extends CapacitySchedulerConf {
  
    // map of queue names to queue info
    private Map<String, FakeQueueInfo> queueMap = 
      new LinkedHashMap<String, FakeQueueInfo>();
    String firstQueue;
    
    
    void setFakeQueues(List<FakeQueueInfo> queues) {
      for (FakeQueueInfo q: queues) {
        queueMap.put(q.queueName, q);
      }
      firstQueue = new String(queues.get(0).queueName);
    }
    
    public synchronized Set<String> getQueues() {
      return queueMap.keySet();
    }
    
    /*public synchronized String getFirstQueue() {
      return firstQueue;
    }*/
    
    public float getCapacity(String queue) {
      if(queueMap.get(queue).gc == -1) {
        return super.getCapacity(queue);
      }
      return queueMap.get(queue).gc;
    }
    
    public int getMinimumUserLimitPercent(String queue) {
      return queueMap.get(queue).ulMin;
    }
    
    public boolean isPrioritySupported(String queue) {
      return queueMap.get(queue).supportsPrio;
    }
    
    @Override
    public long getSleepInterval() {
      return 1;
    }
    
    @Override
    public int getMaxWorkerThreads() {
      return 1;
    }
  }

  protected class FakeClock extends CapacityTaskScheduler.Clock {
    private long time = 0;
    
    public void advance(long millis) {
      time += millis;
    }

    @Override
    long getTime() {
      return time;
    }
  }

  
  protected JobConf conf;
  protected CapacityTaskScheduler scheduler;
  private FakeTaskTrackerManager taskTrackerManager;
  private FakeResourceManagerConf resConf;
  private FakeClock clock;

  @Override
  protected void setUp() {
    setUp(2, 2, 1);
  }

  private void setUp(int numTaskTrackers, int numMapTasksPerTracker,
      int numReduceTasksPerTracker) {
    jobCounter = 0;
    taskTrackerManager =
        new FakeTaskTrackerManager(numTaskTrackers, numMapTasksPerTracker,
            numReduceTasksPerTracker);
    clock = new FakeClock();
    scheduler = new CapacityTaskScheduler(clock);
    scheduler.setTaskTrackerManager(taskTrackerManager);

    conf = new JobConf();
    // Don't let the JobInitializationPoller come in our way.
    resConf = new FakeResourceManagerConf();
    controlledInitializationPoller = new ControlledInitializationPoller(
        scheduler.jobQueuesManager,
        resConf,
        resConf.getQueues());
    scheduler.setInitializationPoller(controlledInitializationPoller);
    scheduler.setConf(conf);
  }
  
  @Override
  protected void tearDown() throws Exception {
    if (scheduler != null) {
      scheduler.terminate();
    }
  }

  private FakeJobInProgress submitJob(int state, JobConf jobConf) throws IOException {
    FakeJobInProgress job =
        new FakeJobInProgress(new JobID("test", ++jobCounter),
            (jobConf == null ? new JobConf() : jobConf), taskTrackerManager,
            jobConf.getUser());
    job.getStatus().setRunState(state);
    taskTrackerManager.submitJob(job);
    return job;
  }

  private FakeJobInProgress submitJobAndInit(int state, JobConf jobConf)
      throws IOException {
    FakeJobInProgress j = submitJob(state, jobConf);
    scheduler.jobQueuesManager.jobUpdated(initTasksAndReportEvent(j));
    return j;
  }

  private FakeJobInProgress submitJob(int state, int maps, int reduces, 
      String queue, String user) throws IOException {
    JobConf jobConf = new JobConf(conf);
    jobConf.setNumMapTasks(maps);
    jobConf.setNumReduceTasks(reduces);
    if (queue != null)
      jobConf.setQueueName(queue);
    jobConf.setUser(user);
    return submitJob(state, jobConf);
  }
  
  // Submit a job and update the listeners
  private FakeJobInProgress submitJobAndInit(int state, int maps, int reduces,
                                             String queue, String user) 
  throws IOException {
    FakeJobInProgress j = submitJob(state, maps, reduces, queue, user);
    scheduler.jobQueuesManager.jobUpdated(initTasksAndReportEvent(j));
    return j;
  }
  
  // Note that there is no concept of setup tasks here. So init itself should 
  // report the job-status change
  private JobStatusChangeEvent initTasksAndReportEvent(FakeJobInProgress jip) 
  throws IOException {
    JobStatus oldStatus = (JobStatus)jip.getStatus().clone();
    jip.initTasks();
    JobStatus newStatus = (JobStatus)jip.getStatus().clone();
    return new JobStatusChangeEvent(jip, EventType.RUN_STATE_CHANGED, 
                                    oldStatus, newStatus);
  }
  
  // test job run-state change
  public void testJobRunStateChange() throws IOException {
    // start the scheduler
    taskTrackerManager.addQueues(new String[] {"default"});
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 1));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
    
    // submit the job
    FakeJobInProgress fjob1 = 
      submitJob(JobStatus.PREP, 1, 0, "default", "user");
    
    FakeJobInProgress fjob2 = 
      submitJob(JobStatus.PREP, 1, 0, "default", "user");
    
    // test if changing the job priority/start-time works as expected in the 
    // waiting queue
    testJobOrderChange(fjob1, fjob2, true);
    
    // Init the jobs
    // simulate the case where the job with a lower priority becomes running 
    // first (may be because of the setup tasks).
    
    // init the lower ranked job first
    JobChangeEvent event = initTasksAndReportEvent(fjob2);
    
    // inform the scheduler
    scheduler.jobQueuesManager.jobUpdated(event);
    
    // init the higher ordered job later
    event = initTasksAndReportEvent(fjob1);
    
    // inform the scheduler
    scheduler.jobQueuesManager.jobUpdated(event);
    
    // check if the jobs are missing from the waiting queue
    // The jobs are not removed from waiting queue until they are scheduled 
    assertEquals("Waiting queue is garbled on job init", 2, 
                 scheduler.jobQueuesManager.getWaitingJobs("default")
                          .size());
    
    // test if changing the job priority/start-time works as expected in the 
    // running queue
    testJobOrderChange(fjob1, fjob2, false);
    
    // schedule a task
    List<Task> tasks = scheduler.assignTasks(tracker("tt1"));
    
    // complete the job
    taskTrackerManager.finishTask("tt1", tasks.get(0).getTaskID().toString(), 
                                  fjob1);
    
    // mark the job as complete
    taskTrackerManager.finalizeJob(fjob1);
    
    Collection<JobInProgress> rqueue = 
      scheduler.jobQueuesManager.getRunningJobQueue("default");
    
    // check if the job is removed from the scheduler
    assertFalse("Scheduler contains completed job", 
                rqueue.contains(fjob1));
    
    // check if the running queue size is correct
    assertEquals("Job finish garbles the queue", 
                 1, rqueue.size());

  }
  
  // test if the queue reflects the changes
  private void testJobOrderChange(FakeJobInProgress fjob1, 
                                  FakeJobInProgress fjob2, 
                                  boolean waiting) {
    String queueName = waiting ? "waiting" : "running";
    
    // check if the jobs in the queue are the right order
    JobInProgress[] jobs = getJobsInQueue(waiting);
    assertTrue(queueName + " queue doesnt contain job #1 in right order", 
                jobs[0].getJobID().equals(fjob1.getJobID()));
    assertTrue(queueName + " queue doesnt contain job #2 in right order", 
                jobs[1].getJobID().equals(fjob2.getJobID()));
    
    // I. Check the start-time change
    // Change job2 start-time and check if job2 bumps up in the queue 
    taskTrackerManager.setStartTime(fjob2, fjob1.startTime - 1);
    
    jobs = getJobsInQueue(waiting);
    assertTrue("Start time change didnt not work as expected for job #2 in "
               + queueName + " queue", 
                jobs[0].getJobID().equals(fjob2.getJobID()));
    assertTrue("Start time change didnt not work as expected for job #1 in"
               + queueName + " queue", 
                jobs[1].getJobID().equals(fjob1.getJobID()));
    
    // check if the queue is fine
    assertEquals("Start-time change garbled the " + queueName + " queue", 
                 2, jobs.length);
    
    // II. Change job priority change
    // Bump up job1's priority and make sure job1 bumps up in the queue
    taskTrackerManager.setPriority(fjob1, JobPriority.HIGH);
    
    // Check if the priority changes are reflected
    jobs = getJobsInQueue(waiting);
    assertTrue("Priority change didnt not work as expected for job #1 in "
               + queueName + " queue",  
                jobs[0].getJobID().equals(fjob1.getJobID()));
    assertTrue("Priority change didnt not work as expected for job #2 in "
               + queueName + " queue",  
                jobs[1].getJobID().equals(fjob2.getJobID()));
    
    // check if the queue is fine
    assertEquals("Priority change has garbled the " + queueName + " queue", 
                 2, jobs.length);
    
    // reset the queue state back to normal
    taskTrackerManager.setStartTime(fjob1, fjob2.startTime - 1);
    taskTrackerManager.setPriority(fjob1, JobPriority.NORMAL);
  }
  
  private JobInProgress[] getJobsInQueue(boolean waiting) {
    Collection<JobInProgress> queue = 
      waiting 
      ? scheduler.jobQueuesManager.getWaitingJobs("default")
      : scheduler.jobQueuesManager.getRunningJobQueue("default");
    return queue.toArray(new JobInProgress[0]);
  }
  
  /*protected void submitJobs(int number, int state, int maps, int reduces)
    throws IOException {
    for (int i = 0; i < number; i++) {
      submitJob(state, maps, reduces);
    }
  }*/
  
  // tests if tasks can be assinged when there are multiple jobs from a same
  // user
  public void testJobFinished() throws Exception {
    taskTrackerManager.addQueues(new String[] {"default"});
    
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit 2 jobs
    FakeJobInProgress j1 = submitJobAndInit(JobStatus.PREP, 3, 0, "default", "u1");
    FakeJobInProgress j2 = submitJobAndInit(JobStatus.PREP, 3, 0, "default", "u1");
    
    // I. Check multiple assignments with running tasks within job
    // ask for a task from first job
    Task t = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    //  ask for another task from the first job
    t = checkAssignment("tt1", "attempt_test_0001_m_000002_0 on tt1");
    
    // complete tasks
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000001_0", j1);
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000002_0", j1);
    
    // II. Check multiple assignments with running tasks across jobs
    // ask for a task from first job
    t = checkAssignment("tt1", "attempt_test_0001_m_000003_0 on tt1");
    
    //  ask for a task from the second job
    t = checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");
    
    // complete tasks
    taskTrackerManager.finishTask("tt1", "attempt_test_0002_m_000001_0", j2);
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000003_0", j1);
    
    // III. Check multiple assignments with completed tasks across jobs
    // ask for a task from the second job
    t = checkAssignment("tt1", "attempt_test_0002_m_000002_0 on tt1");
    
    // complete task
    taskTrackerManager.finishTask("tt1", "attempt_test_0002_m_000002_0", j2);
    
    // IV. Check assignment with completed job
    // finish first job
    scheduler.jobCompleted(j1);
    
    // ask for another task from the second job
    // if tasks can be assigned then the structures are properly updated 
    t = checkAssignment("tt1", "attempt_test_0002_m_000003_0 on tt1");
    
    // complete task
    taskTrackerManager.finishTask("tt1", "attempt_test_0002_m_000003_0", j2);
  }
  
  // basic tests, should be able to submit to queues
  public void testSubmitToQueues() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit a job with no queue specified. It should be accepted
    // and given to the default queue. 
    JobInProgress j = submitJobAndInit(JobStatus.PREP, 10, 10, null, "u1");
    
    // when we ask for a task, we should get one, from the job submitted
    Task t;
    t = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // submit another job, to a different queue
    j = submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u1");
    // now when we get a task, it should be from the second job
    t = checkAssignment("tt2", "attempt_test_0002_m_000001_0 on tt2");
  }
  
  public void testGetJobs() throws Exception {
    // need only one queue
    String[] qs = { "default" };
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
    HashMap<String, ArrayList<FakeJobInProgress>> subJobsList = 
      submitJobs(1, 4, "default");
   
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    
    while(mgr.getWaitingJobs("default").size() < 4){
      Thread.sleep(1);
    }
    //Raise status change events for jobs submitted.
    raiseStatusChangeEvents(mgr);
    Collection<JobInProgress> jobs = scheduler.getJobs("default");
    
    assertTrue("Number of jobs returned by scheduler is wrong" 
        ,jobs.size() == 4);
    
    assertTrue("Submitted jobs and Returned jobs are not same",
        subJobsList.get("u1").containsAll(jobs));
  }
  
  //Basic test to test capacity allocation across the queues which have no
  //capacity configured.
  
  public void testCapacityAllocationToQueues() throws Exception {
    String[] qs = {"default","q1","q2","q3","q4"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default",25.0f,true,25));
    queues.add(new FakeQueueInfo("q1",-1.0f,true,25));
    queues.add(new FakeQueueInfo("q2",-1.0f,true,25));
    queues.add(new FakeQueueInfo("q3",-1.0f,true,25));
    queues.add(new FakeQueueInfo("q4",-1.0f,true,25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start(); 
    assertEquals(18.75f, resConf.getCapacity("q1"));
    assertEquals(18.75f, resConf.getCapacity("q2"));
    assertEquals(18.75f, resConf.getCapacity("q3"));
    assertEquals(18.75f, resConf.getCapacity("q4"));
  }

  // Tests how capacity is computed and assignment of tasks done
  // on the basis of the capacity.
  public void testCapacityBasedAllocation() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    // set the gc % as 10%, so that gc will be zero initially as 
    // the cluster capacity increase slowly.
    queues.add(new FakeQueueInfo("default", 10.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 90.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
   
    // submit a job to the default queue
    submitJobAndInit(JobStatus.PREP, 10, 0, "default", "u1");
    
    // submit a job to the second queue
    submitJobAndInit(JobStatus.PREP, 10, 0, "q2", "u1");
    
    // job from q2 runs first because it has some non-zero capacity.
    checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");
    verifyCapacity("0", "default");
    verifyCapacity("3", "q2");
    
    // add another tt to increase tt slots
    taskTrackerManager.addTaskTracker("tt3");
    checkAssignment("tt2", "attempt_test_0002_m_000002_0 on tt2");
    verifyCapacity("0", "default");
    verifyCapacity("5", "q2");
    
    // add another tt to increase tt slots
    taskTrackerManager.addTaskTracker("tt4");
    checkAssignment("tt3", "attempt_test_0002_m_000003_0 on tt3");
    verifyCapacity("0", "default");
    verifyCapacity("7", "q2");
    
    // add another tt to increase tt slots
    taskTrackerManager.addTaskTracker("tt5");
    // now job from default should run, as it is furthest away
    // in terms of runningMaps / gc.
    checkAssignment("tt4", "attempt_test_0001_m_000001_0 on tt4");
    verifyCapacity("1", "default");
    verifyCapacity("9", "q2");
  }
  
  private void verifyCapacity(String expectedCapacity,
                                          String queue) throws IOException {
    String schedInfo = taskTrackerManager.getQueueManager().
                          getSchedulerInfo(queue).toString();    
    assertTrue(schedInfo.contains("Map tasks\nCapacity: " 
        + expectedCapacity));
  }
  
  // test capacity transfer
  public void testCapacityTransfer() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit a job  
    submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u1");
    // for queue 'q2', the capacity for maps is 2. Since we're the only user,
    // we should get a task 
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // I should get another map task. 
    checkAssignment("tt1", "attempt_test_0001_m_000002_0 on tt1");
    // Now we're at full capacity for maps. If I ask for another map task,
    // I should get a map task from the default queue's capacity. 
    checkAssignment("tt2", "attempt_test_0001_m_000003_0 on tt2");
    // and another
    checkAssignment("tt2", "attempt_test_0001_m_000004_0 on tt2");
  }

  // test user limits
  public void testUserLimits() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit a job  
    submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u1");
    // for queue 'q2', the capacity for maps is 2. Since we're the only user,
    // we should get a task 
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // Submit another job, from a different user
    submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u2");
    // Now if I ask for a map task, it should come from the second job 
    checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");
    // Now we're at full capacity for maps. If I ask for another map task,
    // I should get a map task from the default queue's capacity. 
    checkAssignment("tt2", "attempt_test_0001_m_000002_0 on tt2");
    // and another
    checkAssignment("tt2", "attempt_test_0002_m_000002_0 on tt2");
  }

  // test user limits when a 2nd job is submitted much after first job 
  public void testUserLimits2() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit a job  
    submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u1");
    // for queue 'q2', the capacity for maps is 2. Since we're the only user,
    // we should get a task 
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // since we're the only job, we get another map
    checkAssignment("tt1", "attempt_test_0001_m_000002_0 on tt1");
    // Submit another job, from a different user
    submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u2");
    // Now if I ask for a map task, it should come from the second job 
    checkAssignment("tt2", "attempt_test_0002_m_000001_0 on tt2");
    // and another
    checkAssignment("tt2", "attempt_test_0002_m_000002_0 on tt2");
  }

  // test user limits when a 2nd job is submitted much after first job 
  // and we need to wait for first job's task to complete
  public void testUserLimits3() throws Exception {
    // set up some queues
    String[] qs = {"default", "q2"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // submit a job  
    FakeJobInProgress j1 = submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u1");
    // for queue 'q2', the capacity for maps is 2. Since we're the only user,
    // we should get a task 
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // since we're the only job, we get another map
    checkAssignment("tt1", "attempt_test_0001_m_000002_0 on tt1");
    // we get two more maps from 'default queue'
    checkAssignment("tt2", "attempt_test_0001_m_000003_0 on tt2");
    checkAssignment("tt2", "attempt_test_0001_m_000004_0 on tt2");
    // Submit another job, from a different user
    FakeJobInProgress j2 = submitJobAndInit(JobStatus.PREP, 10, 10, "q2", "u2");
    // one of the task finishes
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000001_0", j1);
    // Now if I ask for a map task, it should come from the second job 
    checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");
    // another task from job1 finishes, another new task to job2
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000002_0", j1);
    checkAssignment("tt1", "attempt_test_0002_m_000002_0 on tt1");
    // now we have equal number of tasks from each job. Whichever job's
    // task finishes, that job gets a new task
    taskTrackerManager.finishTask("tt2", "attempt_test_0001_m_000003_0", j1);
    checkAssignment("tt2", "attempt_test_0001_m_000005_0 on tt2");
    taskTrackerManager.finishTask("tt1", "attempt_test_0002_m_000001_0", j2);
    checkAssignment("tt1", "attempt_test_0002_m_000003_0 on tt1");
  }

  // test user limits with many users, more slots
  public void testUserLimits4() throws Exception {
    // set up one queue, with 10 slots
    String[] qs = {"default"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
    // add some more TTs 
    taskTrackerManager.addTaskTracker("tt3");
    taskTrackerManager.addTaskTracker("tt4");
    taskTrackerManager.addTaskTracker("tt5");

    // u1 submits job
    FakeJobInProgress j1 = submitJobAndInit(JobStatus.PREP, 10, 10, null, "u1");
    // it gets the first 5 slots
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    checkAssignment("tt1", "attempt_test_0001_m_000002_0 on tt1");
    checkAssignment("tt2", "attempt_test_0001_m_000003_0 on tt2");
    checkAssignment("tt2", "attempt_test_0001_m_000004_0 on tt2");
    checkAssignment("tt3", "attempt_test_0001_m_000005_0 on tt3");
    // u2 submits job with 4 slots
    FakeJobInProgress j2 = submitJobAndInit(JobStatus.PREP, 4, 4, null, "u2");
    // u2 should get next 4 slots
    checkAssignment("tt3", "attempt_test_0002_m_000001_0 on tt3");
    checkAssignment("tt4", "attempt_test_0002_m_000002_0 on tt4");
    checkAssignment("tt4", "attempt_test_0002_m_000003_0 on tt4");
    checkAssignment("tt5", "attempt_test_0002_m_000004_0 on tt5");
    // last slot should go to u1, since u2 has no more tasks
    checkAssignment("tt5", "attempt_test_0001_m_000006_0 on tt5");
    // u1 finishes a task
    taskTrackerManager.finishTask("tt5", "attempt_test_0001_m_000006_0", j1);
    // u1 submits a few more jobs 
    // All the jobs are inited when submitted
    // because of addition of Eager Job Initializer all jobs in this
    //case would e initialised.
    submitJobAndInit(JobStatus.PREP, 10, 10, null, "u1");
    submitJobAndInit(JobStatus.PREP, 10, 10, null, "u1");
    submitJobAndInit(JobStatus.PREP, 10, 10, null, "u1");
    // u2 also submits a job
    submitJobAndInit(JobStatus.PREP, 10, 10, null, "u2");
    // now u3 submits a job
    submitJobAndInit(JobStatus.PREP, 2, 2, null, "u3");
    // next slot should go to u3, even though u2 has an earlier job, since
    // user limits have changed and u1/u2 are over limits
    checkAssignment("tt5", "attempt_test_0007_m_000001_0 on tt5");
    // some other task finishes and u3 gets it
    taskTrackerManager.finishTask("tt5", "attempt_test_0002_m_000004_0", j1);
    checkAssignment("tt5", "attempt_test_0007_m_000002_0 on tt5");
    // now, u2 finishes a task
    taskTrackerManager.finishTask("tt4", "attempt_test_0002_m_000002_0", j1);
    // next slot will go to u1, since u3 has nothing to run and u1's job is 
    // first in the queue
    checkAssignment("tt4", "attempt_test_0001_m_000007_0 on tt4");
  }
  
  /*
   * Following is the testing strategy for testing scheduling information.
   * - start capacity scheduler with two queues.
   * - check the scheduling information with respect to the configuration
   * which was used to configure the queues.
   * - Submit 5 jobs to a queue.
   * - Check the waiting jobs count, it should be 5.
   * - Then run initializationPoller()
   * - Check once again the waiting queue, it should be 5 jobs again.
   * - Then raise status change events.
   * - Assign one task to a task tracker. (Map)
   * - Check waiting job count, it should be 4 now and used map (%) = 100
   * - Assign another one task (Reduce)
   * - Check waiting job count, it should be 4 now and used map (%) = 100
   * and used reduce (%) = 100
   * - finish the job and then check the used percentage it should go
   * back to zero
   * - Then pick an initialized job but not scheduled job and fail it.
   * - Run the poller
   * - Check the waiting job count should now be 3.
   * - Now fail a job which has not been initialized at all.
   * - Run the poller, so that it can clean up the job queue.
   * - Check the count, the waiting job count should be 2.
   * - Now raise status change events to move the initialized jobs which
   * should be two in count to running queue.
   * - Then schedule a map of the job in running queue.
   * - Run the poller because the poller is responsible for waiting
   * jobs count. Check the count, it should be using 100% map and one
   * waiting job
   * - fail the running job.
   * - Check the count, it should be now one waiting job and zero running
   * tasks
   */

  public void testSchedulingInformation() throws Exception {
    String[] qs = {"default", "q2"};
    taskTrackerManager = new FakeTaskTrackerManager(2, 1, 1);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 50.0f, true, 25));
    queues.add(new FakeQueueInfo("q2", 50.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    scheduler.assignTasks(tracker("tt1")); // heartbeat
    scheduler.assignTasks(tracker("tt2")); // heartbeat
    int totalMaps = taskTrackerManager.getClusterStatus().getMaxMapTasks();
    int totalReduces = taskTrackerManager.getClusterStatus().getMaxReduceTasks();
    QueueManager queueManager = scheduler.taskTrackerManager.getQueueManager();
    String schedulingInfo = queueManager.getJobQueueInfo("default").getSchedulingInfo();
    String schedulingInfo2 = queueManager.getJobQueueInfo("q2").getSchedulingInfo();
    String[] infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[1] , "Capacity Percentage: 50.0%");
    assertEquals(infoStrings[6] , "Capacity: " + totalMaps * 50/100);
    assertEquals(infoStrings[10] , "Capacity: " + totalReduces * 50/100);
    assertEquals(infoStrings[2] , "User Limit: 25%");
    assertEquals(infoStrings[3] , "Priority Supported: YES");
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 0");
    assertEquals(infoStrings[15] , "Number of users who have submitted jobs: 0");
    assertEquals(schedulingInfo, schedulingInfo2);

    //Testing with actual job submission.
    ArrayList<FakeJobInProgress> userJobs =
      submitJobs(1, 5, "default").get("u1");
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");

    //waiting job should be equal to number of jobs submitted.
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 5");

    //Initalize the jobs but don't raise events
    controlledInitializationPoller.selectJobsToInitialize();

    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    //should be previous value as nothing is scheduled because no events
    //has been raised after initialization.
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 5");

    //Raise status change event so that jobs can move to running queue.
    raiseStatusChangeEvents(scheduler.jobQueuesManager);
    raiseStatusChangeEvents(scheduler.jobQueuesManager, "q2");
    //assign one job
    Task t1 = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    //Initalize extra job.
    controlledInitializationPoller.selectJobsToInitialize();

    //Get scheduling information, now the number of waiting job should have
    //changed to 4 as one is scheduled and has become running.
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 18);
    assertEquals(infoStrings[7], "Running tasks: 100.0% of Capacity");
    assertEquals(infoStrings[13],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[16] , "Number of Waiting Jobs: 4");

    //assign a reduce task
    Task t2 = checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 20);
    assertEquals(infoStrings[7], "Running tasks: 100.0% of Capacity");
    assertEquals(infoStrings[13],"Running tasks: 100.0% of Capacity");
    assertEquals(infoStrings[18] , "Number of Waiting Jobs: 4");

    //Complete the job and check the running tasks count
    FakeJobInProgress u1j1 = userJobs.get(0);
    taskTrackerManager.finishTask("tt1", t1.getTaskID().toString(), u1j1);
    taskTrackerManager.finishTask("tt1", t2.getTaskID().toString(), u1j1);
    taskTrackerManager.finalizeJob(u1j1);

    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 4");

    //Fail a job which is initialized but not scheduled and check the count.
    FakeJobInProgress u1j2 = userJobs.get(1);
    assertTrue("User1 job 2 not initalized ",
        u1j2.getStatus().getRunState() == JobStatus.RUNNING);
    taskTrackerManager.finalizeJob(u1j2, JobStatus.FAILED);
    //Run initializer to clean up failed jobs
    controlledInitializationPoller.selectJobsToInitialize();
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 3");

    //Fail a job which is not initialized but is in the waiting queue.
    FakeJobInProgress u1j5 = userJobs.get(4);
    assertFalse("User1 job 5 initalized ",
        u1j5.getStatus().getRunState() == JobStatus.RUNNING);

    taskTrackerManager.finalizeJob(u1j5, JobStatus.FAILED);
    //run initializer to clean up failed job
    controlledInitializationPoller.selectJobsToInitialize();
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 2");

    //Raise status change events as none of the intialized jobs would be
    //in running queue as we just failed the second job which was initialized
    //and completed the first one.
    raiseStatusChangeEvents(scheduler.jobQueuesManager);
    raiseStatusChangeEvents(scheduler.jobQueuesManager, "q2");

    //Now schedule a map should be job3 of the user as job1 succeeded job2
    //failed and now job3 is running
    t1 = checkAssignment("tt1", "attempt_test_0003_m_000001_0 on tt1");
    FakeJobInProgress u1j3 = userJobs.get(2);
    assertTrue("User Job 3 not running ",
        u1j3.getStatus().getRunState() == JobStatus.RUNNING);

    //now the running count of map should be one and waiting jobs should be
    //one. run the poller as it is responsible for waiting count
    controlledInitializationPoller.selectJobsToInitialize();
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 18);
    assertEquals(infoStrings[7], "Running tasks: 100.0% of Capacity");
    assertEquals(infoStrings[13],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[16] , "Number of Waiting Jobs: 1");

    //Fail the executing job
    taskTrackerManager.finalizeJob(u1j3, JobStatus.FAILED);
    // make sure we update our stats
    scheduler.updateQSIInfoForTests();
    //Now running counts should become zero
    schedulingInfo =
      queueManager.getJobQueueInfo("default").getSchedulingInfo();
    infoStrings = schedulingInfo.split("\n");
    assertEquals(infoStrings.length, 16);
    assertEquals(infoStrings[7], "Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[11],"Running tasks: 0.0% of Capacity");
    assertEquals(infoStrings[14] , "Number of Waiting Jobs: 1");

  }

  /**
   * Test to verify that highMemoryJobs are scheduled like all other jobs when
   * memory-based scheduling is not enabled.
   * @throws IOException
   */
  public void testDisabledMemoryBasedScheduling()
      throws IOException {

    LOG.debug("Starting the scheduler.");
    taskTrackerManager = new FakeTaskTrackerManager(1, 1, 1);

    // Limited TT - 1GB vmem and 512MB pmem
    taskTrackerManager.getTaskTracker("tt1").getResourceStatus()
        .setTotalVirtualMemory(1 * 1024 * 1024 * 1024L);
    taskTrackerManager.getTaskTracker("tt1").getResourceStatus()
        .setTotalPhysicalMemory(512 * 1024 * 1024L);

    taskTrackerManager.addQueues(new String[] { "default" });
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // memory-based scheduling disabled by default.
    scheduler.start();

    LOG.debug("Submit one high memory(3GB vmem, 1GBpmem) job of 1 map task "
        + "and 1 reduce task.");
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(3 * 1024 * 1024 * 1024L); // 3GB vmem
    jConf.setMaxPhysicalMemoryForTask(1 * 1024 * 1024 * 1024L); // 1 GB pmem
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(1);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    submitJobAndInit(JobStatus.RUNNING, jConf);

    // assert that all tasks are launched even though they transgress the
    // scheduling limits.

    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
  }

  /**
   * Test to verify that highPmemJobs are scheduled like all other jobs when
   * physical-memory based scheduling is not enabled.
   * @throws IOException
   */
  public void testDisabledPmemBasedScheduling()
      throws IOException {

    LOG.debug("Starting the scheduler.");
    taskTrackerManager = new FakeTaskTrackerManager(1, 1, 1);

    // Limited TT - 100GB vmem and 500MB pmem
    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    ttStatus.setTotalVirtualMemory(100 * 1024 * 1024 * 1024L);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(500 * 1024 * 1024L);
    ttStatus.setReservedPhysicalMemory(0);

    taskTrackerManager.addQueues(new String[] { "default" });
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enable vmem-based scheduling. pmem based scheduling disabled by default.
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        1536 * 1024 * 1024L);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        3 * 1024 * 1024 * 1024L);
    scheduler.start();

    LOG.debug("Submit one high pmem(3GB vmem, 1GBpmem) job of 1 map task "
        + "and 1 reduce task.");
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(3 * 1024 * 1024 * 1024L); // 3GB vmem
    jConf.setMaxPhysicalMemoryForTask(1 * 1024 * 1024 * 1024L); // 1 GB pmem
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(1);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    submitJobAndInit(JobStatus.RUNNING, jConf);

    // assert that all tasks are launched even though they transgress the
    // scheduling limits.

    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
  }

  /**
   * Test HighMemoryJobs.
   * @throws IOException
   */
  public void testHighMemoryJobs()
      throws IOException {

    LOG.debug("Starting the scheduler.");
    taskTrackerManager = new FakeTaskTrackerManager(1, 1, 1);

    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    ttStatus.setTotalVirtualMemory(3 * 1024 * 1024 * 1024L);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(1 * 1024 * 1024 * 1024L);
    ttStatus.setReservedPhysicalMemory(0);
    // Normal job on this TT would be 1.5GB vmem, 0.5GB pmem

    taskTrackerManager.addQueues(new String[] { "default" });
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enabled memory-based scheduling
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        1536 * 1024 * 1024L);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        3 * 1024 * 1024 * 1024L);
    resConf.setDefaultPercentOfPmemInVmem(33.3f);
    resConf.setLimitMaxPmemForTasks(1 * 1024 * 1024 * 1024L);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    LOG.debug("Submit one high memory(1600MB vmem, 400MB pmem) job of "
        + "1 map task and 1 reduce task.");
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(1600 * 1024 * 1024L); // 1.6GB vmem
    jConf.setMaxPhysicalMemoryForTask(400 * 1024 * 1024L); // 400MB pmem
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(1);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job1 = submitJobAndInit(JobStatus.PREP, jConf);
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");

    // No more tasks of this job can run on the TT because of lack of vmem
    assertNull(scheduler.assignTasks(tracker("tt1")));

    // Let attempt_test_0001_m_000001_0 finish, task assignment should succeed.
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000001_0", job1);
    checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");

    LOG.debug("Submit another high memory(1200MB vmem, 800MB pmem) job of "
        + "1 map task and 0 reduces.");
    jConf.setMaxVirtualMemoryForTask(1200 * 1024 * 1024L);
    jConf.setMaxPhysicalMemoryForTask(800 * 1024 * 1024L);
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(0);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    submitJobAndInit(JobStatus.PREP, jConf); // job2

    // This job shouldn't run the TT now because of lack of pmem
    assertNull(scheduler.assignTasks(tracker("tt1")));

    // Let attempt_test_0001_m_000002_0 finish, task assignment should succeed.
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_r_000001_0", job1);
    checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");

    LOG.debug("Submit a normal memory(200MB vmem, 100MB pmem) job of "
        + "0 maps and 1 reduce task.");
    jConf.setMaxVirtualMemoryForTask(200 * 1024 * 1024L);
    jConf.setMaxPhysicalMemoryForTask(100 * 1024 * 1024L);
    jConf.setNumMapTasks(0);
    jConf.setNumReduceTasks(1);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    submitJobAndInit(JobStatus.PREP, jConf); // job3

    checkAssignment("tt1", "attempt_test_0003_r_000001_0 on tt1");
  }

  /**
   * Test HADOOP-4979. 
   * Bug fix for making sure we always return null to TT if there is a 
   * high-mem job, and not look at reduce jobs (if map tasks are high-mem)
   * or vice-versa.
   * @throws IOException
   */
  public void testHighMemoryBlocking()
      throws IOException {

    // 2 map and 1 reduce slots
    taskTrackerManager = new FakeTaskTrackerManager(1, 2, 1);

    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    ttStatus.setTotalVirtualMemory(3 * 1024 * 1024 * 1024L);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(1536 * 1024 * 1024L);
    ttStatus.setReservedPhysicalMemory(0);
    // Normal job on this TT would be 1GB vmem, 0.5GB pmem

    taskTrackerManager.addQueues(new String[] { "default" });
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    resConf.setFakeQueues(queues);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enabled memory-based scheduling
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        1 * 1024 * 1024 * 1024L);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        3 * 1024 * 1024 * 1024L);
    resConf.setDefaultPercentOfPmemInVmem(33.3f);
    resConf.setLimitMaxPmemForTasks(1536 * 1024 * 1024L);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    // We need a situation where the scheduler needs to run a map task, 
    // but the available one has a high-mem requirement. There should
    // be another job whose maps or reduces can run, but they shouldn't 
    // be scheduled.
    
    LOG.debug("Submit one high memory(2GB vmem, 400MB pmem) job of "
        + "2 map tasks");
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(2 * 1024 * 1024 * 1024L); // 2GB vmem
    jConf.setMaxPhysicalMemoryForTask(400 * 1024 * 1024L); // 400MB pmem
    jConf.setNumMapTasks(2);
    jConf.setNumReduceTasks(0);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job1 = submitJobAndInit(JobStatus.PREP, jConf);
    LOG.debug("Submit another regular memory(900MB vmem, 200MB pmem) job of "
        + "2 map/red tasks");
    jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(900 * 1024 * 1024L); // 900MB vmem
    jConf.setMaxPhysicalMemoryForTask(200 * 1024 * 1024L); // 200MB pmem
    jConf.setNumMapTasks(2);
    jConf.setNumReduceTasks(2);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job2 = submitJobAndInit(JobStatus.PREP, jConf);
    
    // first, a map from j1 will run
    checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    // at this point, the scheduler tries to schedule another map from j1. 
    // there isn't enough space. There is space to run the second job's
    // map or reduce task, but they shouldn't be scheduled
    assertNull(scheduler.assignTasks(tracker("tt1")));
  }
  
  /**
   * test invalid highMemoryJobs
   * @throws IOException
   */
  public void testHighMemoryJobWithInvalidRequirements()
      throws IOException {
    LOG.debug("Starting the scheduler.");
    taskTrackerManager = new FakeTaskTrackerManager(1, 1, 1);
    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    ttStatus.setTotalVirtualMemory(3 * 1024 * 1024 * 1024);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(1 * 1024 * 1024 * 1024);
    ttStatus.setReservedPhysicalMemory(0);

    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    taskTrackerManager.addQueues(new String[] { "default" });
    resConf.setFakeQueues(queues);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enabled memory-based scheduling
    long vmemUpperLimit = 1 * 1024 * 1024 * 1024L;
    long vmemDefault = 1536 * 1024 * 1024L;
    long pmemUpperLimit = vmemUpperLimit;
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        vmemDefault);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        vmemUpperLimit);
    resConf.setDefaultPercentOfPmemInVmem(33.3f);
    resConf.setLimitMaxPmemForTasks(pmemUpperLimit);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    LOG.debug("Submit one invalid high ram(5GB vmem, 3GB pmem) job of "
        + "1 map, 0 reduce tasks.");
    long jobMaxVmem = 5 * 1024 * 1024 * 1024L;
    long jobMaxPmem = 3 * 1024 * 1024 * 1024L;
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(jobMaxVmem);
    jConf.setMaxPhysicalMemoryForTask(jobMaxPmem);
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(0);
    jConf.setQueueName("default");
    jConf.setUser("u1");

    boolean throwsException = false;
    String msg = null;
    FakeJobInProgress job;
    try {
      job = submitJob(JobStatus.PREP, jConf);
    } catch (IOException ioe) {
      // job has to fail
      throwsException = true;
      msg = ioe.getMessage();
    }

    assertTrue(throwsException);
    job = (FakeJobInProgress) taskTrackerManager.getJobs().toArray()[0];
    assertTrue(msg.matches(job.getJobID() + " \\(" + jobMaxVmem + "vmem, "
        + jobMaxPmem + "pmem\\) exceeds the cluster's max-memory-limits \\("
        + vmemUpperLimit + "vmem, " + pmemUpperLimit
        + "pmem\\). Cannot run in this cluster, so killing it."));
    // For job, no cleanup task needed so gets killed immediately.
    assertTrue(job.getStatus().getRunState() == JobStatus.KILLED);
  }

  /**
   * Test blocking of cluster for lack of memory.
   * @throws IOException
   */
  public void testClusterBlockingForLackOfMemory()
      throws IOException {

    LOG.debug("Starting the scheduler.");
    taskTrackerManager = new FakeTaskTrackerManager(1, 1, 1);
    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    ttStatus.setTotalVirtualMemory(3 * 1024 * 1024 * 1024L);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(1 * 1024 * 1024 * 1024L);
    ttStatus.setReservedPhysicalMemory(0);

    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 25));
    taskTrackerManager.addQueues(new String[] { "default" });
    resConf.setFakeQueues(queues);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enabled memory-based scheduling
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        1536 * 1024 * 1024L);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        4 * 1024 * 1024 * 1024L);
    resConf.setDefaultPercentOfPmemInVmem(33.3f);
    resConf.setLimitMaxPmemForTasks(2 * 1024 * 1024 * 1024L);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    LOG.debug("Submit one high memory(4GB vmem, 512MB pmem) job of "
        + "1 map, 0 reduce tasks.");
    JobConf jConf = new JobConf();
    jConf.setMaxVirtualMemoryForTask(4 * 1024 * 1024 * 1024L);
    jConf.setMaxPhysicalMemoryForTask(512 * 1024 * 1024L);
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(0);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job1 = submitJobAndInit(JobStatus.PREP, jConf);
    // TTs should not run these jobs i.e. cluster blocked because of lack of
    // vmem
    assertNull(scheduler.assignTasks(tracker("tt1")));
    assertNull(scheduler.assignTasks(tracker("tt1")));

    // Job should still be alive
    assertTrue(job1.getStatus().getRunState() == JobStatus.RUNNING);

    LOG.debug("Submit a normal job of 1 map, 0 reduce tasks.");
    // Use cluster-wide defaults
    jConf.setMaxVirtualMemoryForTask(JobConf.DISABLED_MEMORY_LIMIT);
    jConf.setMaxPhysicalMemoryForTask(JobConf.DISABLED_MEMORY_LIMIT);
    FakeJobInProgress job2 = submitJobAndInit(JobStatus.PREP, jConf);

    // cluster should still be blocked for job1 and so even job2 should not run
    // even though it is a normal job
    assertNull(scheduler.assignTasks(tracker("tt1")));

    scheduler.taskTrackerManager.killJob(job2.getJobID());
    scheduler.taskTrackerManager.killJob(job1.getJobID());

    LOG.debug("Submit one high memory(2GB vmem, 2GB pmem) job of "
        + "1 map, 0 reduce tasks.");
    jConf.setMaxVirtualMemoryForTask(2 * 1024 * 1024 * 1024L);
    jConf.setMaxPhysicalMemoryForTask(2 * 1024 * 1024 * 1024L);
    FakeJobInProgress job3 = submitJobAndInit(JobStatus.PREP, jConf);
    // TTs should not run these jobs i.e. cluster blocked because of lack of
    // pmem now.
    assertNull(scheduler.assignTasks(tracker("tt1")));
    assertNull(scheduler.assignTasks(tracker("tt1")));
    
    // Job should still be alive
    assertTrue(job3.getStatus().getRunState() == JobStatus.RUNNING);

    LOG.debug("Submit a normal job of 1 map, 0 reduce tasks.");
    // Use cluster-wide defaults
    jConf.setMaxVirtualMemoryForTask(JobConf.DISABLED_MEMORY_LIMIT);
    jConf.setMaxPhysicalMemoryForTask(JobConf.DISABLED_MEMORY_LIMIT);
    submitJobAndInit(JobStatus.PREP, jConf); // job4

    // cluster should still be blocked for job3 and so even job4 should not run
    // even though it is a normal job
    assertNull(scheduler.assignTasks(tracker("tt1")));
  }

  /**
   * Testcase to verify fix for a NPE (HADOOP-5641), when memory based
   * scheduling is enabled and jobs are retired from memory when tasks
   * are still active on some Tasktrackers.
   *  
   * @throws IOException
   */
  public void testMemoryMatchingWithRetiredJobs() throws IOException {
    // create a cluster with a single node.
    LOG.debug("Starting cluster with 1 tasktracker, 2 map and 2 reduce slots");
    taskTrackerManager = new FakeTaskTrackerManager(1, 2, 2);
    TaskTrackerStatus.ResourceStatus ttStatus =
        taskTrackerManager.getTaskTracker("tt1").getResourceStatus();
    LOG.debug("Assume TT has 4 GB virtual mem and 2 GB RAM");
    ttStatus.setTotalVirtualMemory(4 * 1024 * 1024 * 1024L);
    ttStatus.setReservedVirtualMemory(0);
    ttStatus.setTotalPhysicalMemory(2 * 1024 * 1024 * 1024L);
    ttStatus.setReservedPhysicalMemory(0);

    // create scheduler
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    taskTrackerManager.addQueues(new String[] { "default" });
    resConf.setFakeQueues(queues);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    // enabled memory-based scheduling
    LOG.debug("By default, jobs get 0.5 GB per task vmem" +
        " and 2 GB max vmem, with 50% of it for RAM");
    scheduler.getConf().setLong(JobConf.MAPRED_TASK_DEFAULT_MAXVMEM_PROPERTY,
        512 * 1024 * 1024L);
    scheduler.getConf().setLong(JobConf.UPPER_LIMIT_ON_TASK_VMEM_PROPERTY,
        2 * 1024 * 1024 * 1024L);
    resConf.setDefaultPercentOfPmemInVmem(50.0f);
    resConf.setLimitMaxPmemForTasks(1 * 1024 * 1024 * 1024L);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
    
    // submit a normal job
    LOG.debug("Submitting a normal job with 2 maps and 2 reduces");
    JobConf jConf = new JobConf();
    jConf.setNumMapTasks(2);
    jConf.setNumReduceTasks(2);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job1 = submitJobAndInit(JobStatus.PREP, jConf);

    // 1st cycle - 1 map gets assigned.
    Task t = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    
    // kill this job !
    taskTrackerManager.killJob(job1.getJobID());
    
    // retire the job
    taskTrackerManager.removeJob(job1.getJobID());
    
    // submit another job.
    LOG.debug("Submitting another normal job with 1 map and 1 reduce");
    jConf = new JobConf();
    jConf.setNumMapTasks(1);
    jConf.setNumReduceTasks(1);
    jConf.setQueueName("default");
    jConf.setUser("u1");
    FakeJobInProgress job2 = submitJobAndInit(JobStatus.PREP, jConf);
    
    // 2nd cycle - nothing should get assigned. Memory matching code
    // will see the job is missing and fail memory requirements.
    assertNull(scheduler.assignTasks(tracker("tt1")));
    // calling again should not make a difference, as the task is still running
    assertNull(scheduler.assignTasks(tracker("tt1")));
    
    // finish the task on the tracker.
    taskTrackerManager.finishTask("tt1", t.getTaskID().toString(), job1);
    // now a new task can be assigned.
    t = checkAssignment("tt1", "attempt_test_0002_m_000001_0 on tt1");
    // reduce can be assigned.
    t = checkAssignment("tt1", "attempt_test_0002_r_000001_0 on tt1");
  }
  
  protected TaskTrackerStatus tracker(String taskTrackerName) {
    return taskTrackerManager.getTaskTracker(taskTrackerName);
  }
  
  protected Task checkAssignment(String taskTrackerName,
      String expectedTaskString) throws IOException {
    List<Task> tasks = scheduler.assignTasks(tracker(taskTrackerName));
    assertNotNull(expectedTaskString, tasks);
    assertEquals(expectedTaskString, 1, tasks.size());
    assertEquals(expectedTaskString, tasks.get(0).toString());
    return tasks.get(0);
  }
  
  /*
   * Test cases for Job Initialization poller.
   */
  
  /*
   * This test verifies that the correct number of jobs for
   * correct number of users is initialized.
   * It also verifies that as jobs of users complete, new jobs
   * from the correct users are initialized.
   */
  public void testJobInitialization() throws Exception {
    // set up the scheduler
    String[] qs = { "default" };
    taskTrackerManager = new FakeTaskTrackerManager(2, 1, 1);
    scheduler.setTaskTrackerManager(taskTrackerManager);
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
  
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    JobInitializationPoller initPoller = scheduler.getInitializationPoller();

    // submit 4 jobs each for 3 users.
    HashMap<String, ArrayList<FakeJobInProgress>> userJobs = submitJobs(3,
        4, "default");

    // get the jobs submitted.
    ArrayList<FakeJobInProgress> u1Jobs = userJobs.get("u1");
    ArrayList<FakeJobInProgress> u2Jobs = userJobs.get("u2");
    ArrayList<FakeJobInProgress> u3Jobs = userJobs.get("u3");
    
    // reference to the initializedJobs data structure
    // changes are reflected in the set as they are made by the poller
    Set<JobID> initializedJobs = initPoller.getInitializedJobList();
    
    // we should have 12 (3 x 4) jobs in the job queue
    assertEquals(mgr.getWaitingJobs("default").size(), 12);

    // run one poller iteration.
    controlledInitializationPoller.selectJobsToInitialize();
    
    // the poller should initialize 6 jobs
    // 3 users and 2 jobs from each
    assertEquals(initializedJobs.size(), 6);

    assertTrue("Initialized jobs didnt contain the user1 job 1",
        initializedJobs.contains(u1Jobs.get(0).getJobID()));
    assertTrue("Initialized jobs didnt contain the user1 job 2",
        initializedJobs.contains(u1Jobs.get(1).getJobID()));
    assertTrue("Initialized jobs didnt contain the user2 job 1",
        initializedJobs.contains(u2Jobs.get(0).getJobID()));
    assertTrue("Initialized jobs didnt contain the user2 job 2",
        initializedJobs.contains(u2Jobs.get(1).getJobID()));
    assertTrue("Initialized jobs didnt contain the user3 job 1",
        initializedJobs.contains(u3Jobs.get(0).getJobID()));
    assertTrue("Initialized jobs didnt contain the user3 job 2",
        initializedJobs.contains(u3Jobs.get(1).getJobID()));
    
    // now submit one more job from another user.
    FakeJobInProgress u4j1 = 
      submitJob(JobStatus.PREP, 1, 1, "default", "u4");

    // run the poller again.
    controlledInitializationPoller.selectJobsToInitialize();
    
    // since no jobs have started running, there should be no
    // change to the initialized jobs.
    assertEquals(initializedJobs.size(), 6);
    assertFalse("Initialized jobs contains user 4 jobs",
        initializedJobs.contains(u4j1.getJobID()));
    
    // This event simulates raising the event on completion of setup task
    // and moves the job to the running list for the scheduler to pick up.
    raiseStatusChangeEvents(mgr);
    
    // get some tasks assigned.
    Task t1 = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    Task t2 = checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
    Task t3 = checkAssignment("tt2", "attempt_test_0002_m_000001_0 on tt2");
    Task t4 = checkAssignment("tt2", "attempt_test_0002_r_000001_0 on tt2");
    taskTrackerManager.finishTask("tt1", t1.getTaskID().toString(), u1Jobs.get(0));
    taskTrackerManager.finishTask("tt1", t2.getTaskID().toString(), u1Jobs.get(0));
    taskTrackerManager.finishTask("tt2", t3.getTaskID().toString(), u1Jobs.get(1));
    taskTrackerManager.finishTask("tt2", t4.getTaskID().toString(), u1Jobs.get(1));

    // as some jobs have running tasks, the poller will now
    // pick up new jobs to initialize.
    controlledInitializationPoller.selectJobsToInitialize();

    // count should still be the same
    assertEquals(initializedJobs.size(), 6);
    
    // new jobs that have got into the list
    assertTrue(initializedJobs.contains(u1Jobs.get(2).getJobID()));
    assertTrue(initializedJobs.contains(u1Jobs.get(3).getJobID()));
    raiseStatusChangeEvents(mgr);
    
    // the first two jobs are done, no longer in the initialized list.
    assertFalse("Initialized jobs contains the user1 job 1",
        initializedJobs.contains(u1Jobs.get(0).getJobID()));
    assertFalse("Initialized jobs contains the user1 job 2",
        initializedJobs.contains(u1Jobs.get(1).getJobID()));
    
    // finish one more job
    t1 = checkAssignment("tt1", "attempt_test_0003_m_000001_0 on tt1");
    t2 = checkAssignment("tt1", "attempt_test_0003_r_000001_0 on tt1");
    taskTrackerManager.finishTask("tt1", t1.getTaskID().toString(), u1Jobs.get(2));
    taskTrackerManager.finishTask("tt1", t2.getTaskID().toString(), u1Jobs.get(2));

    // no new jobs should be picked up, because max user limit
    // is still 3.
    controlledInitializationPoller.selectJobsToInitialize();
    
    assertEquals(initializedJobs.size(), 5);
    
    // run 1 more jobs.. 
    t1 = checkAssignment("tt1", "attempt_test_0004_m_000001_0 on tt1");
    t1 = checkAssignment("tt1", "attempt_test_0004_r_000001_0 on tt1");
    taskTrackerManager.finishTask("tt1", t1.getTaskID().toString(), u1Jobs.get(3));
    taskTrackerManager.finishTask("tt1", t2.getTaskID().toString(), u1Jobs.get(3));
    
    // Now initialised jobs should contain user 4's job, as
    // user 1's jobs are all done and the number of users is
    // below the limit
    controlledInitializationPoller.selectJobsToInitialize();
    assertEquals(initializedJobs.size(), 5);
    assertTrue(initializedJobs.contains(u4j1.getJobID()));
    
    controlledInitializationPoller.stopRunning();
  }

  /*
   * testHighPriorityJobInitialization() shows behaviour when high priority job
   * is submitted into a queue and how initialisation happens for the same.
   */
  public void testHighPriorityJobInitialization() throws Exception {
    String[] qs = { "default"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    JobInitializationPoller initPoller = scheduler.getInitializationPoller();
    Set<JobID> initializedJobsList = initPoller.getInitializedJobList();

    // submit 3 jobs for 3 users
    submitJobs(3,3,"default");
    controlledInitializationPoller.selectJobsToInitialize();
    assertEquals(initializedJobsList.size(), 6);
    
    // submit 2 job for a different user. one of them will be made high priority
    FakeJobInProgress u4j1 = submitJob(JobStatus.PREP, 1, 1, "default", "u4");
    FakeJobInProgress u4j2 = submitJob(JobStatus.PREP, 1, 1, "default", "u4");
    
    controlledInitializationPoller.selectJobsToInitialize();
    
    // shouldn't change
    assertEquals(initializedJobsList.size(), 6);
    
    assertFalse("Contains U4J1 high priority job " , 
        initializedJobsList.contains(u4j1.getJobID()));
    assertFalse("Contains U4J2 Normal priority job " , 
        initializedJobsList.contains(u4j2.getJobID()));

    // change priority of one job
    taskTrackerManager.setPriority(u4j1, JobPriority.VERY_HIGH);
    
    controlledInitializationPoller.selectJobsToInitialize();
    
    // the high priority job should get initialized, but not the
    // low priority job from u4, as we have already exceeded the
    // limit.
    assertEquals(initializedJobsList.size(), 7);
    assertTrue("Does not contain U4J1 high priority job " , 
        initializedJobsList.contains(u4j1.getJobID()));
    assertFalse("Contains U4J2 Normal priority job " , 
        initializedJobsList.contains(u4j2.getJobID()));
    controlledInitializationPoller.stopRunning();
  }
  
  public void testJobMovement() throws Exception {
    String[] qs = { "default"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();
    
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    
    // check proper running job movement and completion
    checkRunningJobMovementAndCompletion();

    // check failed running job movement
    checkFailedRunningJobMovement();

    // Check job movement of failed initalized job
    checkFailedInitializedJobMovement();

    // Check failed waiting job movement
    checkFailedWaitingJobMovement();
    
  }
  
  public void testStartWithoutDefaultQueueConfigured() throws Exception {
    //configure a single queue which is not default queue
    String[] qs = {"q1"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("q1", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    //Start the scheduler.
    scheduler.start();
    //Submit a job and wait till it completes
    FakeJobInProgress job = 
      submitJob(JobStatus.PREP, 1, 1, "q1", "u1");
    controlledInitializationPoller.selectJobsToInitialize();
    raiseStatusChangeEvents(scheduler.jobQueuesManager, "q1");
    Task t = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    t = checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
  }
  
  public void testFailedJobInitalizations() throws Exception {
    String[] qs = {"default"};
    taskTrackerManager.addQueues(qs);
    ArrayList<FakeQueueInfo> queues = new ArrayList<FakeQueueInfo>();
    queues.add(new FakeQueueInfo("default", 100.0f, true, 100));
    resConf.setFakeQueues(queues);
    scheduler.setResourceManagerConf(resConf);
    scheduler.start();

    JobQueuesManager mgr = scheduler.jobQueuesManager;
    
    //Submit a job whose initialization would fail always.
    FakeJobInProgress job =
      new FakeFailingJobInProgress(new JobID("test", ++jobCounter),
          new JobConf(), taskTrackerManager,"u1");
    job.getStatus().setRunState(JobStatus.PREP);
    taskTrackerManager.submitJob(job);
    //check if job is present in waiting list.
    assertEquals("Waiting job list does not contain submitted job",
        1, mgr.getWaitingJobCount("default"));
    assertTrue("Waiting job does not contain submitted job", 
        mgr.getWaitingJobs("default").contains(job));
    //initialization should fail now.
    controlledInitializationPoller.selectJobsToInitialize();
    //Check if the job has been properly cleaned up.
    assertEquals("Waiting job list contains submitted job",
        0, mgr.getWaitingJobCount("default"));
    assertFalse("Waiting job contains submitted job", 
        mgr.getWaitingJobs("default").contains(job));
    assertFalse("Waiting job contains submitted job", 
        mgr.getRunningJobQueue("default").contains(job));
  }

  private void checkRunningJobMovementAndCompletion() throws IOException {
    
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    JobInitializationPoller p = scheduler.getInitializationPoller();
    // submit a job
    FakeJobInProgress job = 
      submitJob(JobStatus.PREP, 1, 1, "default", "u1");
    controlledInitializationPoller.selectJobsToInitialize();
    
    assertEquals(p.getInitializedJobList().size(), 1);

    // make it running.
    raiseStatusChangeEvents(mgr);
    
    // it should be there in both the queues.
    assertTrue("Job not present in Job Queue",
        mgr.getWaitingJobs("default").contains(job));
    assertTrue("Job not present in Running Queue",
        mgr.getRunningJobQueue("default").contains(job));
    
    // assign a task
    Task t = checkAssignment("tt1", "attempt_test_0001_m_000001_0 on tt1");
    t = checkAssignment("tt1", "attempt_test_0001_r_000001_0 on tt1");
    
    controlledInitializationPoller.selectJobsToInitialize();
    
    // now this task should be removed from the initialized list.
    assertTrue(p.getInitializedJobList().isEmpty());

    // the job should also be removed from the job queue as tasks
    // are scheduled
    assertFalse("Job present in Job Queue",
        mgr.getWaitingJobs("default").contains(job));
    
    // complete tasks and job
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_m_000001_0", job);
    taskTrackerManager.finishTask("tt1", "attempt_test_0001_r_000001_0", job);
    taskTrackerManager.finalizeJob(job);
    
    // make sure it is removed from the run queue
    assertFalse("Job present in running queue",
        mgr.getRunningJobQueue("default").contains(job));
  }
  
  private void checkFailedRunningJobMovement() throws IOException {
    
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    
    //submit a job and initalized the same
    FakeJobInProgress job = 
      submitJobAndInit(JobStatus.RUNNING, 1, 1, "default", "u1");
    
    //check if the job is present in running queue.
    assertTrue("Running jobs list does not contain submitted job",
        mgr.getRunningJobQueue("default").contains(job));
    
    taskTrackerManager.finalizeJob(job, JobStatus.KILLED);
    
    //check if the job is properly removed from running queue.
    assertFalse("Running jobs list does not contain submitted job",
        mgr.getRunningJobQueue("default").contains(job));
    
  }

  private void checkFailedInitializedJobMovement() throws IOException {
    
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    JobInitializationPoller p = scheduler.getInitializationPoller();
    
    //submit a job
    FakeJobInProgress job = submitJob(JobStatus.PREP, 1, 1, "default", "u1");
    //Initialize the job
    p.selectJobsToInitialize();
    //Don't raise the status change event.
    
    //check in waiting and initialized jobs list.
    assertTrue("Waiting jobs list does not contain the job",
        mgr.getWaitingJobs("default").contains(job));
    
    assertTrue("Initialized job does not contain the job",
        p.getInitializedJobList().contains(job.getJobID()));
    
    //fail the initalized job
    taskTrackerManager.finalizeJob(job, JobStatus.KILLED);
    
    //Check if the job is present in waiting queue
    assertFalse("Waiting jobs list contains failed job",
        mgr.getWaitingJobs("default").contains(job));
    
    //run the poller to do the cleanup
    p.selectJobsToInitialize();
    
    //check for failed job in the initialized job list
    assertFalse("Initialized jobs  contains failed job",
        p.getInitializedJobList().contains(job.getJobID()));
  }
  
  private void checkFailedWaitingJobMovement() throws IOException {
    JobQueuesManager mgr = scheduler.jobQueuesManager;
    // submit a job
    FakeJobInProgress job = submitJob(JobStatus.PREP, 1, 1, "default",
        "u1");
    
    // check in waiting and initialized jobs list.
    assertTrue("Waiting jobs list does not contain the job", mgr
        .getWaitingJobs("default").contains(job));
    // fail the waiting job
    taskTrackerManager.finalizeJob(job, JobStatus.KILLED);

    // Check if the job is present in waiting queue
    assertFalse("Waiting jobs list contains failed job", mgr
        .getWaitingJobs("default").contains(job));
  }
  
  private void raiseStatusChangeEvents(JobQueuesManager mgr) {
    raiseStatusChangeEvents(mgr, "default");
  }
  
  private void raiseStatusChangeEvents(JobQueuesManager mgr, String queueName) {
    Collection<JobInProgress> jips = mgr.getWaitingJobs(queueName);
    for(JobInProgress jip : jips) {
      if(jip.getStatus().getRunState() == JobStatus.RUNNING) {
        JobStatusChangeEvent evt = new JobStatusChangeEvent(jip,
            EventType.RUN_STATE_CHANGED,jip.getStatus());
        mgr.jobUpdated(evt);
      }
    }
  }

  private HashMap<String, ArrayList<FakeJobInProgress>> submitJobs(
      int numberOfUsers, int numberOfJobsPerUser, String queue)
      throws Exception{
    HashMap<String, ArrayList<FakeJobInProgress>> userJobs = 
      new HashMap<String, ArrayList<FakeJobInProgress>>();
    for (int i = 1; i <= numberOfUsers; i++) {
      String user = String.valueOf("u" + i);
      ArrayList<FakeJobInProgress> jips = new ArrayList<FakeJobInProgress>();
      for (int j = 1; j <= numberOfJobsPerUser; j++) {
        jips.add(submitJob(JobStatus.PREP, 1, 1, queue, user));
      }
      userJobs.put(user, jips);
    }
    return userJobs;

  }
}
