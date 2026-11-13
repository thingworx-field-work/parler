package com.thingworx.things.agent.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.thingworx.metrics.v2.ICounter;
import com.thingworx.metrics.v2.IHistogram;
import com.thingworx.metrics.v2.MetricOptions;
import com.thingworx.metrics.v2.MetricRecord;
import com.thingworx.metrics.v2.MetricsV2;
import com.thingworx.metrics.v2.ThingworxMetricAttributes;
import com.thingworx.system.configuration.PlatformSettings;

/**
 * Production {@link MetricsRegistrar} over the ThingWorx MetricsV2 API (9.7.0+; signatures shared by 9.7.5 and
 * 10.2.0). This is the only Parler class that imports {@code com.thingworx.metrics.v2} types.
 *
 * <p>The platform registry is JVM-wide: a same-type existing instrument (or a platform-disabled no-op) is returned
 * as a successful registration. Existing histograms keep their buckets and existing async gauges keep their callback.
 */
public final class MetricsV2Registrar implements MetricsRegistrar {

    /** The platform initializes its settings before any extension service runs; plain unit tests never do. */
    @Override
    public boolean platformReady() {
        return PlatformSettings.isInitialized();
    }

    @Override
    public Counter counter(String name, String description, String unit, Set<String> attributeKeys) {
        ICounter counter = MetricsV2.counter(name, options(description, unit, attributeKeys));
        if (counter == null) {
            throw new IllegalStateException("MetricsV2.counter returned null for " + name);
        }
        return (value, attributes) -> counter.add(value, toAttributes(attributes));
    }

    @Override
    public Histogram histogram(String name, String description, String unit, List<Double> buckets,
            Set<String> attributeKeys) {
        IHistogram histogram = MetricsV2.explicitBucketHistogram(name, buckets,
                options(description, unit, attributeKeys));
        if (histogram == null) {
            throw new IllegalStateException("MetricsV2.explicitBucketHistogram returned null for " + name);
        }
        return (value, attributes) -> histogram.record(value, toAttributes(attributes));
    }

    @Override
    public void asyncGauge(String name, String description, String unit, Set<String> attributeKeys,
            Supplier<List<GaugePoint>> callback) {
        Supplier<MetricRecord> platformCallback = () -> {
            MetricRecord record = new MetricRecord();
            for (GaugePoint point : callback.get()) {
                record.recordValue(point.value(), toAttributes(point.attributes()));
            }
            return record;
        };
        if (MetricsV2.asyncGauge(name, platformCallback, options(description, unit, attributeKeys)) == null) {
            throw new IllegalStateException("MetricsV2.asyncGauge returned null for " + name);
        }
    }

    private static MetricOptions options(String description, String unit, Set<String> attributeKeys) {
        MetricOptions options = new MetricOptions();
        options.setDescription(description);
        options.setUnit(unit);
        options.setAttributeKeyInclusionList(new HashSet<>(attributeKeys));
        return options;
    }

    private static ThingworxMetricAttributes toAttributes(Map<String, String> attributes) {
        ThingworxMetricAttributes out = new ThingworxMetricAttributes();
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            out.set(e.getKey(), e.getValue());
        }
        return out;
    }
}
