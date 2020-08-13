/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone;

import com.google.common.base.Optional;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import static org.apache.hadoop.ozone.OzoneConfigKeys.*;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_DEADNODE_INTERVAL_DEFAULT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_DEADNODE_INTERVAL_MS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_STALENODE_INTERVAL_DEFAULT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_STALENODE_INTERVAL_MS;

/**
 * Utility methods for Ozone and Container Clients.
 *
 * The methods to retrieve SCM service endpoints assume there is a single
 * SCM service instance. This will change when we switch to replicated service
 * instances for redundancy.
 */
@InterfaceAudience.Public
@InterfaceStability.Unstable
public final class OzoneClientUtils {
  private static final Logger LOG = LoggerFactory.getLogger(
      OzoneClientUtils.class);

  /**
   * The service ID of the solitary Ozone SCM service.
   */
  public static final String OZONE_SCM_SERVICE_ID = "OzoneScmService";
  public static final String OZONE_SCM_SERVICE_INSTANCE_ID =
      "OzoneScmServiceInstance";

  private OzoneClientUtils() {
    // Never constructed
  }

  /**
   * Retrieve the socket address that should be used by clients to connect
   * to the SCM.
   *
   * @param conf
   * @return Target InetSocketAddress for the SCM client endpoint.
   */
  public static InetSocketAddress getScmAddressForClients(Configuration conf) {
    final Optional<String> host = getHostNameFromConfigKeys(conf,
        OZONE_SCM_CLIENT_ADDRESS_KEY);

    if (!host.isPresent()) {
      throw new IllegalArgumentException(OZONE_SCM_CLIENT_ADDRESS_KEY +
          " must be defined. See" +
          " https://wiki.apache.org/hadoop/Ozone#Configuration for details" +
          " on configuring Ozone.");
    }

    final Optional<Integer> port = getPortNumberFromConfigKeys(conf,
        OZONE_SCM_CLIENT_ADDRESS_KEY);

    return NetUtils.createSocketAddr(host.get() + ":" +
        port.or(OZONE_SCM_CLIENT_PORT_DEFAULT));
  }

  /**
   * Retrieve the socket address that should be used by DataNodes to connect
   * to the SCM.
   *
   * @param conf
   * @return Target InetSocketAddress for the SCM service endpoint.
   */
  public static InetSocketAddress getScmAddressForDataNodes(
      Configuration conf) {
    // We try the following settings in decreasing priority to retrieve the
    // target host.
    // - OZONE_SCM_DATANODE_ADDRESS_KEY
    // - OZONE_SCM_CLIENT_ADDRESS_KEY
    //
    final Optional<String> host = getHostNameFromConfigKeys(conf,
        OZONE_SCM_DATANODE_ADDRESS_KEY, OZONE_SCM_CLIENT_ADDRESS_KEY);

    if (!host.isPresent()) {
      throw new IllegalArgumentException(OZONE_SCM_CLIENT_ADDRESS_KEY +
          " must be defined. See" +
          " https://wiki.apache.org/hadoop/Ozone#Configuration for details" +
          " on configuring Ozone.");
    }

    // If no port number is specified then we'll just try the defaultBindPort.
    final Optional<Integer> port = getPortNumberFromConfigKeys(conf,
        OZONE_SCM_DATANODE_ADDRESS_KEY);

    InetSocketAddress addr = NetUtils.createSocketAddr(host.get() + ":" +
        port.or(OZONE_SCM_DATANODE_PORT_DEFAULT));

    return addr;
  }

  /**
   * Retrieve the socket address that should be used by clients to connect
   * to the SCM.
   *
   * @param conf
   * @return Target InetSocketAddress for the SCM client endpoint.
   */
  public static InetSocketAddress getScmClientBindAddress(
      Configuration conf) {
    final Optional<String> host = getHostNameFromConfigKeys(conf,
        OZONE_SCM_CLIENT_BIND_HOST_KEY);

    final Optional<Integer> port = getPortNumberFromConfigKeys(conf,
        OZONE_SCM_CLIENT_ADDRESS_KEY);

    return NetUtils.createSocketAddr(
        host.or(OZONE_SCM_CLIENT_BIND_HOST_DEFAULT) + ":" +
        port.or(OZONE_SCM_CLIENT_PORT_DEFAULT));
  }

  /**
   * Retrieve the socket address that should be used by DataNodes to connect
   * to the SCM.
   *
   * @param conf
   * @return Target InetSocketAddress for the SCM service endpoint.
   */
  public static InetSocketAddress getScmDataNodeBindAddress(
      Configuration conf) {
    final Optional<String> host = getHostNameFromConfigKeys(conf,
        OZONE_SCM_DATANODE_BIND_HOST_KEY);

    // If no port number is specified then we'll just try the defaultBindPort.
    final Optional<Integer> port = getPortNumberFromConfigKeys(conf,
        OZONE_SCM_DATANODE_ADDRESS_KEY);

    return NetUtils.createSocketAddr(
        host.or(OZONE_SCM_DATANODE_BIND_HOST_DEFAULT) + ":" +
        port.or(OZONE_SCM_DATANODE_PORT_DEFAULT));
  }

  /**
   * Retrieve the hostname, trying the supplied config keys in order.
   * Each config value may be absent, or if present in the format
   * host:port (the :port part is optional).
   *
   * @param conf
   * @param keys a list of configuration key names.
   *
   * @return first hostname component found from the given keys, or absent.
   * @throws IllegalArgumentException if any values are not in the 'host'
   *             or host:port format.
   */
  static Optional<String> getHostNameFromConfigKeys(
      Configuration conf, String ... keys) {
    for (final String key : keys) {
      final String value = conf.getTrimmed(key);
      if (value != null && !value.isEmpty()) {
        String[] splits = value.split(":");

        if(splits.length < 1 || splits.length > 2) {
          throw new IllegalArgumentException(
              "Invalid value " + value + " for config key " + key +
                  ". It should be in 'host' or 'host:port' format");
        }
        return Optional.of(splits[0]);
      }
    }
    return Optional.absent();
  }

  /**
   * Retrieve the port number, trying the supplied config keys in order.
   * Each config value may be absent, or if present in the format
   * host:port (the :port part is optional).
   *
   * @param conf
   * @param keys a list of configuration key names.
   *
   * @return first port number component found from the given keys, or absent.
   * @throws IllegalArgumentException if any values are not in the 'host'
   *             or host:port format.
   */
  static Optional<Integer> getPortNumberFromConfigKeys(
      Configuration conf, String ... keys) {
    for (final String key : keys) {
      final String value = conf.getTrimmed(key);
      if (value != null && !value.isEmpty()) {
        String[] splits = value.split(":");

        if(splits.length < 1 || splits.length > 2) {
          throw new IllegalArgumentException(
              "Invalid value " + value + " for config key " + key +
                  ". It should be in 'host' or 'host:port' format");
        }

        if (splits.length == 2) {
          return Optional.of(Integer.parseInt(splits[1]));
        }
      }
    }
    return Optional.absent();
  }

  /**
   * Return the list of service addresses for the Ozone SCM. This method is used
   * by the DataNodes to determine the service instances to connect to.
   *
   * @param conf
   * @return list of SCM service addresses.
   */
  public static Map<String, ? extends Map<String, InetSocketAddress>>
      getScmServiceRpcAddresses(Configuration conf) {
    final Map<String, InetSocketAddress> serviceInstances = new HashMap<>();
    serviceInstances.put(OZONE_SCM_SERVICE_INSTANCE_ID,
        getScmAddressForDataNodes(conf));

    final Map<String, Map<String, InetSocketAddress>> services =
        new HashMap<>();
    services.put(OZONE_SCM_SERVICE_ID, serviceInstances);
    return services;
  }

  /**
   * Checks that a given value is with a range.
   *
   * For example, sanitizeUserArgs(17, 3, 5, 10)
   * ensures that 17 is greater/equal than 3 * 5 and less/equal to 3 * 10.
   *
   * @param valueTocheck  - value to check
   * @param baseValue     - the base value that is being used.
   * @param minFactor     - range min - a 2 here makes us ensure that value
   *                        valueTocheck is at least twice the baseValue.
   * @param maxFactor     - range max
   * @return long
   */
  private static long sanitizeUserArgs(long valueTocheck, long baseValue,
                                       long minFactor, long maxFactor)
      throws IllegalArgumentException {
    if ((valueTocheck >= (baseValue * minFactor)) &&
        (valueTocheck <= (baseValue * maxFactor))) {
      return valueTocheck;
    }
    String errMsg = String.format("%d is not within min = %d or max = " +
        "%d", valueTocheck, baseValue * minFactor, baseValue * maxFactor);
    throw new IllegalArgumentException(errMsg);
  }


  /**
   * Returns the interval in which the heartbeat processor thread runs.
   *
   * @param conf - Configuration
   * @return long in Milliseconds.
   */
  public static long getScmheartbeatCheckerInterval(Configuration conf) {
    return conf.getLong(OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS,
        OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS_DEFAULT);
  }


  /**
   * Heartbeat Interval - Defines the heartbeat frequency from a datanode to
   * SCM.
   *
   * @param conf - Ozone Config
   * @return - HB interval in seconds.
   */
  public static int getScmHeartbeatInterval(Configuration conf) {
    return conf.getInt(OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS,
        OZONE_SCM_HEARBEAT_INTERVAL_SECONDS_DEFAULT);
  }


  /**
   * Get the Stale Node interval, which is used by SCM to flag a datanode as
   * stale, if the heartbeat from that node has been missing for this duration.
   *
   * @param conf - Configuration.
   * @return - Long, Milliseconds to wait before flagging a node as stale.
   */
  public static long getStaleNodeInterval(Configuration conf) {

    long staleNodeIntevalMs = conf.getLong(OZONE_SCM_STALENODE_INTERVAL_MS,
        OZONE_SCM_STALENODE_INTERVAL_DEFAULT);

    long heartbeatThreadFrequencyMs = getScmheartbeatCheckerInterval(conf);

    long heartbeatIntervalMs = getScmHeartbeatInterval(conf) * 1000;


    // Make sure that StaleNodeInterval is configured way above the frequency
    // at which we run the heartbeat thread.
    //
    // Here we check that staleNodeInterval is at least five times more than the
    // frequency at which the accounting thread is going to run.
    try {
      sanitizeUserArgs(staleNodeIntevalMs, heartbeatThreadFrequencyMs, 5, 1000);
    } catch (IllegalArgumentException ex) {
      LOG.error("Stale Node Interval MS is cannot be honored due to " +
              "mis-configured {}. ex:  {}",
          OZONE_SCM_HEARTBEAT_PROCESS_INTERVAL_MS, ex);
      throw ex;
    }

    // Make sure that stale node value is greater than configured value that
    // datanodes are going to send HBs.
    try {
      sanitizeUserArgs(staleNodeIntevalMs, heartbeatIntervalMs, 3, 1000);
    } catch (IllegalArgumentException ex) {
      LOG.error("Stale Node Interval MS is cannot be honored due to " +
              "mis-configured {}. ex:  {}",
          OZONE_SCM_HEARTBEAT_INTERVAL_SECONDS, ex);
      throw ex;
    }
    return staleNodeIntevalMs;
  }


  /**
   * Gets the interval for dead node flagging. This has to be a value that is
   * greater than stale node value,  and by transitive relation we also know
   * that this value is greater than heartbeat interval and heartbeatProcess
   * Interval.
   *
   * @param conf
   * @return
   */
  public static long getDeadNodeInterval(Configuration conf) {
    long staleNodeIntervalMs = getStaleNodeInterval(conf);
    long deadNodeIntervalMs = conf.getLong(
        OZONE_SCM_DEADNODE_INTERVAL_MS, OZONE_SCM_DEADNODE_INTERVAL_DEFAULT);

    try {
      // Make sure that dead nodes Ms is at least twice the time for staleNodes
      // with a max of 1000 times the staleNodes.
      sanitizeUserArgs(deadNodeIntervalMs, staleNodeIntervalMs, 2, 1000);
    } catch (IllegalArgumentException ex) {
      LOG.error("Dead Node Interval MS is cannot be honored due to " +
              "mis-configured {}. ex:  {}",
          OZONE_SCM_STALENODE_INTERVAL_MS, ex);
      throw ex;
    }
    return deadNodeIntervalMs;
  }

  /**
   * Returns the maximum number of heartbeat to process per loop of the process
   * thread.
   * @param conf Configration
   * @return - int -- Number of HBs to process
   */
  public static int getMaxHBToProcessPerLoop(Configuration conf){
    return conf.getInt(OZONE_SCM_MAX_HB_COUNT_TO_PROCESS,
        OZONE_SCM_MAX_HB_COUNT_TO_PROCESS_DEFAULT);
  }
}
