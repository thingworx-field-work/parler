package com.thingworx.things.agent.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.EventDefinitionCollection;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * Built-in {@code describe_entity_schema}: facet-bounded schema reads for {@code ThingTemplate}, {@code ThingShape},
 * and {@code DataShape} using visibility-aware {@link EntityUtilities#findEntity(String, RelationshipTypes.ThingworxRelationshipTypes)}.
 *
 * <p>Does <strong>not</strong> emit {@code parler.entity.metadata.v1}: facet responses stay
 * outside Tier B entity-metadata promotion until a dedicated format is reviewed.</p>
 *
 * @see docs/agent/entity-schema-description.md
 */
public final class DescribeEntitySchemaExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(DescribeEntitySchemaExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_MAX_ITEMS = 80;
    private static final int MAX_MAX_ITEMS = 200;
    private static final int DESC_TRUNCATE_LIST = 200;

    private static final Set<String> SUPPORTED_SCHEMA_TYPES = Set.of("ThingTemplate", "ThingShape", "DataShape");

    /** Paged list slice; package-private for unit tests in the same package. */
    static final class ListPageBounds {
        final int offset;
        final int endExclusive;
        final int returned;

        ListPageBounds(int offset, int endExclusive, int returned) {
            this.offset = offset;
            this.endExclusive = endExclusive;
            this.returned = returned;
        }
    }

    /**
     * Clamp {@code offset} to {@code [0, totalMatched]} and compute a page window so {@code returned} is never negative;
     * guards {@code offset + maxItems} overflow.
     */
    static ListPageBounds computeListPageBounds(long rawOffset, long rawMaxItems, int totalMatched) {
        long maxItems = rawMaxItems < 1 ? DEFAULT_MAX_ITEMS : rawMaxItems;
        maxItems = Math.min(maxItems, MAX_MAX_ITEMS);
        long total = totalMatched < 0 ? 0 : totalMatched;
        long offset = rawOffset < 0 ? 0 : rawOffset;
        if (offset > total) {
            offset = total;
        }
        long end;
        try {
            end = Math.addExact(offset, maxItems);
        } catch (ArithmeticException overflow) {
            end = total;
        }
        end = Math.min(end, total);
        int o = (int) Math.min(offset, Integer.MAX_VALUE);
        int e = (int) Math.min(end, Integer.MAX_VALUE);
        return new ListPageBounds(o, e, e - o);
    }

    /**
     * Local template/shape reads failed in a way that must not be surfaced as an empty authoritative schema.
     */
    static final class DescribeEntitySchemaPlatformException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        DescribeEntitySchemaPlatformException(String errorCode, String message, Throwable cause) {
            super(message != null ? message : "", cause);
            this.errorCode = errorCode;
        }

        String getErrorCode() {
            return errorCode;
        }
    }

    private DescribeEntitySchemaExecutor() {}

    public static String execute(ToolCall call) {
        try {
            return doDescribe(call);
        } catch (DescribeEntitySchemaPlatformException e) {
            LOG.warn("describe_entity_schema platform unavailable: {}", e.getMessage());
            return errorJson(e.getErrorCode(), e.getMessage(), null);
        } catch (Exception e) {
            LOG.warn("describe_entity_schema: {}", e.getMessage());
            return errorJson("DESCRIBE_ENTITY_SCHEMA_ERROR", e.getMessage(), null);
        }
    }

    private static String doDescribe(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        if (!root.isObject()) {
            return errorJson("INVALID_ARGUMENTS", "Arguments must be a JSON object.", null);
        }
        if (root.has("includePrivateServices")) {
            return errorJson("UNSUPPORTED_TOOL_PARAMETER",
                    "includePrivateServices is not supported in describe_entity_schema v1.", null);
        }

        String entityTypeRaw = text(root, "entityType");
        String entityName = text(root, "entityName");
        if (entityTypeRaw == null || entityTypeRaw.isEmpty()) {
            return errorJson("MISSING_ENTITY_TYPE", "entityType is required", null);
        }
        if (entityName == null || entityName.isEmpty()) {
            return errorJson("MISSING_ENTITY_NAME", "entityName is required", null);
        }

        String unsupported = unsupportedEntityTypeJson(entityTypeRaw);
        if (unsupported != null) {
            return unsupported;
        }

        String normalizedType = normalizeSchemaEntityType(entityTypeRaw.trim());
        RelationshipTypes.ThingworxRelationshipTypes rel =
                ThingworxRootEntityTypes.parseRootEntityType(normalizedType);
        if (rel == null) {
            return unsupportedEntityTypeJson(entityTypeRaw);
        }

        String facet = text(root, "facet");
        if (facet == null || facet.isEmpty()) {
            facet = "summary";
        } else {
            facet = facet.trim();
        }

        if ("subscriptions".equalsIgnoreCase(facet)) {
            return errorJson("INVALID_FACET_FOR_ENTITY_TYPE",
                    "facet \"subscriptions\" is not supported on describe_entity_schema v1.", null);
        }

        String scopeRaw = text(root, "scope");
        boolean effectiveScope = scopeRaw == null || scopeRaw.isEmpty() || "effective".equalsIgnoreCase(scopeRaw);
        if (!effectiveScope && !"local".equalsIgnoreCase(scopeRaw)) {
            return errorJson("INVALID_SCOPE", "scope must be \"effective\" or \"local\".", null);
        }
        String scopeLabel = effectiveScope ? "effective" : "local";

        String facetError = validateFacetForEntityTypeEarly(facet, normalizedType);
        if (facetError != null) {
            return facetError;
        }

        if ("service".equalsIgnoreCase(facet)) {
            String memberName = text(root, "memberName");
            if (memberName == null || memberName.isBlank()) {
                return errorJson("MISSING_MEMBER_NAME", "memberName is required for facet \"service\".", null);
            }
        }

        RootEntity entity;
        try {
            entity = EntityUtilities.findEntity(entityName, rel);
        } catch (Exception e) {
            LOG.warn("describe_entity_schema ENTITY_LOOKUP: {}", e.getMessage());
            return errorJson("ENTITY_NOT_FOUND_OR_NOT_VISIBLE", e.getMessage(), null);
        }
        if (entity == null) {
            return errorJson("ENTITY_NOT_FOUND_OR_NOT_VISIBLE",
                    "Entity not found or not visible for the current user: " + normalizedType + " / " + entityName,
                    null);
        }

        switch (facet.toLowerCase(Locale.ROOT)) {
            case "summary":
                return writeSummary(entity, normalizedType, entityName, scopeLabel, effectiveScope);
            case "properties":
                if (!isTemplateOrShape(normalizedType)) {
                    return invalidFacetForEntity(facet, normalizedType);
                }
                return writePropertyList(root, entity, normalizedType, entityName, scopeLabel, effectiveScope);
            case "property":
            case "event":
            case "field":
                return errorJson("INVALID_FACET_FOR_ENTITY_TYPE",
                        "Singular facet \"" + facet + "\" is not implemented in describe_entity_schema v1.", null);
            case "services":
                if (!isTemplateOrShape(normalizedType)) {
                    return invalidFacetForEntity(facet, normalizedType);
                }
                return writeServiceList(root, entity, normalizedType, entityName, scopeLabel, effectiveScope);
            case "service":
                if (!isTemplateOrShape(normalizedType)) {
                    return invalidFacetForEntity(facet, normalizedType);
                }
                return writeServiceDetail(root, entity, normalizedType, entityName, scopeLabel, effectiveScope);
            case "events":
                if (!isTemplateOrShape(normalizedType)) {
                    return invalidFacetForEntity(facet, normalizedType);
                }
                return writeEventList(root, entity, normalizedType, entityName, scopeLabel, effectiveScope);
            case "fields":
                if (!"DataShape".equals(normalizedType)) {
                    return invalidFacetForEntity(facet, normalizedType);
                }
                return writeFieldList(root, entity, normalizedType, entityName, scopeLabel, effectiveScope);
            default:
                return errorJson("INVALID_FACET_FOR_ENTITY_TYPE", "Unknown facet: " + facet, null);
        }
    }

    private static boolean isTemplateOrShape(String normalizedType) {
        return "ThingTemplate".equals(normalizedType) || "ThingShape".equals(normalizedType);
    }

    private static Object localInstanceShape(RootEntity entity, String normalizedType) throws Exception {
        Object shape = invokeRequiredReadNoArgs(entity, "getInstanceShape");
        ensureNonNullLocalInstanceShape(shape, normalizedType);
        return shape;
    }

    /**
     * {@link ThingTemplate} effective {@code getEffective*Definitions()} methods declare {@code throws Exception};
     * failures must propagate. {@link com.thingworx.thingshape.ThingShape} uses different entry points — keep
     * optional {@link #tryInvokeNoArgs(Object, String)} semantics there.
     * Package-visible for same-package JUnit (a POJO stand-in avoids {@code new ThingTemplate()}
     * ESAPI classpath gaps in tests).
     */
    static Object effectiveMetadataShell(Object entity, String normalizedType, String method) throws Exception {
        if (!"ThingTemplate".equals(normalizedType)) {
            return tryInvokeNoArgs(entity, method);
        }
        return invokeRequiredReadNoArgs(entity, method);
    }

    /**
     * Package-private for JUnit: local ThingTemplate/ThingShape reads require a non-null instance shape.
     */
    static void ensureNonNullLocalInstanceShape(Object shape, String normalizedType) {
        if (shape == null) {
            throw new DescribeEntitySchemaPlatformException("DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE",
                    "getInstanceShape() returned null for " + normalizedType
                            + "; local scope cannot read template/shape members (treated as a platform API failure, "
                            + "not an empty definition).",
                    null);
        }
    }

    /**
     * {@code getEffectivePropertyDefinitions()} / {@code getEffectiveServiceDefinitions()} /
     * {@code getEffectiveEventDefinitions()} on {@link com.thingworx.thingtemplates.ThingTemplate} return a
     * {@link com.thingworx.metadata.ThingShapeDefinitionBase} wrapper — unwrap to the underlying collections.
     */
    static Object unwrapEffectivePropertyDefinitionsSource(Object raw) throws Exception {
        if (raw instanceof com.thingworx.metadata.ThingShapeDefinitionBase) {
            Object pdc = invokeRequiredReadNoArgs(raw, "getPropertyDefinitions");
            return pdc != null ? pdc : raw;
        }
        return raw;
    }

    static Object unwrapEffectiveServiceDefinitionsSource(Object raw) throws Exception {
        if (raw instanceof com.thingworx.metadata.ThingShapeDefinitionBase) {
            Object pub = invokeRequiredReadNoArgs(raw, "getPublicServiceDefinitions");
            if (pub != null && !serviceDefsFromUnknown(pub).isEmpty()) {
                return pub;
            }
            return invokeRequiredReadNoArgs(raw, "getServiceDefinitions");
        }
        return raw;
    }

    static Object unwrapEffectiveEventDefinitionsSource(Object raw) throws Exception {
        if (raw instanceof com.thingworx.metadata.ThingShapeDefinitionBase) {
            Object edc = invokeRequiredReadNoArgs(raw, "getEventDefinitions");
            return edc != null ? edc : raw;
        }
        return raw;
    }

    private static String invalidFacetForEntity(String facet, String normalizedType) {
        return errorJson("INVALID_FACET_FOR_ENTITY_TYPE",
                "facet \"" + facet + "\" is not valid for entityType " + normalizedType + ".", null);
    }

    /** Static facet/type validation before {@link EntityUtilities#findEntity} (avoids JVM lookups on impossible combos). */
    private static String validateFacetForEntityTypeEarly(String facet, String normalizedType) {
        if (facet == null) {
            return null;
        }
        String f = facet.toLowerCase(Locale.ROOT);
        if ("property".equals(f) || "event".equals(f) || "field".equals(f)) {
            return errorJson("INVALID_FACET_FOR_ENTITY_TYPE",
                    "Singular facet \"" + facet + "\" is not implemented in describe_entity_schema v1.", null);
        }
        if ("fields".equals(f) && !"DataShape".equals(normalizedType)) {
            return invalidFacetForEntity(facet, normalizedType);
        }
        if (("properties".equals(f) || "services".equals(f) || "events".equals(f) || "service".equals(f))
                && !isTemplateOrShape(normalizedType)) {
            return invalidFacetForEntity(facet, normalizedType);
        }
        return null;
    }

    /**
     * @return error JSON, or {@code null} when the supplied type is one of ThingTemplate / ThingShape / DataShape
     */
    private static String unsupportedEntityTypeJson(String entityTypeRaw) {
        String t = entityTypeRaw == null ? "" : entityTypeRaw.trim();
        if ("Thing".equalsIgnoreCase(t)) {
            try {
                ObjectNode o = MAPPER.createObjectNode();
                o.put("status", "error");
                o.put("code", "UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION");
                o.put("message",
                        "describe_entity_schema targets ThingTemplate, ThingShape, and DataShape only. "
                                + "For a concrete Thing use discover_thing_members (properties / services facets) "
                                + "and get_property_values when reading values.");
                ObjectNode rh = MAPPER.createObjectNode();
                rh.put("tool", "discover_thing_members");
                rh.put("argument", "thingName");
                o.set("recoveryHint", rh);
                ArrayNode supported = MAPPER.createArrayNode();
                for (String s : new String[] {"ThingTemplate", "ThingShape", "DataShape"}) {
                    supported.add(s);
                }
                o.set("supportedEntityTypes", supported);
                return MAPPER.writeValueAsString(o);
            } catch (Exception e) {
                return "{\"status\":\"error\",\"code\":\"UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION\"}";
            }
        }
        if (normalizeSchemaEntityType(t) != null) {
            return null;
        }
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION");
            o.put("message",
                    "describe_entity_schema only supports ThingTemplate, ThingShape, and DataShape. "
                            + "Do not pass service-target aliases such as DataTable/Stream as entityType; "
                            + "resolve the concrete schema entity name first.");
            ArrayNode supported = MAPPER.createArrayNode();
            for (String s : new String[] {"ThingTemplate", "ThingShape", "DataShape"}) {
                supported.add(s);
            }
            o.set("supportedEntityTypes", supported);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"UNSUPPORTED_ENTITY_TYPE_FOR_SCHEMA_DESCRIPTION\"}";
        }
    }

    private static String normalizeSchemaEntityType(String trimmed) {
        for (String s : SUPPORTED_SCHEMA_TYPES) {
            if (s.equalsIgnoreCase(trimmed)) {
                return s;
            }
        }
        return null;
    }

    private static String writeSummary(RootEntity entity, String normalizedType, String entityName, String scopeLabel,
            boolean effectiveScope) throws Exception {
        ObjectNode out = baseSuccess(normalizedType, entityName, "summary", scopeLabel);
        out.put("description", truncateDescription(entity.getDescription()));
        String template = invokeStringGetter(entity, "getThingTemplateName");
        if (template != null && !template.isEmpty()) {
            out.put("thingTemplateName", template);
        }
        int propCount = 0;
        int svcCount = 0;
        int evtCount = 0;
        int fieldCount = 0;
        if ("DataShape".equals(normalizedType)) {
            fieldCount = countFields(entity, effectiveScope);
        } else {
            propCount = countProperties(entity, effectiveScope, normalizedType);
            svcCount = countServices(entity, effectiveScope, normalizedType);
            evtCount = countEvents(entity, effectiveScope, normalizedType);
        }
        out.put("propertyCount", propCount);
        out.put("serviceCount", svcCount);
        out.put("eventCount", evtCount);
        out.put("fieldCount", fieldCount);
        return MAPPER.writeValueAsString(out);
    }

    private static String writePropertyList(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String scopeLabel, boolean effectiveScope) throws Exception {
        List<PropertyDefinition> all = listPropertyDefinitions(entity, effectiveScope, normalizedType);
        List<PropertyDefinition> matched = filterProperties(all, root);
        return writePagedItems(root, entity, normalizedType, entityName, "properties", scopeLabel, matched.size(),
                pd -> compactPropertyRow(pd), matched);
    }

    private static String writeServiceList(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String scopeLabel, boolean effectiveScope) throws Exception {
        if (!(entity instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", "Entity does not expose services.", null);
        }
        List<ServiceDefinition> all = listPublicServiceDefinitions(entity, effectiveScope, normalizedType);
        List<ServiceDefinition> matched = filterServices(all, root);
        return writePagedItems(root, entity, normalizedType, entityName, "services", scopeLabel, matched.size(),
                sd -> compactServiceRow(sd), matched);
    }

    private static String writeEventList(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String scopeLabel, boolean effectiveScope) throws Exception {
        List<EventDefinition> all = listEventDefinitions(entity, effectiveScope, normalizedType);
        List<EventDefinition> matched = filterEvents(all, root);
        return writePagedItems(root, entity, normalizedType, entityName, "events", scopeLabel, matched.size(),
                ed -> compactEventRow(ed), matched);
    }

    private static String writeFieldList(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String scopeLabel, boolean effectiveScope) throws Exception {
        List<FieldDefinition> all = listDataShapeFields(entity, effectiveScope);
        List<FieldDefinition> matched = filterFields(all, root);
        return writePagedItems(root, entity, normalizedType, entityName, "fields", scopeLabel, matched.size(),
                fd -> compactFieldRow(fd), matched);
    }

    private interface RowSerializer<T> {
        ObjectNode toRow(T t) throws Exception;
    }

    private static <T> String writePagedItems(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String facet, String scopeLabel, int totalMatched, RowSerializer<T> serializer, List<T> matched)
            throws Exception {
        long rawOffset = root.has("offset") ? root.get("offset").asLong(0) : 0L;
        long rawMax = root.has("maxItems") ? root.get("maxItems").asLong(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        ListPageBounds page = computeListPageBounds(rawOffset, rawMax, totalMatched);
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = page.offset; i < page.endExclusive; i++) {
            items.add(serializer.toRow(matched.get(i)));
        }
        ObjectNode out = baseSuccess(normalizedType, entityName, facet, scopeLabel);
        out.set("items", items);
        out.put("totalMatched", totalMatched);
        out.put("offset", page.offset);
        out.put("returned", page.returned);
        out.put("hasMore", page.endExclusive < totalMatched);
        return MAPPER.writeValueAsString(out);
    }

    private static String writeServiceDetail(JsonNode root, RootEntity entity, String normalizedType, String entityName,
            String scopeLabel, boolean effectiveScope) throws Exception {
        String memberName = text(root, "memberName");
        if (memberName == null || memberName.isBlank()) {
            return errorJson("MISSING_MEMBER_NAME", "memberName is required for facet \"service\".", null);
        }
        if (!(entity instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", "Entity does not expose services.", null);
        }
        ServiceDefinition sd = findPublicServiceDefinition(entity, memberName.trim(), effectiveScope, normalizedType);
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND",
                    "Public service \"" + memberName + "\" not found on this schema entity.", null);
        }
        ObjectNode out = baseSuccess(normalizedType, entityName, "service", scopeLabel);
        ObjectNode svc = MAPPER.createObjectNode();
        svc.put("name", sd.getName());
        String d = sd.getDescription();
        svc.put("description", truncateDescription(d));

        ArrayNode params = MAPPER.createArrayNode();
        FieldDefinitionCollection pdefs = sd.getParameters();
        if (pdefs != null && pdefs.values() != null) {
            for (FieldDefinition fd : pdefs.values()) {
                if (fd.getName() == null) {
                    continue;
                }
                ObjectNode p = MAPPER.createObjectNode();
                p.put("name", fd.getName());
                p.put("baseType", fd.getBaseType() != null ? fd.getBaseType().name() : "STRING");
                p.put("required", isRequiredParam(fd));
                if (fd.getBaseType() == BaseTypes.INFOTABLE) {
                    String ds = fieldAspectString(fd, "dataShape");
                    if (ds != null && !ds.isEmpty()) {
                        p.put("dataShape", ds);
                    }
                }
                params.add(p);
            }
        }
        svc.set("parameters", params);

        ObjectNode res = MAPPER.createObjectNode();
        FieldDefinition rt = sd.getResultType();
        if (rt != null && rt.getBaseType() != null) {
            res.put("baseType", rt.getBaseType().name());
            if (rt.getBaseType() == BaseTypes.INFOTABLE) {
                String ds = fieldAspectString(rt, "dataShape");
                if (ds != null && !ds.isEmpty()) {
                    res.put("dataShape", ds);
                }
            }
        } else {
            res.put("baseType", "NOTHING");
        }
        svc.set("result", res);
        out.set("service", svc);
        return MAPPER.writeValueAsString(out);
    }

    private static ObjectNode baseSuccess(String normalizedType, String entityName, String facet, String scopeLabel) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", normalizedType);
        out.put("entityName", entityName);
        out.put("facet", facet);
        out.put("scope", scopeLabel);
        return out;
    }

    private static int countProperties(RootEntity entity, boolean effectiveScope, String normalizedType) {
        return listPropertyDefinitions(entity, effectiveScope, normalizedType).size();
    }

    private static int countServices(RootEntity entity, boolean effectiveScope, String normalizedType) {
        return listPublicServiceDefinitions(entity, effectiveScope, normalizedType).size();
    }

    private static int countEvents(RootEntity entity, boolean effectiveScope, String normalizedType) {
        return listEventDefinitions(entity, effectiveScope, normalizedType).size();
    }

    private static int countFields(RootEntity entity, boolean effectiveScope) {
        return listDataShapeFields(entity, effectiveScope).size();
    }

    private static List<PropertyDefinition> listPropertyDefinitions(RootEntity entity, boolean effectiveScope,
            String normalizedType) {
        try {
            if (!effectiveScope && isTemplateOrShape(normalizedType)) {
                Object shape = localInstanceShape(entity, normalizedType);
                Object coll = invokeRequiredReadNoArgs(shape, "getPropertyDefinitions");
                return propertyDefsFromUnknown(coll);
            }
            if (effectiveScope) {
                Object rawEff = effectiveMetadataShell(entity, normalizedType, "getEffectivePropertyDefinitions");
                Object coll = unwrapEffectivePropertyDefinitionsSource(rawEff);
                List<PropertyDefinition> fromColl = propertyDefsFromUnknown(coll);
                if (!fromColl.isEmpty()) {
                    return fromColl;
                }
            }
            InfoTable gp = PropertyDefinitionsService.fetch(entity);
            if (gp != null && gp.getRowCount() > 0) {
                return propertyDefsFromInfoTable(gp);
            }
            Object coll = tryInvokeNoArgs(entity, "getInstancePropertyDefinitions");
            return propertyDefsFromUnknown(coll);
        } catch (DescribeEntitySchemaPlatformException e) {
            throw e;
        } catch (Exception e) {
            if (isTemplateOrShape(normalizedType)) {
                throw new DescribeEntitySchemaPlatformException("DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE",
                        (effectiveScope ? "Effective" : "Local") + " property definitions read failed for "
                                + normalizedType + ": " + e.getMessage(),
                        e);
            }
            LOG.warn("describe_entity_schema listPropertyDefinitions: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<PropertyDefinition> propertyDefsFromInfoTable(InfoTable gp) {
        List<PropertyDefinition> out = new ArrayList<>();
        try {
            List<String> cols = infoTableColumnNames(gp);
            for (int r = 0; r < gp.getRowCount(); r++) {
                Object rowObj = gp.getRow(r);
                if (rowObj == null) {
                    continue;
                }
                PropertyDefinition pd = rowToPropertyDefinition(gp, r, cols);
                if (pd != null) {
                    out.add(pd);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return out;
    }

    private static PropertyDefinition rowToPropertyDefinition(InfoTable it, int rowIndex, List<String> cols) {
        try {
            ValueCollection row = it.getRow(rowIndex);
            if (row == null) {
                return null;
            }
            String name = readCellString(row, cols, "name");
            if (name == null || name.isEmpty()) {
                return null;
            }
            return new PropertyDefinition(name, readCellString(row, cols, "description"),
                    parseBaseType(readCellString(row, cols, "baseType")));
        } catch (Exception e) {
            return null;
        }
    }

    private static com.thingworx.types.BaseTypes parseBaseType(String raw) {
        if (raw == null || raw.isEmpty()) {
            return com.thingworx.types.BaseTypes.STRING;
        }
        try {
            return com.thingworx.types.BaseTypes.valueOf(raw.trim());
        } catch (Exception e) {
            return com.thingworx.types.BaseTypes.STRING;
        }
    }

    private static String readCellString(ValueCollection row, List<String> cols, String preferred) {
        if (row == null) {
            return null;
        }
        try {
            if (cols.contains(preferred)) {
                Object v = row.getValue(preferred);
                return v != null ? String.valueOf(v) : null;
            }
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(preferred)) {
                    Object v = row.getValue(c);
                    return v != null ? String.valueOf(v) : null;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static List<PropertyDefinition> propertyDefsFromUnknown(Object coll) throws Exception {
        if (coll == null) {
            return List.of();
        }
        if (coll instanceof Iterable) {
            List<PropertyDefinition> out = new ArrayList<>();
            for (Object o : (Iterable<?>) coll) {
                if (o instanceof PropertyDefinition) {
                    out.add((PropertyDefinition) o);
                }
            }
            return out;
        }
        try {
            Method values = coll.getClass().getMethod("values");
            Object v = values.invoke(coll);
            if (!(v instanceof Iterable)) {
                return List.of();
            }
            List<PropertyDefinition> out = new ArrayList<>();
            for (Object o : (Iterable<?>) v) {
                if (o instanceof PropertyDefinition) {
                    out.add((PropertyDefinition) o);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<PropertyDefinition> filterProperties(List<PropertyDefinition> all, JsonNode root) {
        String prefix = text(root, "namePrefix");
        String category = text(root, "category");
        String baseType = text(root, "baseType");
        String dataShape = text(root, "dataShape");
        List<PropertyDefinition> matched = new ArrayList<>();
        for (PropertyDefinition pd : all) {
            if (pd == null || pd.getName() == null) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty()
                    && !pd.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (category != null && !category.isEmpty()) {
                String cat = pd.getCategory();
                if (cat == null || !cat.equalsIgnoreCase(category.trim())) {
                    continue;
                }
            }
            if (baseType != null && !baseType.isEmpty()) {
                String bt = pd.getBaseType() != null ? pd.getBaseType().name() : "";
                if (!bt.equalsIgnoreCase(baseType.trim())) {
                    continue;
                }
            }
            if (dataShape != null && !dataShape.isEmpty()) {
                String ds = aspectString(pd.getAspects(), "dataShape");
                if (ds == null || !ds.equalsIgnoreCase(dataShape.trim())) {
                    continue;
                }
            }
            matched.add(pd);
        }
        matched.sort(Comparator.comparing(PropertyDefinition::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return matched;
    }

    static ObjectNode compactPropertyRow(PropertyDefinition pd) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", pd.getName());
        row.put("description", truncateDescription(pd.getDescription()));
        row.put("baseType", pd.getBaseType() != null ? pd.getBaseType().name() : "STRING");
        String cat = pd.getCategory();
        if (cat != null && !cat.isEmpty()) {
            row.put("category", cat);
        }
        String ds = aspectString(pd.getAspects(), "dataShape");
        if (ds != null && !ds.isEmpty()) {
            row.put("dataShape", ds);
        }
        return row;
    }

    private static List<ServiceDefinition> listPublicServiceDefinitions(RootEntity entity, boolean effectiveScope,
            String normalizedType) {
        if (!(entity instanceof IServiceProvider)) {
            return List.of();
        }
        IServiceProvider sp = (IServiceProvider) entity;
        try {
            if (!effectiveScope && isTemplateOrShape(normalizedType)) {
                Object shape = localInstanceShape(entity, normalizedType);
                Object pubColl = invokeRequiredReadNoArgs(shape, "getPublicServiceDefinitions");
                List<ServiceDefinition> fromPub = filterPublicServices(serviceDefsFromUnknown(pubColl));
                if (!fromPub.isEmpty()) {
                    return fromPub;
                }
                Object svcColl = invokeRequiredReadNoArgs(shape, "getServiceDefinitions");
                return filterPublicServices(serviceDefsFromUnknown(svcColl));
            }
            if (effectiveScope) {
                Object rawEff = effectiveMetadataShell(entity, normalizedType, "getEffectiveServiceDefinitions");
                Object coll = unwrapEffectiveServiceDefinitionsSource(rawEff);
                List<ServiceDefinition> defs = serviceDefsFromUnknown(coll);
                if (!defs.isEmpty()) {
                    return filterPublicServices(defs);
                }
            }
            ServiceDefinitionCollection pub = tryPublicCollection(sp);
            if (pub != null && pub.values() != null && !pub.values().isEmpty()) {
                return filterPublicServices(new ArrayList<>(pub.values()));
            }
            ServiceDefinitionCollection all = sp.getInstanceServiceDefinitions();
            if (all == null || all.values() == null) {
                return List.of();
            }
            return filterPublicServices(new ArrayList<>(all.values()));
        } catch (DescribeEntitySchemaPlatformException e) {
            throw e;
        } catch (Exception e) {
            if (isTemplateOrShape(normalizedType)) {
                throw new DescribeEntitySchemaPlatformException("DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE",
                        (effectiveScope ? "Effective" : "Local") + " public service definitions read failed for "
                                + normalizedType + ": " + e.getMessage(),
                        e);
            }
            LOG.warn("describe_entity_schema listPublicServiceDefinitions: {}", e.getMessage());
            return List.of();
        }
    }

    /** Best-effort: hide private services when the platform exposes {@code isPrivate()} on {@link ServiceDefinition}. */
    static List<ServiceDefinition> filterPublicServices(List<ServiceDefinition> in) {
        List<ServiceDefinition> out = new ArrayList<>();
        for (ServiceDefinition sd : in) {
            if (sd == null || sd.getName() == null) {
                continue;
            }
            if (isPrivateService(sd)) {
                continue;
            }
            out.add(sd);
        }
        return out;
    }

    private static boolean isPrivateService(ServiceDefinition sd) {
        try {
            Method m = sd.getClass().getMethod("isPrivate");
            Object o = m.invoke(sd);
            return o instanceof Boolean && (Boolean) o;
        } catch (Exception e) {
            return false;
        }
    }

    private static ServiceDefinitionCollection tryPublicCollection(IServiceProvider sp) {
        try {
            Method m = sp.getClass().getMethod("getInstancePublicServiceDefinitions");
            Object o = m.invoke(sp);
            return o instanceof ServiceDefinitionCollection ? (ServiceDefinitionCollection) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ServiceDefinition> serviceDefsFromUnknown(Object coll) throws Exception {
        if (coll == null) {
            return List.of();
        }
        if (coll instanceof ServiceDefinitionCollection) {
            ServiceDefinitionCollection c = (ServiceDefinitionCollection) coll;
            return c.values() == null ? List.of() : new ArrayList<>(c.values());
        }
        if (coll instanceof Iterable) {
            List<ServiceDefinition> out = new ArrayList<>();
            for (Object o : (Iterable<?>) coll) {
                if (o instanceof ServiceDefinition) {
                    out.add((ServiceDefinition) o);
                }
            }
            return out;
        }
        Method values = coll.getClass().getMethod("values");
        Object v = values.invoke(coll);
        if (!(v instanceof Iterable)) {
            return List.of();
        }
        List<ServiceDefinition> out = new ArrayList<>();
        for (Object o : (Iterable<?>) v) {
            if (o instanceof ServiceDefinition) {
                out.add((ServiceDefinition) o);
            }
        }
        return out;
    }

    static List<ServiceDefinition> filterServices(List<ServiceDefinition> all, JsonNode root) {
        String prefix = text(root, "namePrefix");
        String category = text(root, "category");
        List<ServiceDefinition> matched = new ArrayList<>();
        for (ServiceDefinition sd : all) {
            if (sd == null || sd.getName() == null) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty()
                    && !sd.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (category != null && !category.isEmpty()) {
                String cat = sd.getCategory();
                if (cat == null || !cat.equalsIgnoreCase(category.trim())) {
                    continue;
                }
            }
            matched.add(sd);
        }
        matched.sort(Comparator.comparing(ServiceDefinition::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return matched;
    }

    static ObjectNode compactServiceRow(ServiceDefinition sd) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", sd.getName());
        row.put("description", truncateDescription(sd.getDescription()));
        String cat = sd.getCategory();
        if (cat != null && !cat.isEmpty()) {
            row.put("category", cat);
        }
        FieldDefinition rt = sd.getResultType();
        if (rt != null && rt.getBaseType() != null) {
            row.put("resultBaseType", rt.getBaseType().name());
        }
        return row;
    }

    private static ServiceDefinition findPublicServiceDefinition(RootEntity entity, String serviceName,
            boolean effectiveScope, String normalizedType) throws Exception {
        for (ServiceDefinition sd : listPublicServiceDefinitions(entity, effectiveScope, normalizedType)) {
            if (serviceName.equalsIgnoreCase(sd.getName())) {
                return sd;
            }
        }
        return null;
    }

    private static List<EventDefinition> listEventDefinitions(RootEntity entity, boolean effectiveScope,
            String normalizedType) {
        try {
            if (!effectiveScope && isTemplateOrShape(normalizedType)) {
                Object shape = localInstanceShape(entity, normalizedType);
                Object coll = invokeRequiredReadNoArgs(shape, "getEventDefinitions");
                return eventDefsFromUnknown(coll);
            }
            if (effectiveScope) {
                Object rawEff = effectiveMetadataShell(entity, normalizedType, "getEffectiveEventDefinitions");
                Object coll = unwrapEffectiveEventDefinitionsSource(rawEff);
                List<EventDefinition> defs = eventDefsFromUnknown(coll);
                if (!defs.isEmpty()) {
                    return defs;
                }
            }
            Object raw = tryInvokeNoArgs(entity, "getInstanceEventDefinitions");
            if (!(raw instanceof EventDefinitionCollection)) {
                return List.of();
            }
            EventDefinitionCollection defs = (EventDefinitionCollection) raw;
            if (defs.values() == null) {
                return List.of();
            }
            return new ArrayList<>(defs.values());
        } catch (DescribeEntitySchemaPlatformException e) {
            throw e;
        } catch (Exception e) {
            if (isTemplateOrShape(normalizedType)) {
                throw new DescribeEntitySchemaPlatformException("DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE",
                        (effectiveScope ? "Effective" : "Local") + " event definitions read failed for "
                                + normalizedType + ": " + e.getMessage(),
                        e);
            }
            LOG.warn("describe_entity_schema listEventDefinitions: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<EventDefinition> eventDefsFromUnknown(Object coll) throws Exception {
        if (coll == null) {
            return List.of();
        }
        if (coll instanceof EventDefinitionCollection) {
            EventDefinitionCollection c = (EventDefinitionCollection) coll;
            return c.values() == null ? List.of() : new ArrayList<>(c.values());
        }
        if (coll instanceof Iterable) {
            List<EventDefinition> out = new ArrayList<>();
            for (Object o : (Iterable<?>) coll) {
                if (o instanceof EventDefinition) {
                    out.add((EventDefinition) o);
                }
            }
            return out;
        }
        Method values = coll.getClass().getMethod("values");
        Object v = values.invoke(coll);
        if (!(v instanceof Iterable)) {
            return List.of();
        }
        List<EventDefinition> out = new ArrayList<>();
        for (Object o : (Iterable<?>) v) {
            if (o instanceof EventDefinition) {
                out.add((EventDefinition) o);
            }
        }
        return out;
    }

    static List<EventDefinition> filterEvents(List<EventDefinition> all, JsonNode root) {
        String prefix = text(root, "namePrefix");
        String category = text(root, "category");
        List<EventDefinition> matched = new ArrayList<>();
        for (EventDefinition ed : all) {
            if (ed == null || ed.getName() == null) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty()
                    && !ed.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (category != null && !category.isEmpty()) {
                String cat = ed.getCategory();
                if (cat == null || !cat.equalsIgnoreCase(category.trim())) {
                    continue;
                }
            }
            matched.add(ed);
        }
        matched.sort(Comparator.comparing(EventDefinition::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return matched;
    }

    static ObjectNode compactEventRow(EventDefinition ed) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", ed.getName());
        row.put("description", truncateDescription(ed.getDescription()));
        String cat = ed.getCategory();
        if (cat != null && !cat.isEmpty()) {
            row.put("category", cat);
        }
        return row;
    }

    private static List<FieldDefinition> listDataShapeFields(RootEntity entity, boolean effectiveScope) {
        try {
            Object shapeObj = null;
            if (effectiveScope) {
                shapeObj = tryInvokeNoArgs(entity, "getEffectiveDataShape");
            }
            if (shapeObj == null) {
                shapeObj = tryInvokeNoArgs(entity, "getDataShape");
            }
            if (shapeObj == null) {
                return List.of();
            }
            Object fields = tryInvokeNoArgs(shapeObj, "getFields");
            if (fields == null) {
                return List.of();
            }
            FieldDefinitionCollection fdc;
            if (fields instanceof FieldDefinitionCollection) {
                fdc = (FieldDefinitionCollection) fields;
            } else {
                Method values = fields.getClass().getMethod("values");
                Object v = values.invoke(fields);
                if (!(v instanceof Iterable)) {
                    return List.of();
                }
                List<FieldDefinition> tmp = new ArrayList<>();
                for (Object o : (Iterable<?>) v) {
                    if (o instanceof FieldDefinition) {
                        tmp.add((FieldDefinition) o);
                    }
                }
                tmp.sort(Comparator.comparingInt(DescribeEntitySchemaExecutor::fieldOrdinal));
                return tmp;
            }
            if (fdc.values() == null) {
                return List.of();
            }
            List<FieldDefinition> tmp = new ArrayList<>(fdc.values());
            tmp.sort(Comparator.comparingInt(DescribeEntitySchemaExecutor::fieldOrdinal));
            return tmp;
        } catch (Exception e) {
            LOG.warn("describe_entity_schema listDataShapeFields: {}", e.getMessage());
            return List.of();
        }
    }

    private static int fieldOrdinal(FieldDefinition fd) {
        try {
            return fd.getOrdinal();
        } catch (Exception e) {
            return 0;
        }
    }

    static List<FieldDefinition> filterFields(List<FieldDefinition> all, JsonNode root) {
        String prefix = text(root, "namePrefix");
        String baseType = text(root, "baseType");
        String dataShape = text(root, "dataShape");
        List<FieldDefinition> matched = new ArrayList<>();
        for (FieldDefinition fd : all) {
            if (fd == null || fd.getName() == null) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty()
                    && !fd.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (baseType != null && !baseType.isEmpty()) {
                String bt = fd.getBaseType() != null ? fd.getBaseType().name() : "";
                if (!bt.equalsIgnoreCase(baseType.trim())) {
                    continue;
                }
            }
            if (dataShape != null && !dataShape.isEmpty()) {
                String ds = fieldAspectString(fd, "dataShape");
                if (ds == null || !ds.equalsIgnoreCase(dataShape.trim())) {
                    continue;
                }
            }
            matched.add(fd);
        }
        matched.sort(Comparator.comparingInt(DescribeEntitySchemaExecutor::fieldOrdinal));
        return matched;
    }

    static ObjectNode compactFieldRow(FieldDefinition fd) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", fd.getName());
        row.put("description", truncateDescription(fd.getDescription()));
        row.put("baseType", fd.getBaseType() != null ? fd.getBaseType().name() : "STRING");
        String ds = fieldAspectString(fd, "dataShape");
        if (ds != null && !ds.isEmpty()) {
            row.put("dataShape", ds);
        }
        return row;
    }


    private static List<String> infoTableColumnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
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
                    Iterator<String> keys = row.keySet().iterator();
                    while (keys.hasNext()) {
                        names.add(keys.next());
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    private static Object tryInvokeNoArgs(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Required schema read: propagates reflection failures. Used where a swallowed exception would look like an empty
     * definition. Optional probes continue to use {@link #tryInvokeNoArgs(Object, String)}.
     */
    private static Object invokeRequiredReadNoArgs(Object target, String method) throws Exception {
        if (target == null) {
            return null;
        }
        Method m = target.getClass().getMethod(method);
        try {
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Error) {
                throw (Error) c;
            }
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            throw e;
        }
    }

    private static String invokeStringGetter(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            Object v = m.invoke(o);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncateDescription(String d) {
        if (d == null) {
            return "";
        }
        if (d.length() > DESC_TRUNCATE_LIST) {
            return d.substring(0, DESC_TRUNCATE_LIST) + "...";
        }
        return d;
    }

    private static boolean isRequiredParam(FieldDefinition fd) {
        try {
            if (fd.getAspects() == null) {
                return true;
            }
            Object v = fd.getAspects().getClass().getMethod("get", Object.class).invoke(fd.getAspects(), "isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
        } catch (Exception ignored) {
            // default required
        }
        return true;
    }

    private static String fieldAspectString(FieldDefinition fd, String key) {
        return aspectString(fd.getAspects(), key);
    }

    private static String aspectString(Object aspects, String key) {
        if (aspects == null) {
            return null;
        }
        try {
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
            return v != null ? v.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    private static String errorJson(String code, String message, ObjectNode recoveryHint) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            if (recoveryHint != null) {
                o.set("recoveryHint", recoveryHint);
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
