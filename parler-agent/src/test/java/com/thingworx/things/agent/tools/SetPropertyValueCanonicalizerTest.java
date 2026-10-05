package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;

/**
 * E3: strict pre-HITL requested-value canonicalization and BOOLEAN admission.
 */
class SetPropertyValueCanonicalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> true;
        SetPropertyValueExecutor.hitlPropertyBaseTypeOverrideForTests = (thing, prop) -> BaseTypes.BOOLEAN;
    }

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        SetPropertyValueExecutor.clearHitlTestOverrides();
        AgentToolContext.clear();
    }

    @Test
    void booleanAcceptsJsonTrueFalseAndExactText() throws Exception {
        assertCanonicalBoolean(true, "true");
        assertCanonicalBoolean(false, "false");
        assertCanonicalBoolean(true, true);
        assertCanonicalBoolean(false, false);
    }

    @Test
    void booleanRejectsCoercionTokens() throws Exception {
        for (String bad : new String[] {"maybe", "True", "FALSE", "1", "0", "yes", ""}) {
            SetPropertyValueExecutor.RequestedValueCanonicalization c =
                    SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.BOOLEAN, MAPPER.readTree(
                            bad.isEmpty() ? "\"\"" : "\"" + bad + "\""));
            assertTrue(c.isError(), "expected reject for " + bad);
            assertTrue(c.errorJson.contains("INVALID_BOOLEAN_VALUE"), c.errorJson);
        }
    }

    @Test
    void gateRejectsInvalidBooleanBeforeGatedCall() throws Exception {
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"value\":\"maybe\"}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        assertNull(g.gatedToolCall());
        JsonNode err = MAPPER.readTree(g.earlyErrorJson());
        assertEquals("INVALID_BOOLEAN_VALUE", err.path("code").asText());
    }

    @Test
    void gateEmitsCanonicalJsonBooleanOnRequestedTrack() throws Exception {
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"value\":\"true\"}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNull(g.earlyErrorJson());
        assertNotNull(g.gatedToolCall());
        JsonNode args = MAPPER.readTree(g.gatedToolCall().getArguments());
        assertEquals("Pump-1", args.path("thingName").asText());
        assertEquals("Enabled", args.path("propertyName").asText());
        assertEquals("BOOLEAN", args.path("baseType").asText());
        assertTrue(args.path("value").isBoolean());
        assertTrue(args.path("value").asBoolean());
        assertFalse(args.path("value").isTextual());
    }

    /**
     * Permanent E3 requested-track identity: gated ToolCall value re-canonicalizes to the
     * same typed JSON the approved-write path consumes (observation track is not this value).
     */
    @Test
    void permanentParity_requestedTrackIdentity_gateEqualsApprovedWriteCanonical() throws Exception {
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"value\":\"false\"}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNull(g.earlyErrorJson());
        JsonNode gatedArgs = MAPPER.readTree(g.gatedToolCall().getArguments());
        assertTrue(gatedArgs.path("value").isBoolean());
        assertFalse(gatedArgs.path("value").asBoolean());

        SetPropertyValueExecutor.RequestedValueCanonicalization fromGated =
                SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.BOOLEAN, gatedArgs.get("value"));
        assertFalse(fromGated.isError());
        assertTrue(fromGated.canonicalValue.isBoolean());
        assertEquals(gatedArgs.path("value").asBoolean(), fromGated.canonicalValue.asBoolean());
        assertEquals(gatedArgs.path("value"), fromGated.canonicalValue);
    }

    @Test
    void hintMismatchRejectedBeforePending() throws Exception {
        SetPropertyValueExecutor.hitlPropertyBaseTypeOverrideForTests = (t, p) -> BaseTypes.NUMBER;
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Temp\",\"baseType\":\"BOOLEAN\",\"value\":1}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNotNull(g.earlyErrorJson());
        assertNull(g.gatedToolCall());
        assertEquals("BASETYPE_HINT_MISMATCH", MAPPER.readTree(g.earlyErrorJson()).path("code").asText());
    }

    @Test
    void readOnlyPropertyRejectedBeforePending() throws Exception {
        SetPropertyValueExecutor.hitlPropertyReadOnlyOverrideForTests = (t, p) -> "Mode".equals(p);
        ToolCall call = new ToolCall("1", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Mode\",\"value\":true}");
        SetPropertyValueExecutor.SetPropertyHitlGate g = SetPropertyValueExecutor.gateSetPropertyValueForHitl(call);
        assertNull(g.gatedToolCall());
        assertEquals("PROPERTY_READ_ONLY", MAPPER.readTree(g.earlyErrorJson()).path("code").asText());

        ToolCall writable = new ToolCall("2", "set_property_value",
                "{\"thingName\":\"Pump-1\",\"propertyName\":\"Enabled\",\"value\":true}");
        assertNotNull(SetPropertyValueExecutor.gateSetPropertyValueForHitl(writable).gatedToolCall());
    }

    @Test
    void integerAndNumberRejectGarbage() throws Exception {
        assertTrue(SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.INTEGER,
                MAPPER.readTree("\"x\"")).isError());
        assertTrue(SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.NUMBER,
                MAPPER.readTree("\"nan\"")).isError());
        assertFalse(SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.INTEGER,
                MAPPER.readTree("3")).isError());
    }

    private static void assertCanonicalBoolean(boolean expected, Object input) throws Exception {
        JsonNode node = input instanceof Boolean
                ? MAPPER.getNodeFactory().booleanNode((Boolean) input)
                : MAPPER.readTree("\"" + input + "\"");
        SetPropertyValueExecutor.RequestedValueCanonicalization c =
                SetPropertyValueExecutor.canonicalizeRequestedValue(BaseTypes.BOOLEAN, node);
        assertFalse(c.isError(), String.valueOf(c.errorJson));
        assertTrue(c.canonicalValue.isBoolean());
        assertEquals(expected, c.canonicalValue.asBoolean());
    }
}
