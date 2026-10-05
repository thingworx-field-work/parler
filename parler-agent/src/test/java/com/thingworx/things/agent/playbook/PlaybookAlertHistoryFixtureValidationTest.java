package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * Phase D slice without touching User-owned {@code dev_data/}: validates a minimal static playbook
 * that calls {@code query_alert_history}.
 */
class PlaybookAlertHistoryFixtureValidationTest {

    @Test
    void minimalAlertHistoryFixture_passesValidatorWithMergedBuiltIns() throws Exception {
        String raw = readFixtureUtf8("playbook-builtin-capability-expansion/minimal_alert_history.playbook.json");
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    private static String readFixtureUtf8(String classpathRelative) throws Exception {
        try (InputStream in = PlaybookAlertHistoryFixtureValidationTest.class.getClassLoader()
                .getResourceAsStream(classpathRelative)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource: " + classpathRelative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
