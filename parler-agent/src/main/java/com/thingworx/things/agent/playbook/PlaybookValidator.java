package com.thingworx.things.agent.playbook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.llm.ToolDefinition;

/** Fail-closed validation for V1a {@code PlaybookJson}. */
public final class PlaybookValidator {

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Set<String> NODE_KINDS = Set.of(
            "tool_call", "derive", "fan_out", "llm_summary", "condition");
    private static final Set<String> V1A_DERIVE_OPS = Set.of(
            "pick_taxonomy_row",
            "extract_field",
            "flatten_region_entities",
            "collect_thing_names_from_assets",
            "build_property_union",
            "group_alerts_by_source_property",
            "summarize_current_values_by_region",
            "summarize_region_health");
    private static final Set<String> V1B_DERIVE_OPS = Set.of(
            "match_entity_identifiers",
            "require_exact_count",
            "flatten_pair_assets",
            "normalize_resolved_thing",
            "match_identifier_in_rows",
            "pick_branch_output",
            "select_primary_problem_property",
            "union_property_names",
            "trend_targets",
            "trend_summary",
            "summarize_asset_pair_health");
    /** Priority 1 generic derive ops (see {@code docs/agent/playbook-generic-ops-foundation.md}). */
    private static final Set<String> GENERIC_DERIVE_OPS = Set.of("project", "filter", "sort", "top_n", "pick_one",
            "group_by", "aggregate", "join_by_key", "build_targets", "collect_gaps", "flatten_fan_out_rows",
            "normalize_resolved_things", "extract_from_tool_output", "build_nested_object", "json_stringify",
            "resolve_time_window_for_playbook", "empty_rows_if_skipped", "merge_row_sets", "add_computed_fields", "collect_values",
            "join_values", "normalize_text", "match_candidates", "dedupe", "limit_rows", "format_evidence_lines");
    private static final Set<String> CONDITION_OPS = Set.of(
            "is_empty", "is_present", "eq", "ne", "gt", "gte", "lt", "lte");
    private static final Set<String> DERIVE_OPS;
    private static final Pattern PLAYBOOK_CATALOG_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static {
        Set<String> all = new java.util.LinkedHashSet<>(V1A_DERIVE_OPS);
        all.addAll(V1B_DERIVE_OPS);
        all.addAll(GENERIC_DERIVE_OPS);
        DERIVE_OPS = Set.copyOf(all);
    }

    private PlaybookValidator() {}

    /** Supported derive op names for operator snapshot / compatibility inventory. */
    public static Set<String> supportedDeriveOps() {
        return DERIVE_OPS;
    }

    /** Supported node kinds for operator snapshot / compatibility inventory. */
    public static Set<String> supportedNodeKinds() {
        return NODE_KINDS;
    }

    public static final class Result {
        private final boolean valid;
        private final List<String> errors;
        private final List<PlaybookValidationIssue> issues;

        Result(boolean valid, List<String> errors) {
            this(valid, errors, List.of());
        }

        Result(boolean valid, List<String> errors, List<PlaybookValidationIssue> issues) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.issues = issues != null ? List.copyOf(issues) : List.of();
        }

        public boolean valid() {
            return valid;
        }

        public List<String> errors() {
            return errors;
        }

        public List<PlaybookValidationIssue> issues() {
            return issues;
        }
    }

    public static Result validateCatalog(JSONObject catalogRoot) {
        List<String> errors = new ArrayList<>();
        if (catalogRoot == null) {
            errors.add("catalog root is null");
            return new Result(false, errors);
        }
        JSONArray playbooks = catalogRoot.optJSONArray("playbooks");
        if (playbooks == null || playbooks.length() < 1 || playbooks.length() > PlaybookIds.MAX_PACKAGED_PLAYBOOKS) {
            errors.add("catalog must contain 1-" + PlaybookIds.MAX_PACKAGED_PLAYBOOKS + " playbook entries");
        } else {
            Set<String> seenIds = new HashSet<>();
            for (int i = 0; i < playbooks.length(); i++) {
                JSONObject entry = playbooks.optJSONObject(i);
                if (entry == null) {
                    errors.add("catalog entry missing at index " + i);
                    continue;
                }
                String id = entry.optString("id", "");
                if (id.isEmpty()) {
                    errors.add("catalog entry missing id at index " + i);
                } else if (!PLAYBOOK_CATALOG_ID.matcher(id).matches()) {
                    errors.add("invalid catalog id (grammar): " + id);
                }
                if (!seenIds.add(id)) {
                    errors.add("duplicate catalog id: " + id);
                }
                if (entry.has("provider")) {
                    errors.add("provider field is not supported: " + id);
                }
                String path = entry.optString("playbookPath", "");
                if (!PlaybookIds.playbookPathForId(id).equals(path)) {
                    errors.add("playbookPath must be " + PlaybookIds.playbookPathForId(id) + " for " + id);
                }
            }
        }
        return new Result(errors.isEmpty(), errors);
    }

    /** Rejects a top-level {@code provider} field on static playbook JSON (not supported in v1). */
    public static Result validatePlaybookJsonRoot(JSONObject root) {
        List<String> errors = new ArrayList<>();
        if (root != null && root.has("provider")) {
            errors.add("playbook document must not contain provider field");
        }
        return new Result(errors.isEmpty(), errors);
    }

    public static Result validateDocument(PlaybookDocument doc, List<ToolDefinition> toolDefs) {
        return validateDocument(doc, null, toolDefs, ExtendedToolRegistrySnapshot.missing());
    }

    public static Result validateDocument(PlaybookDocument doc, List<ToolDefinition> toolDefs,
            ExtendedToolRegistrySnapshot extendedTools) {
        return validateDocument(doc, null, toolDefs, extendedTools);
    }

    /**
     * @param packageDirectoryId immediate child directory name under {@code /playbooks} for directory discovery; when
     *        {@code null} or blank, skips packaging admission rules (legacy catalog-era unit tests on body-only JSON).
     */
    public static Result validateDocument(PlaybookDocument doc, String packageDirectoryId,
            List<ToolDefinition> toolDefs) {
        return validateDocument(doc, packageDirectoryId, toolDefs, ExtendedToolRegistrySnapshot.missing());
    }

    /**
     * @param packageDirectoryId immediate child directory name under {@code /playbooks} for directory discovery; when
     *        {@code null} or blank, skips packaging admission rules (legacy catalog-era unit tests on body-only JSON).
     * @param extendedTools repository extended-tool registry for {@code $infotable} placement (fail-closed when absent).
     */
    public static Result validateDocument(PlaybookDocument doc, String packageDirectoryId,
            List<ToolDefinition> toolDefs, ExtendedToolRegistrySnapshot extendedTools) {
        if (doc == null) {
            PlaybookValidationCollector nullDoc = new PlaybookValidationCollector(null);
            nullDoc.document("document is null");
            return nullDoc.toResult();
        }
        PlaybookValidationCollector col = new PlaybookValidationCollector(doc);
        if (packageDirectoryId != null && !packageDirectoryId.isBlank()) {
            validateDirectoryPackaging(doc, packageDirectoryId, col);
        }
        if (!PlaybookIds.SCHEMA_V1.equals(doc.schema())) {
            col.document("schema must be " + PlaybookIds.SCHEMA_V1);
        }
        if (doc.finalNodeId() == null || doc.finalNodeId().isEmpty()) {
            col.document("finalNode is required");
        }
        int llmSummaryCount = 0;
        Set<String> seenIds = new HashSet<>();
        for (String nodeId : doc.nodeIdsInOrder()) {
            JSONObject node = doc.nodesById().get(nodeId);
            if (node == null) {
                col.document("missing node: " + nodeId);
                continue;
            }
            if (!NODE_ID.matcher(nodeId).matches()) {
                col.node(nodeId, "", "invalid node id: " + nodeId);
            }
            if (!seenIds.add(nodeId)) {
                col.node(nodeId, "", "duplicate node id: " + nodeId);
            }
            String kind = node.optString("kind", "");
            if (!NODE_KINDS.contains(kind)) {
                col.node(nodeId, "", "unknown node kind for " + nodeId + ": " + kind);
                continue;
            }
            validateDependsOn(node, nodeId, doc, col);
            if ("condition".equals(kind)) {
                validateCondition(node, nodeId, doc, col);
            } else if ("tool_call".equals(kind)) {
                validateToolCall(node, nodeId, toolDefs, col);
            } else if ("derive".equals(kind)) {
                validateDerive(node, nodeId, doc, col);
            } else if ("fan_out".equals(kind)) {
                validateFanOut(node, nodeId, toolDefs, col);
            } else if ("llm_summary".equals(kind)) {
                llmSummaryCount++;
                if (!nodeId.equals(doc.finalNodeId())) {
                    col.node(nodeId, "", "llm_summary must be finalNode: " + nodeId);
                }
            }
            validateDollarTablePlacement(node, nodeId, doc, extendedTools, col);
            validateDollarPathPlacement(node, nodeId, col);
        }
        if (llmSummaryCount != 1) {
            col.document("exactly one llm_summary node required, found " + llmSummaryCount);
        }
        if (doc.finalNodeId() != null && !doc.nodesById().containsKey(doc.finalNodeId())) {
            col.document("finalNode not found: " + doc.finalNodeId());
        }
        if (!isAcyclic(doc)) {
            col.document("node graph must be acyclic");
        }
        validateNoOrphanNodes(doc, col);
        return col.toResult();
    }

    private static void validateDirectoryPackaging(PlaybookDocument doc, String packageDirectoryId,
            PlaybookValidationCollector col) {
        if (doc.playbookId().isEmpty()) {
            col.document("document id is required for directory-packaged playbook");
            return;
        }
        if (!packageDirectoryId.equals(doc.playbookId())) {
            col.document("document id must match package directory name: expected " + packageDirectoryId + " but was "
                    + doc.playbookId());
        }
        if (!PLAYBOOK_CATALOG_ID.matcher(doc.playbookId()).matches()) {
            col.document("invalid playbook id (grammar): " + doc.playbookId());
        }
        if (doc.title() == null || doc.title().isBlank()) {
            col.document("title is required for playbook admission");
        }
    }

    /** {@code $table} / {@code $infotable} are valid only inside {@code tool_call.args} (including fan_out child tool_call args). */
    private static void validateDollarTablePlacement(JSONObject node, String nodeId, PlaybookDocument doc,
            ExtendedToolRegistrySnapshot extendedTools, PlaybookValidationCollector col) {
        String kind = node.optString("kind", "");
        if ("tool_call".equals(kind)) {
            validateToolCallParameterBindings(node, nodeId, doc, extendedTools, col);
            return;
        }
        if ("fan_out".equals(kind)) {
            if (jsonContainsDollarTable(shallowCopyWithoutKeys(node, "node"))
                    || jsonContainsDollarInfotable(shallowCopyWithoutKeys(node, "node"))) {
                col.node(nodeId, "", "$table/$infotable is not allowed in fan_out metadata or items (only in child tool_call.args): "
                        + nodeId);
            }
            JSONObject child = node.optJSONObject("node");
            if (child != null && "tool_call".equals(child.optString("kind", ""))) {
                validateToolCallParameterBindings(child, nodeId + ".child", doc, extendedTools, col);
            }
            return;
        }
        if (jsonContainsDollarTable(node)) {
            col.node(nodeId, "", "$table is only allowed inside tool_call.args: " + nodeId);
        }
        if (jsonContainsDollarInfotable(node)) {
            col.node(nodeId, "", "$infotable is only allowed inside tool_call.args: " + nodeId);
        }
    }

    private static void validateToolCallParameterBindings(JSONObject node, String nodeId, PlaybookDocument doc,
            ExtendedToolRegistrySnapshot extendedTools, PlaybookValidationCollector col) {
        if (jsonContainsDollarTable(shallowCopyWithoutKeys(node, "args"))) {
            col.node(nodeId, "", "$table is only allowed inside tool_call.args (not in evidence or other node fields): " + nodeId);
        }
        if (jsonContainsDollarInfotable(shallowCopyWithoutKeys(node, "args"))) {
            col.node(nodeId, "", "$infotable is only allowed inside tool_call.args (not in evidence or other node fields): "
                    + nodeId);
        }
        validateToolCallArgsDollarTableBindingShape(node.optJSONObject("args"), nodeId, doc, col);
        validateToolCallArgsInfotableBindingShape(node, nodeId, doc, extendedTools, col);
    }

    /**
     * Each {@code args} key may use {@code $table} only when that parameter's entire value is exactly
     * {@code { "$table": "<nodeId>.result" }} (top-level service-parameter binding).
     */
    private static void validateToolCallArgsDollarTableBindingShape(JSONObject args, String nodeId,
            PlaybookDocument doc, PlaybookValidationCollector col) {
        if (args == null) {
            return;
        }
        for (String paramKey : args.keySet()) {
            Object v = args.get(paramKey);
            if (!jsonContainsDollarTable(v)) {
                continue;
            }
            if (!(v instanceof JSONObject) || ((JSONObject) v).length() != 1 || !((JSONObject) v).has("$table")) {
                col.node(nodeId, "", "$table is only allowed as the sole value of a top-level args parameter (parameter "
                        + paramKey + ", node " + nodeId + ")");
                continue;
            }
            String ref = ((JSONObject) v).optString("$table", "").trim();
            if (ref.isEmpty() || !ref.endsWith(".result")) {
                col.node(nodeId, "", "invalid $table reference for parameter " + paramKey + " on " + nodeId
                        + " (v1 requires <nodeId>.result)");
            } else if (doc != null) {
                String srcNodeId = ref.substring(0, ref.length() - ".result".length());
                if (srcNodeId.isEmpty()) {
                    col.node(nodeId, "", "invalid $table reference for parameter " + paramKey + " on " + nodeId
                            + " (empty node id before .result)");
                } else if (!NODE_ID.matcher(srcNodeId).matches()) {
                    col.node(nodeId, "", "invalid $table node id in reference for parameter " + paramKey + " on " + nodeId
                            + ": " + srcNodeId);
                } else if (!doc.nodesById().containsKey(srcNodeId)) {
                    col.node(nodeId, "", "$table references unknown node '" + srcNodeId + "' (parameter " + paramKey + ", node "
                            + nodeId + ")");
                }
            }
        }
    }

    /**
     * Validates {@code $infotable} placement and inner shape per {@code docs/agent/playbook-34-35.md} §6.4.
     */
    private static void validateToolCallArgsInfotableBindingShape(JSONObject node, String nodeId,
            PlaybookDocument doc, ExtendedToolRegistrySnapshot extendedTools, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null || !jsonContainsDollarInfotable(args)) {
            return;
        }
        String tool = node.optString("tool", "").trim();
        if (args.has("parameters") && jsonContainsDollarInfotable(args.opt("parameters"))) {
            col.node(nodeId, "", "$infotable under args.parameters is deferred in v1; use a repository extended tool (node "
                    + nodeId + ")");
        }
        for (String paramKey : args.keySet()) {
            Object v = args.get(paramKey);
            if (!jsonContainsDollarInfotable(v)) {
                continue;
            }
            if (!PlaybookInfotableBindingPolicy.isRepositoryExtendedTool(tool, extendedTools)) {
                if (extendedTools != null && extendedTools.isFileInvalid()) {
                    col.node(nodeId, "", "$infotable requires a valid extended-tool registry (registry unavailable) for tool \""
                            + tool + "\" (parameter " + paramKey + ", node " + nodeId + ")");
                } else {
                    col.node(nodeId, "", "$infotable is only supported for repository extended tools, not built-in \"" + tool
                            + "\" (parameter " + paramKey + ", node " + nodeId + ")");
                }
                continue;
            }
            validateInfotableParameterBinding(v, paramKey, nodeId, doc, col);
        }
    }

    private static void validateInfotableParameterBinding(Object v, String paramKey, String nodeId,
            PlaybookDocument doc, PlaybookValidationCollector col) {
        if (!(v instanceof JSONObject) || ((JSONObject) v).length() != 1 || !((JSONObject) v).has("$infotable")) {
            col.node(nodeId, "", "$infotable is only allowed as the sole value of a parameter binding (parameter " + paramKey
                    + ", node " + nodeId + ")");
            return;
        }
        validateInfotableSpec(((JSONObject) v).optJSONObject("$infotable"), paramKey, nodeId, doc, col);
    }

    private static void validateInfotableSpec(JSONObject spec, String paramKey, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        if (spec == null || spec.length() == 0) {
            col.node(nodeId, "", "$infotable binding requires rows and dataShapeName (parameter " + paramKey + ", node "
                    + nodeId + ")");
            return;
        }
        Object rowsSpec = spec.opt("rows");
        if (rowsSpec instanceof JSONArray) {
            col.node(nodeId, "", "$infotable.rows must be a $ref object, not an inline array (parameter " + paramKey + ", node "
                    + nodeId + ")");
            return;
        }
        if (!(rowsSpec instanceof JSONObject)) {
            col.node(nodeId, "", "$infotable.rows is required (parameter " + paramKey + ", node " + nodeId + ")");
            return;
        }
        JSONObject rowsRef = (JSONObject) rowsSpec;
        if (rowsRef.length() != 1 || !rowsRef.has("$ref")) {
            col.node(nodeId, "", "$infotable.rows must be exactly { \"$ref\": \"<nodeId>.<path>\" } (parameter " + paramKey
                    + ", node " + nodeId + ")");
            return;
        }
        String ref = rowsRef.optString("$ref", "").trim();
        if (ref.isEmpty()) {
            col.node(nodeId, "", "$infotable.rows $ref must be non-blank (parameter " + paramKey + ", node " + nodeId + ")");
            return;
        }
        int dot = ref.indexOf('.');
        if (dot <= 0) {
            col.node(nodeId, "", "$infotable.rows $ref must look like <nodeId>.<path> (parameter " + paramKey + ", node "
                    + nodeId + ")");
            return;
        }
        String refNode = ref.substring(0, dot);
        String refPath = ref.substring(dot + 1);
        if (!NODE_ID.matcher(refNode).matches()) {
            col.node(nodeId, "", "$infotable.rows $ref has invalid node id (parameter " + paramKey + ", node " + nodeId + ")");
        } else if (doc != null && !doc.nodesById().containsKey(refNode)) {
            col.node(nodeId, "", "$infotable.rows $ref references unknown node \"" + refNode + "\" (parameter " + paramKey
                    + ", node " + nodeId + ")");
        }
        if (refPath.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(refPath)) {
            col.node(nodeId, "", "$infotable.rows $ref path must be a valid dotted path (parameter " + paramKey + ", node "
                    + nodeId + ")");
        }
        String dataShapeName = spec.optString("dataShapeName", "").trim();
        if (dataShapeName.isEmpty()) {
            col.node(nodeId, "", "$infotable.dataShapeName is required (parameter " + paramKey + ", node " + nodeId + ")");
        }
        if (spec.has("allowEmpty") && !(spec.opt("allowEmpty") instanceof Boolean)) {
            col.node(nodeId, "", "$infotable.allowEmpty must be a boolean when present (parameter " + paramKey + ", node "
                    + nodeId + ")");
        }
    }

    private static boolean jsonContainsDollarInfotable(Object value) {
        return PlaybookExpressionResolver.jsonContainsDollarInfotable(value);
    }

    private static JSONObject shallowCopyWithoutKeys(JSONObject node, String... omitKeys) {
        JSONObject copy = new JSONObject();
        outer: for (String k : node.keySet()) {
            for (String o : omitKeys) {
                if (o.equals(k)) {
                    continue outer;
                }
            }
            copy.put(k, node.get(k));
        }
        return copy;
    }

    private static boolean jsonContainsDollarTable(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("$table")) {
                return true;
            }
            for (String k : o.keySet()) {
                if (jsonContainsDollarTable(o.get(k))) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                if (jsonContainsDollarTable(a.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code $path} is valid only inside {@code build_targets.args.template} or
     * {@code build_nested_object.args.template} (see generic-ops foundation / playbook-34-35 §6.5).
     */
    private static void validateDollarPathPlacement(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        String kind = node.optString("kind", "");
        if ("derive".equals(kind)) {
            String op = node.optString("op", "");
            if ("build_targets".equals(op) || "build_nested_object".equals(op)) {
                JSONObject args = node.optJSONObject("args");
                if (args != null) {
                    for (String key : args.keySet()) {
                        if ("template".equals(key)) {
                            continue;
                        }
                        if (jsonSubtreeContainsDollarPathKey(args.get(key))) {
                            col.node(nodeId, "", "$path binding is only allowed inside " + op + ".args.template (found under args."
                                    + key + "): " + nodeId);
                        }
                    }
                }
                return;
            }
        }
        if (jsonSubtreeContainsDollarPathKey(node)) {
            col.node(nodeId, "", "$path binding is only allowed inside build_targets.args.template or "
                    + "build_nested_object.args.template: " + nodeId);
        }
    }

    private static boolean jsonSubtreeContainsDollarPathKey(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("$path")) {
                return true;
            }
            for (String k : o.keySet()) {
                if (jsonSubtreeContainsDollarPathKey(o.get(k))) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                if (jsonSubtreeContainsDollarPathKey(a.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateDependsOn(JSONObject node, String nodeId, PlaybookDocument doc, PlaybookValidationCollector col) {
        JSONArray deps = node.optJSONArray("dependsOn");
        if (deps == null) {
            return;
        }
        for (int i = 0; i < deps.length(); i++) {
            String dep = deps.optString(i, "");
            if (!doc.nodesById().containsKey(dep)) {
                col.node(nodeId, "", "dependsOn references missing node " + dep);
            }
        }
    }

    private static void validateToolCall(JSONObject node, String nodeId, List<ToolDefinition> toolDefs,
            PlaybookValidationCollector col) {
        String tool = node.optString("tool", "");
        if (tool.isEmpty()) {
            col.node(nodeId, "", "tool_call missing tool: " + nodeId);
            return;
        }
        if (toolDefs == null || toolDefs.isEmpty()) {
            col.node(nodeId, "", "playbook tool definitions missing for validation: " + nodeId);
            return;
        }
        ToolDefinition def = findToolDefinition(toolDefs, tool);
        if (def == null) {
            col.node(nodeId, "", "unknown tool: " + tool);
        } else if (!def.isPlaybookSafe()) {
            col.node(nodeId, "", "tool playbookSafe=false: " + tool);
        }
        validateToolCallEvidence(node, nodeId, col);
    }

    private static final Pattern EVIDENCE_TOOL_OUTPUT_ROOT_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private static void validateToolCallEvidence(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject ev = node.optJSONObject("evidence");
        if (ev == null) {
            return;
        }
        validateIncludeToolOutputRootFields(ev, nodeId, col);
        validateIncludeToolOutputPaths(ev, nodeId, col);
        validateEvidenceTable(ev, nodeId, col);
    }

    private static void validateIncludeToolOutputRootFields(JSONObject ev, String nodeId,
            PlaybookValidationCollector col) {
        if (!ev.has("includeToolOutputRootFields")) {
            return;
        }
        JSONArray arr = ev.optJSONArray("includeToolOutputRootFields");
        if (arr == null) {
            col.node(nodeId, "", "includeToolOutputRootFields must be a JSON array: " + nodeId);
            return;
        }
        if (arr.length() == 0) {
            col.node(nodeId, "", "includeToolOutputRootFields must be non-empty when present: " + nodeId);
            return;
        }
        if (arr.length() > 24) {
            col.node(nodeId, "", "includeToolOutputRootFields exceeds max 24 entries: " + nodeId);
        }
        for (int i = 0; i < arr.length(); i++) {
            String name = arr.optString(i, "").trim();
            if (name.isEmpty() || !EVIDENCE_TOOL_OUTPUT_ROOT_FIELD.matcher(name).matches()) {
                col.node(nodeId, "", "includeToolOutputRootFields[" + i + "] invalid for " + nodeId);
            }
        }
    }

    private static void validateIncludeToolOutputPaths(JSONObject ev, String nodeId,
            PlaybookValidationCollector col) {
        if (!ev.has("includeToolOutputPaths")) {
            return;
        }
        JSONArray arr = ev.optJSONArray("includeToolOutputPaths");
        if (arr == null) {
            col.node(nodeId, "", "includeToolOutputPaths must be a JSON array: " + nodeId);
            return;
        }
        if (arr.length() == 0) {
            col.node(nodeId, "", "includeToolOutputPaths must be non-empty when present: " + nodeId);
            return;
        }
        if (arr.length() > PlaybookEvidencePathSupport.MAX_INCLUDE_TOOL_OUTPUT_PATHS) {
            col.node(nodeId, "", "includeToolOutputPaths exceeds max "
                    + PlaybookEvidencePathSupport.MAX_INCLUDE_TOOL_OUTPUT_PATHS + " entries: " + nodeId);
        }
        for (int i = 0; i < arr.length(); i++) {
            String path = arr.optString(i, "").trim();
            if (path.isEmpty() || !PlaybookEvidencePathSupport.isValidDotPath(path)) {
                col.node(nodeId, "", "includeToolOutputPaths[" + i + "] invalid dot path for " + nodeId);
            }
        }
    }

    private static void validateEvidenceTable(JSONObject ev, String nodeId, PlaybookValidationCollector col) {
        if (!ev.has("table")) {
            return;
        }
        JSONObject table = ev.optJSONObject("table");
        if (table == null) {
            col.node(nodeId, "", "evidence.table must be an object: " + nodeId);
            return;
        }
        String path = table.optString("path", "").trim();
        if (!path.isEmpty() && !PlaybookEvidencePathSupport.isValidDotPath(path)) {
            col.node(nodeId, "", "evidence.table.path invalid dot path for " + nodeId);
        }
        if (table.has("maxRows")) {
            int maxRows = table.optInt("maxRows", -1);
            if (maxRows < 1 || maxRows > PlaybookEvidencePathSupport.MAX_TABLE_MAX_ROWS) {
                col.node(nodeId, "", "evidence.table.maxRows must be 1.."
                        + PlaybookEvidencePathSupport.MAX_TABLE_MAX_ROWS + ": " + nodeId);
            }
        }
        JSONArray columns = table.optJSONArray("columns");
        if (columns != null) {
            for (int i = 0; i < columns.length(); i++) {
                String colName = columns.optString(i, "").trim();
                if (colName.isEmpty() || !EVIDENCE_TOOL_OUTPUT_ROOT_FIELD.matcher(colName).matches()) {
                    col.node(nodeId, "", "evidence.table.columns[" + i + "] invalid for " + nodeId);
                }
            }
        }
    }

    private static ToolDefinition findToolDefinition(List<ToolDefinition> toolDefs, String toolName) {
        for (ToolDefinition d : toolDefs) {
            if (toolName.equals(d.getName())) {
                return d;
            }
        }
        return null;
    }

    private static void validateDerive(JSONObject node, String nodeId, PlaybookDocument doc, PlaybookValidationCollector col) {
        String op = node.optString("op", "");
        if (!DERIVE_OPS.contains(op)) {
            col.node(nodeId, "", "unknown derive op for " + nodeId + ": " + op);
            return;
        }
        if ("project".equals(op)) {
            validateProjectDerive(node, nodeId, col);
        } else if ("filter".equals(op)) {
            validateFilterDerive(node, nodeId, col);
        } else if ("sort".equals(op)) {
            validateSortDerive(node, nodeId, col);
        } else if ("top_n".equals(op)) {
            validateTopNDerive(node, nodeId, col);
        } else if ("pick_one".equals(op)) {
            validatePickOneDerive(node, nodeId, col);
        } else if ("group_by".equals(op)) {
            validateGroupByDerive(node, nodeId, col);
        } else if ("aggregate".equals(op)) {
            validateAggregateDerive(node, nodeId, col);
        } else if ("join_by_key".equals(op)) {
            validateJoinByKeyDerive(node, nodeId, col);
        } else if ("build_targets".equals(op)) {
            validateBuildTargetsDerive(node, nodeId, col);
        } else if ("collect_gaps".equals(op)) {
            validateCollectGapsDerive(node, nodeId, doc, col);
        } else if ("flatten_fan_out_rows".equals(op)) {
            validateFlattenFanOutRowsDerive(node, nodeId, doc, col);
        } else if ("normalize_resolved_things".equals(op)) {
            validateNormalizeResolvedThingsDerive(node, nodeId, doc, col);
        } else if ("extract_from_tool_output".equals(op)) {
            validateExtractFromToolOutputDerive(node, nodeId, doc, col);
        } else if ("build_nested_object".equals(op)) {
            validateBuildNestedObjectDerive(node, nodeId, doc, col);
        } else if ("json_stringify".equals(op)) {
            validateJsonStringifyDerive(node, nodeId, col);
        } else if ("resolve_time_window_for_playbook".equals(op)) {
            validateResolveTimeWindowDerive(node, nodeId, col);
        } else if ("empty_rows_if_skipped".equals(op)) {
            validateEmptyRowsIfSkippedDerive(node, nodeId, doc, col);
        } else if ("merge_row_sets".equals(op)) {
            validateMergeRowSetsDerive(node, nodeId, doc, col);
        } else if ("add_computed_fields".equals(op)) {
            validateAddComputedFieldsDerive(node, nodeId, col);
        } else if ("collect_values".equals(op)) {
            validateCollectValuesDerive(node, nodeId, col);
        } else if ("join_values".equals(op)) {
            validateJoinValuesDerive(node, nodeId, col);
        } else if (PlaybookAuthoringDeriveOps.supports(op)) {
            PlaybookAuthoringDeriveOpsValidator.validate(op, node, nodeId, doc, col);
        } else if ("pick_taxonomy_row".equals(op)) {
            validatePickTaxonomyRowDerive(node, nodeId, col);
        } else if ("normalize_resolved_thing".equals(op)) {
            validateNormalizeResolvedThingDerive(node, nodeId, doc, col);
        } else if ("match_identifier_in_rows".equals(op)) {
            validateMatchIdentifierInRowsDerive(node, nodeId, doc, col);
        } else if ("pick_branch_output".equals(op)) {
            validatePickBranchOutputDerive(node, nodeId, doc, col);
        }
    }

    private static void validateMatchIdentifierInRowsDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "match_identifier_in_rows derive missing args: " + nodeId);
            return;
        }
        if (jsonContainsDollarTable(args)) {
            col.node(nodeId, "", "match_identifier_in_rows must not use $table in args (use $ref to tool JSON rows): " + nodeId);
        }
        if (jsonContainsDollarInfotable(args)) {
            col.node(nodeId, "", "match_identifier_in_rows must not use $infotable in args: " + nodeId);
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "match_identifier_in_rows missing rows: " + nodeId);
        }
        if (!args.has("identifier")) {
            col.node(nodeId, "", "match_identifier_in_rows missing identifier: " + nodeId);
        }
    }

    private static void validatePickBranchOutputDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "pick_branch_output derive missing args: " + nodeId);
            return;
        }
        String condId = args.optString("conditionNodeId", "").trim();
        String thenId = args.optString("thenNodeId", "").trim();
        String elseId = args.optString("elseNodeId", "").trim();
        if (condId.isEmpty() || thenId.isEmpty() || elseId.isEmpty()) {
            col.node(nodeId, "", "pick_branch_output requires conditionNodeId, thenNodeId, and elseNodeId: " + nodeId);
            return;
        }
        JSONObject cond = doc.nodesById().get(condId);
        if (cond == null || !"condition".equals(cond.optString("kind", ""))) {
            col.node(nodeId, "", "pick_branch_output conditionNodeId must reference a condition node: " + nodeId);
            return;
        }
        if (!thenId.equals(cond.optString("then", "").trim())) {
            col.node(nodeId, "", "pick_branch_output thenNodeId must match the condition node's then: " + nodeId);
        }
        String elseRoot = cond.optString("else", "").trim();
        if (!elseId.equals(elseRoot) && !nodeDependsTransitivelyOn(doc, elseId, elseRoot)) {
            col.node(nodeId, "", "pick_branch_output elseNodeId must be the condition else node or depend on it via dependsOn: "
                    + nodeId);
        }
        if (!doc.nodesById().containsKey(thenId)) {
            col.node(nodeId, "", "pick_branch_output thenNodeId not found: " + thenId + " (" + nodeId + ")");
        }
        if (!doc.nodesById().containsKey(elseId)) {
            col.node(nodeId, "", "pick_branch_output elseNodeId not found: " + elseId + " (" + nodeId + ")");
        }
    }

    private static void validatePickTaxonomyRowDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "pick_taxonomy_row derive missing args: " + nodeId);
            return;
        }
        if (!args.has("taxonomyRows")) {
            col.node(nodeId, "", "pick_taxonomy_row derive missing taxonomyRows: " + nodeId);
        }
        if (args.has("whenAssetTypeMissing")) {
            String w = args.optString("whenAssetTypeMissing", "").trim();
            if (!"empty_taxonomy".equals(w) && !"clarify".equals(w)) {
                col.node(nodeId, "", "pick_taxonomy_row whenAssetTypeMissing must be empty_taxonomy or clarify for " + nodeId);
            }
        }
    }

    private static void validateNormalizeResolvedThingDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "normalize_resolved_thing derive missing args: " + nodeId);
            return;
        }
        String sid = args.optString("sourceNodeId", "").trim();
        if (sid.isEmpty()) {
            col.node(nodeId, "", "normalize_resolved_thing missing sourceNodeId: " + nodeId);
            return;
        }
        if (!doc.nodesById().containsKey(sid)) {
            col.node(nodeId, "", "normalize_resolved_thing sourceNodeId not found: " + sid + " (" + nodeId + ")");
            return;
        }
        JSONObject src = doc.nodesById().get(sid);
        if (src == null || !"tool_call".equals(src.optString("kind", ""))) {
            col.node(nodeId, "", "normalize_resolved_thing sourceNodeId must reference a tool_call node: " + sid + " (" + nodeId
                    + ")");
            return;
        }
        if (!"resolve_thing".equals(src.optString("tool", ""))) {
            col.node(nodeId, "", "normalize_resolved_thing sourceNodeId must be a resolve_thing tool_call: " + sid + " ("
                    + nodeId + ")");
        }
    }

    private static void validateNormalizeResolvedThingsDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "normalize_resolved_things derive missing args: " + nodeId);
            return;
        }
        String fanOutId = args.optString("fanOutNodeId", "").trim();
        if (fanOutId.isEmpty()) {
            col.node(nodeId, "", "normalize_resolved_things missing fanOutNodeId: " + nodeId);
            return;
        }
        if (doc != null) {
            JSONObject fo = doc.nodesById().get(fanOutId);
            if (fo == null) {
                col.node(nodeId, "", "normalize_resolved_things fanOutNodeId not found: " + fanOutId + " (" + nodeId + ")");
            } else if (!"fan_out".equals(fo.optString("kind", ""))) {
                col.node(nodeId, "", "normalize_resolved_things fanOutNodeId must name a fan_out node: " + fanOutId + " ("
                        + nodeId + ")");
            } else {
                JSONObject inner = fo.optJSONObject("node");
                if (inner == null || !"tool_call".equals(inner.optString("kind", ""))
                        || !"resolve_thing".equals(inner.optString("tool", ""))) {
                    col.node(nodeId, "", "normalize_resolved_things fan_out " + fanOutId
                            + " must wrap resolve_thing tool_call: " + nodeId);
                }
            }
        }
        if (args.has("onUnresolved")) {
            String ou = args.optString("onUnresolved", "").trim();
            if (!"gap".equals(ou) && !"clarify".equals(ou)) {
                col.node(nodeId, "", "normalize_resolved_things onUnresolved must be gap or clarify: " + nodeId);
            }
        }
        validateOrchestrationMaxRows(args, nodeId, "normalize_resolved_things", col);
        if (args.has("maxRows") && args.has("minResolvedRows")) {
            int maxRows = args.optInt("maxRows", -1);
            int minResolved = args.optInt("minResolvedRows", 0);
            if (maxRows >= 1 && minResolved > maxRows) {
                col.node(nodeId, "", "normalize_resolved_things maxRows must be >= minResolvedRows: " + nodeId);
            }
        }
    }

    private static void validateExtractFromToolOutputDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "extract_from_tool_output derive missing args: " + nodeId);
            return;
        }
        if (args.has("source")) {
            col.node(nodeId, "", "extract_from_tool_output must use sourceNodeId, not source $ref object: " + nodeId);
        }
        String sid = args.optString("sourceNodeId", "").trim();
        if (sid.isEmpty()) {
            col.node(nodeId, "", "extract_from_tool_output missing sourceNodeId: " + nodeId);
            return;
        }
        String mode = args.optString("mode", "").trim();
        if (!"single".equals(mode) && !"fan_out_children".equals(mode)) {
            col.node(nodeId, "", "extract_from_tool_output mode must be single or fan_out_children: " + nodeId);
            return;
        }
        if (doc != null) {
            JSONObject src = doc.nodesById().get(sid);
            if (src == null) {
                col.node(nodeId, "", "extract_from_tool_output sourceNodeId not found: " + sid + " (" + nodeId + ")");
            } else if ("single".equals(mode)) {
                if (!"tool_call".equals(src.optString("kind", ""))) {
                    col.node(nodeId, "", "extract_from_tool_output single mode requires tool_call sourceNodeId: " + nodeId);
                }
            } else if (!"fan_out".equals(src.optString("kind", ""))) {
                col.node(nodeId, "", "extract_from_tool_output fan_out_children mode requires fan_out sourceNodeId: " + nodeId);
            }
        }
        String arrayPath = args.optString("arrayPath", "").trim();
        if (!PlaybookOrchestrationPath.isValidArrayPath(arrayPath)) {
            col.node(nodeId, "", "extract_from_tool_output invalid arrayPath: " + nodeId);
        }
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            col.node(nodeId, "", "extract_from_tool_output fields must be a non-empty array: " + nodeId);
        } else {
            boolean fanOut = "fan_out_children".equals(mode);
            Set<String> seenAs = new HashSet<>();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject spec = fields.optJSONObject(i);
                if (spec == null) {
                    col.node(nodeId, "", "extract_from_tool_output fields[" + i + "] must be an object: " + nodeId);
                    continue;
                }
                String from = spec.optString("from", "").trim();
                if (!PlaybookOrchestrationPath.isValidFieldFrom(from, fanOut)) {
                    col.node(nodeId, "", "extract_from_tool_output fields[" + i + "].from invalid for mode: " + nodeId);
                }
                String as = spec.optString("as", "").trim();
                if (as.isEmpty()) {
                    col.node(nodeId, "", "extract_from_tool_output fields[" + i + "] requires non-blank as: " + nodeId);
                    continue;
                }
                if (!NODE_ID.matcher(as).matches()) {
                    col.node(nodeId, "", "extract_from_tool_output fields[" + i + "] as invalid identifier: " + nodeId);
                }
                if (PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                    col.node(nodeId, "", "extract_from_tool_output fields[" + i + "] as is reserved: " + as + " (" + nodeId + ")");
                }
                if (!seenAs.add(as)) {
                    col.node(nodeId, "", "extract_from_tool_output duplicate as \"" + as + "\": " + nodeId);
                }
            }
        }
        JSONObject where = args.optJSONObject("where");
        if (where != null && where.length() > 0) {
            try {
                PlaybookRowPredicate.requireValidShape(where);
            } catch (PlaybookRunException e) {
                col.node(nodeId, "", "extract_from_tool_output for " + nodeId + ": " + e.getMessage());
            }
        }
        validateOrchestrationMaxRows(args, nodeId, "extract_from_tool_output", col);
    }

    private static void validateBuildNestedObjectDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "build_nested_object derive missing args: " + nodeId);
            return;
        }
        JSONObject sources = args.optJSONObject("sources");
        if (sources == null || sources.length() == 0) {
            col.node(nodeId, "", "build_nested_object derive sources must be a non-empty object: " + nodeId);
        } else {
            for (String srcName : sources.keySet()) {
                if (!NODE_ID.matcher(srcName).matches()) {
                    col.node(nodeId, "", "build_nested_object derive sources key invalid identifier: " + srcName + " (" + nodeId
                            + ")");
                }
            }
        }
        JSONObject template = args.optJSONObject("template");
        if (template == null || template.length() == 0) {
            col.node(nodeId, "", "build_nested_object derive template must be a non-empty object: " + nodeId);
        } else {
            validateNestedObjectTemplate(template, sources != null ? sources.keySet() : Set.of(), nodeId, "", true,
                    doc, col);
        }
        validateNestedCap(args, "maxParents", PlaybookGenericOpsConstants.MAX_NESTED_PARENTS, nodeId, col);
        validateNestedCap(args, "maxChildren", PlaybookGenericOpsConstants.MAX_NESTED_CHILDREN, nodeId, col);
        if (args.has("minParents")) {
            Object mp = args.opt("minParents");
            if (!(mp instanceof Number) || ((Number) mp).doubleValue() < 0
                    || ((Number) mp).doubleValue() != Math.rint(((Number) mp).doubleValue())) {
                col.node(nodeId, "", "build_nested_object derive minParents must be a non-negative integer: " + nodeId);
            } else if (args.has("maxParents")) {
                int minP = ((Number) mp).intValue();
                int maxP = args.optInt("maxParents", 0);
                if (minP > maxP) {
                    col.node(nodeId, "", "build_nested_object derive minParents must be <= maxParents: " + nodeId);
                }
            }
        }
        if (args.has("omitNull") && !(args.opt("omitNull") instanceof Boolean)) {
            col.node(nodeId, "", "build_nested_object derive omitNull must be a boolean: " + nodeId);
        }
    }

    private static void validateJsonStringifyDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "json_stringify derive missing args: " + nodeId);
            return;
        }
        if (!args.has("value")) {
            col.node(nodeId, "", "json_stringify derive missing value: " + nodeId);
        }
        if (!args.has("maxBytes")) {
            col.node(nodeId, "", "json_stringify derive missing maxBytes: " + nodeId);
            return;
        }
        Object mb = args.opt("maxBytes");
        if (!(mb instanceof Number)) {
            col.node(nodeId, "", "json_stringify derive maxBytes must be a number: " + nodeId);
            return;
        }
        double d = ((Number) mb).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > PlaybookGenericOpsConstants.MAX_JSON_STRINGIFY_BYTES) {
            col.node(nodeId, "", "json_stringify derive maxBytes must be an integer from 1 to "
                    + PlaybookGenericOpsConstants.MAX_JSON_STRINGIFY_BYTES + ": " + nodeId);
        }
    }

    private static void validateResolveTimeWindowDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "resolve_time_window_for_playbook derive missing args: " + nodeId);
            return;
        }
        if (!args.has("quickIntervalRows")) {
            col.node(nodeId, "", "resolve_time_window_for_playbook derive missing quickIntervalRows: " + nodeId);
        }
        if (!args.has("timezone")) {
            col.node(nodeId, "", "resolve_time_window_for_playbook derive missing timezone: " + nodeId);
        }
        if (args.has("onUnsupported")) {
            String mode = args.optString("onUnsupported", "").trim();
            if (!"clarify".equals(mode) && !"gap".equals(mode)) {
                col.node(nodeId, "", "resolve_time_window_for_playbook derive onUnsupported must be clarify or gap: " + nodeId);
            }
        }
    }

    private static void validateEmptyRowsIfSkippedDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "empty_rows_if_skipped derive missing args: " + nodeId);
            return;
        }
        String sourceNodeId = args.optString("sourceNodeId", "").trim();
        if (sourceNodeId.isEmpty()) {
            col.node(nodeId, "", "empty_rows_if_skipped derive missing sourceNodeId: " + nodeId);
            return;
        }
        if (!NODE_ID.matcher(sourceNodeId).matches()) {
            col.node(nodeId, "", "empty_rows_if_skipped derive sourceNodeId invalid: " + nodeId);
        } else if (doc != null && !doc.nodesById().containsKey(sourceNodeId)) {
            col.node(nodeId, "", "empty_rows_if_skipped derive sourceNodeId references unknown node \"" + sourceNodeId
                    + "\": " + nodeId);
        }
    }

    private static void validateMergeRowSetsDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "merge_row_sets derive missing args: " + nodeId);
            return;
        }
        JSONArray sources = args.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            col.node(nodeId, "", "merge_row_sets derive sources must be a non-empty array: " + nodeId);
            return;
        }
        for (int i = 0; i < sources.length(); i++) {
            Object el = sources.get(i);
            if (!(el instanceof JSONObject)) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "] must be an object: " + nodeId);
                continue;
            }
            JSONObject spec = (JSONObject) el;
            if (!spec.has("$ref") || spec.length() != 1) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "] must be exactly { \"$ref\": \"...\" }: "
                        + nodeId);
                continue;
            }
            String ref = spec.optString("$ref", "").trim();
            if (ref.isEmpty()) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "].$ref must be non-blank: " + nodeId);
                continue;
            }
            int dot = ref.indexOf('.');
            if (dot <= 0) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "].$ref must look like <nodeId>.<path>: "
                        + nodeId);
                continue;
            }
            String refNode = ref.substring(0, dot);
            String refPath = ref.substring(dot + 1);
            if (!NODE_ID.matcher(refNode).matches()) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "].$ref has invalid node id: " + nodeId);
            } else if (doc != null && !doc.nodesById().containsKey(refNode)) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "].$ref references unknown node \""
                        + refNode + "\": " + nodeId);
            }
            if (refPath.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(refPath)) {
                col.node(nodeId, "", "merge_row_sets derive sources[" + i + "].$ref path must be a valid dotted path: "
                        + nodeId);
            }
        }
        if (!args.has("maxSources")) {
            col.node(nodeId, "", "merge_row_sets derive missing maxSources: " + nodeId);
        } else {
            validatePositiveIntCap(args, "maxSources", PlaybookGenericOpsConstants.MAX_MERGE_ROW_SETS_SOURCES, nodeId,
                    "merge_row_sets", col);
            if (args.opt("maxSources") instanceof Number) {
                int ms = ((Number) args.opt("maxSources")).intValue();
                if (sources.length() > ms) {
                    col.node(nodeId, "", "merge_row_sets derive sources length exceeds maxSources: " + nodeId);
                }
            }
        }
        if (!args.has("maxRows")) {
            col.node(nodeId, "", "merge_row_sets derive missing maxRows: " + nodeId);
        } else {
            validatePositiveIntCap(args, "maxRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS, nodeId,
                    "merge_row_sets", col);
        }
    }

    private static void validateAddComputedFieldsDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "add_computed_fields derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "add_computed_fields derive missing rows: " + nodeId);
        }
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            col.node(nodeId, "", "add_computed_fields derive fields must be a non-empty array: " + nodeId);
            return;
        }
        Set<String> seenAs = new java.util.LinkedHashSet<>();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject spec = fields.optJSONObject(i);
            if (spec == null) {
                col.node(nodeId, "", "add_computed_fields derive fields[" + i + "] must be an object: " + nodeId);
                continue;
            }
            String as = spec.optString("as", "").trim();
            if (as.isEmpty() || !as.matches("[A-Za-z][A-Za-z0-9_-]*")
                    || PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                col.node(nodeId, "", "add_computed_fields derive fields[" + i + "].as invalid: " + nodeId);
            } else if (!seenAs.add(as)) {
                col.node(nodeId, "", "add_computed_fields derive duplicate fields[" + i + "].as \"" + as + "\": " + nodeId);
            }
            JSONObject expr = spec.optJSONObject("expr");
            if (expr == null || expr.length() == 0) {
                col.node(nodeId, "", "add_computed_fields derive fields[" + i + "].expr required: " + nodeId);
                continue;
            }
            String op = expr.optString("op", "").trim().toLowerCase(java.util.Locale.ROOT);
            if (!"datetime_diff_minutes".equals(op) && !"add".equals(op) && !"sub".equals(op) && !"mul".equals(op)
                    && !"div".equals(op)) {
                col.node(nodeId, "", "add_computed_fields derive fields[" + i + "].expr.op unsupported: " + nodeId);
                continue;
            }
            validateAddComputedFieldsExprOperand(expr, "left", i, nodeId, col);
            validateAddComputedFieldsExprOperand(expr, "right", i, nodeId, col);
        }
        if (args.has("onNull")) {
            String mode = args.optString("onNull", "").trim().toLowerCase(java.util.Locale.ROOT);
            if (!"null".equals(mode) && !"skip".equals(mode) && !"gap".equals(mode)) {
                col.node(nodeId, "", "add_computed_fields derive onNull must be null, skip, or gap: " + nodeId);
            }
        }
        if (args.has("maxRows")) {
            validatePositiveIntCap(args, "maxRows", PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS, nodeId,
                    "add_computed_fields", col);
        }
    }

    private static void validateAddComputedFieldsExprOperand(JSONObject expr, String key, int fieldIndex,
            String nodeId, PlaybookValidationCollector col) {
        String path = expr.optString(key, "").trim();
        if (path.isEmpty()) {
            col.node(nodeId, "", "add_computed_fields derive fields[" + fieldIndex + "].expr." + key
                    + " must be a non-blank dotted path: " + nodeId);
        } else if (!PlaybookGenericPathGrammar.isValidDottedPath(path)) {
            col.node(nodeId, "", "add_computed_fields derive fields[" + fieldIndex + "].expr." + key
                    + " invalid dotted path: " + nodeId);
        }
    }

    private static void validateCollectValuesDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "collect_values derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "collect_values derive missing rows: " + nodeId);
        }
        String field = args.optString("field", "").trim();
        if (field.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(field)) {
            col.node(nodeId, "", "collect_values derive field must be a non-blank dotted path: " + nodeId);
        }
        if (args.has("maxValues")) {
            validatePositiveIntCap(args, "maxValues", PlaybookGenericOpsConstants.MAX_COLLECT_VALUES, nodeId,
                    "collect_values", col);
        }
    }

    private static void validateJoinValuesDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "join_values derive missing args: " + nodeId);
            return;
        }
        if (!args.has("values")) {
            col.node(nodeId, "", "join_values derive missing values: " + nodeId);
        }
        if (args.has("maxLength")) {
            validatePositiveIntCap(args, "maxLength", PlaybookGenericOpsConstants.MAX_JOIN_VALUE_CHARS, nodeId,
                    "join_values", col);
        }
    }

    private static void validatePositiveIntCap(JSONObject args, String key, int cap, String nodeId, String op,
            PlaybookValidationCollector col) {
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            col.node(nodeId, "", op + " derive " + key + " must be a number: " + nodeId);
            return;
        }
        double d = ((Number) o).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > cap) {
            col.node(nodeId, "", op + " derive " + key + " must be an integer between 1 and " + cap + ": " + nodeId);
        }
    }

    private static void validateNestedCap(JSONObject args, String key, int cap, String nodeId, PlaybookValidationCollector col) {
        if (!args.has(key)) {
            col.node(nodeId, "", "build_nested_object derive missing " + key + ": " + nodeId);
            return;
        }
        Object o = args.opt(key);
        if (!(o instanceof Number)) {
            col.node(nodeId, "", "build_nested_object derive " + key + " must be a number: " + nodeId);
            return;
        }
        double d = ((Number) o).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > cap) {
            col.node(nodeId, "", "build_nested_object derive " + key + " must be an integer from 1 to " + cap + ": " + nodeId);
        }
    }

    private static void validateNestedNodeRef(String ref, String path, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        if (ref == null || ref.isBlank()) {
            col.node(nodeId, "", "build_nested_object derive $nodeRef must be non-blank at " + path + ": " + nodeId);
            return;
        }
        int dot = ref.indexOf('.');
        if (dot <= 0) {
            col.node(nodeId, "", "build_nested_object derive $nodeRef must look like <nodeId>.<path> at " + path + ": "
                    + nodeId);
            return;
        }
        String refNode = ref.substring(0, dot);
        String refPath = ref.substring(dot + 1);
        if (!NODE_ID.matcher(refNode).matches()) {
            col.node(nodeId, "", "build_nested_object derive $nodeRef has invalid node id at " + path + ": " + nodeId);
        } else if (doc != null && !doc.nodesById().containsKey(refNode)) {
            col.node(nodeId, "", "build_nested_object derive $nodeRef references unknown node \"" + refNode + "\" at " + path
                    + ": " + nodeId);
        }
        if (refPath.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(refPath)) {
            col.node(nodeId, "", "build_nested_object derive $nodeRef path must be a valid dotted path at " + path + ": "
                    + nodeId);
        }
    }

    private static void validateNestedObjectTemplate(JSONObject template, Set<String> sourceNames, String nodeId,
            String debugPath, boolean topLevel, PlaybookDocument doc, PlaybookValidationCollector col) {
        for (String key : template.keySet()) {
            Object raw = template.get(key);
            String path = debugPath.isEmpty() ? key : debugPath + "." + key;
            if (raw instanceof JSONArray) {
                col.node(nodeId, "", "build_nested_object derive template arrays require $map at " + path + ": " + nodeId);
                continue;
            }
            if (!(raw instanceof JSONObject)) {
                continue;
            }
            JSONObject o = (JSONObject) raw;
            if (o.length() == 1 && o.has("$map")) {
                if (!topLevel) {
                    col.node(nodeId, "", "build_nested_object derive $map only allowed at template top level (" + path + "): "
                            + nodeId);
                }
                JSONObject mapSpec = o.optJSONObject("$map");
                if (mapSpec == null) {
                    col.node(nodeId, "", "build_nested_object derive $map must be an object at " + path + ": " + nodeId);
                    continue;
                }
                String over = mapSpec.optString("over", "").trim();
                if (over.isEmpty() || !sourceNames.contains(over)) {
                    col.node(nodeId, "", "build_nested_object derive $map.over must name a declared source at " + path + ": "
                            + nodeId);
                }
                JSONObject each = mapSpec.optJSONObject("each");
                if (each == null || each.length() == 0) {
                    col.node(nodeId, "", "build_nested_object derive $map.each required at " + path + ": " + nodeId);
                } else {
                    validateNestedEachTemplate(each, sourceNames, nodeId, path + ".$map.each", doc, col);
                }
                continue;
            }
            if (o.length() == 1 && o.has("$nodeRef")) {
                validateNestedNodeRef(o.optString("$nodeRef", "").trim(), path, nodeId, doc, col);
                continue;
            }
            if (o.length() == 1 && o.has("$literal")) {
                continue;
            }
            if (o.length() == 1 && o.has("$path")) {
                col.node(nodeId, "", "build_nested_object derive $path only allowed inside $map.each at " + path + ": " + nodeId);
                continue;
            }
            if (o.length() == 1 && o.has("$src")) {
                col.node(nodeId, "", "build_nested_object derive $src only allowed inside $map.each at " + path + ": " + nodeId);
                continue;
            }
            for (String k : o.keySet()) {
                if (!k.isEmpty() && k.charAt(0) == '$') {
                    col.node(nodeId, "", "build_nested_object derive unsupported binding " + k + " at " + path + ": " + nodeId);
                }
            }
            validateNestedObjectTemplate(o, sourceNames, nodeId, path, false, doc, col);
        }
    }

    private static void validateNestedEachTemplate(JSONObject each, Set<String> sourceNames, String nodeId,
            String debugPath, PlaybookDocument doc, PlaybookValidationCollector col) {
        for (String key : each.keySet()) {
            Object raw = each.get(key);
            String path = debugPath + "." + key;
            if (!(raw instanceof JSONObject)) {
                continue;
            }
            JSONObject o = (JSONObject) raw;
            if (o.length() == 1 && o.has("$path")) {
                String field = o.optString("$path", "").trim();
                if (field.isEmpty() || !PlaybookGenericPathGrammar.isSingleSegmentField(field)) {
                    col.node(nodeId, "", "build_nested_object derive $path in $map.each must be a single identifier at " + path
                            + ": " + nodeId);
                }
                continue;
            }
            if (o.length() == 1 && o.has("$src")) {
                String src = o.optString("$src", "").trim();
                if (src.isEmpty() || !sourceNames.contains(src)) {
                    col.node(nodeId, "", "build_nested_object derive $src must name a declared source at " + path + ": "
                            + nodeId);
                }
                continue;
            }
            if (o.length() == 1 && o.has("$nodeRef")) {
                validateNestedNodeRef(o.optString("$nodeRef", "").trim(), path, nodeId, doc, col);
                continue;
            }
            if (o.length() == 1 && o.has("$literal")) {
                continue;
            }
            for (String k : o.keySet()) {
                if (!k.isEmpty() && k.charAt(0) == '$') {
                    col.node(nodeId, "", "build_nested_object derive unsupported binding " + k + " at " + path + ": " + nodeId);
                }
            }
            validateNestedObjectTemplate(o, sourceNames, nodeId, path, false, doc, col);
        }
    }

    private static void validateOrchestrationMaxRows(JSONObject args, String nodeId, String op, PlaybookValidationCollector col) {
        if (!args.has("maxRows")) {
            col.node(nodeId, "", op + " missing maxRows: " + nodeId);
            return;
        }
        Object mr = args.opt("maxRows");
        if (!(mr instanceof Number)) {
            col.node(nodeId, "", op + " maxRows must be a number: " + nodeId);
            return;
        }
        double d = ((Number) mr).doubleValue();
        if (d < 1 || d != Math.rint(d) || d > PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS) {
            col.node(nodeId, "", op + " maxRows must be an integer from 1 to "
                    + PlaybookGenericOpsConstants.MAX_GENERIC_OUTPUT_ROWS + ": " + nodeId);
        }
    }

    private static void validateFilterDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "filter derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "filter derive missing rows: " + nodeId);
        }
        JSONObject where = args.optJSONObject("where");
        if (where == null || where.length() == 0) {
            col.node(nodeId, "", "filter derive where must be a non-empty predicate object: " + nodeId);
            return;
        }
        try {
            PlaybookRowPredicate.requireValidShape(where);
        } catch (PlaybookRunException e) {
            col.node(nodeId, "", "filter derive for " + nodeId + ": " + e.getMessage());
        }
    }

    private static void validateSortDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "sort derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "sort derive missing rows: " + nodeId);
        }
        JSONArray orderBy = args.optJSONArray("orderBy");
        if (orderBy == null || orderBy.length() == 0) {
            col.node(nodeId, "", "sort derive orderBy must be a non-empty array: " + nodeId);
            return;
        }
        validateOrderByEntries(orderBy, nodeId, "sort", col);
    }

    private static void validateTopNDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "top_n derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "top_n derive missing rows: " + nodeId);
        }
        if (!args.has("n")) {
            col.node(nodeId, "", "top_n derive missing n: " + nodeId);
        } else {
            Object nObj = args.opt("n");
            if (!(nObj instanceof Number)) {
                col.node(nodeId, "", "top_n derive n must be a number: " + nodeId);
            } else {
                double nd = ((Number) nObj).doubleValue();
                if (nd < 0 || nd != Math.rint(nd)) {
                    col.node(nodeId, "", "top_n derive n must be a non-negative integer: " + nodeId);
                } else if (nd > PlaybookGenericOpsConstants.MAX_GENERIC_TOP_N) {
                    col.node(nodeId, "", "top_n derive n exceeds cap " + PlaybookGenericOpsConstants.MAX_GENERIC_TOP_N + ": "
                            + nodeId);
                }
            }
        }
        if (args.has("orderBy")) {
            JSONArray orderBy = args.optJSONArray("orderBy");
            if (orderBy == null) {
                col.node(nodeId, "", "top_n derive orderBy must be an array when present: " + nodeId);
            } else if (orderBy.length() == 0) {
                col.node(nodeId, "", "top_n derive orderBy must be omitted or non-empty: " + nodeId);
            } else {
                validateOrderByEntries(orderBy, nodeId, "top_n", col);
            }
        }
    }

    private static void validatePickOneDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "pick_one derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "pick_one derive missing rows: " + nodeId);
        }
        JSONObject where = args.optJSONObject("where");
        if (where == null || where.length() == 0) {
            col.node(nodeId, "", "pick_one derive where must be a non-empty predicate object: " + nodeId);
        } else {
            try {
                PlaybookRowPredicate.requireValidShape(where);
            } catch (PlaybookRunException e) {
                col.node(nodeId, "", "pick_one derive for " + nodeId + ": " + e.getMessage());
            }
        }
        String onZero = args.optString("onZero", "").trim().toLowerCase(Locale.ROOT);
        if (!onZero.isEmpty()
                && !Set.of("needs_clarification", "gap", "empty").contains(onZero)) {
            col.node(nodeId, "", "pick_one derive onZero invalid for " + nodeId);
        }
        String onMultiple = args.optString("onMultiple", "").trim().toLowerCase(Locale.ROOT);
        if (!onMultiple.isEmpty()
                && !Set.of("needs_clarification", "gap", "first").contains(onMultiple)) {
            col.node(nodeId, "", "pick_one derive onMultiple invalid for " + nodeId);
        }
    }

    private static void validateGroupByDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "group_by derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "group_by derive missing rows: " + nodeId);
        }
        if (args.optBoolean("foldOverflowToOther", false)) {
            col.node(nodeId, "", "group_by derive foldOverflowToOther is not supported: " + nodeId);
        }
        JSONArray keys = args.optJSONArray("keys");
        Set<String> keySet = new HashSet<>();
        if (keys == null || keys.length() == 0) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("action", "fix_group_by_keys");
            hint.put("expectedShape", "array of single-segment identifier strings");
            hint.put("example", Map.of("keys", List.of("status")));
            col.node(nodeId, ".args.keys", "INVALID_GROUP_BY_KEYS",
                    "group_by derive keys must be a non-empty array: " + nodeId, hint);
        } else {
            for (int i = 0; i < keys.length(); i++) {
                Object raw = keys.get(i);
                if (!(raw instanceof String)) {
                    col.node(nodeId, ".args.keys[" + i + "]", "group_by derive keys[" + i + "] must be a JSON string: " + nodeId);
                    continue;
                }
                String k = ((String) raw).trim();
                if (k.isEmpty()) {
                    col.node(nodeId, ".args.keys[" + i + "]", "group_by derive keys[" + i + "] must be a non-blank string: " + nodeId);
                    continue;
                }
                if ("_other".equals(k)) {
                    col.node(nodeId, ".args.keys[" + i + "]", "group_by derive keys[" + i + "] must not use reserved name _other: " + nodeId);
                }
                if (!PlaybookGenericPathGrammar.isSingleSegmentField(k)) {
                    col.node(nodeId, ".args.keys[" + i + "]", "group_by derive keys[" + i + "] must be a single identifier segment (no dots): "
                            + nodeId);
                }
                if (!keySet.add(k)) {
                    col.node(nodeId, "", "group_by derive duplicate key \"" + k + "\": " + nodeId);
                }
            }
        }
        if (!args.has("maxGroups")) {
            col.node(nodeId, "", "group_by derive missing maxGroups: " + nodeId);
        } else {
            Object mgObj = args.opt("maxGroups");
            if (!(mgObj instanceof Number)) {
                col.node(nodeId, "", "group_by derive maxGroups must be a number: " + nodeId);
            } else {
                double mgd = ((Number) mgObj).doubleValue();
                if (mgd < 1 || mgd != Math.rint(mgd)) {
                    col.node(nodeId, "", "group_by derive maxGroups must be an integer >= 1: " + nodeId);
                } else if (mgd > PlaybookGenericOpsConstants.MAX_GENERIC_GROUPS) {
                    col.node(nodeId, "", "group_by derive maxGroups exceeds cap " + PlaybookGenericOpsConstants.MAX_GENERIC_GROUPS
                            + ": " + nodeId);
                }
            }
        }
        if (args.has("measures")) {
            JSONArray measures = args.optJSONArray("measures");
            if (measures == null) {
                col.node(nodeId, "", "group_by derive measures must be a JSON array when present: " + nodeId);
            } else {
                PlaybookGenericMeasures.validateMeasureSpecs(measures, keySet, "group_by", nodeId, col);
            }
        }
    }

    private static void validateAggregateDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "aggregate derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "aggregate derive missing rows: " + nodeId);
        }
        if (!args.has("measures")) {
            col.node(nodeId, "", "aggregate derive missing measures: " + nodeId);
        } else {
            JSONArray measures = args.optJSONArray("measures");
            if (measures == null) {
                col.node(nodeId, "", "aggregate derive measures must be a JSON array: " + nodeId);
            } else if (measures.length() == 0) {
                col.node(nodeId, "", "aggregate derive measures must be non-empty: " + nodeId);
            } else {
                PlaybookGenericMeasures.validateMeasureSpecs(measures, Set.of(), "aggregate", nodeId, col);
            }
        }
    }

    private static void validateJoinByKeyDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "join_by_key derive missing args: " + nodeId);
            return;
        }
        if (!args.has("left")) {
            col.node(nodeId, "", "join_by_key derive missing left: " + nodeId);
        }
        if (!args.has("right")) {
            col.node(nodeId, "", "join_by_key derive missing right: " + nodeId);
        }
        if (!args.has("leftKey")) {
            col.node(nodeId, "", "join_by_key derive missing leftKey: " + nodeId);
        } else {
            Object lk = args.get("leftKey");
            if (!(lk instanceof String)) {
                col.node(nodeId, "", "join_by_key derive leftKey must be a JSON string: " + nodeId);
            } else {
                String s = ((String) lk).trim();
                if (s.isEmpty()) {
                    col.node(nodeId, "", "join_by_key derive leftKey must be non-blank: " + nodeId);
                } else if (!PlaybookGenericPathGrammar.isValidDottedPath(s)) {
                    col.node(nodeId, "", "join_by_key derive leftKey invalid dotted path: " + nodeId);
                }
            }
        }
        if (!args.has("rightKey")) {
            col.node(nodeId, "", "join_by_key derive missing rightKey: " + nodeId);
        } else {
            Object rk = args.get("rightKey");
            if (!(rk instanceof String)) {
                col.node(nodeId, "", "join_by_key derive rightKey must be a JSON string: " + nodeId);
            } else {
                String s = ((String) rk).trim();
                if (s.isEmpty()) {
                    col.node(nodeId, "", "join_by_key derive rightKey must be non-blank: " + nodeId);
                } else if (!PlaybookGenericPathGrammar.isValidDottedPath(s)) {
                    col.node(nodeId, "", "join_by_key derive rightKey invalid dotted path: " + nodeId);
                }
            }
        }
        if (!args.has("joinType")) {
            col.node(nodeId, "", "join_by_key derive missing joinType: " + nodeId);
        } else {
            Object jt = args.get("joinType");
            if (!(jt instanceof String)) {
                col.node(nodeId, "", "join_by_key derive joinType must be a JSON string: " + nodeId);
            } else {
                String j = ((String) jt).trim().toLowerCase(Locale.ROOT);
                if (!("inner".equals(j) || "left".equals(j))) {
                    col.node(nodeId, "", "join_by_key derive joinType must be inner or left: " + nodeId);
                }
            }
        }
        if (args.has("rightPrefix")) {
            Object rp = args.get("rightPrefix");
            if (!(rp instanceof String)) {
                col.node(nodeId, "", "join_by_key derive rightPrefix must be a JSON string when present: " + nodeId);
            }
        }
        if (!args.has("maxRows")) {
            col.node(nodeId, "", "join_by_key derive missing maxRows: " + nodeId);
        } else {
            Object mgObj = args.opt("maxRows");
            if (!(mgObj instanceof Number)) {
                col.node(nodeId, "", "join_by_key derive maxRows must be a number: " + nodeId);
            } else {
                double mgd = ((Number) mgObj).doubleValue();
                if (mgd < 1 || mgd != Math.rint(mgd)) {
                    col.node(nodeId, "", "join_by_key derive maxRows must be an integer >= 1: " + nodeId);
                } else if (mgd > PlaybookGenericOpsConstants.MAX_GENERIC_JOIN_OUTPUT_ROWS) {
                    col.node(nodeId, "", "join_by_key derive maxRows exceeds cap " + PlaybookGenericOpsConstants.MAX_GENERIC_JOIN_OUTPUT_ROWS
                            + ": " + nodeId);
                }
            }
        }
    }

    private static void validateBuildTargetsDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "build_targets derive missing args: " + nodeId);
            return;
        }
        JSONObject sources = args.optJSONObject("sources");
        Set<String> sourceKeys = new HashSet<>();
        if (sources == null || sources.length() == 0) {
            col.node(nodeId, "", "build_targets derive sources must be a non-empty object: " + nodeId);
        } else {
            for (String srcName : sources.keySet()) {
                if (!NODE_ID.matcher(srcName).matches()) {
                    col.node(nodeId, "", "build_targets derive sources key invalid identifier: " + srcName + " (" + nodeId + ")");
                }
                sourceKeys.add(srcName);
            }
        }
        JSONObject template = args.optJSONObject("template");
        if (template == null || template.length() == 0) {
            col.node(nodeId, "", "build_targets derive template must be a non-empty object: " + nodeId);
        } else {
            validateBuildTargetsTemplateSubtree(template, sourceKeys, nodeId, "template", col);
        }
        if (!args.has("maxTargets")) {
            col.node(nodeId, "", "build_targets derive missing maxTargets: " + nodeId);
        } else {
            Object mt = args.opt("maxTargets");
            if (!(mt instanceof Number)) {
                col.node(nodeId, "", "build_targets derive maxTargets must be a number: " + nodeId);
            } else {
                double d = ((Number) mt).doubleValue();
                if (d < 1 || d != Math.rint(d)) {
                    col.node(nodeId, "", "build_targets derive maxTargets must be an integer >= 1: " + nodeId);
                } else if (d > PlaybookGenericOpsConstants.MAX_GENERIC_TARGETS) {
                    col.node(nodeId, "", "build_targets derive maxTargets exceeds cap "
                            + PlaybookGenericOpsConstants.MAX_GENERIC_TARGETS + ": " + nodeId);
                }
            }
        }
    }

    private static void validateBuildTargetsTemplateSubtree(JSONObject template, Set<String> sourceNames,
            String nodeId, String pathPrefix, PlaybookValidationCollector col) {
        for (String fieldKey : template.keySet()) {
            validateBuildTargetsTemplateValue(template.get(fieldKey), sourceNames, nodeId, pathPrefix + "." + fieldKey,
                    col);
        }
    }

    private static void validateBuildTargetsTemplateValue(Object raw, Set<String> sourceNames, String nodeId,
            String debugPath, PlaybookValidationCollector col) {
        if (raw == null) {
            return;
        }
        if (raw instanceof JSONArray) {
            col.node(nodeId, "", "build_targets derive template value at " + debugPath + " must not be an array: " + nodeId);
            return;
        }
        if (!(raw instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) raw;
        if (o.length() == 1 && o.has("$path")) {
            Object p = o.opt("$path");
            if (!(p instanceof String)) {
                col.node(nodeId, "", "build_targets derive $path must be a JSON string (" + nodeId + " at " + debugPath + ")");
                return;
            }
            validateBuildTargetsPathSpec((String) p, sourceNames, nodeId, debugPath, col);
            return;
        }
        if (o.length() == 1) {
            String only = o.keys().next();
            if (!only.isEmpty() && only.charAt(0) == '$') {
                if ("$input".equals(only) || "$var".equals(only) || "$ref".equals(only) || "$item".equals(only)) {
                    return;
                }
                col.node(nodeId, "", "build_targets derive unsupported template binding " + only + " at " + debugPath + ": "
                        + nodeId);
                return;
            }
        }
        for (String k : o.keySet()) {
            if (!k.isEmpty() && k.charAt(0) == '$') {
                col.node(nodeId, "", "build_targets derive template object must not mix $-bindings with siblings at " + debugPath
                        + ": " + nodeId);
                return;
            }
        }
        for (String k : o.keySet()) {
            validateBuildTargetsTemplateValue(o.get(k), sourceNames, nodeId, debugPath + "." + k, col);
        }
    }

    private static void validateBuildTargetsPathSpec(String spec, Set<String> sourceNames, String nodeId,
            String debugPath, PlaybookValidationCollector col) {
        String trimmed = spec.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0) {
            col.node(nodeId, "", "build_targets derive $path must be sourceName.dottedPath at " + debugPath + ": " + nodeId);
            return;
        }
        String src = trimmed.substring(0, dot).trim();
        String path = trimmed.substring(dot + 1).trim();
        if (src.isEmpty() || path.isEmpty() || !PlaybookGenericPathGrammar.isValidDottedPath(path)) {
            col.node(nodeId, "", "build_targets derive invalid $path at " + debugPath + ": " + nodeId);
            return;
        }
        if (!sourceNames.isEmpty() && !sourceNames.contains(src)) {
            col.node(nodeId, "", "build_targets derive $path source \"" + src + "\" is not declared in sources (" + nodeId + ")");
        }
    }

    private static void validateCollectGapsDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "collect_gaps derive missing args: " + nodeId);
            return;
        }
        JSONArray refs = args.optJSONArray("refs");
        if (refs == null || refs.length() == 0) {
            col.node(nodeId, "", "collect_gaps derive refs must be a non-empty array: " + nodeId);
        } else {
            for (int i = 0; i < refs.length(); i++) {
                Object re = refs.get(i);
                if (!(re instanceof String)) {
                    col.node(nodeId, "", "collect_gaps derive refs[" + i + "] must be a JSON string: " + nodeId);
                    continue;
                }
                String path = ((String) re).trim();
                if (path.isEmpty()) {
                    col.node(nodeId, "", "collect_gaps derive refs[" + i + "] must be non-blank: " + nodeId);
                    continue;
                }
                int dot = path.indexOf('.');
                if (dot <= 0) {
                    col.node(nodeId, "", "collect_gaps derive refs[" + i + "] must look like <nodeId>.<path>: " + nodeId);
                    continue;
                }
                String refNode = path.substring(0, dot);
                if (!NODE_ID.matcher(refNode).matches()) {
                    col.node(nodeId, "", "collect_gaps derive refs[" + i + "] has invalid node id: " + nodeId);
                } else if (doc != null && !doc.nodesById().containsKey(refNode)) {
                    col.node(nodeId, "", "collect_gaps derive refs[" + i + "] references unknown node \"" + refNode + "\": "
                            + nodeId);
                }
            }
        }
        if (!args.has("maxItems")) {
            col.node(nodeId, "", "collect_gaps derive missing maxItems: " + nodeId);
        } else {
            Object mi = args.opt("maxItems");
            if (!(mi instanceof Number)) {
                col.node(nodeId, "", "collect_gaps derive maxItems must be a number: " + nodeId);
            } else {
                double d = ((Number) mi).doubleValue();
                if (d < 1 || d != Math.rint(d)) {
                    col.node(nodeId, "", "collect_gaps derive maxItems must be an integer >= 1: " + nodeId);
                } else if (d > PlaybookGenericOpsConstants.MAX_COLLECT_GAPS_ITEMS) {
                    col.node(nodeId, "", "collect_gaps derive maxItems exceeds cap "
                            + PlaybookGenericOpsConstants.MAX_COLLECT_GAPS_ITEMS + ": " + nodeId);
                }
            }
        }
    }

    private static void validateFlattenFanOutRowsDerive(JSONObject node, String nodeId, PlaybookDocument doc,
            PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "flatten_fan_out_rows derive missing args: " + nodeId);
            return;
        }
        String ref = args.optString("fanOutNodeId", "").trim();
        if (ref.isEmpty()) {
            col.node(nodeId, "", "flatten_fan_out_rows derive missing fanOutNodeId: " + nodeId);
            return;
        }
        if (doc == null) {
            return;
        }
        JSONObject target = doc.nodesById().get(ref);
        if (target == null) {
            col.node(nodeId, "", "flatten_fan_out_rows derive references unknown node \"" + ref + "\": " + nodeId);
            return;
        }
        if (!"fan_out".equals(target.optString("kind", ""))) {
            col.node(nodeId, "", "flatten_fan_out_rows derive fanOutNodeId must name a fan_out node (" + nodeId + " -> " + ref
                    + ")");
            return;
        }
        JSONObject inner = target.optJSONObject("node");
        if (inner == null || !"tool_call".equals(inner.optString("kind", ""))) {
            col.node(nodeId, "", "flatten_fan_out_rows derive fan_out " + ref + " must wrap a tool_call child: " + nodeId);
        }
        if (args.has("injectFromItem")) {
            JSONArray inj = args.optJSONArray("injectFromItem");
            if (inj == null) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem must be a JSON array when present: " + nodeId);
            } else if (inj.length() > 0) {
                validateFlattenFanOutInjectSpecs(inj, nodeId, col);
            }
        }
    }

    private static void validateFlattenFanOutInjectSpecs(JSONArray inj, String nodeId, PlaybookValidationCollector col) {
        Set<String> seenAs = new HashSet<>();
        for (int i = 0; i < inj.length(); i++) {
            JSONObject spec = inj.optJSONObject(i);
            if (spec == null) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem[" + i + "] must be an object: " + nodeId);
                continue;
            }
            String from = spec.optString("from", "").trim();
            String as = spec.optString("as", "").trim();
            if (from.isEmpty() || as.isEmpty()) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem[" + i + "] requires non-blank from and as: "
                        + nodeId);
                continue;
            }
            if (!PlaybookGenericPathGrammar.isValidDottedPath(from)) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem[" + i + "] invalid from path: " + nodeId);
                continue;
            }
            if (!NODE_ID.matcher(as).matches()) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem[" + i + "] invalid as identifier: " + nodeId);
                continue;
            }
            if (PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem[" + i + "] as is reserved: " + as + " ("
                        + nodeId + ")");
                continue;
            }
            if (!seenAs.add(as)) {
                col.node(nodeId, "", "flatten_fan_out_rows derive injectFromItem duplicate as \"" + as + "\": " + nodeId);
            }
        }
    }

    private static void validateOrderByEntries(JSONArray orderBy, String nodeId, String opLabel, PlaybookValidationCollector col) {
        for (int i = 0; i < orderBy.length(); i++) {
            JSONObject spec = orderBy.optJSONObject(i);
            if (spec == null) {
                col.node(nodeId, "", opLabel + " derive orderBy[" + i + "] must be an object: " + nodeId);
                continue;
            }
            String field = spec.optString("field", "").trim();
            if (field.isEmpty()) {
                col.node(nodeId, "", opLabel + " derive orderBy[" + i + "] requires field: " + nodeId);
                continue;
            }
            if (!PlaybookGenericPathGrammar.isValidDottedPath(field)) {
                col.node(nodeId, "", opLabel + " derive orderBy[" + i + "] invalid dotted field path: " + nodeId);
            }
            String dir = spec.optString("direction", "asc").trim().toLowerCase(Locale.ROOT);
            if (!"asc".equals(dir) && !"desc".equals(dir)) {
                col.node(nodeId, "", opLabel + " derive orderBy[" + i + "] direction must be asc or desc: " + nodeId);
            }
        }
    }

    private static void validateProjectDerive(JSONObject node, String nodeId, PlaybookValidationCollector col) {
        JSONObject args = node.optJSONObject("args");
        if (args == null) {
            col.node(nodeId, "", "project derive missing args: " + nodeId);
            return;
        }
        if (!args.has("rows")) {
            col.node(nodeId, "", "project derive missing rows: " + nodeId);
        }
        JSONArray fields = args.optJSONArray("fields");
        if (fields == null || fields.length() == 0) {
            col.node(nodeId, "", "project derive fields must be a non-empty array: " + nodeId);
            return;
        }
        Set<String> seenAs = new HashSet<>();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f == null) {
                col.node(nodeId, "", "project derive fields[" + i + "] must be an object: " + nodeId);
                continue;
            }
            String from = f.optString("from", "").trim();
            String as = f.optString("as", "").trim();
            if (from.isEmpty() || as.isEmpty()) {
                col.node(nodeId, "", "project derive fields[" + i + "] requires non-blank from and as: " + nodeId);
                continue;
            }
            if (!PlaybookGenericPathGrammar.isValidDottedPath(from)) {
                col.node(nodeId, "", "project derive fields[" + i + "] from invalid dotted path: " + nodeId);
            }
            if (!NODE_ID.matcher(as).matches()) {
                col.node(nodeId, "", "project derive fields[" + i + "] as invalid identifier: " + nodeId);
            }
            if (PlaybookGenericOpsConstants.isReservedOutputField(as)) {
                col.node(nodeId, "", "project derive fields[" + i + "] as is reserved: " + as + " (" + nodeId + ")");
            }
            if (!seenAs.add(as)) {
                col.node(nodeId, "", "project derive duplicate as \"" + as + "\": " + nodeId);
            }
        }
    }

    private static void validateFanOut(JSONObject node, String nodeId, List<ToolDefinition> toolDefs,
            PlaybookValidationCollector col) {
        if (node.has("maxConcurrency")
                && node.optInt("maxConcurrency", PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY)
                        != PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY) {
            col.node(nodeId, "", "fan_out maxConcurrency must be " + PlaybookGenericOpsConstants.MAX_FAN_OUT_CONCURRENCY + ": "
                    + nodeId);
        }
        if (node.has("itemVar")) {
            String itemVar = node.optString("itemVar", "").trim();
            if (!PlaybookGenericPathGrammar.isSingleSegmentField(itemVar)) {
                col.node(nodeId, "", "fan_out itemVar must be a single identifier segment: " + nodeId);
            }
        }
        JSONObject child = node.optJSONObject("node");
        if (child == null) {
            col.node(nodeId, "", "fan_out missing child node: " + nodeId);
            return;
        }
        String childKind = child.optString("kind", "");
        if ("tool_call".equals(childKind)) {
            validateToolCall(child, nodeId + ".child", toolDefs, col);
        } else {
            col.node(nodeId, "", "fan_out child must be tool_call: " + nodeId);
        }
    }

    private static boolean isAcyclic(PlaybookDocument doc) {
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String id : doc.nodeIdsInOrder()) {
            if (!dfsAcyclic(id, doc, visiting, done)) {
                return false;
            }
        }
        return true;
    }

    private static void validateCondition(JSONObject node, String nodeId, PlaybookDocument doc, PlaybookValidationCollector col) {
        if (!node.has("if")) {
            col.node(nodeId, "", "condition missing if: " + nodeId);
        } else {
            validatePredicate(node.optJSONObject("if"), nodeId, col);
        }
        String then = node.optString("then", "");
        String els = node.optString("else", "");
        if (then.isEmpty() || els.isEmpty()) {
            col.node(nodeId, "", "condition requires then and else node ids: " + nodeId);
        }
        if (!doc.nodesById().containsKey(then)) {
            col.node(nodeId, "", "condition then references missing node: " + then);
        }
        if (!doc.nodesById().containsKey(els)) {
            col.node(nodeId, "", "condition else references missing node: " + els);
        }
    }

    private static void validatePredicate(JSONObject pred, String nodeId, PlaybookValidationCollector col) {
        if (pred == null || pred.length() == 0) {
            col.node(nodeId, "", "condition if must be a non-empty predicate object: " + nodeId);
            return;
        }
        if (pred.has("and")) {
            JSONArray arr = pred.optJSONArray("and");
            if (arr == null) {
                col.node(nodeId, "", "condition and must be an array: " + nodeId);
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    validatePredicate(arr.optJSONObject(i), nodeId, col);
                }
            }
            return;
        }
        if (pred.has("or")) {
            JSONArray arr = pred.optJSONArray("or");
            if (arr == null) {
                col.node(nodeId, "", "condition or must be an array: " + nodeId);
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    validatePredicate(arr.optJSONObject(i), nodeId, col);
                }
            }
            return;
        }
        if (pred.has("any")) {
            JSONArray arr = pred.optJSONArray("any");
            if (arr == null) {
                col.node(nodeId, "", "condition any must be an array: " + nodeId);
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    validatePredicate(arr.optJSONObject(i), nodeId, col);
                }
            }
            return;
        }
        if (pred.has("not")) {
            validatePredicate(pred.optJSONObject("not"), nodeId, col);
            return;
        }
        String op = pred.optString("op", "");
        if (op.isEmpty()) {
            col.node(nodeId, "", "condition predicate missing op: " + nodeId);
            return;
        }
        if (!CONDITION_OPS.contains(op)) {
            col.node(nodeId, "", "unsupported condition op for " + nodeId + ": " + op);
        }
    }

    private static void validateNoOrphanNodes(PlaybookDocument doc, PlaybookValidationCollector col) {
        Set<String> referenced = new HashSet<>();
        String finalId = doc.finalNodeId();
        for (String id : doc.nodeIdsInOrder()) {
            JSONObject node = doc.nodesById().get(id);
            if (node == null) {
                continue;
            }
            JSONArray deps = node.optJSONArray("dependsOn");
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    referenced.add(deps.optString(i, ""));
                }
            }
            if ("condition".equals(node.optString("kind", ""))) {
                referenced.add(node.optString("then", ""));
                referenced.add(node.optString("else", ""));
            }
            if ("llm_summary".equals(node.optString("kind", ""))) {
                JSONArray refs = node.optJSONArray("evidenceRefs");
                if (refs != null) {
                    for (int i = 0; i < refs.length(); i++) {
                        referenced.add(refs.optString(i, ""));
                    }
                }
            }
            collectExplicitReferencesFromNode(node, doc, referenced);
        }
        for (String id : doc.nodeIdsInOrder()) {
            if (id.equals(finalId)) {
                continue;
            }
            if (!referenced.contains(id)) {
                col.node(id, "", "orphan node (output never referenced): " + id);
            }
        }
    }

    private static void collectExplicitReferencesFromNode(
            JSONObject node, PlaybookDocument doc, Set<String> referenced) {
        JSONObject args = node.optJSONObject("args");
        if (args != null) {
            collectExpressionRefs(args, doc, referenced);
            for (String key : args.keySet()) {
                if (key.endsWith("NodeId")) {
                    addNodeIdIfKnown(args.optString(key, ""), doc, referenced);
                }
            }
        }
        Object items = node.opt("items");
        if (items instanceof JSONObject) {
            collectExpressionRefs((JSONObject) items, doc, referenced);
        }
    }

    private static void collectExpressionRefs(Object value, PlaybookDocument doc, Set<String> referenced) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            if (obj.has("$ref")) {
                addRefTarget(obj.optString("$ref", ""), doc, referenced);
            }
            if (obj.has("$table")) {
                addRefTarget(obj.optString("$table", ""), doc, referenced);
            }
            if (obj.has("$infotable")) {
                JSONObject spec = obj.optJSONObject("$infotable");
                if (spec != null) {
                    JSONObject rowsRef = spec.optJSONObject("rows");
                    if (rowsRef != null && rowsRef.has("$ref")) {
                        addRefTarget(rowsRef.optString("$ref", ""), doc, referenced);
                    }
                }
            }
            for (String key : obj.keySet()) {
                if ("$ref".equals(key) || "$table".equals(key) || "$infotable".equals(key)) {
                    continue;
                }
                collectExpressionRefs(obj.get(key), doc, referenced);
            }
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                collectExpressionRefs(arr.get(i), doc, referenced);
            }
        }
    }

    private static void addRefTarget(String ref, PlaybookDocument doc, Set<String> referenced) {
        if (ref == null || ref.isEmpty()) {
            return;
        }
        int dot = ref.indexOf('.');
        String nodeId = dot > 0 ? ref.substring(0, dot) : ref;
        addNodeIdIfKnown(nodeId, doc, referenced);
    }

    private static void addNodeIdIfKnown(String nodeId, PlaybookDocument doc, Set<String> referenced) {
        if (nodeId != null && !nodeId.isEmpty() && doc.nodesById().containsKey(nodeId)) {
            referenced.add(nodeId);
        }
    }

    private static boolean nodeDependsTransitivelyOn(PlaybookDocument doc, String startId, String ancestorId) {
        if (startId.equals(ancestorId)) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(startId);
        while (!q.isEmpty()) {
            String id = q.poll();
            if (!seen.add(id)) {
                continue;
            }
            JSONObject node = doc.nodesById().get(id);
            JSONArray deps = node != null ? node.optJSONArray("dependsOn") : null;
            if (deps == null) {
                continue;
            }
            for (int i = 0; i < deps.length(); i++) {
                String dep = deps.optString(i, "").trim();
                if (dep.isEmpty()) {
                    continue;
                }
                if (dep.equals(ancestorId)) {
                    return true;
                }
                q.add(dep);
            }
        }
        return false;
    }

    private static boolean dfsAcyclic(String id, PlaybookDocument doc, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) {
            return true;
        }
        if (!visiting.add(id)) {
            return false;
        }
        JSONObject node = doc.nodesById().get(id);
        if (node != null) {
            JSONArray deps = node.optJSONArray("dependsOn");
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    String dep = deps.optString(i, "");
                    if (!dfsAcyclic(dep, doc, visiting, done)) {
                        return false;
                    }
                }
            }
        }
        visiting.remove(id);
        done.add(id);
        return true;
    }
}
