package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.taskstate.AgentTaskState;

/**
 * Phase 4 D2: when the active turn skill is {@code document_search} only and no non-document tool has run yet,
 * attach a narrowed LLM tool list (utility core + document tools). Mis-detection or empty result reverts to the
 * full merged list. Design: {@code docs/operations/doc-index-enhance.md} §6 D2.
 */
public final class DocumentTurnToolNarrowing {

    /** Registered skill short id for document Q&amp;A (matches {@code /document_search} and skill catalog). */
    public static final String DOCUMENT_SKILL_SHORT_ID = "document_search";

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(DocumentTurnToolNarrowing.class);

    /** Tools retained during a narrowed document-skill turn (utility core + document built-ins). */
    /** E14/B6: single source for document-turn admission — includes resolve_document_set. */
    static final Set<String> NARROWED_TOOL_NAMES = Set.of(
            "get_agent_skill",
            "search_document_chunks",
            "get_document_chunk",
            "resolve_document_set");

    private static final ThreadLocal<Boolean> NON_DOCUMENT_TOOL_INVOKED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final ThreadLocal<Boolean> DOCUMENT_TOOL_INVOKED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DocumentTurnToolNarrowing() {}

    /** Clears per-turn narrowing state; call at user-turn entry before {@link com.thingworx.things.agent.AgentLoop#run}. */
    public static void resetTurnState() {
        NON_DOCUMENT_TOOL_INVOKED.set(Boolean.FALSE);
        DOCUMENT_TOOL_INVOKED.set(Boolean.FALSE);
    }

    /**
     * Records a completed or attempted built-in / extended tool dispatch for gating. Allowed document-turn tools do
     * not flip the latch; any other name disables narrowing for subsequent rounds.
     */
    public static void recordToolInvocation(String functionName) {
        if (functionName == null || functionName.isEmpty()) {
            return;
        }
        if (NARROWED_TOOL_NAMES.contains(functionName)) {
            if ("search_document_chunks".equals(functionName) || "get_document_chunk".equals(functionName)) {
                DOCUMENT_TOOL_INVOKED.set(Boolean.TRUE);
            }
            return;
        }
        NON_DOCUMENT_TOOL_INVOKED.set(Boolean.TRUE);
        DocumentSearchProgressGuard.onNonDocumentTool();
    }

    /**
     * Applies D2 gating to the merged tool list for an agent loop round. Returns {@code merged} unchanged when
     * narrowing does not apply or would yield an empty list.
     */
    public static List<ToolDefinition> filterForRound(List<ToolDefinition> merged, int iterationOneBased) {
        return filterForRoundInternal(merged, iterationOneBased, narrowingEnabledFromConfig());
    }

    /** Test hook: same as {@link #filterForRound} with explicit config flag. */
    static List<ToolDefinition> filterForRoundInternal(List<ToolDefinition> merged, int iterationOneBased,
            boolean narrowingEnabled) {
        if (merged == null || merged.isEmpty()) {
            return merged != null ? merged : Collections.emptyList();
        }
        if (!shouldNarrow(narrowingEnabled, iterationOneBased)) {
            if (iterationOneBased == 1) {
                logNarrowingSkipped(narrowingEnabled);
            }
            return merged;
        }
        List<ToolDefinition> narrowed = new ArrayList<>();
        for (ToolDefinition def : merged) {
            if (def != null && NARROWED_TOOL_NAMES.contains(def.getName())) {
                narrowed.add(def);
            }
        }
        if (narrowed.isEmpty()) {
            LOG.warn("Document-turn tool narrowing produced empty tool list; reverting to full {} tools", merged.size());
            return merged;
        }
        if (iterationOneBased == 1) {
            String cid = AgentToolContext.getConversationId();
            LOG.info("Document-turn tool narrowing: {} -> {} tools (skill={}, iteration={}, conversationId={})",
                    merged.size(), narrowed.size(), DOCUMENT_SKILL_SHORT_ID, iterationOneBased,
                    cid != null ? cid : "");
        }
        return narrowed;
    }

    static boolean shouldNarrowForTests() {
        return shouldNarrow(narrowingEnabledFromConfig(), 1);
    }

    static boolean shouldNarrowForTests(int iterationOneBased) {
        return shouldNarrow(narrowingEnabledFromConfig(), iterationOneBased);
    }

    private static boolean shouldNarrow(boolean narrowingEnabled, int iterationOneBased) {
        if (!narrowingEnabled) {
            return false;
        }
        if (nonDocumentToolInvokedThisTurn()) {
            return false;
        }
        if (isOnlyDocumentSkillActive()) {
            return true;
        }
        return iterationOneBased >= 2 && documentToolInvokedThisTurn();
    }

    private static boolean narrowingEnabledFromConfig() {
        AgentThing agent = AgentToolContext.getAgentThing();
        return agent != null && agent.isDocumentTurnToolNarrowingEnabled();
    }

    static boolean isOnlyDocumentSkillActive() {
        List<String> slash = AgentToolContext.snapshotSlashSkillShortNamesForPending();
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        List<String> dynamic = st != null ? st.getDynamicSkillShortNamesInOrder() : List.of();

        LinkedHashSet<String> active = new LinkedHashSet<>();
        if (slash != null) {
            active.addAll(slash);
        }
        if (dynamic != null) {
            active.addAll(dynamic);
        }
        return active.size() == 1 && DOCUMENT_SKILL_SHORT_ID.equals(active.iterator().next());
    }

    private static boolean nonDocumentToolInvokedThisTurn() {
        return Boolean.TRUE.equals(NON_DOCUMENT_TOOL_INVOKED.get());
    }

    private static boolean documentToolInvokedThisTurn() {
        return Boolean.TRUE.equals(DOCUMENT_TOOL_INVOKED.get());
    }

    private static void logNarrowingSkipped(boolean narrowingEnabled) {
        String cid = AgentToolContext.getConversationId();
        String cidSuffix = cid != null && !cid.isEmpty() ? " conversationId=" + cid : "";
        if (!narrowingEnabled) {
            LOG.info("Document-turn tool narrowing skipped: AgentSettings.documentTurnToolNarrowingDisabled=true{}",
                    cidSuffix);
            return;
        }
        if (!isOnlyDocumentSkillActive() && !documentToolInvokedThisTurn()) {
            LOG.info("Document-turn tool narrowing skipped: no document tool invoked yet and skill is not sole document_search{}",
                    cidSuffix);
            return;
        }
        if (nonDocumentToolInvokedThisTurn()) {
            LOG.info("Document-turn tool narrowing skipped: non-document tool already invoked this turn{}",
                    cidSuffix);
        }
    }
}
