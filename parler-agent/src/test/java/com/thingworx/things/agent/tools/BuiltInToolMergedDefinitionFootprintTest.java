package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Reproducible merged-tool-list size metric: UTF-8 bytes of each {@link ToolDefinition}
 * description plus minified JSON for {@link ToolDefinition#getParametersSchema()} using the same Jackson instance
 * the agent stack uses elsewhere in tests. Use before/after comparisons when trimming model-facing schemas.
 * <p>
 * <strong>Description total ({@code descUtf8})</strong> is stable (Java string literals only) — good for cross-machine
 * before/after on copy edits. <strong>Schema total ({@code schemaUtf8})</strong> scales with the platform
 * {@link ThingworxRootEntityTypes#sortedRootEntityTypeNames()} enum embedded in several tool schemas (e.g.
 * {@code invoke_service}, {@code discover_services}); a maintainer JVM with full ThingWorx jars often lands in the
 * tens of kB for merged schema JSON, while a minimal classpath can be much smaller. Compare {@code schemaUtf8} only
 * when {@code rootEntityTypeEnumSize} matches, or normalize by enum size. See {@link #built_in_registry_merged_footprint_sanity()}
 * stdout / assertion messages for live numbers.
 */
class BuiltInToolMergedDefinitionFootprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ENTITY_INTROSPECTION_CLUSTER = Set.of(
            "discover_services",
            "get_service_definition",
            "discover_properties",
            "discover_thing_members",
            "describe_entity_schema");

    @Test
    void built_in_registry_merged_footprint_sanity() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        assertEquals(28, reg.getAllDefinitions().size(), "update count when BuiltInTools.registerAll changes");

        long descUtf8 = 0;
        long schemaUtf8 = 0;
        long entityDesc = 0;
        long entitySchema = 0;
        long candidateDesc = 0;
        long candidateSchema = 0;
        long discoverThingMembersDesc = 0;
        long discoverThingMembersSchema = 0;
        Set<String> candidateNames = Set.of("describe_entity_schema");
        for (ToolDefinition d : reg.getAllDefinitions()) {
            int dl = d.getDescription().getBytes(StandardCharsets.UTF_8).length;
            int sl = MAPPER.writeValueAsString(d.getParametersSchema()).getBytes(StandardCharsets.UTF_8).length;
            descUtf8 += dl;
            schemaUtf8 += sl;
            if (ENTITY_INTROSPECTION_CLUSTER.contains(d.getName())) {
                entityDesc += dl;
                entitySchema += sl;
            }
            if ("discover_thing_members".equals(d.getName())) {
                discoverThingMembersDesc = dl;
                discoverThingMembersSchema = sl;
            }
            if (candidateNames.contains(d.getName())) {
                candidateDesc += dl;
                candidateSchema += sl;
            }
        }
        int rootEnum = ThingworxRootEntityTypes.sortedRootEntityTypeNames().size();
        String evidence = String.format(
                "mergedToolList descUtf8=%d schemaUtf8=%d rootEntityTypeEnumSize=%d entityCluster(desc+schema)=%d "
                        + "deAdvertiseCandidateTools(desc+schema)=%d discover_thing_members(desc+schema)=%d",
                descUtf8, schemaUtf8, rootEnum, entityDesc + entitySchema, candidateDesc + candidateSchema,
                discoverThingMembersDesc + discoverThingMembersSchema);
        System.out.println("BuiltInToolMergedDefinitionFootprintTest: " + evidence);

        // descUtf8: stable string literals — floor tracks ~18k merged baseline on this branch.
        assertTrue(descUtf8 >= 16_000, evidence);
        // schemaUtf8: lower bound for non-trivial schemas; scales with rootEnum (see class Javadoc).
        assertTrue(schemaUtf8 >= 5_000, evidence);
        assertTrue(schemaUtf8 >= rootEnum * 150L, evidence);
        assertTrue(schemaUtf8 < 600_000, evidence);
        assertTrue(entityDesc + entitySchema >= 5_000, evidence);
        assertTrue(candidateDesc + candidateSchema >= 800, evidence);
    }
}
