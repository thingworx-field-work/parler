package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.collections.AspectCollection;

/**
 * Offline coverage for list row shape and list filters — no entity resolution.
 */
class DescribeEntitySchemaExecutorRowAndFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compactPropertyRow_includes_optional_category_and_dataShape() {
        PropertyDefinition pd = new PropertyDefinition("p1", "desc", BaseTypes.STRING);
        pd.setCategory("cat1");
        AspectCollection ac = new AspectCollection();
        ac.setStringAspect("dataShape", "NamedThing");
        pd.setAspects(ac);
        ObjectNode row = DescribeEntitySchemaExecutor.compactPropertyRow(pd);
        assertEquals("p1", row.path("name").asText());
        assertEquals("STRING", row.path("baseType").asText());
        assertEquals("cat1", row.path("category").asText());
        assertEquals("NamedThing", row.path("dataShape").asText());
    }

    @Test
    void filterProperties_namePrefix_category_baseType_dataShape_and_sort() throws Exception {
        PropertyDefinition a = new PropertyDefinition("alpha", "", BaseTypes.NUMBER);
        a.setCategory("c1");
        PropertyDefinition b = new PropertyDefinition("Beta", "", BaseTypes.STRING);
        b.setCategory("c1");
        AspectCollection ac = new AspectCollection();
        ac.setStringAspect("dataShape", "DS1");
        b.setAspects(ac);
        PropertyDefinition c = new PropertyDefinition("gamma", "", BaseTypes.STRING);
        c.setCategory("c2");
        List<PropertyDefinition> all = List.of(a, b, c);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("namePrefix", "a");
        List<PropertyDefinition> byPrefix = DescribeEntitySchemaExecutor.filterProperties(all, root);
        assertEquals(1, byPrefix.size());
        assertEquals("alpha", byPrefix.get(0).getName());

        root = MAPPER.createObjectNode();
        root.put("category", "c1");
        List<PropertyDefinition> byCat = DescribeEntitySchemaExecutor.filterProperties(all, root);
        assertEquals(2, byCat.size());
        assertEquals("alpha", byCat.get(0).getName());
        assertEquals("Beta", byCat.get(1).getName());

        root = MAPPER.createObjectNode();
        root.put("baseType", "STRING");
        List<PropertyDefinition> byBt = DescribeEntitySchemaExecutor.filterProperties(all, root);
        assertEquals(2, byBt.size());

        root = MAPPER.createObjectNode();
        root.put("dataShape", "ds1");
        List<PropertyDefinition> byDs = DescribeEntitySchemaExecutor.filterProperties(all, root);
        assertEquals(1, byDs.size());
        assertEquals("Beta", byDs.get(0).getName());
    }

    @Test
    void filterServices_and_compactServiceRow() throws Exception {
        ServiceDefinition s1 = new ServiceDefinition("Zeta", "z");
        s1.setCategory("svc");
        s1.setResultType(new FieldDefinition("r", BaseTypes.NUMBER));
        ServiceDefinition s2 = new ServiceDefinition("alpha", "a");
        s2.setCategory("svc");
        List<ServiceDefinition> all = new ArrayList<>(List.of(s1, s2));

        ObjectNode root = MAPPER.createObjectNode();
        root.put("namePrefix", "al");
        List<ServiceDefinition> matched = DescribeEntitySchemaExecutor.filterServices(all, root);
        assertEquals(1, matched.size());
        assertEquals("alpha", matched.get(0).getName());

        ObjectNode row = DescribeEntitySchemaExecutor.compactServiceRow(s1);
        assertEquals("Zeta", row.path("name").asText());
        assertEquals("NUMBER", row.path("resultBaseType").asText());
    }

    @Test
    void filterPublicServices_hides_private() {
        ServiceDefinition pub = new ServiceDefinition("Pub", "p");
        pub.setPrivate(Boolean.FALSE);
        ServiceDefinition prv = new ServiceDefinition("Prv", "x");
        prv.setPrivate(Boolean.TRUE);
        List<ServiceDefinition> out = DescribeEntitySchemaExecutor.filterPublicServices(List.of(pub, prv));
        assertEquals(1, out.size());
        assertEquals("Pub", out.get(0).getName());
    }

    @Test
    void filterEvents_and_compactEventRow() throws Exception {
        EventDefinition e1 = new EventDefinition("Zed", "z");
        e1.setCategory("ev");
        EventDefinition e2 = new EventDefinition("Alarm", "a");
        e2.setCategory("ev");
        List<EventDefinition> all = List.of(e1, e2);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("category", "ev");
        List<EventDefinition> matched = DescribeEntitySchemaExecutor.filterEvents(all, root);
        assertEquals(2, matched.size());
        assertEquals("Alarm", matched.get(0).getName());

        ObjectNode row = DescribeEntitySchemaExecutor.compactEventRow(e1);
        assertEquals("Zed", row.path("name").asText());
        assertEquals("ev", row.path("category").asText());
    }

    @Test
    void filterFields_sorts_by_ordinal_and_filters() throws Exception {
        FieldDefinition fLate = new FieldDefinition("late", "", BaseTypes.STRING);
        fLate.setOrdinal(99);
        FieldDefinition fEarly = new FieldDefinition("early", "", BaseTypes.NUMBER);
        fEarly.setOrdinal(1);
        AspectCollection ac = new AspectCollection();
        ac.setStringAspect("dataShape", "RowRef");
        fEarly.setAspects(ac);
        List<FieldDefinition> all = List.of(fLate, fEarly);

        List<FieldDefinition> sorted = DescribeEntitySchemaExecutor.filterFields(all, MAPPER.createObjectNode());
        assertEquals("early", sorted.get(0).getName());
        assertEquals("late", sorted.get(1).getName());

        ObjectNode root = MAPPER.createObjectNode();
        root.put("baseType", "NUMBER");
        assertEquals(1, DescribeEntitySchemaExecutor.filterFields(all, root).size());

        root = MAPPER.createObjectNode();
        root.put("dataShape", "rowref");
        assertEquals(1, DescribeEntitySchemaExecutor.filterFields(all, root).size());

        ObjectNode row = DescribeEntitySchemaExecutor.compactFieldRow(fEarly);
        assertEquals("NUMBER", row.path("baseType").asText());
        assertEquals("RowRef", row.path("dataShape").asText());
        assertFalse(row.has("category"));
    }
}
