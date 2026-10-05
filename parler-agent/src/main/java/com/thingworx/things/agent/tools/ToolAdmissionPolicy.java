package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * The deterministic {@code narrow} admission engine
 * (docs/operations/tool-schema-admission-control.md §2.3–§2.4, §3.1). Pure: given a merged tool list and the turn's
 * {@link ToolAdmissionSignals}, it returns the admitted subset plus the audit detail for the {@code TOOL_ADMISSION}
 * log. No I/O, no ThreadLocals — the live signal resolution and logging live in the agent wiring.
 *
 * <p>Model (§2.3): the <em>operational base</em> buckets (identity, entity-set, current-values/trends, alerts) and
 * {@link ToolBucket#OTHER} (unknown/deployment-specific tools) are always admitted in {@code narrow}; the
 * <em>gated</em> buckets (documents, utilization, metadata-exploration, skills/playbooks) are admitted only when a
 * deterministic signal selects them. The <em>core set</em> (resolve_thing + conditional taxonomy/skill/playbook entry
 * points) and any host-context {@code requiredTools}/{@code requiredBuckets} (§2.5) are always admitted.
 */
public final class ToolAdmissionPolicy {

    static final String CORE_RESOLVE_THING = "resolve_thing";
    static final String CORE_RESOLVE_ASSET_TYPE = "resolve_asset_type";
    static final String CORE_LIST_ASSET_TYPES = "list_asset_types";
    static final String CORE_GET_AGENT_SKILL = "get_agent_skill";
    static final String CORE_START_PLAYBOOK = "start_playbook";

    /** Buckets always advertised in {@code narrow} (the operational workhorses for operator Q&A). */
    static final Set<ToolBucket> OPERATIONAL_BASE = Collections.unmodifiableSet(EnumSet.of(
            ToolBucket.IDENTITY_ROUTING,
            ToolBucket.ENTITY_SET_QUERY,
            ToolBucket.CURRENT_VALUES_TRENDS,
            ToolBucket.ALERTS));

    private ToolAdmissionPolicy() {}

    /** Result of a {@code narrow} pass: the admitted subset plus audit detail for logging. */
    public static final class Result {
        private final List<ToolDefinition> admitted;
        private final Set<ToolBucket> admittedBuckets;
        private final Set<ToolBucket> droppedBuckets;
        private final List<String> droppedToolNames;
        private final int toolsBefore;
        private final boolean reverted;

        Result(List<ToolDefinition> admitted, Set<ToolBucket> admittedBuckets, Set<ToolBucket> droppedBuckets,
                List<String> droppedToolNames, int toolsBefore, boolean reverted) {
            this.admitted = admitted;
            this.admittedBuckets = admittedBuckets;
            this.droppedBuckets = droppedBuckets;
            this.droppedToolNames = droppedToolNames;
            this.toolsBefore = toolsBefore;
            this.reverted = reverted;
        }

        public List<ToolDefinition> admitted() {
            return admitted;
        }

        public Set<ToolBucket> admittedBuckets() {
            return admittedBuckets;
        }

        public Set<ToolBucket> droppedBuckets() {
            return droppedBuckets;
        }

        public List<String> droppedToolNames() {
            return droppedToolNames;
        }

        public int toolsBefore() {
            return toolsBefore;
        }

        public int toolsAfter() {
            return admitted.size();
        }

        /** True when narrowing would have dropped everything and the full set was kept as a safety fallback. */
        public boolean reverted() {
            return reverted;
        }
    }

    /**
     * Apply {@code narrow} admission. Never returns null; if narrowing would empty the list, the full {@code merged}
     * set is returned unchanged (with {@link Result#reverted()} true) so a turn is never left tool-less.
     */
    public static Result narrow(List<ToolDefinition> merged, ToolAdmissionSignals signals) {
        if (merged == null || merged.isEmpty()) {
            return new Result(merged != null ? merged : Collections.emptyList(),
                    Collections.emptySet(), Collections.emptySet(), Collections.emptyList(), 0, false);
        }
        Set<ToolBucket> admittedBuckets = EnumSet.copyOf(OPERATIONAL_BASE);
        admittedBuckets.add(ToolBucket.OTHER);
        admittedBuckets.addAll(signals.requiredBuckets());
        if (documentSignal(signals)) {
            admittedBuckets.add(ToolBucket.DOCUMENTS);
        }
        if (utilizationSignal(signals)) {
            admittedBuckets.add(ToolBucket.UTILIZATION);
        }
        Set<String> core = coreToolNames(signals);

        List<ToolDefinition> admitted = new ArrayList<>();
        Set<ToolBucket> bucketsInMerged = EnumSet.noneOf(ToolBucket.class);
        Set<ToolBucket> admittedBucketsSeen = EnumSet.noneOf(ToolBucket.class);
        TreeSet<String> dropped = new TreeSet<>();
        for (ToolDefinition td : merged) {
            if (td == null) {
                continue;
            }
            String name = td.getName();
            ToolBucket bucket = ToolBuckets.bucketOf(name);
            bucketsInMerged.add(bucket);
            boolean admit = (name != null && core.contains(name))
                    || (name != null && signals.requiredTools().contains(name))
                    || admittedBuckets.contains(bucket);
            if (admit) {
                admitted.add(td);
                admittedBucketsSeen.add(bucket);
            } else {
                dropped.add(name != null ? name : "");
            }
        }
        if (admitted.isEmpty()) {
            return new Result(merged, bucketsInMerged, Collections.emptySet(), Collections.emptyList(),
                    merged.size(), true);
        }
        Set<ToolBucket> droppedBuckets = EnumSet.noneOf(ToolBucket.class);
        droppedBuckets.addAll(bucketsInMerged);
        droppedBuckets.removeAll(admittedBucketsSeen);
        return new Result(admitted,
                unmodifiable(admittedBucketsSeen),
                unmodifiable(droppedBuckets),
                Collections.unmodifiableList(new ArrayList<>(dropped)),
                merged.size(),
                false);
    }

    /** Max characters of the {@code load_tool_schemas} catalog text (so {@code lazy} cannot itself overflow the cap). */
    public static final int LAZY_CATALOG_MAX_CHARS = 8000;

    /** Max characters of a single catalog tool's blurb (name + whenToUse). */
    static final int LAZY_CATALOG_BLURB_MAX_CHARS = 160;

    /** One deferred tool in the {@code lazy} catalog: its name + a short whenToUse blurb. */
    public static final class CatalogEntry {
        private final String name;
        private final String blurb;

        CatalogEntry(String name, String blurb) {
            this.name = name;
            this.blurb = blurb;
        }

        public String name() {
            return name;
        }

        public String blurb() {
            return blurb;
        }
    }

    /** Result of a {@code lazy} pass: the tools advertised with full schemas now, and the deferred catalog. */
    public static final class LazyResult {
        private final List<ToolDefinition> advertised;
        private final List<CatalogEntry> catalog;
        private final int toolsBefore;

        LazyResult(List<ToolDefinition> advertised, List<CatalogEntry> catalog, int toolsBefore) {
            this.advertised = advertised;
            this.catalog = catalog;
            this.toolsBefore = toolsBefore;
        }

        /** Tools advertised with full schema this round (core + required + already-registered). */
        public List<ToolDefinition> advertised() {
            return advertised;
        }

        /** Deferred tools, advertised only as name + whenToUse in the meta-tool catalog. */
        public List<CatalogEntry> catalog() {
            return catalog;
        }

        public int toolsBefore() {
            return toolsBefore;
        }
    }

    /**
     * Apply {@code lazy} admission: advertise only the core set + host-context-required tools/buckets + any tools the
     * model has already loaded this turn via {@code load_tool_schemas} ({@code registered}); everything else becomes a
     * deferred catalog entry. Pure — the caller builds the {@code load_tool_schemas} meta-tool from the catalog and
     * appends it.
     */
    public static LazyResult lazy(List<ToolDefinition> merged, ToolAdmissionSignals signals, Set<String> registered) {
        if (merged == null || merged.isEmpty()) {
            return new LazyResult(merged != null ? merged : Collections.emptyList(),
                    Collections.emptyList(), 0);
        }
        Set<String> core = coreToolNames(signals);
        Set<ToolBucket> requiredBuckets = signals.requiredBuckets();
        Set<String> requiredTools = signals.requiredTools();
        Set<String> reg = registered != null ? registered : Collections.emptySet();

        List<ToolDefinition> advertised = new ArrayList<>();
        List<CatalogEntry> catalog = new ArrayList<>();
        for (ToolDefinition td : merged) {
            if (td == null) {
                continue;
            }
            String name = td.getName();
            boolean advertise = (name != null)
                    && (core.contains(name)
                        || requiredTools.contains(name)
                        || requiredBuckets.contains(ToolBuckets.bucketOf(name))
                        || reg.contains(name));
            if (advertise) {
                advertised.add(td);
            } else {
                catalog.add(new CatalogEntry(name != null ? name : "", blurb(td)));
            }
        }
        return new LazyResult(advertised, catalog, merged.size());
    }

    private static String blurb(ToolDefinition td) {
        String d = td.getDescription() != null ? td.getDescription().strip().replace('\n', ' ') : "";
        if (d.length() > LAZY_CATALOG_BLURB_MAX_CHARS) {
            d = d.substring(0, LAZY_CATALOG_BLURB_MAX_CHARS - 1).strip() + "…";
        }
        return d;
    }

    /**
     * Renders the deferred catalog as {@code name: whenToUse} lines, capped at {@link #LAZY_CATALOG_MAX_CHARS} so the
     * meta-tool description cannot itself overflow the request cap. Truncation is noted with a trailing marker.
     */
    public static String renderCatalog(List<CatalogEntry> catalog) {
        StringBuilder sb = new StringBuilder();
        int omitted = 0;
        for (CatalogEntry e : catalog) {
            String line = "- " + e.name() + ": " + e.blurb() + "\n";
            if (sb.length() + line.length() > LAZY_CATALOG_MAX_CHARS) {
                omitted++;
                continue;
            }
            sb.append(line);
        }
        if (omitted > 0) {
            sb.append("- … (").append(omitted).append(" more tools omitted; load by name if needed)\n");
        }
        return sb.toString();
    }

    /** The always-advertised core: resolve_thing plus the conditional taxonomy/skill/playbook entry points (§2.3). */
    static Set<String> coreToolNames(ToolAdmissionSignals signals) {
        Set<String> core = new LinkedHashSet<>();
        core.add(CORE_RESOLVE_THING);
        if (signals.taxonomyReady()) {
            core.add(CORE_RESOLVE_ASSET_TYPE);
            core.add(CORE_LIST_ASSET_TYPES);
        }
        if (signals.loadedSkills()) {
            core.add(CORE_GET_AGENT_SKILL);
        }
        if (signals.loadedPlaybook()) {
            core.add(CORE_START_PLAYBOOK);
        }
        return core;
    }

    static boolean documentSignal(ToolAdmissionSignals s) {
        return s.slashActive()
                || s.documentScopeActive()
                || s.hostKeyContains("document")
                || s.requiredBuckets().contains(ToolBucket.DOCUMENTS);
    }

    static boolean utilizationSignal(ToolAdmissionSignals s) {
        return s.hostKeyContains("utilization")
                || s.requiredBuckets().contains(ToolBucket.UTILIZATION);
    }

    private static Set<ToolBucket> unmodifiable(Set<ToolBucket> in) {
        return in.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(in));
    }

    /**
     * Builds the {@code TOOL_ADMISSION} audit line so a live debugger can see <em>why</em> a bucket disappeared
     * (§3 M2). {@code beforeChars}/{@code afterChars} are the serialized tool-schema sizes (negative = omitted).
     */
    public static String formatDecisionLine(String agentName, String mode, Result r,
            int beforeChars, int afterChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("TOOL_ADMISSION agent=").append(agentName != null ? agentName : "");
        sb.append(" mode=").append(mode != null ? mode : "");
        sb.append(" admittedBuckets=").append(joinBuckets(r.admittedBuckets()));
        sb.append(" droppedBuckets=").append(joinBuckets(r.droppedBuckets()));
        sb.append(" toolsBefore=").append(r.toolsBefore());
        sb.append(" toolsAfter=").append(r.toolsAfter());
        if (beforeChars >= 0) {
            sb.append(" toolSchemaCharsBefore=").append(beforeChars);
        }
        if (afterChars >= 0) {
            sb.append(" toolSchemaCharsAfter=").append(afterChars);
        }
        sb.append(" droppedTools=").append(String.join(",", r.droppedToolNames()));
        if (r.reverted()) {
            sb.append(" reverted=1");
        }
        return sb.toString();
    }

    /** Builds the {@code TOOL_ADMISSION mode=lazy} audit line (M3). Negative char counts are omitted. */
    public static String formatLazyDecisionLine(String agentName, LazyResult r, int registeredCount,
            int beforeChars, int afterChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("TOOL_ADMISSION agent=").append(agentName != null ? agentName : "");
        sb.append(" mode=lazy");
        sb.append(" toolsBefore=").append(r.toolsBefore());
        sb.append(" advertised=").append(r.advertised().size());
        sb.append(" catalog=").append(r.catalog().size());
        sb.append(" registered=").append(Math.max(0, registeredCount));
        if (beforeChars >= 0) {
            sb.append(" toolSchemaCharsBefore=").append(beforeChars);
        }
        if (afterChars >= 0) {
            sb.append(" toolSchemaCharsAfter=").append(afterChars);
        }
        return sb.toString();
    }

    private static String joinBuckets(Set<ToolBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return "";
        }
        Set<String> names = new TreeSet<>();
        for (ToolBucket b : buckets) {
            names.add(b.name());
        }
        return String.join(",", names);
    }
}
