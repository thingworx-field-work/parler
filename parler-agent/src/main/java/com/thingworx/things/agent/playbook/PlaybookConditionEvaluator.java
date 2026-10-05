package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/** Evaluates V1b {@code condition} predicates ({@code is_empty}, {@code eq}, {@code and}, …). */
public final class PlaybookConditionEvaluator {

    private PlaybookConditionEvaluator() {}

    public static boolean evaluate(JSONObject predicate, PlaybookRunContext ctx) throws PlaybookRunException {
        if (predicate == null || predicate.length() == 0) {
            return false;
        }
        if (predicate.has("and")) {
            JSONArray arr = predicate.optJSONArray("and");
            if (arr == null) {
                return false;
            }
            for (int i = 0; i < arr.length(); i++) {
                if (!evaluate(arr.optJSONObject(i), ctx)) {
                    return false;
                }
            }
            return true;
        }
        if (predicate.has("or")) {
            JSONArray arr = predicate.optJSONArray("or");
            if (arr == null) {
                return false;
            }
            for (int i = 0; i < arr.length(); i++) {
                if (evaluate(arr.optJSONObject(i), ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (predicate.has("any")) {
            JSONArray arr = predicate.optJSONArray("any");
            if (arr == null) {
                return false;
            }
            for (int i = 0; i < arr.length(); i++) {
                if (evaluate(arr.optJSONObject(i), ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (predicate.has("not")) {
            return !evaluate(predicate.optJSONObject("not"), ctx);
        }
        String op = predicate.optString("op", "");
        if (op.isEmpty()) {
            return false;
        }
        Object left = resolveUnaryOrBinaryLeft(predicate, ctx);
        Object right = resolveValue(predicate.opt("right"), ctx);
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
        throw new PlaybookRunException("unsupported condition op: " + op);
    }

    /** Unary ops accept {@code value} (canonical) or {@code left} (binary alias). */
    private static Object resolveUnaryOrBinaryLeft(JSONObject predicate, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (predicate.has("left")) {
            return resolveValue(predicate.opt("left"), ctx);
        }
        if (predicate.has("value")) {
            return resolveValue(predicate.opt("value"), ctx);
        }
        return null;
    }

    private static Object resolveValue(Object spec, PlaybookRunContext ctx) throws PlaybookRunException {
        if (spec instanceof JSONObject) {
            return PlaybookExpressionResolver.resolve(spec, ctx);
        }
        return spec;
    }
}
