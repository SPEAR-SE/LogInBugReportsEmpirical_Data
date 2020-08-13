/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.mqtt;

import java.util.concurrent.TimeUnit;

import org.apache.activemq.util.Wait;
import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.fusesource.mqtt.client.Tracer;
import org.fusesource.mqtt.codec.MQTTFrame;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MQTTTest extends AbstractMQTTTest {

    private static final Logger LOG = LoggerFactory.getLogger(MQTTTest.class);

    @Test(timeout=300000)
    public void testPingKeepsInactivityMonitorAlive() throws Exception {
        addMQTTConnector();
        brokerService.start();
        MQTT mqtt = createMQTTConnection();
        mqtt.setKeepAlive((short)2);
        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        assertTrue("KeepAlive didn't work properly", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return connection.isConnected();
            }
        }));

        connection.disconnect();
    }

    @Test(timeout=300000)
    public void testTurnOffInactivityMonitor()throws Exception{
        addMQTTConnector("transport.useInactivityMonitor=false");
        brokerService.start();
        MQTT mqtt = createMQTTConnection();
        mqtt.setKeepAlive((short)2);
        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        assertTrue("KeepAlive didn't work properly", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return connection.isConnected();
            }
        }));

        connection.disconnect();
    }

    @Test(timeout=300000)
    public void testSubscribeMultipleTopics() throws Exception {
        byte[] payload = new byte[1024 * 32];
        for (int i = 0; i < payload.length; i++){
            payload[i] = '2';
        }

        addMQTTConnector();
        brokerService.start();
        MQTT mqtt = createMQTTConnection();
        mqtt.setClientId("MQTT-Client");
        mqtt.setCleanSession(false);

        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        Topic[] topics = {new Topic("TopicA", QoS.EXACTLY_ONCE), new Topic("TopicB", QoS.EXACTLY_ONCE)};
        connection.subscribe(topics);

        for (Topic topic : topics) {
            connection.publish(topic.name().toString(), payload, QoS.AT_LEAST_ONCE, false);
        }

        int received = 0;
        for (int i = 0; i < topics.length; ++i) {
            Message message = connection.receive();
            assertNotNull(message);
            received++;
            payload = message.getPayload();
            String messageContent = new String(payload);
            LOG.info("Received message from topic: " + message.getTopic() +
                     " Message content: " + messageContent);
            message.ack();
        }

        assertEquals("Should have received " + topics.length + " messages", topics.length, received);
    }

    @Test(timeout=300000)
    public void testReceiveMessageSentWhileOffline() throws Exception {
        byte[] payload = new byte[1024 * 32];
        for (int i = 0; i < payload.length; i++){
            payload[i] = '2';
        }

        int numberOfRuns = 100;
        int messagesPerRun = 2;

        addMQTTConnector("trace=true");
        brokerService.start();
        MQTT mqttPub = createMQTTConnection();
        mqttPub.setClientId("MQTT-Pub-Client");

        MQTT mqttSub = createMQTTConnection();
        mqttSub.setClientId("MQTT-Sub-Client");
        mqttSub.setCleanSession(false);

        final BlockingConnection connectionPub = mqttPub.blockingConnection();
        connectionPub.connect();

        BlockingConnection connectionSub = mqttSub.blockingConnection();
        connectionSub.connect();

        Topic[] topics = {new Topic("TopicA", QoS.EXACTLY_ONCE)};
        connectionSub.subscribe(topics);

        for (int i = 0; i < messagesPerRun; ++i) {
            connectionPub.publish(topics[0].name().toString(), payload, QoS.AT_LEAST_ONCE, false);
        }

        int received = 0;
        for (int i = 0; i < messagesPerRun; ++i) {
            Message message = connectionSub.receive(5, TimeUnit.SECONDS);
            assertNotNull(message);
            received++;
            payload = message.getPayload();
            String messageContent = new String(payload);
            LOG.info("Received message from topic: " + message.getTopic() +
                     " Message content: " + messageContent);
            message.ack();
        }
        connectionSub.disconnect();

        for(int j = 0; j < numberOfRuns; j++) {

            for (int i = 0; i < messagesPerRun; ++i) {
                connectionPub.publish(topics[0].name().toString(), payload, QoS.AT_LEAST_ONCE, false);
            }

            mqttSub = createMQTTConnection();
            mqttSub.setClientId("MQTT-Sub-Client");
            mqttSub.setCleanSession(false);

            connectionSub = mqttSub.blockingConnection();
            connectionSub.connect();
            connectionSub.subscribe(topics);

            for (int i = 0; i < messagesPerRun; ++i) {
                Message message = connectionSub.receive(5, TimeUnit.SECONDS);
                assertNotNull(message);
                received++;
                payload = message.getPayload();
                String messageContent = new String(payload);
                LOG.info("Received message from topic: " + message.getTopic() +
                         " Message content: " + messageContent);
                message.ack();
            }
            connectionSub.disconnect();
        }
        assertEquals("Should have received " + (messagesPerRun * (numberOfRuns + 1)) + " messages", (messagesPerRun * (numberOfRuns + 1)), received);
    }

    @Test(timeout=30000)
    public void testDefaultKeepAliveWhenClientSpecifiesZero() throws Exception {
        // default keep alive in milliseconds
        addMQTTConnector("transport.defaultKeepAlive=2000");
        brokerService.start();
        MQTT mqtt = createMQTTConnection();
        mqtt.setKeepAlive((short)0);
        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        assertTrue("KeepAlive didn't work properly", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return connection.isConnected();
            }
        }));
    }

    @Test(timeout=300000)
    public void testReuseConnection() throws Exception {
        addMQTTConnector();
        brokerService.start();

        MQTT mqtt = createMQTTConnection();
        mqtt.setClientId("Test-Client");

        {
            BlockingConnection connection = mqtt.blockingConnection();
            connection.connect();
            connection.disconnect();
            Thread.sleep(1000);
        }
        {
            BlockingConnection connection = mqtt.blockingConnection();
            connection.connect();
            connection.disconnect();
            Thread.sleep(1000);
        }
    }

    @Override
    protected String getProtocolScheme() {
        return "mqtt";
    }

    @Override
    protected void addMQTTConnector() throws Exception {
        addMQTTConnector();
    }

    @Override
    protected MQTTClientProvider getMQTTClientProvider() {
        return new FuseMQQTTClientProvider();
    }

    protected MQTT createMQTTConnection() throws Exception {
        return createMQTTConnection(null, false);
    }

    protected MQTT createMQTTConnection(String clientId, boolean clean) throws Exception {
        MQTT mqtt = new MQTT();
        mqtt.setConnectAttemptsMax(1);
        mqtt.setReconnectAttemptsMax(0);
        mqtt.setTracer(createTracer());
        if (clientId != null) {
            mqtt.setClientId(clientId);
        }
        mqtt.setCleanSession(clean);
        mqtt.setHost("localhost", mqttConnector.getConnectUri().getPort());
        // shut off connect retry
        return mqtt;
    }

    protected Tracer createTracer() {
        return new Tracer(){
            @Override
            public void onReceive(MQTTFrame frame) {
                LOG.info("Client Received:\n"+frame);
            }

            @Override
            public void onSend(MQTTFrame frame) {
                LOG.info("Client Sent:\n" + frame);
            }

            @Override
            public void debug(String message, Object... args) {
                LOG.info(message, args);
            }
        };
    }

}