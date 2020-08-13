/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.container.common.impl;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.hadoop.ozone.container.common.helpers.ContainerData;
import org.apache.hadoop.ozone.container.common.helpers.ContainerUtils;
import org.apache.hadoop.ozone.container.common.helpers.KeyData;
import org.apache.hadoop.utils.LevelDBStore;
import org.apache.hadoop.ozone.web.utils.OzoneUtils;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

import static org.apache.hadoop.ozone.container.ContainerTestHelper
    .createSingleNodePipeline;
import static org.apache.hadoop.ozone.container.ContainerTestHelper.getChunk;
import static org.apache.hadoop.ozone.container.ContainerTestHelper.getData;
import static org.apache.hadoop.ozone.container.ContainerTestHelper
    .setDataChecksum;
import static org.junit.Assert.fail;

/**
 * Simple tests to verify that container persistence works as expected.
 */
public class TestContainerPersistence {
  @Rule
  public ExpectedException exception = ExpectedException.none();

  /**
   * Set the timeout for every test.
   */
  @Rule
  public Timeout testTimeout = new Timeout(300000);

  private static String path;
  private static ContainerManagerImpl containerManager;
  private static ChunkManagerImpl chunkManager;
  private static KeyManagerImpl keyManager;
  private static OzoneConfiguration conf;
  private static MiniOzoneCluster cluster;
  private static List<StorageLocation> pathLists = new LinkedList<>();

  @BeforeClass
  public static void init() throws Throwable {
    conf = new OzoneConfiguration();
    URL p = conf.getClass().getResource("");
    path = p.getPath().concat(
        TestContainerPersistence.class.getSimpleName());
    path += conf.getTrimmed(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT,
        OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT_DEFAULT);
    conf.set(OzoneConfigKeys.OZONE_LOCALSTORAGE_ROOT, path);

    File containerDir = new File(path);
    if (containerDir.exists()) {
      FileUtils.deleteDirectory(new File(path));
    }
    Assert.assertTrue(containerDir.mkdirs());

    cluster = new MiniOzoneCluster.Builder(conf)
        .setHandlerType("distributed").build();
    containerManager = new ContainerManagerImpl();
    chunkManager = new ChunkManagerImpl(containerManager);
    containerManager.setChunkManager(chunkManager);
    keyManager = new KeyManagerImpl(containerManager, conf);
    containerManager.setKeyManager(keyManager);

  }

  @AfterClass
  public static void shutdown() throws IOException {
    if(cluster != null) {
      cluster.shutdown();
    }
    FileUtils.deleteDirectory(new File(path));
  }

  @Before
  public void setupPaths() throws IOException {
    if (!new File(path).exists() && !new File(path).mkdirs()) {
      throw new IOException("Unable to create paths. " + path);
    }
    pathLists.clear();
    containerManager.getContainerMap().clear();
    pathLists.add(StorageLocation.parse(path.toString()));
    containerManager.init(conf, pathLists);
  }

  @After
  public void cleanupDir() throws IOException {
    FileUtils.deleteDirectory(new File(path));
  }

  @Test
  public void testCreateContainer() throws Exception {

    String containerName = OzoneUtils.getRequestID();
    ContainerData data = new ContainerData(containerName);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");
    containerManager.createContainer(createSingleNodePipeline(containerName),
        data);
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName));
    ContainerManagerImpl.ContainerStatus status = containerManager
        .getContainerMap().get(containerName);

    Assert.assertTrue(status.isActive());
    Assert.assertNotNull(status.getContainer().getContainerPath());
    Assert.assertNotNull(status.getContainer().getDBPath());


    Assert.assertTrue(new File(status.getContainer().getContainerPath())
        .exists());

    Path meta = Paths.get(status.getContainer().getDBPath()).getParent();
    Assert.assertTrue(meta != null && Files.exists(meta));


    String dbPath = status.getContainer().getDBPath();
    LevelDBStore store = null;
    try {
      store = new LevelDBStore(new File(dbPath), false);
      Assert.assertNotNull(store.getDB());
    } finally {
      if (store != null) {
        store.close();
      }
    }
  }

  @Test
  public void testCreateDuplicateContainer() throws Exception {
    String containerName = OzoneUtils.getRequestID();

    ContainerData data = new ContainerData(containerName);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");
    containerManager.createContainer(createSingleNodePipeline(containerName),
        data);
    try {
      containerManager.createContainer(createSingleNodePipeline(
          containerName), data);
      fail("Expected Exception not thrown.");
    } catch (IOException ex) {
      Assert.assertNotNull(ex);
    }
  }

  @Test
  public void testDeleteContainer() throws Exception {
    String containerName1 = OzoneUtils.getRequestID();
    String containerName2 = OzoneUtils.getRequestID();


    ContainerData data = new ContainerData(containerName1);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");
    containerManager.createContainer(createSingleNodePipeline(containerName1),
        data);

    data = new ContainerData(containerName2);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");
    containerManager.createContainer(createSingleNodePipeline(containerName2),
        data);

    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName1));
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName2));

    containerManager.deleteContainer(createSingleNodePipeline(containerName1),
        containerName1);
    Assert.assertFalse(containerManager.getContainerMap()
        .containsKey(containerName1));

    // Let us make sure that we are able to re-use a container name after
    // delete.

    data = new ContainerData(containerName1);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");
    containerManager.createContainer(createSingleNodePipeline(containerName1),
        data);

    // Assert we still have both containers.
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName1));
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName2));

    // Add some key to a container and then delete.
    // Delete should fail because the container is no longer empty.
    KeyData someKey = new KeyData(containerName1, "someKey");
    someKey.setChunks(new LinkedList<ContainerProtos.ChunkInfo>());
    keyManager.putKey(
        createSingleNodePipeline(containerName1),
        someKey);

    exception.expect(StorageContainerException.class);
    exception.expectMessage(
        "Container cannot be deleted because it is not empty.");
    containerManager.deleteContainer(
        createSingleNodePipeline(containerName1),
        containerName1);
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName1));
  }

  /**
   * This test creates 1000 containers and reads them back 5 containers at a
   * time and verifies that we did get back all containers.
   *
   * @throws IOException
   */
  @Test
  public void testListContainer() throws IOException {
    final int count = 1000;
    final int step = 5;

    Map<String, ContainerData> testMap = new HashMap<>();
    for (int x = 0; x < count; x++) {
      String containerName = OzoneUtils.getRequestID();

      ContainerData data = new ContainerData(containerName);
      data.addMetadata("VOLUME", "shire");
      data.addMetadata("owner)", "bilbo");
      containerManager.createContainer(createSingleNodePipeline(containerName),
          data);
      testMap.put(containerName, data);
    }

    int counter = 0;
    String prevKey = "";
    List<ContainerData> results = new LinkedList<>();
    while (counter < count) {
      containerManager.listContainer(null, step, prevKey, results);
      for (int y = 0; y < results.size(); y++) {
        testMap.remove(results.get(y).getContainerName());
      }
      counter += step;
      String nextKey = results.get(results.size() - 1).getContainerName();

      //Assert that container is returning results in a sorted fashion.
      Assert.assertTrue(prevKey.compareTo(nextKey) < 0);
      prevKey = nextKey;
      results.clear();
    }
    // Assert that we listed all the keys that we had put into
    // container.
    Assert.assertTrue(testMap.isEmpty());
  }

  private ChunkInfo writeChunkHelper(String containerName, String keyName,
      Pipeline pipeline) throws IOException,
      NoSuchAlgorithmException {
    final int datalen = 1024;
    pipeline.setContainerName(containerName);
    ContainerData cData = new ContainerData(containerName);
    cData.addMetadata("VOLUME", "shire");
    cData.addMetadata("owner)", "bilbo");
    if(!containerManager.getContainerMap()
        .containsKey(containerName)) {
      containerManager.createContainer(pipeline, cData);
    }
    ChunkInfo info = getChunk(keyName, 0, 0, datalen);
    byte[] data = getData(datalen);
    setDataChecksum(info, data);
    chunkManager.writeChunk(pipeline, keyName, info, data);
    return info;

  }

  /**
   * Writes a single chunk.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testWriteChunk() throws IOException,
      NoSuchAlgorithmException {
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    writeChunkHelper(containerName, keyName, pipeline);
  }

  /**
   * Writes many chunks of the same key into different chunk files and verifies
   * that we have that data in many files.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testWritReadManyChunks() throws IOException,
      NoSuchAlgorithmException {
    final int datalen = 1024;
    final int chunkCount = 1024;

    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    Map<String, ChunkInfo> fileHashMap = new HashMap<>();

    pipeline.setContainerName(containerName);
    ContainerData cData = new ContainerData(containerName);
    cData.addMetadata("VOLUME", "shire");
    cData.addMetadata("owner)", "bilbo");
    containerManager.createContainer(pipeline, cData);
    for (int x = 0; x < chunkCount; x++) {
      ChunkInfo info = getChunk(keyName, x, 0, datalen);
      byte[] data = getData(datalen);
      setDataChecksum(info, data);
      chunkManager.writeChunk(pipeline, keyName, info, data);
      String fileName = String.format("%s.data.%d", keyName, x);
      fileHashMap.put(fileName, info);
    }

    ContainerData cNewData = containerManager.readContainer(containerName);
    Assert.assertNotNull(cNewData);
    Path dataDir = ContainerUtils.getDataDirectory(cNewData);

    String globFormat = String.format("%s.data.*", keyName);
    MessageDigest sha = MessageDigest.getInstance(OzoneConsts.FILE_HASH);

    // Read chunk via file system and verify.
    int count = 0;
    try (DirectoryStream<Path> stream =
             Files.newDirectoryStream(dataDir, globFormat)) {
      for (Path fname : stream) {
        sha.update(FileUtils.readFileToByteArray(fname.toFile()));
        String val = Hex.encodeHexString(sha.digest());
        Assert.assertEquals(fileHashMap.get(fname.getFileName().toString())
                .getChecksum(), val);
        count++;
        sha.reset();
      }
      Assert.assertEquals(chunkCount, count);

      // Read chunk via ReadChunk call.
      sha.reset();
      for (int x = 0; x < chunkCount; x++) {
        String fileName = String.format("%s.data.%d", keyName, x);
        ChunkInfo info = fileHashMap.get(fileName);
        byte[] data = chunkManager.readChunk(pipeline, keyName, info);
        sha.update(data);
        Assert.assertEquals(Hex.encodeHexString(sha.digest()),
            info.getChecksum());
        sha.reset();
      }
    }
  }

  /**
   * Writes a single chunk and tries to overwrite that chunk without over write
   * flag then re-tries with overwrite flag.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testOverWrite() throws IOException,
      NoSuchAlgorithmException {
    final int datalen = 1024;
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);

    pipeline.setContainerName(containerName);
    ContainerData cData = new ContainerData(containerName);
    cData.addMetadata("VOLUME", "shire");
    cData.addMetadata("owner)", "bilbo");
    containerManager.createContainer(pipeline, cData);
    ChunkInfo info = getChunk(keyName, 0, 0, datalen);
    byte[] data = getData(datalen);
    setDataChecksum(info, data);
    chunkManager.writeChunk(pipeline, keyName, info, data);
    try {
      chunkManager.writeChunk(pipeline, keyName, info, data);
    } catch (IOException ex) {
      Assert.assertTrue(ex.getMessage().contains(
          "Rejecting write chunk request. OverWrite flag required."));
    }

    // With the overwrite flag it should work now.
    info.addMetadata(OzoneConsts.CHUNK_OVERWRITE, "true");
    chunkManager.writeChunk(pipeline, keyName, info, data);
  }

  /**
   * This test writes data as many small writes and tries to read back the data
   * in a single large read.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testMultipleWriteSingleRead() throws IOException,
      NoSuchAlgorithmException {
    final int datalen = 1024;
    final int chunkCount = 1024;

    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);

    pipeline.setContainerName(containerName);
    ContainerData cData = new ContainerData(containerName);
    cData.addMetadata("VOLUME", "shire");
    cData.addMetadata("owner)", "bilbo");
    containerManager.createContainer(pipeline, cData);
    MessageDigest oldSha = MessageDigest.getInstance(OzoneConsts.FILE_HASH);
    for (int x = 0; x < chunkCount; x++) {
      // we are writing to the same chunk file but at different offsets.
      long offset = x * datalen;
      ChunkInfo info = getChunk(keyName, 0, offset, datalen);
      byte[] data = getData(datalen);
      oldSha.update(data);
      setDataChecksum(info, data);
      chunkManager.writeChunk(pipeline, keyName, info, data);
    }

    // Request to read the whole data in a single go.
    ChunkInfo largeChunk = getChunk(keyName, 0, 0, datalen * chunkCount);
    byte[] newdata = chunkManager.readChunk(pipeline, keyName, largeChunk);
    MessageDigest newSha = MessageDigest.getInstance(OzoneConsts.FILE_HASH);
    newSha.update(newdata);
    Assert.assertEquals(Hex.encodeHexString(oldSha.digest()),
        Hex.encodeHexString(newSha.digest()));
  }

  /**
   * Writes a chunk and deletes it, re-reads to make sure it is gone.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testDeleteChunk() throws IOException,
      NoSuchAlgorithmException {
    final int datalen = 1024;
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);

    pipeline.setContainerName(containerName);
    ContainerData cData = new ContainerData(containerName);
    cData.addMetadata("VOLUME", "shire");
    cData.addMetadata("owner)", "bilbo");
    containerManager.createContainer(pipeline, cData);
    ChunkInfo info = getChunk(keyName, 0, 0, datalen);
    byte[] data = getData(datalen);
    setDataChecksum(info, data);
    chunkManager.writeChunk(pipeline, keyName, info, data);
    chunkManager.deleteChunk(pipeline, keyName, info);
    exception.expect(StorageContainerException.class);
    exception.expectMessage("Unable to find the chunk file.");
    chunkManager.readChunk(pipeline, keyName, info);
  }

  /**
   * Tests a put key and read key.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testPutKey() throws IOException, NoSuchAlgorithmException {
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    ChunkInfo info = writeChunkHelper(containerName, keyName, pipeline);
    KeyData keyData = new KeyData(containerName, keyName);
    List<ContainerProtos.ChunkInfo> chunkList = new LinkedList<>();
    chunkList.add(info.getProtoBufMessage());
    keyData.setChunks(chunkList);
    keyManager.putKey(pipeline, keyData);
    KeyData readKeyData = keyManager.getKey(keyData);
    ChunkInfo readChunk =
        ChunkInfo.getFromProtoBuf(readKeyData.getChunks().get(0));
    Assert.assertEquals(info.getChecksum(), readChunk.getChecksum());
  }

  /**
   * Tests a put key and read key.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testPutKeyWithLotsOfChunks() throws IOException,
      NoSuchAlgorithmException {
    final int chunkCount = 1024;
    final int datalen = 1024;
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    List<ChunkInfo> chunkList = new LinkedList<>();
    ChunkInfo info = writeChunkHelper(containerName, keyName, pipeline);
    chunkList.add(info);
    for (int x = 1; x < chunkCount; x++) {
      info = getChunk(keyName, x, x * datalen, datalen);
      byte[] data = getData(datalen);
      setDataChecksum(info, data);
      chunkManager.writeChunk(pipeline, keyName, info, data);
      chunkList.add(info);
    }

    KeyData keyData = new KeyData(containerName, keyName);
    List<ContainerProtos.ChunkInfo> chunkProtoList = new LinkedList<>();
    for (ChunkInfo i : chunkList) {
      chunkProtoList.add(i.getProtoBufMessage());
    }
    keyData.setChunks(chunkProtoList);
    keyManager.putKey(pipeline, keyData);
    KeyData readKeyData = keyManager.getKey(keyData);
    ChunkInfo lastChunk = chunkList.get(chunkList.size() - 1);
    ChunkInfo readChunk =
        ChunkInfo.getFromProtoBuf(readKeyData.getChunks().get(readKeyData
            .getChunks().size() - 1));
    Assert.assertEquals(lastChunk.getChecksum(), readChunk.getChecksum());
  }

  /**
   * Deletes a key and tries to read it back.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testDeleteKey() throws IOException, NoSuchAlgorithmException {
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    ChunkInfo info = writeChunkHelper(containerName, keyName, pipeline);
    KeyData keyData = new KeyData(containerName, keyName);
    List<ContainerProtos.ChunkInfo> chunkList = new LinkedList<>();
    chunkList.add(info.getProtoBufMessage());
    keyData.setChunks(chunkList);
    keyManager.putKey(pipeline, keyData);
    keyManager.deleteKey(pipeline, keyName);
    exception.expect(StorageContainerException.class);
    exception.expectMessage("Unable to find the key.");
    keyManager.getKey(keyData);
  }

  /**
   * Tries to Deletes a key twice.
   *
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  @Test
  public void testDeleteKeyTwice() throws IOException,
      NoSuchAlgorithmException {
    String containerName = OzoneUtils.getRequestID();
    String keyName = OzoneUtils.getRequestID();
    Pipeline pipeline = createSingleNodePipeline(containerName);
    ChunkInfo info = writeChunkHelper(containerName, keyName, pipeline);
    KeyData keyData = new KeyData(containerName, keyName);
    List<ContainerProtos.ChunkInfo> chunkList = new LinkedList<>();
    chunkList.add(info.getProtoBufMessage());
    keyData.setChunks(chunkList);
    keyManager.putKey(pipeline, keyData);
    keyManager.deleteKey(pipeline, keyName);
    exception.expect(StorageContainerException.class);
    exception.expectMessage("Unable to find the key.");
    keyManager.deleteKey(pipeline, keyName);
  }

  /**
   * Tries to update an existing and non-existing container.
   * Verifies container map and persistent data both updated.
   *
   * @throws IOException
   */
  @Test
  public void testUpdateContainer() throws IOException {
    String containerName = OzoneUtils.getRequestID();
    ContainerData data = new ContainerData(containerName);
    data.addMetadata("VOLUME", "shire");
    data.addMetadata("owner)", "bilbo");

    containerManager.createContainer(
        createSingleNodePipeline(containerName),
        data);

    File orgContainerFile = containerManager.getContainerFile(data);
    Assert.assertTrue(orgContainerFile.exists());

    ContainerData newData = new ContainerData(containerName);
    newData.addMetadata("VOLUME", "shire_new");
    newData.addMetadata("owner)", "bilbo_new");

    containerManager.updateContainer(
        createSingleNodePipeline(containerName),
        containerName,
        newData);

    Assert.assertEquals(1, containerManager.getContainerMap().size());
    Assert.assertTrue(containerManager.getContainerMap()
        .containsKey(containerName));

    // Verify in-memory map
    ContainerData actualNewData = containerManager.getContainerMap()
        .get(containerName).getContainer();
    Assert.assertEquals(actualNewData.getAllMetadata().get("VOLUME"),
        "shire_new");
    Assert.assertEquals(actualNewData.getAllMetadata().get("owner)"),
        "bilbo_new");

    // Verify container data on disk
    File newContainerFile = containerManager.getContainerFile(actualNewData);
    Assert.assertTrue("Container file should exist.",
        newContainerFile.exists());
    Assert.assertEquals("Container file should be in same location.",
        orgContainerFile.getAbsolutePath(),
        newContainerFile.getAbsolutePath());

    try (FileInputStream newIn = new FileInputStream(newContainerFile)) {
      ContainerProtos.ContainerData actualContainerDataProto =
          ContainerProtos.ContainerData.parseDelimitedFrom(newIn);
      ContainerData actualContainerData = ContainerData
          .getFromProtBuf(actualContainerDataProto);
      Assert.assertEquals(actualContainerData.getAllMetadata().get("VOLUME"),
          "shire_new");
      Assert.assertEquals(actualContainerData.getAllMetadata().get("owner)"),
          "bilbo_new");
    }

    // Update a non-existing container
    exception.expect(StorageContainerException.class);
    exception.expectMessage("Container doesn't exist.");
    containerManager.updateContainer(
        createSingleNodePipeline("non_exist_container"),
        "non_exist_container", newData);
  }

  private KeyData writeKeyHelper(Pipeline pipeline,
      String containerName, String keyName)
      throws IOException, NoSuchAlgorithmException {
    ChunkInfo info = writeChunkHelper(containerName, keyName, pipeline);
    KeyData keyData = new KeyData(containerName, keyName);
    List<ContainerProtos.ChunkInfo> chunkList = new LinkedList<>();
    chunkList.add(info.getProtoBufMessage());
    keyData.setChunks(chunkList);
    return keyData;
  }

  @Test
  public void testListKey() throws Exception {
    String containerName = "c-0";
    Pipeline pipeline = createSingleNodePipeline(containerName);
    List<String> expectedKeys = new ArrayList<String>();
    for (int i = 0; i < 10; i++) {
      String keyName = "k" + i + "-" + UUID.randomUUID();
      expectedKeys.add(keyName);
      KeyData kd = writeKeyHelper(pipeline, containerName, keyName);
      keyManager.putKey(pipeline, kd);
    }

    // List all keys
    List<KeyData> result = keyManager.listKey(pipeline, null, null, 100);
    Assert.assertEquals(10, result.size());

    int index = 0;
    for (int i = index; i < result.size(); i++) {
      KeyData data = result.get(i);
      Assert.assertEquals(containerName, data.getContainerName());
      Assert.assertEquals(expectedKeys.get(i), data.getKeyName());
      index++;
    }

    // List key with prefix
    result = keyManager.listKey(pipeline, "k1", null, 100);
    // There is only one key with prefix k1
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(expectedKeys.get(1), result.get(0).getKeyName());


    // List key with preKev filter
    String k6 = expectedKeys.get(6);
    result = keyManager.listKey(pipeline, null, k6, 100);

    Assert.assertEquals(3, result.size());
    for (int i = 7; i < 10; i++) {
      Assert.assertEquals(expectedKeys.get(i),
          result.get(i - 7).getKeyName());
    }

    // List key with both prefix and preKey filter
    String k7 = expectedKeys.get(7);
    result = keyManager.listKey(pipeline, "k3", k7, 100);
    // k3 is after k7, enhance we get an empty result
    Assert.assertTrue(result.isEmpty());

    // Set a pretty small cap for the key count
    result = keyManager.listKey(pipeline, null, null, 3);
    Assert.assertEquals(3, result.size());
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(expectedKeys.get(i), result.get(i).getKeyName());
    }

    // Count must be >0
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Count must be a positive number.");
    keyManager.listKey(pipeline, null, null, -1);
  }
}
