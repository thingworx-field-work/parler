package com.thingworx.things.agent.tools;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import com.thingworx.things.Thing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.InfoTablePrimitive;
import com.thingworx.types.primitives.structs.VTQ;

/**
 * Current property values read with the current user's permissions only.
 *
 * <p>{@link Thing#getNamedPropertyValuesAsVTQInfoTable} checks the access modifier and PropertyRead for the current
 * security context with no System-user fallback, and leaves out every property the user may not read. A property
 * missing from the result is simply not returned; callers report it as unreadable and never retry through a more
 * permissive getter.
 */
public final class PropertyValueReads {

    private PropertyValueReads() {}

    public static IPrimitiveType readCurrentValue(Thing thing, String propertyName) throws Exception {
        return readCurrentValues(thing, java.util.List.of(propertyName)).get(propertyName);
    }

    /** @return readable values keyed by property name; unknown or unreadable properties are absent */
    public static Map<String, IPrimitiveType> readCurrentValues(Thing thing, Collection<String> propertyNames)
            throws Exception {
        HashSet<String> defined = new HashSet<>();
        for (String name : propertyNames) {
            if (name != null && !name.isEmpty() && thing.getInstancePropertyDefinition(name) != null) {
                defined.add(name);
            }
        }
        if (defined.isEmpty()) {
            return Map.of();
        }
        return valuesFromNamedVtqTable(thing.getNamedPropertyValuesAsVTQInfoTable(defined));
    }

    /** Unwraps the one-row result: each readable property is a field holding a value / time / quality table. */
    static Map<String, IPrimitiveType> valuesFromNamedVtqTable(InfoTable table) {
        Map<String, IPrimitiveType> out = new LinkedHashMap<>();
        if (table == null || table.getRowCount() == 0) {
            return out;
        }
        ValueCollection row = table.getRow(0);
        for (String name : row.keySet()) {
            Object cell = row.getPrimitive(name);
            if (!(cell instanceof InfoTablePrimitive)) {
                continue;
            }
            InfoTable vtq = ((InfoTablePrimitive) cell).getValue();
            if (vtq == null || vtq.getRowCount() == 0) {
                continue;
            }
            IPrimitiveType value = vtq.getRow(0).getPrimitive(VTQ.VALUE);
            if (value != null) {
                out.put(name, value);
            }
        }
        return out;
    }
}
