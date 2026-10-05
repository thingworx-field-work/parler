package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.join.CollisionPolicyKind;
import com.thingworx.things.agent.join.JoinCardinality;
import com.thingworx.things.agent.join.JoinType;
import com.thingworx.things.agent.quality.QualitySeverity;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * TQJ-0 freeze: U4 reuses U2/U3 vocabularies and locks v1 join/quality enums without advertising
 * tools.
 */
class Tqj0VocabularyLockTest {

    @Test
    void evidenceStatusMatchesU3() {
        assertEquals(EnumSet.of(
                EvidenceStatus.SUCCESS,
                EvidenceStatus.NO_FINDING,
                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                EvidenceStatus.ERROR), EnumSet.allOf(EvidenceStatus.class));
    }

    @Test
    void completenessMatchesU2() {
        assertEquals(EnumSet.of(
                CompletenessStatus.COMPLETE,
                CompletenessStatus.PARTIAL,
                CompletenessStatus.UNKNOWN), EnumSet.allOf(CompletenessStatus.class));
    }

    @Test
    void qualitySeverityLocked() {
        assertEquals(EnumSet.of(
                QualitySeverity.INFO,
                QualitySeverity.WARNING,
                QualitySeverity.BLOCKING), EnumSet.allOf(QualitySeverity.class));
    }

    @Test
    void joinV1Closed() {
        assertEquals(EnumSet.of(JoinType.INNER, JoinType.LEFT), EnumSet.allOf(JoinType.class));
        assertEquals(EnumSet.of(
                JoinCardinality.ONE_TO_ONE,
                JoinCardinality.ONE_TO_N,
                JoinCardinality.N_TO_ONE), EnumSet.allOf(JoinCardinality.class));
        assertEquals(EnumSet.of(
                CollisionPolicyKind.ERROR,
                CollisionPolicyKind.PREFIX_LEFT_RIGHT,
                CollisionPolicyKind.EXPLICIT_RENAME_MAP), EnumSet.allOf(CollisionPolicyKind.class));
        // n:n intentionally absent
        assertTrue(EnumSet.allOf(JoinCardinality.class).stream()
                .noneMatch(c -> c.name().contains("N_TO_N") || c.name().equals("N_N")));
    }
}
