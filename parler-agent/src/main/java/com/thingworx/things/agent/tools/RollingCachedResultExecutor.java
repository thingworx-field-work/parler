package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.RollingCacheRunner;
import com.thingworx.things.agent.transform.time.RollingOperator.WindowKind;
import com.thingworx.things.agent.transform.time.SeriesRunResult;

/** TQJ-5 G3 rolling executor — {@code mode=rolling} + demoted {@value #TOOL_NAME}. */
public final class RollingCachedResultExecutor {

    public static final String TOOL_NAME = "rolling_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RollingCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!U4OperationAdmission.rollingEnabled()) {
            return U4ToolResultJson.error(U4OperationAdmission.ROLLING_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            HalfOpenWindow window = U4SeriesToolArgs.requireWindow(args);
            WindowKind kind = U4SeriesToolArgs.resolveRollingKind(args);
            SeriesRunResult run = RollingCacheRunner.run(
                    cacheId,
                    timeColumn,
                    U4SeriesToolArgs.text(args, "valueColumn"),
                    window,
                    kind,
                    U4SeriesToolArgs.resolveObservationWindow(args),
                    U4SeriesToolArgs.resolveDurationWindow(args),
                    U4SeriesToolArgs.resolveMinSupport(args));
            if (run.unavailable()) {
                return U4ToolResultJson.error(run.unavailableReason(), null);
            }
            return U4ToolResultJson.ok("OK", run.findingCacheId(), run.mayPublish(), run.envelope());
        } catch (IllegalArgumentException e) {
            return U4ToolResultJson.error(U4SeriesToolArgs.reasonFrom(e.getMessage()), e.getMessage());
        }
    }
}
