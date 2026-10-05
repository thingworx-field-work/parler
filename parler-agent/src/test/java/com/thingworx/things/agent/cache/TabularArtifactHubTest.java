package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.recovery.CacheMissClassifier;
import com.thingworx.things.agent.recovery.TypedToolErrorJson;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.TabularCacheHandleMirror;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class TabularArtifactHubTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable sampleTable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition a = new FieldDefinition();
        a.setName("name");
        a.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(a);
        FieldDefinition b = new FieldDefinition();
        b.setName("n");
        b.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(b);
        InfoTable t = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("x"));
        row.put("n", new NumberPrimitive(1));
        t.addRow(row);
        return t;
    }

    private static InfoTable passwordColumnTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition secret = new FieldDefinition();
        secret.setName("secret");
        secret.setBaseType(BaseTypes.PASSWORD);
        shape.addFieldDefinition(secret);
        return new InfoTable(shape);
    }

    @Test
    void storeLookupRoundTripViaHub() throws Exception {
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        assertTrue(ArtifactCacheIds.isWellFormedPublicCacheId(cacheId));
        InfoTable got = InvokeServiceExecutor.lookupCachedInfotable(cacheId);
        assertNotNull(got);
        assertEquals(1, got.getRowCount().intValue());
        assertEquals("x", got.getRow(0).getStringValue("name"));
    }

    @Test
    void malformedCacheIdIsUniformMiss() {
        assertNull(InvokeServiceExecutor.lookupCachedInfotable("not-a-uuid"));
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(""));
    }

    @Test
    void noAgentAndNoExplicitTestCacheFailsClosed() {
        TabularArtifactHub.clearTestState();
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable()));
        assertEquals(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, ex.code());
    }

    @Test
    void restoreApiRetired_bp9DoesNotCreateLiveEntry() throws Exception {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertFalse(InvokeServiceExecutor.restoreInfotableInConversationCache("conv-1", id, sampleTable()),
                "BP9: restoreInfotableInConversationCache must not resurrect live cache");
        assertNull(InvokeServiceExecutor.lookupCachedInfotableForConversation("conv-1", id));
        assertNull(InvokeServiceExecutor.lookupCachedInfotableForConversation("other-conv", id));
    }

    @Test
    void tokenMirrorFacadeRecordsAndPrunes() throws Exception {
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        TabularCacheHandleMirror.recordQualifyingCacheId(cacheId);
        assertEquals(cacheId, TabularCacheHandleMirror.resolveConversationMirror());
        TabularCacheHandleMirror.pruneIfPointsTo(cacheId);
        assertNull(TabularCacheHandleMirror.resolveConversationMirror());
    }

    @Test
    void differentPrincipalsCannotShareCacheNamespace() throws Exception {
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "alice");
        AgentToolContext.setConversationId("shared-conv");
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        assertNotNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId));

        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "bob");
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId),
                "bob must not open alice-namespaced tabular artifacts");
    }

    @Test
    void crossPrincipalCacheMissOmitsNotFoundReason() throws Exception {
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "alice");
        AgentToolContext.setConversationId("shared-conv");
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        assertTrue(CacheMissClassifier.isLiveNotFoundProven(cacheId),
                "alice owns the live handle and may prove NOT_FOUND");

        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "bob");
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId),
                "bob must not open alice-namespaced tabular artifacts");
        assertFalse(CacheMissClassifier.isLiveNotFoundProven(cacheId),
                "bob must not prove NOT_FOUND from alice's descriptor");

        JsonNode err = MAPPER.readTree(TypedToolErrorJson.cacheMiss(cacheId, "gone"));
        assertEquals("CACHE_MISS", err.path("code").asText());
        assertFalse(err.has("reason"),
                "cross-principal miss must omit reason while keeping outer CACHE_MISS");
    }

    @Test
    void clearForConversationInvalidatesArtifactScope() throws Exception {
        AgentToolContext.setConversationId("clear-conv");
        String cacheId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        assertNotNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId));
        TabularCacheHandleMirror.recordQualifyingCacheId(cacheId);

        TabularCacheHandleMirror.clearForConversationId("clear-conv");

        assertNull(TabularCacheHandleMirror.resolveConversationMirror());
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(cacheId),
                "lookup after conversation clear must miss");
    }

    @Test
    void passwordColumnRejectedBeforeStore() {
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(passwordColumnTable()));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void passwordColumnRestoreFailsClosed() {
        String id = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
        assertFalse(InvokeServiceExecutor.restoreInfotableInConversationCache(
                "pwd-restore", id, passwordColumnTable()));
        assertNull(InvokeServiceExecutor.lookupCachedInfotableForConversation("pwd-restore", id));
    }

    @Test
    void schemaForInfotableExposesPasswordColumn() throws Exception {
        ArtifactSchemaNode schema = TabularArtifactHub.schemaForInfotable(passwordColumnTable());
        assertEquals(BaseTypes.INFOTABLE, schema.baseType());
        assertEquals(1, schema.children().size());
        assertEquals("secret", schema.children().get(0).name());
        assertEquals(BaseTypes.PASSWORD, schema.children().get(0).baseType());
    }

    @Test
    void nestedInfotablePasswordRejectedAtStore() {
        DataShapeDefinition nested = new DataShapeDefinition();
        FieldDefinition secret = new FieldDefinition();
        secret.setName("secret");
        secret.setBaseType(BaseTypes.PASSWORD);
        nested.addFieldDefinition(secret);
        DataShapeDefinition outer = new DataShapeDefinition();
        FieldDefinition child = new FieldDefinition();
        child.setName("child");
        child.setBaseType(BaseTypes.INFOTABLE);
        child.setLocalDataShape(nested);
        outer.addFieldDefinition(child);
        InfoTable t = new InfoTable(outer);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(t));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void nestedInfotableWithoutLocalShapeRejectedAtStore() {
        DataShapeDefinition outer = new DataShapeDefinition();
        FieldDefinition child = new FieldDefinition();
        child.setName("child");
        child.setBaseType(BaseTypes.INFOTABLE);
        outer.addFieldDefinition(child);
        InfoTable t = new InfoTable(outer);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(t));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
    }

    @Test
    void sourceDescriptorAttachedAndComposedOnDerive() throws Exception {
        AgentToolContext.setConversationId("desc-conv");
        String parentId = InvokeServiceExecutor.storeInfotableInConversationCache(sampleTable());
        SourceDescriptor parent = InvokeServiceExecutor.lookupSourceDescriptor(parentId);
        assertNotNull(parent);
        assertEquals("invoke_service", parent.sourceRouteId());
        assertEquals(SourceDescriptor.CompletenessStatus.UNKNOWN, parent.completenessStatus());
        assertEquals(1L, parent.rowsExamined());
        assertEquals(1L, parent.rowsReturned());
        assertNull(parent.rowsAvailable(), "unproven total must stay absent while COMPLETE/UNKNOWN");

        // Derived output smaller than parent examined (filter_count-style one-row result).
        InfoTable oneRow = sampleTable();
        SourceDescriptor derived = com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(
                parent, parentId, oneRow, "tabulate_cached_result");
        assertEquals(1L, derived.rowsExamined(), "derived rowsExamined must prefer parent examined");
        assertEquals(1L, derived.rowsReturned());
        assertNull(derived.rowsAvailable());

        String childId = InvokeServiceExecutor.storeInfotableInConversationCache(oneRow, derived);
        SourceDescriptor got = InvokeServiceExecutor.lookupSourceDescriptor(childId);
        assertNotNull(got);
        assertEquals("tabulate_cached_result", got.sourceRouteId());
        assertTrue(got.parentSourceCacheIds().contains(parentId));

        TabularCacheHandleMirror.clearForConversationId("desc-conv");
        assertNull(InvokeServiceExecutor.lookupSourceDescriptor(parentId));
        assertNull(InvokeServiceExecutor.lookupSourceDescriptor(childId));
    }

    @Test
    void derivedStorePreservesParentExaminedAcrossSmallerOutput() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition a = new FieldDefinition();
        a.setName("name");
        a.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(a);
        InfoTable parentTable = new InfoTable(shape);
        for (int i = 0; i < 5; i++) {
            ValueCollection row = new ValueCollection();
            row.put("name", new StringPrimitive("r" + i));
            parentTable.addRow(row);
        }
        InfoTable derivedOut = new InfoTable(shape);
        ValueCollection one = new ValueCollection();
        one.put("name", new StringPrimitive("only"));
        derivedOut.addRow(one);

        SourceDescriptor parent = com.thingworx.things.agent.source.SourceDescriptorSupport.forPrimaryStore(
                parentTable, "invoke_service");
        assertEquals(5L, parent.rowsExamined());
        assertNull(parent.rowsAvailable());

        SourceDescriptor derived = com.thingworx.things.agent.source.SourceDescriptorSupport.forDerivedStore(
                parent, "parent-id", derivedOut, "tabulate_cached_result");
        assertEquals(5L, derived.rowsExamined());
        assertEquals(1L, derived.rowsReturned());
        assertNull(derived.rowsAvailable());
    }

    @Test
    void columnRoles_readableFromRuntimeDescriptor_andGoneWithScope() throws Exception {
        AgentToolContext.setConversationId("hub-column-roles");
        com.thingworx.things.agent.source.SourceDescriptor desc = com.thingworx.things.agent.source.SourceDescriptor
                .builder().sourceRouteId("any.producer").timeColumn("name").valueColumn("n").build();
        String cacheId = TabularArtifactHub.store(sampleTable(), desc);
        com.thingworx.things.agent.source.SourceDescriptor got = TabularArtifactHub.lookupDescriptor(cacheId);
        assertNotNull(got);
        assertEquals("name", got.timeColumn());
        assertEquals("n", got.valueColumn());

        TabularArtifactHub.invalidateCurrentScope();
        assertNull(TabularArtifactHub.lookupDescriptor(cacheId), "roles follow the descriptor's scope lifecycle");
    }
}
