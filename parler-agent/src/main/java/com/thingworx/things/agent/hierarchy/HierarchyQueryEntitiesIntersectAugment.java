package com.thingworx.things.agent.hierarchy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.EntityHierarchyIntersectHelper;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;

/**
 * When {@code query_entities*} omit a non-empty {@code intersectThingNames} list, expands non-blank
 * {@code hierarchyNodeName} into {@code intersectThingNames} via {@link HierarchyNetworkServiceFacade#getAssetList}.
 *
 * <p><b>Host Context v2:</b> Mashup {@code hostContext} is rendered prompt only — no server-side inject from
 * uplink JSON. Scope from the page must appear in the rendered template and be passed explicitly by the model
 * as {@code hierarchyNodeName} and/or {@code intersectThingNames}.</p>
 *
 * <p><b>SoT:</b> {@code docs/architecture/hierarchy-network-services.md} §6; {@code CONTRACTS/API_CONTRACT.md}.</p>
 */
public final class HierarchyQueryEntitiesIntersectAugment {

    /**
     * Test seam: when non-null, replaces {@link HierarchyNetworkServiceFacade#getAssetList} (agent may be null).
     */
    static volatile BiFunction<AgentThing, String, InfoTable> assetListInvokerOverrideForTests;

    static void setAssetListInvokerOverrideForTests(BiFunction<AgentThing, String, InfoTable> override) {
        assetListInvokerOverrideForTests = override;
    }

    private HierarchyQueryEntitiesIntersectAugment() {
    }

    /**
     * @return error JSON for the tool executor to return immediately, or {@code null} to continue
     */
    public static String maybeInjectIntersectFromHierarchyScope(ObjectNode root) {
        if (root == null) {
            return null;
        }
        if (hasNonEmptyIntersectThingNamesArray(root)) {
            return null;
        }
        if (root.has("hierarchyNodeId") && !root.get("hierarchyNodeId").isNull()) {
            String id = root.get("hierarchyNodeId").asText("").trim();
            if (!id.isEmpty()) {
                return injectIntersectFromNetworkId(root, id);
            }
        }
        return maybeInjectIntersectFromHierarchyNodeName(root);
    }

    /**
     * @return error JSON for the tool executor to return immediately, or {@code null} to continue
     */
    public static String maybeInjectIntersectFromHierarchyNodeName(ObjectNode root) {
        if (root == null) {
            return null;
        }
        if (hasNonEmptyIntersectThingNamesArray(root)) {
            return null;
        }
        if (!root.has("hierarchyNodeName") || root.get("hierarchyNodeName").isNull()) {
            return null;
        }
        String fragment = root.get("hierarchyNodeName").asText("").trim();
        if (fragment.isEmpty()) {
            return null;
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return errorJson("HIERARCHY_RESOLVE_FAILED",
                    "Agent context unavailable; cannot resolve hierarchyNodeName=\"" + fragment
                            + "\". Scoped empty — do not fall back to an unscoped global listing.");
        }
        IdOrError scope = resolveNetworkIdStrict(agent, fragment);
        if (scope.isError()) {
            return scope.errorJson;
        }
        final String id = scope.id;
        return injectIntersectFromNetworkId(root, id);
    }

    private static String injectIntersectFromNetworkId(ObjectNode root, String networkId) {
        AgentThing agent = AgentToolContext.getAgentThing();
        if (assetListInvokerOverrideForTests == null && agent == null) {
            return errorJson("HIERARCHY_ASSET_LIST_FAILED",
                    "Agent context unavailable; cannot apply hierarchy scope for id=\"" + networkId
                            + "\". Scoped empty — do not fall back to an unscoped global listing.");
        }
        InfoTable assets;
        try {
            if (assetListInvokerOverrideForTests != null) {
                assets = assetListInvokerOverrideForTests.apply(agent, networkId);
            } else {
                assets = HierarchyNetworkServiceFacade.getAssetList(agent, networkId);
            }
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            return errorJson("HIERARCHY_ASSET_LIST_FAILED", msg != null ? msg : "GetAssetList failed");
        }
        List<String> names = new ArrayList<>();
        int rows = assets != null ? assets.getRowCount() : 0;
        for (int i = 0; i < rows; i++) {
            ValueCollection row = assets.getRow(i);
            String n = assetRowThingName(row);
            if (n != null && !n.isEmpty()) {
                names.add(n);
            }
        }
        if (names.isEmpty()) {
            return errorJson("HIERARCHY_SCOPED_EMPTY",
                    "No asset Thing names under hierarchy scope id=\"" + networkId
                            + "\" (GetAssetList returned no usable rows). Scoped empty — do not fall back to an "
                            + "unscoped global listing.");
        }
        if (names.size() > EntityHierarchyIntersectHelper.MAX_INTERSECT_NAMES) {
            return errorJson("INTERSECT_LIST_TOO_LARGE",
                    "Hierarchy GetAssetList produced " + names.size() + " names; exceeds cap "
                            + EntityHierarchyIntersectHelper.MAX_INTERSECT_NAMES + ".");
        }
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (String n : names) {
            arr.add(n);
        }
        root.set("intersectThingNames", arr);
        root.remove("hierarchyNodeId");
        root.remove("hierarchyNodeName");
        return null;
    }

    private static final class IdOrError {
        final String id;
        final String errorJson;

        private IdOrError(String id, String errorJson) {
            this.id = id;
            this.errorJson = errorJson;
        }

        static IdOrError ok(String networkId) {
            return new IdOrError(networkId, null);
        }

        static IdOrError err(String json) {
            return new IdOrError(null, json);
        }

        boolean isError() {
            return errorJson != null;
        }
    }

    private static IdOrError resolveNetworkIdStrict(AgentThing agent, String fragment) {
        InfoTable nodes;
        try {
            nodes = HierarchyNetworkServiceFacade.resolveNetworkId(agent, fragment);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            return IdOrError.err(errorJson("HIERARCHY_RESOLVE_FAILED",
                    "ResolveNetworkID failed for hierarchyNodeName=\"" + fragment + "\": "
                            + (msg != null ? msg : e.getMessage())));
        }
        int n = nodes != null ? nodes.getRowCount() : 0;
        if (n == 0) {
            return IdOrError.err(errorJson("HIERARCHY_RESOLVE_NOT_FOUND",
                    "ResolveNetworkID(\"" + fragment + "\") returned 0 hierarchy nodes. "
                            + "Use GetFlattenNameDescription / GetChildNodes or a clearer fragment."));
        }
        if (n > 1) {
            return IdOrError.err(errorJson("HIERARCHY_RESOLVE_AMBIGUOUS",
                    "ResolveNetworkID(\"" + fragment + "\") returned " + n + " nodes; need exactly one. "
                            + "Candidates (id / name): " + summarizeResolveRows(nodes)));
        }
        String id = hierarchyNodeRowId(nodes.getRow(0));
        if (id == null || id.isEmpty()) {
            return IdOrError.err(errorJson("HIERARCHY_RESOLVE_FAILED",
                    "ResolveNetworkID returned one row but no usable id column for hierarchyNodeName=\"" + fragment
                            + "\"."));
        }
        return IdOrError.ok(id);
    }

    private static String summarizeResolveRows(InfoTable nodes) {
        StringBuilder sb = new StringBuilder();
        int cap = Math.min(nodes.getRowCount(), 8);
        for (int i = 0; i < cap; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ValueCollection row = nodes.getRow(i);
            String rid = hierarchyNodeRowId(row);
            String disp = hierarchyNodeRowDisplayName(row);
            sb.append(rid != null ? rid : "?");
            if (disp != null && !disp.isEmpty() && !disp.equals(rid)) {
                sb.append(" / ").append(disp);
            }
        }
        if (nodes.getRowCount() > cap) {
            sb.append("; …");
        }
        return sb.toString();
    }

    private static String hierarchyNodeRowId(ValueCollection row) {
        if (row == null) {
            return null;
        }
        for (String pref : new String[] {"id", "networkId", "NetworkID"}) {
            try {
                String s = primitiveToPlainString(row.getValue(pref));
                if (s != null && !s.trim().isEmpty()) {
                    return s.trim();
                }
            } catch (Exception ignored) {
                // next
            }
        }
        try {
            for (String k : row.keySet()) {
                if (k != null && k.equalsIgnoreCase("id")) {
                    String s = primitiveToPlainString(row.getValue(k));
                    if (s != null && !s.trim().isEmpty()) {
                        return s.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static String hierarchyNodeRowDisplayName(ValueCollection row) {
        if (row == null) {
            return null;
        }
        try {
            String s = primitiveToPlainString(row.getValue("name"));
            return s != null ? s.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasNonEmptyIntersectThingNamesArray(ObjectNode root) {
        JsonNode arr = root.get("intersectThingNames");
        if (arr == null || !arr.isArray()) {
            return false;
        }
        return arr.size() > 0;
    }

    private static String assetRowThingName(ValueCollection row) {
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
                // try next
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

    private static String errorJson(String code, String message) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message == null ? "" : message);
        return o.toString();
    }
}
