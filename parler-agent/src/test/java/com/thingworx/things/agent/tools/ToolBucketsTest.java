package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ToolBucketsTest {

    @Test
    void classifiesKnownToolsIntoDesignBuckets() {
        assertEquals(ToolBucket.IDENTITY_ROUTING, ToolBuckets.bucketOf("resolve_thing"));
        assertEquals(ToolBucket.IDENTITY_ROUTING, ToolBuckets.bucketOf("resolve_asset_type"));
        assertEquals(ToolBucket.ENTITY_SET_QUERY, ToolBuckets.bucketOf("query_entities"));
        assertEquals(ToolBucket.CURRENT_VALUES_TRENDS, ToolBuckets.bucketOf("tabulate_cached_result"));
        assertEquals(ToolBucket.CURRENT_VALUES_TRENDS, ToolBuckets.bucketOf("set_property_value"));
        assertEquals(ToolBucket.ALERTS, ToolBuckets.bucketOf("query_alert_summary"));
        assertEquals(ToolBucket.METADATA_EXPLORATION, ToolBuckets.bucketOf("invoke_service"));
        assertEquals(ToolBucket.DOCUMENTS, ToolBuckets.bucketOf("search_document_chunks"));
        assertEquals(ToolBucket.SKILLS_PLAYBOOKS, ToolBuckets.bucketOf("get_agent_skill"));
        assertEquals(ToolBucket.SKILLS_PLAYBOOKS, ToolBuckets.bucketOf("start_playbook"));
    }

    @Test
    void utilizationPrefixMapsToUtilizationBucket() {
        assertEquals(ToolBucket.UTILIZATION, ToolBuckets.bucketOf("utilization_records"));
        assertEquals(ToolBucket.UTILIZATION, ToolBuckets.bucketOf("utilization_machine_listing_with_dates"));
    }

    @Test
    void unknownAndEmptyNamesFallToOther() {
        assertEquals(ToolBucket.OTHER, ToolBuckets.bucketOf("some_deployment_specific_tool"));
        assertEquals(ToolBucket.OTHER, ToolBuckets.bucketOf(""));
        assertEquals(ToolBucket.OTHER, ToolBuckets.bucketOf(null));
    }

    @Test
    void parseBucketAcceptsLowerSnakeNamesAndRejectsTypos() {
        assertEquals(ToolBucket.ENTITY_SET_QUERY, ToolBuckets.parseBucket("entity_set_query"));
        assertEquals(ToolBucket.UTILIZATION, ToolBuckets.parseBucket("  Utilization "));
        assertNull(ToolBuckets.parseBucket("not_a_bucket"));
        assertNull(ToolBuckets.parseBucket(""));
        assertNull(ToolBuckets.parseBucket(null));
    }
}
