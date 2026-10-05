package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

class AnalysisEnvelopeBuilderValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void successComplete_golden() throws Exception {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.RESAMPLE)
                .addSourceCacheId("src-1")
                .findingCacheId("out-1")
                .method(descriptor(AnalysisOperation.RESAMPLE))
                .completeness(CompletenessStatus.COMPLETE)
                .n(12)
                .coverage("1.0")
                .metrics(Map.of("buckets", "12"))
                .summaryFacts(List.of("mean=3.5"))
                .budget(sampleBudget())
                .rowsRead(12)
                .rowsOutput(12)
                .inputsFullyScanned(true)
                .build();

        assertEquals(EvidenceStatus.SUCCESS, env.status());
        assertEquals(CompletenessStatus.COMPLETE, env.completeness());
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());

        JsonNode json = MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(env));
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals("resample", json.path("operation").asText());
        assertEquals("COMPLETE", json.path("evidence").path("completeness").asText());
        assertEquals(12, json.path("evidence").path("n").asInt());
        assertEquals("src-1", json.path("sourceCacheIds").get(0).asText());
        assertEquals("out-1", json.path("findingCacheId").asText());
        assertTrue(json.path("inputsFullyScanned").asBoolean());
    }

    @Test
    void noFindingComplete_golden() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.NO_FINDING)
                .operation(AnalysisOperation.QUALITY)
                .addSourceCacheId("src-1")
                .method(descriptor(AnalysisOperation.QUALITY))
                .completeness(CompletenessStatus.COMPLETE)
                .n(0)
                .addWarning("no_quality_findings")
                .inputsFullyScanned(true)
                .build();
        assertEquals(EvidenceStatus.NO_FINDING, env.status());
        assertEquals(0L, env.evidence().n());
    }

    @Test
    void insufficientPartial_golden() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.PERIOD_COMPARE)
                .completenessFromParents(CompletenessStatus.PARTIAL)
                .n(3)
                .addWarning("SOURCE_PARTIAL")
                .inputsFullyScanned(false)
                .build();
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, env.status());
        assertEquals(CompletenessStatus.PARTIAL, env.completeness());
        assertFalse(env.inputsFullyScanned());
    }

    @Test
    void errorUnknown_golden() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.ERROR)
                .operation(AnalysisOperation.EXACT_JOIN)
                .completeness(CompletenessStatus.UNKNOWN)
                .addWarning("CARDINALITY_VIOLATION")
                .build();
        assertEquals(EvidenceStatus.ERROR, env.status());
        assertEquals(null, env.findingCacheId());
    }

    @Test
    void successRejectsNonComplete() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.RESAMPLE)
                .completeness(CompletenessStatus.PARTIAL)
                .n(5)
                .build());
    }

    @Test
    void fleetBenchmarkAllowsSuccessUnderPartialCompleteness() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.FLEET_BENCHMARK)
                .method(descriptor(AnalysisOperation.FLEET_BENCHMARK))
                .completeness(CompletenessStatus.PARTIAL)
                .n(3)
                .addWarning("COHORT_PARTIAL")
                .metrics(Map.of("outcomeCode", "COHORT_PARTIAL"))
                .chartIntent("u6.fleet_benchmark.distribution")
                .inputsFullyScanned(false)
                .build();
        assertEquals(EvidenceStatus.SUCCESS, env.status());
        assertEquals(CompletenessStatus.PARTIAL, env.completeness());
        assertTrue(AnalysisEnvelopeValidator.validate(env).isEmpty());
    }

    @Test
    void methodOperationMismatchRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.RESAMPLE)
                .method(descriptor(AnalysisOperation.QUALITY))
                .completeness(CompletenessStatus.UNKNOWN)
                .build());
    }

    @Test
    void blockingQualityTokenPreserved() {
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .operation(AnalysisOperation.QUALITY)
                .completeness(CompletenessStatus.COMPLETE)
                .addBlockingQuality("flatline")
                .build();
        assertTrue(env.evidence().quality().contains("flatline"));
        assertTrue(env.evidence().quality().contains("BLOCKING:flatline"));
    }

    @Test
    void gapBearingCompleteTransform_envelopeMayStayComplete() {
        CompletenessStatus derived = CompletenessPropagation.forTransform(
                CompletenessStatus.COMPLETE, true, true);
        AnalysisEnvelope env = AnalysisEnvelopeBuilder.create()
                .status(EvidenceStatus.SUCCESS)
                .operation(AnalysisOperation.RESAMPLE)
                .completeness(derived)
                .n(10)
                .addQuality("empty_buckets")
                .addWarning("observation_gaps_present")
                .inputsFullyScanned(true)
                .build();
        assertEquals(CompletenessStatus.COMPLETE, env.completeness());
        assertTrue(env.evidence().quality().contains("empty_buckets"));
    }

    @Test
    void negativeRowsReadRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AnalysisEnvelopeBuilder.create()
                        .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                        .operation(AnalysisOperation.RESAMPLE)
                        .completeness(CompletenessStatus.UNKNOWN)
                        .rowsRead(-1)
                        .build());
        assertTrue(ex.getMessage().contains("rowsRead"));
    }

    @Test
    void negativeRowsOutputRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AnalysisEnvelopeBuilder.create()
                        .status(EvidenceStatus.INSUFFICIENT_EVIDENCE)
                        .operation(AnalysisOperation.RESAMPLE)
                        .completeness(CompletenessStatus.UNKNOWN)
                        .rowsOutput(-1)
                        .build());
        assertTrue(ex.getMessage().contains("rowsOutput"));
    }

    @Test
    void negativeConsumedBudgetRejected() {
        BudgetVector v = BudgetVector.defaultsForTabular();
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisBudgetAccounting.builder()
                        .requested(v)
                        .effective(v)
                        .consumedRows(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisBudgetAccounting.builder()
                        .requested(v)
                        .effective(v)
                        .consumedBytes(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisBudgetAccounting.builder()
                        .requested(v)
                        .effective(v)
                        .consumedWallTimeMillis(-1)
                        .build());
    }

    private static AnalysisMethodDescriptor descriptor(AnalysisOperation op) {
        return AnalysisMethodDescriptor.builder()
                .id("u4." + op.name().toLowerCase())
                .version("1")
                .profileDigest("digest-test")
                .operation(op)
                .build();
    }

    private static AnalysisBudgetAccounting sampleBudget() {
        BudgetVector v = BudgetVector.defaultsForTabular();
        return AnalysisBudgetAccounting.builder()
                .requested(v)
                .effective(v)
                .consumedRows(12)
                .consumedBytes(1024)
                .consumedWallTimeMillis(15)
                .clamped(false)
                .build();
    }
}
