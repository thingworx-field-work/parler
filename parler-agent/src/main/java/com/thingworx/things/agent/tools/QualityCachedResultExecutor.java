package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.quality.DemoQualityAppProfile;
import com.thingworx.things.agent.quality.QualityCacheRunner;
import com.thingworx.things.agent.quality.QualityCacheRunner.QualityRunResult;

/**
 * TQJ-5 G6 quality executor shared by {@code tabulate_cached_result} {@code mode=quality} and the
 * demoted executor-only alias {@value #TOOL_NAME}.
 */
public final class QualityCachedResultExecutor {

    public static final String TOOL_NAME = "quality_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QualityCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.qualityEnabled()) {
            return errorJson(U4OperationAdmission.QUALITY_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        String cacheId = U4SeriesToolArgs.text(args, "cacheId");
        if (cacheId == null) {
            cacheId = U4SeriesToolArgs.text(args, "sourceCacheId");
        }
        if (cacheId == null) {
            return errorJson(U4SeriesToolArgs.ARGUMENT_MISSING, "cacheId required");
        }
        String timeColumn;
        HalfOpenWindow window;
        try {
            timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            window = U4SeriesToolArgs.requireWindow(args);
        } catch (IllegalArgumentException e) {
            return errorJson(reasonFrom(e.getMessage()), e.getMessage());
        }
        String valueColumn = U4SeriesToolArgs.text(args, "valueColumn");
        QualityRunResult run;
        try {
            run = QualityCacheRunner.run(
                    cacheId,
                    timeColumn,
                    valueColumn,
                    window,
                    DemoQualityAppProfile.defaultProfile(),
                    window.endExclusive());
        } catch (IllegalArgumentException e) {
            return errorJson(reasonFrom(e.getMessage()), e.getMessage());
        }
        if (run.unavailable()) {
            return errorJson(run.unavailableReason(), null);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "OK");
        out.put("reason", run.assessment().hasBlocking() ? "QUALITY_BLOCKING" : "OK");
        out.put("mayPublish", false);
        AnalysisEnvelope envelope = run.envelope();
        if (envelope != null) {
            out.set("analysisEnvelope", MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(envelope)));
        }
        return MAPPER.writeValueAsString(out);
    }

    private static String reasonFrom(String message) {
        if (message == null) {
            return U4SeriesToolArgs.ARGUMENT_MISSING;
        }
        if (message.startsWith(U4SeriesToolArgs.WINDOW_INVALID)) {
            return U4SeriesToolArgs.WINDOW_INVALID;
        }
        if (message.startsWith(U4SeriesToolArgs.TIME_AXIS_MISSING)
                || U4SeriesToolArgs.TIME_AXIS_MISSING.equals(message)) {
            return U4SeriesToolArgs.TIME_AXIS_MISSING;
        }
        if (message.startsWith(U4SeriesToolArgs.ARGUMENT_MISSING)) {
            return U4SeriesToolArgs.ARGUMENT_MISSING;
        }
        return U4SeriesToolArgs.ARGUMENT_MISSING;
    }

    private static String errorJson(String reason, String detail) throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "ERROR");
        err.put("reason", reason);
        if (detail != null && !detail.isBlank()) {
            err.put("detail", detail);
        }
        err.put("mayPublish", false);
        return MAPPER.writeValueAsString(err);
    }
}
