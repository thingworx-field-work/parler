package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ArtifactCacheProductionWiringGuardTest {

    @Test
    void productionSourcesContainNoAutomaticMemoryCacheOrFallbackPrincipal() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                assertFalse(text.contains("new InMemoryArtifactPayloadStore"), source.toString());
            }
        }

        String hub = Files.readString(sourceRoot.resolve(
                "com/thingworx/things/agent/cache/TabularArtifactHub.java"), StandardCharsets.UTF_8);
        for (String forbidden : new String[] {
                "JVM_FALLBACK",
                "jvmFallbackCache",
                "FALLBACK_PRINCIPAL",
                "ensureFallbackPrincipalIfAbsent"
        }) {
            assertFalse(hub.contains(forbidden), "production Hub retains forbidden symbol " + forbidden);
        }
    }
}
