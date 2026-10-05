package com.thingworx.things.agent.analysis;

import java.util.Locale;
import java.util.Objects;

import com.thingworx.things.agent.evidence.EvidenceStatus;

/**
 * Closed §6.3.3 {@code threshold_crossing} outcome codes. Status is not free-choice.
 */
public enum ThresholdCrossingOutcome {
    CROSSING_WITHIN_HORIZON("crossing_within_horizon", EvidenceStatus.SUCCESS),
    ALREADY_CROSSED("already_crossed", EvidenceStatus.SUCCESS),
    OUTSIDE_HORIZON("outside_horizon", EvidenceStatus.NO_FINDING),
    FLAT("flat", EvidenceStatus.NO_FINDING),
    WRONG_DIRECTION("wrong_direction", EvidenceStatus.NO_FINDING),
    INSUFFICIENT_SUPPORT("insufficient_support", EvidenceStatus.INSUFFICIENT_EVIDENCE),
    INSUFFICIENT_FIT("insufficient_fit", EvidenceStatus.INSUFFICIENT_EVIDENCE),
    QUALITY_BLOCKED("quality_blocked", EvidenceStatus.INSUFFICIENT_EVIDENCE),
    APPLICABILITY_FAILED("applicability_failed", EvidenceStatus.INSUFFICIENT_EVIDENCE);

    private final String code;
    private final EvidenceStatus status;

    ThresholdCrossingOutcome(String code, EvidenceStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public EvidenceStatus status() {
        return status;
    }

    public static ThresholdCrossingOutcome fromCode(String raw) {
        String code = AnalysisOutcomeCodes.requireNonBlank(raw).toLowerCase(Locale.ROOT);
        for (ThresholdCrossingOutcome o : values()) {
            if (o.code.equals(code)) {
                return o;
            }
        }
        throw new IllegalArgumentException("unknown threshold_crossing outcomeCode: " + raw);
    }

    public static boolean isKnown(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String code = raw.trim().toLowerCase(Locale.ROOT);
        for (ThresholdCrossingOutcome o : values()) {
            if (o.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    public void assertMatches(EvidenceStatus envelopeStatus) {
        Objects.requireNonNull(envelopeStatus, "status");
        if (envelopeStatus != status) {
            throw new IllegalArgumentException(
                    "outcomeCode " + code + " requires status " + status + " but was " + envelopeStatus);
        }
    }
}
