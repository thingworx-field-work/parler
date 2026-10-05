package com.thingworx.things.agent.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Aggregate G6 assessment for a series window. */
public final class QualityAssessment {

    private final String profileDigest;
    private final List<QualityFinding> findings;

    private QualityAssessment(String profileDigest, List<QualityFinding> findings) {
        this.profileDigest = Objects.requireNonNull(profileDigest, "profileDigest");
        this.findings = List.copyOf(findings == null ? List.of() : findings);
    }

    public static QualityAssessment of(String profileDigest, List<QualityFinding> findings) {
        return new QualityAssessment(profileDigest, findings);
    }

    public String profileDigest() {
        return profileDigest;
    }

    public List<QualityFinding> findings() {
        return findings;
    }

    public boolean hasBlocking() {
        for (QualityFinding f : findings) {
            if (f.severity() == QualitySeverity.BLOCKING && !f.unknown()) {
                return true;
            }
        }
        return false;
    }

    public List<QualityFinding> blockingFindings() {
        List<QualityFinding> out = new ArrayList<>();
        for (QualityFinding f : findings) {
            if (f.severity() == QualitySeverity.BLOCKING && !f.unknown()) {
                out.add(f);
            }
        }
        return out;
    }

    public boolean hasMetric(QualityMetricId id) {
        for (QualityFinding f : findings) {
            if (f.metric() == id) {
                return true;
            }
        }
        return false;
    }

    public QualityFinding finding(QualityMetricId id) {
        for (QualityFinding f : findings) {
            if (f.metric() == id) {
                return f;
            }
        }
        return null;
    }
}
