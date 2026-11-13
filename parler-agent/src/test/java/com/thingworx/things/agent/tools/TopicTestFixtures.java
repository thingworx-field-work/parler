package com.thingworx.things.agent.tools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/** Synthetic package reader shared by loader, loop and budget tests; never accesses ThingWorx. */
public final class TopicTestFixtures implements RepositoryReader {
    public static final String AGENT = "TopicTestAgent";
    public static final DocumentKnowledgeSettings SETTINGS = new DocumentKnowledgeSettings(
            "TestDocs", "/document-knowledge", 300, 100, 10000, 5, 10, 400, 6000);
    public String doc = "demo-guide";
    public String chunk = "topic-demo-r1";
    public String body = resource("topic-demo-r1.md");
    /** Served for the pre-round-5 name {@code topics/<chunk>.txt} when non-null; the loader must never request it. */
    public String legacyTxtBody;
    public final List<String> paths = new ArrayList<>();
    public int listings;
    public int fileReads;
    public int bodyReads;

    public static String resource(String name) {
        try (InputStream in = TopicTestFixtures.class.getResourceAsStream("/document-knowledge/topic-round1/" + name)) {
            if (in == null) throw new IllegalArgumentException(name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public ToolCall call(String id) {
        return new ToolCall(id, "load_topic", new org.json.JSONObject()
                .put("docId", doc).put("chunkId", chunk).toString());
    }

    public String result() {
        return TopicMethodText.format(new TopicMethodText.Reference(doc, chunk), body);
    }

    public String topicPath(String extension) {
        return "/document-knowledge/" + doc + "/topics/" + chunk + "." + extension;
    }

    @Override
    public InfoTable getFileListing(String path, String mask) {
        listings++;
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(com.thingworx.types.constants.CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(com.thingworx.types.constants.CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable out = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put(com.thingworx.types.constants.CommonPropertyNames.PROP_NAME, new StringPrimitive(doc));
        row.put(com.thingworx.types.constants.CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
        out.addRow(row);
        return out;
    }

    @Override
    public String loadText(String path) throws Exception {
        fileReads++;
        paths.add(path);
        if (path.equals(topicPath("md"))) {
            bodyReads++;
            if (body == null) throw new java.io.FileNotFoundException("synthetic missing body");
            return body;
        }
        if (legacyTxtBody != null && path.equals(topicPath("txt")))
            return legacyTxtBody;
        if (path.equals("/document-knowledge/" + doc + "/manifest.json"))
            return resource("manifest.json").replace("demo-guide", doc);
        if (path.equals("/document-knowledge/" + doc + "/chunks/chunks.jsonl"))
            return resource("chunks.jsonl").replace("demo-guide", doc).replace("topic-demo-r1", chunk);
        throw new java.io.FileNotFoundException(path);
    }
}
