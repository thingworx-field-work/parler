package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Root JSON for {@code tabulate_cached_result} / {@code summarize_cached_result} tool {@code arguments}.
 * Split out so offline JUnit can validate parse behavior without loading {@link CachedTabularToolsExecutor}
 * (which pulls ThingWorx logging statics). See {@code docs/agent/cached_tabular_golden.md} N-06.
 */
public final class CachedTabularToolArgumentsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CachedTabularToolArgumentsJson() {}

    /**
     * Parses the tool call {@code arguments} string as a JSON value; object tools expect an object root.
     *
     * @param arguments raw JSON or {@code null} (treated as {@code "{}"})
     */
    public static JsonNode readRoot(String arguments) throws JsonProcessingException {
        return MAPPER.readTree(arguments == null ? "{}" : arguments);
    }
}
