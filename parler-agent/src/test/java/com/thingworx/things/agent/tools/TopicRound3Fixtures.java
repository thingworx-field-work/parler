package com.thingworx.things.agent.tools;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/** Several synthetic packages: a large manual that shares the family identity with a short article holding the topic. */
final class TopicRound3Fixtures implements RepositoryReader {
    static final String MANUAL = "family-turbine-manual";
    static final String ARTICLE = "short-article";
    static final String NOTES = "family-turbine-notes";
    static final String OTHER = "other-article";
    static final String TOPIC = "topic-internal-control-r1";
    private static final List<String> DOCUMENTS = List.of(MANUAL, ARTICLE, NOTES, OTHER);
    private static final String ROOT = "/document-knowledge/";

    /** Extra {@code chunks.jsonl} line for the article, for rows that must not live in the shared resource. */
    String extraArticleRow = "";

    @Override
    public InfoTable getFileListing(String path, String mask) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable out = new InfoTable(shape);
        for (String document : DOCUMENTS) {
            ValueCollection row = new ValueCollection();
            row.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(document));
            row.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
            out.addRow(row);
        }
        return out;
    }

    @Override
    public String loadText(String path) throws Exception {
        for (String document : DOCUMENTS) {
            if (path.equals(ROOT + document + "/manifest.json")) {
                return resource(document + "/manifest.json");
            }
            if (path.equals(ROOT + document + "/chunks/chunks.jsonl")) {
                return resource(document + "/chunks.jsonl") + (ARTICLE.equals(document) ? extraArticleRow : "");
            }
        }
        throw new FileNotFoundException(path);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = TopicRound3Fixtures.class.getResourceAsStream("/document-knowledge/topic-round3/" + name)) {
            if (in == null) {
                throw new FileNotFoundException(name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
