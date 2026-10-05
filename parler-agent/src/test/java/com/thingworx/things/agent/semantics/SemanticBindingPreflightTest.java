package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticBindingPreflightTest {

    @Test
    void checkProperty_nullThing_targetNotFound() {
        SemanticBindingPreflight.Result r = SemanticBindingPreflight.checkProperty(null, "Wrst1");
        assertEquals(SemanticBindingPreflight.Status.TARGET_NOT_FOUND, r.status());
        assertFalse(r.ok());
    }

    @Test
    void checkProperty_blankName_targetNotFound() {
        SemanticBindingPreflight.Result r = SemanticBindingPreflight.checkProperty(null, "  ");
        assertEquals(SemanticBindingPreflight.Status.TARGET_NOT_FOUND, r.status());
    }

    @Test
    void check_nullBinding_unavailable() {
        SemanticBindingPreflight.Result r = SemanticBindingPreflight.check(null, null);
        assertEquals(SemanticBindingPreflight.Status.UNAVAILABLE, r.status());
    }

    @Test
    void checkService_nullThing_targetNotFound() {
        SemanticRoleBinding binding = SemanticRoleBinding.service("CellAAnalytics", "GetEnergyPerCycle", "value");
        SemanticBindingPreflight.Result r = SemanticBindingPreflight.check(null, binding);
        assertEquals(SemanticBindingPreflight.Status.TARGET_NOT_FOUND, r.status());
        assertTrue(r.message().contains("Thing"));
    }

    @Test
    void checkServiceThingName_exactMatch_ok() {
        SemanticBindingPreflight.Result r =
                SemanticBindingPreflight.checkServiceThingName("CellAAnalytics", "CellAAnalytics");
        assertEquals(SemanticBindingPreflight.Status.OK, r.status());
        assertTrue(r.ok());
    }

    @Test
    void checkServiceThingName_mismatched_targetNotFound() {
        SemanticBindingPreflight.Result r =
                SemanticBindingPreflight.checkServiceThingName("OtherThing", "CellAAnalytics");
        assertEquals(SemanticBindingPreflight.Status.TARGET_NOT_FOUND, r.status());
        assertFalse(r.ok());
        assertTrue(r.message().contains("CellAAnalytics"));
        assertTrue(r.message().contains("OtherThing"));
    }

    @Test
    void checkServiceThingName_blankBinding_targetNotFound() {
        SemanticBindingPreflight.Result r = SemanticBindingPreflight.checkServiceThingName("CellAAnalytics", "  ");
        assertEquals(SemanticBindingPreflight.Status.TARGET_NOT_FOUND, r.status());
    }
}

