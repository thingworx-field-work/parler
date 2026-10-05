/**
 * HITL decision-submit sequencing for the open approval gate
 * (see `CONTRACTS/UI_CLIENT_PROTOCOL.md` rule 10b and `docs/ui/chart-from-json.md` §8.1).
 *
 * One decision per pending: the caller's buttons are disabled while a submit is in flight or
 * accepted, and this module refuses a second uplink so keyboard or programmatic triggers cannot
 * slip past that. A failed uplink is not a turn terminal — the server may already be running the
 * decision — so the failure surfaces on the card and re-enables the buttons instead of ending the
 * session.
 */

import { buildSubmitApprovalDecisionParams } from "./alwaysOnInvokeParams.js";
import { EntityTypes } from "./twEntityTypes.js";

export const APPROVAL_SUBMIT_MSG_SUBMITTING = "Submitting decision…";

/**
 * Worded to hold for every decision: an accepted uplink means the server took the decision, not
 * that an approved operation started or succeeded. Cancel and reject run no business operation.
 */
export const APPROVAL_SUBMIT_MSG_ACCEPTED = "Decision accepted. Waiting for result.";

export const APPROVAL_SUBMIT_MSG_UNCONFIRMED =
  "The decision was not confirmed. Try again.";

/**
 * @param {import('./types.js').ApprovalGateState | null | undefined} gate
 * @returns {boolean} true while a decision for this gate is in flight or already accepted
 */
export function isApprovalDecisionPending(gate) {
  return !!gate && (gate.submitState ?? "idle") !== "idle";
}

/**
 * @param {import('./types.js').ApprovalGateState | null | undefined} gate
 * @returns {string | null} status line for the approval card, or null when there is nothing to say
 */
export function approvalSubmitStatusText(gate) {
  if (!gate) return null;
  const state = gate.submitState ?? "idle";
  if (state === "submitting") return APPROVAL_SUBMIT_MSG_SUBMITTING;
  if (state === "submitted") return APPROVAL_SUBMIT_MSG_ACCEPTED;
  return null;
}

/**
 * Submits one approval decision on the bound ParlerGateway and drives the client-only
 * `approval.decisionSubmitting` / `decisionAccepted` / `decisionSubmitFailed` substate. Every
 * dispatched event carries the `pendingId` captured at call time, so a late callback from a
 * superseded gate cannot touch the current one.
 *
 * @param {object} args
 * @param {import('./types.js').ApprovalGateState | null | undefined} args.gate open gate
 * @param {{ invokeService: (req: object, cb: (err: unknown) => void) => void } | null | undefined} args.client AlwaysOn client
 * @param {string} args.conversationId bound conversation Thing name
 * @param {boolean} args.connected transport is connected
 * @param {'approve'|'cancel'|'reject_with_comment'} args.decision
 * @param {string} args.comment required for `reject_with_comment`; empty otherwise
 * @param {(evt: import('./types.js').UiEvent) => void} args.dispatch applies a UI event
 * @param {(msg: string, detail?: unknown) => void} [args.onTransportWarn]
 * @returns {'submitted'|'skipped_no_gate'|'skipped_already_pending'|'skipped_no_transport'}
 */
export function submitApprovalDecision({
  gate,
  client,
  conversationId,
  connected,
  decision,
  comment,
  dispatch,
  onTransportWarn,
}) {
  if (!gate) return "skipped_no_gate";
  if (isApprovalDecisionPending(gate)) return "skipped_already_pending";
  if (!client || !connected || !conversationId) {
    onTransportWarn?.("SubmitApprovalDecision aborted: transport or conversationId missing");
    return "skipped_no_transport";
  }

  const pendingId = gate.pendingId;
  const params = buildSubmitApprovalDecisionParams(
    pendingId,
    decision,
    gate.requestId,
    gate.conversationId,
    comment ?? ""
  );

  dispatch({ type: "approval.decisionSubmitting", pendingId });

  client.invokeService(
    {
      entityName: conversationId,
      serviceName: "SubmitApprovalDecision",
      entityType: EntityTypes.Things,
      parameters: params,
    },
    (err) => {
      if (err) {
        const msg = err instanceof Error ? err.message : String(err);
        onTransportWarn?.("SubmitApprovalDecision failed", msg);
        dispatch({
          type: "approval.decisionSubmitFailed",
          pendingId,
          message: msg || APPROVAL_SUBMIT_MSG_UNCONFIRMED,
        });
        return;
      }
      dispatch({ type: "approval.decisionAccepted", pendingId });
    }
  );
  return "submitted";
}
