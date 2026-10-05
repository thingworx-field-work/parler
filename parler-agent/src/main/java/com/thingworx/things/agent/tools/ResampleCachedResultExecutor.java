package com.thingworx.things.agent.tools;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.Aggregation;
import com.thingworx.things.agent.transform.time.DemoResampleAppProfile;
import com.thingworx.things.agent.transform.time.ResampleCacheRunner;
import com.thingworx.things.agent.transform.time.ResampleCacheRunner.ResampleRunResult;

/**
 * TQJ-5 G3 resample executor shared by {@code tabulate_cached_result} {@code mode=resample} and the
 * demoted executor-only alias {@value #TOOL_NAME}.
 */
public final class ResampleCachedResultExecutor {

    public static final String TOOL_NAME = "resample_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResampleCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.resampleEnabled()) {
            return errorJson(U4OperationAdmission.RESAMPLE_UNAVAILABLE, null);
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
        Aggregation aggregation;
        try {
            timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            window = U4SeriesToolArgs.requireWindow(args);
            aggregation = resolveAggregation(args);
        } catch (IllegalArgumentException e) {
            return errorJson(reasonFrom(e.getMessage()), e.getMessage());
        }
        String valueColumn = U4SeriesToolArgs.text(args, "valueColumn");
        ResampleRunResult run;
        try {
            run = ResampleCacheRunner.run(
                    cacheId,
                    timeColumn,
                    valueColumn,
                    window,
                    aggregation,
                    DemoResampleAppProfile.missingPolicy(),
                    DemoResampleAppProfile.PROFILE_DIGEST);
        } catch (IllegalArgumentException e) {
            return errorJson(reasonFrom(e.getMessage()), e.getMessage());
        }
        if (run.unavailable()) {
            return errorJson(run.unavailableReason(), null);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "OK");
        out.put("reason", "OK");
        if (run.findingCacheId() != null) {
            out.put("findingCacheId", run.findingCacheId());
        }
        out.put("mayPublish", run.mayPublish());
        AnalysisEnvelope envelope = run.envelope();
        if (envelope != null) {
            out.set("analysisEnvelope", MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(envelope)));
        }
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Missing {@code aggregation} defaults to the demo profile. A present value must be a known
     * {@link Aggregation} name; anything else fails fast.
     */
    static Aggregation resolveAggregation(JsonNode args) {
        String raw = U4SeriesToolArgs.text(args, "aggregation");
        if (raw == null) {
            return DemoResampleAppProfile.aggregation();
        }
        try {
            return Aggregation.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    U4SeriesToolArgs.AGGREGATION_INVALID + ": got " + raw);
        }
    }

    private static String reasonFrom(String message) {
        if (message == null) {
            return U4SeriesToolArgs.ARGUMENT_MISSING;
        }
        if (message.startsWith(U4SeriesToolArgs.AGGREGATION_INVALID)) {
            return U4SeriesToolArgs.AGGREGATION_INVALID;
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
