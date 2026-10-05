package com.thingworx.things.agent.tools;

import org.json.JSONArray;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.types.TagCollection;
import com.thingworx.types.primitives.TagCollectionPrimitive;

/**
 * Parses LLM-friendly JSON into {@link TagCollection} for platform services (Model tags).
 *
 * <p>Preferred shape: JSON array of {@code { "vocabulary": "...", "vocabularyTerm": "..." }}.
 * Also accepts a non-empty string via {@link TagCollection#fromString(String)} (platform delimiter-separated).</p>
 */
public final class TagJsonCodec {

    private TagJsonCodec() {}

    /**
     * @param node null / missing / null JSON → empty collection (matches all for EntityServices-style filters)
     */
    public static TagCollection parseTagCollection(JsonNode node) throws Exception {
        if (node == null || node.isNull()) {
            return new TagCollection();
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                return new TagCollection();
            }
            JSONArray ja = new JSONArray(node.toString());
            return TagCollection.fromJSON(ja);
        }
        if (node.isTextual()) {
            String s = node.asText();
            if (s == null || s.isBlank()) {
                return new TagCollection();
            }
            return TagCollection.fromString(s);
        }
        throw new IllegalArgumentException(
                "Tags must be a JSON array of objects with \"vocabulary\" and \"vocabularyTerm\", or a platform tag string; got: "
                        + node.getNodeType());
    }

    public static TagCollectionPrimitive parseTagCollectionPrimitive(JsonNode node) throws Exception {
        return new TagCollectionPrimitive(parseTagCollection(node));
    }
}
