package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;
import com.thingworx.things.agent.join.DemoExactJoinAppProfile;
import com.thingworx.things.agent.join.ExactJoinCacheRunner;
import com.thingworx.things.agent.join.ExactJoinCacheRunner.ExactJoinRunResult;
import com.thingworx.things.agent.join.ExactJoinConfig;
import com.thingworx.things.agent.join.JoinType;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * TQJ-5 exact-join executor shared by:
 * <ul>
 *   <li>model-visible {@code tabulate_cached_result} {@code mode=exact_join} (Option A / B9), and</li>
 *   <li>demoted executor-only tool name {@value #TOOL_NAME} (App / replay alias).</li>
 * </ul>
 * Admission gated by {@link U4OperationAdmission}. Tool-argument parsing fails fast — invalid
 * values are not coerced into another join profile.
 */
public final class ExactJoinCachedResultExecutor {

    public static final String TOOL_NAME = "exact_join_cached_result";

    /** Missing required tool argument (not a join-key column fault). */
    public static final String ARGUMENT_MISSING = "ARGUMENT_MISSING";

    /** Present {@code joinType} that is not {@code INNER} or {@code LEFT}. */
    public static final String JOIN_TYPE_INVALID = "JOIN_TYPE_INVALID";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExactJoinCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.exactJoinEnabled()) {
            return errorJson(U4OperationAdmission.EXACT_JOIN_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        String leftId = text(args, "leftCacheId");
        String rightId = text(args, "rightCacheId");
        if (leftId == null || rightId == null) {
            return errorJson(ARGUMENT_MISSING, "leftCacheId and rightCacheId required");
        }
        ExactJoinConfig config;
        try {
            config = resolveConfig(args);
        } catch (IllegalArgumentException e) {
            return errorJson(JOIN_TYPE_INVALID, e.getMessage());
        }
        ExactJoinRunResult run = ExactJoinCacheRunner.run(leftId, rightId, config);
        if (run.unavailable()) {
            return errorJson(run.unavailableReason(), null);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", run.join().success() ? "OK" : "ERROR");
        out.put("reason", run.join().reason().name());
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
     * Missing {@code joinType} defaults to INNER. A present value must be exactly {@code INNER} or
     * {@code LEFT} (case-insensitive); any other value fails fast.
     */
    static ExactJoinConfig resolveConfig(JsonNode args) {
        String joinType = text(args, "joinType");
        if (joinType == null) {
            return DemoExactJoinAppProfile.innerOneToOneOnId();
        }
        if (joinType.equalsIgnoreCase(JoinType.INNER.name())) {
            return DemoExactJoinAppProfile.innerOneToOneOnId();
        }
        if (joinType.equalsIgnoreCase(JoinType.LEFT.name())) {
            return DemoExactJoinAppProfile.leftOneToOneOnId();
        }
        throw new IllegalArgumentException(
                "joinType must be INNER or LEFT when supplied; got: " + joinType);
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

    private static String text(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) {
            return null;
        }
        String v = args.get(field).asText();
        return v == null || v.isBlank() ? null : v.trim();
    }
}
