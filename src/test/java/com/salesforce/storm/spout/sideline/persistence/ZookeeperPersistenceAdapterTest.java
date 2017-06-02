package com.salesforce.storm.spout.sideline.persistence;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.salesforce.storm.spout.sideline.ConsumerPartition;
import com.salesforce.storm.spout.sideline.Tools;
import com.salesforce.storm.spout.sideline.config.SidelineSpoutConfig;
import com.salesforce.storm.spout.sideline.kafka.ConsumerState;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequest;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequestIdentifier;
import com.salesforce.storm.spout.sideline.trigger.SidelineType;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.json.simple.JSONValue;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests our Zookeeper Persistence layer.
 */
public class ZookeeperPersistenceAdapterTest {
    /**
     * By default, no exceptions should be thrown.
     */
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    // For logging within test.
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperPersistenceAdapterTest.class);

    // An internal zookeeper server used for testing.
    private static TestingServer zkServer;

    /**
     * This gets run before all test methods in class.
     * It stands up an internal zookeeper server that is shared for all test methods in this class.
     */
    @BeforeClass
    public static void setupZkServer() throws Exception {
        InstanceSpec zkInstanceSpec = new InstanceSpec(null, -1, -1, -1, true, -1, -1, 1000);
        zkServer = new TestingServer(zkInstanceSpec, true);
    }

    /**
     * After running all the test methods in this class, destroy our internal zk server.
     */
    @AfterClass
    public static void destroyZkServer() throws Exception {
        zkServer.stop();
        zkServer.close();
    }

    /**
     * Tests that if you're missing the configuration item for ZkRootNode it will throw
     * an IllegalStateException.
     */
    @Test
    public void testOpenMissingConfigForZkRootNode() {
        final List<String> inputHosts = Lists.newArrayList("localhost:2181", "localhost2:2183");

        // Create our config
        final Map topologyConfig = createDefaultConfig(inputHosts, null, null);

        // Create instance and open it.
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.open(topologyConfig);
    }

    /**
     * Tests that the constructor does what we think.
     */
    @Test
    public void testOpen() {
        final int partitionId = 1;
        final String expectedZkConnectionString = "localhost:2181,localhost2:2183";
        final List<String> inputHosts = Lists.newArrayList("localhost:2181", "localhost2:2183");
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();
        final String expectedZkRoot = configuredZkRoot + "/" + configuredConsumerPrefix;
        final String expectedConsumerId = configuredConsumerPrefix + ":MyConsumerId";
        final String expectedZkConsumerStatePath = expectedZkRoot + "/consumers/" + expectedConsumerId + "/" + String.valueOf(partitionId);
        final String expectedZkRequestStatePath = expectedZkRoot + "/requests/" + expectedConsumerId + "/" + String.valueOf(partitionId);

        // Create our config
        final Map topologyConfig = createDefaultConfig(inputHosts, configuredZkRoot, configuredConsumerPrefix);

        // Create instance and open it.
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Validate
        assertEquals("Unexpected zk connection string", expectedZkConnectionString, persistenceAdapter.getZkConnectionString());
        assertEquals("Unexpected zk root string", expectedZkRoot, persistenceAdapter.getZkRoot());

        // Validate that getZkXXXXStatePath returns the expected value
        assertEquals("Unexpected zkConsumerStatePath returned", expectedZkConsumerStatePath, persistenceAdapter.getZkConsumerStatePath(expectedConsumerId, partitionId));
        assertEquals("Unexpected zkRequestStatePath returned", expectedZkRequestStatePath, persistenceAdapter.getZkRequestStatePath(expectedConsumerId, partitionId));

        // Close everyone out
        persistenceAdapter.close();
    }

    /**
     * Does an end to end test of this persistence layer for storing/retrieving Consumer state.
     * 1 - Sets up an internal Zk server
     * 2 - Connects to it
     * 3 - writes state data to it
     * 4 - reads state data from it
     * 5 - compares that its valid.
     */
    @Test
    public void testEndToEndConsumerStatePersistence() throws InterruptedException {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        final String consumerId = "myConsumer" + Clock.systemUTC().millis();
        final int partitionId1 = 1;
        final int partitionId2 = 2;

        // Create our config
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);

        // Create instance and open it.
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        final Long offset1 = 100L;

        persistenceAdapter.persistConsumerState(consumerId, partitionId1, offset1);

        final Long actual1 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId1);

        // Validate result
        assertNotNull("Got an object back", actual1);
        assertEquals(offset1, actual1);

        // Close outs
        persistenceAdapter.close();

        // Create new instance, reconnect to ZK, make sure we can still read it out with our new instance.
        persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Re-retrieve, should still be there.
        final Long actual2 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId1);

        assertNotNull("Got an object back", actual2);
        assertEquals(offset1, actual2);

        final Long offset2 = 101L;

        // Update our existing state
        persistenceAdapter.persistConsumerState(consumerId, partitionId1, offset2);

        // Re-retrieve, should still be there.
        final Long actual3 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId1);

        assertNotNull("Got an object back", actual3);
        assertEquals(offset2, actual3);

        final Long actual4 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId2);

        assertNull("Partition hasn't been set yet", actual4);

        final Long offset3 = 102L;

        persistenceAdapter.persistConsumerState(consumerId, partitionId2, offset3);

        final Long actual5 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId2);

        assertNotNull("Got an object back", actual5);
        assertEquals(offset3, actual5);

        // Re-retrieve, should still be there.
        final Long actual6 = persistenceAdapter.retrieveConsumerState(consumerId, partitionId1);

        assertNotNull("Got an object back", actual3);
        assertEquals(offset2, actual6);

        // Close outs
        persistenceAdapter.close();
    }

    /**
     * Tests end to end persistence of Consumer state, using an independent ZK client to verify things are written
     * into zookeeper as we expect.
     *
     * We do the following:
     * 1 - Connect to ZK and ensure that the zkRootNode path does NOT exist in Zookeeper yet
     *     If it does, we'll clean it up.
     * 2 - Create an instance of our state manager passing an expected root node
     * 3 - Attempt to persist some state
     * 4 - Go into zookeeper directly and verify the state got written under the appropriate prefix path (zkRootNode).
     * 5 - Read the stored value directly out of zookeeper and verify the right thing got written.
     */
    @Test
    public void testEndToEndConsumerStatePersistenceWithValidationWithIndependentZkClient() throws IOException, KeeperException, InterruptedException {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        // Define our ZK Root Node
        final String zkRootNodePath = configuredZkRoot + "/" + configuredConsumerPrefix;
        final String zkConsumersRootNodePath = zkRootNodePath + "/consumers";
        final String consumerId = "MyConsumer" + Clock.systemUTC().millis();
        final int partitionId = 1;

        // 1 - Connect to ZK directly
        ZooKeeper zookeeperClient = new ZooKeeper(zkServer.getConnectString(), 6000, event -> logger.info("Got event {}", event));

        // Ensure that our node does not exist before we run test,
        // Validate that our assumption that this node does not exist!
        Stat doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
        // We need to clean up
        if (doesNodeExist != null) {
            zookeeperClient.delete(zkRootNodePath, doesNodeExist.getVersion());

            // Check again
            doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
            if (doesNodeExist != null) {
                throw new RuntimeException("Failed to ensure zookeeper was clean before running test");
            }
        }

        // 2. Create our instance and open it
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Define the offset we are storing
        final long offset = 100L;

        // Persist it
        logger.info("Persisting {}", offset);
        persistenceAdapter.persistConsumerState(consumerId, partitionId, offset);

        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkConsumersRootNodePath, false), notNullValue());

        // 4. Go into zookeeper and see where data got written
        doesNodeExist = zookeeperClient.exists(zkConsumersRootNodePath, false);
        logger.debug("Result {}", doesNodeExist);
        assertNotNull("Our root node should now exist", doesNodeExist);

        // Now attempt to read our state
        List<String> childrenNodes = zookeeperClient.getChildren(zkConsumersRootNodePath, false);
        logger.debug("Children Node Names {}", childrenNodes);

        // We should have a single child
        assertEquals("Should have a single filter", 1, childrenNodes.size());

        // Grab the child node node
        final String childNodeName = childrenNodes.get(0);
        assertNotNull("Child Node Name should not be null", childNodeName);
        assertEquals("Child Node name not correct", consumerId, childNodeName);

        // 5. Grab the value and validate it
        final byte[] storedDataBytes = zookeeperClient.getData(zkConsumersRootNodePath + "/" + consumerId + "/" + String.valueOf(partitionId), false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        final Long storedData = Long.valueOf(new String(storedDataBytes, Charsets.UTF_8));
        logger.info("Stored data {}", storedData);
        assertNotNull("Stored data should be non-null", storedData);
        assertEquals("Got unexpected state", offset, (long) storedData);

        // Test clearing state actually clears state.
        persistenceAdapter.clearConsumerState(consumerId, partitionId);

        // Validate the node no longer exists
        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkConsumersRootNodePath + "/" + consumerId + "/" + partitionId, false), nullValue());

        // Make sure the top level key no longer exists
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkConsumersRootNodePath + "/" + consumerId , false), nullValue());

        // Close everyone out
        persistenceAdapter.close();
        zookeeperClient.close();
    }

    /**
     * Tests end to end persistence of Consumer state, using an independent ZK client to verify things are written
     * into zookeeper as we expect.
     *
     * We do the following:
     * 1 - Connect to ZK and ensure that the zkRootNode path does NOT exist in Zookeeper yet
     *     If it does, we'll clean it up.
     * 2 - Create an instance of our state manager passing an expected root node
     * 3 - Attempt to persist some state
     * 4 - Go into zookeeper directly and verify the state got written under the appropriate prefix path (zkRootNode).
     * 5 - Read the stored value directly out of zookeeper and verify the right thing got written.
     */
    @Test
    public void testEndToEndConsumerStatePersistenceMultipleValuesWithValidationWithIndependentZkClient() throws IOException, KeeperException, InterruptedException {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        // Define our ZK Root Node
        final String zkRootNodePath = configuredZkRoot + "/" + configuredConsumerPrefix;
        final String zkConsumersRootNodePath = zkRootNodePath + "/consumers";
        final String virtualSpoutId = "MyConsumer" + Clock.systemUTC().millis();
        final String zkVirtualSpoutIdNodePath = zkConsumersRootNodePath + "/" + virtualSpoutId;

        // Define partitionIds
        final int partition0 = 0;
        final int partition1 = 1;
        final int partition2 = 2;

        // Define the offset we are storing
        final long partition0Offset = 100L;
        final long partition1Offset = 200L;
        final long partition2Offset = 300L;

        // 1 - Connect to ZK directly
        ZooKeeper zookeeperClient = new ZooKeeper(zkServer.getConnectString(), 6000, event -> logger.info("Got event {}", event));

        // Ensure that our node does not exist before we run test,
        // Validate that our assumption that this node does not exist!
        Stat doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
        // We need to clean up
        if (doesNodeExist != null) {
            zookeeperClient.delete(zkRootNodePath, doesNodeExist.getVersion());

            // Check again
            doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
            if (doesNodeExist != null) {
                throw new RuntimeException("Failed to ensure zookeeper was clean before running test");
            }
        }

        // 2. Create our instance and open it
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Persist it
        persistenceAdapter.persistConsumerState(virtualSpoutId, partition0, partition0Offset);
        persistenceAdapter.persistConsumerState(virtualSpoutId, partition1, partition1Offset);
        persistenceAdapter.persistConsumerState(virtualSpoutId, partition2, partition2Offset);

        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkConsumersRootNodePath, false), notNullValue());

        // 4. Go into zookeeper and see where data got written
        doesNodeExist = zookeeperClient.exists(zkConsumersRootNodePath, false);
        logger.debug("Result {}", doesNodeExist);
        assertNotNull("Our root node should now exist", doesNodeExist);

        // Now attempt to read our state
        List<String> virtualSpoutIdNodes = zookeeperClient.getChildren(zkConsumersRootNodePath, false);
        logger.debug("VirtualSpoutId Node Names {}", virtualSpoutIdNodes);

        // We should have a single child
        assertEquals("Should have a single VirtualSpoutId", 1, virtualSpoutIdNodes.size());

        // Grab the virtualSpoutId
        final String foundVirtualSpoutIdNode = virtualSpoutIdNodes.get(0);
        assertNotNull("foundVirtualSpoutIdNode entry should not be null", foundVirtualSpoutIdNode);
        assertEquals("foundVirtualSpoutIdNode name not correct", virtualSpoutId, foundVirtualSpoutIdNode);

        // Grab entries under that virtualSpoutId
        List<String> partitionIdNodes = zookeeperClient.getChildren(zkVirtualSpoutIdNodePath, false);

        // We should have a 3 children
        logger.info("PartitionId nodes: {}", partitionIdNodes);
        assertEquals("Should have 3 partitions", 3, partitionIdNodes.size());
        assertTrue("Should contain partition 0", partitionIdNodes.contains(String.valueOf(partition0)));
        assertTrue("Should contain partition 1", partitionIdNodes.contains(String.valueOf(partition1)));
        assertTrue("Should contain partition 2", partitionIdNodes.contains(String.valueOf(partition2)));

        // Grab each partition and validate it
        byte[] storedDataBytes = zookeeperClient.getData(zkVirtualSpoutIdNodePath + "/" + partition0, false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        Long storedData = Long.valueOf(new String(storedDataBytes, Charsets.UTF_8));
        logger.info("Stored data {}", storedData);
        assertNotNull("Stored data should be non-null", storedData);
        assertEquals("Got unexpected state", partition0Offset, (long) storedData);

        // Now remove partition0 from persistence
        persistenceAdapter.clearConsumerState(virtualSpoutId, partition0);

        // Validate the node no longer exists
        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkVirtualSpoutIdNodePath + "/" + partition0, false), nullValue());

        // Validation partition1
        storedDataBytes = zookeeperClient.getData(zkVirtualSpoutIdNodePath + "/" + partition1, false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        storedData = Long.valueOf(new String(storedDataBytes, Charsets.UTF_8));
        logger.info("Stored data {}", storedData);
        assertNotNull("Stored data should be non-null", storedData);
        assertEquals("Got unexpected state", partition1Offset, (long) storedData);

        // Now remove partition1 from persistence
        persistenceAdapter.clearConsumerState(virtualSpoutId, partition1);

        // Validate the node no longer exists
        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkVirtualSpoutIdNodePath + "/" + partition1, false), nullValue());

        // Validation partition2
        storedDataBytes = zookeeperClient.getData(zkVirtualSpoutIdNodePath + "/" + partition2, false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        storedData = Long.valueOf(new String(storedDataBytes, Charsets.UTF_8));
        logger.info("Stored data {}", storedData);
        assertNotNull("Stored data should be non-null", storedData);
        assertEquals("Got unexpected state", partition2Offset, (long) storedData);

        // Now remove partition1 from persistence
        persistenceAdapter.clearConsumerState(virtualSpoutId, partition2);

        // Validate the node no longer exists
        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkVirtualSpoutIdNodePath + "/" + partition1, false), nullValue());

        // Make sure the top level key no longer exists
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkConsumersRootNodePath + "/" + virtualSpoutId , false), nullValue());

        // Close everyone out
        persistenceAdapter.close();
        zookeeperClient.close();
    }

    /**
     * Does an end to end test of this persistence layer for storing/retrieving request state.
     * 1 - Sets up an internal Zk server
     * 2 - Connects to it
     * 3 - writes state data to it
     * 4 - reads state data from it
     * 5 - compares that its valid.
     */
    @Test
    public void testEndToEndRequestStatePersistence() throws InterruptedException {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        final String topicName = "MyTopic1";
        final String zkRootPath = configuredZkRoot + "/" + configuredConsumerPrefix;
        final SidelineRequestIdentifier sidelineRequestIdentifier = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest = new SidelineRequest(null);

        // Create our config
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);

        // Create instance and open it.
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Create state
        final ConsumerState consumerState = ConsumerState.builder()
            .withPartition(new ConsumerPartition(topicName, 0), 10L)
            .withPartition(new ConsumerPartition(topicName, 1), 1000L)
            .withPartition(new ConsumerPartition(topicName, 3), 3000L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerState);

        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, 0, 10L, 11L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, 1, 100L, 101L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, 2, 1000L, 1001L);

        // Attempt to read it?
        SidelinePayload result1 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 0);
        SidelinePayload result2 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 1);
        SidelinePayload result3 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 2);

        logger.info("Result {} {} {}", result1, result2, result3);

        assertNotNull("Got an object back", result1);
        assertEquals("Starting offset matches", Long.valueOf(10L), result1.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(11L), result1.endingOffset);

        assertNotNull("Got an object back", result2);
        assertEquals("Starting offset matches", Long.valueOf(100L), result2.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(101L), result2.endingOffset);

        assertNotNull("Got an object back", result3);
        assertEquals("Starting offset matches", Long.valueOf(1000L), result3.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(1001L), result3.endingOffset);

        // Close outs
        persistenceAdapter.close();

        // Create new instance, reconnect to ZK, make sure we can still read it out with our new instance.
        persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // Re-retrieve, should still be there.
        // Attempt to read it?
        // Attempt to read it?
        result1 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 0);
        result2 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 1);
        result3 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 2);

        logger.info("Result {} {} {}", result1, result2, result3);

        assertNotNull("Got an object back", result1);
        assertEquals("Starting offset matches", Long.valueOf(10L), result1.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(11L), result1.endingOffset);

        assertNotNull("Got an object back", result2);
        assertEquals("Starting offset matches", Long.valueOf(100L), result2.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(101L), result2.endingOffset);

        assertNotNull("Got an object back", result3);
        assertEquals("Starting offset matches", Long.valueOf(1000L), result3.startingOffset);
        assertEquals("Ending offset matches", Long.valueOf(1001L), result3.endingOffset);

        // Clear out hose requests
        persistenceAdapter.clearSidelineRequest(sidelineRequestIdentifier, 0);
        persistenceAdapter.clearSidelineRequest(sidelineRequestIdentifier, 1);
        persistenceAdapter.clearSidelineRequest(sidelineRequestIdentifier, 2);

        // Attempt to retrieve those sideline requests, they should come back null
        result1 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 0);
        result2 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 1);
        result3 = persistenceAdapter.retrieveSidelineRequest(sidelineRequestIdentifier, 2);

        logger.info("Result {} {} {}", result1, result2, result3);

        assertNull("Sideline request was cleared", result1);
        assertNull("Sideline request was cleared", result2);
        assertNull("Sideline request was cleared", result3);

        // Close outs
        persistenceAdapter.close();
    }

    /**
     * Tests end to end persistence of Consumer state, using an independent ZK client to verify things are written
     * into zookeeper as we expect.
     *
     * We do the following:
     * 1 - Connect to ZK and ensure that the zkRootNode path does NOT exist in Zookeeper yet
     *     If it does, we'll clean it up.
     * 2 - Create an instance of our state manager passing an expected root node
     * 3 - Attempt to persist some state
     * 4 - Go into zookeeper directly and verify the state got written under the appropriate prefix path (zkRootNode).
     * 5 - Read the stored value directly out of zookeeper and verify the right thing got written.
     */
    @Test
    public void testEndToEndRequestStatePersistenceWithValidationWithIndependentZkClient() throws IOException, KeeperException, InterruptedException {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        // Define our ZK Root Node
        final String zkRootNodePath = configuredZkRoot + "/" + configuredConsumerPrefix;
        final String zkRequestsRootNodePath = zkRootNodePath + "/requests";
        final SidelineRequestIdentifier sidelineRequestIdentifier = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest = new SidelineRequest(null);

        // 1 - Connect to ZK directly
        ZooKeeper zookeeperClient = new ZooKeeper(zkServer.getConnectString(), 6000, event -> logger.info("Got event {}", event));

        // Ensure that our node does not exist before we run test,
        // Validate that our assumption that this node does not exist!
        Stat doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
        // We need to clean up
        if (doesNodeExist != null) {
            zookeeperClient.delete(zkRootNodePath, doesNodeExist.getVersion());

            // Check again
            doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
            if (doesNodeExist != null) {
                throw new RuntimeException("Failed to ensure zookeeper was clean before running test");
            }
        }

        // 2. Create our instance and open it
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        // 3. Attempt to persist some state.
        final String topicName = "MyTopic";

        // Define our expected result that will be stored in zookeeper
        final Map<String,Object> expectedJsonMap = Maps.newHashMap();
        expectedJsonMap.put("startingOffset", 1L);
        expectedJsonMap.put("endingOffset", 2L);
        expectedJsonMap.put("filterChainStep", "rO0ABXA=");
        expectedJsonMap.put("type", SidelineType.START.toString());

        final String expectedStoredState = JSONValue.toJSONString(expectedJsonMap);

        logger.info("expectedStoredState = {}", expectedStoredState);

        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, 0, 1L, 2L);

        // Since this is an async operation, use await() to watch for the change
        await()
            .atMost(6, TimeUnit.SECONDS)
            .until(() -> zookeeperClient.exists(zkRequestsRootNodePath, false), notNullValue());

        // 4. Go into zookeeper and see where data got written
        doesNodeExist = zookeeperClient.exists(zkRequestsRootNodePath, false);
        logger.debug("Result {}", doesNodeExist);
        assertNotNull("Our root node should now exist", doesNodeExist);

        // Now attempt to read our state
        List<String> childrenNodes = zookeeperClient.getChildren(zkRequestsRootNodePath, false);
        logger.debug("Children Node Names {}", childrenNodes);

        // We should have a single child
        assertEquals("Should have a single filter", 1, childrenNodes.size());

        // Grab the child node node
        final String childNodeName = childrenNodes.get(0);
        assertNotNull("Child Node Name should not be null", childNodeName);
        assertEquals("Child Node name not correct", sidelineRequestIdentifier.toString(), childNodeName);

        // 5. Grab the value and validate it
        final byte[] storedDataBytes = zookeeperClient.getData(
            zkRequestsRootNodePath + "/" + sidelineRequestIdentifier.toString() + "/" + 0,
            false,
            null
        );
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        final String storedDataStr = new String(storedDataBytes, Charsets.UTF_8);
        logger.info("Stored data string {}", storedDataStr);
        assertNotNull("Stored data string should be non-null", storedDataStr);
        assertEquals("Got unexpected state", expectedStoredState, storedDataStr);

        // Now test clearing
        persistenceAdapter.clearSidelineRequest(sidelineRequestIdentifier, 0);

        // Validate in the Zk Client.
        doesNodeExist = zookeeperClient.exists(
            zkRequestsRootNodePath + "/" + sidelineRequestIdentifier.toString() + "/" + 0,
            false
        );

        logger.debug("Result {}", doesNodeExist);
        assertNull("Our partition node should No longer exist", doesNodeExist);

        doesNodeExist = zookeeperClient.exists(
            zkRequestsRootNodePath + "/" + sidelineRequestIdentifier.toString(),
            false
        );
        assertNull("Our partition node should No longer exist", doesNodeExist);

        // Close everyone out
        persistenceAdapter.close();
        zookeeperClient.close();
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testPersistConsumerStateBeforeBeingOpened() {
        final int partitionId = 1;

        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.persistConsumerState("MyConsumerId", partitionId, 100L);
    }

    /**
     * Verify we get an exception if you try to retrieve before calling open().
     */
    @Test
    public void testRetrieveConsumerStateBeforeBeingOpened() {
        final int partitionId = 1;

        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.retrieveConsumerState("MyConsumerId", partitionId);
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testClearConsumerStateBeforeBeingOpened() {
        final int partitionId = 1;

        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.clearConsumerState("MyConsumerId", partitionId);
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testPersistSidelineRequestStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        final SidelineRequest sidelineRequest = new SidelineRequest(null);

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, new SidelineRequestIdentifier(), sidelineRequest, 0, 1L, 2L);
    }

    /**
     * Verify we get an exception if you try to retrieve before calling open().
     */
    @Test
    public void testRetrieveSidelineRequestStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.retrieveSidelineRequest(new SidelineRequestIdentifier(), 0);
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testClearSidelineRequestBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceAdapter.clearSidelineRequest(new SidelineRequestIdentifier(), 0);
    }

    @Test
    public void testListSidelineRequests() {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        final String zkRootPath = configuredZkRoot + "/" + configuredConsumerPrefix;
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);

        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        final SidelineRequestIdentifier sidelineRequestIdentifier1 = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest1 = new SidelineRequest(null);

        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier1, sidelineRequest1, 0, 10L, 11L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier1, sidelineRequest1, 1, 10L, 11L);

        final SidelineRequestIdentifier sidelineRequestIdentifier2 = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest2 = new SidelineRequest(null);

        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier2, sidelineRequest2, 0, 100L, 101L);

        final SidelineRequestIdentifier sidelineRequestIdentifier3 = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest3 = new SidelineRequest(null);

        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier3, sidelineRequest3, 0, 1000L, 1001L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier3, sidelineRequest3, 1, 1000L, 1001L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier3, sidelineRequest3, 2, 1000L, 1001L);

        final List<SidelineRequestIdentifier> ids = persistenceAdapter.listSidelineRequests();

        assertNotNull(ids);
        assertTrue(ids.size() == 3);
        assertTrue(ids.contains(sidelineRequestIdentifier1));
        assertTrue(ids.contains(sidelineRequestIdentifier2));
        assertTrue(ids.contains(sidelineRequestIdentifier3));
    }

    /**
     * Test that given a sideline request we receive a set of partition ids for it
     */
    @Test
    public void testListSidelineRequestPartitions() {
        final String configuredConsumerPrefix = "consumerIdPrefix";
        final String configuredZkRoot = getRandomZkRootNode();

        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), configuredZkRoot, configuredConsumerPrefix);

        ZookeeperPersistenceAdapter persistenceAdapter = new ZookeeperPersistenceAdapter();
        persistenceAdapter.open(topologyConfig);

        final SidelineRequestIdentifier sidelineRequestIdentifier1 = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest1 = new SidelineRequest(null);

        final SidelineRequestIdentifier sidelineRequestIdentifier2 = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest2 = new SidelineRequest(null);

        // Two partitions for sideline request 1
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier1, sidelineRequest1, 0, 10L, 11L);
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier1, sidelineRequest1, 1, 10L, 11L);
        // One partition for sideline request 2
        persistenceAdapter.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier2, sidelineRequest1, 0, 10L, 11L);

        Set<Integer> partitionsForSidelineRequest1 = persistenceAdapter.listSidelineRequestPartitions(sidelineRequestIdentifier1);

        assertEquals(Collections.unmodifiableSet(Sets.newHashSet(0, 1)), partitionsForSidelineRequest1);

        Set<Integer> partitionsForSidelineRequest2 = persistenceAdapter.listSidelineRequestPartitions(sidelineRequestIdentifier2);

        assertEquals(Collections.unmodifiableSet(Sets.newHashSet(0)), partitionsForSidelineRequest2);
    }

    /**
     * Helper method.
     */
    private Map createDefaultConfig(List<String> zkServers, String zkRootNode, String consumerIdPrefix) {
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.PERSISTENCE_ZK_SERVERS, zkServers);
        config.put(SidelineSpoutConfig.PERSISTENCE_ZK_ROOT, zkRootNode);
        config.put(SidelineSpoutConfig.CONSUMER_ID_PREFIX, consumerIdPrefix);

        return Tools.immutableCopy(SidelineSpoutConfig.setDefaults(config));
    }

    /**
     * Helper method.
     */
    private Map createDefaultConfig(String zkServers, String zkRootNode, String consumerIdPrefix) {
        return createDefaultConfig(Lists.newArrayList(zkServers.split(",")), zkRootNode, consumerIdPrefix);
    }

    /**
     * Helper method to generate a random zkRootNode path to use.
     */
    private String getRandomZkRootNode() {
        return "/testRoot" + System.currentTimeMillis();
    }
}