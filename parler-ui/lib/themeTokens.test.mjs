import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import {
  THEME_ROLE_SOURCES,
  THEME_SOURCES,
  THEME_TOKENS,
} from "./themeTokens.mjs";

const css = fs.readFileSync("styles/parler-ui.css", "utf8");
const chartThemeSource = fs.readFileSync("components/chart-theme.js", "utf8");
const chartDrawSource = fs.readFileSync("components/chart-draw.js", "utf8");
const printSource = fs.readFileSync("lib/assistantResponseActions.js", "utf8");
const customerSpec = fs.readFileSync("../docs/ui/theme-api.md", "utf8");
const canonicalDesign = fs.readFileSync("../docs/ui/theme.md", "utf8");

const specificationRows = new Map(
  customerSpec
    .split("\n")
    .filter((line) => line.startsWith("| `--parler-") && line.endsWith("|"))
    .map((line) => {
      const cells = line.split("|").slice(1, -1).map((cell) => cell.trim());
      return [
        cells[0].slice(1, -1),
        {
          defaultText: cells[2],
          acceptedValue:
            cells[3] ?? (cells[0].includes("--parler-chart-") ? "Solid CSS color" : undefined),
        },
      ];
    })
);

test("implemented registry has unique public, source, and effective names", () => {
  assert.equal(THEME_TOKENS.length, 151);
  assert.equal(new Set(THEME_TOKENS.map((entry) => entry.publicName)).size, 151);
  assert.equal(new Set(THEME_TOKENS.map((entry) => entry.effectiveName)).size, 151);

  const sourced = THEME_TOKENS.filter((entry) => entry.expression != null);
  assert.equal(sourced.length, 78);
  const chartTokens = THEME_TOKENS.filter((entry) =>
    entry.publicName.startsWith("--parler-chart-")
  );
  assert.equal(chartTokens.length, 61);
  assert.equal(
    chartTokens.filter((entry) => entry.expression != null).length,
    35
  );
  const printTokens = THEME_TOKENS.filter((entry) =>
    entry.publicName.startsWith("--parler-print-")
  );
  assert.equal(printTokens.length, 34);
  assert.equal(printTokens.filter((entry) => entry.expression != null).length, 0);
  assert.equal(
    new Set(sourced.map((entry) => entry.themeName)).size,
    sourced.length
  );
  for (const entry of sourced) {
    assert.match(entry.themeName, /^--parler-theme-/);
    assert.match(entry.expression, /^[a-z0-9-]+$/);
  }

  const warning = THEME_TOKENS.find(
    (entry) => entry.publicName === "--parler-color-warning"
  );
  assert.equal(warning.expression, null);
  assert.equal(warning.themeName, null);
  const userMessage = THEME_TOKENS.find(
    (entry) => entry.publicName === "--parler-color-user-message"
  );
  assert.equal(userMessage.expression, "global-color-bg-secondary");
  assert.equal(userMessage.fallback, "#f2f4f5");
  const onUserMessage = THEME_TOKENS.find(
    (entry) => entry.publicName === "--parler-color-on-user-message"
  );
  assert.equal(onUserMessage.expression, "global-color-text-body");
  assert.equal(onUserMessage.fallback, "#232b2d");
  assert.deepEqual(THEME_ROLE_SOURCES, [
    {
      themeName: "--parler-theme-notice-info",
      expression: "global-color-core-info",
    },
    {
      themeName: "--parler-theme-chart-point-outline-secondary",
      expression: "e-chart-pie-slice-stroke-color",
    },
  ]);
  assert.equal(THEME_SOURCES.length, 80);
  assert.equal(
    new Set(THEME_SOURCES.map((entry) => entry.themeName)).size,
    THEME_SOURCES.length
  );
});

test("every implemented public token is specified and consumed by its renderer", () => {
  for (const entry of THEME_TOKENS) {
    const specification = specificationRows.get(entry.publicName);
    assert.ok(specification, entry.publicName);
    assert.ok(
      specification.defaultText.includes(entry.fallback),
      `${entry.publicName} fallback must match the customer specification`
    );
    assert.equal(
      specification.acceptedValue,
      entry.acceptedValue,
      `${entry.publicName} constraint must match the customer specification`
    );
    assert.ok(css.includes(`${entry.effectiveName}:`), entry.effectiveName);
    if (entry.publicName.startsWith("--parler-print-")) {
      assert.ok(printSource.includes("resolvePortablePrintTheme"));
      assert.ok(printSource.includes("rewriteClonedChartPaletteForPrint"));
    } else if (entry.publicName.startsWith("--parler-chart-")) {
      assert.ok(chartThemeSource.includes("resolveChartRenderObject"));
      assert.ok(chartDrawSource.includes("data-parler-palette-role"));
    } else {
      assert.ok(
        css.includes(`var(${entry.effectiveName})`),
        `${entry.effectiveName} must be consumed by production CSS`
      );
    }
  }
});

test("mashup bindings preserve public then Theme source then fallback precedence", () => {
  for (const entry of THEME_TOKENS) {
    const expected = entry.publicName === "--parler-chart-point-outline"
      ? "var(--parler-chart-point-outline, var(--parler-theme-chart-point-outline, var(--parler-theme-chart-point-outline-secondary, #ffffff)))"
      : entry.themeName == null
      ? `var(${entry.publicName}, ${entry.fallback})`
      : `var(${entry.publicName}, var(${entry.themeName}, ${entry.fallback}))`;
    assert.ok(
      css.includes(`${entry.effectiveName}: ${expected};`),
      `${entry.effectiveName} must use the reviewed precedence`
    );
  }
});

test("canonical live capture stays synchronized with the complete bridge manifest", () => {
  const capture = canonicalDesign.match(
    /const tokenNames = \[([\s\S]*?)\n  \];/
  );
  assert.ok(capture);
  const capturedNames = [...capture[1].matchAll(/"(--parler-theme-[^"]+)"/g)]
    .map((match) => match[1]);
  assert.deepEqual(
    capturedNames,
    THEME_SOURCES.map((entry) => entry.themeName)
  );
});

test("both Theme modes define the complete implemented effective-token set", () => {
  for (const mode of ["mashup", "parler-dark"]) {
    const match = css.match(
      new RegExp(`parler-ui\\[theme-mode="${mode}"\\] \\{([\\s\\S]*?)\\n\\}`)
    );
    assert.ok(match, mode);
    for (const entry of THEME_TOKENS) {
      assert.ok(
        match[1].includes(`${entry.effectiveName}:`),
        `${mode} must define ${entry.effectiveName}`
      );
    }
  }
});

test("message and Markdown tokens own their complete production surfaces", () => {
  for (const selector of [
    '[part="markdown-content"] h1',
    '[part="markdown-content"] a',
    '[part="markdown-content"] a:visited',
    '[part="markdown-content"] a:hover',
    '[part="markdown-content"] a:focus-visible',
    '[part="markdown-content"] strong',
    '[part="markdown-content"] em',
    '[part="markdown-content"] s',
    '[part="markdown-content"] code',
    '[part="markdown-content"] pre',
    '[part="markdown-content"] pre code',
    '[part="markdown-content"] blockquote',
    '[part="markdown-content"] ul',
    '[part="markdown-content"] ol',
    '[part="markdown-content"] table',
    '[part="markdown-content"] hr',
    '[part="markdown-content"] img',
  ]) {
    assert.ok(css.includes(`parler-ui ${selector}`), selector);
  }

  assert.ok(!css.includes("background: var(--bubble-user);"));
  assert.ok(
    css.includes("max-width: min(100%, var(--parler-effective-message-max-width));")
  );
  assert.equal(
    (css.match(/--parler-effective-message-max-width: var\(--parler-message-max-width, min\(92%, 960px\)\);/g) ?? []).length,
    2
  );
  assert.ok(
    css.includes("background: var(--parler-effective-color-user-message);")
  );
  assert.ok(
    css.includes(
      'parler-ui[theme-mode="mashup"] .user-bubble {\n  border-color: var(--parler-effective-color-accent);\n}'
    )
  );
  assert.ok(
    css.includes("background: var(--parler-effective-color-assistant-message);")
  );
});

test("container, scrollbar, focus, and selection use their effective tokens", () => {
  for (const declaration of [
    "max-width: min(100%, var(--parler-effective-content-max-width));",
    "scrollbar-color: var(--parler-effective-color-scrollbar-thumb) var(--parler-effective-color-scrollbar-track);",
    "background: var(--parler-effective-color-scrollbar-thumb);",
    "background: var(--parler-effective-color-scrollbar-track);",
    "background: var(--parler-effective-color-selection-bg);",
    "color: var(--parler-effective-color-selection-text);",
    "outline: 2px solid var(--parler-effective-color-focus);",
  ]) {
    assert.ok(css.includes(declaration), declaration);
  }
  assert.ok(css.includes("scrollbar-width: thin;"));
  assert.ok(css.includes("parler-ui ::-webkit-scrollbar-thumb"));
  assert.ok(css.includes("parler-ui ::selection"));
  assert.ok(!css.includes("parler-ui .lede"));
});

test("ordinary interaction surfaces consume semantic tokens and stable states", () => {
  for (const declaration of [
    "background: var(--parler-effective-color-surface-raised);",
    "background: var(--parler-effective-color-surface-subtle);",
    "background: var(--parler-effective-color-control-hover);",
    "background: var(--parler-effective-color-control-disabled);",
    "background: var(--parler-effective-color-scrim);",
    "color: var(--parler-effective-color-danger);",
    "color: var(--parler-effective-color-success);",
    "gap: var(--parler-effective-space-unit);",
  ]) {
    assert.ok(css.includes(declaration), declaration);
  }

  for (const severity of ["info", "success", "warning", "danger"]) {
    assert.ok(
      css.includes(`[part="notice"][data-severity="${severity}"]`),
      severity
    );
    assert.ok(
      css.includes(`[part="task-state"][data-severity="${severity}"]`),
      severity
    );
  }

  assert.ok(
    css.includes(
      "--parler-effective-notice-info: var(--parler-theme-notice-info, var(--parler-effective-color-accent));"
    )
  );
  assert.ok(css.includes("@media (forced-colors: active)"));
  for (const retired of ["var(--accent)", "var(--border)", "var(--muted)", "var(--bubble-assistant)"]) {
    assert.ok(!css.includes(retired), retired);
  }
});

test("native controls inherit the host typeface and Latest shares the compact status row", () => {
  assert.match(
    css,
    /parler-ui button,\s*parler-ui input,\s*parler-ui select,\s*parler-ui textarea \{\s*font-family: inherit;/
  );
  const statusRow = css.match(/parler-ui \.status-row \{([\s\S]*?)\n\}/);
  assert.ok(statusRow);
  assert.match(statusRow[1], /display: flex;/);
  assert.match(statusRow[1], /justify-content: space-between;/);
  assert.match(statusRow[1], /min-height: 28px;/);
  const jumpLatest = css.match(/parler-ui \.jump-latest \{([\s\S]*?)\n\}/);
  assert.ok(jumpLatest);
  assert.match(jumpLatest[1], /position: static;/);
  assert.match(jumpLatest[1], /min-height: 24px;/);
  assert.match(jumpLatest[1], /margin: 0;/);
  assert.match(jumpLatest[1], /padding: 2px 8px;/);
  assert.doesNotMatch(jumpLatest[1], /position: absolute|bottom:|right:/);
});
