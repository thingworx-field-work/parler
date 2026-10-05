package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider-safe JSON Schema for {@code declare_chart_group} (chart-enhancement design §8.5). */
public final class DeclareChartGroupToolSchema {
    private DeclareChartGroupToolSchema() {}

    public static Map<String, Object> parametersSchema() {
        Map<String, Object> member = new LinkedHashMap<>();
        Map<String, Object> memberProps = new LinkedHashMap<>();
        memberProps.put("key", Map.of("type", "string", "pattern", "^[a-z0-9_-]{1,32}$",
                "description", "Unique slot key you will pass as groupMemberKey when building that chart."));
        memberProps.put("name", Map.of("type", "string", "description", "Readable member name (1-80 chars)."));
        member.put("type", "object");
        member.put("properties", memberProps);
        member.put("required", new String[]{"key", "name"});
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("type", "array");
        members.put("minItems", ChartGroupState.MIN_MEMBERS);
        members.put("maxItems", ChartGroupState.MAX_MEMBERS);
        members.put("items", member);
        members.put("description", "2-6 chart slots in display order; declare only members you will build this turn.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "Group title (1-120 chars)."));
        props.put("members", members);
        props.put("layout", Map.of("type", "string", "enum", List.of("auto", "stack", "grid"),
                "description", "Layout hint (default auto)."));
        props.put("sharedCategoryDimension", Map.of("type", "string",
                "description", "Optional (1-80 chars). Give it only when the members share one category dimension "
                        + "(for example device state) so the same category takes the same colour across the group; "
                        + "omit it when the members measure different or unrelated categories."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new String[]{"title", "members"});
        return schema;
    }
}
