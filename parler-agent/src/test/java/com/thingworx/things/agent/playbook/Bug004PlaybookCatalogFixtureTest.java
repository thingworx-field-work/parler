package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

/**
 * Bug 004: frozen copies under {@code src/test/resources/bug004-scpa-utilization-fixture/} (not {@code dev_data/})
 * must validate against the current four-tool utilization manifest once those tools are registered.
 */
class Bug004PlaybookCatalogFixtureTest {

    private static final String[] UTILIZATION_TOOL_NAMES = {
            "list_utilization_machines",
            "get_utilization_records",
            "get_utilization_state_summary",
            "get_utilization_overview"
    };

    @Test
    void playbookRegistryBuilder_loadsFrozenUtilizationCatalog() throws Exception {
        List<ToolDefinition> playbookTools = new ArrayList<>();
        Map<String, Object> emptyParams = new LinkedHashMap<>();
        emptyParams.put("type", "object");
        emptyParams.put("properties", new LinkedHashMap<String, Object>());
        for (String name : UTILIZATION_TOOL_NAMES) {
            playbookTools.add(new ToolDefinition(name, "fixture", emptyParams, true));
        }
        playbookTools.add(new ToolDefinition("resolve_thing", "fixture", emptyParams, true));

        RepositoryReader reader = new FixtureReader();
        PlaybookRegistrySnapshot snap = PlaybookRegistryBuilder.buildFromReader(reader, "Bug004Agent", playbookTools,
                NOPLogger.NOP_LOGGER);

        assertTrue(snap.isLoaded(), "diagnostics: " + snap.diagnostics());
        assertEquals(3, snap.catalogById().size());
        assertTrue(snap.documentsById().containsKey("utilization_summary"));
        assertTrue(snap.documentsById().containsKey("machine_utilization_summary"));
        assertTrue(snap.documentsById().containsKey("utilization_overview"));
    }

    private static InfoTable playbooksDirectoryListing() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String dir : new String[] {"machine_utilization_summary", "utilization_overview", "utilization_summary"}) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(dir));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
            it.addRow(vc);
        }
        return it;
    }

    private static final class FixtureReader implements RepositoryReader {
        @Override
        public InfoTable getFileListing(String path, String nameMask) {
            if (PlaybookIds.PLAYBOOK_ROOT.equals(path)) {
                return playbooksDirectoryListing();
            }
            return new InfoTable();
        }

        @Override
        public String loadText(String path) throws Exception {
            if (!path.startsWith(PlaybookIds.PLAYBOOK_ROOT + "/") || !path.endsWith("/" + PlaybookIds.PLAYBOOK_FILE_NAME)) {
                return null;
            }
            String dir = path.substring(PlaybookIds.PLAYBOOK_ROOT.length() + 1);
            dir = dir.substring(0, dir.indexOf('/'));
            String resource = "/bug004-scpa-utilization-fixture/" + dir + "/playbook.json";
            try (InputStream in = Bug004PlaybookCatalogFixtureTest.class.getResourceAsStream(resource)) {
                Objects.requireNonNull(in, "missing classpath resource: " + resource);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
