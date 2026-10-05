package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;

/**
 * §6.2 of {@code docs/core/advanced-compact.md}: builds a checkpoint's {@code evidenceRefs} from the covered prefix.
 *
 * <p>A ref is a <b>pointer to server-authored evidence, never a copy of it</b>. Accepting a format authorizes
 * extracting the bounded metadata that format's schema defines — result kind, cache lineage, completeness, sample
 * status — and nothing else. No body is copied, and no tool arguments are carried: §6.2 requires that re-fetching be
 * reconstructed from the current request or fresh discovery rather than replayed from checkpoint text.
 *
 * <p><b>Closed allowlist, no heuristics.</b> There is deliberately no "looks like compact JSON" branch. A ref
 * requires a complete, id-paired {@code Role.TOOL} row in the covered prefix whose body carries one of five accepted
 * shapes. Everything else — unknown or absent {@code $format}, unmarked generic JSON, raw rows or pages, error
 * shells, PASSWORD-bearing bodies, HITL and playbook internal rows — produces no ref at all.
 *
 * <p><b>Identity comes from execution, not from the body.</b> {@code toolCallId} is the row's own pairing id and
 * {@code tool} is the recorded executed-tool name (falling back to the paired assistant's declared call). A body
 * field of the same name is never trusted, so a tool result cannot rename itself or claim another call's identity.
 *
 * <p><b>Liveness is never invented.</b> Refs are emitted as {@code historical-recompute} unless a caller-supplied
 * {@link LivenessResolver} — a real current-JVM lookup in the right conversation and principal scope — says
 * otherwise. §5 invariant 11 allows nothing else to say {@code live}, and a persisted {@code live} is re-decided at
 * every rehydrate.
 */
public final class ConversationCheckpointEvidenceManifest {

    /** Current-JVM cache lookup. Supplied by the caller that owns conversation and principal scope. */
    @FunctionalInterface
    public interface LivenessResolver {
        /** @return {@code true} only when this {@code cacheId} resolves in the current JVM for the current scope */
        boolean isLive(String cacheId);
    }

    /** Upper bound on refs; matches the envelope cap so the manifest cannot produce an unserializable checkpoint. */
    public static final int MAX_REFS = ConversationCheckpointCodec.MAX_EVIDENCE_REFS;

    /**
     * Bounded-row limit for row-bearing evidence, matching the shipped
     * {@code CompactFetchStreamRehydrate} sample-array bound so the manifest never admits a body Stage-2 rehydrate
     * would treat as oversized.
     */
    static final int MAX_EVIDENCE_ROWS = 200;

    /**
     * Every raw row/page collection an accepted producer or its raw predecessor can carry — the same key set the
     * matrix codec resolves over, so summaries and matrices agree on what "carries rows" means.
     */
    private static final String[] ROW_BEARING_FIELDS = InfoTableMatrixCodec.TABULAR_ROWS_KEYS;

    /**
     * Tools whose names may be offered as {@code recomputeTool} (§6.2: the name comes from a server allowlist).
     *
     * <p>Two independent conditions, and both matter:
     *
     * <ul>
     *   <li><b>Model-facing.</b> Every name here must appear in the advertised tool definitions, because a
     *       recompute suggestion the model cannot call is advice it cannot act on. Executor-only names are
     *       excluded even though they produce accepted evidence — the demoted cached-result aliases
     *       ({@code exact_join_cached_result}, {@code quality_cached_result}, {@code resample_cached_result},
     *       {@code rolling_cached_result}, {@code rate_of_change_cached_result},
     *       {@code period_compare_cached_result}) whose model-visible path is {@code tabulate_cached_result mode=*},
     *       {@code get_entity}, and {@code analyze_cached_result} when its admission gate withdraws it.
     *       {@code ConversationCheckpointEvidenceManifestTest} asserts parity against the real registry rather than
     *       trusting this list to stay accurate.</li>
     *   <li><b>Safely repeatable.</b> Recompute means "get this evidence again", so writes and side-effecting tools
     *       — {@code set_property_value}, {@code acknowledge_alerts}, {@code invoke_service},
     *       {@code start_playbook} — are excluded even though they are model-facing and can produce an accepted
     *       body. Re-running them is not a recompute.</li>
     * </ul>
     *
     * <p>A ref whose producing tool is outside this set is still emitted, with an empty {@code recomputeTool},
     * because the lineage is real even when no safe re-run exists.
     */
    static final Set<String> RECOMPUTE_TOOL_ALLOWLIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "query_entities", "query_entities_by_taxonomy", "analyze_entity_set",
            "get_property_values", "query_property_history", "query_stream_data",
            "fetch_cached_result", "tabulate_cached_result", "summarize_cached_result",
            "describe_entity_schema", "discover_thing_members")));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConversationCheckpointEvidenceManifest() {}

    /**
     * Builds the refs a checkpoint may carry for this covered prefix.
     *
     * @param coveredPrefix        the §7.2 semantic prefix, in document order
     * @param livenessOrNull       current-JVM lookup, or {@code null} to emit every ref as historical-recompute
     */
    public static List<ConversationCheckpoint.EvidenceRef> build(List<ChatMessage> coveredPrefix,
            LivenessResolver livenessOrNull) {
        List<ConversationCheckpoint.EvidenceRef> out = new ArrayList<>();
        if (coveredPrefix == null) {
            return out;
        }
        for (int i = 0; i < coveredPrefix.size() && out.size() < MAX_REFS; i++) {
            ConversationCheckpoint.EvidenceRef ref = admitRow(coveredPrefix, i, livenessOrNull);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    /**
     * Whether the row at {@code index} is §6.2-admitted compact evidence — the same question {@link #build} asks
     * per row, answered per <em>concrete row</em> rather than by looking a tool-call id up in a set.
     *
     * <p>That distinction is load-bearing. Tool-call ids are unique only within one assistant batch, so a set built
     * from accepted rows can be satisfied by a different, unaccepted row that happens to reuse the id in a later
     * batch. And this answer is independent of {@link #MAX_REFS}: the envelope's ref cap bounds what is persisted,
     * and must not decide which validated rows a summary request may describe.
     */
    public static boolean isAdmittedEvidenceRow(List<ChatMessage> rows, int index) {
        return admitRow(rows, index, null) != null;
    }

    /**
     * The single, uncapped §6.2 row-admission decision: pairing, family, success shape, PASSWORD, row bound, and
     * every execution/metadata cap. Returns the ref this row would contribute, or {@code null}.
     *
     * <p>Both consumers call this. {@link #build} adds the results until {@link #MAX_REFS}; the summary request asks
     * only whether the answer is non-null. Only that final list cap differs, so "produces no ref" and "may not be
     * shown to the model" cannot mean different things — a body whose metadata exceeds a cap is not a bounded
     * success envelope, whichever consumer is asking.
     */
    static ConversationCheckpoint.EvidenceRef admitRow(List<ChatMessage> rows, int index,
            LivenessResolver livenessOrNull) {
        if (rows == null || index < 0 || index >= rows.size()) {
            return null;
        }
        ChatMessage m = rows.get(index);
        if (m == null || m.getRole() != ChatMessage.Role.TOOL) {
            return null;
        }
        String toolCallId = m.getToolCallId();
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        String tool = resolveToolName(rows, index, m, toolCallId);
        if (tool == null || tool.isBlank()) {
            return null;
        }
        JsonNode body = readObject(m.getContent());
        if (body == null) {
            return null;
        }
        String family = acceptedFamily(body, m.getContent());
        if (family == null) {
            return null;
        }
        return toRef(toolCallId, tool, family, body, livenessOrNull);
    }

    /**
     * Re-validates refs carried forward from a previous checkpoint on repeated compaction (§6.2). Identity, caps,
     * and the tool allowlist are re-checked, and liveness is decided again from the current JVM — a persisted
     * {@code live} never survives on its own authority.
     */
    public static List<ConversationCheckpoint.EvidenceRef> revalidateCarriedForward(
            List<ConversationCheckpoint.EvidenceRef> priorRefs, LivenessResolver livenessOrNull) {
        List<ConversationCheckpoint.EvidenceRef> out = new ArrayList<>();
        if (priorRefs == null) {
            return out;
        }
        for (ConversationCheckpoint.EvidenceRef r : priorRefs) {
            if (out.size() >= MAX_REFS) {
                break;
            }
            // Identity and a recognized admitting family, re-checked rather than trusted. A carried ref arrives from
            // a persisted envelope, so "it was valid once" is an assumption, not a fact.
            if (r == null || !r.isAdmissible() || !withinMetadataCaps(r)) {
                continue;
            }
            boolean live = livenessOrNull != null && !r.cacheId().isBlank() && livenessOrNull.isLive(r.cacheId());
            out.add(new ConversationCheckpoint.EvidenceRef(
                    r.toolCallId(), r.tool(), r.evidenceFormat(), r.resultKind(), r.cacheId(), r.completeness(),
                    r.sampleOnly(),
                    live ? ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE
                            : ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE,
                    allowedRecomputeTool(r.tool())));
        }
        return out;
    }

    /**
     * Over-cap metadata is dropped rather than truncated on carry-forward: truncating would silently rewrite a
     * persisted ref into a different one, which is the failure the codec's canonical rules exist to prevent.
     */
    private static boolean withinMetadataCaps(ConversationCheckpoint.EvidenceRef r) {
        int max = ConversationCheckpointCodec.MAX_ITEM_CHARS;
        return r.toolCallId().length() <= max
                && r.tool().length() <= max
                && r.resultKind().length() <= max
                && r.cacheId().length() <= max
                && r.completeness().length() <= max;
    }

    /**
     * Execution identity for a tool row, established <b>pairing first</b>.
     *
     * <p>§6.2 admits only a complete, id-paired tool row, so the owning assistant call is located before any name is
     * considered. A recorded {@code executedToolName} is server metadata, but it is not evidence of pairing: without
     * this ordering an orphan row carrying an executed name would mint a ref for a call batch that never existed.
     *
     * <p>Consistency rule once paired: an absent executed name yields the declared name; a matching executed name
     * yields the same; a <b>disagreement</b> yields no ref, because there is no principled way to choose between two
     * server-recorded identities for one call, and guessing would attribute evidence to the wrong tool.
     *
     * @return the tool name, or {@code null} when the row is unpaired or the two identities disagree
     */
    private static String resolveToolName(List<ChatMessage> rows, int toolRowIndex, ChatMessage toolRow,
            String toolCallId) {
        String declared = null;
        for (int j = toolRowIndex - 1; j >= 0; j--) {
            ChatMessage prior = rows.get(j);
            if (prior == null || prior.getRole() == ChatMessage.Role.TOOL) {
                continue;
            }
            if (prior.getRole() != ChatMessage.Role.ASSISTANT || !prior.hasToolCalls()) {
                return null;
            }
            for (ToolCall tc : prior.getToolCalls()) {
                if (tc != null && toolCallId.equals(tc.getId())) {
                    declared = tc.getFunctionName();
                    break;
                }
            }
            break;
        }
        if (declared == null || declared.isBlank()) {
            return null;
        }
        String executed = toolRow.getExecutedToolName();
        if (executed == null || executed.isBlank()) {
            return declared;
        }
        return executed.equals(declared) ? declared : null;
    }

    /**
     * The five accepted shapes of §6.2, and nothing else.
     *
     * <p>A {@code $format} is a <b>discriminator, not proof</b> that a body came through its producer. Each family
     * therefore validates the accepted <em>success envelope</em>: the success status and the bounded fields that
     * family's producer always writes. Without that, a marker-only object, an error shell, or raw page-shaped
     * content relabelled with an accepted format would mint a ref.
     *
     * @return the admitting {@code FAMILY_*} token, or {@code null} when nothing admits this body
     */
    static String acceptedFamily(JsonNode body, String rawJson) {
        String format = text(body, "$format");
        if (LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1.equals(format)) {
            return isValidInfotableSummary(body)
                    ? ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY : null;
        }
        if (EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1.equals(format)) {
            return isValidEntityMetadataSummary(body)
                    ? ConversationCheckpoint.EvidenceRef.FAMILY_ENTITY_METADATA_SUMMARY : null;
        }
        if (InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(format)) {
            return isValidMatrix(body) ? ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_MATRIX : null;
        }
        if (LlmToolResultCohortMerger.FORMAT_BUNDLE.equals(format)) {
            JsonNode members = body.get("members");
            if (members == null || !members.isArray()) {
                return null;
            }
            JsonNode result = body.get("result");
            if (result == null || !result.isObject()) {
                return null;
            }
            // The inner body is held to exactly the standard a standalone body of that family would face.
            String innerFormat = text(result, "$format");
            boolean innerOk =
                    (InfoTableMatrixCodec.FORMAT_MATRIX_V1.equals(innerFormat) && isValidMatrix(result))
                            || (LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1.equals(innerFormat)
                                    && isValidInfotableSummary(result));
            return innerOk ? ConversationCheckpoint.EvidenceRef.FAMILY_COHORT_BUNDLE : null;
        }
        // Family 5 delegates to the shipped acceptance policy so the checkpoint can never be more permissive than
        // Stage-2 rehydrate already is.
        return acceptsCompactFetchFamily(rawJson)
                ? ConversationCheckpoint.EvidenceRef.FAMILY_COMPACT_FETCH : null;
    }

    /**
     * {@code LlmToolResultTierBPromoter} always writes success, a result kind, a row count, and columns — and it
     * emits <b>no rows at all</b>, which is the point of promoting to a summary. A body carrying every required
     * field plus a row collection is therefore raw content wearing the summary's label, not a summary, so any
     * row-bearing field disqualifies it outright rather than merely being size-checked.
     */
    private static boolean isValidInfotableSummary(JsonNode body) {
        return isSuccess(body)
                && !text(body, "resultKind").isBlank()
                && isNamedColumnArray(body.get("columns"))
                && body.path("rowCount").isNumber()
                && !hasPasswordColumn(body)
                && !hasAnyRowBearingField(body);
    }

    /**
     * {@code EntityMetadataSummaryCodec} writes success, the entity identity it summarized, and at least one of
     * {@code properties} / {@code services} / {@code events} — it returns {@code null} rather than emit a summary
     * with no member payload. Identity alone is a shape the producer cannot produce.
     *
     * <p>Property base types live under {@code properties[]}, not {@code columns}, so the PASSWORD gate has to look
     * where this family actually declares them.
     */
    private static boolean isValidEntityMetadataSummary(JsonNode body) {
        if (!isSuccess(body) || text(body, "entityType").isBlank() || text(body, "entityName").isBlank()) {
            return false;
        }
        JsonNode properties = body.get("properties");
        boolean anyMember = isNamedMemberArray(properties)
                || isNamedMemberArray(body.get("services"))
                || isNamedMemberArray(body.get("events"));
        if (!anyMember) {
            return false;
        }
        for (String key : new String[] { "properties", "services", "events" }) {
            JsonNode n = body.get(key);
            if (n != null && (!n.isArray() || !isNamedMemberArray(n) || n.size() > MAX_EVIDENCE_ROWS)) {
                return false;
            }
        }
        if (properties != null) {
            for (JsonNode prop : properties) {
                if ("PASSWORD".equalsIgnoreCase(text(prop, "baseType"))) {
                    return false;
                }
            }
        }
        return !hasPasswordColumn(body) && !hasAnyRowBearingField(body);
    }

    /** A non-empty array of {@code {name: ...}} objects, the shape both entity-summary compactors emit. */
    private static boolean isNamedMemberArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return false;
        }
        for (JsonNode m : node) {
            if (!m.isObject() || text(m, "name").isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Any raw row/page collection. Summaries carry none; their presence means the body is not a summary. */
    private static boolean hasAnyRowBearingField(JsonNode body) {
        for (String key : ROW_BEARING_FIELDS) {
            JsonNode n = body.get(key);
            if (n != null && !n.isNull()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sealed matrix shape plus the PASSWORD and bounded-row checks §6.2 requires. Rows live under {@code rows} or
     * {@code sampleRows} depending on whether the producer sampled, and exactly one must be present: a matrix with
     * neither is not the sealed shape, and object-shaped rows are the <em>raw</em> shape the codec replaces.
     */
    private static boolean isValidMatrix(JsonNode body) {
        // Shape is the producer's question, so ask the producer. Re-deriving it here is what let taxonomy row keys
        // and all-constant column elision be rejected as malformed while an unproducible empty matrix passed.
        if (!InfoTableMatrixCodec.isSealedMatrixV1(body)) {
            return false;
        }
        // Evidence policy on top of shape: §6.2's success envelope, PASSWORD exclusion, and row bound.
        if (!isSuccess(body) || hasPasswordColumn(body)) {
            return false;
        }
        JsonNode rows = InfoTableMatrixCodec.sealedMatrixRows(body);
        return rows != null && rows.size() <= MAX_EVIDENCE_ROWS;
    }

    private static boolean isSuccess(JsonNode body) {
        return "success".equalsIgnoreCase(text(body, "status"));
    }

    private static boolean isNamedColumnArray(JsonNode columns) {
        if (columns == null || !columns.isArray() || columns.isEmpty()) {
            return false;
        }
        for (JsonNode c : columns) {
            if (!c.isObject() || text(c, "name").isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean acceptsCompactFetchFamily(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            return CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(new JSONObject(rawJson));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasPasswordColumn(JsonNode body) {
        JsonNode columns = body.get("columns");
        if (columns == null || !columns.isArray()) {
            return false;
        }
        for (JsonNode c : columns) {
            if (c.isObject() && "PASSWORD".equalsIgnoreCase(text(c, "baseType"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts only the bounded metadata the accepted schemas define. Values are read from the body because they are
     * server-authored fields of an already-accepted envelope; identity and liveness are not, and come from execution
     * and from the current-JVM lookup respectively.
     */
    /**
     * Extracts only the bounded metadata the accepted schemas define.
     *
     * <p><b>Nothing is truncated.</b> An over-cap field means the body is not the shape its producer emits, and
     * truncating identity would be actively harmful: a shortened {@code toolCallId} no longer equals either paired
     * message and can collide with another truncated id, silently attributing evidence to the wrong call. Fresh
     * construction therefore fails closed exactly as carry-forward does.
     *
     * @return the ref, or {@code null} when any field exceeds its cap
     */
    private static ConversationCheckpoint.EvidenceRef toRef(String toolCallId, String tool, String family,
            JsonNode body, LivenessResolver livenessOrNull) {
        String resultKind = firstText(body, "resultKind", "result_kind");
        String cacheId = firstText(body, "cacheId", "cache_id");
        String completeness = firstText(body, "completeness");
        int max = ConversationCheckpointCodec.MAX_ITEM_CHARS;
        if (toolCallId.length() > max || tool.length() > max || resultKind.length() > max
                || cacheId.length() > max || completeness.length() > max) {
            return null;
        }
        boolean live = livenessOrNull != null && !cacheId.isBlank() && livenessOrNull.isLive(cacheId);
        return new ConversationCheckpoint.EvidenceRef(
                toolCallId,
                tool,
                family,
                resultKind,
                cacheId,
                completeness,
                body.path("sampleOnly").asBoolean(false) || body.path("sample").asBoolean(false),
                live ? ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE
                        : ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE,
                allowedRecomputeTool(tool));
    }

    static String allowedRecomputeTool(String tool) {
        return tool != null && RECOMPUTE_TOOL_ALLOWLIST.contains(tool) ? tool : "";
    }

    private static JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }

}
