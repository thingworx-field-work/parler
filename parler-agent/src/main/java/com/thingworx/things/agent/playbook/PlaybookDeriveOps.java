package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

/** Built-in V1a derive transforms. */
public final class PlaybookDeriveOps {

    private static final int PROPERTY_UNION_CAP = 24;
    private static final int SUMMARY_MAX_BYTES = 8192;
    private static final int REGION_ALERT_GROUPS_CAP = 10;
    /** Bounded per-alert attribution projection (region, thingName, alertName, sourceProperty). */
    private static final int ALERT_ATTRIBUTION_ROWS_CAP = 40;
    private static final int REGION_TOP_PROPERTIES_CAP = 5;

    private PlaybookDeriveOps() {}

    public static JSONObject execute(String op, JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        if (PlaybookGenericDeriveOps.supports(op)) {
            return PlaybookGenericDeriveOps.execute(op, args, ctx);
        }
        if ("pick_taxonomy_row".equals(op)) {
            return pickTaxonomyRow(args, ctx);
        }
        if ("extract_field".equals(op)) {
            return extractField(args, ctx);
        }
        if ("flatten_region_entities".equals(op)) {
            return flattenRegionEntities(args, ctx);
        }
        if ("collect_thing_names_from_assets".equals(op)) {
            return collectThingNamesFromAssets(args, ctx);
        }
        if ("build_property_union".equals(op)) {
            return buildPropertyUnion(args, ctx);
        }
        if ("group_alerts_by_source_property".equals(op)) {
            return groupAlertsBySourceProperty(args, ctx);
        }
        if ("summarize_current_values_by_region".equals(op)) {
            return summarizeCurrentValuesByRegion(args, ctx);
        }
        if ("summarize_region_health".equals(op)) {
            return summarizeRegionHealth(args, ctx);
        }
        return PlaybookDeriveOpsV1b.execute(op, args, ctx);
    }

    private static JSONObject pickTaxonomyRow(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Object assetTypeObj = PlaybookExpressionResolver.resolve(
                args != null ? args.opt("assetType") : null, ctx);
        String assetType = assetTypeObj != null ? String.valueOf(assetTypeObj).trim() : "";
        String whenMissing = args != null ? args.optString("whenAssetTypeMissing", "clarify") : "clarify";
        if (assetType.isEmpty() && "empty_taxonomy".equals(whenMissing)) {
            Map<String, Object> taxonomy = emptyTaxonomyContext();
            ctx.putVar("taxonomy", taxonomy);
            JSONObject out = new JSONObject();
            out.put("status", "ok");
            out.put("output", new JSONObject(taxonomy));
            PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(
                    "Optional asset-type hint omitted; proceeding without assetTypeKey narrowing."));
            return out;
        }
        Object rowsObj = PlaybookExpressionResolver.resolve(
                args != null ? args.opt("taxonomyRows") : null, ctx);
        JSONArray rows = toJsonArray(rowsObj);
        String normalized = ModelKeyResolutionNormalize.normalizePhase0(assetType);
        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            if (assetTypeMatches(row, normalized, assetType)) {
                matches.add(row);
            }
        }
        if (matches.isEmpty()) {
            return needsClarification("I could not match asset type \"" + assetType
                    + "\" to a taxonomy row. Which asset type should I use?");
        }
        if (matches.size() > 1) {
            return needsClarification("Asset type \"" + assetType
                    + "\" matches multiple taxonomy rows. Please specify the exact asset type.");
        }
        JSONObject match = matches.get(0);
        String critRaw = match.optString("criticalProperties", "");
        Map<String, Object> taxonomy = new LinkedHashMap<>();
        taxonomy.put("EntityType", match.optString("entityType", ""));
        taxonomy.put("EntityName", match.optString("entityName", ""));
        taxonomy.put("CriticalProperties", critRaw);
        taxonomy.put("CriticalPropertiesList", criticalPropertiesArray(critRaw));
        taxonomy.put("AssetType", match.optString("assetType", ""));
        ctx.putVar("taxonomy", taxonomy);
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject(taxonomy));
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(PlaybookNodeEvidence.taxonomyLine(out.getJSONObject("output"))));
        return out;
    }

    private static boolean assetTypeMatches(JSONObject row, String normalizedUser, String rawUser) {
        String assetType = row.optString("assetType", "");
        if (!assetType.isEmpty()
                && (assetType.equalsIgnoreCase(rawUser) || ModelKeyResolutionNormalize.normalizePhase0(assetType)
                        .equals(normalizedUser))) {
            return true;
        }
        JSONArray syns = row.optJSONArray("synonymsNormalized");
        if (syns != null) {
            for (int i = 0; i < syns.length(); i++) {
                if (normalizedUser.equals(syns.optString(i, ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Taxonomy vars when no user asset-type hint (see {@code pick_taxonomy_row.whenAssetTypeMissing}). */
    private static Map<String, Object> emptyTaxonomyContext() {
        Map<String, Object> taxonomy = new LinkedHashMap<>();
        taxonomy.put("EntityType", "");
        taxonomy.put("EntityName", "");
        taxonomy.put("CriticalProperties", "");
        taxonomy.put("CriticalPropertiesList", criticalPropertiesArray(""));
        taxonomy.put("AssetType", "");
        return taxonomy;
    }

    static JSONObject extractField(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Object rowsObj = PlaybookExpressionResolver.resolve(args.opt("rows"), ctx);
        String field = args.optString("fieldName", "name");
        JSONArray rows = toJsonArray(rowsObj);
        JSONArray outRows = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.has(field)) {
                outRows.put(row.get(field));
            }
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject().put("values", outRows));
        return out;
    }

    private static JSONObject flattenRegionEntities(JSONObject args, PlaybookRunContext ctx) {
        String fanOutId = args.optString("fanOutNodeId", "assets_by_region");
        JSONObject fanOut = ctx.nodeOutput(fanOutId);
        JSONArray assets = new JSONArray();
        JSONArray gaps = new JSONArray();
        if (fanOut != null) {
            JSONArray children = fanOut.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    if (child == null) {
                        continue;
                    }
                    String region = child.optString("region", "");
                    if (!"ok".equals(child.optString("status", ""))) {
                        gaps.put(PlaybookGapObjects.withContext("REGION_QUERY_FAILED",
                                "Region query failed for " + region, new JSONObject().put("region", region)));
                        continue;
                    }
                    JSONObject toolOut = child.optJSONObject("toolOutput");
                    PlaybookTaxonomyEntityRows.Extracted extracted =
                            PlaybookTaxonomyEntityRows.fromToolOutput(toolOut);
                    if (extracted.partial() && extracted.gapNote() != null) {
                        gaps.put(PlaybookGapObjects.withContext("TAXONOMY_PARTIAL",
                                extracted.gapNote(), new JSONObject().put("region", region)));
                    }
                    JSONArray entityRows = extracted.rows();
                    for (int r = 0; r < entityRows.length(); r++) {
                        JSONObject row = entityRows.optJSONObject(r);
                        if (row == null) {
                            continue;
                        }
                        JSONObject asset = new JSONObject();
                        asset.put("region", region);
                        asset.put("name", row.optString("name", row.optString("Name", "")));
                        asset.put("row", row);
                        assets.put(asset);
                    }
                }
            }
        }
        JSONObject output = new JSONObject();
        output.put("assets", assets);
        output.put("gaps", gaps);
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.regionEntitiesLines(assets, gaps));
        return out;
    }

    private static final int MAX_ALERT_THING_NAMES_PER_CALL = 25;

    private static JSONObject collectThingNamesFromAssets(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String assetsRef = args.optString("assetsRef", "");
        if (assetsRef.isEmpty()) {
            throw new PlaybookRunException("collect_thing_names_from_assets requires assetsRef", "bad_args");
        }
        int maxNames = Math.max(1, Math.min(args.optInt("maxNames", MAX_ALERT_THING_NAMES_PER_CALL),
                MAX_ALERT_THING_NAMES_PER_CALL));
        Object assetsObj = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", assetsRef), ctx);
        JSONArray assets = toJsonArray(assetsObj);
        JSONArray names = new JSONArray();
        int totalAssets = 0;
        boolean capped = false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            totalAssets++;
            if (names.length() >= maxNames) {
                capped = true;
                continue;
            }
            names.put(name);
        }
        JSONObject output = new JSONObject();
        output.put("names", names);
        output.put("totalAssets", totalAssets);
        output.put("returnedNames", names.length());
        if (capped) {
            output.put("capped", true);
            output.put("gapNote",
                    "Alert comparison limited to " + maxNames + " Things; " + totalAssets + " assets in scope.");
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.singleLine(
                "Collected " + names.length() + " Thing name(s) for query_alert_summary (scope " + totalAssets + ")."));
        return out;
    }

    private static void groupAlertsFromMultiSummary(JSONObject toolOut, Map<String, String> thingToRegion,
            Map<String, Integer> globalCounts, Map<String, Map<String, Integer>> countsByRegion,
            Map<String, Set<String>> regionGapNotes, JSONArray alertAttribution, int[] attributionOmitted) {
        PlaybookAlertSummaryRows.Extracted extracted = PlaybookAlertSummaryRows.fromToolOutput(toolOut);
        if (extracted.partial() && extracted.gapNote() != null) {
            regionGapNotes.computeIfAbsent("unknown", k -> new LinkedHashSet<>()).add(extracted.gapNote());
        }
        JSONArray byThing = toolOut.optJSONArray("byThing");
        if (byThing == null) {
            return;
        }
        for (int i = 0; i < byThing.length(); i++) {
            JSONObject entry = byThing.optJSONObject(i);
            if (entry == null || !"success".equals(entry.optString("status"))) {
                continue;
            }
            String thingName = entry.optString("thingName", "");
            String region = thingToRegion.getOrDefault(thingName, "unknown");
            Map<String, Integer> regionCounts = countsByRegion.computeIfAbsent(region, k -> new LinkedHashMap<>());
            JSONArray top = entry.optJSONArray("topAlerts");
            int totalAlerts = entry.optInt("totalAlerts", 0);
            if (top != null && top.length() > 0) {
                accumulateAlertRows(top, regionCounts, globalCounts);
                for (int j = 0; j < top.length(); j++) {
                    JSONObject alert = top.optJSONObject(j);
                    if (alert == null) {
                        continue;
                    }
                    if (alertAttribution.length() >= ALERT_ATTRIBUTION_ROWS_CAP) {
                        attributionOmitted[0]++;
                        continue;
                    }
                    JSONObject attributed = new JSONObject()
                            .put("region", region)
                            .put("thingName", thingName)
                            .put("alertName", alert.optString("alertName", "unknown"));
                    String sourceProperty = alert.optString("sourceProperty", "");
                    if (!sourceProperty.isEmpty()) {
                        attributed.put("sourceProperty", sourceProperty);
                    }
                    alertAttribution.put(attributed);
                }
                if (totalAlerts > top.length()) {
                    // Claim a drill-in handle only when the rollup actually retained one for this Thing.
                    String remainder = entry.has("cacheId")
                            ? " total alerts (remainder via per-Thing cacheId)."
                            : " total alerts (remainder not retained in evidence).";
                    String note = "Source-property groups for " + thingName + " use the top "
                            + top.length() + " alert sample; " + totalAlerts + remainder;
                    regionGapNotes.computeIfAbsent(region, k -> new LinkedHashSet<>()).add(note);
                }
            } else if (entry.has("totalAlerts")) {
                int total = entry.optInt("totalAlerts", 0);
                if (total > 0) {
                    regionCounts.merge("unknown", total, Integer::sum);
                    globalCounts.merge("unknown", total, Integer::sum);
                }
            }
        }
    }

    private static JSONObject buildPropertyUnion(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Object critObj = PlaybookExpressionResolver.resolve(args.opt("criticalProperties"), ctx);
        Set<String> names = new LinkedHashSet<>();
        Object groupsObj = null;
        String groupsRef = args.optString("alertGroupsRef", "");
        if (!groupsRef.isEmpty()) {
            groupsObj = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", groupsRef), ctx);
        } else {
            groupsObj = PlaybookExpressionResolver.resolve(args.opt("alertGroups"), ctx);
        }
        if (groupsObj instanceof JSONObject) {
            JSONObject wrap = (JSONObject) groupsObj;
            if (wrap.has("groups")) {
                groupsObj = wrap.get("groups");
            }
        }
        if (groupsObj instanceof JSONArray) {
            JSONArray groups = (JSONArray) groupsObj;
            for (int i = 0; i < groups.length(); i++) {
                JSONObject grp = groups.optJSONObject(i);
                if (grp != null) {
                    String prop = grp.optString("property", grp.optString("sourceProperty", ""));
                    if (!prop.isBlank()) {
                        names.add(prop.trim());
                    }
                }
            }
        }
        addCriticalPropertyNames(names, critObj);
        List<String> capped = new ArrayList<>();
        for (String n : names) {
            if (capped.size() >= PROPERTY_UNION_CAP) {
                break;
            }
            capped.add(n);
        }
        ctx.putVar("propertyUnion.names", capped);
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", new JSONObject().put("names", new JSONArray(capped)));
        return out;
    }

    private static JSONObject groupAlertsBySourceProperty(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String alertSummaryNodeId = args.optString("alertSummaryNodeId", "").trim();
        String fanOutId = args.optString("fanOutNodeId", "alerts_by_asset");
        Map<String, String> thingToRegion = indexThingToRegion(args, ctx);
        Map<String, Integer> globalCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> countsByRegion = new LinkedHashMap<>();
        Map<String, Set<String>> regionGapNotes = new LinkedHashMap<>();
        JSONArray alertAttribution = new JSONArray();
        int[] attributionOmitted = {0};
        if (!alertSummaryNodeId.isEmpty()) {
            JSONObject summaryNode = ctx.nodeOutput(alertSummaryNodeId);
            JSONObject toolOut = summaryNode != null ? summaryNode.optJSONObject("toolOutput") : null;
            if (toolOut != null) {
                groupAlertsFromMultiSummary(toolOut, thingToRegion, globalCounts, countsByRegion, regionGapNotes,
                        alertAttribution, attributionOmitted);
            }
        } else {
            JSONObject fanOut = ctx.nodeOutput(fanOutId);
            if (fanOut != null) {
                JSONArray children = fanOut.optJSONArray("children");
                if (children != null) {
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null || !"ok".equals(child.optString("status", ""))) {
                            continue;
                        }
                        String region = resolveRegion(child, thingToRegion);
                        if (region.isEmpty()) {
                            region = "unknown";
                        }
                        JSONObject toolOut = child.optJSONObject("toolOutput");
                        PlaybookAlertSummaryRows.Extracted extracted =
                                PlaybookAlertSummaryRows.fromToolOutput(toolOut);
                        if (extracted.partial() && extracted.gapNote() != null) {
                            regionGapNotes.computeIfAbsent(region, k -> new LinkedHashSet<>())
                                    .add(extracted.gapNote());
                        }
                        Map<String, Integer> regionCounts = countsByRegion.computeIfAbsent(region,
                                k -> new LinkedHashMap<>());
                        accumulateAlertRows(extracted.rows(), regionCounts, globalCounts);
                    }
                }
            }
        }
        JSONObject groupsByRegion = new JSONObject();
        for (Map.Entry<String, Map<String, Integer>> e : countsByRegion.entrySet()) {
            groupsByRegion.put(e.getKey(), cappedAlertGroupsArray(e.getValue()));
        }
        JSONObject output = new JSONObject();
        output.put("groups", cappedAlertGroupsArray(globalCounts));
        output.put("groupsByRegion", groupsByRegion);
        if (alertAttribution.length() > 0) {
            output.put("alertAttribution", alertAttribution);
        }
        if (attributionOmitted[0] > 0) {
            output.put("alertAttributionOmitted", attributionOmitted[0]);
        }
        if (!regionGapNotes.isEmpty()) {
            output.put("regionAlertGaps", gapNotesByRegion(regionGapNotes));
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", output);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.alertGroupsLines(output));
        return out;
    }

    private static Map<String, String> indexThingToRegion(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        Map<String, String> thingToRegion = new LinkedHashMap<>();
        String assetsRef = args.optString("assetsRef", "");
        if (assetsRef.isEmpty()) {
            return thingToRegion;
        }
        Object assetsObj = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", assetsRef), ctx);
        JSONArray assets = toJsonArray(assetsObj);
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            String region = asset.optString("region", "");
            if (region.isEmpty()) {
                region = asset.optString("slot", "");
            }
            if (!name.isEmpty() && !region.isEmpty()) {
                thingToRegion.put(name, region);
            }
        }
        return thingToRegion;
    }

    private static String resolveRegion(JSONObject child, Map<String, String> thingToRegion) {
        String region = child.optString("region", "");
        if (!region.isEmpty()) {
            return region;
        }
        JSONObject item = child.optJSONObject("item");
        if (item != null) {
            region = item.optString("region", "");
            if (!region.isEmpty()) {
                return region;
            }
            String name = item.optString("name", "");
            return thingToRegion.getOrDefault(name, "");
        }
        return "";
    }

    private static void accumulateAlertRows(JSONArray rows, Map<String, Integer> regionCounts,
            Map<String, Integer> globalCounts) {
        for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.optJSONObject(r);
            if (row == null) {
                continue;
            }
            String prop = row.optString("sourceProperty", "");
            if (prop.isBlank()) {
                prop = "unknown";
            }
            regionCounts.merge(prop, 1, Integer::sum);
            globalCounts.merge(prop, 1, Integer::sum);
        }
    }

    private static JSONArray cappedAlertGroupsArray(Map<String, Integer> counts) {
        JSONArray groups = new JSONArray();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            if (i < REGION_ALERT_GROUPS_CAP - 1) {
                groups.put(new JSONObject()
                        .put("property", e.getKey())
                        .put("alertCount", e.getValue()));
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            groups.put(new JSONObject().put("property", "_other").put("alertCount", other));
        }
        return groups;
    }

    private static JSONObject gapNotesByRegion(Map<String, Set<String>> regionGapNotes) {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Set<String>> e : regionGapNotes.entrySet()) {
            out.put(e.getKey(), new JSONArray(e.getValue()));
        }
        return out;
    }

    /**
     * Compact per-region rollup of {@code get_property_values} fan-out so {@code llm_summary} can cite live values
     * separately from alert-derived evidence (cross_region_health playbook).
     */
    private static JSONObject summarizeCurrentValuesByRegion(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String assetsRef = args.optString("assetsRef", "");
        if (assetsRef.isEmpty()) {
            throw new PlaybookRunException("summarize_current_values_by_region requires assetsRef", "bad_args");
        }
        Object assetsObj = PlaybookExpressionResolver.resolve(new JSONObject().put("$ref", assetsRef), ctx);
        JSONArray assets = toJsonArray(assetsObj);

        String fanOutId = args.optString("valuesFanOutNodeId", "values_by_asset");
        int maxProps = Math.max(1, Math.min(args.optInt("maxPropertiesPerRegion", 24), PROPERTY_UNION_CAP));
        int maxExamples = Math.max(1, Math.min(args.optInt("maxExamplesPerProperty", 3), 12));

        Object namesObj = PlaybookExpressionResolver.resolve(args.opt("propertyNames"), ctx);
        List<String> propertyNamesAll = coercePropertyNames(namesObj, PROPERTY_UNION_CAP);
        List<String> propertyOrder = filterExcludedPropertyNames(propertyNamesAll, args.optJSONArray("excludePropertyNames"));
        if (propertyOrder.size() > maxProps) {
            propertyOrder = new ArrayList<>(propertyOrder.subList(0, maxProps));
        }

        Map<String, JSONObject> valuesByThing = indexValuesFanOut(ctx, fanOutId);
        Map<String, List<String>> regionToThings = new LinkedHashMap<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String reg = asset.optString("region", "");
            String name = asset.optString("name", "");
            if (reg.isEmpty() || name.isEmpty()) {
                continue;
            }
            regionToThings.computeIfAbsent(reg, k -> new ArrayList<>()).add(name);
        }

        JSONArray regionsOut = new JSONArray();
        for (Map.Entry<String, List<String>> e : regionToThings.entrySet()) {
            String region = e.getKey();
            List<String> things = e.getValue();
            JSONArray propsOut = new JSONArray();
            for (String prop : propertyOrder) {
                JSONArray examples = new JSONArray();
                int successfulReads = 0;
                int failedReads = 0;
                List<Double> nums = new ArrayList<>();
                for (String thing : things) {
                    JSONObject toolOut = valuesByThing.get(thing);
                    if (toolOut == null || !"success".equals(toolOut.optString("status", ""))) {
                        continue;
                    }
                    JSONObject cell = findPropertyCell(toolOut.optJSONArray("properties"), prop);
                    if (cell == null) {
                        continue;
                    }
                    if (!cell.optBoolean("ok", false)) {
                        failedReads++;
                        continue;
                    }
                    successfulReads++;
                    Object valObj = cell.isNull("value") ? null : cell.opt("value");
                    double d = toNumericOrNaN(valObj);
                    if (!Double.isNaN(d)) {
                        nums.add(d);
                    }
                    if (examples.length() < maxExamples) {
                        examples.put(new JSONObject().put("thingName", thing).put("value",
                                valObj == null ? JSONObject.NULL : valObj));
                    }
                }
                if (successfulReads == 0 && failedReads == 0 && examples.length() == 0) {
                    continue;
                }
                JSONObject pstat = new JSONObject()
                        .put("name", prop)
                        .put("successfulReads", successfulReads)
                        .put("failedReads", failedReads);
                pstat.put("examples", examples);
                if (!nums.isEmpty()) {
                    if (nums.size() == 1) {
                        pstat.put("numericMean", nums.get(0));
                    } else {
                        pstat.put("numericMin", Collections.min(nums));
                        pstat.put("numericMax", Collections.max(nums));
                        double sum = 0.0;
                        for (double v : nums) {
                            sum += v;
                        }
                        pstat.put("numericMean", sum / nums.size());
                    }
                }
                propsOut.put(pstat);
            }
            regionsOut.put(new JSONObject().put("region", region).put("properties", propsOut));
        }

        JSONObject output = new JSONObject().put("regions", regionsOut);
        JSONObject out = new JSONObject().put("status", "ok").put("output", output);
        int evidenceExampleCap = Math.min(2, maxExamples);
        PlaybookNodeEvidence.attachLinesOnly(out, currentValueStatsEvidenceLines(output, evidenceExampleCap));
        String json = out.toString();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > SUMMARY_MAX_BYTES) {
            throw new PlaybookRunException(
                    "Current-value stats exceeded 8KB cap; reduce maxPropertiesPerRegion or scope.",
                    "evidence_too_large");
        }
        return out;
    }

    private static JSONObject findPropertyCell(JSONArray props, String name) {
        if (props == null || name == null || name.isEmpty()) {
            return null;
        }
        for (int i = 0; i < props.length(); i++) {
            JSONObject o = props.optJSONObject(i);
            if (o != null && name.equals(o.optString("name", ""))) {
                return o;
            }
        }
        return null;
    }

    private static double toNumericOrNaN(Object valObj) {
        if (valObj == null) {
            return Double.NaN;
        }
        if (valObj instanceof Number) {
            return ((Number) valObj).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(valObj));
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static JSONArray currentValueStatsEvidenceLines(JSONObject output, int maxInlineExamples) {
        JSONArray lines = new JSONArray();
        JSONArray regions = output.optJSONArray("regions");
        if (regions == null) {
            lines.put("current values: (no regions)");
            return lines;
        }
        int cap = 36;
        int exCap = Math.max(0, Math.min(maxInlineExamples, 3));
        for (int r = 0; r < regions.length() && lines.length() < cap; r++) {
            JSONObject reg = regions.optJSONObject(r);
            if (reg == null) {
                continue;
            }
            String rn = reg.optString("region", "");
            JSONArray props = reg.optJSONArray("properties");
            if (props == null) {
                continue;
            }
            for (int p = 0; p < props.length() && lines.length() < cap; p++) {
                JSONObject pr = props.optJSONObject(p);
                if (pr == null) {
                    continue;
                }
                String pname = pr.optString("name", "");
                StringBuilder sb = new StringBuilder();
                sb.append(rn).append(' ').append(pname).append(": ok=").append(pr.optInt("successfulReads", 0));
                if (pr.optInt("failedReads", 0) > 0) {
                    sb.append(" fail=").append(pr.optInt("failedReads", 0));
                }
                if (pr.has("numericMean") && !pr.isNull("numericMean")) {
                    sb.append(" mean=").append(pr.get("numericMean"));
                }
                JSONArray ex = pr.optJSONArray("examples");
                if (ex != null && ex.length() > 0) {
                    sb.append(" ex=");
                    for (int k = 0; k < ex.length() && k < exCap; k++) {
                        JSONObject ev = ex.optJSONObject(k);
                        if (ev == null) {
                            continue;
                        }
                        if (k > 0) {
                            sb.append(',');
                        }
                        sb.append(ev.optString("thingName", "?")).append('=').append(ev.opt("value"));
                    }
                }
                lines.put(sb.toString());
            }
        }
        return lines;
    }

    private static JSONObject summarizeRegionHealth(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        JSONObject assetsWrap = ctx.nodeOutput("region_entities");
        JSONArray assets = assetsWrap != null ? assetsWrap.optJSONObject("output").optJSONArray("assets")
                : new JSONArray();
        JSONObject groupsWrap = ctx.nodeOutput("alert_groups");
        JSONObject groupsOutput = groupsWrap != null ? groupsWrap.optJSONObject("output") : null;
        JSONObject groupsByRegion = groupsOutput != null ? groupsOutput.optJSONObject("groupsByRegion")
                : new JSONObject();
        JSONObject regionAlertGaps = groupsOutput != null ? groupsOutput.optJSONObject("regionAlertGaps") : null;
        Map<String, List<JSONObject>> assetsByRegion = new LinkedHashMap<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String region = asset.optString("region", "");
            assetsByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(asset);
        }
        Map<String, JSONObject> valuesByThing = indexValuesFanOut(ctx, args.optString("valuesFanOutNodeId", "values_by_asset"));
        Map<String, JSONObject> regionMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<JSONObject>> entry : assetsByRegion.entrySet()) {
            String region = entry.getKey();
            List<JSONObject> regionAssets = entry.getValue();
            JSONArray alertGroups = groupsByRegion.optJSONArray(region);
            if (alertGroups == null) {
                alertGroups = new JSONArray();
            }
            JSONArray topProperties = new JSONArray();
            for (JSONObject asset : regionAssets) {
                if (topProperties.length() >= REGION_TOP_PROPERTIES_CAP) {
                    break;
                }
                String name = asset.optString("name", "");
                JSONObject vals = valuesByThing.get(name);
                appendTopPropertiesRoundRobin(topProperties, vals, REGION_TOP_PROPERTIES_CAP);
            }
            JSONArray gaps = new JSONArray();
            if (regionAlertGaps != null && regionAlertGaps.has(region)) {
                JSONArray notes = regionAlertGaps.optJSONArray(region);
                if (notes != null) {
                    for (int g = 0; g < notes.length() && gaps.length() < 3; g++) {
                        gaps.put(notes.optString(g, ""));
                    }
                }
            }
            JSONObject regionObj = new JSONObject();
            regionObj.put("name", region);
            regionObj.put("assetCount", regionAssets.size());
            regionObj.put("alertGroups", alertGroups);
            regionObj.put("topProperties", topProperties);
            regionObj.put("gaps", gaps);
            regionMap.put(region, regionObj);
        }
        JSONArray regionsArr = new JSONArray();
        for (JSONObject r : regionMap.values()) {
            regionsArr.put(r);
        }
        JSONObject comparison = buildComparison(regionMap);
        JSONObject summary = new JSONObject()
                .put("regions", regionsArr)
                .put("comparison", comparison);
        if (groupsOutput != null && groupsOutput.has("groups")) {
            summary.put("globalAlertGroups", groupsOutput.optJSONArray("groups"));
        }
        String json = summary.toString();
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > SUMMARY_MAX_BYTES) {
            throw new PlaybookRunException(
                    "Region summary exceeded 8KB cap; reduce regions or rerun with narrower scope.",
                    "evidence_too_large");
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("output", summary);
        PlaybookNodeEvidence.attach(out, PlaybookNodeEvidence.regionSummaryLines(summary));
        return out;
    }

    private static JSONObject buildComparison(Map<String, JSONObject> regionMap) {
        String higher = null;
        int maxAlertRows = -1;
        int regionsAtMax = 0;
        List<String> reasonParts = new ArrayList<>();
        for (JSONObject region : regionMap.values()) {
            int total = sumAlertGroupCounts(region.optJSONArray("alertGroups"));
            String name = region.optString("name", "");
            reasonParts.add(name + ": " + total + " alert rows by source property");
            if (total > maxAlertRows) {
                maxAlertRows = total;
                higher = name;
                regionsAtMax = 1;
            } else if (total == maxAlertRows && maxAlertRows > 0) {
                regionsAtMax++;
            }
        }
        JSONObject comparison = new JSONObject();
        JSONArray reasons = new JSONArray();
        if (regionMap.size() >= 2 && maxAlertRows > 0 && higher != null && regionsAtMax == 1) {
            comparison.put("higherAttentionRegion", higher);
            reasons.put(String.join("; ", reasonParts));
        } else {
            comparison.put("higherAttentionRegion", JSONObject.NULL);
            if (regionMap.size() >= 2 && maxAlertRows > 0 && regionsAtMax > 1) {
                reasons.put("Regions tied on alert-row count (" + maxAlertRows + " rows each).");
            }
        }
        comparison.put("reasons", reasons);
        return comparison;
    }

    private static int sumAlertGroupCounts(JSONArray alertGroups) {
        if (alertGroups == null) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < alertGroups.length(); i++) {
            JSONObject g = alertGroups.optJSONObject(i);
            if (g != null) {
                sum += g.optInt("alertCount", 0);
            }
        }
        return sum;
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
            if (child == null) {
                continue;
            }
            String name = child.optJSONObject("item") != null
                    ? child.getJSONObject("item").optString("name", "") : "";
            if ("ok".equals(child.optString("status", ""))) {
                byThing.put(name, child.optJSONObject("toolOutput"));
            }
        }
        return byThing;
    }

    /** At most one property per asset pass until {@code maxTotal} region entries (canonical cap 5). */
    private static void appendTopPropertiesRoundRobin(JSONArray top, JSONObject vals, int maxTotal) {
        if (vals == null || top.length() >= maxTotal) {
            return;
        }
        JSONArray props = vals.optJSONArray("properties");
        if (props == null || props.length() == 0) {
            return;
        }
        JSONObject p = props.optJSONObject(0);
        if (p == null) {
            return;
        }
        String propName = p.optString("name", "");
        for (int i = 0; i < top.length(); i++) {
            JSONObject existing = top.optJSONObject(i);
            if (existing != null && propName.equals(existing.optString("name", ""))) {
                return;
            }
        }
        top.put(new JSONObject()
                .put("name", propName)
                .put("value", p.has("value") ? p.get("value") : JSONObject.NULL));
    }

    private static JSONObject needsClarification(String message) {
        return new JSONObject()
                .put("status", "needs_clarification")
                .put("message", message);
    }

    private static JSONArray toJsonArray(Object o) {
        if (o instanceof JSONArray) {
            return (JSONArray) o;
        }
        if (o instanceof List) {
            return new JSONArray((List<?>) o);
        }
        return new JSONArray();
    }

    private static JSONArray criticalPropertiesArray(String semicolonSeparated) {
        JSONArray arr = new JSONArray();
        for (String p : splitSemicolonList(semicolonSeparated)) {
            arr.put(p);
        }
        return arr;
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
            for (String p : splitSemicolonList(String.valueOf(critObj))) {
                names.add(p);
            }
        }
    }

    private static List<String> splitSemicolonList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : s.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> filterExcludedPropertyNames(List<String> names, JSONArray exclude) {
        if (names == null || names.isEmpty()) {
            return new ArrayList<>();
        }
        if (exclude == null || exclude.length() == 0) {
            return new ArrayList<>(names);
        }
        Set<String> banned = new LinkedHashSet<>();
        for (int i = 0; i < exclude.length(); i++) {
            String s = exclude.optString(i, "").trim();
            if (!s.isEmpty()) {
                banned.add(s);
            }
        }
        if (banned.isEmpty()) {
            return new ArrayList<>(names);
        }
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (!banned.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Normalizes {@code propertyNames} from playbook {@code $var} resolution: {@link JSONArray}, Java
     * {@link Collection} (as stored by {@link #buildPropertyUnion}), or semicolon-separated {@link String}.
     */
    static List<String> coercePropertyNames(Object raw, int maxProps) {
        List<String> out = new ArrayList<>();
        if (raw == null || maxProps <= 0) {
            return out;
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length() && out.size() < maxProps; i++) {
                String p = arr.optString(i, "").trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
            return out;
        }
        if (raw instanceof Collection<?>) {
            for (Object o : (Collection<?>) raw) {
                if (out.size() >= maxProps) {
                    break;
                }
                if (o == null) {
                    continue;
                }
                String p = String.valueOf(o).trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
            return out;
        }
        for (String p : splitSemicolonList(String.valueOf(raw))) {
            if (out.size() >= maxProps) {
                break;
            }
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
