package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.stats.AnalysisUnitPropagation;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.InfoTable;

class Dik1RuntimeTest {

    @Test
    void methodRegistry_complexityBudget() {
        U5MethodCapability theil = U5MethodRegistry.requireEnabled("theil_sen");
        U5MethodRegistry.assertWithinComplexity(theil, 100L, 4950L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> U5MethodRegistry.assertWithinComplexity(theil, 100L, 2_000_000L));
        assertTrue(ex.getMessage().contains(U5MethodRegistry.BUDGET_EXCEEDED_REASON));
    }

    @Test
    void methodRegistry_unknownUnavailable() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> U5MethodRegistry.requireEnabled("not_a_method"));
        assertTrue(ex.getMessage().contains(U5MethodRegistry.UNAVAILABLE_REASON));
    }

    @Test
    void unitPropagation_slopeAndMismatch() {
        assertEquals("degC / s", AnalysisUnitPropagation.slopeUnit("degC", "s"));
        boolean[] mismatch = new boolean[1];
        assertEquals(null, AnalysisUnitPropagation.requireSame("degC", "kPa", mismatch));
        assertTrue(mismatch[0]);
        assertEquals("degC", AnalysisUnitPropagation.requireSame("degC", "degC", mismatch));
        assertTrue(!mismatch[0]);
    }

    @Test
    void findingWriter_compactSchemaDeterministic() throws Exception {
        List<FindingRow> findings = List.of(
                FindingRow.builder()
                        .sourceOrdinal(0)
                        .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                        .score(3.5)
                        .methodId("robust_z")
                        .outcomeCode("outlier")
                        .build(),
                FindingRow.builder()
                        .sourceOrdinal(2)
                        .score(-4.0)
                        .methodId("robust_z")
                        .outcomeCode("outlier")
                        .build());
        InfoTable a = FindingRowWriter.toInfoTable(findings);
        InfoTable b = FindingRowWriter.toInfoTable(findings);
        assertEquals(2, a.getRowCount().intValue());
        assertEquals(a.getRowCount(), b.getRowCount());
        assertEquals(8, a.getDataShape().getFields().size());
        assertTrue(a.getDataShape().getFields().containsKey("methodId"));
        assertTrue(a.getDataShape().getFields().containsKey("outcomeCode"));
    }

    @Test
    void envelopeSnapshot_repeatedBuildIdentical() throws Exception {
        AnalysisEnvelope first = snapshot();
        AnalysisEnvelope second = snapshot();
        assertEquals(AnalysisEnvelopeJson.toCompactJson(first), AnalysisEnvelopeJson.toCompactJson(second));
    }

    private static AnalysisEnvelope snapshot() {
        return AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.TREND)
                .method(U5MethodRegistry.descriptor("ols_trend", "profile-1"))
                .completeness(CompletenessStatus.COMPLETE)
                .n(10)
                .metrics(Map.of(
                        AnalysisOutcomeCodes.METRIC_KEY, "slope_ok",
                        "slopeUnit", AnalysisUnitPropagation.slopeUnit("degC", "s")))
                .summaryFacts(List.of("slope=0.1"))
                .inputsFullyScanned(true)
                .rowsRead(10)
                .rowsOutput(0)
                .build();
    }
}
