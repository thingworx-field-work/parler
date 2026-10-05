package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmUsageHelperServiceTest {

    private static final Path HELPER_SOURCE = Path.of(
            "src/main/java/com/thingworx/things/agent/AgentLlmUsageHelperThing.java");

    @BeforeEach
    void installSink() {
        LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void cleanup() {
        AgentLlmCallStreamReader.clearTestHooks();
        LlmUsageHelperSupport.clearTestHooks();
        LlmCallRecorder.resetForTest();
    }

    @Test
    void buildReportJson_returnsJsonWithoutSaving() throws Exception {
        installAzureCall();
        AtomicInteger saves = new AtomicInteger();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, path, csv) -> saves.incrementAndGet());

        JSONObject report = LlmUsageHelperSupport.buildReportJson(
                queryStart(), queryEnd(), null, null, null, AzureLatencyUsageFixtures.azurePriceTableJson());

        assertEquals(0, saves.get());
        assertEquals(1, report.getInt("callCount"));
        assertEquals("test-v1", report.getString("priceVersion"));
        assertEquals("known", report.getJSONArray("calls").getJSONObject(0).getString("costStatus"));
    }

    @Test
    void exportCsv_savesGeneratedCsvOnceAndReturnsSameText() throws Exception {
        installAzureCall();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<String> savedPath = new AtomicReference<>();
        AtomicReference<String> savedCsv = new AtomicReference<>();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, path, csv) -> {
            saves.incrementAndGet();
            savedPath.set(path);
            savedCsv.set(csv);
        });

        String returned = LlmUsageHelperSupport.exportCsv(
                queryStart(), queryEnd(), null, null, null, "CustomRepo", "/reports/usage.csv",
                AzureLatencyUsageFixtures.azurePriceTableJson());

        assertEquals(1, saves.get());
        assertEquals("/reports/usage.csv", savedPath.get());
        assertEquals(returned, savedCsv.get());
        assertTrue(returned.startsWith("callId,logicalCallId"));
        assertTrue(returned.contains("26019"));
    }

    @Test
    void exportCsv_saveFailurePropagates() {
        installAzureCall();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, path, csv) -> {
            throw new RuntimeException("SaveText rejected");
        });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> LlmUsageHelperSupport.exportCsv(
                        queryStart(), queryEnd(), null, null, null, "CustomRepo", "/x.csv",
                        AzureLatencyUsageFixtures.azurePriceTableJson()));
        assertEquals("SaveText rejected", thrown.getMessage());
    }

    @Test
    void exportCsv_readerFailure_doesNotSave() {
        AtomicInteger saves = new AtomicInteger();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, path, csv) -> saves.incrementAndGet());
        AgentLlmCallStreamReader.setTestStreamMissing(true);

        LlmUsageReportException thrown = assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.exportCsv(
                        queryStart(), queryEnd(), null, null, null, "CustomRepo", "/x.csv",
                        AzureLatencyUsageFixtures.azurePriceTableJson()));
        assertEquals("REPORT_READ_FAILED", thrown.getErrorCode());
        assertEquals(0, saves.get());
    }

    @Test
    void exportCsv_missingRepository_failsBeforeSave() {
        installAzureCall();
        AtomicInteger saves = new AtomicInteger();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, path, csv) -> saves.incrementAndGet());
        LlmUsageHelperSupport.setRepositoryResolverForTest(name -> null);

        LlmUsageReportException thrown = assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.exportCsv(
                        queryStart(), queryEnd(), null, null, null, "MissingRepo", "/x.csv",
                        AzureLatencyUsageFixtures.azurePriceTableJson()));
        assertEquals(LlmUsageHelperSupport.ERROR_EXPORT_TARGET_NOT_FOUND, thrown.getErrorCode());
        assertEquals(0, saves.get());
    }

    @Test
    void exportCsv_defaultRepositoryAndFilename() throws Exception {
        installAzureCall();
        AtomicReference<String> repo = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, filePath, csv) -> {
            repo.set(repository);
            path.set(filePath);
        });

        LlmUsageHelperSupport.exportCsv(
                queryStart(), queryEnd(), null, null, null, null, null,
                AzureLatencyUsageFixtures.azurePriceTableJson());

        assertEquals("SystemRepository", repo.get());
        assertTrue(path.get().matches("/LLM-USAGE\\d{12}\\.csv"));
    }

    @Test
    void exportCsv_passesLiteralEmptyAndUnsafePathsThrough() throws Exception {
        installAzureCall();
        AtomicReference<String> emptyPath = new AtomicReference<>();
        AtomicReference<String> unsafePath = new AtomicReference<>();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, filePath, csv) -> {
            if (filePath.isEmpty()) {
                emptyPath.set(filePath);
            } else {
                unsafePath.set(filePath);
            }
        });

        LlmUsageHelperSupport.exportCsv(
                queryStart(), queryEnd(), null, null, null, "CustomRepo", "",
                AzureLatencyUsageFixtures.azurePriceTableJson());
        LlmUsageHelperSupport.exportCsv(
                queryStart(), queryEnd(), null, null, null, "CustomRepo", "../unsafe/path.csv",
                AzureLatencyUsageFixtures.azurePriceTableJson());

        assertEquals("", emptyPath.get());
        assertEquals("../unsafe/path.csv", unsafePath.get());
    }

    @Test
    void buildReportJson_usesIndependentPriceTablePerInvocation() throws Exception {
        installAzureCall();
        JSONObject priced = LlmUsageHelperSupport.buildReportJson(
                queryStart(), queryEnd(), null, null, null, AzureLatencyUsageFixtures.azurePriceTableJson());
        JSONObject unpriced = LlmUsageHelperSupport.buildReportJson(
                queryStart(), queryEnd(), null, null, null, "{\"version\":\"empty-v1\",\"prices\":[]}");

        assertEquals("test-v1", priced.getString("priceVersion"));
        assertEquals("known", priced.getJSONArray("calls").getJSONObject(0).getString("costStatus"));
        assertEquals("empty-v1", unpriced.getString("priceVersion"));
        assertEquals("unpriced", unpriced.getJSONArray("calls").getJSONObject(0).getString("costStatus"));
    }

    @Test
    void buildReportJson_rejectsInvertedRange() {
        DateTime start = queryStart();
        DateTime end = queryEnd();
        assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.buildReportJson(
                        end, start, null, null, null, AzureLatencyUsageFixtures.azurePriceTableJson()));
    }

    @Test
    void agentLlmUsageHelperThingMetadata_declaresRequiredDatetimeAndExportDefaults() throws Exception {
        String source = Files.readString(HELPER_SOURCE);
        String getReport = serviceSlice(source, "GetLlmUsageReport", "ExportLlmUsageCsv");
        String exportCsv = serviceSlice(source, "ExportLlmUsageCsv", null);

        assertServiceParameters(getReport, false);
        assertTrue(getReport.contains("LlmUsageHelperSupport.buildReportJson("));
        assertTrue(getReport.contains("loadUsagePricesJson()"));

        assertServiceParameters(exportCsv, true);
        assertTrue(exportCsv.contains("LlmUsageHelperSupport.exportCsv("));
        assertTrue(exportCsv.contains("loadUsagePricesJson()"));
    }

    private static void assertServiceParameters(String slice, boolean exportOnly) {
        assertTrue(slice.contains("@ThingworxServiceParameter(name = \"StartTime\", baseType = \"DATETIME\"")
                && slice.contains("aspects = {\"isRequired:true\"}"));
        assertTrue(slice.contains("@ThingworxServiceParameter(name = \"EndTime\", baseType = \"DATETIME\"")
                && slice.contains("aspects = {\"isRequired:true\"}"));
        if (exportOnly) {
            assertTrue(slice.contains("@ThingworxServiceParameter(name = \"FileRepository\", baseType = \"THINGNAME\"")
                    && slice.contains("defaultValue:SystemRepository"));
            assertTrue(slice.contains("@ThingworxServiceParameter(name = \"FileName\", baseType = \"STRING\"")
                    && slice.contains("defaultValue:/LLM-USAGE{YYYYmmddHHMM}.csv"));
        }
    }

    private static String serviceSlice(String source, String serviceName, String nextServiceName) {
        String marker = "@ThingworxServiceDefinition(\n            name = \"" + serviceName + "\"";
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "missing service definition: " + serviceName);
        if (nextServiceName == null) {
            return source.substring(start);
        }
        String endMarker = "@ThingworxServiceDefinition(\n            name = \"" + nextServiceName + "\"";
        int end = source.indexOf(endMarker, start + marker.length());
        assertTrue(end > start, "missing next service boundary: " + nextServiceName);
        return source.substring(start, end);
    }

    private static DateTime queryStart() {
        return new DateTime(System.currentTimeMillis() - 60_000L, DateTimeZone.UTC);
    }

    private static DateTime queryEnd() {
        return new DateTime(System.currentTimeMillis() + 60_000L, DateTimeZone.UTC);
    }

    private static void installAzureCall() {
        LlmUsageSnapshot snapshot = AzureLatencyUsageFixtures.capture(26019, 121, 26140);
        LlmCallContext context = LlmCallRecorder.agentRoundContext(
                "rid", "demo_conversationId", "SCPA_Demo_Agent", 1, AzureLatencyUsageFixtures.AZURE_IDS, null);
        LlmCallRecorder.LlmCallAttempt attempt = LlmCallRecorder.begin(context);
        attempt.markDispatched();
        attempt.finishSuccess(
                AzureLatencyUsageFixtures.AZURE_IDS,
                snapshot,
                null,
                200,
                "req",
                "resp",
                "gpt-5.4-2026-03-05",
                10L);
        AgentLlmCallStreamReader.setTestEvents(AgentLlmCallStreamWriter.testCapture());
    }
}
