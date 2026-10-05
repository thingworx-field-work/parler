package com.thingworx.things.agent.playbook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/** Parsed static {@code PlaybookJson} for one playbook file (merged catalog + DAG root). */
public final class PlaybookDocument {

    private final String playbookId;
    private final String schema;
    private final String title;
    private final String description;
    private final String whenToUse;
    private final JSONObject inputSchema;
    private final JSONObject execution;
    private final JSONObject budgets;
    private final String finalNodeId;
    private final Map<String, JSONObject> nodesById;
    private final List<String> nodeIdsInOrder;

    public PlaybookDocument(String playbookId, String schema, String title, String description, String whenToUse,
            JSONObject inputSchema, JSONObject execution, JSONObject budgets, String finalNodeId,
            Map<String, JSONObject> nodesById, List<String> nodeIdsInOrder) {
        this.playbookId = playbookId != null ? playbookId : "";
        this.schema = schema;
        this.title = title;
        this.description = description != null ? description : "";
        this.whenToUse = whenToUse != null ? whenToUse : "";
        this.inputSchema = inputSchema;
        this.execution = execution;
        this.budgets = budgets;
        this.finalNodeId = finalNodeId;
        this.nodesById = Collections.unmodifiableMap(nodesById);
        this.nodeIdsInOrder = Collections.unmodifiableList(nodeIdsInOrder);
    }

    /** Document {@code id} field (must match package directory name when using directory discovery). */
    public String playbookId() {
        return playbookId;
    }

    public String schema() {
        return schema;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String whenToUse() {
        return whenToUse;
    }

    public JSONObject inputSchema() {
        return inputSchema;
    }

    public JSONObject execution() {
        return execution;
    }

    public JSONObject budgets() {
        return budgets;
    }

    public String finalNodeId() {
        return finalNodeId;
    }

    public Map<String, JSONObject> nodesById() {
        return nodesById;
    }

    public List<String> nodeIdsInOrder() {
        return nodeIdsInOrder;
    }

    public static PlaybookDocument parse(String rawJson) {
        JSONObject root = new JSONObject(rawJson);
        String id = root.optString("id", "");
        String schema = root.optString("schema", "");
        String title = root.optString("title", "");
        String description = root.optString("description", "");
        String whenToUse = root.optString("whenToUse", "");
        JSONObject inputSchema = root.optJSONObject("inputSchema");
        if (inputSchema == null) {
            inputSchema = new JSONObject();
        }
        JSONObject execution = root.optJSONObject("execution");
        if (execution == null) {
            execution = new JSONObject();
        }
        JSONObject budgets = root.optJSONObject("budgets");
        if (budgets == null) {
            budgets = new JSONObject();
        }
        String finalNode = root.optString("finalNode", "");
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null) {
            nodes = new JSONArray();
        }
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) {
                continue;
            }
            String nodeId = n.optString("id", "");
            if (nodeId.isEmpty()) {
                continue;
            }
            byId.put(nodeId, n);
            order.add(nodeId);
        }
        return new PlaybookDocument(id, schema, title, description, whenToUse, inputSchema, execution, budgets,
                finalNode, byId, order);
    }
}
