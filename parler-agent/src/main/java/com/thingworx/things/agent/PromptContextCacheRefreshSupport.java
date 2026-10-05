package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.thingworx.things.agent.configrepo.ExternalSystemPromptAssembly;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptSelection;
import com.thingworx.things.agent.configrepo.SystemPromptFileLoader;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.AgentWorkflowCatalogFormatter;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;

/**
 * Production refresh commit and runtime prompt inspection for the prompt-context snapshot.
 * {@link AgentThing} delegates here; same-package tests exercise these operations directly.
 */
public final class PromptContextCacheRefreshSupport {

    /** When set, {@link AgentThing} build throws before replacing the committed snapshot. */
    static volatile RuntimeException failSnapshotBuildForTests;

    public static final class SnapshotRef {
        private volatile PromptContextCacheSnapshot value;

        public SnapshotRef() {
        }

        public SnapshotRef(PromptContextCacheSnapshot initial) {
            this.value = initial;
        }

        public PromptContextCacheSnapshot get() {
            return value;
        }

        public void set(PromptContextCacheSnapshot snapshot) {
            this.value = snapshot;
        }
    }

    public static final class CommitResult {
        private final PromptContextCacheSnapshot snapshot;
        private final String assembledStable;
        private final String refreshResponseBody;

        public CommitResult(PromptContextCacheSnapshot snapshot, String assembledStable, String refreshResponseBody) {
            this.snapshot = snapshot;
            this.assembledStable = assembledStable;
            this.refreshResponseBody = refreshResponseBody;
        }

        public PromptContextCacheSnapshot snapshot() {
            return snapshot;
        }

        public String assembledStable() {
            return assembledStable;
        }

        public String refreshResponseBody() {
            return refreshResponseBody;
        }
    }

    public static final class PromptSnapshotInspection {
        private final String stablePromptSource;
        private final String externalSystemPromptPath;
        private final String externalSystemPromptFallback;
        private final String stableSystemPrompt;
        private final List<String> diagnosticLines;

        public PromptSnapshotInspection(
                String stablePromptSource,
                String externalSystemPromptPath,
                String externalSystemPromptFallback,
                String stableSystemPrompt,
                List<String> diagnosticLines) {
            this.stablePromptSource = stablePromptSource;
            this.externalSystemPromptPath = externalSystemPromptPath;
            this.externalSystemPromptFallback = externalSystemPromptFallback;
            this.stableSystemPrompt = stableSystemPrompt;
            this.diagnosticLines = diagnosticLines;
        }

        public String stablePromptSource() {
            return stablePromptSource;
        }

        public String externalSystemPromptPath() {
            return externalSystemPromptPath;
        }

        public String externalSystemPromptFallback() {
            return externalSystemPromptFallback;
        }

        public String stableSystemPrompt() {
            return stableSystemPrompt;
        }

        public List<String> diagnosticLines() {
            return diagnosticLines;
        }
    }

    private PromptContextCacheRefreshSupport() {
    }

    static void resetTestHooks() {
        failSnapshotBuildForTests = null;
    }

    /**
     * Commits a freshly built snapshot only after {@code build} succeeds — mirrors
     * {@code AgentThing.commitPromptContextCacheRefreshLocked}.
     */
    public static CommitResult commitRefresh(
            SnapshotRef ref,
            Callable<PromptContextCacheSnapshot> build,
            Supplier<PromptContextAssemblyContext> assemblyContextSupplier,
            BiConsumer<PromptContextCacheSnapshot, StringBuilder> appendOperatorTail) throws Exception {
        return commitRefresh(ref::get, ref::set, build, assemblyContextSupplier, appendOperatorTail);
    }

    public static CommitResult commitRefresh(
            Supplier<PromptContextCacheSnapshot> snapshotGetter,
            java.util.function.Consumer<PromptContextCacheSnapshot> snapshotSetter,
            Callable<PromptContextCacheSnapshot> build,
            Supplier<PromptContextAssemblyContext> assemblyContextSupplier,
            BiConsumer<PromptContextCacheSnapshot, StringBuilder> appendOperatorTail) throws Exception {
        PromptContextCacheSnapshot snap = build.call();
        snapshotSetter.accept(snap);
        PromptContextAssemblyContext assemblyContext = assemblyContextSupplier.get();
        String assembled = assembleLeadingStable(snap, null, assemblyContext);
        StringBuilder out = new StringBuilder(assembled);
        out.append("\n\n").append(snap.getExternalSystemPrompt().formatRefreshDiagnosticsMarkdown());
        if (appendOperatorTail != null) {
            appendOperatorTail.accept(snap, out);
        }
        return new CommitResult(snap, assembled, out.toString());
    }

    /** Coherent operator inspection from one snapshot — mirrors {@code GetAgentRuntimeSnapshot} prompt fields. */
    public static PromptSnapshotInspection inspect(
            PromptContextCacheSnapshot snap,
            boolean includePrompt,
            PromptContextAssemblyContext assemblyContext) {
        ExternalSystemPromptSelection external = snap != null
                ? snap.getExternalSystemPrompt()
                : ExternalSystemPromptSelection.defaultSelection();
        String assembled = includePrompt ? assembleLeadingStable(snap, null, assemblyContext) : null;
        return new PromptSnapshotInspection(
                external.sourceKey(),
                external.isActiveExternal() ? external.activePath() : null,
                blankToNull(external.fallbackDiagnostic()),
                assembled,
                external.refreshDiagnosticLines());
    }

    /** Applies the production loader to a base snapshot shell (offline refresh inputs). */
    public static PromptContextCacheSnapshot withLoadedExternalSelection(
            PromptContextCacheSnapshot base,
            RepositoryReader reader,
            String repoName,
            Logger log) {
        ExternalSystemPromptSelection loaded = SystemPromptFileLoader.load(reader, repoName, log);
        return base.withExternalSystemPrompt(loaded);
    }

    static String assembleLeadingStable(
            PromptContextCacheSnapshot snapshot,
            String systemPromptOverride,
            PromptContextAssemblyContext assemblyContext) {
        SkillRegistrySnapshot skillRegistry = snapshot != null
                ? snapshot.getSkillRegistry()
                : SkillRegistrySnapshot.empty(java.time.Instant.EPOCH);
        PlaybookRegistrySnapshot playbooks = assemblyContext.playbookRegistry();
        String workflowCatalog = AgentWorkflowCatalogFormatter.format(
                new ArrayList<>(skillRegistry.descriptorsByShortId().values()), playbooks);
        return ExternalSystemPromptAssembly.resolveLeadingStableFromSnapshot(
                snapshot,
                systemPromptOverride,
                assemblyContext.agentSettingsSystemPrompt(),
                assemblyContext.appendBuiltInToolRoutingGuide(),
                assemblyContext.taxonomyPromptInjectionEffective(),
                workflowCatalog);
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
