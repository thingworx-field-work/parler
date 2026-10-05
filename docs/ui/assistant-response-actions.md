# Assistant response actions

Status: implemented (`parler-ui/lib/assistantResponseActions.js`).  
Scope: `parler-ui` / `parler-ui-widget` only for Copy / Print. No agent wire, server PDF, or FileRepository artifact
changes.

This document is the Copy / Print baseline. The expanded demo action strip (`copy`, `print`, feedback, info, cut off)
is specified in `docs/ui/turn-actions-and-table-export.md` because it intentionally crosses into agent persistence, Stream
roles, and conversation cutoff services.

## Purpose

Parler should let users quickly reuse a completed assistant answer without turning the chat UI into a reporting product.

The target is:

- copy one final assistant response
- print one final assistant response, including its visible charts and tables
- let the browser print pipeline handle PDF output

The actions are row-level UI actions; there is no broader artifact export or server-side PDF.

## UX

Add a small action footer to each completed assistant response that has at least one eligible action.

Actions:

- `Copy`
- `Print`

Display behavior:

- Desktop: actions are visually quiet by default and become clear on hover or keyboard focus near the assistant bubble footer.
- Hover/focus affordance applies to the whole assistant row footer strip, not only the button pixels.
- Keyboard and touch: actions must remain reachable without hover. On no-hover / coarse-pointer devices, eligible actions are visible by default.
- The footer must not cause layout shift when it appears. Reserve the footer area in assistant bubbles and use opacity/visibility transitions.
- The footer wrapper may render on all assistant rows so neighboring rows do not shift when one row has eligible actions and another does not.
- Suggested desktop default: action chips at about `opacity: 0.45` with muted text; row hover / `:focus-within` raises opacity to `1`.
- Keyboard: each action chip should use `:focus-visible` styles (contrast + outline) so tab users receive full-opacity affordance without relying on row hover alone.
- Buttons must have clear accessible names, such as `Copy answer text` and `Print this answer`; use matching `title` text so sighted mouse users also get a native tooltip.
- Touch targets should be at least 44px tall/wide even if the visual treatment stays quiet.
- Control order is `Copy`, then `Print`.
- Render the action footer after the visible answer content as the last child of the assistant bubble, so keyboard tab order is content, `Copy`, `Print`, then the next row.
- Do not show actions while the current row is still actively streaming or waiting on a matching HITL approval gate.
- Do not show actions for empty failed rows that only contain transport/provider errors outside the assistant markdown.

The intent is that the answer stays primary. The actions are tools attached to the row, not another panel.

**Final response only (product):** expose `Copy` / `Print` only on the **completed, settled final assistant message** for an exchange—not on streaming-in-progress content, tool rows, or other mid-process UI. Eligibility already excludes active streaming and HITL-pending rows; implementors must not attach the action footer to transient assistant placeholders.

**Two actions:** **`Copy`** and **`Print`** (with split eligibility so `Print` may appear without `Copy` when appropriate).

**“Final” is user-visible, not prose-only:** from the user’s perspective, the **final assistant answer** is whatever they see in that **completed** assistant row as a whole—**final markdown plus any charts and structured tables** that are part of the same LLM-delivered response for that turn. **Print** must include those visible charts and tables (same order and fidelity as on screen—clone the rendered row). **Copy** still carries the assistant’s **markdown source** for that row only (no chart/table HTML); users who need the **full visual** artifact use **Print**. In all cases, do not ship an export that **omits** chart/table content the user can already see in that row, or otherwise **diverges** from the on-screen answer.

## Copy

`Copy` copies the final markdown response for the selected assistant row.

Eligibility:

- `row.markdown.trim()` is non-empty
- the row is not active/streaming
- the row is not waiting on a matching HITL approval gate

`Copy` always copies the full assistant markdown for that row, not the current text selection.

Clipboard payload:

- `text/html`: markdown rendered by the same `markdown-it` pipeline used by `<parler-ui>`, with minimal safe wrapper HTML.
- `text/plain`: the source `row.markdown` string. This preserves headings, list markers, code fences, and markdown table pipes for markdown-aware paste targets.

Fallback:

- If `navigator.clipboard.write()` with `ClipboardItem` is unavailable, use `navigator.clipboard.writeText()` with the plain text.
- If clipboard write fails or permission is denied, show a short inline status near the action footer. Nothing is sent to the agent and there is no global toast system.
- On success, the button may temporarily read `Copied` for about 1.5 seconds with a polite accessibility announcement. Put the announcement in a small inline `role="status" aria-live="polite"` element near the button, not on the clicked button itself.

Copy deliberately does not include charts and structured tables. Users asking for a visual artifact use `Print`.

## Print

`Print` opens a separate browser document for the selected assistant row and uses the browser print flow.

Eligibility:

- `row.markdown.trim()` is non-empty, or `row.charts.length > 0`, or `row.tables.length > 0`
- the row is not active/streaming
- the row is not waiting on a matching HITL approval gate

When only one action is eligible, show only that action. For example, a chart-only row can show `Print` without `Copy`.

The print content should include the assistant row as the user sees it. **Assemble** the print document in this order: inject the optional plain-text user header first, then insert the **cloned assistant subtree without reordering** nodes so charts, tables, and markdown stay in the same relative order as in the live bubble (chart/table already appear before final markdown in the UI).

1. a compact plain-text header with the preceding user prompt when available
2. rendered charts
3. rendered structured tables
4. final assistant markdown
5. table CSV download links or export status text when already visible
6. lightweight metadata in a footer when available, such as request id and conversation id

Do not include:

- task-state panel
- streaming activity indicator
- hidden system prompt
- raw tool JSON
- HITL internals
- chain-of-thought
- the whole conversation

The insight-envelope hint is visible user-facing metadata, so print keeps it. It must not expand into raw insight-envelope JSON.

## Print Implementation

The print path clones the rendered DOM of the selected assistant bubble.

Reason:

- `<parler-ui-chart>` renders in light DOM and contains ordinary SVG after D3 draws the chart.
- Structured tables are already ordinary HTML tables.
- Markdown has already been rendered safely with `markdown-it`.
- Cloning the visible DOM avoids building a second chart renderer inside the print window.

Implementation sketch:

```js
function printAssistantRow(row, rowElement, context) {
  const printable = rowElement.cloneNode(true);
  stripNonPrintableControls(printable);
  const html = buildPrintDocumentHtml(printable, context);
  const w = window.open("", "_blank", "noopener,noreferrer");
  if (!w) return { ok: false, reason: "popup_blocked" };
  w.document.write(html);
  w.document.close();
  waitForPrintDocument(w).then(() => {
    w.focus();
    w.print();
  });
  return { ok: true };
}
```

Notes:

- `context` should be a small object derived from the selected row and chat state, for example `{ requestId?: string, conversationId?: string }`.
- `stripNonPrintableControls` removes the action footer, task-state panel, active-turn indicator, and any element marked with `data-no-print`.
- The print document should inject its own print CSS instead of relying on the dark chat theme.
- Call `print()` only after the print document has laid out. Prefer `document.fonts.ready` when available, with `load` / `requestAnimationFrame` fallback.
- If popups are blocked, show an inline status near the `Print` action: "Print blocked by browser. Allow popups for this site, then click Print again." **Also** offer a **user-initiated** follow-up control (for example an `<a target="_blank" rel="noopener noreferrer">` link to a Blob URL document) so a second explicit click can open the print view when extensions or policies block `window.open` (the popup allowlist alone is not relied on). Do not navigate the Mashup tab.
- Create the Blob URL lazily only after the popup-blocked state is reached for that row. Reuse the same `buildPrintDocumentHtml(printable, context)` output used by the popup path, and revoke the Blob URL when the inline status is dismissed or the assistant row unmounts.
- Avoid unbounded repeated print windows: ignore or disable rapid re-clicks while the print helper is opening the window.
- Re-enable the `Print` control once the helper has either opened the print window and invoked `print()`, or has shown the popup-blocked status/link. Do not wait for the child print window to close; some browsers keep it open after the print dialog is dismissed.

## Print Styling

Use a clean standalone document:

- white background
- dark text
- max-width suitable for Letter / A4
- system font stack with CJK-capable fallbacks
- no chat bubbles, dark theme chrome, or transport status
- charts and tables avoid being split across pages when reasonable
- tables keep borders and header styling, but use a print-friendly palette
- links show as clickable links in HTML and printable text where useful

Print CSS lives in a module-local constant; there is no new global stylesheet contract.

Minimum print CSS requirements:

```css
.print-root {
  max-width: 760px;
  margin: 24px auto;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI",
    "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}
parler-ui-chart,
.chart-root {
  display: block;
  width: 100%;
}
parler-ui-chart .chart-root svg,
.chart-root svg {
  width: 100%;
  height: auto;
}
.print-root img {
  max-width: 100%;
  height: auto;
}
parler-ui-chart,
.chart-root,
.parler-data-table,
.print-root table {
  page-break-inside: avoid;
  break-inside: avoid;
}
.print-root table,
.parler-data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
  border: 1px solid #888;
}
.print-root th,
.print-root td,
.parler-data-table th,
.parler-data-table td {
  border: 1px solid #aaa;
  padding: 4px 8px;
  text-align: left;
  vertical-align: top;
}
.print-root th,
.parler-data-table th {
  font-weight: 600;
  background: #f0f0f0;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}
@media print {
  .parler-data-table-dl a::after {
    content: " (" attr(href) ")";
  }
}
```

## Data Source

Use the current `ChatRow` and rendered row DOM.

Do not ask the LLM to restate or summarize the answer for export. The exported content must be the same evidence and prose already visible to the user.

Action eligibility is split:

- `Copy`: assistant row, non-empty markdown, not active, not pending HITL approval for that row.
- `Print`: assistant row, non-empty markdown or visible charts/tables, not active, not pending HITL approval for that row.

HITL suppression uses the same predicate as the chat UI: when an `approvalGate` is present and its `requestId` matches the assistant row’s `requestId`, treat the row as pending and hide both actions.

This approximates “successful final response” with current UI state. 

For print, when the preceding user prompt is included, derive it from the nearest prior **`kind === 'user'`** row in `ChatUiState.rows` (skip `tool` and other non-user rows). If no prior **`kind === 'user'`** row exists, omit the prompt header. Render it as plain text in a compact header and truncate very long prompts, for example after about 400 characters.

## Implementation

- `parler-ui/parler-ui.js` renders the row action footer in `renderRow` as the last child of the assistant bubble
  (marked `data-no-print`), attaches the copy / print handlers, and passes the row and row DOM identity to the
  helpers.
- `parler-ui/styles/parler-ui.css` holds the quiet footer layout, hover/focus visibility and the touch fallback.
- `parler-ui/lib/assistantResponseActions.js` holds the clipboard helpers, print document builder and DOM cleanup
  helpers as unit-testable pure functions.

`CONTRACTS/*` and `parler-agent/*` are not involved.

## Tests

Unit tests (`parler-ui/lib/assistantResponseActions.test.mjs`) cover helper behavior:

- markdown HTML + plain text copy payload generation
- copy success uses a nearby `role="status" aria-live="polite"` announcement element
- fallback plain text generation
- print document includes markdown, rendered table HTML, and rendered SVG
- print CSS preserves table borders/header styling for both structured tables and markdown-rendered tables
- print CSS constrains markdown images and avoids splitting charts/tables across pages where the browser can honor it
- print document removes action footer, task-state, active indicator, and `data-no-print` elements
- copy eligibility: active streaming row has no action; completed markdown row has `Copy`
- print eligibility: chart/table-only completed row has `Print`; active streaming row has no action
- HITL eligibility: matching `approvalGate.requestId` suppresses both actions
- popup-blocked print returns an inline-status reason, offers a **user-initiated** “open print view” follow-up (see Print Implementation), and does not navigate the Mashup tab
- popup-blocked Blob URLs are created lazily, reuse the same print HTML builder, and are revoked on status dismissal or row unmount
- footer reserved height prevents vertical shift even on assistant rows with no eligible actions

## Non-Goals

Not implemented:

- server-side PDF
- FileRepository export of the whole response
- full conversation export
- selected turn ranges
- per-chart PNG/SVG download
- per-table TSV copy
- copy selection
- new wire frames
- model-generated export summaries

Known interaction:

- If the chat UI currently shows both a markdown table and a structured wire table for the same answer, print will clone what the user sees. Deduplication belongs to the visible UI/table topic, not to this print action.
