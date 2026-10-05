package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

/** Resolves user text to asset type keys per {@code docs/agent/taxonomy.md} §7. */
final class TaxonomyAssetTypeResolver {

    static final class Match {
        final AssetTypeEntry entry;
        final String field;
        final String rule;
        final String value;

        Match(AssetTypeEntry entry, String field, String rule, String value) {
            this.entry = entry;
            this.field = field;
            this.rule = rule;
            this.value = value;
        }
    }

    private TaxonomyAssetTypeResolver() {}

    static List<Match> findMatches(String userText, List<AssetTypeEntry> entries) {
        return findMatches(userText, "", entries);
    }

    /**
     * @param entityHint optional {@code entities[].key} or {@code entities[].aliases[]} phrase (trimmed); when
     *            non-blank, only types under a matching entity are considered
     */
    static List<Match> findMatches(String userText, String entityHint, List<AssetTypeEntry> entries) {
        if (userText == null || userText.isBlank() || entries == null) {
            return List.of();
        }
        List<AssetTypeEntry> scope = entries;
        String rawHint = entityHint != null ? entityHint.trim() : "";
        if (!rawHint.isEmpty()) {
            scope = filterEntriesForEntityHint(entries, rawHint);
        }
        String raw = userText.trim();
        String normalizedUser = ModelKeyResolutionNormalize.normalizePhase0(raw);
        LinkedHashMap<String, Match> byKey = new LinkedHashMap<>();
        for (AssetTypeEntry e : scope) {
            considerPhrase(e, "key", e.key(), raw, normalizedUser, byKey);
            for (String alias : e.aliases()) {
                considerPhrase(e, "aliases", alias, raw, normalizedUser, byKey);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<AssetTypeEntry> filterEntriesForEntityHint(List<AssetTypeEntry> entries, String rawHint) {
        String normHint = ModelKeyResolutionNormalize.normalizePhase0(rawHint);
        List<AssetTypeEntry> out = new ArrayList<>();
        for (AssetTypeEntry e : entries) {
            if (entityRowMatchesHint(e, rawHint, normHint)) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean entityRowMatchesHint(AssetTypeEntry e, String rawHint, String normHint) {
        if (e.entityKey().equals(rawHint)) {
            return true;
        }
        if (!normHint.isEmpty() && ModelKeyResolutionNormalize.normalizePhase0(e.entityKey()).equals(normHint)) {
            return true;
        }
        for (String a : e.entityAliases()) {
            if (a.equals(rawHint)) {
                return true;
            }
            if (!normHint.isEmpty() && ModelKeyResolutionNormalize.normalizePhase0(a).equals(normHint)) {
                return true;
            }
        }
        return false;
    }

    private static void considerPhrase(AssetTypeEntry entry, String field, String phrase, String rawUser,
            String normalizedUser, Map<String, Match> byKey) {
        if (phrase == null || phrase.isEmpty()) {
            return;
        }
        String rowDedup = entry.matchDedupKey();
        if (phrase.equals(rawUser)) {
            byKey.put(rowDedup, new Match(entry, field, "exact", phrase));
            return;
        }
        String normPhrase = ModelKeyResolutionNormalize.normalizePhase0(phrase);
        if (!normPhrase.isEmpty() && normPhrase.equals(normalizedUser)) {
            byKey.putIfAbsent(rowDedup, new Match(entry, field, "normalized", phrase));
        }
    }

    static ObjectNode candidateNode(Match m, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectNode c = mapper.createObjectNode();
        c.put("entityKey", m.entry.entityKey());
        c.put("key", m.entry.key());
        ArrayNode aliases = mapper.createArrayNode();
        for (String a : m.entry.aliases()) {
            aliases.add(a);
        }
        c.set("aliases", aliases);
        c.put("entityType", m.entry.parentEntityType());
        c.put("entityName", m.entry.parentEntityName());
        if (m.entry.queryParent() != null) {
            ObjectNode qp = mapper.createObjectNode();
            qp.put("entityType", m.entry.queryParent().entityType());
            qp.put("entityName", m.entry.queryParent().entityName());
            if (!m.entry.queryParent().role().isEmpty()) {
                qp.put("role", m.entry.queryParent().role());
            }
            c.set("queryParent", qp);
        }
        ArrayNode dp = mapper.createArrayNode();
        for (String p : m.entry.criticalProperties()) {
            dp.add(p);
        }
        c.set("criticalProperties", dp);
        ObjectNode mb = mapper.createObjectNode();
        mb.put("field", m.field);
        mb.put("rule", m.rule);
        mb.put("value", m.value);
        c.set("matchedBy", mb);
        return c;
    }
}
