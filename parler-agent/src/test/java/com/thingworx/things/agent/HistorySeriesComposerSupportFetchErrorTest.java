package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;

class HistorySeriesComposerSupportFetchErrorTest {

    @Test
    void fetchFailure_missingInput_isNeutralReason() {
        var outcome = HistorySeriesComposerSupport.fetchNumericHistory(
                null, "currentDraw",
                Instant.parse("2026-06-30T14:00:00Z"),
                Instant.parse("2026-06-30T15:00:00Z"));
        assertTrue(outcome.isError());
        assertEquals(HistorySeriesComposerSupport.FetchFailureReason.MISSING_INPUT, outcome.failureReason);
    }

    @Test
    void popBoundary_mapsNeutralReasonsToPopCodes() {
        assertEquals("POP_INTERNAL",
                HistorySeriesComposerSupport.popErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.MISSING_INPUT));
        assertEquals("POP_INTERNAL",
                HistorySeriesComposerSupport.popErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.FETCH_EXCEPTION));
        assertEquals("POP_HISTORY_TIMESTAMP_UNRESOLVED",
                HistorySeriesComposerSupport.popErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.TIMESTAMP_UNRESOLVED));
    }

    @Test
    void multiSeriesBoundary_mapsNeutralReasonsToMultiSeriesCodes() {
        assertEquals("MULTI_SERIES_HISTORY_FETCH_FAILED",
                HistorySeriesComposerSupport.multiSeriesErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.MISSING_INPUT));
        assertEquals("MULTI_SERIES_HISTORY_FETCH_FAILED",
                HistorySeriesComposerSupport.multiSeriesErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.FETCH_EXCEPTION));
        assertEquals("MULTI_SERIES_HISTORY_TIMESTAMP_UNRESOLVED",
                HistorySeriesComposerSupport.multiSeriesErrorCode(
                        HistorySeriesComposerSupport.FetchFailureReason.TIMESTAMP_UNRESOLVED));
    }

    @Test
    void fetchFailure_timestampUnresolved_isNeutralNotPopCode() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("value", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("value", new NumberPrimitive(1.0));
        table.addRow(row);

        // extractPopNumericHistoryPoints is the internal path; fetchNumericHistory needs a Thing.
        // Verify mapping from extract error shape via failure reason on a simulated outcome.
        var extract = com.thingworx.things.agent.tools.PropertyToolsExecutor
                .extractPopNumericHistoryPoints(table, "value");
        assertEquals("POP_HISTORY_TIMESTAMP_UNRESOLVED", extract.errorCode);
        var mapped = HistorySeriesComposerSupport.multiSeriesErrorCode(
                HistorySeriesComposerSupport.FetchFailureReason.TIMESTAMP_UNRESOLVED);
        assertEquals("MULTI_SERIES_HISTORY_TIMESTAMP_UNRESOLVED", mapped);
    }
}
