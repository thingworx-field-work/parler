package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.primitives.GUIDPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.LocationPrimitive;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Pins typed {@code invoke_service} parameter coercion for non-string primitive families. Plain JUnit cannot
 * instantiate every ThingWorx primitive here because some platform utility static initializers require the full
 * container runtime; these tests still pin the regression boundary that caused JSON service parameters to be sent as
 * {@code StringPrimitive(node.toString())}.
 */
class InvokeServiceArgumentCoercionPrimitiveTypesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonObjectConvertsToJSONObjectNotStringifiedJson() throws Exception {
        Object converted = InvokeServiceArgumentCoercion.jsonNodeToPlatformObject(
                MAPPER.readTree("{\"selectedNetworkNode\":\"SE.CellFab.Model.Site.BOS-CellFab\"}"));

        JSONObject json = assertInstanceOf(JSONObject.class, converted);
        assertEquals("SE.CellFab.Model.Site.BOS-CellFab", json.getString("selectedNetworkNode"));
        assertNotEquals("{\"selectedNetworkNode\":\"SE.CellFab.Model.Site.BOS-CellFab\"}", converted);
    }

    @Test
    void jsonArrayConvertsToJSONArrayNotStringifiedJson() throws Exception {
        Object converted = InvokeServiceArgumentCoercion.jsonNodeToPlatformObject(
                MAPPER.readTree("[{\"name\":\"A\"},{\"name\":\"B\"}]"));

        JSONArray array = assertInstanceOf(JSONArray.class, converted);
        assertEquals(2, array.length());
        assertEquals("A", array.getJSONObject(0).getString("name"));
        assertNotEquals("[{\"name\":\"A\"},{\"name\":\"B\"}]", converted);
    }

    @Test
    void variantEnvelopeShapedObjectStillCrossesBoundaryAsJsonObject() throws Exception {
        Object converted = InvokeServiceArgumentCoercion.jsonNodeToPlatformObject(
                MAPPER.readTree("{\"baseType\":\"INTEGER\",\"value\":42}"));

        JSONObject json = assertInstanceOf(JSONObject.class, converted);
        assertEquals("INTEGER", json.getString("baseType"));
        assertEquals(42, json.getInt("value"));
    }

    @Test
    void guidCoercesToGuidPrimitive() {
        String guid = "00000000-0000-0000-0000-000000000001";
        IPrimitiveType result = InvokeServiceArgumentCoercion.coerce("id", new TextNode(guid), BaseTypes.GUID);

        GUIDPrimitive primitive = assertInstanceOf(GUIDPrimitive.class, result);
        assertEquals(guid, primitive.getValue());
    }

    @Test
    void locationObjectCoercesToLocationPrimitive() throws Exception {
        IPrimitiveType result = InvokeServiceArgumentCoercion.coerce("where",
                MAPPER.readTree("{\"latitude\":42.1,\"longitude\":-71.2,\"elevation\":3.0}"),
                BaseTypes.LOCATION);

        LocationPrimitive primitive = assertInstanceOf(LocationPrimitive.class, result);
        assertEquals(42.1, primitive.getValue().getLatitude());
        assertEquals(-71.2, primitive.getValue().getLongitude());
        assertEquals(3.0, primitive.getValue().getElevation());
    }
}
