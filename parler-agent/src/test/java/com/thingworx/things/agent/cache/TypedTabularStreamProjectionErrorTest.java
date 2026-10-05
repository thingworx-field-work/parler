package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/** CM-2: a projection miss carries the visible schema, its position and the descriptor without reopening. */
class TypedTabularStreamProjectionErrorTest {

    @BeforeEach
    void setUp() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId("typed-stream-projection-error");
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String storeThreeColumns(SourceDescriptor descriptor) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition ts = new FieldDefinition();
        ts.setName("observedAt");
        ts.setBaseType(BaseTypes.DATETIME);
        shape.addFieldDefinition(ts);
        FieldDefinition v = new FieldDefinition();
        v.setName("reading");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        FieldDefinition label = new FieldDefinition();
        label.setName("site");
        label.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(label);
        InfoTable table = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("observedAt", new DatetimePrimitive(new DateTime(0L)));
        row.put("reading", new NumberPrimitive(1.0));
        row.put("site", new StringPrimitive("A"));
        table.addRow(row);
        return TabularArtifactHub.store(table, descriptor);
    }

    @Test
    void unknownColumn_carriesSchemaIndexAndDescriptor() throws Exception {
        String cacheId = storeThreeColumns(SourceDescriptor.builder()
                .sourceRouteId("any.producer").timeColumn("observedAt").valueColumn("reading").build());
        UnknownProjectedColumnException ex = assertThrows(UnknownProjectedColumnException.class,
                () -> TypedTabularStream.open(cacheId, List.of("observedAt", "nope"), 0));
        assertEquals("unknown projected column: nope", ex.getMessage());
        assertEquals("nope", ex.columnName());
        assertEquals(1, ex.projectionIndex());
        assertEquals(List.of("observedAt", "reading", "site"),
                ex.visibleSchema().stream().map(TypedColumn::name).toList());
        assertNotNull(ex.descriptor());
        assertEquals("reading", ex.descriptor().valueColumn());
    }

    @Test
    void unknownColumn_keepsIllegalArgumentIdentity_andNullProjectionStillOpens() throws Exception {
        String cacheId = storeThreeColumns(SourceDescriptor.builder().sourceRouteId("plain").build());
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> TypedTabularStream.open(cacheId, List.of("nope"), 0));
        assertEquals("unknown projected column: nope", iae.getMessage());
        assertNull(((UnknownProjectedColumnException) iae).descriptor().timeColumn());
        try (TypedTabularStream all = TypedTabularStream.open(cacheId, null, 0)) {
            assertEquals(3, all.schema().size());
        }
    }
}
