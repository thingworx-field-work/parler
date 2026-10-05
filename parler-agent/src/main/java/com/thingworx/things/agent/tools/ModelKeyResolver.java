package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Implements {@code docs/agent/key-resolution.md} §3 resolution for {@code query_entities} parent: **Phase −1 taxonomy
 * synonym is authoritative** — matches scan all taxonomy rows (not filtered by whether the model passed {@code
 * thingTemplate} vs {@code thingShape}); the matching row’s {@code EntityType} / {@code EntityName} wins. Then **Phase 0.5**
 * GenericThing incoming dependencies ({@link GenericThingIncomingDependencyResolver}); then Phase 1a /
 * 1b for non-taxonomy paths.
 *
 * <p>Normalization helpers live in {@link ModelKeyResolutionNormalize}. Typed-parent-only scope (no Phase 2 shape
 * fallback when template arg was wrong) is intentional for v1 — see Javadoc on {@link #resolveQueryEntitiesParent}.</p>
 */
public final class ModelKeyResolver {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ModelKeyResolver.class);

    static final int MAX_WILDCARD_ITEMS = 500;

    /**
     * Result of {@link #resolveQueryEntitiesParent(String, RelationshipTypes.ThingworxRelationshipTypes)}.
     *
     * <p>{@link #getReasonEnum()} is non-null for successful resolutions. When {@link #isTaxonomySynonymEntityMissing()},
     * taxonomy {@code Synonyms} matched but the row’s {@code EntityName} did not resolve — fix taxonomy data (do not fall
     * through to platform discovery). When {@link #isTaxonomyRowInvalidAfterSynonym()}, {@code Synonyms} matched but the row
     * could not be interpreted — callers surface {@code TAXONOMY_ROW_INVALID} using {@link #getTaxonomyRowInvalidDetail()}.</p>
     */
    public static final class ParentResolution {
        private final RootEntity entity;
        private final KeyResolutionReason reason;
        /** Platform relationship for QIT — always set when {@link #entity} is non-null. */
        private final RelationshipTypes.ThingworxRelationshipTypes effectiveRel;
        private final boolean taxonomySynonymEntityMissing;
        private final boolean taxonomyRowInvalidAfterSynonym;
        /** Non-null when {@link #taxonomyRowInvalidAfterSynonym} is true. */
        private final String taxonomyRowInvalidDetail;
        private final String taxonomyRowEntityType;
        private final String taxonomyRowEntityName;
        /** Phase 0.5: conflicting GenericThing dependency rows — caller surfaces {@code KEY_RESOLUTION_AMBIGUOUS}. */
        private final boolean modelKeyAmbiguous;
        /** Non-null when {@link #modelKeyAmbiguous}. */
        private final String modelKeyAmbiguityDetail;

        private ParentResolution(RootEntity entity, KeyResolutionReason reason,
                RelationshipTypes.ThingworxRelationshipTypes effectiveRel, boolean taxonomySynonymEntityMissing,
                boolean taxonomyRowInvalidAfterSynonym, String taxonomyRowInvalidDetail, String taxonomyRowEntityType,
                String taxonomyRowEntityName, boolean modelKeyAmbiguous, String modelKeyAmbiguityDetail) {
            this.entity = entity;
            this.reason = reason;
            this.effectiveRel = effectiveRel;
            this.taxonomySynonymEntityMissing = taxonomySynonymEntityMissing;
            this.taxonomyRowInvalidAfterSynonym = taxonomyRowInvalidAfterSynonym;
            this.taxonomyRowInvalidDetail = taxonomyRowInvalidDetail;
            this.taxonomyRowEntityType = taxonomyRowEntityType;
            this.taxonomyRowEntityName = taxonomyRowEntityName;
            this.modelKeyAmbiguous = modelKeyAmbiguous;
            this.modelKeyAmbiguityDetail = modelKeyAmbiguityDetail;
        }

        public static ParentResolution ok(RootEntity entity, KeyResolutionReason reason,
                RelationshipTypes.ThingworxRelationshipTypes effectiveRel) {
            return new ParentResolution(entity, reason, effectiveRel, false, false, null, null, null, false, null);
        }

        public static ParentResolution miss() {
            return new ParentResolution(null, null, null, false, false, null, null, null, false, null);
        }

        /**
         * Phase −1 matched {@code Synonyms} but {@code EntityName} did not resolve — taxonomy data bug; caller should
         * surface {@code TAXONOMY_ENTITY_UNRESOLVED}.
         */
        public static ParentResolution taxonomySynonymUnresolved(String rowEntityType, String rowEntityName) {
            return new ParentResolution(null, KeyResolutionReason.TAXONOMY_SYNONYM, null, true, false, null, rowEntityType,
                    rowEntityName, false, null);
        }

        /**
         * Phase −1 matched {@code Synonyms} but {@code EntityType} / {@code EntityName} are unusable — caller should surface
         * {@code TAXONOMY_ROW_INVALID} with {@code detail}.
         */
        public static ParentResolution taxonomyRowInvalidAfterSynonym(String detail, String rowEntityType,
                String rowEntityName) {
            return new ParentResolution(null, KeyResolutionReason.TAXONOMY_SYNONYM, null, false, true, detail,
                    rowEntityType, rowEntityName, false, null);
        }

        /** Phase 0.5 produced multiple equal-priority parent candidates — model must not guess silently. */
        public static ParentResolution ambiguousModelKey(String detail) {
            return new ParentResolution(null, null, null, false, false, null, null, null, true, detail);
        }

        public RootEntity getEntity() {
            return entity;
        }

        /**
         * Resolution path for telemetry / optional {@code keyResolution.reason}; serialize to wire with {@link Enum#name()}.
         */
        public KeyResolutionReason getReasonEnum() {
            return reason;
        }

        public RelationshipTypes.ThingworxRelationshipTypes getEffectiveRel() {
            return effectiveRel;
        }

        public boolean isTaxonomySynonymEntityMissing() {
            return taxonomySynonymEntityMissing;
        }

        public boolean isTaxonomyRowInvalidAfterSynonym() {
            return taxonomyRowInvalidAfterSynonym;
        }

        public String getTaxonomyRowInvalidDetail() {
            return taxonomyRowInvalidDetail;
        }

        public String getTaxonomyRowEntityType() {
            return taxonomyRowEntityType;
        }

        public String getTaxonomyRowEntityName() {
            return taxonomyRowEntityName;
        }

        public boolean isModelKeyAmbiguous() {
            return modelKeyAmbiguous;
        }

        public String getModelKeyAmbiguityDetail() {
            return modelKeyAmbiguityDetail;
        }
    }

    private ModelKeyResolver() {}

    /**
     * Resolves the parent {@link RootEntity} for {@code query_entities}. {@code callerRel} is used only when Phase −1 does
     * not apply — taxonomy synonym hits ignore it and use the row’s {@code EntityType}.
     */
    public static ParentResolution resolveQueryEntitiesParent(String rawKey,
            RelationshipTypes.ThingworxRelationshipTypes callerRel) throws Exception {
        if (rawKey == null || rawKey.trim().isEmpty()) {
            return ParentResolution.miss();
        }
        String trimmed = rawKey.trim();
        String normalizedUser = ModelKeyResolutionNormalize.normalizePhase0(trimmed);

        AgentThing agent = AgentToolContext.getAgentThing();
        com.thingworx.things.agent.PromptContextCacheSnapshot snap =
                agent != null ? agent.getPromptContextSnapshot() : null;

        if (snap != null && snap.getTaxonomyRows() != null && !snap.getTaxonomyRows().isEmpty()) {
            ParentResolution tax = tryTaxonomySynonymCached(trimmed, normalizedUser, snap.getTaxonomyRows());
            if (tax != null) {
                return tax;
            }
        }

        ParentResolution phase05;
        if (snap != null && snap.getGenericThingTemplateNames() != null
                && !snap.getGenericThingTemplateNames().isEmpty()) {
            phase05 = tryGenericThingWithRows(trimmed, normalizedUser,
                    IncomingDependencyMatcher.rowsFromCachedThingTemplateNames(snap.getGenericThingTemplateNames()));
        } else if (snap == null) {
            phase05 = tryGenericThingIncomingDependencyLive(trimmed, normalizedUser);
        } else {
            phase05 = null;
        }
        if (phase05 != null) {
            return phase05;
        }

        RootEntity r1 = PlatformAccess.findAsUser(trimmed, callerRel);
        if (r1 != null) {
            return ParentResolution.ok(r1, KeyResolutionReason.EXACT_RAW_LOOKUP, callerRel);
        }

        String canonical = ModelKeyResolutionNormalize.canonicalTemplateHint(normalizedUser);
        if (canonical != null) {
            RootEntity r2 = PlatformAccess.findAsUser(canonical, callerRel);
            if (r2 != null) {
                return ParentResolution.ok(r2, KeyResolutionReason.HINT_TABLE, callerRel);
            }
        }

        if (!normalizedUser.isEmpty()) {
            String capitalized =
                    Character.toUpperCase(normalizedUser.charAt(0)) + normalizedUser.substring(1);
            RootEntity r3 = PlatformAccess.findAsUser(capitalized, callerRel);
            if (r3 != null) {
                return ParentResolution.ok(r3, KeyResolutionReason.CAPITALIZE_NORMALIZED, callerRel);
            }
        }

        RootEntity wild = wildcardResolve(trimmed, normalizedUser, callerRel);
        if (wild != null) {
            return ParentResolution.ok(wild, KeyResolutionReason.WILDCARD_GET_ENTITY_LIST, callerRel);
        }
        return ParentResolution.miss();
    }

    /** Live GenericThing fetch when prompt-context snapshot is absent. */
    private static ParentResolution tryGenericThingIncomingDependencyLive(String trimmed, String normalizedUser) {
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent != null) {
            agent.noteResolverFallbackGenericThing();
        }
        return tryGenericThingWithRows(trimmed, normalizedUser,
                GenericThingIncomingDependencyResolver.fetchDependencyRowsOrEmpty());
    }

    /** Phase 0.5 — taxonomy must have missed prior to invoking this path. */
    private static ParentResolution tryGenericThingWithRows(String trimmed, String normalizedUser,
            List<IncomingDependencyMatcher.Row> rows) {
        try {
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            IncomingDependencyMatcher.MatchOutcome mo =
                    IncomingDependencyMatcher.matchParent(rows, trimmed, normalizedUser);
            if (mo.kind == IncomingDependencyMatcher.MatchOutcome.Kind.MISS) {
                return null;
            }
            if (mo.kind == IncomingDependencyMatcher.MatchOutcome.Kind.AMBIGUOUS) {
                return ParentResolution.ambiguousModelKey(mo.ambiguityDetail);
            }
            if (mo.resolvedRel == null || mo.resolvedName == null) {
                return null;
            }
            RootEntity ent = PlatformAccess.findAsUser(mo.resolvedName, mo.resolvedRel);
            if (ent == null) {
                return null;
            }
            return ParentResolution.ok(ent, KeyResolutionReason.GENERIC_THING_INCOMING_DEPENDENCY, mo.resolvedRel);
        } catch (Exception e) {
            LOG.debug("ModelKeyResolver Phase 0.5: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Phase −1 using cached {@link TaxonomyRow} list (prompt-context snapshot).
     */
    private static ParentResolution tryTaxonomySynonymCached(String trimmedRaw, String normalizedUser,
            List<TaxonomyRow> rows) throws Exception {
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            TaxonomyRow row = rows.get(i);
            if (row == null) {
                continue;
            }
            if (!row.synonymsMatchNormalizedUser(normalizedUser)) {
                continue;
            }
            String entityType = row.getEntityType();
            String entityName = row.getEntityName();
            String rowInvalid = ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail(trimmedRaw, entityType,
                    entityName);
            if (rowInvalid != null) {
                LOG.warn("ModelKeyResolver: taxonomy row {} — {}", i, rowInvalid);
                return ParentResolution.taxonomyRowInvalidAfterSynonym(rowInvalid, entityType, entityName);
            }
            RelationshipTypes.ThingworxRelationshipTypes rowRel =
                    ModelKeyResolutionNormalize.parseTaxonomyEntityType(entityType);
            RootEntity ent = PlatformAccess.findAsUser(entityName.trim(), rowRel);
            if (ent != null) {
                LOG.debug(
                        "ModelKeyResolver: taxonomy synonym matched input \"{}\" -> {} \"{}\" (cached taxonomy rows)",
                        trimmedRaw, entityType, entityName);
                return ParentResolution.ok(ent, KeyResolutionReason.TAXONOMY_SYNONYM, rowRel);
            }
            LOG.warn(
                    "ModelKeyResolver: taxonomy Synonyms matched \"{}\" but EntityName \"{}\" ({}) did not resolve — not falling through",
                    trimmedRaw, entityName, entityType);
            return ParentResolution.taxonomySynonymUnresolved(entityType, entityName.trim());
        }
        return null;
    }

    private static RootEntity wildcardResolve(String trimmedRaw, String normalizedUser,
            RelationshipTypes.ThingworxRelationshipTypes rel) throws Exception {
        RootEntity es = PlatformAccess.findAsUser(ListEntitiesByTypeExecutor.ENTITY_SERVICES_NAME,
                RelationshipTypes.ThingworxRelationshipTypes.Resource);
        if (es == null || !(es instanceof IServiceProvider)) {
            return null;
        }
        IServiceProvider provider = (IServiceProvider) es;
        ServiceDefinition sd = provider.getInstanceServiceDefinition(ListEntitiesByTypeExecutor.SERVICE_GET_LIST);
        if (sd == null) {
            return null;
        }
        String collectionType = rel.name();
        String mask = "*" + trimmedRaw + "*";
        ValueCollection params =
                buildMinimalGetEntityListParams(sd, collectionType, mask, (double) MAX_WILDCARD_ITEMS);
        InfoTable outer = provider.processAPIServiceRequest(ListEntitiesByTypeExecutor.SERVICE_GET_LIST, params);
        InfoTable data = ServiceResultInfotable.extractInfotableResult(outer);
        if (data == null || data.getRowCount() == 0) {
            return null;
        }
        List<String> cols = columnNames(data);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < data.getRowCount(); i++) {
            ValueCollection row = data.getRow(i);
            if (row == null) {
                continue;
            }
            String name = firstString(row, cols, "name", "entityName", "EntityName");
            if (name == null || name.isEmpty()) {
                continue;
            }
            int rank = ModelKeyResolutionNormalize.rankCandidate(name, trimmedRaw, normalizedUser);
            if (rank <= 4) {
                scored.add(new Scored(rank, name));
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.rank).thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));
        String best = scored.get(0).name;
        return PlatformAccess.findAsUser(best, rel);
    }

    private static final class Scored {
        final int rank;
        final String name;

        Scored(int rank, String name) {
            this.rank = rank;
            this.name = name;
        }
    }

    private static ValueCollection buildMinimalGetEntityListParams(ServiceDefinition sd, String collectionType,
            String nameMask, double maxItemsD) throws Exception {
        ValueCollection vc = new ValueCollection();
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return vc;
        }
        boolean typeSet = false;
        boolean maskSet = false;
        boolean maxSet = false;
        boolean tagsSet = false;
        for (FieldDefinition fd : defs.values()) {
            String n = fd.getName();
            if (n == null) {
                continue;
            }
            String nl = n.toLowerCase(Locale.ROOT);
            BaseTypes bt = fd.getBaseType() != null ? fd.getBaseType() : BaseTypes.STRING;
            if (nl.equals("type") && (bt == BaseTypes.STRING || bt == BaseTypes.TEXT)) {
                vc.put(n, new StringPrimitive(collectionType));
                typeSet = true;
                continue;
            }
            if (nl.contains("namemask") && (bt == BaseTypes.STRING || bt == BaseTypes.TEXT)) {
                vc.put(n, new StringPrimitive(nameMask != null ? nameMask : ""));
                maskSet = true;
                continue;
            }
            if (nl.contains("max") && nl.contains("item") && bt == BaseTypes.NUMBER) {
                vc.put(n, new NumberPrimitive(maxItemsD));
                maxSet = true;
                continue;
            }
            if (bt == BaseTypes.TAGS) {
                vc.put(n, TagJsonCodec.parseTagCollectionPrimitive(null));
                tagsSet = true;
            }
        }
        if (!typeSet) {
            tryPutStringParam(vc, defs, "type", collectionType);
        }
        if (!maskSet) {
            tryPutStringParam(vc, defs, "nameMask", nameMask != null ? nameMask : "");
        }
        if (!maxSet) {
            tryPutNumberParam(vc, defs, "maxItems", maxItemsD);
        }
        if (!tagsSet) {
            tryPutEmptyTags(vc, defs);
        }
        return vc;
    }

    private static void tryPutStringParam(ValueCollection vc, FieldDefinitionCollection defs, String want, String value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName())
                    && (fd.getBaseType() == BaseTypes.STRING || fd.getBaseType() == BaseTypes.TEXT)) {
                try {
                    vc.put(fd.getName(), new StringPrimitive(value != null ? value : ""));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutNumberParam(ValueCollection vc, FieldDefinitionCollection defs, String want, double value) {
        for (FieldDefinition fd : defs.values()) {
            if (want.equalsIgnoreCase(fd.getName()) && fd.getBaseType() == BaseTypes.NUMBER) {
                try {
                    vc.put(fd.getName(), new NumberPrimitive(value));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static void tryPutEmptyTags(ValueCollection vc, FieldDefinitionCollection defs) {
        for (FieldDefinition fd : defs.values()) {
            if (fd.getBaseType() == BaseTypes.TAGS) {
                try {
                    vc.put(fd.getName(), TagJsonCodec.parseTagCollectionPrimitive(null));
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
    }

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        if (it == null) {
            return names;
        }
        try {
            if (it.getDataShape() != null && it.getDataShape().getFields() != null) {
                for (FieldDefinition f : it.getDataShape().getFields().values()) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    names.addAll(row.keySet());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return names;
    }

    private static String firstString(ValueCollection row, List<String> cols, String... preferredNames) {
        for (String pref : preferredNames) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(pref)) {
                    Object v = row.getValue(c);
                    String s = primitiveToString(v);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private static String primitiveToString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof IPrimitiveType) {
            try {
                Object inner = ((IPrimitiveType) v).getValue();
                return inner != null ? inner.toString() : null;
            } catch (Exception e) {
                return v.toString();
            }
        }
        return v.toString();
    }
}
