package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class TypedTabularStreamTest {

    TypedTabularStreamTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void projectsAndEarlyStopsWithoutFullScan() throws Exception {
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table(500, true));
        try (TypedTabularStream stream = TypedTabularStream.open(cacheId, List.of("ts", "v"), 50)) {
            assertEquals(2, stream.schema().size());
            assertEquals("ts", stream.schema().get(0).name());
            List<TypedRow> all = new ArrayList<>();
            while (!stream.exhausted()) {
                TypedBatch batch = stream.readBatch(10);
                all.addAll(batch.rows());
            }
            assertEquals(50, all.size());
            assertEquals(50L, stream.rowsRead());
            assertTrue(stream.stoppedEarly());
            assertFalse(stream.inputsFullyScanned());
            assertEquals(0L, all.get(0).sourceOrdinal());
            assertEquals(49L, all.get(49).sourceOrdinal());
        }
    }

    @Test
    void fullScanMarksInputsFullyScanned() throws Exception {
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table(12, false));
        try (TypedTabularStream stream = TypedTabularStream.open(cacheId, List.of("name"), 0)) {
            long total = 0;
            while (!stream.exhausted()) {
                total += stream.readBatch(5).size();
            }
            assertEquals(12L, total);
            assertEquals(12L, stream.rowsRead());
            assertFalse(stream.stoppedEarly());
            assertTrue(stream.inputsFullyScanned());
        }
    }

    @Test
    void derivedPublishPropagatesCompletenessMonotone() throws Exception {
        SourceDescriptor parent = SourceDescriptor.builder()
                .sourceRouteId("parent")
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined(100L)
                .rowsReturned(100L)
                .build();
        String parentId = TabularArtifactHub.store(table(3, false), parent);

        List<TypedRow> outRows;
        List<TypedColumn> schema;
        boolean fullyScanned;
        try (TypedTabularStream stream = TypedTabularStream.open(parentId, List.of("name"), 0)) {
            schema = stream.schema();
            outRows = new ArrayList<>();
            while (!stream.exhausted()) {
                outRows.addAll(stream.readBatch(10).rows());
            }
            fullyScanned = stream.inputsFullyScanned();
        }

        InfoTable derivedTable = DerivedTabularPublisher.toInfoTable(schema, outRows);
        SourceDescriptor derivedDesc = DerivedArtifactLineage.forTransform(
                parent, parentId, derivedTable, "u4.derive", fullyScanned, true);
        assertEquals(CompletenessStatus.COMPLETE, derivedDesc.completenessStatus());
        assertTrue(derivedDesc.parentSourceCacheIds().contains(parentId));

        String derivedId = DerivedTabularPublisher.publish(schema, outRows, derivedDesc);
        assertTrue(ArtifactCacheIds.isWellFormedPublicCacheId(derivedId));
        SourceDescriptor looked = TabularArtifactHub.lookupDescriptor(derivedId);
        assertEquals(CompletenessStatus.COMPLETE, looked.completenessStatus());
    }

    @Test
    void partialParentCannotUpgrade() throws Exception {
        SourceDescriptor parent = SourceDescriptor.builder()
                .sourceRouteId("parent")
                .completenessStatus(CompletenessStatus.PARTIAL)
                .build();
        InfoTable t = table(2, false);
        SourceDescriptor derived = DerivedArtifactLineage.forTransform(
                parent, "parent-id", t, "u4.derive", true, true);
        assertEquals(CompletenessStatus.PARTIAL, derived.completenessStatus());
    }

    @Test
    void earlyStopYieldsUnknownEvenIfParentComplete() throws Exception {
        SourceDescriptor parent = SourceDescriptor.builder()
                .sourceRouteId("parent")
                .completenessStatus(CompletenessStatus.COMPLETE)
                .build();
        String parentId = TabularArtifactHub.store(table(40, false), parent);
        List<TypedRow> outRows = new ArrayList<>();
        List<TypedColumn> schema;
        boolean fullyScanned;
        try (TypedTabularStream stream = TypedTabularStream.open(parentId, List.of("name"), 5)) {
            schema = stream.schema();
            while (!stream.exhausted()) {
                outRows.addAll(stream.readBatch(5).rows());
            }
            fullyScanned = stream.inputsFullyScanned();
            assertTrue(stream.stoppedEarly());
        }
        InfoTable derivedTable = DerivedTabularPublisher.toInfoTable(schema, outRows);
        SourceDescriptor derived = DerivedArtifactLineage.forTransform(
                parent, parentId, derivedTable, "u4.derive", fullyScanned, true);
        assertEquals(CompletenessStatus.UNKNOWN, derived.completenessStatus());
    }

    @Test
    void exactCapEqualsRowCount_isFullScan_preservesComplete() throws Exception {
        SourceDescriptor parent = SourceDescriptor.builder()
                .sourceRouteId("parent")
                .completenessStatus(CompletenessStatus.COMPLETE)
                .build();
        String parentId = TabularArtifactHub.store(table(50, false), parent);
        List<TypedRow> outRows = new ArrayList<>();
        List<TypedColumn> schema;
        boolean fullyScanned;
        try (TypedTabularStream stream = TypedTabularStream.open(parentId, List.of("name"), 50)) {
            schema = stream.schema();
            while (!stream.exhausted()) {
                outRows.addAll(stream.readBatch(10).rows());
            }
            assertEquals(50L, stream.rowsRead());
            assertFalse(stream.stoppedEarly(), "exact cap must not report truncation");
            assertTrue(stream.inputsFullyScanned());
            fullyScanned = stream.inputsFullyScanned();
        }
        InfoTable derivedTable = DerivedTabularPublisher.toInfoTable(schema, outRows);
        SourceDescriptor derived = DerivedArtifactLineage.forTransform(
                parent, parentId, derivedTable, "u4.derive", fullyScanned, true);
        assertEquals(CompletenessStatus.COMPLETE, derived.completenessStatus());
    }

    @Test
    void earlyStopDoesNotConsumeUnreadSuffix() throws Exception {
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(table(2_000, false));
        long earlyBytes;
        try (TypedTabularStream early = TypedTabularStream.open(cacheId, List.of("name"), 50)) {
            while (!early.exhausted()) {
                early.readBatch(25);
            }
            assertTrue(early.stoppedEarly());
            assertFalse(early.inputsFullyScanned());
            earlyBytes = early.bytesReadFromArtifact();
        }
        long fullBytes;
        try (TypedTabularStream full = TypedTabularStream.open(cacheId, List.of("name"), 0)) {
            while (!full.exhausted()) {
                full.readBatch(200);
            }
            assertFalse(full.stoppedEarly());
            assertTrue(full.inputsFullyScanned());
            fullBytes = full.bytesReadFromArtifact();
        }
        assertTrue(earlyBytes > 0L);
        assertTrue(fullBytes > earlyBytes,
                "early-stop must not drain the unread suffix; earlyBytes=" + earlyBytes
                        + " fullBytes=" + fullBytes);
        assertTrue(earlyBytes * 2 < fullBytes,
                "early-stop should read substantially less than a full scan");
    }

    private static InfoTable table(int rows, boolean withTs) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        if (withTs) {
            FieldDefinition ts = new FieldDefinition();
            ts.setName("ts");
            ts.setBaseType(BaseTypes.NUMBER);
            shape.addFieldDefinition(ts);
            FieldDefinition v = new FieldDefinition();
            v.setName("v");
            v.setBaseType(BaseTypes.NUMBER);
            shape.addFieldDefinition(v);
        }
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < rows; i++) {
            ValueCollection row = new ValueCollection();
            if (withTs) {
                row.put("ts", new NumberPrimitive((double) (1_700_000_000_000L + i)));
                row.put("v", new NumberPrimitive((double) i));
            }
            row.put("name", new StringPrimitive("r" + i));
            t.addRow(row);
        }
        return t;
    }
}
