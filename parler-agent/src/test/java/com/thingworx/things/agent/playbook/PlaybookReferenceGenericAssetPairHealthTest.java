package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * Phase E reference playbook (see {@code docs/agent/playbook-generic-ops-foundation.md} §11 / §16): validates the
 * generic-ops variant authored under {@code docs/agent/} without touching User-owned {@code dev_data/}.
 */
class PlaybookReferenceGenericAssetPairHealthTest {

    @Test
    void crossAssetPairHealthGenericReference_passesValidatorWithMergedBuiltIns() throws Exception {
        Path root = repoRoot();
        String raw = Files.readString(
                root.resolve("docs/agent/playbook-engine-cross-asset-pair-health-generic.json"), StandardCharsets.UTF_8);
        PlaybookDocument doc = PlaybookDocument.parse(raw);
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(reg, ExtendedToolRegistrySnapshot.missing());
        PlaybookValidator.Result r = PlaybookValidator.validateDocument(doc, tools);
        assertTrue(r.valid(), String.join("; ", r.errors()));
    }

    private static Path repoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("parler-agent")) {
            return cwd.getParent();
        }
        return cwd;
    }
}
