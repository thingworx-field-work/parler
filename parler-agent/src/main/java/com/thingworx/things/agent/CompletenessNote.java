package com.thingworx.things.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway.EgressResult;
import com.thingworx.things.agent.source.ReadLimitFact;

/**
 * The completeness note of topic round 6 (R26, {@code CONTRACTS/TOOL_RESULT_COMPLETENESS.md}): whenever the envelope
 * root of a tool result shows a reduction, from the gateway or from the producer, the result itself says so in the
 * text field {@value #FIELD}. Each variant is at most {@value #MAX_NOTE_CHARS} characters.
 */
public final class CompletenessNote {

    public static final String FIELD = "completenessNote";
    public static final int MAX_NOTE_CHARS = 1_200;
    public static final String CHUNK_MARKDOWN_TRUNCATED = "CHUNK_MARKDOWN_TRUNCATED";
    public static final String RESULT_KIND_INFOTABLE_LARGE = "INFOTABLE_LARGE";
    public static final String RESULT_KIND_RESULT_OVERFLOW = "RESULT_OVERFLOW";

    /** The variant of the note, chosen from what the envelope root shows. */
    public enum Variant {
        GATEWAY_CUT, TABLE_SAMPLE, PRODUCER_PARTIAL, PAGE, OVERFLOW
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SAY_IT =
            " Say in your answer which values or rows did not arrive, and say the same in any file you save."
                    + " Do not present it as complete.";

    private CompletenessNote() {
    }

    /** True when the envelope root carries a reduction sign; {@code _egress} alone and nested fields do not count. */
    public static boolean rootShowsReduction(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (isTrue(root, "truncated") || isTrue(root, "sampleOnly") || isTrue(root, "rowsOmitted")
                || isTrue(root, ReadLimitFact.FIELD_REACHED)) {
            return true;
        }
        String kind = root.path("resultKind").asText("");
        if (RESULT_KIND_INFOTABLE_LARGE.equals(kind) || RESULT_KIND_RESULT_OVERFLOW.equals(kind)) {
            return true;
        }
        if (hasChunkTruncatedWarning(root)) {
            return true;
        }
        JsonNode egress = root.path("_egress");
        return egress.isObject() && (egress.has("reducedFields") || egress.has("rawChars"));
    }

    private static boolean isTrue(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v != null && v.isBoolean() && v.booleanValue();
    }

    static boolean hasChunkTruncatedWarning(JsonNode root) {
        JsonNode warnings = root.path("warnings");
        if (!warnings.isArray()) {
            return false;
        }
        for (JsonNode w : warnings) {
            if (w != null && CHUNK_MARKDOWN_TRUNCATED.equals(w.path("code").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /** True for a root object with {@code columns} and {@code rows} or {@code sampleRows} arrays. */
    public static boolean isTableShaped(JsonNode root) {
        return root != null && root.isObject() && root.path("columns").isArray()
                && (root.path("rows").isArray() || root.path("sampleRows").isArray());
    }

    static String rowsKey(JsonNode root) {
        return root.path("sampleRows").isArray() ? "sampleRows" : "rows";
    }

    /** Total rows a table-shaped root says the source had, or -1 when it does not say. */
    static long totalRows(JsonNode root) {
        if (root.path("totalRows").canConvertToLong()) {
            return root.path("totalRows").asLong();
        }
        if (root.path("rowCount").canConvertToLong()) {
            return root.path("rowCount").asLong();
        }
        return -1;
    }

    /**
     * Adds the note to an egress result whose envelope root shows a reduction. Error envelopes, non-object roots and
     * results that already carry a note are returned unchanged.
     */
    static EgressResult annotate(EgressResult out) {
        if (out == null) {
            return null;
        }
        String content = out.getLlmContent();
        if (content == null || content.isEmpty() || content.charAt(0) != '{') {
            return out;
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (Exception notJson) {
            return out;
        }
        if (!root.isObject() || ToolResultEgressGateway.isErrorEnvelope(root) || root.has(FIELD)
                || !rootShowsReduction(root)) {
            return out;
        }
        ObjectNode obj = (ObjectNode) root;
        obj.put(FIELD, text(variantFor(obj, out.isCompacted()), obj));
        try {
            String annotated = JSON.writeValueAsString(obj);
            return out.isCompacted()
                    ? EgressResult.compacted(annotated, out.getRawChars())
                    : EgressResult.annotated(annotated, out.getRawChars());
        } catch (Exception e) {
            return out;
        }
    }

    static Variant variantFor(JsonNode root, boolean gatewayReduced) {
        String kind = root.path("resultKind").asText("");
        if (RESULT_KIND_RESULT_OVERFLOW.equals(kind)) {
            return Variant.OVERFLOW;
        }
        if (gatewayReduced) {
            return Variant.GATEWAY_CUT;
        }
        if (isTableShaped(root) && totalRows(root) >= 0) {
            return Variant.TABLE_SAMPLE;
        }
        if (hasChunkTruncatedWarning(root)) {
            return Variant.PAGE;
        }
        return Variant.PRODUCER_PARTIAL;
    }

    /** The note text for {@code variant}, built from the root (row counts, a cache handle, the page shape). */
    public static String text(Variant variant, JsonNode root) {
        String note;
        switch (variant) {
            case OVERFLOW:
                note = "This result was too large to show; only its shape is given (see rawChars and the names"
                        + " listed). None of its values arrived. Do not answer from it as if you had seen the data;"
                        + " say in your answer that the result was too large, and say the same in any file you save."
                        + recovery(root);
                break;
            case TABLE_SAMPLE:
                note = tableSampleText(rowsShown(root), Math.max(0, totalRows(root)), root);
                break;
            case PAGE:
                note = "This page was cut at the configured maximum (see warnings " + CHUNK_MARKDOWN_TRUNCATED
                        + "). The rest of the page did not arrive." + SAY_IT
                        + " To see more, search for the missing passage or read a neighbouring chunk.";
                break;
            case PRODUCER_PARTIAL:
                note = "This result is partial: the tool returned only part of the data (see readLimitReached,"
                        + " readLimitNote, sampleOnly, rowsOmitted, truncated)." + SAY_IT + recovery(root);
                break;
            case GATEWAY_CUT:
            default:
                note = "This result was reduced before you saw it (see truncated, rowsOmitted,"
                        + " _egress.reducedFields)." + SAY_IT + recovery(root);
                break;
        }
        return note.length() <= MAX_NOTE_CHARS ? note : note.substring(0, MAX_NOTE_CHARS);
    }

    static String tableSampleText(long shown, long total, JsonNode root) {
        return "This result is a sample of " + shown + " of " + total + " rows. The other rows did not arrive."
                + SAY_IT + recovery(root);
    }

    private static long rowsShown(JsonNode root) {
        JsonNode rows = root.path(rowsKey(root));
        return rows.isArray() ? rows.size() : 0;
    }

    /** The recovery clause of R25c: ask the tool for less, with a cache route only when a handle exists. */
    static String recovery(JsonNode root) {
        String cacheId = root.path("cacheId").asText("");
        if (cacheId.isBlank()) {
            if (hasChunkTruncatedWarning(root) || "page".equals(root.path("contentType").asText(""))) {
                return " To see more, search for the missing passage or read a neighbouring chunk.";
            }
            return " To see more, ask the tool for less: fewer fields, fewer rows or a narrower request.";
        }
        if (isTableShaped(root) || root.path("columnNames").isArray()) {
            return " To see more, ask the tool for less, or read the cached result by its cacheId:"
                    + " tabulate_cached_result with mode \"filter_sort_topn\", a sorts entry and fields (every"
                    + " column you name, for up to maxItems rows, default 50 and at most 500, paged with offset),"
                    + " or fetch_cached_result for a page (a page over 4,096 characters of row text is itself"
                    + " reduced to a sample).";
        }
        return " To see more, ask the tool for less, or inspect the stored copy by its cacheId with"
                + " inspect_cached_payload.";
    }
}
