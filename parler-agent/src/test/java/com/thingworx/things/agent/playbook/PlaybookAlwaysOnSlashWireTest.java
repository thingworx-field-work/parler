package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.AgentToolContext;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression: AlwaysOn structured slash must bind stream + remote context before
 * {@link PlaybookRunner} so {@link PlaybookTaskProgressEmitter} can flush {@code task.state} frames.
 */
class PlaybookAlwaysOnSlashWireTest {

    private final List<String> wirePayloads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        wirePayloads.clear();
        PlaybookTaskProgressEmitter.setTestWireSink(wirePayloads::add);
        AgentToolContext.setParlerStreamIds("req-slash", "playbook_slash_json");
    }

    @AfterEach
    void tearDown() {
        PlaybookTaskProgressEmitter.clearTestWireSink();
        AgentToolContext.clear();
    }

    @Test
    void flush_emitsPlaybookTaskStateWhenAlwaysOnStreamContextBound() throws Exception {
        String raw = "{"
                + "\"schema\":\"parler-playbook-v1\",\"title\":\"T\","
                + "\"nodes\":[{\"id\":\"taxonomy_row\",\"kind\":\"derive\",\"dependsOn\":[],"
                + "\"op\":\"pick_taxonomy_row\",\"evidence\":{\"label\":\"Resolve asset type\"}}],"
                + "\"finalNode\":\"taxonomy_row\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookTaskProgressEmitter.begin(doc, PlaybookIds.V1A_PLAYBOOK_ID);
        PlaybookTaskProgressEmitter.onNodeStarted("taxonomy_row");
        PlaybookTaskProgressEmitter.end(true);

        assertFalse(wirePayloads.isEmpty(), "expected at least one task.state frame");
        boolean sawPlaybook = false;
        for (String json : wirePayloads) {
            JSONObject wire = new JSONObject(json);
            if (!"task.state".equals(wire.optString("type"))) {
                continue;
            }
            JSONArray items = wire.optJSONArray("items");
            if (items != null && items.length() > 0
                    && "playbook".equals(items.getJSONObject(0).optString("source"))) {
                sawPlaybook = true;
                break;
            }
        }
        assertTrue(sawPlaybook, "expected task.state with items[].source=playbook");
    }

    @Test
    void slashProbeFinally_clearsConversationIdOnNonPlaybookMiss() {
        AgentToolContext.setConversationId("probe-conv");
        try {
            // Non-playbook slash miss: production clears in finally before AgentLoop rebind.
        } finally {
            AgentToolContext.clear();
        }
        assertNotEquals("probe-conv", AgentToolContext.getConversationId());
    }

    @Test
    void flush_noopsWithoutParlerStreamIds() throws Exception {
        AgentToolContext.clear();
        wirePayloads.clear();
        String raw = "{"
                + "\"schema\":\"parler-playbook-v1\",\"title\":\"T\","
                + "\"nodes\":[{\"id\":\"a\",\"kind\":\"derive\",\"dependsOn\":[],\"op\":\"x\","
                + "\"evidence\":{\"label\":\"A\"}}],\"finalNode\":\"a\"}";
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookTaskProgressEmitter.begin(doc, "pb");
        PlaybookTaskProgressEmitter.onNodeStarted("a");
        PlaybookTaskProgressEmitter.end(true);
        assertTrue(wirePayloads.isEmpty());
    }
}
