package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guard: v1 host-context / inject strings must not appear in live normative or runtime paths.
 */
class HostContextStaleGuidanceTest {

    private static final Pattern[] FORBIDDEN = new Pattern[] {
            Pattern.compile("HostScopeJsonUplink"),
            Pattern.compile("ParlerHostScopeSystemMeta"),
            Pattern.compile("mashup-host-context\\.md"),
            Pattern.compile("hostContext\\.hierarchy_scope"),
    };

    private static final Path REPO = Paths.get("..").toAbsolutePath().normalize();

    @Test
    void livePaths_doNotContainRemovedV1HostContextGuidance() throws IOException {
        List<Path> files = List.of(
                REPO.resolve("CONTRACTS/API_CONTRACT.md"),
                REPO.resolve("CONTRACTS/UI_CLIENT_PROTOCOL.md"),
                REPO.resolve("docs/agent/AGENT-CONTEXT.md"),
                REPO.resolve("docs/architecture/entity-hierarchy.md"),
                REPO.resolve("docs/architecture/hierarchy-network-services.md"),
                REPO.resolve("parler-agent/src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt"));

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (file.getFileName().toString().equals("API_CONTRACT.md")) {
                int cut = text.indexOf("\n## Versioning");
                if (cut > 0) {
                    text = text.substring(0, cut);
                }
            }
            for (Pattern p : FORBIDDEN) {
                if (p.matcher(text).find()) {
                    hits.add(file + " matches " + p.pattern());
                }
            }
        }
        scanJavaTree(REPO.resolve("parler-agent/src/main/java"), hits);
        if (!hits.isEmpty()) {
            fail("Stale v1 host-context guidance found:\n" + String.join("\n", hits));
        }
    }

    private static void scanJavaTree(Path root, List<String> hits) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (Pattern p : FORBIDDEN) {
                        if (p.matcher(text).find()) {
                            hits.add(file + " matches " + p.pattern());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
