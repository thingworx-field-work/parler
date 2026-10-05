package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class IdentityTypesJsonParserTest {

    @Test
    void parsesMinimalFixture_withoutResolvedParents() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/taxonomy/identity-types-minimal.json")) {
            assertTrue(in != null, "classpath fixture");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(out.configValid(), out.diagnostics().toString());
        assertEquals(4, out.entries().size());
        AssetTypeEntry first = out.entries().get(0);
        assertEquals("asset", first.entityKey());
        assertEquals("Jet Dryer", first.key());
        assertEquals("shape_as_type", first.representation());
        assertEquals("ThingShape", first.parentEntityType());
        assertEquals("PTCTDD.CellfabDataset.JetDryer_TS", first.parentEntityName());
        AssetTypeEntry withQp = out.entries().get(2);
        assertEquals("Workunit Jet Dryer", withQp.key());
        assertEquals("PTC.MfgModel.DefaultWorkunit_TT", withQp.queryParent().entityName());
        assertEquals("ThingTemplate", withQp.queryParent().entityType());
        AssetTypeEntry clinic = out.entries().get(3);
        assertEquals("Registered Clinic", clinic.key());
        assertEquals("template_as_type", clinic.representation());
        assertEquals("ThingTemplate", clinic.parentEntityType());
        assertEquals("PTC.MfgModel.DefaultWorkunit_TT", clinic.parentEntityName());
    }

    @Test
    void rejectsShapeAsTypeWithThingTemplateMembership() {
        String json = "{\"version\":2,\"entities\":[{\"key\":\"e\",\"types\":[{\"key\":\"Bad\",\"representation\":\"shape_as_type\",\"membership\":{\"entityType\":\"ThingTemplate\",\"entityName\":\"T.T\"},\"identity\":{\"properties\":[\"name\"],\"matchRules\":[\"exact\"]}}]}]}";
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(out.configValid());
        assertTrue(out.entries().isEmpty());
        assertTrue(out.diagnostics().stream().anyMatch(d -> "TAXONOMY_ROW_INVALID".equals(d.code())));
    }

    @Test
    void rejectsTemplateAsTypeWithThingShapeMembership() {
        String json = "{\"version\":2,\"entities\":[{\"key\":\"e\",\"types\":[{\"key\":\"Bad\",\"representation\":\"template_as_type\",\"membership\":{\"entityType\":\"ThingShape\",\"entityName\":\"S.S\"},\"identity\":{\"properties\":[\"name\"],\"matchRules\":[\"exact\"]}}]}]}";
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(out.configValid());
        assertTrue(out.entries().isEmpty());
        assertTrue(out.diagnostics().stream().anyMatch(d -> "TAXONOMY_ROW_INVALID".equals(d.code())));
    }

    @Test
    void modelSerialTemplateRowSkippedWithFutureIgnored() {
        String json = "{\"version\":2,\"entities\":[{\"key\":\"e\",\"types\":[{\"key\":\"R3\",\"representation\":\"model_serial_template\",\"membership\":{\"entityType\":\"ThingTemplate\",\"entityName\":\"T.T\"},\"identity\":{\"properties\":[\"serialNumber\"],\"matchRules\":[\"exact\"]}}]}]}";
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(out.configValid());
        assertTrue(out.entries().isEmpty());
        assertTrue(out.diagnostics().stream().anyMatch(d -> "TAXONOMY_FUTURE_FIELD_IGNORED".equals(d.code())));
    }

    @Test
    void rejectsNonVersion2() {
        IdentityTypesJsonParser.ParseOutcome out = IdentityTypesJsonParser.parse(
                "{\"version\":1,\"entities\":[]}", NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(!out.configValid());
        assertTrue(out.diagnostics().stream().anyMatch(d -> "TAXONOMY_CONFIG_INVALID".equals(d.code())));
    }
}
