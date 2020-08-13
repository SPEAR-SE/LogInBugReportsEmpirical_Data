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

package org.apache.hadoop.mapreduce.security;

import java.io.IOException;
import java.net.URI;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HftpFileSystem;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobTracker;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.KerberosName;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;

/**
 * This class provides user facing APIs for transferring secrets from
 * the job client to the tasks.
 * The secrets can be stored just before submission of jobs and read during
 * the task execution.   
 */
//@InterfaceStability.Evolving
public class TokenCache {
  
  private static final Log LOG = LogFactory.getLog(TokenCache.class);

  /**
   * auxiliary method to get user's secret keys..
   * @param alias
   * @return secret key from the storage
   */
  public static byte[] getSecretKey(Credentials credentials, Text alias) {
    if(credentials == null)
      return null;
    return credentials.getSecretKey(alias);
  }

  /**
   * Convenience method to obtain delegation tokens from namenodes 
   * corresponding to the paths passed.
   * @param ps array of paths
   * @param conf configuration
   * @throws IOException
   */
  public static void obtainTokensForNamenodes(Credentials credentials,
                                              Path [] ps, Configuration conf) 
  throws IOException {
    if (!UserGroupInformation.isSecurityEnabled()) {
      return;
    }
    obtainTokensForNamenodesInternal(credentials, ps, conf);
  }

  static void obtainTokensForNamenodesInternal(Credentials credentials,
                                               Path [] ps, Configuration conf)
  throws IOException {
    // get jobtracker principal id (for the renewer)
    KerberosName jtKrbName = new KerberosName(conf.get(JobTracker.JT_USER_NAME, ""));
    Text delegTokenRenewer = new Text(jtKrbName.getShortName());
    boolean notReadFile = true;
    for(Path p: ps) {
      //TODO: Connecting to the namenode is not required in the case,
      //where we already have the credentials in the file
      FileSystem fs = FileSystem.get(p.toUri(), conf);
      if(fs instanceof DistributedFileSystem) {
        DistributedFileSystem dfs = (DistributedFileSystem)fs;
        URI uri = fs.getUri();
        String fs_addr = 
            SecurityUtil.buildDTServiceName(uri, NameNode.DEFAULT_PORT);

        // see if we already have the token
        Token<DelegationTokenIdentifier> token = 
          TokenCache.getDelegationToken(credentials, fs_addr); 
        if(token != null) {
          LOG.debug("DT for " + token.getService()  + " is already present");
          continue;
        }
        if (notReadFile) { //read the file only once
          String binaryTokenFilename =
            conf.get("mapreduce.job.credentials.binary");
          if (binaryTokenFilename != null) {
            credentials.readTokenStorageFile(new Path("file:///" +  
                binaryTokenFilename), conf);
          }
          notReadFile = false;
          token = 
            TokenCache.getDelegationToken(credentials, fs_addr); 
          if(token != null) {
            LOG.debug("DT for " + token.getService()  + " is already present");
            continue;
          }
        }
        // get the token
        token = dfs.getDelegationToken(delegTokenRenewer);
        if(token==null) {
          LOG.warn("Token from " + fs_addr + " is null");
          continue;
        }

        token.setService(new Text(fs_addr));
        credentials.addToken(new Text(fs_addr), token);
        LOG.info("Got dt for " + p + ";uri="+ fs_addr + 
            ";t.service="+token.getService());
      } else if (fs instanceof HftpFileSystem) {
        String fs_addr = 
          SecurityUtil.buildDTServiceName(fs.getUri(), NameNode.DEFAULT_PORT);
        Token<DelegationTokenIdentifier> token = 
          TokenCache.getDelegationToken(credentials, fs_addr); 
        if(token != null) {
          LOG.debug("DT for " + token.getService()  + " is already present");
          continue;
        }
        //the initialize method of hftp, called via FileSystem.get() done
        //earlier gets a delegation token
        Token<? extends TokenIdentifier> t = ((HftpFileSystem) fs).getDelegationToken(); 
        if (t != null) {
          credentials.addToken(new Text(fs_addr), t);
        
          // in this case port in fs_addr is port for hftp request, but
          // token's port is for RPC
          // to find the correct DT we need to know the mapping between Hftp port 
          // and RPC one. hence this new setting in the conf.
          URI uri = ((HftpFileSystem) fs).getUri();
          String key = HftpFileSystem.HFTP_SERVICE_NAME_KEY+
             SecurityUtil.buildDTServiceName(uri, NameNode.DEFAULT_PORT);
          conf.set(key, t.getService().toString());
          LOG.info("GOT dt for " + p + " and stored in conf as " + key + "=" 
              + t.getService());
        }
      }
    }
  }

  /**
   * file name used on HDFS for generated job token
   */
  //@InterfaceAudience.Private
  public static final String JOB_TOKEN_HDFS_FILE = "jobToken";

  /**
   * conf setting for job tokens cache file name
   */
  //@InterfaceAudience.Private
  public static final String JOB_TOKENS_FILENAME = "mapreduce.job.jobTokenFile";
  private static final Text JOB_TOKEN = new Text("ShuffleAndJobToken");

  /**
   * 
   * @param namenode
   * @return delegation token
   */
  @SuppressWarnings("unchecked")
  //@InterfaceAudience.Private
  public static Token<DelegationTokenIdentifier> 
  getDelegationToken(Credentials credentials, String namenode) {
    return (Token<DelegationTokenIdentifier>)
        credentials.getToken(new Text(namenode));
  }

  /**
   * load job token from a file
   * @param conf
   * @throws IOException
   */
  //@InterfaceAudience.Private
  public static Credentials loadTokens(String jobTokenFile, JobConf conf) 
  throws IOException {
    Path localJobTokenFile = new Path ("file:///" + jobTokenFile);
    
    Credentials ts = new Credentials();
    ts.readTokenStorageFile(localJobTokenFile, conf);

    if(LOG.isDebugEnabled()) {
      LOG.debug("Task: Loaded jobTokenFile from: "+localJobTokenFile.toUri().getPath() 
        +"; num of sec keys  = " + ts.numberOfSecretKeys() + " Number of tokens " + 
        ts.numberOfTokens());
    }
    return ts;
  }

  /**
   * store job token
   * @param t
   */
  //@InterfaceAudience.Private
  public static void setJobToken(Token<? extends TokenIdentifier> t, 
      Credentials credentials) {
    credentials.addToken(JOB_TOKEN, t);
  }
  /**
   * 
   * @return job token
   */
  //@InterfaceAudience.Private
  @SuppressWarnings("unchecked")
  public static Token<JobTokenIdentifier> getJobToken(Credentials credentials) {
    return (Token<JobTokenIdentifier>) credentials.getToken(JOB_TOKEN);
  }
}
