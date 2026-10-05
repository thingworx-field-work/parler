package com.thingworx.things.agent.hostcontext;

import java.util.Map;
import java.util.Set;

/**
 * Supported host-context formatters and required argument counts (load-time validation).
 */
final class HostContextFormatterSpec {

    static final Set<String> KNOWN = Set.of(
            "jsonFence", "typedList", "filters", "timeWindow", "hierarchy", "list", "kv");

    private static final Map<String, Integer> REQUIRED_ARGS = Map.of(
            "jsonFence", 2,
            "typedList", 4,
            "filters", 1,
            "timeWindow", 1,
            "hierarchy", 2,
            "list", 2,
            "kv", 1);

    private HostContextFormatterSpec() {
    }

    static String validateCall(String name, int argCount, boolean wholeLine) {
        if (name == null || name.isEmpty()) {
            return "formatter name is empty";
        }
        if (!KNOWN.contains(name)) {
            return "unknown formatter: " + name;
        }
        Integer required = REQUIRED_ARGS.get(name);
        if (required != null && argCount != required) {
            return "format." + name + " requires " + required + " arguments, got " + argCount;
        }
        if ("jsonFence".equals(name) && !wholeLine) {
            return "format.jsonFence placeholder must occupy the whole line (no inline jsonFence)";
        }
        return null;
    }
}
