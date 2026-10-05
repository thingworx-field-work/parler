/**
 * InfoTable JSON matching alwayson-js-codec serde (for build_service_request_message / build_success_response_message).
 * @see alwayson-js-codec infotable.rs, prim.rs (TwPrim tag = "kind", content = "value").
 */

/** @param {string} s */
function stringField(s) {
  return { kind: "String", value: ["String", s] };
}

/** @param {number} n */
function numberField(n) {
  return { kind: "Number", value: ["Number", Number(n)] };
}

/** @param {string} jsonUtf8 payload as Json baseType */
function jsonField(jsonUtf8) {
  return { kind: "String", value: ["Json", jsonUtf8] };
}

function emptyAspects() {
  return { inner: {} };
}

/**
 * @param {Record<string, { name: string, description: string, baseType: string, aspects?: object }>} entries
 */
function dataShapeFromEntries(entries) {
  return {
    name: null,
    entries,
  };
}

/** SubmitApprovalDecision on ParlerGateway (HITL); fields map to {@code approval.decision} in API_CONTRACT. */
export function submitApprovalDecisionInfoTable(
  pendingId,
  decision,
  requestId,
  conversationId,
  comment
) {
  const entries = {
    pendingId: {
      name: "pendingId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    decision: {
      name: "decision",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    requestId: {
      name: "requestId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    conversationId: {
      name: "conversationId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    comment: {
      name: "comment",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const c = comment != null ? String(comment) : "";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [
      {
        fields: [
          stringField(pendingId),
          stringField(decision),
          stringField(requestId),
          stringField(conversationId),
          stringField(c),
        ],
      },
    ],
  };
}

/** ParlerStreamToRemoteThing parameters (matches Java parameter order). */
export function parlerStreamInfoTable(
  message,
  remoteConversationThingName,
  systemPrompt = "",
  userTimezone = "",
  hostContext = ""
) {
  const entries = {
    message: {
      name: "message",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    systemPrompt: {
      name: "systemPrompt",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    remoteConversationThingName: {
      name: "remoteConversationThingName",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    userTimezone: {
      name: "userTimezone",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    hostContext: {
      name: "hostContext",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const sp = systemPrompt != null ? String(systemPrompt) : "";
  const tz = userTimezone != null ? String(userTimezone) : "";
  const hc = hostContext != null ? String(hostContext) : "";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [
      {
        fields: [
          stringField(message),
          stringField(sp),
          stringField(remoteConversationThingName),
          stringField(tz),
          stringField(hc),
        ],
      },
    ],
  };
}

/**
 * ParlerGateway.GetConversationHistoryJson — {@code maxItems} column (ThingWorx NUMBER at runtime).
 * DataShape {@code baseType} must be {@code "Number"} for alwayson-js-codec JSON (not {@code "NUMBER"}).
 * @param {number} maxItems
 */
export function getConversationHistoryJsonInfoTable(maxItems) {
  const cap = Math.max(1, Math.min(20_000, Math.floor(Number(maxItems) || 500)));
  const entries = {
    maxItems: {
      name: "maxItems",
      description: "",
      baseType: "Number",
      aspects: emptyAspects(),
    },
  };
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [{ fields: [numberField(cap)] }],
  };
}

/**
 * ParlerGateway.CancelUserPrompt — parameter order matches Java service definition.
 * @param {string} requestId
 * @param {string} agentThingName
 * @param {string} [reason] e.g. {@code user_stop}
 */
export function cancelUserPromptInfoTable(requestId, agentThingName, reason = "user_stop") {
  const entries = {
    requestId: {
      name: "requestId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    agentThingName: {
      name: "agentThingName",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    reason: {
      name: "reason",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const r = requestId != null ? String(requestId) : "";
  const a = agentThingName != null ? String(agentThingName) : "";
  const rs = reason != null ? String(reason) : "user_stop";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [
      {
        fields: [stringField(r), stringField(a), stringField(rs)],
      },
    ],
  };
}

/** SubmitUserPrompt on ParlerGateway (Thing name = conversationId). */
export function submitUserPromptInfoTable(
  message,
  agentThingName,
  systemPrompt = "",
  userTimezone = "",
  hostContext = ""
) {
  const entries = {
    message: {
      name: "message",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    agentThingName: {
      name: "agentThingName",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    systemPrompt: {
      name: "systemPrompt",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    userTimezone: {
      name: "userTimezone",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    hostContext: {
      name: "hostContext",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const sp = systemPrompt != null ? String(systemPrompt) : "";
  const tz = userTimezone != null ? String(userTimezone) : "";
  const hc = hostContext != null ? String(hostContext) : "";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [
      {
        fields: [
          stringField(message),
          stringField(agentThingName),
          stringField(sp),
          stringField(tz),
          stringField(hc),
        ],
      },
    ],
  };
}

/** ParlerGateway.SetConversationHistoryCutoff — ISO-8601 string column. */
export function setConversationHistoryCutoffInfoTable(cutoffAtIso) {
  const entries = {
    cutoffAtIso: {
      name: "cutoffAtIso",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const s = cutoffAtIso != null ? String(cutoffAtIso) : "";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [{ fields: [stringField(s)] }],
  };
}

/** ParlerGateway.RecordAssistantFeedback (or AgentThing with same parameter names). */
export function recordAssistantFeedbackInfoTable(
  conversationId,
  assistantMessageId,
  rating,
  requestId,
  previousRating
) {
  const entries = {
    conversationId: {
      name: "conversationId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    assistantMessageId: {
      name: "assistantMessageId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    rating: {
      name: "rating",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    requestId: {
      name: "requestId",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    previousRating: {
      name: "previousRating",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [
      {
        fields: [
          stringField(conversationId ?? ""),
          stringField(assistantMessageId ?? ""),
          stringField(rating ?? ""),
          stringField(requestId ?? ""),
          stringField(previousRating ?? ""),
        ],
      },
    ],
  };
}

/** Empty NamedVTQ Infotable (SynchronizeModelState success). */
export function emptyNamedVtqInfoTable() {
  const entries = {
    name: {
      name: "name",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    time: {
      name: "time",
      description: "",
      baseType: "DateTime",
      aspects: emptyAspects(),
    },
    value: {
      name: "value",
      description: "",
      baseType: "Variant",
      aspects: emptyAspects(),
    },
    quality: {
      name: "quality",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [],
  };
}

/** ParlerGateway.GetConnectionInfo — `agentThingName` + optional `widgetPackageVersion` (STRING columns). */
export function getConnectionInfoInfoTable(agentThingName, widgetPackageVersion = "") {
  const entries = {
    agentThingName: {
      name: "agentThingName",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
    widgetPackageVersion: {
      name: "widgetPackageVersion",
      description: "",
      baseType: "String",
      aspects: emptyAspects(),
    },
  };
  const a = agentThingName != null ? String(agentThingName) : "";
  const w = widgetPackageVersion != null ? String(widgetPackageVersion) : "";
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [{ fields: [stringField(a), stringField(w)] }],
  };
}

/**
 * GetMetadata JSON (single Json column), shaped like a standard AlwaysOn edge Thing's metadata where practical.
 * @param {string} thingName
 */
export function getMetadataSuccessInfoTable(thingName) {
  const meta = {
    isSystemObject: false,
    type: "Thing",
    name: thingName,
    description: "",
    implementedShapes: [],
    serviceDefinitions: {},
    propertyDefinitions: {},
    eventDefinitions: {},
  };
  const entries = {
    result: {
      name: "result",
      description: "",
      baseType: "Json",
      aspects: emptyAspects(),
    },
  };
  return {
    datashape: dataShapeFromEntries(entries),
    rows: [{ fields: [jsonField(JSON.stringify(meta))] }],
  };
}
