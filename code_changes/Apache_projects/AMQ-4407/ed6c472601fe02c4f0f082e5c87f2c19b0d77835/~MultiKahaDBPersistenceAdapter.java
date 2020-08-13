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
package org.apache.activemq.store.kahadb;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerServiceAware;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.LocalTransactionId;
import org.apache.activemq.command.ProducerId;
import org.apache.activemq.command.TransactionId;
import org.apache.activemq.command.XATransactionId;
import org.apache.activemq.filter.AnyDestination;
import org.apache.activemq.filter.DestinationMap;
import org.apache.activemq.protobuf.Buffer;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.TransactionStore;
import org.apache.activemq.store.kahadb.data.KahaTransactionInfo;
import org.apache.activemq.store.kahadb.data.KahaXATransactionId;
import org.apache.activemq.usage.SystemUsage;
import org.apache.activemq.util.IOHelper;
import org.apache.activemq.util.IntrospectionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link org.apache.activemq.store.PersistenceAdapter}  that supports
 * distribution of destinations across multiple kahaDB persistence adapters
 *
 * @org.apache.xbean.XBean element="mKahaDB"
 */
public class MultiKahaDBPersistenceAdapter extends DestinationMap implements PersistenceAdapter, BrokerServiceAware {
    static final Logger LOG = LoggerFactory.getLogger(MultiKahaDBPersistenceAdapter.class);

    final static ActiveMQDestination matchAll = new AnyDestination(new ActiveMQDestination[]{new ActiveMQQueue(">"), new ActiveMQTopic(">")});
    final int LOCAL_FORMAT_ID_MAGIC = Integer.valueOf(System.getProperty("org.apache.activemq.store.kahadb.MultiKahaDBTransactionStore.localXaFormatId", "61616"));

    BrokerService brokerService;
    List<KahaDBPersistenceAdapter> adapters = new LinkedList<KahaDBPersistenceAdapter>();
    private File directory = new File(IOHelper.getDefaultDataDirectory() + File.separator + "mKahaDB");

    MultiKahaDBTransactionStore transactionStore = new MultiKahaDBTransactionStore(this);

    // all local store transactions are XA, 2pc if more than one adapter involved
    TransactionIdTransformer transactionIdTransformer = new TransactionIdTransformer() {
        @Override
        public KahaTransactionInfo transform(TransactionId txid) {
            if (txid == null) {
                return null;
            }
            KahaTransactionInfo rc = new KahaTransactionInfo();
            KahaXATransactionId kahaTxId = new KahaXATransactionId();
            if (txid.isLocalTransaction()) {
                LocalTransactionId t = (LocalTransactionId) txid;
                kahaTxId.setBranchQualifier(new Buffer(Long.toString(t.getValue()).getBytes(Charset.forName("utf-8"))));
                kahaTxId.setGlobalTransactionId(new Buffer(t.getConnectionId().getValue().getBytes(Charset.forName("utf-8"))));
                kahaTxId.setFormatId(LOCAL_FORMAT_ID_MAGIC);
            } else {
                XATransactionId t = (XATransactionId) txid;
                kahaTxId.setBranchQualifier(new Buffer(t.getBranchQualifier()));
                kahaTxId.setGlobalTransactionId(new Buffer(t.getGlobalTransactionId()));
                kahaTxId.setFormatId(t.getFormatId());
            }
            rc.setXaTransactionId(kahaTxId);
            return rc;
        }
    };

    /**
     * Sets the  FilteredKahaDBPersistenceAdapter entries
     *
     * @org.apache.xbean.ElementType class="org.apache.activemq.store.kahadb.FilteredKahaDBPersistenceAdapter"
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void setFilteredPersistenceAdapters(List entries) {
        for (Object entry : entries) {
            FilteredKahaDBPersistenceAdapter filteredAdapter = (FilteredKahaDBPersistenceAdapter) entry;
            KahaDBPersistenceAdapter adapter = filteredAdapter.getPersistenceAdapter();
            if (filteredAdapter.getDestination() == null) {
                filteredAdapter.setDestination(matchAll);
            }

            if (filteredAdapter.isPerDestination()) {
                configureDirectory(adapter, null);
                // per destination adapters will be created on demand or during recovery
                continue;
            } else {
                configureDirectory(adapter, nameFromDestinationFilter(filteredAdapter.getDestination()));
            }

            configureAdapter(adapter);
            adapters.add(adapter);
        }
        super.setEntries(entries);
    }

    private String nameFromDestinationFilter(ActiveMQDestination destination) {
        if (destination.getQualifiedName().length() > IOHelper.getMaxFileNameLength()) {
            LOG.warn("Destination name is longer than 'MaximumFileNameLength' system property, " +
                     "potential problem with recovery can result from name truncation.");
        }

        return IOHelper.toFileSystemSafeName(destination.getQualifiedName());
    }

    public boolean isLocalXid(TransactionId xid) {
        return xid instanceof XATransactionId &&
                ((XATransactionId)xid).getFormatId() == LOCAL_FORMAT_ID_MAGIC;
    }

    @Override
    public void beginTransaction(ConnectionContext context) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public void checkpoint(final boolean sync) throws IOException {
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.checkpoint(sync);
        }
    }

    @Override
    public void commitTransaction(ConnectionContext context) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public MessageStore createQueueMessageStore(ActiveMQQueue destination) throws IOException {
        PersistenceAdapter persistenceAdapter = getMatchingPersistenceAdapter(destination);
        return transactionStore.proxy(persistenceAdapter.createTransactionStore(), persistenceAdapter.createQueueMessageStore(destination));
    }

    private PersistenceAdapter getMatchingPersistenceAdapter(ActiveMQDestination destination) {
        Object result = this.chooseValue(destination);
        if (result == null) {
            throw new RuntimeException("No matching persistence adapter configured for destination: " + destination + ", options:" + adapters);
        }
        FilteredKahaDBPersistenceAdapter filteredAdapter = (FilteredKahaDBPersistenceAdapter) result;
        if (filteredAdapter.getDestination() == matchAll && filteredAdapter.isPerDestination()) {
            result = addAdapter(filteredAdapter, destination);
            startAdapter(((FilteredKahaDBPersistenceAdapter) result).getPersistenceAdapter(), destination.getQualifiedName());
            if (LOG.isTraceEnabled()) {
                LOG.info("created per destination adapter for: " + destination  + ", " + result);
            }
        }
        return ((FilteredKahaDBPersistenceAdapter) result).getPersistenceAdapter();
    }

    private void startAdapter(KahaDBPersistenceAdapter kahaDBPersistenceAdapter, String destination) {
        try {
            kahaDBPersistenceAdapter.start();
        } catch (Exception e) {
            RuntimeException detail = new RuntimeException("Failed to start per destination persistence adapter for destination: " + destination + ", options:" + adapters, e);
            LOG.error(detail.toString(), e);
            throw detail;
        }
    }

    private void stopAdapter(KahaDBPersistenceAdapter kahaDBPersistenceAdapter, String destination) {
        try {
            kahaDBPersistenceAdapter.stop();
        } catch (Exception e) {
            RuntimeException detail = new RuntimeException("Failed to stop per destination persistence adapter for destination: " + destination + ", options:" + adapters, e);
            LOG.error(detail.toString(), e);
            throw detail;
        }
    }

    @Override
    public TopicMessageStore createTopicMessageStore(ActiveMQTopic destination) throws IOException {
        PersistenceAdapter persistenceAdapter = getMatchingPersistenceAdapter(destination);
        return transactionStore.proxy(persistenceAdapter.createTransactionStore(), persistenceAdapter.createTopicMessageStore(destination));
    }

    @Override
    public TransactionStore createTransactionStore() throws IOException {
        return transactionStore;
    }

    @Override
    public void deleteAllMessages() throws IOException {
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.deleteAllMessages();
        }
        transactionStore.deleteAllMessages();
        IOHelper.deleteChildren(getDirectory());
    }

    @Override
    public Set<ActiveMQDestination> getDestinations() {
        Set<ActiveMQDestination> results = new HashSet<ActiveMQDestination>();
        for (PersistenceAdapter persistenceAdapter : adapters) {
            results.addAll(persistenceAdapter.getDestinations());
        }
        return results;
    }

    @Override
    public long getLastMessageBrokerSequenceId() throws IOException {
        long maxId = -1;
        for (PersistenceAdapter persistenceAdapter : adapters) {
            maxId = Math.max(maxId, persistenceAdapter.getLastMessageBrokerSequenceId());
        }
        return maxId;
    }

    @Override
    public long getLastProducerSequenceId(ProducerId id) throws IOException {
        long maxId = -1;
        for (PersistenceAdapter persistenceAdapter : adapters) {
            maxId = Math.max(maxId, persistenceAdapter.getLastProducerSequenceId(id));
        }
        return maxId;
    }

    @Override
    public void removeQueueMessageStore(ActiveMQQueue destination) {
        PersistenceAdapter adapter = getMatchingPersistenceAdapter(destination);
        if (adapter instanceof KahaDBPersistenceAdapter) {
            adapter.removeQueueMessageStore(destination);
            removeMessageStore((KahaDBPersistenceAdapter)adapter, destination);
            removeAll(destination);
        }
    }

    @Override
    public void removeTopicMessageStore(ActiveMQTopic destination) {
        PersistenceAdapter adapter = getMatchingPersistenceAdapter(destination);
        if (adapter instanceof KahaDBPersistenceAdapter) {
            adapter.removeTopicMessageStore(destination);
            removeMessageStore((KahaDBPersistenceAdapter)adapter, destination);
            removeAll(destination);
        }
    }

    private void removeMessageStore(KahaDBPersistenceAdapter adapter, ActiveMQDestination destination) {
        if (adapter.getDestinations().isEmpty()) {
            stopAdapter(adapter, destination.toString());
            File adapterDir = adapter.getDirectory();
            if (adapterDir != null) {
                if (IOHelper.deleteFile(adapterDir)) {
                    if (LOG.isTraceEnabled()) {
                        LOG.info("deleted per destination adapter directory for: " + destination);
                    }
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.info("failed to deleted per destination adapter directory for: " + destination);
                    }
                }
            }
        }
    }

    @Override
    public void rollbackTransaction(ConnectionContext context) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public void setBrokerName(String brokerName) {
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.setBrokerName(brokerName);
        }
    }

    @Override
    public void setUsageManager(SystemUsage usageManager) {
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.setUsageManager(usageManager);
        }
    }

    @Override
    public long size() {
        long size = 0;
        for (PersistenceAdapter persistenceAdapter : adapters) {
            size += persistenceAdapter.size();
        }
        return size;
    }

    @Override
    public void start() throws Exception {
        Object result = this.chooseValue(matchAll);
        if (result != null) {
            FilteredKahaDBPersistenceAdapter filteredAdapter = (FilteredKahaDBPersistenceAdapter) result;
            if (filteredAdapter.getDestination() == matchAll && filteredAdapter.isPerDestination()) {
                findAndRegisterExistingAdapters(filteredAdapter);
            }
        }
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.start();
        }
    }

    private void findAndRegisterExistingAdapters(FilteredKahaDBPersistenceAdapter template) {
        FileFilter destinationNames = new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().startsWith("queue#") || file.getName().startsWith("topic#");
            }
        };
        File[] candidates = template.getPersistenceAdapter().getDirectory().listFiles(destinationNames);
        if (candidates != null) {
            for (File candidate : candidates) {
                registerExistingAdapter(template, candidate);
            }
        }
    }

    private void registerExistingAdapter(FilteredKahaDBPersistenceAdapter filteredAdapter, File candidate) {
        KahaDBPersistenceAdapter adapter = adapterFromTemplate(filteredAdapter.getPersistenceAdapter(), candidate.getName());
        startAdapter(adapter, candidate.getName());
        Set<ActiveMQDestination> destinations = adapter.getDestinations();
        if (destinations.size() != 0) {
            registerAdapter(adapter, destinations.toArray(new ActiveMQDestination[]{})[0]);
        } else {
            stopAdapter(adapter, candidate.getName());
        }
    }

    private FilteredKahaDBPersistenceAdapter addAdapter(FilteredKahaDBPersistenceAdapter filteredAdapter, ActiveMQDestination destination) {
        KahaDBPersistenceAdapter adapter = adapterFromTemplate(filteredAdapter.getPersistenceAdapter(), nameFromDestinationFilter(destination));
        return registerAdapter(adapter, destination);
    }

    private KahaDBPersistenceAdapter adapterFromTemplate(KahaDBPersistenceAdapter template, String destinationName) {
        KahaDBPersistenceAdapter adapter = kahaDBFromTemplate(template);
        configureAdapter(adapter);
        configureDirectory(adapter, destinationName);
        return adapter;
    }

    private void configureDirectory(KahaDBPersistenceAdapter adapter, String fileName) {
        File directory = null;
        if (MessageDatabase.DEFAULT_DIRECTORY.equals(adapter.getDirectory())) {
            // not set so inherit from mkahadb
            directory = getDirectory();
        } else {
            directory = adapter.getDirectory();
        }
        if (fileName != null) {
            directory = new File(directory, fileName);
        }
        adapter.setDirectory(directory);
    }

    private FilteredKahaDBPersistenceAdapter registerAdapter(KahaDBPersistenceAdapter adapter, ActiveMQDestination destination) {
        adapters.add(adapter);
        FilteredKahaDBPersistenceAdapter result = new FilteredKahaDBPersistenceAdapter(destination, adapter);
        put(destination, result);
        return result;
    }

    private void configureAdapter(KahaDBPersistenceAdapter adapter) {
        // need a per store factory that will put the store in the branch qualifier to disiambiguate xid mbeans
        adapter.getStore().setTransactionIdTransformer(transactionIdTransformer);
        adapter.setBrokerService(getBrokerService());
    }

    private KahaDBPersistenceAdapter kahaDBFromTemplate(KahaDBPersistenceAdapter template) {
        Map<String, Object> configuration = new HashMap<String, Object>();
        IntrospectionSupport.getProperties(template, configuration, null);
        KahaDBPersistenceAdapter adapter = new KahaDBPersistenceAdapter();
        IntrospectionSupport.setProperties(adapter, configuration);
        return adapter;
    }

    @Override
    public void stop() throws Exception {
        for (PersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.stop();
        }
    }

    @Override
    public File getDirectory() {
        return this.directory;
    }

    @Override
    public void setDirectory(File directory) {
        this.directory = directory;
    }

    @Override
    public void setBrokerService(BrokerService brokerService) {
        for (KahaDBPersistenceAdapter persistenceAdapter : adapters) {
            persistenceAdapter.setBrokerService(brokerService);
        }
        this.brokerService = brokerService;
    }

    public BrokerService getBrokerService() {
        return brokerService;
    }

    public void setTransactionStore(MultiKahaDBTransactionStore transactionStore) {
        this.transactionStore = transactionStore;
    }

    /**
     * Set the max file length of the transaction journal
     * When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can
     * be used
     *
     * @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryIntPropertyEditor"
     */
    public void setJournalMaxFileLength(int maxFileLength) {
        transactionStore.setJournalMaxFileLength(maxFileLength);
    }

    public int getJournalMaxFileLength() {
        return transactionStore.getJournalMaxFileLength();
    }

    /**
     * Set the max write batch size of  the transaction journal
     * When set using Xbean, values of the form "20 Mb", "1024kb", and "1g" can
     * be used
     *
     * @org.apache.xbean.Property propertyEditor="org.apache.activemq.util.MemoryIntPropertyEditor"
     */
    public void setJournalWriteBatchSize(int journalWriteBatchSize) {
        transactionStore.setJournalMaxWriteBatchSize(journalWriteBatchSize);
    }

    public int getJournalWriteBatchSize() {
        return transactionStore.getJournalMaxWriteBatchSize();
    }

    @Override
    public String toString() {
        String path = getDirectory() != null ? getDirectory().getAbsolutePath() : "DIRECTORY_NOT_SET";
        return "MultiKahaDBPersistenceAdapter[" + path + "]" + adapters;
    }
}
