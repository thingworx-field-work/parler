/**
 * Shared serial turn orchestration for context-compaction eval (§11.4).
 */

import { matchesRequest, terminalFromEvents } from "./wire_contract.mjs";

/** Terminal kinds that stop the run without advancing to the next turn. */
export const RUN_STOP_TERMINALS = new Set([
  "approval.required",
  "error",
  "session.cancelled",
  "session.superseded",
  "completion_unknown",
]);

/**
 * @param {object} opts
 * @param {{ submitTurn: Function, close?: Function }} opts.transport
 * @param {(obj: Record<string, unknown>) => void} opts.emit
 * @param {(turn: any, evidence: any, golden?: any) => { judgmentStatus: string, details?: unknown }} opts.judgeTurn
 * @param {number} opts.clientWaitMs
 * @param {number} [opts.invokeTimeoutMs]
 */
export class TurnOrchestrator {
  constructor({ transport, emit, judgeTurn, clientWaitMs, invokeTimeoutMs, turnGapMs = 0 }) {
    this.transport = transport;
    this.emit = emit;
    this.judgeTurn = judgeTurn;
    this.clientWaitMs = clientWaitMs;
    this.invokeTimeoutMs = invokeTimeoutMs ?? Math.min(clientWaitMs, 120_000);
    this.turnGapMs = turnGapMs;
    this.activeRequestId = null;
    this.conversationId = "";
  }

  /**
   * @param {object} opts
   * @param {any[]} opts.turns
   * @param {Record<string, any>} [opts.goldenByTurnId]
   * @param {string} [opts.conversationId]
   * @param {number} [opts.turnGapMs]
   */
  async runSuite({ turns, goldenByTurnId = {}, conversationId = "cc-eval-conv", turnGapMs }) {
    this.conversationId = conversationId;
    const gapMs = Number(turnGapMs ?? this.turnGapMs ?? 0);
    /** @type {any[]} */
    const turnResults = [];
    for (const turn of turns) {
      if (this.activeRequestId) {
        throw new Error(`serial violation: ${this.activeRequestId} still active`);
      }
      const result = await this.runOneTurn(
        turn,
        goldenByTurnId[String(turn.id)] ?? goldenByTurnId[turn.id],
        turnResults
      );
      if (result?.stoppedRun) {
        return turnResults;
      }
      const effectiveGapMs = Number(turn.turnGapMs ?? gapMs);
      if (effectiveGapMs > 0) {
        await sleep(effectiveGapMs);
      }
    }
    this.emit({ kind: "run_complete", conversationId: this.conversationId, turnCount: turns.length, turnResults });
    return turnResults;
  }

  /** @param {any} turn @param {any} golden @param {any[]} turnResults */
  async runOneTurn(turn, golden, turnResults) {
    /** @type {any[]} */
    const wireEvents = [];
    /** @type {any[]} */
    let preSubmitWire = [];
    let submitRequestId = null;

    const captureWire = (event) => {
      const activeId = submitRequestId ?? "";
      this.emit({
        kind: "wire_event",
        turnId: turn.id,
        requestId: activeId || null,
        conversationId: this.conversationId,
        event,
      });
      if (!submitRequestId) {
        preSubmitWire.push(event);
        return;
      }
      if (matchesRequest(event, this.conversationId, submitRequestId)) {
        wireEvents.push(event);
      }
    };

    const flushPreSubmitWire = () => {
      if (!submitRequestId) return;
      for (const event of preSubmitWire) {
        if (matchesRequest(event, this.conversationId, submitRequestId)) {
          wireEvents.push(event);
        }
      }
      preSubmitWire = [];
    };

    const unsubscribe = this.transport.onWireEvent(captureWire);
    this.activeRequestId = "pending";
    const turnStartedAt = Date.now();

    try {
      const submit = await withTimeout(
        this.transport.submitTurn({
          turn,
          conversationId: this.conversationId,
          agentThing: this.transport.agentThing,
          gatewayThing: this.transport.gatewayThing,
        }),
        this.invokeTimeoutMs,
        "submit_turn_timeout"
      );
      submitRequestId = String(submit?.requestId ?? "").trim();
      if (!submitRequestId) {
        throw new Error("missing_request_id");
      }
      this.activeRequestId = submitRequestId;
      flushPreSubmitWire();

      const terminal = await waitForTerminal({
        wireEvents,
        transport: this.transport,
        conversationId: this.conversationId,
        requestId: submitRequestId,
        timeoutMs: this.clientWaitMs,
      });
      const judgment = this.judgeTurn(
        turn,
        {
          wireEvents,
          terminal,
          requestId: submitRequestId,
          conversationId: this.conversationId,
          manualJudgments: submit?.manualJudgments,
        },
        golden
      );
      const turnResult = {
        kind: "turn_result",
        turnId: turn.id,
        requestId: submitRequestId,
        conversationId: this.conversationId,
        terminal: terminal.kind,
        completion: terminal.completion,
        judgmentStatus: judgment.judgmentStatus,
        judgmentDetails: judgment.details ?? null,
        clientElapsedMs: Date.now() - turnStartedAt,
      };
      this.emit(turnResult);
      turnResults.push(turnResult);
      if (RUN_STOP_TERMINALS.has(terminal.kind)) {
        this.emit({
          kind: "run_complete",
          conversationId: this.conversationId,
          turnCount: turnResults.length,
          turnResults,
          stopped: true,
          stopReason: terminal.kind,
        });
        return { stoppedRun: true };
      }
      return turnResult;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      const turnResult = {
        kind: "turn_result",
        turnId: turn.id,
        requestId: submitRequestId,
        conversationId: this.conversationId,
        terminal: "completion_unknown",
        completion: "completion_unknown",
        judgmentStatus: "insufficient_evidence",
        judgmentDetails: { reason: "submit_or_invoke_failure", message },
        error: message,
        clientElapsedMs: Date.now() - turnStartedAt,
      };
      this.emit(turnResult);
      turnResults.push(turnResult);
      this.emit({
        kind: "run_complete",
        conversationId: this.conversationId,
        turnCount: turnResults.length,
        turnResults,
        stopped: true,
        stopReason: "completion_unknown",
      });
      return { stoppedRun: true };
    } finally {
      unsubscribe?.();
      this.activeRequestId = null;
    }
  }
}

export { matchesRequest };

/** @param {Promise<any>} promise @param {number} timeoutMs @param {string} label */
async function withTimeout(promise, timeoutMs, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** @param {object} opts */
async function waitForTerminal({ wireEvents, transport, conversationId, requestId, timeoutMs }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const fromBuffer = terminalFromEvents(wireEvents, conversationId, requestId);
    if (fromBuffer) return fromBuffer;
    if (typeof transport.pollTerminal === "function") {
      const polled = await transport.pollTerminal(requestId, conversationId);
      if (polled) return polled;
    }
    await sleep(20);
  }
  return { kind: "completion_unknown", completion: "completion_unknown" };
}

/** @param {number} ms */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
