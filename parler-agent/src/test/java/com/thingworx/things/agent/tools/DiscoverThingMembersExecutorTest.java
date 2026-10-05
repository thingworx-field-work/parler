package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.EntityReference;
import com.thingworx.implementation.ServiceImplementation;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.subscriptions.MultiEventSubscription;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.events.EventDescriptor;

class DiscoverThingMembersExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missing_thing_name_returns_thingname_value_required() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t1", "discover_thing_members", "{}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("THINGNAME_VALUE_REQUIRED", body.path("code").asText());
        assertEquals("thingName", body.path("parameterName").asText());
    }

    @Test
    void include_private_services_rejected() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t2", "discover_thing_members",
                "{\"thingName\":\"X\",\"includePrivateServices\":true}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("includePrivateServices"));
    }

    @Test
    void subscription_scope_rejected() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t3", "discover_thing_members",
                "{\"thingName\":\"X\",\"subscriptionScope\":\"dynamic\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
    }

    @Test
    void unknown_root_key_rejected() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t4", "discover_thing_members",
                "{\"thingName\":\"X\",\"entityType\":\"Thing\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("entityType"));
    }

    @Test
    void unknown_facet_returns_invalid_facet() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t5", "discover_thing_members",
                "{\"thingName\":\"SomeThing\",\"facet\":\"not_a_real_facet\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("INVALID_FACET", body.path("code").asText());
    }

    @Test
    void member_name_on_subscriptions_list_rejected_with_name_prefix_hint() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t5b", "discover_thing_members",
                "{\"thingName\":\"SomeThing\",\"facet\":\"subscriptions\",\"memberName\":\"Sub1\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("memberName"));
        assertEquals("discover_thing_members", body.path("recoveryHint").path("tool").asText());
        assertEquals("namePrefix", body.path("recoveryHint").path("argument").asText());
    }

    @Test
    void member_name_on_properties_list_rejected_with_name_prefix_hint() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t5c", "discover_thing_members",
                "{\"thingName\":\"SomeThing\",\"facet\":\"properties\",\"memberName\":\"Temperature\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("UNSUPPORTED_TOOL_PARAMETER", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("list facet"));
        assertEquals("namePrefix", body.path("recoveryHint").path("argument").asText());
    }

    @Test
    void singular_event_missing_member_name() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t7", "discover_thing_members",
                "{\"thingName\":\"SomeThing\",\"facet\":\"event\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("MISSING_MEMBER_NAME", body.path("code").asText());
    }

    @Test
    void singular_property_missing_member_name() throws Exception {
        String json = DiscoverThingMembersExecutor.execute(new ToolCall("t6", "discover_thing_members",
                "{\"thingName\":\"SomeThing\",\"facet\":\"property\"}"));
        JsonNode body = MAPPER.readTree(json);
        assertEquals("MISSING_MEMBER_NAME", body.path("code").asText());
    }

    @Test
    void list_page_bounds_clamps_offset_past_total() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 100);
        root.put("maxItems", 10);
        DescribeEntitySchemaExecutor.ListPageBounds b = DiscoverThingMembersExecutor.listPageBoundsForRequest(root, 5);
        assertEquals(5, b.offset);
        assertEquals(5, b.endExclusive);
        assertEquals(0, b.returned);
    }

    @Test
    void list_page_bounds_negative_offset_becomes_zero() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", -5);
        root.put("maxItems", 80);
        DescribeEntitySchemaExecutor.ListPageBounds b = DiscoverThingMembersExecutor.listPageBoundsForRequest(root, 20);
        assertEquals(0, b.offset);
        assertTrue(b.returned > 0);
    }

    @Test
    void property_slice_empty_when_offset_equals_total() throws Exception {
        List<PropertyDefinition> defs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            defs.add(new PropertyDefinition("p" + i, "d", BaseTypes.STRING));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 3);
        root.put("maxItems", 10);
        ArrayNode items = DiscoverThingMembersExecutor.slicePropertyPageForTest(defs, root);
        assertEquals(0, items.size());
    }

    @Test
    void service_slice_rows_include_category_and_result_base_type() throws Exception {
        ServiceDefinition s = new ServiceDefinition("GetX", "does X");
        s.setCategory("Data");
        s.setResultType(new FieldDefinition("r", BaseTypes.NUMBER));
        List<ServiceDefinition> defs = List.of(s);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 0);
        root.put("maxItems", 80);
        ArrayNode items = DiscoverThingMembersExecutor.sliceServicePageForTest(defs, root);
        assertEquals(1, items.size());
        assertEquals("GetX", items.get(0).path("name").asText());
        assertEquals("Data", items.get(0).path("category").asText());
        assertEquals("NUMBER", items.get(0).path("resultBaseType").asText());
    }

    @Test
    void filter_event_definitions_by_data_shape_case_insensitive() throws Exception {
        EventDefinition a = new EventDefinition("E1", "d");
        a.setDataShapeName("ShapeA");
        EventDefinition b = new EventDefinition("E2", "d");
        b.setDataShapeName("Other");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("dataShape", "shapea");
        List<EventDefinition> out = DiscoverThingMembersExecutor.filterEventDefinitionsByDataShape(List.of(a, b), root);
        assertEquals(1, out.size());
        assertEquals("E1", out.get(0).getName());
    }

    @Test
    void event_slice_rows_include_data_shape_when_set() throws Exception {
        EventDefinition e = new EventDefinition("AlarmRaised", "desc");
        e.setCategory("Alerts");
        e.setDataShapeName("NamedThing");
        List<EventDefinition> defs = List.of(e);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 0);
        root.put("maxItems", 80);
        ArrayNode items = DiscoverThingMembersExecutor.sliceEventPageForTest(defs, root);
        assertEquals(1, items.size());
        assertEquals("AlarmRaised", items.get(0).path("name").asText());
        assertEquals("Alerts", items.get(0).path("category").asText());
        assertEquals("NamedThing", items.get(0).path("dataShape").asText());
    }

    /**
     * Probative visibility filtering: when {@code getInstancePropertyDefinitionIfVisible} would return
     * null for a name that still appears in the raw instance collection, the list facet must omit it — exercised
     * via {@link DiscoverThingMembersExecutor#listVisiblePropertyDefinitionsUsing} (platform {@link Thing}
     * construction is not available in this offline suite).
     */
    @Test
    void list_visible_property_definitions_using_excludes_names_where_lookup_returns_null() throws Exception {
        List<PropertyDefinition> raw = new ArrayList<>();
        raw.add(new PropertyDefinition("VisibleProp", "", BaseTypes.STRING));
        raw.add(new PropertyDefinition("HiddenProp", "", BaseTypes.NUMBER));
        List<PropertyDefinition> out = DiscoverThingMembersExecutor.listVisiblePropertyDefinitionsUsing(raw,
                name -> "VisibleProp".equals(name) ? raw.get(0) : null);
        assertEquals(1, out.size());
        assertEquals("VisibleProp", out.get(0).getName());
    }

    /**
     * Singular {@code service} facet resolves only from the same public list as {@code services} (negative
     * case: a name not in that list is not found).
     */
    @Test
    void find_public_service_in_list_returns_null_when_name_absent() {
        ServiceDefinition pub = new ServiceDefinition("GetData", "g");
        List<ServiceDefinition> list = List.of(pub);
        assertEquals(pub, DiscoverThingMembersExecutor.findPublicServiceInList(list, "getdata"));
        assertEquals(null, DiscoverThingMembersExecutor.findPublicServiceInList(list, "PrivateOnlySvc"));
    }

    @Test
    void filter_subscriptions_by_name_prefix() throws Exception {
        MultiEventSubscription a = new MultiEventSubscription();
        a.setName("AlphaSub");
        a.setEnabled(true);
        MultiEventSubscription b = new MultiEventSubscription();
        b.setName("BetaSub");
        b.setEnabled(false);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("namePrefix", "al");
        List<MultiEventSubscription> out = DiscoverThingMembersExecutor.filterSubscriptionsByNamePrefix(
                List.of(a, b), root);
        assertEquals(1, out.size());
        assertEquals("AlphaSub", out.get(0).getName());
    }

    @Test
    void subscription_slice_row_includes_owner_and_handler() throws Exception {
        MultiEventSubscription s = new MultiEventSubscription();
        s.setName("Sub1");
        s.setEnabled(true);
        s.setOwner(new EntityReference("OwnerThing", RelationshipTypes.ThingworxRelationshipTypes.Thing));
        ServiceImplementation sim = new ServiceImplementation();
        sim.setHandlerName("MyHandler");
        s.setServiceImplementation(sim);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 0);
        root.put("maxItems", 80);
        ArrayNode items = DiscoverThingMembersExecutor.sliceSubscriptionPageForTest(List.of(s), root);
        assertEquals(1, items.size());
        assertEquals("Sub1", items.get(0).path("name").asText());
        assertTrue(items.get(0).path("enabled").asBoolean());
        assertEquals("OwnerThing", items.get(0).path("owner").asText());
        assertEquals("MyHandler", items.get(0).path("handlerName").asText());
        assertEquals("configured", items.get(0).path("scope").asText());
        assertTrue(items.get(0).has("eventDescriptors"));
        assertTrue(items.get(0).path("eventDescriptors").isArray());
        assertEquals(0, items.get(0).path("eventDescriptors").size());
    }

    @Test
    void subscription_row_serializes_event_descriptors_when_present() throws Exception {
        MultiEventSubscription s = new MultiEventSubscription();
        s.setName("SubWithEv");
        s.setEnabled(true);
        EventDescriptor ed = EventDescriptor.fromLocalDescriptor("Thing.MyThing:DataChange");
        s.addEvent(ed, "");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", 0);
        root.put("maxItems", 80);
        ArrayNode items = DiscoverThingMembersExecutor.sliceSubscriptionPageForTest(List.of(s), root);
        assertEquals(1, items.size());
        assertTrue(items.get(0).path("eventDescriptors").isArray());
        assertEquals(1, items.get(0).path("eventDescriptors").size());
        String desc = items.get(0).path("eventDescriptors").get(0).asText();
        assertTrue(desc.contains("DataChange"), desc);
    }

    @Test
    void non_visible_thing_name_returns_identity_resolution_required() throws Exception {
        try {
            ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
            ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
            String json = DiscoverThingMembersExecutor.execute(new ToolCall("t9", "discover_thing_members",
                    "{\"thingName\":\"GhostLabel\",\"facet\":\"properties\"}"));
            JsonNode body = MAPPER.readTree(json);
            assertEquals("IDENTITY_RESOLUTION_REQUIRED", body.path("code").asText());
            assertEquals("thingName", body.path("parameterName").asText());
        } finally {
            ScalarThingnamePreflight.clearModelGateOverrideForTests();
        }
    }
}
