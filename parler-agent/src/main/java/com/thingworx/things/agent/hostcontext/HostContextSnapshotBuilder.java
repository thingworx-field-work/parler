package com.thingworx.things.agent.hostcontext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.json.JSONObject;

import com.thingworx.things.agent.RejectReason;

/**
 * Builds {@code hostContextSnapshotJson} for AgentMessageStream user rows.
 * SoT: docs/architecture/host-context-turn-state.md §3.1–§3.3.
 */
public final class HostContextSnapshotBuilder {

    public static final String SCHEMA = "parler-host-context-snapshot-v1";

    private HostContextSnapshotBuilder() {
    }

    public static String sha256Prefixed(String rawUtf8) {
        if (rawUtf8 == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawUtf8.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean promptInsertedOutcome(HostContextUplink.Outcome outcome) {
        return outcome == HostContextUplink.Outcome.ACCEPTED
                || outcome == HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK;
    }

    public static boolean changedFromPreviousUserTurn(HostContextUplink.Outcome currentOutcome, String currentAcceptedHash,
            HostContextPreviousSnapshot previous) {
        if (currentOutcome == HostContextUplink.Outcome.ABSENT) {
            if (previous.kind() == HostContextPreviousSnapshot.Kind.ABSENT
                    || previous.kind() == HostContextPreviousSnapshot.Kind.NONE) {
                return false;
            }
            if (previous.kind() == HostContextPreviousSnapshot.Kind.ACCEPTED) {
                return true;
            }
            return false;
        }
        if (promptInsertedOutcome(currentOutcome)) {
            if (previous.kind() == HostContextPreviousSnapshot.Kind.NONE) {
                return true;
            }
            if (previous.kind() == HostContextPreviousSnapshot.Kind.ACCEPTED) {
                String prevHash = previous.acceptedHashOrNull();
                if (prevHash == null || prevHash.isEmpty()) {
                    return true;
                }
                return !prevHash.equals(currentAcceptedHash);
            }
            return true;
        }
        return false;
    }

    /**
     * @param rawHostContextWireBytes uplink string as received (null/empty for absent)
     */
    public static String buildSnapshotJson(HostContextUplink.Decision decision, String rawHostContextWireBytes,
            HostContextPreviousSnapshot previous) {
        if (decision == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA);

        if (decision.outcome == HostContextUplink.Outcome.ABSENT) {
            o.put("accepted", false);
            o.put("outcome", "ABSENT");
            o.put("changedFromPreviousUserTurn",
                    changedFromPreviousUserTurn(decision.outcome, null, previous));
            return o.toString();
        }

        if (decision.outcome == HostContextUplink.Outcome.ACCEPTED) {
            String hash = sha256Prefixed(rawHostContextWireBytes);
            boolean changed = changedFromPreviousUserTurn(decision.outcome, hash, previous);
            o.put("accepted", true);
            o.put("outcome", "ACCEPTED");
            o.put("key", decision.templateKey != null ? decision.templateKey : "");
            o.put("hash", hash);
            o.put("utf8Bytes", decision.measuredUtf8Bytes);
            o.put("changedFromPreviousUserTurn", changed);
            boolean storeRaw = changed;
            o.put("rawJsonStored", storeRaw);
            if (storeRaw && rawHostContextWireBytes != null) {
                o.put("rawJson", rawHostContextWireBytes);
            }
            return o.toString();
        }

        if (decision.outcome == HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK) {
            String hash = sha256Prefixed(rawHostContextWireBytes);
            boolean changed = changedFromPreviousUserTurn(decision.outcome, hash, previous);
            o.put("accepted", true);
            o.put("genericFallback", true);
            o.put("templateFound", false);
            o.put("outcome", "UNREGISTERED_GENERIC_FALLBACK");
            o.put("key", decision.templateKey != null ? decision.templateKey : "");
            o.put("hash", hash);
            o.put("utf8Bytes", decision.measuredUtf8Bytes);
            o.put("changedFromPreviousUserTurn", changed);
            o.put("renderTruncated", decision.renderTruncated);
            boolean storeRaw = changed;
            o.put("rawJsonStored", storeRaw);
            if (storeRaw && rawHostContextWireBytes != null) {
                o.put("rawJson", rawHostContextWireBytes);
            }
            return o.toString();
        }

        o.put("accepted", false);
        o.put("outcome", decision.outcome.name());
        if (decision.measuredUtf8Bytes > 0) {
            o.put("utf8Bytes", decision.measuredUtf8Bytes);
        }
        RejectReason rr = decision.rejectReason;
        if (rr != null) {
            o.put("rejectCode", rr.code());
        }
        if (decision.rejectDetail != null && !decision.rejectDetail.isEmpty()) {
            o.put("rejectDetail", decision.rejectDetail);
        }
        o.put("changedFromPreviousUserTurn", false);
        return o.toString();
    }
}
