package com.thingworx.things.agent.tools;

import java.math.BigInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.MeasurementException;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;
import com.thingworx.things.agent.transform.time.TimeWeightedCacheRunner;
import com.thingworx.things.agent.transform.time.TimeWeightedIntegral;

/**
 * CF-01 executor for {@code tabulate_cached_result} {@code mode=time_weighted}. There is no standalone
 * advertised tool and no replay alias; {@value #TOOL_NAME} only names the dispatched call. The hold rule,
 * the longest valid gap and the time unit each decide the number, so none of them has a default.
 */
public final class TimeWeightedCachedResultExecutor {

    public static final String TOOL_NAME = "time_weighted_cached_result";
    public static final String ARGUMENT_UNSUPPORTED = "ARGUMENT_UNSUPPORTED";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigInteger MAX_GAP = BigInteger.valueOf(TimeWeightedIntegral.MAX_GAP_SECONDS);

    private TimeWeightedCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!ComputingOperationAdmission.timeWeightedEnabled()) {
            return U4ToolResultJson.error(ComputingOperationAdmission.TIME_WEIGHTED_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            String valueColumn = required(args, "valueColumn");
            if (U4SeriesToolArgs.text(args, "entityColumn") != null) {
                throw new MeasurementException(ARGUMENT_UNSUPPORTED,
                        "time_weighted integrates one series per call and takes no entityColumn");
            }
            HalfOpenWindow window = U4SeriesToolArgs.requireWindow(args);
            TimeWeightedIntegral.Config config = new TimeWeightedIntegral.Config(
                    TimeWeightedIntegral.Method.fromWire(required(args, "integrationMethod")),
                    maxGapSeconds(args),
                    TimeWeightedIntegral.Unit.fromWire(required(args, "timeUnit")));
            MeasurementRunResult run = TimeWeightedCacheRunner.run(cacheId, timeColumn, valueColumn, window, config);
            if (run.unavailable()) {
                return U4ToolResultJson.error(run.unavailableReason(), null);
            }
            return MeasurementToolResultJson.success(run);
        } catch (MeasurementException e) {
            return U4ToolResultJson.error(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return U4ToolResultJson.error(U4SeriesToolArgs.reasonFrom(e.getMessage()), e.getMessage());
        }
    }

    private static String required(JsonNode args, String field) {
        String v = U4SeriesToolArgs.text(args, field);
        if (v == null) {
            throw new IllegalArgumentException(
                    U4SeriesToolArgs.ARGUMENT_MISSING + ": " + field + " required for time_weighted");
        }
        return v;
    }

    /** Range-checked on the JSON number as written, before it narrows to a long. */
    private static long maxGapSeconds(JsonNode args) {
        if (args == null || !args.has("maxGapSeconds") || args.get("maxGapSeconds").isNull()) {
            throw new IllegalArgumentException(
                    U4SeriesToolArgs.ARGUMENT_MISSING + ": maxGapSeconds required for time_weighted");
        }
        JsonNode n = args.get("maxGapSeconds");
        if (!n.isIntegralNumber() || n.bigIntegerValue().signum() <= 0 || n.bigIntegerValue().compareTo(MAX_GAP) > 0) {
            throw new MeasurementException(TimeWeightedIntegral.MAX_GAP_INVALID,
                    "maxGapSeconds must be a positive whole number of seconds, at most 2^53");
        }
        return n.longValue();
    }
}
