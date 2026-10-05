import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost/",
});

for (const key of [
  "window",
  "document",
  "customElements",
  "HTMLElement",
  "HTMLImageElement",
  "Element",
  "Node",
  "Event",
  "CustomEvent",
  "MutationObserver",
  "CSSStyleSheet",
  "Document",
  "ShadowRoot",
]) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.requestAnimationFrame = (callback) => {
  callback(0);
  return 1;
};
globalThis.cancelAnimationFrame = () => {};
globalThis.ResizeObserver = class {
  observe() {}
  disconnect() {}
};

await import("../parler-ui.js");

const componentSource = fs.readFileSync("parler-ui.js", "utf8");

async function createParlerUi() {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  return element;
}

test("ThemeMode defaults, reflects, and normalizes property and attribute input", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  assert.equal(element.themeMode, "mashup");
  assert.equal(element.getAttribute("theme-mode"), "mashup");
  assert.ok(element.querySelector('[part="connection-status"]'));

  element.themeMode = "parler-dark";
  await element.updateComplete;
  assert.equal(element.getAttribute("theme-mode"), "parler-dark");

  element.themeMode = "SYSTEM";
  await element.updateComplete;
  assert.equal(element.themeMode, "mashup");
  assert.equal(element.getAttribute("theme-mode"), "mashup");

  element.setAttribute("theme-mode", "PARLER-DARK");
  await element.updateComplete;
  assert.equal(element.themeMode, "mashup");
  assert.equal(element.getAttribute("theme-mode"), "mashup");

  element.removeAttribute("theme-mode");
  await element.updateComplete;
  assert.equal(element.themeMode, "mashup");
  assert.equal(element.getAttribute("theme-mode"), "mashup");
});

test("connection-status reflects all transport transitions", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  for (const status of ["disconnected", "connecting", "connected"]) {
    element.connectionStatus = status;
    await element.updateComplete;
    assert.equal(element.getAttribute("connection-status"), status);
  }
});

test("theme surfaces expose their stable light-DOM parts", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  for (const part of [
    "shell",
    "thread",
    "thread-panel",
    "composer",
    "composer-input",
    "primary-action",
  ]) {
    assert.ok(element.querySelector(`[part="${part}"]`), part);
  }

  element.hideHeader = false;
  await element.updateComplete;
  assert.ok(element.querySelector('[part="header"]'));
});

test("the complete supported part and message-action names are present without retired aliases", () => {
  const parts = [...componentSource.matchAll(/part="([^"]+)"/g)]
    .map((match) => match[1])
    .filter((part) => !part.includes(" "));
  const expectedParts = [
    "shell", "header", "thread", "thread-panel", "empty-state",
    "empty-state-media", "empty-state-label", "message-meta", "user-message",
    "user-message-actions", "host-context-disclosure", "host-context-content",
    "assistant-message", "markdown-content", "message-actions", "message-action",
    "notice", "activity-status", "activity-progress", "task-state", "insight-hint",
    "data-table-disclosure", "data-table", "data-table-header", "data-table-cell",
    "data-table-footer", "chart", "connection-status", "approval-panel",
    "approval-input", "approval-primary-action", "approval-secondary-action",
    "approval-danger-action", "composer", "composer-input", "primary-action",
    "stop-action", "floating-action", "overlay-scrim", "popover", "popover-close",
    "chart-expand", "chart-expand-close", "chart-expand-placeholder",
  "chart-grid", "chart-group", "chart-group-title", "chart-group-slot", "chart-group-summary",
  ];
  assert.deepEqual([...new Set(parts)].sort(), expectedParts.sort());

  const actions = [...componentSource.matchAll(/data-action="([^"]+)"/g)]
    .map((match) => match[1]);
  assert.deepEqual([...new Set(actions)].sort(), [
    "copy", "cutoff", "dismiss", "feedback-down", "feedback-up", "info", "print",
  ]);
  for (const retired of [
    'part="task-state-panel"',
    'part="insight-envelope-hint"',
    'part="empty-brand"',
  ]) {
    assert.equal(componentSource.includes(retired), false, retired);
  }
});

test("conditional ordinary surfaces expose their parts and binding severities", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  const table = {
    kind: "entity-list",
    columns: [{ key: "state", label: "State", baseType: "STRING" }],
    rows: [{ state: "Running" }],
    shownRows: 1,
    totalRows: 2,
    cacheId: "cache-theme",
    exportStatus: "ok",
    exportMessage: null,
    exportFile: "theme.csv",
    exportRepository: "Exports",
    exportDownloadUrl: "/download/theme.csv",
  };
  const user = {
    kind: "user",
    text: "Prompt",
    hostContext: {
      schema: "parler-host-context-snapshot-v1",
      accepted: true,
      outcome: "ACCEPTED",
      key: "scope",
      rawJsonStored: true,
      rawJson: '{"key":"scope"}',
    },
  };
  const assistant = {
    kind: "assistant",
    requestId: "req-theme-surfaces",
    markdown: "Answer",
    charts: [],
    tables: [table],
    artifacts: [],
    insightEnvelopeLoose: { schemaVersion: "1" },
    taskState: {
      status: "failed",
      summary: { failed: 1 },
      items: [{ status: "failed", label: "Step" }],
    },
  };
  element._copyActionFeedback = { rid: "u-0-prompt", kind: "copied" };
  element._printBlocked = {
    requestId: assistant.requestId,
    blobUrl: "blob:theme-test",
    message: "Print blocked",
  };
  element._gatewayInvokeError = "Gateway failed";
  element._chatState = {
    rows: [user, assistant],
    busy: false,
    error: "Chat failed",
    activeRequestId: null,
    approvalGate: {
      requestId: "req-approval",
      summary: { title: "Confirm", lines: [{ label: "Action", value: "Run" }] },
      actions: ["approve", "cancel", "reject_with_comment"],
      submitState: "idle",
      submitError: null,
    },
  };
  element._threadHasOverflow = true;
  element._stickToBottom = false;
  element._stickBump += 1;
  await element.updateComplete;

  element.querySelector('[part="host-context-disclosure"]').click();
  await element.updateComplete;

  for (const part of [
    "user-message-actions", "host-context-disclosure", "host-context-content",
    "message-actions", "message-action", "notice", "task-state", "insight-hint",
    "data-table-disclosure", "data-table", "data-table-header", "data-table-cell",
    "data-table-footer", "approval-panel", "approval-input", "approval-primary-action",
    "approval-secondary-action", "approval-danger-action", "floating-action",
  ]) {
    assert.ok(element.querySelector(`[part="${part}"]`), part);
  }
  assert.ok(element.querySelector('[part="notice"][data-severity="success"]'));
  assert.ok(element.querySelector('[part="notice"][data-severity="warning"]'));
  assert.equal(
    element.querySelectorAll('[part="notice"][data-severity="danger"]').length,
    2
  );
  assert.ok(element.querySelector('[part="task-state"][data-severity="danger"]'));
  assert.equal(element.querySelector('[part="insight-envelope-hint"]'), null);

  element.themeMode = "parler-dark";
  await element.updateComplete;
  assert.equal(element.getAttribute("theme-mode"), "parler-dark");
  for (const part of [
    "host-context-disclosure", "message-actions", "notice", "task-state",
    "data-table", "approval-panel", "floating-action",
  ]) {
    assert.ok(element.querySelector(`[part="${part}"]`), `parler-dark: ${part}`);
  }
});

test("history loading uses the information notice state", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());
  element._historyPhase = "loading";
  await element.updateComplete;
  assert.ok(element.querySelector('[part="notice"][data-severity="info"]'));
});

test("message and every Markdown output kind render through supported parts in both modes", async (t) => {
  const markdown = [
    "# Heading 1",
    "## Heading 2",
    "### Heading 3",
    "#### Heading 4",
    "##### Heading 5",
    "###### Heading 6",
    "Paragraph with a hard break\ncontinuation.",
    "[link](https://example.test/) **strong** *emphasis* ~~struck~~ `inline`",
    "```js\nconst fenced = true;\n```",
    "    const indented = true;",
    "> quoted text",
    "- unordered\n  - nested",
    "1. ordered",
    "| A | B |\n| --- | --- |\n| one | two |",
    "---",
    "![example](https://example.test/image.png)",
  ].join("\n\n");

  for (const mode of ["mashup", "parler-dark"]) {
    await t.test(mode, async () => {
      const element = await createParlerUi();
      element.themeMode = mode;
      element._chatState = {
        rows: [
          { kind: "user", text: "User prompt" },
          {
            kind: "assistant",
            requestId: `req-${mode}`,
            markdown,
            charts: [],
            tables: [],
            artifacts: [],
          },
        ],
        busy: false,
        error: null,
        activeRequestId: null,
        approvalGate: null,
      };
      await element.updateComplete;
      t.after(() => element.remove());

      assert.equal(element.getAttribute("theme-mode"), mode);
      assert.equal(element.querySelectorAll('[part="message-meta"]').length, 2);
      assert.ok(element.querySelector('[part="user-message"]'));
      assert.ok(element.querySelector('[part="assistant-message"]'));
      const content = element.querySelector('[part="markdown-content"]');
      assert.ok(content);
      for (const selector of [
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "br",
        "a",
        "strong",
        "em",
        "s",
        "code",
        "blockquote",
        "ul ul",
        "ol",
        "table",
        "th",
        "td",
        "hr",
        "img",
      ]) {
        assert.ok(content.querySelector(selector), `${mode}: ${selector}`);
      }
      assert.equal(content.querySelectorAll("pre > code").length, 2);
    });
  }
});

test("ordinary scoped Custom CSS reaches a supported light-DOM part only", async (t) => {
  const scope = document.createElement("div");
  scope.className = "customer-chat";
  const scopedElement = document.createElement("parler-ui");
  const unscopedElement = document.createElement("parler-ui");
  const style = document.createElement("style");
  style.textContent =
    '.customer-chat parler-ui [part="primary-action"] { font-weight: 731; }';
  scope.append(scopedElement);
  document.body.append(style, scope, unscopedElement);
  await Promise.all([scopedElement.updateComplete, unscopedElement.updateComplete]);
  t.after(() => {
    style.remove();
    scope.remove();
    unscopedElement.remove();
  });

  assert.equal(
    getComputedStyle(scopedElement.querySelector('[part="primary-action"]')).fontWeight,
    "731"
  );
  assert.notEqual(
    getComputedStyle(unscopedElement.querySelector('[part="primary-action"]')).fontWeight,
    "731"
  );
});

test("active-turn parts expose distinct working and rate-controlled states", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  const row = {
    kind: "assistant",
    requestId: "req-theme",
    markdown: "",
    activity: "Working",
    rateControlWaiting: false,
    charts: [],
    tables: [],
    artifacts: [],
  };
  element._chatState = {
    rows: [row],
    busy: true,
    error: null,
    activeRequestId: row.requestId,
    approvalGate: null,
  };
  await element.updateComplete;

  assert.equal(
    element.querySelector('[part="activity-status"]')?.getAttribute("data-activity"),
    "working"
  );
  assert.equal(
    element.querySelector('[part="activity-progress"]')?.getAttribute("data-activity"),
    "working"
  );

  row.rateControlWaiting = true;
  element._chatState = { ...element._chatState, rows: [{ ...row }] };
  await element.updateComplete;

  assert.equal(
    element.querySelector('[part="activity-status"]')?.getAttribute("data-activity"),
    "rate-controlled"
  );
  assert.equal(
    element.querySelector('[part="activity-progress"]')?.getAttribute("data-activity"),
    "rate-controlled"
  );
});

test("progress properties normalize and compact projection preserves semantic status", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  assert.equal(element.progressPresentation, "detailed");
  assert.equal(element.progressLabel, "Thinking...");
  assert.equal(element.rateControlLabel, "Waiting for capacity...");

  const routine = {
    kind: "assistant",
    requestId: "req-progress",
    markdown: "",
    activity: "Calling tools",
    rateControlWaiting: false,
    charts: [],
    tables: [],
    artifacts: [],
    taskState: {
      status: "executing",
      summary: {},
      items: [{ status: "in-progress", label: "Read source" }],
    },
  };
  element.progressPresentation = "compact";
  element.progressLabel = "  Still thinking  ";
  element.rateControlLabel = "  Capacity pause  ";
  element._chatState = {
    rows: [routine],
    busy: true,
    error: null,
    activeRequestId: routine.requestId,
    approvalGate: null,
  };
  await element.updateComplete;

  const status = element.querySelector('[part="activity-status"]');
  assert.equal(status?.textContent.trim(), "Still thinking");
  assert.equal(status?.getAttribute("data-activity"), "working");
  assert.equal(element.querySelector('[part="task-state"]'), null);

  const stableStatus = status;
  element.placeholder = "Unrelated rerender";
  await element.updateComplete;
  assert.equal(element.querySelector('[part="activity-status"]'), stableStatus);
  assert.equal(stableStatus?.textContent.trim(), "Still thinking");

  const rateControlled = { ...routine, rateControlWaiting: true };
  element._chatState = { ...element._chatState, rows: [rateControlled] };
  await element.updateComplete;
  assert.equal(
    element.querySelector('[part="activity-status"]')?.textContent.trim(),
    "Capacity pause"
  );
  assert.equal(
    element.querySelector('[part="activity-status"]')?.getAttribute("data-activity"),
    "rate-controlled"
  );

  element.progressPresentation = "invalid";
  await element.updateComplete;
  assert.equal(element.progressPresentation, "detailed");
  assert.equal(
    element.querySelector('[part="activity-status"]')?.textContent.trim(),
    "Calling tools"
  );
  assert.ok(element.querySelector('[part="task-state"]'));
});

test("compact mode retains attention-required task detail with semantic severity", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  const row = {
    kind: "assistant",
    requestId: "req-attention",
    markdown: "",
    activity: "Working",
    charts: [],
    tables: [],
    artifacts: [],
    taskState: {
      status: "completed",
      summary: {},
      items: [{ status: "cancelled", label: "Approval-dependent step" }],
    },
  };
  element.progressPresentation = "compact";
  element._chatState = {
    rows: [row],
    busy: false,
    error: null,
    activeRequestId: null,
    approvalGate: null,
  };
  await element.updateComplete;

  const panel = element.querySelector('[part="task-state"]');
  assert.ok(panel);
  assert.equal(panel.getAttribute("data-severity"), "danger");
  assert.equal(element.querySelector('[part="task-state-panel"]'), null);
});

test("empty-state visibility remains owned by rows and history loading", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  assert.ok(element.querySelector('[part="empty-state"]'));
  assert.ok(element.querySelector('[part="empty-state-label"]'));
  assert.equal(element.querySelector('[part="empty-state-media"]'), null);
  assert.equal(element.querySelector('[part="empty-brand"]'), null);

  element._historyPhase = "loading";
  await element.updateComplete;
  assert.equal(element.querySelector('[part="empty-state"]'), null);

  element._historyPhase = "ready";
  element._chatState = {
    rows: [{ kind: "user", text: "hello" }],
    busy: false,
    error: null,
    activeRequestId: null,
    approvalGate: null,
  };
  await element.updateComplete;
  assert.equal(element.querySelector('[part="empty-state"]'), null);

  element._chatState = { ...element._chatState, rows: [] };
  await element.updateComplete;
  assert.ok(element.querySelector('[part="empty-state"]'));
});

test("ThingWorx Media stays hidden until load and replaces the fallback atomically", async (t) => {
  const previousTw = globalThis.TW;
  const calls = [];
  globalThis.TW = {
    convertImageLink(value) {
      calls.push(value);
      return `/converted${value}`;
    },
  };
  const element = await createParlerUi();
  t.after(() => {
    element.remove();
    if (previousTw === undefined) delete globalThis.TW;
    else globalThis.TW = previousTw;
  });

  element.emptyStateMedia = "  /Thingworx/MediaEntities/ParlerLogo  ";
  await element.updateComplete;

  const root = element.querySelector('[part="empty-state"]');
  const image = element.querySelector('[part="empty-state-media"]');
  assert.deepEqual(calls, ["/Thingworx/MediaEntities/ParlerLogo"]);
  assert.equal(root?.getAttribute("aria-hidden"), "true");
  assert.equal(image?.getAttribute("alt"), "");
  assert.equal(image?.hidden, true);
  assert.ok(element.querySelector('[part="empty-state-label"]'));

  image.dispatchEvent(new Event("load"));
  await element.updateComplete;
  assert.equal(element.querySelector('[part="empty-state-media"]')?.hidden, false);
  assert.equal(element.querySelector('[part="empty-state-label"]'), null);
});

test("failed conversion and image errors retain the label without broken-image UI", async (t) => {
  const previousTw = globalThis.TW;
  globalThis.TW = {
    convertImageLink() {
      throw new Error("denied");
    },
  };
  const element = await createParlerUi();
  t.after(() => {
    element.remove();
    if (previousTw === undefined) delete globalThis.TW;
    else globalThis.TW = previousTw;
  });

  element.emptyStateMedia = "/denied.svg";
  await element.updateComplete;
  assert.equal(element.querySelector('[part="empty-state-media"]'), null);
  assert.ok(element.querySelector('[part="empty-state-label"]'));

  delete globalThis.TW;
  element.emptyStateMedia = "/missing.svg";
  await element.updateComplete;
  const image = element.querySelector('[part="empty-state-media"]');
  image.dispatchEvent(new Event("error"));
  await element.updateComplete;
  assert.equal(image.hidden, true);
  assert.ok(element.querySelector('[part="empty-state-label"]'));
});

test("runtime Media changes ignore stale events and never inject raw markup", async (t) => {
  const previousTw = globalThis.TW;
  delete globalThis.TW;
  const element = await createParlerUi();
  t.after(() => {
    element.remove();
    if (previousTw === undefined) delete globalThis.TW;
    else globalThis.TW = previousTw;
  });

  element.emptyStateMedia = "/first.svg";
  await element.updateComplete;
  const first = element.querySelector('[part="empty-state-media"]');

  element.emptyStateMedia = "/second.svg";
  await element.updateComplete;
  const second = element.querySelector('[part="empty-state-media"]');
  assert.notEqual(first, second);
  assert.ok(element.querySelector('[part="empty-state-label"]'));

  first.dispatchEvent(new Event("load"));
  await element.updateComplete;
  assert.equal(second.hidden, true);
  assert.ok(element.querySelector('[part="empty-state-label"]'));

  second.dispatchEvent(new Event("load"));
  await element.updateComplete;
  assert.equal(second.hidden, false);
  assert.equal(element.querySelector('[part="empty-state-label"]'), null);

  element.emptyStateMedia = '<svg onload="globalThis.injected=true"></svg>';
  await element.updateComplete;
  assert.equal(element.querySelector("svg"), null);
  assert.ok(element.querySelector('[part="empty-state-media"]'));
  assert.ok(element.querySelector('[part="empty-state-label"]'));
});

test("an already-complete cached image settles without relying on a load event", async (t) => {
  const previousTw = globalThis.TW;
  delete globalThis.TW;
  const element = await createParlerUi();
  t.after(() => {
    element.remove();
    if (previousTw === undefined) delete globalThis.TW;
    else globalThis.TW = previousTw;
  });

  element.emptyStateMedia = "/cached.svg";
  await element.updateComplete;
  const image = element.querySelector('[part="empty-state-media"]');
  Object.defineProperty(image, "complete", { configurable: true, value: true });
  Object.defineProperty(image, "naturalWidth", { configurable: true, value: 24 });
  element.requestUpdate();
  await element.updateComplete;
  await element.updateComplete;

  assert.equal(image.hidden, false);
  assert.equal(element.querySelector('[part="empty-state-label"]'), null);
});

test("approval decision-submit states render feedback and disable the decision controls", async (t) => {
  const element = await createParlerUi();
  t.after(() => element.remove());

  /** @param {'idle'|'submitting'|'submitted'} submitState */
  const setGate = async (submitState, submitError = null) => {
    element._approvalComment = "because";
    element._chatState = {
      rows: [],
      busy: true,
      error: null,
      activeRequestId: "req-approval",
      approvalGate: {
        requestId: "req-approval",
        conversationId: "gw-1",
        pendingId: "pid-1",
        expiresAt: "2026-09-13T17:00:00Z",
        toolName: "invoke_service",
        summary: { title: "Confirm", lines: [{ label: "Action", value: "Run" }] },
        actions: ["approve", "cancel", "reject_with_comment"],
        submitState,
        submitError,
      },
    };
    await element.updateComplete;
  };
  const decisionButtons = () => [
    element.querySelector('[part="approval-primary-action"]'),
    element.querySelector('[part="approval-secondary-action"]'),
    element.querySelector('[part="approval-danger-action"]'),
  ];

  await setGate("idle");
  assert.ok(element.querySelector('[part="approval-panel"]'));
  assert.equal(element.querySelector(".approval-submit-status"), null);
  for (const button of decisionButtons()) assert.equal(button.disabled, false);

  await setGate("submitting");
  assert.equal(
    element.querySelector(".approval-submit-status").textContent.trim(),
    "Submitting decision…"
  );
  for (const button of decisionButtons()) assert.equal(button.disabled, true);

  await setGate("submitted");
  const accepted = element.querySelector(".approval-submit-status").textContent.trim();
  assert.equal(accepted, "Decision accepted. Waiting for result.");
  assert.doesNotMatch(accepted, /executing|running|approved operation/i);
  for (const button of decisionButtons()) assert.equal(button.disabled, true);

  // The status text inherits approval-panel colors; only the error variant overrides them, and
  // the buttons keep the existing disabled styling rather than gaining a new part or token.
  await setGate("idle", "The decision was not confirmed. Try again.");
  const errorLine = element.querySelector(".approval-submit-status");
  assert.equal(errorLine.textContent.trim(), "The decision was not confirmed. Try again.");
  assert.ok(errorLine.classList.contains("approval-submit-status--error"));
  assert.equal(errorLine.getAttribute("role"), "alert");
  for (const button of decisionButtons()) assert.equal(button.disabled, false);
  assert.equal(element.querySelector('[part="approval-submit-status"]'), null);
});
