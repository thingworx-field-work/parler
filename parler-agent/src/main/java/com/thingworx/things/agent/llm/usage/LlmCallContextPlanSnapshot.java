package com.thingworx.things.agent.llm.usage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional planner measurements copied into call events (CC-7.2 contextPlan).
 */
public final class LlmCallContextPlanSnapshot {

    private final Map<String, Object> fields;

    public LlmCallContextPlanSnapshot(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            this.fields = Collections.emptyMap();
        } else {
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    public Map<String, Object> asMap() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }
}
