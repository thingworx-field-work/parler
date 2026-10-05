package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;

/**
 * Bug 004: extended-tool schema build for SCPA utilization helper services with INFOTABLE + named DataShape aspects.
 * Uses {@link InfotableJsonCodec#setTestDataShapeProviderOverride} so tests do not require live DataShape entities.
 *
 * @see com.thingworx.things.agent.configrepo.ExtendedToolsManifest
 */
class Bug004UtilizationExtendedToolHarvestTest {

    private static final Set<String> BUG004_SHAPES = Set.of(
            "PTCSC.Utilization.UtilizationWithDuration",
            "PTCSC.Utilization.Aggregate",
            "PTCSC.Utilization.Statistics",
            "RootEntityList",
            "PTCSC.Utilization.Machine");

    @AfterEach
    void tearDown() {
        InfotableJsonCodec.clearTestDataShapeProviderOverride();
    }

    @Test
    void allSevenUtilizationExtendedToolSchemasBuild() {
        InfotableJsonCodec.setTestDataShapeProviderOverride(Bug004UtilizationExtendedToolHarvestTest::stubShape);

        assertToolSchema("utilization_records", svcGetUtilizationRecords());
        assertToolSchema("utilization_records_by_machine", svcGetUtilizationRecordsByMachine());
        assertToolSchema("get_utilization_overview", svcGetUtilizationOverview());
        assertToolSchema("get_utilization_state_summary", svcGetUtilizationStateSummary());
        assertToolSchema("utilization_aggregate_by_state", svcGetAggregatesByUtilizationState());
        assertToolSchema("utilization_stats_for_aggregate", svcGetStatsForAggregateData());
        assertToolSchema("utilization_machine_listing", svcGetMachineListing());
        assertToolSchema("utilization_machine_listing_with_dates", svcGetMachineListingWithDates());
        assertToolSchema("utilization_aggregate_by_state_time_fence", svcGetAggregatesByUtilizationStateTimeFence());
    }

    @Test
    void s13SampleBaseTypes_machineThingNameAndIncludeStatsBoolean() {
        InfotableJsonCodec.setTestDataShapeProviderOverride(Bug004UtilizationExtendedToolHarvestTest::stubShape);

        ServiceDefinition overview = svcGetUtilizationOverview();
        assertEquals(BaseTypes.THINGNAME, overview.getParameters().getFieldDefinition("Machine").getBaseType());

        ServiceDefinition byMachine = svcGetUtilizationRecordsByMachine();
        assertEquals(BaseTypes.THINGNAME, byMachine.getParameters().getFieldDefinition("Machine").getBaseType());

        ServiceDefinition stateSummary = svcGetUtilizationStateSummary();
        assertEquals(BaseTypes.BOOLEAN, stateSummary.getParameters().getFieldDefinition("IncludeStats").getBaseType());
        assertEquals(BaseTypes.THINGNAME, stateSummary.getParameters().getFieldDefinition("Machine").getBaseType());

        assertToolSchema("get_utilization_overview", overview);
        assertToolSchema("get_utilization_state_summary", stateSummary);
        assertToolSchema("utilization_records_by_machine", byMachine);
    }

    private static void assertToolSchema(String llmName, ServiceDefinition sd) {
        ToolDefinition td = CustomToolHarvester.toToolDefinitionForExtendedTool(sd, llmName, "when", "Title", true);
        assertNotNull(td);
        Map<String, Object> root = td.getParametersSchema();
        assertEquals("object", root.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) root.get("properties");
        assertNotNull(props);
        assertTrue(props.size() >= 1, llmName + " should declare at least one parameter in this fixture");
        for (Object v : props.values()) {
            assertTrue(v instanceof Map<?, ?>, llmName + " property schema should be a map");
        }
    }

    private static DataShapeDefinition stubShape(String name) {
        if (name == null || !BUG004_SHAPES.contains(name)) {
            return null;
        }
        DataShapeDefinition dsd = new DataShapeDefinition();
        dsd.addFieldDefinition(new FieldDefinition("stub_" + name.replace('.', '_'), "", BaseTypes.STRING));
        return dsd;
    }

    private static ServiceDefinition svcGetUtilizationRecords() {
        ServiceDefinition sd = new ServiceDefinition("GetUtilizationRecords", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        return sd;
    }

    private static ServiceDefinition svcGetUtilizationRecordsByMachine() {
        ServiceDefinition sd = new ServiceDefinition("GetUtilizationRecordsByMachine", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        sd.getParameters().addFieldDefinition(new FieldDefinition("Machine", "", BaseTypes.THINGNAME));
        return sd;
    }

    /** Mirrors corrected DEV-import types in {@code dev_data/Parler_SCPA_Guidance.xml} (S13). */
    private static ServiceDefinition svcGetUtilizationOverview() {
        ServiceDefinition sd = new ServiceDefinition("GetUtilizationOverview", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        sd.getParameters().addFieldDefinition(new FieldDefinition("Machine", "", BaseTypes.THINGNAME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("IncludeMachineCoverage", "", BaseTypes.BOOLEAN));
        sd.getParameters().addFieldDefinition(new FieldDefinition("IncludeEffectiveDates", "", BaseTypes.BOOLEAN));
        sd.getParameters().addFieldDefinition(new FieldDefinition("IncludeStateSummary", "", BaseTypes.BOOLEAN));
        sd.getParameters().addFieldDefinition(new FieldDefinition("MaxMachines", "", BaseTypes.INTEGER));
        return sd;
    }

    /** Mirrors corrected DEV-import types in {@code dev_data/Parler_SCPA_Guidance.xml} (S13). */
    private static ServiceDefinition svcGetUtilizationStateSummary() {
        ServiceDefinition sd = new ServiceDefinition("GetUtilizationStateSummary", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        sd.getParameters().addFieldDefinition(new FieldDefinition("Machine", "", BaseTypes.THINGNAME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("IncludeStats", "", BaseTypes.BOOLEAN));
        return sd;
    }

    private static ServiceDefinition svcGetAggregatesByUtilizationState() {
        ServiceDefinition sd = new ServiceDefinition("GetAggregatesByUtilizationState", "fixture");
        sd.getParameters().addFieldDefinition(FieldDefinition.createInfoTableFieldDefinition("UtilizationRecords", "",
                "PTCSC.Utilization.UtilizationWithDuration"));
        return sd;
    }

    private static ServiceDefinition svcGetStatsForAggregateData() {
        ServiceDefinition sd = new ServiceDefinition("GetStatsForAggregateData", "fixture");
        sd.getParameters().addFieldDefinition(FieldDefinition.createInfoTableFieldDefinition(
                "AggregatedByUtilizationStateData", "", "PTCSC.Utilization.Aggregate"));
        return sd;
    }

    private static ServiceDefinition svcGetMachineListing() {
        ServiceDefinition sd = new ServiceDefinition("GetMachineListing", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("UsesSelection", "", BaseTypes.BOOLEAN));
        FieldDefinition machines = FieldDefinition.createInfoTableFieldDefinition("Machines", "", "RootEntityList");
        if (machines.getAspects() != null) {
            machines.getAspects().setBooleanAspect("isRequired", false);
        }
        sd.getParameters().addFieldDefinition(machines);
        return sd;
    }

    private static ServiceDefinition svcGetMachineListingWithDates() {
        ServiceDefinition sd = new ServiceDefinition("GetMachineListingWithDates", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        FieldDefinition machines = FieldDefinition.createInfoTableFieldDefinition("Machines", "", "RootEntityList");
        if (machines.getAspects() != null) {
            machines.getAspects().setBooleanAspect("isRequired", false);
        }
        sd.getParameters().addFieldDefinition(machines);
        return sd;
    }

    private static ServiceDefinition svcGetAggregatesByUtilizationStateTimeFence() {
        ServiceDefinition sd = new ServiceDefinition("GetAggregatesByUtilizationStateTimeFence", "fixture");
        sd.getParameters().addFieldDefinition(new FieldDefinition("StartDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("EndDate", "", BaseTypes.DATETIME));
        sd.getParameters().addFieldDefinition(new FieldDefinition("ShiftID", "", BaseTypes.STRING));
        return sd;
    }
}
