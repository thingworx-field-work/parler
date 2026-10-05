package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.Aggregation;
import com.thingworx.things.agent.transform.time.PeriodCompareCacheRunner;
import com.thingworx.things.agent.transform.time.PeriodCompareCacheRunner.PeriodCompareRunResult;

/** TQJ-5 G3 period-compare executor — {@code mode=period_compare} + demoted {@value #TOOL_NAME}. */
public final class PeriodCompareCachedResultExecutor {

    public static final String TOOL_NAME = "period_compare_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PeriodCompareCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.periodCompareEnabled()) {
            return U4ToolResultJson.error(U4OperationAdmission.PERIOD_COMPARE_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            HalfOpenWindow original = U4SeriesToolArgs.requireNamedWindow(
                    args, "originalWindowStart", "originalWindowEnd");
            HalfOpenWindow current = U4SeriesToolArgs.requireNamedWindow(
                    args, "currentWindowStart", "currentWindowEnd");
            Aggregation aggregation = U4SeriesToolArgs.resolveAggregation(args);
            PeriodCompareRunResult run = PeriodCompareCacheRunner.run(
                    cacheId,
                    timeColumn,
                    U4SeriesToolArgs.text(args, "valueColumn"),
                    original,
                    current,
                    aggregation);
            if (run.unavailable()) {
                return U4ToolResultJson.error(run.unavailableReason(), null);
            }
            String reason = run.result() != null && run.result().percentUndefined()
                    ? "PERCENT_UNDEFINED"
                    : "OK";
            return U4ToolResultJson.ok(reason, null, false, run.envelope());
        } catch (IllegalArgumentException e) {
            return U4ToolResultJson.error(U4SeriesToolArgs.reasonFrom(e.getMessage()), e.getMessage());
        }
    }
}
