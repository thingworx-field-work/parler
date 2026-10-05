package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.TagJsonCodec;
import com.thingworx.things.agent.transform.time.CalendarBucketCacheRunner;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Granularity;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.LocationPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CF-03 acceptance 4: every source cell of the labelled table equals the cell an ordinary cache read returns,
 * for scalar columns and for LOCATION, TAGS and nested INFOTABLE, which the typed stream would read as null.
 * Lives in this package to compare cells in the cache's own encoding. INTEGER decodes as NumberPrimitive and
 * is covered, including an empty cell. LONG remains unverified: LongPrimitive requires the missing local
 * com.thingworx.security.io.ValidationException class. No substitute platform class is introduced here.
 */
class CalendarBucketSourceCellPreservationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-03-02T06:00:00Z");

    CalendarBucketSourceCellPreservationTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce4-cell-preservation");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void everySourceCell_equalsTheOrdinaryCacheRead_forScalarAndNonScalarTypes() throws Exception {
        String sourceId = TabularArtifactHub.store(mixedTable());
        MeasurementRunResult run = CalendarBucketCacheRunner.run(sourceId, "ts", ZoneId.of("Europe/Berlin"),
                Granularity.DAY);
        assertNotNull(run.findingCacheId());

        InfoTable ordinaryRead = TabularArtifactHub.lookup(sourceId);
        InfoTable labelled = TabularArtifactHub.lookup(run.findingCacheId());
        JsonNode want = MAPPER.readTree(TabularInfotableCodec.encode(ordinaryRead));
        JsonNode got = MAPPER.readTree(TabularInfotableCodec.encode(labelled));

        List<String> sourceColumns = List.of("s", "n", "i", "b", "ts", "loc", "tags", "children");
        for (int f = 0; f < sourceColumns.size(); f++) {
            assertEquals(want.path("fields").get(f), got.path("fields").get(f), "field " + sourceColumns.get(f));
        }
        assertEquals(sourceColumns.size() + CalendarBucketCacheRunner.BUCKET_COLUMNS.size(),
                got.path("fields").size());
        assertEquals(want.path("rows").size(), got.path("rows").size());
        for (int r = 0; r < want.path("rows").size(); r++) {
            for (String column : sourceColumns) {
                assertEquals(want.path("rows").get(r).get(column), got.path("rows").get(r).get(column),
                        "row " + r + " column " + column);
            }
        }
        JsonNode first = got.path("rows").get(0);
        assertEquals(2_147_483_647d, first.path("i").asDouble(), 0d, "the INTEGER survived");
        assertTrue(got.path("rows").get(1).path("i").isNull(), "an empty INTEGER stays empty");
        assertEquals(34d, first.path("loc").path("longitude").asDouble(), 0d, "the LOCATION survived");
        assertEquals("Sensor", first.path("tags").get(0).path("vocabularyTerm").asText(), "the TAGS survived");
        assertEquals(2, first.path("children").path("rows").size(), "the nested table survived");
        assertFalse(first.path("bucketLabel").isNull());
        assertTrue(got.path("rows").get(1).path("loc").isNull(), "an empty cell stays empty");
    }

    private static InfoTable mixedTable() throws Exception {
        DataShapeDefinition child = new DataShapeDefinition();
        child.addFieldDefinition(field("name", BaseTypes.STRING, null));
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("s", BaseTypes.STRING, null));
        shape.addFieldDefinition(field("n", BaseTypes.NUMBER, null));
        shape.addFieldDefinition(field("i", BaseTypes.INTEGER, null));
        shape.addFieldDefinition(field("b", BaseTypes.BOOLEAN, null));
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME, null));
        shape.addFieldDefinition(field("loc", BaseTypes.LOCATION, null));
        shape.addFieldDefinition(field("tags", BaseTypes.TAGS, null));
        shape.addFieldDefinition(field("children", BaseTypes.INFOTABLE, child));
        InfoTable table = new InfoTable(shape);

        InfoTable children = new InfoTable(child);
        for (String name : new String[] {"c0", "c1"}) {
            ValueCollection c = new ValueCollection();
            c.put("name", new StringPrimitive(name));
            children.addRow(c);
        }
        ValueCollection full = new ValueCollection();
        full.put("s", new StringPrimitive("pump"));
        full.put("n", new NumberPrimitive(1.5));
        full.put("i", new NumberPrimitive(2_147_483_647d));
        full.put("b", new BooleanPrimitive(true));
        full.put("ts", new DatetimePrimitive(new DateTime(T0.toEpochMilli())));
        full.put("loc", new LocationPrimitive(12d, 34d, 56d));
        full.put("tags", TagJsonCodec.parseTagCollectionPrimitive(MAPPER.readTree(
                "[{\"vocabulary\":\"DeviceCategory\",\"vocabularyTerm\":\"Sensor\"}]")));
        full.put("children", new InfoTablePrimitive(children));
        table.addRow(full);

        ValueCollection sparse = new ValueCollection();
        sparse.put("s", new StringPrimitive("valve"));
        sparse.put("ts", new DatetimePrimitive(new DateTime(T0.plusSeconds(90_000).toEpochMilli())));
        table.addRow(sparse);
        return table;
    }

    private static FieldDefinition field(String name, BaseTypes type, DataShapeDefinition local) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(type);
        if (local != null) {
            f.setLocalDataShape(local);
        }
        return f;
    }
}
