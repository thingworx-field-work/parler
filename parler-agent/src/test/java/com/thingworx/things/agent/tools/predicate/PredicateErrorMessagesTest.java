package com.thingworx.things.agent.tools.predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PredicateErrorMessagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unknownLeafKeys_missing_fieldName_true_placeholder_appends_recovery_hint() throws Exception {
        String msg = PredicateErrorMessages.unknownLeafKeys("fieldName", MAPPER.readTree("{\"type\":\"TRUE\"}"));
        assertTrue(msg.contains(PredicateErrorMessages.UNCONDITIONAL_MEASURE_OMIT_FILTERS_RECOVERY), msg);
    }
}
