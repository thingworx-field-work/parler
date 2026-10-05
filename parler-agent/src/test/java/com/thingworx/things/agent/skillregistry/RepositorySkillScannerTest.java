package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RepositorySkillScannerTest {

    private static String skillMd(String body) {
        return "---\ntitle: T\nwhen_to_use: When\n---\n" + body;
    }

    private static int mergeSorted(
            List<String> dirNames,
            Map<String, String> idToMarkdown,
            Map<String, SkillRegistryDescriptor> map,
            List<String> diagnostics,
            java.util.function.Consumer<String> onError,
            java.util.function.Consumer<String> onWarn,
            String agentName)
            throws Exception {
        List<String> sorted = new ArrayList<>(dirNames);
        java.util.Collections.sort(sorted);
        return RepositorySkillScanner.scanAndMergeSortedDirectories(sorted, "Repo",
                id -> {
                    if (!idToMarkdown.containsKey(id)) {
                        throw new java.io.FileNotFoundException(id);
                    }
                    return idToMarkdown.get(id);
                },
                map, diagnostics, onError, onWarn, agentName);
    }

    @Test
    void merges_valid_repository_skill() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        texts.put("alpha", skillMd("# Hi"));
        int n = mergeSorted(List.of("alpha"), texts, map, diag, null, null, "Agent");
        assertEquals(1, n);
        assertNotNull(map.get("alpha"));
        assertEquals(SkillSourceKind.REPOSITORY, map.get("alpha").sourceKind());
    }

    @Test
    void null_error_warn_sinks_do_not_throw() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        texts.put("bad", skillMd("x"));
        mergeSorted(List.of("bad", "9bad"), texts, map, diag, null, null, "Agent");
        assertTrue(diag.stream().anyMatch(s -> s.contains("invalid directory")));
    }

    @Test
    void hidden_directory_skipped() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        int n = mergeSorted(List.of(".secret"), Map.of(), map, diag, null, null, "A");
        assertEquals(0, n);
        assertTrue(diag.stream().anyMatch(s -> s.contains("hidden")));
    }

    @Test
    void duplicate_repository_skill_skipped_when_id_already_in_registry() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        map.put("dup", new SkillRegistryDescriptor("dup", "S", "w", SkillSourceKind.SERVICE, "_skill_dup", null, null));
        List<String> diag = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        texts.put("dup", skillMd("x"));
        int n = mergeSorted(List.of("dup"), texts, map, diag, null, null, "A");
        assertEquals(0, n);
        assertEquals(SkillSourceKind.SERVICE, map.get("dup").sourceKind());
        assertTrue(diag.stream().anyMatch(s -> s.contains("already registered") || s.contains("duplicate")));
    }

    @Test
    void case_conflict_with_existing_registered_id() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        map.put("Ab", new SkillRegistryDescriptor("Ab", "S", "w", SkillSourceKind.SERVICE, "_skill_Ab", null, null));
        List<String> diag = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        texts.put("aB", skillMd("x"));
        mergeSorted(List.of("aB"), texts, map, diag, null, null, "A");
        assertFalse(map.containsKey("aB"));
        assertTrue(diag.stream().anyMatch(s -> s.contains("case conflicts")));
    }

    @Test
    void second_repo_directory_case_only_collision() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        texts.put("Foo", skillMd("a"));
        texts.put("foo", skillMd("b"));
        int n = mergeSorted(List.of("Foo", "foo"), texts, map, diag, null, null, "A");
        assertEquals(1, n);
        assertTrue(diag.stream().anyMatch(s -> s.contains("case conflicts") || s.contains("case-only duplicate")));
    }

    @Test
    void oversized_skill_md_skipped() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        String huge = "x".repeat(SkillRegistryLimits.MAX_SKILL_MD_CHARS + 1);
        mergeSorted(List.of("big"), Map.of("big", huge), map, diag, null, null, "A");
        assertTrue(map.isEmpty());
        assertTrue(diag.stream().anyMatch(s -> s.contains("oversized")));
    }

    @Test
    void name_mismatch_skipped() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        String md = "---\nname: other\nwhen_to_use: w\n---\nB";
        mergeSorted(List.of("self"), Map.of("self", md), map, diag, null, null, "A");
        assertTrue(map.isEmpty());
        assertTrue(diag.stream().anyMatch(s -> s.contains("name mismatch")));
    }

    @Test
    void missing_skill_md_skipped() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        mergeSorted(List.of("gone"), Map.of(), map, diag, null, null, "A");
        assertTrue(map.isEmpty());
        assertTrue(diag.stream().anyMatch(s -> s.contains("unreadable")));
    }

    @Test
    void routing_hint_warning_emits_on_warn() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        List<String> warns = new CopyOnWriteArrayList<>();
        String md = "---\ntitle: T\n---\n# B";
        mergeSorted(List.of("hint"), Map.of("hint", md), map, diag, s -> {}, warns::add, "A");
        assertEquals(1, map.size());
        assertTrue(diag.stream().anyMatch(s -> s.contains("warning: repository skill")));
        assertFalse(warns.isEmpty());
    }

    @Test
    void cap_skips_beyond_max_repository_skills() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        for (int i = 0; i <= SkillRegistryLimits.MAX_REPOSITORY_SKILLS; i++) {
            String id = String.format("z%03d", i);
            dirs.add(id);
            texts.put(id, skillMd("b"));
        }
        int n = mergeSorted(dirs, texts, map, diag, null, null, "A");
        assertEquals(SkillRegistryLimits.MAX_REPOSITORY_SKILLS, n);
        assertTrue(diag.stream().anyMatch(s -> s.contains("LoadText attempt") || s.contains("beyond scan cap") || s.contains("beyond cap")));
    }

    @Test
    void cap_limits_load_attempts_when_loads_fail() throws Exception {
        int max = SkillRegistryLimits.MAX_REPOSITORY_SKILLS;
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < max + 10; i++) {
            dirs.add(String.format("z%03d", i));
        }
        AtomicInteger loads = new AtomicInteger();
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        int n = RepositorySkillScanner.scanAndMergeSortedDirectories(
                dirs, "Repo",
                id -> {
                    loads.incrementAndGet();
                    throw new java.io.FileNotFoundException("missing");
                },
                map, diag, null, null, "A");
        assertEquals(0, n);
        assertEquals(max, loads.get());
        assertTrue(diag.stream().anyMatch(s -> s.contains("LoadText attempt") || s.contains("beyond scan cap") || s.contains("beyond cap")));
    }

    @Test
    void skillReservedByPlaybook_isSkipped() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        RepositorySkillScanner.scanAndMergeSortedDirectories(
                List.of("foo"),
                "Repo",
                id -> "---\ntitle: T\nwhen_to_use: w\n---\nbody",
                map,
                diag,
                null,
                null,
                "Agent",
                Set.of("foo"));
        assertFalse(map.containsKey("foo"));
        assertTrue(diag.stream().anyMatch(s -> s.contains("reserved by playbook")));
    }

    @Test
    void on_error_invoked_for_invalid_directory() throws Exception {
        Map<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        List<String> errs = new CopyOnWriteArrayList<>();
        mergeSorted(List.of("9bad"), Map.of(), map, diag, errs::add, null, "MyAgent");
        assertFalse(errs.isEmpty());
        assertTrue(errs.get(0).contains("MyAgent"));
    }
}
