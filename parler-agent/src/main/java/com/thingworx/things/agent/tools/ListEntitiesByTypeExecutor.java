package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.TagCollection;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.types.primitives.TagCollectionPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Lists model entities by collection type via Resource {@value #ENTITY_SERVICES_NAME} services
 * {@value #SERVICE_GET_LIST} / {@value #SERVICE_GET_LIST_REGEX}.
 *
 * <p><b>Does not support {@code type=Thing}</b> — use {@code query_entities} (template/shape) or
 * {@code spotlight_search}; scanning the Thing collection is unsafe at scale.</p>
 */
public final class ListEntitiesByTypeExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ListEntitiesByTypeExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ENTITY_SERVICES_NAME = "EntityServices";
    static final String SERVICE_GET_LIST = "GetEntityList";
    static final String SERVICE_GET_LIST_REGEX = "GetEntityListByRegEx";

    private static final int DEFAULT_MAX_ITEMS = 50;
    private static final int MAX_MAX_ITEMS = 200;

    private ListEntitiesByTypeExecutor() {}

    public static String execute(ToolCall call) {
        try {
            return doList(call);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("list_entities_by_type: {}", e.getMessage(), e);
            return errorJson("LIST_ENTITIES_BY_TYPE_ERROR", e.getMessage());
        }
    }

    private static String doList(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String collectionType = text(root, "entityCollectionType");
        if (collectionType == null || collectionType.isBlank()) {
            return errorJson("MISSING_ENTITY_COLLECTION_TYPE",
                    "entityCollectionType is required (e.g. ThingTemplate, ThingShape, Mashup, DataShape).");
        }
        collectionType = collectionType.trim();
        if ("Thing".equalsIgnoreCase(collectionType)) {
            return errorJson("THING_NOT_SUPPORTED_HERE",
                    "Listing Things by collection type is not supported here (can scan millions of entities). "
                            + "Use query_entities with thingTemplate or thingShape, or spotlight_search.");
        }

        boolean useRegEx = root.has("useRegEx") && root.get("useRegEx").asBoolean(false);
        String serviceName = useRegEx ? SERVICE_GET_LIST_REGEX : SERVICE_GET_LIST;
        String nameMask = text(root, "nameMask");
        if (nameMask != null && nameMask.isBlank()) {
            nameMask = null;
        }
        int maxItems = root.has("maxItems") ? root.get("maxItems").asInt(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        maxItems = Math.min(Math.max(1, maxItems), MAX_MAX_ITEMS);
        double maxItemsD = maxItems;

        RootEntity entity = PlatformAccess.findAsUser(ENTITY_SERVICES_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.Resource);
        if (entity == null) {
            return errorJson("ENTITY_SERVICES_NOT_FOUND",
                    "Resource \"" + ENTITY_SERVICES_NAME + "\" not found.");
        }
        if (!(entity instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", "EntityServices does not expose services.");
        }
        IServiceProvider provider = (IServiceProvider) entity;

        ServiceDefinition sd;
        try {
            sd = provider.getInstanceServiceDefinition(serviceName);
        } catch (Exception e) {
            return errorJson("SERVICE_LOOKUP_FAILED", e.getMessage());
        }
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND", serviceName + " not on " + ENTITY_SERVICES_NAME + ".");
        }

        JsonNode tagsNode = root.get("tags");
        ValueCollection params = buildGetEntityListParams(sd, collectionType, nameMask, maxItemsD, tagsNode);
        LOG.info("list_entities_by_type {} nameMask={} maxItems={} service={}",
                collectionType, nameMask != null ? "(set)" : "(none)", maxItems, serviceName);

        InfoTable outer;
        try {
            outer = provider.processAPIServiceRequest(serviceName, params);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            String repairPayload = invalidEntityServicesCollectionRepairOrNull(collectionType, msg);
            if (repairPayload != null) {
                return repairPayload;
            }
            LOG.warn("list_entities_by_type SERVICE_FAILED {}: {}", serviceName, msg);
            return errorJson("SERVICE_EXECUTION_FAILED", msg);
        }

        InfoTable data = unwrapResultInfotable(outer);
        int returned = data != null ? data.getRowCount() : 0;
        List<String> cols = columnNames(data);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("service", serviceName);
        out.put("entityCollectionType", collectionType);
        out.put("useRegEx", useRegEx);
        out.put("returnedRows", returned);
        out.put("maxItems", maxItems);
        out.put("note", "No totalCount from GetEntityList*; result is capped by maxItems. "
                + "Refine nameMask or call again if needed.");

        if (data == null || returned == 0) {
            out.put("resultKind", "ENTITY_LIST_EMPTY");
            out.putArray("rows");
            return MAPPER.writeValueAsString(out);
        }

        ToolResultEgressGateway.TabularEvidencePlan plan = ToolResultEgressGateway.planTabularEvidence(
                returned, "ENTITY_LIST_INLINE", "ENTITY_LIST_LARGE", "rows", "sampleRows");
        out.set("columns", listEntitiesSlimColumnsMetadata());
        String cacheId = null;
        if (plan.large()) {
            cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(data);
        }
        ArrayNode evidenceRows = MAPPER.createArrayNode();
        for (int i = 0; i < plan.rowsToEmit(); i++) {
            evidenceRows.add(slimEntityRow(data, i, cols));
        }
        ToolResultEgressGateway.applyTabularEvidence(
                out, plan, evidenceRows, cacheId, InvokeServiceExecutor.FETCH_CACHED_LARGE_TABLE_HINT);

        LOG.info("list_entities_by_type ok collectionType={} returnedRows={}", collectionType, returned);
        return MAPPER.writeValueAsString(out);
    }

    /** When GetEntityList* fails because {@code type="Stream"}-style keys are not collection types. */
    private static String invalidEntityServicesCollectionRepairOrNull(String collectionType, String failureMessage) {
        if (!failureMessageIndicatesInvalidEntityCollectionType(failureMessage)) {
            return null;
        }
        try {
            return GenericThingIncomingDependencyResolver.tryRepairJsonListCollectionType(MAPPER, collectionType);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean failureMessageIndicatesInvalidEntityCollectionType(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("invalid") && m.contains("entity") && m.contains("type");
    }

    private static ValueCollection buildGetEntityListParams(ServiceDefinition sd, String type, String nameMask,
            double maxItems, JsonNode tagsNode) throws Exception {
        ValueCollection vc = new ValueCollection();
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return vc;
        }

        boolean typeSet = false;
        boolean maskSet = false;
        boolean maxSet = false;
        boolean tagsSet = false;

        for (FieldDefinition fd : defs.values()) {
            String n = fd.getName();
            if (n == null) {
                continue;
            }
            String nl = n.toLowerCase(Locale.ROOT);
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;

            if (nl.equals("type") && (bt == BaseTypes.STRING || bt == BaseTypes.TEXT)) {
                vc.put(n, new StringPrimitive(type));
                typeSet = true;
                continue;
            }
            if (nl.contains("namemask") && (bt == BaseTypes.STRING || bt == BaseTypes.TEXT)) {
                vc.put(n, new StringPrimitive(nameMask != null ? nameMask : ""));
                maskSet = true;
                continue;
            }
            if (nl.contains("max") && nl.contains("item") && bt == BaseTypes.NUMBER) {
                vc.put(n, new NumberPrimitive(maxItems));
                maxSet = true;
                continue;
            }
            if (bt == BaseTypes.TAGS) {
                TagCollection tc = TagJsonCodec.parseTagCollection(tagsNode);
                vc.put(n, new TagCollectionPrimitive(tc));
                tagsSet = true;
                continue;
            }
        }

        if (!typeSet) {
            tryPutStringParam(vc, defs, "type", type);
        }
        if (!maskSet) {
            tryPutStringParam(vc, defs, "nameMask", nameMask != null ? nameMask : "");
        }
        if (!maxSet) {
            tryPutNumberParam(vc, defs, "maxItems", maxItems);
        }
        if (!tagsSet) {
            tryPutEmptyTags(vc, defs, tagsNode);
        }

        return vc;
    }

    private static void tryPutStringParam(ValueCollection vc, FieldDefinitionCollection defs, String want, String value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName())
                    && (fd.getBaseType() == BaseTypes.STRING || fd.getBaseType() == BaseTypes.TEXT)) {
                try {
                    vc.put(fd.getName(), new StringPrimitive(value != null ? value : ""));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutNumberParam(ValueCollection vc, FieldDefinitionCollection defs, String want, double value) {
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

    private static void tryPutEmptyTags(ValueCollection vc, FieldDefinitionCollection defs, JsonNode tagsNode) {
        for (FieldDefinition fd : defs.values()) {
            if (fd.getBaseType() == BaseTypes.TAGS) {
                try {
                    TagCollection tc = TagJsonCodec.parseTagCollection(tagsNode);
                    vc.put(fd.getName(), new TagCollectionPrimitive(tc));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static InfoTable unwrapResultInfotable(InfoTable outer) {
        return ServiceResultInfotable.extractInfotableResult(outer);
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

    private static ObjectNode slimEntityRow(InfoTable it, int rowIndex, List<String> cols) {
        ObjectNode o = MAPPER.createObjectNode();
        ValueCollection row = it.getRow(rowIndex);
        if (row == null) {
            return o;
        }
        String name = firstString(row, cols, "name", "entityName", "EntityName");
        if (name != null) {
            o.put("name", name);
        }
        String desc = firstString(row, cols, "description", "Description");
        if (desc != null && !desc.isEmpty()) {
            String d = desc.length() > 400 ? desc.substring(0, 400) + "..." : desc;
            o.put("description", d);
        }
        String et = firstString(row, cols, "entityType", "type", "modelType");
        if (et != null) {
            o.put("entityType", et);
        }
        String tags = firstString(row, cols, "tags", "Tags");
        if (tags != null) {
            o.put("tags", tags.length() > 200 ? tags.substring(0, 200) + "..." : tags);
        }
        return o;
    }

    /** Column metadata for slim {@code ENTITY_LIST_*} rows (see {@link #slimEntityRow}). */
    static ArrayNode listEntitiesSlimColumnsMetadata() {
        ArrayNode a = MAPPER.createArrayNode();
        listCol(a, "name", "THINGNAME");
        listCol(a, "description", "STRING");
        listCol(a, "entityType", "STRING");
        listCol(a, "tags", "TAGS");
        return a;
    }

    private static void listCol(ArrayNode cols, String name, String baseType) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("name", name);
        c.put("baseType", baseType);
        cols.add(c);
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
