package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;

/**
 * Slice B: {@link InvokeServiceExecutor#formatDirectServiceResultForLlm} surfaces {@code cacheId} on small
 * {@link BaseTypes#INFOTABLE} results for extended / direct-invoke paths.
 */
class InvokeServiceExecutorFormatDirectInfotableCacheIdTest {

    InvokeServiceExecutorFormatDirectInfotableCacheIdTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void smallInfotable_includesCacheIdAndRows() throws Exception {
        AgentToolContext.setConversationId("conv-direct-infotable-cacheid");

        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(fd);
        InfoTable inner = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("a", new NumberPrimitive(42));
        inner.addRow(row);

        ServiceDefinition sd = new ServiceDefinition("Q", "fixture");
        FieldDefinition rt = new FieldDefinition("result", "", BaseTypes.INFOTABLE);
        sd.getClass().getMethod("setResultType", FieldDefinition.class).invoke(sd, rt);

        String json = InvokeServiceExecutor.formatDirectServiceResultForLlm(inner, sd, null);
        JSONObject o = new JSONObject(json);
        assertEquals("success", o.getString("status"));
        assertEquals("INFOTABLE", o.getString("resultKind"));
        assertTrue(o.has("cacheId"));
        String cacheId = o.getString("cacheId");
        assertFalse(cacheId.isBlank());
        assertEquals(1, o.getInt("rowCount"));
        assertEquals(42, o.getJSONArray("rows").getJSONObject(0).getInt("a"));
    }
}
