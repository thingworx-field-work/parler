package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;

/**
 * Slice B of {@code docs/core/advanced-compact.md}: the {@code parler.conversation_checkpoint.v1} wire codec, its
 * fixed caps, the §8.3 protected-value scan, semantic-only parsing of model output, and the §6.2 retained-tail
 * acceptance policy.
 *
 * <p><b>Two inputs, two policies.</b> Model output and a persisted envelope are not the same kind of data and are
 * not validated the same way:
 *
 * <ul>
 *   <li><b>Model output</b> ({@link #parseModelSemantic}) is untrusted but expected to be sloppy about size. A
 *       wrong <em>type</em> on a known field fails §8.3 step 1 and rejects the whole reply; being <em>too large</em>
 *       is handled by §8.3's deterministic reduction rather than rejection, because shrinking is exactly what that
 *       step exists to do.</li>
 *   <li><b>A persisted envelope</b> ({@link #parse}) was already validated when it was written, so any wrong type,
 *       illegal enum, incoherent row, or over-cap field means it is corrupt or forged. §6.1 requires rejection, and
 *       nothing here repairs it into "a different but acceptable checkpoint".</li>
 * </ul>
 *
 * <p><b>Ownership (§6.2).</b> {@link #parseModelSemantic} reads <em>only</em> {@code semantic}. A model that emits
 * {@code source}, {@code evidenceRefs}, {@code retainedTail}, or {@code generated} has those discarded, so it can
 * never forge identity, a cache handle, or a liveness claim. Discarding server-owned fields outside {@code semantic}
 * does not license silently dropping malformed fields inside it — those still fail step 1.
 *
 * <p><b>Whole or not at all (§8.3).</b> Validation rejects an envelope; it never redacts a value or repairs a field,
 * so a half-scrubbed checkpoint can never be persisted as though it were clean.
 *
 * <p>This class does not construct {@code evidenceRefs} from a conversation — that is the §6.2 evidence manifest,
 * which sources refs from paired {@code Role.TOOL} rows under a closed format allowlist. The codec knows the ref
 * <em>shape</em>, validates it, and round-trips it; the manifest knows the sourcing rules.
 */
public final class ConversationCheckpointCodec {

    public static final String FORMAT_V1 = "parler.conversation_checkpoint.v1";

    /**
     * Marker opening the working-set {@link ChatMessage} that carries a checkpoint into a provider request. The
     * planner, the storage trimmer, and rehydrate all identify the injected row by this prefix
     * ({@link #isInjectedCheckpoint}) rather than by content sniffing.
     */
    public static final String INJECTED_PREFIX = "[parler:conversation-checkpoint]\n";

    // --- §5 invariant 8 caps ------------------------------------------------------------------------------------

    /** Model-facing rendered semantic text (what actually enters the provider request). */
    public static final int MAX_SEMANTIC_CHARS = 8_000;
    public static final int MAX_RETAINED_TAIL_MESSAGES = 100;
    public static final int MAX_RETAINED_TAIL_CHARS = 100_000;
    /**
     * Full serialized envelope. Deliberately half of {@link com.thingworx.things.agent.cache.LargeJsonCaps
     * LargeJsonCaps#STREAM_PERSISTENCE_CHAR_CAP} (500000), so a validated checkpoint can never reach the appender's
     * silent truncation branch, which would persist unparseable JSON (§9.1).
     */
    public static final int MAX_ENVELOPE_CHARS = 250_000;
    public static final int MAX_EVIDENCE_REFS = 64;

    /**
     * Structural caps on {@code semantic} (§5 invariant 8: "arrays and single items must also have fixed caps").
     * These bound the <em>shape</em> — they stop a model returning a ten-thousand-element array — while
     * {@link #MAX_SEMANTIC_CHARS} bounds the <em>size</em>. Both are needed: a thousand one-character items would
     * pass a char cap, and one enormous item would pass an element-count cap.
     */
    public static final int MAX_GOAL_CHARS = 600;
    public static final int MAX_ITEM_CHARS = 300;
    public static final int MAX_CONSTRAINTS = 12;
    public static final int MAX_PROGRESS_ITEMS = 12;
    public static final int MAX_DECISIONS = 8;
    public static final int MAX_REJECTED_ALTERNATIVES = 4;
    public static final int MAX_NEXT_STEPS = 8;
    public static final int MAX_CRITICAL_CONTEXT = 12;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * §8.3 rule 3: an {@link com.thingworx.things.agent.cache.ArtifactPathLayout ArtifactPathLayout}-shaped
     * repository-relative path — {@code <hex-username>/<yyyy-MM-dd>/<artifact-id>.payload}.
     */
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "[0-9a-fA-F]{2,}/\\d{4}-\\d{2}-\\d{2}/[^\\s/]+\\.payload");

    private ConversationCheckpointCodec() {}

    // --- working-set representation ------------------------------------------------------------------------------

    /** Whether this working row is the injected checkpoint (§10.1). */
    public static boolean isInjectedCheckpoint(ChatMessage m) {
        return m != null
                && m.getRole() == ChatMessage.Role.ASSISTANT
                && !m.hasToolCalls()
                && m.getContent() != null
                && m.getContent().startsWith(INJECTED_PREFIX);
    }

    /**
     * Renders a checkpoint as the assistant-provenance working row (§9.2): assistant, never system — it must not
     * acquire system authority — and never user, so it cannot impersonate the person.
     */
    public static ChatMessage toInjectedAssistant(ConversationCheckpointSemantic semantic) {
        if (semantic == null) {
            return null;
        }
        return ChatMessage.assistant(INJECTED_PREFIX + semantic.renderForModel());
    }

    // --- §8.3 steps 1–4 over model output --------------------------------------------------------------------------

    /** Why a summary reply was refused, mapped 1:1 onto the §12 skip reasons. */
    public enum SemanticRejection {
        /** Accepted. */
        NONE,
        /** §8.3 step 1: unparseable, wrong root shape, wrong field type, or no usable goal. */
        INVALID_JSON,
        /** §8.3 step 3: a fixed protected-value rule matched. */
        PROTECTED_VALUE
    }

    /** Outcome of {@link #parseModelSemantic}; carries the §12 reason so a caller never has to infer it. */
    public static final class SemanticParseResult {
        private final ConversationCheckpointSemantic semantic;
        private final SemanticRejection rejection;

        private SemanticParseResult(ConversationCheckpointSemantic semantic, SemanticRejection rejection) {
            this.semantic = semantic;
            this.rejection = rejection;
        }

        static SemanticParseResult accepted(ConversationCheckpointSemantic s) {
            return new SemanticParseResult(s, SemanticRejection.NONE);
        }

        static SemanticParseResult rejected(SemanticRejection reason) {
            return new SemanticParseResult(null, reason);
        }

        public boolean isAccepted() {
            return rejection == SemanticRejection.NONE && semantic != null;
        }

        /** {@code null} unless {@link #isAccepted()}. */
        public ConversationCheckpointSemantic semantic() {
            return semantic;
        }

        public SemanticRejection rejection() {
            return rejection;
        }
    }

    /**
     * Runs §8.3 steps 1–4 over the summary model's reply <b>in the order the design fixes</b>, which is why this is
     * one method rather than a parse the caller then post-processes:
     *
     * <ol>
     *   <li>JSON and schema/type checks — a wrong type on a known field rejects the reply;</li>
     *   <li>{@code semantic} only; server-owned fields the model emitted are discarded;</li>
     *   <li>the protected-value scan, over the <b>uncapped</b> strings — a scan run after capping cannot see an
     *       assignment sitting at character 301 of an item or in the 13th element of an array, and would accept a
     *       checkpoint §8.3 requires be rejected outright;</li>
     *   <li>only then structural item/array caps and the deterministic rendered-size shrink.</li>
     * </ol>
     */
    public static SemanticParseResult parseModelSemantic(String modelJson) {
        if (modelJson == null || modelJson.isBlank()) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(modelJson.trim());
        } catch (Exception e) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        if (root == null || !root.isObject()) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        // Step 2: a bare semantic object or a wrapper carrying one; anything else in the wrapper is server-owned.
        JsonNode semanticNode = root.has("semantic") ? root.get("semantic") : root;
        if (semanticNode == null || !semanticNode.isObject()) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        // Step 1: types only — verbatim, so step 3 sees every character the model wrote.
        ConversationCheckpointSemantic raw = readSemanticVerbatim(semanticNode);
        if (raw == null) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        // Step 3 runs before the goal check: a protected value must always be reported as PROTECTED_VALUE, even in
        // a reply that would also have been refused for having no goal.
        if (containsProtectedValue(raw)) {
            return SemanticParseResult.rejected(SemanticRejection.PROTECTED_VALUE);
        }
        if (!raw.hasGoal()) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        // Step 4: canonicalize first (safe now that the scan has run), then cap, then shrink.
        ConversationCheckpointSemantic capped = applyStructuralCaps(normalizeModelSemantic(raw));
        if (!capped.hasGoal()) {
            return SemanticParseResult.rejected(SemanticRejection.INVALID_JSON);
        }
        return SemanticParseResult.accepted(capped.shrunkTo(MAX_SEMANTIC_CHARS));
    }

    /**
     * Reads {@code semantic} <b>verbatim</b>: types are checked, nothing else is touched. No trimming, no dropping
     * of blank members, no discarding of a decision whose {@code decision} is empty, and no size limits.
     *
     * <p>Being non-destructive is a correctness requirement, not tidiness. The §8.3 protected-value scan runs on
     * whatever this returns, so anything discarded here is never scanned — a decision object with an empty
     * {@code decision} and a {@code rationale} of {@code token=abc123} would be dropped before the scan and the reply
     * accepted. It also lets persisted decoding be exact: a value that differs from what was written is a different
     * checkpoint, not a repaired one.
     *
     * <p>An <em>explicitly present</em> JSON {@code null} on a known field is a wrong type, not an absent optional.
     *
     * @return the verbatim semantic state, or {@code null} when a known field has the wrong type
     */
    private static ConversationCheckpointSemantic readSemanticVerbatim(JsonNode n) {
        JsonNode goalNode = n.get("goal");
        if (goalNode != null && !goalNode.isTextual()) {
            return null;
        }
        String goal = goalNode != null ? goalNode.asText() : "";

        List<String> constraints = readStringsVerbatim(n.get("constraints"));
        List<String> nextSteps = readStringsVerbatim(n.get("nextSteps"));
        List<String> criticalContext = readStringsVerbatim(n.get("criticalContext"));
        if (constraints == null || nextSteps == null || criticalContext == null) {
            return null;
        }
        JsonNode progress = n.get("progress");
        if (progress != null && !progress.isObject()) {
            return null;
        }
        List<String> done = readStringsVerbatim(progress != null ? progress.get("done") : null);
        List<String> inProgress = readStringsVerbatim(progress != null ? progress.get("inProgress") : null);
        List<String> blocked = readStringsVerbatim(progress != null ? progress.get("blocked") : null);
        if (done == null || inProgress == null || blocked == null) {
            return null;
        }
        List<ConversationCheckpointSemantic.Decision> decisions = readDecisionsVerbatim(n.get("decisions"));
        if (decisions == null) {
            return null;
        }
        return new ConversationCheckpointSemantic(goal, constraints, done, inProgress, blocked, decisions, nextSteps,
                criticalContext);
    }

    /** @return the strings exactly as written, or {@code null} when the node is present with the wrong type */
    private static List<String> readStringsVerbatim(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            return null;
        }
        for (JsonNode n : node) {
            if (!n.isTextual()) {
                return null;
            }
            out.add(n.asText());
        }
        return out;
    }

    private static List<ConversationCheckpointSemantic.Decision> readDecisionsVerbatim(JsonNode node) {
        List<ConversationCheckpointSemantic.Decision> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            return null;
        }
        for (JsonNode n : node) {
            if (!n.isObject()) {
                return null;
            }
            JsonNode d = n.get("decision");
            JsonNode r = n.get("rationale");
            if ((d != null && !d.isTextual()) || (r != null && !r.isTextual())) {
                return null;
            }
            List<String> rejected = readStringsVerbatim(n.get("rejectedAlternatives"));
            if (rejected == null) {
                return null;
            }
            out.add(new ConversationCheckpointSemantic.Decision(
                    d != null ? d.asText() : "", r != null ? r.asText() : "", rejected));
        }
        return out;
    }

    /**
     * Canonical form for anything that crosses the persistence boundary: every string trimmed and non-blank, every
     * decision carrying a non-blank {@code decision}. Checked — never applied — by {@link #serialize} and
     * {@link #parse}, so a serialize/parse round trip is exact and decoding can never quietly produce a different
     * checkpoint from the one that was written.
     */
    static boolean isCanonicalSemantic(ConversationCheckpointSemantic s) {
        if (s == null || !isCanonicalString(s.goal())) {
            return false;
        }
        if (!allCanonical(s.constraints()) || !allCanonical(s.done()) || !allCanonical(s.inProgress())
                || !allCanonical(s.blocked()) || !allCanonical(s.nextSteps())
                || !allCanonical(s.criticalContext())) {
            return false;
        }
        for (ConversationCheckpointSemantic.Decision d : s.decisions()) {
            if (d == null || !isCanonicalString(d.decision()) || !allCanonical(d.rejectedAlternatives())) {
                return false;
            }
            // A rationale may be absent, but if present it must be canonical.
            if (!d.rationale().isEmpty() && !isCanonicalString(d.rationale())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allCanonical(List<String> items) {
        for (String s : items) {
            if (!isCanonicalString(s)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonicalString(String s) {
        return s != null && !s.isBlank() && s.equals(s.trim());
    }

    /**
     * Model-path canonicalization, applied only <em>after</em> the protected scan has seen every original string:
     * trims, drops blank members, and drops a decision with no decision text.
     */
    static ConversationCheckpointSemantic normalizeModelSemantic(ConversationCheckpointSemantic s) {
        List<ConversationCheckpointSemantic.Decision> decisions = new ArrayList<>();
        for (ConversationCheckpointSemantic.Decision d : s.decisions()) {
            if (d == null || d.decision().trim().isEmpty()) {
                continue;
            }
            decisions.add(new ConversationCheckpointSemantic.Decision(
                    d.decision().trim(), d.rationale().trim(), trimNonBlank(d.rejectedAlternatives())));
        }
        return new ConversationCheckpointSemantic(
                s.goal().trim(),
                trimNonBlank(s.constraints()),
                trimNonBlank(s.done()),
                trimNonBlank(s.inProgress()),
                trimNonBlank(s.blocked()),
                decisions,
                trimNonBlank(s.nextSteps()),
                trimNonBlank(s.criticalContext()));
    }

    private static List<String> trimNonBlank(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * §8.3 step 4 structural reduction. Survivor policy matches {@link ConversationCheckpointSemantic#shrunkTo}:
     * <b>newest kept</b>, because a checkpoint exists to resume the current task rather than to archive the earliest
     * one. Applying one policy in the cap pass and the opposite in the shrink pass would make which items survive
     * depend on which limit happened to bind.
     */
    static ConversationCheckpointSemantic applyStructuralCaps(ConversationCheckpointSemantic s) {
        List<ConversationCheckpointSemantic.Decision> decisions = new ArrayList<>();
        for (ConversationCheckpointSemantic.Decision d : keepNewest(s.decisions(), MAX_DECISIONS)) {
            decisions.add(new ConversationCheckpointSemantic.Decision(
                    truncate(d.decision(), MAX_ITEM_CHARS),
                    truncate(d.rationale(), MAX_ITEM_CHARS),
                    truncateEach(keepNewest(d.rejectedAlternatives(), MAX_REJECTED_ALTERNATIVES))));
        }
        return new ConversationCheckpointSemantic(
                truncate(s.goal(), MAX_GOAL_CHARS),
                truncateEach(keepNewest(s.constraints(), MAX_CONSTRAINTS)),
                truncateEach(keepNewest(s.done(), MAX_PROGRESS_ITEMS)),
                truncateEach(keepNewest(s.inProgress(), MAX_PROGRESS_ITEMS)),
                truncateEach(keepNewest(s.blocked(), MAX_PROGRESS_ITEMS)),
                decisions,
                truncateEach(keepNewest(s.nextSteps(), MAX_NEXT_STEPS)),
                truncateEach(keepNewest(s.criticalContext(), MAX_CRITICAL_CONTEXT)));
    }

    private static <T> List<T> keepNewest(List<T> in, int max) {
        return in.size() <= max ? in : new ArrayList<>(in.subList(in.size() - max, in.size()));
    }

    private static List<String> truncateEach(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(truncate(s, MAX_ITEM_CHARS));
        }
        return out;
    }

    /** Truncation must preserve canonical form, or a cut landing on a space would make the result unserializable. */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max).trim();
    }

    // --- §8.3 step 3: bounded protected-value reject ---------------------------------------------------------------

    /**
     * The complete, fixed rule set from §8.3. This is defence in depth over model-generated prose, <b>not</b> a proof
     * of absence: no scanner can prove a free-text string carries no secret, and the design says so. It scans
     * {@code semantic} only — {@code retainedTail} is exact transcript prose already persisted verbatim in the same
     * Stream under the same authorization, so copying it into the envelope introduces no new exposure class.
     *
     * <p>False positives are acceptable: a rejected checkpoint costs continuity for one compaction and nothing else.
     */
    public static boolean containsProtectedValue(ConversationCheckpointSemantic semantic) {
        if (semantic == null) {
            return false;
        }
        for (String s : semantic.allStrings()) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            // (1) a masked value reaching prose means an upstream masking path was traversed.
            if (s.contains(ProtectedValuePolicy.MASK)) {
                return true;
            }
            // (2) a sensitive key in key/value shape — a mention is fine, an assignment is not.
            String lower = s.toLowerCase(Locale.ROOT);
            for (String key : ProtectedValuePolicy.SENSITIVE_KEY_NAMES) {
                if (hasKeyValueShape(lower, key)) {
                    return true;
                }
            }
            // (3) a FileRepository-shaped artifact path.
            if (ARTIFACT_PATH.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    /** {@code token=value} / {@code token: value} — an assignment, not the word appearing in a sentence. */
    private static boolean hasKeyValueShape(String lowerText, String key) {
        int from = 0;
        while (true) {
            int at = lowerText.indexOf(key, from);
            if (at < 0) {
                return false;
            }
            int i = at + key.length();
            while (i < lowerText.length() && lowerText.charAt(i) == ' ') {
                i++;
            }
            if (i < lowerText.length() && (lowerText.charAt(i) == ':' || lowerText.charAt(i) == '=')) {
                int v = i + 1;
                while (v < lowerText.length() && Character.isWhitespace(lowerText.charAt(v))) {
                    v++;
                }
                if (v < lowerText.length()) {
                    return true;
                }
            }
            from = at + key.length();
        }
    }

    // --- §6.2 retained-tail acceptance ------------------------------------------------------------------------------

    /**
     * Projects surviving working rows onto the rows a checkpoint may freeze (§6.2): user transcript, final prose
     * assistant, and already-approved compact evidence carrying Stage-2 assistant provenance.
     *
     * <p>System rows, assistant tool-call rows, {@code Role.TOOL} rows, and therefore HITL synthetic results and
     * playbook internal artifacts (which are all tool rows) are excluded, as is a previously injected checkpoint —
     * §5 invariant 2 allows exactly one working checkpoint, so freezing an older one into this envelope's tail would
     * smuggle a second one back through repeated compaction.
     *
     * <p>Over the §5 invariant 8 caps, whole oldest rows are dropped and a leading assistant is never left behind, so
     * the tail always starts user-led and no row is ever cut mid-string.
     */
    public static List<ConversationCheckpoint.RetainedRow> acceptRetainedTail(List<ChatMessage> survivingRows) {
        return applyTailCaps(projectRetainedRows(survivingRows));
    }

    /**
     * The §6.2 row projection <b>without</b> caps. Callers that must prove a specific span survived need the
     * uncapped projection to compare against, because {@link #applyTailCaps} evicts from the oldest end — a
     * cardinality comparison cannot tell "the caps dropped something else" from "the caps dropped exactly the rows I
     * needed to keep".
     */
    public static List<ConversationCheckpoint.RetainedRow> projectRetainedRows(List<ChatMessage> survivingRows) {
        List<ConversationCheckpoint.RetainedRow> out = new ArrayList<>();
        if (survivingRows == null) {
            return out;
        }
        for (ChatMessage m : survivingRows) {
            if (m == null || m.getContent() == null || m.getContent().isEmpty()) {
                continue;
            }
            if (m.getRole() == ChatMessage.Role.USER) {
                out.add(new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_USER,
                        m.getContent(), ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT));
                continue;
            }
            if (m.getRole() != ChatMessage.Role.ASSISTANT || m.hasToolCalls() || isInjectedCheckpoint(m)) {
                continue;
            }
            String provenance = isStage2Framed(m.getContent())
                    ? ConversationCheckpoint.RetainedRow.PROVENANCE_COMPACT_EVIDENCE
                    : ConversationCheckpoint.RetainedRow.PROVENANCE_FINAL_ASSISTANT;
            out.add(new ConversationCheckpoint.RetainedRow(ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT,
                    m.getContent(), provenance));
        }
        return out;
    }

    /**
     * The single Stage-2 classification rule: whether {@code content} is server-framed compact tool evidence
     * restored from the Stream as assistant prose rather than a final assistant answer.
     *
     * <p>Public because the rehydrate char budget needs the same question answered (§9.2 step 6) and a second
     * prefix comparison elsewhere is a second thing that can drift from this one.
     */
    public static boolean isStage2Framed(String content) {
        return content != null
                && content.startsWith(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX);
    }

    /**
     * One coherence rule shared by construction, serialization, and persisted parsing, so the three cannot drift.
     * Role, provenance, and content must agree — enum membership alone would admit {@code user + final-assistant} or
     * a {@code compact-evidence} row with no Stage-2 framing, and would let an older checkpoint marker back in
     * through a row merely labelled {@code final-assistant}.
     */
    static boolean isCoherentRetainedRow(ConversationCheckpoint.RetainedRow r) {
        if (r == null || r.content().isEmpty()) {
            return false;
        }
        if (r.content().startsWith(INJECTED_PREFIX)) {
            return false;
        }
        if (ConversationCheckpoint.RetainedRow.ROLE_USER.equals(r.role())) {
            return ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT.equals(r.provenance());
        }
        if (!ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT.equals(r.role())) {
            return false;
        }
        if (ConversationCheckpoint.RetainedRow.PROVENANCE_COMPACT_EVIDENCE.equals(r.provenance())) {
            return isStage2Framed(r.content());
        }
        if (ConversationCheckpoint.RetainedRow.PROVENANCE_FINAL_ASSISTANT.equals(r.provenance())) {
            return !isStage2Framed(r.content());
        }
        return false;
    }

    /** Drops whole oldest rows until the caps hold, then restores the user-led boundary. */
    static List<ConversationCheckpoint.RetainedRow> applyTailCaps(List<ConversationCheckpoint.RetainedRow> rows) {
        List<ConversationCheckpoint.RetainedRow> out = new ArrayList<>(rows);
        dropLeadingAssistants(out);
        while (!out.isEmpty() && !withinTailCaps(out)) {
            out.remove(0);
            dropLeadingAssistants(out);
        }
        return out;
    }

    private static boolean withinTailCaps(List<ConversationCheckpoint.RetainedRow> rows) {
        if (rows.size() > MAX_RETAINED_TAIL_MESSAGES) {
            return false;
        }
        int chars = 0;
        for (ConversationCheckpoint.RetainedRow r : rows) {
            chars += r.chars();
        }
        return chars <= MAX_RETAINED_TAIL_CHARS;
    }

    private static void dropLeadingAssistants(List<ConversationCheckpoint.RetainedRow> rows) {
        while (!rows.isEmpty()
                && ConversationCheckpoint.RetainedRow.ROLE_ASSISTANT.equals(rows.get(0).role())) {
            rows.remove(0);
        }
    }

    // --- strict structural validation shared by serialize and persisted parse ---------------------------------------

    /** Every §5 invariant 8 structural cap, checked rather than repaired. */
    static boolean semanticWithinStructuralCaps(ConversationCheckpointSemantic s) {
        if (s == null || s.goal() == null || s.goal().length() > MAX_GOAL_CHARS) {
            return false;
        }
        if (!listWithinCaps(s.constraints(), MAX_CONSTRAINTS)
                || !listWithinCaps(s.done(), MAX_PROGRESS_ITEMS)
                || !listWithinCaps(s.inProgress(), MAX_PROGRESS_ITEMS)
                || !listWithinCaps(s.blocked(), MAX_PROGRESS_ITEMS)
                || !listWithinCaps(s.nextSteps(), MAX_NEXT_STEPS)
                || !listWithinCaps(s.criticalContext(), MAX_CRITICAL_CONTEXT)) {
            return false;
        }
        if (s.decisions().size() > MAX_DECISIONS) {
            return false;
        }
        for (ConversationCheckpointSemantic.Decision d : s.decisions()) {
            if (d == null || d.decision().length() > MAX_ITEM_CHARS || d.rationale().length() > MAX_ITEM_CHARS) {
                return false;
            }
            if (!listWithinCaps(d.rejectedAlternatives(), MAX_REJECTED_ALTERNATIVES)) {
                return false;
            }
        }
        return true;
    }

    private static boolean listWithinCaps(List<String> items, int maxItems) {
        if (items.size() > maxItems) {
            return false;
        }
        for (String s : items) {
            if (s == null || s.length() > MAX_ITEM_CHARS) {
                return false;
            }
        }
        return true;
    }

    private static boolean tailIsValid(List<ConversationCheckpoint.RetainedRow> tail) {
        if (tail.size() > MAX_RETAINED_TAIL_MESSAGES) {
            return false;
        }
        int chars = 0;
        for (ConversationCheckpoint.RetainedRow r : tail) {
            if (!isCoherentRetainedRow(r)) {
                return false;
            }
            chars += r.chars();
        }
        if (chars > MAX_RETAINED_TAIL_CHARS) {
            return false;
        }
        return tail.isEmpty() || ConversationCheckpoint.RetainedRow.ROLE_USER.equals(tail.get(0).role());
    }

    private static boolean evidenceRefsAreValid(List<ConversationCheckpoint.EvidenceRef> refs) {
        if (refs.size() > MAX_EVIDENCE_REFS) {
            return false;
        }
        for (ConversationCheckpoint.EvidenceRef r : refs) {
            // Identity, a recognized admitting family, and every string cap — a persisted ref that skips these is
            // exactly the forged carry-forward input §6.2 requires be re-checked.
            if (r == null || !r.isAdmissible()) {
                return false;
            }
            if (r.toolCallId().length() > MAX_ITEM_CHARS
                    || r.tool().length() > MAX_ITEM_CHARS
                    || r.resultKind().length() > MAX_ITEM_CHARS
                    || r.cacheId().length() > MAX_ITEM_CHARS
                    || r.completeness().length() > MAX_ITEM_CHARS
                    || r.recomputeTool().length() > MAX_ITEM_CHARS) {
                return false;
            }
        }
        return true;
    }

    // --- envelope serialize / parse --------------------------------------------------------------------------------

    /**
     * Serializes a complete envelope after the §8.3 step 5–8 server-side checks. A directly constructed semantic
     * object is held to the same structural caps as a parsed one, so no path can persist an envelope that
     * {@link #parse} would then refuse.
     *
     * @return the JSON, or {@code null} when identity is incomplete, the goal is empty, a cap or coherence rule is
     *         violated, or the serialized envelope would exceed {@link #MAX_ENVELOPE_CHARS}
     */
    public static String serialize(ConversationCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.source() == null || checkpoint.semantic() == null) {
            return null;
        }
        if (!checkpoint.source().isComplete() || !checkpoint.semantic().hasGoal()) {
            return null;
        }
        if (!isCanonicalSemantic(checkpoint.semantic())
                || !semanticWithinStructuralCaps(checkpoint.semantic())
                || checkpoint.semantic().modelFacingChars() > MAX_SEMANTIC_CHARS) {
            return null;
        }
        if (!evidenceRefsAreValid(checkpoint.evidenceRefs()) || !tailIsValid(checkpoint.retainedTail())) {
            return null;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(toJson(checkpoint));
        } catch (Exception e) {
            return null;
        }
        return json.length() <= MAX_ENVELOPE_CHARS ? json : null;
    }

    /**
     * Parses a persisted envelope. Any unknown {@code $format}, wrong type, illegal enum, incoherent tail row,
     * over-cap field, or identity mismatch rejects the whole envelope (§6.1) — nothing is repaired, truncated, or
     * dropped, because a persisted checkpoint that no longer satisfies the rules it was written under is corrupt or
     * forged, not merely large.
     *
     * @param expectedConversationId required match; a checkpoint from another conversation is never usable
     * @param expectedAgentThing     required match (§9.3); a mismatch is skipped, not adopted
     * @return the envelope, or {@code null} when it must not be used
     */
    public static ConversationCheckpoint parse(String json, String expectedConversationId, String expectedAgentThing) {
        if (json == null || json.isBlank() || json.length() > MAX_ENVELOPE_CHARS) {
            return null;
        }
        // Ownership is not optional. A caller that cannot name the conversation and agent it expects has no basis
        // for adopting a checkpoint, so a missing expectation fails closed rather than matching everything.
        if (expectedConversationId == null || expectedConversationId.isBlank()
                || expectedAgentThing == null || expectedAgentThing.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject() || !FORMAT_V1.equals(text(root, "$format"))) {
            return null;
        }
        JsonNode sourceNode = root.get("source");
        if (sourceNode == null || !sourceNode.isObject()) {
            return null;
        }
        ConversationCheckpoint.Source source = new ConversationCheckpoint.Source(
                text(sourceNode, "conversationId"),
                text(sourceNode, "agentThing"),
                text(sourceNode, "throughAssistantMessageId"));
        if (!source.isComplete()) {
            return null;
        }
        if (!expectedConversationId.equals(source.conversationId())
                || !expectedAgentThing.equals(source.agentThing())) {
            return null;
        }
        JsonNode semanticNode = root.get("semantic");
        if (semanticNode == null || !semanticNode.isObject()) {
            return null;
        }
        ConversationCheckpointSemantic semantic = readSemanticVerbatim(semanticNode);
        if (semantic == null || !semantic.hasGoal() || !isCanonicalSemantic(semantic)) {
            return null;
        }
        if (!semanticWithinStructuralCaps(semantic) || semantic.modelFacingChars() > MAX_SEMANTIC_CHARS) {
            return null;
        }
        List<ConversationCheckpoint.EvidenceRef> refs = parseEvidenceRefs(root.get("evidenceRefs"));
        if (refs == null || !evidenceRefsAreValid(refs)) {
            return null;
        }
        List<ConversationCheckpoint.RetainedRow> tail = parseRetainedTail(root.get("retainedTail"));
        if (tail == null || !tailIsValid(tail)) {
            return null;
        }
        ConversationCheckpoint.Generated generated = parseGenerated(root.get("generated"));
        if (generated == null) {
            return null;
        }
        return new ConversationCheckpoint(source, semantic, refs, tail, generated);
    }

    /** {@code generated} must be an object whose {@code modelGenerated} is literally {@code true} (§6.1). */
    private static ConversationCheckpoint.Generated parseGenerated(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode flag = node.get("modelGenerated");
        if (flag == null || !flag.isBoolean() || !flag.asBoolean()) {
            return null;
        }
        for (String field : new String[] { "at", "provider", "model" }) {
            JsonNode v = node.get(field);
            if (v != null && !v.isTextual()) {
                return null;
            }
        }
        return new ConversationCheckpoint.Generated(text(node, "at"), text(node, "provider"), text(node, "model"));
    }

    private static List<ConversationCheckpoint.EvidenceRef> parseEvidenceRefs(JsonNode node) {
        List<ConversationCheckpoint.EvidenceRef> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray() || node.size() > MAX_EVIDENCE_REFS) {
            return null;
        }
        for (JsonNode n : node) {
            if (!n.isObject()) {
                return null;
            }
            for (String field : new String[] { "toolCallId", "tool", "evidenceFormat", "resultKind", "cacheId",
                    "completeness", "liveness", "recomputeTool" }) {
                JsonNode v = n.get(field);
                if (v != null && !v.isTextual()) {
                    return null;
                }
            }
            JsonNode sample = n.get("sampleOnly");
            if (sample != null && !sample.isBoolean()) {
                return null;
            }
            String liveness = text(n, "liveness");
            if (!ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE.equals(liveness)
                    && !ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE.equals(liveness)) {
                return null;
            }
            if (!ConversationCheckpoint.EvidenceRef.isKnownFamily(text(n, "evidenceFormat"))) {
                return null;
            }
            out.add(new ConversationCheckpoint.EvidenceRef(
                    text(n, "toolCallId"),
                    text(n, "tool"),
                    text(n, "evidenceFormat"),
                    text(n, "resultKind"),
                    text(n, "cacheId"),
                    text(n, "completeness"),
                    sample != null && sample.asBoolean(),
                    liveness,
                    text(n, "recomputeTool")));
        }
        return out;
    }

    private static List<ConversationCheckpoint.RetainedRow> parseRetainedTail(JsonNode node) {
        List<ConversationCheckpoint.RetainedRow> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray() || node.size() > MAX_RETAINED_TAIL_MESSAGES) {
            return null;
        }
        for (JsonNode n : node) {
            if (!n.isObject()) {
                return null;
            }
            for (String field : new String[] { "role", "content", "provenance" }) {
                JsonNode v = n.get(field);
                if (v == null || !v.isTextual()) {
                    return null;
                }
            }
            out.add(new ConversationCheckpoint.RetainedRow(text(n, "role"), text(n, "content"),
                    text(n, "provenance")));
        }
        return out;
    }

    static ObjectNode toJson(ConversationCheckpoint c) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$format", FORMAT_V1);

        ObjectNode source = root.putObject("source");
        source.put("conversationId", c.source().conversationId());
        source.put("agentThing", c.source().agentThing());
        source.put("throughAssistantMessageId", c.source().throughAssistantMessageId());

        ObjectNode semantic = root.putObject("semantic");
        semantic.put("goal", c.semantic().goal());
        putStrings(semantic.putArray("constraints"), c.semantic().constraints());
        ObjectNode progress = semantic.putObject("progress");
        putStrings(progress.putArray("done"), c.semantic().done());
        putStrings(progress.putArray("inProgress"), c.semantic().inProgress());
        putStrings(progress.putArray("blocked"), c.semantic().blocked());
        ArrayNode decisions = semantic.putArray("decisions");
        for (ConversationCheckpointSemantic.Decision d : c.semantic().decisions()) {
            ObjectNode dn = decisions.addObject();
            dn.put("decision", d.decision());
            dn.put("rationale", d.rationale());
            putStrings(dn.putArray("rejectedAlternatives"), d.rejectedAlternatives());
        }
        putStrings(semantic.putArray("nextSteps"), c.semantic().nextSteps());
        putStrings(semantic.putArray("criticalContext"), c.semantic().criticalContext());

        ArrayNode refs = root.putArray("evidenceRefs");
        for (ConversationCheckpoint.EvidenceRef r : c.evidenceRefs()) {
            ObjectNode rn = refs.addObject();
            rn.put("toolCallId", r.toolCallId());
            rn.put("tool", r.tool());
            rn.put("evidenceFormat", r.evidenceFormat());
            rn.put("resultKind", r.resultKind());
            rn.put("cacheId", r.cacheId());
            rn.put("completeness", r.completeness());
            rn.put("sampleOnly", r.sampleOnly());
            rn.put("liveness", r.liveness());
            rn.put("recomputeTool", r.recomputeTool());
        }

        ArrayNode tail = root.putArray("retainedTail");
        for (ConversationCheckpoint.RetainedRow r : c.retainedTail()) {
            ObjectNode rn = tail.addObject();
            rn.put("role", r.role());
            rn.put("content", r.content());
            rn.put("provenance", r.provenance());
        }

        ObjectNode gen = root.putObject("generated");
        ConversationCheckpoint.Generated g = c.generated();
        gen.put("at", g != null ? g.at() : "");
        gen.put("provider", g != null ? g.provider() : "");
        gen.put("model", g != null ? g.model() : "");
        gen.put("modelGenerated", true);
        return root;
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static void putStrings(ArrayNode target, List<String> values) {
        for (String s : values) {
            target.add(s);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
