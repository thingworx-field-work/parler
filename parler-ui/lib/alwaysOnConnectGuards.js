export const AUTH_FAILURE_RETRY_SUPPRESS_MS = 30_000;

export function makeBindContext(conversationId, agentThingName, rawUrl) {
  return {
    conversationId: String(conversationId || "").trim(),
    agentThingName: String(agentThingName || "").trim(),
    rawUrl: String(rawUrl || "").trim(),
  };
}

export function sameBindContext(a, b) {
  return (
    !!a &&
    !!b &&
    a.conversationId === b.conversationId &&
    a.agentThingName === b.agentThingName &&
    a.rawUrl === b.rawUrl
  );
}

export function isLikelyAlwaysOnAuthFailure(message) {
  const text = String(message || "").toLowerCase();
  return (
    text.includes("application key") ||
    text.includes("app key") ||
    text.includes("auth") ||
    text.includes("unauthorized") ||
    text.includes("credentials")
  );
}

export function shouldSuppressRepeatedAuthFailure(recent, appKey, nowMs, windowMs = AUTH_FAILURE_RETRY_SUPPRESS_MS) {
  if (!recent || !appKey) return false;
  if (recent.appKey !== appKey) return false;
  const ageMs = nowMs - recent.atMs;
  return ageMs >= 0 && ageMs < windowMs;
}
