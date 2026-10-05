package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * PASSWORD route matrix — typed {@link BaseTypes#PASSWORD} columns are rejected at
 * ArtifactCache create (typed schema proof) before any cache write. Former tabulate never-echo
 * cases that required a cached PASSWORD table are unreachable once the store gate is enforced;
 * executor {@code PROTECTED_TABULAR_COLUMN_BLOCKED} remains defense-in-depth for in-memory paths.
 */
class QuerySpecPasswordNeverEchoTabulateMatrixTest {

    QuerySpecPasswordNeverEchoTabulateMatrixTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final String LEAK = "Xq7PasswordLeakTestValue";

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable passwordSampleTable() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fl = new FieldDefinition();
        fl.setName("label");
        fl.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fl);
        FieldDefinition fs = new FieldDefinition();
        fs.setName("secretCol");
        fs.setBaseType(BaseTypes.PASSWORD);
        shape.addFieldDefinition(fs);
        InfoTable src = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("label", new StringPrimitive("a"));
        row.put("secretCol", new StringPrimitive(LEAK));
        src.addRow(row);
        return src;
    }

    private static void assertStoreRejectsPasswordWithoutEchoingLeak() {
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.storeInfotableInConversationCache(passwordSampleTable()));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
        assertFalse(ex.getMessage() != null && ex.getMessage().contains(LEAK),
                "PASSWORD reject must not echo cell/secret payload: " + ex.getMessage());
    }

    @Test
    void store_rejects_password_column_filter_count_path() {
        AgentToolContext.setConversationId("pwd-matrix-1");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_filter_rows_fields_path() {
        AgentToolContext.setConversationId("pwd-matrix-2");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_group_metric_path() {
        AgentToolContext.setConversationId("pwd-matrix-3");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_legacy_op_column_path() {
        AgentToolContext.setConversationId("pwd-matrix-5");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_legacy_field_key_path() {
        AgentToolContext.setConversationId("pwd-matrix-6");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_sort_topn_path() {
        AgentToolContext.setConversationId("pwd-matrix-7");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }

    @Test
    void store_rejects_password_column_group_metric_where_legacy_path() {
        AgentToolContext.setConversationId("pwd-matrix-8");
        assertStoreRejectsPasswordWithoutEchoingLeak();
    }
}
