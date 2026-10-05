package com.thingworx.things.agent.compaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;

/** Writes an export snapshot under build/ when requested; never overwrites pinned `census-baseline.json`. */
class M1aBaselineExportOnceTest {

    @Test
    void exportBaselineSnapshotWhenRequested() throws Exception {
        if (!"1".equals(System.getenv("M1A_EXPORT_BASELINE_V2"))) {
            return;
        }
        Path exportDir = Path.of("build/context-compaction/m1a");
        Files.createDirectories(exportDir);
        Path target = exportDir.resolve("census-baseline-export.json");
        Files.writeString(target, M1aBaselineSupport.fullBaselineJson());
        Path pin = Path.of("src/test/resources/context-compaction/m1a/census-baseline.json");
        if (!Files.exists(pin)) {
            Files.copy(target, pin, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
