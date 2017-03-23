package com.salesforce.storm.spout.sideline.metrics;

import org.apache.storm.metric.api.AssignableMetric;
import org.apache.storm.metric.api.IMetric;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 *
 */
public class MultiAssignableMetric implements IMetric {
    private final Map<String, AssignableMetric> values = new HashMap();

    public MultiAssignableMetric() {
    }

    public AssignableMetric scope(String key) {
        return this.values.computeIfAbsent(key, k -> new AssignableMetric(null));
    }

    public Object getValueAndReset() {
        HashMap ret = new HashMap();

        for (Map.Entry<String, AssignableMetric> entry : this.values.entrySet()) {
            ret.put(entry.getKey(), (entry.getValue()).getValueAndReset());
        }

        return ret;
    }
}
