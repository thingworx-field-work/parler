package com.thingworx.things.agent.hierarchy;

/**
 * Stable ThingWorx **service names** for the hierarchy Network override surface.
 *
 * <p><b>SoT:</b> {@code docs/architecture/hierarchy-network-services.md}. Implementations live on a customer
 * {@link com.thingworx.entities.interfaces.IServiceProvider} (Thing / ThingTemplate / ThingShape); Parler calls them
 * via {@link HierarchyNetworkServiceFacade} using {@code processAPIServiceRequest}.</p>
 *
 * <p><b>Do not</b> copy PTCTS JavaScript {@code GetConfiguredAssetNetworkName()} or ad-hoc
 * {@code GetConfigurationTable({tableName: ...})} patterns — effective {@code networkName} / description rules
 * are product-owned per {@code entity-hierarchy.md} §8.</p>
 */
public final class HierarchyNetworkServiceNames {

    /** Flattened node list: {@code name} = {@code NetworkID}, {@code description} per §8. */
    public static final String GET_FLATTEN_NAME_DESCRIPTION = "GetFlattenNameDescription";

    /** NL / UI display fragment → 0..n matching {@code NetworkID} rows. */
    public static final String RESOLVE_NETWORK_ID = "ResolveNetworkID";

    /** Current context root: 0 or 1 row. */
    public static final String GET_ROOT_NODE = "GetRootNode";

    /** {@code networkId} → related business Things (not child network nodes). */
    public static final String GET_ASSET_LIST = "GetAssetList";

    /** {@code networkId} → immediate child network nodes only. */
    public static final String GET_CHILD_NODES = "GetChildNodes";

    private HierarchyNetworkServiceNames() {}
}
