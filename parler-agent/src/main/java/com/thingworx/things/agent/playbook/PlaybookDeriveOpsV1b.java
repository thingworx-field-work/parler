package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.ToolResultEgressGateway;

/** V1b derive transforms ({@code cross_asset_pair_health} and shared matchers). */
final class PlaybookDeriveOpsV1b {

    private static final int SUMMARY_MAX_BYTES = 8192;
    private static final int PROPERTY_UNION_CAP = 24;
    private static final int ALERT_GROUPS_CAP = 10;
    private static final int TOP_PROPERTIES_CAP = 5;

    private PlaybookDeriveOpsV1b() {}

    static JSONObject execute(String op, JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if ("match_entity_identifiers".equals(op)) {
            return matchEntityIdentifiers(args, ctx);
        }
        if ("require_exact_count".equals(op)) {
            return requireExactCount(args, ctx);
        }
        if ("flatten_pair_assets".equals(op)) {
            return flattenPairAssets(args, ctx);
        }
        if ("select_primary_problem_property".equals(op)) {
            return selectPrimaryProblemProperty(args, ctx);
        }
        if ("union_property_names".equals(op)) {
            return unionPropertyNames(args, ctx);
        }
        if ("trend_targets".equals(op)) {
            return trendTargets(args, ctx);
        }
        if ("trend_summary".equals(op)) {
            return trendSummary(args, ctx);
        }
        if ("summarize_asset_pair_health".equals(op)) {
            return summarizeAssetPairHealth(args, ctx);
        }
        if ("normalize_resolved_thing".equals(op)) {
            return normalizeResolvedThing(args, ctx);
        }
        if ("match_identifier_in_rows".equals(op)) {
            return matchIdentifierInRows(args, ctx);
        }
        if ("pick_branch_output".equals(op)) {
            return pickBranchOutput(args, ctx);
        }
        throw new PlaybookRunException("unknown derive op: " + op);
    }

    /**
     * Maps a {@code resolve_thing} {@code toolOutput} envelope (success / error / large) to either a compact
     * {@code match_entity_identifiers}-compatible success row or {@code needs_clarification}. See
     * {@code docs/agent/playbook-input-resolution.md}.
     */
    private static JSONObject normalizeResolvedThing(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String sourceNodeId = args != null ? args.optString("sourceNodeId", "").trim() : "";
        if (sourceNodeId.isEmpty()) {
            throw new PlaybookRunException("normalize_resolved_thing: missing sourceNodeId");
        }
        String label = args != null ? args.optString("label", "asset") : "asset";
        JSONObject nodeOut = ctx.nodeOutput(sourceNodeId);
        if (nodeOut == null) {
            throw new PlaybookRunException("normalize_resolved_thing: missing node output for " + sourceNodeId);
        }
        JSONObject toolOut = nodeOut.optJSONObject("toolOutput");
        if (toolOut == null) {
            return needsClarification("No resolver output for " + label + ".");
        }
        String status = toolOut.optString("status", "").trim();
        if ("error".equalsIgnoreCase(status)) {
            return normalizeResolveThingError(toolOut, label);
        }
        if (!"success".equalsIgnoreCase(status)) {
            return needsClarification("Unexpected resolve_thing status \"" + status + "\" for " + label + ".");
        }
        String resultKind = toolOut.optString("resultKind", "").trim();
        if ("THING_RESOLVED_LARGE".equals(resultKind) || "TAXONOMY_ASSET_IDENTIFIER_LARGE".equals(resultKind)) {
            JSONArray sample = toolOut.optJSONArray("sampleMatches");
            int total = toolOut.optInt("totalCount", -1);
            String msg = "Too many Things matched " + label + " (large result set";
            if (total >= 0) {
                msg += ", totalCount=" + total;
            }
            msg += "). Please narrow the identifier or supply an asset-type hint.";
            return needsClarification(msg, sample != null ? candidateNamesOnly(sample) : null);
        }
        JSONArray matches = toolOut.optJSONArray("matches");
        if (matches == null || matches.length() == 0) {
            return needsClarification("No Thing matched " + label + " after resolution.");
        }
        if (matches.length() > 1) {
            return needsClarification("Multiple Things matched " + label + ". Please choose one.",
                    candidateNamesOnly(matches));
        }
        JSONObject m0 = matches.optJSONObject(0);
        if (m0 == null) {
            return needsClarification("Resolver returned an invalid match row for " + label + ".");
        }
        String name = m0.optString("name", "").trim();
        if (name.isEmpty()) {
            return needsClarification("Resolver match for " + label + " had no canonical name.");
        }
        JSONObject output = new JSONObject();
        output.put("name", name);
        output.put("row", m0);
        output.put("ambiguousCount", 0);
        if (m0.has("matchedBy")) {
            output.put("matchedBy", m0.opt("matchedBy"));
        }
        return new JSONObject().put("status", "ok").put("output", output);
    }

    private static JSONObject normalizeResolveThingError(JSONObject toolOut, String label) {
        String code = toolOut.optString("code", "").trim();
        if ("IDENTITY_AMBIGUOUS".equals(code) || "ASSET_IDENTIFIER_AMBIGUOUS".equals(code)) {
            JSONArray c = toolOut.optJSONArray("candidates");
            return needsClarification("Multiple Things matched " + label + ". Please choose one.",
                    c != null ? candidateNamesOnly(c) : null);
        }
        if ("IDENTITY_NOT_FOUND".equals(code) || "ASSET_IDENTIFIER_NOT_FOUND".equals(code)) {
            return needsClarification(
                    toolOut.optString("message", "No Thing matched " + label + ".").trim());
        }
        if ("TAXONOMY_UNAVAILABLE".equals(code) || "ASSET_TYPES_NOT_CONFIGURED".equals(code)
                || "ASSET_TYPE_NOT_FOUND".equals(code)) {
            return needsClarification(
                    toolOut.optString("message", "Taxonomy or asset-type configuration blocks resolution for " + label
                            + " (" + code + ").").trim());
        }
        return needsClarification(
                toolOut.optString("message", "resolve_thing failed for " + label + " (" + code + ").").trim());
    }

    private static JSONArray candidateNamesOnly(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in == null) {
            return out;
        }
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String n = o.optString("name", "").trim();
            if (!n.isEmpty()) {
                out.put(new JSONObject().put("name", n));
            }
        }
        return out;
    }

    private static final List<String> DEFAULT_MATCH_IDENTIFIER_FIELDS = Arrays.asList(
            "machineName", "displayName", "description", "name", "Name", "ThingName", "EquipmentID",
            "EquipmentDesc");

    private enum IdentifierMatchTier {
        EXACT,
        CASE_INSENSITIVE,
        NORMALIZED,
        SUFFIX
    }

    /**
     * Deterministic row match for utilization-style listings: exact → case-insensitive → normalized whitespace /
     * separators → guarded suffix on canonical name columns only. See {@code docs/agent/playbook-input-resolution.md}.
     */
    private static JSONObject matchIdentifierInRows(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Object rowsObj = PlaybookExpressionResolver.resolve(args != null ? args.opt("rows") : null, ctx);
        JSONArray rows = toJsonArray(rowsObj);
        String identifier = stringArg(args, "identifier", ctx);
        String label = args != null ? args.optString("label", "identifier").trim() : "identifier";
        if (identifier.isBlank()) {
            throw new PlaybookRunException("match_identifier_in_rows: missing identifier");
        }
        if (rows.length() == 0) {
            return needsClarification("No rows available to match " + label + ".");
        }
        List<String> fields = matchIdentifierFieldList(args);
        String normId = normalizeIdentifier(identifier);
        for (IdentifierMatchTier tier : IdentifierMatchTier.values()) {
            for (String field : fields) {
                if (tier == IdentifierMatchTier.SUFFIX && !isCanonicalThingNameField(field)) {
                    continue;
                }
                List<JSONObject> hits = new ArrayList<>();
                for (int r = 0; r < rows.length(); r++) {
                    JSONObject row = rows.optJSONObject(r);
                    if (row == null) {
                        continue;
                    }
                    String cell = cellString(row, field);
                    if (cell.isEmpty()) {
                        continue;
                    }
                    if (identifierMatchesCell(cell, identifier, normId, tier)) {
                        hits.add(row);
                    }
                }
                if (hits.size() == 1) {
                    JSONObject row = hits.get(0);
                    String canon = canonicalThingNameFromRow(row);
                    if (canon.isEmpty()) {
                        return needsClarification("Matched " + label + " on field \"" + field
                                + "\" but the row has no canonical Thing name (machineName/name/ThingName); "
                                + "refusing to pass a "
                                + "non-canonical identifier downstream.");
                    }
                    JSONObject matchedBy = new JSONObject()
                            .put("field", field)
                            .put("tier", tier.name())
                            .put("value", identifier);
                    JSONObject output = new JSONObject()
                            .put("name", canon)
                            .put("row", row)
                            .put("ambiguousCount", 0)
                            .put("matchedBy", matchedBy);
                    return new JSONObject().put("status", "ok").put("output", output);
                }
                if (hits.size() > 1) {
                    return needsClarification("Multiple rows matched " + label + " \"" + identifier + "\" on field \""
                            + field + "\" (" + tier.name() + "). Please choose one.",
                            candidateNamesFromRows(hits));
                }
            }
        }
        return needsClarification("Could not match " + label + " \"" + identifier + "\" to a single machine row.");
    }

    private static List<String> matchIdentifierFieldList(JSONObject args) {
        JSONArray arr = args != null ? args.optJSONArray("fields") : null;
        if (arr == null || arr.length() == 0) {
            return DEFAULT_MATCH_IDENTIFIER_FIELDS;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String f = arr.optString(i, "").trim();
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return out.isEmpty() ? DEFAULT_MATCH_IDENTIFIER_FIELDS : out;
    }

    private static boolean isCanonicalThingNameField(String field) {
        String f = field.trim();
        return "name".equalsIgnoreCase(f) || "thingname".equalsIgnoreCase(f)
                || "machinename".equalsIgnoreCase(f);
    }

    private static String cellString(JSONObject row, String field) {
        if (row == null || !row.has(field)) {
            return "";
        }
        Object v = row.opt(field);
        if (v == null || v == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    private static boolean identifierMatchesCell(String cell, String rawId, String normId, IdentifierMatchTier tier) {
        switch (tier) {
            case EXACT:
                return cell.equals(rawId);
            case CASE_INSENSITIVE:
                return cell.equalsIgnoreCase(rawId);
            case NORMALIZED:
                return normalizeIdentifier(cell).equals(normId);
            case SUFFIX:
                // Guarded suffix: only a user-supplied short suffix matching the END of the canonical
                // Thing name. The inverse direction (a longer/contaminated identifier ending with the
                // canonical cell) is intentionally rejected so over-specific input does not silently match.
                String nc = normalizeIdentifier(cell);
                return !normId.isEmpty() && nc.endsWith(normId);
            default:
                return false;
        }
    }

    /**
     * Strict canonical Thing name for the matcher output: a column that is a real ThingWorx Thing name
     * ({@code name} / {@code Name} / {@code ThingName} / {@code machineName}) only. EquipmentID,
     * EquipmentDesc, displayName, and description are identity hints a row may be matched on, but they are NOT
     * canonical Thing names and MUST NOT be passed downstream as {@code Machine} (see
     * {@code docs/agent/playbook-input-resolution.md} §1 / §5.2).
     */
    private static String canonicalThingNameFromRow(JSONObject row) {
        String n = row.optString("machineName", "").trim();
        if (!n.isEmpty()) {
            return n;
        }
        n = thingName(row);
        if (!n.isEmpty()) {
            return n;
        }
        return row.optString("ThingName", "").trim();
    }

    /** Display label for clarification candidates only; may fall back to EquipmentID to help the user choose. */
    private static String candidateLabelFromRow(JSONObject row) {
        String n = canonicalThingNameFromRow(row);
        if (!n.isEmpty()) {
            return n;
        }
        return row.optString("EquipmentID", "").trim();
    }

    private static JSONArray candidateNamesFromRows(List<JSONObject> rows) {
        JSONArray out = new JSONArray();
        for (JSONObject row : rows) {
            String n = candidateLabelFromRow(row);
            if (!n.isEmpty()) {
                out.put(new JSONObject().put("name", n));
            }
        }
        return out;
    }

    /**
     * After an exclusive {@code condition}, copies the derive/tool JSON result from the taken branch's terminal node.
     * Required when both branch terminals must converge on one downstream consumer (see
     * {@code docs/agent/playbook-input-resolution.md} §6.0.2).
     */
    private static JSONObject pickBranchOutput(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        String condId = requireNonBlank(args, "conditionNodeId", "pick_branch_output");
        String thenId = requireNonBlank(args, "thenNodeId", "pick_branch_output");
        String elseId = requireNonBlank(args, "elseNodeId", "pick_branch_output");
        JSONObject condOut = ctx.nodeOutput(condId);
        if (condOut == null) {
            throw new PlaybookRunException("pick_branch_output: missing condition output for " + condId);
        }
        String branch = condOut.optString("branch", "").trim();
        String pickId = "then".equalsIgnoreCase(branch) ? thenId : elseId;
        JSONObject picked = ctx.nodeOutput(pickId);
        if (picked == null) {
            throw new PlaybookRunException("pick_branch_output: missing output for branch terminal " + pickId);
        }
        JSONObject out = new JSONObject();
        out.put("status", picked.optString("status", ""));
        for (String k : new String[] {"output", "message", "candidates"}) {
            if (picked.has(k)) {
                out.put(k, picked.get(k));
            }
        }
        return out;
    }

    private static String requireNonBlank(JSONObject args, String key, String opLabel) throws PlaybookRunException {
        String v = args != null ? args.optString(key, "").trim() : "";
        if (v.isEmpty()) {
            throw new PlaybookRunException(opLabel + ": missing " + key);
        }
        return v;
    }

    private static JSONObject matchEntityIdentifiers(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String identifier = stringArg(args, "identifier", ctx);
        String label = args != null ? args.optString("label", "asset") : "asset";
        Object candidatesObj = PlaybookExpressionResolver.resolve(
                args != null ? args.opt("candidates") : null, ctx);
        JSONArray rows = toJsonArray(candidatesObj);
        if (rows.length() == 0) {
            String candidatesRef = args != null ? args.optString("candidatesNodeId", "") : "";
            if (!candidatesRef.isEmpty()) {
                JSONObject toolOut = toolOutputFromNode(ctx, candidatesRef);
                rows = PlaybookTaxonomyEntityRows.fromToolOutput(toolOut).rows();
            }
        }
        String normalizedUser = normalizeIdentifier(identifier);
        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && rowMatchesIdentifier(row, normalizedUser, identifier)) {
                matches.add(row);
            }
        }
        if (matches.isEmpty()) {
            return needsClarification("I could not match " + label + " \""
                    + identifier + "\" to a single Thing. Please specify the exact asset name or identifier.");
        }
        if (matches.size() > 1) {
            JSONArray names = new JSONArray();
            for (JSONObject m : matches) {
                names.put(thingName(m));
            }
            return needsClarification("Multiple Things match " + label + " \"" + identifier
                    + "\". Please choose one: " + names);
        }
        JSONObject match = matches.get(0);
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject()
                .put("name", thingName(match))
                .put("row", match)
                .put("ambiguousCount", 0));
        return out;
    }

    private static JSONObject requireExactCount(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        String nodeId = args != null ? args.optString("matchNodeId", "") : "";
        JSONObject matchOut = ctx.nodeOutput(nodeId);
        if (matchOut == null) {
            throw new PlaybookRunException("require_exact_count: missing node " + nodeId);
        }
        String status = matchOut.optString("status", "");
        if ("needs_clarification".equals(status)) {
            return matchOut;
        }
        JSONObject output = matchOut.optJSONObject("output");
        if (output == null || output.optString("name", "").isBlank()) {
            return needsClarification("Could not resolve asset to exactly one Thing.");
        }
        return matchOut;
    }

    private static JSONObject flattenPairAssets(JSONObject args, PlaybookRunContext ctx) {
        JSONArray assets = new JSONArray();
        appendResolvedAsset(assets, ctx, args.optString("matchNodeA", "match_asset_a"), "A");
        appendResolvedAsset(assets, ctx, args.optString("matchNodeB", "match_asset_b"), "B");
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject().put("assets", assets));
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.pairAssetsLines(assets));
        return out;
    }

    private static void appendResolvedAsset(JSONArray assets, PlaybookRunContext ctx, String nodeId, String slot) {
        JSONObject matchOut = ctx.nodeOutput(nodeId);
        if (matchOut == null) {
            return;
        }
        JSONObject output = matchOut.optJSONObject("output");
        if (output == null) {
            return;
        }
        String name = output.optString("name", "");
        if (name.isEmpty()) {
            return;
        }
        JSONObject asset = new JSONObject();
        asset.put("slot", slot);
        asset.put("name", name);
        asset.put("row", output.optJSONObject("row"));
        assets.put(asset);
    }

    private static JSONObject selectPrimaryProblemProperty(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        Object groupsObj = PlaybookExpressionResolver.resolve(
                args != null ? args.opt("alertGroups") : null, ctx);
        if (groupsObj == null && args != null) {
            String ref = args.optString("alertGroupsRef", "");
            if (!ref.isEmpty()) {
                groupsObj = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", ref), ctx);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (groupsObj instanceof JSONObject) {
            JSONArray groups = ((JSONObject) groupsObj).optJSONArray("groups");
            accumulateGroups(groups, counts);
        } else if (groupsObj instanceof JSONArray) {
            accumulateGroups((JSONArray) groupsObj, counts);
        }
        String userHint = stringArg(args, "userDimension", ctx);
        String chosen = null;
        int best = -1;
        if (!userHint.isBlank()) {
            for (String prop : counts.keySet()) {
                if (prop.equalsIgnoreCase(userHint)) {
                    chosen = prop;
                    best = counts.get(prop);
                    break;
                }
            }
        }
        if (chosen == null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    chosen = e.getKey();
                }
            }
        }
        boolean numeric = chosen != null && isLikelyNumericProperty(chosen);
        JSONObject output = new JSONObject();
        output.put("primaryProperty", chosen != null ? chosen : JSONObject.NULL);
        output.put("isNumeric", numeric);
        output.put("numericNameHeuristicMatched", numeric);
        output.put("alertGroups", cappedGroups(counts));
        JSONArray gaps = new JSONArray();
        if (chosen == null) {
            gaps.put("No alert sourceProperty evidence to select a primary problem property.");
        }
        output.put("gaps", gaps);
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(PlaybookNodeEvidence.primaryPropertyLine(output)));
        return out;
    }

    private static JSONObject unionPropertyNames(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Set<String> names = new LinkedHashSet<>();
        addCriticalPropertyNames(names, PlaybookExpressionResolver.resolve(args.opt("criticalProperties"), ctx));
        Object primaryObj = PlaybookExpressionResolver.resolve(args.opt("primaryProperty"), ctx);
        if (primaryObj != null && !String.valueOf(primaryObj).isBlank()
                && !"null".equals(String.valueOf(primaryObj))) {
            names.add(String.valueOf(primaryObj).trim());
        }
        Object groupsObj = PlaybookExpressionResolver.resolve(args.opt("alertGroups"), ctx);
        if (groupsObj instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) groupsObj).length(); i++) {
                JSONObject g = ((JSONArray) groupsObj).optJSONObject(i);
                if (g != null) {
                    String p = g.optString("property", g.optString("sourceProperty", ""));
                    if (!p.isBlank()) {
                        names.add(p.trim());
                    }
                }
            }
        }
        List<String> capped = new ArrayList<>();
        for (String n : names) {
            if (capped.size() >= PROPERTY_UNION_CAP) {
                break;
            }
            capped.add(n);
        }
        ctx.putVar("propertyUnion.names", capped);
        return new JSONObject()
                .put("status", "ok")
                .put("output", new JSONObject().put("names", new JSONArray(capped)));
    }

    private static JSONObject trendTargets(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        JSONObject primaryNode = ctx.nodeOutput(args.optString("primaryNodeId", "primary_property"));
        boolean numeric = false;
        if (primaryNode != null) {
            JSONObject output = primaryNode.optJSONObject("output");
            if (output != null) {
                numeric = output.optBoolean("numericNameHeuristicMatched", output.optBoolean("isNumeric", false));
            }
        }
        JSONArray targets = new JSONArray();
        if (numeric) {
            String propertyName = "";
            if (primaryNode != null) {
                Object p = primaryNode.optJSONObject("output").opt("primaryProperty");
                if (p != null && p != JSONObject.NULL) {
                    propertyName = String.valueOf(p);
                }
            }
            String duration = ctx.params().optString("timeWindow", "24h");
            if (duration.isBlank()) {
                duration = "24h";
            }
            Object assetsObj = PlaybookExpressionResolver.resolve(args.opt("assets"), ctx);
            JSONArray assets = toJsonArray(assetsObj);
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                if (!name.isEmpty() && !propertyName.isEmpty()) {
                    targets.put(new JSONObject()
                            .put("thingName", name)
                            .put("propertyName", propertyName)
                            .put("relativeDuration", duration)
                            .put("slot", asset.optString("slot", "")));
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject().put("targets", targets));
        return out;
    }

    private static JSONObject trendSummary(JSONObject args, PlaybookRunContext ctx) {
        String fanOutId = args != null ? args.optString("trendsFanOutNodeId", "trends_by_asset") : "trends_by_asset";
        JSONObject fanOut = ctx.nodeOutput(fanOutId);
        JSONArray perAsset = new JSONArray();
        JSONArray gaps = new JSONArray();
        if (fanOut != null) {
            JSONArray children = fanOut.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    if (child == null) {
                        continue;
                    }
                    JSONObject item = child.optJSONObject("item");
                    String thingName = item != null ? item.optString("thingName", item.optString("name", "")) : "";
                    String propertyName = item != null ? item.optString("propertyName", "") : "";
                    String slot = item != null ? item.optString("slot", "") : "";
                    JSONObject entry = new JSONObject();
                    entry.put("thingName", thingName);
                    entry.put("propertyName", propertyName);
                    entry.put("slot", slot);
                    if (!"ok".equals(child.optString("status", ""))) {
                        entry.put("status", "failed");
                        gaps.put("Trend query failed for " + thingName);
                        perAsset.put(entry);
                        continue;
                    }
                    JSONObject toolOut = child.optJSONObject("toolOutput");
                    int points = toolOut != null
                            ? toolOut.optInt("pointsReturned", toolOut.optInt("returnedRows", -1))
                            : -1;
                    int rows = toolOut != null
                            ? toolOut.optInt("totalRows", toolOut.optInt("rowCount", toolOut.optInt("totalCount", -1)))
                            : -1;
                    entry.put("status", "ok");
                    entry.put("pointsReturned", points >= 0 ? points : JSONObject.NULL);
                    entry.put("rowCount", rows >= 0 ? rows : JSONObject.NULL);
                    PlaybookNodeEvidence.enrichTrendEntry(entry, toolOut);
                    if (points == 0) {
                        gaps.put("No numeric samples returned for " + thingName + " / " + propertyName);
                    }
                    perAsset.put(entry);
                }
            }
        }
        JSONObject output = new JSONObject().put("assets", perAsset).put("gaps", gaps);
        JSONObject result = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attach(result, PlaybookNodeEvidence.trendSummaryLines(perAsset, gaps));
        return result;
    }

    private static JSONObject summarizeAssetPairHealth(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        Object assetsObj = PlaybookExpressionResolver.resolve(args.opt("assets"), ctx);
        JSONArray assets = toJsonArray(assetsObj);
        JSONObject groupsWrap = ctx.nodeOutput(args.optString("alertGroupsNodeId", "alert_groups"));
        JSONArray globalGroups = groupsWrap != null && groupsWrap.optJSONObject("output") != null
                ? groupsWrap.getJSONObject("output").optJSONArray("groups") : new JSONArray();
        JSONObject primaryWrap = ctx.nodeOutput(args.optString("primaryNodeId", "primary_property"));
        String primaryProperty = "";
        if (primaryWrap != null) {
            Object p = primaryWrap.optJSONObject("output").opt("primaryProperty");
            if (p != null && p != JSONObject.NULL) {
                primaryProperty = String.valueOf(p);
            }
        }
        Map<String, JSONObject> valuesByThing = indexValuesFanOut(ctx, args.optString("valuesFanOutNodeId", "values_by_asset"));
        JSONArray assetSummaries = new JSONArray();
        int maxAlerts = -1;
        String lessHealthy = null;
        int tied = 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            int alertTotal = countAlertsForThing(ctx, args.optString("alertsFanOutNodeId", "alerts_by_asset"), name);
            JSONObject summary = new JSONObject();
            summary.put("slot", asset.optString("slot", ""));
            summary.put("name", name);
            summary.put("alertRowCount", alertTotal);
            summary.put("alertGroups", globalGroups);
            summary.put("topProperties", topPropertiesForThing(valuesByThing.get(name)));
            assetSummaries.put(summary);
            if (alertTotal > maxAlerts) {
                maxAlerts = alertTotal;
                lessHealthy = asset.optString("slot", name);
                tied = 1;
            } else if (alertTotal == maxAlerts && maxAlerts >= 0) {
                tied++;
            }
        }
        JSONObject comparison = new JSONObject();
        JSONArray reasons = new JSONArray();
        if (tied == 1 && lessHealthy != null) {
            comparison.put("lessHealthyAsset", lessHealthy);
            reasons.put("Higher current alert-row count on asset " + lessHealthy);
        } else {
            comparison.put("lessHealthyAsset", JSONObject.NULL);
            if (tied > 1) {
                reasons.put("Assets tied on alert-row count.");
            }
        }
        comparison.put("reasons", reasons);
        JSONObject body = new JSONObject()
                .put("assets", assetSummaries)
                .put("comparison", comparison)
                .put("primaryProperty", primaryProperty.isEmpty() ? JSONObject.NULL : primaryProperty);
        if (body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > SUMMARY_MAX_BYTES) {
            throw new PlaybookRunException(
                    "Asset pair summary exceeded 8KB cap; narrow scope and rerun.",
                    "evidence_too_large");
        }
        JSONObject result = new JSONObject().put("status", "ok").put("output", body);
        PlaybookNodeEvidence.attach(result, PlaybookNodeEvidence.pairSummaryLines(body));
        return result;
    }

    private static boolean rowMatchesIdentifier(JSONObject row, String normalizedUser, String rawUser) {
        String name = thingName(row);
        if (!name.isEmpty()) {
            if (name.equalsIgnoreCase(rawUser) || normalizeIdentifier(name).equals(normalizedUser)) {
                return true;
            }
            if (normalizeIdentifier(name).endsWith(normalizedUser) || normalizedUser.endsWith(normalizeIdentifier(name))) {
                return true;
            }
        }
        for (String key : row.keySet()) {
            if ("name".equalsIgnoreCase(key) || "Name".equals(key)) {
                continue;
            }
            Object val = row.opt(key);
            if (val == null || val == JSONObject.NULL) {
                continue;
            }
            String s = String.valueOf(val).trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.equalsIgnoreCase(rawUser) || normalizeIdentifier(s).equals(normalizedUser)) {
                return true;
            }
            if (normalizeIdentifier(s).endsWith(normalizedUser)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeIdentifier(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_.]+", "");
    }

    private static String thingName(JSONObject row) {
        return row.optString("name", row.optString("Name", ""));
    }

    private static JSONObject toolOutputFromNode(PlaybookRunContext ctx, String nodeId) {
        JSONObject nodeOut = ctx.nodeOutput(nodeId);
        return nodeOut != null ? nodeOut.optJSONObject("toolOutput") : null;
    }

    private static String stringArg(JSONObject args, String key, PlaybookRunContext ctx) throws PlaybookRunException {
        Object v = PlaybookExpressionResolver.resolve(args != null ? args.opt(key) : null, ctx);
        return v != null ? String.valueOf(v).trim() : "";
    }

    private static JSONArray toJsonArray(Object o) {
        if (o instanceof JSONArray) {
            return (JSONArray) o;
        }
        if (o instanceof JSONObject) {
            JSONObject wrap = (JSONObject) o;
            if (wrap.has("assets")) {
                return wrap.optJSONArray("assets");
            }
            if (wrap.has("targets")) {
                return wrap.optJSONArray("targets");
            }
        }
        if (o instanceof List) {
            return new JSONArray((List<?>) o);
        }
        return new JSONArray();
    }

    private static void accumulateGroups(JSONArray groups, Map<String, Integer> counts) {
        if (groups == null) {
            return;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) {
                continue;
            }
            String prop = g.optString("property", g.optString("sourceProperty", "unknown"));
            int c = g.optInt("alertCount", 0);
            counts.merge(prop, c, Integer::sum);
        }
    }

    private static JSONArray cappedGroups(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JSONArray arr = new JSONArray();
        int other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            if (arr.length() < ALERT_GROUPS_CAP) {
                arr.put(new JSONObject().put("property", e.getKey()).put("alertCount", e.getValue()));
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            arr.put(new JSONObject().put("property", "_other").put("alertCount", other));
        }
        return arr;
    }

    private static boolean isLikelyNumericProperty(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("temperature") || n.contains("speed") || n.contains("voltage")
                || n.contains("pressure") || n.contains("percent") || n.contains("count")
                || n.startsWith("is") && n.contains("stopped");
    }

    private static Map<String, JSONObject> indexValuesFanOut(PlaybookRunContext ctx, String fanOutId) {
        Map<String, JSONObject> byThing = new LinkedHashMap<>();
        JSONObject fanOut = ctx.nodeOutput(fanOutId);
        if (fanOut == null) {
            return byThing;
        }
        JSONArray children = fanOut.optJSONArray("children");
        if (children == null) {
            return byThing;
        }
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null || !"ok".equals(child.optString("status", ""))) {
                continue;
            }
            JSONObject item = child.optJSONObject("item");
            String name = item != null ? item.optString("name", "") : "";
            if (!name.isEmpty()) {
                byThing.put(name, child.optJSONObject("toolOutput"));
            }
        }
        return byThing;
    }

    private static JSONArray topPropertiesForThing(JSONObject vals) {
        JSONArray top = new JSONArray();
        if (vals == null) {
            return top;
        }
        JSONArray props = vals.optJSONArray("properties");
        if (props == null) {
            return top;
        }
        for (int i = 0; i < props.length() && top.length() < TOP_PROPERTIES_CAP; i++) {
            JSONObject p = props.optJSONObject(i);
            if (p != null) {
                top.put(new JSONObject()
                        .put("name", p.optString("name", ""))
                        .put("value", p.has("value") ? p.get("value") : JSONObject.NULL));
            }
        }
        return top;
    }

    private static int countAlertsForThing(PlaybookRunContext ctx, String alertNodeId, String thingName) {
        JSONObject nodeOut = ctx.nodeOutput(alertNodeId);
        if (nodeOut == null) {
            return 0;
        }
        JSONArray children = nodeOut.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child == null) {
                    continue;
                }
                JSONObject item = child.optJSONObject("item");
                if (item != null && thingName.equals(item.optString("name", ""))) {
                    JSONObject toolOut = child.optJSONObject("toolOutput");
                    return alertRowCountFromToolOutput(toolOut, thingName);
                }
            }
            return 0;
        }
        JSONObject toolOut = nodeOut.optJSONObject("toolOutput");
        return alertRowCountFromToolOutput(toolOut, thingName);
    }

    private static int alertRowCountFromToolOutput(JSONObject toolOut, String thingName) {
        if (toolOut == null) {
            return 0;
        }
        if (ToolResultEgressGateway.RESULT_KIND_ALERT_SUMMARY_MULTI.equals(toolOut.optString("resultKind", ""))) {
            JSONArray byThing = toolOut.optJSONArray("byThing");
            if (byThing != null) {
                for (int i = 0; i < byThing.length(); i++) {
                    JSONObject entry = byThing.optJSONObject(i);
                    if (entry != null && thingName.equals(entry.optString("thingName"))
                            && "success".equals(entry.optString("status"))) {
                        return entry.optInt("totalAlerts",
                                entry.optJSONArray("topAlerts") != null ? entry.optJSONArray("topAlerts").length() : 0);
                    }
                }
            }
            return 0;
        }
        if (thingName.equals(toolOut.optString("thingName", ""))) {
            return PlaybookAlertSummaryRows.fromToolOutput(toolOut).rows().length();
        }
        return 0;
    }

    private static void addCriticalPropertyNames(Set<String> names, Object critObj) {
        if (critObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) critObj;
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "").trim();
                if (!p.isEmpty()) {
                    names.add(p);
                }
            }
            return;
        }
        if (critObj != null) {
            for (String part : String.valueOf(critObj).split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    names.add(t);
                }
            }
        }
    }

    private static JSONObject needsClarification(String message) {
        return needsClarification(message, null);
    }

    private static JSONObject needsClarification(String message, JSONArray candidates) {
        JSONObject o = new JSONObject()
                .put("status", "needs_clarification")
                .put("message", message);
        if (candidates != null && candidates.length() > 0) {
            o.put("candidates", candidates);
        }
        return o;
    }
}
