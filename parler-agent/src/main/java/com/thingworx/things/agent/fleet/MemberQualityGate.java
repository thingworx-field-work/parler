package com.thingworx.things.agent.fleet;

import com.thingworx.things.agent.quality.QualitySeverity;

/**
 * Per-member quality assessment seam for FRC-1. BLOCKING demotes an otherwise valued member to
 * {@link CohortMemberStatus#INSUFFICIENT_EVIDENCE} before published coverage is built.
 */
@FunctionalInterface
public interface MemberQualityGate {

    /** @return severity for the authorized member; null treated as {@link QualitySeverity#INFO} */
    QualitySeverity severityFor(String semanticAssetId);

    static MemberQualityGate allowAll() {
        return id -> QualitySeverity.INFO;
    }

    static MemberQualityGate blocking(String... semanticAssetIds) {
        java.util.Set<String> blocked = new java.util.HashSet<>();
        if (semanticAssetIds != null) {
            for (String id : semanticAssetIds) {
                if (id != null && !id.isBlank()) {
                    blocked.add(id.trim());
                }
            }
        }
        return id -> blocked.contains(id) ? QualitySeverity.BLOCKING : QualitySeverity.INFO;
    }
}
