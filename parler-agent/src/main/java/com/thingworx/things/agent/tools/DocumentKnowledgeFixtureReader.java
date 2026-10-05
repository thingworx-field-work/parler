package com.thingworx.things.agent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads document-knowledge package files from a local directory (unit tests / fixtures).
 */
public final class DocumentKnowledgeFixtureReader {

    public static final class ChunkLoadResult {
        private final List<DocumentKnowledgeChunk> chunks;
        private final int invalidLineCount;

        public ChunkLoadResult(List<DocumentKnowledgeChunk> chunks, int invalidLineCount) {
            this.chunks = chunks;
            this.invalidLineCount = invalidLineCount;
        }

        public List<DocumentKnowledgeChunk> chunks() {
            return chunks;
        }

        public int invalidLineCount() {
            return invalidLineCount;
        }
    }

    private DocumentKnowledgeFixtureReader() {}

    public static Optional<DocumentKnowledgePackageManifest> readManifest(Path packageDir) throws IOException {
        Path manifestPath = packageDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return DocumentKnowledgePackageManifest.parseJson(json);
    }

    public static ChunkLoadResult readChunks(Path packageDir, DocumentKnowledgePackageManifest manifest)
            throws IOException {
        String rel = manifest.chunksPath();
        Path chunksPath = rel.startsWith("/") ? packageDir.resolve(rel.substring(1)) : packageDir.resolve(rel);
        return readChunksFile(chunksPath);
    }

    public static ChunkLoadResult readChunksFile(Path chunksFile) throws IOException {
        List<DocumentKnowledgeChunk> chunks = new ArrayList<>();
        int invalid = 0;
        if (!Files.isRegularFile(chunksFile)) {
            return new ChunkLoadResult(chunks, invalid);
        }
        for (String line : Files.readAllLines(chunksFile, StandardCharsets.UTF_8)) {
            Optional<DocumentKnowledgeChunk> parsed = DocumentKnowledgeChunk.parseJsonLine(line);
            if (parsed.isPresent()) {
                chunks.add(parsed.get());
            } else if (line != null && !line.isBlank()) {
                invalid++;
            }
        }
        return new ChunkLoadResult(List.copyOf(chunks), invalid);
    }
}
