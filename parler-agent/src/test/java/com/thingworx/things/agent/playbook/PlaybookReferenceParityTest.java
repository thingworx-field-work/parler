package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookReferenceParityTest {

    @Test
    void referenceDag_matchesRuntimeCopy() throws Exception {
        Path root = repoRoot();
        String reference = Files.readString(
                root.resolve("docs/agent/playbook-engine-cross-region-health.json"), StandardCharsets.UTF_8);
        String runtime = Files.readString(
                root.resolve("dev_data/playbooks/cross_region_health/playbook.json"), StandardCharsets.UTF_8);
        assertEquals(new JSONObject(reference).toString(), new JSONObject(runtime).toString());
    }

    private static Path repoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("parler-agent")) {
            return cwd.getParent();
        }
        return cwd;
    }
}
