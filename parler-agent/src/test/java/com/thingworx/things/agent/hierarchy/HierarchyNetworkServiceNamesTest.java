package com.thingworx.things.agent.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HierarchyNetworkServiceNamesTest {

    @Test
    void service_names_are_distinct_non_empty() {
        Set<String> names = new HashSet<>();
        names.add(HierarchyNetworkServiceNames.GET_FLATTEN_NAME_DESCRIPTION);
        names.add(HierarchyNetworkServiceNames.RESOLVE_NETWORK_ID);
        names.add(HierarchyNetworkServiceNames.GET_ROOT_NODE);
        names.add(HierarchyNetworkServiceNames.GET_ASSET_LIST);
        names.add(HierarchyNetworkServiceNames.GET_CHILD_NODES);
        assertFalse(names.stream().anyMatch(s -> s == null || s.isEmpty()));
        assertEquals(5, names.size());
    }
}
