package com.thingworx.things.agent.tools;

import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * {@code GetPropertyDefinitions} / {@code GetLocalPropertyDefinitions} for the entity tools, invoked as the current
 * user. A service the user may not invoke yields nothing; there is no programmatic retry.
 */
final class PropertyDefinitionsService {

    private static final String[] SERVICES = {"GetPropertyDefinitions", "GetLocalPropertyDefinitions"};

    private PropertyDefinitionsService() {}

    static InfoTable fetch(Object entity) {
        if (!(entity instanceof IServiceProvider)) {
            return null;
        }
        IServiceProvider provider = (IServiceProvider) entity;
        for (String service : SERVICES) {
            try {
                InfoTable table = PlatformAccess.invokeAsUser(provider, service, new ValueCollection());
                if (table != null && table.getRowCount() > 0) {
                    return table;
                }
            } catch (Exception ignored) {
                // not implemented, not permitted, or empty: try the next service
            }
        }
        return null;
    }
}
