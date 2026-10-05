/**
 * InfoTable payloads for Parler AlwaysOn invokes (codec JSON shape for build_service_request_message).
 * @see docs/architecture/agent-alwayson.md
 */

import {
  cancelUserPromptInfoTable,
  getConversationHistoryJsonInfoTable,
  getConnectionInfoInfoTable,
  parlerStreamInfoTable,
  recordAssistantFeedbackInfoTable,
  setConversationHistoryCutoffInfoTable,
  submitApprovalDecisionInfoTable,
  submitUserPromptInfoTable,
} from "./parlerInfotableJson.js";

/** @param {string} [userTimezone] IANA from browser (e.g. Intl…resolvedOptions().timeZone) */
export function buildParlerStreamParams(
  message,
  remoteConversationThingName,
  userTimezone = "",
  hostContext = ""
) {
  return parlerStreamInfoTable(
    message,
    remoteConversationThingName,
    "",
    userTimezone,
    hostContext
  );
}

/** @param {string} [userTimezone] IANA from browser @param {string} [hostContext] raw HostScopeJson UTF-8 text */
export function buildSubmitUserPromptParams(
  message,
  agentThingName,
  userTimezone = "",
  hostContext = ""
) {
  return submitUserPromptInfoTable(message, agentThingName, "", userTimezone, hostContext);
}

/** ParlerGateway.GetConversationHistoryJson (max stream rows cap; default 500 in widget if invalid). */
export function buildGetConversationHistoryJsonParams(maxItems) {
  return getConversationHistoryJsonInfoTable(maxItems);
}

/**
 * @param {string} pendingId
 * @param {string} decision approve | cancel | reject_with_comment
 * @param {string} requestId
 * @param {string} conversationId
 * @param {string} [comment] used with reject_with_comment
 */
/** ParlerGateway.SubmitApprovalDecision (same invoke locus as SubmitUserPrompt). */
export function buildSubmitApprovalDecisionParams(
  pendingId,
  decision,
  requestId,
  conversationId,
  comment = ""
) {
  return submitApprovalDecisionInfoTable(
    pendingId,
    decision,
    requestId,
    conversationId,
    comment
  );
}

/** ParlerGateway.GetConnectionInfo (post-bind version handshake). */
export function buildGetConnectionInfoParams(agentThingName, widgetPackageVersion = "") {
  return getConnectionInfoInfoTable(agentThingName, widgetPackageVersion);
}

/** ParlerGateway.CancelUserPrompt (same Gateway entity as SubmitUserPrompt — conversationId). */
export function buildCancelUserPromptParams(requestId, agentThingName, reason = "user_stop") {
  return cancelUserPromptInfoTable(requestId, agentThingName, reason);
}

/** ParlerGateway.SetConversationHistoryCutoff */
export function buildSetConversationHistoryCutoffParams(cutoffAtIso) {
  return setConversationHistoryCutoffInfoTable(cutoffAtIso);
}

/** ParlerGateway.RecordAssistantFeedback */
export function buildRecordAssistantFeedbackParams(
  conversationId,
  assistantMessageId,
  rating,
  requestId,
  previousRating
) {
  return recordAssistantFeedbackInfoTable(
    conversationId,
    assistantMessageId,
    rating,
    requestId,
    previousRating
  );
}
