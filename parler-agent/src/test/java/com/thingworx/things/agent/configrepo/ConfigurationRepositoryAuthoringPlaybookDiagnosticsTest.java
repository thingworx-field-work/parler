package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.thingworx.things.agent.playbook.PlaybookRegistryDiagnostic;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;

/** {@link ConfigurationRepositoryAuthoringJson} playbook diagnostic severity for {@code ValidateAgentConfigurationRepository}. */
class ConfigurationRepositoryAuthoringPlaybookDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void playbookParseFailure_mapsToErrorItem() {
        List<PlaybookRegistryDiagnostic> dx = List.of(PlaybookRegistryDiagnostic.error(
                "/playbooks/beta/playbook.json", "PLAYBOOK_JSON_PARSE", "syntax error"));
        PlaybookRegistrySnapshot snap = PlaybookRegistrySnapshot.empty(Instant.now(), dx);
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendPlaybookRegistryValidationItems(snap, items, ew);
        assertEquals(1, ew[0]);
        assertEquals(0, ew[1]);
        assertEquals(1, items.size());
        assertEquals("error", items.get(0).get("severity").asText());
        assertEquals("/playbooks/beta/playbook.json", items.get(0).get("path").asText());
        assertEquals("PLAYBOOK_JSON_PARSE", items.get(0).get("code").asText());
        assertTrue(items.get(0).get("message").asText().contains("beta"));
    }

    @Test
    void playbookCap_mapsToWarningItem() {
        List<PlaybookRegistryDiagnostic> dx = List.of(PlaybookRegistryDiagnostic.warning("/playbooks",
                "PLAYBOOK_REGISTRY_CAP", "playbook registry capped at 32 packages; skipping further directories (next: z)"));
        PlaybookRegistrySnapshot snap = PlaybookRegistrySnapshot.empty(Instant.now(), dx);
        ArrayNode items = MAPPER.createArrayNode();
        int[] ew = {0, 0};
        ConfigurationRepositoryAuthoringJson.appendPlaybookRegistryValidationItems(snap, items, ew);
        assertEquals(0, ew[0]);
        assertEquals(1, ew[1]);
        assertEquals("warning", items.get(0).get("severity").asText());
        assertEquals("PLAYBOOK_REGISTRY_CAP", items.get(0).get("code").asText());
    }
}
