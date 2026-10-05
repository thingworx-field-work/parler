package com.thingworx.things.agent;

/**
 * Stable WARN / INFO suffix text for {@link AgentThing#ParlerStreamToRemoteThing} host-context logs so
 * {@code hostScopeField=}, {@code hostScopeCode=}, {@code hostScopeObserved=}, {@code hostScopeLimit=}, {@code detail=}
 * key order and names stay locked for downstream parsers (Splunk / Loki). See {@code CONTRACTS/API_CONTRACT.md} 2.4.9+.
 */
public final class ParlerHostScopeLogFormatter {

    private ParlerHostScopeLogFormatter() {}

    /** Empty string when {@code s} is null (log token normalization). */
    public static String hostScopeLogToken(String s) {
        return s != null ? s : "";
    }

    /**
     * WARN suffix after the headline: either five structured keys + {@code detail=}, or {@code detail=} only
     * when {@code rejectReason} is absent.
     */
    public static String discardWarnSuffix(RejectReason rejectReason, String rejectDetail) {
        String detail = rejectDetail != null ? rejectDetail : "";
        if (rejectReason != null) {
            return "hostScopeField=" + hostScopeLogToken(rejectReason.field())
                    + " hostScopeCode=" + hostScopeLogToken(rejectReason.code())
                    + " hostScopeObserved=" + hostScopeLogToken(rejectReason.observed())
                    + " hostScopeLimit=" + (rejectReason.limit() != null ? rejectReason.limit().toString() : "")
                    + " detail=" + detail;
        }
        return "detail=" + detail;
    }

    /**
     * INFO suffix for ACCEPTED payloads with normalization notes: structured keys when {@code rejectReason} is set,
     * otherwise a single {@code detail=} line.
     */
    public static String acceptedNormalizationSuffix(RejectReason rejectReason, String rejectDetail) {
        String detail = rejectDetail != null ? rejectDetail : "";
        if (rejectReason != null) {
            return "hostScopeField=" + hostScopeLogToken(rejectReason.field())
                    + " hostScopeCode=" + hostScopeLogToken(rejectReason.code())
                    + " detail=" + detail;
        }
        return "detail=" + detail;
    }
}
