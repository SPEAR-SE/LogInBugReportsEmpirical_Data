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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.TaskLog;
import org.apache.hadoop.mapred.TaskLog.LogName;
import org.apache.hadoop.mapred.TaskLog.LogFileDetail;
import org.apache.hadoop.mapreduce.server.tasktracker.JVMInfo;
import org.apache.hadoop.mapreduce.server.tasktracker.userlogs.UserLogManager;

/**
 * The class for truncating the user logs. 
 * Should be used only by {@link UserLogManager}. 
 *
 */
public class TaskLogsTruncater {
  static final Log LOG = LogFactory.getLog(TaskLogsTruncater.class);

  static final String MAP_USERLOG_RETAIN_SIZE =
    "mapreduce.cluster.map.userlog.retain-size";
  static final String REDUCE_USERLOG_RETAIN_SIZE =
    "mapreduce.cluster.reduce.userlog.retain-size";
  static final int DEFAULT_RETAIN_SIZE = -1;
  
  long mapRetainSize, reduceRetainSize;

  public TaskLogsTruncater(Configuration conf) {
    mapRetainSize = conf.getLong(MAP_USERLOG_RETAIN_SIZE, DEFAULT_RETAIN_SIZE);
    reduceRetainSize = conf.getLong(REDUCE_USERLOG_RETAIN_SIZE,
        DEFAULT_RETAIN_SIZE);
    LOG.info("Initializing logs' truncater with mapRetainSize=" + mapRetainSize
        + " and reduceRetainSize=" + reduceRetainSize);

  }

  private static final int DEFAULT_BUFFER_SIZE = 4 * 1024;

  static final int MINIMUM_RETAIN_SIZE_FOR_TRUNCATION = 0;

  /**
   * Process the removed task's logs. This involves truncating them to
   * retainSize.
   */
  public void truncateLogs(JVMInfo lInfo) {
    TaskAttemptID firstAttempt = TaskAttemptID.downgrade(lInfo
        .getFirstAttemptID());

    // Read the log-file details for all the attempts that ran in this JVM
    Map<Task, Map<LogName, LogFileDetail>> taskLogFileDetails;
    try {
      taskLogFileDetails = getAllLogsFileDetails(lInfo.getAllAttempts());
    } catch (IOException e) {
      LOG.warn(
          "Exception in truncateLogs while getting allLogsFileDetails()."
              + " Ignoring the truncation of logs of this process.", e);
      return;
    }

    // set this boolean to true if any of the log files is truncated
    boolean indexModified = false;

    Map<Task, Map<LogName, LogFileDetail>> updatedTaskLogFileDetails =
        new HashMap<Task, Map<LogName, LogFileDetail>>();
    // Make a copy of original indices into updated indices 
    for (LogName logName : LogName.values()) {
      copyOriginalIndexFileInfo(lInfo, taskLogFileDetails,
          updatedTaskLogFileDetails, logName);
    }

    File attemptLogDir = TaskLog.getAttemptDir(firstAttempt.toString());

    FileWriter tmpFileWriter;
    FileReader logFileReader;
    // Now truncate file by file
    logNameLoop: for (LogName logName : LogName.values()) {

      File logFile = TaskLog.getTaskLogFile(firstAttempt, logName);

      // //// Optimization: if no task is over limit, just skip truncation-code
      if (logFile.exists()
          && !isTruncationNeeded(lInfo, taskLogFileDetails, logName)) {
        LOG.debug("Truncation is not needed for "
            + logFile.getAbsolutePath());
        continue;
      }
      // //// End of optimization

      // Truncation is needed for this log-file. Go ahead now.
      File tmpFile = new File(attemptLogDir, "truncate.tmp");
      try {
        tmpFileWriter = new FileWriter(tmpFile);
      } catch (IOException ioe) {
        LOG.warn("Cannot open " + tmpFile.getAbsolutePath()
            + " for writing truncated log-file "
            + logFile.getAbsolutePath()
            + ". Continuing with other log files. ", ioe);
        continue;
      }

      try {
        logFileReader = new FileReader(logFile);
      } catch (FileNotFoundException fe) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Cannot open " + logFile.getAbsolutePath()
              + " for reading. Continuing with other log files");
        }
        if (!tmpFile.delete()) {
          LOG.warn("Cannot delete tmpFile " + tmpFile.getAbsolutePath());
        }
        continue;
      }

      long newCurrentOffset = 0;
      // Process each attempt from the ordered list passed.
      for (Task task : lInfo.getAllAttempts()) {

        // Truncate the log files of this task-attempt so that only the last
        // retainSize many bytes of this log file is retained and the log
        // file is reduced in size saving disk space.
        long retainSize =
            (task.isMapTask() ? mapRetainSize : reduceRetainSize);
        LogFileDetail newLogFileDetail = null;
        try {
          newLogFileDetail =
              truncateALogFileOfAnAttempt(task.getTaskID(),
                  taskLogFileDetails.get(task).get(logName), retainSize,
                  tmpFileWriter, logFileReader, logName);
        } catch (IOException ioe) {
          LOG.warn("Cannot truncate the log file "
              + logFile.getAbsolutePath()
              + ". Caught exception while handling " + task.getTaskID(),
              ioe);
          // revert back updatedTaskLogFileDetails
          copyOriginalIndexFileInfo(lInfo, taskLogFileDetails,
              updatedTaskLogFileDetails, logName);
          if (!tmpFile.delete()) {
            LOG.warn("Cannot delete tmpFile " + tmpFile.getAbsolutePath());
          }
          continue logNameLoop;
        }

        // Track information for updating the index file properly.
        // Index files don't track DEBUGOUT and PROFILE logs, so skip'em.
        if (TaskLog.LOGS_TRACKED_BY_INDEX_FILES.contains(logName)) {
          if (!updatedTaskLogFileDetails.containsKey(task)) {
            updatedTaskLogFileDetails.put(task,
                new HashMap<LogName, LogFileDetail>());
          }
          // newLogFileDetail already has the location and length set, just
          // set the start offset now.
          newLogFileDetail.start = newCurrentOffset;
          updatedTaskLogFileDetails.get(task).put(logName, newLogFileDetail);
          newCurrentOffset += newLogFileDetail.length;
          indexModified = true; // set the flag
        }
      }

      try {
        tmpFileWriter.close();
      } catch (IOException ioe) {
        LOG.warn("Couldn't close the tmp file " + tmpFile.getAbsolutePath()
            + ". Deleting it.", ioe);
        copyOriginalIndexFileInfo(lInfo, taskLogFileDetails,
            updatedTaskLogFileDetails, logName);
        if (!tmpFile.delete()) {
          LOG.warn("Cannot delete tmpFile " + tmpFile.getAbsolutePath());
        }
        continue;
      }

      if (!tmpFile.renameTo(logFile)) {
        // If the tmpFile cannot be renamed revert back
        // updatedTaskLogFileDetails to maintain the consistency of the
        // original log file
        copyOriginalIndexFileInfo(lInfo, taskLogFileDetails,
            updatedTaskLogFileDetails, logName);
        if (!tmpFile.delete()) {
          LOG.warn("Cannot delete tmpFile " + tmpFile.getAbsolutePath());
        }
      }
    }

    if (indexModified) {
      // Update the index files
      updateIndicesAfterLogTruncation(firstAttempt, updatedTaskLogFileDetails);
    }
  }

  /**
   * @param lInfo
   * @param taskLogFileDetails
   * @param updatedTaskLogFileDetails
   * @param logName
   */
  private void copyOriginalIndexFileInfo(JVMInfo lInfo,
      Map<Task, Map<LogName, LogFileDetail>> taskLogFileDetails,
      Map<Task, Map<LogName, LogFileDetail>> updatedTaskLogFileDetails,
      LogName logName) {
    if (TaskLog.LOGS_TRACKED_BY_INDEX_FILES.contains(logName)) {
      for (Task task : lInfo.getAllAttempts()) {
        if (!updatedTaskLogFileDetails.containsKey(task)) {
          updatedTaskLogFileDetails.put(task,
              new HashMap<LogName, LogFileDetail>());
        }
        updatedTaskLogFileDetails.get(task).put(logName,
            taskLogFileDetails.get(task).get(logName));
      }
    }
  }

  /**
   * Get the logFileDetails of all the list of attempts passed.
   * 
   * @param lInfo
   * @return a map of task to the log-file detail
   * @throws IOException
   */
  private Map<Task, Map<LogName, LogFileDetail>> getAllLogsFileDetails(
      final List<Task> allAttempts) throws IOException {
    Map<Task, Map<LogName, LogFileDetail>> taskLogFileDetails =
        new HashMap<Task, Map<LogName, LogFileDetail>>();
    for (Task task : allAttempts) {
      Map<LogName, LogFileDetail> allLogsFileDetails;
      allLogsFileDetails =
          TaskLog.getAllLogsFileDetails(task.getTaskID(),
              task.isTaskCleanupTask());
      taskLogFileDetails.put(task, allLogsFileDetails);
    }
    return taskLogFileDetails;
  }

  /**
   * Check if truncation of logs is needed for the given jvmInfo. If all the
   * tasks that ran in a JVM are within the log-limits, then truncation is not
   * needed. Otherwise it is needed.
   * 
   * @param lInfo
   * @param taskLogFileDetails
   * @param logName
   * @return true if truncation is needed, false otherwise
   */
  private boolean isTruncationNeeded(JVMInfo lInfo,
      Map<Task, Map<LogName, LogFileDetail>> taskLogFileDetails,
      LogName logName) {
    boolean truncationNeeded = false;
    LogFileDetail logFileDetail = null;
    for (Task task : lInfo.getAllAttempts()) {
      long taskRetainSize =
          (task.isMapTask() ? mapRetainSize : reduceRetainSize);
      Map<LogName, LogFileDetail> allLogsFileDetails =
          taskLogFileDetails.get(task);
      logFileDetail = allLogsFileDetails.get(logName);
      if (taskRetainSize > MINIMUM_RETAIN_SIZE_FOR_TRUNCATION
          && logFileDetail.length > taskRetainSize) {
        truncationNeeded = true;
        break;
      }
    }
    return truncationNeeded;
  }

  /**
   * Truncate the log file of this task-attempt so that only the last retainSize
   * many bytes of each log file is retained and the log file is reduced in size
   * saving disk space.
   * 
   * @param taskID Task whose logs need to be truncated
   * @param oldLogFileDetail contains the original log details for the attempt
   * @param taskRetainSize retain-size
   * @param tmpFileWriter New log file to write to. Already opened in append
   *          mode.
   * @param logFileReader Original log file to read from.
   * @return
   * @throws IOException
   */
  private LogFileDetail truncateALogFileOfAnAttempt(
      final TaskAttemptID taskID, final LogFileDetail oldLogFileDetail,
      final long taskRetainSize, final FileWriter tmpFileWriter,
      final FileReader logFileReader,
      final LogName logName) throws IOException {
    LogFileDetail newLogFileDetail = new LogFileDetail();

    // ///////////// Truncate log file ///////////////////////

    // New location of log file is same as the old
    newLogFileDetail.location = oldLogFileDetail.location;
    if (taskRetainSize > MINIMUM_RETAIN_SIZE_FOR_TRUNCATION
        && oldLogFileDetail.length > taskRetainSize) {
      LOG.info("Truncating " + logName + " logs for " + taskID + " from "
          + oldLogFileDetail.length + "bytes to " + taskRetainSize
          + "bytes.");
      newLogFileDetail.length = taskRetainSize;
    } else {
      LOG.debug("No truncation needed for " + logName + " logs for " + taskID
          + " length is " + oldLogFileDetail.length + " retain size "
          + taskRetainSize + "bytes.");
      newLogFileDetail.length = oldLogFileDetail.length;
    }
    long charsSkipped =
        logFileReader.skip(oldLogFileDetail.length
            - newLogFileDetail.length);
    if (charsSkipped != oldLogFileDetail.length - newLogFileDetail.length) {
      throw new IOException("Erroneously skipped " + charsSkipped
          + " instead of the expected "
          + (oldLogFileDetail.length - newLogFileDetail.length)
          + " while truncating " + logName + " logs for " + taskID );
    }
    long alreadyRead = 0;
    while (alreadyRead < newLogFileDetail.length) {
      char tmpBuf[]; // Temporary buffer to read logs
      if (newLogFileDetail.length - alreadyRead >= DEFAULT_BUFFER_SIZE) {
        tmpBuf = new char[DEFAULT_BUFFER_SIZE];
      } else {
        tmpBuf = new char[(int) (newLogFileDetail.length - alreadyRead)];
      }
      int bytesRead = logFileReader.read(tmpBuf);
      if (bytesRead < 0) {
        break;
      } else {
        alreadyRead += bytesRead;
      }
      tmpFileWriter.write(tmpBuf);
    }
    // ////// End of truncating log file ///////////////////////

    return newLogFileDetail;
  }

  /**
   * Truncation of logs is done. Now sync the index files to reflect the
   * truncated sizes.
   * 
   * @param firstAttempt
   * @param updatedTaskLogFileDetails
   */
  private void updateIndicesAfterLogTruncation(TaskAttemptID firstAttempt,
      Map<Task, Map<LogName, LogFileDetail>> updatedTaskLogFileDetails) {
    for (Entry<Task, Map<LogName, LogFileDetail>> entry : 
                                updatedTaskLogFileDetails.entrySet()) {
      Task task = entry.getKey();
      Map<LogName, LogFileDetail> logFileDetails = entry.getValue();
      Map<LogName, Long[]> logLengths = new HashMap<LogName, Long[]>();
      // set current and previous lengths
      for (LogName logName : TaskLog.LOGS_TRACKED_BY_INDEX_FILES) {
        logLengths.put(logName, new Long[] { Long.valueOf(0L),
            Long.valueOf(0L) });
        LogFileDetail lfd = logFileDetails.get(logName);
        if (lfd != null) {
          // Set previous lengths
          logLengths.get(logName)[0] = Long.valueOf(lfd.start);
          // Set current lengths
          logLengths.get(logName)[1] = Long.valueOf(lfd.start + lfd.length);
        }
      }
      try {
        TaskLog.writeToIndexFile(firstAttempt, task.getTaskID(),
            task.isTaskCleanupTask(), logLengths);
      } catch (IOException ioe) {
        LOG.warn("Exception encountered while updating index file of task "
            + task.getTaskID()
            + ". Ignoring and continuing with other tasks.", ioe);
      }
    }
  }

}
