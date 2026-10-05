package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.MeasurementException;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;
import com.thingworx.things.agent.transform.time.RollingStats;
import com.thingworx.things.agent.transform.time.RollingStatsCacheRunner;

/**
 * CF-52 executor for {@code tabulate_cached_result} {@code mode=rolling_stats}. There is no standalone
 * advertised tool and no replay alias; {@value #TOOL_NAME} only names the dispatched call. Window arguments
 * share their parsing, defaults and error codes with the existing {@code rolling} mode.
 */
public final class RollingStatsCachedResultExecutor {

    public static final String TOOL_NAME = "rolling_stats_cached_result";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RollingStatsCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!ComputingOperationAdmission.rollingStatsEnabled()) {
            return U4ToolResultJson.error(ComputingOperationAdmission.ROLLING_STATS_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            String valueColumn = U4SeriesToolArgs.text(args, "valueColumn");
            String statistic = U4SeriesToolArgs.text(args, "statistic");
            if (valueColumn == null || statistic == null) {
                throw new IllegalArgumentException(U4SeriesToolArgs.ARGUMENT_MISSING
                        + ": valueColumn and statistic required for rolling_stats");
            }
            HalfOpenWindow window = U4SeriesToolArgs.requireWindow(args);
            RollingStats.Config config = new RollingStats.Config(
                    RollingStats.Statistic.fromWire(statistic),
                    U4SeriesToolArgs.resolveRollingKind(args),
                    U4SeriesToolArgs.resolveObservationWindow(args),
                    U4SeriesToolArgs.resolveDurationWindow(args),
                    U4SeriesToolArgs.resolveMinSupport(args));
            MeasurementRunResult run = RollingStatsCacheRunner.run(
                    cacheId, timeColumn, valueColumn, U4SeriesToolArgs.text(args, "entityColumn"), window, config);
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
}
