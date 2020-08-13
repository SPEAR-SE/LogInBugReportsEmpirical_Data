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

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.mapred.JvmManager.JvmEnv;
import org.apache.hadoop.mapred.TaskTracker.PermissionsHandler;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;

/**
 * Controls initialization, finalization and clean up of tasks, and
 * also the launching and killing of task JVMs.
 * 
 * This class defines the API for initializing, finalizing and cleaning
 * up of tasks, as also the launching and killing task JVMs.
 * Subclasses of this class will implement the logic required for
 * performing the actual actions. 
 */
abstract class TaskController implements Configurable {
  
  private Configuration conf;
  
  public static final Log LOG = LogFactory.getLog(TaskController.class);
  
  public Configuration getConf() {
    return conf;
  }

  // The list of directory paths specified in the variable mapred.local.dir.
  // This is used to determine which among the list of directories is picked up
  // for storing data for a particular task.
  protected String[] mapredLocalDirs;

  public void setConf(Configuration conf) {
    this.conf = conf;
    mapredLocalDirs = conf.getStrings("mapred.local.dir");
  }

  /**
   * Sets up the permissions of the following directories on all the configured
   * disks:
   * <ul>
   * <li>mapred-local directories</li>
   * <li>Job cache directories</li>
   * <li>Archive directories</li>
   * <li>Hadoop log directories</li>
   * </ul>
   */
  void setup() {
    for (String localDir : this.mapredLocalDirs) {
      // Set up the mapred-local directories.
      File mapredlocalDir = new File(localDir);
      if (!mapredlocalDir.exists() && !mapredlocalDir.mkdirs()) {
        LOG.warn("Unable to create mapred-local directory : "
            + mapredlocalDir.getPath());
      } else {
        PermissionsHandler.setPermissions(mapredlocalDir,
            PermissionsHandler.sevenFiveFive);
      }

      // Set up the cache directory used for distributed cache files
      File distributedCacheDir =
          new File(localDir, TaskTracker.getDistributedCacheDir());
      if (!distributedCacheDir.exists() && !distributedCacheDir.mkdirs()) {
        LOG.warn("Unable to create cache directory : "
            + distributedCacheDir.getPath());
      } else {
        PermissionsHandler.setPermissions(distributedCacheDir,
            PermissionsHandler.sevenFiveFive);
      }

      // Set up the jobcache directory
      File jobCacheDir = new File(localDir, TaskTracker.getJobCacheSubdir());
      if (!jobCacheDir.exists() && !jobCacheDir.mkdirs()) {
        LOG.warn("Unable to create job cache directory : "
            + jobCacheDir.getPath());
      } else {
        PermissionsHandler.setPermissions(jobCacheDir,
            PermissionsHandler.sevenFiveFive);
      }
    }

    // Set up the user log directory
    File taskLog = TaskLog.getUserLogDir();
    if (!taskLog.exists() && !taskLog.mkdirs()) {
      LOG.warn("Unable to create taskLog directory : " + taskLog.getPath());
    } else {
      PermissionsHandler.setPermissions(taskLog,
          PermissionsHandler.sevenFiveFive);
    }
  }

  /**
   * Take task-controller specific actions to initialize job. This involves
   * setting appropriate permissions to job-files so as to secure the files to
   * be accessible only by the user's tasks.
   * 
   * @throws IOException
   */
  abstract void initializeJob(JobInitializationContext context) throws IOException;

  /**
   * Launch a task JVM
   * 
   * This method defines how a JVM will be launched to run a task. Each
   * task-controller should also do an
   * {@link #initializeTask(TaskControllerContext)} inside this method so as to
   * initialize the task before launching it. This is for reasons of
   * task-controller specific optimizations w.r.t combining initialization and
   * launching of tasks.
   * 
   * @param context the context associated to the task
   */
  abstract void launchTaskJVM(TaskControllerContext context)
                                      throws IOException;

  /**
   * Top level cleanup a task JVM method.
   *
   * The current implementation does the following.
   * <ol>
   * <li>Sends a graceful terminate signal to task JVM allowing its sub-process
   * to cleanup.</li>
   * <li>Waits for stipulated period</li>
   * <li>Sends a forceful kill signal to task JVM, terminating all its
   * sub-process forcefully.</li>
   * </ol>
   * 
   * @param context the task for which kill signal has to be sent.
   */
  final void destroyTaskJVM(TaskControllerContext context) {
    terminateTask(context);
    try {
      Thread.sleep(context.sleeptimeBeforeSigkill);
    } catch (InterruptedException e) {
      LOG.warn("Sleep interrupted : " + 
          StringUtils.stringifyException(e));
    }
    killTask(context);
  }

  /** Perform initializing actions required before a task can run.
    * 
    * For instance, this method can be used to setup appropriate
    * access permissions for files and directories that will be
    * used by tasks. Tasks use the job cache, log, and distributed cache
    * directories and files as part of their functioning. Typically,
    * these files are shared between the daemon and the tasks
    * themselves. So, a TaskController that is launching tasks
    * as different users can implement this method to setup
    * appropriate ownership and permissions for these directories
    * and files.
    */
  abstract void initializeTask(TaskControllerContext context)
      throws IOException;

  /**
   * Contains task information required for the task controller.  
   */
  static class TaskControllerContext {
    // task being executed
    Task task;
    ShellCommandExecutor shExec;     // the Shell executor executing the JVM for this task.

    // Information used only when this context is used for launching new tasks.
    JvmEnv env;     // the JVM environment for the task.

    // Information used only when this context is used for destroying a task jvm.
    String pid; // process handle of task JVM.
    long sleeptimeBeforeSigkill; // waiting time before sending SIGKILL to task JVM after sending SIGTERM
  }

  static class JobInitializationContext {
    JobID jobid;
    File workDir;
    String user;
  }

  /**
   * Sends a graceful terminate signal to taskJVM and it sub-processes. 
   *   
   * @param context task context
   */
  abstract void terminateTask(TaskControllerContext context);
  
  /**
   * Sends a KILL signal to forcefully terminate the taskJVM and its
   * sub-processes.
   * 
   * @param context task context
   */
  abstract void killTask(TaskControllerContext context);
}
