package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads entity rows from {@code query_entities_by_taxonomy} tool JSON ({@code rootEntityList} or
 * {@code sampleRootEntityList} when {@code ENTITY_TAXONOMY_QUERY_LARGE}).
 */
final class PlaybookTaxonomyEntityRows {

    static final class Extracted {
        private final JSONArray rows;
        private final boolean partial;
        private final String gapNote;

        Extracted(JSONArray rows, boolean partial, String gapNote) {
            this.rows = rows != null ? rows : new JSONArray();
            this.partial = partial;
            this.gapNote = gapNote;
        }

        JSONArray rows() {
            return rows;
        }

        boolean partial() {
            return partial;
        }

        String gapNote() {
            return gapNote;
        }
    }

    private PlaybookTaxonomyEntityRows() {}

    static Extracted fromToolOutput(JSONObject toolOut) {
        if (toolOut == null) {
            return new Extracted(new JSONArray(), false, null);
        }
        String kind = toolOut.optString("resultKind", "");
        if ("ENTITY_TAXONOMY_QUERY_LARGE".equals(kind)) {
            JSONArray sample = toolOut.optJSONArray("sampleRootEntityList");
            if (sample == null) {
                sample = new JSONArray();
            }
            int total = toolOut.optInt("totalCount", sample.length());
            String note = "Entity list truncated (totalCount=" + total
                    + "); asset count and downstream reads use sample only.";
            return new Extracted(sample, true, note);
        }
        JSONArray root = toolOut.optJSONArray("rootEntityList");
        if (root != null) {
            return new Extracted(root, false, null);
        }
        JSONArray sample = toolOut.optJSONArray("sampleRootEntityList");
        if (sample != null && sample.length() > 0) {
            return new Extracted(sample, true,
                    "Entity list uses sample rows only; full region scope may be incomplete.");
        }
        return new Extracted(new JSONArray(), false, null);
    }
}
