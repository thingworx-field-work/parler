package com.thingworx.things.agent.investigation;

/**
 * Governed event / maintenance / batch read-Service binding (fleet-rca D8).
 */
public final class CatalogServiceBinding {

    public enum BindingKind {
        EVENT,
        MAINTENANCE,
        BATCH
    }

    private final BindingKind kind;
    private final String thingName;
    private final String serviceName;
    private final long coOccurrenceWindowMillis;

    public CatalogServiceBinding(BindingKind kind, String thingName, String serviceName,
            long coOccurrenceWindowMillis) {
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.thingName = requireNonBlank(thingName, "thingName");
        this.serviceName = requireNonBlank(serviceName, "serviceName");
        if (coOccurrenceWindowMillis <= 0L) {
            throw new IllegalArgumentException("coOccurrenceWindowMillis must be > 0");
        }
        this.coOccurrenceWindowMillis = coOccurrenceWindowMillis;
    }

    public BindingKind kind() {
        return kind;
    }

    public String thingName() {
        return thingName;
    }

    public String serviceName() {
        return serviceName;
    }

    public long coOccurrenceWindowMillis() {
        return coOccurrenceWindowMillis;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
