package com.thingworx.things.agent.investigation;

import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;

/**
 * Resolved incident identity for G7 (FRC-0). Free prose is rejected — callers must resolve
 * asset/event/window before constructing this type.
 */
public final class IncidentAnchor {

    private final String focusAssetId;
    private final String eventId;
    private final HalfOpenWindow evidenceWindow;

    private IncidentAnchor(Builder b) {
        this.focusAssetId = requireNonBlank(b.focusAssetId, "focusAssetId");
        this.eventId = blankToNull(b.eventId);
        this.evidenceWindow = Objects.requireNonNull(b.evidenceWindow, "evidenceWindow");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String focusAssetId() {
        return focusAssetId;
    }

    public String eventId() {
        return eventId;
    }

    public HalfOpenWindow evidenceWindow() {
        return evidenceWindow;
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    public static final class Builder {
        private String focusAssetId;
        private String eventId;
        private HalfOpenWindow evidenceWindow;

        public Builder focusAssetId(String v) {
            this.focusAssetId = v;
            return this;
        }

        public Builder eventId(String v) {
            this.eventId = v;
            return this;
        }

        public Builder evidenceWindow(HalfOpenWindow v) {
            this.evidenceWindow = v;
            return this;
        }

        public IncidentAnchor build() {
            return new IncidentAnchor(this);
        }
    }
}
