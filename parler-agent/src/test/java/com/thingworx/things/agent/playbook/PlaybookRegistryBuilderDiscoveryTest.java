package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/** Directory discovery and partial-success semantics for {@link PlaybookRegistryBuilder}. */
class PlaybookRegistryBuilderDiscoveryTest {

    private static List<ToolDefinition> minimalTools() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return List.of(new ToolDefinition("query_entities_by_taxonomy", "x", schema, true));
    }

    private static InfoTable dirs(String... names) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String n : names) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(n));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
            it.addRow(vc);
        }
        return it;
    }

    private static String minimalPlaybook(String id) {
        return "{\"schema\":\"" + PlaybookIds.SCHEMA_V1 + "\",\"id\":\"" + id + "\",\"title\":\"T " + id + "\","
                + "\"description\":\"\",\"whenToUse\":\"\",\"inputSchema\":{},\"execution\":{},"
                + "\"budgets\":{},\"nodes\":["
                + "{\"id\":\"n1\",\"kind\":\"tool_call\",\"dependsOn\":[],\"tool\":\"query_entities_by_taxonomy\","
                + "\"args\":{}},"
                + "{\"id\":\"end\",\"kind\":\"llm_summary\",\"dependsOn\":[\"n1\"],\"evidenceRefs\":[\"n1\"],"
                + "\"prompt\":\"p\"}],\"finalNode\":\"end\"}";
    }

    @Test
    void partialSuccess_oneInvalid_stillLoadsOther() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("/playbooks/alpha/playbook.json", minimalPlaybook("alpha"));
        files.put("/playbooks/beta/playbook.json", minimalPlaybook("wrong_id"));
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return dirs("beta", "alpha");
            }

            @Override
            public String loadText(String path) throws Exception {
                return files.get(path);
            }
        };
        PlaybookRegistrySnapshot snap =
                PlaybookRegistryBuilder.buildFromReader(reader, "Agent", minimalTools(), NOPLogger.NOP_LOGGER);
        assertTrue(snap.isLoaded());
        assertEquals(1, snap.catalogById().size());
        assertTrue(snap.catalogById().containsKey("alpha"));
        assertTrue(snap.diagnostics().stream().anyMatch(d -> d.contains("/playbooks/beta/playbook.json")));
    }

    @Test
    void partialSuccess_malformedJson_stillLoadsOther() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("/playbooks/alpha/playbook.json", minimalPlaybook("alpha"));
        files.put("/playbooks/beta/playbook.json", "{ not json");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return dirs("beta", "alpha");
            }

            @Override
            public String loadText(String path) throws Exception {
                return files.get(path);
            }
        };
        PlaybookRegistrySnapshot snap =
                PlaybookRegistryBuilder.buildFromReader(reader, "Agent", minimalTools(), NOPLogger.NOP_LOGGER);
        assertTrue(snap.isLoaded(), "alpha should load despite beta JSON failure");
        assertEquals(1, snap.catalogById().size());
        assertTrue(snap.catalogById().containsKey("alpha"));
        assertTrue(snap.registryDiagnostics().stream()
                .anyMatch(d -> "PLAYBOOK_JSON_PARSE".equals(d.code()) && d.path().contains("beta")));
    }

    @Test
    void dotPrefixedDirectory_skippedSilently() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("/playbooks/visible/playbook.json", minimalPlaybook("visible"));
        files.put("/playbooks/.hidden/playbook.json", minimalPlaybook("hidden"));
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return dirs(".hidden", "visible");
            }

            @Override
            public String loadText(String path) throws Exception {
                return files.get(path);
            }
        };
        PlaybookRegistrySnapshot snap =
                PlaybookRegistryBuilder.buildFromReader(reader, "Agent", minimalTools(), NOPLogger.NOP_LOGGER);
        assertTrue(snap.isLoaded());
        assertEquals(1, snap.catalogById().size());
        assertFalse(snap.diagnostics().stream().anyMatch(d -> d.contains("hidden")));
    }

    @Test
    void registryCap_stopsAt32Packages() throws Exception {
        Map<String, String> files = new HashMap<>();
        String[] names = new String[33];
        for (int i = 0; i < 33; i++) {
            String id = "p" + i;
            names[i] = id;
            files.put("/playbooks/" + id + "/playbook.json", minimalPlaybook(id));
        }
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return dirs(names);
            }

            @Override
            public String loadText(String path) throws Exception {
                return files.get(path);
            }
        };
        PlaybookRegistrySnapshot snap =
                PlaybookRegistryBuilder.buildFromReader(reader, "Agent", minimalTools(), NOPLogger.NOP_LOGGER);
        assertEquals(32, snap.catalogById().size());
        assertTrue(snap.diagnostics().stream().anyMatch(d -> d.contains("capped") || d.contains("cap")));
    }
}
