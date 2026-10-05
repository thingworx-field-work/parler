/**
 * M1a AlwaysOn adapter: line-at-a-time stdin commands, JSONL stdout events.
 */

import readline from "node:readline";
import { stdin as input, stdout as output } from "node:process";
import { TurnOrchestrator } from "./context_compaction/orchestrator.mjs";
import { createFakeTransport } from "./context_compaction/fake_transport.mjs";
import { createLiveTransport } from "./context_compaction/live_transport.mjs";
import { judgeTurn } from "./context_compaction/judge.mjs";

/** @param {Record<string, unknown>} obj */
function emit(obj) {
  output.write(`${JSON.stringify(obj)}\n`);
}

/** @param {Record<string, any> | undefined} golden */
function indexGolden(golden) {
  if (!golden) return {};
  if (golden.turns && typeof golden.turns === "object") return golden.turns;
  return golden;
}

/** @param {any} cmd */
async function handleCommand(cmd) {
  const fake = cmd.fake === true || process.env.PARLER_CC_FAKE === "1";
  const goldenByTurnId = indexGolden(cmd.golden);
  const turns = cmd.turns ?? [];

  const transport = fake
    ? createFakeTransport({ scenario: cmd.scenario ?? "happy" })
    : await createLiveTransport({ ...cmd, emit });

  const orchestrator = new TurnOrchestrator({
    transport,
    emit,
    judgeTurn,
    clientWaitMs: Number(cmd.clientWaitSeconds ?? 600) * 1000,
    invokeTimeoutMs: Number(cmd.invokeTimeoutSeconds ?? 120) * 1000,
    turnGapMs: Number(cmd.turnGapSeconds ?? 0) * 1000,
  });

  if (cmd.suiteConfig) {
    emit({ kind: "run_config", ...cmd.suiteConfig });
  }

  await orchestrator.runSuite({
    turns,
    goldenByTurnId,
    conversationId: transport.conversationId ?? cmd.gatewayThing ?? cmd.conversationId ?? "cc-eval-conv",
    turnGapMs: Number(cmd.turnGapSeconds ?? 0) * 1000,
  });
  await transport.close?.();
}

const rl = readline.createInterface({ input, crlfDelay: Infinity });
rl.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  handleCommand(JSON.parse(trimmed))
    .then(() => process.exit(0))
    .catch((err) => {
      emit({ kind: "error", message: err instanceof Error ? err.message : String(err) });
      process.exit(1);
    });
});
