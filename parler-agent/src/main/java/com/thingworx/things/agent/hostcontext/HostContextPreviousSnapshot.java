package com.thingworx.things.agent.hostcontext;

/**
 * Comparison baseline for {@link HostContextSnapshotBuilder#changedFromPreviousUserTurn}.
 * Parsed from the prior user row's {@code hostContextSnapshotJson} or JVM carry.
 */
public final class HostContextPreviousSnapshot {

    /** No prior user row in effective history (or lookup miss). */
    public static final HostContextPreviousSnapshot NONE = new HostContextPreviousSnapshot(Kind.NONE, null, null);

    public enum Kind {
        NONE,
        ABSENT,
        ACCEPTED,
        REJECTED
    }

    private final Kind kind;
    private final String acceptedHashOrNull;
    private final HostContextUplink.Outcome rejectedOutcomeOrNull;

    private HostContextPreviousSnapshot(Kind kind, String acceptedHashOrNull,
            HostContextUplink.Outcome rejectedOutcomeOrNull) {
        this.kind = kind;
        this.acceptedHashOrNull = acceptedHashOrNull;
        this.rejectedOutcomeOrNull = rejectedOutcomeOrNull;
    }

    public Kind kind() {
        return kind;
    }

    public String acceptedHashOrNull() {
        return acceptedHashOrNull;
    }

    public static HostContextPreviousSnapshot absent() {
        return new HostContextPreviousSnapshot(Kind.ABSENT, null, null);
    }

    public static HostContextPreviousSnapshot accepted(String hash) {
        return new HostContextPreviousSnapshot(Kind.ACCEPTED, hash, null);
    }

    public static HostContextPreviousSnapshot rejected(HostContextUplink.Outcome outcome) {
        return new HostContextPreviousSnapshot(Kind.REJECTED, null, outcome);
    }

    public static HostContextPreviousSnapshot fromSnapshotJson(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return NONE;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(snapshotJson);
            String outcome = o.optString("outcome", "").trim();
            if ("ABSENT".equalsIgnoreCase(outcome)) {
                return absent();
            }
            if (o.optBoolean("accepted", false)) {
                String hash = o.optString("hash", "").trim();
                return hash.isEmpty() ? NONE : accepted(hash);
            }
            if (!outcome.isEmpty()) {
                try {
                    return rejected(HostContextUplink.Outcome.valueOf(outcome));
                } catch (IllegalArgumentException ignored) {
                    return rejected(HostContextUplink.Outcome.SCHEMA_REJECT);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return NONE;
    }

    /** Updates JVM carry after persisting a user-row snapshot. */
    public static HostContextPreviousSnapshot afterPersistedSnapshot(String snapshotJson) {
        return fromSnapshotJson(snapshotJson);
    }
}
