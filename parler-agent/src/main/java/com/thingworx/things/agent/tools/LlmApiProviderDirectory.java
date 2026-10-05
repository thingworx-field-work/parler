package com.thingworx.things.agent.tools;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Runtime listing for {@code AgentThing.GetAvailableLlmApiProviders()} ({@code docs/agent/llm-api-provider.md} §5.10).
 */
public final class LlmApiProviderDirectory {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(LlmApiProviderDirectory.class);
    private static final int QIT_MAX_ITEMS = 5000;

    private LlmApiProviderDirectory() {}

    public static InfoTable listEnabledLlmApiProviders() throws Exception {
        InfoTable out = new InfoTable(buildListingDataShape());

        RootEntity shapeEnt = PlatformAccess.findAsUser(LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.ThingShape);
        if (shapeEnt == null || !(shapeEnt instanceof IServiceProvider)) {
            LOG.warn("ThingShape {} not found; empty provider listing", LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME);
            return out;
        }
        IServiceProvider shapeServices = (IServiceProvider) shapeEnt;
        ServiceDefinition sd = pickImplementingThingsService(shapeServices);
        if (sd == null) {
            LOG.warn("No QueryImplementingThings* on ThingShape {}; empty listing",
                    LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME);
            return out;
        }
        QueryEntitiesExecutor.ColumnPick columns = QueryEntitiesExecutor.createLeanNameOnlyPick(false);
        ValueCollection params = QueryEntitiesExecutor.buildImplementingThingsParams(
                sd, QIT_MAX_ITEMS, 0, null, false, null, null, columns);
        String serviceName = sd.getName();
        InfoTable outer = shapeServices.processAPIServiceRequest(serviceName, params);
        QueryEntitiesExecutor.QitParse parsed = QueryEntitiesExecutor.parseImplementingThingsOutput(outer);
        InfoTable data = parsed.dataTable;
        if (data == null || data.getRowCount() == 0) {
            return out;
        }
        for (int i = 0; i < data.getRowCount(); i++) {
            ValueCollection row = (ValueCollection) data.getRow(i);
            String thingName = cellString(row, "name");
            if (thingName == null || thingName.isBlank()) {
                continue;
            }
            Object rent = PlatformAccess.findAsUser(thingName.trim(), RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (!(rent instanceof LLMAPIProviderThing)) {
                continue;
            }
            LLMAPIProviderThing provider = (LLMAPIProviderThing) rent;
            Thing thing = (Thing) rent;
            if (!thing.isEnabled()) {
                continue;
            }
            ValueCollection outRow = new ValueCollection();
            outRow.put("name", new StringPrimitive(thing.getName()));
            outRow.put("displayName", new StringPrimitive(thing.getName()));
            outRow.put("providerTemplateName", new StringPrimitive(thing.getThingTemplateName()));
            outRow.put("apiShapeId", new StringPrimitive(provider.getApiShapeId()));
            outRow.put("modelName", new StringPrimitive(provider.getEffectiveModelLabel()));
            outRow.put("contextWindowTokens", new IntegerPrimitive(0));
            outRow.put("enabled", new BooleanPrimitive(true));
            outRow.put("lastHealthy", new StringPrimitive(""));
            String desc = thing.getDescription();
            outRow.put("description", new StringPrimitive(desc != null ? desc : ""));
            out.addRow(outRow);
        }
        return out;
    }

    private static ServiceDefinition pickImplementingThingsService(IServiceProvider parent) throws Exception {
        for (String svc : new String[] {
                QueryEntitiesExecutor.SERVICE_OPTIMIZED_TOTAL,
                QueryEntitiesExecutor.SERVICE_OPTIMIZED,
                QueryEntitiesExecutor.SERVICE_LEGACY }) {
            try {
                ServiceDefinition d = parent.getInstanceServiceDefinition(svc);
                if (d != null) {
                    return d;
                }
            } catch (Exception e) {
                LOG.debug("Provider listing: service {} unavailable: {}", svc, e.getMessage());
            }
        }
        return null;
    }

    private static String cellString(ValueCollection row, String col) {
        if (row == null) {
            return null;
        }
        Object v;
        try {
            v = row.getValue(col);
        } catch (Exception e) {
            v = null;
        }
        if (v == null) {
            return null;
        }
        if (v instanceof IPrimitiveType) {
            try {
                v = ((IPrimitiveType) v).getValue();
            } catch (Exception e) {
                return null;
            }
        }
        return v.toString();
    }

    private static DataShapeDefinition buildListingDataShape() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        int o = 0;
        dsd.addFieldDefinition(stringField("name", o++));
        dsd.addFieldDefinition(stringField("displayName", o++));
        dsd.addFieldDefinition(stringField("providerTemplateName", o++));
        dsd.addFieldDefinition(stringField("apiShapeId", o++));
        dsd.addFieldDefinition(stringField("modelName", o++));
        FieldDefinition ctx = new FieldDefinition();
        ctx.setName("contextWindowTokens");
        ctx.setBaseType(BaseTypes.INTEGER);
        ctx.setOrdinal(o++);
        dsd.addFieldDefinition(ctx);
        FieldDefinition en = new FieldDefinition();
        en.setName("enabled");
        en.setBaseType(BaseTypes.BOOLEAN);
        en.setOrdinal(o++);
        dsd.addFieldDefinition(en);
        dsd.addFieldDefinition(stringField("lastHealthy", o++));
        dsd.addFieldDefinition(stringField("description", o++));
        return dsd;
    }

    private static FieldDefinition stringField(String name, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(BaseTypes.STRING);
        fd.setOrdinal(ordinal);
        return fd;
    }
}
