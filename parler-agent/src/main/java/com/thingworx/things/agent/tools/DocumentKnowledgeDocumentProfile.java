package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Optional {@code documentProfile} block from a package manifest (§5,
 * {@code docs/operations/document-retrieval-stability.md}).
 */
public final class DocumentKnowledgeDocumentProfile {

    private final List<String> aliases;
    private final List<String> manufacturers;
    private final List<String> assetModels;
    private final List<String> documentKinds;
    private final List<String> domainTerms;

    private DocumentKnowledgeDocumentProfile(
            List<String> aliases,
            List<String> manufacturers,
            List<String> assetModels,
            List<String> documentKinds,
            List<String> domainTerms) {
        this.aliases = List.copyOf(aliases);
        this.manufacturers = List.copyOf(manufacturers);
        this.assetModels = List.copyOf(assetModels);
        this.documentKinds = List.copyOf(documentKinds);
        this.domainTerms = List.copyOf(domainTerms);
    }

    public static DocumentKnowledgeDocumentProfile empty() {
        return new DocumentKnowledgeDocumentProfile(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static DocumentKnowledgeDocumentProfile parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return empty();
        }
        JsonNode profile = root.get("documentProfile");
        if (profile == null || !profile.isObject()) {
            return empty();
        }
        return new DocumentKnowledgeDocumentProfile(
                readStringList(profile.get("aliases")),
                readStringList(profile.get("manufacturers")),
                readStringList(profile.get("assetModels")),
                readStringList(profile.get("documentKinds")),
                readStringList(profile.get("domainTerms")));
    }

    public List<String> aliases() {
        return aliases;
    }

    public List<String> manufacturers() {
        return manufacturers;
    }

    public List<String> assetModels() {
        return assetModels;
    }

    public List<String> documentKinds() {
        return documentKinds;
    }

    public List<String> domainTerms() {
        return domainTerms;
    }

    private static List<String> readStringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && item.isTextual()) {
                String t = item.asText().trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Normalizes document-kind spellings such as operating_manual / operations_manual. */
    static String normalizeDocumentKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "";
        }
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if ("operations_manual".equals(k)) {
            return "operating_manual";
        }
        return k;
    }
}
