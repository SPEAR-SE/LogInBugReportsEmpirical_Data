/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.web.client;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.RatisTestHelper;
import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;

import static org.apache.hadoop.ozone.web.client.TestKeys.*;

/** The same as {@link TestKeys} except that this test is Ratis enabled. */
@Ignore("Disabling Ratis tests for pipeline work.")
public class TestKeysRatis {
  @Rule
  public Timeout testTimeout = new Timeout(300000);

  private static RatisTestHelper.RatisTestSuite suite;
  private static String path;
  private static OzoneRestClient ozoneRestClient;

  @BeforeClass
  public static void init() throws Exception {
    suite = new RatisTestHelper.RatisTestSuite(TestKeysRatis.class);
    path = suite.getConf().get(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT);
    ozoneRestClient = suite.newOzoneRestClient();
  }

  @AfterClass
  public static void shutdown() {
    if (suite != null) {
      suite.close();
    }
  }

  @Test
  public void testPutKey() throws OzoneException {
    runTestPutKey(new PutHelper(ozoneRestClient, path));
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestPutKey(new PutHelper(ozoneRestClient, path,
        getMultiPartKey(delimiter)));
  }

  @Test
  public void testPutAndGetKeyWithDnRestart()
      throws OzoneException, IOException, URISyntaxException {
    runTestPutAndGetKeyWithDnRestart(
        new PutHelper(ozoneRestClient, path), suite.getCluster());
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestPutAndGetKeyWithDnRestart(
        new PutHelper(ozoneRestClient, path, getMultiPartKey(delimiter)),
        suite.getCluster());
  }

  @Test
  public void testPutAndGetKey() throws OzoneException, IOException {
    runTestPutAndGetKey(new PutHelper(ozoneRestClient, path));
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestPutAndGetKey(new PutHelper(ozoneRestClient, path,
        getMultiPartKey(delimiter)));
  }

  @Test
  public void testPutAndDeleteKey() throws OzoneException, IOException {
    runTestPutAndDeleteKey(new PutHelper(ozoneRestClient, path));
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestPutAndDeleteKey(new PutHelper(ozoneRestClient, path,
        getMultiPartKey(delimiter)));
  }

  @Test
  public void testPutAndListKey()
      throws OzoneException, IOException, ParseException {
    runTestPutAndListKey(new PutHelper(ozoneRestClient, path));
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestPutAndListKey(new PutHelper(ozoneRestClient, path,
        getMultiPartKey(delimiter)));
  }

  @Test
  public void testGetKeyInfo()
      throws OzoneException, IOException, ParseException {
    runTestGetKeyInfo(new PutHelper(ozoneRestClient, path));
    String delimiter = RandomStringUtils.randomAlphanumeric(1);
    runTestGetKeyInfo(new PutHelper(ozoneRestClient, path,
        getMultiPartKey(delimiter)));
  }
}
