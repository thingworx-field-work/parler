package com.thingworx.things.agent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.execution.BudgetVector;

/**
 * Compact JSON projection of {@link AnalysisEnvelope}. Internal/tool-facing only — not a
 * normative wire contract unless a later same-topic consumer requires one.
 */
public final class AnalysisEnvelopeJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisEnvelopeJson() {}

    public static String toCompactJson(AnalysisEnvelope envelope) {
        if (envelope == null) {
            return "{}";
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", envelope.status().name());
        root.put("operation", envelope.operation().wireName());
        putStringArray(root, "sourceCacheIds", envelope.sourceCacheIds());
        if (envelope.findingCacheId() != null) {
            root.put("findingCacheId", envelope.findingCacheId());
        }
        AnalysisMethodDescriptor method = envelope.method();
        if (method != null) {
            ObjectNode m = root.putObject("method");
            m.put("id", method.id());
            m.put("version", method.version());
            if (method.profileDigest() != null) {
                m.put("profileDigest", method.profileDigest());
            }
        }
        EvidenceAssessment evidence = envelope.evidence();
        ObjectNode ev = root.putObject("evidence");
        ev.put("n", evidence.n());
        if (evidence.coverage() != null) {
            ev.put("coverage", evidence.coverage());
        } else {
            ev.putNull("coverage");
        }
        ev.put("completeness", evidence.completeness().name());
        putStringArray(ev, "quality", evidence.quality());
        putStringArray(ev, "warnings", evidence.warnings());

        if (!envelope.metrics().isEmpty()) {
            ObjectNode metrics = root.putObject("metrics");
            envelope.metrics().forEach(metrics::put);
        } else {
            root.putObject("metrics");
        }

        ObjectNode presentation = root.putObject("presentation");
        if (envelope.chartIntent() != null) {
            presentation.put("chartIntent", envelope.chartIntent());
        } else {
            presentation.putNull("chartIntent");
        }
        putStringArray(presentation, "summaryFacts", envelope.summaryFacts());

        AnalysisBudgetAccounting budget = envelope.budget();
        ObjectNode budgetNode = root.putObject("budget");
        if (budget != null) {
            budgetNode.set("requested", budgetVectorNode(budget.requested()));
            budgetNode.set("effective", budgetVectorNode(budget.effective()));
            ObjectNode consumed = budgetNode.putObject("consumed");
            consumed.put("rows", budget.consumedRows());
            consumed.put("bytes", budget.consumedBytes());
            consumed.put("wallTimeMillis", budget.consumedWallTimeMillis());
            budgetNode.put("clamped", budget.clamped());
        } else {
            budgetNode.putObject("requested");
            budgetNode.putObject("effective");
            budgetNode.putObject("consumed");
            budgetNode.put("clamped", false);
        }

        root.put("rowsRead", envelope.rowsRead());
        root.put("rowsOutput", envelope.rowsOutput());
        root.put("inputsFullyScanned", envelope.inputsFullyScanned());

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"status\":\"" + envelope.status().name() + "\"}";
        }
    }

    private static ObjectNode budgetVectorNode(BudgetVector v) {
        ObjectNode n = MAPPER.createObjectNode();
        if (v == null) {
            return n;
        }
        n.put("maxReturnedRows", v.maxReturnedRows());
        n.put("maxDecodeBytes", v.maxDecodeBytes());
        n.put("maxWallTimeMillis", v.maxWallTimeMillis());
        return n;
    }

    private static void putStringArray(ObjectNode n, String field, java.util.List<String> values) {
        ArrayNode arr = n.putArray(field);
        if (values == null) {
            return;
        }
        for (String v : values) {
            arr.add(v);
        }
    }
}
