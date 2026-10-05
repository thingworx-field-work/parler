package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TabularCompleteAnswerSetDetectorTest {

    @Test
    void answerSetComplete_true() {
        String json = "{\"status\":\"success\",\"answerSetComplete\":true}";
        assertTrue(TabularCompleteAnswerSetDetector.isCompleteAnswerSet("tabulate_cached_result", json));
    }

    @Test
    void strict_equivalent_rows_match_total() {
        String json = "{\"status\":\"success\",\"sampleOnly\":false,\"rowsOmitted\":false,"
                + "\"returnedRows\":3,\"totalRows\":3}";
        assertTrue(TabularCompleteAnswerSetDetector.isCompleteAnswerSet("tabulate_cached_result", json));
    }

    @Test
    void wrong_tool_name_false() {
        String json = "{\"status\":\"success\",\"answerSetComplete\":true}";
        assertFalse(TabularCompleteAnswerSetDetector.isCompleteAnswerSet("other_tool", json));
    }

    @Test
    void tabulateSuccessJsonCompleteEnough_delegates_same_semantics() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertTrue(TabularCompleteAnswerSetDetector.tabulateSuccessJsonCompleteEnough(
                om.readTree("{\"status\":\"success\",\"answerSetComplete\":true}")));
        assertFalse(TabularCompleteAnswerSetDetector.tabulateSuccessJsonCompleteEnough(
                om.readTree("{\"status\":\"success\",\"sampleOnly\":true,\"rows\":[{\"a\":1}]}")));
    }
}
