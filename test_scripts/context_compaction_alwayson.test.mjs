import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readFileSync, existsSync } from "node:fs";
import { matchesRequest } from "./context_compaction/orchestrator.mjs";
import { judgeTurn } from "./context_compaction/judge.mjs";
import {
  validateChartBlock,
  validateTableBlock,
  terminalFromEvents,
} from "./context_compaction/wire_contract.mjs";
import { assertGoldenMatchesContract, CHART_REFERENCE_KEYS } from "./context_compaction/golden_contract.mjs";
import { wsUrlFromHttpBase } from "./context_compaction/live_transport.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.join(__dirname, "..");
const CODEC_DIR = path.join(REPO_ROOT, "parler-ui", "node_modules", "@xudesheng", "alwayson-js-codec");
const adapter = path.join(__dirname, "context_compaction_alwayson.mjs");
const NODE_DRIVER_ARGS = ["--experimental-wasm-modules"];
const golden = JSON.parse(
  readFileSync(path.join(__dirname, "context_compaction", "ten_turns_golden.json"), "utf8")
);

function runAdapter(command) {
  const proc = spawnSync("node", [...NODE_DRIVER_ARGS, adapter], {
    input: `${JSON.stringify(command)}\n`,
    encoding: "utf8",
    env: { ...process.env, PARLER_CC_FAKE: "1" },
  });
  assert.equal(proc.status, 0, proc.stderr || proc.stdout);
  return proc.stdout.trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
}

test("fake transport completes ten-turn suite with honest automatic yield", () => {
  const turns = Array.from({ length: 10 }, (_, i) => ({ id: i + 1, expandedPrompt: `turn ${i + 1}` }));
  const lines = runAdapter({ cmd: "run_suite", fake: true, turns, golden, clientWaitSeconds: 5 });
  const results = lines.filter((e) => e.kind === "turn_result");
  assert.equal(results.length, 10);
  const passCount = results.filter((r) => r.judgmentStatus === "pass").length;
  const insufficient = results.filter((r) => r.judgmentStatus === "insufficient_evidence");
  assert.equal(passCount, 8);
  assert.equal(insufficient.length, 2);
  assert.deepEqual(
    insufficient.map((r) => r.turnId).sort(),
    [6, 7]
  );
  assert.equal(lines.at(-1)?.kind, "run_complete");
});

test("fake transport applies bound manual judgments when fixture supplies them", () => {
  const turns = [
    { id: 6, expandedPrompt: "turn 6" },
    { id: 7, expandedPrompt: "turn 7" },
  ];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "manual_scored",
    turns,
    golden: { turns: { 6: golden.turns["6"], 7: golden.turns["7"] } },
    clientWaitSeconds: 5,
  });
  const results = lines.filter((e) => e.kind === "turn_result");
  assert.equal(results.length, 2);
  assert.ok(results.every((r) => r.judgmentStatus === "pass"));
});

test("stale terminal id does not complete the active turn early", () => {
  const turns = [{ id: 1, expandedPrompt: "one" }];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "stale_terminal",
    turns,
    golden: { turns: { 1: golden.turns["1"] } },
    clientWaitSeconds: 1,
    invokeTimeoutSeconds: 1,
  });
  const results = lines.filter((e) => e.kind === "turn_result");
  assert.equal(results.length, 1);
  assert.equal(results[0]?.terminal, "completion_unknown");
  assert.equal(lines.at(-1)?.stopped, true);
});

test("approval.required stops the run without advancing", () => {
  const turns = [
    { id: 1, expandedPrompt: "one" },
    { id: 2, expandedPrompt: "two" },
  ];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "approval",
    turns,
    golden,
    clientWaitSeconds: 1,
  });
  assert.equal(lines.filter((e) => e.kind === "turn_result").length, 1);
  assert.equal(lines.at(-1)?.stopped, true);
});

test("superseded terminal stops without second submission", () => {
  const turns = [
    { id: 1, expandedPrompt: "one" },
    { id: 2, expandedPrompt: "two" },
  ];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "superseded",
    turns,
    golden,
    clientWaitSeconds: 1,
  });
  assert.equal(lines.filter((e) => e.kind === "turn_result").length, 1);
  assert.equal(lines.at(-1)?.stopReason, "session.superseded");
});

test("reject submit stops without advancing", () => {
  const turns = [
    { id: 1, expandedPrompt: "one" },
    { id: 2, expandedPrompt: "two" },
  ];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "reject_submit",
    turns,
    golden,
    clientWaitSeconds: 1,
  });
  assert.equal(lines.filter((e) => e.kind === "turn_result").length, 1);
  assert.equal(lines.at(-1)?.stopped, true);
});

test("matchesRequest requires conversation identity when present", () => {
  assert.equal(
    matchesRequest(
      { type: "done", request_id: "rid-1", conversation_id: "conv-a" },
      "conv-a",
      "rid-1"
    ),
    true
  );
  assert.equal(
    matchesRequest(
      { type: "done", request_id: "rid-1", conversation_id: "conv-b" },
      "conv-a",
      "rid-1"
    ),
    false
  );
  assert.equal(
    matchesRequest({ type: "done", request_id: "rid-1" }, "conv-a", "rid-1"),
    false
  );
});

test("numeric chart mismatch fails judgment", () => {
  const turns = [{ id: 1, expandedPrompt: "one" }];
  const lines = runAdapter({
    cmd: "run_suite",
    fake: true,
    scenario: "numeric_mismatch",
    turns,
    golden: { turns: { 1: golden.turns["1"] } },
    clientWaitSeconds: 2,
  });
  const result = lines.find((e) => e.kind === "turn_result");
  assert.equal(result?.judgmentStatus, "fail");
  assert.equal(result?.judgmentDetails?.reason, "chart_series_y_mismatch");
});

test("judge rejects malformed chart and missing reference evidence", () => {
  const noRef = judgeTurn({ id: 1 }, { terminal: { kind: "done" }, wireEvents: [] }, { terminal: "done" });
  assert.equal(noRef.judgmentStatus, "insufficient_evidence");

  const wrongChart = judgeTurn(
    { id: 2 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "chart",
          request_id: "r1",
          conversation_id: "c1",
          chart: {
            kind: "pie",
            source: {
              sourceResolved: "cache_id",
              sourceColumns: ["status", "hours"],
              rowCount: 10,
              pointCount: 3,
              truncationApplied: false,
            },
            series: [{ x: ["A"], y: [1] }],
          },
        },
      ],
    },
    {
      terminal: "done",
      reference: {
        chart: {
          kind: "pie",
          source: {
            sourceResolved: "cache_id",
            sourceColumns: ["status", "hours"],
            rowCount: 10,
            pointCount: 3,
            truncationApplied: false,
          },
          series: [{ x: ["A"], y: [99] }],
        },
      },
    }
  );
  assert.equal(wrongChart.judgmentStatus, "fail");
});

test("committed golden references use only contract wire fields", () => {
  assertGoldenMatchesContract(golden);
});

test("manual judgment requires matching run identity and is not read from golden", () => {
  const comparisonGolden = golden.turns["3"];
  const withGoldenManual = {
    ...comparisonGolden,
    reference: {
      ...comparisonGolden.reference,
      comparison: { ...comparisonGolden.reference.comparison, suppliedJudgment: "pass" },
    },
  };
  const withoutEvidence = judgeTurn(
    { id: 3 },
    { terminal: { kind: "done" }, requestId: "r1", conversationId: "c1", wireEvents: [] },
    withGoldenManual
  );
  assert.notEqual(withoutEvidence.judgmentStatus, "pass");

  const withRunManual = judgeTurn(
    { id: 3 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [],
      manualJudgments: {
        comparison: { judgment: "pass", conversationId: "c1", requestId: "r1" },
      },
    },
    comparisonGolden
  );
  assert.equal(withRunManual.judgmentStatus, "pass");

  const wrongRun = judgeTurn(
    { id: 3 },
    {
      terminal: { kind: "done" },
      requestId: "other",
      conversationId: "c1",
      wireEvents: [],
      manualJudgments: {
        comparison: { judgment: "pass", conversationId: "c1", requestId: "r1" },
      },
    },
    comparisonGolden
  );
  assert.equal(wrongRun.judgmentStatus, "fail");
  assert.equal(wrongRun.details?.reason, "missing_comparison_text");

  for (const manual of [
    "pass",
    { judgment: "pass" },
    { judgment: "pass", conversationId: "new-c" },
    { judgment: "pass", requestId: "new-r" },
  ]) {
    const verdict = judgeTurn(
      { id: 3 },
      {
        terminal: { kind: "done" },
        requestId: "new-r",
        conversationId: "new-c",
        wireEvents: [],
        manualJudgments: { comparison: manual },
      },
      comparisonGolden
    );
    assert.notEqual(verdict.judgmentStatus, "pass", JSON.stringify(manual));
  }
});

test("wire validators reject null chart y, pie constraints, and malformed table rows", () => {
  assert.equal(validateChartBlock({ kind: "pie", series: [{ x: ["A"], y: [null] }] }).ok, false);
  assert.equal(validateChartBlock({ kind: "pie", series: [{ x: ["A"], y: [-1] }] }).ok, false);
  assert.equal(
    validateChartBlock({
      kind: "pie",
      series: [
        { x: ["A"], y: [1] },
        { x: ["B"], y: [2] },
      ],
    }).ok,
    false
  );
  assert.equal(
    validateTableBlock({
      kind: "entity-list",
      columns: [{ key: "a", label: "A", baseType: "STRING" }],
      rows: [null],
    }).ok,
    false
  );
  assert.equal(validateTableBlock({ kind: "entity-list", columns: [{ key: "a", label: "A", baseType: "STRING" }] }).ok, false);
});

test("wire done terminal is recognized on shipped frames", () => {
  const terminal = terminalFromEvents(
    [
      { type: "session.ack", request_id: "r1", conversation_id: "c1" },
      { type: "done", request_id: "r1", conversation_id: "c1" },
    ],
    "c1",
    "r1"
  );
  assert.equal(terminal?.kind, "done");
});

test("comparison judge accepts correct prose and fails wrong magnitudes", () => {
  const good = judgeTurn(
    { id: 3 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "Turn 1 Running=6 (60%), Idle=2 (20%), Down=1 (10%) with denominator 10. Turn 2 Running=5 (50%), Idle=3 (30%), Down=1 (10%) with denominator 10.",
        },
      ],
    },
    golden.turns["3"]
  );
  assert.equal(good.judgmentStatus, "pass");

  const bad = judgeTurn(
    { id: 3 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "Turn 1 Running=60 (60%) with denominator 10.",
        },
      ],
    },
    golden.turns["3"]
  );
  assert.equal(bad.judgmentStatus, "fail");
  assert.equal(bad.details?.reason, "comparison_hours_mismatch");

  const badPercent = judgeTurn(
    { id: 3 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "Turn 1 Running=6 (90%), Idle=2 (5%), Down=1 (5%) with denominator 10. Turn 2 Running=5 (90%), Idle=3 (5%), Down=1 (5%) with denominator 10.",
        },
      ],
    },
    golden.turns["3"]
  );
  assert.equal(badPercent.judgmentStatus, "fail");
  assert.equal(badPercent.details?.reason, "comparison_percent_mismatch");
});

test("window explanation prose alone is insufficient without manual judgment", () => {
  for (const text of [
    "UTC and America/New_York are not the same window; the local day shifts the underlying UTC boundaries.",
    "UTC and America/New_York do not use the same window; local boundaries shift.",
    "The claim that UTC and America/New_York use the same window is false; local boundaries shift.",
    "UTC and local window labels differ, not data; both use the same window.",
    "UTC and America/New_York use exactly the same window.",
  ]) {
    const verdict = judgeTurn(
      { id: 6 },
      {
        terminal: { kind: "done" },
        requestId: "r1",
        conversationId: "c1",
        wireEvents: [
          {
            type: "content.delta",
            request_id: "r1",
            conversation_id: "c1",
            text,
          },
        ],
      },
      golden.turns["6"]
    );
    assert.equal(verdict.judgmentStatus, "insufficient_evidence", text);
    assert.equal(verdict.details?.reason, "reference_family_not_extractable", text);
  }
});

test("window explanation passes with identity-bound manual judgment", () => {
  const verdict = judgeTurn(
    { id: 6 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "Turn 1 uses UTC calendar day boundaries; turn 5 re-resolves in America/New_York.",
        },
      ],
      manualJudgments: {
        explanation: { judgment: "pass", conversationId: "c1", requestId: "r1" },
      },
    },
    golden.turns["6"]
  );
  assert.equal(verdict.judgmentStatus, "pass");
});

test("statistics prose alone is insufficient without manual judgment", () => {
  const chart = golden.turns["7"].reference.chart;
  for (const text of [
    "CurrentDraw over the full window: average 12.1e3, minimum 11.8e3, maximum 12.4e3.",
    "CurrentDraw over the full window: average 12.1, minimum is 11.8, maximum is 12.4.",
    "CurrentDraw over the full window: average 12.1 × 10^3, minimum 11.8 × 10^3, maximum 12.4 × 10^3.",
    "The earlier values (average 12.1, minimum 11.8, maximum 12.4) were incorrect. The full-window statistics are average 121, minimum 118, maximum 124.",
  ]) {
    const verdict = judgeTurn(
      { id: 7 },
      {
        terminal: { kind: "done" },
        requestId: "r1",
        conversationId: "c1",
        wireEvents: [
          { type: "content.delta", request_id: "r1", conversation_id: "c1", text },
          { type: "chart", request_id: "r1", conversation_id: "c1", chart },
        ],
      },
      golden.turns["7"]
    );
    assert.equal(verdict.judgmentStatus, "insufficient_evidence", text);
    assert.deepEqual(verdict.details?.families, ["statistics"], text);
  }
});

test("statistics passes with structured observation or identity-bound manual judgment", () => {
  const structured = judgeTurn(
    { id: 7 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: JSON.stringify({ statistics: { average: 12.1, min: 11.8, max: 12.4 } }),
        },
        {
          type: "chart",
          request_id: "r1",
          conversation_id: "c1",
          chart: golden.turns["7"].reference.chart,
        },
      ],
    },
    golden.turns["7"]
  );
  assert.equal(structured.judgmentStatus, "pass");

  const manual = judgeTurn(
    { id: 7 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "CurrentDraw over the full window: average 12.1, minimum 11.8, maximum 12.4.",
        },
        {
          type: "chart",
          request_id: "r1",
          conversation_id: "c1",
          chart: golden.turns["7"].reference.chart,
        },
      ],
      manualJudgments: {
        statistics: { judgment: "pass", conversationId: "c1", requestId: "r1" },
      },
    },
    golden.turns["7"]
  );
  assert.equal(manual.judgmentStatus, "pass");
});

test("golden contract self-check uses exported allowlist sets", () => {
  CHART_REFERENCE_KEYS.add("bogusInventedField");
  try {
    assert.throws(() => assertGoldenMatchesContract(golden), /bogusInventedField/);
  } finally {
    CHART_REFERENCE_KEYS.delete("bogusInventedField");
  }
});

test("golden contract rejects sourceWindow on pie charts", () => {
  assert.throws(
    () =>
      assertGoldenMatchesContract({
        turns: {
          99: {
            reference: {
              chart: {
                kind: "pie",
                series: [{ x: ["a"], y: [1], sourceWindow: { thingName: "T" } }],
              },
            },
          },
        },
      }),
    /sourceWindow/
  );
});

test("codec loader resolves through parler-ui dependency boundary", async () => {
  if (!existsSync(CODEC_DIR)) {
    const { loadAlwaysOnJsCodec } = await import("./context_compaction/codec_loader.mjs");
    await assert.rejects(loadAlwaysOnJsCodec, (err) => {
      const message = err instanceof Error ? err.message : String(err);
      assert.match(message, /Cannot find module '@xudesheng\/alwayson-js-codec'/);
      return true;
    });
    return;
  }
  const proc = spawnSync(
    "node",
    [
      ...NODE_DRIVER_ARGS,
      "-e",
      "import { loadAlwaysOnJsCodec } from './test_scripts/context_compaction/codec_loader.mjs'; const codec = await loadAlwaysOnJsCodec(); if (typeof codec.stringFromInvokeResult !== 'function') process.exit(2);",
    ],
    { cwd: REPO_ROOT, encoding: "utf8", env: { ...process.env, NODE_OPTIONS: "" } }
  );
  assert.equal(proc.status, 0, proc.stderr || proc.stdout);
});

test("turn 7 requires extractable statistics separate from chart wire", () => {
  const chartOnly = judgeTurn(
    { id: 7 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "chart",
          request_id: "r1",
          conversation_id: "c1",
          chart: {
            kind: "line",
            source: {
              sourceResolved: "history_overlay",
              rowCount: 3,
              pointCount: 3,
              truncationApplied: false,
            },
            series: [
              {
                x: [
                  "2026-09-14T18:00:00.000Z",
                  "2026-09-14T18:15:00.000Z",
                  "2026-09-14T18:30:00.000Z",
                ],
                y: [12.1, 11.8, 12.4],
              },
            ],
          },
        },
      ],
    },
    golden.turns["7"]
  );
  assert.equal(chartOnly.judgmentStatus, "insufficient_evidence");
  assert.deepEqual(chartOnly.details?.families, ["statistics"]);
});

test("alert count matching rejects substring false positives", () => {
  const verdict = judgeTurn(
    { id: 8 },
    {
      terminal: { kind: "done" },
      requestId: "r1",
      conversationId: "c1",
      wireEvents: [
        {
          type: "content.delta",
          request_id: "r1",
          conversation_id: "c1",
          text: "Historical alerts: 20 warnings for SE.CellFab.Model.Workunit.ORD-Contacting-01.",
        },
      ],
    },
    golden.turns["8"]
  );
  assert.equal(verdict.judgmentStatus, "insufficient_evidence");
  assert.deepEqual(verdict.details?.families, ["alerts"]);
});

test("wsUrlFromHttpBase uses Thingworx WS path for server root", () => {
  assert.equal(wsUrlFromHttpBase("https://host:8443"), "wss://host:8443/Thingworx/WS");
  assert.equal(wsUrlFromHttpBase("https://host:8443/"), "wss://host:8443/Thingworx/WS");
  assert.equal(wsUrlFromHttpBase("http://localhost"), "ws://localhost:80/Thingworx/WS");
  assert.equal(
    wsUrlFromHttpBase("http://proxy.example/custom"),
    "ws://proxy.example:80/custom/Thingworx/WS"
  );
});
