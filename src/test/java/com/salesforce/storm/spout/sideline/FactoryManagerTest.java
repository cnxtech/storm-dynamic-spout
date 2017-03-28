package com.salesforce.storm.spout.sideline;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.config.SidelineSpoutConfig;
import com.salesforce.storm.spout.sideline.kafka.deserializer.Deserializer;
import com.salesforce.storm.spout.sideline.kafka.deserializer.Utf8StringDeserializer;
import com.salesforce.storm.spout.sideline.kafka.retryManagers.DefaultRetryManager;
import com.salesforce.storm.spout.sideline.kafka.retryManagers.NeverRetryManager;
import com.salesforce.storm.spout.sideline.kafka.retryManagers.RetryManager;
import com.salesforce.storm.spout.sideline.persistence.PersistenceManager;
import com.salesforce.storm.spout.sideline.persistence.ZookeeperPersistenceManager;
import com.salesforce.storm.spout.sideline.tupleBuffer.FIFOBuffer;
import com.salesforce.storm.spout.sideline.tupleBuffer.RoundRobinBuffer;
import com.salesforce.storm.spout.sideline.tupleBuffer.TupleBuffer;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(DataProviderRunner.class)
public class FactoryManagerTest {

    /**
     * By default, no exceptions should be thrown.
     */
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    /**
     * Tests that if you fail to pass a deserializer config it throws an exception.
     */
    @Test
    public void testCreateNewDeserializerInstance_missingConfig() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        final FactoryManager factoryManager = new FactoryManager(config);

        // We expect this to throw an exception.
        expectedException.expect(IllegalStateException.class);
        factoryManager.createNewDeserializerInstance();
    }

    /**
     * Tests that create new deserializer instance works as expected.
     */
    @Test
    public void testCreateNewDeserializerInstance_usingDefaultImpl() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.DESERIALIZER_CLASS, "com.salesforce.storm.spout.sideline.kafka.deserializer.Utf8StringDeserializer");
        final FactoryManager factoryManager = new FactoryManager(config);

        // Create a few instances
        List<Deserializer> instances = Lists.newArrayList();
        for (int x=0; x<5; x++) {
            Deserializer deserializer = factoryManager.createNewDeserializerInstance();

            // Validate it
            assertNotNull(deserializer);
            assertTrue("Is correct instance", deserializer instanceof Utf8StringDeserializer);

            // Verify its a different instance than our previous ones
            assertFalse("Not a previous instance", instances.contains(deserializer));

            // Add to our list
            instances.add(deserializer);
        }
    }

    /**
     * Tests that if you fail to pass a deserializer config it throws an exception.
     */
    @Test
    public void testCreateNewFailedMsgRetryManagerInstance_missingConfig() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        final FactoryManager factoryManager = new FactoryManager(config);

        // We expect this to throw an exception.
        expectedException.expect(IllegalStateException.class);
        factoryManager.createNewFailedMsgRetryManagerInstance();
    }

    /**
     * Provides various tuple buffer implementation.
     */
    @DataProvider
    public static Object[][] provideFailedMsgRetryManagerClasses() {
        return new Object[][]{
            { NeverRetryManager.class },
            { DefaultRetryManager.class }
        };
    }

    /**
     * Tests that create new deserializer instance works as expected.
     */
    @Test
    @UseDataProvider("provideFailedMsgRetryManagerClasses")
    public void testCreateNewFailedMsgRetryManager(final Class clazz) {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.RETRY_MANAGER_CLASS, clazz.getName());
        final FactoryManager factoryManager = new FactoryManager(config);

        // Create a few instances
        List<RetryManager> instances = Lists.newArrayList();
        for (int x=0; x<5; x++) {
            RetryManager retryManager = factoryManager.createNewFailedMsgRetryManagerInstance();

            // Validate it
            assertNotNull(retryManager);
            assertEquals("Is correct instance type", retryManager.getClass(), clazz);

            // Verify its a different instance than our previous ones
            assertFalse("Not a previous instance", instances.contains(retryManager));

            // Add to our list
            instances.add(retryManager);
        }
    }

    /**
     * Tests that if you fail to pass a config it throws an exception.
     */
    @Test
    public void testCreateNewPersistenceManagerInstance_missingConfig() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        final FactoryManager factoryManager = new FactoryManager(config);

        // We expect this to throw an exception.
        expectedException.expect(IllegalStateException.class);
        factoryManager.createNewPersistenceManagerInstance();
    }

    /**
     * Tests that create new deserializer instance works as expected.
     */
    @Test
    public void testCreateNewPersistenceManager_usingDefaultImpl() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.PERSISTENCE_MANAGER_CLASS, "com.salesforce.storm.spout.sideline.persistence.ZookeeperPersistenceManager");
        final FactoryManager factoryManager = new FactoryManager(config);

        // Create a few instances
        List<PersistenceManager> instances = Lists.newArrayList();
        for (int x=0; x<5; x++) {
            PersistenceManager instance = factoryManager.createNewPersistenceManagerInstance();

            // Validate it
            assertNotNull(instance);
            assertTrue("Is correct instance", instance instanceof ZookeeperPersistenceManager);

            // Verify its a different instance than our previous ones
            assertFalse("Not a previous instance", instances.contains(instance));

            // Add to our list
            instances.add(instance);
        }
    }

    /**
     * Tests that if you fail to pass a deserializer config it throws an exception.
     */
    @Test
    public void testCreateNewTupleBufferInstance_missingConfig() {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        final FactoryManager factoryManager = new FactoryManager(config);

        // We expect this to throw an exception.
        expectedException.expect(IllegalStateException.class);
        factoryManager.createNewTupleBufferInstance();
    }

    /**
     * Provides various tuple buffer implementation.
     */
    @DataProvider
    public static Object[][] provideTupleBufferClasses() {
        return new Object[][]{
                { FIFOBuffer.class },
                { RoundRobinBuffer.class }
        };
    }

    /**
     * Tests that create new deserializer instance works as expected.
     */
    @Test
    @UseDataProvider("provideTupleBufferClasses")
    public void testCreateNewTupleBuffer(final Class clazz) {
        // Try with UTF8 String deserializer
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.TUPLE_BUFFER_CLASS, clazz.getName());
        final FactoryManager factoryManager = new FactoryManager(config);

        // Create a few instances
        List<TupleBuffer> instances = Lists.newArrayList();
        for (int x=0; x<5; x++) {
            TupleBuffer tupleBuffer = factoryManager.createNewTupleBufferInstance();

            // Validate it
            assertNotNull(tupleBuffer);
            assertEquals("Is correct instance type", tupleBuffer.getClass(), clazz);

            // Verify its a different instance than our previous ones
            assertFalse("Not a previous instance", instances.contains(tupleBuffer));

            // Add to our list
            instances.add(tupleBuffer);
        }
    }
}