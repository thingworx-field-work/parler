package com.thingworx.things.agent.playbook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/** Compact evidence block for final {@code llm_summary}. */
public final class PlaybookEvidenceFormatter {

    /** {@code evidence.includeToolOutputRootFields} entries must be simple JSON name tokens. */
    private static final Pattern TOOL_OUTPUT_ROOT_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** Cap fan-out child blocks in {@code llm_summary} evidence. */
    private static final int FAN_OUT_CHILD_EVIDENCE_CAP = 16;

    private PlaybookEvidenceFormatter() {}

    public static String format(PlaybookDocument document, PlaybookRunContext ctx, JSONArray evidenceRefs, int maxBytes) {
        Set<String> provenNumericProps = collectProvenNumericPropertyNames(document, ctx, evidenceRefs);
        StringBuilder sb = new StringBuilder();
        sb.append("Playbook evidence:\n");
        if (evidenceRefs != null) {
            for (int i = 0; i < evidenceRefs.length(); i++) {
                String nodeId = evidenceRefs.optString(i, "");
                if (nodeId.isEmpty()) {
                    continue;
                }
                appendNodeEvidence(sb, nodeId, document, ctx, provenNumericProps);
            }
        }
        String text = sb.toString().trim();
        return truncateToUtf8ByteBudget(text, maxBytes);
    }

    /**
     * UTF-8 byte-safe truncation: guarantees the returned string's UTF-8 byte length is {@code <= maxBytes}, never
     * splits a multibyte code point, and accounts for the marker's own bytes. A char-index
     * {@code substring} is not byte-safe — multibyte thing names / property labels / row values can otherwise return
     * evidence text whose UTF-8 byte length still exceeds the budget.
     */
    static String truncateToUtf8ByteBudget(String text, int maxBytes) {
        if (text == null || maxBytes <= 0) {
            return text;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        String marker = "\n… (truncated)";
        int markerBytes = marker.getBytes(StandardCharsets.UTF_8).length;
        if (maxBytes <= markerBytes) {
            String ascii = "(truncated)";
            return ascii.length() <= maxBytes ? ascii : ascii.substring(0, maxBytes);
        }
        int budget = maxBytes - markerBytes;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + cpBytes > budget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += cpBytes;
            i += Character.charCount(cp);
        }
        return sb.append(marker).toString();
    }

    /**
     * Property names that received successful numeric history / trend reads elsewhere in the same evidence bundle,
     * used to qualify {@code primary_property} name-heuristic lines.
     */
    static Set<String> collectProvenNumericPropertyNames(PlaybookDocument document, PlaybookRunContext ctx,
            JSONArray evidenceRefs) {
        Set<String> sink = new HashSet<>();
        if (evidenceRefs == null || document == null || ctx == null) {
            return sink;
        }
        for (int i = 0; i < evidenceRefs.length(); i++) {
            String nodeId = evidenceRefs.optString(i, "");
            if (nodeId.isEmpty()) {
                continue;
            }
            collectProvenNumericFromReferencedNode(nodeId, document, ctx, sink);
        }
        return sink;
    }

    private static void collectProvenNumericFromReferencedNode(String nodeId, PlaybookDocument document,
            PlaybookRunContext ctx, Set<String> sink) {
        JSONObject nodeMeta = document.nodesById().get(nodeId);
        JSONObject nodeOut = ctx.nodeOutput(nodeId);
        if (nodeOut == null) {
            return;
        }
        if (nodeMeta != null && "fan_out".equals(nodeMeta.optString("kind", ""))) {
            JSONArray ch = nodeOut.optJSONArray("children");
            if (ch != null) {
                for (int i = 0; i < ch.length(); i++) {
                    JSONObject c = ch.optJSONObject(i);
                    if (c != null) {
                        collectProvenNumericPropertyFromToolOut(c.optJSONObject("toolOutput"), sink);
                    }
                }
            }
            return;
        }
        collectProvenNumericPropertyFromToolOut(nodeOut.optJSONObject("toolOutput"), sink);
        collectProvenNumericFromTrendSummaryOutput(nodeOut.optJSONObject("output"), sink);
    }

    private static void collectProvenNumericPropertyFromToolOut(JSONObject toolOut, Set<String> sink) {
        if (toolOut == null) {
            return;
        }
        if (!"success".equals(toolOut.optString("status", ""))) {
            return;
        }
        String rk = toolOut.optString("resultKind", "");
        if (!rk.contains("NUMERIC")) {
            return;
        }
        int pts = toolOut.optInt("pointsReturned", toolOut.optInt("returnedRows", -1));
        JSONArray sample = toolOut.optJSONArray("sampleRows");
        boolean hasPoints = pts > 0;
        boolean hasSample = sample != null && sample.length() > 0;
        if (!hasPoints && !hasSample) {
            return;
        }
        String pn = toolOut.optString("propertyName", "").trim();
        if (!pn.isEmpty()) {
            sink.add(pn);
        }
    }

    private static void collectProvenNumericFromTrendSummaryOutput(JSONObject output, Set<String> sink) {
        if (output == null) {
            return;
        }
        JSONArray assets = output.optJSONArray("assets");
        if (assets == null) {
            return;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null || !"ok".equals(a.optString("status", ""))) {
                continue;
            }
            boolean hasTrend = a.has("first") || a.has("last") || a.optInt("pointsReturned", 0) > 0;
            if (!hasTrend) {
                continue;
            }
            String pn = a.optString("propertyName", "").trim();
            if (!pn.isEmpty()) {
                sink.add(pn);
            }
        }
    }

    private static void appendNodeEvidence(StringBuilder sb, String nodeId, PlaybookDocument document,
            PlaybookRunContext ctx, Set<String> provenNumericPropertyNames) {
        JSONObject out = ctx.nodeOutput(nodeId);
        if (out == null) {
            return;
        }
        String status = out.optString("status", "");
        if ("needs_clarification".equals(status)) {
            sb.append("- ").append(nodeId).append(": ").append(out.optString("message", "")).append('\n');
            return;
        }
        JSONObject node = document != null ? document.nodesById().get(nodeId) : null;

        if (node != null && "fan_out".equals(node.optString("kind", ""))) {
            String fanBlock = formatFanOutChildToolEvidenceBlock(nodeId, node, out, ctx);
            if (fanBlock != null && !fanBlock.isBlank()) {
                sb.append("- ").append(nodeId).append(":\n").append(fanBlock).append('\n');
                return;
            }
        }

        String explicit = formatExplicitEvidence(out);
        if (explicit != null && !explicit.isBlank()) {
            if (isPrimaryPropertyDeriveNode(node)) {
                String refreshed = refreshPrimaryPropertyEvidenceText(out, provenNumericPropertyNames);
                if (refreshed != null && !refreshed.isBlank()) {
                    explicit = refreshed;
                }
            }
            sb.append("- ").append(nodeId).append(": ").append(explicit).append('\n');
            return;
        }
        JSONObject toolOut = out.optJSONObject("toolOutput");
        if (node != null && toolOut != null) {
            JSONObject ev = node.optJSONObject("evidence");
            boolean wantsRoot = hasNonEmptyIncludeToolOutputRootFields(ev);
            boolean wantsPaths = hasNonEmptyIncludeToolOutputPaths(ev);
            String rootFields = formatIncludedToolOutputRootFields(node, toolOut);
            String pathFields = formatIncludedToolOutputPaths(node, toolOut);
            String projected = projectTableEvidenceSummary(nodeId, node, ctx, toolOut);
            boolean hasTableSpec = ev != null && ev.optJSONObject("table") != null;
            String scalarPrelude = mergeScalarEvidenceBlocks(rootFields, pathFields);

            if ((wantsRoot || wantsPaths) && scalarPrelude != null && !scalarPrelude.isBlank()) {
                String tablePart = projected;
                if ((tablePart == null || tablePart.isBlank()) && hasTableSpec) {
                    tablePart = formatEmptyTableProjectionNote(toolOut);
                }
                String block = mergeRootFieldsAndTableBlock(scalarPrelude, tablePart);
                block = appendUiArtifactBlock(block, formatUiArtifactEvidenceLines(toolOut));
                sb.append("- ").append(nodeId).append(": ").append(block).append('\n');
                return;
            }
            if (projected != null && !projected.isBlank()) {
                String block = mergeRootFieldsAndTableBlock(scalarPrelude, projected);
                block = appendUiArtifactBlock(block, formatUiArtifactEvidenceLines(toolOut));
                sb.append("- ").append(nodeId).append(": ").append(block).append('\n');
                return;
            }
            String uiOnly = formatUiArtifactEvidenceLines(toolOut);
            if (uiOnly != null && !uiOnly.isBlank()) {
                sb.append("- ").append(nodeId).append(": ").append(uiOnly.trim()).append('\n');
                return;
            }
        }
        JSONObject output = out.optJSONObject("output");
        String legacy = output != null ? formatLegacyFallback(output, out) : null;
        if (legacy != null && !legacy.isBlank()) {
            sb.append("- ").append(nodeId).append(": ").append(legacy).append('\n');
            return;
        }
        JSONObject evidence = out.optJSONObject("evidence");
        if (evidence != null) {
            String summary = evidence.optString("summary", "");
            if (!summary.isBlank()) {
                sb.append("- ").append(nodeId).append(": ").append(summary).append('\n');
            }
        }
    }

    private static boolean isPrimaryPropertyDeriveNode(JSONObject node) {
        return node != null && "derive".equals(node.optString("kind", ""))
                && "primary_property".equals(node.optString("op", ""));
    }

    private static String refreshPrimaryPropertyEvidenceText(JSONObject nodeOut, Set<String> provenNumericPropertyNames) {
        JSONObject output = nodeOut.optJSONObject("output");
        if (output == null) {
            return null;
        }
        return PlaybookNodeEvidence.primaryPropertyLineForSummary(output, provenNumericPropertyNames);
    }

    /**
     * When a {@code fan_out} wraps a {@code tool_call}, expand one bounded block per child for {@code llm_summary}.
     * A child contributes its configured {@code includeToolOutputRootFields} / {@code table}
     * projection and/or — even without any child evidence config — a compact {@code uiArtifact.chart} line whenever
     * the child emitted a UI chart artifact, mirroring the top-level {@code tool_call} {@code uiOnly} fallback so
     * fan-outs of chart-emitting internal tools never lose artifact awareness.
     */
    static String formatFanOutChildToolEvidenceBlock(String fanOutId, JSONObject fanOutNode, JSONObject fanOutResult,
            PlaybookRunContext ctx) {
        JSONObject inner;
        try {
            inner = fanOutNode.getJSONObject("node");
        } catch (Exception e) {
            return null;
        }
        if (!"tool_call".equals(inner.optString("kind", ""))) {
            return null;
        }
        JSONArray children = fanOutResult.optJSONArray("children");
        if (children == null || children.length() == 0) {
            return null;
        }
        JSONObject innerEv = inner.optJSONObject("evidence");
        boolean wantsRoot = hasNonEmptyIncludeToolOutputRootFields(innerEv);
        boolean wantsPaths = hasNonEmptyIncludeToolOutputPaths(innerEv);
        boolean hasTable = innerEv != null && innerEv.optJSONObject("table") != null;
        int cap = Math.min(children.length(), FAN_OUT_CHILD_EVIDENCE_CAP);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            JSONObject ch = children.optJSONObject(i);
            if (ch == null) {
                continue;
            }
            JSONObject toolOut = ch.optJSONObject("toolOutput");
            JSONObject item = ch.optJSONObject("item");
            String childNodeId = fanOutId + "[" + i + "]";
            String block = null;
            if (wantsRoot || wantsPaths || hasTable) {
                String scalarPrelude = mergeScalarEvidenceBlocks(
                        formatIncludedToolOutputRootFields(inner, toolOut),
                        formatIncludedToolOutputPaths(inner, toolOut));
                String projected = projectTableEvidenceSummary(childNodeId, inner, ctx, toolOut);
                if ((wantsRoot || wantsPaths) && scalarPrelude != null && !scalarPrelude.isBlank()) {
                    String tablePart = projected;
                    if ((tablePart == null || tablePart.isBlank()) && hasTable) {
                        tablePart = formatEmptyTableProjectionNote(toolOut);
                    }
                    block = mergeRootFieldsAndTableBlock(scalarPrelude, tablePart);
                } else if (projected != null && !projected.isBlank()) {
                    block = mergeRootFieldsAndTableBlock(
                            scalarPrelude != null && !scalarPrelude.isBlank() ? scalarPrelude : null, projected);
                }
            }
            block = appendUiArtifactBlock(block, formatUiArtifactEvidenceLines(toolOut));
            if (block == null || block.isBlank()) {
                continue;
            }
            String identity = fanOutChildIdentityLine(item, toolOut);
            sb.append("  child[").append(i).append("] ").append(identity).append(":\n");
            sb.append(block).append('\n');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String fanOutChildIdentityLine(JSONObject item, JSONObject toolOut) {
        String thing = "";
        String prop = "";
        if (item != null) {
            thing = item.optString("thingName", item.optString("name", "")).trim();
            prop = item.optString("propertyName", "").trim();
        }
        if (thing.isEmpty() && toolOut != null) {
            thing = toolOut.optString("thingName", "").trim();
        }
        if (prop.isEmpty() && toolOut != null) {
            prop = toolOut.optString("propertyName", "").trim();
        }
        if (thing.isEmpty() && prop.isEmpty()) {
            return "(child)";
        }
        if (prop.isEmpty()) {
            return thing;
        }
        if (thing.isEmpty()) {
            return prop;
        }
        return thing + "/" + prop;
    }

    /** Compact UI chart/table artifact metadata for {@code llm_summary} — no series payloads. */
    static String formatUiArtifactEvidenceLines(JSONObject toolOut) {
        if (toolOut == null || !toolOut.optBoolean("chartEmitted", false)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  uiArtifact.chart emitted=true");
        if (toolOut.has("chartBlockPersisted")) {
            sb.append(" persisted=").append(toolOut.optBoolean("chartBlockPersisted", false));
        }
        if (toolOut.has("chartBlockPointCount")) {
            sb.append(" pointCount=").append(toolOut.optInt("chartBlockPointCount", 0));
        }
        String kind = toolOut.optString("chart_kind", "").trim();
        if (kind.isEmpty()) {
            kind = toolOut.optString("resultKind", "").trim();
        }
        if (!kind.isEmpty()) {
            sb.append(" kind=").append(kind);
        }
        sb.append(" (Parler UI wire artifact; not an image in the LLM context)");
        return sb.toString();
    }

    private static String appendUiArtifactBlock(String block, String uiLines) {
        if (uiLines == null || uiLines.isBlank()) {
            return block;
        }
        if (block == null || block.isBlank()) {
            return uiLines.trim();
        }
        return block + '\n' + uiLines.trim();
    }

    /**
     * When {@code evidence.table} is configured on a {@code tool_call} node, returns a compact row/column summary
     * following projection source order: raw run table, then JSON {@code rows}, then {@code sampleRows} (noted as
     * sampled).
     */
    public static String projectTableEvidenceSummary(String nodeId, JSONObject node, PlaybookRunContext ctx,
            JSONObject toolOut) {
        JSONObject evSpec = node.optJSONObject("evidence");
        if (evSpec == null) {
            return null;
        }
        JSONObject tableSpec = evSpec.optJSONObject("table");
        if (tableSpec == null) {
            return null;
        }
        int maxRows = tableSpec.optInt("maxRows", 12);
        JSONArray columns = tableSpec.optJSONArray("columns");
        List<String> colNames = new ArrayList<>();
        if (columns != null) {
            for (int i = 0; i < columns.length(); i++) {
                String c = columns.optString(i, "");
                if (!c.isEmpty()) {
                    colNames.add(c);
                }
            }
        }
        InfoTable raw = ctx != null ? ctx.rawTable(nodeId + ".result") : null;
        if (raw != null) {
            return renderInfoTableRows(raw, colNames, maxRows, null);
        }
        String tablePath = tableSpec.optString("path", "").trim();
        if (!tablePath.isEmpty()) {
            Object resolved = PlaybookEvidencePathSupport.resolveDotPath(toolOut, tablePath);
            if (resolved instanceof JSONArray) {
                JSONArray pathRows = (JSONArray) resolved;
                if (pathRows.length() > 0) {
                    return renderJsonRows(pathRows, colNames, maxRows, null);
                }
            }
            return null;
        }
        JSONArray rows = toolOut.optJSONArray("rows");
        if (rows != null && rows.length() > 0) {
            return renderJsonRows(rows, colNames, maxRows, null);
        }
        JSONArray sample = toolOut.optJSONArray("sampleRows");
        if (sample != null && sample.length() > 0) {
            return renderJsonRows(sample, colNames, maxRows, "(sampled rows only; not full table)");
        }
        return null;
    }

    private static String mergeNotes(String a, String b) {
        if (b == null || b.isBlank()) {
            return a;
        }
        if (a == null || a.isBlank()) {
            return b;
        }
        return a + "; " + b;
    }

    private static String renderInfoTableRows(InfoTable table, List<String> columns, int maxRows, String note) {
        if (table == null || table.getRowCount() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(maxRows, table.getRowCount());
        boolean[] colPrinted = columns.isEmpty() ? null : new boolean[columns.size()];
        for (int r = 0; r < n; r++) {
            ValueCollection row = table.getRow(r);
            sb.append("  row").append(r + 1).append(": ");
            if (columns.isEmpty()) {
                sb.append("(no columns configured)");
            } else {
                boolean first = true;
                for (int ci = 0; ci < columns.size(); ci++) {
                    String col = columns.get(ci);
                    Object v = row.getValue(col);
                    if (v == null) {
                        continue;
                    }
                    colPrinted[ci] = true;
                    if (!first) {
                        sb.append("; ");
                    }
                    first = false;
                    sb.append(col).append('=').append(String.valueOf(v));
                }
            }
            sb.append('\n');
        }
        String missingNote = null;
        if (colPrinted != null) {
            List<String> silent = new ArrayList<>();
            for (int ci = 0; ci < columns.size(); ci++) {
                if (!colPrinted[ci]) {
                    silent.add(columns.get(ci));
                }
            }
            if (!silent.isEmpty()) {
                missingNote = "no values shown for configured columns: " + String.join(", ", silent);
            }
        }
        note = mergeNotes(note, missingNote);
        if (note != null) {
            sb.append("  ").append(note).append('\n');
        }
        return sb.toString().trim();
    }

    private static String renderJsonRows(JSONArray rows, List<String> columns, int maxRows, String note) {
        if (rows == null || rows.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(maxRows, rows.length());
        boolean[] colPrinted = columns.isEmpty() ? null : new boolean[columns.size()];
        for (int r = 0; r < n; r++) {
            JSONObject row = rows.optJSONObject(r);
            sb.append("  row").append(r + 1).append(": ");
            if (row == null) {
                sb.append("(null)");
                sb.append('\n');
                continue;
            }
            if (columns.isEmpty()) {
                sb.append(row.toString());
            } else {
                boolean first = true;
                for (int ci = 0; ci < columns.size(); ci++) {
                    String col = columns.get(ci);
                    if (!row.has(col)) {
                        continue;
                    }
                    colPrinted[ci] = true;
                    if (!first) {
                        sb.append("; ");
                    }
                    first = false;
                    sb.append(col).append('=').append(String.valueOf(row.opt(col)));
                }
            }
            sb.append('\n');
        }
        String missingNote = null;
        if (colPrinted != null) {
            List<String> silent = new ArrayList<>();
            for (int ci = 0; ci < columns.size(); ci++) {
                if (!colPrinted[ci]) {
                    silent.add(columns.get(ci));
                }
            }
            if (!silent.isEmpty()) {
                missingNote = "no values shown for configured columns: " + String.join(", ", silent);
            }
        }
        note = mergeNotes(note, missingNote);
        if (note != null) {
            sb.append("  ").append(note).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * When {@code evidence.includeToolOutputRootFields} lists string keys, copies matching scalar fields from the
     * tool JSON root (e.g. {@code appliedStartTime} / {@code rowCount} on {@code query_alert_history} envelopes) into
     * evidence lines before row projection.
     */
    static String formatIncludedToolOutputRootFields(JSONObject node, JSONObject toolOut) {
        if (node == null || toolOut == null) {
            return null;
        }
        JSONObject ev = node.optJSONObject("evidence");
        if (ev == null) {
            return null;
        }
        JSONArray names = ev.optJSONArray("includeToolOutputRootFields");
        if (names == null || names.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i, "").trim();
            if (name.isEmpty() || !TOOL_OUTPUT_ROOT_FIELD.matcher(name).matches()) {
                continue;
            }
            if (!toolOut.has(name)) {
                continue;
            }
            Object v = toolOut.opt(name);
            if (v == null || JSONObject.NULL.equals(v)) {
                continue;
            }
            if (v instanceof JSONObject || v instanceof JSONArray) {
                continue;
            }
            sb.append("  ").append(name).append('=').append(String.valueOf(v)).append('\n');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String mergeScalarEvidenceBlocks(String rootFieldsOrNull, String pathFieldsOrNull) {
        if (rootFieldsOrNull == null || rootFieldsOrNull.isBlank()) {
            return pathFieldsOrNull;
        }
        if (pathFieldsOrNull == null || pathFieldsOrNull.isBlank()) {
            return rootFieldsOrNull;
        }
        return rootFieldsOrNull + '\n' + pathFieldsOrNull;
    }

    /**
     * When {@code evidence.includeToolOutputPaths} lists bounded dot paths from the tool-output root, copy matching
     * scalar leaves into evidence lines (nested JSON envelope services).
     */
    static String formatIncludedToolOutputPaths(JSONObject node, JSONObject toolOut) {
        if (node == null || toolOut == null) {
            return null;
        }
        JSONObject ev = node.optJSONObject("evidence");
        if (ev == null) {
            return null;
        }
        JSONArray paths = ev.optJSONArray("includeToolOutputPaths");
        if (paths == null || paths.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.length(); i++) {
            String dotPath = paths.optString(i, "").trim();
            if (dotPath.isEmpty() || !PlaybookEvidencePathSupport.isValidDotPath(dotPath)) {
                continue;
            }
            Object v = PlaybookEvidencePathSupport.resolveDotPath(toolOut, dotPath);
            if (v == null || JSONObject.NULL.equals(v) || v instanceof JSONObject || v instanceof JSONArray) {
                continue;
            }
            sb.append("  ").append(dotPath).append('=').append(String.valueOf(v)).append('\n');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Non-null {@code tableProjection} with optional root-field prelude (each line already indented with two spaces). */
    static String mergeRootFieldsAndTableBlock(String rootFieldsOrNull, String tableProjection) {
        if (rootFieldsOrNull == null || rootFieldsOrNull.isBlank()) {
            return tableProjection;
        }
        if (tableProjection == null || tableProjection.isBlank()) {
            return rootFieldsOrNull;
        }
        return rootFieldsOrNull + '\n' + tableProjection;
    }

    /** True when {@code evidence.includeToolOutputRootFields} is a non-empty JSON array. */
    public static boolean hasNonEmptyIncludeToolOutputRootFields(JSONObject evidenceSpec) {
        if (evidenceSpec == null) {
            return false;
        }
        JSONArray names = evidenceSpec.optJSONArray("includeToolOutputRootFields");
        return names != null && names.length() > 0;
    }

    /** True when {@code evidence.includeToolOutputPaths} is a non-empty JSON array. */
    public static boolean hasNonEmptyIncludeToolOutputPaths(JSONObject evidenceSpec) {
        if (evidenceSpec == null) {
            return false;
        }
        JSONArray paths = evidenceSpec.optJSONArray("includeToolOutputPaths");
        return paths != null && paths.length() > 0;
    }

    /**
     * When {@code evidence.table} is configured but the tool returned no rows to project, emit a one-line note so
     * root-only metadata (e.g. {@code query_alert_history} window + {@code rowCount: 0}) still reaches
     * {@code llm_summary}.
     */
    public static String formatEmptyTableProjectionNote(JSONObject toolOut) {
        if (toolOut == null) {
            return "  (no table rows)";
        }
        int rc = toolOut.optInt("rowCount", toolOut.optInt("totalCount", -1));
        if (rc >= 0) {
            return "  (no table rows; rowCount=" + rc + ")";
        }
        return "  (no table rows)";
    }

    /** Prefer derive-provided compact evidence; formatter stays schema-agnostic. */
    private static String formatExplicitEvidence(JSONObject nodeOut) {
        JSONArray lines = nodeOut.optJSONArray("evidenceLines");
        if (lines != null && lines.length() > 0) {
            return PlaybookNodeEvidence.joinLines(lines);
        }
        String text = nodeOut.optString("evidenceText", "");
        return text.isBlank() ? null : text;
    }

    /** Minimal fallback for nodes that omit explicit evidence (should be rare). */
    private static String formatLegacyFallback(JSONObject output, JSONObject nodeOut) {
        if (output.has("EntityType")) {
            return PlaybookNodeEvidence.taxonomyLine(output);
        }
        JSONObject toolOut = nodeOut.optJSONObject("toolOutput");
        if (toolOut != null) {
            int rows = toolOut.optInt("rowCount", toolOut.optInt("totalCount", -1));
            if (rows >= 0) {
                return "tool returned " + rows + " rows";
            }
        }
        return null;
    }
}
