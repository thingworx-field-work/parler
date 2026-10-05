package com.thingworx.things.agent.llm.usage;

import java.time.Instant;
import java.util.TimeZone;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Shared helper-service query, default CSV path, and FileRepository SaveText wiring (CC-7.6).
 */
public final class LlmUsageHelperSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String DEFAULT_FILE_REPOSITORY = "SystemRepository";
    public static final String DEFAULT_FILE_NAME_PLACEHOLDER = "/LLM-USAGE{YYYYmmddHHMM}.csv";
    public static final String ERROR_EXPORT_TARGET_NOT_FOUND = "REPORT_EXPORT_TARGET_NOT_FOUND";

    public interface SaveTextInvoker {
        void saveText(String repositoryThingName, String path, String content) throws Exception;
    }

    @FunctionalInterface
    interface RepositoryResolver {
        Thing resolve(String repositoryThingName) throws Exception;
    }

    static volatile SaveTextInvoker saveTextInvokerForTest;
    static volatile RepositoryResolver repositoryResolverForTest;

    private LlmUsageHelperSupport() {}

    static void setSaveTextInvokerForTest(SaveTextInvoker invoker) {
        saveTextInvokerForTest = invoker;
    }

    static void setRepositoryResolverForTest(RepositoryResolver resolver) {
        repositoryResolverForTest = resolver;
    }

    public static void clearTestHooks() {
        saveTextInvokerForTest = null;
        repositoryResolverForTest = null;
    }

    public static JSONObject buildReportJson(
            DateTime startTime,
            DateTime endTime,
            String conversationId,
            String agentThing,
            String model,
            String usagePricesJson) throws Exception {
        LlmUsageReportQuery query = parseQuery(startTime, endTime, conversationId, agentThing, model);
        Instant reportAsOf = Instant.now();
        ObjectNode report = reduceReport(query, usagePricesJson, reportAsOf);
        return new JSONObject(JSON.writeValueAsString(report));
    }

    public static String exportCsv(
            DateTime startTime,
            DateTime endTime,
            String conversationId,
            String agentThing,
            String model,
            String fileRepository,
            String fileName,
            String usagePricesJson) throws Exception {
        LlmUsageReportQuery query = parseQuery(startTime, endTime, conversationId, agentThing, model);
        Instant reportAsOf = Instant.now();
        String repositoryName = resolveRepositoryName(fileRepository);
        ensureExportTargetAccessible(repositoryName);
        String path = resolveFileName(fileName, reportAsOf);
        ObjectNode report = reduceReport(query, usagePricesJson, reportAsOf);
        String csv = LlmUsageCsvWriter.write(report);
        saveCsvToRepository(repositoryName, path, csv);
        return csv;
    }

    private static void ensureExportTargetAccessible(String repositoryThingName) throws LlmUsageReportException {
        if (saveTextInvokerForTest != null && repositoryResolverForTest == null) {
            return;
        }
        resolveRepositoryThing(repositoryThingName);
    }

    private static ObjectNode reduceReport(
            LlmUsageReportQuery query,
            String usagePricesJson,
            Instant reportAsOf) throws Exception {
        LlmUsageCostCalculator calculator = new LlmUsageCostCalculator(usagePricesJson);
        AgentLlmCallStreamReader.ReadOutcome readOutcome = AgentLlmCallStreamReader.read(query, reportAsOf);
        return LlmUsageReportReducer.reduce(query, readOutcome, reportAsOf, calculator);
    }

    public static LlmUsageReportQuery parseQuery(
            DateTime startTime,
            DateTime endTime,
            String conversationId,
            String agentThing,
            String model) throws LlmUsageReportException {
        if (startTime == null || endTime == null) {
            throw new LlmUsageReportException("REPORT_READ_FAILED", "StartTime and EndTime are required");
        }
        Instant start = Instant.ofEpochMilli(startTime.getMillis());
        Instant end = Instant.ofEpochMilli(endTime.getMillis());
        try {
            return new LlmUsageReportQuery(start, end, conversationId, agentThing, model);
        } catch (IllegalArgumentException e) {
            throw new LlmUsageReportException("REPORT_READ_FAILED", e.getMessage());
        }
    }

    public static String resolveRepositoryName(String fileRepository) {
        if (fileRepository == null || fileRepository.isBlank()) {
            return DEFAULT_FILE_REPOSITORY;
        }
        return fileRepository;
    }

    public static String resolveFileName(String fileName, Instant reportAsOf) {
        if (fileName == null || DEFAULT_FILE_NAME_PLACEHOLDER.equals(fileName)) {
            DateTime dt = new DateTime(
                    reportAsOf.toEpochMilli(),
                    DateTimeZone.forTimeZone(TimeZone.getDefault()));
            return "/LLM-USAGE" + dt.toString("yyyyMMddHHmm") + ".csv";
        }
        return fileName;
    }

    public static void saveCsvToRepository(String repositoryThingName, String path, String csv) throws Exception {
        if (saveTextInvokerForTest != null && repositoryResolverForTest == null) {
            saveTextInvokerForTest.saveText(repositoryThingName, path, csv);
            return;
        }
        Thing repository = resolveRepositoryThing(repositoryThingName);
        dispatchSaveText(repository, path, csv);
    }

    static Thing resolveRepositoryThing(String repositoryThingName) throws LlmUsageReportException {
        if (repositoryResolverForTest != null) {
            try {
                Thing repository = repositoryResolverForTest.resolve(repositoryThingName);
                if (repository == null) {
                    throw exportTargetNotFound(repositoryThingName);
                }
                return repository;
            } catch (LlmUsageReportException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmUsageReportException(ERROR_EXPORT_TARGET_NOT_FOUND, e.getMessage());
            }
        }
        RootEntity entity = EntityUtilities.findEntity(
                repositoryThingName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(entity instanceof Thing)) {
            throw exportTargetNotFound(repositoryThingName);
        }
        return (Thing) entity;
    }

    static void dispatchSaveText(Thing repository, String path, String csv) throws Exception {
        repository.processServiceRequest("SaveText", saveTextParameters(path, csv));
    }

    static ValueCollection saveTextParameters(String path, String csv) {
        ValueCollection params = new ValueCollection();
        params.put("path", new StringPrimitive(path));
        params.put("content", new StringPrimitive(csv));
        return params;
    }

    private static LlmUsageReportException exportTargetNotFound(String repositoryThingName) {
        return new LlmUsageReportException(
                ERROR_EXPORT_TARGET_NOT_FOUND,
                "FileRepository thing not found: " + repositoryThingName);
    }
}
