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
package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.server.nodemanager.api.LocalizationProtocol;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalResourceStatus;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalizerAction;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalizerStatus;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestContainerLocalizer {

  static final Path basedir =
      new Path("target", TestContainerLocalizer.class.getName());

  @Test
  @SuppressWarnings("unchecked") // mocked generics
  public void testContainerLocalizerMain() throws Exception {
    Configuration conf = new Configuration();
    AbstractFileSystem spylfs =
      spy(FileContext.getLocalFSFileContext().getDefaultFileSystem());
    // don't actually create dirs
    doNothing().when(spylfs).mkdir(
        isA(Path.class), isA(FsPermission.class), anyBoolean());
    FileContext lfs = FileContext.getFileContext(spylfs, conf);
    final String user = "yak";
    final String appId = "app_RM_0";
    final String cId = "container_0";
    final InetSocketAddress nmAddr = new InetSocketAddress("foobar", 8040);
    final List<Path> localDirs = new ArrayList<Path>();
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
    }
    RecordFactory mockRF = getMockLocalizerRecordFactory();
    ContainerLocalizer concreteLoc = new ContainerLocalizer(lfs, user,
        appId, cId, localDirs, mockRF);
    ContainerLocalizer localizer = spy(concreteLoc);

    // return credential stream instead of opening local file
    final Random r = new Random();
    long seed = r.nextLong();
    r.setSeed(seed);
    System.out.println("SEED: " + seed);
    DataInputBuffer appTokens = createFakeCredentials(r, 10);
    Path tokenPath =
      lfs.makeQualified(new Path(
            String.format(ContainerLocalizer.TOKEN_FILE_NAME_FMT, cId)));
    doReturn(new FSDataInputStream(new FakeFSDataInputStream(appTokens))
        ).when(spylfs).open(tokenPath);

    // mock heartbeat responses from NM
    LocalizationProtocol nmProxy = mock(LocalizationProtocol.class);
    LocalResource rsrcA = getMockRsrc(r, LocalResourceVisibility.PRIVATE);
    LocalResource rsrcB = getMockRsrc(r, LocalResourceVisibility.PRIVATE);
    LocalResource rsrcC = getMockRsrc(r, LocalResourceVisibility.APPLICATION);
    LocalResource rsrcD = getMockRsrc(r, LocalResourceVisibility.PRIVATE);
    when(nmProxy.heartbeat(isA(LocalizerStatus.class)))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.LIVE,
            Collections.singletonList(rsrcA)))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.LIVE,
            Collections.singletonList(rsrcB)))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.LIVE,
            Collections.singletonList(rsrcC)))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.LIVE,
            Collections.singletonList(rsrcD)))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.LIVE,
            Collections.<LocalResource>emptyList()))
      .thenReturn(new MockLocalizerHeartbeatResponse(LocalizerAction.DIE,
            null));
    doReturn(new FakeDownload(rsrcA.getResource().getFile(), true)).when(
        localizer).download(isA(LocalDirAllocator.class), eq(rsrcA),
        isA(UserGroupInformation.class));
    doReturn(new FakeDownload(rsrcB.getResource().getFile(), true)).when(
        localizer).download(isA(LocalDirAllocator.class), eq(rsrcB),
        isA(UserGroupInformation.class));
    doReturn(new FakeDownload(rsrcC.getResource().getFile(), true)).when(
        localizer).download(isA(LocalDirAllocator.class), eq(rsrcC),
        isA(UserGroupInformation.class));
    doReturn(new FakeDownload(rsrcD.getResource().getFile(), true)).when(
        localizer).download(isA(LocalDirAllocator.class), eq(rsrcD),
        isA(UserGroupInformation.class));
    doReturn(nmProxy).when(localizer).getProxy(nmAddr);
    doNothing().when(localizer).sleep(anyInt());

    // return result instantly for deterministic test
    ExecutorService syncExec = mock(ExecutorService.class);
    CompletionService<Path> cs = mock(CompletionService.class);
    when(cs.submit(isA(Callable.class)))
      .thenAnswer(new Answer<Future<Path>>() {
          @Override
          public Future<Path> answer(InvocationOnMock invoc)
              throws Throwable {
            Future<Path> done = mock(Future.class);
            when(done.isDone()).thenReturn(true);
            FakeDownload d = (FakeDownload) invoc.getArguments()[0];
            when(done.get()).thenReturn(d.call());
            return done;
          }
        });
    doReturn(syncExec).when(localizer).createDownloadThreadPool();
    doReturn(cs).when(localizer).createCompletionService(syncExec);

    // run localization
    assertEquals(0, localizer.runLocalization(nmAddr));

    // verify created cache
    for (Path p : localDirs) {
      Path base = new Path(new Path(p, ContainerLocalizer.USERCACHE), user);
      Path privcache = new Path(base, ContainerLocalizer.FILECACHE);
      // $x/usercache/$user/filecache
      verify(spylfs).mkdir(eq(privcache), isA(FsPermission.class), eq(false));
      Path appDir =
        new Path(base, new Path(ContainerLocalizer.APPCACHE, appId));
      // $x/usercache/$user/appcache/$appId/filecache
      Path appcache = new Path(appDir, ContainerLocalizer.FILECACHE);
      verify(spylfs).mkdir(eq(appcache), isA(FsPermission.class), eq(false));
    }

    // verify tokens read at expected location
    verify(spylfs).open(tokenPath);

    // verify downloaded resources reported to NM
    verify(nmProxy).heartbeat(argThat(new HBMatches(rsrcA)));
    verify(nmProxy).heartbeat(argThat(new HBMatches(rsrcB)));
    verify(nmProxy).heartbeat(argThat(new HBMatches(rsrcC)));
    verify(nmProxy).heartbeat(argThat(new HBMatches(rsrcD)));

    // verify all HB use localizerID provided
    verify(nmProxy, never()).heartbeat(argThat(
        new ArgumentMatcher<LocalizerStatus>() {
          @Override
          public boolean matches(Object o) {
            LocalizerStatus status = (LocalizerStatus) o;
            return !cId.equals(status.getLocalizerId());
          }
        }));
  }

  static class HBMatches extends ArgumentMatcher<LocalizerStatus> {
    final LocalResource rsrc;
    HBMatches(LocalResource rsrc) {
      this.rsrc = rsrc;
    }
    @Override
    public boolean matches(Object o) {
      LocalizerStatus status = (LocalizerStatus) o;
      for (LocalResourceStatus localized : status.getResources()) {
        switch (localized.getStatus()) {
        case FETCH_SUCCESS:
          if (localized.getLocalPath().getFile().contains(
                rsrc.getResource().getFile())) {
            return true;
          }
          break;
        default:
          fail("Unexpected: " + localized.getStatus());
          break;
        }
      }
      return false;
    }
  }

  static class FakeDownload implements Callable<Path> {
    private final Path localPath;
    private final boolean succeed;
    FakeDownload(String absPath, boolean succeed) {
      this.localPath = new Path("file:///localcache" + absPath);
      this.succeed = succeed;
    }
    @Override
    public Path call() throws IOException {
      if (!succeed) {
        throw new IOException("FAIL " + localPath);
      }
      return localPath;
    }
  }

  static RecordFactory getMockLocalizerRecordFactory() {
    RecordFactory mockRF = mock(RecordFactory.class);
    when(mockRF.newRecordInstance(same(LocalResourceStatus.class)))
      .thenAnswer(new Answer<LocalResourceStatus>() {
          @Override
          public LocalResourceStatus answer(InvocationOnMock invoc)
              throws Throwable {
            return new MockLocalResourceStatus();
          }
        });
    when(mockRF.newRecordInstance(same(LocalizerStatus.class)))
      .thenAnswer(new Answer<LocalizerStatus>() {
          @Override
          public LocalizerStatus answer(InvocationOnMock invoc)
              throws Throwable {
            return new MockLocalizerStatus();
          }
        });
    return mockRF;
  }

  static LocalResource getMockRsrc(Random r,
      LocalResourceVisibility vis) {
    LocalResource rsrc = mock(LocalResource.class);

    String name = Long.toHexString(r.nextLong());
    URL uri = mock(org.apache.hadoop.yarn.api.records.URL.class);
    when(uri.getScheme()).thenReturn("file");
    when(uri.getHost()).thenReturn(null);
    when(uri.getFile()).thenReturn("/local/" + vis + "/" + name);

    when(rsrc.getResource()).thenReturn(uri);
    when(rsrc.getSize()).thenReturn(r.nextInt(1024) + 1024L);
    when(rsrc.getTimestamp()).thenReturn(r.nextInt(1024) + 2048L);
    when(rsrc.getType()).thenReturn(LocalResourceType.FILE);
    when(rsrc.getVisibility()).thenReturn(vis);

    return rsrc;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
static DataInputBuffer createFakeCredentials(Random r, int nTok)
      throws IOException {
    Credentials creds = new Credentials();
    byte[] password = new byte[20];
    Text kind = new Text();
    Text service = new Text();
    Text alias = new Text();
    for (int i = 0; i < nTok; ++i) {
      byte[] identifier = ("idef" + i).getBytes();
      r.nextBytes(password);
      kind.set("kind" + i);
      service.set("service" + i);
      alias.set("token" + i);
      Token token = new Token(identifier, password, kind, service);
      creds.addToken(alias, token);
    }
    DataOutputBuffer buf = new DataOutputBuffer();
    creds.writeTokenStorageToStream(buf);
    DataInputBuffer ret = new DataInputBuffer();
    ret.reset(buf.getData(), 0, buf.getLength());
    return ret;
  }

}
