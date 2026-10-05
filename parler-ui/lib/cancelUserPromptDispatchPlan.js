/**
 * Pure planning for {@code ParlerGateway.CancelUserPrompt} invoke outcomes
 * (see {@code docs/agent/turn-cancellation-control.md} §5).
 * @typedef {{ ok: false, reason: string } | { ok: true, status: string, alreadyRequested: boolean }} ParsedCancel
 */

/** @typedef {'schedule_fallback'|'schedule_retry'|'apply_already_terminal'|'apply_unsupported_local'|'clear_stopping_transient'} CancelDispatchKind */

/**
 * @typedef {object} PlanScheduleFallback
 * @property {'schedule_fallback'} kind
 */

/**
 * @typedef {object} PlanScheduleRetry
 * @property {'schedule_retry'} kind
 */

/**
 * @typedef {object} PlanApplyAlreadyTerminal
 * @property {'apply_already_terminal'} kind
 * @property {string} message
 */

/**
 * @typedef {object} PlanApplyUnsupportedLocal
 * @property {'apply_unsupported_local'} kind
 * @property {string} message
 */

/**
 * @typedef {object} PlanClearStoppingTransient
 * @property {'clear_stopping_transient'} kind
 * @property {string} message
 */

/** @typedef {PlanScheduleFallback|PlanScheduleRetry|PlanApplyAlreadyTerminal|PlanApplyUnsupportedLocal|PlanClearStoppingTransient} CancelDispatchPlan */

export const CANCEL_STOP_MSG_UNPARSEABLE =
  "Stop response could not be read. Try again.";
export const CANCEL_STOP_MSG_UNEXPECTED =
  "Stop request could not be completed. Try again.";
export const CANCEL_STOP_MSG_ALREADY_TERMINAL = "Turn already stopped.";
export const CANCEL_STOP_MSG_UNSUPPORTED =
  "Stop is not supported by this agent version.";

/**
 * @param {ParsedCancel} parsed from {@link parseCancelUserPromptResult}
 * @param {"initial" | "retry"} phase
 * @param {boolean} cancelNotActiveRetryUsed true after the one delayed retry has been scheduled
 * @returns {CancelDispatchPlan}
 */
export function planCancelUserPromptDispatch(parsed, phase, cancelNotActiveRetryUsed) {
  if (!parsed.ok) {
    return { kind: "clear_stopping_transient", message: CANCEL_STOP_MSG_UNPARSEABLE };
  }
  const { status } = parsed;
  switch (status) {
    case "accepted":
      return { kind: "schedule_fallback" };
    case "not_active":
      if (phase === "initial" && !cancelNotActiveRetryUsed) {
        return { kind: "schedule_retry" };
      }
      return { kind: "schedule_fallback" };
    case "already_terminal":
      return {
        kind: "apply_already_terminal",
        message: CANCEL_STOP_MSG_ALREADY_TERMINAL,
      };
    case "unsupported":
      return {
        kind: "apply_unsupported_local",
        message: CANCEL_STOP_MSG_UNSUPPORTED,
      };
    default:
      return { kind: "clear_stopping_transient", message: CANCEL_STOP_MSG_UNEXPECTED };
  }
}
