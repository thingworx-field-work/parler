package com.thingworx.things.agent.tools;

import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.transform.time.CalendarBucketCacheRunner;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler;
import com.thingworx.things.agent.transform.time.MeasurementException;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;

/**
 * CF-03 executor for {@code tabulate_cached_result} {@code mode=calendar_bucket}. There is no standalone
 * advertised tool and no replay alias; {@value #TOOL_NAME} only names the dispatched call. The zone decides
 * which day a row belongs to, so it has no default.
 */
public final class CalendarBucketCachedResultExecutor {

    public static final String TOOL_NAME = "calendar_bucket_cached_result";
    public static final String ARGUMENT_UNSUPPORTED = TimeWeightedCachedResultExecutor.ARGUMENT_UNSUPPORTED;

    /** Ignoring one of these would let the caller believe rows were filtered, partitioned or read as values. */
    private static final String[] NOT_ACCEPTED = {"valueColumn", "entityColumn", "windowStart", "windowEnd"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CalendarBucketCachedResultExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        if (!ComputingOperationAdmission.calendarBucketEnabled()) {
            return U4ToolResultJson.error(ComputingOperationAdmission.CALENDAR_BUCKET_UNAVAILABLE, null);
        }
        JsonNode args = MAPPER.readTree(toolCall.getArguments() == null ? "{}" : toolCall.getArguments());
        try {
            String cacheId = U4SeriesToolArgs.requireCacheId(args);
            String timeColumn = U4SeriesToolArgs.requireTimeColumn(args);
            for (String field : NOT_ACCEPTED) {
                if (U4SeriesToolArgs.text(args, field) != null) {
                    throw new MeasurementException(ARGUMENT_UNSUPPORTED,
                            "calendar_bucket labels every row by its own time and takes no " + field);
                }
            }
            ZoneId zone = CalendarBucketLabeler.requireRegionZone(required(args, "timeZone"));
            CalendarBucketLabeler.Granularity granularity =
                    CalendarBucketLabeler.Granularity.fromWire(required(args, "calendarBucket"));
            MeasurementRunResult run = CalendarBucketCacheRunner.run(cacheId, timeColumn, zone, granularity);
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
                    U4SeriesToolArgs.ARGUMENT_MISSING + ": " + field + " required for calendar_bucket");
        }
        return v;
    }
}
