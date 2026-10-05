package com.thingworx.things.agent.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;
import com.thingworx.types.BaseTypes;

/**
 * Property names safe to request on implementing-things QIT projections (excludes PASSWORD on parent metadata).
 */
public final class TaxonomyPropertyProjection {

    private TaxonomyPropertyProjection() {}

    /** Identity fields safe to read from QIT rows (includes {@code name}). */
    public static List<String> safeIdentityFields(AssetTypeEntry type) {
        return safeIdentityFields(type, qitProjectionParent(type));
    }

    static List<String> safeIdentityFields(AssetTypeEntry type, RootEntity parent) {
        LinkedHashSet<String> projected = new LinkedHashSet<>();
        projected.add("name");
        projected.addAll(qitPropertyNamesForParent(type, parent));
        List<String> out = new ArrayList<>();
        for (String p : type.identityProperties()) {
            if (projected.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Critical property names safe to read from QIT rows or emit on identifier responses. */
    public static List<String> safeCriticalPropertyFields(AssetTypeEntry type) {
        return safeCriticalPropertyFields(type, qitProjectionParent(type));
    }

    static List<String> safeCriticalPropertyFields(AssetTypeEntry type, RootEntity parent) {
        LinkedHashSet<String> projected = new LinkedHashSet<>(qitPropertyNamesForParent(type, parent));
        List<String> out = new ArrayList<>();
        for (String p : type.criticalProperties()) {
            if (projected.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static List<String> qitPropertyNames(AssetTypeEntry type) {
        return qitPropertyNamesForParent(type, qitProjectionParent(type));
    }

    /** Package-visible for unit tests with a fixed parent (pass {@code null} when parent is unknown). */
    static List<String> qitPropertyNamesForParent(AssetTypeEntry type, RootEntity parent) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String p : type.identityProperties()) {
            if (p != null && !p.isBlank() && !"name".equals(p)) {
                names.add(p.trim());
            }
        }
        for (String p : type.criticalProperties()) {
            if (p != null && !p.isBlank() && !"name".equals(p)) {
                names.add(p.trim());
            }
        }
        // v3 synthetic rows use developer-reviewed identity-types.json; do not strip PASSWORD-typed
        // identity fields from QIT projection (bug 006 — they would never match in Java).
        if (isV3ZipSynthetic(type)) {
            return new ArrayList<>(names);
        }
        return filterPasswordOnParent(parent, names);
    }

    private static boolean isV3ZipSynthetic(AssetTypeEntry type) {
        String ek = type.entityKey();
        return ek != null && ek.startsWith("v3zip:");
    }

    private static List<String> filterPasswordOnParent(RootEntity parent, Set<String> propertyNames) {
        List<String> out = new ArrayList<>();
        for (String name : propertyNames) {
            if (!isPasswordOnParent(parent, name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static RootEntity qitProjectionParent(AssetTypeEntry type) {
        TaxonomyQueryParent qp = type.queryParent();
        if (qp != null && "ThingTemplate".equals(qp.entityType()) && !qp.entityName().isEmpty()) {
            return resolveParent(qp.entityType(), qp.entityName());
        }
        return resolveParent(type.parentEntityType(), type.parentEntityName());
    }

    private static RootEntity resolveParent(String entityType, String entityName) {
        if (entityName == null || entityName.isEmpty()) {
            return null;
        }
        RelationshipTypes.ThingworxRelationshipTypes rel = "ThingTemplate".equals(entityType)
                ? RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate
                : RelationshipTypes.ThingworxRelationshipTypes.ThingShape;
        return PlatformAccess.findForPolicyCheck(entityName, rel);
    }

    private static boolean isPasswordOnParent(RootEntity parent, String propertyName) {
        if (parent == null || propertyName == null || propertyName.isEmpty()) {
            return true;
        }
        if (!(parent instanceof IServiceProvider)) {
            return true;
        }
        try {
            Object coll = parent.getClass().getMethod("getInstancePropertyDefinitions").invoke(parent);
            if (coll == null) {
                return true;
            }
            Object vals = coll.getClass().getMethod("values").invoke(coll);
            if (!(vals instanceof Iterable)) {
                return true;
            }
            for (Object pd : (Iterable<?>) vals) {
                Object name = pd.getClass().getMethod("getName").invoke(pd);
                if (!propertyName.equals(name)) {
                    continue;
                }
                Object bt = pd.getClass().getMethod("getBaseType").invoke(pd);
                if (bt instanceof BaseTypes) {
                    return ProtectedValuePolicy.isProtectedBaseType((BaseTypes) bt);
                }
                return true;
            }
        } catch (Exception ignored) {
            return true;
        }
        return true;
    }
}
