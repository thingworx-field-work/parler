package com.thingworx.things.agent.quality;

import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;

/**
 * Attaches {@link QualityAssessment} findings to an {@link AnalysisEnvelopeBuilder}. {@code
 * BLOCKING} findings are always marked with the {@code BLOCKING:} prefix so they cannot be dropped
 * silently from evidence (B4 / TQJ-3 exit).
 */
public final class QualityEvidenceAttachment {

    private QualityEvidenceAttachment() {}

    public static AnalysisEnvelopeBuilder attach(AnalysisEnvelopeBuilder builder, QualityAssessment assessment) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(assessment, "assessment");
        builder.addQuality("profile:" + assessment.profileDigest());
        for (QualityFinding finding : assessment.findings()) {
            String token = finding.evidenceToken();
            if (finding.unknown()) {
                builder.addQuality("UNKNOWN:" + token);
                continue;
            }
            if (finding.severity() == QualitySeverity.BLOCKING) {
                builder.addBlockingQuality(token);
            } else if (finding.severity() == QualitySeverity.WARNING) {
                builder.addQuality(token);
                builder.addWarning(token);
            } else {
                builder.addQuality(token);
            }
        }
        return builder;
    }

    /**
     * Fail-fast guard: every non-unknown {@code BLOCKING} finding must appear as {@code
     * BLOCKING:<token>} in the evidence quality list.
     */
    public static void requireBlockingPreserved(QualityAssessment assessment, Iterable<String> qualityTokens) {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(qualityTokens, "qualityTokens");
        for (QualityFinding finding : assessment.blockingFindings()) {
            String required = "BLOCKING:" + finding.evidenceToken();
            boolean present = false;
            for (String token : qualityTokens) {
                if (required.equals(token)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                throw new IllegalStateException("blocking quality dropped from evidence: " + required);
            }
        }
    }
}
