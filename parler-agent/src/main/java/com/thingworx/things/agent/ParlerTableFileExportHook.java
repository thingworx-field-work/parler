package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.entities.RootEntity;
import com.thingworx.logging.LogUtilities;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Optional CSV export to a configured FileRepository Thing ({@code SaveText}) before Parler
 * {@code type: "table"} is sent — see {@code docs/ui/table-view-solution.md} §5 and {@code CONTRACTS/TABLE_CONTRACT.md}.
 */
public final class ParlerTableFileExportHook {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ParlerTableFileExportHook.class);
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[^a-zA-Z0-9._-]+");

    private ParlerTableFileExportHook() {}

    /**
     * Mutates {@code table} export fields when policy applies; may emit a short {@code activity} line before
     * {@code SaveText}. Artifact-local cache faults degrade to the existing bounded export fallback;
     * repository-wide cache failure propagates to the terminal channel adapter.
     */
    public static void apply(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            JSONObject table) {
        if (table == null) {
            return;
        }
        // Re-built TableBlock after stream sidecar merge, or second downlink pass: do not re-run SaveText.
        if (!"none".equals(table.optString("exportStatus", "none"))) {
            return;
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return;
        }
        final int maxCsvChars = agent.getParlerTableExportMaxCsvChars();
        String repoName = agent.getParlerTableExportRepositoryName();
        int threshold = agent.getParlerTableExportRowThreshold();
        JSONArray cols = table.optJSONArray("columns");
        JSONArray rows = table.optJSONArray("rows");
        if (cols == null || rows == null || cols.length() < 1) {
            return;
        }
        int rowsLen = rows.length();
        int totalRows = table.optInt("totalRows", -1);
        if (totalRows < 0) {
            totalRows = rowsLen;
        }
        boolean partialSample = totalRows > rowsLen && rowsLen > 0;
        boolean overThreshold = totalRows > threshold;
        if (!partialSample && !overThreshold) {
            return;
        }
        if (repoName == null || repoName.trim().isEmpty()) {
            table.put("exportStatus", "repo_missing");
            table.put("exportMessage",
                    "Table export skipped: AgentThing exportFileRepository (FileRepository Thing name) is empty.");
            table.put("exportFile", JSONObject.NULL);
            table.put("exportRepository", JSONObject.NULL);
            table.put("exportDownloadUrl", JSONObject.NULL);
            return;
        }
        repoName = repoName.trim();
        if (remoteConversation != null && wireRequestId != null && wireConversationId != null) {
            ParlerReceiveMessageSupport.send(remoteConversation,
                    ParlerReceiveMessageSupport.wireActivity(wireRequestId, wireConversationId,
                            "Large table: writing CSV export to FileRepository…"));
        }
        String cacheId = readCacheId(table);
        InfoTable full = null;
        if (cacheId != null && !cacheId.isEmpty()) {
            try {
                full = InvokeServiceExecutor.lookupCachedInfotable(cacheId);
            } catch (ArtifactCacheException e) {
                ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
                LOG.warn("Parler table CSV cache lookup unavailable: code={}", e.code());
            }
        }
        String csv;
        if (full != null) {
            csv = csvFromInfoTable(full, cols);
        } else if (!partialSample && rowsLen > 0) {
            csv = csvFromJsonRows(rows, cols);
        } else {
            LOG.warn(
                    "Parler table CSV skipped_limit: reason=no_cache_for_full_table totalRows={} inlineRows={} conversationId={} requestId={}",
                    totalRows, rowsLen,
                    wireConversationId != null ? wireConversationId : "",
                    wireRequestId != null ? wireRequestId : "");
            table.put("exportStatus", "skipped_limit");
            table.put("exportMessage",
                    "Full CSV export requires a conversation cacheId for the complete table; only a sample is on the wire.");
            table.put("exportFile", JSONObject.NULL);
            table.put("exportRepository", JSONObject.NULL);
            table.put("exportDownloadUrl", JSONObject.NULL);
            return;
        }
        if (csv == null || csv.isEmpty()) {
            table.put("exportStatus", "write_error");
            table.put("exportMessage", "CSV export produced no content.");
            table.put("exportFile", JSONObject.NULL);
            table.put("exportRepository", JSONObject.NULL);
            table.put("exportDownloadUrl", JSONObject.NULL);
            return;
        }
        if (ParlerTableCsvPathCollisions.csvExceedsMaxChars(csv, maxCsvChars)) {
            LOG.warn(
                    "Parler table CSV skipped_limit: reason=max_chars csvChars={} cap={} totalRows={} inlineRows={} conversationId={} requestId={}",
                    csv.length(), maxCsvChars, totalRows, rowsLen,
                    wireConversationId != null ? wireConversationId : "",
                    wireRequestId != null ? wireRequestId : "");
            table.put("exportStatus", "skipped_limit");
            table.put("exportMessage", "CSV export skipped: result exceeds configured size limit.");
            table.put("exportFile", JSONObject.NULL);
            table.put("exportRepository", JSONObject.NULL);
            table.put("exportDownloadUrl", JSONObject.NULL);
            return;
        }
        String path = buildExportPath(wireRequestId);
        try {
            RootEntity ent = PlatformAccess.findProgrammatic(repoName, RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (ent == null) {
                table.put("exportStatus", "repo_error");
                table.put("exportMessage", "FileRepository Thing not found: " + repoName);
                table.put("exportFile", JSONObject.NULL);
                table.put("exportRepository", JSONObject.NULL);
                table.put("exportDownloadUrl", JSONObject.NULL);
                return;
            }
            if (!(ent instanceof Thing)) {
                table.put("exportStatus", "repo_error");
                table.put("exportMessage", "Configured exportFileRepository is not a Thing: " + repoName);
                table.put("exportFile", JSONObject.NULL);
                table.put("exportRepository", JSONObject.NULL);
                table.put("exportDownloadUrl", JSONObject.NULL);
                return;
            }
            Thing repoThing = (Thing) ent;
            ParlerTableCsvPathCollisions.FileListingLookup lister =
                    (parent, mask) -> invokeGetFileListing(repoThing, parent, mask);
            String chosenPath = ParlerTableCsvPathCollisions.resolveUniqueCsvPath(lister, path);
            if (chosenPath == null) {
                table.put("exportStatus", "path_collision");
                table.put("exportMessage",
                        "CSV export aborted: target path already exists and alternate paths could not be reserved.");
                table.put("exportFile", JSONObject.NULL);
                table.put("exportRepository", JSONObject.NULL);
                table.put("exportDownloadUrl", JSONObject.NULL);
                return;
            }
            ValueCollection vc = new ValueCollection();
            vc.put("path", new StringPrimitive(chosenPath));
            vc.put("content", new StringPrimitive(csv));
            PlatformAccess.invokeProgrammatic(repoThing, "SaveText", vc);
            table.put("exportStatus", "ok");
            table.put("exportMessage", JSONObject.NULL);
            table.put("exportRepository", repoName);
            table.put("exportFile", chosenPath);
            table.put("exportDownloadUrl", JSONObject.NULL);
        } catch (Exception e) {
            LOG.warn("Parler table CSV SaveText failed: {}", e.getMessage());
            table.put("exportStatus", "write_error");
            table.put("exportMessage", "CSV export unavailable.");
            table.put("exportFile", JSONObject.NULL);
            table.put("exportRepository", repoName);
            table.put("exportDownloadUrl", JSONObject.NULL);
        }
    }

    private static InfoTable invokeGetFileListing(Thing fileRepositoryThing, String parent, String nameMask)
            throws Exception {
        try {
            ValueCollection vc = new ValueCollection();
            vc.put("path", new StringPrimitive(parent));
            vc.put("nameMask", new StringPrimitive(nameMask != null ? nameMask : ""));
            Object out = PlatformAccess.invokeProgrammatic(fileRepositoryThing, "GetFileListing", vc);
            if (!(out instanceof InfoTable)) {
                LOG.warn("GetFileListing on {} returned {}; treating as not listed", parent,
                        out == null ? "null" : out.getClass().getName());
                return null;
            }
            return (InfoTable) out;
        } catch (Exception e) {
            if (ParlerTableCsvPathCollisions.isMissingParentDirectoryMessage(e.getMessage())) {
                return null;
            }
            throw e;
        }
    }

    private static String readCacheId(JSONObject table) {
        if (!table.has("cacheId") || table.isNull("cacheId")) {
            return null;
        }
        String s = table.optString("cacheId", "");
        return s.isEmpty() ? null : s;
    }

    private static String buildExportPath(String wireRequestId) {
        return exportPath(SecurityContextUtil.currentPrincipalName(), wireRequestId, DateTime.now(DateTimeZone.UTC));
    }

    static String exportPath(String principal, String wireRequestId, DateTime nowUtc) {
        String user = principal == null || principal.isEmpty() ? "anonymous" : principal;
        String safeUser = sanitizeSegment(user);
        String day = nowUtc.toString("yyyyMMdd");
        String ts = nowUtc.toString("yyyyMMdd'T'HHmmss'Z'");
        String rid = sanitizeSegment(wireRequestId != null ? wireRequestId : "req");
        return "/" + safeUser + "/" + day + "/" + ts + "_" + rid + ".csv";
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "x";
        }
        String s = SAFE_SEGMENT.matcher(raw).replaceAll("_");
        if (s.isEmpty()) {
            return "x";
        }
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }
        return s;
    }

    private static List<String> columnKeysFromTable(JSONArray cols) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < cols.length(); i++) {
            JSONObject c = cols.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String k = c.optString("key", "");
            if (!k.isEmpty()) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static String csvFromJsonRows(JSONArray rows, JSONArray cols) {
        List<String> keys = columnKeysFromTable(cols);
        if (keys.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, keys);
        for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.optJSONObject(r);
            appendDataRow(sb, keys, row);
        }
        return sb.toString();
    }

    private static String csvFromInfoTable(InfoTable full, JSONArray cols) {
        List<String> keys = columnKeysFromTable(cols);
        if (keys.isEmpty() || full == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, keys);
        for (int r = 0; r < full.getRowCount(); r++) {
            ValueCollection vc = full.getRow(r);
            JSONObject pseudo = new JSONObject();
            if (vc != null) {
                for (String k : keys) {
                    try {
                        Object v = vc.getValue(k);
                        pseudo.put(k, primitiveToJsonish(v));
                    } catch (Exception ignored) {
                        pseudo.put(k, JSONObject.NULL);
                    }
                }
            }
            appendDataRow(sb, keys, pseudo);
        }
        return sb.toString();
    }

    private static Object primitiveToJsonish(Object v) {
        if (v == null) {
            return JSONObject.NULL;
        }
        try {
            if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
                return ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
            }
        } catch (Exception ignored) {
            return v.toString();
        }
        return v;
    }

    private static void appendHeader(StringBuilder sb, List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsvCell(keys.get(i)));
        }
        sb.append('\n');
    }

    private static void appendDataRow(StringBuilder sb, List<String> keys, JSONObject row) {
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String k = keys.get(i);
            Object v = row != null && row.has(k) ? row.get(k) : JSONObject.NULL;
            sb.append(escapeCsvCell(formatCell(v)));
        }
        sb.append('\n');
    }

    private static String formatCell(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return "";
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Number) {
            return String.valueOf(v);
        }
        return String.valueOf(v);
    }

    private static String escapeCsvCell(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        boolean needQuote = raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0;
        String s = raw.replace("\"", "\"\"");
        if (needQuote) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
