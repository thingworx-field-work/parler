package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class InvokeServiceAllowPolicyTest {

    @Test
    void blank_json_is_file_missing_not_invalid() {
        InvokeServiceAllowPolicy p = InvokeServiceAllowPolicy.parseJsonOrInvalid("  ");
        assertFalse(p.isInvalid());
        assertTrue(p.isFileMissing());
    }

    @Test
    void invalid_version_is_invalid() {
        InvokeServiceAllowPolicy p = InvokeServiceAllowPolicy.parseJsonOrInvalid("{\"version\":2,\"rules\":[]}");
        assertTrue(p.isInvalid());
        assertFalse(p.isFileMissing());
    }

    @Test
    void same_priority_rules_still_allow_bypass_when_any_matches() {
        String json = "{\"version\":1,\"rules\":["
                + "{\"id\":\"b\",\"priority\":1,\"match\":{\"entityTypes\":[\"Thing\"],\"entityNames\":[\"T\"],"
                + "\"serviceNames\":[\"S\"]}}"
                + ",{\"id\":\"a\",\"priority\":1,\"match\":{\"entityTypes\":[\"Thing\"],\"entityNames\":[\"T\"],"
                + "\"serviceNames\":[\"S\"]}}"
                + "]}";
        InvokeServiceAllowPolicy p = InvokeServiceAllowPolicy.parseJsonOrInvalid(json);
        assertFalse(p.isInvalid());
        assertTrue(p.allowsBypass("Thing", "T", "S"));
    }

    @Test
    void same_priority_sorted_rule_ids_follow_array_order() {
        String json = "{\"version\":1,\"rules\":["
                + "{\"id\":\"second\",\"priority\":1,\"match\":{\"entityTypes\":[\"Thing\"],\"entityNames\":[\"X\"],"
                + "\"serviceNames\":[\"Svc\"]}}"
                + ",{\"id\":\"first\",\"priority\":1,\"match\":{\"entityTypes\":[\"Thing\"],\"entityNames\":[\"X\"],"
                + "\"serviceNames\":[\"Svc\"]}}"
                + "]}";
        InvokeServiceAllowPolicy p = InvokeServiceAllowPolicy.parseJsonOrInvalid(json);
        assertEquals(List.of("second", "first"), p.sortedRuleIdsInEffectOrder());
    }

    @Test
    void wildcard_service_matches() {
        String json = "{\"version\":1,\"rules\":[{\"id\":\"r1\",\"priority\":10,\"match\":{"
                + "\"entityTypes\":[\"Thing\"],\"entityNames\":[\"*\"],\"serviceNames\":[\"Get*\"]}}]}";
        InvokeServiceAllowPolicy p = InvokeServiceAllowPolicy.parseJsonOrInvalid(json);
        assertTrue(p.allowsBypass("Thing", "X", "GetData"));
    }
}
