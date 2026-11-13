package com.thingworx.things.agent.tools;

import org.json.JSONObject;

import com.thingworx.common.utils.EnhancedDataOutputStream;
import com.thingworx.common.utils.SerializationOptions;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * A JSON cell that holds an {@link org.json.JSONObject}, the value type the SDK's {@code JSONPrimitive} returns from
 * {@code getValue()}. The local library's {@code JSONPrimitive} cannot be constructed in unit tests (its static
 * initializer needs a Jackson Joda module that is not on the test classpath), so this stands in for it; the User's
 * live check confirms the real type on a server.
 */
final class JsonObjectCell implements IPrimitiveType<JsonObjectCell, JSONObject> {
    private static final long serialVersionUID = 1L;
    private JSONObject value;

    JsonObjectCell(JSONObject value) {
        this.value = value;
    }

    @Override
    public JsonObjectCell clone() {
        return new JsonObjectCell(value);
    }

    @Override
    public Object getSerializedValue() {
        return value;
    }

    @Override
    public String getStringValue() {
        return String.valueOf(value);
    }

    @Override
    public JSONObject getValue() {
        return value;
    }

    @Override
    public void setValue(JSONObject v) {
        this.value = v;
    }

    @Override
    public BaseTypes getBaseType() {
        return BaseTypes.JSON;
    }

    @Override
    public Object toJSON(SerializationOptions options) {
        return value;
    }

    @Override
    public org.w3c.dom.Node toXML(org.w3c.dom.Element parent, SerializationOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeToStream(EnhancedDataOutputStream out, SerializationOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeToGenerator(com.fasterxml.jackson.core.JsonGenerator gen, SerializationOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Object o) {
        return 0;
    }

    @Override
    public int compare(JsonObjectCell a, JsonObjectCell b) {
        return 0;
    }
}
