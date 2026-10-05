package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.types.BaseTypes;

/**
 * Offline tests for {@link ProtectedValuePolicy} (no {@code LogUtilities} / platform Thing fixture).
 */
class ProtectedValuePolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void invokeServiceSuppliesPassword_trueWhenKeyPresentIncludingNull() throws Exception {
        ServiceDefinition sd = svc("SomeService", param("secret", BaseTypes.PASSWORD), param("x", BaseTypes.STRING));
        ObjectNode p = MAPPER.createObjectNode();
        p.putNull("secret");
        assertTrue(ProtectedValuePolicy.invokeServiceSuppliesPasswordParameter(sd, p));
    }

    @Test
    void invokeServiceSuppliesPassword_falseWhenPasswordKeyOmitted() throws Exception {
        ServiceDefinition sd = svc("SomeService", param("secret", BaseTypes.PASSWORD));
        ObjectNode p = MAPPER.createObjectNode();
        assertFalse(ProtectedValuePolicy.invokeServiceSuppliesPasswordParameter(sd, p));
    }

    @Test
    void invokeServiceSuppliesPassword_falseForNonPasswordParams() throws Exception {
        ServiceDefinition sd = svc("SomeService", param("label", BaseTypes.STRING));
        ObjectNode p = MAPPER.createObjectNode();
        p.put("label", "hi");
        assertFalse(ProtectedValuePolicy.invokeServiceSuppliesPasswordParameter(sd, p));
    }

    @Test
    void maskConstantMatchesContract() {
        assertEquals("***", ProtectedValuePolicy.mask());
        assertEquals("***", ProtectedValuePolicy.MASK);
    }

    @Test
    void sensitiveKeyHeuristic_token_password_secret() {
        assertTrue(ProtectedValuePolicy.isSensitiveKeyName("apiKey"));
        assertTrue(ProtectedValuePolicy.isSensitiveKeyName("user_password"));
        assertFalse(ProtectedValuePolicy.isSensitiveKeyName("displayName"));
    }

    @Test
    void redactPersistedToolArgumentsJson_deepNested_apiKey() throws Exception {
        String raw = "{\"parameters\":{\"apiKey\":\"sekret\",\"label\":\"ok\"}}";
        String out = ProtectedValuePolicy.redactPersistedToolArgumentsJson(raw, "invoke_service");
        ObjectNode parsed = (ObjectNode) MAPPER.readTree(out);
        assertEquals("***", parsed.get("parameters").get("apiKey").asText());
        assertEquals("ok", parsed.get("parameters").get("label").asText());
    }

    @Test
    void redactPersisted_failClosed_onMalformedJson() {
        String malicious = "{\"secret\":\"x\",\"truncated\":";
        assertEquals(ProtectedValuePolicy.PERSIST_REDACTION_FAILED_JSON,
                ProtectedValuePolicy.redactPersistedToolArgumentsJson(malicious, "invoke_service"));
    }

    @Test
    void redactPersisted_failClosed_whenRootNotObject() {
        assertEquals(ProtectedValuePolicy.PERSIST_REDACTION_FAILED_JSON,
                ProtectedValuePolicy.redactPersistedToolArgumentsJson("[\"no\",\"object\"]", "invoke_service"));
    }

    @Test
    void serviceDefinitionReturnsPassword_whenResultTypePassword() throws Exception {
        ServiceDefinition sd = new ServiceDefinition("_tool_secret", "test");
        FieldDefinition rt = new FieldDefinition("result", "", BaseTypes.PASSWORD);
        sd.getClass().getMethod("setResultType", FieldDefinition.class).invoke(sd, rt);
        assertTrue(ProtectedValuePolicy.serviceDefinitionReturnsPassword(sd));
        assertTrue(ProtectedValuePolicy.shouldOmitCustomToolFromDiscovery(sd));
    }

    @Test
    void shouldMaskCustomToolResult_whenResultDeclaredPassword() throws Exception {
        ServiceDefinition sd = new ServiceDefinition("_tool_x", "t");
        FieldDefinition rt = new FieldDefinition("result", "", BaseTypes.PASSWORD);
        sd.getClass().getMethod("setResultType", FieldDefinition.class).invoke(sd, rt);
        assertTrue(ProtectedValuePolicy.shouldMaskCustomToolResult(sd, new StringPrimitive("x")));
    }

    @Test
    void maskResolvedInvokePassword_masksTopLevelBeforeNormalizer() {
        ServiceDefinition sd = svc("SomeService", param("pin", BaseTypes.PASSWORD));
        ObjectNode root = MAPPER.createObjectNode();
        root.put("pin", "clear-secret");
        ObjectNode params = MAPPER.createObjectNode();
        ProtectedValuePolicy.maskResolvedInvokePasswordParametersForPersistence(root, params, sd);
        assertEquals(ProtectedValuePolicy.MASK, root.get("pin").asText());
    }

    @Test
    void maskResolvedInvokePassword_masksUnderParametersAndRoot() {
        ServiceDefinition sd = svc("SomeService", param("pin", BaseTypes.PASSWORD));
        ObjectNode root = MAPPER.createObjectNode();
        root.put("pin", "top");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("pin", "nested");
        ProtectedValuePolicy.maskResolvedInvokePasswordParametersForPersistence(root, params, sd);
        assertEquals(ProtectedValuePolicy.MASK, root.get("pin").asText());
        assertEquals(ProtectedValuePolicy.MASK, params.get("pin").asText());
    }

    @Test
    void redactInvokeParameters_masksSensitiveKeys() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("password", "sekret");
        params.put("label", "ok");
        root.set("parameters", params);
        String out = ProtectedValuePolicy.redactToolArgumentsForApprovalPreview(MAPPER.writeValueAsString(root),
                "invoke_service");
        ObjectNode parsed = (ObjectNode) MAPPER.readTree(out);
        assertEquals("***", parsed.get("parameters").get("password").asText());
        assertEquals("ok", parsed.get("parameters").get("label").asText());
    }

    private static ServiceDefinition svc(String name, FieldDefinition... params) {
        ServiceDefinition sd = new ServiceDefinition(name, "test");
        for (FieldDefinition fd : params) {
            sd.getParameters().addFieldDefinition(fd);
        }
        return sd;
    }

    private static FieldDefinition param(String name, BaseTypes baseType) {
        return new FieldDefinition(name, "", baseType);
    }
}
