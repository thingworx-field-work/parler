package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

class ConfigurationRepositoryAuthoringSemanticProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String readFixture(String name) throws Exception {
        String path = "/nearterm/semantics/" + name;
        try (InputStream in = ConfigurationRepositoryAuthoringSemanticProfileTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static RepositoryReader readerWithProfile(String profileJson) {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON.equals(path)) {
                    return profileJson;
                }
                return null;
            }
        };
    }

    @Test
    void missingProfile_isNotConfigured_notError() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode semanticProfile = root.putObject("semanticProfile");
        ArrayNode items = root.putArray("items");
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendSemanticProfileValidation(readerWithProfile(null),
                NOPLogger.NOP_LOGGER, "Agent", semanticProfile, items, ew);
        assertEquals("not_configured", semanticProfile.get("status").asText());
        assertEquals(0, ew[0]);
        assertEquals(0, items.size());
    }

    @Test
    void validExample_loadsWithoutErrors() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode semanticProfile = root.putObject("semanticProfile");
        ArrayNode items = root.putArray("items");
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendSemanticProfileValidation(
                readerWithProfile(readFixture("stacking-robot.example.json")), NOPLogger.NOP_LOGGER, "Agent",
                semanticProfile, items, ew);
        assertEquals("loaded", semanticProfile.get("status").asText());
        assertTrue(semanticProfile.get("loaded").asBoolean());
        assertEquals(0, ew[0]);
        assertEquals("cell-a-operations", semanticProfile.get("profileId").asText());
    }

    @Test
    void invalidUnit_reportsErrorItem() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode semanticProfile = root.putObject("semanticProfile");
        ArrayNode items = root.putArray("items");
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendSemanticProfileValidation(
                readerWithProfile(readFixture("invalid-unit-mismatch.json")), NOPLogger.NOP_LOGGER, "Agent",
                semanticProfile, items, ew);
        assertTrue(ew[0] > 0);
        assertTrue(items.size() > 0);
        assertEquals(ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON, items.get(0).get("path").asText());
    }
}
