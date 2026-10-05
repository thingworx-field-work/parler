package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.taxonomy.AssetTypeEntry;
import com.thingworx.things.agent.taxonomy.TaxonomyQueryParent;
import com.thingworx.things.agent.taxonomy.TaxonomyIdentifierContract;
import com.thingworx.things.agent.taxonomy.TaxonomyIdentifierContract.ScanTruncation;
import com.thingworx.things.agent.taxonomy.TaxonomyParentMembership;
import com.thingworx.things.agent.taxonomy.TaxonomyIdentityV3QitQuery;
import com.thingworx.things.agent.taxonomy.TaxonomyPropertyProjection;
import com.thingworx.things.agent.taxonomy.TaxonomyResolverJson;
import com.thingworx.things.agent.taxonomy.TaxonomyV3Normalize;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Resolves asset identifiers under a known identity type. Uses QIT row projection for matching and display fields;
 * optional {@code queryParent} ThingTemplate narrows QIT before shape membership checks.
 */
public final class TaxonomyIdentifierResolver {

    private static final Logger LOG =
            LogUtilities.getInstance().getApplicationLogger(TaxonomyIdentifierResolver.class);
    private static final ObjectMapper MAPPER = TaxonomyResolverJson.mapper();
    private static final int QIT_MAX_ITEMS = TaxonomyIdentifierContract.QIT_MAX_ITEMS;
    private static final int IDENTITY_AMBIGUOUS_CANDIDATE_MAX = 10;

    public static final class Candidate {
        final String name;
        final Map<String, String> criticalPropertyValues;
        final String matchedField;
        final String matchedRule;
        final String matchedValue;

        Candidate(String name, Map<String, String> criticalPropertyValues, String matchedField, String matchedRule,
                String matchedValue) {
            this.name = name;
            this.criticalPropertyValues = criticalPropertyValues;
            this.matchedField = matchedField;
            this.matchedRule = matchedRule;
            this.matchedValue = matchedValue;
        }
    }

    private TaxonomyIdentifierResolver() {}

    public static String resolve(AssetTypeEntry type, String identifier, boolean stale) {
        try {
            return doResolve(type, identifier, stale);
        } catch (Exception e) {
            LOG.warn("taxonomy_identifier_resolve: {}", e.getMessage(), e);
            return TaxonomyResolverJson.error("TAXONOMY_RESOLVE_FAILED",
                    e.getMessage() != null ? e.getMessage() : "resolve failed", TaxonomyResolverJson.staleField(stale),
                    null, null);
        }
    }

    private static String doResolve(AssetTypeEntry type, String identifier, boolean stale) throws Exception {
        String id = identifier != null ? identifier.trim() : "";
        boolean identitySemantics = isV3ZipSynthetic(type);
        if (id.isEmpty()) {
            return notFoundEmptyId(stale, identitySemantics);
        }
        if (type.hasNameExactRule()) {
            Candidate fast = tryDirectNameFastPath(type, id);
            if (fast != null) {
                return inlineSuccess(type.key(), List.of(fast), stale, null, null, identitySemantics);
            }
        }
        JsonNode qitQuery = identitySemantics ? TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, id) : null;
        QitScan scan = scanImplementors(type, qitQuery);
        List<Candidate> matches = identitySemantics ? matchFromProjectedRowsZippered(type, id, scan.rows)
                : matchFromProjectedRows(type, id, scan.rows);
        return branchResponse(type.key(), matches, stale, scan.truncated, scan.totalUnderlyingCount, identitySemantics);
    }

    private static boolean isV3ZipSynthetic(AssetTypeEntry type) {
        return type.entityKey() != null && type.entityKey().startsWith("v3zip:");
    }

    private static String notFoundEmptyId(boolean stale, boolean identitySemantics) {
        if (identitySemantics) {
            return TaxonomyResolverJson.error("IDENTITY_NOT_FOUND", "text is required",
                    TaxonomyResolverJson.staleField(stale), null, null);
        }
        return TaxonomyResolverJson.error("ASSET_IDENTIFIER_NOT_FOUND", "identifier is required",
                TaxonomyResolverJson.staleField(stale), null, null);
    }

    private static Candidate tryDirectNameFastPath(AssetTypeEntry type, String identifier) throws Exception {
        RootEntity re = PlatformAccess.findAsUser(identifier,
                RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (!(re instanceof Thing)) {
            return null;
        }
        Thing thing = (Thing) re;
        if (!TaxonomyParentMembership.thingImplementsParent(thing, type.parentEntityType(), type.parentEntityName())) {
            return null;
        }
        TaxonomyQueryParent qp = type.queryParent();
        if (qp != null && !TaxonomyParentMembership.thingImplementsParent(thing, qp.entityType(), qp.entityName())) {
            return null;
        }
        Map<String, String> dp =
                projectCriticalPropertyValuesProtected(thing, TaxonomyPropertyProjection.safeCriticalPropertyFields(type));
        return new Candidate(identifier, dp, "name", "exact", identifier);
    }

    private static final class QitScan {
        List<ValueCollection> rows = List.of();
        boolean truncated;
        Integer totalUnderlyingCount;
    }

    private static QitScan scanImplementors(AssetTypeEntry type, JsonNode queryNode) throws Exception {
        QitScan out = new QitScan();
        String rep = type.representation();
        TaxonomyQueryParent qp = type.queryParent();
        boolean templateNarrowing = "shape_as_type".equals(rep) && qp != null && "ThingTemplate".equals(qp.entityType())
                && !qp.entityName().isEmpty();
        boolean templateParent = "template_as_type".equals(rep) || templateNarrowing;
        RelationshipTypes.ThingworxRelationshipTypes rel = templateParent
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                : RelationshipTypes.ThingworxRelationshipTypes.ThingShape;
        String parentName = templateNarrowing ? qp.entityName() : type.parentEntityName();
        RootEntity parent = PlatformAccess.findAsUser(parentName, rel);
        if (!(parent instanceof IServiceProvider)) {
            return out;
        }
        IServiceProvider provider = (IServiceProvider) parent;
        var sd = provider.getInstanceServiceDefinition(QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL);
        if (sd == null) {
            return out;
        }
        List<String> projected = TaxonomyPropertyProjection.qitPropertyNames(type);
        QueryEntitiesExecutor.ColumnPick columns =
                QueryEntitiesExecutor.createTaxonomyProjectionPick(templateParent, projected);
        ValueCollection params = buildQitParams(sd, columns, queryNode);
        InfoTable outer = provider.processAPIServiceRequest(QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL, params);
        QueryEntitiesExecutor.QitParse parsed = QueryEntitiesExecutor.parseImplementingThingsOutput(outer);
        InfoTable rootList = parsed.dataTable;
        int returned = rootList != null ? rootList.getRowCount() : 0;
        Long inferred = null;
        if (parsed.totalCount == null && returned >= QIT_MAX_ITEMS) {
            inferred = QueryEntitiesExecutor.inferTotalWhenPlatformOmitsCount(provider, sd, QIT_MAX_ITEMS, 0, columns,
                    returned, parsed.totalCount);
        }
        ScanTruncation trunc = TaxonomyIdentifierContract.scanTruncation(parsed.totalCount, inferred);
        out.truncated = trunc.truncated;
        out.totalUnderlyingCount = trunc.totalUnderlyingCount;
        List<ValueCollection> rows = new ArrayList<>();
        if (rootList != null) {
            for (int i = 0; i < rootList.getRowCount(); i++) {
                rows.add(rootList.getRow(i));
            }
        }
        if (templateNarrowing) {
            rows = filterRowsImplementingShape(rows, type.parentEntityType(), type.parentEntityName());
        }
        out.rows = rows;
        return out;
    }

    private static ValueCollection buildQitParams(ServiceDefinition sd, QueryEntitiesExecutor.ColumnPick columns,
            JsonNode queryNode) throws Exception {
        try {
            return QueryEntitiesExecutor.buildImplementingThingsParams(sd, QIT_MAX_ITEMS, 0, null, false, queryNode, null,
                    columns);
        } catch (Exception ex) {
            if (queryNode != null && !queryNode.isNull()) {
                LOG.warn("taxonomy_qit_query_rejected: {}", ex.getMessage());
                return QueryEntitiesExecutor.buildImplementingThingsParams(sd, QIT_MAX_ITEMS, 0, null, false, null, null,
                        columns);
            }
            throw ex;
        }
    }

    private static List<ValueCollection> filterRowsImplementingShape(List<ValueCollection> rows, String shapeType,
            String shapeName) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<ValueCollection> kept = new ArrayList<>();
        for (ValueCollection listRow : rows) {
            String thingName = listingRowName(listRow);
            if (thingName == null || thingName.isEmpty()) {
                continue;
            }
            RootEntity re = PlatformAccess.findAsUser(thingName,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(re instanceof Thing)) {
                continue;
            }
            if (TaxonomyParentMembership.thingImplementsParent((Thing) re, shapeType, shapeName)) {
                kept.add(listRow);
            }
        }
        return kept;
    }

    private static List<Candidate> matchFromProjectedRows(AssetTypeEntry type, String identifier,
            List<ValueCollection> rows) {
        LinkedHashMap<String, Candidate> byName = new LinkedHashMap<>();
        String rawId = identifier.trim();
        String normId = ModelKeyResolutionNormalize.normalizePhase0(rawId);
        String normV3 = TaxonomyV3Normalize.normalize(rawId);
        List<String> identityProps = TaxonomyPropertyProjection.safeIdentityFields(type);
        List<String> displayProps = TaxonomyPropertyProjection.safeCriticalPropertyFields(type);
        for (String prop : identityProps) {
            for (String rule : type.matchRules()) {
                for (ValueCollection listRow : rows) {
                    String thingName = listingRowName(listRow);
                    if (thingName == null || thingName.isEmpty()) {
                        continue;
                    }
                    String fieldValue = fieldValueFromRow(listRow, prop);
                    if (fieldValue == null) {
                        continue;
                    }
                    if (matchesIdentityRule(rawId, normId, normV3, fieldValue, rule, false)) {
                        byName.putIfAbsent(thingName,
                                new Candidate(thingName, criticalPropertyValuesFromRow(listRow, displayProps), prop, rule,
                                        displayMatchedValue(rawId, fieldValue, rule)));
                    }
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Zippered rules align to {@code type.identityProperties()} indices — walk pairs in order so duplicate property
     * names keep distinct match modes (never use {@code indexOf} alone).
     */
    private static List<String> alignedZipperedMatchRules(AssetTypeEntry type, List<String> safeIdentityProps) {
        List<String> props = type.identityProperties();
        List<String> allRules = type.matchRules();
        Set<String> safe = new LinkedHashSet<>(safeIdentityProps);
        List<String> out = new ArrayList<>();
        int n = Math.min(props.size(), allRules.size());
        for (int i = 0; i < n; i++) {
            if (safe.contains(props.get(i))) {
                out.add(allRules.get(i));
            }
        }
        return out;
    }

    /**
     * Same zippered OR matching as {@link #matchFromProjectedRowsZippered} using caller-supplied projection lists
     * (JUnit; avoids live {@code EntityUtilities} when the rule's ThingTemplate is not materialized in the test JVM).
     */
    static List<Candidate> matchFromProjectedRowsZipperedForTest(AssetTypeEntry type, String identifier,
            List<ValueCollection> rows, List<String> identityProps, List<String> zipperedMatchRules,
            List<String> displayProps) {
        return matchFromProjectedRowsZipperedWithLists(type, identifier, rows, identityProps, zipperedMatchRules,
                displayProps);
    }

    /**
     * v3 identity rule: each {@link AssetTypeEntry#identityProperties()} entry pairs with the same index in
     * {@link AssetTypeEntry#matchRules()} (zippered), not the Cartesian product. Any matching pair is sufficient
     * (OR across pairs) per {@code docs/agent/taxonomy.md} §4.1.
     */
    private static List<Candidate> matchFromProjectedRowsZipperedWithLists(AssetTypeEntry type, String identifier,
            List<ValueCollection> rows, List<String> identityProps, List<String> rules, List<String> displayProps) {
        LinkedHashMap<String, Candidate> byName = new LinkedHashMap<>();
        String rawId = identifier.trim();
        String normId = ModelKeyResolutionNormalize.normalizePhase0(rawId);
        String normV3 = TaxonomyV3Normalize.normalize(rawId);
        int n = Math.min(identityProps.size(), rules.size());
        if (n == 0) {
            return List.of();
        }
        for (ValueCollection listRow : rows) {
            String thingName = listingRowName(listRow);
            if (thingName == null || thingName.isEmpty()) {
                continue;
            }
            String matchProp = null;
            String matchRule = null;
            String matchVal = null;
            for (int i = 0; i < n; i++) {
                String prop = identityProps.get(i);
                String rule = rules.get(i);
                String fieldValue = fieldValueFromRow(listRow, prop);
                if (matchesIdentityRule(rawId, normId, normV3, fieldValue, rule, true)) {
                    matchProp = prop;
                    matchRule = rule;
                    matchVal = displayMatchedValue(rawId, fieldValue, rule);
                    break;
                }
            }
            if (matchProp != null) {
                byName.putIfAbsent(thingName,
                        new Candidate(thingName, criticalPropertyValuesFromRow(listRow, displayProps), matchProp,
                                matchRule, matchVal != null ? matchVal : rawId));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Resolves identity props and PASSWORD-safe QIT projection, then zippered OR match. */
    static List<Candidate> matchFromProjectedRowsZippered(AssetTypeEntry type, String identifier,
            List<ValueCollection> rows) {
        List<String> identityProps = TaxonomyPropertyProjection.safeIdentityFields(type);
        List<String> rules = alignedZipperedMatchRules(type, identityProps);
        List<String> displayProps = TaxonomyPropertyProjection.safeCriticalPropertyFields(type);
        return matchFromProjectedRowsZipperedWithLists(type, identifier, rows, identityProps, rules, displayProps);
    }

    /**
     * @param v3ZipRow when true, v2 {@code exact}/{@code normalized} are not used; v3 supports only {@code equals} and
     *         {@code suffix} (validated at {@code identity-types.json} load).
     */
    private static boolean matchesIdentityRule(String rawId, String normPhase0Id, String normV3Id, String fieldValue,
            String rule, boolean v3ZipRow) {
        if (fieldValue == null) {
            return false;
        }
        String r = rule != null ? rule : "";
        if (!v3ZipRow) {
            if ("exact".equals(r)) {
                return rawId.equals(fieldValue);
            }
            if ("normalized".equals(r)) {
                return normPhase0Id.equals(ModelKeyResolutionNormalize.normalizePhase0(fieldValue));
            }
            return false;
        }
        switch (r) {
        case "equals":
            return rawId.equalsIgnoreCase(fieldValue);
        case "suffix":
            return v3CaseInsensitiveSuffix(fieldValue, rawId);
        default:
            return false;
        }
    }

    private static boolean v3CaseInsensitiveSuffix(String fieldValue, String rawId) {
        int fl = fieldValue.length();
        int rl = rawId.length();
        if (rl > fl) {
            return false;
        }
        return fieldValue.regionMatches(true, fl - rl, rawId, 0, rl);
    }

    private static String displayMatchedValue(String rawId, String fieldValue, String rule) {
        if ("exact".equals(rule) || "equals".equals(rule) || "contains".equals(rule) || "suffix".equals(rule)) {
            return fieldValue;
        }
        return rawId;
    }

    private static String fieldValueFromRow(ValueCollection listRow, String prop) {
        if ("name".equals(prop)) {
            return listingRowName(listRow);
        }
        return rowPropertyString(listRow, prop);
    }

    private static Map<String, String> criticalPropertyValuesFromRow(ValueCollection listRow,
            List<String> criticalPropertyNames) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String prop : criticalPropertyNames) {
            String v = rowPropertyString(listRow, prop);
            if (v != null) {
                out.put(prop, v);
            } else {
                out.put(prop, "");
            }
        }
        return out;
    }

    private static Map<String, String> projectCriticalPropertyValuesProtected(Thing thing, List<String> criticalPropertyNames)
            throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String prop : criticalPropertyNames) {
            if (ProtectedValuePolicy.isProtectedProperty(thing, prop)) {
                continue;
            }
            IPrimitiveType live = readPropertyPrimitive(thing, prop);
            if (live == null) {
                continue;
            }
            Object val = live.getValue();
            out.put(prop, val == null ? "" : String.valueOf(val));
        }
        return out;
    }

    private static String branchResponse(String assetTypeKey, List<Candidate> matches, boolean stale,
            boolean truncated, Integer totalUnderlying, boolean identitySemantics) throws Exception {
        int n = matches.size();
        Boolean truncFlag = truncated ? Boolean.TRUE : null;
        Boolean staleField = TaxonomyResolverJson.staleField(stale);
        if (n == 0) {
            if (truncated) {
                String msg =
                        "No matching Thing was found in the scanned result prefix; the underlying scope was truncated.";
                return TaxonomyResolverJson.error(
                        identitySemantics ? "IDENTITY_NOT_FOUND" : "ASSET_IDENTIFIER_NOT_FOUND", msg, staleField,
                        Boolean.TRUE, totalUnderlying);
            }
            return TaxonomyResolverJson.error(
                    identitySemantics ? "IDENTITY_NOT_FOUND" : "ASSET_IDENTIFIER_NOT_FOUND",
                    identitySemantics ? "No Thing matched the given text." : "No Thing matched the identifier.",
                    staleField, null, null);
        }
        if (n == 1) {
            return inlineSuccess(assetTypeKey, matches, stale, truncFlag, totalUnderlying, identitySemantics);
        }
        if (!ToolResultEgressGateway.isLargeTabularResult(n)) {
            return ambiguous(assetTypeKey, matches, stale, truncFlag, totalUnderlying, identitySemantics);
        }
        return largeSuccess(assetTypeKey, matches, stale, truncated, totalUnderlying, criticalPropertyKeysFrom(matches),
                identitySemantics);
    }

    private static List<String> criticalPropertyKeysFrom(List<Candidate> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(matches.get(0).criticalPropertyValues.keySet());
    }

    private static String inlineSuccess(String assetTypeKey, List<Candidate> matches, boolean stale, Boolean truncated,
            Integer totalUnderlying, boolean identitySemantics) throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("resultKind", identitySemantics ? "THING_RESOLVED_INLINE" : "TAXONOMY_ASSET_IDENTIFIER_INLINE");
        o.put("stale", stale);
        TaxonomyResolverJson.putTruncation(o, truncated, totalUnderlying);
        o.put("assetTypeKey", assetTypeKey);
        o.put("totalCount", matches.size());
        o.set("matches", matchesArray(matches));
        return MAPPER.writeValueAsString(o);
    }

    private static String ambiguous(String assetTypeKey, List<Candidate> matches, boolean stale, Boolean truncated,
            Integer totalUnderlying, boolean identitySemantics) throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "error");
        o.put("code", identitySemantics ? "IDENTITY_AMBIGUOUS" : "ASSET_IDENTIFIER_AMBIGUOUS");
        o.put("stale", stale);
        TaxonomyResolverJson.putTruncation(o, truncated, totalUnderlying);
        o.put("assetTypeKey", assetTypeKey);
        o.put("totalCount", matches.size());
        o.put("message", identitySemantics ? "Multiple Things matched." : "Multiple assets matched.");
        List<Candidate> emit = matches;
        if (identitySemantics && matches.size() > IDENTITY_AMBIGUOUS_CANDIDATE_MAX) {
            emit = new ArrayList<>(matches.subList(0, IDENTITY_AMBIGUOUS_CANDIDATE_MAX));
            o.put("ambiguousCandidatesTruncated", true);
        }
        o.set("candidates", matchesArray(emit));
        return MAPPER.writeValueAsString(o);
    }

    private static String largeSuccess(String assetTypeKey, List<Candidate> matches, boolean stale,
            boolean scanTruncated, Integer totalUnderlying, List<String> criticalPropertyNames,
            boolean identitySemantics) throws Exception {
        InfoTable built = buildCacheTable(matches, criticalPropertyNames);
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(built);
        ToolResultEgressGateway.TabularEvidencePlan plan = ToolResultEgressGateway.planTabularEvidence(
                matches.size(),
                identitySemantics ? "THING_RESOLVED_INLINE" : "TAXONOMY_ASSET_IDENTIFIER_INLINE",
                identitySemantics ? "THING_RESOLVED_LARGE" : "TAXONOMY_ASSET_IDENTIFIER_LARGE",
                "matches",
                "sampleMatches");
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("stale", stale);
        TaxonomyResolverJson.putTruncation(o, scanTruncated ? Boolean.TRUE : null, totalUnderlying);
        o.put("assetTypeKey", assetTypeKey);
        o.put("totalCount", matches.size());
        ArrayNode sample = MAPPER.createArrayNode();
        for (int i = 0; i < plan.rowsToEmit(); i++) {
            sample.add(candidateObject(matches.get(i), false));
        }
        ToolResultEgressGateway.applyTabularEvidence(
                o, plan, sample, cacheId, InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
        return MAPPER.writeValueAsString(o);
    }

    private static InfoTable buildCacheTable(List<Candidate> matches, List<String> criticalPropertyNames) {
        DataShapeDefinition dsd = new DataShapeDefinition();
        int ord = 0;
        FieldDefinition nameFd = new FieldDefinition();
        nameFd.setName("name");
        nameFd.setBaseType(BaseTypes.STRING);
        nameFd.setOrdinal(ord++);
        dsd.addFieldDefinition(nameFd);
        for (String p : criticalPropertyNames) {
            FieldDefinition fd = new FieldDefinition();
            fd.setName(p);
            fd.setBaseType(BaseTypes.STRING);
            fd.setOrdinal(ord++);
            dsd.addFieldDefinition(fd);
        }
        InfoTable table = new InfoTable(dsd);
        for (Candidate c : matches) {
            ValueCollection row = new ValueCollection();
            row.put("name", new StringPrimitive(c.name));
            for (String p : criticalPropertyNames) {
                row.put(p, new StringPrimitive(c.criticalPropertyValues.getOrDefault(p, "")));
            }
            table.addRow(row);
        }
        return table;
    }

    private static ArrayNode matchesArray(List<Candidate> matches) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Candidate c : matches) {
            arr.add(candidateObject(c, true));
        }
        return arr;
    }

    private static ObjectNode candidateObject(Candidate c, boolean includeMatchedBy) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", c.name);
        ObjectNode dp = MAPPER.createObjectNode();
        for (Map.Entry<String, String> e : c.criticalPropertyValues.entrySet()) {
            dp.put(e.getKey(), e.getValue());
        }
        row.set("criticalProperties", dp);
        if (includeMatchedBy) {
            ObjectNode mb = MAPPER.createObjectNode();
            mb.put("field", c.matchedField);
            mb.put("rule", c.matchedRule);
            mb.put("value", c.matchedValue);
            row.set("matchedBy", mb);
        }
        return row;
    }

    private static String listingRowName(ValueCollection row) {
        if (row == null) {
            return null;
        }
        for (String pref : new String[] {"name", "entityName", "EntityName", "thingName"}) {
            try {
                Object v = row.getValue(pref);
                String s = primitiveToPlainString(v);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        return null;
    }

    private static String rowPropertyString(ValueCollection row, String prop) {
        if (row == null || prop == null || prop.isEmpty()) {
            return null;
        }
        try {
            Object v = row.getValue(prop);
            return primitiveToPlainString(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IPrimitiveType readPropertyPrimitive(Thing thing, String name) throws Exception {
        return PropertyValueReads.readCurrentValue(thing, name);
    }

    private static String primitiveToPlainString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof IPrimitiveType) {
            Object val = ((IPrimitiveType) v).getValue();
            return val != null ? String.valueOf(val) : null;
        }
        return String.valueOf(v);
    }
}
