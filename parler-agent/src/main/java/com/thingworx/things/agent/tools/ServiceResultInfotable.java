package com.thingworx.things.agent.tools;

import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.InfoTablePrimitive;

/**
 * Normalizes {@link com.thingworx.entities.interfaces.IServiceProvider#processAPIServiceRequest} results when the
 * declared service result is {@link com.thingworx.types.BaseTypes#INFOTABLE}.
 *
 * <p>The platform's service reflection processor returns a Java {@link InfoTable} method result <b>as that
 * table</b>, not as a single wrapper row with a {@code result} column. Some stacks still use the wrapper shape.
 * This helper supports both.</p>
 */
public final class ServiceResultInfotable {

    private ServiceResultInfotable() {}

    /**
     * If the first row has {@code result} (or primitive wrapper) as an InfoTable, return that; otherwise return
     * {@code outer} (the service already returned the data table).
     */
    public static InfoTable extractInfotableResult(InfoTable outer) {
        if (outer == null || outer.getRowCount() == 0) {
            return outer;
        }
        ValueCollection row0 = outer.getRow(0);
        if (row0 == null) {
            return outer;
        }
        Object raw = row0.getValue("result");
        if (raw instanceof InfoTable) {
            return (InfoTable) raw;
        }
        if (raw instanceof InfoTablePrimitive) {
            return ((InfoTablePrimitive) raw).getValue();
        }
        return outer;
    }
}
