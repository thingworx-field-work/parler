package com.thingworx.things.agent.taskstate;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.source.SourceDescriptor;

/**
 * First-merge adapter interpretation per {@code docs/agent/task-state.md} (structural fields only).
 */
public final class TaskStateInvokeFetchParsers {

    private TaskStateInvokeFetchParsers() {}

    public static void applyInvokeServiceResult(AgentTaskEvidence row, String json) {
        if (row == null) {
            return;
        }
        if (json == null || json.isEmpty()) {
            row.setStatus("error");
            row.setErrorCode(TaskStateErrorCode.TOOL_RESULT_INVALID);
            row.setRowCount(-1);
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            String status = root.optString("status", "");
            if ("error".equals(status)) {
                row.setStatus("error");
                row.setProtectedOmissions(false);
                row.setErrorCode(TaskStateErrorMapper.mapFromToolJson(root));
                row.setRowCount(-1);
                return;
            }
            if (!"success".equals(status)) {
                row.setStatus("error");
                row.setErrorCode(TaskStateErrorCode.UNKNOWN);
                row.setRowCount(-1);
                return;
            }
            row.setStatus("ok");
            row.setErrorCode(null);
            String rk = root.optString("resultKind", null);
            row.setResultKind(rk);
            if ("INFOTABLE".equals(rk)) {
                int rc = root.optInt("rowCount", -1);
                if (rc < 0 && root.has("rows") && root.get("rows") instanceof JSONArray) {
                    rc = root.getJSONArray("rows").length();
                }
                row.setRowCount(rc);
                row.setTotalCount(rc);
                row.setTotalCountInferred(false);
                row.setSampleOnly(false);
                row.setCacheId(null);
                // Full inlined INFOTABLE is a completed return of the examined set.
                row.setCompletenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE);
            } else if ("INFOTABLE_LARGE".equals(rk)) {
                JSONArray sample = root.optJSONArray("sampleRows");
                int sampleLen = sample != null ? sample.length() : 0;
                row.setRowCount(sampleLen);
                boolean hasTotal = root.has("totalRows") && !root.isNull("totalRows");
                int total = hasTotal ? root.getInt("totalRows") : sampleLen;
                row.setTotalCount(total);
                row.setTotalCountInferred(!hasTotal);
                row.setSampleOnly(true);
                String cid = root.optString("cacheId", null);
                row.setCacheId(cid != null && !cid.isEmpty() ? cid : null);
                row.setCompletenessStatus(SourceDescriptor.CompletenessStatus.PARTIAL);
            } else {
                // Generic / entity-list style success (e.g. query_entities): never invent COMPLETE.
                int returned = root.has("returnedRows") ? root.optInt("returnedRows", -1)
                        : root.optInt("rowCount", -1);
                boolean hasTotal = root.has("totalRows") && !root.isNull("totalRows");
                int total = hasTotal ? root.getInt("totalRows") : -1;
                boolean inferred = root.optBoolean("totalRowsInferred", false) || !hasTotal;
                row.setRowCount(returned);
                row.setTotalCount(total);
                row.setTotalCountInferred(inferred);
                row.setSampleOnly(false);
                row.setCompletenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN);
            }
            if (root.optBoolean("protectedValueOmissions", false) || root.optBoolean("passwordFieldsOmitted", false)) {
                row.setProtectedOmissions(true);
            }
        } catch (Exception e) {
            row.setStatus("error");
            row.setErrorCode(TaskStateErrorCode.TOOL_RESULT_INVALID);
            row.setRowCount(-1);
        }
    }

    public static void applyFetchCachedResult(AgentTaskEvidence row, String json) {
        if (row == null) {
            return;
        }
        if (json == null || json.isEmpty()) {
            row.setStatus("error");
            row.setErrorCode(TaskStateErrorCode.TOOL_RESULT_INVALID);
            row.setRowCount(-1);
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            String status = root.optString("status", "");
            if ("error".equals(status)) {
                row.setStatus("error");
                row.setProtectedOmissions(false);
                row.setErrorCode(TaskStateErrorMapper.mapFromToolJson(root));
                row.setRowCount(-1);
                return;
            }
            if (!"success".equals(status)) {
                row.setStatus("error");
                row.setErrorCode(TaskStateErrorCode.UNKNOWN);
                row.setRowCount(-1);
                return;
            }
            row.setStatus("ok");
            row.setErrorCode(null);
            row.setResultKind("CACHED_PAGE");
            int returned = root.optInt("returnedRows", 0);
            boolean hasTotal = root.has("totalRows") && !root.isNull("totalRows");
            int total = hasTotal ? root.getInt("totalRows") : returned;
            row.setRowCount(returned);
            row.setTotalCount(total);
            row.setTotalCountInferred(!hasTotal);
            String cid = root.optString("cacheId", null);
            row.setCacheId(cid != null && !cid.isEmpty() ? cid : null);
            boolean hasMore = root.optBoolean("hasMore", false);
            int offset = root.optInt("offset", 0);
            if (root.optBoolean("sampleOnly", false)) {
                row.setSampleOnly(true);
            } else {
                row.setSampleOnly(hasMore || offset > 0 || returned < total);
            }
            if (row.isSampleOnly() || !hasTotal || hasMore || offset > 0 || returned < total) {
                row.setCompletenessStatus(SourceDescriptor.CompletenessStatus.PARTIAL);
            } else {
                row.setCompletenessStatus(SourceDescriptor.CompletenessStatus.COMPLETE);
            }
        } catch (Exception e) {
            row.setStatus("error");
            row.setErrorCode(TaskStateErrorCode.TOOL_RESULT_INVALID);
            row.setRowCount(-1);
        }
    }
}
