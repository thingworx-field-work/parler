package com.thingworx.things.agent.tools;

import java.math.BigInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.CounterDelta;
import com.thingworx.things.agent.transform.time.CounterDelta.CounterDeltaException;
import com.thingworx.things.agent.transform.time.CounterDeltaCacheRunner;
import com.thingworx.things.agent.transform.time.MeasurementException;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;

/**
 * CF-05 executor for {@code tabulate_cached_result} {@code mode=counter_delta}. There is no standalone
 * advertised tool and no replay alias; {@value #TOOL_NAME} only names the dispatched call.
 */
public final class CounterDeltaCachedResultExecutor {

    public static final String TOOL_NAME = "counter_delta_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CounterDeltaCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!ComputingOperationAdmission.counterDeltaEnabled()) {
            return U4ToolResultJson.error(ComputingOperationAdmission.COUNTER_DELTA_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            String valueColumn = U4SeriesToolArgs.text(args, "valueColumn");
            if (valueColumn == null) {
                throw new IllegalArgumentException(
                        U4SeriesToolArgs.ARGUMENT_MISSING + ": valueColumn required for counter_delta");
            }
            HalfOpenWindow window = U4SeriesToolArgs.requireWindow(args);
            CounterDelta.Rules rules = new CounterDelta.Rules(
                    number(args, "counterModulus"),
                    number(args, "maxRatePerSecond"),
                    wholeSeconds(args, "maxGapSeconds"),
                    number(args, "resetBaseline"));
            MeasurementRunResult run = CounterDeltaCacheRunner.run(
                    cacheId, timeColumn, valueColumn, U4SeriesToolArgs.text(args, "entityColumn"), window, rules);
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

    private static final BigInteger TWO_POW_53 = BigInteger.ONE.shiftLeft(53);

    /**
     * Range-checked on the JSON number as written, before it narrows to a double: 2^53+1 would otherwise
     * round onto the permitted 2^53. A non-integral literal at or above 2^53 is refused as well, because
     * it may already be such a rounded value by the time the arguments are parsed.
     */
    private static Double number(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) {
            return null;
        }
        JsonNode n = args.get(field);
        if (!n.isNumber()) {
            throw new CounterDeltaException(CounterDelta.RULE_INVALID, field + " must be a number");
        }
        boolean aboveCap = n.isIntegralNumber()
                ? n.bigIntegerValue().abs().compareTo(TWO_POW_53) > 0
                : !(Math.abs(n.asDouble()) < CounterDelta.EXACT_INTEGER_LIMIT);
        if (aboveCap) {
            throw new CounterDeltaException(CounterDelta.RULE_INVALID,
                    field + " must be finite and at most 2^53 (exactly 2^53 only as an integer literal)");
        }
        return n.asDouble();
    }

    private static Long wholeSeconds(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) {
            return null;
        }
        JsonNode n = args.get(field);
        if (!n.isIntegralNumber() || n.bigIntegerValue().signum() <= 0
                || n.bigIntegerValue().compareTo(TWO_POW_53) > 0) {
            throw new CounterDeltaException(CounterDelta.RULE_INVALID,
                    field + " must be a positive whole number of seconds, at most 2^53");
        }
        return n.longValue();
    }
}
