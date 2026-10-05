package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.RateOfChangeCacheRunner;
import com.thingworx.things.agent.transform.time.SeriesRunResult;

/** TQJ-5 G3 rate-of-change executor — {@code mode=rate_of_change} + demoted {@value #TOOL_NAME}. */
public final class RateOfChangeCachedResultExecutor {

    public static final String TOOL_NAME = "rate_of_change_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RateOfChangeCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.rateOfChangeEnabled()) {
            return U4ToolResultJson.error(U4OperationAdmission.RATE_OF_CHANGE_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            HalfOpenWindow window = U4SeriesToolArgs.requireWindow(args);
            SeriesRunResult run = RateOfChangeCacheRunner.run(
                    cacheId,
                    timeColumn,
                    U4SeriesToolArgs.text(args, "valueColumn"),
                    window);
            if (run.unavailable()) {
                return U4ToolResultJson.error(run.unavailableReason(), null);
            }
            return U4ToolResultJson.ok("OK", run.findingCacheId(), run.mayPublish(), run.envelope());
        } catch (IllegalArgumentException e) {
            return U4ToolResultJson.error(U4SeriesToolArgs.reasonFrom(e.getMessage()), e.getMessage());
        }
    }
}
