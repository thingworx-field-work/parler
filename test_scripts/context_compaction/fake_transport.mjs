/**
 * Injectable fake wire transport sharing orchestrator code paths with live mode.
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const EVIDENCE = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "fake_evidence.json"), "utf8")
).turns;

const MANUAL_SCORED_JUDGMENTS = {
  "6": { explanation: { judgment: "pass" } },
  "7": { statistics: { judgment: "pass" } },
};

/**
 * @param {object} [opts]
 * @param {'happy'|'manual_scored'|'early_ack'|'stale_terminal'|'approval'|'reject_submit'|'invoke_hang'|'superseded'|'wrong_chart'|'numeric_mismatch'} [opts.scenario]
 */
export function createFakeTransport(opts = {}) {
  const scenario = opts.scenario ?? "happy";
  /** @type {((event: any) => void) | null} */
  let listener = null;

  return {
    agentThing: "FakeAgent",
    gatewayThing: "FakeGateway",
    onWireEvent(fn) {
      listener = fn;
      return () => {
        if (listener === fn) listener = null;
      };
    },
    async submitTurn({ turn, conversationId }) {
      if (scenario === "reject_submit") {
        throw new Error("submit_rejected");
      }
      if (scenario === "invoke_hang") {
        return new Promise(() => {});
      }

      const serverRequestId = randomUUID();
      const emit = (event) => listener?.(event);
      const turnKey = String(turn?.id ?? "");
      let evidence = EVIDENCE[turnKey] ?? {};
      if (scenario === "manual_scored" && MANUAL_SCORED_JUDGMENTS[turnKey]) {
        evidence = { ...evidence, manualJudgments: MANUAL_SCORED_JUDGMENTS[turnKey] };
      }

      if (scenario === "early_ack") {
        emit(wireFrame("session.ack", serverRequestId, conversationId));
      }
      if (scenario === "stale_terminal") {
        emit(wireFrame("done", `stale-${serverRequestId}`, conversationId, { assistant_message_id: "stale" }));
        return { requestId: serverRequestId };
      }
      if (scenario === "approval") {
        emit(wireFrame("approval.required", serverRequestId, conversationId, { pending_id: "p1" }));
        return { requestId: serverRequestId };
      }
      if (scenario === "superseded") {
        queueMicrotask(() => {
          emit(wireFrame("session.superseded", serverRequestId, conversationId, { message: "superseded" }));
        });
        return { requestId: serverRequestId };
      }

      queueMicrotask(() => {
        emitWireEvidence({
          emit,
          evidence,
          turnId: turnKey,
          requestId: serverRequestId,
          conversationId,
          scenario,
        });
      });
      return {
        requestId: serverRequestId,
        manualJudgments: bindManualJudgments(evidence.manualJudgments, serverRequestId, conversationId),
      };
    },
    async pollTerminal() {
      return null;
    },
    async close() {},
  };
}

/** @param {Record<string, any> | undefined} raw @param {string} requestId @param {string} conversationId */
function bindManualJudgments(raw, requestId, conversationId) {
  if (!raw || typeof raw !== "object") return undefined;
  /** @type {Record<string, any>} */
  const out = {};
  for (const [family, entry] of Object.entries(raw)) {
    if (entry && typeof entry === "object" && (entry.judgment === "pass" || entry.judgment === "fail")) {
      out[family] = { ...entry, conversationId, requestId };
    }
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** @param {object} opts */
function emitWireEvidence({ emit, evidence, turnId, requestId, conversationId, scenario }) {
  emit(wireFrame("session.ack", requestId, conversationId));
  if (evidence.chart) {
    const chart = structuredClone(evidence.chart);
    if (scenario === "wrong_chart") {
      chart.kind = "invalid-kind";
    } else if (scenario === "numeric_mismatch" && Array.isArray(chart.series) && chart.series[0]) {
      chart.series[0].y = chart.series[0].y.map((v) => Number(v) * 100);
    }
    emit(wireFrame("chart", requestId, conversationId, { chart }));
  }
  if (evidence.table) {
    emit(wireFrame("table", requestId, conversationId, { table: structuredClone(evidence.table) }));
  }
  if (evidence.contentDelta) {
    emit(wireFrame("content.delta", requestId, conversationId, { text: evidence.contentDelta }));
  }
  if (evidence.comparison && evidence.contentDelta) {
    /* comparison values are carried in content.delta for this harness */
  }
  emit(
    wireFrame("done", requestId, conversationId, {
      assistant_message_id: `msg-${turnId}`,
      completed_at: new Date().toISOString(),
    })
  );
}

/** @param {string} type @param {string} requestId @param {string} conversationId @param {Record<string, any>} [extra] */
function wireFrame(type, requestId, conversationId, extra = {}) {
  return {
    type,
    request_id: requestId,
    conversation_id: conversationId,
    ...extra,
  };
}
