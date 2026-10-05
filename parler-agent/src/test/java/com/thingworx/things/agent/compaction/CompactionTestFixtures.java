package com.thingworx.things.agent.compaction;

/**
 * Shared matrix tool-result JSON for compaction tests. Tier B shrink-only promotion
 * requires fixtures whose summary envelope is strictly smaller than the raw matrix body.
 */
public final class CompactionTestFixtures {

    private CompactionTestFixtures() {}

    /**
     * Large cached sample matrix: shrinks reliably under Tier B (cacheId-only column schema).
     */
    public static String largeCacheSampleMatrixToolJson() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"E").append(i).append("\"]");
        }
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE_LARGE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"cacheId\":\"tabular-cache-abc\",\"totalCount\":500,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],"
                + "\"sampleRows\":[" + rows + "]}";
    }

    /** Tiny matrix whose Tier B summary is larger than the raw body (no-shrink skip). */
    public static String smallTwoRowMatrixToolJson() {
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"$format\":\""
                + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"x\",\"baseType\":\"STRING\"}],\"rows\":[[\"a\"],[\"b\"]]}";
    }

    /**
     * Stamped {@code get_entity} body large enough to shrink under Tier B entity promotion.
     */
    public static String largeGetEntityShapedToolJson() {
        String aspectsPad = "a".repeat(400);
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < 45; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append("{\"name\":\"Prop").append(i).append("\",\"baseType\":\"NUMBER\",")
                    .append("\"description\":\"d\",\"isPersistent\":true,\"isLogged\":false,")
                    .append("\"aspects\":{\"defaultValue\":0,\"detail\":\"").append(aspectsPad).append(i)
                    .append("\"}}");
        }
        StringBuilder services = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                services.append(',');
            }
            services.append("{\"name\":\"Svc").append(i).append("\",\"description\":\"svc\",")
                    .append("\"parameterDefinitions\":[{\"name\":\"p\",\"baseType\":\"STRING\",\"required\":true}]}");
        }
        return "{\"status\":\"success\",\"$format\":\"" + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_V1
                + "\",\"entityType\":\"ThingTemplate\",\"entityName\":\"Tmpl.Large\","
                + "\"template\":\"Base\",\"description\":\"demo entity\","
                + "\"tags\":[\"a\",\"b\"],\"properties\":[" + properties + "],\"services\":["
                + services + "],\"events\":[{\"name\":\"Ev1\",\"description\":\"e\"}]}";
    }

    /** invoke_service-style event history — must not be promoted as entity metadata. */
    public static String invokeServiceEventHistoryShapedToolJson() {
        return "{\"status\":\"success\",\"entityType\":\"Thing\",\"entityName\":\"PumpA\","
                + "\"events\":[{\"name\":\"Alarm\",\"timestamp\":\"2025-10-01T00:00:00Z\","
                + "\"severity\":\"CRITICAL\",\"message\":\"flow drop\"},"
                + "{\"name\":\"Alarm\",\"timestamp\":\"2025-10-01T01:00:00Z\","
                + "\"severity\":\"WARN\",\"message\":\"pressure spike\"}]}";
    }

    /** Slim discover_properties shape — must not be promoted as entity metadata. */
    public static String discoverPropertiesShapedToolJson() {
        return "{\"status\":\"success\",\"entityType\":\"Thing\",\"entityName\":\"MyThing\","
                + "\"properties\":[{\"name\":\"Temperature\",\"description\":\"t\",\"baseType\":\"NUMBER\"}],"
                + "\"hasMore\":false,\"offset\":0,\"totalMatched\":1,\"propertySource\":\"getInstancePropertyDefinitions\"}";
    }
}
