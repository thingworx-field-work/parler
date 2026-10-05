package com.thingworx.things.agent.playbook;

import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Row-level predicates for generic derive ops ({@code filter}, {@code pick_one}, …) — section 7 of
 * {@code docs/agent/playbook-generic-ops-foundation.md}. Leaves delegate comparisons to
 * {@link PlaybookPredicateValueOps}; composition mirrors {@link PlaybookConditionEvaluator}.
 * <p>
 * Each predicate object MUST have exactly one structural branch among {@code and}, {@code or},
 * {@code any}, {@code not}, or a non-blank leaf {@code op}. Composition arrays MUST be non-empty
 * JSONArray values whose elements are JSON objects. {@code not} MUST bind a JSON object.
 * Load-time validation and runtime evaluation share {@link #requireValidShape(JSONObject)} (single DFS)
 * so malformed composition payloads and invalid {@code not} bindings fail closed consistently without
 * validator/runtime drift (including malformed {@code not}, which must not fail open as {@code true}).
 */
public final class PlaybookRowPredicate {

    private static final Set<String> ROW_LEAF_OPS =
            Set.of("is_empty", "is_present", "eq", "ne", "gt", "gte", "lt", "lte");
    private static final Set<String> ROW_BINARY_OPS = Set.of("eq", "ne", "gt", "gte", "lt", "lte");

    private PlaybookRowPredicate() {}

    /**
     * Counts structural branches: presence of keys {@code and}, {@code or}, {@code any}, {@code not},
     * or a non-blank {@code op} string (each contributes at most one).
     */
    public static int structuralBranchCount(JSONObject pred) {
        if (pred == null) {
            return 0;
        }
        int n = 0;
        if (pred.has("and")) {
            n++;
        }
        if (pred.has("or")) {
            n++;
        }
        if (pred.has("any")) {
            n++;
        }
        if (pred.has("not")) {
            n++;
        }
        if (!pred.optString("op", "").trim().isEmpty()) {
            n++;
        }
        return n;
    }

    /**
     * Fail-closed shape check for the entire predicate tree (validator + {@link #evaluate}).
     * Mirrors the rules previously split between {@link PlaybookValidator} and runtime.
     */
    public static void requireValidShape(JSONObject pred) throws PlaybookRunException {
        if (pred == null || pred.length() == 0) {
            throw new PlaybookRunException("row predicate must be a non-empty object", "GENERIC_PREDICATE_INVALID");
        }
        if (structuralBranchCount(pred) != 1) {
            throw new PlaybookRunException(
                    "row predicate must have exactly one structural branch (and, or, any, not, or leaf op)",
                    "GENERIC_PREDICATE_INVALID");
        }
        if (pred.has("and")) {
            requireCompositionArray(pred, "and");
            return;
        }
        if (pred.has("or")) {
            requireCompositionArray(pred, "or");
            return;
        }
        if (pred.has("any")) {
            requireCompositionArray(pred, "any");
            return;
        }
        if (pred.has("not")) {
            Object raw = pred.opt("not");
            if (!(raw instanceof JSONObject)) {
                throw new PlaybookRunException("row predicate not must be a JSONObject", "GENERIC_PREDICATE_INVALID");
            }
            requireValidShape((JSONObject) raw);
            return;
        }
        requireValidLeaf(pred);
    }

    private static void requireCompositionArray(JSONObject pred, String key) throws PlaybookRunException {
        Object raw = pred.opt(key);
        if (!(raw instanceof JSONArray)) {
            throw new PlaybookRunException("row predicate " + key + " must be a JSONArray", "GENERIC_PREDICATE_INVALID");
        }
        JSONArray arr = (JSONArray) raw;
        if (arr.length() == 0) {
            throw new PlaybookRunException("row predicate " + key + " must be non-empty", "GENERIC_PREDICATE_INVALID");
        }
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (!(o instanceof JSONObject)) {
                throw new PlaybookRunException(
                        "row predicate " + key + "[" + i + "] must be a JSONObject", "GENERIC_PREDICATE_INVALID");
            }
            requireValidShape((JSONObject) o);
        }
    }

    private static void requireValidLeaf(JSONObject pred) throws PlaybookRunException {
        String op = pred.optString("op", "").trim();
        if (op.isEmpty()) {
            throw new PlaybookRunException("row predicate leaf missing op", "GENERIC_PREDICATE_INVALID");
        }
        if (!ROW_LEAF_OPS.contains(op)) {
            throw new PlaybookRunException("unsupported row predicate op: " + op, "GENERIC_PREDICATE_INVALID");
        }
        boolean hasField = pred.has("field") && !pred.optString("field", "").trim().isEmpty();
        boolean hasLeft = pred.has("left");
        boolean hasValue = pred.has("value");
        int n = (hasField ? 1 : 0) + (hasLeft ? 1 : 0) + (hasValue ? 1 : 0);
        if (n != 1) {
            throw new PlaybookRunException(
                    "row predicate leaf op " + op + " requires exactly one of field, left, value",
                    "GENERIC_PREDICATE_INVALID");
        }
        if (hasField) {
            String f = pred.optString("field", "").trim();
            if (!PlaybookGenericPathGrammar.isValidDottedPath(f)) {
                throw new PlaybookRunException("invalid field path: " + f, "GENERIC_PREDICATE_INVALID");
            }
        }
        if (ROW_BINARY_OPS.contains(op) && !pred.has("right")) {
            throw new PlaybookRunException("row predicate op " + op + " requires right", "GENERIC_PREDICATE_INVALID");
        }
    }

    public static boolean evaluate(JSONObject predicate, JSONObject row, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (predicate == null || predicate.length() == 0) {
            return false;
        }
        requireValidShape(predicate);
        return evaluateWithoutShapeCheck(predicate, row, ctx);
    }

    /** Same as {@link #evaluate} after {@link #requireValidShape} has been applied to the root (and shared across rows). */
    static boolean evaluateWithoutShapeCheck(JSONObject predicate, JSONObject row, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (predicate.has("and")) {
            JSONArray arr = predicate.getJSONArray("and");
            for (int i = 0; i < arr.length(); i++) {
                if (!evaluateWithoutShapeCheck(arr.getJSONObject(i), row, ctx)) {
                    return false;
                }
            }
            return true;
        }
        if (predicate.has("or")) {
            JSONArray arr = predicate.getJSONArray("or");
            for (int i = 0; i < arr.length(); i++) {
                if (evaluateWithoutShapeCheck(arr.getJSONObject(i), row, ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (predicate.has("any")) {
            JSONArray arr = predicate.getJSONArray("any");
            for (int i = 0; i < arr.length(); i++) {
                if (evaluateWithoutShapeCheck(arr.getJSONObject(i), row, ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (predicate.has("not")) {
            return !evaluateWithoutShapeCheck(predicate.getJSONObject("not"), row, ctx);
        }
        return evaluateLeaf(predicate, row, ctx);
    }

    private static boolean evaluateLeaf(JSONObject predicate, JSONObject row, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String op = predicate.optString("op", "");
        Object left = resolveLeafLeft(predicate, row, ctx);
        Object right = resolveMaybe(predicate.opt("right"), ctx);
        if ("is_empty".equals(op)) {
            return PlaybookPredicateValueOps.isEmpty(left);
        }
        if ("is_present".equals(op)) {
            return !PlaybookPredicateValueOps.isEmpty(left);
        }
        if ("eq".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) == 0;
        }
        if ("ne".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) != 0;
        }
        if ("gt".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) > 0;
        }
        if ("gte".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) >= 0;
        }
        if ("lt".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) < 0;
        }
        if ("lte".equals(op)) {
            return PlaybookPredicateValueOps.compare(left, right) <= 0;
        }
        throw new PlaybookRunException("unsupported row predicate op: " + op, "GENERIC_PREDICATE_INVALID");
    }

    private static Object resolveLeafLeft(JSONObject predicate, JSONObject row, PlaybookRunContext ctx)
            throws PlaybookRunException {
        String field = predicate.optString("field", "").trim();
        if (!field.isEmpty()) {
            return PlaybookJsonRowPath.getAtPath(row, field);
        }
        if (predicate.has("left")) {
            return resolveMaybe(predicate.opt("left"), ctx);
        }
        if (predicate.has("value")) {
            return resolveMaybe(predicate.opt("value"), ctx);
        }
        return null;
    }

    private static Object resolveMaybe(Object spec, PlaybookRunContext ctx) throws PlaybookRunException {
        if (spec instanceof JSONObject) {
            return PlaybookExpressionResolver.resolve(spec, ctx);
        }
        return spec;
    }
}
