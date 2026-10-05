package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of {@link ScalarThingnamePreflight#gateApplicationThing} (trim / blank-after-trim and envelopes)
 * without constructing a platform {@link com.thingworx.things.Thing}.
 */
class ScalarThingnamePreflightGateApplicationThingTest {

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
    }

    @Test
    void gate_null_is_value_required() {
        ScalarThingnamePreflight.ApplicationThingGateOutcome g =
                ScalarThingnamePreflight.gateApplicationThing("thingName", null);
        assertTrue(g.isError());
        assertTrue(g.errorJson.contains("THINGNAME_VALUE_REQUIRED"));
        assertNull(g.thing);
    }

    @Test
    void gate_whitespace_only_is_value_required() {
        ScalarThingnamePreflight.ApplicationThingGateOutcome g =
                ScalarThingnamePreflight.gateApplicationThing("thingName", "  \t  ");
        assertTrue(g.isError());
        assertTrue(g.errorJson.contains("THINGNAME_VALUE_REQUIRED"));
    }

    @Test
    void gate_trims_for_identity_supplied_value() {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ScalarThingnamePreflight.ApplicationThingGateOutcome g =
                ScalarThingnamePreflight.gateApplicationThing("thingName", "  display  ");
        assertTrue(g.isError());
        assertTrue(g.errorJson.contains("\"suppliedValue\":\"display\""));
        assertTrue(g.errorJson.contains("IDENTITY_RESOLUTION_REQUIRED"));
    }
}
