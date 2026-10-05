package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceAssessmentAggregator;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.AnalyzeCachedResultExecutor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * DIK-5 close: U5 analysisEnvelope status/applicability remains available to model-facing
 * task state even when {@code mayPublish=false}.
 */
class Dik5EvidenceAssessmentTest {

    Dik5EvidenceAssessmentTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("dik5-evidence-assessment");
        AgentTaskState st = new AgentTaskState("req", "dik5-evidence-assessment", "goal");
        AgentToolContext.setAgentTaskState(st);
        U5OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U5OperationAdmission.resetForTests();
    }

    @Test
    void relationshipEnvelope_recordsAssociationalApplicability() throws Exception {
        String left = storeSeries(new double[] {1, 2, 3, 4, 5, 6, 7, 8});
        String right = storeSeries(new double[] {2, 4, 6, 8, 10, 12, 14, 16});
        String args = "{\"operation\":\"relationship\",\"methodId\":\"pearson\","
                + "\"cacheId\":\"" + left + "\",\"rightCacheId\":\"" + right + "\","
                + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}";
        AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));

        AgentTaskState st = AgentToolContext.getAgentTaskState();
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.SUCCESS, a.status());
        assertEquals(CompletenessStatus.COMPLETE, a.completeness());
        assertTrue(a.hasApplicability("associational"), a.applicability().toString());
        assertTrue(a.hasApplicability("not_tested_causal"), a.applicability().toString());
    }

    @Test
    void insufficientOutlier_recordsInsufficientEvidence() throws Exception {
        // Too few points for robust_z → INSUFFICIENT_EVIDENCE
        String cacheId = storeSeries(new double[] {1, 2});
        String args = "{\"operation\":\"outlier\",\"methodId\":\"robust_z\","
                + "\"cacheId\":\"" + cacheId + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}";
        AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));

        AgentTaskState st = AgentToolContext.getAgentTaskState();
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
    }

    @Test
    void outsideHorizonApplicability_isPreserved() {
        EvidenceAssessment a = EvidenceAssessment.builder()
                .status(EvidenceStatus.NO_FINDING)
                .completeness(CompletenessStatus.COMPLETE)
                .applicability(List.of("outside_horizon"))
                .build();
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        st.recordAnalysisAssessment(a);
        EvidenceAssessment merged = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.NO_FINDING, merged.status());
        assertEquals(CompletenessStatus.COMPLETE, merged.completeness());
        assertTrue(merged.hasApplicability("outside_horizon"));
    }

    @Test
    void soleSourceNoFinding_preservesStatus() throws Exception {
        // Varied but non-extreme series → robust_z NO_FINDING on sufficient support.
        String cacheId = storeSeries(new double[] {1, 2, 3, 4, 5, 6, 7, 8});
        String args = "{\"operation\":\"outlier\",\"methodId\":\"robust_z\","
                + "\"cacheId\":\"" + cacheId + "\",\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}";
        AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));

        AgentTaskState st = AgentToolContext.getAgentTaskState();
        assertTrue(st.getEvidenceRows().isEmpty(), "analyze path must not invent publishable evidence rows");
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.NO_FINDING, a.status(), a.toString());
        assertEquals(CompletenessStatus.COMPLETE, a.completeness());
    }

    @Test
    void soleSourceSuccess_preservesStatus() throws Exception {
        String left = storeSeries(new double[] {1, 2, 3, 4, 5, 6, 7, 8});
        String right = storeSeries(new double[] {2, 4, 6, 8, 10, 12, 14, 16});
        String args = "{\"operation\":\"relationship\",\"methodId\":\"pearson\","
                + "\"cacheId\":\"" + left + "\",\"rightCacheId\":\"" + right + "\","
                + "\"timeColumn\":\"ts\",\"valueColumn\":\"v\"}";
        AnalyzeCachedResultExecutor.execute(new ToolCall("1", AnalyzeCachedResultExecutor.TOOL_NAME, args));

        AgentTaskState st = AgentToolContext.getAgentTaskState();
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.SUCCESS, a.status(), a.toString());
        assertEquals(CompletenessStatus.COMPLETE, a.completeness());
        assertTrue(a.hasApplicability("associational"));
    }

    private static String storeSeries(double[] values) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName("ts");
        ts.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(ts);
        FieldDefinition v = new FieldDefinition();
        v.setName("v");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < values.length; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(i).toEpochMilli())));
            vc.put("v", new NumberPrimitive(values[i]));
            table.addRow(vc);
        }
        return TabularArtifactHub.store(table, SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined((long) values.length)
                .rowsReturned((long) values.length)
                .build());
    }
}
