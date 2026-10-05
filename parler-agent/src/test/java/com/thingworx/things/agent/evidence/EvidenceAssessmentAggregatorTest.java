package com.thingworx.things.agent.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.taskstate.AgentTaskEvidence;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.taskstate.TaskStateErrorCode;

class EvidenceAssessmentAggregatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptyState_insufficientUnknown() {
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(new AgentTaskState("r", "c", "g"));
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, a.completeness());
        assertTrue(a.warnings().contains("no_evidence_rows"));
    }

    @Test
    void emptySuccessProvenComplete_noFinding() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        st.getEvidenceRows().add(okEmptyProven("e1"));
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.NO_FINDING, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.COMPLETE, a.completeness());
        assertTrue(a.hasApplicability("associational"));
    }

    @Test
    void rowsSuccess_provenTotal_successComplete() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = okEmptyProven("e1");
        e.setRowCount(3);
        e.setTotalCount(3);
        e.setTotalCountInferred(false);
        e.setCompletenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE);
        e.setCacheId("cid-1");
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.SUCCESS, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.COMPLETE, a.completeness());
        assertEquals(3L, a.n());
        assertTrue(a.sourceCacheIds().contains("cid-1"));
    }

    @Test
    void inferredTotal_cannotBecomeCompleteOrSuccess() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = new AgentTaskEvidence("e1", 1, "k", "t", "query_entities", "ok", 1L);
        e.setRowCount(5);
        e.setTotalCount(5);
        e.setTotalCountInferred(true);
        e.setResultKind("ENTITY_LIST");
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, a.completeness());
        assertTrue(a.hasQuality("total_inferred"));
        assertTrue(a.conflicts().contains("rows_under_unproven_completeness"));
    }

    @Test
    void carriedUnknownCompleteness_cannotBecomeCompleteOrSuccess() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = new AgentTaskEvidence("e1", 1, "k", "t", "tool", "ok", 1L);
        e.setRowCount(2);
        e.setTotalCount(2);
        e.setTotalCountInferred(false);
        e.setCompletenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN);
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, a.completeness());
    }

    @Test
    void missingTotal_unknownNotSuccess() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = new AgentTaskEvidence("e1", 1, "k", "t", "tool", "ok", 1L);
        e.setRowCount(4);
        // totalCount remains -1
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, a.completeness());
    }

    @Test
    void sampleOnly_neverComplete_insufficient() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = okEmptyProven("e1");
        e.setRowCount(5);
        e.setTotalCount(5);
        e.setSampleOnly(true);
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertEquals(SourceDescriptor.CompletenessStatus.PARTIAL, a.completeness());
        assertTrue(a.hasQuality("sample_only"));
    }

    @Test
    void errorOnly_errorStatus() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence e = new AgentTaskEvidence("e1", 1, "k", "t1", "tool", "error", 1L);
        e.setErrorCode(TaskStateErrorCode.CACHE_MISS);
        st.getEvidenceRows().add(e);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.ERROR, a.status());
        assertTrue(a.warnings().contains("cache_miss"));
    }

    @Test
    void mixedErrorAndOk_insufficientNotSuccess() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        AgentTaskEvidence ok = okEmptyProven("e1");
        ok.setRowCount(2);
        ok.setTotalCount(2);
        ok.setCompletenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE);
        st.getEvidenceRows().add(ok);
        AgentTaskEvidence err = new AgentTaskEvidence("e2", 2, "k2", "t2", "tool", "error", 2L);
        err.setErrorCode(TaskStateErrorCode.CACHE_MISS);
        st.getEvidenceRows().add(err);
        EvidenceAssessment a = EvidenceAssessmentAggregator.fromTaskState(st);
        assertEquals(EvidenceStatus.INSUFFICIENT_EVIDENCE, a.status());
        assertFalse(a.status() == EvidenceStatus.SUCCESS);
    }

    @Test
    void compactJson_omitsEmptyArrays() throws Exception {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        st.getEvidenceRows().add(okEmptyProven("e1"));
        String json = EvidenceAssessmentJson.toCompactJson(EvidenceAssessmentAggregator.fromTaskState(st));
        JsonNode n = MAPPER.readTree(json);
        assertEquals("NO_FINDING", n.path("status").asText());
        assertEquals("COMPLETE", n.path("completeness").asText());
        assertFalse(n.has("coverage"));
        assertTrue(n.path("applicability").isArray());
    }

    private static AgentTaskEvidence okEmptyProven(String id) {
        AgentTaskEvidence e = new AgentTaskEvidence(id, 1, "k", "t", "tool", "ok", 1L);
        e.setRowCount(0);
        e.setTotalCount(0);
        e.setTotalCountInferred(false);
        e.setSampleOnly(false);
        e.setResultKind("INFOTABLE");
        e.setCompletenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE);
        return e;
    }
}
