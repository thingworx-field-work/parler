package com.thingworx.things.agent.playbook;

/** Playbook identifiers and schema constants. */
public final class PlaybookIds {

    public static final String SCHEMA_V1 = "parler-playbook-v1";
    public static final String CROSS_REGION_HEALTH_ID = "cross_region_health";
    /** @deprecated Use {@link #CROSS_REGION_HEALTH_ID}. */
    @Deprecated
    public static final String V1A_PLAYBOOK_ID = CROSS_REGION_HEALTH_ID;
    /** V1b pair playbook id (distinct from skill {@code asset_pair_health}). */
    public static final String CROSS_ASSET_PAIR_HEALTH_ID = "cross_asset_pair_health";
    /** @deprecated Use {@link #CROSS_ASSET_PAIR_HEALTH_ID}. */
    @Deprecated
    public static final String ASSET_PAIR_HEALTH_ID = CROSS_ASSET_PAIR_HEALTH_ID;

    /** Repository root for playbook packages. */
    public static final String PLAYBOOK_ROOT = "/playbooks";
    /** Effective playbook document name inside each package directory. */
    public static final String PLAYBOOK_FILE_NAME = "playbook.json";
    /** Human-readable discovery glob (deterministic listing is by directory children). */
    public static final String DISCOVERY_PATTERN = "/playbooks/*/playbook.json";
    /** Maximum successfully loaded directory packages (re-homes legacy catalog row cap). */
    public static final int MAX_PACKAGED_PLAYBOOKS = 32;

    /**
     * @deprecated Legacy central catalog path; runtime discovery no longer reads this file. Retained for tests and
     *             migration references.
     */
    @Deprecated
    public static final String CATALOG_PATH = "/playbooks/playbooks.json";

    /** @deprecated Legacy flat document layout; use {@link #playbookPathForId(String)}. */
    @Deprecated
    public static final String CROSS_REGION_PLAYBOOK_PATH = "/playbooks/cross_region_health.playbook.json";
    /** @deprecated Use {@link #CROSS_REGION_PLAYBOOK_PATH}. */
    @Deprecated
    public static final String RUNTIME_PLAYBOOK_PATH = CROSS_REGION_PLAYBOOK_PATH;
    /** @deprecated Legacy flat document layout; use {@link #playbookPathForId(String)}. */
    @Deprecated
    public static final String CROSS_ASSET_PAIR_PLAYBOOK_PATH =
            "/playbooks/cross_asset_pair_health.playbook.json";
    /** @deprecated Use {@link #CROSS_ASSET_PAIR_PLAYBOOK_PATH}. */
    @Deprecated
    public static final String ASSET_PAIR_PLAYBOOK_PATH = CROSS_ASSET_PAIR_PLAYBOOK_PATH;

    private PlaybookIds() {}

    /** Effective repository path for one playbook id (directory package layout). */
    public static String playbookPathForId(String playbookId) {
        return PLAYBOOK_ROOT + "/" + playbookId + "/" + PLAYBOOK_FILE_NAME;
    }
}
