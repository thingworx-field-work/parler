package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LlmUsageHelperSupportTest {

    @AfterEach
    void cleanup() {
        LlmUsageHelperSupport.clearTestHooks();
    }

    @Test
    void parseQuery_datetimeParametersPreserveHalfOpenRange() throws Exception {
        DateTime start = new DateTime(2026, 9, 13, 0, 46, 4, 697, DateTimeZone.UTC);
        DateTime end = new DateTime(2026, 9, 14, 0, 46, 4, 697, DateTimeZone.UTC);
        LlmUsageReportQuery query = LlmUsageHelperSupport.parseQuery(start, end, " demo ", " Agent ", " gpt-5.4 ");
        assertEquals(Instant.ofEpochMilli(start.getMillis()), query.getRangeStart());
        assertEquals(Instant.ofEpochMilli(end.getMillis()), query.getRangeEnd());
        assertEquals("demo", query.getConversationId());
        assertEquals("Agent", query.getAgentThing());
        assertEquals("gpt-5.4", query.getModel());
    }

    @Test
    void parseQuery_rejectsMissingOrInvertedBounds() {
        DateTime start = new DateTime(2026, 9, 14, 0, 0, 0, 0, DateTimeZone.UTC);
        DateTime end = new DateTime(2026, 9, 13, 0, 0, 0, 0, DateTimeZone.UTC);
        assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.parseQuery(null, end, null, null, null));
        assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.parseQuery(start, null, null, null, null));
        assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.parseQuery(start, start, null, null, null));
    }

    @Test
    void resolveFileName_expandsDefaultPlaceholderWithServerTimezone() {
        Instant reportAsOf = Instant.parse("2026-12-31T23:59:00Z");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            DateTime expected = new DateTime(
                    reportAsOf.toEpochMilli(), DateTimeZone.forTimeZone(TimeZone.getDefault()));
            String resolved = LlmUsageHelperSupport.resolveFileName(
                    LlmUsageHelperSupport.DEFAULT_FILE_NAME_PLACEHOLDER, reportAsOf);
            assertEquals("/LLM-USAGE" + expected.toString("yyyyMMddHHmm") + ".csv", resolved);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void resolveFileName_passesExplicitPathsThroughUnchanged() {
        Instant reportAsOf = Instant.parse("2026-09-13T21:10:00Z");
        assertEquals("/reports/usage.csv",
                LlmUsageHelperSupport.resolveFileName("/reports/usage.csv", reportAsOf));
        assertEquals("",
                LlmUsageHelperSupport.resolveFileName("", reportAsOf));
        assertEquals("../unsafe/path.csv",
                LlmUsageHelperSupport.resolveFileName("../unsafe/path.csv", reportAsOf));
    }

    @Test
    void resolveRepositoryName_defaultsToSystemRepository() {
        assertEquals("SystemRepository", LlmUsageHelperSupport.resolveRepositoryName(null));
        assertEquals("SystemRepository", LlmUsageHelperSupport.resolveRepositoryName("  "));
        assertEquals("CustomRepo", LlmUsageHelperSupport.resolveRepositoryName("CustomRepo"));
    }

    @Test
    void saveCsvToRepository_invokesSaveTextWithLiteralPathAndFullContent() throws Exception {
        AtomicReference<String> repo = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> content = new AtomicReference<>();
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, filePath, csv) -> {
            repo.set(repository);
            path.set(filePath);
            content.set(csv);
        });
        String csv = "callId,logicalCallId\nabc,def\n";
        LlmUsageHelperSupport.saveCsvToRepository("CustomRepo", "/nested/LLM-USAGE.csv", csv);
        assertEquals("CustomRepo", repo.get());
        assertEquals("/nested/LLM-USAGE.csv", path.get());
        assertEquals(csv, content.get());
        assertTrue(content.get().endsWith("\n"));
    }

    @Test
    void saveCsvToRepository_propagatesPlatformFailure() {
        LlmUsageHelperSupport.setSaveTextInvokerForTest((repository, filePath, csv) -> {
            throw new RuntimeException("SaveText rejected");
        });
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> LlmUsageHelperSupport.saveCsvToRepository(
                        "SystemRepository", "/LLM-USAGE.csv", "callId\n"));
        assertEquals("SaveText rejected", thrown.getMessage());
    }

    @Test
    void saveCsvToRepository_missingRepository_usesExportTargetErrorCode() {
        LlmUsageHelperSupport.setRepositoryResolverForTest(name -> null);
        LlmUsageReportException ex = assertThrows(LlmUsageReportException.class,
                () -> LlmUsageHelperSupport.saveCsvToRepository("MissingRepo", "/x.csv", "body"));
        assertEquals(LlmUsageHelperSupport.ERROR_EXPORT_TARGET_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void saveTextParameters_buildsSaveTextServiceArguments() {
        com.thingworx.types.collections.ValueCollection params =
                LlmUsageHelperSupport.saveTextParameters("/nested/usage.csv", "a,b\n");
        assertEquals("/nested/usage.csv",
                ((com.thingworx.types.primitives.StringPrimitive) params.get("path")).getValue());
        assertEquals("a,b\n",
                ((com.thingworx.types.primitives.StringPrimitive) params.get("content")).getValue());
    }

    @Test
    void resolveFileName_crossYearBoundaryUsesJvmDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Instant reportAsOf = Instant.parse("2027-01-01T04:30:00Z");
            assertEquals("/LLM-USAGE202612312330.csv",
                    LlmUsageHelperSupport.resolveFileName(null, reportAsOf));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
