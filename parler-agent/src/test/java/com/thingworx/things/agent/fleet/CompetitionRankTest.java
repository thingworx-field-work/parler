package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.outlier.RobustZDetector;

class CompetitionRankTest {

    @Test
    void competitionRankTiesShareRank_higherIsBetter() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 10),
                new ComparableMemberMetric("b", 20),
                new ComparableMemberMetric("c", 20),
                new ComparableMemberMetric("d", 5));
        assertEquals(1, CompetitionRank.competitionRank(cohort.get(1), cohort, RankingDirection.HIGHER_IS_BETTER));
        assertEquals(1, CompetitionRank.competitionRank(cohort.get(2), cohort, RankingDirection.HIGHER_IS_BETTER));
        assertEquals(3, CompetitionRank.competitionRank(cohort.get(0), cohort, RankingDirection.HIGHER_IS_BETTER));
        assertEquals(4, CompetitionRank.competitionRank(cohort.get(3), cohort, RankingDirection.HIGHER_IS_BETTER));
    }

    @Test
    void competitionRank_lowerIsBetter() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 10),
                new ComparableMemberMetric("b", 20));
        assertEquals(1, CompetitionRank.competitionRank(cohort.get(0), cohort, RankingDirection.LOWER_IS_BETTER));
        assertEquals(2, CompetitionRank.competitionRank(cohort.get(1), cohort, RankingDirection.LOWER_IS_BETTER));
    }

    @Test
    void statisticalPercentileIsLowToHighIndependentOfDirection() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 10),
                new ComparableMemberMetric("b", 20),
                new ComparableMemberMetric("c", 30));
        // mid value: lower=1, equal=1 → 100*(1+0.5)/3 = 50
        assertEquals(50.0, CompetitionRank.statisticalPercentile(cohort.get(1), cohort), 1e-9);
        // direction must not change percentile
        assertEquals(
                CompetitionRank.statisticalPercentile(cohort.get(2), cohort),
                100.0 * (2 + 0.5) / 3,
                1e-9);
    }

    @Test
    void madZeroYieldsUndefinedRobustZ() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 5),
                new ComparableMemberMetric("b", 5),
                new ComparableMemberMetric("c", 5));
        assertEquals(0.0, CompetitionRank.mad(cohort), 0.0);
        assertNull(CompetitionRank.robustZ(cohort.get(0), cohort));
    }

    @Test
    void robustZUsesU5MadScale() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 0),
                new ComparableMemberMetric("b", 10),
                new ComparableMemberMetric("c", 20));
        double med = CompetitionRank.median(cohort);
        assertEquals(10.0, med, 1e-9);
        Double z = CompetitionRank.robustZ(cohort.get(2), cohort);
        double mad = CompetitionRank.mad(cohort);
        assertEquals(RobustZDetector.MAD_SCALE * (20 - med) / mad, z, 1e-9);
    }

    @Test
    void focusOutsideTopNIsAppendedWithFlag() {
        List<ComparableMemberMetric> cohort = List.of(
                new ComparableMemberMetric("a", 30),
                new ComparableMemberMetric("b", 20),
                new ComparableMemberMetric("c", 10),
                new ComparableMemberMetric("focus", 5));
        List<MemberPosition> top = CompetitionRank.topNWithFocus(
                cohort, RankingDirection.HIGHER_IS_BETTER, 2, "focus");
        assertEquals(3, top.size());
        assertEquals("a", top.get(0).semanticAssetId());
        assertEquals("b", top.get(1).semanticAssetId());
        assertEquals("focus", top.get(2).semanticAssetId());
        assertTrue(top.get(2).focusOutsideTopN());
        assertEquals(4, top.get(2).competitionRank());
    }
}
