package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

class ToolAdmissionPolicyLazyTest {

    private static final List<String> SURFACE = List.of(
            "resolve_thing", "resolve_asset_type", "list_asset_types", "query_entities", "query_alert_summary",
            "tabulate_cached_result", "invoke_service", "search_document_chunks", "get_agent_skill",
            "utilization_records", "utilization_machine_listing");

    private static List<ToolDefinition> surface() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (String n : SURFACE) {
            defs.add(new ToolDefinition(n, "whenToUse blurb for " + n, Map.of()));
        }
        return defs;
    }

    private static Set<String> advertisedNames(ToolAdmissionPolicy.LazyResult r) {
        return r.advertised().stream().map(ToolDefinition::getName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> catalogNames(ToolAdmissionPolicy.LazyResult r) {
        return r.catalog().stream().map(ToolAdmissionPolicy.CatalogEntry::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static ToolAdmissionSignals noSignals() {
        return new ToolAdmissionSignals(null, false, false, false, false, false,
                Collections.emptySet(), Collections.emptySet());
    }

    @Test
    void firstRound_advertisesOnlyCore_deferringTheRestToCatalog() {
        ToolAdmissionPolicy.LazyResult r = ToolAdmissionPolicy.lazy(surface(), noSignals(), Collections.emptySet());
        Set<String> adv = advertisedNames(r);
        // Core with no taxonomy/skills/playbook signals = resolve_thing only.
        assertEquals(Set.of("resolve_thing"), adv);
        // Everything else is in the catalog.
        assertTrue(catalogNames(r).contains("query_entities"));
        assertTrue(catalogNames(r).contains("invoke_service"));
        assertTrue(catalogNames(r).contains("utilization_records"));
        assertFalse(catalogNames(r).contains("resolve_thing"));
        assertEquals(SURFACE.size(), r.advertised().size() + r.catalog().size());
    }

    @Test
    void taxonomyAndLoadedEntryPoints_joinCore() {
        ToolAdmissionSignals s = new ToolAdmissionSignals(null, false, false, true, false, true,
                Collections.emptySet(), Collections.emptySet());
        Set<String> adv = advertisedNames(ToolAdmissionPolicy.lazy(surface(), s, Collections.emptySet()));
        assertTrue(adv.contains("resolve_thing"));
        assertTrue(adv.contains("resolve_asset_type"));
        assertTrue(adv.contains("list_asset_types"));
        assertTrue(adv.contains("get_agent_skill"));
    }

    @Test
    void requiredToolsAndBuckets_areAdvertisedNotCatalogued() {
        ToolAdmissionSignals s = new ToolAdmissionSignals("card", false, false, false, false, false,
                Set.of("invoke_service"), EnumSet.of(ToolBucket.UTILIZATION));
        ToolAdmissionPolicy.LazyResult r = ToolAdmissionPolicy.lazy(surface(), s, Collections.emptySet());
        Set<String> adv = advertisedNames(r);
        assertTrue(adv.contains("invoke_service"), "requiredTools advertised with full schema");
        assertTrue(adv.contains("utilization_records"), "requiredBuckets advertised with full schema");
        assertTrue(adv.contains("utilization_machine_listing"));
        assertFalse(catalogNames(r).contains("invoke_service"));
    }

    @Test
    void registeredTools_areAdvertisedOnSubsequentRounds() {
        Set<String> registered = new LinkedHashSet<>(List.of("query_entities", "search_document_chunks"));
        ToolAdmissionPolicy.LazyResult r = ToolAdmissionPolicy.lazy(surface(), noSignals(), registered);
        Set<String> adv = advertisedNames(r);
        assertTrue(adv.contains("query_entities"));
        assertTrue(adv.contains("search_document_chunks"));
        assertFalse(catalogNames(r).contains("query_entities"));
    }

    @Test
    void renderCatalog_capsCharsAndNotesOmissions() {
        List<ToolAdmissionPolicy.CatalogEntry> many = new ArrayList<>();
        // Build a catalog far larger than the cap.
        List<ToolDefinition> bigDefs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            bigDefs.add(new ToolDefinition("tool_" + i, "x".repeat(120), Map.of()));
        }
        ToolAdmissionPolicy.LazyResult r = ToolAdmissionPolicy.lazy(bigDefs, noSignals(), Collections.emptySet());
        String catalog = ToolAdmissionPolicy.renderCatalog(r.catalog());
        assertTrue(catalog.length() <= ToolAdmissionPolicy.LAZY_CATALOG_MAX_CHARS + 80,
                "catalog must be capped near LAZY_CATALOG_MAX_CHARS, was " + catalog.length());
        assertTrue(catalog.contains("more tools omitted"), "truncation must be noted");
        // unused local kept intentionally minimal
        assertTrue(many.isEmpty());
    }

    @Test
    void lazyAdvertisesStrictlyFewerFullSchemasThanNarrow() {
        List<ToolDefinition> merged = surface();
        ToolAdmissionSignals s = noSignals();
        int narrowCount = ToolAdmissionPolicy.narrow(merged, s).admitted().size();
        int lazyCount = ToolAdmissionPolicy.lazy(merged, s, Collections.emptySet()).advertised().size();
        assertTrue(lazyCount < narrowCount,
                "lazy advertises fewer full schemas than narrow (" + lazyCount + " < " + narrowCount + ")");
    }

    @Test
    void emptyMerged_isSafe() {
        ToolAdmissionPolicy.LazyResult r = ToolAdmissionPolicy.lazy(List.of(), noSignals(), Collections.emptySet());
        assertTrue(r.advertised().isEmpty());
        assertTrue(r.catalog().isEmpty());
    }
}
