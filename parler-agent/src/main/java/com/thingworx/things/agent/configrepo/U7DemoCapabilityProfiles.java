package com.thingworx.things.agent.configrepo;

import java.util.List;

/**
 * Demo App capability surface for U7 SPR-5 matrix. Discovery / fixture helpers only — does not
 * open caches, advertise always-on resident tools, or execute live mutating Services.
 *
 * <p>Descriptors mirror App {@code extended_tools.json} entries: one READ_ONLY read Service and
 * one MUTATING + HITL + dry-run capability (live effects avoided via dry-run enforce).
 */
public final class U7DemoCapabilityProfiles {

    public static final String READ_TOOL = "u7_demo_read_equipment_status";
    public static final String MUTATING_TOOL = "u7_demo_create_maintenance_work_order";

    public static final String READ_THING = "U7DemoPlantEquipment";
    public static final String READ_SERVICE = "GetEquipmentStatus";

    public static final String MUTATING_THING = "U7DemoPlantCmms";
    public static final String MUTATING_SERVICE = "CreateWorkOrder";

    private U7DemoCapabilityProfiles() {}

    /** Model-advertised U7 demo operations — empty until a measured R11 admits any. */
    public static List<String> advertisedOperations() {
        return List.of();
    }

    /**
     * Fixture {@code extended_tools.json} body (version 1) with the two SPR-5 demo capabilities.
     * Parameter/ServiceDefinition parity is validated by tests with a stub lookup.
     */
    public static String extendedToolsFixtureJson() {
        return "{"
                + "\"version\":1,"
                + "\"tools\":["
                + "{"
                + "\"name\":\"" + READ_TOOL + "\","
                + "\"title\":\"Read equipment status\","
                + "\"whenToUse\":\"Read current demo equipment status (READ_ONLY).\","
                + "\"target\":{\"entityName\":\"" + READ_THING + "\",\"serviceName\":\"" + READ_SERVICE + "\"},"
                + "\"hitl\":false,"
                + "\"playbookSafe\":true,"
                + "\"risk\":\"READ_ONLY\","
                + "\"purpose\":\"Return current equipment status for the U7 demo App.\","
                + "\"dataClassification\":[\"INTERNAL\"],"
                + "\"admission\":\"LAZY\","
                + "\"enabled\":true,"
                + "\"idempotency\":{\"mode\":\"NONE\"}"
                + "},"
                + "{"
                + "\"name\":\"" + MUTATING_TOOL + "\","
                + "\"title\":\"Create maintenance work order\","
                + "\"whenToUse\":\"Propose a demo CMMS work order (MUTATING; dry-run by default).\","
                + "\"target\":{\"entityName\":\"" + MUTATING_THING + "\",\"serviceName\":\""
                + MUTATING_SERVICE + "\"},"
                + "\"hitl\":true,"
                + "\"playbookSafe\":false,"
                + "\"risk\":\"MUTATING\","
                + "\"purpose\":\"Create one governed demo CMMS work order through ThingWorx.\","
                + "\"dryRun\":{\"supported\":true,\"parameter\":\"dryRun\"},"
                + "\"idempotency\":{\"mode\":\"CALLER_KEY\",\"parameter\":\"requestId\"},"
                + "\"dataClassification\":[\"INTERNAL\"],"
                + "\"admission\":\"LAZY\","
                + "\"enabled\":true"
                + "}"
                + "]"
                + "}";
    }

    /** Minimal route-profile fixture for the SPR-5 Provider matrix (no credentials/endpoints). */
    public static String routeProfilesFixtureJson() {
        return "{"
                + "\"profiles\":[{"
                + "\"id\":\"u7-demo-primary-fallback\","
                + "\"providers\":[\"U7DemoPrimaryProvider\",\"U7DemoFallbackProvider\"],"
                + "\"requiredCapabilities\":[\"TOOLS\",\"DATA_EGRESS_REGION\"],"
                + "\"allowedDataClassifications\":[\"INTERNAL\",\"PUBLIC\"],"
                + "\"maxSameProviderRetries\":1,"
                + "\"maxProvidersTried\":2,"
                + "\"maxTotalAttempts\":4,"
                + "\"maxCumulativeWaitMs\":60000,"
                + "\"maxWallTimeMs\":120000,"
                + "\"qualityTier\":\"APPROVED_INTERACTIVE\","
                + "\"fallbackVisible\":true"
                + "}]}";
    }
}
