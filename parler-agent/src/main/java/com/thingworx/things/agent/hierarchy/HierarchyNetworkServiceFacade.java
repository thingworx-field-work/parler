package com.thingworx.things.agent.hierarchy;

import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Thin **in-process** invoker for the five hierarchy Network services.
 *
 * <p>Callers supply an {@link IServiceProvider} that **already** hosts override-friendly implementations
 * (effective Thing). This class **only** builds {@link ValueCollection} inputs and delegates to
 * {@link IServiceProvider#processAPIServiceRequest(String, ValueCollection)} — it does **not** resolve
 * {@code networkName} from PTCTS-specific configuration tables.</p>
 *
 * <p><b>SoT:</b> {@code docs/architecture/hierarchy-network-services.md}.</p>
 *
 * <p>{@link #resolveNetworkId}, {@link #getAssetList}, and {@link #getChildNodes} throw {@link IllegalArgumentException}
 * when {@code name} / {@code id} is {@code null} or empty after trim — do not rely on silent empty-string fallthrough.</p>
 */
public final class HierarchyNetworkServiceFacade {

    public static final String NAME_PARAM = "name";
    public static final String ID_PARAM = "id";

    private HierarchyNetworkServiceFacade() {}

    /** @see HierarchyNetworkServiceNames#GET_FLATTEN_NAME_DESCRIPTION */
    public static InfoTable getFlattenNameDescription(IServiceProvider provider) throws Exception {
        return provider.processAPIServiceRequest(HierarchyNetworkServiceNames.GET_FLATTEN_NAME_DESCRIPTION,
                new ValueCollection());
    }

    /**
     * @param name hierarchy node display name
     * @see HierarchyNetworkServiceNames#RESOLVE_NETWORK_ID
     */
    public static InfoTable resolveNetworkId(IServiceProvider provider, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("ResolveNetworkID: name must be non-null and non-blank.");
        }
        ValueCollection params = new ValueCollection();
        params.put(NAME_PARAM, new StringPrimitive(name));
        return provider.processAPIServiceRequest(HierarchyNetworkServiceNames.RESOLVE_NETWORK_ID, params);
    }

    /** @see HierarchyNetworkServiceNames#GET_ROOT_NODE */
    public static InfoTable getRootNode(IServiceProvider provider) throws Exception {
        return provider.processAPIServiceRequest(HierarchyNetworkServiceNames.GET_ROOT_NODE, new ValueCollection());
    }

    /**
     * @param id stable node id (typically the node Thing {@code name}; see hierarchy-network-services.md §1)
     * @see HierarchyNetworkServiceNames#GET_ASSET_LIST
     */
    public static InfoTable getAssetList(IServiceProvider provider, String id) throws Exception {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("GetAssetList: id must be non-null and non-blank.");
        }
        ValueCollection params = new ValueCollection();
        params.put(ID_PARAM, new StringPrimitive(id));
        return provider.processAPIServiceRequest(HierarchyNetworkServiceNames.GET_ASSET_LIST, params);
    }

    /**
     * @param id parent node {@code NetworkID}
     * @see HierarchyNetworkServiceNames#GET_CHILD_NODES
     */
    public static InfoTable getChildNodes(IServiceProvider provider, String id) throws Exception {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("GetChildNodes: id must be non-null and non-blank.");
        }
        ValueCollection params = new ValueCollection();
        params.put(ID_PARAM, new StringPrimitive(id));
        return provider.processAPIServiceRequest(HierarchyNetworkServiceNames.GET_CHILD_NODES, params);
    }
}
