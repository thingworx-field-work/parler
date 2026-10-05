package com.thingworx.things.agent.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Shared loader for the committed document-knowledge fixtures under
 * {@code dev_data/future_repo/document-knowledge}. Backs the offline JUnit gate
 * (docs/operations/knowledge-retrieval-pipeline.md §5).
 */
final class DocumentKnowledgeFixtures {

    private DocumentKnowledgeFixtures() {
    }

    /** Discovers the committed fixture docIds (every subdirectory holding a manifest.json). */
    static List<String> docIds() {
        Path base = docKnowledgeDir();
        try (Stream<Path> entries = Files.list(base)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("manifest.json")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static DocumentKnowledgeIndex loadIndex() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        return DocumentKnowledgeIndex.load(allFixturesReader(), settings, Instant.now());
    }

    static RepositoryReader allFixturesReader() {
        Path base = docKnowledgeDir();
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return directoryListing(docIds());
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                String prefix = "/document-knowledge/";
                if (path.startsWith(prefix)) {
                    Path file = base.resolve(path.substring(prefix.length()));
                    if (Files.isRegularFile(file)) {
                        return Files.readString(file, StandardCharsets.UTF_8);
                    }
                }
                throw new java.io.FileNotFoundException(path);
            }
        };
    }

    private static InfoTable directoryListing(List<String> dirNames) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String dir : dirNames) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(dir));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
            it.addRow(vc);
        }
        return it;
    }

    private static Path docKnowledgeDir() {
        return repoRoot().resolve("dev_data/future_repo/document-knowledge");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !dir.resolve("CONTRACTS/CONTRACT_VERSION.md").toFile().exists()) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found from cwd");
        }
        return dir;
    }
}
