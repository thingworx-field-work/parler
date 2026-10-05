package com.thingworx.things.agent.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import com.thingworx.types.InfoTable;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.configrepo.InvokeServicePolicyBatchCache;
import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.taskstate.AgentTaskState;

/**
 * Per-request context for built-in tools that need the current chat session (e.g. large-result cache)
 * or the executing {@link AgentThing} (e.g. {@code get_agent_skill}).
 * Set by {@link com.thingworx.things.agent.AgentThing} around {@code AgentLoop.run}.
 */
public final class AgentToolContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<AgentThing> AGENT_THING = new ThreadLocal<>();
    private static final ThreadLocal<com.thingworx.things.agent.execution.RunInvocationContext> RUN_INVOCATION =
            new ThreadLocal<>();
    /** Parler AlwaysOn stream: assistant turn {@code request_id} (wire). */
    private static final ThreadLocal<String> PARLER_REQUEST_ID = new ThreadLocal<>();
    /** Parler AlwaysOn: bound gateway / conversation RemoteThing name (equals {@code conversation_id} on wire). */
    private static final ThreadLocal<String> PARLER_REMOTE_THING_NAME = new ThreadLocal<>();
    /** Mutable LLM message list for the active Parler turn (for approval snapshot). */
    private static final ThreadLocal<List<ChatMessage>> PARLER_ACTIVE_MESSAGES = new ThreadLocal<>();
    /**
     * Indices of per-turn system injections in {@link #PARLER_ACTIVE_MESSAGES} for the active Parler AlwaysOn turn
     * (same order as {@code LlmTurnContext} in {@link com.thingworx.things.agent.AgentThing}); used to strip
     * ephemerals before {@link com.thingworx.things.agent.tools.PendingApprovalRecord} snapshots. Cleared in
     * {@link #clear()}.
     */
    private static final ThreadLocal<ParlerEphemeralSystemIndices> PARLER_EPHEMERAL_SYSTEM_INDICES =
            new ThreadLocal<>();
    /** Per-turn {@code last_invoke} bookkeeping for {@code build_chart_from_tabular_result}. */
    private static final ThreadLocal<TabularChartRoundState> TABULAR_CHART_ROUND = new ThreadLocal<>();
    /** Monotonic chart id sequence for one {@link AgentLoop} run (see {@code docs/agent/chart-intent.md}). */
    /**
     * Pending {@code type: chart} payloads from {@code build_chart_from_tabular_result} for Parler wire (FIFO —
     * multiple charts per turn; see {@code docs/agent/multi-chart-and-thrashing-safeguards.md} §4.3 Fix B2).
     */
    private static final ThreadLocal<ArrayDeque<JSONObject>> PARLER_PENDING_CHART_BLOCKS = new ThreadLocal<>();
    /** C3b-1: final chart-group manifests (JSON array text) for the final assistant Stream row's {@code chartGroupsJson}. */
    private static final ThreadLocal<String> CHART_GROUPS_JSON_FINAL_ROW = new ThreadLocal<>();
    /**
     * Set when {@code build_chart_from_tabular_result} was invoked this turn (tool entry). Drives behavior-derived
     * {@code chartExpectedButMissing} and chart-rescue eligibility ({@code docs/agent/multi-chart-and-thrashing-safeguards.md}).
     */
    private static final ThreadLocal<Boolean> PARLER_CHART_BUILD_ATTEMPTED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /**
     * Set when {@code build_chart_from_tabular_result} returned a recoverable chart-construction error (v1:
     * {@code DUPLICATE_SLICE_LABEL}).
     */
    private static final ThreadLocal<Boolean> PARLER_CHART_BUILD_FAILED_RECOVERABLY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Parler AlwaysOn: count of {@code type: chart} wire frames successfully downlinked this turn (Fix B3). */
    private static final ThreadLocal<Integer> PARLER_CHART_WIRE_EMITTED_COUNT = new ThreadLocal<>();
    /** {@code source.sourceCacheId} of every chart frame downlinked this turn (Answer Presentation Phase exposure). */
    private static final ThreadLocal<java.util.Set<String>> PARLER_CHARTED_SOURCE_CACHE_IDS = new ThreadLocal<>();
    /**
     * Parler: {@link com.thingworx.things.agent.AgentLoop} scheduled an end-of-turn singleton
     * {@code build_chart_from_tabular_result} rescue round after assistant prose without a chart wire
     * ({@code docs/agent/prompt-to-chart.md} §7.6). Cleared with other turn perf flags.
     */
    private static final ThreadLocal<Boolean> PARLER_CHART_END_TURN_RESCUE_ATTEMPTED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Answer Presentation Phase: rounds consumed this {@code AgentLoop} run (max 1 for v1). */
    private static final ThreadLocal<Integer> PRESENTATION_PHASE_ROUNDS_USED =
            ThreadLocal.withInitial(() -> 0);
    /** Set when a post-marker round exposes {@code build_chart_from_tabular_result} via presentation artifacts (not rescue). */
    private static final ThreadLocal<Boolean> PRESENTATION_PHASE_ENTERED_THIS_LOOP =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Integer> PRESENTATION_ACTIONS_REQUESTED = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> PRESENTATION_ACTIONS_EXECUTED = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> PRESENTATION_ACTIONS_BLOCKED = ThreadLocal.withInitial(() -> 0);
    /** Canonical IANA id from the active Parler uplink (per turn); tools may read for session-scoped logic. */
    private static final ThreadLocal<String> USER_IANA_TIMEZONE = new ThreadLocal<>();
    /**
     * Raw UTF-8 JSON text from an uplink-accepted host scope (HostScopeJson): {@code ParlerStreamToRemoteThing} /
     * Composer {@code Chat} / {@code ChatAsync} {@code hostContext}, when present and within size limits; cleared in
     * {@link #clear()}.
     */
    private static final ThreadLocal<String> HOST_CONTEXT_JSON = new ThreadLocal<>();
    /**
     * Turn-scoped document scope server-resolved from host-context at turn start
     * (knowledge-retrieval-pipeline §3.2): the document ids and the resolver source
     * (`custom` / `default-match`). {@code search_document_chunks} auto-defaults its
     * {@code documentIds} to this set when the model omits the field. Cleared in
     * {@link #clear()}.
     */
    private static final ThreadLocal<List<String>> INJECTED_DOCUMENT_SCOPE_IDS = new ThreadLocal<>();
    private static final ThreadLocal<String> INJECTED_DOCUMENT_SCOPE_SOURCE = new ThreadLocal<>();
    /**
     * Chat(sync/async): nonce per user message so {@link FetchCachedReplayGuard} can scope paging counts without a
     * Parler {@code request_id}. Cleared in {@link #clear()}.
     */
    private static final ThreadLocal<String> FETCH_CACHED_CHAT_TURN_NONCE = new ThreadLocal<>();
    /**
     * Last successful {@code resolve_asset_type} match {@code assetType.key} this turn (narrowing hint for
     * {@code resolve_thing} recovery on extended-tool {@code THINGNAME} preflight).
     */
    private static final ThreadLocal<String> LAST_RESOLVED_ASSET_TYPE_KEY = new ThreadLocal<>();
    /**
     * When {@link com.thingworx.things.agent.tools.InvokeServiceExecutor} returns compact LLM JSON for
     * {@code fetch_cached_result}, stores the full wire JSON keyed by tool call id for the stream path only.
     */
    private static final ThreadLocal<Map<String, String>> FETCH_CACHED_STREAM_JSON_BY_TOOL_CALL_ID =
            ThreadLocal.withInitial(HashMap::new);
    /**
     * After {@link com.thingworx.things.agent.tools.FetchCachedStreamLaneHelper#augmentToolForParlerStreamPersist} runs
     * export on the full fetch body, holds the augmented full JSON for {@link #takeFetchCachedFullAugmentedForDownlink}
     * so {@code resolveToolMessageForParlerTableDownlinks} does not re-run {@link ParlerTableFileExportHook}.
     */
    private static final ThreadLocal<Map<String, String>> FETCH_CACHED_FULL_AUGMENTED_JSON_BY_TOOL_CALL_ID =
            ThreadLocal.withInitial(HashMap::new);
    /**
     * Generic Phase-A tool egress full-body lane keyed by tool call id. Used only for live table/chart downlink
     * resolution after LLM/Stream replay received a compact body; does not imply {@code fetch_cached_result}.
     */
    private static final ThreadLocal<Map<String, String>> TOOL_EGRESS_FULL_JSON_BY_TOOL_CALL_ID =
            ThreadLocal.withInitial(HashMap::new);
    /** Per-turn v1a evidence ledger (cleared in {@link #clear()}). */
    private static final ThreadLocal<AgentTaskState> AGENT_TASK_STATE = new ThreadLocal<>();
    /** Parler AlwaysOn: gateway {@link Thing} for {@code ReceiveMessage} downlink (task.state, etc.). */
    private static final ThreadLocal<Thing> PARLER_REMOTE_CONVERSATION = new ThreadLocal<>();
    /**
     * Mutable flag for whether {@code ReceiveMessage} sends still succeed this turn; shared with Playbook-internal
     * artifact emission.
     */
    private static final ThreadLocal<AtomicBoolean> PARLER_DOWNLINK_OK = new ThreadLocal<>();
    /**
     * Parler AlwaysOn: caller principal snapshot for the gateway worker thread (running-turn cooperative cancel —
     * {@link ParlerRunningTurnCancelRegistry} / {@link AgentLoop}).
     */
    private static final ThreadLocal<String> PARLER_GATEWAY_CALLER_PRINCIPAL = new ThreadLocal<>();
    /** JUnit-only override for {@link com.thingworx.things.agent.AgentLoop} running-cancel registry keys when no {@link AgentThing} is bound. */
    private static final ThreadLocal<String> PARLER_RUNNING_CANCEL_AGENT_NAME_TEST = new ThreadLocal<>();
    /** Slash-loaded skill ids (user message order) for v1b checklist + PendingApprovalRecord snapshot. */
    private static final ThreadLocal<List<String>> PARLER_SLASH_SKILL_SHORT_IDS = new ThreadLocal<>();
    /**
     * When set, the active {@link com.thingworx.things.agent.AgentLoop} ends the turn with this text after a
     * successful {@code start_playbook} (cleared in {@link #clear()} and by {@link #consumePlaybookTerminalAnswer()}).
     */
    private static final ThreadLocal<String> PLAYBOOK_TERMINAL_ANSWER = new ThreadLocal<>();
    /**
     * When true, a {@code tabulate_cached_result} tool result this {@link com.thingworx.things.agent.AgentLoop} turn
     * satisfied complete-answer semantics ({@code docs/agent/query-spec.md} §11). Used for
     * {@code fetchAfterCompleteAnswerSetCount} ({@code docs/agent/llm-performance.md}).
     */
    private static final ThreadLocal<Boolean> COMPLETE_ANSWER_SET_SEEN_THIS_TURN = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Integer> FETCH_AFTER_COMPLETE_ANSWER_SET_COUNT = ThreadLocal.withInitial(() -> 0);

    /**
     * Count of synthetic {@code REPETITION_BLOCKED} tool envelopes returned this {@link com.thingworx.things.agent.AgentLoop}
     * run ({@code docs/agent/multi-chart-and-thrashing-safeguards.md} §3).
     */
    private static final ThreadLocal<Integer> REPETITION_BLOCKED_COUNT_THIS_LOOP = ThreadLocal.withInitial(() -> 0);

    /** Set when {@link DocumentSearchProgressGuard} requests a forced tool-none summary round. */
    private static final ThreadLocal<Boolean> DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Set alongside {@link #DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY} when the forced round was requested by the
     * C1 retrieval-saturation path (document-retrieval-convergence). Distinguishes a coverage-grounded
     * finalize (must state what the manual does/does not cover) from the plain fingerprint/zero-fetch summary.
     */
    private static final ThreadLocal<Boolean> DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** When true, v1b skill {@code task.state} emission is suppressed; playbook owns the process panel. */
    private static final ThreadLocal<Boolean> PLAYBOOK_TASK_PROGRESS_ACTIVE = new ThreadLocal<>();
    /**
     * Last qualifying tabular {@code cacheId} for {@link CachedTabularLastCacheHandle#TOKEN} resolution across
     * separate agent turns in the same JVM. Map keys:
     * <ul>
     *   <li><b>Persistent</b> {@code conversation_id} (non-{@link #SINGLE_TURN_CONVERSATION_ID}) — survives
     *       {@link #clear()} so a follow-up user message can resolve TOKEN until TTL / explicit removal.</li>
     *   <li>{@link #SINGLE_TURN_CONVERSATION_ID} with {@link #getParlerRequestId()} set — {@code __single_turn__\u0001 + requestId}
     *       so concurrent AlwaysOn turns do not share one key; removed in {@link #clear()}.</li>
     *   <li>{@link #SINGLE_TURN_CONVERSATION_ID} without request id — plain {@code __single_turn__}; removed in {@link #clear()}.</li>
     * </ul>
     * {@link #setConversationId(String)} removes the previous key when the id string changes. No TTL / reaper —
     * JVM restart clears the map; {@link com.thingworx.things.agent.AgentThing#ClearConversation} removes the mirror
     * for a cleared thread id.
     */
    public static final String SINGLE_TURN_CONVERSATION_ID = "__single_turn__";
    private static final char SINGLE_TURN_MIRROR_KEY_SEP = '\u0001';

    private static final ConcurrentMap<String, String> LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION =
            new ConcurrentHashMap<>();

    private AgentToolContext() {}

    /** @param conversationId may be null or empty (single-turn / no persistent thread) */
    public static void setConversationId(String conversationId) {
        String newId = (conversationId != null && !conversationId.isEmpty()) ? conversationId : SINGLE_TURN_CONVERSATION_ID;
        String prev = CONVERSATION_ID.get();
        if (prev != null && !prev.equals(newId)) {
            LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.remove(
                    tabularTokenMirrorMapKeyForConversationAndRequest(prev, PARLER_REQUEST_ID.get()));
        }
        CONVERSATION_ID.set(newId);
        // U2: remint opaque ArtifactCache scope when conversation namespace changes.
        if (prev == null || !prev.equals(newId)) {
            com.thingworx.things.agent.cache.TabularArtifactHub.ensureRunInvocationBoundToConversation(newId);
        }
    }

    public static String getConversationId() {
        String s = CONVERSATION_ID.get();
        return s != null ? s : SINGLE_TURN_CONVERSATION_ID;
    }

    /**
     * Map key for {@link #LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION} under current thread locals.
     */
    static String tabularTokenMirrorMapKey() {
        return tabularTokenMirrorMapKeyForConversationAndRequest(CONVERSATION_ID.get(), PARLER_REQUEST_ID.get());
    }

    static String tabularTokenMirrorMapKeyForConversationAndRequest(String conversationId, String parlerRequestId) {
        String conv = (conversationId != null && !conversationId.isEmpty()) ? conversationId : SINGLE_TURN_CONVERSATION_ID;
        if (SINGLE_TURN_CONVERSATION_ID.equals(conv)) {
            if (parlerRequestId != null && !parlerRequestId.isEmpty()) {
                return SINGLE_TURN_CONVERSATION_ID + SINGLE_TURN_MIRROR_KEY_SEP + parlerRequestId;
            }
        }
        return conv;
    }

    public static void setAgentThing(AgentThing thing) {
        AGENT_THING.set(thing);
        if (thing != null && RUN_INVOCATION.get() == null) {
            com.thingworx.things.agent.cache.TabularArtifactHub
                    .ensureRunInvocationBoundToConversation(getConversationId());
        }
    }

    /** @return null if not inside an agent chat turn */
    public static AgentThing getAgentThing() {
        return AGENT_THING.get();
    }

    /** Core-created U2 invocation context for ArtifactCache namespace + budgets; may be null. */
    public static void setRunInvocationContext(
            com.thingworx.things.agent.execution.RunInvocationContext ctx) {
        if (ctx == null) {
            RUN_INVOCATION.remove();
        } else {
            RUN_INVOCATION.set(ctx);
        }
    }

    public static com.thingworx.things.agent.execution.RunInvocationContext getRunInvocationContext() {
        return RUN_INVOCATION.get();
    }

    /** @param remoteConversation bound RemoteThing for active AlwaysOn turn; {@code null} clears */
    public static void setParlerRemoteConversation(Thing remoteConversation) {
        if (remoteConversation == null) {
            PARLER_REMOTE_CONVERSATION.remove();
        } else {
            PARLER_REMOTE_CONVERSATION.set(remoteConversation);
        }
    }

    /** @return active Parler stream RemoteThing, or {@code null} */
    public static Thing getParlerRemoteConversation() {
        return PARLER_REMOTE_CONVERSATION.get();
    }

    public static void setParlerDownlinkOk(AtomicBoolean downlinkOk) {
        if (downlinkOk == null) {
            PARLER_DOWNLINK_OK.remove();
        } else {
            PARLER_DOWNLINK_OK.set(downlinkOk);
        }
    }

    public static AtomicBoolean getParlerDownlinkOk() {
        return PARLER_DOWNLINK_OK.get();
    }

    /** Principal string for {@link com.thingworx.things.agent.ParlerRunningTurnCancelRegistry} keys (Parler worker thread). */
    public static void setParlerGatewayCallerPrincipal(String principalOrNull) {
        if (principalOrNull == null || principalOrNull.isEmpty()) {
            PARLER_GATEWAY_CALLER_PRINCIPAL.remove();
        } else {
            PARLER_GATEWAY_CALLER_PRINCIPAL.set(principalOrNull);
        }
    }

    /** @return null when not in a Parler AlwaysOn turn with principal bound */
    public static String getParlerGatewayCallerPrincipal() {
        return PARLER_GATEWAY_CALLER_PRINCIPAL.get();
    }

    /**
     * Test-only: {@link com.thingworx.things.agent.ParlerRunningTurnCancelRegistry} keys include agent thing name; unit
     * tests that run {@link com.thingworx.things.agent.AgentLoop} without a bound {@link AgentThing} set this to match
     * {@code register} / {@code tryRequestCancel}. Pass {@code null} or blank to clear.
     */
    public static void setParlerRunningCancelAgentThingNameForTests(String agentThingNameOrNull) {
        if (agentThingNameOrNull == null || agentThingNameOrNull.isEmpty()) {
            PARLER_RUNNING_CANCEL_AGENT_NAME_TEST.remove();
        } else {
            PARLER_RUNNING_CANCEL_AGENT_NAME_TEST.set(agentThingNameOrNull);
        }
    }

    /** Agent thing name for running-cancel registry keys — test override or live {@link #getAgentThing()}. */
    public static String parlerRunningCancelAgentThingNameForRegistry() {
        String o = PARLER_RUNNING_CANCEL_AGENT_NAME_TEST.get();
        if (o != null) {
            return o;
        }
        AgentThing at = getAgentThing();
        return at != null ? at.getName() : "";
    }

    /**
     * Per AlwaysOn turn: slash skills for checklist parsing and PendingApproval snapshot; {@code null} clears.
     */
    public static void setParlerSlashSkillShortNamesForTurn(List<String> slashSkillShortIdsOrNull) {
        if (slashSkillShortIdsOrNull == null || slashSkillShortIdsOrNull.isEmpty()) {
            PARLER_SLASH_SKILL_SHORT_IDS.remove();
        } else {
            PARLER_SLASH_SKILL_SHORT_IDS.set(new ArrayList<>(slashSkillShortIdsOrNull));
        }
    }

    /** @return mutable snapshot for {@link PendingApprovalRecord}, or {@code null} when none */
    public static List<String> snapshotSlashSkillShortNamesForPending() {
        List<String> l = PARLER_SLASH_SKILL_SHORT_IDS.get();
        if (l == null || l.isEmpty()) {
            return null;
        }
        return new ArrayList<>(l);
    }

    public static void setParlerStreamIds(String requestId, String remoteThingName) {
        if (requestId != null && !requestId.isEmpty()) {
            PARLER_REQUEST_ID.set(requestId);
        } else {
            PARLER_REQUEST_ID.remove();
        }
        if (remoteThingName != null && !remoteThingName.isEmpty()) {
            PARLER_REMOTE_THING_NAME.set(remoteThingName);
        } else {
            PARLER_REMOTE_THING_NAME.remove();
        }
    }

    public static String getParlerRequestId() {
        return PARLER_REQUEST_ID.get();
    }

    public static String getParlerRemoteThingName() {
        return PARLER_REMOTE_THING_NAME.get();
    }

    public static void setParlerActiveMessages(List<ChatMessage> messages) {
        PARLER_ACTIVE_MESSAGES.set(messages);
    }

    public static List<ChatMessage> getParlerActiveMessages() {
        return PARLER_ACTIVE_MESSAGES.get();
    }

    /** Sets the terminal assistant text for the current turn after successful {@code start_playbook}. */
    public static void setPlaybookTerminalAnswer(String assistantText) {
        if (assistantText != null && !assistantText.isBlank()) {
            PLAYBOOK_TERMINAL_ANSWER.set(assistantText);
        } else {
            PLAYBOOK_TERMINAL_ANSWER.remove();
        }
    }

    /**
     * Returns and clears the terminal playbook answer for the active AgentLoop turn, or {@code null} if none.
     */
    public static String consumePlaybookTerminalAnswer() {
        String answer = PLAYBOOK_TERMINAL_ANSWER.get();
        PLAYBOOK_TERMINAL_ANSWER.remove();
        return answer;
    }

    /** While true, skill v1b {@code task.state} hooks are suppressed in favor of playbook progress wire. */
    public static void setPlaybookTaskProgressActive(boolean active) {
        if (active) {
            PLAYBOOK_TASK_PROGRESS_ACTIVE.set(Boolean.TRUE);
        } else {
            PLAYBOOK_TASK_PROGRESS_ACTIVE.remove();
        }
    }

    public static boolean isPlaybookTaskProgressActive() {
        return Boolean.TRUE.equals(PLAYBOOK_TASK_PROGRESS_ACTIVE.get());
    }

    /** @param indices per-turn ephemeral system row indices; {@code null} clears */
    public static void setParlerEphemeralSystemIndices(ParlerEphemeralSystemIndices indices) {
        if (indices == null) {
            PARLER_EPHEMERAL_SYSTEM_INDICES.remove();
        } else {
            PARLER_EPHEMERAL_SYSTEM_INDICES.set(indices);
        }
    }

    /** @return ephemeral indices for the active Parler stream turn, or {@code null} */
    public static ParlerEphemeralSystemIndices getParlerEphemeralSystemIndices() {
        return PARLER_EPHEMERAL_SYSTEM_INDICES.get();
    }

    public static void resetTabularChartRound() {
        TabularChartRoundState s = TABULAR_CHART_ROUND.get();
        if (s == null) {
            TABULAR_CHART_ROUND.set(new TabularChartRoundState());
        } else {
            s.reset();
        }
    }

    /**
     * Next stable chart id for the active user <b>request</b> ({@code c1}, {@code c2}, …). The counter lives on
     * {@link TabularChartRoundState}, so an approval pause carries it in the pending snapshot and a chart built
     * after approval continues the numbering instead of restarting at {@code c1}.
     */
    public static String nextParlerChartId() {
        return tabularChartRoundState().nextChartId();
    }

    /** The turn's chart-round state when one exists; never creates one (used at turn end, before {@link #clear()}). */
    public static TabularChartRoundState tabularChartRoundStateOrNull() {
        return TABULAR_CHART_ROUND.get();
    }

    public static TabularChartRoundState tabularChartRoundState() {
        TabularChartRoundState s = TABULAR_CHART_ROUND.get();
        if (s == null) {
            s = new TabularChartRoundState();
            TABULAR_CHART_ROUND.set(s);
        }
        return s;
    }

    public static void setChartGroupsJsonForFinalAssistantRow(String json) {
        if (json == null || json.isBlank()) {
            CHART_GROUPS_JSON_FINAL_ROW.remove();
        } else {
            CHART_GROUPS_JSON_FINAL_ROW.set(json);
        }
    }

    /**
     * JSON array text of the request's final chart-group manifests, or empty when no group was declared. The
     * value is set after {@link #clear()} by {@code ChartGroupTurnHooks.onTurnEnd} and is consumed by this read,
     * so it reaches exactly one final assistant row and cannot leak into a later turn on the same thread.
     */
    public static String takeChartGroupsJsonForFinalAssistantRow() {
        String s = CHART_GROUPS_JSON_FINAL_ROW.get();
        CHART_GROUPS_JSON_FINAL_ROW.remove();
        return s != null ? s : "";
    }

    public static void addPendingParlerChartBlock(JSONObject chart) {
        if (chart == null) {
            return;
        }
        ArrayDeque<JSONObject> d = PARLER_PENDING_CHART_BLOCKS.get();
        if (d == null) {
            d = new ArrayDeque<>();
            PARLER_PENDING_CHART_BLOCKS.set(d);
        }
        d.addLast(chart);
    }

    /**
     * Drains all pending Parler chart wire payloads (FIFO order) and clears the queue.
     *
     * @return non-empty list, or empty when none were pending
     */
    public static List<JSONObject> drainPendingParlerChartBlocks() {
        ArrayDeque<JSONObject> d = PARLER_PENDING_CHART_BLOCKS.get();
        if (d == null || d.isEmpty()) {
            return List.of();
        }
        List<JSONObject> out = new ArrayList<>(d);
        d.clear();
        return out;
    }

    /**
     * Resets chart artifact observability for one {@link com.thingworx.things.agent.AgentLoop} run
     * (Parler stream paths only; callers pass {@code false} when not applicable).
     */
    /**
     * Clears Parler chart wire + chart-attempt observability at the start of a Parler stream turn (before
     * {@link com.thingworx.things.agent.AgentLoop#run}).
     */
    public static void resetChartArtifactObservabilityForTurn() {
        PARLER_CHART_WIRE_EMITTED_COUNT.remove();
        PARLER_CHARTED_SOURCE_CACHE_IDS.remove();
        PARLER_CHART_BUILD_ATTEMPTED.remove();
        PARLER_CHART_BUILD_FAILED_RECOVERABLY.remove();
    }

    public static void markChartBuildAttemptedThisTurn() {
        PARLER_CHART_BUILD_ATTEMPTED.set(Boolean.TRUE);
    }

    public static boolean chartBuildAttemptedThisTurn() {
        return Boolean.TRUE.equals(PARLER_CHART_BUILD_ATTEMPTED.get());
    }

    public static void markChartBuildFailedRecoverablyThisTurn() {
        PARLER_CHART_BUILD_FAILED_RECOVERABLY.set(Boolean.TRUE);
    }

    public static boolean chartBuildFailedRecoverablyThisTurn() {
        return Boolean.TRUE.equals(PARLER_CHART_BUILD_FAILED_RECOVERABLY.get());
    }

    /**
     * Whether {@link TabularChartRoundState} holds a chartable tabular source (cached id resolving to a non-empty table,
     * or non-empty inline rows) for chart-rescue gating.
     */
    public static boolean chartableTabularSourceAvailableForRescue() {
        TabularChartRoundState st = tabularChartRoundState();
        String cid = st.getLastCacheId();
        if (cid != null && !cid.isBlank()) {
            try {
                InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(cid);
                return t != null && t.getRowCount() > 0;
            } catch (ArtifactCacheException e) {
                ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
                return false;
            }
        }
        return st.hasChartableLastInvokeTarget();
    }

    /**
     * Post-marker / chart-rescue tool exposure: model attempted {@code build_chart_from_tabular_result}, no chart wire
     * was emitted, a recoverable chart error occurred, and a chartable tabular source is available.
     */
    public static boolean eligibleChartRescueToolExposureForTurn() {
        if (!chartBuildAttemptedThisTurn()) {
            return false;
        }
        if (parlerChartWireEmittedCountForTurnPerf() != 0) {
            return false;
        }
        if (!chartBuildFailedRecoverablyThisTurn()) {
            return false;
        }
        return chartableTabularSourceAvailableForRescue();
    }

    /** Call after each successful {@code ReceiveMessage} downlink of {@code type: chart}. */
    public static void markParlerChartWireEmitted() {
        markParlerChartWireEmitted(null);
    }

    /**
     * Same, recording the chart's {@code source.sourceCacheId} when it has one. A chart without a source cache id
     * (the numeric-history line that {@code query_property_history} emits on its own) records nothing, so it does
     * not count as having charted any tabulate artifact.
     */
    public static void markParlerChartWireEmitted(String sourceCacheIdOrNull) {
        if (sourceCacheIdOrNull != null && !sourceCacheIdOrNull.trim().isEmpty()) {
            java.util.Set<String> ids = PARLER_CHARTED_SOURCE_CACHE_IDS.get();
            if (ids == null) {
                ids = new java.util.LinkedHashSet<>();
                PARLER_CHARTED_SOURCE_CACHE_IDS.set(ids);
            }
            ids.add(sourceCacheIdOrNull.trim());
        }
        Integer cur = PARLER_CHART_WIRE_EMITTED_COUNT.get();
        int n = cur != null ? cur : 0;
        if (n < Integer.MAX_VALUE - 1) {
            PARLER_CHART_WIRE_EMITTED_COUNT.set(n + 1);
        }
    }

    /** Count of chart wire frames downlinked this Parler turn (for {@code LLM_TURN_PERFORMANCE}). */
    public static int parlerChartWireEmittedCountForTurnPerf() {
        Integer n = PARLER_CHART_WIRE_EMITTED_COUNT.get();
        return n != null ? Math.max(0, n) : 0;
    }

    /**
     * @return whether the model invoked {@code build_chart_from_tabular_result} this turn but no {@code type: chart}
     *         wire was successfully downlinked ({@code docs/agent/multi-chart-and-thrashing-safeguards.md} —
     *         no user-prompt keyword heuristic).
     */
    public static boolean chartExpectedButMissingForTurnPerf() {
        return chartBuildAttemptedThisTurn() && parlerChartWireEmittedCountForTurnPerf() == 0;
    }

    /** Marks {@code chartRescueAttempted} on terminal turn performance / merged {@code llm_usage}. */
    public static void markParlerChartEndTurnRescueAttemptedForTurnPerf() {
        PARLER_CHART_END_TURN_RESCUE_ATTEMPTED.set(Boolean.TRUE);
    }

    public static boolean chartEndTurnRescueAttemptedForTurnPerf() {
        return Boolean.TRUE.equals(PARLER_CHART_END_TURN_RESCUE_ATTEMPTED.get());
    }

    /** @param canonicalIana may be null to clear */
    public static void setUserIanaTimezone(String canonicalIana) {
        if (canonicalIana != null && !canonicalIana.isEmpty()) {
            USER_IANA_TIMEZONE.set(canonicalIana);
        } else {
            USER_IANA_TIMEZONE.remove();
        }
    }

    /** @return canonical IANA or null if not in a Parler turn with timezone */
    public static String getUserIanaTimezone() {
        return USER_IANA_TIMEZONE.get();
    }

    /** @param json may be null or empty to clear */
    public static void setHostContextJson(String json) {
        if (json != null && !json.isEmpty()) {
            HOST_CONTEXT_JSON.set(json);
        } else {
            HOST_CONTEXT_JSON.remove();
        }
    }

    /** @return raw host-scope JSON for this agent turn (Parler or Chat), or null */
    public static String getHostContextJson() {
        return HOST_CONTEXT_JSON.get();
    }

    /** Stores the host-context-resolved document scope for this turn; null/empty clears it. */
    public static void setInjectedDocumentScope(List<String> documentIds, String resolverSource) {
        if (documentIds == null || documentIds.isEmpty()) {
            INJECTED_DOCUMENT_SCOPE_IDS.remove();
            INJECTED_DOCUMENT_SCOPE_SOURCE.remove();
            return;
        }
        INJECTED_DOCUMENT_SCOPE_IDS.set(new ArrayList<>(documentIds));
        if (resolverSource != null && !resolverSource.isEmpty()) {
            INJECTED_DOCUMENT_SCOPE_SOURCE.set(resolverSource);
        } else {
            INJECTED_DOCUMENT_SCOPE_SOURCE.remove();
        }
    }

    /** @return the host-context-resolved document scope ids for this turn, or null */
    public static List<String> getInjectedDocumentScopeIds() {
        List<String> ids = INJECTED_DOCUMENT_SCOPE_IDS.get();
        return ids != null ? List.copyOf(ids) : null;
    }

    /** @return the resolver source (`custom` / `default-match`) of the injected scope, or null */
    public static String getInjectedDocumentScopeSource() {
        return INJECTED_DOCUMENT_SCOPE_SOURCE.get();
    }

    public static void setFetchCachedChatTurnNonce(String uuidOrNull) {
        if (uuidOrNull != null && !uuidOrNull.isEmpty()) {
            FETCH_CACHED_CHAT_TURN_NONCE.set(uuidOrNull);
        } else {
            FETCH_CACHED_CHAT_TURN_NONCE.remove();
        }
    }

    /** @return nonce for Chat replay-guard scoping, or {@code null} */
    public static String getFetchCachedChatTurnNonce() {
        return FETCH_CACHED_CHAT_TURN_NONCE.get();
    }

    public static void setFetchCachedStreamJsonForToolCall(String toolCallId, String fullSuccessJson) {
        if (toolCallId == null || toolCallId.isEmpty() || fullSuccessJson == null) {
            return;
        }
        FETCH_CACHED_STREAM_JSON_BY_TOOL_CALL_ID.get().put(toolCallId, fullSuccessJson);
    }

    /**
     * Returns the full {@code fetch_cached_result} success JSON for live UI table downlink, if registered for this
     * tool call id, without removing it (see {@link #takeFetchCachedStreamJsonForToolCall} for removal).
     */
    public static String peekFetchCachedStreamJsonForToolCall(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, String> m = FETCH_CACHED_STREAM_JSON_BY_TOOL_CALL_ID.get();
        return m != null ? m.get(toolCallId) : null;
    }

    /** Removes and returns the full JSON for stream/table wire, if any. */
    public static String takeFetchCachedStreamJsonForToolCall(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, String> m = FETCH_CACHED_STREAM_JSON_BY_TOOL_CALL_ID.get();
        return m != null ? m.remove(toolCallId) : null;
    }

    /** Holds augmented full fetch JSON between persist and downlink for one tool call id (same thread). */
    public static void setFetchCachedFullAugmentedForDownlink(String toolCallId, String fullAugmentedToolJson) {
        if (toolCallId == null || toolCallId.isEmpty() || fullAugmentedToolJson == null) {
            return;
        }
        FETCH_CACHED_FULL_AUGMENTED_JSON_BY_TOOL_CALL_ID.get().put(toolCallId, fullAugmentedToolJson);
    }

    /** @return augmented full tool JSON and removes the entry */
    public static String takeFetchCachedFullAugmentedForDownlink(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, String> m = FETCH_CACHED_FULL_AUGMENTED_JSON_BY_TOOL_CALL_ID.get();
        return m != null ? m.remove(toolCallId) : null;
    }

    public static void setToolEgressFullJsonForToolCall(String toolCallId, String fullSuccessJson) {
        if (toolCallId == null || toolCallId.isEmpty() || fullSuccessJson == null) {
            return;
        }
        TOOL_EGRESS_FULL_JSON_BY_TOOL_CALL_ID.get().put(toolCallId, fullSuccessJson);
    }

    public static String peekToolEgressFullJsonForToolCall(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, String> m = TOOL_EGRESS_FULL_JSON_BY_TOOL_CALL_ID.get();
        return m != null ? m.get(toolCallId) : null;
    }

    public static String takeToolEgressFullJsonForToolCall(String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        Map<String, String> m = TOOL_EGRESS_FULL_JSON_BY_TOOL_CALL_ID.get();
        return m != null ? m.remove(toolCallId) : null;
    }

    public static void setAgentTaskState(AgentTaskState state) {
        if (state == null) {
            AGENT_TASK_STATE.remove();
        } else {
            AGENT_TASK_STATE.set(state);
        }
    }

    /** @return per-turn task state for {@code invoke_service} / {@code fetch_cached_result} evidence, or null */
    public static AgentTaskState getAgentTaskState() {
        return AGENT_TASK_STATE.get();
    }

    /**
     * Records the latest qualifying tabular {@code cacheId} for TOKEN fallback for the active {@link #getConversationId()}.
     */
    public static void noteLastQualifyingTabularCacheIdForConversation(String cacheId) {
        if (cacheId == null || cacheId.isEmpty()) {
            return;
        }
        LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.put(tabularTokenMirrorMapKey(), cacheId);
    }

    /**
     * Parses a {@code summarize_cached_result} tool JSON body; on {@code status: success}, updates the P2 TOKEN
     * mirror from {@code sourceCacheId} without touching {@link TabularChartRoundState} (see {@link TabularChartRoundHooks}).
     */
    public static void noteTokenMirrorFromSummarizeCachedResultJson(String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(jsonBody);
            if (!"success".equals(root.optString("status", ""))) {
                return;
            }
            if (!root.has("sourceCacheId") || root.isNull("sourceCacheId")) {
                return;
            }
            String sid = root.getString("sourceCacheId");
            if (sid == null || sid.isEmpty()) {
                return;
            }
            sid = sid.trim();
            if (sid.isEmpty()) {
                return;
            }
            noteLastQualifyingTabularCacheIdForConversation(sid);
        } catch (Exception ignored) {
            // Malformed JSON or wrong types: skip mirror update.
        }
    }

    /**
     * Clears the conversation-scoped TOKEN fallback (e.g. last qualifying tabular was inline-only).
     */
    public static void clearLastQualifyingTabularCacheIdForConversation() {
        LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.remove(tabularTokenMirrorMapKey());
    }

    /** @return last recorded qualifying {@code cacheId} for this conversation, or {@code null} */
    public static String getConversationLastQualifyingTabularCacheId() {
        return LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.get(tabularTokenMirrorMapKey());
    }

    /**
     * Removes the TOKEN mirror for a persistent {@code conversationId} (e.g. {@link com.thingworx.things.agent.AgentThing#ClearConversation}).
     */
    public static void removeLastQualifyingTabularCacheMirrorForConversationId(String conversationId) {
        if (conversationId == null || conversationId.isEmpty() || SINGLE_TURN_CONVERSATION_ID.equals(conversationId)) {
            return;
        }
        LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.remove(conversationId);
    }

    /**
     * If the TOKEN mirror for the current thread key still points at {@code resolvedReadCacheId} (e.g. after
     * {@link com.thingworx.things.agent.tools.InvokeServiceExecutor#lookupCachedInfotable(String)} returned null),
     * removes that mirror entry so TOKEN does not keep resolving to an expired id.
     */
    public static void pruneTabularTokenMirrorIfPointsTo(String resolvedReadCacheId) {
        if (resolvedReadCacheId == null || resolvedReadCacheId.isEmpty()) {
            return;
        }
        String k = tabularTokenMirrorMapKey();
        String v = LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.get(k);
        if (resolvedReadCacheId.equals(v)) {
            LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.remove(k);
        }
    }

    /**
     * Clears per-turn complete-answer + fetch-after-complete counters at {@link com.thingworx.things.agent.AgentLoop}
     * start ({@code docs/agent/llm-performance.md}).
     */
    public static void resetLlmTurnPerformanceFlagsForAgentLoop() {
        COMPLETE_ANSWER_SET_SEEN_THIS_TURN.set(Boolean.FALSE);
        FETCH_AFTER_COMPLETE_ANSWER_SET_COUNT.set(0);
        PARLER_CHART_END_TURN_RESCUE_ATTEMPTED.remove();
        REPETITION_BLOCKED_COUNT_THIS_LOOP.remove();
        DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY.remove();
        DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY.remove();
        PARLER_CHART_BUILD_ATTEMPTED.remove();
        PARLER_CHART_BUILD_FAILED_RECOVERABLY.remove();
        PRESENTATION_PHASE_ROUNDS_USED.remove();
        PRESENTATION_PHASE_ENTERED_THIS_LOOP.remove();
        PRESENTATION_ACTIONS_REQUESTED.remove();
        PRESENTATION_ACTIONS_EXECUTED.remove();
        PRESENTATION_ACTIONS_BLOCKED.remove();
    }

    public static int getPresentationPhaseRoundsUsedThisTurn() {
        Integer n = PRESENTATION_PHASE_ROUNDS_USED.get();
        return n != null ? Math.max(0, n) : 0;
    }

    public static void incrementPresentationPhaseRoundsUsedThisTurn() {
        int c = getPresentationPhaseRoundsUsedThisTurn();
        if (c < Integer.MAX_VALUE - 1) {
            PRESENTATION_PHASE_ROUNDS_USED.set(c + 1);
        }
    }

    public static void markPresentationPhaseEnteredThisLoop() {
        PRESENTATION_PHASE_ENTERED_THIS_LOOP.set(Boolean.TRUE);
    }

    public static boolean presentationPhaseEnteredThisLoop() {
        return Boolean.TRUE.equals(PRESENTATION_PHASE_ENTERED_THIS_LOOP.get());
    }

    public static void addPresentationActionsRequested(int delta) {
        if (delta <= 0) {
            return;
        }
        int v = Math.max(0, PRESENTATION_ACTIONS_REQUESTED.get()) + delta;
        PRESENTATION_ACTIONS_REQUESTED.set(Math.min(v, Integer.MAX_VALUE - 1));
    }

    public static void addPresentationActionsExecuted(int delta) {
        if (delta <= 0) {
            return;
        }
        int v = Math.max(0, PRESENTATION_ACTIONS_EXECUTED.get()) + delta;
        PRESENTATION_ACTIONS_EXECUTED.set(Math.min(v, Integer.MAX_VALUE - 1));
    }

    public static void addPresentationActionsBlocked(int delta) {
        if (delta <= 0) {
            return;
        }
        int v = Math.max(0, PRESENTATION_ACTIONS_BLOCKED.get()) + delta;
        PRESENTATION_ACTIONS_BLOCKED.set(Math.min(v, Integer.MAX_VALUE - 1));
    }

    public static int getPresentationActionsRequestedForTurnPerf() {
        return Math.max(0, PRESENTATION_ACTIONS_REQUESTED.get());
    }

    public static int getPresentationActionsExecutedForTurnPerf() {
        return Math.max(0, PRESENTATION_ACTIONS_EXECUTED.get());
    }

    public static int getPresentationActionsBlockedForTurnPerf() {
        return Math.max(0, PRESENTATION_ACTIONS_BLOCKED.get());
    }

    /**
     * Post-marker Answer Presentation Phase exposure: a complete chartable tabular artifact exists that no
     * chart downlinked this turn was built from, presentation round budget remains, and chart-rescue gate is not
     * active (rescue owns the singleton when true).
     *
     * <p>The test is per artifact, not "no chart wire yet". {@code query_property_history} downlinks a numeric line
     * chart by itself; with the old turn-wide test that chart closed the presentation round, so a history query
     * followed by {@code bin_numeric} / {@code box_summary} / {@code group_metric} in the same turn could never be
     * charted. An artifact that already is the source of a downlinked chart still does not reopen the round.</p>
     */
    public static boolean eligibleAnswerPresentationPhasePostMarkerExposure() {
        if (eligibleChartRescueToolExposureForTurn()) {
            return false;
        }
        if (getPresentationPhaseRoundsUsedThisTurn() >= 1) {
            return false;
        }
        return PresentationArtifactRegistry.hasCompleteChartable(FetchCachedReplayGuard.resolveCurrentTurnKey(),
                chartedSourceCacheIdsThisTurn());
    }

    /** Source cache ids of the charts downlinked this turn; never {@code null}. */
    public static java.util.Set<String> chartedSourceCacheIdsThisTurn() {
        java.util.Set<String> ids = PARLER_CHARTED_SOURCE_CACHE_IDS.get();
        return ids == null ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    /** Increments once per synthetic {@code REPETITION_BLOCKED} envelope ({@code ConsecutiveIdenticalToolCallTracker}). */
    public static void incrementRepetitionBlockedCountForTurnPerf() {
        int c = REPETITION_BLOCKED_COUNT_THIS_LOOP.get();
        if (c < Integer.MAX_VALUE - 1) {
            REPETITION_BLOCKED_COUNT_THIS_LOOP.set(c + 1);
        }
    }

    public static int getRepetitionBlockedCountForTurnPerf() {
        return Math.max(0, REPETITION_BLOCKED_COUNT_THIS_LOOP.get());
    }

    public static void requestForcedSummaryForDocumentSearchLoop() {
        DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY.set(Boolean.TRUE);
    }

    /** Returns {@code true} once per request; subsequent calls return {@code false} until re-requested. */
    public static boolean consumeDocumentSearchLoopForcedSummary() {
        if (Boolean.TRUE.equals(DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY.get())) {
            DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY.set(Boolean.FALSE);
            return true;
        }
        return false;
    }

    /** C1: the requested forced summary must be a coverage-grounded finalize (states what is/isn't covered). */
    public static void requestGroundedCoverageSummary() {
        DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY.set(Boolean.TRUE);
    }

    /** Returns {@code true} once per request; subsequent calls return {@code false} until re-requested. */
    public static boolean consumeGroundedCoverageSummary() {
        if (Boolean.TRUE.equals(DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY.get())) {
            DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY.set(Boolean.FALSE);
            return true;
        }
        return false;
    }

    /** Outcome of the post-tool-batch forced-summary checkpoint. */
    public static final class ForcedSummaryDecision {
        private final boolean forceSummary;
        private final boolean coverageGuidance;

        public ForcedSummaryDecision(boolean forceSummary, boolean coverageGuidance) {
            this.forceSummary = forceSummary;
            this.coverageGuidance = coverageGuidance;
        }

        public boolean forceSummary() {
            return forceSummary;
        }

        public boolean coverageGuidance() {
            return coverageGuidance;
        }
    }

    /**
     * Resolves the forced tool-none summary decision after a tool batch, consuming the document-search and
     * grounded-coverage flags. The repetition-blocked path and the document-search saturation path
     * <em>compose</em>: a pending grounded-coverage request (C1) rides whichever forced-summary path fires,
     * so saturation coverage is never dropped just because repetition blocking also tripped this turn.
     * Coverage is only consumed when a summary is actually
     * forced, so a stray coverage flag cannot be lost without a finalize round to carry it.
     *
     * @param repetitionForcedSummary whether the repetition-blocked threshold already forces a summary
     */
    public static ForcedSummaryDecision resolveForcedSummaryDecision(boolean repetitionForcedSummary) {
        boolean documentSearchForcedSummary = consumeDocumentSearchLoopForcedSummary();
        boolean forceSummary = repetitionForcedSummary || documentSearchForcedSummary;
        boolean coverageGuidance = forceSummary && consumeGroundedCoverageSummary();
        return new ForcedSummaryDecision(forceSummary, coverageGuidance);
    }

    public static void markCompleteAnswerSetSeenThisTurn() {
        COMPLETE_ANSWER_SET_SEEN_THIS_TURN.set(Boolean.TRUE);
    }

    public static boolean isCompleteAnswerSetSeenThisTurn() {
        return Boolean.TRUE.equals(COMPLETE_ANSWER_SET_SEEN_THIS_TURN.get());
    }

    public static int getFetchAfterCompleteAnswerSetCount() {
        Integer n = FETCH_AFTER_COMPLETE_ANSWER_SET_COUNT.get();
        return n != null ? n : 0;
    }

    public static void incrementFetchAfterCompleteAnswerSetIfMarked() {
        if (isCompleteAnswerSetSeenThisTurn()) {
            FETCH_AFTER_COMPLETE_ANSWER_SET_COUNT.set(getFetchAfterCompleteAnswerSetCount() + 1);
        }
    }

    /** Clears when {@code key} is null or blank. */
    public static void setLastResolvedAssetTypeKey(String key) {
        if (key == null || key.isBlank()) {
            LAST_RESOLVED_ASSET_TYPE_KEY.remove();
        } else {
            LAST_RESOLVED_ASSET_TYPE_KEY.set(key);
        }
    }

    /** @return null when no successful {@code resolve_asset_type} narrowed this turn */
    public static String getLastResolvedAssetTypeKey() {
        return LAST_RESOLVED_ASSET_TYPE_KEY.get();
    }

    public static void clear() {
        String conv = CONVERSATION_ID.get();
        String rid = PARLER_REQUEST_ID.get();
        if (conv == null || SINGLE_TURN_CONVERSATION_ID.equals(conv)) {
            LAST_QUALIFYING_TABULAR_CACHE_ID_BY_CONVERSATION.remove(tabularTokenMirrorMapKeyForConversationAndRequest(conv, rid));
        }
        COMPLETE_ANSWER_SET_SEEN_THIS_TURN.remove();
        FETCH_AFTER_COMPLETE_ANSWER_SET_COUNT.remove();
        InvokeServicePolicyBatchCache.clear();
        CONVERSATION_ID.remove();
        AGENT_THING.remove();
        RUN_INVOCATION.remove();
        PARLER_REQUEST_ID.remove();
        PARLER_REMOTE_THING_NAME.remove();
        PARLER_ACTIVE_MESSAGES.remove();
        TABULAR_CHART_ROUND.remove();
        PARLER_PENDING_CHART_BLOCKS.remove();
        CHART_GROUPS_JSON_FINAL_ROW.remove();
        PARLER_CHART_BUILD_ATTEMPTED.remove();
        PARLER_CHART_BUILD_FAILED_RECOVERABLY.remove();
        PARLER_CHART_WIRE_EMITTED_COUNT.remove();
        PARLER_CHARTED_SOURCE_CACHE_IDS.remove();
        PARLER_CHART_END_TURN_RESCUE_ATTEMPTED.remove();
        USER_IANA_TIMEZONE.remove();
        HOST_CONTEXT_JSON.remove();
        INJECTED_DOCUMENT_SCOPE_IDS.remove();
        INJECTED_DOCUMENT_SCOPE_SOURCE.remove();
        PARLER_EPHEMERAL_SYSTEM_INDICES.remove();
        AGENT_TASK_STATE.remove();
        PARLER_REMOTE_CONVERSATION.remove();
        PARLER_DOWNLINK_OK.remove();
        PARLER_GATEWAY_CALLER_PRINCIPAL.remove();
        PARLER_RUNNING_CANCEL_AGENT_NAME_TEST.remove();
        PARLER_SLASH_SKILL_SHORT_IDS.remove();
        PLAYBOOK_TERMINAL_ANSWER.remove();
        PLAYBOOK_TASK_PROGRESS_ACTIVE.remove();
        FETCH_CACHED_CHAT_TURN_NONCE.remove();
        FETCH_CACHED_STREAM_JSON_BY_TOOL_CALL_ID.remove();
        FETCH_CACHED_FULL_AUGMENTED_JSON_BY_TOOL_CALL_ID.remove();
        TOOL_EGRESS_FULL_JSON_BY_TOOL_CALL_ID.remove();
        LAST_RESOLVED_ASSET_TYPE_KEY.remove();
        REPETITION_BLOCKED_COUNT_THIS_LOOP.remove();
        DOCUMENT_SEARCH_LOOP_FORCED_SUMMARY.remove();
        DOCUMENT_SEARCH_GROUNDED_COVERAGE_SUMMARY.remove();
        PRESENTATION_PHASE_ROUNDS_USED.remove();
        PRESENTATION_PHASE_ENTERED_THIS_LOOP.remove();
        PRESENTATION_ACTIONS_REQUESTED.remove();
        PRESENTATION_ACTIONS_EXECUTED.remove();
        PRESENTATION_ACTIONS_BLOCKED.remove();
    }
}
