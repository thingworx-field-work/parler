package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.common.interfaces.IDataShapeDefinitionProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/** {@link InfotableJsonCodec} DataShape resolution (bug 004): {@link com.thingworx.common.utils.MetadataUtilities}. */
class InfotableJsonCodecTest {

    @AfterEach
    void tearDown() {
        InfotableJsonCodec.clearTestDataShapeProviderOverride();
    }

    @Test
    void parameterSchemaForLlm_embeddedLocalDataShape_buildsArraySchema() {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("machine", "", BaseTypes.STRING));
        FieldDefinition param = new FieldDefinition("rows", "", BaseTypes.INFOTABLE);
        param.setLocalDataShape(rowShape);

        Map<String, Object> schema = InfotableJsonCodec.parameterSchemaForLlm(param);
        assertEquals("array", schema.get("type"));
        assertNotNull(schema.get("items"));
    }

    @Test
    void parameterSchemaForLlm_namedAspect_resolvesViaTestProvider() {
        IDataShapeDefinitionProvider provider = name -> {
            if ("PTCSC.Utilization.Aggregate".equals(name)) {
                DataShapeDefinition dsd = new DataShapeDefinition();
                dsd.addFieldDefinition(new FieldDefinition("UtilizationState", "", BaseTypes.STRING));
                return dsd;
            }
            return null;
        };
        InfotableJsonCodec.setTestDataShapeProviderOverride(provider);
        FieldDefinition param = FieldDefinition.createInfoTableFieldDefinition("AggregatedByUtilizationStateData", "",
                "PTCSC.Utilization.Aggregate");

        Map<String, Object> schema = InfotableJsonCodec.parameterSchemaForLlm(param);
        assertEquals("array", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) items.get("properties");
        assertTrue(props.containsKey("UtilizationState"));
    }

    @Test
    void parameterSchemaForLlm_unresolvedAspect_throws() {
        InfotableJsonCodec.setTestDataShapeProviderOverride(name -> null);
        FieldDefinition param = FieldDefinition.createInfoTableFieldDefinition("p", "", "No.Such.DataShape");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InfotableJsonCodec.parameterSchemaForLlm(param));
        assertTrue(ex.getMessage().contains("cannot resolve INFOTABLE row shape"));
    }

    @Test
    void parameterSchemaForLlm_emptyLocalShape_throws() {
        DataShapeDefinition empty = new DataShapeDefinition();
        FieldDefinition param = new FieldDefinition("rows", "", BaseTypes.INFOTABLE);
        param.setLocalDataShape(empty);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InfotableJsonCodec.parameterSchemaForLlm(param));
        assertTrue(ex.getMessage().contains("empty column list"));
    }

    @Test
    void parameterSchemaForLlm_variantColumn_throws() {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("bad", "", BaseTypes.VARIANT));
        FieldDefinition param = new FieldDefinition("rows", "", BaseTypes.INFOTABLE);
        param.setLocalDataShape(rowShape);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InfotableJsonCodec.parameterSchemaForLlm(param));
        assertTrue(ex.getMessage().contains("VARIANT"));
    }

    @Test
    void parameterSchemaForLlm_nestedInfotable_resolvesInnerShapeViaProvider() {
        IDataShapeDefinitionProvider provider = name -> {
            if ("PTCSC.Utilization.Aggregate".equals(name)) {
                DataShapeDefinition inner = new DataShapeDefinition();
                inner.addFieldDefinition(new FieldDefinition("UtilizationState", "", BaseTypes.STRING));
                return inner;
            }
            return null;
        };
        InfotableJsonCodec.setTestDataShapeProviderOverride(provider);

        FieldDefinition nestedCol = FieldDefinition.createInfoTableFieldDefinition("nestedTab", "",
                "PTCSC.Utilization.Aggregate");
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(nestedCol);
        FieldDefinition param = new FieldDefinition("outer", "", BaseTypes.INFOTABLE);
        param.setLocalDataShape(rowShape);

        Map<String, Object> schema = InfotableJsonCodec.parameterSchemaForLlm(param);
        assertEquals("array", schema.get("type"));
    }

    @Test
    void resolveDataShapeForParameter_nonInfotable_returnsNull() {
        FieldDefinition fd = new FieldDefinition("x", "", BaseTypes.STRING);
        assertNull(InfotableJsonCodec.resolveDataShapeForParameter(fd));
    }

    @Test
    void resolveDataShapeForParameter_namedAspect_returnsDefinitionFromProvider() {
        IDataShapeDefinitionProvider provider = name -> {
            if ("RootEntityList".equals(name)) {
                DataShapeDefinition dsd = new DataShapeDefinition();
                dsd.addFieldDefinition(new FieldDefinition("name", "", BaseTypes.STRING));
                return dsd;
            }
            return null;
        };
        InfotableJsonCodec.setTestDataShapeProviderOverride(provider);
        FieldDefinition fd = FieldDefinition.createInfoTableFieldDefinition("Machines", "", "RootEntityList");
        DataShapeDefinition resolved = InfotableJsonCodec.resolveDataShapeForParameter(fd);
        assertNotNull(resolved);
        assertTrue(resolved.getFields().values().stream().anyMatch(f -> "name".equals(f.getName())));
    }

    @Test
    void jsonToInfoTable_roundTrip_withLocalShape() throws Exception {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("machine", "", BaseTypes.STRING));
        FieldDefinition param = new FieldDefinition("rows", "", BaseTypes.INFOTABLE);
        param.setLocalDataShape(rowShape);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = om.readTree("[{\"machine\":\"M1\"}]");
        com.thingworx.types.InfoTable it = InfotableJsonCodec.jsonToInfoTable(arr, rowShape, "rows");
        assertEquals(1, it.getRowCount());
    }

    @Test
    void jsonToInfoTable_presentJsonNullOnRequiredDatetime_acceptsRow() throws Exception {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("ModifiedAt", "", BaseTypes.DATETIME));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = om.readTree("[{\"ModifiedAt\":null}]");
        InfoTable it = InfotableJsonCodec.jsonToInfoTable(arr, rowShape, "UtilizationRecords");
        assertEquals(1, it.getRowCount());
    }

    @Test
    void jsonToInfoTable_presentJsonNullOnRequiredString_acceptsWhenNotPrimaryKey() throws Exception {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("label", "", BaseTypes.STRING));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = om.readTree("[{\"label\":null}]");
        InfoTable it = InfotableJsonCodec.jsonToInfoTable(arr, rowShape, "rows");
        assertEquals(1, it.getRowCount());
    }

    @Test
    void jsonToInfoTable_absentRequiredColumn_reportsMissing() throws Exception {
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(new FieldDefinition("label", "", BaseTypes.STRING));
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = om.readTree("[{}]");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InfotableJsonCodec.jsonToInfoTable(arr, rowShape, "rows"));
        assertTrue(ex.getMessage().contains("missing required column"), ex.getMessage());
    }

    @Test
    void jsonToInfoTable_presentJsonNullOnPrimaryKeyColumn_rejects() throws Exception {
        FieldDefinition idCol = new FieldDefinition("id", "", BaseTypes.STRING);
        idCol.getAspects().setBooleanAspect("isPrimaryKey", true);
        DataShapeDefinition rowShape = new DataShapeDefinition();
        rowShape.addFieldDefinition(idCol);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode arr = om.readTree("[{\"id\":null}]");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InfotableJsonCodec.jsonToInfoTable(arr, rowShape, "rows"));
        assertTrue(ex.getMessage().contains("NULL_VALUE_NOT_ALLOWED"), ex.getMessage());
        assertTrue(ex.getMessage().contains("primary key"), ex.getMessage());
    }
}
