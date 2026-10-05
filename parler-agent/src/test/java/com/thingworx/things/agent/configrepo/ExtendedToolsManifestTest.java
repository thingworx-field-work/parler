package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.playbook.PlaybookToolDefinitionsMerge;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;
import com.thingworx.types.InfoTable;

class ExtendedToolsManifestTest {

    @Test
    void unsupported_version_marks_invalid() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "{\"version\":2,\"tools\":[]}";
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertTrue(snap.isFileInvalid());
    }

    @Test
    void missing_file_is_not_invalid() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertTrue(snap.isFileMissing());
    }

    @Test
    void extended_tool_name_conflicting_with_executor_only_alias_skips_entry_not_whole_file() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (ConfigurationRepositoryPaths.EXTENDED_TOOLS.equals(path)) {
                    return "{\"version\":1,\"tools\":[{\"name\":\"query_numeric_property_history\","
                            + "\"whenToUse\":\"x\",\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"},\"hitl\":true}]}";
                }
                return null;
            }
        };
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg);
        java.util.HashSet<String> reserved = new java.util.HashSet<>();
        for (ToolDefinition td : reg.getAllDefinitions()) {
            reserved.add(td.getName());
        }
        reserved.addAll(reg.getExecutorOnlyAliases());
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, reserved, NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }

    @Test
    void blank_target_entity_skips_entry_not_whole_file() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "{\"version\":1,\"tools\":[{\"name\":\"t1\",\"whenToUse\":\"w\",\"target\":{\"entityName\":\"  \","
                        + "\"serviceName\":\"S\"},\"hitl\":true}]}";
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }

    @Test
    void executorOnly_non_boolean_skips_entry_not_whole_file() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (ConfigurationRepositoryPaths.EXTENDED_TOOLS.equals(path)) {
                    return "{\"version\":1,\"tools\":[{\"name\":\"bad_ext\",\"whenToUse\":\"w\","
                            + "\"executorOnly\":\"notbool\","
                            + "\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"},\"hitl\":true}]}";
                }
                return null;
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }

    @Test
    void capability_invalid_risk_skips_entry_not_whole_file() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "{\"version\":1,\"tools\":[{\"name\":\"t1\",\"whenToUse\":\"w\","
                        + "\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"},\"hitl\":true,"
                        + "\"risk\":\"NOT_A_RISK\"}]}";
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }

    @Test
    void capability_non_textual_schemaDigest_skips_entry_not_whole_file() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "{\"version\":1,\"tools\":[{\"name\":\"t1\",\"whenToUse\":\"w\","
                        + "\"target\":{\"entityName\":\"T\",\"serviceName\":\"S\"},\"hitl\":true,"
                        + "\"risk\":\"READ_ONLY\",\"inputSemantics\":{\"schemaDigest\":123}}]}";
            }
        };
        ExtendedToolRegistrySnapshot snap =
                ExtendedToolsManifest.load(reader, "MyAgent", null, Set.of(), NOPLogger.NOP_LOGGER);
        assertFalse(snap.isFileInvalid());
        assertTrue(snap.allByName().isEmpty());
    }

    @Test
    void playbook_merge_includes_executor_only_extended_tools() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ToolDefinition("builtin", "d", Map.of("type", "object")), c -> "{}");
        ToolDefinition extHidden = new ToolDefinition("extHidden", "d", Map.of("type", "object"));
        ToolDefinition extShown = new ToolDefinition("extShown", "d", Map.of("type", "object"));
        ExtendedToolRegistrySnapshot snap = ExtendedToolRegistrySnapshot.ok(List.of(
                new ExtendedToolDefinition("extHidden", "", "", "Thing1", "S1", false, true, extHidden),
                new ExtendedToolDefinition("extShown", "", "", "Thing1", "S2", false, false, extShown)));
        List<ToolDefinition> merged = PlaybookToolDefinitionsMerge.merge(reg, snap);
        assertEquals(3, merged.size());
        assertTrue(merged.stream().anyMatch(d -> "extShown".equals(d.getName())));
        assertTrue(merged.stream().anyMatch(d -> "extHidden".equals(d.getName())));
    }
}
