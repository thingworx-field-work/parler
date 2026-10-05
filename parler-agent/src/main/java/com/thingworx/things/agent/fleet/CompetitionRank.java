package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.outlier.RobustZDetector;
import com.thingworx.things.agent.analysis.stats.LinearPercentile;

/**
 * Frozen G5 rank / percentile / MAD / robust-z rules (fleet-rca D4). Pure functions only —
 * collection and artifact publish remain FRC-1/FRC-2.
 */
public final class CompetitionRank {

    private CompetitionRank() {}

    /**
     * Competition rank = {@code 1 + count(strictly better)} under {@code direction}; ties share
     * rank. Presentation order among equals breaks by stable semantic id only.
     */
    public static int competitionRank(
            ComparableMemberMetric member, List<ComparableMemberMetric> cohort, RankingDirection direction) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(direction, "direction");
        int better = 0;
        for (ComparableMemberMetric other : cohort) {
            int cmp = compareMetric(other.metricValue(), member.metricValue(), direction);
            if (cmp < 0) {
                better++;
            }
        }
        return 1 + better;
    }

    /**
     * Low-to-high statistical percentile:
     * {@code 100 * (count(lower) + 0.5*count(equal)) / n}. Never relabeled by performance
     * direction.
     */
    public static double statisticalPercentile(ComparableMemberMetric member, List<ComparableMemberMetric> cohort) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(cohort, "cohort");
        int n = cohort.size();
        if (n <= 0) {
            throw new IllegalArgumentException("cohort must be non-empty");
        }
        int lower = 0;
        int equal = 0;
        for (ComparableMemberMetric other : cohort) {
            int cmp = Double.compare(other.metricValue(), member.metricValue());
            if (cmp < 0) {
                lower++;
            } else if (cmp == 0) {
                equal++;
            }
        }
        return 100.0 * (lower + 0.5 * equal) / n;
    }

    /** Median via U5 {@code h=(n-1)p} convention. */
    public static double median(List<ComparableMemberMetric> cohort) {
        double[] values = valuesOf(cohort);
        return LinearPercentile.of(values, 0.5);
    }

    /** Median absolute deviation from the cohort median. */
    public static double mad(List<ComparableMemberMetric> cohort) {
        double med = median(cohort);
        double[] absDev = new double[cohort.size()];
        for (int i = 0; i < cohort.size(); i++) {
            absDev[i] = Math.abs(cohort.get(i).metricValue() - med);
        }
        return LinearPercentile.of(absDev, 0.5);
    }

    /**
     * Robust z-score {@code 0.67448975 * (x-median)/MAD}. Returns {@code null} when {@code MAD==0}.
     */
    public static Double robustZ(ComparableMemberMetric member, List<ComparableMemberMetric> cohort) {
        Objects.requireNonNull(member, "member");
        double med = median(cohort);
        double mad = mad(cohort);
        if (mad == 0.0) {
            return null;
        }
        return RobustZDetector.MAD_SCALE * (member.metricValue() - med) / mad;
    }

    /**
     * Top-N under direction, then stable id. If focus is comparable but outside top N, append it
     * with {@code focusOutsideTopN=true}.
     */
    public static List<MemberPosition> topNWithFocus(
            List<ComparableMemberMetric> cohort,
            RankingDirection direction,
            int topN,
            String focusAssetId) {
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(direction, "direction");
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be > 0");
        }
        List<ComparableMemberMetric> ordered = new ArrayList<>(cohort);
        ordered.sort(presentationOrder(direction));
        Map<String, MemberPosition> byId = new LinkedHashMap<>();
        int limit = Math.min(topN, ordered.size());
        for (int i = 0; i < limit; i++) {
            ComparableMemberMetric m = ordered.get(i);
            byId.put(m.semanticAssetId(), positionOf(m, cohort, direction, false));
        }
        if (focusAssetId != null && !focusAssetId.isBlank()) {
            String focus = focusAssetId.trim();
            if (!byId.containsKey(focus)) {
                ComparableMemberMetric focusMember = null;
                for (ComparableMemberMetric m : cohort) {
                    if (m.semanticAssetId().equals(focus)) {
                        focusMember = m;
                        break;
                    }
                }
                if (focusMember != null) {
                    byId.put(focus, positionOf(focusMember, cohort, direction, true));
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private static MemberPosition positionOf(
            ComparableMemberMetric m,
            List<ComparableMemberMetric> cohort,
            RankingDirection direction,
            boolean focusOutsideTopN) {
        return new MemberPosition(
                m.semanticAssetId(),
                m.metricValue(),
                competitionRank(m, cohort, direction),
                statisticalPercentile(m, cohort),
                robustZ(m, cohort),
                focusOutsideTopN);
    }

    private static Comparator<ComparableMemberMetric> presentationOrder(RankingDirection direction) {
        return (a, b) -> {
            int cmp = compareMetric(a.metricValue(), b.metricValue(), direction);
            if (cmp != 0) {
                return cmp;
            }
            return a.semanticAssetId().compareTo(b.semanticAssetId());
        };
    }

    /** Negative when {@code a} is better than {@code b} under {@code direction}. */
    private static int compareMetric(double a, double b, RankingDirection direction) {
        int raw = Double.compare(a, b);
        if (direction == RankingDirection.HIGHER_IS_BETTER) {
            return -raw;
        }
        return raw;
    }

    private static double[] valuesOf(List<ComparableMemberMetric> cohort) {
        if (cohort == null || cohort.isEmpty()) {
            throw new IllegalArgumentException("cohort must be non-empty");
        }
        double[] values = new double[cohort.size()];
        for (int i = 0; i < cohort.size(); i++) {
            values[i] = cohort.get(i).metricValue();
        }
        return values;
    }
}
