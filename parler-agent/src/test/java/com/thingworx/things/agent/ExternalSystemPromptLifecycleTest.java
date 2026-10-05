package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptSelection;
import com.thingworx.things.agent.configrepo.SystemPromptFileLoader;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsApiMessages;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUtcClockInjector;
import com.thingworx.things.agent.playbook.PlaybookCatalogEntry;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Production-connected lifecycle tests for CC-1.6 external whole-block prompts via
 * {@link PromptContextCacheRefreshSupport}, {@link LeadingStableSystemRowSupport}, and
 * {@link SystemPromptFileLoader}.
 */
class ExternalSystemPromptLifecycleTest {

    private static final String SETTINGS = "agent-settings-base";
    private static final PromptContextAssemblyContext ASSEMBLY_CONTEXT = new PromptContextAssemblyContext(
            SETTINGS, false, "full_table", null);
    private static final String EXTERNAL_A = "external-stable-block-A";
    private static final String EXTERNAL_B = "external-stable-block-B";

    @AfterEach
    void resetHooks() {
        PromptContextCacheRefreshSupport.resetTestHooks();
    }

    private static PromptContextCacheSnapshot emptySnapshotShell() {
        return new PromptContextCacheSnapshot("", Collections.emptyList(), Collections.emptyList(), "", "",
                Instant.parse("2026-09-16T00:00:00Z"));
    }

    private static InfoTable listing(String... fileNames) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String name : fileNames) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(name));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("F"));
            it.addRow(vc);
        }
        return it;
    }

    private static RepositoryReader readerForFiles(Map<String, String> texts, String... listedNames) {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                assertEquals(ConfigurationRepositoryPaths.SYSTEM_PROMPT_ROOT, path);
                return listing(listedNames);
            }

            @Override
            public String loadText(String path) {
                return texts.get(path);
            }
        };
    }

    private static PlaybookRegistrySnapshot playbookRegistry(String id) {
        PlaybookCatalogEntry entry = new PlaybookCatalogEntry(id, id + " PB", "", "PB when",
                "/playbooks/" + id + "/playbook.json", new JSONObject(), new JSONObject());
        return PlaybookRegistrySnapshot.loaded(Instant.now(), Map.of(id, entry), Map.of(), List.of());
    }

    private static PromptContextAssemblyContext assemblyContextFor(PlaybookRegistrySnapshot playbooks) {
        return new PromptContextAssemblyContext(SETTINGS, false, "full_table", playbooks);
    }

    @Test
    void commitRefresh_usesPlaybookRegistryAfterBuild_matchesSubsequentInspection() throws Exception {
        AtomicReference<PlaybookRegistrySnapshot> registry =
                new AtomicReference<>(playbookRegistry("old_pb"));
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef();

        PromptContextCacheRefreshSupport.CommitResult committed = PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> {
                    registry.set(playbookRegistry("new_pb"));
                    return emptySnapshotShell();
                },
                () -> assemblyContextFor(registry.get()),
                null);

        PromptContextCacheRefreshSupport.PromptSnapshotInspection inspection =
                PromptContextCacheRefreshSupport.inspect(ref.get(), true, assemblyContextFor(registry.get()));

        assertEquals(committed.assembledStable(), inspection.stableSystemPrompt());
        assertTrue(committed.assembledStable().contains("`new_pb`"));
        assertFalse(committed.assembledStable().contains("`old_pb`"));
    }

    @Test
    void commitRefresh_emptyToLoadedPlaybook_includesNewCatalogInRefreshResponse() throws Exception {
        AtomicReference<PlaybookRegistrySnapshot> registry =
                new AtomicReference<>(PlaybookRegistrySnapshot.empty(Instant.now()));
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef();

        PromptContextCacheRefreshSupport.CommitResult committed = PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> {
                    registry.set(playbookRegistry("first_pb"));
                    return emptySnapshotShell();
                },
                () -> assemblyContextFor(registry.get()),
                null);

        assertTrue(committed.assembledStable().contains("`first_pb`"));
        assertTrue(committed.assembledStable().contains("Agent playbooks"));
    }

    @Test
    void commitRefresh_externalViaLoaderThenInvalidSelection_commitsDefaultAssembly() throws Exception {
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef();
        Map<String, String> textsA = Map.of("/SystemPrompt/a.md", EXTERNAL_A);
        RepositoryReader readerA = readerForFiles(textsA, "a.md");

        PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> PromptContextCacheRefreshSupport.withLoadedExternalSelection(
                        emptySnapshotShell(), readerA, "repo", NOPLogger.NOP_LOGGER),
                () -> ASSEMBLY_CONTEXT,
                null);

        assertEquals("external_file", ref.get().getExternalSystemPrompt().sourceKey());
        assertEquals(EXTERNAL_A, ref.get().getExternalSystemPrompt().promptText());

        RepositoryReader readerInvalid = readerForFiles(Map.of(), "a.md", "b.md");
        PromptContextCacheRefreshSupport.CommitResult invalid = PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> PromptContextCacheRefreshSupport.withLoadedExternalSelection(
                        emptySnapshotShell(), readerInvalid, "repo", NOPLogger.NOP_LOGGER),
                () -> ASSEMBLY_CONTEXT,
                null);

        assertEquals("default", ref.get().getExternalSystemPrompt().sourceKey());
        assertTrue(ref.get().getExternalSystemPrompt().fallbackDiagnostic().contains("multiple_files"));
        assertTrue(invalid.assembledStable().contains(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE));
        assertFalse(invalid.assembledStable().contains(EXTERNAL_A));
        assertTrue(invalid.refreshResponseBody().contains("multiple_files"));
    }

    @Test
    void commitRefresh_failedBuild_preservesPriorCommittedSnapshot() throws Exception {
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef();
        Map<String, String> textsA = Map.of("/SystemPrompt/a.md", EXTERNAL_A);
        RepositoryReader readerA = readerForFiles(textsA, "a.md");
        PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> PromptContextCacheRefreshSupport.withLoadedExternalSelection(
                        emptySnapshotShell(), readerA, "repo", NOPLogger.NOP_LOGGER),
                () -> ASSEMBLY_CONTEXT,
                null);

        PromptContextCacheSnapshot prior = ref.get();
        assertThrows(RuntimeException.class, () -> PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> {
                    throw new RuntimeException("skill registry build failed");
                },
                () -> ASSEMBLY_CONTEXT,
                null));

        assertEquals(prior, ref.get());
        assertEquals(EXTERNAL_A, PromptContextCacheRefreshSupport.inspect(prior, true, ASSEMBLY_CONTEXT)
                .stableSystemPrompt());
    }

    @Test
    void commitRefresh_buildHookFailure_preservesPriorCommittedSnapshot() throws Exception {
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef();
        RepositoryReader readerA = readerForFiles(Map.of("/SystemPrompt/a.md", EXTERNAL_A), "a.md");
        PromptContextCacheRefreshSupport.commitRefresh(
                ref,
                () -> PromptContextCacheRefreshSupport.withLoadedExternalSelection(
                        emptySnapshotShell(), readerA, "repo", NOPLogger.NOP_LOGGER),
                () -> ASSEMBLY_CONTEXT,
                null);
        PromptContextCacheSnapshot prior = ref.get();

        PromptContextCacheRefreshSupport.failSnapshotBuildForTests =
                new RuntimeException("injected build failure");
        try {
            assertThrows(RuntimeException.class, () -> PromptContextCacheRefreshSupport.commitRefresh(
                    ref,
                    () -> {
                        RuntimeException fail = PromptContextCacheRefreshSupport.failSnapshotBuildForTests;
                        if (fail != null) {
                            throw fail;
                        }
                        return PromptContextCacheRefreshSupport.withLoadedExternalSelection(
                                emptySnapshotShell(),
                                readerForFiles(Map.of("/SystemPrompt/b.md", EXTERNAL_B), "b.md"),
                                "repo",
                                NOPLogger.NOP_LOGGER);
                    },
                    () -> ASSEMBLY_CONTEXT,
                    null));
        } finally {
            PromptContextCacheRefreshSupport.resetTestHooks();
        }

        assertEquals(prior, ref.get());
        assertEquals("/SystemPrompt/a.md", prior.getExternalSystemPrompt().activePath());
    }

    @Test
    void leadingStableRowApply_capturedTurnKeepsStableTextWhileNextTurnSeesRefresh() {
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef(
                emptySnapshotShell().withExternalSystemPrompt(
                        ExternalSystemPromptSelection.externalFile("/SystemPrompt/a.md", EXTERNAL_A)));

        List<ChatMessage> turnMessages = new ArrayList<>();
        turnMessages.add(ChatMessage.user("first question"));
        LeadingStableSystemRowSupport.apply(turnMessages,
                () -> PromptContextCacheRefreshSupport.assembleLeadingStable(ref.get(), null, ASSEMBLY_CONTEXT));
        List<ChatMessage> capturedTurn = List.copyOf(turnMessages);

        ref.set(emptySnapshotShell().withExternalSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/b.md", EXTERNAL_B)));
        List<ChatMessage> nextTurn = new ArrayList<>();
        nextTurn.add(ChatMessage.user("second question"));
        LeadingStableSystemRowSupport.apply(nextTurn,
                () -> PromptContextCacheRefreshSupport.assembleLeadingStable(ref.get(), null, ASSEMBLY_CONTEXT));

        assertEquals(EXTERNAL_A, capturedTurn.get(0).getContent());
        assertEquals(EXTERNAL_B, nextTurn.get(0).getContent());
    }

    @Test
    void hitlPendingRecord_preservesProductionCapturedLeadingRowAcrossRefresh() {
        PromptContextCacheRefreshSupport.SnapshotRef ref = new PromptContextCacheRefreshSupport.SnapshotRef(
                emptySnapshotShell().withExternalSystemPrompt(
                        ExternalSystemPromptSelection.externalFile("/SystemPrompt/a.md", EXTERNAL_A)));

        List<ChatMessage> live = new ArrayList<>();
        live.add(ChatMessage.user("invoke gated tool"));
        LeadingStableSystemRowSupport.apply(live,
                () -> PromptContextCacheRefreshSupport.assembleLeadingStable(ref.get(), null, ASSEMBLY_CONTEXT));

        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid", "rid", "cid", "user", "Agent", "remote", null, live, null, null,
                null, System.currentTimeMillis() + 60_000, null, null, List.of(), List.of(),
                null, null, null);
        assertEquals(EXTERNAL_A, rec.getMessagesCopy().get(0).getContent());

        ref.set(emptySnapshotShell().withExternalSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/b.md", EXTERNAL_B)));
        assertNotEquals(
                rec.getMessagesCopy().get(0).getContent(),
                PromptContextCacheRefreshSupport.assembleLeadingStable(ref.get(), null, ASSEMBLY_CONTEXT));
    }

    @Test
    void runtimeInspection_returnsCoherentSourcePathAndStableTextFromOneSnapshot() {
        PromptContextCacheSnapshot snap = emptySnapshotShell().withExternalSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/a.md", EXTERNAL_A));
        PromptContextCacheRefreshSupport.PromptSnapshotInspection inspection =
                PromptContextCacheRefreshSupport.inspect(snap, true, ASSEMBLY_CONTEXT);

        assertEquals("external_file", inspection.stablePromptSource());
        assertEquals("/SystemPrompt/a.md", inspection.externalSystemPromptPath());
        assertEquals(EXTERNAL_A, inspection.stableSystemPrompt());
    }

    @Test
    void runtimeInspection_misalignedMetadataAndTextPair_isNotReturnedByProductionPath() {
        PromptContextCacheSnapshot snapA = emptySnapshotShell().withExternalSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/a.md", EXTERNAL_A));

        PromptContextCacheRefreshSupport.PromptSnapshotInspection coherentA =
                PromptContextCacheRefreshSupport.inspect(snapA, true, ASSEMBLY_CONTEXT);
        assertEquals("/SystemPrompt/a.md", coherentA.externalSystemPromptPath());
        assertEquals(EXTERNAL_A, coherentA.stableSystemPrompt());
        assertNotEquals(EXTERNAL_B, coherentA.stableSystemPrompt(),
                "production inspect must not pair snap-A metadata with snap-B text");
    }

    @Test
    void defaultAndExternalEquivalentPrompt_matchLeadingRowAndCacheMarkersOnBothWires() {
        PromptContextCacheSnapshot base = emptySnapshotShell();
        String defaultAssembled = PromptContextCacheRefreshSupport.assembleLeadingStable(base, null, ASSEMBLY_CONTEXT);

        PromptContextCacheSnapshot externalSnap = base.withExternalSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/SystemPrompt/default.md", defaultAssembled));
        String externalAssembled = PromptContextCacheRefreshSupport.assembleLeadingStable(
                externalSnap, null, ASSEMBLY_CONTEXT);
        assertEquals(defaultAssembled, externalAssembled);

        String timeA = LlmUtcClockInjector.buildTimeBlockContent(
                "America/New_York", Instant.parse("2026-05-06T18:00:00Z"));
        String timeB = LlmUtcClockInjector.buildTimeBlockContent(
                "America/New_York", Instant.parse("2026-05-06T18:01:00Z"));

        List<ChatMessage> defaultRound = List.of(
                ChatMessage.system(defaultAssembled), ChatMessage.system(timeA), ChatMessage.user("user-one"));
        List<ChatMessage> externalRound = List.of(
                ChatMessage.system(externalAssembled), ChatMessage.system(timeB), ChatMessage.user("user-two"));

        assertStablePrefixAndCacheMarkers(defaultRound, defaultAssembled);
        assertStablePrefixAndCacheMarkers(externalRound, externalAssembled);
    }

    private static void assertStablePrefixAndCacheMarkers(List<ChatMessage> planned, String stable) {
        List<Map<String, Object>> openAi = ChatCompletionsApiMessages.toApiMessages(planned);
        assertEquals(stable, openAi.get(0).get("content"));

        Map<String, Object> anthropic = AnthropicMessagesApi.buildRequestPayload(
                planned, Collections.emptyList(), "claude", 0, 1024);
        assertEquals(stable, firstSystemText(anthropic));
        assertTrue(hasStableSystemCacheMarker(anthropic));
        assertFalse(promptTexts(anthropic.get("system")).contains(planned.get(1).getContent()));
    }

    @SuppressWarnings("unchecked")
    private static String firstSystemText(Map<String, Object> payload) {
        Object system = payload.get("system");
        if (system instanceof String) {
            return (String) system;
        }
        return (String) ((Map<String, Object>) ((List<?>) system).get(0)).get("text");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasStableSystemCacheMarker(Map<String, Object> payload) {
        Object system = payload.get("system");
        if (system instanceof Map<?, ?>) {
            return ((Map<String, Object>) system).containsKey("cache_control");
        }
        if (system instanceof List<?>) {
            Object block = ((List<?>) system).get(0);
            return block instanceof Map<?, ?> && ((Map<String, Object>) block).containsKey("cache_control");
        }
        return false;
    }

    private static List<String> promptTexts(Object value) {
        List<String> out = new ArrayList<>();
        collectPromptTexts(value, out);
        return out;
    }

    private static void collectPromptTexts(Object value, List<String> out) {
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                collectPromptTexts(item, out);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object text = map.get("text");
            if (text == null) {
                text = map.get("content");
            }
            if (text != null) {
                out.add(String.valueOf(text));
            }
        }
    }
}
