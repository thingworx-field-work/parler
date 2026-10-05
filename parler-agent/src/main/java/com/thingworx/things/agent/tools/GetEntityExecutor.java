package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.EventDefinitionCollection;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.compaction.EntityMetadataSummaryCodec;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * Built-in {@code get_entity}: full entity metadata for Tier B compaction ({@value EntityMetadataSummaryCodec#FORMAT_ENTITY_METADATA_V1}).
 *
 * @see docs/agent/metadata_discovery.md
 * @see docs/agent/context-compaction.md §16 Slice G
 */
public final class GetEntityExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(GetEntityExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DESC_TRUNCATE = 400;
    private static final Set<String> SUPPORTED_ENTITY_TYPES =
            Set.of("ThingTemplate", "ThingShape", "DataShape");

    private GetEntityExecutor() {}

    public static String execute(ToolCall call) {
        try {
            return doGetEntity(call);
        } catch (Exception e) {
            LOG.warn("get_entity: {}", e.getMessage());
            return errorJson("GET_ENTITY_ERROR", e.getMessage());
        }
    }

    private static PromptContextCacheSnapshot promptSnapshotOrNull() {
        AgentThing at = AgentToolContext.getAgentThing();
        return at != null ? at.getPromptContextSnapshot() : null;
    }

    private static boolean isSupportedGetEntityType(String entityType) {
        if (entityType == null) {
            return false;
        }
        String t = entityType.trim();
        for (String supported : SUPPORTED_ENTITY_TYPES) {
            if (supported.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    private static String unsupportedEntityTypeForGetEntity(String suppliedValue) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY");
            o.put("message",
                    "get_entity does not accept Thing instances (would inflate replay context). For a specific Thing's "
                            + "properties use discover_thing_members (facet=properties) + get_property_values. For its "
                            + "template chain, resolve the Thing then use describe_entity_schema on its ThingTemplate.");
            o.put("parameterName", "entityType");
            o.put("suppliedValue", suppliedValue != null ? suppliedValue : "");
            ArrayNode supported = MAPPER.createArrayNode();
            for (String s : SUPPORTED_ENTITY_TYPES) {
                supported.add(s);
            }
            o.set("supportedEntityTypes", supported);
            ObjectNode rh = MAPPER.createObjectNode();
            rh.put("tool", "discover_thing_members");
            rh.put("argument", "thingName");
            o.set("recoveryHint", rh);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY\","
                    + "\"message\":\"serialization failed\"}";
        }
    }

    private static String doGetEntity(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String et = text(root, "entityType");
        String en = text(root, "entityName");
        if (et == null || et.isEmpty()) {
            return metaWarn("MISSING_ENTITY_TYPE", "entityType is required");
        }
        if (en == null || en.isEmpty()) {
            return metaWarn("MISSING_ENTITY_NAME", "entityName is required");
        }
        if ("Thing".equalsIgnoreCase(et.trim()) || !isSupportedGetEntityType(et)) {
            return unsupportedEntityTypeForGetEntity(et);
        }
        LOG.info("get_entity start: {} / {}", et, en);
        ServiceTargetEntityTypeResolution tr =
                ServiceTargetEntityTypeResolver.resolve(et, promptSnapshotOrNull());
        if (tr.isError()) {
            return metaWarn(tr.getErrorCode(), tr.getErrorMessage());
        }
        RelationshipTypes.ThingworxRelationshipTypes rel = tr.getEffectiveRel();
        RootEntity entity;
        try {
            entity = PlatformAccess.findAsUser(en, rel);
        } catch (Exception e) {
            return metaWarn("ENTITY_NOT_FOUND", e.getMessage());
        }
        if (entity == null) {
            return metaWarn("ENTITY_NOT_FOUND", tr.augmentEntityNotFoundMessage(en, rel));
        }
        ObjectNode out = buildEntityMetadata(entity, tr.getEffectiveEntityTypeName(), en);
        tr.putEntityTypeNormalizedIfPresent(out);
        LOG.info("get_entity done: {} / {} properties={} services={} events={}",
                et, en,
                out.path("properties").size(),
                out.path("services").size(),
                out.path("events").size());
        return MAPPER.writeValueAsString(out);
    }

    static ObjectNode buildEntityMetadata(RootEntity entity, String entityType, String entityName) throws Exception {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("$format", EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_V1);
        out.put("status", "success");
        out.put("entityType", entityType);
        out.put("entityName", entityName);
        String template = invokeStringGetter(entity, "getThingTemplateName");
        if (template != null && !template.isEmpty()) {
            out.put("template", template);
        }
        String description = entity.getDescription();
        if (description != null && !description.isEmpty()) {
            if (description.length() > DESC_TRUNCATE) {
                description = description.substring(0, DESC_TRUNCATE) + "...";
            }
            out.put("description", description);
        }
        ArrayNode tags = readTags(entity);
        if (tags != null && tags.size() > 0) {
            out.set("tags", tags);
        }
        out.set("properties", listPropertyDefinitions(entity));
        out.set("services", listServiceDefinitions(entity));
        out.set("events", listEventDefinitions(entity));
        return out;
    }

    private static ArrayNode listPropertyDefinitions(RootEntity entity) throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        List<Object> props = collectPropertyDefinitions(entity);
        props.sort(Comparator.comparing(o -> invokeStringGetter(o, "getName") != null ? invokeStringGetter(o, "getName") : ""));
        for (Object pd : props) {
            ObjectNode row = propertyDefinitionToJson(pd);
            if (row != null) {
                arr.add(row);
            }
        }
        return arr;
    }

    private static List<Object> collectPropertyDefinitions(RootEntity entity) throws Exception {
        InfoTable gpTable = PropertyDefinitionsService.fetch(entity);
        if (gpTable != null && gpTable.getRowCount() > 0) {
            List<String> cols = infoTableColumnNames(gpTable);
            List<Object> rows = new ArrayList<>();
            for (int r = 0; r < gpTable.getRowCount(); r++) {
                rows.add(infoTableRowToJson(gpTable, r, cols));
            }
            return rows;
        }
        Object coll;
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("getInstancePropertyDefinitions");
            coll = m.invoke(entity);
        } catch (NoSuchMethodException e) {
            return List.of();
        }
        List<Object> props = new ArrayList<>();
        if (coll == null) {
            return props;
        }
        java.lang.reflect.Method values = coll.getClass().getMethod("values");
        Object v = values.invoke(coll);
        if (v instanceof Iterable) {
            for (Object o : (Iterable<?>) v) {
                props.add(o);
            }
        }
        return props;
    }

    private static ObjectNode propertyDefinitionToJson(Object pd) {
        if (pd instanceof PropertyDefinition) {
            return propertyDefinitionToJson((PropertyDefinition) pd);
        }
        if (pd instanceof ObjectNode) {
            return propertyDefinitionFromInfoTableRow((ObjectNode) pd);
        }
        return null;
    }

    private static ObjectNode propertyDefinitionToJson(PropertyDefinition pd) {
        String name = pd.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", name);
        String d = pd.getDescription();
        if (d != null && d.length() > DESC_TRUNCATE) {
            d = d.substring(0, DESC_TRUNCATE) + "...";
        }
        row.put("description", d != null ? d : "");
        row.put("baseType", pd.getBaseType() != null ? pd.getBaseType().name() : "STRING");
        row.put("isLogged", pd.isLogged());
        row.put("isPersistent", pd.isPersistent());
        Object defaultValue = pd.getDefaultValue();
        if (defaultValue != null) {
            row.put("defaultValue", String.valueOf(defaultValue));
        }
        ObjectNode aspects = aspectsToJson(pd.getAspects());
        if (aspects != null && aspects.size() > 0) {
            row.set("aspects", aspects);
        }
        return row;
    }

    private static ObjectNode propertyDefinitionFromInfoTableRow(ObjectNode row) {
        String name = row.path("name").asText(null);
        if (name == null || name.isEmpty()) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("name", name);
        if (row.has("description")) {
            out.set("description", row.get("description").deepCopy());
        } else {
            out.put("description", "");
        }
        String bt = row.path("baseType").asText(null);
        if (bt == null || bt.isEmpty()) {
            bt = row.path("type").asText("STRING");
        }
        out.put("baseType", bt);
        if (row.has("aspects")) {
            out.set("aspects", row.get("aspects").deepCopy());
        }
        return out;
    }

    private static ArrayNode listServiceDefinitions(RootEntity entity) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (!(entity instanceof IServiceProvider)) {
            return arr;
        }
        ServiceDefinitionCollection defs = ((IServiceProvider) entity).getInstanceServiceDefinitions();
        if (defs == null || defs.values() == null) {
            return arr;
        }
        List<ServiceDefinition> list = new ArrayList<>(defs.values());
        list.sort(Comparator.comparing(sd -> sd.getName() != null ? sd.getName() : ""));
        for (ServiceDefinition sd : list) {
            ObjectNode row = serviceDefinitionToJson(sd);
            if (row != null) {
                arr.add(row);
            }
        }
        return arr;
    }

    private static ObjectNode serviceDefinitionToJson(ServiceDefinition sd) {
        if (sd.getName() == null) {
            return null;
        }
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", sd.getName());
        String d = sd.getDescription();
        if (d != null && d.length() > DESC_TRUNCATE) {
            d = d.substring(0, DESC_TRUNCATE) + "...";
        }
        row.put("description", d != null ? d : "");
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
        row.set("parameterDefinitions", params);
        return row;
    }

    private static ArrayNode listEventDefinitions(RootEntity entity) {
        ArrayNode arr = MAPPER.createArrayNode();
        EventDefinitionCollection defs;
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("getInstanceEventDefinitions");
            Object raw = m.invoke(entity);
            if (!(raw instanceof EventDefinitionCollection)) {
                return arr;
            }
            defs = (EventDefinitionCollection) raw;
        } catch (Exception e) {
            return arr;
        }
        if (defs == null || defs.values() == null) {
            return arr;
        }
        List<EventDefinition> list = new ArrayList<>(defs.values());
        list.sort(Comparator.comparing(ed -> ed.getName() != null ? ed.getName() : ""));
        for (EventDefinition ed : list) {
            if (ed.getName() == null) {
                continue;
            }
            ObjectNode row = MAPPER.createObjectNode();
            row.put("name", ed.getName());
            String d = ed.getDescription();
            if (d != null && d.length() > DESC_TRUNCATE) {
                d = d.substring(0, DESC_TRUNCATE) + "...";
            }
            row.put("description", d != null ? d : "");
            String cat = ed.getCategory();
            if (cat != null && !cat.isEmpty()) {
                row.put("category", cat);
            }
            arr.add(row);
        }
        return arr;
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

    private static ObjectNode infoTableRowToJson(InfoTable it, int rowIndex, List<String> cols) {
        ObjectNode o = MAPPER.createObjectNode();
        ValueCollection row = it.getRow(rowIndex);
        if (row == null) {
            return o;
        }
        for (String col : cols) {
            Object val = row.getValue(col);
            putJsonCell(o, col, val);
        }
        return o;
    }

    private static void putJsonCell(ObjectNode o, String col, Object val) {
        if (val == null) {
            o.putNull(col);
            return;
        }
        try {
            if (val instanceof IPrimitiveType) {
                Object v = ((IPrimitiveType) val).getValue();
                if (v == null) {
                    o.putNull(col);
                } else if (v instanceof Boolean) {
                    o.put(col, (Boolean) v);
                } else if (v instanceof Number) {
                    o.put(col, ((Number) v).doubleValue());
                } else {
                    o.put(col, v.toString());
                }
            } else {
                o.put(col, val.toString());
            }
        } catch (Exception e) {
            o.put(col, String.valueOf(val));
        }
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
        try {
            Object aspects = fd.getAspects();
            if (aspects == null) {
                return null;
            }
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
            return v != null ? v.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ArrayNode readTags(RootEntity entity) {
        Object tags = invokeObject(entity, "getTags");
        if (tags == null) {
            return null;
        }
        try {
            java.lang.reflect.Method stream = tags.getClass().getMethod("stream");
            Object s = stream.invoke(tags);
            if (!(s instanceof java.util.stream.Stream)) {
                return null;
            }
            ArrayNode arr = MAPPER.createArrayNode();
            try (java.util.stream.Stream<?> st = (java.util.stream.Stream<?>) s) {
                st.forEach(tag -> {
                    String label = tag != null ? tag.toString() : "";
                    if (!label.isEmpty()) {
                        arr.add(label);
                    }
                });
            }
            return arr.size() > 0 ? arr : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ObjectNode aspectsToJson(Object aspects) {
        if (aspects == null) {
            return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        for (String key : new String[] {"dataShape", "defaultValue", "category", "isPrimaryKey"}) {
            String v = aspectString(aspects, key);
            if (v != null && !v.isEmpty()) {
                out.put(key, v);
            }
        }
        return out.size() > 0 ? out : null;
    }

    private static String aspectString(Object aspects, String key) {
        try {
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
            return v != null ? v.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String invokeStringGetter(Object o, String method) {
        try {
            java.lang.reflect.Method m = o.getClass().getMethod(method);
            Object v = m.invoke(o);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invokeObject(Object o, String method) {
        try {
            java.lang.reflect.Method m = o.getClass().getMethod(method);
            return m.invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String metaWarn(String code, String message) {
        LOG.warn("get_entity [{}]: {}", code, message);
        return errorJson(code, message);
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
