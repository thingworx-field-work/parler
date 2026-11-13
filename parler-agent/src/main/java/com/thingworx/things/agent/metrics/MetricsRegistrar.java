package com.thingworx.things.agent.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Injectable instrument registration seam. The production implementation ({@link MetricsV2Registrar}) calls the
 * ThingWorx MetricsV2 static methods; tests use a fake that captures names, buckets, allowlists, and gauge suppliers
 * without initializing platform settings or OpenTelemetry.
 */
public interface MetricsRegistrar {

    /**
     * Whether the platform registry can be used yet. When {@code false}, {@link ParlerMetrics} records nothing for
     * that call and retries registration later instead of latching the no-op sink (plain unit tests never initialize
     * platform settings).
     */
    default boolean platformReady() {
        return true;
    }

    Counter counter(String name, String description, String unit, Set<String> attributeKeys);

    Histogram histogram(String name, String description, String unit, List<Double> buckets,
            Set<String> attributeKeys);

    /** Registers an observable gauge whose callback returns one point per attribute set. */
    void asyncGauge(String name, String description, String unit, Set<String> attributeKeys,
            Supplier<List<GaugePoint>> callback);

    interface Counter {
        void add(long value, Map<String, String> attributes);
    }

    interface Histogram {
        void record(long value, Map<String, String> attributes);
    }

    /** One observable gauge data point. */
    final class GaugePoint {
        private final long value;
        private final Map<String, String> attributes;

        public GaugePoint(long value, Map<String, String> attributes) {
            this.value = value;
            this.attributes = attributes;
        }

        public long value() {
            return value;
        }

        public Map<String, String> attributes() {
            return attributes;
        }
    }
}
