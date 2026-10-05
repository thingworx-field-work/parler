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
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Fuzzy entity search via platform Resource {@value #SEARCH_FUNCTIONS_NAME} service
 * {@value #SPOTLIGHT_SERVICE} — same entry point as REST
 * {@code /Thingworx/Resources/SearchFunctions/Services/SpotlightSearchV2}, but invoked in-process
 * (no HTTP).
 *
 * <p>The concrete Resource implementation class is supplied by the ThingWorx platform JAR (package
 * typically under {@code com.thingworx.resources.*}); we resolve it by name as {@link RootEntity} and
 * call {@link IServiceProvider#processAPIServiceRequest(String, ValueCollection)}.</p>
 */
public final class SpotlightSearchExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(SpotlightSearchExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SEARCH_FUNCTIONS_NAME = "SearchFunctions";
    private static final String SPOTLIGHT_SERVICE = "SpotlightSearchV2";
    private static final int DEFAULT_MAX_ITEMS = 30;
    private static final int MAX_MAX_ITEMS = 100;

    private SpotlightSearchExecutor() {}

    public static String executeSpotlightSearch(ToolCall call) {
        try {
            return doSpotlightSearch(call);
        } catch (Exception e) {
            LOG.warn("spotlight_search: {}", e.getMessage());
            return errorJson("SPOTLIGHT_SEARCH_ERROR", e.getMessage());
        }
    }

    private static String doSpotlightSearch(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String query = text(root, "query");
        if (query == null || query.isBlank()) {
            return errorJson("MISSING_QUERY", "query is required (search text)");
        }
        int maxItems = root.has("maxItems") ? root.get("maxItems").asInt(DEFAULT_MAX_ITEMS) : DEFAULT_MAX_ITEMS;
        maxItems = Math.min(Math.max(1, maxItems), MAX_MAX_ITEMS);

        RootEntity entity = PlatformAccess.findAsUser(SEARCH_FUNCTIONS_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.Resource);
        if (entity == null) {
            return errorJson("SEARCH_FUNCTIONS_NOT_FOUND",
                    "Resource \"" + SEARCH_FUNCTIONS_NAME + "\" not found. It is required for Spotlight search.");
        }
        if (!(entity instanceof IServiceProvider)) {
            return errorJson("NOT_SERVICE_PROVIDER", "SearchFunctions does not expose services.");
        }
        IServiceProvider provider = (IServiceProvider) entity;
        ServiceDefinition sd;
        try {
            sd = provider.getInstanceServiceDefinition(SPOTLIGHT_SERVICE);
        } catch (Exception e) {
            return errorJson("SERVICE_LOOKUP_FAILED", e.getMessage());
        }
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND", "Service " + SPOTLIGHT_SERVICE + " not on SearchFunctions.");
        }

        ValueCollection params = buildSpotlightParams(sd, query.trim(), maxItems, root.get("entityTypes"));
        InfoTable outer = provider.processAPIServiceRequest(SPOTLIGHT_SERVICE, params);
        InfoTable hits = unwrapSpotlightHits(outer, sd);
        if (hits == null) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", "success");
            out.put("service", SPOTLIGHT_SERVICE);
            out.put("rowCount", 0);
            out.putArray("hits");
            return MAPPER.writeValueAsString(out);
        }

        List<String> cols = columnNames(hits);
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < hits.getRowCount(); i++) {
            arr.add(slimSpotlightRow(hits, i, cols));
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("service", SPOTLIGHT_SERVICE);
        out.put("rowCount", arr.size());
        out.set("hits", arr);
        out.put("note",
                "Each hit includes name, entityType, and optional description / thingTemplate when present in platform result.");
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Map tool inputs onto whatever parameter names the platform declares for SpotlightSearchV2.
     */
    private static ValueCollection buildSpotlightParams(ServiceDefinition sd, String query, int maxItems,
            JsonNode entityTypesNode) throws Exception {
        ValueCollection vc = new ValueCollection();
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            vc.put("searchExpression", new StringPrimitive(query));
            vc.put("maxSearchItems", new IntegerPrimitive(maxItems));
            return vc;
        }
        boolean searchSet = false;
        boolean maxSet = false;
        for (FieldDefinition fd : defs.values()) {
            String n = fd.getName();
            if (n == null) {
                continue;
            }
            String nl = n.toLowerCase(Locale.ROOT);
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;

            if ((bt == BaseTypes.STRING || bt == BaseTypes.TEXT)
                    && (nl.contains("search") || nl.equals("searchexpression") || nl.equals("searchterm"))) {
                vc.put(n, new StringPrimitive(query));
                searchSet = true;
                continue;
            }
            if ((bt == BaseTypes.INTEGER || bt == BaseTypes.LONG)
                    && (nl.contains("max") || nl.equals("maxitems"))) {
                vc.put(n, new IntegerPrimitive(maxItems));
                maxSet = true;
                continue;
            }
            if (nl.contains("parent") && nl.contains("context") && (bt == BaseTypes.STRING || bt == BaseTypes.TEXT)) {
                vc.put(n, new StringPrimitive(""));
                continue;
            }
            if (entityTypesNode != null && entityTypesNode.isArray() && bt == BaseTypes.INFOTABLE
                    && nl.contains("type")) {
                // Future: build INFOTABLE of model tags from entityTypesNode
                continue;
            }
        }
        if (!searchSet) {
            try {
                vc.put("searchExpression", new StringPrimitive(query));
            } catch (Exception e) {
                LOG.warn("spotlight_search: could not set searchExpression fallback: {}", e.getMessage());
            }
        }
        if (!maxSet) {
            try {
                vc.put("maxSearchItems", new IntegerPrimitive(maxItems));
            } catch (Exception e) {
                LOG.warn("spotlight_search: could not set maxSearchItems fallback: {}", e.getMessage());
            }
        }
        return vc;
    }

    /**
     * SpotlightSearchV2 returns INFOTABLE; {@link ServiceResultInfotable} handles both wrapped and direct tables
     * returned by the platform's service reflection processor.
     */
    private static InfoTable unwrapSpotlightHits(InfoTable outer, ServiceDefinition sd) throws Exception {
        if (outer == null) {
            return null;
        }
        FieldDefinition resultFd = sd.getResultType();
        BaseTypes resultBase = resultFd != null && resultFd.getBaseType() != null
                ? resultFd.getBaseType() : BaseTypes.NOTHING;
        if (resultBase != BaseTypes.INFOTABLE) {
            return null;
        }
        InfoTable hits = ServiceResultInfotable.extractInfotableResult(outer);
        return hits != null && hits.getRowCount() > 0 ? hits : null;
    }

    private static List<String> columnNames(InfoTable it) {
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
        return names;
    }

    /**
     * Minimal fields for LLM: name, entityType, optional description, optional thingTemplate (when column exists).
     */
    private static ObjectNode slimSpotlightRow(InfoTable it, int rowIndex, List<String> cols) {
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
        String et = firstString(row, cols, "entityType", "type", "modelType", "modelTag", "ModelTags");
        if (et != null) {
            o.put("entityType", et);
        }
        String tt = firstString(row, cols, "thingTemplate", "thingTemplateName", "ThingTemplate", "implementedTemplate");
        if (tt != null) {
            o.put("thingTemplate", tt);
        }
        return o;
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
