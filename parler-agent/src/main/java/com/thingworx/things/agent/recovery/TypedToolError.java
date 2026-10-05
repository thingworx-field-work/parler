package com.thingworx.things.agent.recovery;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Additive G18 typed descriptor around a stable outer {@code code}. Presentation {@code message}
 * is never a classifier.
 */
public final class TypedToolError {

    private final String code;
    private final ErrorCategory category;
    private final String reason;
    private final boolean retryable;
    private final String retryBudgetKey;
    private final List<RecoveryAction> recoveryActions;
    private final boolean evidenceStillUsable;
    private final String message;

    private TypedToolError(String code, ErrorCategory category, String reason, boolean retryable,
            String retryBudgetKey, List<RecoveryAction> recoveryActions, boolean evidenceStillUsable,
            String message) {
        this.code = Objects.requireNonNull(code, "code");
        this.category = Objects.requireNonNull(category, "category");
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.retryable = retryable;
        this.retryBudgetKey = retryBudgetKey == null || retryBudgetKey.isBlank() ? null : retryBudgetKey.trim();
        this.recoveryActions = recoveryActions == null || recoveryActions.isEmpty()
                ? List.of()
                : List.copyOf(recoveryActions);
        this.evidenceStillUsable = evidenceStillUsable;
        this.message = message != null ? message : "";
    }

    public static TypedToolError of(String code, ErrorCategory category, String reason, boolean retryable,
            String retryBudgetKey, List<RecoveryAction> recoveryActions, boolean evidenceStillUsable,
            String message) {
        return new TypedToolError(code, category, reason, retryable, retryBudgetKey, recoveryActions,
                evidenceStillUsable, message);
    }

    public String code() {
        return code;
    }

    public ErrorCategory category() {
        return category;
    }

    /** Proven reason token, or {@code null} when not proven. */
    public String reason() {
        return reason;
    }

    public boolean retryable() {
        return retryable;
    }

    public String retryBudgetKey() {
        return retryBudgetKey;
    }

    public List<RecoveryAction> recoveryActions() {
        return recoveryActions;
    }

    public boolean evidenceStillUsable() {
        return evidenceStillUsable;
    }

    public String message() {
        return message;
    }

    /** Copy with a replacement recovery-action list (preserves retryable / budget key). */
    public TypedToolError withRecoveryActions(List<RecoveryAction> actions) {
        return new TypedToolError(code, category, reason, retryable, retryBudgetKey, actions, evidenceStillUsable,
                message);
    }

    public TypedToolError withMessage(String newMessage) {
        return new TypedToolError(code, category, reason, retryable, retryBudgetKey, recoveryActions,
                evidenceStillUsable, newMessage);
    }

    /**
     * Terminal shaping when no retryable recovery actions remain: {@code retryable=false} and no
     * {@code retryBudgetKey}.
     */
    public TypedToolError withoutRetryPath(List<RecoveryAction> remainingActions, String newMessage) {
        return new TypedToolError(code, category, reason, false, null, remainingActions, evidenceStillUsable,
                newMessage);
    }
}

