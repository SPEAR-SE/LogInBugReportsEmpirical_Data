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

package org.apache.hadoop.yarn.server.resourcemanager.nodelabels;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.NodeAttribute;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.nodelabels.AttributeValue;
import org.apache.hadoop.yarn.nodelabels.NodeAttributesManager;
import org.apache.hadoop.yarn.nodelabels.NodeLabelUtil;
import org.apache.hadoop.yarn.nodelabels.RMNodeAttribute;
import org.apache.hadoop.yarn.nodelabels.StringAttributeValue;
import org.apache.hadoop.yarn.server.api.protocolrecords.AttributeMappingOperationType;

/**
 * Manager holding the attributes to Labels.
 */
public class NodeAttributesManagerImpl extends NodeAttributesManager {
  protected static final Log LOG =
      LogFactory.getLog(NodeAttributesManagerImpl.class);
  /**
   * If a user doesn't specify value for a label, then empty string is
   * considered as default.
   */
  public static final String EMPTY_ATTRIBUTE_VALUE = "";

  private Dispatcher dispatcher;

  // TODO may be we can have a better collection here.
  // this will be updated to get the attributeName to NM mapping
  private ConcurrentHashMap<NodeAttribute, RMNodeAttribute> clusterAttributes =
      new ConcurrentHashMap<>();

  // hostname -> (Map (attributeName -> NodeAttribute))
  // Instead of NodeAttribute, plan to have it in future as AttributeValue
  // AttributeValue
  // / \
  // StringNodeAttributeValue LongAttributeValue
  // and convert the configured value to the specific type so that the
  // expression evaluations are faster
  private ConcurrentMap<String, Host> nodeCollections =
      new ConcurrentHashMap<>();

  private final ReadLock readLock;
  private final WriteLock writeLock;

  public NodeAttributesManagerImpl() {
    super("NodeAttributesManagerImpl");
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    readLock = lock.readLock();
    writeLock = lock.writeLock();
  }

  protected void initDispatcher(Configuration conf) {
    // create async handler
    dispatcher = new AsyncDispatcher("AttributeNodeLabelsManager dispatcher");
    AsyncDispatcher asyncDispatcher = (AsyncDispatcher) dispatcher;
    asyncDispatcher.init(conf);
    asyncDispatcher.setDrainEventsOnStop();
  }

  protected void startDispatcher() {
    // start dispatcher
    AsyncDispatcher asyncDispatcher = (AsyncDispatcher) dispatcher;
    asyncDispatcher.start();
  }

  @Override
  protected void serviceStart() throws Exception {
    initNodeAttributeStore(getConfig());
    // init dispatcher only when service start, because recover will happen in
    // service init, we don't want to trigger any event handling at that time.
    initDispatcher(getConfig());

    if (null != dispatcher) {
      dispatcher.register(NodeAttributesStoreEventType.class,
          new ForwardingEventHandler());
    }

    startDispatcher();
    super.serviceStart();
  }

  protected void initNodeAttributeStore(Configuration conf) throws Exception {
    // TODO to generalize and make use of the FileSystemNodeLabelsStore
  }

  private void internalUpdateAttributesOnNodes(
      Map<String, Map<NodeAttribute, AttributeValue>> nodeAttributeMapping,
      AttributeMappingOperationType op,
      Map<NodeAttribute, RMNodeAttribute> newAttributesToBeAdded) {
    try {
      writeLock.lock();

      // shows node->attributes Mapped as part of this operation.
      StringBuilder logMsg = new StringBuilder(op.name());
      logMsg.append(" attributes on nodes:");
      // do update labels from nodes
      for (Entry<String, Map<NodeAttribute, AttributeValue>> entry : nodeAttributeMapping
          .entrySet()) {
        String nodeHost = entry.getKey();
        Map<NodeAttribute, AttributeValue> attributes = entry.getValue();

        Host node = nodeCollections.get(nodeHost);
        if (node == null) {
          node = new Host(nodeHost);
        }
        switch (op) {
        case REMOVE:
          removeNodeFromAttributes(nodeHost, attributes.keySet());
          node.removeAttributes(attributes);
          break;
        case ADD:
          clusterAttributes.putAll(newAttributesToBeAdded);
          addNodeToAttribute(nodeHost, attributes);
          node.addAttributes(attributes);
          break;
        case REPLACE:
          clusterAttributes.putAll(newAttributesToBeAdded);
          replaceNodeToAttribute(nodeHost, node.getAttributes(), attributes);
          node.replaceAttributes(attributes);
          break;
        default:
          break;
        }
        logMsg.append(" NM = ");
        logMsg.append(entry.getKey());
        logMsg.append(", attributes=[ ");
        logMsg.append(StringUtils.join(entry.getValue().entrySet(), ","));
        logMsg.append("] ,");
      }

      LOG.info(logMsg);

      if (null != dispatcher) {
        dispatcher.getEventHandler()
            .handle(new NodeAttributesStoreEvent(nodeAttributeMapping, op));
      }

    } finally {
      writeLock.unlock();
    }
  }

  private void removeNodeFromAttributes(String nodeHost,
      Set<NodeAttribute> attributeMappings) {
    for (NodeAttribute attribute : attributeMappings) {
      clusterAttributes.get(attribute).removeNode(nodeHost);
    }
  }

  private void addNodeToAttribute(String nodeHost,
      Map<NodeAttribute, AttributeValue> attributeMappings) {
    for (NodeAttribute attribute : attributeMappings.keySet()) {
      clusterAttributes.get(attribute).addNode(nodeHost);
    }
  }

  private void replaceNodeToAttribute(String nodeHost,
      Map<NodeAttribute, AttributeValue> oldAttributeMappings,
      Map<NodeAttribute, AttributeValue> newAttributeMappings) {
    if (oldAttributeMappings != null) {
      removeNodeFromAttributes(nodeHost, oldAttributeMappings.keySet());
    }
    addNodeToAttribute(nodeHost, newAttributeMappings);
  }

  /**
   * @param nodeAttributeMapping
   * @param newAttributesToBeAdded
   * @param isRemoveOperation : to indicate whether its a remove operation.
   * @return Map<String, Map<NodeAttribute, AttributeValue>>, node -> Map(
   *         NodeAttribute -> AttributeValue)
   * @throws IOException : on invalid mapping in the current request or against
   *           already existing NodeAttributes.
   */
  protected Map<String, Map<NodeAttribute, AttributeValue>> validate(
      Map<String, Set<NodeAttribute>> nodeAttributeMapping,
      Map<NodeAttribute, RMNodeAttribute> newAttributesToBeAdded,
      boolean isRemoveOperation) throws IOException {
    Map<String, Map<NodeAttribute, AttributeValue>> nodeToAttributesMap =
        new TreeMap<>();
    Map<NodeAttribute, AttributeValue> attributesValues;
    Set<Entry<String, Set<NodeAttribute>>> entrySet =
        nodeAttributeMapping.entrySet();
    for (Entry<String, Set<NodeAttribute>> nodeToAttrMappingEntry : entrySet) {
      attributesValues = new HashMap<>();
      String node = nodeToAttrMappingEntry.getKey().trim();
      if (nodeToAttrMappingEntry.getValue().isEmpty()) {
        // no attributes to map mostly remove operation
        continue;
      }

      // validate for attributes
      for (NodeAttribute attribute : nodeToAttrMappingEntry.getValue()) {
        String attributeName = attribute.getAttributeName().trim();
        NodeLabelUtil.checkAndThrowLabelName(attributeName);
        NodeLabelUtil
            .checkAndThrowAttributePrefix(attribute.getAttributePrefix());

        // ensure trimmed values are set back
        attribute.setAttributeName(attributeName);
        attribute.setAttributePrefix(attribute.getAttributePrefix().trim());

        // verify for type against prefix/attributeName
        if (validateForAttributeTypeMismatch(isRemoveOperation, attribute,
            newAttributesToBeAdded)) {
          newAttributesToBeAdded.put(attribute,
              new RMNodeAttribute(attribute));
        }
        // TODO type based value setting needs to be done using a factory
        StringAttributeValue value = new StringAttributeValue();
        value.validateAndInitializeValue(
            normalizeAttributeValue(attribute.getAttributeValue()));
        attributesValues.put(attribute, value);
      }
      nodeToAttributesMap.put(node, attributesValues);
    }
    return nodeToAttributesMap;
  }

  /**
   *
   * @param isRemoveOperation
   * @param attribute
   * @param newAttributes
   * @return Whether its a new Attribute added
   * @throws IOException
   */
  private boolean validateForAttributeTypeMismatch(boolean isRemoveOperation,
      NodeAttribute attribute,
      Map<NodeAttribute, RMNodeAttribute> newAttributes)
      throws IOException {
    if (isRemoveOperation && !clusterAttributes.containsKey(attribute)) {
      // no need to validate anything as its remove operation and attribute
      // doesn't exist.
      return false; // no need to add as its remove operation
    } else {
      // already existing or attribute is mapped to another Node in the
      // current command, then check whether the attribute type is matching
      NodeAttribute existingAttribute =
          (clusterAttributes.containsKey((attribute))
              ? clusterAttributes.get(attribute).getAttribute()
              : (newAttributes.containsKey(attribute)
                  ? newAttributes.get(attribute).getAttribute()
                  : null));
      if (existingAttribute == null) {
        return true;
      } else if (existingAttribute.getAttributeType() != attribute
          .getAttributeType()) {
        throw new IOException("Attribute name - type is not matching with "
            + "already configured mapping for the attribute "
            + attribute.getAttributeName() + " existing : "
            + existingAttribute.getAttributeType() + ", new :"
            + attribute.getAttributeType());
      }
      return false;
    }
  }

  protected String normalizeAttributeValue(String value) {
    if (value != null) {
      return value.trim();
    }
    return EMPTY_ATTRIBUTE_VALUE;
  }

  @Override
  public Set<NodeAttribute> getClusterNodeAttributes(Set<String> prefix) {
    Set<NodeAttribute> attributes = new HashSet<>();
    try {
      readLock.lock();
      attributes.addAll(clusterAttributes.keySet());
    } finally {
      readLock.unlock();
    }
    if (prefix != null && prefix.isEmpty()) {
      Iterator<NodeAttribute> iterator = attributes.iterator();
      while (iterator.hasNext()) {
        NodeAttribute attribute = iterator.next();
        if (!prefix.contains(attribute.getAttributePrefix())) {
          iterator.remove();
        }
      }
    }
    return attributes;
  }

  // TODO need to handle as part of REST patch.
  /*
   * @Override public Map<NodeAttribute, Set<String>> getAttributesToNodes(
   * Set<NodeAttribute> attributes) { try { readLock.lock(); boolean
   * fetchAllAttributes = (attributes == null || attributes.isEmpty());
   * Map<NodeAttribute, Set<String>> attributesToNodes = new HashMap<>(); for
   * (Entry<NodeAttribute, RMAttributeNodeLabel> attributeEntry :
   * attributeCollections .entrySet()) { if (fetchAllAttributes ||
   * attributes.contains(attributeEntry.getKey())) {
   * attributesToNodes.put(attributeEntry.getKey(),
   * attributeEntry.getValue().getAssociatedNodeIds()); } } return
   * attributesToNodes; } finally { readLock.unlock(); } }
   */

  public Resource getResourceByAttribute(NodeAttribute attribute) {
    try {
      readLock.lock();
      return clusterAttributes.containsKey(attribute)
          ? clusterAttributes.get(attribute).getResource()
          : Resource.newInstance(0, 0);
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Map<NodeAttribute, AttributeValue> getAttributesForNode(
      String hostName) {
    try {
      readLock.lock();
      return nodeCollections.containsKey(hostName)
          ? nodeCollections.get(hostName).getAttributes()
          : new HashMap<>();
    } finally {
      readLock.unlock();
    }
  }

  public void activateNode(NodeId nodeId, Resource resource) {
    try {
      writeLock.lock();
      String hostName = nodeId.getHost();
      Host host = nodeCollections.get(hostName);
      if (host == null) {
        host = new Host(hostName);
        nodeCollections.put(hostName, host);
      }
      host.activateNode(resource);
      for (NodeAttribute attribute : host.getAttributes().keySet()) {
        clusterAttributes.get(attribute).removeNode(resource);
      }
    } finally {
      writeLock.unlock();
    }
  }

  public void deactivateNode(NodeId nodeId) {
    try {
      writeLock.lock();
      Host host = nodeCollections.get(nodeId.getHost());
      for (NodeAttribute attribute : host.getAttributes().keySet()) {
        clusterAttributes.get(attribute).removeNode(host.getResource());
      }
      host.deactivateNode();
    } finally {
      writeLock.unlock();
    }
  }

  public void updateNodeResource(NodeId node, Resource newResource) {
    deactivateNode(node);
    activateNode(node, newResource);
  }

  /**
   * A <code>Host</code> can have multiple <code>Node</code>s.
   */
  public static class Host {
    private String hostName;
    private Map<NodeAttribute, AttributeValue> attributes;
    private Resource resource;
    private boolean isActive;

    private Map<NodeAttribute, AttributeValue> getAttributes() {
      return attributes;
    }

    public void setAttributes(Map<NodeAttribute, AttributeValue> attributes) {
      this.attributes = attributes;
    }

    public void removeAttributes(
        Map<NodeAttribute, AttributeValue> attributesMapping) {
      for (NodeAttribute attribute : attributesMapping.keySet()) {
        this.attributes.remove(attribute);
      }
    }

    public void replaceAttributes(
        Map<NodeAttribute, AttributeValue> attributesMapping) {
      this.attributes.clear();
      this.attributes.putAll(attributesMapping);
    }

    public void addAttributes(
        Map<NodeAttribute, AttributeValue> attributesMapping) {
      this.attributes.putAll(attributesMapping);
    }

    public Resource getResource() {
      return resource;
    }

    public void setResource(Resource resourceParam) {
      this.resource = resourceParam;
    }

    public boolean isActive() {
      return isActive;
    }

    public void deactivateNode() {
      this.isActive = false;
      this.resource = Resource.newInstance(0, 0);
    }

    public void activateNode(Resource r) {
      this.isActive = true;
      this.resource = r;
    }

    public String getHostName() {
      return hostName;
    }

    public void setHostName(String hostName) {
      this.hostName = hostName;
    }

    public Host(String hostName) {
      this(hostName, new HashMap<NodeAttribute, AttributeValue>());
    }

    public Host(String hostName,
        Map<NodeAttribute, AttributeValue> attributes) {
      this(hostName, attributes, Resource.newInstance(0, 0), false);
    }

    public Host(String hostName, Map<NodeAttribute, AttributeValue> attributes,
        Resource resource, boolean isActive) {
      super();
      this.attributes = attributes;
      this.resource = resource;
      this.isActive = isActive;
      this.hostName = hostName;
    }
  }

  private final class ForwardingEventHandler
      implements EventHandler<NodeAttributesStoreEvent> {

    @Override
    public void handle(NodeAttributesStoreEvent event) {
      handleStoreEvent(event);
    }
  }

  // Dispatcher related code
  protected void handleStoreEvent(NodeAttributesStoreEvent event) {
    // TODO Need to extend the File
  }

  @Override
  public void replaceNodeAttributes(
      Map<String, Set<NodeAttribute>> nodeAttributeMapping) throws IOException {
    processMapping(nodeAttributeMapping, AttributeMappingOperationType.REPLACE);
  }

  @Override
  public void addNodeAttributes(
      Map<String, Set<NodeAttribute>> nodeAttributeMapping) throws IOException {
    processMapping(nodeAttributeMapping, AttributeMappingOperationType.ADD);
  }

  @Override
  public void removeNodeAttributes(
      Map<String, Set<NodeAttribute>> nodeAttributeMapping) throws IOException {
    processMapping(nodeAttributeMapping, AttributeMappingOperationType.REMOVE);
  }

  private void processMapping(
      Map<String, Set<NodeAttribute>> nodeAttributeMapping,
      AttributeMappingOperationType mappingType) throws IOException {
    Map<NodeAttribute, RMNodeAttribute> newAttributesToBeAdded =
        new HashMap<>();
    Map<String, Map<NodeAttribute, AttributeValue>> validMapping =
        validate(nodeAttributeMapping, newAttributesToBeAdded, false);

    internalUpdateAttributesOnNodes(validMapping, mappingType,
        newAttributesToBeAdded);
  }
}
