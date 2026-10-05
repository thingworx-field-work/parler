package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.data.util.InfoTableInstanceFactory;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.hierarchy.HierarchyQueryEntitiesIntersectAugment;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.JSONPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Structured listing of Things that implement a {@link com.thingworx.things.ThingTemplate} or
 * {@link com.thingworx.things.ThingShape}, preferring platform
 * {@value #SERVICE_OPTIMIZED_TOTAL} / {@value #SERVICE_OPTIMIZED} when available.
 *
 * <p>Return shape for {@value #SERVICE_OPTIMIZED_TOTAL} matches the platform data shape
 * {@code ImplementedThingsWithTotalCount}: one row with {@code rootEntityList} + {@code totalCount}.</p>
 *
 * <p><b>Column policy (docs/agent/query_with_total_count.md §10):</b> by default the executor passes an explicit
 * {@code basicPropertyNames} {@code EntityList} with {@code name} only and an <b>empty</b> {@code propertyNames}
 * {@code EntityList}, so the platform does not expand to “all properties”. Use {@code widePropertyColumns=true} to
 * restore platform-wide defaults. Optional toggles add basic columns or {@code thingTemplate} in {@code propertyNames}
 * (ThingTemplate parent only).</p>
 */
public final class QueryEntitiesExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(QueryEntitiesExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SERVICE_OPTIMIZED_TOTAL = "QueryImplementingThingsOptimizedWithTotalCount";
    static final String SERVICE_OPTIMIZED = "QueryImplementingThingsOptimized";
    static final String SERVICE_LEGACY = "QueryImplementingThings";

    private static final int DEFAULT_MAX_ITEMS = 50;
    private static final int MAX_MAX_ITEMS = 200;

    private QueryEntitiesExecutor() {}

    public static String executeQueryEntities(ToolCall call) {
        try {
            return doQueryEntities(call);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("query_entities: {}", e.getMessage(), e);
            return errorJson("QUERY_ENTITIES_ERROR", e.getMessage());
        }
    }

    private static String doQueryEntities(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        if (root instanceof ObjectNode) {
            String injectErr = HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyScope(
                    (ObjectNode) root);
            if (injectErr != null) {
                return injectErr;
            }
        }
        String entityType = text(root, "entityType");
        String thingShape = text(root, "thingShape");
        String thingTemplate = text(root, "thingTemplate");

        if (thingShape != null && thingShape.isBlank()) {
            thingShape = null;
        }
        if (thingTemplate != null && thingTemplate.isBlank()) {
            thingTemplate = null;
        }

        if (thingShape != null && thingTemplate != null) {
            return errorJson("BOTH_TEMPLATE_AND_SHAPE",
                    "Provide only one of thingShape or thingTemplate (not both).");
        }
        if (thingShape == null && thingTemplate == null) {
            return errorJson("MISSING_TEMPLATE_OR_SHAPE",
                    "thingTemplate or thingShape is required. For fuzzy search use spotlight_search.");
        }

        final EntityHierarchyIntersectHelper.IntersectThingNamesParse intersectParse;
        try {
            intersectParse = EntityHierarchyIntersectHelper.parseIntersectThingNamesDetailed(root);
        } catch (IllegalArgumentException e) {
            return errorJson("INTERSECT_LIST_TOO_LARGE", e.getMessage());
        }
        final Set<String> intersectB = intersectParse.namesOrNull();
        final boolean intersectActive = intersectB != null && !intersectB.isEmpty();
        final boolean expandArgHasMore = EntityHierarchyIntersectHelper.parseIntersectExpandHasMore(root);

        if (entityType != null && !entityType.isBlank() && !"Thing".equalsIgnoreCase(entityType.trim())) {
            LOG.warn("query_entities: entityType={} is not Thing; proceeding as Thing instance listing",
                    entityType);
        }

        int maxItems = root.has("maxItems") ? root.get("maxItems").asInt(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        maxItems = Math.min(Math.max(1, maxItems), MAX_MAX_ITEMS);
        int offset = root.has("offset") ? root.get("offset").asInt(0) : 0;
        if (offset < 0) {
            offset = 0;
        }
        String namePrefix = text(root, "namePrefix");
        // Platform Optimized services use withPermissions (not withData). Accept legacy alias withData.
        boolean withPermissions = root.has("withPermissions") && root.get("withPermissions").asBoolean(false)
                || root.has("withData") && root.get("withData").asBoolean(false);
        JsonNode queryNode = root.get("query");
        JsonNode modelTagsNode = root.get("modelTags");

        String admissionErr = QueryEntitiesQueryAdmission.validateOrErrorJson(queryNode);
        if (admissionErr != null) {
            return admissionErr;
        }

        RelationshipTypes.ThingworxRelationshipTypes rel = thingShape != null
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingShape
                : RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate;
        String inputParent = thingShape != null ? thingShape.trim() : thingTemplate.trim();

        ModelKeyResolver.ParentResolution resolvedParent;
        try {
            resolvedParent = ModelKeyResolver.resolveQueryEntitiesParent(inputParent, rel);
        } catch (Exception e) {
            LOG.warn("query_entities key resolution failed: {}", e.getMessage(), e);
            return errorJson("KEY_RESOLUTION_ERROR",
                    "Failed to resolve " + rel.name() + " key \"" + inputParent + "\": " + e.getMessage());
        }
        if (resolvedParent.isTaxonomyRowInvalidAfterSynonym()) {
            return errorJson("TAXONOMY_ROW_INVALID", resolvedParent.getTaxonomyRowInvalidDetail());
        }
        if (resolvedParent.isTaxonomySynonymEntityMissing()) {
            return errorJson("TAXONOMY_ENTITY_UNRESOLVED",
                    "Taxonomy Synonyms matched \"" + inputParent + "\" but EntityName \""
                            + resolvedParent.getTaxonomyRowEntityName() + "\" ("
                            + resolvedParent.getTaxonomyRowEntityType()
                            + ") does not resolve in the model. Fix taxonomy row data.");
        }
        if (resolvedParent.isModelKeyAmbiguous()) {
            return errorJson("KEY_RESOLUTION_AMBIGUOUS",
                    resolvedParent.getModelKeyAmbiguityDetail());
        }
        if (resolvedParent.getEntity() == null) {
            return errorJson("ENTITY_NOT_FOUND",
                    "No " + rel.name() + " matching \"" + inputParent + "\".");
        }
        RelationshipTypes.ThingworxRelationshipTypes effectiveRel = resolvedParent.getEffectiveRel();
        RootEntity parent = resolvedParent.getEntity();
        String parentName = parent.getName() != null && !parent.getName().isEmpty() ? parent.getName() : inputParent;
        if (!(parent instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", parent.getClass().getSimpleName() + " does not expose services.");
        }
        IServiceProvider provider = (IServiceProvider) parent;

        String[] tryOrder = new String[] { SERVICE_OPTIMIZED_TOTAL, SERVICE_OPTIMIZED, SERVICE_LEGACY };
        ServiceDefinition sd = null;
        String serviceUsed = null;
        for (String svc : tryOrder) {
            try {
                ServiceDefinition d = provider.getInstanceServiceDefinition(svc);
                if (d != null) {
                    sd = d;
                    serviceUsed = svc;
                    break;
                }
            } catch (Exception e) {
                LOG.debug("query_entities: service {} not available: {}", svc, e.getMessage());
            }
        }
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND",
                    "None of " + String.join(", ", tryOrder) + " is available on " + effectiveRel.name() + " \"" + parentName + "\".");
        }

        final ColumnPick columns;
        try {
            boolean templateParent = effectiveRel == RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate;
            columns = ColumnPick.fromToolArgs(root, templateParent);
        } catch (IllegalArgumentException e) {
            return errorJson("INVALID_COLUMN_OPTIONS", e.getMessage());
        }

        ValueCollection params = buildImplementingThingsParams(sd, maxItems, offset, namePrefix, withPermissions, queryNode,
                modelTagsNode, columns);
        LOG.info("query_entities {}.{} maxItems={} offset={} parent={} ({}) columnMode={}",
                parentName, serviceUsed, maxItems, offset, parentName, effectiveRel.name(),
                columns.widePropertyColumns ? "wide" : "lean");

        InfoTable outer;
        try {
            outer = provider.processAPIServiceRequest(serviceUsed, params);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            LOG.warn("query_entities SERVICE_EXECUTION_FAILED {}.{}: {}", parentName, serviceUsed, msg);
            return errorJson("QUERY_SERVICE_FAILED", msg);
        }

        QitParse parsed = parseImplementingThingsOutput(outer);
        InfoTable data = parsed.dataTable;
        int returnedPostQit = data != null ? data.getRowCount() : 0;
        Long platformTotal = parsed.totalCount;
        boolean totalRowsInferred = false;
        Long inferred = tryInferTotalWhenPlatformOmitsCount(provider, sd, serviceUsed, maxItems, offset, namePrefix,
                withPermissions, queryNode, modelTagsNode, columns, returnedPostQit, platformTotal);
        if (inferred != null) {
            platformTotal = inferred;
            totalRowsInferred = true;
        }

        List<String> cols = columnNames(data);
        boolean querySideHasMore =
                EntityHierarchyIntersectHelper.computeQuerySideHasMore(offset, returnedPostQit, platformTotal, maxItems);

        int preIntersectRows = 0;
        if (intersectActive && data != null && returnedPostQit > 0) {
            preIntersectRows = returnedPostQit;
            data = applyIntersectThingNamesFilter(data, intersectB);
        }
        int returned = data != null ? data.getRowCount() : 0;
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("service", serviceUsed);
        out.put("parentKind", effectiveRel.name());
        out.put("parentName", parentName);
        RelationshipTypes.ThingworxRelationshipTypes requestedRel = thingShape != null
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingShape
                : RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate;
        KeyResolutionReason krr = resolvedParent.getReasonEnum();
        // Emit when: Phase 0.5, (1) platform parent name differs from tool input, (2) taxonomy synonym ran (even if names match —
        // semantic authority), or (3) taxonomy / Phase 0.5 changed ThingTemplate vs ThingShape vs caller branch.
        boolean emitKeyResolution = krr != null
                && (krr == KeyResolutionReason.GENERIC_THING_INCOMING_DEPENDENCY
                        || !inputParent.equals(parentName) || krr == KeyResolutionReason.TAXONOMY_SYNONYM
                        || effectiveRel != requestedRel);
        if (emitKeyResolution) {
            ObjectNode kr = MAPPER.createObjectNode();
            kr.put("inputKey", inputParent);
            kr.put("reason", krr.name());
            if (krr == KeyResolutionReason.GENERIC_THING_INCOMING_DEPENDENCY) {
                kr.put("resolvedName", parentName);
            }
            if (effectiveRel != requestedRel) {
                kr.put("requestedParentKind", requestedRel.name());
                kr.put("effectiveParentKind", effectiveRel.name());
            }
            out.set("keyResolution", kr);
        }
        out.put("returnedRows", returned);
        out.put("offset", offset);
        out.put("maxItems", maxItems);
        out.put("columnMode", columns.widePropertyColumns ? "wide" : "lean");

        if (platformTotal != null) {
            out.put("totalRows", platformTotal);
            out.put("hasMore", querySideHasMore);
            if (totalRowsInferred) {
                out.put("totalRowsInferred", true);
                out.put("note",
                        "totalRows inferred: platform totalCount was null on QueryImplementingThingsOptimizedWithTotalCount. "
                                + "Exact when returnedRows < maxItems (last page), or when a probe at offset+maxItems returned no rows.");
            }
        } else {
            out.putNull("totalRows");
            out.put("hasMore", querySideHasMore);
            out.put("note",
                    "totalRows unknown for this service; hasMore is heuristic when returnedRows == maxItems.");
        }
        EntityHierarchyIntersectHelper.writeIntersectSuccessFields(
                out, intersectActive, preIntersectRows, returned, querySideHasMore, expandArgHasMore);
        if (intersectActive && intersectParse.droppedEmptyOrNonTextCount() > 0) {
            String extra = "intersectThingNames: dropped " + intersectParse.droppedEmptyOrNonTextCount()
                    + " empty or non-text entries.";
            if (out.has("note") && !out.get("note").isNull()) {
                out.put("note", out.get("note").asText() + " " + extra);
            } else {
                out.put("note", extra);
            }
        }

        if (data == null || returned == 0) {
            out.put("resultKind", "ENTITY_QUERY_EMPTY");
            out.putArray("rows");
            return MAPPER.writeValueAsString(out);
        }

        ToolResultEgressGateway.TabularEvidencePlan plan = ToolResultEgressGateway.planTabularEvidence(
                returned, "ENTITY_QUERY_INLINE", "ENTITY_QUERY_LARGE", "rows", "sampleRows");
        out.set("columns", queryEntitiesSlimColumnsMetadata());
        String cacheId = null;
        if (plan.large()) {
            cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(data);
        }
        ArrayNode evidenceRows = MAPPER.createArrayNode();
        for (int i = 0; i < plan.rowsToEmit(); i++) {
            evidenceRows.add(slimThingRow(data, i, cols));
        }
        ToolResultEgressGateway.applyTabularEvidence(
                out, plan, evidenceRows, cacheId, InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);

        LOG.info("query_entities ok service={} returnedRows={} totalRows={} resultKind={}",
                serviceUsed, returned, platformTotal, out.get("resultKind").asText());
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Parsed {@code ImplementedThingsWithTotalCount} outer row (package for taxonomy tool).
     * U1B E1: {@link #listingValidity} and {@link #platformTotal} preserve malformed vs empty
     * vs absent-total; {@link #totalCount} remains the usable {@code PRESENT}+{@code OK} value
     * (or null) for callers that only need a Long.
     */
    static final class QitParse {
        InfoTable dataTable;
        Long totalCount;
        BoundedQueryCompleteness.ListingValidity listingValidity =
                BoundedQueryCompleteness.ListingValidity.MISSING_OR_UNRECOGNIZED;
        BoundedQueryCompleteness.PlatformTotal platformTotal = BoundedQueryCompleteness.PlatformTotal.absent();
    }

    /**
     * Lean QIT column pick: {@code basicPropertyNames} = {@code name} only, empty {@code propertyNames}
     * (see docs/agent/query_with_total_count.md §10).
     */
    static ColumnPick createLeanNameOnlyPick(boolean templateParent) throws Exception {
        return ColumnPick.fromToolArgs(MAPPER.readTree("{}"), templateParent);
    }

    /**
     * QIT projection for taxonomy identifier resolution: {@code name} plus configured Thing properties.
     */
    static ColumnPick createTaxonomyProjectionPick(boolean templateParent, Iterable<String> thingPropertyNames)
            throws Exception {
        LinkedHashSet<String> basics = new LinkedHashSet<>();
        basics.add("name");
        LinkedHashSet<String> props = new LinkedHashSet<>();
        if (thingPropertyNames != null) {
            for (String p : thingPropertyNames) {
                if (p != null && !p.isBlank() && !"name".equals(p)) {
                    props.add(p.trim());
                }
            }
        }
        InfoTable basicTable = buildEntityListTable(basics);
        InfoTable propTable = buildEntityListTable(props);
        return new ColumnPick(false, basicTable, propTable);
    }

    /**
     * When {@code totalCount} is omitted on Optimized QIT, probe for an exact total (see
     * {@link #tryInferTotalWhenPlatformOmitsCount}). Used by taxonomy identifier resolver.
     */
    static Long inferTotalWhenPlatformOmitsCount(IServiceProvider provider, ServiceDefinition sd, int maxItems,
            int offset, ColumnPick columns, int returned, Long parsedTotal) throws Exception {
        return tryInferTotalWhenPlatformOmitsCount(provider, sd, SERVICE_OPTIMIZED_TOTAL, maxItems, offset, null,
                false, null, null, columns, returned, parsedTotal);
    }

    /**
     * Lean vs wide column selection for Optimized QIT services (see docs/agent/query_with_total_count.md §10).
     */
    static final class ColumnPick {
        final boolean widePropertyColumns;
        final InfoTable basicPropertyNames;
        final InfoTable propertyNames;

        private ColumnPick(boolean wide, InfoTable basic, InfoTable prop) {
            this.widePropertyColumns = wide;
            this.basicPropertyNames = basic;
            this.propertyNames = prop;
        }

        static ColumnPick fromToolArgs(JsonNode root, boolean templateParent) throws Exception {
            boolean wide = root.has("widePropertyColumns") && root.get("widePropertyColumns").asBoolean(false);
            if (wide) {
                return new ColumnPick(true, null, null);
            }
            boolean includeConcrete = root.has("includeConcreteTemplate") && root.get("includeConcreteTemplate").asBoolean(false);
            if (includeConcrete && !templateParent) {
                throw new IllegalArgumentException(
                        "includeConcreteTemplate is only valid when thingTemplate is set (not thingShape). "
                                + "ThingShape queries cannot return a thingTemplate column.");
            }
            Set<String> basics = new LinkedHashSet<>();
            basics.add("name");
            if (truthy(root, "includeDescription")) {
                basics.add("description");
            }
            if (truthy(root, "includeIsSystemObject")) {
                basics.add("isSystemObject");
            }
            if (truthy(root, "includeTags")) {
                basics.add("tags");
            }
            InfoTable basicTable = buildEntityListTable(basics);
            List<String> propList = new ArrayList<>();
            if (includeConcrete && templateParent) {
                propList.add("thingTemplate");
            }
            InfoTable propTable = buildEntityListTable(propList);
            return new ColumnPick(false, basicTable, propTable);
        }
    }

    private static boolean truthy(JsonNode root, String field) {
        return root.has(field) && root.get(field).asBoolean(false);
    }

    /**
     * Builds an {@code EntityList} InfoTable: one row per field with column {@code name} set (platform reads only {@code name}).
     */
    private static InfoTable buildEntityListTable(Iterable<String> fieldNames) throws Exception {
        InfoTable t = InfoTableInstanceFactory.createInfoTableFromDataShape("EntityList");
        for (String fn : fieldNames) {
            if (fn == null || fn.isBlank()) {
                continue;
            }
            ValueCollection vc = new ValueCollection();
            vc.put("name", new StringPrimitive(fn.trim()));
            t.addRow(vc);
        }
        return t;
    }

    private static void putEntityListParameterIfPresent(ValueCollection vc, FieldDefinitionCollection defs, String logical,
            InfoTable table) {
        if (table == null) {
            return;
        }
        for (FieldDefinition fd : defs.values()) {
            if (fd.getName() == null || fd.getBaseType() != BaseTypes.INFOTABLE) {
                continue;
            }
            if (!logical.equalsIgnoreCase(fd.getName())) {
                continue;
            }
            try {
                vc.SetInfoTableValue(fd.getName(), table);
            } catch (Exception e) {
                LOG.warn("query_entities: could not set parameter {}: {}", fd.getName(), e.getMessage());
            }
            return;
        }
    }

    /**
     * Unwraps {@code processAPIServiceRequest} return value.
     * <p><b>In-process invoke</b> ({@code Thing.processAPIServiceRequest}): the handler returns the service result INFOTABLE
     * directly. For {@link #SERVICE_OPTIMIZED_TOTAL} that table is
     * {@code ImplementedThingsWithTotalCount}: one row with {@code rootEntityList} and {@code totalCount}.</p>
     * <p>We must detect that shape <em>before</em> {@link #firstInfoTable(ValueCollection, String...)} on {@code wrapRow},
     * otherwise the generic column scan picks {@code rootEntityList} as the nested table and never reads {@code totalCount}
     * from the same row (regression: tool JSON {@code totalRows} stays null).</p>
     * <p>Some call paths may wrap the result in an extra row with {@code result} / {@code results} / {@code data}; those
     * are handled below. Legacy {@code RootEntityList} (no total) is returned as {@code inner} as-is.</p>
     */
    /** Package access for {@link QueryEntitiesByTaxonomyExecutor}. */
    static QitParse parseImplementingThingsOutput(InfoTable outer) {
        QitParse p = new QitParse();
        if (outer == null || outer.getRowCount() == 0) {
            return p; // MISSING_OR_UNRECOGNIZED
        }
        ValueCollection wrapRow = outer.getRow(0);
        if (wrapRow == null) {
            return p; // MISSING_OR_UNRECOGNIZED
        }

        QitParse unpacked = tryUnpackImplementedThingsRow(wrapRow);
        if (unpacked != null) {
            return unpacked;
        }

        InfoTable inner = firstInfoTable(wrapRow, "result", "results", "data");
        if (inner == null && looksLikeThingListing(outer)) {
            p.dataTable = outer;
            p.listingValidity = BoundedQueryCompleteness.ListingValidity.VALID_TABLE;
            p.platformTotal = BoundedQueryCompleteness.PlatformTotal.absent();
            p.totalCount = null;
            return p;
        }
        if (inner == null) {
            return p; // MISSING_OR_UNRECOGNIZED
        }

        if (inner.getRowCount() > 0) {
            QitParse nested = tryUnpackImplementedThingsRow(inner.getRow(0));
            if (nested != null) {
                return nested;
            }
        }

        p.dataTable = inner;
        p.listingValidity = BoundedQueryCompleteness.ListingValidity.VALID_TABLE;
        p.platformTotal = BoundedQueryCompleteness.PlatformTotal.absent();
        p.totalCount = null;
        return p;
    }

    /**
     * Detects a row shaped like {@code ImplementedThingsWithTotalCount} (nested list + optional total) using case-insensitive
     * column names — some platform paths omit {@link InfoTable#getDataShape()} or use different key casing.
     */
    private static QitParse tryUnpackImplementedThingsRow(ValueCollection row) {
        if (row == null) {
            return null;
        }
        InfoTable list = asInfoTable(valueByKeyIgnoreCase(row, "rootEntityList"));
        if (list == null) {
            return null;
        }
        QitParse p = new QitParse();
        p.dataTable = list;
        p.listingValidity = BoundedQueryCompleteness.ListingValidity.VALID_TABLE;
        p.platformTotal = readPlatformTotalFlexible(row);
        p.totalCount = p.platformTotal.presence == BoundedQueryCompleteness.TotalFieldPresence.PRESENT
                && p.platformTotal.parseStatus == BoundedQueryCompleteness.TotalParseStatus.OK
                ? p.platformTotal.parsedTotal
                : null;
        return p;
    }

    private static Object valueByKeyIgnoreCase(ValueCollection row, String canonical) {
        try {
            for (String k : row.keySet()) {
                if (k != null && k.equalsIgnoreCase(canonical)) {
                    return row.getValue(k);
                }
            }
        } catch (Exception ignored) {
            // keySet unsupported on some ValueCollection views
        }
        try {
            return row.getValue(canonical);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * E1 platform-total carrier: distinguishes ABSENT vs PRESENT+OK vs PRESENT+MALFORMED.
     * Does not accept fractional truncation via {@link Number#longValue()}.
     */
    private static BoundedQueryCompleteness.PlatformTotal readPlatformTotalFlexible(ValueCollection row) {
        Object raw = valueByKeyIgnoreCase(row, "totalCount");
        if (raw != null) {
            return platformTotalFromRaw(raw);
        }
        try {
            for (String k : row.keySet()) {
                if (k == null) {
                    continue;
                }
                String kl = k.toLowerCase(Locale.ROOT);
                if (kl.equals("totalrows") || (kl.contains("total") && kl.contains("count"))) {
                    Object alt = row.getValue(k);
                    if (alt != null) {
                        return platformTotalFromRaw(alt);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return BoundedQueryCompleteness.PlatformTotal.absent();
    }

    private static BoundedQueryCompleteness.PlatformTotal platformTotalFromRaw(Object v) {
        if (v == null) {
            return BoundedQueryCompleteness.PlatformTotal.absent();
        }
        if (v instanceof IPrimitiveType) {
            try {
                v = ((IPrimitiveType) v).getValue();
            } catch (Exception e) {
                return BoundedQueryCompleteness.PlatformTotal.malformed();
            }
        }
        if (v == null) {
            return BoundedQueryCompleteness.PlatformTotal.absent();
        }
        if (v instanceof Number) {
            return BoundedQueryCompleteness.platformTotalFromNumber((Number) v);
        }
        return BoundedQueryCompleteness.platformTotalFromExactDecimalText(v.toString());
    }

    /**
     * When the platform omits {@code count} in {@code QueryImplementingThingsOptimizedProcessor}, {@code totalCount} on the
     * result row is null. Infer an exact total when possible: last (under-filled) page, or full first page with a follow-up
     * empty page at {@code offset + maxItems}.
     */
    private static Long tryInferTotalWhenPlatformOmitsCount(IServiceProvider provider, ServiceDefinition sd, String serviceUsed,
            int maxItems, int offset, String namePrefix, boolean withPermissions, JsonNode queryNode, JsonNode modelTagsNode,
            ColumnPick columns, int returned, Long parsedTotal) throws Exception {
        if (parsedTotal != null || !SERVICE_OPTIMIZED_TOTAL.equals(serviceUsed) || returned <= 0) {
            return null;
        }
        if (returned < maxItems) {
            return (long) offset + returned;
        }
        if (returned != maxItems || maxItems <= 0) {
            return null;
        }
        int probeOffset = offset + maxItems;
        try {
            ValueCollection probeParams = buildImplementingThingsParams(sd, 1, probeOffset, namePrefix, withPermissions,
                    queryNode, modelTagsNode, columns);
            InfoTable probeOuter = provider.processAPIServiceRequest(serviceUsed, probeParams);
            QitParse probe = parseImplementingThingsOutput(probeOuter);
            int probeRows = probe.dataTable != null ? probe.dataTable.getRowCount() : 0;
            if (probeRows == 0) {
                LOG.debug("query_entities: inferred totalRows={} (totalCount null; probe page at offset {} empty)",
                        offset + returned, probeOffset);
                return (long) offset + returned;
            }
        } catch (Exception e) {
            LOG.debug("query_entities: total probe at offset {} failed: {}", probeOffset, e.getMessage());
        }
        return null;
    }

    private static boolean looksLikeThingListing(InfoTable it) {
        if (it == null || it.getRowCount() == 0) {
            return false;
        }
        List<String> cols = columnNames(it);
        for (String c : cols) {
            if (c != null && c.equalsIgnoreCase("name")) {
                return true;
            }
        }
        return false;
    }

    private static InfoTable asInfoTable(Object v) {
        if (v instanceof InfoTable) {
            return (InfoTable) v;
        }
        if (v instanceof InfoTablePrimitive) {
            return ((InfoTablePrimitive) v).getValue();
        }
        return null;
    }

    private static InfoTable firstInfoTable(ValueCollection row, String... preferredKeys) {
        for (String k : preferredKeys) {
            InfoTable it = asInfoTable(row.getValue(k));
            if (it != null) {
                return it;
            }
        }
        try {
            for (String key : row.keySet()) {
                if (key == null) {
                    continue;
                }
                String kl = key.toLowerCase(Locale.ROOT);
                if (kl.contains("total") || kl.contains("count")) {
                    continue;
                }
                InfoTable it = asInfoTable(row.getValue(key));
                if (it != null) {
                    return it;
                }
            }
        } catch (Exception ignored) {
            // keySet may be unsupported
        }
        return null;
    }

    static ValueCollection buildImplementingThingsParams(ServiceDefinition sd, int maxItems, int offset,
            String nameMask, boolean withPermissions, JsonNode queryNode, JsonNode modelTagsNode, ColumnPick columns)
            throws Exception {
        ValueCollection vc = new ValueCollection();
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return vc;
        }

        boolean maxSet = false;
        boolean offsetSet = false;
        boolean nameMaskSet = false;
        boolean withPermissionsSet = false;
        boolean querySet = false;
        boolean tagsSet = false;
        double maxD = maxItems;
        double offsetD = offset;

        for (FieldDefinition fd : defs.values()) {
            String n = fd.getName();
            if (n == null) {
                continue;
            }
            String nl = n.toLowerCase(Locale.ROOT);
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;

            // ThingTemplate: maxItems + offset are NUMBER (Double) on Optimized* services
            if (bt == BaseTypes.NUMBER) {
                if (nl.equals("offset")) {
                    vc.put(n, new NumberPrimitive(offsetD));
                    offsetSet = true;
                    continue;
                }
                if (nl.contains("max") && nl.contains("item")) {
                    vc.put(n, new NumberPrimitive(maxD));
                    maxSet = true;
                    continue;
                }
            }
            if (bt == BaseTypes.INTEGER || bt == BaseTypes.LONG) {
                if (nl.contains("offset")) {
                    vc.put(n, new IntegerPrimitive(offset));
                    offsetSet = true;
                    continue;
                }
                if (nl.contains("max") && (nl.contains("item") || nl.equals("nmaxitems") || nl.equals("maxitems"))) {
                    vc.put(n, new IntegerPrimitive(maxItems));
                    maxSet = true;
                    continue;
                }
            }

            if ((bt == BaseTypes.STRING || bt == BaseTypes.TEXT) && (nl.contains("namemask") || nl.equals("mask"))) {
                if (nameMask != null && !nameMask.isBlank()) {
                    vc.put(n, new StringPrimitive(nameMask.trim()));
                    nameMaskSet = true;
                }
                continue;
            }

            if (bt == BaseTypes.BOOLEAN && nl.contains("withpermissions")) {
                vc.put(n, new BooleanPrimitive(withPermissions));
                withPermissionsSet = true;
                continue;
            }
            if (bt == BaseTypes.BOOLEAN && nl.contains("withdata")) {
                // Legacy / other services only; Optimized uses withPermissions
                continue;
            }
            if (bt == BaseTypes.BOOLEAN && nl.contains("sortfirst")) {
                // QueryImplementingThings (9.4+); default false — omit unless we expose it on the tool
                continue;
            }

            if (bt == BaseTypes.QUERY && queryNode != null && !queryNode.isNull()) {
                JSONObject jo = QueryJsonPrimitiveMapper.parseQueryObject(n, queryNode, MAPPER);
                QueryJsonPrimitiveMapper.validateQueryObject(jo, n);
                // Platform service methods take org.json.JSONObject for QUERY params (ThingTemplate /
                // DataTableThing); processServiceRequest expects JSONPrimitive, not com.thingworx.types.data.queries.Query.
                vc.put(n, new JSONPrimitive(jo));
                querySet = true;
                continue;
            }

            if (bt == BaseTypes.TAGS) {
                vc.put(n, TagJsonCodec.parseTagCollectionPrimitive(modelTagsNode));
                tagsSet = true;
                continue;
            }

            // Optional network filters: leave unset
        }

        if (!maxSet) {
            tryPutNumberByName(vc, defs, "maxItems", maxD);
            tryPutIntByName(vc, defs, "maxItems", maxItems);
            tryPutIntByName(vc, defs, "nMaxItems", maxItems);
        }
        if (!offsetSet) {
            tryPutNumberByName(vc, defs, "offset", offsetD);
            tryPutIntByName(vc, defs, "offset", offset);
        }
        if (!nameMaskSet && nameMask != null && !nameMask.isBlank()) {
            tryPutStringByName(vc, defs, "nameMask", nameMask.trim());
        }
        if (!withPermissionsSet) {
            tryPutBooleanByName(vc, defs, "withPermissions", withPermissions);
        }
        if (!querySet && queryNode != null && !queryNode.isNull()) {
            tryPutQueryByName(vc, defs, queryNode);
        }
        if (!tagsSet) {
            tryPutEmptyTagsParam(vc, defs, modelTagsNode);
        }

        if (columns != null && !columns.widePropertyColumns) {
            putEntityListParameterIfPresent(vc, defs, "basicPropertyNames", columns.basicPropertyNames);
            putEntityListParameterIfPresent(vc, defs, "propertyNames", columns.propertyNames);
        }

        return vc;
    }

    private static void tryPutEmptyTagsParam(ValueCollection vc, FieldDefinitionCollection defs, JsonNode modelTagsNode)
            throws Exception {
        for (FieldDefinition fd : defs.values()) {
            if (fd.getBaseType() == BaseTypes.TAGS) {
                vc.put(fd.getName(), TagJsonCodec.parseTagCollectionPrimitive(modelTagsNode));
                return;
            }
        }
    }

    private static void tryPutNumberByName(ValueCollection vc, FieldDefinitionCollection defs, String want, double value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName()) && fd.getBaseType() == BaseTypes.NUMBER) {
                try {
                    vc.put(fd.getName(), new NumberPrimitive(value));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutIntByName(ValueCollection vc, FieldDefinitionCollection defs, String want, int value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName())
                    && (fd.getBaseType() == BaseTypes.INTEGER || fd.getBaseType() == BaseTypes.LONG)) {
                try {
                    vc.put(fd.getName(), new IntegerPrimitive(value));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutStringByName(ValueCollection vc, FieldDefinitionCollection defs, String want, String value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName())
                    && (fd.getBaseType() == BaseTypes.STRING || fd.getBaseType() == BaseTypes.TEXT)) {
                try {
                    vc.put(fd.getName(), new StringPrimitive(value));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutBooleanByName(ValueCollection vc, FieldDefinitionCollection defs, String want, boolean value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName()) && fd.getBaseType() == BaseTypes.BOOLEAN) {
                try {
                    vc.put(fd.getName(), new BooleanPrimitive(value));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutQueryByName(ValueCollection vc, FieldDefinitionCollection defs, JsonNode queryNode)
            throws Exception {
        for (FieldDefinition fd : defs.values()) {
            if ("query".equalsIgnoreCase(fd.getName()) && fd.getBaseType() == BaseTypes.QUERY) {
                JSONObject jo = QueryJsonPrimitiveMapper.parseQueryObject(fd.getName(), queryNode, MAPPER);
                QueryJsonPrimitiveMapper.validateQueryObject(jo, fd.getName());
                vc.put(fd.getName(), new JSONPrimitive(jo));
                return;
            }
        }
    }

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        if (it == null) {
            return names;
        }
        try {
            if (it.getDataShape() != null && it.getDataShape().getFields() != null) {
                for (FieldDefinition f : it.getDataShape().getFields().values()) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    for (String k : row.keySet()) {
                        names.add(k);
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    private static ObjectNode slimThingRow(InfoTable it, int rowIndex, List<String> cols) {
        ObjectNode o = MAPPER.createObjectNode();
        ValueCollection row = it.getRow(rowIndex);
        if (row == null) {
            return o;
        }
        o.put("entityType", "Thing");
        String name = firstString(row, cols, "name", "entityName", "EntityName", "thingName");
        if (name != null) {
            o.put("name", name);
        }
        String desc = firstString(row, cols, "description", "Description");
        if (desc != null && !desc.isEmpty()) {
            String d = desc.length() > 400 ? desc.substring(0, 400) + "..." : desc;
            o.put("description", d);
        }
        String tt = firstString(row, cols, "thingTemplate", "thingTemplateName", "ThingTemplate", "implementedTemplate");
        if (tt != null) {
            o.put("thingTemplate", tt);
        }
        String tags = firstString(row, cols, "tags", "Tags");
        if (tags != null) {
            o.put("tags", tags.length() > 200 ? tags.substring(0, 200) + "..." : tags);
        }
        Boolean iso = firstBoolean(row, cols, "isSystemObject", "IsSystemObject");
        if (iso != null) {
            o.put("isSystemObject", iso);
        }
        return o;
    }

    /**
     * Column metadata for slim {@code ENTITY_QUERY_*} rows; matches {@link #slimThingRow} keys so
     * {@link com.thingworx.things.agent.compaction.InfoTableMatrixCodec} can encode replay payloads.
     */
    static ArrayNode queryEntitiesSlimColumnsMetadata() {
        ArrayNode a = MAPPER.createArrayNode();
        slimCol(a, "entityType", "STRING");
        slimCol(a, "name", "THINGNAME");
        slimCol(a, "description", "STRING");
        slimCol(a, "thingTemplate", "THINGTEMPLATENAME");
        slimCol(a, "tags", "TAGS");
        slimCol(a, "isSystemObject", "BOOLEAN");
        return a;
    }

    private static void slimCol(ArrayNode cols, String name, String baseType) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("name", name);
        c.put("baseType", baseType);
        cols.add(c);
    }

    private static Boolean firstBoolean(ValueCollection row, List<String> cols, String... preferredNames) {
        for (String pref : preferredNames) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(pref)) {
                    Object v = row.getValue(c);
                    if (v instanceof IPrimitiveType) {
                        try {
                            Object inner = ((IPrimitiveType) v).getValue();
                            if (inner instanceof Boolean) {
                                return (Boolean) inner;
                            }
                        } catch (Exception ignored) {
                            // fall through
                        }
                    }
                    if (v instanceof Boolean) {
                        return (Boolean) v;
                    }
                }
            }
        }
        return null;
    }

    private static String firstString(ValueCollection row, List<String> cols, String... preferredNames) {
        for (String pref : preferredNames) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(pref)) {
                    Object v = row.getValue(c);
                    String s = primitiveToString(v);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private static String primitiveToString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return inner != null ? inner.toString() : null;
            } catch (Exception e) {
                return v.toString();
            }
        }
        return v.toString();
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    static InfoTable applyIntersectThingNamesFilter(InfoTable data, Set<String> b) throws Exception {
        if (data == null || b == null || b.isEmpty()) {
            return data;
        }
        int n = data.getRowCount();
        List<String> cols = columnNames(data);
        List<Integer> keep = new ArrayList<>();
        boolean loggedMissingIntersectName = false;
        for (int i = 0; i < n; i++) {
            ValueCollection row = data.getRow(i);
            String nm = firstString(row, cols, "name", "entityName", "EntityName", "thingName");
            if (nm == null && !loggedMissingIntersectName) {
                LOG.debug("query_entities intersect: no name cell in preferred columns; cols={}", cols);
                loggedMissingIntersectName = true;
            }
            if (nm != null && b.contains(nm)) {
                keep.add(i);
            }
        }
        if (keep.size() == n) {
            return data;
        }
        return sliceInfoTableByRowIndices(data, keep);
    }

    private static InfoTable sliceInfoTableByRowIndices(InfoTable data, List<Integer> indices) throws Exception {
        InfoTable out = InfoTableInstanceFactory.createInfoTableFromDataShape(data.getDataShape().getName());
        for (int i : indices) {
            out.addRow(data.getRow(i));
        }
        return out;
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
