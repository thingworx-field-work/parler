package com.thingworx.things.agent.tools;

import java.util.Locale;

import com.thingworx.relationships.RelationshipTypes;

/**
 * Phase 0 normalization and ranking helpers for {@link ModelKeyResolver} — no ThingWorx logging static init so plain
 * JUnit can load this class ({@link ModelKeyResolutionNormalizeTest}).
 */
public final class ModelKeyResolutionNormalize {

    private ModelKeyResolutionNormalize() {}

    public static String normalizePhase0(String key) {
        if (key == null) {
            return "";
        }
        String s = key.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '.' || c == '/') {
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    static boolean synonymsMatchNormalizedUser(String normalizedUser, String synonymsCell) {
        if (synonymsCell == null || synonymsCell.isBlank() || normalizedUser.isEmpty()) {
            return false;
        }
        for (String part : synonymsCell.split(";")) {
            String frag = part.trim();
            if (frag.isEmpty()) {
                continue;
            }
            if (normalizedUser.equals(normalizePhase0(frag))) {
                return true;
            }
        }
        return false;
    }

    static RelationshipTypes.ThingworxRelationshipTypes parseTaxonomyEntityType(String entityType) {
        if ("ThingTemplate".equals(entityType)) {
            return RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate;
        }
        if ("ThingShape".equals(entityType)) {
            return RelationshipTypes.ThingworxRelationshipTypes.ThingShape;
        }
        return null;
    }

    /**
     * Phase −1: {@code Synonyms} already matched this row. If {@code EntityType} / {@code EntityName} cannot drive
     * {@link com.thingworx.things.agent.PlatformAccess#findAsUser}, returns a user-facing error detail; otherwise
     * {@code null}. Keeps invalid taxonomy rows from falling through to Phase 0 / 1b.
     */
    static String taxonomyMatchedRowBlockingDetail(String trimmedUserKey, String entityType, String entityName) {
        if (entityName == null || entityName.isBlank()) {
            return "Taxonomy Synonyms matched \"" + trimmedUserKey
                    + "\" but EntityName is missing or blank on that row. Fix taxonomy.";
        }
        if (entityType == null || entityType.isBlank()) {
            return "Taxonomy Synonyms matched \"" + trimmedUserKey
                    + "\" but EntityType is missing or blank on that row. Fix taxonomy.";
        }
        if (parseTaxonomyEntityType(entityType) == null) {
            return "Taxonomy Synonyms matched \"" + trimmedUserKey + "\" but EntityType \"" + entityType
                    + "\" must be ThingTemplate or ThingShape. Fix taxonomy.";
        }
        return null;
    }

    static String canonicalTemplateHint(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return null;
        }
        switch (normalizedKey) {
            case "datatable":
            case "datatablething":
                return "DataTable";
            case "stream":
            case "streamthing":
                return "Stream";
            case "valuestream":
            case "valuestreamthing":
                return "ValueStream";
            default:
                return null;
        }
    }

    /**
     * Phase 1b ranking (§3). Rank 4 uses substring either way: candidate may embed the user token (long template name)
     * or the user phrase may embed a short candidate token.
     */
    static int rankCandidate(String candidateName, String trimmedRaw, String normalizedUser) {
        if (candidateName == null) {
            return 99;
        }
        if (candidateName.equals(trimmedRaw)) {
            return 1;
        }
        if (candidateName.equalsIgnoreCase(trimmedRaw)) {
            return 2;
        }
        String nc = normalizePhase0(candidateName);
        if (nc.equals(normalizedUser)) {
            return 3;
        }
        if (normalizedUser.length() > 0 && (nc.contains(normalizedUser) || normalizedUser.contains(nc))) {
            return 4;
        }
        return 99;
    }
}
