package com.thingworx.things.agent.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheIds;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.join.ExactJoinCacheRunner.ExactJoinRunResult;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class ExactJoinCacheRunnerTest {

    ExactJoinCacheRunnerTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("tqj5-exact-join");
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        U4OperationAdmission.resetForTests();
    }

    @Test
    void streamsProbeAndPublishesWithLineage() throws Exception {
        SourceDescriptor complete = SourceDescriptor.builder()
                .sourceRouteId("src")
                .completenessStatus(CompletenessStatus.COMPLETE)
                .rowsExamined(3L)
                .rowsReturned(3L)
                .build();
        String leftId = TabularArtifactHub.store(idValTable(3), complete);
        String rightId = TabularArtifactHub.store(idNameTable(3), complete);

        ExactJoinRunResult run = ExactJoinCacheRunner.run(
                leftId, rightId, DemoExactJoinAppProfile.innerOneToOneOnId());
        assertTrue(run.mayPublish());
        assertNotNull(run.findingCacheId());
        assertTrue(ArtifactCacheIds.isWellFormedPublicCacheId(run.findingCacheId()));
        assertEquals(EvidenceStatus.SUCCESS, run.envelope().status());
        assertEquals(CompletenessStatus.COMPLETE, run.envelope().completeness());
        assertTrue(run.envelope().inputsFullyScanned());
        assertEquals(3L, run.join().matchedRows());

        SourceDescriptor derived = TabularArtifactHub.lookupDescriptor(run.findingCacheId());
        assertNotNull(derived);
        assertTrue(derived.parentSourceCacheIds().contains(leftId));
        assertTrue(derived.parentSourceCacheIds().contains(rightId));
    }

    @Test
    void failurePublishesNoHandle() throws Exception {
        SourceDescriptor complete = SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .build();
        String leftId = TabularArtifactHub.store(idValTableWithDupKeys(), complete);
        String rightId = TabularArtifactHub.store(idNameTable(1), complete);

        ExactJoinRunResult run = ExactJoinCacheRunner.run(
                leftId, rightId, DemoExactJoinAppProfile.innerOneToOneOnId());
        assertFalse(run.mayPublish());
        assertNull(run.findingCacheId());
        assertEquals(ExactJoinReason.CARDINALITY_VIOLATION, run.join().reason());
        assertEquals(EvidenceStatus.ERROR, run.envelope().status());
    }

    @Test
    void admissionDisabled_rejects() throws Exception {
        U4OperationAdmission.setExactJoinEnabled(false);
        String leftId = InvokeServiceExecutor.storeInfotableInConversationCache(idValTable(1));
        String rightId = InvokeServiceExecutor.storeInfotableInConversationCache(idNameTable(1));
        ExactJoinRunResult run = ExactJoinCacheRunner.run(
                leftId, rightId, DemoExactJoinAppProfile.innerOneToOneOnId());
        assertTrue(run.unavailable());
        assertEquals(U4OperationAdmission.EXACT_JOIN_UNAVAILABLE, run.unavailableReason());
        assertNull(run.findingCacheId());
    }

    @Test
    void oversizedBuild_noPublish() throws Exception {
        SourceDescriptor complete = SourceDescriptor.builder()
                .completenessStatus(CompletenessStatus.COMPLETE)
                .build();
        String leftId = TabularArtifactHub.store(idValTable(5), complete);
        String rightId = TabularArtifactHub.store(idNameTable(5), complete);
        ExactJoinConfig cfg = ExactJoinConfig.builder()
                .keys(java.util.List.of(new JoinKeySpec("id", "id")))
                .buildSide(BuildSide.LEFT)
                .maxBuildRows(2)
                .profileDigest("budget-test")
                .build();
        ExactJoinRunResult run = ExactJoinCacheRunner.run(leftId, rightId, cfg);
        assertFalse(run.mayPublish());
        assertNull(run.findingCacheId());
        assertEquals(ExactJoinReason.BUDGET_EXCEEDED, run.join().reason());
    }

    private static InfoTable idValTable(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition id = new FieldDefinition();
        id.setName("id");
        id.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(id);
        FieldDefinition val = new FieldDefinition();
        val.setName("val");
        val.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(val);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("id", new StringPrimitive("k" + i));
            vc.put("val", new NumberPrimitive((double) i));
            table.addRow(vc);
        }
        return table;
    }

    private static InfoTable idValTableWithDupKeys() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition id = new FieldDefinition();
        id.setName("id");
        id.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(id);
        FieldDefinition val = new FieldDefinition();
        val.setName("val");
        val.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(val);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < 2; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("id", new StringPrimitive("dup"));
            vc.put("val", new NumberPrimitive((double) i));
            table.addRow(vc);
        }
        return table;
    }

    private static InfoTable idNameTable(int n) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition id = new FieldDefinition();
        id.setName("id");
        id.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(id);
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        InfoTable table = new InfoTable(shape);
        for (int i = 0; i < n; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("id", new StringPrimitive("k" + i));
            vc.put("name", new StringPrimitive("n" + i));
            table.addRow(vc);
        }
        return table;
    }
}
