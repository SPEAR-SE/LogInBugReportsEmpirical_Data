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
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CLIENT_FAILOVER_PROXY_PROVIDER_KEY_PREFIX;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSClient.Conf;
import org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.GetUserMappingsProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.GetUserMappingsProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.JournalProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.JournalProtocolTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.NamenodeProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.NamenodeProtocolTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.RefreshAuthorizationPolicyProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.RefreshAuthorizationPolicyProtocolPB;
import org.apache.hadoop.hdfs.protocolPB.RefreshUserMappingsProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.protocolPB.RefreshUserMappingsProtocolPB;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.hdfs.server.protocol.JournalProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.retry.DefaultFailoverProxyProvider;
import org.apache.hadoop.io.retry.FailoverProxyProvider;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.RefreshUserMappingsProtocol;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.RefreshAuthorizationPolicyProtocol;
import org.apache.hadoop.tools.GetUserMappingsProtocol;

import com.google.common.base.Preconditions;
import com.google.protobuf.ServiceException;

/**
 * Create proxy objects to communicate with a remote NN. All remote access to an
 * NN should be funneled through this class. Most of the time you'll want to use
 * {@link NameNodeProxies#createProxy(Configuration, URI, Class)}, which will
 * create either an HA- or non-HA-enabled client proxy as appropriate.
 */
public class NameNodeProxies {
  
  private static final Log LOG = LogFactory.getLog(NameNodeProxies.class);

  /**
   * Wrapper for a client proxy as well as its associated service ID.
   * This is simply used as a tuple-like return type for
   * {@link NameNodeProxies#createProxy} and
   * {@link NameNodeProxies#createNonHAProxy}.
   */
  public static class ProxyAndInfo<PROXYTYPE> {
    private final PROXYTYPE proxy;
    private final Text dtService;
    
    public ProxyAndInfo(PROXYTYPE proxy, Text dtService) {
      this.proxy = proxy;
      this.dtService = dtService;
    }
    
    public PROXYTYPE getProxy() {
      return proxy;
    }
    
    public Text getDelegationTokenService() {
      return dtService;
    }
  }

  /**
   * Creates the namenode proxy with the passed protocol. This will handle
   * creation of either HA- or non-HA-enabled proxy objects, depending upon
   * if the provided URI is a configured logical URI.
   * 
   * @param conf the configuration containing the required IPC
   *        properties, client failover configurations, etc.
   * @param nameNodeUri the URI pointing either to a specific NameNode
   *        or to a logical nameservice.
   * @param xface the IPC interface which should be created
   * @return an object containing both the proxy and the associated
   *         delegation token service it corresponds to
   * @throws IOException if there is an error creating the proxy
   **/
  @SuppressWarnings("unchecked")
  public static <T> ProxyAndInfo<T> createProxy(Configuration conf,
      URI nameNodeUri, Class<T> xface) throws IOException {
    Class<FailoverProxyProvider<T>> failoverProxyProviderClass =
        getFailoverProxyProviderClass(conf, nameNodeUri, xface);
  
    if (failoverProxyProviderClass == null) {
      // Non-HA case
      return createNonHAProxy(conf, NameNode.getAddress(nameNodeUri), xface,
          UserGroupInformation.getCurrentUser(), true);
    } else {
      // HA case
      FailoverProxyProvider<T> failoverProxyProvider = NameNodeProxies
          .createFailoverProxyProvider(conf, failoverProxyProviderClass, xface,
              nameNodeUri);
      Conf config = new Conf(conf);
      T proxy = (T) RetryProxy.create(xface, failoverProxyProvider, RetryPolicies
          .failoverOnNetworkException(RetryPolicies.TRY_ONCE_THEN_FAIL,
              config.maxFailoverAttempts, config.failoverSleepBaseMillis,
              config.failoverSleepMaxMillis));
      
      Text dtService = HAUtil.buildTokenServiceForLogicalUri(nameNodeUri);
      return new ProxyAndInfo<T>(proxy, dtService);
    }
  }

  /**
   * Creates an explicitly non-HA-enabled proxy object. Most of the time you
   * don't want to use this, and should instead use {@link NameNodeProxies#createProxy}.
   * 
   * @param conf the configuration object
   * @param nnAddr address of the remote NN to connect to
   * @param xface the IPC interface which should be created
   * @param ugi the user who is making the calls on the proxy object
   * @param withRetries certain interfaces have a non-standard retry policy
   * @return an object containing both the proxy and the associated
   *         delegation token service it corresponds to
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public static <T> ProxyAndInfo<T> createNonHAProxy(
      Configuration conf, InetSocketAddress nnAddr, Class<T> xface,
      UserGroupInformation ugi, boolean withRetries) throws IOException {
    Text dtService = SecurityUtil.buildTokenService(nnAddr);
  
    T proxy;
    if (xface == ClientProtocol.class) {
      proxy = (T) createNNProxyWithClientProtocol(nnAddr, conf, ugi,
          withRetries);
    } else if (xface == JournalProtocol.class) {
      proxy = (T) createNNProxyWithJournalProtocol(nnAddr, conf, ugi);
    } else if (xface == NamenodeProtocol.class) {
      proxy = (T) createNNProxyWithNamenodeProtocol(nnAddr, conf, ugi,
          withRetries);
    } else if (xface == GetUserMappingsProtocol.class) {
      proxy = (T) createNNProxyWithGetUserMappingsProtocol(nnAddr, conf, ugi);
    } else if (xface == RefreshUserMappingsProtocol.class) {
      proxy = (T) createNNProxyWithRefreshUserMappingsProtocol(nnAddr, conf, ugi);
    } else if (xface == RefreshAuthorizationPolicyProtocol.class) {
      proxy = (T) createNNProxyWithRefreshAuthorizationPolicyProtocol(nnAddr,
          conf, ugi);
    } else {
      String message = "Upsupported protocol found when creating the proxy " +
          "connection to NameNode: " +
          ((xface != null) ? xface.getClass().getName() : "null");
      LOG.error(message);
      throw new IllegalStateException(message);
    }
    return new ProxyAndInfo<T>(proxy, dtService);
  }
  
  private static JournalProtocol createNNProxyWithJournalProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi)
      throws IOException {
    JournalProtocolPB proxy = (JournalProtocolPB) createNameNodeProxy(address,
        conf, ugi, JournalProtocolPB.class, 30000);
    return new JournalProtocolTranslatorPB(proxy);
  }

  private static RefreshAuthorizationPolicyProtocol
      createNNProxyWithRefreshAuthorizationPolicyProtocol(InetSocketAddress address,
          Configuration conf, UserGroupInformation ugi) throws IOException {
    RefreshAuthorizationPolicyProtocolPB proxy = (RefreshAuthorizationPolicyProtocolPB)
        createNameNodeProxy(address, conf, ugi, RefreshAuthorizationPolicyProtocolPB.class, 0);
    return new RefreshAuthorizationPolicyProtocolClientSideTranslatorPB(proxy);
  }
  
  private static RefreshUserMappingsProtocol
      createNNProxyWithRefreshUserMappingsProtocol(InetSocketAddress address,
          Configuration conf, UserGroupInformation ugi) throws IOException {
    RefreshUserMappingsProtocolPB proxy = (RefreshUserMappingsProtocolPB)
        createNameNodeProxy(address, conf, ugi, RefreshUserMappingsProtocolPB.class, 0);
    return new RefreshUserMappingsProtocolClientSideTranslatorPB(proxy);
  }

  private static GetUserMappingsProtocol createNNProxyWithGetUserMappingsProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi)
      throws IOException {
    GetUserMappingsProtocolPB proxy = (GetUserMappingsProtocolPB)
        createNameNodeProxy(address, conf, ugi, GetUserMappingsProtocolPB.class, 0);
    return new GetUserMappingsProtocolClientSideTranslatorPB(proxy);
  }
  
  private static NamenodeProtocol createNNProxyWithNamenodeProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi,
      boolean withRetries) throws IOException {
    NamenodeProtocolPB proxy = (NamenodeProtocolPB) createNameNodeProxy(
        address, conf, ugi, NamenodeProtocolPB.class, 0);
    if (withRetries) { // create the proxy with retries
      RetryPolicy timeoutPolicy = RetryPolicies.exponentialBackoffRetry(5, 200,
          TimeUnit.MILLISECONDS);
      Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap 
                     = new HashMap<Class<? extends Exception>, RetryPolicy>();
      RetryPolicy methodPolicy = RetryPolicies.retryByException(timeoutPolicy,
          exceptionToPolicyMap);
      Map<String, RetryPolicy> methodNameToPolicyMap 
                     = new HashMap<String, RetryPolicy>();
      methodNameToPolicyMap.put("getBlocks", methodPolicy);
      methodNameToPolicyMap.put("getAccessKeys", methodPolicy);
      proxy = (NamenodeProtocolPB) RetryProxy.create(NamenodeProtocolPB.class,
          proxy, methodNameToPolicyMap);
    }
    return new NamenodeProtocolTranslatorPB(proxy);
  }
  
  /**
   * Return the default retry policy used in RPC.
   * 
   * If dfs.client.retry.policy.enabled == false, use TRY_ONCE_THEN_FAIL.
   * 
   * Otherwise, first unwrap ServiceException if possible, and then 
   * (1) use multipleLinearRandomRetry for
   *     - SafeModeException, or
   *     - IOException other than RemoteException, or
   *     - ServiceException; and
   * (2) use TRY_ONCE_THEN_FAIL for
   *     - non-SafeMode RemoteException, or
   *     - non-IOException.
   *     
   * Note that dfs.client.retry.max < 0 is not allowed.
   */
  private static RetryPolicy getDefaultRpcRetryPolicy(Configuration conf) {
    final RetryPolicy multipleLinearRandomRetry = getMultipleLinearRandomRetry(conf);
    if (LOG.isDebugEnabled()) {
      LOG.debug("multipleLinearRandomRetry = " + multipleLinearRandomRetry);
    }
    if (multipleLinearRandomRetry == null) {
      //no retry
      return RetryPolicies.TRY_ONCE_THEN_FAIL;
    } else {
      return new RetryPolicy() {
        @Override
        public RetryAction shouldRetry(Exception e, int retries, int failovers,
            boolean isMethodIdempotent) throws Exception {
          if (e instanceof ServiceException) {
            //unwrap ServiceException
            final Throwable cause = e.getCause();
            if (cause != null && cause instanceof Exception) {
              e = (Exception)cause;
            }
          }

          //see (1) and (2) in the javadoc of this method.
          final RetryPolicy p;
          if (e instanceof RemoteException) {
            final RemoteException re = (RemoteException)e;
            p = SafeModeException.class.getName().equals(re.getClassName())?
                multipleLinearRandomRetry: RetryPolicies.TRY_ONCE_THEN_FAIL;
          } else if (e instanceof IOException || e instanceof ServiceException) {
            p = multipleLinearRandomRetry;
          } else { //non-IOException
            p = RetryPolicies.TRY_ONCE_THEN_FAIL;
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug("RETRY " + retries + ") policy="
                + p.getClass().getSimpleName() + ", exception=" + e);
          }
          LOG.info("RETRY " + retries + ") policy="
              + p.getClass().getSimpleName() + ", exception=" + e);
          return p.shouldRetry(e, retries, failovers, isMethodIdempotent);
        }
      };
    }
  }

  /**
   * Return the MultipleLinearRandomRetry policy specified in the conf,
   * or null if the feature is disabled.
   * If the policy is specified in the conf but the policy cannot be parsed,
   * the default policy is returned.
   * 
   * Conf property: N pairs of sleep-time and number-of-retries
   *   dfs.client.retry.policy = "s1,n1,s2,n2,..."
   */
  private static RetryPolicy getMultipleLinearRandomRetry(Configuration conf) {
    final boolean enabled = conf.getBoolean(
        DFSConfigKeys.DFS_CLIENT_RETRY_POLICY_ENABLED_KEY,
        DFSConfigKeys.DFS_CLIENT_RETRY_POLICY_ENABLED_DEFAULT);
    if (!enabled) {
      return null;
    }

    final String policy = conf.get(
        DFSConfigKeys.DFS_CLIENT_RETRY_POLICY_SPEC_KEY,
        DFSConfigKeys.DFS_CLIENT_RETRY_POLICY_SPEC_DEFAULT);

    final RetryPolicy r = RetryPolicies.MultipleLinearRandomRetry.parseCommaSeparatedString(policy);
    return r != null? r: RetryPolicies.MultipleLinearRandomRetry.parseCommaSeparatedString(
        DFSConfigKeys.DFS_CLIENT_RETRY_POLICY_SPEC_DEFAULT);
  }

  private static ClientProtocol createNNProxyWithClientProtocol(
      InetSocketAddress address, Configuration conf, UserGroupInformation ugi,
      boolean withRetries) throws IOException {
    RPC.setProtocolEngine(conf, ClientNamenodeProtocolPB.class, ProtobufRpcEngine.class);

    final RetryPolicy defaultPolicy = getDefaultRpcRetryPolicy(conf);
    final long version = RPC.getProtocolVersion(ClientNamenodeProtocolPB.class);
    ClientNamenodeProtocolPB proxy = RPC.getProtocolProxy(
        ClientNamenodeProtocolPB.class, version, address, ugi, conf,
        NetUtils.getDefaultSocketFactory(conf), 0, defaultPolicy).getProxy();

    if (withRetries) { // create the proxy with retries

      RetryPolicy createPolicy = RetryPolicies
          .retryUpToMaximumCountWithFixedSleep(5,
              HdfsConstants.LEASE_SOFTLIMIT_PERIOD, TimeUnit.MILLISECONDS);
    
      Map<Class<? extends Exception>, RetryPolicy> remoteExceptionToPolicyMap 
                 = new HashMap<Class<? extends Exception>, RetryPolicy>();
      remoteExceptionToPolicyMap.put(AlreadyBeingCreatedException.class,
          createPolicy);
    
      Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap
                 = new HashMap<Class<? extends Exception>, RetryPolicy>();
      exceptionToPolicyMap.put(RemoteException.class, RetryPolicies
          .retryByRemoteException(defaultPolicy,
              remoteExceptionToPolicyMap));
      RetryPolicy methodPolicy = RetryPolicies.retryByException(
          defaultPolicy, exceptionToPolicyMap);
      Map<String, RetryPolicy> methodNameToPolicyMap 
                 = new HashMap<String, RetryPolicy>();
    
      methodNameToPolicyMap.put("create", methodPolicy);
    
      proxy = (ClientNamenodeProtocolPB) RetryProxy.create(
          ClientNamenodeProtocolPB.class,
          new DefaultFailoverProxyProvider<ClientNamenodeProtocolPB>(
              ClientNamenodeProtocolPB.class, proxy),
          methodNameToPolicyMap,
          defaultPolicy);
    }
    return new ClientNamenodeProtocolTranslatorPB(proxy);
  }
  
  @SuppressWarnings("unchecked")
  private static Object createNameNodeProxy(InetSocketAddress address,
      Configuration conf, UserGroupInformation ugi, Class xface, int rpcTimeout)
      throws IOException {
    RPC.setProtocolEngine(conf, xface, ProtobufRpcEngine.class);
    Object proxy = RPC.getProxy(xface, RPC.getProtocolVersion(xface), address,
        ugi, conf, NetUtils.getDefaultSocketFactory(conf), rpcTimeout);
    return proxy;
  }

  /** Gets the configured Failover proxy provider's class */
  private static <T> Class<FailoverProxyProvider<T>> getFailoverProxyProviderClass(
      Configuration conf, URI nameNodeUri, Class<T> xface) throws IOException {
    if (nameNodeUri == null) {
      return null;
    }
    String host = nameNodeUri.getHost();
  
    String configKey = DFS_CLIENT_FAILOVER_PROXY_PROVIDER_KEY_PREFIX + "."
        + host;
    try {
      @SuppressWarnings("unchecked")
      Class<FailoverProxyProvider<T>> ret = (Class<FailoverProxyProvider<T>>) conf
          .getClass(configKey, null, FailoverProxyProvider.class);
      if (ret != null) {
        // If we found a proxy provider, then this URI should be a logical NN.
        // Given that, it shouldn't have a non-default port number.
        int port = nameNodeUri.getPort();
        if (port > 0 && port != DFSConfigKeys.DFS_NAMENODE_RPC_PORT_DEFAULT) {
          throw new IOException("Port " + port + " specified in URI "
              + nameNodeUri + " but host '" + host
              + "' is a logical (HA) namenode"
              + " and does not use port information.");
        }
      }
      return ret;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ClassNotFoundException) {
        throw new IOException("Could not load failover proxy provider class "
            + conf.get(configKey) + " which is configured for authority "
            + nameNodeUri, e);
      } else {
        throw e;
      }
    }
  }

  /** Creates the Failover proxy provider instance*/
  @SuppressWarnings("unchecked")
  private static <T> FailoverProxyProvider<T> createFailoverProxyProvider(
      Configuration conf, Class<FailoverProxyProvider<T>> failoverProxyProviderClass,
      Class<T> xface, URI nameNodeUri) throws IOException {
    Preconditions.checkArgument(
        xface.isAssignableFrom(NamenodeProtocols.class),
        "Interface %s is not a NameNode protocol", xface);
    try {
      Constructor<FailoverProxyProvider<T>> ctor = failoverProxyProviderClass
          .getConstructor(Configuration.class, URI.class, Class.class);
      FailoverProxyProvider<?> provider = ctor.newInstance(conf, nameNodeUri,
          xface);
      return (FailoverProxyProvider<T>) provider;
    } catch (Exception e) {
      String message = "Couldn't create proxy provider " + failoverProxyProviderClass;
      if (LOG.isDebugEnabled()) {
        LOG.debug(message, e);
      }
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new IOException(message, e);
      }
    }
  }

}
