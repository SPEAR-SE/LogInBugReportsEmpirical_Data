/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.fs.ozone.contract;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.OzoneConfiguration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.contract.AbstractFSContract;
import org.apache.hadoop.fs.ozone.Constants;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.ObjectStoreHandler;
import org.apache.hadoop.ozone.MiniOzoneClassicCluster;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.rest.OzoneException;
import org.apache.hadoop.ozone.web.handlers.BucketArgs;
import org.apache.hadoop.ozone.web.handlers.UserArgs;
import org.apache.hadoop.ozone.web.handlers.VolumeArgs;
import org.apache.hadoop.ozone.web.interfaces.StorageHandler;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;
import org.junit.Assert;

import java.io.IOException;

/**
 * The contract of Ozone: only enabled if the test bucket is provided.
 */
class OzoneContract extends AbstractFSContract {

  private static MiniOzoneClassicCluster cluster;
  private static StorageHandler storageHandler;
  private static final String CONTRACT_XML = "contract/ozone.xml";

  OzoneContract(Configuration conf) {
    super(conf);
    //insert the base features
    addConfResource(CONTRACT_XML);
  }

  @Override
  public String getScheme() {
    return Constants.OZONE_URI_SCHEME;
  }

  @Override
  public Path getTestPath() {
    Path path = new Path("/test");
    return path;
  }

  public static void createCluster() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.addResource(CONTRACT_XML);

    cluster =
        new MiniOzoneClassicCluster.Builder(conf).numDataNodes(5)
            .setHandlerType(OzoneConsts.OZONE_HANDLER_DISTRIBUTED).build();
    cluster.waitClusterUp();
    storageHandler = new ObjectStoreHandler(conf).getStorageHandler();
  }

  @Override
  public FileSystem getTestFileSystem() throws IOException {
    //assumes cluster is not null
    Assert.assertNotNull("cluster not created", cluster);

    String userName = "user" + RandomStringUtils.randomNumeric(5);
    String adminName = "admin" + RandomStringUtils.randomNumeric(5);
    String volumeName = "volume" + RandomStringUtils.randomNumeric(5);
    String bucketName = "bucket" + RandomStringUtils.randomNumeric(5);


    UserArgs userArgs = new UserArgs(null, OzoneUtils.getRequestID(),
        null, null, null, null);
    VolumeArgs volumeArgs = new VolumeArgs(volumeName, userArgs);
    volumeArgs.setUserName(userName);
    volumeArgs.setAdminName(adminName);
    BucketArgs bucketArgs = new BucketArgs(volumeName, bucketName, userArgs);
    try {
      storageHandler.createVolume(volumeArgs);


      storageHandler.createBucket(bucketArgs);
    } catch (OzoneException e) {
      throw new IOException(e.getMessage());
    }
    DataNode dataNode = cluster.getDataNodes().get(0);
    final int port = dataNode.getInfoPort();

    String uri = String.format("%s://localhost:%d/%s/%s",
        Constants.OZONE_URI_SCHEME, port, volumeName, bucketName);
    getConf().set("fs.defaultFS", uri);
    return FileSystem.get(getConf());
  }

  public static void destroyCluster() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }
}
