package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.thingworx.things.agent.hierarchy.HierarchyQueryEntitiesIntersectAugment;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;

/**
 * Lists Things under a single {@link com.thingworx.things.ThingTemplate} or {@link com.thingworx.things.ThingShape},
 * then optionally filters by {@code LookupProperties} (<b>OR</b> across keys: any one property–value pair may match)
 * and projects columns — an interim substitute until the platform exposes a single query that can constrain by
 * <b>both</b> ThingTemplate and ThingShape at once.
 *
 * <p><b>Platform gap (workaround):</b> ThingWorx today lists implementors per parent (template <em>or</em> shape).
 * When you need taxonomy-style selection across both dimensions, the product should add a dedicated service or tool
 * that accepts combined template+shape criteria. Until that exists, this tool implements client-side filtering on top
 * of {@value QueryEntitiesExecutor#SERVICE_OPTIMIZED_TOTAL} with {@code maxItems=5000} and name-only QIT columns.</p>
 */
public final class QueryEntitiesByTaxonomyExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(QueryEntitiesByTaxonomyExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Internal page size for {@link QueryEntitiesExecutor#SERVICE_OPTIMIZED_TOTAL} (name-only listing).
     * **Not** the same concept as {@link EntityHierarchyIntersectHelper#MAX_INTERSECT_NAMES} (LLM name-list cap);
     * values match in v1 only by coincidence.
     */
    private static final int QIT_MAX_ITEMS = 5000;

    private QueryEntitiesByTaxonomyExecutor() {}

    public static String executeQueryEntitiesByTaxonomy(ToolCall call) {
        try {
            return doExecute(call);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("query_entities_by_taxonomy: {}", e.getMessage(), e);
            return errorJson("QUERY_ENTITIES_BY_TAXONOMY_ERROR", e.getMessage());
        }
    }

    private static String doExecute(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        if (root instanceof ObjectNode) {
            String injectErr = HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyScope(
                    (ObjectNode) root);
            if (injectErr != null) {
                return injectErr;
            }
        }
        String entityType = textExact(root, "EntityType");
        String entityName = textExact(root, "EntityName");
        if (entityName == null || entityName.isEmpty()) {
            return errorJson("MISSING_ENTITY_NAME", "EntityName is required.");
        }
        if (!"ThingTemplate".equals(entityType) && !"ThingShape".equals(entityType)) {
            return errorJson("INVALID_ENTITY_TYPE",
                    "EntityType must be exactly \"ThingTemplate\" or \"ThingShape\" (case-sensitive).");
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

        boolean templateParent = "ThingTemplate".equals(entityType);
        RelationshipTypes.ThingworxRelationshipTypes rel = templateParent
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                : RelationshipTypes.ThingworxRelationshipTypes.ThingShape;

        RootEntity parent = PlatformAccess.findAsUser(entityName, rel);
        if (parent == null) {
            return errorJson("ENTITY_NOT_FOUND", "No " + rel.name() + " named \"" + entityName + "\".");
        }
        if (!(parent instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", parent.getClass().getSimpleName() + " does not expose services.");
        }
        IServiceProvider provider = (IServiceProvider) parent;

        ServiceDefinition sd;
        try {
            sd = provider.getInstanceServiceDefinition(QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL);
        } catch (Exception e) {
            sd = null;
        }
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND",
                    "QueryImplementingThingsOptimizedWithTotalCount is required on " + rel.name() + " \"" + entityName
                            + "\" for query_entities_by_taxonomy.");
        }

        QueryEntitiesExecutor.ColumnPick columns = QueryEntitiesExecutor.createLeanNameOnlyPick(templateParent);
        ValueCollection params = QueryEntitiesExecutor.buildImplementingThingsParams(sd, QIT_MAX_ITEMS, 0, null, false,
                null, null, columns);

        InfoTable outer;
        try {
            outer = provider.processAPIServiceRequest(QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL, params);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            LOG.warn("query_entities_by_taxonomy SERVICE_EXECUTION_FAILED {}.{}: {}", entityName,
                    QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL, msg);
            return errorJson("QUERY_SERVICE_FAILED", msg);
        }

        QueryEntitiesExecutor.QitParse parsed = QueryEntitiesExecutor.parseImplementingThingsOutput(outer);
        if (parsed.listingValidity != BoundedQueryCompleteness.ListingValidity.VALID_TABLE) {
            return errorJson("TAXONOMY_LISTING_MALFORMED",
                    "QueryImplementingThingsOptimizedWithTotalCount did not return a recognized implementor listing.");
        }
        InfoTable rootList = parsed.dataTable;
        int qitListingRows = rootList != null ? rootList.getRowCount() : 0;

        List<String> critical = parseSemicolonList(text(root, "CriticalProperties"));
        List<String> additional = parseSemicolonList(text(root, "AdditionalProperties"));
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        wanted.add("name");
        wanted.addAll(critical);
        wanted.addAll(additional);

        JsonNode lookupNode = root.get("LookupProperties");
        if (lookupNode != null && lookupNode.isTextual()) {
            String raw = lookupNode.asText().trim();
            if (!raw.isEmpty()) {
                try {
                    lookupNode = MAPPER.readTree(raw);
                } catch (Exception e) {
                    return errorJson("INVALID_LOOKUP_PROPERTIES", "LookupProperties must be a JSON object (or JSON text).");
                }
            }
        }
        ObjectNode lookupObj = null;
        if (lookupNode != null && lookupNode.isObject() && lookupNode.size() > 0) {
            lookupObj = (ObjectNode) lookupNode;
        }
        final boolean filtered = lookupObj != null;

        ProjectedTable projected = null;
        int evaluationLossCount = 0;
        if (rootList != null) {
            String firstName = firstListingThingName(rootList);
            if (firstName == null || firstName.isEmpty()) {
                LOG.warn("query_entities_by_taxonomy: rootEntityList has no usable name column");
                evaluationLossCount = qitListingRows;
            } else {
                Thing probeThing = resolveThing(firstName);
                projected = buildProjectedTable(probeThing, wanted, firstName);
            }
        }

        List<ValueCollection> matchedRows = new ArrayList<>();
        if (projected != null && rootList != null) {
            for (int i = 0; i < rootList.getRowCount(); i++) {
                ValueCollection listRow = rootList.getRow(i);
                String thingName = listingRowName(listRow);
                if (thingName == null || thingName.isEmpty()) {
                    evaluationLossCount++;
                    continue;
                }
                Thing thing;
                try {
                    thing = resolveThing(thingName);
                } catch (Exception ex) {
                    LOG.warn("query_entities_by_taxonomy: skip thing \"{}\": {}", thingName, ex.getMessage());
                    evaluationLossCount++;
                    continue;
                }
                if (!lookupMatches(thing, lookupObj)) {
                    continue;
                }
                matchedRows.add(buildOutputRow(thing, projected));
            }
        }

        BoundedQueryCompleteness.Result completeness = BoundedQueryCompleteness.evaluate(
                parsed.listingValidity,
                qitListingRows,
                parsed.platformTotal,
                QIT_MAX_ITEMS,
                filtered,
                evaluationLossCount,
                intersectActive,
                expandArgHasMore);
        boolean taxonomyQueryHasMore = BoundedQueryCompleteness.queryHasMoreSignal(completeness.querySide);
        boolean completenessUnknown = BoundedQueryCompleteness.completenessUnknownForIntersect(completeness);

        int preIntersectTaxonomy = 0;
        if (intersectActive) {
            preIntersectTaxonomy = matchedRows.size();
            matchedRows = filterMatchedRowsByIntersectNames(matchedRows, intersectB);
        }
        int n = matchedRows.size();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("resultShape", "ImplementedThingsWithTotalCount");
        out.put("parentKind", rel.name());
        out.put("parentName", entityName);
        out.put("totalCount", n);
        out.put("service", QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL);
        if (intersectActive) {
            EntityHierarchyIntersectHelper.writeIntersectSuccessFields(
                    out, true, preIntersectTaxonomy, n, taxonomyQueryHasMore, expandArgHasMore, completenessUnknown);
        } else {
            BoundedQueryCompleteness.writeNonIntersectFields(out, completeness);
        }
        if (intersectActive && intersectParse.droppedEmptyOrNonTextCount() > 0) {
            String extra = "intersectThingNames: dropped " + intersectParse.droppedEmptyOrNonTextCount()
                    + " empty or non-text entries.";
            if (out.has("note") && !out.get("note").isNull()) {
                out.put("note", out.get("note").asText() + " " + extra);
            } else {
                out.put("note", extra);
            }
        }

        if (projected != null) {
            out.set("columns", projectedColumnsMetadata(projected));
        }

        if (n == 0) {
            out.put("resultKind", "ENTITY_TAXONOMY_QUERY_EMPTY");
            out.putArray("rootEntityList");
            return MAPPER.writeValueAsString(out);
        }

        ToolResultEgressGateway.TabularEvidencePlan plan = ToolResultEgressGateway.planTabularEvidence(
                n, "ENTITY_TAXONOMY_QUERY_INLINE", "ENTITY_TAXONOMY_QUERY_LARGE",
                "rootEntityList", "sampleRootEntityList");
        String cacheId = null;
        if (plan.large()) {
            InfoTable built = new InfoTable(projected.dataShape);
            for (ValueCollection row : matchedRows) {
                built.addRow(row);
            }
            cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(built);
        }
        ArrayNode evidenceRows = MAPPER.createArrayNode();
        for (int i = 0; i < plan.rowsToEmit(); i++) {
            evidenceRows.add(valueCollectionToObject(matchedRows.get(i), projected.columnNames));
        }
        ToolResultEgressGateway.applyTabularEvidence(
                out, plan, evidenceRows, cacheId, InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);
        logFirstRowJsonPreview(matchedRows, projected, plan.large() ? "large-sample" : "inline");

        LOG.info("query_entities_by_taxonomy ok parent={}/{} matchedRows={} resultKind={}", rel.name(), entityName, n,
                out.get("resultKind").asText());
        return MAPPER.writeValueAsString(out);
    }

    private static List<ValueCollection> filterMatchedRowsByIntersectNames(List<ValueCollection> rows, Set<String> b) {
        List<ValueCollection> out = new ArrayList<>();
        for (ValueCollection row : rows) {
            String nm = listingRowName(row);
            if (nm != null && b.contains(nm)) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * One INFO line with the first matched row as JSON (truncated) so ApplicationLog correlates tool success with
     * actual keys/values the LLM sees, without dumping the full table.
     */
    private static void logFirstRowJsonPreview(List<ValueCollection> matchedRows, ProjectedTable projected, String context) {
        if (matchedRows == null || matchedRows.isEmpty()) {
            return;
        }
        try {
            ObjectNode first = valueCollectionToObject(matchedRows.get(0), projected.columnNames);
            String json = MAPPER.writeValueAsString(first);
            int fullLen = json.length();
            if (json.length() > 500) {
                json = json.substring(0, 500) + "...";
            }
            LOG.info("query_entities_by_taxonomy firstRowPreview context={} jsonChars={} sample={}", context, fullLen, json);
        } catch (Exception e) {
            LOG.debug("query_entities_by_taxonomy firstRowPreview skip context={}: {}", context, e.getMessage());
        }
    }

    private static List<String> parseSemicolonList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(";")) {
            if (part == null) {
                continue;
            }
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static ArrayNode projectedColumnsMetadata(ProjectedTable projected) {
        ArrayNode cols = MAPPER.createArrayNode();
        if (projected == null || projected.dataShape == null || projected.dataShape.getFields() == null) {
            return cols;
        }
        for (FieldDefinition f : projected.dataShape.getFields().values()) {
            if (f.getName() == null) {
                continue;
            }
            ObjectNode c = MAPPER.createObjectNode();
            c.put("name", f.getName());
            c.put("baseType", f.getBaseType() != null ? f.getBaseType().name() : "STRING");
            cols.add(c);
        }
        return cols;
    }

    /** Column order and {@link DataShapeDefinition} for the projected {@code rootEntityList} rows. */
    private static final class ProjectedTable {
        final DataShapeDefinition dataShape;
        final List<String> columnNames;

        ProjectedTable(DataShapeDefinition dataShape, List<String> columnNames) {
            this.dataShape = dataShape;
            this.columnNames = columnNames;
        }
    }

    private static ProjectedTable buildProjectedTable(Thing probeThing, LinkedHashSet<String> wanted, String probeThingName)
            throws Exception {
        DataShapeDefinition dsd = new DataShapeDefinition();
        List<String> names = new ArrayList<>();
        int ord = 0;
        for (String prop : wanted) {
            BaseTypes bt = resolveProjectionBaseType(probeThing, prop, probeThingName);
            if (bt == null) {
                LOG.warn("query_entities_by_taxonomy: property \"{}\" not readable on sample Thing \"{}\"; column omitted",
                        prop, probeThingName);
                continue;
            }
            FieldDefinition fd = new FieldDefinition();
            fd.setName(prop);
            fd.setBaseType(bt);
            fd.setOrdinal(ord++);
            dsd.addFieldDefinition(fd);
            names.add(prop);
        }
        return new ProjectedTable(dsd, names);
    }

    /**
     * Prefer metadata ({@link #propertyBaseTypeOnThing}); if absent, infer base type from a live
     * {@link #readPropertyPrimitive} read on the probe Thing. Some Things expose runtime values for shape-backed
     * properties without listing every field in {@code getInstancePropertyDefinitions}, which would otherwise drop
     * taxonomy {@code CriticalProperties} columns entirely.
     */
    private static BaseTypes resolveProjectionBaseType(Thing probeThing, String prop, String probeThingName)
            throws Exception {
        BaseTypes bt = propertyBaseTypeOnThing(probeThing, prop);
        if (bt != null) {
            return bt;
        }
        if ("name".equals(prop)) {
            return BaseTypes.STRING;
        }
        IPrimitiveType live = readPropertyPrimitive(probeThing, prop);
        if (live == null) {
            return null;
        }
        BaseTypes fromLive = live.getBaseType();
        if (fromLive != null) {
            LOG.info("query_entities_by_taxonomy: property \"{}\" on \"{}\" inferred from live read (not in instance defs)",
                    prop, probeThingName);
            return fromLive;
        }
        return BaseTypes.STRING;
    }

    private static BaseTypes propertyBaseTypeOnThing(Thing thing, String propertyName) throws Exception {
        if ("name".equals(propertyName)) {
            return BaseTypes.STRING;
        }
        Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
        if (coll == null) {
            return null;
        }
        Object vals = coll.getClass().getMethod("values").invoke(coll);
        if (!(vals instanceof Iterable)) {
            return null;
        }
        for (Object pd : (Iterable<?>) vals) {
            try {
                Object name = pd.getClass().getMethod("getName").invoke(pd);
                if (!propertyName.equals(name)) {
                    continue;
                }
                Object bt = pd.getClass().getMethod("getBaseType").invoke(pd);
                if (bt instanceof BaseTypes) {
                    return (BaseTypes) bt;
                }
            } catch (Exception ignored) {
                // next
            }
        }
        return null;
    }

    private static ValueCollection buildOutputRow(Thing thing, ProjectedTable projected) throws Exception {
        ValueCollection row = new ValueCollection();
        for (String col : projected.columnNames) {
            IPrimitiveType pv;
            if ("name".equals(col)) {
                pv = new StringPrimitive(thing.getName());
            } else {
                pv = readPropertyPrimitive(thing, col);
            }
            if (pv == null) {
                BaseTypes btCol = baseTypeForProjectedColumn(projected, col);
                if (btCol != null && isTextualProjectionBaseType(btCol)) {
                    row.put(col, new StringPrimitive(""));
                } else {
                    LOG.warn("query_entities_by_taxonomy: Thing \"{}\" has no readable value for property \"{}\"",
                            thing.getName(), col);
                }
            } else {
                row.put(col, pv);
            }
        }
        return row;
    }

    private static BaseTypes baseTypeForProjectedColumn(ProjectedTable projected, String col) {
        if (projected.dataShape == null || projected.dataShape.getFields() == null) {
            return null;
        }
        for (FieldDefinition f : projected.dataShape.getFields().values()) {
            if (col.equals(f.getName())) {
                return f.getBaseType() != null ? f.getBaseType() : BaseTypes.STRING;
            }
        }
        return null;
    }

    /**
     * Empty JSON values for these base types are represented as {@code ""} so every projected column appears in
     * {@code rootEntityList} rows (stable keys for the LLM when a Thing has no bound value yet).
     */
    private static boolean isTextualProjectionBaseType(BaseTypes bt) {
        return bt == BaseTypes.STRING || bt == BaseTypes.TEXT || bt == BaseTypes.HTML || bt == BaseTypes.GUID;
    }

    private static ObjectNode valueCollectionToObject(ValueCollection row, List<String> columnNames) {
        ObjectNode o = MAPPER.createObjectNode();
        if (row == null) {
            return o;
        }
        for (String col : columnNames) {
            IPrimitiveType p = primitiveCell(row, col);
            if (p != null) {
                o.set(col, primitiveToJsonNode(p));
            }
        }
        return o;
    }

    /**
     * After {@link ValueCollection#put}, {@link ValueCollection#getValue} may still return {@code null} for a column
     * name (shape-backed rows vs in-memory projection). Fall back to {@link Map#get} when the collection is map-like.
     * Uses Java&nbsp;11-compatible {@code instanceof} (no pattern variables); ThingWorx extension compiles with
     * {@code -source 11} even when the Gradle toolchain runs on a newer JDK.
     */
    @SuppressWarnings("unchecked")
    private static IPrimitiveType primitiveCell(ValueCollection row, String col) {
        Object v = row.getValue(col);
        if (v instanceof IPrimitiveType) {
            return (IPrimitiveType) v;
        }
        if (row instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) row;
            Object v2 = map.get(col);
            if (v2 instanceof IPrimitiveType) {
                return (IPrimitiveType) v2;
            }
        }
        return null;
    }

    /**
     * When {@code LookupProperties} is non-empty: a Thing matches if **any** non-empty property key has a value
     * equal to the expected JSON entry (**OR**). Empty keys are skipped. If there are no usable keys, no filter is applied.
     */
    private static boolean lookupMatches(Thing thing, ObjectNode lookup) throws Exception {
        if (lookup == null) {
            return true;
        }
        boolean sawUsableKey = false;
        Iterator<Map.Entry<String, JsonNode>> it = lookup.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String prop = e.getKey();
            if (prop == null || prop.isEmpty()) {
                continue;
            }
            sawUsableKey = true;
            JsonNode expected = e.getValue();
            IPrimitiveType actual = readPropertyPrimitive(thing, prop);
            if (jsonValueEquals(expected, actual)) {
                return true;
            }
        }
        return !sawUsableKey;
    }

    /**
     * Exact match: JSON literal vs live property primitive (no wildcards / regex).
     */
    private static boolean jsonValueEquals(JsonNode expected, IPrimitiveType actual) {
        if (expected == null || expected.isNull()) {
            if (actual == null) {
                return true;
            }
            try {
                return actual.getValue() == null;
            } catch (Exception e) {
                return false;
            }
        }
        if (actual == null) {
            return false;
        }
        JsonNode actualNode = primitiveToJsonNode(actual);
        if (expected.getNodeType() != actualNode.getNodeType()) {
            if (expected.isNumber() && actualNode.isNumber()) {
                return expected.decimalValue().compareTo(actualNode.decimalValue()) == 0;
            }
            if (expected.isTextual() && actualNode.isNumber()) {
                return expected.asText().equals(actualNode.asText());
            }
            if (expected.isNumber() && actualNode.isTextual()) {
                return expected.asText().equals(actualNode.asText());
            }
        }
        return expected.equals(actualNode);
    }

    private static JsonNode primitiveToJsonNode(IPrimitiveType p) {
        if (p == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        BaseTypes bt = p.getBaseType();
        try {
            if (bt == BaseTypes.BOOLEAN && p instanceof BooleanPrimitive) {
                return MAPPER.getNodeFactory().booleanNode(((BooleanPrimitive) p).getValue());
            }
            if (bt == BaseTypes.INTEGER && p instanceof IntegerPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof LongPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((LongPrimitive) p).getValue());
            }
            if (bt == BaseTypes.LONG && p instanceof IntegerPrimitive) {
                return MAPPER.getNodeFactory().numberNode((long) ((IntegerPrimitive) p).getValue());
            }
            if (bt == BaseTypes.NUMBER && p instanceof NumberPrimitive) {
                return MAPPER.getNodeFactory().numberNode(((NumberPrimitive) p).getValue());
            }
            if (p instanceof StringPrimitive) {
                return MAPPER.getNodeFactory().textNode(((StringPrimitive) p).getValue());
            }
            if (p instanceof DatetimePrimitive) {
                return MAPPER.getNodeFactory().textNode(((DatetimePrimitive) p).getValue().toString());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return MAPPER.getNodeFactory().textNode(p.toString());
    }

    private static IPrimitiveType readPropertyPrimitive(Thing thing, String name) throws Exception {
        return PropertyValueReads.readCurrentValue(thing, name);
    }

    private static Thing resolveThing(String thingName) throws Exception {
        RootEntity re = PlatformAccess.findAsUser(thingName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (re == null) {
            throw new IllegalArgumentException("Thing not found: " + thingName);
        }
        if (!(re instanceof Thing)) {
            throw new IllegalArgumentException("Entity is not a Thing: " + thingName);
        }
        return (Thing) re;
    }

    private static String firstListingThingName(InfoTable rootList) {
        for (int i = 0; i < rootList.getRowCount(); i++) {
            String n = listingRowName(rootList.getRow(i));
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return null;
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
        try {
            for (String k : row.keySet()) {
                if (k != null && k.equalsIgnoreCase("name")) {
                    String s = primitiveToPlainString(row.getValue(k));
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static String primitiveToPlainString(Object v) {
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
        String s = n.asText();
        return s != null && !s.isEmpty() ? s : null;
    }

    /** Field name as sent by the tool schema (case-sensitive keys in JSON). */
    private static String textExact(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
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
