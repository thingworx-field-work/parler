package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * B13/S10 proof: discovery siblings share {@link DescribeEntitySchemaExecutor#computeListPageBounds}
 * and legacy Thing-path mappers preserve the same paging keys ({@code totalMatched}/{@code returned}/
 * {@code hasMore}/{@code offset}).
 */
class B13S10DiscoverySiblingSymmetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void discoverThingMembersDelegatesToSharedListPageBounds() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/thingworx/things/agent/tools/DiscoverThingMembersExecutor.java"));
        assertTrue(src.contains("DescribeEntitySchemaExecutor.computeListPageBounds")
                        || src.contains("listPageBoundsForRequest"),
                "discover_thing_members must use shared list page bounds");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", -3);
        root.put("maxItems", 999);
        DescribeEntitySchemaExecutor.ListPageBounds page =
                DiscoverThingMembersExecutor.listPageBoundsForRequest(root, 1000);
        assertEquals(0, page.offset);
        assertEquals(200, page.returned); // hard cap from DescribeEntitySchemaExecutor
        assertEquals(200, page.endExclusive);
    }

    @Test
    void legacyDiscoverPropertiesMapperPreservesPagingKeys() throws Exception {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("entityName", "T1");
        inner.putArray("items").addObject().put("name", "p");
        inner.put("hasMore", true);
        inner.put("offset", 10);
        inner.put("totalMatched", 40);
        ObjectNode legacy = MetadataDiscoveryExecutor.discoverPropertiesLegacySuccessFromInner(inner, "T1");
        assertEquals(true, legacy.path("hasMore").asBoolean());
        assertEquals(10, legacy.path("offset").asInt());
        assertEquals(40, legacy.path("totalMatched").asInt());
        assertTrue(legacy.has("properties"));
        assertFalse(legacy.has("returnedRows"), "legacy properties path keeps totalMatched/returned vocabulary");
    }

    @Test
    void describeEntitySchemaAndDiscoverThingMembersShareClampSemantics() {
        DescribeEntitySchemaExecutor.ListPageBounds a =
                DescribeEntitySchemaExecutor.computeListPageBounds(100, 25, 90);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 100);
        root.put("maxItems", 25);
        DescribeEntitySchemaExecutor.ListPageBounds b =
                DiscoverThingMembersExecutor.listPageBoundsForRequest(root, 90);
        assertEquals(a.offset, b.offset);
        assertEquals(a.returned, b.returned);
        assertEquals(a.endExclusive, b.endExclusive);
        assertEquals(90, a.offset); // clamped to total
        assertEquals(0, a.returned);
    }
}
