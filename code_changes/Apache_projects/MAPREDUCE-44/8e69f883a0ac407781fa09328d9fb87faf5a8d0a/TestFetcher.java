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

package org.apache.hadoop.mapreduce.task.reduce;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import javax.crypto.SecretKey;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.security.SecureShuffleUtils;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.junit.Test;

/**
 * Test that the Fetcher does what we expect it to.
 */
public class TestFetcher {

  public static class FakeFetcher<K,V> extends Fetcher<K,V> {

    private HttpURLConnection connection;

    public FakeFetcher(JobConf job, TaskAttemptID reduceId,
        ShuffleScheduler<K,V> scheduler, MergeManager<K,V> merger, Reporter reporter,
        ShuffleClientMetrics metrics, ExceptionReporter exceptionReporter,
        SecretKey jobTokenSecret, HttpURLConnection connection) {
      super(job, reduceId, scheduler, merger, reporter, metrics, exceptionReporter,
          jobTokenSecret);
      this.connection = connection;
    }
    
    @Override
    protected HttpURLConnection openConnection(URL url) throws IOException {
      if(connection != null) {
        return connection;
      }
      return super.openConnection(url);
    }
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void testCopyFromHostBogusHeader() throws Exception {
    JobConf job = new JobConf();
    TaskAttemptID id = TaskAttemptID.forName("attempt_0_1_r_1_1");
    ShuffleScheduler<Text, Text> ss = mock(ShuffleScheduler.class);
    MergeManager<Text, Text> mm = mock(MergeManager.class);
    Reporter r = mock(Reporter.class);
    ShuffleClientMetrics metrics = mock(ShuffleClientMetrics.class);
    ExceptionReporter except = mock(ExceptionReporter.class);
    SecretKey key = JobTokenSecretManager.createSecretKey(new byte[]{0,0,0,0});
    HttpURLConnection connection = mock(HttpURLConnection.class);
    
    Counters.Counter allErrs = mock(Counters.Counter.class);
    when(r.getCounter(anyString(), anyString()))
      .thenReturn(allErrs);
    
    Fetcher<Text,Text> underTest = new FakeFetcher<Text,Text>(job, id, ss, mm,
        r, metrics, except, key, connection);
    

    MapHost host = new MapHost("localhost", "http://localhost:8080/");
    
    ArrayList<TaskAttemptID> maps = new ArrayList<TaskAttemptID>(1);
    TaskAttemptID map1ID = TaskAttemptID.forName("attempt_0_1_m_1_1");
    maps.add(map1ID);
    TaskAttemptID map2ID = TaskAttemptID.forName("attempt_0_1_m_2_1");
    maps.add(map2ID);
    when(ss.getMapsForHost(host)).thenReturn(maps);
    
    String encHash = "vFE234EIFCiBgYs2tCXY/SjT8Kg=";
    String replyHash = SecureShuffleUtils.generateHash(encHash.getBytes(), key);
    
    when(connection.getResponseCode()).thenReturn(200);
    when(connection.getHeaderField(SecureShuffleUtils.HTTP_HEADER_REPLY_URL_HASH))
      .thenReturn(replyHash);
    ByteArrayInputStream in = new ByteArrayInputStream(
        "5 BOGUS DATA\nBOGUS DATA\nBOGUS DATA\n".getBytes());
    when(connection.getInputStream()).thenReturn(in);
    
    underTest.copyFromHost(host);
    
    verify(connection)
      .addRequestProperty(SecureShuffleUtils.HTTP_HEADER_URL_HASH, 
          encHash);
    
    verify(allErrs).increment(1);
    verify(ss).copyFailed(map1ID, host, true);
    verify(ss).copyFailed(map2ID, host, true);
  }

}
