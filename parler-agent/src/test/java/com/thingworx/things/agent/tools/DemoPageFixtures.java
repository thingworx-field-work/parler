package com.thingworx.things.agent.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * The synthetic {@code demo-guide} package of topic round 6 (design section 6): {@code page-0001} of 3,000 plain
 * characters and {@code page-0002} of 5,500 characters that are all quotes, backslashes and newlines. Read through a
 * fake repository in the pattern of {@link TopicTestFixtures}; never accesses ThingWorx.
 */
public final class DemoPageFixtures implements RepositoryReader {
    public static final String AGENT = "DemoPageAgent";
    public static final String DOC = "demo-guide";
    public static int chunkReads;
    /** When non-null, replaces the markdown of {@code page-0001} (a changed published row). */
    public String changedPage0001;

    public static DocumentKnowledgeSettings settings(int chunkMaxChars) {
        return new DocumentKnowledgeSettings("TestDocs", "/document-knowledge", 300, 100, 10_000, 5, 10, 400,
                chunkMaxChars);
    }

    static String resource(String name) {
        try (InputStream in = DemoPageFixtures.class.getResourceAsStream("/document-knowledge/topic-round6/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException(name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ToolCall getChunk(String id, String chunkId) {
        return new ToolCall(id, "get_document_chunk",
                new org.json.JSONObject().put("docId", DOC).put("chunkId", chunkId).toString());
    }

    @Override
    public InfoTable getFileListing(String path, String mask) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable out = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(DOC));
        row.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
        out.addRow(row);
        return out;
    }

    @Override
    public String loadText(String path) throws Exception {
        if (path.equals("/document-knowledge/" + DOC + "/manifest.json")) {
            return resource("manifest.json");
        }
        if (path.equals("/document-knowledge/" + DOC + "/chunks/chunks.jsonl")) {
            chunkReads++;
            String chunks = resource("chunks.jsonl");
            if (changedPage0001 != null) {
                String[] lines = chunks.split("\n");
                org.json.JSONObject first = new org.json.JSONObject(lines[0]);
                first.put("markdown", changedPage0001);
                lines[0] = first.toString();
                chunks = String.join("\n", lines) + "\n";
            }
            return chunks;
        }
        throw new java.io.FileNotFoundException(path);
    }
}
