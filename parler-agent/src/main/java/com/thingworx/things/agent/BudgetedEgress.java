package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.CompletenessNote.Variant;
import com.thingworx.things.agent.ToolResultEgressGateway.EgressResult;

/**
 * The budgeted egress of a non-error JSON tool result (topic round 6, R23 and R25b). Every size is the serialized
 * length in UTF-16 code units, escaping, markers, the completeness note and the classification stamp included.
 *
 * <ol>
 * <li>Sample eligible arrays to 20 (today's exemptions), recording markers.</li>
 * <li>Build the candidate with its markers, note and stamp; at or under the soft cap it is returned, text uncut (the
 * raw string byte for byte when nothing was sampled, annotated or stamped).</li>
 * <li>Otherwise a table-shaped root keeps whole rows, in source order, while the envelope fits; any other root takes
 * pass 1 (text cap, arrays 20), pass 2 (512, arrays 5) and pass 3 (the last resort) while still over.</li>
 * <li>What still does not fit becomes the overflow response, itself built within the cap.</li>
 * </ol>
 */
final class BudgetedEgress {

    static final String RESULT_KIND_RESULT_OVERFLOW = CompletenessNote.RESULT_KIND_RESULT_OVERFLOW;
    static final int HINT_MAX_CHARS = 400;

    private BudgetedEgress() {
    }

    static EgressResult compact(JsonNode root, String raw, ResultBudget budget, List<String> classification) {
        int cap = budget.softCapChars();
        String rootKind = ToolResultEgressGateway.rootResultKind(root);

        ObjectNode markers = ToolResultEgressGateway.JSON.createObjectNode();
        JsonNode sampled = sampleArrays(root, markers, rootKind);
        Built candidate = finish(sampled, markers, raw.length(), classification, null);
        if (candidate.json.length() <= cap) {
            if (markers.size() == 0 && !candidate.noteAdded && classification.isEmpty()
                    && raw.length() <= cap) {
                return EgressResult.unchanged(raw);
            }
            return result(candidate, markers, raw);
        }

        if (CompletenessNote.isTableShaped(root)) {
            return tableFill(root, markers, raw, budget, classification);
        }

        markers = ToolResultEgressGateway.JSON.createObjectNode();
        JsonNode pass1 = ToolResultEgressGateway.compactNode(root, null, ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT,
                budget.textCapChars(), markers, rootKind);
        markRootArray(root, pass1, markers);
        Built built = finish(pass1, markers, raw.length(), classification, null);
        if (built.json.length() <= cap) {
            return result(built, markers, raw);
        }
        markers = ToolResultEgressGateway.JSON.createObjectNode();
        JsonNode pass2 = ToolResultEgressGateway.compactNode(root, null,
                Math.max(1, ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT / 4),
                ToolResultEgressGateway.HARD_TEXT_EXCERPT_CHARS, markers, rootKind);
        markRootArray(root, pass2, markers);
        built = finish(pass2, markers, raw.length(), classification, null);
        if (built.json.length() <= cap) {
            return result(built, markers, raw);
        }
        markers = ToolResultEgressGateway.JSON.createObjectNode();
        JsonNode pass3 = ToolResultEgressGateway.compactLastResort(root, markers, rootKind);
        built = finish(pass3, markers, raw.length(), classification, null);
        if (built.json.length() <= cap) {
            return result(built, markers, raw);
        }
        return overflow(root, raw, budget, classification);
    }

    /**
     * Step 2 of R23: sample eligible arrays to 20. A table-shaped root keeps its rows opaque: only the outer rows
     * array is limited to 20, each kept row whole (D7: a cell is never cut, array-valued and nested cells included);
     * its other fields take the ordinary sampling. A sampled root array is recorded as {@code $root}.
     */
    static JsonNode sampleArrays(JsonNode root, ObjectNode markers, String rootKind) {
        if (!CompletenessNote.isTableShaped(root)) {
            JsonNode sampled = ToolResultEgressGateway.compactNode(root, null,
                    ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT, Integer.MAX_VALUE, markers, rootKind);
            markRootArray(root, sampled, markers);
            return sampled;
        }
        String rowsKey = CompletenessNote.rowsKey(root);
        ObjectNode out = ToolResultEgressGateway.JSON.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (rowsKey.equals(e.getKey())) {
                out.set(rowsKey, outerRows(root, rowsKey, markers));
            } else if ("columns".equals(e.getKey())) {
                out.set(e.getKey(), e.getValue());
            } else {
                out.set(e.getKey(), compactField(e.getKey(), e.getValue(),
                        ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT, Integer.MAX_VALUE, markers, rootKind));
            }
        }
        return out;
    }

    /**
     * Compacts one root field through the parent-object branch of {@code compactNode}, so a sampled array field
     * records its {@code originalCount} / {@code returnedCount} marker exactly as it does inside any object.
     */
    private static JsonNode compactField(String name, JsonNode value, int arrayLimit, int textLimit,
            ObjectNode markers, String rootKind) {
        ObjectNode holder = ToolResultEgressGateway.JSON.createObjectNode();
        holder.set(name, value);
        return ToolResultEgressGateway.compactNode(holder, null, arrayLimit, textLimit, markers, rootKind).get(name);
    }

    /** The first 20 rows of a table-shaped root, each the original row object, unmodified. */
    private static ArrayNode outerRows(JsonNode root, String rowsKey, ObjectNode markers) {
        JsonNode rows = root.path(rowsKey);
        ArrayNode kept = ToolResultEgressGateway.JSON.createArrayNode();
        int limit = Math.min(rows.size(), ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT);
        for (int i = 0; i < limit; i++) {
            kept.add(rows.get(i));
        }
        if (markers != null && rows.size() > limit) {
            ObjectNode m = ToolResultEgressGateway.JSON.createObjectNode();
            m.put("originalCount", rows.size());
            m.put("returnedCount", limit);
            markers.set(rowsKey, m);
        }
        return kept;
    }

    /** Records a sampled root array, which has no parent field to carry its marker (R26: no silent loss). */
    private static void markRootArray(JsonNode root, JsonNode reduced, ObjectNode markers) {
        if (root != null && root.isArray() && reduced != null && reduced.isArray() && reduced.size() < root.size()) {
            ObjectNode m = ToolResultEgressGateway.JSON.createObjectNode();
            m.put("originalCount", root.size());
            m.put("returnedCount", reduced.size());
            markers.set("$root", m);
        }
    }

    private static EgressResult result(Built built, ObjectNode markers, String raw) {
        if (markers.size() > 0 || !built.json.equals(raw) && !built.noteAdded && !built.stamped) {
            return EgressResult.compacted(built.json, raw.length());
        }
        if (built.noteAdded) {
            return EgressResult.annotated(built.json, raw.length());
        }
        return EgressResult.classified(built.json, raw.length());
    }

    /** One serialized envelope and what was added to it. */
    static final class Built {
        final String json;
        final boolean noteAdded;
        final boolean stamped;

        Built(String json, boolean noteAdded, boolean stamped) {
            this.json = json;
            this.noteAdded = noteAdded;
            this.stamped = stamped;
        }
    }

    /**
     * Wraps a non-object body when it was reduced, applies the root markers, adds the note when the root shows a
     * reduction (with {@code noteOverride} when the caller knows the variant text) and the classification stamp.
     */
    static Built finish(JsonNode body, ObjectNode markers, int rawChars, List<String> classification,
            String noteOverride) {
        ObjectNode obj;
        if (body != null && body.isObject()) {
            obj = (ObjectNode) body;
        } else if (markers.size() > 0) {
            obj = ToolResultEgressGateway.JSON.createObjectNode();
            obj.put("status", ToolResultEgressGateway.statusText(body));
            obj.put("resultKind", "LARGE_JSON_COMPACT");
            obj.set("sample", body);
        } else {
            return new Built(write(body), false, false);
        }
        if (markers.size() > 0) {
            ToolResultEgressGateway.applyTopLevelMarkers(obj, markers, rawChars);
        }
        boolean noteAdded = false;
        if (noteOverride != null) {
            obj.put(CompletenessNote.FIELD, noteOverride);
            noteAdded = true;
        } else if (!obj.has(CompletenessNote.FIELD) && CompletenessNote.rootShowsReduction(obj)) {
            obj.put(CompletenessNote.FIELD, CompletenessNote.text(
                    CompletenessNote.variantFor(obj, markers.size() > 0), obj));
            noteAdded = true;
        }
        boolean stamped = stamp(obj, classification);
        return new Built(write(obj), noteAdded, stamped);
    }

    static boolean stamp(ObjectNode obj, List<String> classification) {
        if (classification.isEmpty()) {
            return false;
        }
        ObjectNode egress = obj.has("_egress") && obj.get("_egress").isObject()
                ? (ObjectNode) obj.get("_egress")
                : ToolResultEgressGateway.JSON.createObjectNode();
        ArrayNode arr = egress.putArray("dataClassification");
        classification.forEach(arr::add);
        obj.set("_egress", egress);
        return true;
    }

    // ---- R25b: whole-row fill of a table-shaped root ----

    private static EgressResult tableFill(JsonNode root, ObjectNode sampleMarkers, String raw,
            ResultBudget budget, List<String> classification) {
        int cap = budget.softCapChars();
        String rowsKey = CompletenessNote.rowsKey(root);
        // The original rows (outer limit of 20 kept), measured and kept or dropped whole.
        ArrayNode rows = outerRows(root, rowsKey, null);
        long total = CompletenessNote.totalRows(root);
        boolean declaresTotal = total >= 0;
        if (!declaresTotal) {
            total = root.path(rowsKey).size();
        }
        String rootKind = ToolResultEgressGateway.rootResultKind(root);

        // Protected and other fields: every field but the rows; other text fields are cut at the text cap as usual,
        // cells are never cut (the rows are taken whole from the sampled copy).
        ObjectNode textMarkers = ToolResultEgressGateway.JSON.createObjectNode();
        ObjectNode base = ToolResultEgressGateway.JSON.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (rowsKey.equals(e.getKey())) {
                base.putNull(rowsKey);
                continue;
            }
            if ("columns".equals(e.getKey()) || CompletenessNote.FIELD.equals(e.getKey())) {
                base.set(e.getKey(), e.getValue());
                continue;
            }
            base.set(e.getKey(), compactField(e.getKey(), e.getValue(),
                    ToolResultEgressGateway.ARRAY_SAMPLE_LIMIT, budget.textCapChars(), textMarkers, rootKind));
        }
        boolean hasCache = !base.path("cacheId").asText("").isBlank();

        int k = 0;
        Built fitted = null;
        long mDigitsPlaceholder = total;
        Built zero = tableEnvelope(base, rowsKey, rows, 0, total, declaresTotal, hasCache, sampleMarkers, textMarkers,
                raw.length(), classification, mDigitsPlaceholder);
        if (zero.json.length() > cap) {
            return overflow(root, raw, budget, classification);
        }
        fitted = zero;
        for (int n = 1; n <= rows.size(); n++) {
            Built next = tableEnvelope(base, rowsKey, rows, n, total, declaresTotal, hasCache, sampleMarkers,
                    textMarkers, raw.length(), classification, mDigitsPlaceholder);
            if (next.json.length() > cap) {
                break;
            }
            k = n;
            fitted = next;
        }
        Built fin = tableEnvelope(base, rowsKey, rows, k, total, declaresTotal, hasCache, sampleMarkers, textMarkers,
                raw.length(), classification, k);
        if (fin.json.length() > cap) {
            fin = fitted;
        }
        return EgressResult.compacted(fin.json, raw.length());
    }

    private static Built tableEnvelope(ObjectNode base, String rowsKey, ArrayNode rows, int k, long total,
            boolean declaresTotal, boolean hasCache, ObjectNode sampleMarkers, ObjectNode textMarkers, int rawChars,
            List<String> classification, long noteN) {
        ObjectNode obj = base.deepCopy();
        ArrayNode kept = obj.putArray(rowsKey);
        for (int i = 0; i < k; i++) {
            kept.add(rows.get(i));
        }
        obj.put("returnedRows", k);
        if (!declaresTotal) {
            obj.put("totalRows", total);
        }
        boolean rowsLeftOut = k < total;
        ObjectNode markers = sampleMarkers.deepCopy();
        markers.setAll(textMarkers.deepCopy());
        if (k < rows.size()) {
            ObjectNode m = ToolResultEgressGateway.JSON.createObjectNode();
            m.put("originalCount", total);
            m.put("returnedCount", k);
            markers.set(rowsKey, m);
        }
        if (rowsLeftOut) {
            obj.put("rowsOmitted", true);
            if (!hasCache) {
                obj.put("recovery", "unavailable");
            }
        }
        if (markers.size() > 0) {
            ToolResultEgressGateway.applyTopLevelMarkers(obj, markers, rawChars);
        }
        String note = null;
        if (rowsLeftOut) {
            note = CompletenessNote.tableSampleText(noteN, total, obj);
        } else if (markers.size() > 0) {
            note = CompletenessNote.text(Variant.GATEWAY_CUT, obj);
        }
        if (note != null) {
            obj.remove(CompletenessNote.FIELD);
        }
        return finish(obj, ToolResultEgressGateway.JSON.createObjectNode(), rawChars, classification, note);
    }

    // ---- R23 step 6: the budgeted overflow response ----

    static EgressResult overflow(JsonNode root, String raw, ResultBudget budget, List<String> classification) {
        int cap = budget.softCapChars();
        boolean table = CompletenessNote.isTableShaped(root);
        List<String> names = table ? columnNames(root) : fieldNames(root);
        String namesKey = table ? "columnNames" : "fieldNames";
        String omittedKey = table ? "columnNamesOmitted" : "fieldNamesOmitted";
        String cacheId = root.path("cacheId").asText("");

        ObjectNode readLimit = readLimitEvidence(root);
        Built withHint = overflowWithNames(raw, cacheId, names, namesKey, omittedKey, classification, cap, true, table,
                readLimit);
        if (withHint != null) {
            return EgressResult.compacted(withHint.json, raw.length());
        }
        Built noHint = overflowWithNames(raw, cacheId, names, namesKey, omittedKey, classification, cap, false, table,
                readLimit);
        if (noHint != null) {
            return EgressResult.compacted(noHint.json, raw.length());
        }
        // Reduce the read-limit evidence in tiers (all of it, then the flag and the note, then the flag) and, on the
        // first tier that fits, still add the names that fit with their omitted count.
        for (ObjectNode evidence : evidenceTiers(readLimit)) {
            Built named = overflowWithNames(raw, cacheId, names, namesKey, omittedKey, classification, cap, false,
                    table, evidence);
            if (named != null) {
                return EgressResult.compacted(named.json, raw.length());
            }
        }
        // No names fit at all: the fixed fields with as much evidence as fits.
        for (ObjectNode evidence : evidenceTiers(readLimit)) {
            ObjectNode o = fixedOverflow(raw, cacheId, false, table);
            o.setAll(evidence.deepCopy());
            stamp(o, classification);
            String json = write(o);
            if (json.length() <= cap) {
                return EgressResult.compacted(json, raw.length());
            }
        }
        // The stated exception, measured: the minimal envelope (fixed fields, note, retained stamp, and the
        // read-limit flag when the source read was limited) itself exceeds the cap.
        ObjectNode minimal = fixedOverflow(raw, cacheId, false, table);
        if (readLimit.has(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED)) {
            minimal.set(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED,
                    readLimit.get(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED));
        }
        stamp(minimal, classification);
        if (write(minimal).length() > cap) {
            ((ObjectNode) minimal.get("_egress")).putObject("reducedFields").put("overBudget", true);
        }
        return EgressResult.compacted(write(minimal), raw.length());
    }

    /** The read-limit evidence in decreasing completeness: all of it, then the flag and the note, then the flag. */
    private static List<ObjectNode> evidenceTiers(ObjectNode readLimit) {
        List<ObjectNode> tiers = new ArrayList<>();
        tiers.add(readLimit);
        String reached = com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED;
        String note = com.thingworx.things.agent.source.ReadLimitFact.FIELD_NOTE;
        if (readLimit.has(reached)) {
            ObjectNode flagAndNote = ToolResultEgressGateway.JSON.createObjectNode();
            flagAndNote.set(reached, readLimit.get(reached));
            if (readLimit.has(note)) {
                flagAndNote.set(note, readLimit.get(note));
            }
            tiers.add(flagAndNote);
            ObjectNode flag = ToolResultEgressGateway.JSON.createObjectNode();
            flag.set(reached, readLimit.get(reached));
            tiers.add(flag);
        }
        return tiers;
    }

    private static ObjectNode fixedOverflow(String raw, String cacheId, boolean hint, boolean table) {
        ObjectNode o = ToolResultEgressGateway.JSON.createObjectNode();
        o.put("status", "success");
        o.put("resultKind", RESULT_KIND_RESULT_OVERFLOW);
        o.put("rawChars", raw.length());
        if (!cacheId.isBlank()) {
            o.put("cacheId", cacheId);
        } else {
            o.put("recovery", "unavailable");
        }
        ObjectNode forNote = o.deepCopy();
        if (table) {
            forNote.putArray("columnNames");
        }
        o.put(CompletenessNote.FIELD, CompletenessNote.text(Variant.OVERFLOW, forNote));
        if (hint) {
            String h = "The result exceeded the tool-result budget (toolResultSoftCapChars) even after reduction."
                    + " Ask the tool for less: fewer fields, fewer rows or a narrower request.";
            o.put("hint", h.length() <= HINT_MAX_CHARS ? h : h.substring(0, HINT_MAX_CHARS));
        }
        ObjectNode egress = o.putObject("_egress");
        egress.put("reason", "llm_evidence_cap");
        egress.put("rawChars", raw.length());
        return o;
    }

    /**
     * TR-0 read-limit evidence of the root (the flag, the note, at most six series labels; text cut at 512), kept in
     * the overflow response as it is kept through every pass: it is how a result says its source read was limited.
     */
    private static ObjectNode readLimitEvidence(JsonNode root) {
        ObjectNode out = ToolResultEgressGateway.JSON.createObjectNode();
        if (root == null || !root.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (ToolResultEgressGateway.isReadLimitEvidence(e.getKey(), e.getValue())) {
                out.set(e.getKey(), ToolResultEgressGateway.compactNode(e.getValue(), e.getKey(),
                        Integer.MAX_VALUE, ToolResultEgressGateway.HARD_TEXT_EXCERPT_CHARS,
                        ToolResultEgressGateway.JSON.createObjectNode(), ""));
            }
        }
        return out;
    }

    private static Built overflowWithNames(String raw, String cacheId, List<String> names, String namesKey,
            String omittedKey, List<String> classification, int cap, boolean hint, boolean table,
            ObjectNode readLimit) {
        ObjectNode base = fixedOverflow(raw, cacheId, hint, table);
        base.setAll(readLimit.deepCopy());
        // Measure with no name and the largest omitted count, then add names by their serialized cost.
        ObjectNode probe = base.deepCopy();
        probe.putArray(namesKey);
        if (!names.isEmpty()) {
            probe.put(omittedKey, names.size());
        }
        stamp(probe, classification);
        int length = write(probe).length();
        if (length > cap) {
            return null;
        }
        int k = 0;
        for (String name : names) {
            int cost = write(ToolResultEgressGateway.JSON.getNodeFactory().textNode(name)).length() + (k == 0 ? 0 : 1);
            if (length + cost > cap) {
                break;
            }
            length += cost;
            k++;
        }
        while (true) {
            ObjectNode o = base.deepCopy();
            ArrayNode arr = o.putArray(namesKey);
            for (int i = 0; i < k; i++) {
                arr.add(names.get(i));
            }
            if (k < names.size()) {
                o.put(omittedKey, names.size() - k);
            }
            stamp(o, classification);
            String json = write(o);
            if (json.length() <= cap || k == 0) {
                return json.length() <= cap ? new Built(json, true, !classification.isEmpty()) : null;
            }
            k--;
        }
    }

    private static List<String> columnNames(JsonNode root) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : root.path("columns")) {
            if (c.isTextual()) {
                out.add(c.asText());
            } else if (c.has("name")) {
                out.add(c.path("name").asText(""));
            }
        }
        return out;
    }

    private static List<String> fieldNames(JsonNode root) {
        List<String> out = new ArrayList<>();
        if (root != null && root.isObject()) {
            root.fieldNames().forEachRemaining(out::add);
        }
        return out;
    }

    private static String write(JsonNode n) {
        try {
            return ToolResultEgressGateway.JSON.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
