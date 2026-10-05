package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * {@code declare_chart_group} (chart-enhancement design §8.5): declares the one chart group of this user request
 * with 2-6 ordered chart slots. Revision 1 of the manifest (every member {@code pending}) is downlinked by the
 * wire emitter right after this tool result, so the manifest precedes its charts. Declaring is not a
 * presentation action and never counts against the chart action budget.
 */
public final class DeclareChartGroupExecutor {
    public static final String TOOL_NAME = "declare_chart_group";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeclareChartGroupExecutor() {}

    public static String execute(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
            TabularChartRoundState round = AgentToolContext.tabularChartRoundState();
            if (round.getChartGroup() != null) {
                return error("CHART_GROUP_LIMIT", "One chart group per request; bind the remaining charts to the declared group.", null);
            }
            String title = text(root, "title");
            if (title.isEmpty() || title.length() > ChartGroupState.MAX_TITLE_CHARS) {
                return error("INVALID_PARAMETERS", "title must be 1-" + ChartGroupState.MAX_TITLE_CHARS + " characters.", reason("title"));
            }
            String layout = root.has("layout") && !root.get("layout").isNull() ? (root.get("layout").isTextual() ? root.get("layout").asText() : "?") : "auto";
            if (!ChartGroupState.LAYOUTS.contains(layout)) {
                return error("INVALID_PARAMETERS", "layout must be auto, stack or grid.", reason("layout"));
            }
            JsonNode members = root.get("members");
            if (members == null || !members.isArray() || members.size() < ChartGroupState.MIN_MEMBERS
                    || members.size() > ChartGroupState.MAX_MEMBERS) {
                return error("INVALID_PARAMETERS", "members must list " + ChartGroupState.MIN_MEMBERS + "-"
                        + ChartGroupState.MAX_MEMBERS + " slots.", reason("members_count"));
            }
            List<String[]> keysAndNames = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode m : members) {
                String key = text(m, "key");
                String name = text(m, "name");
                if (!ChartGroupState.MEMBER_KEY.matcher(key).matches()) {
                    return error("INVALID_PARAMETERS", "member key must match ^[a-z0-9_-]{1,32}$.", reason("member_key"));
                }
                if (!seen.add(key)) {
                    return error("INVALID_PARAMETERS", "member keys must be unique.", reason("duplicate_key"));
                }
                if (name.isEmpty() || name.length() > ChartGroupState.MAX_NAME_CHARS) {
                    return error("INVALID_PARAMETERS", "member name must be 1-" + ChartGroupState.MAX_NAME_CHARS + " characters.", reason("member_name"));
                }
                keysAndNames.add(new String[]{key, name});
            }
            // C3b-2a (design §8.7): optional shared category dimension; trimmed 1-80 chars declares that members
            // share one category dimension, else the group is a plain C3b-1 group.
            String sharedDimension = null;
            if (root.has("sharedCategoryDimension") && !root.get("sharedCategoryDimension").isNull()) {
                JsonNode dimNode = root.get("sharedCategoryDimension");
                String dim = dimNode.isTextual() ? dimNode.asText().trim() : "";
                if (dim.isEmpty() || dim.length() > ChartGroupState.MAX_DIMENSION_CHARS) {
                    return error("INVALID_PARAMETERS", "sharedCategoryDimension must be 1-"
                            + ChartGroupState.MAX_DIMENSION_CHARS + " characters when supplied.", reason("shared_dimension"));
                }
                sharedDimension = dim;
            }
            String groupId = round.nextGroupId();
            ChartGroupState state = new ChartGroupState(groupId, title, layout, keysAndNames, sharedDimension);
            round.setChartGroup(state);
            JSONObject ok = new JSONObject();
            ok.put("status", "success");
            ok.put("code", "CHART_GROUP_DECLARED");
            ok.put("groupId", groupId);
            ok.put("revision", 1);
            ok.put("title", title);
            ok.put("layout", layout);
            if (sharedDimension != null) {
                ok.put("sharedCategoryDimension", sharedDimension);
            }
            JSONArray keys = new JSONArray();
            JSONArray full = new JSONArray();
            int order = 0;
            for (String[] kn : keysAndNames) {
                keys.put(kn[0]);
                full.put(new JSONObject().put("key", kn[0]).put("name", kn[1]).put("order", order++));
            }
            ok.put("memberKeys", keys);
            ok.put("members", full);
            ok.put("next", "Build each member with build_chart_from_tabular_result and groupMemberKey; members you do not build end as errors.");
            return ok.toString();
        } catch (Exception e) {
            return error("INVALID_PARAMETERS", "declare_chart_group arguments could not be read: " + e.getMessage(), null);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node != null ? node.get(field) : null;
        return v != null && v.isTextual() ? v.asText().trim() : "";
    }

    private static JSONObject reason(String r) {
        return new JSONObject().put("reason", r);
    }

    private static String error(String code, String message, JSONObject details) {
        JSONObject o = new JSONObject();
        o.put("status", "error");
        o.put("code", code);
        o.put("message", message);
        if (details != null) {
            o.put("details", details);
        }
        return o.toString();
    }
}
