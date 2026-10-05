package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * Shared list-class {@code TableBlock} reconstruction from TOOL JSON bodies (live downlink, stream sidecar,
 * {@link AgentMessageStreamHistoryExporter}).
 */
public final class ParlerToolTableWireUtil {

    private ParlerToolTableWireUtil() {}

    /**
     * @return first matching list-class table for known Parler table tools, or {@code null}
     */
    public static JSONObject tableBlockFromListClassToolJson(String body) {
        return tableBlockFromListClassToolJson(body, null);
    }

    /**
     * @param executedToolName when known (stream row / live carrier); passed only to invoke-shaped infotable wire
     * @return first matching list-class table for known Parler table tools, or {@code null}
     */
    public static JSONObject tableBlockFromListClassToolJson(String body, String executedToolName) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        JSONObject table = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(body);
        if (table == null) {
            table = ParlerAnalyzeEntitySetEntityListTableWire.tableBlockFromAnalyzeEntitySetToolSuccessJson(body);
        }
        if (table == null) {
            table = ParlerTaxonomyEntityListTableWire.tableBlockFromTaxonomyToolSuccessJson(body);
        }
        if (table == null) {
            table = ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(body);
        }
        if (table == null) {
            table = ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(body);
        }
        if (table == null) {
            table = ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(body, executedToolName);
        }
        if (table == null) {
            table = ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(body);
        }
        return table;
    }
}
