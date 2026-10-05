package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * Phase 0.5: ThingTemplates["GenericThing"].{@code GetIncomingDependencies(maxItems=2000)} for exact EntityDescriptor-name
 * keys ({@code docs/agent/key-resolution.md}, Phase 0.5).
 */
public final class GenericThingIncomingDependencyResolver {

    private static final Logger LOG =
            LogUtilities.getInstance().getApplicationLogger(GenericThingIncomingDependencyResolver.class);

    /**
     * When {@link com.thingworx.things.agent.AgentThing} runs {@code list_entities_by_type} and the prompt-context
     * snapshot lists GenericThing-derived ThingTemplate names, repair uses these rows instead of live
     * {@link #fetchDependencyRows()} (system-prompt-cache v1).
     */
    private static final ThreadLocal<List<String>> REPAIR_CACHED_THING_TEMPLATE_NAMES = new ThreadLocal<>();

    /** Platform ThingTemplate defining base Thing behavior. */
    public static final String GENERIC_THING_TEMPLATE_NAME = "GenericThing";

    static final String GET_INCOMING_DEPENDENCIES_SERVICE = "GetIncomingDependencies";
    /** v1 explicit cap — see {@code docs/agent/key-resolution.md}, Phase 0.5. */
    public static final double MAX_ITEMS_CAP = 2000.0;

    private GenericThingIncomingDependencyResolver() {}

    /**
     * ThingTemplate {@code name} values from live {@link #fetchDependencyRows()}, sorted uniquely for prompt cache.
     */
    public static List<String> fetchSortedThingTemplateNames() throws Exception {
        List<IncomingDependencyMatcher.Row> rows = fetchDependencyRows();
        if (rows == null) {
            return Collections.emptyList();
        }
        return IncomingDependencyMatcher.sortedUniqueThingTemplateNames(rows);
    }

    /**
     * @return normalized dependency rows from live platform, or {@code null} when GenericThing lookup / service fails
     */
    public static List<IncomingDependencyMatcher.Row> fetchDependencyRows() throws Exception {
        RootEntity genericThingRoot = PlatformAccess.findAsUser(GENERIC_THING_TEMPLATE_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate);
        if (genericThingRoot == null || !(genericThingRoot instanceof IServiceProvider)) {
            return null;
        }
        IServiceProvider provider = (IServiceProvider) genericThingRoot;
        ValueCollection params = buildMaxItemsParams(provider);
        InfoTable outer = provider.processAPIServiceRequest(GET_INCOMING_DEPENDENCIES_SERVICE, params);
        InfoTable data = ServiceResultInfotable.extractInfotableResult(outer);
        if (data == null) {
            return Collections.emptyList();
        }
        return rowsFromDependencyTable(data);
    }

    static List<IncomingDependencyMatcher.Row> fetchDependencyRowsOrEmpty() {
        try {
            List<IncomingDependencyMatcher.Row> r = fetchDependencyRows();
            return r != null ? r : Collections.emptyList();
        } catch (Exception e) {
            LOG.debug("GenericThing GetIncomingDependencies failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** @see #REPAIR_CACHED_THING_TEMPLATE_NAMES */
    public static void bindRepairCachedThingTemplateNames(List<String> sortedTemplateNames) {
        REPAIR_CACHED_THING_TEMPLATE_NAMES.set(sortedTemplateNames);
    }

    /** @see #REPAIR_CACHED_THING_TEMPLATE_NAMES */
    public static void unbindRepairCachedThingTemplateNames() {
        REPAIR_CACHED_THING_TEMPLATE_NAMES.remove();
    }

    /**
     * {@code null} = no {@link com.thingworx.things.agent.AgentThing} binding (use live rows). Non-null (including empty)
     * = authoritative cached ThingTemplate names from the snapshot — matches {@link com.thingworx.things.agent.tools.ModelKeyResolver}
     * semantics when the snapshot exists but the list is empty.
     */
    static List<IncomingDependencyMatcher.Row> dependencyRowsForListEntitiesRepair() {
        List<String> cached = REPAIR_CACHED_THING_TEMPLATE_NAMES.get();
        if (cached != null) {
            return IncomingDependencyMatcher.rowsFromCachedThingTemplateNames(cached);
        }
        return fetchDependencyRowsOrEmpty();
    }

    /** When {@link ListEntitiesByTypeExecutor} receives invalid EntityServices collection {@code type}. */
    public static String tryRepairJsonListCollectionType(ObjectMapper mapper, String collectionTypeTrimmed)
            throws Exception {
        List<IncomingDependencyMatcher.Row> rows = dependencyRowsForListEntitiesRepair();
        IncomingDependencyMatcher.MatchOutcome mo =
                IncomingDependencyMatcher.matchEntityCollectionMisuse(rows, collectionTypeTrimmed);
        if (mo.kind != IncomingDependencyMatcher.MatchOutcome.Kind.PARENT_HIT) {
            return null;
        }
        RelationshipTypes.ThingworxRelationshipTypes rel = mo.resolvedRel;
        if (rel != RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                && rel != RelationshipTypes.ThingworxRelationshipTypes.ThingShape) {
            return null;
        }
        boolean cacheBackedNames = REPAIR_CACHED_THING_TEMPLATE_NAMES.get() != null;
        if (cacheBackedNames && rel != RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate) {
            return null;
        }
        String typeStr = rel == RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate ? "ThingTemplate" : "ThingShape";
        ObjectNode resolvedAs = mapper.createObjectNode();
        resolvedAs.put("type", typeStr);
        resolvedAs.put("name", mo.resolvedName != null ? mo.resolvedName : "");
        if (mo.description != null && !mo.description.isEmpty()) {
            resolvedAs.put("description", mo.description);
        }

        ObjectNode repairArgs = mapper.createObjectNode();
        repairArgs.put("entityType", "Thing");
        if (rel == RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate) {
            repairArgs.put("thingTemplate", mo.resolvedName);
        } else {
            repairArgs.put("thingShape", mo.resolvedName);
        }

        ObjectNode repair = mapper.createObjectNode();
        repair.put("tool", "query_entities");
        repair.set("arguments", repairArgs);

        ObjectNode root = mapper.createObjectNode();
        root.put("status", "error");
        root.put("code", "ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY");
        root.put("message",
                "\"" + collectionTypeTrimmed + "\" is not a ThingWorx entity collection type; it resolves to " + typeStr
                        + " \"" + mo.resolvedName + "\". Use query_entities with " + (rel == RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                                ? "thingTemplate" : "thingShape") + "=\"" + mo.resolvedName
                        + "\" to count or list implementing Things.");
        root.set("resolvedAs", resolvedAs);
        root.set("repair", repair);
        return mapper.writeValueAsString(root);
    }

    static List<IncomingDependencyMatcher.Row> rowsFromDependencyTable(InfoTable data) {
        List<String> cols = dependencyColumnNames(data);
        List<IncomingDependencyMatcher.Row> out = new ArrayList<>();
        for (int i = 0; i < data.getRowCount(); i++) {
            com.thingworx.types.collections.ValueCollection row = data.getRow(i);
            if (row == null) {
                continue;
            }
            String name = dependencyFirstString(row, cols, "name", "entityName");
            String type = dependencyFirstString(row, cols, "type", "entityType");
            String descr = dependencyFirstString(row, cols, "description");
            if (name != null && !name.isEmpty() && type != null && !type.isEmpty()) {
                out.add(new IncomingDependencyMatcher.Row(name, type, descr));
            }
        }
        return out;
    }

    private static ValueCollection buildMaxItemsParams(IServiceProvider genericThingThingTemplateRoot) throws Exception {
        ValueCollection vc = new ValueCollection();
        ServiceDefinition sd =
                genericThingThingTemplateRoot.getInstanceServiceDefinition(GET_INCOMING_DEPENDENCIES_SERVICE);
        if (sd == null || sd.getParameters() == null) {
            vc.put("maxItems", new NumberPrimitive(MAX_ITEMS_CAP));
            return vc;
        }
        FieldDefinitionCollection defs = sd.getParameters();
        for (FieldDefinition fd : defs.values()) {
            String n = fd.getName();
            if (n == null) {
                continue;
            }
            String nl = n.toLowerCase(java.util.Locale.ROOT);
            if (nl.contains("max") && fd.getBaseType() == BaseTypes.NUMBER) {
                vc.put(n, new NumberPrimitive(MAX_ITEMS_CAP));
                return vc;
            }
        }
        tryPutDoubleParamAny(vc, defs, MAX_ITEMS_CAP);
        return vc;
    }

    private static void tryPutDoubleParamAny(ValueCollection vc, FieldDefinitionCollection defs, double v) throws Exception {
        for (FieldDefinition fd : defs.values()) {
            if (fd.getBaseType() == BaseTypes.NUMBER) {
                vc.put(fd.getName(), new NumberPrimitive(v));
                return;
            }
        }
    }

    private static List<String> dependencyColumnNames(InfoTable it) {
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
            com.thingworx.types.collections.ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    names.addAll(row.keySet());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    private static String dependencyFirstString(com.thingworx.types.collections.ValueCollection row, List<String> cols,
            String... preferredNames) {
        for (String pref : preferredNames) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(pref)) {
                    Object cell = row.getValue(c);
                    String s = primitiveToString(cell);
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
                return null;
            }
        }
        return v.toString();
    }
}
