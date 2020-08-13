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

package org.apache.hadoop.hdfs.security.token.block;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.ClientDatanodeProtocolService;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReplicaVisibleLengthRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.ClientDatanodeProtocolProtos.GetReplicaVisibleLengthResponseProto;
import org.apache.hadoop.hdfs.protocolPB.ClientDatanodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.TestWritable;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SaslInputStream;
import org.apache.hadoop.security.SaslRpcClient;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.protobuf.BlockingService;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.apache.hadoop.fs.StorageType;

/** Unit tests for block tokens */
public class TestBlockToken {
  public static final Log LOG = LogFactory.getLog(TestBlockToken.class);
  private static final String ADDRESS = "0.0.0.0";

  static {
    GenericTestUtils.setLogLevel(Client.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(Server.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(SaslRpcClient.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(SaslRpcServer.LOG, Level.ALL);
    GenericTestUtils.setLogLevel(SaslInputStream.LOG, Level.ALL);
  }

  /**
   * Directory where we can count our open file descriptors under Linux
   */
  static final File FD_DIR = new File("/proc/self/fd/");

  final long blockKeyUpdateInterval = 10 * 60 * 1000; // 10 mins
  final long blockTokenLifetime = 2 * 60 * 1000; // 2 mins
  final ExtendedBlock block1 = new ExtendedBlock("0", 0L);
  final ExtendedBlock block2 = new ExtendedBlock("10", 10L);
  final ExtendedBlock block3 = new ExtendedBlock("-10", -108L);

  @Before
  public void disableKerberos() {
    Configuration conf = new Configuration();
    conf.set(HADOOP_SECURITY_AUTHENTICATION, "simple");
    UserGroupInformation.setConfiguration(conf);
  }

  private static class GetLengthAnswer implements
      Answer<GetReplicaVisibleLengthResponseProto> {
    final BlockTokenSecretManager sm;
    final BlockTokenIdentifier ident;

    public GetLengthAnswer(BlockTokenSecretManager sm,
                           BlockTokenIdentifier ident) {
      this.sm = sm;
      this.ident = ident;
    }

    @Override
    public GetReplicaVisibleLengthResponseProto answer(
        InvocationOnMock invocation) throws IOException {
      Object args[] = invocation.getArguments();
      assertEquals(2, args.length);
      GetReplicaVisibleLengthRequestProto req =
          (GetReplicaVisibleLengthRequestProto) args[1];
      Set<TokenIdentifier> tokenIds = UserGroupInformation.getCurrentUser()
          .getTokenIdentifiers();
      assertEquals("Only one BlockTokenIdentifier expected", 1, tokenIds.size());
      long result = 0;
      for (TokenIdentifier tokenId : tokenIds) {
        BlockTokenIdentifier id = (BlockTokenIdentifier) tokenId;
        LOG.info("Got: " + id.toString());
        assertTrue("Received BlockTokenIdentifier is wrong", ident.equals(id));
        sm.checkAccess(id, null, PBHelperClient.convert(req.getBlock()),
            BlockTokenIdentifier.AccessMode.WRITE,
            new StorageType[]{StorageType.DEFAULT}, null);
        result = id.getBlockId();
      }
      return GetReplicaVisibleLengthResponseProto.newBuilder()
          .setLength(result).build();
    }
  }

  private BlockTokenIdentifier generateTokenId(BlockTokenSecretManager sm,
      ExtendedBlock block, EnumSet<BlockTokenIdentifier.AccessMode> accessModes,
      StorageType[] storageTypes, String[] storageIds)
      throws IOException {
    Token<BlockTokenIdentifier> token = sm.generateToken(block, accessModes,
        storageTypes, storageIds);
    BlockTokenIdentifier id = sm.createIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token
        .getIdentifier())));
    return id;
  }

  private void testWritable(boolean enableProtobuf) throws Exception {
    TestWritable.testWritable(new BlockTokenIdentifier());
    BlockTokenSecretManager sm = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        enableProtobuf);
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, null));
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.of(BlockTokenIdentifier.AccessMode.WRITE),
        new StorageType[]{StorageType.DEFAULT}, null));
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, null));
    TestWritable.testWritable(generateTokenId(sm, block1,
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, null));
    TestWritable.testWritable(generateTokenId(sm, block2,
        EnumSet.of(BlockTokenIdentifier.AccessMode.WRITE),
        new StorageType[]{StorageType.DEFAULT}, null));
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, null));
    // We must be backwards compatible when adding storageType
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class), null, null));
    TestWritable.testWritable(generateTokenId(sm, block3,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class),
        StorageType.EMPTY_ARRAY, null));
  }

  @Test
  public void testWritableLegacy() throws Exception {
    testWritable(false);
  }

  @Test
  public void testWritableProtobuf() throws Exception {
    testWritable(true);
  }

  private static void checkAccess(BlockTokenSecretManager m,
      Token<BlockTokenIdentifier> t, ExtendedBlock blk,
      BlockTokenIdentifier.AccessMode mode, StorageType[] storageTypes,
      String[] storageIds) throws SecretManager.InvalidToken {
    m.checkAccess(t, null, blk, mode, storageTypes, storageIds);
  }

  private void tokenGenerationAndVerification(BlockTokenSecretManager master,
      BlockTokenSecretManager slave, StorageType[] storageTypes,
      String[] storageIds) throws Exception {
    // single-mode tokens
    for (BlockTokenIdentifier.AccessMode mode : BlockTokenIdentifier.AccessMode
        .values()) {
      // generated by master
      Token<BlockTokenIdentifier> token1 = master.generateToken(block1,
          EnumSet.of(mode), storageTypes, storageIds);
      checkAccess(master, token1, block1, mode, storageTypes, storageIds);
      checkAccess(slave, token1, block1, mode, storageTypes, storageIds);
      // generated by slave
      Token<BlockTokenIdentifier> token2 = slave.generateToken(block2,
          EnumSet.of(mode), storageTypes, storageIds);
      checkAccess(master, token2, block2, mode, storageTypes, storageIds);
      checkAccess(slave, token2, block2, mode, storageTypes, storageIds);
    }
    // multi-mode tokens
    Token<BlockTokenIdentifier> mtoken = master.generateToken(block3,
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        storageTypes, storageIds);
    for (BlockTokenIdentifier.AccessMode mode : BlockTokenIdentifier.AccessMode
        .values()) {
      checkAccess(master, mtoken, block3, mode, storageTypes, storageIds);
      checkAccess(slave, mtoken, block3, mode, storageTypes, storageIds);
    }
  }

  /** test block key and token handling */
  private void testBlockTokenSecretManager(boolean enableProtobuf)
      throws Exception {
    BlockTokenSecretManager masterHandler = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        enableProtobuf);
    BlockTokenSecretManager slaveHandler = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, "fake-pool", null,
        enableProtobuf);
    ExportedBlockKeys keys = masterHandler.exportKeys();
    slaveHandler.addKeys(keys);
    tokenGenerationAndVerification(masterHandler, slaveHandler,
        new StorageType[]{StorageType.DEFAULT}, null);
    tokenGenerationAndVerification(masterHandler, slaveHandler, null, null);
    // key updating
    masterHandler.updateKeys();
    tokenGenerationAndVerification(masterHandler, slaveHandler,
        new StorageType[]{StorageType.DEFAULT}, null);
    tokenGenerationAndVerification(masterHandler, slaveHandler, null, null);
    keys = masterHandler.exportKeys();
    slaveHandler.addKeys(keys);
    tokenGenerationAndVerification(masterHandler, slaveHandler,
        new StorageType[]{StorageType.DEFAULT}, null);
    tokenGenerationAndVerification(masterHandler, slaveHandler, null, null);
  }

  @Test
  public void testBlockTokenSecretManagerLegacy() throws Exception {
    testBlockTokenSecretManager(false);
  }

  @Test
  public void testBlockTokenSecretManagerProtobuf() throws Exception {
    testBlockTokenSecretManager(true);
  }

  private static Server createMockDatanode(BlockTokenSecretManager sm,
      Token<BlockTokenIdentifier> token, Configuration conf)
      throws IOException, ServiceException {
    ClientDatanodeProtocolPB mockDN = mock(ClientDatanodeProtocolPB.class);

    BlockTokenIdentifier id = sm.createIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token
        .getIdentifier())));

    doAnswer(new GetLengthAnswer(sm, id)).when(mockDN)
        .getReplicaVisibleLength(any(RpcController.class),
            any(GetReplicaVisibleLengthRequestProto.class));

    RPC.setProtocolEngine(conf, ClientDatanodeProtocolPB.class,
        ProtobufRpcEngine.class);
    BlockingService service = ClientDatanodeProtocolService
        .newReflectiveBlockingService(mockDN);
    return new RPC.Builder(conf).setProtocol(ClientDatanodeProtocolPB.class)
        .setInstance(service).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(5).setVerbose(true).setSecretManager(sm).build();
  }

  private void testBlockTokenRpc(boolean enableProtobuf) throws Exception {
    Configuration conf = new Configuration();
    conf.set(HADOOP_SECURITY_AUTHENTICATION, "kerberos");
    UserGroupInformation.setConfiguration(conf);

    BlockTokenSecretManager sm = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        enableProtobuf);
    Token<BlockTokenIdentifier> token = sm.generateToken(block3,
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, new String[0]);

    final Server server = createMockDatanode(sm, token, conf);

    server.start();

    final InetSocketAddress addr = NetUtils.getConnectAddress(server);
    final UserGroupInformation ticket = UserGroupInformation
        .createRemoteUser(block3.toString());
    ticket.addToken(token);

    ClientDatanodeProtocol proxy = null;
    try {
      proxy = DFSUtilClient.createClientDatanodeProtocolProxy(addr, ticket, conf,
          NetUtils.getDefaultSocketFactory(conf));
      assertEquals(block3.getBlockId(), proxy.getReplicaVisibleLength(block3));
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
  }

  @Test
  public void testBlockTokenRpcLegacy() throws Exception {
    testBlockTokenRpc(false);
  }

  @Test
  public void testBlockTokenRpcProtobuf() throws Exception {
    testBlockTokenRpc(true);
  }

  /**
   * Test that fast repeated invocations of createClientDatanodeProtocolProxy
   * will not end up using up thousands of sockets. This is a regression test
   * for HDFS-1965.
   */
  private void testBlockTokenRpcLeak(boolean enableProtobuf) throws Exception {
    Configuration conf = new Configuration();
    conf.set(HADOOP_SECURITY_AUTHENTICATION, "kerberos");
    UserGroupInformation.setConfiguration(conf);

    Assume.assumeTrue(FD_DIR.exists());
    BlockTokenSecretManager sm = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        enableProtobuf);
    Token<BlockTokenIdentifier> token = sm.generateToken(block3,
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, new String[0]);

    final Server server = createMockDatanode(sm, token, conf);
    server.start();

    final InetSocketAddress addr = NetUtils.getConnectAddress(server);
    DatanodeID fakeDnId = DFSTestUtil.getLocalDatanodeID(addr.getPort());

    ExtendedBlock b = new ExtendedBlock("fake-pool", new Block(12345L));
    LocatedBlock fakeBlock = new LocatedBlock(b, new DatanodeInfo[0]);
    fakeBlock.setBlockToken(token);

    // Create another RPC proxy with the same configuration - this will never
    // attempt to connect anywhere -- but it causes the refcount on the
    // RPC "Client" object to stay above 0 such that RPC.stopProxy doesn't
    // actually close the TCP connections to the real target DN.
    ClientDatanodeProtocol proxyToNoWhere = RPC.getProxy(
        ClientDatanodeProtocol.class, ClientDatanodeProtocol.versionID,
        new InetSocketAddress("1.1.1.1", 1),
        UserGroupInformation.createRemoteUser("junk"), conf,
        NetUtils.getDefaultSocketFactory(conf));

    ClientDatanodeProtocol proxy = null;

    int fdsAtStart = countOpenFileDescriptors();
    try {
      long endTime = Time.now() + 3000;
      while (Time.now() < endTime) {
        proxy = DFSUtilClient.createClientDatanodeProtocolProxy(fakeDnId, conf, 1000,
            false, fakeBlock);
        assertEquals(block3.getBlockId(), proxy.getReplicaVisibleLength(block3));
        if (proxy != null) {
          RPC.stopProxy(proxy);
        }
        LOG.info("Num open fds:" + countOpenFileDescriptors());
      }

      int fdsAtEnd = countOpenFileDescriptors();

      if (fdsAtEnd - fdsAtStart > 50) {
        fail("Leaked " + (fdsAtEnd - fdsAtStart) + " fds!");
      }
    } finally {
      server.stop();
    }

    RPC.stopProxy(proxyToNoWhere);
  }

  @Test
  public void testBlockTokenRpcLeakLegacy() throws Exception {
    testBlockTokenRpcLeak(false);
  }

  @Test
  public void testBlockTokenRpcLeakProtobuf() throws Exception {
    testBlockTokenRpcLeak(true);
  }

  /**
   * @return the current number of file descriptors open by this process.
   */
  private static int countOpenFileDescriptors() {
    return FD_DIR.list().length;
  }

  /**
   * Test {@link BlockPoolTokenSecretManager}
   */
  private void testBlockPoolTokenSecretManager(boolean enableProtobuf)
      throws Exception {
    BlockPoolTokenSecretManager bpMgr = new BlockPoolTokenSecretManager();

    // Test BlockPoolSecretManager with upto 10 block pools
    for (int i = 0; i < 10; i++) {
      String bpid = Integer.toString(i);
      BlockTokenSecretManager masterHandler = new BlockTokenSecretManager(
          blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
          enableProtobuf);
      BlockTokenSecretManager slaveHandler = new BlockTokenSecretManager(
          blockKeyUpdateInterval, blockTokenLifetime, "fake-pool", null,
          enableProtobuf);
      bpMgr.addBlockPool(bpid, slaveHandler);

      ExportedBlockKeys keys = masterHandler.exportKeys();
      bpMgr.addKeys(bpid, keys);
      String[] storageIds = new String[] {"DS-9001"};
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid),
          new StorageType[]{StorageType.DEFAULT}, storageIds);
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid), null,
          null);
      // Test key updating
      masterHandler.updateKeys();
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid),
          new StorageType[]{StorageType.DEFAULT}, storageIds);
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid), null,
          null);
      keys = masterHandler.exportKeys();
      bpMgr.addKeys(bpid, keys);
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid),
          new StorageType[]{StorageType.DEFAULT}, new String[]{"DS-9001"});
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid), null,
          null);
    }
  }

  @Test
  public void testBlockPoolTokenSecretManagerLegacy() throws Exception {
    testBlockPoolTokenSecretManager(false);
  }

  @Test
  public void testBlockPoolTokenSecretManagerProtobuf() throws Exception {
    testBlockPoolTokenSecretManager(true);
  }

  /**
   * This test writes a file and gets the block locations without closing the
   * file, and tests the block token in the last block. Block token is verified
   * by ensuring it is of correct kind.
   *
   * @throws IOException
   * @throws InterruptedException
   */
  private void testBlockTokenInLastLocatedBlock(boolean enableProtobuf)
      throws IOException, InterruptedException {
    Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, true);
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, 512);
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_PROTOBUF_ENABLE,
        enableProtobuf);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(1).build();
    cluster.waitActive();

    try {
      FileSystem fs = cluster.getFileSystem();
      String fileName = "/testBlockTokenInLastLocatedBlock";
      Path filePath = new Path(fileName);
      FSDataOutputStream out = fs.create(filePath, (short) 1);
      out.write(new byte[1000]);
      // ensure that the first block is written out (see FSOutputSummer#flush)
      out.flush();
      LocatedBlocks locatedBlocks = cluster.getNameNodeRpc().getBlockLocations(
          fileName, 0, 1000);
      while (locatedBlocks.getLastLocatedBlock() == null) {
        Thread.sleep(100);
        locatedBlocks = cluster.getNameNodeRpc().getBlockLocations(fileName, 0,
            1000);
      }
      Token<BlockTokenIdentifier> token = locatedBlocks.getLastLocatedBlock()
          .getBlockToken();
      Assert.assertEquals(BlockTokenIdentifier.KIND_NAME, token.getKind());
      out.close();
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testBlockTokenInLastLocatedBlockLegacy() throws IOException,
      InterruptedException {
    testBlockTokenInLastLocatedBlock(false);
  }

  @Test
  public void testBlockTokenInLastLocatedBlockProtobuf() throws IOException,
      InterruptedException {
    testBlockTokenInLastLocatedBlock(true);
  }

  @Test
  public void testLegacyBlockTokenBytesIsLegacy() throws IOException {
    final boolean useProto = false;
    BlockTokenSecretManager sm = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        useProto);
    Token<BlockTokenIdentifier> token = sm.generateToken(block1,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DEFAULT}, new String[0]);
    final byte[] tokenBytes = token.getIdentifier();
    BlockTokenIdentifier legacyToken = new BlockTokenIdentifier();
    BlockTokenIdentifier protobufToken = new BlockTokenIdentifier();
    BlockTokenIdentifier readToken = new BlockTokenIdentifier();

    DataInputBuffer dib = new DataInputBuffer();

    dib.reset(tokenBytes, tokenBytes.length);
    legacyToken.readFieldsLegacy(dib);

    boolean invalidProtobufMessage = false;
    try {
      dib.reset(tokenBytes, tokenBytes.length);
      protobufToken.readFieldsProtobuf(dib);
    } catch (IOException e) {
      invalidProtobufMessage = true;
    }
    assertTrue(invalidProtobufMessage);

    dib.reset(tokenBytes, tokenBytes.length);
    readToken.readFields(dib);

    // Using legacy, the token parses as a legacy block token and not a protobuf
    assertEquals(legacyToken, readToken);
    assertNotEquals(protobufToken, readToken);
  }

  @Test
  public void testEmptyLegacyBlockTokenBytesIsLegacy() throws IOException {
    BlockTokenIdentifier emptyIdent = new BlockTokenIdentifier();
    DataOutputBuffer dob = new DataOutputBuffer(4096);
    DataInputBuffer dib = new DataInputBuffer();

    emptyIdent.writeLegacy(dob);
    byte[] emptyIdentBytes = Arrays.copyOf(dob.getData(), dob.getLength());

    BlockTokenIdentifier legacyToken = new BlockTokenIdentifier();
    BlockTokenIdentifier protobufToken = new BlockTokenIdentifier();
    BlockTokenIdentifier readToken = new BlockTokenIdentifier();

    dib.reset(emptyIdentBytes, emptyIdentBytes.length);
    legacyToken.readFieldsLegacy(dib);

    boolean invalidProtobufMessage = false;
    try {
      dib.reset(emptyIdentBytes, emptyIdentBytes.length);
      protobufToken.readFieldsProtobuf(dib);
    } catch (IOException e) {
      invalidProtobufMessage = true;
    }
    assertTrue(invalidProtobufMessage);

    dib.reset(emptyIdentBytes, emptyIdentBytes.length);
    readToken.readFields(dib);
  }

  @Test
  public void testProtobufBlockTokenBytesIsProtobuf() throws IOException {
    final boolean useProto = true;
    BlockTokenSecretManager sm = new BlockTokenSecretManager(
        blockKeyUpdateInterval, blockTokenLifetime, 0, 1, "fake-pool", null,
        useProto);
    Token<BlockTokenIdentifier> token = sm.generateToken(block1,
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class),
        StorageType.EMPTY_ARRAY, new String[0]);
    final byte[] tokenBytes = token.getIdentifier();
    BlockTokenIdentifier legacyToken = new BlockTokenIdentifier();
    BlockTokenIdentifier protobufToken = new BlockTokenIdentifier();
    BlockTokenIdentifier readToken = new BlockTokenIdentifier();

    DataInputBuffer dib = new DataInputBuffer();

    /* We receive NegativeArraySizeException because we didn't call
     * readFields and instead try to parse this directly as a legacy
     * BlockTokenIdentifier.
     *
     * Note: because the parsing depends on the expiryDate which is based on
     * `Time.now()` it can sometimes fail with IOException and sometimes with
     * NegativeArraySizeException.
     */
    boolean invalidLegacyMessage = false;
    try {
      dib.reset(tokenBytes, tokenBytes.length);
      legacyToken.readFieldsLegacy(dib);
    } catch (IOException | NegativeArraySizeException e) {
      invalidLegacyMessage = true;
    }
    assertTrue(invalidLegacyMessage);

    dib.reset(tokenBytes, tokenBytes.length);
    protobufToken.readFieldsProtobuf(dib);

    dib.reset(tokenBytes, tokenBytes.length);
    readToken.readFields(dib);

    // Using protobuf, the token parses as a protobuf and not a legacy block
    // token
    assertNotEquals(legacyToken, readToken);
    assertEquals(protobufToken, readToken);
  }

  private void testCraftedProtobufBlockTokenIdentifier(
      BlockTokenIdentifier identifier, boolean expectIOE,
      boolean expectRTE) throws IOException {
    DataOutputBuffer dob = new DataOutputBuffer(4096);
    DataInputBuffer dib = new DataInputBuffer();

    identifier.writeProtobuf(dob);
    byte[] identBytes = Arrays.copyOf(dob.getData(), dob.getLength());

    BlockTokenIdentifier legacyToken = new BlockTokenIdentifier();
    BlockTokenIdentifier protobufToken = new BlockTokenIdentifier();
    BlockTokenIdentifier readToken = new BlockTokenIdentifier();

    boolean invalidLegacyMessage = false;
    try {
      dib.reset(identBytes, identBytes.length);
      legacyToken.readFieldsLegacy(dib);
    } catch (IOException e) {
      if (!expectIOE) {
        fail("Received IOException but it was not expected.");
      }
      invalidLegacyMessage = true;
    } catch (RuntimeException e) {
      if (!expectRTE) {
        fail("Received RuntimeException but it was not expected.");
      }
      invalidLegacyMessage = true;
    }

    assertTrue(invalidLegacyMessage);

    dib.reset(identBytes, identBytes.length);
    protobufToken.readFieldsProtobuf(dib);

    dib.reset(identBytes, identBytes.length);
    readToken.readFieldsProtobuf(dib);
    assertEquals(protobufToken, readToken);
    assertEquals(identifier, readToken);
  }

  @Test
  public void testEmptyProtobufBlockTokenBytesIsProtobuf() throws IOException {
    // Empty BlockTokenIdentifiers throw IOException
    BlockTokenIdentifier identifier = new BlockTokenIdentifier();
    testCraftedProtobufBlockTokenIdentifier(identifier, true, false);
  }

  @Test
  public void testCraftedProtobufBlockTokenBytesIsProtobuf() throws
      IOException {
    /* Parsing BlockTokenIdentifier with expiryDate
     * 2017-02-09 00:12:35,072+0100 will throw IOException.
     * However, expiryDate of
     * 2017-02-09 00:12:35,071+0100 will throw NegativeArraySizeException.
     */
    BlockTokenIdentifier identifier = new BlockTokenIdentifier("user",
        "blockpool", 123, EnumSet.allOf(BlockTokenIdentifier.AccessMode.class),
        new StorageType[]{StorageType.DISK, StorageType.ARCHIVE},
        new String[] {"fake-storage-id"}, true);
    Calendar cal = new GregorianCalendar();
    cal.set(2017, 1, 9, 0, 12, 35);
    long datetime = cal.getTimeInMillis();
    datetime = ((datetime / 1000) * 1000); // strip milliseconds.
    datetime = datetime + 71; // 2017-02-09 00:12:35,071+0100
    identifier.setExpiryDate(datetime);
    testCraftedProtobufBlockTokenIdentifier(identifier, false, true);
    datetime += 1; // 2017-02-09 00:12:35,072+0100
    identifier.setExpiryDate(datetime);
    testCraftedProtobufBlockTokenIdentifier(identifier, true, false);
  }

  private BlockTokenIdentifier writeAndReadBlockToken(
      BlockTokenIdentifier identifier) throws IOException {
    DataOutputBuffer dob = new DataOutputBuffer(4096);
    DataInputBuffer dib = new DataInputBuffer();
    identifier.write(dob);
    byte[] identBytes = Arrays.copyOf(dob.getData(), dob.getLength());

    BlockTokenIdentifier readToken = new BlockTokenIdentifier();

    dib.reset(identBytes, identBytes.length);
    readToken.readFields(dib);
    assertEquals(identifier, readToken);
    return readToken;
  }

  @Test
  public void testEmptyBlockTokenSerialization() throws IOException {
    BlockTokenIdentifier ident = new BlockTokenIdentifier();
    BlockTokenIdentifier ret = writeAndReadBlockToken(ident);
    assertEquals(ret.getExpiryDate(), 0);
    assertEquals(ret.getKeyId(), 0);
    assertEquals(ret.getUserId(), null);
    assertEquals(ret.getBlockPoolId(), null);
    assertEquals(ret.getBlockId(), 0);
    assertEquals(ret.getAccessModes(),
        EnumSet.noneOf(BlockTokenIdentifier.AccessMode.class));
    assertArrayEquals(ret.getStorageTypes(), StorageType.EMPTY_ARRAY);
  }

  private void testBlockTokenSerialization(boolean useProto) throws
      IOException {
    EnumSet<BlockTokenIdentifier.AccessMode> accessModes =
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class);
    StorageType[] storageTypes =
        new StorageType[]{StorageType.RAM_DISK, StorageType.SSD,
            StorageType.DISK, StorageType.ARCHIVE};
    BlockTokenIdentifier ident = new BlockTokenIdentifier("user", "bpool",
        123, accessModes, storageTypes, new String[] {"fake-storage-id"},
        useProto);
    ident.setExpiryDate(1487080345L);
    BlockTokenIdentifier ret = writeAndReadBlockToken(ident);
    assertEquals(ret.getExpiryDate(), 1487080345L);
    assertEquals(ret.getKeyId(), 0);
    assertEquals(ret.getUserId(), "user");
    assertEquals(ret.getBlockPoolId(), "bpool");
    assertEquals(ret.getBlockId(), 123);
    assertEquals(ret.getAccessModes(),
        EnumSet.allOf(BlockTokenIdentifier.AccessMode.class));
    assertArrayEquals(ret.getStorageTypes(), storageTypes);
    assertArrayEquals(ret.getStorageIds(), new String[] {"fake-storage-id"});
  }

  @Test
  public void testBlockTokenSerialization() throws IOException {
    testBlockTokenSerialization(false);
    testBlockTokenSerialization(true);
  }
}