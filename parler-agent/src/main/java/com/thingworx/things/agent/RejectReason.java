package com.thingworx.things.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Structured diagnostics for {@link com.thingworx.things.agent.hostcontext.HostContextUplink} rejects.
 * <p>
 * {@link #toRejectDetail()} matches the legacy human-readable {@code rejectDetail} log line for each factory.
 * The {@code detailLine} is pre-stored (not derived from {@code field}/{@code code}/…) so log parsers and tests stay
 * byte-stable.
 * <p>
 * Runtime logs: {@link com.thingworx.things.agent.AgentThing#ParlerStreamToRemoteThing} emits
 * {@code hostScopeField}, {@code hostScopeCode}, {@code hostScopeObserved}, {@code hostScopeLimit} alongside
 * {@code detail=} when {@link com.thingworx.things.agent.hostcontext.HostContextUplink.Decision#rejectReason} is non-null.
 */
public final class RejectReason {

    private final String field;
    private final String code;
    private final String observed;
    private final Integer limit;
    private final String detailLine;

    private RejectReason(String field, String code, String observed, Integer limit, String detailLine) {
        this.field = field;
        this.code = code;
        this.observed = observed;
        this.limit = limit;
        this.detailLine = detailLine != null ? detailLine : "";
    }

    /** Logical JSON path or component (e.g. {@code hierarchy_scope.path[0]}); may be {@code null}. */
    public String field() {
        return field;
    }

    /** Stable machine token for aggregation (e.g. {@code unknown_kind}, {@code oversize_utf8}). */
    public String code() {
        return code;
    }

    /** Observed value or fragment (e.g. exception message tail, unknown {@code kind} string); may be {@code null}. */
    public String observed() {
        return observed;
    }

    /** Numeric cap when {@code code} implies a bound; may be {@code null}. */
    public Integer limit() {
        return limit;
    }

    /** Same text historically carried on uplink {@code rejectDetail} for rejects. */
    public String toRejectDetail() {
        return detailLine;
    }

    public static RejectReason oversizeUtf8(int utf8Len, int cap) {
        return new RejectReason(null, "oversize_utf8", String.valueOf(utf8Len), cap,
                "oversize utf8_bytes=" + utf8Len + " cap=" + cap);
    }

    public static RejectReason invalidJson(String message) {
        String msg = message != null ? message : "";
        return new RejectReason(null, "invalid_json", msg.isEmpty() ? null : msg, null, "invalid_json " + msg);
    }

    public static RejectReason kindMissingOrEmpty() {
        return new RejectReason("kind", "missing_or_empty", null, null, "kind: missing_or_empty");
    }

    public static RejectReason unknownKind(String kind) {
        String k = kind != null ? kind : "";
        return new RejectReason(null, "unknown_kind", k.isEmpty() ? null : k, null, "unknown_kind=" + k);
    }

    /**
     * @param detailLine full legacy log line (must stay byte-stable for existing log parsers / tests)
     */
    public static RejectReason structured(String field, String code, String observed, Integer limit, String detailLine) {
        return new RejectReason(field, code, observed, limit, detailLine);
    }

    /**
     * Every {@link #code()} value produced by {@link com.thingworx.things.agent.hostcontext.HostContextUplink}.
     */
    public static Set<String> allHostScopeRejectCodes() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "oversize_utf8",
                "invalid_json",
                "missing_or_empty",
                "missing_or_wrong_type")));
    }

    @Override
    public String toString() {
        return "RejectReason{field=" + field + ", code=" + code + ", observed=" + observed + ", limit=" + limit + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RejectReason that = (RejectReason) o;
        if (field != null ? !field.equals(that.field) : that.field != null) {
            return false;
        }
        if (!code.equals(that.code)) {
            return false;
        }
        if (observed != null ? !observed.equals(that.observed) : that.observed != null) {
            return false;
        }
        if (limit != null ? !limit.equals(that.limit) : that.limit != null) {
            return false;
        }
        return detailLine.equals(that.detailLine);
    }

    @Override
    public int hashCode() {
        int result = field != null ? field.hashCode() : 0;
        result = 31 * result + code.hashCode();
        result = 31 * result + (observed != null ? observed.hashCode() : 0);
        result = 31 * result + (limit != null ? limit.hashCode() : 0);
        result = 31 * result + detailLine.hashCode();
        return result;
    }
}
