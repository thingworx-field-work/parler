package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.primitives.BooleanPrimitive;

class ExtendedToolThingnamePreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AgentToolContext.clear();
    }

    @Test
    void passes_when_thingname_is_visible_under_test_predicate() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests =
                "SE.CellFab.Model.Workunit.ORD-Contacting-01"::equals;
        ServiceDefinition sd = svc("GetUtilizationRecordsByMachine", paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "SE.CellFab.Model.Workunit.ORD-Contacting-01");
        assertNull(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
    }

    @Test
    void identity_resolution_required_when_not_exact_thing() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ServiceDefinition sd = svc("GetUtilizationRecordsByMachine", paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "ORD-Contacting-01");
        String err = ExtendedToolThingnamePreflight.checkJsonArgs(sd, args);
        assertNotNull(err);
        JsonNode n = MAPPER.readTree(err);
        assertEquals("error", n.path("status").asText());
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("Machine", n.path("parameterName").asText());
        assertEquals("ORD-Contacting-01", n.path("suppliedValue").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
    }

    @Test
    void recovery_hint_includes_asset_type_key_from_context() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        AgentToolContext.setLastResolvedAssetTypeKey("Contacting");
        ServiceDefinition sd = svc("GetUtilizationRecordsByMachine", paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "short");
        JsonNode n = MAPPER.readTree(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
        assertEquals("Contacting", n.path("recoveryHint").path("assetTypeKey").asText());
    }

    @Test
    void thingname_value_required_when_required_blank() throws Exception {
        ServiceDefinition sd = svc("GetUtilizationRecordsByMachine", paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "");
        JsonNode n = MAPPER.readTree(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
        assertEquals("THINGNAME_VALUE_REQUIRED", n.path("code").asText());
    }

    @Test
    void optional_blank_thingname_skips_preflight() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = "CANON"::equals;
        ServiceDefinition sd = svc("SomeService", paramThingname("OptionalThing", false), paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "CANON");
        assertNull(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
    }

    @Test
    void optional_blank_thingname_as_empty_string_skips_preflight() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ServiceDefinition sd = svc("SomeService", paramThingname("OptionalThing", false));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("OptionalThing", "");
        assertNull(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
    }

    @Test
    void optional_nonblank_invalid_thingname_still_requires_resolution() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ServiceDefinition sd = svc("SomeService", paramThingname("OptionalThing", false));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("OptionalThing", "not-a-real-thing");
        JsonNode n = MAPPER.readTree(ExtendedToolThingnamePreflight.checkJsonArgs(sd, args));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("OptionalThing", n.path("parameterName").asText());
    }

    @Test
    void idempotent_same_short_label_twice() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        ServiceDefinition sd = svc("GetUtilizationRecordsByMachine", paramThingname("Machine", true));
        ObjectNode args = MAPPER.createObjectNode();
        args.put("Machine", "ORD-Contacting-01");
        String a = ExtendedToolThingnamePreflight.checkJsonArgs(sd, args);
        String b = ExtendedToolThingnamePreflight.checkJsonArgs(sd, args);
        assertEquals(a, b);
        assertFalse(a.contains("success"));
    }

    private static ServiceDefinition svc(String name, FieldDefinition... params) {
        ServiceDefinition sd = new ServiceDefinition(name, "test");
        for (FieldDefinition fd : params) {
            sd.getParameters().addFieldDefinition(fd);
        }
        return sd;
    }

    private static FieldDefinition paramThingname(String name, boolean required) {
        FieldDefinition fd = new FieldDefinition(name, "", BaseTypes.THINGNAME);
        if (!required) {
            fd.getAspects().put("isRequired", new BooleanPrimitive(false));
        }
        return fd;
    }
}
