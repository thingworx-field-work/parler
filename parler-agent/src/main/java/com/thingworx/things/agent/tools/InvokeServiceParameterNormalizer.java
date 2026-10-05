package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;

/**
 * Moves service input fields mistakenly emitted at the {@code invoke_service} top level into
 * {@code parameters}, using only names declared on {@link ServiceDefinition#getParameters()}.
 */
public final class InvokeServiceParameterNormalizer {

    private static final Set<String> RESERVED_TOP_LEVEL = Set.of(
            "entityType", "entityName", "serviceName", "parameters");

    private InvokeServiceParameterNormalizer() {}

    /**
     * Result of {@link #mergeTopLevelServiceFieldsIntoRoot(ObjectNode, ServiceDefinition, ObjectMapper)}.
     */
    public static final class Repair {
        private static final Repair NONE = new Repair(Collections.emptyList());

        private final List<String> movedFromTopLevel;

        private Repair(List<String> movedFromTopLevel) {
            this.movedFromTopLevel = movedFromTopLevel;
        }

        public static Repair none() {
            return NONE;
        }

        /** True when at least one field was hoisted from the root into {@code parameters}. */
        public boolean isRepaired() {
            return !movedFromTopLevel.isEmpty();
        }

        /** Inverse of {@link #isRepaired()} — matches the binding guard style. */
        public boolean isEmpty() {
            return !isRepaired();
        }

        public List<String> getMovedFromTopLevel() {
            return movedFromTopLevel;
        }

        public void putMetadataIfPresent(ObjectNode target, ObjectMapper mapper) {
            if (!isRepaired()) {
                return;
            }
            ObjectNode meta = mapper.createObjectNode();
            meta.put("from", "topLevelServiceFields");
            var arr = mapper.createArrayNode();
            for (String m : movedFromTopLevel) {
                arr.add(m);
            }
            meta.set("moved", arr);
            target.set("parametersNormalized", meta);
        }
    }

    /**
     * Copies declared {@code parameters} object fields, then fills missing service keys from the root
     * when the root has a top-level key with the exact service parameter name (excluding reserved keys).
     * Mutates {@code root}: sets {@code parameters}, removes moved top-level keys. Nested {@code parameters}
     * values win over top-level duplicates.
     */
    public static Repair mergeTopLevelServiceFieldsIntoRoot(ObjectNode root, ServiceDefinition sd,
            ObjectMapper mapper) {
        if (root == null || sd == null) {
            return Repair.none();
        }
        return mergeTopLevelFromParameterDefs(root, sd.getParameters(), mapper);
    }

    /**
     * Package access for unit tests and callers that already hold a {@link FieldDefinitionCollection}.
     */
    static Repair mergeTopLevelFromParameterDefs(ObjectNode root, FieldDefinitionCollection defs,
            ObjectMapper mapper) {
        if (root == null || mapper == null) {
            return Repair.none();
        }
        if (defs == null || defs.values() == null) {
            return Repair.none();
        }
        Set<String> serviceParamNames = new LinkedHashSet<>();
        for (FieldDefinition fd : defs.values()) {
            if (fd != null && fd.getName() != null && !fd.getName().isEmpty()) {
                serviceParamNames.add(fd.getName());
            }
        }
        if (serviceParamNames.isEmpty()) {
            return Repair.none();
        }

        ObjectNode out = mapper.createObjectNode();
        JsonNode declared = root.get("parameters");
        if (declared != null && declared.isObject()) {
            Iterator<String> it = declared.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                out.set(k, declared.get(k));
            }
        }

        List<String> moved = new ArrayList<>();
        for (String name : serviceParamNames) {
            if (RESERVED_TOP_LEVEL.contains(name)) {
                continue;
            }
            if (out.has(name)) {
                continue;
            }
            if (!root.has(name) || root.get(name).isNull()) {
                continue;
            }
            out.set(name, root.get(name));
            moved.add(name);
        }

        for (String name : serviceParamNames) {
            if (RESERVED_TOP_LEVEL.contains(name)) {
                continue;
            }
            if (declared != null && declared.isObject() && declared.has(name) && root.has(name)) {
                root.remove(name);
            }
        }

        if (moved.isEmpty()) {
            return Repair.none();
        }

        root.set("parameters", out);
        for (String m : moved) {
            root.remove(m);
        }
        return new Repair(Collections.unmodifiableList(new ArrayList<>(moved)));
    }

    /** True when {@code name} is a reserved {@code invoke_service} argument key (case-sensitive). */
    public static boolean isReservedInvokeArgumentKey(String name) {
        if (name == null) {
            return false;
        }
        return RESERVED_TOP_LEVEL.contains(name);
    }
}
