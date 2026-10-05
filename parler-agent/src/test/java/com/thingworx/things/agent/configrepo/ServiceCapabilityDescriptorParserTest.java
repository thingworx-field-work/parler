package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ServiceCapabilityDescriptorParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyEntryWithoutCapabilityFields() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"name\":\"t\",\"whenToUse\":\"w\",\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"}}",
                ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r = ServiceCapabilityDescriptorParser.parse(entry, null);
        assertFalse(r.shouldSkip());
        assertTrue(r.metadata().isEmpty());
    }

    @Test
    void capabilityRequiresRisk() throws Exception {
        ObjectNode entry = MAPPER.readValue("{\"purpose\":\"x\"}", ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r = ServiceCapabilityDescriptorParser.parse(entry, null);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("require risk"));
    }

    @Test
    void validCapabilityParsesRiskAndPlaybookIndependentFields() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"MUTATING\",\"purpose\":\"Create WO\",\"dryRun\":{\"supported\":true,\"parameter\":\"dryRun\"},"
                        + "\"idempotency\":{\"mode\":\"CALLER_KEY\",\"parameter\":\"requestId\"},"
                        + "\"dataClassification\":[\"INTERNAL\"],\"admission\":\"LAZY\",\"enabled\":true}",
                ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r = ServiceCapabilityDescriptorParser.parse(entry, null);
        assertFalse(r.shouldSkip());
        ServiceCapabilityMetadata m = r.metadata().orElseThrow();
        assertEquals(ServiceCapabilityRisk.MUTATING, m.risk().orElseThrow());
        assertTrue(m.dryRunSupported());
        assertEquals("dryRun", m.dryRunParameter());
        assertEquals(ServiceIdempotencyMode.CALLER_KEY, m.idempotencyMode().orElseThrow());
        assertEquals(ServiceCapabilityAdmission.LAZY, m.admission().orElseThrow());
        assertEquals(ServiceCapabilityRuntimeState.ACTIVE, m.runtimeState());
    }

    @Test
    void forbiddenU8FieldsSkip() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"approvalWorkflow\":{}}", ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r = ServiceCapabilityDescriptorParser.parse(entry, null);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("approvalWorkflow"));
    }

    @Test
    void invalidRiskSkips() throws Exception {
        ObjectNode entry = MAPPER.readValue("{\"risk\":\"SIDE_EFFECT\"}", ObjectNode.class);
        assertTrue(ServiceCapabilityDescriptorParser.parse(entry, null).shouldSkip());
    }

    @Test
    void callerKeyRequiresParameter() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"MUTATING\",\"idempotency\":{\"mode\":\"CALLER_KEY\"}}", ObjectNode.class);
        assertTrue(ServiceCapabilityDescriptorParser.parse(entry, null).shouldSkip());
    }

    @Test
    void disabledSetsRuntimeState() throws Exception {
        ObjectNode entry = MAPPER.readValue("{\"risk\":\"READ_ONLY\",\"enabled\":false}", ObjectNode.class);
        ServiceCapabilityMetadata m =
                ServiceCapabilityDescriptorParser.parse(entry, null).metadata().orElseThrow();
        assertEquals(ServiceCapabilityRuntimeState.DISABLED, m.runtimeState());
    }

    @Test
    void serviceDefinitionParamMismatchSkips() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"MUTATING\",\"dryRun\":{\"supported\":true,\"parameter\":\"dryRun\"}}",
                ObjectNode.class);
        ServiceParameterLookup params = new ServiceParameterLookup() {
            @Override
            public boolean hasParameter(String parameterName) {
                return false;
            }

            @Override
            public String inputShapeDigest() {
                return "";
            }
        };
        ServiceCapabilityDescriptorParser.ParseResult r =
                ServiceCapabilityDescriptorParser.parse(entry, params);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("dryRun.parameter"));
    }

    @Test
    void schemaDigestMismatchSkips() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"inputSemantics\":{\"schemaDigest\":\"abc\"}}", ObjectNode.class);
        ServiceParameterLookup params = new ServiceParameterLookup() {
            @Override
            public boolean hasParameter(String parameterName) {
                return true;
            }

            @Override
            public String inputShapeDigest() {
                return "def";
            }
        };
        ServiceCapabilityDescriptorParser.ParseResult r =
                ServiceCapabilityDescriptorParser.parse(entry, params);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("SCHEMA_DRIFT"));
    }

    @Test
    void schemaDigestMatchSucceeds() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"inputSemantics\":{\"schemaDigest\":\"abc\"}}", ObjectNode.class);
        ServiceParameterLookup params = new ServiceParameterLookup() {
            @Override
            public boolean hasParameter(String parameterName) {
                return true;
            }

            @Override
            public String inputShapeDigest() {
                return "abc";
            }
        };
        assertFalse(ServiceCapabilityDescriptorParser.parse(entry, params).shouldSkip());
    }

    @Test
    void nonTextualSchemaDigestFailsClosedAndDoesNotBypassParity() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"inputSemantics\":{\"schemaDigest\":123}}", ObjectNode.class);
        ServiceParameterLookup params = new ServiceParameterLookup() {
            @Override
            public boolean hasParameter(String parameterName) {
                return true;
            }

            @Override
            public String inputShapeDigest() {
                return "live-digest";
            }
        };
        ServiceCapabilityDescriptorParser.ParseResult r =
                ServiceCapabilityDescriptorParser.parse(entry, params);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("schemaDigest must be a string"));
    }

    @Test
    void nonTextualIdempotencyParameterUnderNoneFailsClosed() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"idempotency\":{\"mode\":\"NONE\",\"parameter\":42}}",
                ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r =
                ServiceCapabilityDescriptorParser.parse(entry, null);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("idempotency.parameter"));
    }

    @Test
    void textualIdempotencyParameterUnderNoneStillFailsClosed() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"READ_ONLY\",\"idempotency\":{\"mode\":\"NONE\",\"parameter\":\"x\"}}",
                ObjectNode.class);
        ServiceCapabilityDescriptorParser.ParseResult r =
                ServiceCapabilityDescriptorParser.parse(entry, null);
        assertTrue(r.shouldSkip());
        assertTrue(r.skipReason().contains("must be omitted when mode=NONE"));
    }

    @Test
    void nonTextualPurposeCapabilityVersionAndProfileRefsFailClosed() throws Exception {
        assertTrue(ServiceCapabilityDescriptorParser.parse(
                MAPPER.readValue("{\"risk\":\"READ_ONLY\",\"purpose\":1}", ObjectNode.class), null)
                .shouldSkip());
        assertTrue(ServiceCapabilityDescriptorParser.parse(
                MAPPER.readValue("{\"risk\":\"READ_ONLY\",\"capabilityVersion\":2}", ObjectNode.class), null)
                .shouldSkip());
        assertTrue(ServiceCapabilityDescriptorParser.parse(
                MAPPER.readValue(
                        "{\"risk\":\"READ_ONLY\",\"inputSemantics\":{\"profileRef\":true}}", ObjectNode.class),
                null).shouldSkip());
        assertTrue(ServiceCapabilityDescriptorParser.parse(
                MAPPER.readValue(
                        "{\"risk\":\"READ_ONLY\",\"outputSemantics\":{\"schemaDigest\":[]}}", ObjectNode.class),
                null).shouldSkip());
        assertTrue(ServiceCapabilityDescriptorParser.parse(
                MAPPER.readValue(
                        "{\"risk\":\"READ_ONLY\",\"outputSemantics\":{\"profileRef\":{}}}", ObjectNode.class),
                null).shouldSkip());
    }

    @Test
    void nonTextualDryRunParameterFailsClosed() throws Exception {
        ObjectNode entry = MAPPER.readValue(
                "{\"risk\":\"MUTATING\",\"dryRun\":{\"supported\":true,\"parameter\":false}}",
                ObjectNode.class);
        assertTrue(ServiceCapabilityDescriptorParser.parse(entry, null).shouldSkip());
    }

    @Test
    void textOptionalDistinguishesAbsentFromWrongType() throws Exception {
        ObjectNode entry = MAPPER.createObjectNode();
        assertEquals(
                ServiceCapabilityDescriptorParser.OptionalText.Kind.ABSENT,
                ServiceCapabilityDescriptorParser.textOptional(entry, "purpose").kind());
        entry.put("purpose", "ok");
        assertEquals(
                ServiceCapabilityDescriptorParser.OptionalText.Kind.PRESENT,
                ServiceCapabilityDescriptorParser.textOptional(entry, "purpose").kind());
        entry.put("purpose", 9);
        assertEquals(
                ServiceCapabilityDescriptorParser.OptionalText.Kind.WRONG_TYPE,
                ServiceCapabilityDescriptorParser.textOptional(entry, "purpose").kind());
    }

    @Test
    void manifestSkipsForbiddenFieldWithoutInvalidatingFile() {
        com.thingworx.things.agent.skillregistry.RepositoryReader reader =
                new com.thingworx.things.agent.skillregistry.RepositoryReader() {
                    @Override
                    public com.thingworx.types.InfoTable getFileListing(String path, String nameMask) {
                        return new com.thingworx.types.InfoTable();
                    }

                    @Override
                    public String loadText(String path) {
                        if (ConfigurationRepositoryPaths.EXTENDED_TOOLS.equals(path)) {
                            return "{\"version\":1,\"tools\":[{\"name\":\"bad_cap\",\"whenToUse\":\"w\","
                                    + "\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"},\"hitl\":true,"
                                    + "\"risk\":\"READ_ONLY\",\"compensation\":{}}]}";
                        }
                        return null;
                    }
                };
        ExtendedToolRegistrySnapshot snap = ExtendedToolsManifest.load(
                reader, "MyAgent", null, Set.of(), org.slf4j.helpers.NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }
}
