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
package org.apache.hadoop.yarn.webapp.util;

import static org.apache.hadoop.yarn.util.StringHelper.join;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.http.HttpConfig.Policy;
import org.apache.hadoop.http.HttpServer2;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;

@Private
@Evolving
public class WebAppUtils {
  public static void setRMWebAppPort(Configuration conf, int port) {
    String hostname = getRMWebAppURLWithoutScheme(conf);
    hostname =
        (hostname.contains(":")) ? hostname.substring(0, hostname.indexOf(":"))
            : hostname;
    setRMWebAppHostnameAndPort(conf, hostname, port);
  }

  public static void setRMWebAppHostnameAndPort(Configuration conf,
      String hostname, int port) {
    String resolvedAddress = hostname + ":" + port;
    if (YarnConfiguration.useHttps(conf)) {
      conf.set(YarnConfiguration.RM_WEBAPP_HTTPS_ADDRESS, resolvedAddress);
    } else {
      conf.set(YarnConfiguration.RM_WEBAPP_ADDRESS, resolvedAddress);
    }
  }
  
  public static void setNMWebAppHostNameAndPort(Configuration conf,
      String hostName, int port) {
    if (YarnConfiguration.useHttps(conf)) {
      conf.set(YarnConfiguration.NM_WEBAPP_HTTPS_ADDRESS,
          hostName + ":" + port);
    } else {
      conf.set(YarnConfiguration.NM_WEBAPP_ADDRESS,
          hostName + ":" + port);
    }
  }
  
  public static String getRMWebAppURLWithScheme(Configuration conf) {
    return getHttpSchemePrefix(conf) + getRMWebAppURLWithoutScheme(conf);
  }
  
  public static String getRMWebAppURLWithoutScheme(Configuration conf) {
    if (YarnConfiguration.useHttps(conf)) {
      return conf.get(YarnConfiguration.RM_WEBAPP_HTTPS_ADDRESS,
          YarnConfiguration.DEFAULT_RM_WEBAPP_HTTPS_ADDRESS);
    }else {
      return conf.get(YarnConfiguration.RM_WEBAPP_ADDRESS,
          YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS);
    }
  }
  
  public static String getProxyHostAndPort(Configuration conf) {
    String addr = conf.get(YarnConfiguration.PROXY_ADDRESS);
    if(addr == null || addr.isEmpty()) {
      addr = getResolvedRMWebAppURLWithoutScheme(conf);
    }
    return addr;
  }

  public static String getResolvedRMWebAppURLWithScheme(Configuration conf) {
    return getHttpSchemePrefix(conf)
        + getResolvedRMWebAppURLWithoutScheme(conf);
  }
  
  public static String getResolvedRMWebAppURLWithoutScheme(Configuration conf) {
    return getResolvedRMWebAppURLWithoutScheme(conf,
        YarnConfiguration.useHttps(conf) ? Policy.HTTPS_ONLY : Policy.HTTP_ONLY);
  }
  
  public static String getResolvedRMWebAppURLWithoutScheme(Configuration conf,
      Policy httpPolicy) {
    InetSocketAddress address = null;
    if (httpPolicy == Policy.HTTPS_ONLY) {
      address =
          conf.getSocketAddr(YarnConfiguration.RM_WEBAPP_HTTPS_ADDRESS,
              YarnConfiguration.DEFAULT_RM_WEBAPP_HTTPS_ADDRESS,
              YarnConfiguration.DEFAULT_RM_WEBAPP_HTTPS_PORT);
    } else {
      address =
          conf.getSocketAddr(YarnConfiguration.RM_WEBAPP_ADDRESS,
              YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS,
              YarnConfiguration.DEFAULT_RM_WEBAPP_PORT);      
    }
    address = NetUtils.getConnectAddress(address);
    StringBuffer sb = new StringBuffer();
    InetAddress resolved = address.getAddress();
    if (resolved == null || resolved.isAnyLocalAddress() || 
        resolved.isLoopbackAddress()) {
      String lh = address.getHostName();
      try {
        lh = InetAddress.getLocalHost().getCanonicalHostName();
      } catch (UnknownHostException e) {
        //Ignore and fallback.
      }
      sb.append(lh);
    } else {
      sb.append(address.getHostName());
    }
    sb.append(":").append(address.getPort());
    return sb.toString();
  }
  
  public static String getNMWebAppURLWithoutScheme(Configuration conf) {
    if (YarnConfiguration.useHttps(conf)) {
      return conf.get(YarnConfiguration.NM_WEBAPP_HTTPS_ADDRESS,
        YarnConfiguration.DEFAULT_NM_WEBAPP_HTTPS_ADDRESS);
    } else {
      return conf.get(YarnConfiguration.NM_WEBAPP_ADDRESS,
        YarnConfiguration.DEFAULT_NM_WEBAPP_ADDRESS);
    }
  }

  public static String getAHSWebAppURLWithoutScheme(Configuration conf) {
    if (YarnConfiguration.useHttps(conf)) {
      return conf.get(YarnConfiguration.TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS);
    } else {
      return conf.get(YarnConfiguration.TIMELINE_SERVICE_WEBAPP_ADDRESS,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_ADDRESS);
    }
  }
  
  /**
   * if url has scheme then it will be returned as it is else it will return
   * url with scheme.
   * @param schemePrefix eg. http:// or https://
   * @param url
   * @return url with scheme
   */
  public static String getURLWithScheme(String schemePrefix, String url) {
    // If scheme is provided then it will be returned as it is
    if (url.indexOf("://") > 0) {
      return url;
    } else {
      return schemePrefix + url;
    }
  }
  
  public static String getLogUrl(String nodeHttpAddress, String allocatedNode,
      ContainerId containerId, String user) {
    return join("//", nodeHttpAddress, "/logs", "/",
        allocatedNode, "/", ConverterUtils.toString(containerId), "/",
        ConverterUtils.toString(containerId), "/", user);
  }

  /**
   * Choose which scheme (HTTP or HTTPS) to use when generating a URL based on
   * the configuration.
   * 
   * @return the schmeme (HTTP / HTTPS)
   */
  public static String getHttpSchemePrefix(Configuration conf) {
    return YarnConfiguration.useHttps(conf) ? "https://" : "http://";
  }

  /**
   * Load the SSL keystore / truststore into the HttpServer builder.
   */
  public static HttpServer2.Builder loadSslConfiguration(
      HttpServer2.Builder builder) {
    Configuration sslConf = new Configuration(false);
    boolean needsClientAuth = YarnConfiguration.YARN_SSL_CLIENT_HTTPS_NEED_AUTH_DEFAULT;
    sslConf.addResource(YarnConfiguration.YARN_SSL_SERVER_RESOURCE_DEFAULT);

    return builder
        .needsClientAuth(needsClientAuth)
        .keyPassword(sslConf.get("ssl.server.keystore.keypassword"))
        .keyStore(sslConf.get("ssl.server.keystore.location"),
            sslConf.get("ssl.server.keystore.password"),
            sslConf.get("ssl.server.keystore.type", "jks"))
        .trustStore(sslConf.get("ssl.server.truststore.location"),
            sslConf.get("ssl.server.truststore.password"),
            sslConf.get("ssl.server.truststore.type", "jks"));
  }
}
