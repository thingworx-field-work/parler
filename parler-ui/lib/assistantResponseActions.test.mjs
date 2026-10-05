import assert from "node:assert/strict";
import { test } from "node:test";
import {
  assistantRowActionsEligibility,
  buildCopyClipboardParts,
  buildPortablePrintCss,
  buildPrintDocumentHtml,
  DEFAULT_PORTABLE_PRINT_THEME,
  escapeHtmlText,
  findPriorUserPromptText,
  isAssistantRowActiveStreaming,
  isAssistantRowHitlPending,
  MINIMUM_PRINT_CSS,
  resolvePortablePrintTheme,
  rewriteClonedChartPaletteForPrint,
  stripNonPrintableControls,
} from "./assistantResponseActions.js";
import { expandTablesForPrint } from "./artifactPresentation.js";

test("escapeHtmlText escapes markup", () => {
  assert.equal(escapeHtmlText(`a<b>"c"`), "a&lt;b&gt;&quot;c&quot;");
});

test("findPriorUserPromptText skips tools and picks latest user", () => {
  const rows = [
    { kind: "user", text: "  hi  " },
    { kind: "tool", text: "ignored" },
    { kind: "assistant", requestId: "r1", markdown: "x", charts: [], tables: [] },
  ];
  assert.equal(findPriorUserPromptText(rows, 2), "hi");
});

test("findPriorUserPromptText truncates", () => {
  const long = "x".repeat(500);
  const rows = [{ kind: "user", text: long }, { kind: "assistant", requestId: "r", markdown: "m", charts: [], tables: [] }];
  const out = findPriorUserPromptText(rows, 1, 400);
  assert.equal(out.length, 401);
  assert.ok(out.endsWith("…"));
});

test("HITL pending suppresses actions", () => {
  const row = { kind: "assistant", requestId: "rid", markdown: "a", charts: [], tables: [] };
  const chat = {
    busy: false,
    activeRequestId: null,
    approvalGate: { requestId: "rid", summary: { title: "t", lines: [] }, actions: ["approve"] },
  };
  assert.equal(isAssistantRowHitlPending(chat, row), true);
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: false,
    print: false,
    showFooter: false,
    feedback: false,
    cutoff: false,
    info: false,
  });
});

test("active streaming suppresses actions", () => {
  const row = { kind: "assistant", requestId: "rid", markdown: "a", charts: [], tables: [] };
  const chat = { busy: true, activeRequestId: "rid", approvalGate: null };
  assert.equal(isAssistantRowActiveStreaming(chat, row), true);
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: false,
    print: false,
    showFooter: false,
    feedback: false,
    cutoff: false,
    info: false,
  });
});

test("print-only when chart without markdown", () => {
  const row = {
    kind: "assistant",
    requestId: "r",
    markdown: "   ",
    charts: [{ kind: "line", series: [] }],
    tables: [],
  };
  const chat = { busy: false, activeRequestId: null, approvalGate: null };
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: false,
    print: true,
    showFooter: true,
    feedback: false,
    cutoff: false,
    info: false,
  });
});

test("copy + print when markdown and chart", () => {
  const row = {
    kind: "assistant",
    requestId: "r",
    markdown: "hello",
    charts: [{ kind: "line", series: [] }],
    tables: [],
  };
  const chat = { busy: false, activeRequestId: null, approvalGate: null };
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: true,
    print: true,
    showFooter: true,
    feedback: false,
    cutoff: false,
    info: false,
  });
});

test("feedback and cutoff when stable ids present", () => {
  const row = {
    kind: "assistant",
    requestId: "amid-1",
    assistantMessageId: "amid-1",
    completedAt: "2026-05-21T12:00:00.000Z",
    markdown: "x",
    charts: [],
    tables: [],
  };
  const chat = { busy: false, activeRequestId: null, approvalGate: null };
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: true,
    print: true,
    showFooter: true,
    feedback: true,
    cutoff: true,
    info: true,
  });
});

test("cutoff is available with completedAt even without assistantMessageId", () => {
  const row = {
    kind: "assistant",
    requestId: "req-cancel",
    completedAt: "2026-05-21T12:00:00.000Z",
    markdown: "",
    charts: [],
    tables: [],
  };
  const chat = { busy: false, activeRequestId: null, approvalGate: null };
  assert.deepEqual(assistantRowActionsEligibility(row, chat), {
    copy: false,
    print: false,
    showFooter: true,
    feedback: false,
    cutoff: true,
    info: false,
  });
});

test("buildCopyClipboardParts wraps html", () => {
  const { html, plain } = buildCopyClipboardParts("# t", "<h1>t</h1>");
  assert.equal(plain, "# t");
  assert.ok(html.includes("parler-copy-html"));
  assert.ok(html.includes("<h1>t</h1>"));
  assert.doesNotMatch(html, /--parler-|data-parler-palette-role/);
});

test("buildPrintDocumentHtml includes optional blocks", () => {
  const html = buildPrintDocumentHtml("<p>body</p>", {
    userPromptText: "Q?",
    requestId: "req",
    conversationId: "conv",
  });
  assert.ok(html.includes("User asked"));
  assert.ok(html.includes("Q?"));
  assert.ok(html.includes("<p>body</p>"));
  assert.ok(html.includes("req"));
  assert.ok(html.includes("conv"));
  assert.ok(html.includes(MINIMUM_PRINT_CSS.slice(0, 30)));
});

test("portable print resolution honors only print roles and uses portable fallbacks", () => {
  const values = {
    "--parler-effective-print-text": "#010203",
    "--parler-effective-print-accent": "linear-gradient(red, blue)",
    "--parler-print-accent": "#123456",
    "--parler-effective-print-chart-series-1": "#234567",
    "--parler-effective-chart-series-1": "#screen1",
    "--parler-theme-chart-series-2": "#screen2",
    "--parler-effective-font-family": '"Screen Theme", sans-serif',
    "--parler-effective-font-size": "22px",
    "--parler-effective-line-height": "2",
    "--parler-effective-markdown-image-radius": "20px",
    "--parler-font-family": '"Print Sans", sans-serif',
    "--parler-font-size": "16px",
    "--parler-line-height": "1.75",
    "--parler-markdown-image-radius": "5px",
  };
  const theme = resolvePortablePrintTheme(
    { getPropertyValue: (name) => values[name] ?? "" },
    {
      validateColor: (value) => /^#[0-9a-f]{6}$/i.test(value) ? value : null,
    }
  );

  assert.equal(theme.palette.text, "#010203");
  assert.equal(theme.palette.accent, "#123456");
  assert.equal(theme.palette.series[0], "#234567");
  assert.equal(theme.palette.series[1], "#117864");
  assert.equal(theme.palette.series.length, 24);
  assert.deepEqual(theme.typography, {
    fontFamily: '"Print Sans", sans-serif',
    fontSize: "16px",
    lineHeight: "1.75",
    imageRadius: "5px",
  });
});

test("portable print theme carries the default chart card typography", () => {
  assert.deepEqual(DEFAULT_PORTABLE_PRINT_THEME.chartType, {
    fontFamily: '"Open Sans", "Segoe UI", system-ui, sans-serif',
    titleSize: 14,
    titleWeight: "600",
    legendSize: 12,
  });
  const css = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);
  assert.ok(css.includes("font-size: 14px;\n  font-weight: 600;"));
  assert.ok(css.includes("font-size: 12px;"));
});

test("portable print CSS serializes concrete palette and typography values", () => {
  const theme = structuredClone(DEFAULT_PORTABLE_PRINT_THEME);
  theme.palette.canvas = "#fefefe";
  theme.palette.text = "#101010";
  theme.palette.muted = "#505050";
  theme.palette.border = "#707070";
  theme.palette.surfaceSubtle = "#eeeeee";
  theme.palette.accent = "#004488";
  theme.typography.fontFamily = '"Print Test", sans-serif';
  theme.typography.fontSize = "15px";
  theme.typography.lineHeight = "1.6";
  theme.typography.imageRadius = "6px";

  const css = buildPortablePrintCss(theme);
  assert.ok(css.includes("background: #fefefe;"));
  assert.ok(css.includes("color: #101010;"));
  assert.ok(css.includes("color: #505050;"));
  assert.ok(css.includes("border: 1px solid #707070;"));
  assert.ok(css.includes("background: #eeeeee;"));
  assert.ok(css.includes("color: #004488;"));
  assert.ok(css.includes('font-family: "Print Test", sans-serif;'));
  assert.ok(css.includes("border-radius: 6px;"));

  const html = buildPrintDocumentHtml("<h2>body</h2>", { printTheme: theme });
  assert.ok(html.includes(css));
  assert.ok(html.includes("<h2>body</h2>"));
});

test("print chart rewriting recolors the clone without mutating the screen SVG", async () => {
  const { JSDOM } = await import("jsdom");
  const dom = new JSDOM(`
    <div id="screen"><svg>
      <path id="line" fill="none" stroke="#screen01" data-parler-palette-role="series" data-parler-series-slot="0"></path>
      <circle id="point" fill="#screen02" stroke="#screen03" data-parler-palette-role="series point-outline" data-parler-series-slot="1"></circle>
      <text id="title" fill="#screen04" data-parler-palette-role="title"></text>
      <text id="legend" fill="#screen05" data-parler-palette-role="legend"></text>
      <text id="tick" fill="#screen06" data-parler-palette-role="tick"></text>
      <text id="axis-label" fill="#screen07" data-parler-palette-role="axis-label"></text>
      <line id="axis" stroke="#screen08" data-parler-palette-role="axis"></line>
      <line id="grid" stroke="#screen09" data-parler-palette-role="grid"></line>
      <line id="danger-line" stroke="#screen10" data-parler-palette-role="reference-danger"></line>
      <text id="warning-label" fill="#screen11" data-parler-palette-role="reference-warning"></text>
      <line id="target-line" stroke="#screen12" data-parler-palette-role="reference-target"></line>
    </svg></div>
  `);
  const screen = dom.window.document.getElementById("screen");
  const clone = screen.cloneNode(true);
  const theme = structuredClone(DEFAULT_PORTABLE_PRINT_THEME);
  const count = rewriteClonedChartPaletteForPrint(clone, theme);

  assert.equal(count, 11);
  assert.equal(clone.querySelector("#line").getAttribute("fill"), "none");
  assert.equal(clone.querySelector("#line").getAttribute("stroke"), theme.palette.series[0]);
  assert.equal(clone.querySelector("#point").getAttribute("fill"), theme.palette.series[1]);
  assert.equal(clone.querySelector("#point").getAttribute("stroke"), theme.palette.outline);
  assert.equal(clone.querySelector("#title").getAttribute("fill"), theme.palette.text);
  for (const id of ["legend", "tick", "axis-label"]) {
    assert.equal(clone.querySelector(`#${id}`).getAttribute("fill"), theme.palette.muted);
  }
  for (const id of ["axis", "grid"]) {
    assert.equal(clone.querySelector(`#${id}`).getAttribute("stroke"), theme.palette.border);
  }
  assert.equal(clone.querySelector("#danger-line").getAttribute("stroke"), theme.palette.reference.danger);
  assert.equal(clone.querySelector("#warning-label").getAttribute("fill"), theme.palette.reference.warning);
  assert.equal(clone.querySelector("#target-line").getAttribute("stroke"), theme.palette.reference.target);
  assert.doesNotMatch(clone.innerHTML, /#screen/i);
  assert.equal(screen.querySelector("#line").getAttribute("stroke"), "#screen01");
  assert.equal(screen.querySelector("#point").getAttribute("fill"), "#screen02");
});

test("print chart rewriting rejects unowned roles and invalid slots", async () => {
  const { JSDOM } = await import("jsdom");
  const unknown = new JSDOM('<div id="r"><path data-parler-palette-role="future"></path></div>')
    .window.document.getElementById("r");
  assert.throws(
    () => rewriteClonedChartPaletteForPrint(unknown, DEFAULT_PORTABLE_PRINT_THEME),
    /Unknown print chart palette role/
  );
  const badSlot = new JSDOM('<div id="r"><path stroke="red" data-parler-palette-role="series" data-parler-series-slot="24"></path></div>')
    .window.document.getElementById("r");
  assert.throws(
    () => rewriteClonedChartPaletteForPrint(badSlot, DEFAULT_PORTABLE_PRINT_THEME),
    /Invalid print chart series slot/
  );
});

test("expandTablesForPrint removes collapsed state from clone", async () => {
  const { JSDOM } = await import("jsdom");
  const dom = new JSDOM(
    `<div id="r"><div class="parler-data-table-wrap parler-data-table-wrap--collapsed"><button class="parler-data-table-disclosure" aria-expanded="false"></button><div class="parler-data-table-panel" hidden></div></div></div>`
  );
  const root = dom.window.document.getElementById("r");
  expandTablesForPrint(root);
  const wrap = root.querySelector(".parler-data-table-wrap");
  assert.ok(wrap);
  assert.equal(wrap.classList.contains("parler-data-table-wrap--collapsed"), false);
  assert.equal(
    root.querySelector(".parler-data-table-disclosure")?.getAttribute("aria-expanded"),
    "true"
  );
  assert.equal(
    root.querySelector(".parler-data-table-panel")?.hasAttribute("hidden"),
    false
  );
});

test("stripNonPrintableControls removes markers", async () => {
  const { JSDOM } = await import("jsdom");
  const dom = new JSDOM(
    `<div id="r"><aside class="task-state-panel">x</aside><div class="working">w</div><div data-no-print>f</div><p>keep</p></div>`
  );
  const root = dom.window.document.getElementById("r");
  stripNonPrintableControls(root);
  assert.equal(root.querySelectorAll("aside.task-state-panel").length, 0);
  assert.equal(root.querySelectorAll(".working").length, 0);
  assert.equal(root.querySelectorAll("[data-no-print]").length, 0);
  assert.ok(root.textContent.includes("keep"));
});
