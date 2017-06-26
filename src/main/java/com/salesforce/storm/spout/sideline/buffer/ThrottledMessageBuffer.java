package com.salesforce.storm.spout.sideline.buffer;

import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.Message;
import com.salesforce.storm.spout.sideline.VirtualSpoutIdentifier;
import com.salesforce.storm.spout.sideline.config.SpoutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prototype ThrottledMessageBuffer based on configurable BlockingQueue sizes based on VirtualSpoutIds.
 */
public class ThrottledMessageBuffer implements MessageBuffer {
    private static final Logger logger = LoggerFactory.getLogger(ThrottledMessageBuffer.class);

    /**
     * Config option for NON-throttled buffer size.
     */
    public static final String CONFIG_BUFFER_SIZE = SpoutConfig.TUPLE_BUFFER_MAX_SIZE;

    /**
     * Config option for throttled buffer size.
     */
    public static final String CONFIG_THROTTLE_BUFFER_SIZE = "spout.coordinator.tuple_buffer.throttled_buffer_size";

    /**
     * Config option to define a regex pattern to match against VirtualSpoutIds.  If a VirtualSpoutId
     * matches this pattern, it will be throttled.
     */
    public static final String CONFIG_THROTTLE_REGEX_PATTERN = "spout.coordinator.tuple_buffer.throttled_spout_id_regex";

    /**
     * A Map of VirtualSpoutIds => Its own Blocking Queue.
     */
    private final Map<VirtualSpoutIdentifier, BlockingQueue<Message>> messageBuffer = new ConcurrentHashMap<>();

    // Config values around buffer sizes.
    private int maxBufferSize = 2000;
    private int throttledBufferSize = 200;

    // Match everything by default
    private Pattern regexPattern = Pattern.compile(".*");

    /**
     * An iterator over the Keys in buffer.  Used to Round Robin through the VirtualSpouts.
     */
    private Iterator<VirtualSpoutIdentifier> consumerIdIterator = null;

    public ThrottledMessageBuffer() {
    }

    /**
     * Helper method for creating a default instance.
     */
    public static ThrottledMessageBuffer createDefaultInstance() {
        Map<String, Object> map = Maps.newHashMap();
        map.put(SpoutConfig.TUPLE_BUFFER_MAX_SIZE, 10000);
        map.put(CONFIG_THROTTLE_BUFFER_SIZE, 10);

        ThrottledMessageBuffer buffer = new ThrottledMessageBuffer();
        buffer.open(map);

        return buffer;
    }

    @Override
    public void open(final Map spoutConfig) {
        // Setup non-throttled buffer size
        Object bufferSize = spoutConfig.get(SpoutConfig.TUPLE_BUFFER_MAX_SIZE);
        if (bufferSize != null && bufferSize instanceof Number) {
            maxBufferSize = ((Number) bufferSize).intValue();
        }

        // Setup throttled buffer size
        bufferSize = spoutConfig.get(CONFIG_THROTTLE_BUFFER_SIZE);
        if (bufferSize != null && bufferSize instanceof Number) {
            throttledBufferSize = ((Number) bufferSize).intValue();
        }

        // setup regex
        String regexPatternStr = (String) spoutConfig.get(CONFIG_THROTTLE_REGEX_PATTERN);
        if (regexPatternStr != null && !regexPatternStr.isEmpty()) {
            regexPattern = Pattern.compile(regexPatternStr);
        }
    }

    /**
     * Let the Implementation know that we're adding a new VirtualSpoutId.
     * @param virtualSpoutId - Identifier of new Virtual Spout.
     */
    @Override
    public void addVirtualSpoutId(final VirtualSpoutIdentifier virtualSpoutId) {
        synchronized (messageBuffer) {
            messageBuffer.putIfAbsent(virtualSpoutId, createBuffer(virtualSpoutId));
        }
    }

    /**
     * Let the Implementation know that we're removing/cleaning up from closing a VirtualSpout.
     * @param virtualSpoutId - Identifier of Virtual Spout to be cleaned up.
     */
    @Override
    public void removeVirtualSpoutId(final VirtualSpoutIdentifier virtualSpoutId) {
        synchronized (messageBuffer) {
            messageBuffer.remove(virtualSpoutId);
        }
    }

    /**
     * Put a new message onto the queue.  This method is blocking if the queue buffer is full.
     * @param message - Message to be added to the queue.
     * @throws InterruptedException - thrown if a thread is interrupted while blocked adding to the queue.
     */
    @Override
    public void put(final Message message) throws InterruptedException {
        // Grab the source virtual spoutId
        final VirtualSpoutIdentifier virtualSpoutId = message.getMessageId().getSrcVirtualSpoutId();

        // Add to correct buffer
        BlockingQueue<Message> virtualSpoutQueue = messageBuffer.get(virtualSpoutId);

        // If our queue doesn't exist
        if (virtualSpoutQueue == null) {
            // Attempt to put it
            messageBuffer.putIfAbsent(virtualSpoutId, createBuffer(virtualSpoutId));

            // Grab a reference.
            virtualSpoutQueue = messageBuffer.get(virtualSpoutId);
        }
        // Put it.
        virtualSpoutQueue.put(message);
    }

    @Override
    public int size() {
        int total = 0;
        for (final Queue queue: messageBuffer.values()) {
            total += queue.size();
        }
        return total;
    }

    /**
     * @return - returns the next Message to be processed out of the queue.
     */
    @Override
    public Message poll() {
        // If its null, or we hit the end, reset it.
        if (consumerIdIterator == null || !consumerIdIterator.hasNext()) {
            consumerIdIterator = messageBuffer.keySet().iterator();
        }

        // Try every buffer until we hit the end.
        Message returnMsg = null;
        while (returnMsg == null && consumerIdIterator.hasNext()) {

            // Advance iterator
            final VirtualSpoutIdentifier nextConsumerId = consumerIdIterator.next();

            // Find our buffer
            final BlockingQueue<Message> queue = messageBuffer.get(nextConsumerId);

            // We missed?
            if (queue == null) {
                logger.info("Non-existent queue found, resetting iterator.");
                consumerIdIterator = messageBuffer.keySet().iterator();
                continue;
            }
            returnMsg = queue.poll();
        }
        return returnMsg;
    }

    /**
     * @return - return a new LinkedBlockingQueue instance with a max size of our configured buffer.
     */
    private BlockingQueue<Message> createNewThrottledQueue() {
        return new LinkedBlockingQueue<>(getThrottledBufferSize());
    }

    /**
     * @return - return a new LinkedBlockingQueue instance with a max size of our configured buffer.
     */
    private BlockingQueue<Message> createNewNonThrottledQueue() {
        return new LinkedBlockingQueue<>(getMaxBufferSize());
    }

    public int getThrottledBufferSize() {
        return throttledBufferSize;
    }

    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    Pattern getRegexPattern() {
        return regexPattern;
    }

    BlockingQueue<Message> createBuffer(final VirtualSpoutIdentifier virtualSpoutIdentifier) {
        // Match VirtualSpoutId against our regex pattern
        final Matcher matches = regexPattern.matcher(virtualSpoutIdentifier.toString());

        // If we match it
        if (matches.find()) {
            // Create a throttled queue.
            return createNewThrottledQueue();
        }
        // Otherwise non-throttled.
        return createNewNonThrottledQueue();
    }
}
