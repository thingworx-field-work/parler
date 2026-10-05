package com.thingworx.things.agent.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Serializes additive G18 fields onto a stable outer error envelope ({@code status}/{@code code}/
 * {@code message} unchanged as primary identities). Applies the EG3 retry ledger for
 * {@link RecoveryActionType#REEXECUTE_SOURCE} on {@code CACHE_MISS}.
 */
public final class TypedToolErrorJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TypedToolErrorJson() {}

    /**
     * Build {@code CACHE_MISS} error JSON. Emits {@code reason=NOT_FOUND} only when
     * {@link CacheMissClassifier#isLiveNotFoundProven(String)} is true for {@code cacheId};
     * otherwise omits reason (malformed / foreign / never-seen / pre-restart handles).
     */
    public static String cacheMiss(String cacheId, String message) {
        boolean liveNotFound = CacheMissClassifier.isLiveNotFoundProven(cacheId);
        TypedToolError typed = ErrorRecoveryMapper.map("CACHE_MISS", message, liveNotFound);
        typed = applyLedgerForReexecute(typed);
        bindRetryBudgetKey(typed);
        return toJson(typed);
    }

    /** Serialize any mapped typed error (no ledger side effects unless caller already shaped it). */
    public static String toJson(TypedToolError error) {
        if (error == null) {
            return "{\"status\":\"error\",\"code\":\"INTERNAL\",\"message\":\"\"}";
        }
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", error.code());
            o.put("message", error.message() == null ? "" : error.message());
            o.put("category", error.category().name());
            if (error.reason() != null) {
                o.put("reason", error.reason());
            }
            o.put("retryable", error.retryable());
            if (error.retryBudgetKey() != null) {
                o.put("retryBudgetKey", error.retryBudgetKey());
            }
            o.put("evidenceStillUsable", error.evidenceStillUsable());
            ArrayNode actions = o.putArray("recoveryActions");
            for (RecoveryAction a : error.recoveryActions()) {
                ObjectNode ae = actions.addObject();
                ae.put("type", a.type().name());
                ObjectNode patch = ae.putObject("argumentPatch");
                for (Map.Entry<String, String> e : a.argumentPatch().entrySet()) {
                    patch.put(e.getKey(), e.getValue());
                }
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + error.code()
                    + "\",\"message\":\"serialization failed\"}";
        }
    }

    static TypedToolError applyLedgerForReexecute(TypedToolError typed) {
        if (typed == null) {
            return null;
        }
        boolean wantsReexecute = false;
        for (RecoveryAction a : typed.recoveryActions()) {
            if (a.type() == RecoveryActionType.REEXECUTE_SOURCE) {
                wantsReexecute = true;
                break;
            }
        }
        if (!wantsReexecute || typed.retryBudgetKey() == null) {
            return typed;
        }
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        if (inv == null || inv.invocationId() == null || inv.invocationId().isBlank()) {
            // No invocation context: emit advice without ledger (offline / early path).
            return typed;
        }
        RetryLedger.ensureBudget(inv.invocationId(), typed.retryBudgetKey(),
                RetryLedger.DEFAULT_SOURCE_QUERY_ALLOWANCE);
        if (RetryLedger.tryConsume(inv.invocationId(), typed.retryBudgetKey())) {
            return typed;
        }
        // Budget exhausted: terminal — drop REEXECUTE_SOURCE and clear retry path fields.
        List<RecoveryAction> kept = new ArrayList<>();
        for (RecoveryAction a : typed.recoveryActions()) {
            if (a.type() != RecoveryActionType.REEXECUTE_SOURCE) {
                kept.add(a);
            }
        }
        boolean stillRetryable = false;
        for (RecoveryAction a : kept) {
            if (a.type() == RecoveryActionType.RETRY_SAME_CALL
                    || a.type() == RecoveryActionType.REEXECUTE_SOURCE) {
                stillRetryable = true;
                break;
            }
        }
        String msg = typed.message() + " Retry budget for re-execute is exhausted for this invocation.";
        if (stillRetryable) {
            return typed.withRecoveryActions(kept).withMessage(msg);
        }
        return typed.withoutRetryPath(kept, msg);
    }

    private static void bindRetryBudgetKey(TypedToolError typed) {
        if (typed == null || typed.retryBudgetKey() == null || !typed.retryable()) {
            return;
        }
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        if (inv == null) {
            return;
        }
        if (typed.retryBudgetKey().equals(inv.retryBudgetKey())) {
            return;
        }
        AgentToolContext.setRunInvocationContext(inv.withRetryBudgetKey(typed.retryBudgetKey()));
    }
}
