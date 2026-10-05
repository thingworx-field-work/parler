import { LitElement, html, nothing } from "lit";
import { repeat } from "lit/directives/repeat.js";
import { unsafeHTML } from "lit/directives/unsafe-html.js";
import MarkdownIt from "markdown-it";
import {
  createInitialChatState,
  reduceUiEvent,
  startUserTurn,
} from "./lib/chatSession.js";
import { wireToUiEvent } from "./lib/wireAdapter.js";
import {
  chatUiStateFromHistoryRows,
  parseHistoryRows,
} from "./lib/historyHydrate.js";
import { renderChartPrintCard } from "./components/parler-ui-chart.js";
import { chartLegendItems } from "./components/chart-draw.js";
import { resolveChartTheme } from "./components/chart-theme.js";
import { chartViewStateKey, describeChartView, reconcileChartViewState } from "./lib/chartViewState.js";
import { focusTrapTarget } from "./lib/keyboardReachable.js";
import { parseThingworxWsUrl } from "./lib/parseThingworxWsUrl.js";
import { resolveThingworxWsUrlBinding } from "./lib/deriveThingworxWsUrl.js";
import { promiseAlwaysOnConnectBind } from "./lib/alwaysOnConnect.js";
import {
  AUTH_FAILURE_RETRY_SUPPRESS_MS,
  isLikelyAlwaysOnAuthFailure,
  makeBindContext,
  sameBindContext,
  shouldSuppressRepeatedAuthFailure,
} from "./lib/alwaysOnConnectGuards.js";
import { stringFromInvokeResult } from "@xudesheng/alwayson-js-codec";
import {
  buildParlerStreamParams,
  buildCancelUserPromptParams,
  buildGetConversationHistoryJsonParams,
  buildGetConnectionInfoParams,
  buildSubmitUserPromptParams,
  buildRecordAssistantFeedbackParams,
  buildSetConversationHistoryCutoffParams,
} from "./lib/alwaysOnInvokeParams.js";
import { parseCancelUserPromptResult } from "./lib/cancelUserPromptResult.js";
import { planCancelUserPromptDispatch } from "./lib/cancelUserPromptDispatchPlan.js";
import {
  approvalSubmitStatusText,
  isApprovalDecisionPending,
  submitApprovalDecision,
} from "./lib/approvalDecisionSubmit.js";
import { EntityTypes } from "./lib/twEntityTypes.js";
import {
  shouldDropWireTypeDuringHistoryLoad,
  tryParseWireBatch,
} from "./lib/historyBootstrap.js";
import {
  initialPromptHistoryNavigationState,
  navigatePromptHistory,
  userPromptHistoryFromRows,
} from "./lib/promptHistoryNavigation.js";
import {
  boundTransportString,
  boundTransportStringWire,
} from "./lib/boundTransportString.js";
import {
  connectionInfoEpochStale,
  formatConnectionVersionSuffix,
  parseConnectionInfoJson,
} from "./lib/connectionInfoHandshake.js";
import { WIDGET_PACKAGE_VERSION } from "./lib/widgetPackageVersion.js";
import {
  shouldRenderTaskStatePanelForRow,
  taskStateSeverity,
} from "./lib/taskStatePanelVisibility.mjs";
import {
  activeTurnIndicatorText,
  normalizeProgressPresentation,
} from "./lib/activeTurnIndicator.mjs";
import {
  settleEmptyStateMediaProjection,
  startEmptyStateMediaProjection,
} from "./lib/emptyStateMedia.mjs";
import { normalizeThemeMode } from "./lib/themeMode.mjs";
import {
  assembleChartCardsForPrint,
  assistantRowActionsEligibility,
  buildCopyClipboardParts,
  buildPrintDocumentHtml,
  COPY_FEEDBACK_MS,
  expandChartNotesForPrint,
  findPriorUserPromptText,
  resolvePortablePrintThemeFromElement,
  rewriteClonedChartPaletteForPrint,
  stripNonPrintableControls,
  waitForPrintDocument,
} from "./lib/assistantResponseActions.js";
import {
  buildArtifactsFromLegacyBuckets,
  CUTOFF_CONFIRM_MESSAGE,
  defaultTableCollapsed,
  expandTablesForPrint,
  chartColorContextFor,
  chartGroupMemberStateText,
  chartGroupSummaryText,
  groupArtifactsForLayout,
  orderArtifactsForDisplay,
  parentTableArtifactForChart,
  tableDisclosureSummaryLabel,
} from "./lib/artifactPresentation.js";
import { buildAssistantTurnInfoEntries } from "./lib/assistantTurnInfo.js";
import {
  iconRowCopy,
  iconRowPrint,
  iconRowThumbUp,
  iconRowThumbDown,
  iconRowInfo,
  iconRowCutoff,
} from "./lib/assistantRowActionIcons.mjs";
import { writeClipboardWithSelectionFallback } from "./lib/clipboardFallback.js";
import {
  buildLiveHostContextFromWire,
  formatHostContextRawForDisplay,
  hostContextDisclosureSummaryLabel,
  resolveHostContextRawJson,
  shouldShowHostContextDisclosure,
} from "./lib/hostContextRow.js";
import { openPrintDocumentWindow } from "./lib/openPrintDocumentWindow.js";
import { productSafeTableExportMessage } from "./lib/productSafeTableExportMessage.js";
import { rewriteThingworxPdfHrefForInlineRender } from "./lib/thingworxDocumentLinks.js";

/**
 * `<parler-ui>` — Lit ThingWorx widget: AlwaysOn transport, wire reducer, charts/tables, history hydrate.
 * Normative product / wire: `docs/architecture/agent-alwayson.md`, `CONTRACTS/UI_CLIENT_PROTOCOL.md`, `docs/ui/load-history.md`.
 */
const md = new MarkdownIt({ html: false, linkify: true, breaks: true });
installThingworxPdfLinkRewrite(md);
const TURN_TIMEOUT_MS = 3_600_000;
/** UI-only: block Send while invoke is in flight until the wire `request_id` is known (`docs/architecture/agent-alwayson.md`). */
const PRE_REQUEST_ID_TIMEOUT_MS = TURN_TIMEOUT_MS;
/** `CancelUserPrompt` pre-`session.ack` `not_active` — single retry (`docs/agent/turn-cancellation-control.md`). */
const CANCEL_USER_PROMPT_RETRY_MS = 300;
/** Bounded wait for terminal after accepted cancel (cooperative running stop; same doc §12). */
const CANCEL_STOP_BOUNDED_TERMINAL_MS = 15_000;
const PIN_THRESHOLD_PX = 140;
/** Auto-clear banner after Gateway row-action errors (feedback / cutoff). */
const GATEWAY_ERROR_CLEAR_MS = 8000;
/** UI-only: max rendered rows for `entity-list` table preview (`renderTableBlock`); does not trim `TableBlock.rows`. */
const UI_TABLE_PREVIEW_ROW_CAP = 5;

const PARLER_UI_LOG = "[parler-ui]";

/** @param {MarkdownIt} markdown */
function installThingworxPdfLinkRewrite(markdown) {
  const defaultRender =
    markdown.renderer.rules.link_open ??
    ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));

  markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    try {
      const token = tokens[idx];
      const href = token.attrGet("href");
      const origin =
        typeof window !== "undefined" && window.location?.origin
          ? window.location.origin
          : "";
      const rewritten = rewriteThingworxPdfHrefForInlineRender(href ?? "", origin);
      if (href && rewritten !== href) {
        token.attrSet("href", rewritten);
        token.attrSet("target", "_blank");
        token.attrSet("rel", "noopener noreferrer");
      }
    } catch {
      // Leave markdown-it's default link rendering intact on malformed links.
    }
    return defaultRender(tokens, idx, options, env, self);
  };
}

/** IANA time zone for Parler uplink (`userTimezone` / logical `user_timezone`); empty if host has none. */
function browserIanaTimeZone() {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return typeof tz === "string" && tz.length > 0 ? tz : "";
  } catch {
    return "";
  }
}

/** Local dev only: mock wire replay when running outside ThingWorx; omit on production mashups. */
const DEMO_MOCK_SEND_ATTR = "data-demo-mock-send";
const PROMPT_HISTORY_EDITING_KEYS = new Set([
  "ArrowLeft",
  "ArrowRight",
  "ArrowUp",
  "ArrowDown",
  "Home",
  "End",
  "PageUp",
  "PageDown",
  "Escape",
]);

/** @param {unknown} v @returns {v is HTMLTextAreaElement} */
function isTextAreaElement(v) {
  return typeof HTMLTextAreaElement !== "undefined" && v instanceof HTMLTextAreaElement;
}

/** @param {HTMLTextAreaElement} el */
function textareaCaretAtStart(el) {
  return el.selectionStart === 0 && el.selectionEnd === 0;
}

/** @param {HTMLTextAreaElement} el */
function textareaCaretAtEnd(el) {
  return el.selectionStart === el.value.length && el.selectionEnd === el.value.length;
}

/** @param {string} [s] @param {number} [max] */
function previewString(s, max = 160) {
  if (s == null || s === "") return "(empty)";
  const t = String(s);
  return t.length <= max ? t : `${t.slice(0, max)}…`;
}

/**
 * Log ConnectAndBind / transport steps to the console and to ThingWorx `TW.log.info` when available.
 * @param {string} phase
 * @param {Record<string, unknown> | string | number | boolean | null | undefined} [detail]
 */
function logTransport(phase, detail) {
  if (detail !== undefined) {
    console.info(PARLER_UI_LOG, phase, detail);
  } else {
    console.info(PARLER_UI_LOG, phase);
  }
  try {
    const g =
      typeof globalThis !== "undefined"
        ? globalThis
        : typeof window !== "undefined"
          ? window
          : undefined;
    const twlog = g?.TW?.log;
    if (!twlog || typeof twlog.info !== "function") return;
    let msg = `${PARLER_UI_LOG} ${phase}`;
    if (detail !== undefined) {
      if (detail !== null && typeof detail === "object") {
        try {
          msg += ` ${JSON.stringify(detail)}`;
        } catch {
          msg += " {…}";
        }
      } else {
        msg += ` ${String(detail)}`;
      }
    }
    twlog.info(msg);
  } catch {
    /* ignore */
  }
}

/**
 * @param {string} phase
 * @param {Record<string, unknown> | string | undefined} [detail]
 */
function logTransportWarn(phase, detail) {
  if (detail !== undefined) {
    console.warn(PARLER_UI_LOG, phase, detail);
  } else {
    console.warn(PARLER_UI_LOG, phase);
  }
  try {
    const g =
      typeof globalThis !== "undefined"
        ? globalThis
        : typeof window !== "undefined"
          ? window
          : undefined;
    const twlog = g?.TW?.log;
    if (!twlog || typeof twlog.warn !== "function") return;
    let msg = `${PARLER_UI_LOG} ${phase}`;
    if (detail !== undefined) {
      if (typeof detail === "object" && detail !== null) {
        try {
          msg += ` ${JSON.stringify(detail)}`;
        } catch {
          msg += " {…}";
        }
      } else {
        msg += ` ${String(detail)}`;
      }
    }
    twlog.warn(msg);
  } catch {
    /* ignore */
  }
}

/** True when bundled hosts (e.g. Vite) already injected parler-ui.css. */
function parlerUiStylesAlreadyPresent() {
  try {
    for (const sheet of document.styleSheets) {
      let rules;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      if (!rules?.length) continue;
      for (let i = 0; i < rules.length; i++) {
        const text = rules[i].cssText;
        if (
          typeof text === "string" &&
          (text.includes("ai-parler") || text.includes("parler-ui")) &&
          text.includes(".shell")
        ) {
          return true;
        }
      }
    }
  } catch {
    /* ignore */
  }
  return false;
}

/** Last-resort layout if parler-ui.css fails to load (wrong deploy path, CSP, etc.). */
function injectParlerUiFallbackCss() {
  if (typeof document === "undefined") return;
  if (document.querySelector("style[data-parler-ui-fallback]")) return;
  const s = document.createElement("style");
  s.setAttribute("data-parler-ui-fallback", "");
  s.textContent = `
parler-ui {
  display: flex !important;
  flex-direction: column !important;
  box-sizing: border-box !important;
  min-height: 320px !important;
  width: 100% !important;
  color: #e8eaed !important;
  font: 14px/1.5 "Segoe UI", system-ui, sans-serif !important;
  background: #0f1218 !important;
}
parler-ui .shell {
  flex: 1 1 auto !important;
  display: flex !important;
  flex-direction: column !important;
  min-height: 0 !important;
  width: 100% !important;
}
parler-ui .thread-wrap {
  flex: 1 1 auto !important;
  min-height: 160px !important;
  display: flex !important;
  flex-direction: column !important;
  position: relative !important;
}
parler-ui .thread-scroll {
  flex: 1 1 auto !important;
  min-height: 120px !important;
  overflow-y: auto !important;
}
parler-ui .thread-panel {
  min-height: 72px !important;
  padding: 12px !important;
  border: 1px solid #2a3038 !important;
  border-radius: 12px !important;
  background: #12151c !important;
}
parler-ui .composer {
  flex-shrink: 0 !important;
  display: flex !important;
  gap: 10px !important;
  align-items: flex-end !important;
  padding-top: 12px !important;
  border-top: 1px solid #2a3038 !important;
}
parler-ui .input {
  flex: 1 !important;
  min-height: 72px !important;
  padding: 10px 12px !important;
  border: 1px solid #2a3038 !important;
  border-radius: 10px !important;
  background: #12151c !important;
  color: #e8eaed !important;
  font: inherit !important;
}
parler-ui .send {
  padding: 10px 18px !important;
  border-radius: 10px !important;
  border: none !important;
  background: #8ab4ff !important;
  color: #0a0c10 !important;
  font-weight: 600 !important;
  cursor: pointer !important;
}
parler-ui .thread-panel {
  min-width: 0 !important;
}
parler-ui .bubble {
  box-sizing: border-box !important;
  max-width: min(92%, 960px) !important;
}
parler-ui .assistant-bubble {
  min-width: 0 !important;
  width: 100% !important;
}
parler-ui-chart {
  display: block !important;
  width: 100% !important;
  max-width: 100% !important;
  min-width: 0 !important;
}
parler-ui .chart-root {
  max-width: 100% !important;
  min-width: 0 !important;
  overflow-x: hidden !important;
}
parler-ui .chart-root svg {
  display: block !important;
  width: 100% !important;
  max-width: 100% !important;
  height: auto !important;
  box-sizing: border-box !important;
}`;
  document.head.appendChild(s);
}

function ensureParlerUiStylesheet() {
  if (typeof document === "undefined") return;
  if (document.querySelector("link[data-parler-ui-css]")) return;
  if (parlerUiStylesAlreadyPresent()) return;
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = new URL("./styles/parler-ui.css", import.meta.url).href;
  link.setAttribute("data-parler-ui-css", "");
  link.addEventListener("error", () => injectParlerUiFallbackCss(), { once: true });
  document.head.appendChild(link);
}

/**
 * @param {unknown} v
 * @param {string} [baseType] ThingWorx-style column type (e.g. PASSWORD)
 */
function formatTableCell(v, baseType) {
  const bt = String(baseType ?? "").trim().toUpperCase();
  if (bt === "PASSWORD") return "••••";
  if (v === null || v === undefined) return "";
  if (typeof v === "boolean") return v ? "true" : "false";
  return String(v);
}

/**
 * Build a browser GET URL for a ThingWorx FileRepository file (path as returned by {@code SaveText}).
 * @param {string} repositoryThingName
 * @param {string} repositoryRelativePath
 * @returns {string | null}
 */
function thingworxFileRepositoriesHref(repositoryThingName, repositoryRelativePath) {
  try {
    if (typeof window === "undefined" || !window.location?.origin) return null;
    const repo = String(repositoryThingName ?? "").trim();
    const rel = String(repositoryRelativePath ?? "").trim();
    if (!repo || !rel) return null;
    const path = rel.startsWith("/") ? rel : `/${rel}`;
    return `${window.location.origin}/Thingworx/FileRepositories/${encodeURIComponent(repo)}${encodeURI(path)}`;
  } catch {
    return null;
  }
}

/**
 * Path-style `/Thingworx/FileRepositories/...` URLs treat `?` / `#` as delimiters; use query form (see
 * `docs/ui/table-view-solution.md` §5.5 Method B, downloader link).
 * @param {string} repositoryThingName
 * @param {string} repositoryRelativePath
 * @returns {string | null}
 */
function thingworxFileRepositoryDownloaderHref(repositoryThingName, repositoryRelativePath) {
  try {
    if (typeof window === "undefined" || !window.location?.origin) return null;
    const repo = String(repositoryThingName ?? "").trim();
    let rel = String(repositoryRelativePath ?? "").trim().replace(/\\/g, "/");
    if (!repo || !rel) return null;
    if (rel.startsWith("/")) rel = rel.slice(1);
    const q = new URLSearchParams();
    q.set("download-repository", repo);
    q.set("download-path", rel);
    return `${window.location.origin}/Thingworx/FileRepositoryDownloader?${q.toString()}`;
  } catch {
    return null;
  }
}

/** @param {string | null | undefined} repositoryRelativePath */
function fileRepoPathNeedsDownloaderQuery(repositoryRelativePath) {
  return /[?#]/.test(String(repositoryRelativePath ?? ""));
}

/**
 * @param {string} repositoryThingName
 * @param {string} repositoryRelativePath
 * @returns {string | null}
 */
function thingworxTableExportDownloadHref(repositoryThingName, repositoryRelativePath) {
  if (fileRepoPathNeedsDownloaderQuery(repositoryRelativePath)) {
    return thingworxFileRepositoryDownloaderHref(repositoryThingName, repositoryRelativePath);
  }
  return thingworxFileRepositoriesHref(repositoryThingName, repositoryRelativePath);
}

/** Tooltip / aria for export links (mashup hover debugging). */
function tableExportLinkLabel(repositoryThingName, repositoryRelativePath) {
  const r = String(repositoryThingName ?? "").trim();
  const f = String(repositoryRelativePath ?? "").trim();
  if (r && f) return `${r}: ${f}`;
  return "Download CSV";
}

/**
 * @param {import('./lib/types.js').TableBlock} tb
 * @param {{
 *   collapsed?: boolean,
 *   summaryLabel?: string,
 *   onToggle?: (ev: Event) => void,
 *   onKeydown?: (ev: KeyboardEvent) => void,
 * }} [opts]
 */
function renderTableBlock(tb, opts = {}) {
  if (tb.kind !== "entity-list") return nothing;
  const collapsed = opts.collapsed === true;
  const summaryLabel =
    opts.summaryLabel != null && String(opts.summaryLabel).trim() !== ""
      ? String(opts.summaryLabel)
      : tableDisclosureSummaryLabel(tb);
  const cols = tb.columns;
  const rows = Array.isArray(tb.rows) ? tb.rows : [];
  const cap = UI_TABLE_PREVIEW_ROW_CAP;
  const rowCapActive = rows.length > cap;
  const previewRows = rowCapActive ? rows.slice(0, cap) : rows;
  const renderedCount = previewRows.length;
  /** @type {string[]} */
  const footLines = [];
  if (rowCapActive) {
    const denom =
      typeof tb.totalRows === "number" &&
      Number.isFinite(tb.totalRows) &&
      tb.totalRows >= 0
        ? tb.totalRows
        : rows.length;
    footLines.push(`Showing ${renderedCount} of ${denom} rows`);
  } else if (
    typeof tb.shownRows === "number" &&
    typeof tb.totalRows === "number" &&
    tb.shownRows < tb.totalRows
  ) {
    footLines.push(`Showing ${tb.shownRows} of ${tb.totalRows} rows`);
  }
  if (tb.exportMessage) footLines.push(productSafeTableExportMessage(tb.exportMessage));
  else if (
    tb.exportStatus &&
    tb.exportStatus !== "none" &&
    tb.exportStatus !== "ok"
  ) {
    footLines.push(`Export: ${tb.exportStatus}`);
  }
  const foot =
    footLines.length > 0
      ? html`<p class="parler-data-table-foot" part="data-table-footer">
          ${footLines.join(" — ")}
        </p>`
      : nothing;
  const exportOk =
    tb.exportStatus === "ok" &&
    tb.exportRepository?.trim() &&
    tb.exportFile?.trim();
  const repoFileHref =
    exportOk && !tb.exportDownloadUrl?.trim()
      ? thingworxTableExportDownloadHref(tb.exportRepository, tb.exportFile)
      : null;
  const dlUrl = tb.exportDownloadUrl?.trim() ?? "";
  const dlTitle =
    dlUrl.length > 0
      ? dlUrl.length > 160
        ? `${dlUrl.slice(0, 160)}…`
        : dlUrl
      : tableExportLinkLabel(tb.exportRepository, tb.exportFile);
  const dl =
    dlUrl
      ? html`<p class="parler-data-table-dl" part="data-table-footer">
          <a
            href=${dlUrl}
            target="_blank"
            rel="noopener noreferrer"
            title=${dlTitle}
            aria-label=${dlTitle}
            >Download</a
          >
        </p>`
      : repoFileHref
        ? html`<p class="parler-data-table-dl" part="data-table-footer">
            <a
              href=${repoFileHref}
              target="_blank"
              rel="noopener noreferrer"
              title=${tableExportLinkLabel(tb.exportRepository, tb.exportFile)}
              aria-label=${tableExportLinkLabel(tb.exportRepository, tb.exportFile)}
              >Download CSV</a
            >
          </p>`
        : tb.exportRepository &&
            tb.exportFile &&
            tb.exportRepository.trim() &&
            tb.exportFile.trim()
          ? html`<p class="parler-data-table-dl muted" part="data-table-footer">
              ${tb.exportRepository}${tb.exportFile}
            </p>`
          : nothing;
  const body = html`
      <table class="parler-data-table" part="data-table">
        <thead>
          <tr>
            ${repeat(
              cols,
              (c) => c.key,
              (c) => html`<th scope="col" part="data-table-header">${c.label}</th>`
            )}
          </tr>
        </thead>
        <tbody>
          ${repeat(
            previewRows,
            (_r, idx) => idx,
            (r) => html`
              <tr>
                ${repeat(
                  cols,
                  (c) => c.key,
                  (c) => html`<td part="data-table-cell">${formatTableCell(
                    r[c.key],
                    c.baseType
                  )}</td>`
                )}
              </tr>
            `
          )}
        </tbody>
      </table>
      ${foot}
      ${dl}
  `;
  return html`
    <div
      class="parler-data-table-wrap ${collapsed
        ? "parler-data-table-wrap--collapsed"
        : ""}"
      data-parler-artifact-key=${opts.artifactKey ?? ""}
    >
      <button
        type="button"
        class="parler-data-table-disclosure"
        part="data-table-disclosure"
        aria-expanded=${collapsed ? "false" : "true"}
        aria-label=${`Toggle table: ${summaryLabel}`}
        @click=${opts.onToggle}
        @keydown=${opts.onKeydown}
      >
        <span class="parler-data-table-disclosure-icon" aria-hidden="true"
          >${collapsed ? "▶" : "▼"}</span
        >
        <span class="parler-data-table-disclosure-label">${summaryLabel}</span>
      </button>
      <div class="parler-data-table-panel" ?hidden=${collapsed}>${body}</div>
    </div>
  `;
}

/** @param {string} text @param {boolean} [rateControlWaiting] */
function renderActiveTurnIndicator(text, rateControlWaiting = false) {
  const cls =
    rateControlWaiting === true ? "working working--rate-control-wait" : "working";
  const activity = rateControlWaiting === true ? "rate-controlled" : "working";
  return html`
    <div
      class=${cls}
      part="activity-status"
      data-activity=${activity}
      role="status"
      aria-live="polite"
    >
      <span class="working-text">${text}</span>
      <span
        class="working-pulse"
        part="activity-progress"
        data-activity=${activity}
        aria-hidden="true"
      ></span>
    </div>
  `;
}

/**
 * Compact v1b task progress panel (metadata-only — no raw JSON fallback).
 * @param {import('./lib/types.js').TaskStateSnapshot} ts
 */
function renderTaskStatePanel(ts) {
  if (!ts || typeof ts.status !== "string" || !ts.status.trim()) return nothing;
  const severity = taskStateSeverity(ts);
  const title = ts.title != null ? String(ts.title).trim() : "";
  const sum =
    ts.summary && typeof ts.summary === "object" ? ts.summary : {};
  /** @type {string[]} */
  const summaryBits = [];
  for (const k of ["satisfied", "total", "inProgress", "failed", "blocked"]) {
    const v = sum[k];
    if (typeof v === "number" && Number.isFinite(v)) {
      summaryBits.push(`${k}: ${v}`);
    }
  }
  const summaryLine = summaryBits.join(" · ");
  const items = Array.isArray(ts.items) ? ts.items : [];
  return html`
    <aside
      class="task-state-panel"
      part="task-state"
      data-severity=${severity}
      aria-label="Task progress"
    >
      ${title
        ? html`<h4 class="task-state-title">${title}</h4>`
        : nothing}
      <p class="task-state-meta">
        ${ts.status}${summaryLine ? ` — ${summaryLine}` : ""}
      </p>
      ${items.length > 0
        ? html`
            <ul class="task-state-items">
              ${repeat(
                items.slice(0, 40),
                (_it, idx) => idx,
                (it) => {
                  const rec =
                    it && typeof it === "object"
                      ? /** @type {Record<string, unknown>} */ (it)
                      : {};
                  const label =
                    typeof rec.label === "string"
                      ? rec.label
                      : String(rec.id ?? "");
                  const st =
                    typeof rec.status === "string" ? rec.status : "";
                  const sm =
                    rec.summary != null ? String(rec.summary) : "";
                  return html`<li class="task-state-item">
                    <span class="task-state-status">${st}</span>
                    <span class="task-state-label">${label}</span>
                    ${sm
                      ? html`<span class="task-state-summary">${sm}</span>`
                      : nothing}
                  </li>`;
                }
              )}
            </ul>
          `
        : nothing}
    </aside>
  `;
}

/**
 * Parler chat UI + **embedded ThingWorx AlwaysOn Transport** (`@xudesheng/alwayson-js-codec` + `lib/alwaysOnParlerClient.js`).
 * Mashup flow: **`ConnectAndBind`** → user **Send** → internal **`invokeService`** (`ParlerStreamToRemoteThing` or **`SubmitUserPrompt`** on C per §2.2) + **`ReceiveMessage`** wire into the reducer (**`agent-alwayson.md`**).
 * There is **no** DOM **`parler-user-message`** event and **no** Composer **SubmitUserPrompt** event binding — AlwaysOn-only; turns are **not** delegated to the Mashup.
 */
export class ParlerUi extends LitElement {
  /** @type {import('./lib/alwaysOnParlerClient.js').AlwaysOnParlerClient | null} AlwaysOn facade when connected */
  #twAlwaysOnClient = null;
  /** @type {{ conversationId: string, agentThingName: string, rawUrl: string } | null} Active successful bind context. */
  #activeBindContext = null;
  /** @type {{ conversationId: string, agentThingName: string, rawUrl: string } | null} ConnectAndBind currently opening. */
  #connectInFlightContext = null;
  /** @type {{ appKey: string, atMs: number, message: string } | null} Recent auth failure for retry suppression. */
  #recentAlwaysOnAuthFailure = null;
  /** @type {string[]} */
  #wireBacklog = [];
  /** True after `invokeService` until callback supplies `request_id` or fails (pre-busy hang guard). */
  #awaitingInvokeRequestId = false;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  #preRequestIdTimer = undefined;
  /**
   * Bumped on each Send and on pre-`request_id` timeout / transport loss / reset so late
   * `invokeService` callbacks do not start a turn after the user was told the send failed (invoke completion generation; `docs/ui/load-history.md`).
   */
  #invokeCompletionGeneration = 0;
  /** Invalidates in-flight GetConversationHistoryJson and pairs with per-invoke epochAtStart (docs/ui/load-history.md §4.3). */
  #historyLoadEpoch = 0;
  /** `GetConnectionInfo` agent.extensionVersion (and optional implementation) — cleared on disconnect; epoch-guarded. */
  #agentConnExt = "";
  #agentConnImpl = "";
  /** From `GetConnectionInfo` `capabilities.supportsCancellation` (strict JSON boolean `true` only); cleared with connection info. */
  #supportsCancellationAgent = false;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  #cancelRetryTimer = undefined;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  #cancelBoundedTimer = undefined;
  /** After Stop click: wait for terminal or bounded fallback (`docs/agent/turn-cancellation-control.md` §5). */
  #cancelStoppingUi = false;
  /** One `not_active` → single delayed re-invoke. */
  #cancelNotActiveRetryUsed = false;
  /** `activeRequestId` when Stop was pressed (ignore late invoke callbacks for other turns). */
  #cancelTargetRid = "";
  /** After `session.superseded`, block Send until the next successful ConnectAndBind (§4.4). */
  #liveSessionInvalidUntilReconnect = false;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  #copyFeedbackClearTimer = undefined;
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  #gatewayInvokeErrorClearTimer = undefined;
  /** True while opening / printing so rapid Print clicks are ignored (`docs/ui/assistant-response-actions.md`). */
  #printInFlight = false;
  /** @type {Map<string, number>} assistantMessageId → last feedback invoke epoch ms */
  #feedbackThrottleAt = new Map();
  /** Client-local table disclosure overrides. */
  /** @type {Set<string>} */
  #tableDisclosureUserTouched = new Set();
  /** Per-chart view state (design §4.4), keyed by conversation + request + artifact key. @type {Map<string, object>} */
  #chartViewStates = new Map();
  /**
   * Inline chart expansion (design §4.5 C1b-3): widget-owned, never part of the view state,
   * cleared when the source answer or artifact is gone. `scrollTop` is the thread position to
   * restore on close.
   * @type {{ requestId: string, artifactKey: string, scrollTop: number } | null}
   */
  #expandedChart = null;
  /** @type {Map<string, boolean>} */
  #tableDisclosureCollapsed = new Map();
  /** @type {Set<string>} expanded Host Context disclosure keys (`u-${index}`). */
  #hostContextExpandedKeys = new Set();
  /** @type {Map<string, string>} last confirmed rating per assistantMessageId (up|down) for RecordAssistantFeedback.previousRating */
  #lastFeedbackRatingByAssistantId = new Map();
  /** @type {string | null} */
  #assistantInfoOpenAid = null;
  /** @type {{ label: string, value: string }[] | null} */
  #assistantInfoLines = null;
  /** @type {((ev: KeyboardEvent) => void) | null} */
  #assistantInfoEscapeHandler = null;
  /** @type {import('./lib/promptHistoryNavigation.js').PromptHistoryNavigationState} */
  #promptHistoryNavigation = initialPromptHistoryNavigationState();
  /** @type {"mashup" | "parler-dark" | undefined} */
  #themeMode;
  /** @type {"detailed" | "compact" | undefined} */
  #progressPresentation;
  /** @type {string} */
  #emptyStateMedia = "";
  /** @type {{ value: string, source: string, generation: number, phase: string }} */
  #emptyStateMediaProjection = Object.freeze({
    value: "",
    source: "",
    generation: 0,
    phase: "fallback",
  });

  static properties = {
    themeMode: {
      type: String,
      attribute: "theme-mode",
      reflect: true,
      noAccessor: true,
    },
    progressPresentation: {
      type: String,
      attribute: "progress-presentation",
      noAccessor: true,
    },
    progressLabel: { type: String, attribute: "progress-label" },
    rateControlLabel: { type: String, attribute: "rate-control-label" },
    emptyStateMedia: {
      type: String,
      attribute: "empty-state-media",
      noAccessor: true,
    },
    placeholder: { type: String },
    disabled: { type: Boolean },
    /** When true, the hero block (title only) is not rendered. */
    hideHeader: { type: Boolean, attribute: "hide-header" },
    /** Main heading when the header is visible. */
    headerTitle: { type: String, attribute: "header-title" },
    /** @deprecated Unused header subtitle; kept for Mashup property compatibility. */
    headerSubtitle: { type: String, attribute: "header-subtitle" },
    /**
     * Full ThingWorx AlwaysOn WebSocket URL (`ws://…` / `wss://…`). If empty after trim,
     * {@link resolveThingworxWsUrlBinding} derives from `window.location` and `/Thingworx/WS`.
     */
    thingworxWsUrl: { type: String, attribute: "thingworx-ws-url" },
    /** Application key (bind from temporary key service in Mashup). */
    appKey: { type: String, attribute: "app-key" },
    /** AIAgent Thing name (§2.1 / §2.2). */
    agentThingName: { type: String, attribute: "agent-thing-name" },
    /**
     * If true (Phase F default), Send uses `SubmitUserPrompt` on `ParlerGateway` (conversationId); if false, invokes
     * `ParlerStreamToRemoteThing` on the agent (§2.1-style invoke shape). Server-side **AgentThreadDataTable** ownership
     * applies to **both** paths for `conversationId`; false only switches **which Thing** receives the service call.
     * @see docs/architecture/agent-alwayson.md §2.2
     */
    useSubmitUserPromptOnConversation: {
      type: Boolean,
      attribute: "use-submit-user-prompt-on-conversation",
    },
    /** `disconnected` \| `connecting` \| `connected` — updated by ConnectAndBind / transport. */
    connectionStatus: {
      type: String,
      attribute: "connection-status",
      reflect: true,
    },
    /**
     * Conversation / thread id (EdgeThing name, routing). Required for **Send** with real Transport or **`data-demo-mock-send`** demo.
     */
    conversationId: { type: String, attribute: "conversation-id" },
    /**
     * Optional Mashup **HostScopeJson** (UTF-8 JSON string). Read synchronously on Send; uplink `hostContext`.
     * @see docs/architecture/host-context.md
     */
    hostScopeJson: { type: String, attribute: "host-scope-json" },
    /**
     * When true (default), after ConnectAndBind the widget invokes GetConversationHistoryJson on the Gateway and hydrates the thread.
     * @see docs/ui/load-history.md
     */
    loadHistoryOnBind: { type: Boolean, attribute: "load-history-on-bind" },
    /** Override Gateway service name for history JSON (default GetConversationHistoryJson). */
    historyServiceName: { type: String, attribute: "history-service-name" },
    /** Max rows passed to GetConversationHistoryJson / QueryStreamData (default 500; clamped 1..20000). */
    historyMaxItems: { type: Number, attribute: "history-max-items" },
    /** Banner text while loading history; empty uses built-in default. */
    historyLoadingMessage: { type: String, attribute: "history-loading-message" },
    /** @internal idle | loading | ready — history bootstrap phase (state). */
    _historyPhase: { type: String, state: true },
    /**
     * ThingWorx Composer / runtime assigns a backing `model` (e.g. lodash merge onto `jqElement[0]`).
     * Must be writable — a getter-only `model` on the prototype throws and breaks the mashup.
     * Not a Composer binding (omit from `parler-ui.json`); Parler does not use this value.
     */
    model: { type: Object, attribute: false },
    /** Bump to re-run stylesheet injection if needed */
    _stickBump: { type: Number, state: true },
    _chatState: { type: Object, state: true },
    _draft: { type: String, state: true },
    /** Draft for {@code reject_with_comment} when the gate lists that action. */
    _approvalComment: { type: String, state: true },
    _threadHasOverflow: { type: Boolean, state: true },
    /** Popup-blocked print fallback: blob URL + message (`docs/ui/assistant-response-actions.md`). */
    _printBlocked: { type: Object, state: true },
    /** `{ rid, kind }` for Copy success/failure inline status. */
    _copyActionFeedback: { type: Object, state: true },
    /** Gateway-only row actions (feedback / cutoff) user-visible error banner. */
    _gatewayInvokeError: { type: String, state: true },
  };

  constructor() {
    super();
    this.themeMode = "mashup";
    this.progressPresentation = "detailed";
    this.progressLabel = "Thinking...";
    this.rateControlLabel = "Waiting for capacity...";
    this.emptyStateMedia = "";
    this.placeholder = "Ask about device / property history…";
    this.disabled = false;
    this.hideHeader = true;
    this.headerTitle = "AI Parler";
    this.headerSubtitle = "";
    this.thingworxWsUrl = "";
    this.appKey = "";
    this.agentThingName = "";
    this.useSubmitUserPromptOnConversation = true;
    this.connectionStatus = "disconnected";
    this.conversationId = "";
    this.hostScopeJson = "";
    this.loadHistoryOnBind = true;
    this.historyServiceName = "";
    this.historyMaxItems = 500;
    this.historyLoadingMessage = "";
    this._historyPhase = "idle";
    this.model = null;
    /** @type {import('./lib/types.js').ChatUiState} */
    this._chatState = createInitialChatState();
    this._draft = "";
    this.#resetPromptHistoryNavigation();
    this._approvalComment = "";
    this._threadHasOverflow = false;
    this._stickBump = 0;
    this._printBlocked = null;
    this._copyActionFeedback = null;
    this._gatewayInvokeError = "";
    /** @type {ReturnType<typeof setTimeout> | undefined} */
    this._watchdog = undefined;
    /** @type {ResizeObserver | null} */
    this._threadRo = null;
    this._stickToBottom = true;
  }

  createRenderRoot() {
    return this;
  }

  get themeMode() {
    return this.#themeMode ?? "mashup";
  }

  set themeMode(value) {
    const normalized = normalizeThemeMode(value);
    const previous = this.#themeMode;
    this.#themeMode = normalized;
    if (previous !== normalized) {
      this.requestUpdate("themeMode", previous);
    } else if (this.hasUpdated && this.getAttribute("theme-mode") !== normalized) {
      this.setAttribute("theme-mode", normalized);
    }
  }

  get progressPresentation() {
    return this.#progressPresentation ?? "detailed";
  }

  set progressPresentation(value) {
    const normalized = normalizeProgressPresentation(value);
    const previous = this.#progressPresentation;
    this.#progressPresentation = normalized;
    if (previous !== normalized) {
      this.requestUpdate("progressPresentation", previous);
    }
  }

  get emptyStateMedia() {
    return this.#emptyStateMedia;
  }

  set emptyStateMedia(value) {
    const normalized = typeof value === "string" ? value : "";
    const previous = this.#emptyStateMedia;
    if (previous === normalized) return;
    this.#emptyStateMedia = normalized;
    const tw = globalThis.TW;
    const convertImageLink =
      typeof tw?.convertImageLink === "function"
        ? tw.convertImageLink.bind(tw)
        : undefined;
    this.#emptyStateMediaProjection = startEmptyStateMediaProjection(
      normalized,
      convertImageLink,
      this.#emptyStateMediaProjection.generation + 1
    );
    this.requestUpdate("emptyStateMedia", previous);
  }

  connectedCallback() {
    super.connectedCallback();
    ensureParlerUiStylesheet();
  }

  firstUpdated() {
    const viewport = this.querySelector(".thread-scroll");
    const panel = this.querySelector(".thread-panel");
    if (!viewport || !panel) return;
    this._threadRo = new ResizeObserver(() => {
      this.updateThreadOverflow();
      this.scrollThreadToBottom(false);
    });
    this._threadRo.observe(viewport);
    this._threadRo.observe(panel);
  }

  willUpdate() {
    // The ordered artifact list is the only source for the expansion's lifetime: when the source
    // answer or chart artifact is no longer rendered, the layer goes with it (§4.5 C1b-3).
    if (this.#expandedChart && !this.#expandedChartSource()) this.#expandedChart = null;
  }

  updated(changed) {
    if (changed.has("conversationId")) {
      this.#clearTableDisclosureClientState();
    }
    if (changed.has("_chatState")) {
      this.scrollThreadToBottom(false);
      this.#revokePrintBlockedIfRowGone();
    }
    this.#settleCachedEmptyStateMedia();
  }

  /** @param {number} generation @param {"loaded" | "failed"} outcome */
  #settleEmptyStateMedia(generation, outcome) {
    const next = settleEmptyStateMediaProjection(
      this.#emptyStateMediaProjection,
      generation,
      outcome
    );
    if (next === this.#emptyStateMediaProjection) return;
    this.#emptyStateMediaProjection = next;
    this.requestUpdate();
  }

  #settleCachedEmptyStateMedia() {
    const projection = this.#emptyStateMediaProjection;
    if (projection.phase !== "loading") return;
    const image = this.querySelector('[part="empty-state-media"]');
    if (!(image instanceof HTMLImageElement) || image.complete !== true) return;
    this.#settleEmptyStateMedia(
      projection.generation,
      image.naturalWidth > 0 ? "loaded" : "failed"
    );
  }

  /** Client-local table disclosure overrides; reset when the bound conversation changes. */
  #clearTableDisclosureClientState() {
    this.#tableDisclosureUserTouched.clear();
    this.#tableDisclosureCollapsed.clear();
    this.#hostContextExpandedKeys.clear();
    this.#chartViewStates.clear();
    this.#expandedChart = null;
  }

  /** @param {string} requestId */
  #assistantBubble(requestId) {
    return [...this.querySelectorAll("[data-parler-assistant-request]")].find(
      (b) => b.getAttribute("data-parler-assistant-request") === requestId
    ) ?? null;
  }

  /** @param {import('./lib/types.js').ChatRow} row @param {string} artifactKey */
  #isChartExpanded(row, artifactKey) {
    const x = this.#expandedChart;
    return x !== null && x.requestId === String(row.requestId ?? "") && x.artifactKey === artifactKey;
  }

  /** The answer row and chart artifact behind the current expansion; null when either is gone. */
  #expandedChartSource() {
    const x = this.#expandedChart;
    if (!x) return null;
    const row = this._chatState.rows.find(
      (r) => r.kind === "assistant" && String(r.requestId ?? "") === x.requestId
    );
    if (!row) return null;
    const raw =
      Array.isArray(row.artifacts) && row.artifacts.length > 0
        ? row.artifacts
        : buildArtifactsFromLegacyBuckets(row.charts, row.tables);
    const artifacts = orderArtifactsForDisplay(raw);
    const artifact = artifacts.find((a) => a.type === "chart" && a.key === x.artifactKey && a.chart);
    return artifact ? { row, artifact, artifacts } : null;
  }

  /**
   * "Expand" / "Close" from a chart card (design §4.5 C1b-3). Opening records the thread scroll
   * position and moves focus to the layer's close control once rendered.
   * @param {CustomEvent} ev @param {import('./lib/types.js').ChatRow} row @param {string} artifactKey
   */
  #onChartExpand(ev, row, artifactKey) {
    ev.preventDefault();
    ev.stopPropagation();
    if (this.#isChartExpanded(row, artifactKey)) {
      this.#closeChartExpansion({ returnFocus: true });
      return;
    }
    const thread = this.querySelector(".thread-scroll");
    this.#expandedChart = {
      requestId: String(row.requestId ?? ""),
      artifactKey,
      scrollTop: thread ? thread.scrollTop : 0,
    };
    this.requestUpdate();
    void this.updateComplete.then(() => {
      const close = this.querySelector(".chart-expand-layer .chart-expand-close");
      if (close instanceof HTMLElement) close.focus();
    });
  }

  /**
   * Close the expansion: the answer renders its card again, the thread scroll position returns,
   * and focus goes back to that card's expand control, else the answer bubble, else the thread.
   * @param {{ returnFocus: boolean }} opts
   */
  #closeChartExpansion({ returnFocus }) {
    const x = this.#expandedChart;
    if (!x) return;
    this.#expandedChart = null;
    this.requestUpdate();
    void this.updateComplete.then(async () => {
      const thread = this.querySelector(".thread-scroll");
      if (thread) thread.scrollTop = x.scrollTop;
      if (!returnFocus) return;
      const bubble = this.#assistantBubble(x.requestId);
      const card = bubble
        ? [...bubble.querySelectorAll("parler-ui-chart")].find(
            (c) => c.getAttribute("data-parler-artifact-key") === x.artifactKey
          )
        : null;
      if (card && "updateComplete" in card) {
        await card.updateComplete;
        await card.updateComplete;
      }
      const toggle = card?.querySelector(".chart-expand-toggle");
      if (toggle instanceof HTMLElement) {
        toggle.focus();
        return;
      }
      if (bubble instanceof HTMLElement) {
        if (!bubble.hasAttribute("tabindex")) bubble.setAttribute("tabindex", "-1");
        bubble.focus();
        return;
      }
      if (thread instanceof HTMLElement) thread.focus();
    });
  }

  /** Escape closes; Tab cycles inside the layer as the fallback when `inert` is unsupported. @param {KeyboardEvent} ev */
  #onChartExpandKeydown = (ev) => {
    if (ev.key === "Escape") {
      ev.preventDefault();
      ev.stopPropagation();
      this.#closeChartExpansion({ returnFocus: true });
      return;
    }
    if (ev.key !== "Tab") return;
    const layer = this.querySelector(".chart-expand-layer");
    if (!layer) return;
    // Endpoints come from the controls keyboard traversal can reach right now (closed
    // disclosures hide their contents), recomputed on every press as disclosures open and close.
    const target = focusTrapTarget(layer, { shiftKey: ev.shiftKey, active: document.activeElement });
    if (!target) return;
    ev.preventDefault();
    target.focus();
  };

  /** The modal expand layer over the thread area (design §4.5 C1b-3); one per widget instance. */
  #renderChartExpandLayer() {
    const src = this.#expandedChartSource();
    if (!src) return nothing;
    const { row, artifact, artifacts } = src;
    const viewKey = chartViewStateKey(this.conversationId, row.requestId, artifact.key);
    const rawTitle = typeof artifact.chart.title === "string" ? artifact.chart.title.trim() : "";
    const title = rawTitle || "Chart";
    return html`<div
      class="chart-expand-layer"
      part="chart-expand"
      role="dialog"
      aria-modal="true"
      aria-label=${title}
      @keydown=${this.#onChartExpandKeydown}
    >
      <div class="chart-expand-tools">
        <span class="chart-expand-title">${title}</span>
        <button
          type="button"
          class="chart-expand-close"
          part="chart-expand-close"
          aria-label="Close expanded chart"
          @click=${() => this.#closeChartExpansion({ returnFocus: true })}
        >
          Close
        </button>
      </div>
      <div class="chart-expand-body">
        <parler-ui-chart
          part="chart"
          data-parler-artifact-key=${artifact.key}
          .chart=${artifact.chart}
          .viewState=${this.#chartViewStates.get(viewKey) ?? null}
          .expandable=${true}
          .expanded=${true}
          .colorKeys=${this.#chartColorContext(row, artifacts, artifact.key).colorKeys}
          .colorNote=${this.#chartColorContext(row, artifacts, artifact.key).colorNote}
          @chart-view-change=${(ev) => this.#onChartViewChange(viewKey, ev)}
          @chart-view-data=${(ev) => this.#onChartViewData(ev, row, artifacts)}
          @chart-expand=${(ev) => this.#onChartExpand(ev, row, artifact.key)}
        ></parler-ui-chart>
      </div>
    </div>`;
  }

  /**
   * "View data" from a chart card: locate the chart's direct parent table in the same answer,
   * expand it and move focus to its disclosure (design §4.1). Cancelling the event tells the card
   * the table was found; otherwise the card shows its own paged chart-data view.
   * @param {CustomEvent<{ chart: object }>} ev
   * @param {import('./lib/types.js').ChatRowAssistant} row
   * @param {import('./lib/artifactPresentation.js').RowArtifact[]} artifacts
   */
  #onChartViewData(ev, row, artifacts) {
    const parent = parentTableArtifactForChart(artifacts, ev.detail?.chart);
    const artifactKey = ev.target instanceof Element ? ev.target.getAttribute("data-parler-artifact-key") ?? "" : "";
    const expanded = this.#isChartExpanded(row, artifactKey);
    if (!parent && !expanded) return;
    ev.preventDefault();
    ev.stopPropagation();
    // From the expand layer, the expansion closes first so focus never lands behind the overlay
    // (§4.5 C1b-3); the answer's own card then receives the in-card data view when no parent
    // table exists.
    if (expanded) this.#closeChartExpansion({ returnFocus: false });
    if (parent) {
      const storageKey = this.#tableDisclosureStorageKey(row, parent.key);
      this.#tableDisclosureCollapsed.set(storageKey, false);
      this.#tableDisclosureUserTouched.add(storageKey);
    }
    this.requestUpdate();
    void this.updateComplete.then(async () => {
      const bubble = this.#assistantBubble(String(row.requestId ?? ""));
      if (parent) {
        const wraps = bubble ? bubble.querySelectorAll(".parler-data-table-wrap") : [];
        const wrap = [...wraps].find((w) => w.getAttribute("data-parler-artifact-key") === parent.key);
        const disclosure = wrap?.querySelector(".parler-data-table-disclosure");
        if (disclosure instanceof HTMLElement) {
          if (typeof disclosure.scrollIntoView === "function") disclosure.scrollIntoView({ block: "nearest" });
          disclosure.focus();
        }
        return;
      }
      const card = bubble
        ? [...bubble.querySelectorAll("parler-ui-chart")].find(
            (c) => c.getAttribute("data-parler-artifact-key") === artifactKey
          )
        : null;
      if (card && typeof card.openDataView === "function") {
        await card.updateComplete;
        await card.updateComplete;
        card.openDataView();
      }
    });
  }

  /** @param {string} key @param {CustomEvent<{ state: object }>} ev */
  /** C3b-2a (design §8.7): the category highlighted in each group — transient, never persisted or printed. */
  #groupHighlight = new Map();

  #onCategoryHighlight(groupId, ev) {
    ev.stopPropagation();
    if (!groupId) return;
    const key = typeof ev.detail?.key === "string" && ev.detail.key ? ev.detail.key : null;
    const current = this.#groupHighlight.get(groupId) ?? null;
    if (key === current) return;
    if (key === null) this.#groupHighlight.delete(groupId);
    else this.#groupHighlight.set(groupId, key);
    this.requestUpdate();
  }

  /** C3b-2a: the colour context of one chart artifact rendered outside its group card (expand layer, print). */
  #chartColorContext(row, artifacts, artifactKey) {
    return chartColorContextFor(groupArtifactsForLayout(artifacts, row.groups ?? []), artifactKey);
  }

  #onChartViewChange(key, ev) {
    ev.stopPropagation();
    const state = ev.detail?.state;
    if (!state || typeof state !== "object") return;
    this.#chartViewStates.set(key, state);
    this.requestUpdate();
  }

  /** Revoke Blob fallback when the owning assistant row is no longer in the thread. */
  #revokePrintBlockedIfRowGone() {
    const pb = this._printBlocked;
    if (!pb?.requestId) return;
    const rid = pb.requestId;
    const rows = this._chatState?.rows;
    if (!Array.isArray(rows)) return;
    const stillThere = rows.some(
      (r) =>
        r &&
        r.kind === "assistant" &&
        String(r.requestId ?? "") === String(rid)
    );
    if (!stillThere) {
      this.#revokePrintBlockedBlob();
      this._printBlocked = null;
      this.requestUpdate();
    }
  }

  disconnectedCallback() {
    this.#closeAssistantInfo();
    this.disconnectAlwaysOn();
    super.disconnectedCallback();
    this.clearTurnWatchdog();
    this.#clearCopyFeedbackTimer();
    this.#clearGatewayInvokeErrorTimer();
    this.#clearTableDisclosureClientState();
    this.#revokePrintBlockedBlob();
    this._printBlocked = null;
    this._threadRo?.disconnect();
    this._threadRo = null;
  }

  #clearCopyFeedbackTimer() {
    if (this.#copyFeedbackClearTimer !== undefined) {
      clearTimeout(this.#copyFeedbackClearTimer);
      this.#copyFeedbackClearTimer = undefined;
    }
  }

  #clearGatewayInvokeErrorTimer() {
    if (this.#gatewayInvokeErrorClearTimer !== undefined) {
      clearTimeout(this.#gatewayInvokeErrorClearTimer);
      this.#gatewayInvokeErrorClearTimer = undefined;
    }
  }

  #scheduleGatewayInvokeErrorClear() {
    this.#clearGatewayInvokeErrorTimer();
    this.#gatewayInvokeErrorClearTimer = setTimeout(() => {
      this.#gatewayInvokeErrorClearTimer = undefined;
      this._gatewayInvokeError = "";
      this.requestUpdate();
    }, GATEWAY_ERROR_CLEAR_MS);
  }

  #revokePrintBlockedBlob() {
    const u = this._printBlocked?.blobUrl;
    if (typeof u === "string" && u.length > 0) {
      try {
        URL.revokeObjectURL(u);
      } catch {
        /* ignore */
      }
    }
  }

  #scheduleCopyFeedbackClear() {
    this.#clearCopyFeedbackTimer();
    this.#copyFeedbackClearTimer = setTimeout(() => {
      this.#copyFeedbackClearTimer = undefined;
      this._copyActionFeedback = null;
      this.requestUpdate();
    }, COPY_FEEDBACK_MS);
  }

  #parlerGatewayRowActionsEnabled() {
    return !!this.useSubmitUserPromptOnConversation && !!this.#twAlwaysOnClient;
  }

  #feedbackThrottleOk(assistantMessageId) {
    const key = String(assistantMessageId ?? "").trim();
    if (!key) return false;
    const now = Date.now();
    const last = this.#feedbackThrottleAt.get(key) ?? 0;
    if (now - last < 500) return false;
    this.#feedbackThrottleAt.set(key, now);
    return true;
  }

  #closeAssistantInfo() {
    if (this.#assistantInfoEscapeHandler) {
      document.removeEventListener("keydown", this.#assistantInfoEscapeHandler);
      this.#assistantInfoEscapeHandler = null;
    }
    this.#assistantInfoOpenAid = null;
    this.#assistantInfoLines = null;
    this.requestUpdate();
  }

  /**
   * @param {HTMLElement | null | undefined} target
   */
  #pulseActionButton(target) {
    if (!target || !target.classList) return;
    target.classList.remove("assistant-action--tap-pulse");
    void target.offsetWidth;
    target.classList.add("assistant-action--tap-pulse");
    const done = () => {
      try {
        target.classList.remove("assistant-action--tap-pulse");
      } catch {
        /* ignore */
      }
    };
    target.addEventListener("animationend", done, { once: true });
    window.setTimeout(done, 450);
  }

  /**
   * @param {Event} ev
   * @param {import('./lib/types.js').ChatRow} row
   */
  #toggleAssistantInfo(ev, row) {
    ev.preventDefault();
    ev.stopPropagation();
    if (row.kind !== "assistant" || !this.#parlerGatewayRowActionsEnabled()) return;
    const aid = String(row.assistantMessageId ?? "").trim();
    if (!aid) return;
    if (this.#assistantInfoOpenAid === aid) {
      this.#closeAssistantInfo();
      return;
    }
    const cid = boundTransportString(this, "conversationId", "conversation-id").trim();
    let lines = buildAssistantTurnInfoEntries(row, cid);
    if (!lines.length) lines = [{ label: "Assistant message", value: aid }];
    this.#assistantInfoOpenAid = aid;
    this.#assistantInfoLines = lines;
    if (this.#assistantInfoEscapeHandler) {
      document.removeEventListener("keydown", this.#assistantInfoEscapeHandler);
    }
    this.#assistantInfoEscapeHandler = (e) => {
      if (e.key === "Escape") this.#closeAssistantInfo();
    };
    document.addEventListener("keydown", this.#assistantInfoEscapeHandler);
    this.requestUpdate();
  }

  /** Seeds {@link #lastFeedbackRatingByAssistantId} from hydrated assistant rows (server last-win). */
  #applyLastFeedbackFromHistoryRows(rows) {
    this.#lastFeedbackRatingByAssistantId.clear();
    if (!Array.isArray(rows)) return;
    for (const r of rows) {
      if (!r || r.kind !== "assistant") continue;
      const aid = String(r.assistantMessageId ?? "").trim();
      if (!aid) continue;
      const fr = String(r.feedbackRating ?? "").trim().toLowerCase();
      if (fr === "up" || fr === "down") {
        this.#lastFeedbackRatingByAssistantId.set(aid, fr);
      }
    }
  }

  /**
   * @param {Event} ev
   * @param {import('./lib/types.js').ChatRow} row
   * @param {'up'|'down'} rating
   */
  #onAssistantFeedback(ev, row, rating) {
    ev.preventDefault();
    ev.stopPropagation();
    if (row.kind !== "assistant" || !this.#parlerGatewayRowActionsEnabled()) return;
    this.#pulseActionButton(/** @type {HTMLElement | undefined} */ (ev.currentTarget));
    const aid = String(row.assistantMessageId ?? "").trim();
    if (!aid || !this.#feedbackThrottleOk(aid)) return;
    const cid = boundTransportString(this, "conversationId", "conversation-id").trim();
    const client = this.#twAlwaysOnClient;
    if (!cid || !client) return;
    const prev = this.#lastFeedbackRatingByAssistantId.get(aid) ?? "";
    client.invokeService(
      {
        entityName: cid,
        entityType: EntityTypes.Things,
        serviceName: "RecordAssistantFeedback",
        parameters: buildRecordAssistantFeedbackParams(cid, aid, rating, row.requestId ?? "", prev),
      },
      (err) => {
        if (err) {
          const m = err instanceof Error ? err.message : String(err);
          logTransportWarn("RecordAssistantFeedback invoke failed", err);
          if (m.includes("FEEDBACK_PERSIST_FAILED")) {
            this._gatewayInvokeError =
              "Feedback could not be saved. Try again or reconnect.";
          } else {
            this._gatewayInvokeError = "Feedback could not be saved.";
          }
          this.#scheduleGatewayInvokeErrorClear();
          this.requestUpdate();
          return;
        }
        this.#lastFeedbackRatingByAssistantId.set(aid, rating);
        this.requestUpdate();
      }
    );
  }

  /**
   * @param {Event} ev
   * @param {import('./lib/types.js').ChatRow} row
   */
  /**
   * @param {import('./lib/types.js').ChatRowAssistant} row
   * @param {string} artifactKey
   */
  #tableDisclosureStorageKey(row, artifactKey) {
    return `${row.requestId ?? ""}:${artifactKey}`;
  }

  /**
   * @param {string} storageKey
   * @param {boolean} defaultCollapsed
   */
  #effectiveTableCollapsed(storageKey, defaultCollapsed) {
    if (this.#tableDisclosureUserTouched.has(storageKey)) {
      return this.#tableDisclosureCollapsed.get(storageKey) ?? defaultCollapsed;
    }
    return defaultCollapsed;
  }

  /**
   * @param {Event} ev
   * @param {string} storageKey
   * @param {boolean} defaultCollapsed
   */
  #toggleTableDisclosure(ev, storageKey, defaultCollapsed) {
    ev.preventDefault();
    ev.stopPropagation();
    const cur = this.#effectiveTableCollapsed(storageKey, defaultCollapsed);
    this.#tableDisclosureCollapsed.set(storageKey, !cur);
    this.#tableDisclosureUserTouched.add(storageKey);
    this.requestUpdate();
  }

  /**
   * @param {KeyboardEvent} ev
   * @param {string} storageKey
   * @param {boolean} defaultCollapsed
   */
  #onTableDisclosureKeydown(ev, storageKey, defaultCollapsed) {
    if (ev.key === "Enter" || ev.key === " ") {
      this.#toggleTableDisclosure(ev, storageKey, defaultCollapsed);
    }
  }

  #onAssistantCutoff(ev, row) {
    ev.preventDefault();
    ev.stopPropagation();
    if (row.kind !== "assistant" || !this.#parlerGatewayRowActionsEnabled()) return;
    const iso = String(row.completedAt ?? "").trim();
    if (!iso) return;
    if (typeof globalThis.confirm === "function") {
      if (!globalThis.confirm(CUTOFF_CONFIRM_MESSAGE)) return;
    }
    const aid = String(row.assistantMessageId ?? "").trim();
    const throttleKey = aid.length > 0 ? `cutoff:${aid}` : `cutoff-rid:${row.requestId ?? ""}`;
    if (!this.#feedbackThrottleOk(throttleKey)) return;
    const cid = boundTransportString(this, "conversationId", "conversation-id").trim();
    const client = this.#twAlwaysOnClient;
    if (!cid || !client) return;
    client.invokeService(
      {
        entityName: cid,
        entityType: EntityTypes.Things,
        serviceName: "SetConversationHistoryCutoff",
        parameters: buildSetConversationHistoryCutoffParams(iso),
      },
      (err) => {
        if (err) {
          const m = err instanceof Error ? err.message : String(err);
          logTransportWarn("SetConversationHistoryCutoff invoke failed", m);
          if (m.includes("CUTOFF_BLOCKED_HITL_PENDING")) {
            this._gatewayInvokeError =
              "History cutoff blocked: resolve pending approvals first.";
          } else {
            this._gatewayInvokeError = "History cutoff could not be applied.";
          }
          this.#scheduleGatewayInvokeErrorClear();
          this.requestUpdate();
          return;
        }
        const targetAid = aid;
        const rows = this._chatState.rows;
        const idx = targetAid
          ? rows.findIndex(
              (r) =>
                r &&
                r.kind === "assistant" &&
                String(r.assistantMessageId ?? "").trim() === targetAid
            )
          : rows.findIndex(
              (r) =>
                r &&
                r.kind === "assistant" &&
                String(r.completedAt ?? "").trim() === iso &&
                String(r.requestId ?? "").trim() === String(row.requestId ?? "").trim()
            );
        if (idx < 0) {
          logTransportWarn("SetConversationHistoryCutoff: assistant row not found after success", targetAid || iso);
          return;
        }
        this._chatState = {
          ...this._chatState,
          rows: rows.slice(idx + 1),
        };
        this.requestUpdate();
      }
    );
  }

  /**
   * @param {string} feedbackKey
   * @param {string} plain
   */
  #copyPlainTextToClipboard(feedbackKey, plain) {
    const ok = () => {
      this._copyActionFeedback = { rid: feedbackKey, kind: "copied" };
      this.#scheduleCopyFeedbackClear();
      this.requestUpdate();
    };
    const fail = () => {
      this._copyActionFeedback = { rid: feedbackKey, kind: "failed" };
      this.#scheduleCopyFeedbackClear();
      this.requestUpdate();
    };
    void (async () => {
      let copied = false;
      try {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(plain);
          copied = true;
        }
      } catch {
        copied = false;
      }
      if (!copied) {
        copied = writeClipboardWithSelectionFallback(document, { plain, html: "" });
      }
      if (copied) ok();
      else fail();
    })();
  }

  /** @param {Event} ev @param {import('./lib/types.js').ChatRowUser} row @param {number} i */
  #onUserPromptCopy(ev, row, i) {
    ev.preventDefault();
    ev.stopPropagation();
    const plain = typeof row.text === "string" ? row.text : "";
    this.#copyPlainTextToClipboard(`u-${i}-prompt`, plain);
  }

  /**
   * @param {Event} ev
   * @param {import('./lib/types.js').ChatRowUser} row
   * @param {number} i
   */
  #onHostContextRawCopy(ev, row, i) {
    ev.preventDefault();
    ev.stopPropagation();
    if (!row.hostContext) return;
    const resolved = resolveHostContextRawJson(row.hostContext, this._chatState.rows, i);
    if (!resolved.available) return;
    const plain = formatHostContextRawForDisplay(resolved.text);
    this.#copyPlainTextToClipboard(`u-${i}-hostctx`, plain);
  }

  /** @param {Event} ev @param {number} i */
  #toggleHostContextDisclosure(ev, i) {
    ev.preventDefault();
    const key = `u-${i}`;
    if (this.#hostContextExpandedKeys.has(key)) {
      this.#hostContextExpandedKeys.delete(key);
    } else {
      this.#hostContextExpandedKeys.add(key);
    }
    this.requestUpdate();
  }

  /** @param {Event} ev @param {number} i */
  #onHostContextDisclosureKeydown(ev, i) {
    if (ev.key !== "Enter" && ev.key !== " ") return;
    this.#toggleHostContextDisclosure(ev, i);
  }

  /**
   * @param {import('./lib/types.js').ChatRowUser} row
   * @param {number} i
   */
  renderUserHostContext(row, i) {
    const snap = row.hostContext;
    if (!shouldShowHostContextDisclosure(snap)) return nothing;
    const expanded = this.#hostContextExpandedKeys.has(`u-${i}`);
    const resolved = resolveHostContextRawJson(snap, this._chatState.rows, i);
    const fb = this._copyActionFeedback;
    const copyStatus =
      fb?.rid === `u-${i}-hostctx` && fb.kind === "copied"
        ? "Copied to clipboard."
        : fb?.rid === `u-${i}-hostctx` && fb.kind === "failed"
          ? "Copy failed — check permissions or try again."
          : "";
    return html`
      <div class="user-host-context">
        <button
          type="button"
          class="user-host-context-disclosure"
          part="host-context-disclosure"
          aria-expanded=${expanded ? "true" : "false"}
          @click=${(e) => this.#toggleHostContextDisclosure(e, i)}
          @keydown=${(e) => this.#onHostContextDisclosureKeydown(e, i)}
        >
          <span class="user-host-context-disclosure-icon" aria-hidden="true"
            >${expanded ? "▾" : "▸"}</span
          >
          <span class="user-host-context-disclosure-label"
            >${hostContextDisclosureSummaryLabel(snap)}</span
          >
        </button>
        ${expanded
          ? html`<div class="user-host-context-panel" part="host-context-content">
              ${resolved.available
                ? html`<div class="user-host-context-raw-row">
                    <button
                      type="button"
                      class="assistant-action assistant-action--copy assistant-action--icon user-host-context-copy"
                      part="message-action"
                      data-action="copy"
                      aria-label="Copy host context JSON"
                      title="Copy host context JSON"
                      @click=${(e) => this.#onHostContextRawCopy(e, row, i)}
                    >
                      ${iconRowCopy()}
                    </button>
                    <pre class="user-host-context-raw">${formatHostContextRawForDisplay(
                      resolved.text
                    )}</pre>
                  </div>`
                : html`<p class="user-host-context-unavailable">
                    raw JSON unavailable in loaded history
                  </p>`}
              ${copyStatus
                ? html`<span
                    class="assistant-action-status"
                    part="notice"
                    data-severity=${fb?.kind === "copied" ? "success" : "danger"}
                    role="status"
                    >${copyStatus}</span
                  >`
                : nothing}
            </div>`
          : nothing}
      </div>
    `;
  }

  #onAssistantCopy(ev, row) {
    ev.preventDefault();
    ev.stopPropagation();
    if (row.kind !== "assistant") return;
    const plain = typeof row.markdown === "string" ? row.markdown : "";
    const rendered = md.render(plain);
    const parts = buildCopyClipboardParts(plain, rendered);
    const rid = row.requestId ?? "";
    const ok = () => {
      this._copyActionFeedback = { rid, kind: "copied" };
      this.#scheduleCopyFeedbackClear();
      this.requestUpdate();
    };
    const fail = () => {
      this._copyActionFeedback = { rid, kind: "failed" };
      this.#scheduleCopyFeedbackClear();
      this.requestUpdate();
    };
    void (async () => {
      let copied = false;
      try {
        if (navigator.clipboard && typeof ClipboardItem !== "undefined") {
          await navigator.clipboard.write([
            new ClipboardItem({
              "text/html": new Blob([parts.html], { type: "text/html" }),
              "text/plain": new Blob([parts.plain], { type: "text/plain" }),
            }),
          ]);
          copied = true;
        }
      } catch {
        copied = false;
      }
      if (!copied) {
        try {
          if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(parts.plain);
            copied = true;
          }
        } catch {
          copied = false;
        }
      }
      if (!copied) {
        copied = writeClipboardWithSelectionFallback(document, parts);
      }
      if (copied) {
        ok();
      } else {
        fail();
      }
    })();
  }

  /**
   * @param {import('./lib/types.js').ChatRow} row
   * @param {number} i
   */
  #onAssistantPrint(ev, row, i) {
    ev.preventDefault();
    ev.stopPropagation();
    if (row.kind !== "assistant" || this.#printInFlight) return;
    const bubble = ev.currentTarget?.closest?.(".assistant-bubble");
    if (!bubble || !(bubble instanceof HTMLElement)) return;

    this.#revokePrintBlockedBlob();
    this._printBlocked = null;

    const docHtml = this.buildAssistantPrintHtml(row, i, bubble);

    this.#printInFlight = true;
    this.requestUpdate();

    void (async () => {
      try {
        const w = openPrintDocumentWindow();
        const blocked = !w || w.closed;
        if (blocked) {
          const blobUrl = URL.createObjectURL(
            new Blob([docHtml], { type: "text/html" })
          );
          this._printBlocked = {
            requestId: row.requestId,
            blobUrl,
            message:
              "Print blocked by browser. Allow popups for this site, then click Print again.",
          };
          this.requestUpdate();
          return;
        }
        w.document.open();
        w.document.write(docHtml);
        w.document.close();
        await waitForPrintDocument(w);
      } catch (err) {
        logTransportWarn("assistant-print", err);
      } finally {
        this.#printInFlight = false;
        this.requestUpdate();
      }
    })();
  }

  /**
   * Assemble the standalone print document for one assistant row (chart-enhancement design §4.5
   * C1b-1): clone the bubble, strip controls, expand tables and notes, then rebuild every chart
   * card from the row's ordered artifacts with the full analysis view so membership never
   * depends on what is currently mounted, and finally apply the print palette.
   * @param {import('./lib/types.js').ChatRowAssistant} row
   * @param {number} i row index, for the preceding user prompt
   * @param {HTMLElement} bubble the live assistant bubble
   * @returns {string}
   */
  buildAssistantPrintHtml(row, i, bubble) {
    const clone = /** @type {HTMLElement} */ (bubble.cloneNode(true));
    stripNonPrintableControls(clone);
    expandTablesForPrint(clone);
    expandChartNotesForPrint(clone);
    const rawArtifacts =
      Array.isArray(row.artifacts) && row.artifacts.length > 0
        ? row.artifacts
        : buildArtifactsFromLegacyBuckets(row.charts, row.tables);
    const artifacts = orderArtifactsForDisplay(rawArtifacts);
    const chartTheme = resolveChartTheme(this);
    const bubbleWidth = Number(bubble.getBoundingClientRect?.().width) || 0;
    const conv = String(this.conversationId ?? "").trim();
    assembleChartCardsForPrint(clone, artifacts, (artifact) => {
      const live = [...bubble.querySelectorAll("parler-ui-chart[data-parler-artifact-key]")].find(
        (el) => el.getAttribute("data-parler-artifact-key") === artifact.key
      );
      // §4.6: the print width is the card's outer width `W`, never the plot host, which the size
      // policy may already have narrowed. C3a (§8.1, §10.2): a card that sits in a chart grid prints
      // single-column at the bubble width; the on-screen column width is never printed as a thumbnail.
      const inGrid = Boolean(live?.closest?.(".chart-grid"));
      const liveWidth = inGrid ? 0 : Number(live?.querySelector("figure.chart-card")?.getBoundingClientRect?.().width) || 0;
      const state = reconcileChartViewState(
        this.#chartViewStates.get(chartViewStateKey(conv, row.requestId, artifact.key)) ?? null,
        artifact.chart
      );
      const colorKeys = this.#chartColorContext(row, artifacts, artifact.key).colorKeys;
      const viewSummary = describeChartView(state, artifact.chart, chartLegendItems(artifact.chart, chartTheme, colorKeys));
      return renderChartPrintCard({
        chart: artifact.chart,
        theme: chartTheme,
        width: liveWidth || bubbleWidth || 640,
        viewSummary,
        doc: this.ownerDocument,
        colorKeys,
      });
    });
    const printTheme = resolvePortablePrintThemeFromElement(this);
    rewriteClonedChartPaletteForPrint(clone, printTheme);
    const userPrompt = findPriorUserPromptText(this._chatState.rows, i);
    return buildPrintDocumentHtml(clone.innerHTML, {
      userPromptText: userPrompt,
      requestId: row.requestId,
      conversationId: conv,
      printTheme,
    });
  }

  #dismissPrintBlocked(ev) {
    ev.preventDefault();
    ev.stopPropagation();
    this.#revokePrintBlockedBlob();
    this._printBlocked = null;
    this.requestUpdate();
  }

  /**
   * @param {import('./lib/types.js').ChatRow} row
   * @param {number} i
   * @param {{ copy: boolean, print: boolean, showFooter: boolean, feedback?: boolean, cutoff?: boolean, info?: boolean }} eligibility
   */
  renderAssistantRowActions(row, i, eligibility) {
    const rid = row.requestId ?? "";
    const fb = this._copyActionFeedback;
    const copyStatus =
      fb?.rid === rid && fb.kind === "copied"
        ? "Copied to clipboard."
        : fb?.rid === rid && fb.kind === "failed"
          ? "Copy failed — check permissions or try again."
          : "";
    const pb =
      this._printBlocked && this._printBlocked.requestId === rid
        ? this._printBlocked
        : null;
    const showInner = eligibility.showFooter;
    const gw = this.#parlerGatewayRowActionsEnabled();
    const feedbackOn = gw && !!eligibility.feedback;
    const infoOn = gw && !!eligibility.info;
    const cutoffOn = gw && !!eligibility.cutoff;
    const fbAid =
      row.kind === "assistant" ? String(row.assistantMessageId ?? "").trim() : "";
    const fbSel = fbAid ? String(this.#lastFeedbackRatingByAssistantId.get(fbAid) ?? "") : "";
    const infoOpen = infoOn && this.#assistantInfoOpenAid === fbAid;
    return html`
      <div class="assistant-row-actions" data-no-print>
        <div
          class="assistant-row-actions-inner ${showInner
            ? ""
            : "assistant-row-actions-inner--placeholder"}"
          part="message-actions"
        >
          ${eligibility.copy
            ? html`<button
                type="button"
                class="assistant-action assistant-action--copy assistant-action--icon"
                part="message-action"
                data-action="copy"
                aria-label="Copy answer text"
                title="Copy answer text"
                @click=${(e) => this.#onAssistantCopy(e, row)}
              >
                ${iconRowCopy()}
              </button>`
            : nothing}
          ${eligibility.print
            ? html`<button
                type="button"
                class="assistant-action assistant-action--print assistant-action--icon"
                part="message-action"
                data-action="print"
                aria-label="Print this answer"
                title="Print this answer"
                ?disabled=${this.#printInFlight}
                @click=${(e) => this.#onAssistantPrint(e, row, i)}
              >
                ${iconRowPrint()}
              </button>`
            : nothing}
          ${feedbackOn
            ? html`<button
                type="button"
                class="assistant-action assistant-action--feedback assistant-action--icon${fbSel === "up"
                  ? " assistant-action--feedback-selected"
                  : ""}"
                part="message-action"
                data-action="feedback-up"
                aria-label="Thumbs up"
                title="Thumbs up"
                aria-pressed=${fbSel === "up"}
                @click=${(e) => this.#onAssistantFeedback(e, row, "up")}
              >
                ${iconRowThumbUp()}
              </button>`
            : nothing}
          ${feedbackOn
            ? html`<button
                type="button"
                class="assistant-action assistant-action--feedback assistant-action--icon${fbSel === "down"
                  ? " assistant-action--feedback-selected"
                  : ""}"
                part="message-action"
                data-action="feedback-down"
                aria-label="Thumbs down"
                title="Thumbs down"
                aria-pressed=${fbSel === "down"}
                @click=${(e) => this.#onAssistantFeedback(e, row, "down")}
              >
                ${iconRowThumbDown()}
              </button>`
            : nothing}
          ${infoOn
            ? html`<button
                type="button"
                class="assistant-action assistant-action--info assistant-action--icon${infoOpen
                  ? " assistant-action--info-selected"
                  : ""}"
                part="message-action"
                data-action="info"
                aria-label="Turn details"
                title="Turn details"
                aria-expanded=${infoOpen}
                @click=${(e) => this.#toggleAssistantInfo(e, row)}
              >
                ${iconRowInfo()}
              </button>`
            : nothing}
          ${cutoffOn
            ? html`<button
                type="button"
                class="assistant-action assistant-action--cutoff assistant-action--icon"
                part="message-action"
                data-action="cutoff"
                aria-label="Cut off history at this answer"
                title="Cut off history at this answer"
                @click=${(e) => this.#onAssistantCutoff(e, row)}
              >
                ${iconRowCutoff()}
              </button>`
            : nothing}
          ${copyStatus
            ? html`<span
                class="assistant-action-status"
                part="notice"
                data-severity=${fb?.kind === "copied" ? "success" : "danger"}
                role="status"
                aria-live="polite"
                >${copyStatus}</span
              >`
            : nothing}
          ${pb
            ? html`<div
                class="assistant-print-blocked"
                part="notice"
                data-severity="warning"
                role="status"
              >
                <p class="assistant-print-blocked-msg">${pb.message}</p>
                <p>
                  <a
                    class="assistant-print-blocked-link"
                    href=${pb.blobUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    >Open print view</a
                  >
                </p>
                <button
                  type="button"
                  class="assistant-print-blocked-dismiss"
                  part="message-action"
                  data-action="dismiss"
                  @click=${(e) => this.#dismissPrintBlocked(e)}
                >
                  Dismiss
                </button>
              </div>`
            : nothing}
        </div>
      </div>
    `;
  }

  clearTurnWatchdog() {
    if (this._watchdog !== undefined) {
      clearTimeout(this._watchdog);
      this._watchdog = undefined;
    }
  }

  /** @param {import('./lib/types.js').UiEvent} evt */
  applyUiEvent(evt) {
    const busyBefore = this._chatState.busy;
    const ridBefore = this._chatState.activeRequestId;
    if (
      evt.type === "session.done" ||
      evt.type === "session.error" ||
      evt.type === "session.superseded" ||
      evt.type === "session.cancelled" ||
      evt.type === "session.cancel_unsupported_local"
    ) {
      this.clearTurnWatchdog();
    }
    this._chatState = reduceUiEvent(this._chatState, evt);
    if (evt.type === "approval.required") {
      this._approvalComment = "";
    } else if (!this._chatState.approvalGate) {
      this._approvalComment = "";
    }
    const busyAfter = this._chatState.busy;
    const ridAfter = this._chatState.activeRequestId;
    if (this.#cancelStoppingUi && busyBefore && !busyAfter) {
      this.#clearCancelStopUiTimers();
    } else if (this.#cancelStoppingUi && ridBefore && ridAfter && ridAfter !== ridBefore) {
      this.#clearCancelStopUiTimers();
    }
  }

  /**
   * Apply one server wire JSON object (same shapes as Parler WebSocket).
   * `session.superseded` is ignored when `conversation_id` does not match this element’s `conversationId` (broadcast-safe).
   * @param {unknown} raw
   */
  applyWireMessage(raw) {
    if (raw && typeof raw === "object") {
      const cid = String(raw.conversation_id ?? "").trim();
      const mine = boundTransportString(this, "conversationId", "conversation-id");
      if (mine && cid && cid !== mine) {
        return;
      }
      if (raw.type === "session.superseded") {
        this.#historyLoadEpoch++;
        this.#liveSessionInvalidUntilReconnect = true;
        this.#agentConnExt = "";
        this.#agentConnImpl = "";
        this.#supportsCancellationAgent = false;
        if (this._historyPhase === "loading") {
          this._historyPhase = "ready";
        }
      }
    }
    const ui = wireToUiEvent(raw);
    if (ui) this.applyUiEvent(ui);
  }

  /** @param {string} jsonString */
  applyWireMessageString(jsonString) {
    try {
      const raw = JSON.parse(jsonString);
      this.applyWireMessage(raw);
    } catch {
      this.applyUiEvent({
        type: "session.error",
        requestId: this._chatState.activeRequestId ?? "",
        message: "Invalid live JSON payload",
      });
    }
  }

  reset() {
    this.clearTurnWatchdog();
    this.#clearPreRequestIdAwait();
    this.#clearCancelStopUiTimers();
    this.#bumpInvokeCompletionGeneration();
    this._chatState = createInitialChatState();
    this._draft = "";
    this.#resetPromptHistoryNavigation();
    this._approvalComment = "";
    this._stickToBottom = true;
    this._stickBump += 1;
    this.#wireBacklog.length = 0;
    this.#lastFeedbackRatingByAssistantId.clear();
    this.#feedbackThrottleAt.clear();
    this._gatewayInvokeError = "";
    this.#clearGatewayInvokeErrorTimer();
  }

  /** ThingWorx service-friendly alias for {@link #reset}. */
  resetChat() {
    this.reset();
  }

  /** ThingWorx service-friendly alias for {@link #applyWireMessageString}. */
  applyLiveJson(jsonString) {
    this.applyWireMessageString(jsonString);
  }

  /**
   * Replace the thread from a history document (see docs/ui/AI_PARLER_HISTORY.md).
   * Clears pre-`request_id` wait, bumps invoke completion generation, and drains wire backlog so stale invokes cannot affect the replaced thread.
   * @param {unknown} obj
   */
  hydrateHistoryFromObject(obj) {
    this.clearTurnWatchdog();
    this.#alignTransportStateForHistoryReplace();
    try {
      const rows = parseHistoryRows(obj);
      this._draft = "";
      this.#resetPromptHistoryNavigation();
      this._approvalComment = "";
      this._stickToBottom = true;
      this._stickBump += 1;
      this._chatState = chatUiStateFromHistoryRows(rows);
      this.#applyLastFeedbackFromHistoryRows(rows);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this._chatState = {
        ...this._chatState,
        busy: false,
        activeRequestId: null,
        error: msg,
      };
    }
  }

  /**
   * @param {string} jsonString
   */
  hydrateHistoryFromJsonString(jsonString) {
    try {
      const obj = JSON.parse(jsonString);
      this.hydrateHistoryFromObject(obj);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.clearTurnWatchdog();
      this.#alignTransportStateForHistoryReplace();
      this._chatState = {
        ...this._chatState,
        busy: false,
        activeRequestId: null,
        error: msg,
      };
    }
  }

  /** ThingWorx service-friendly alias for {@link #hydrateHistoryFromJsonString}. */
  loadHistoryJson(jsonString) {
    if (this._chatState.busy || this._chatState.approvalGate) {
      console.warn(PARLER_UI_LOG, "LoadHistoryJson ignored: busy or approval gate open");
      return;
    }
    this.#historyLoadEpoch++;
    this.hydrateHistoryFromJsonString(jsonString);
    this._historyPhase = "ready";
    this.requestUpdate();
  }

  /** Non-empty trimmed `conversationId` — required to send (for now). */
  get hasConversationId() {
    return boundTransportString(this, "conversationId", "conversation-id").length > 0;
  }

  get canSend() {
    const draftOk =
      this._draft.trim().length > 0 &&
      !this._chatState.busy &&
      !this.#awaitingInvokeRequestId &&
      !this.disabled;
    if (!draftOk) return false;
    if (this.#liveSessionInvalidUntilReconnect) return false;
    if (this.loadHistoryOnBind && this._historyPhase !== "ready") return false;
    if (!this.#isAlwaysOnTransportReady()) return false;
    return (
      boundTransportString(this, "conversationId", "conversation-id").length > 0 &&
      boundTransportString(this, "agentThingName", "agent-thing-name").length > 0
    );
  }

  updateThreadOverflow() {
    const el = this.querySelector(".thread-scroll");
    this._threadHasOverflow = !!el && el.scrollHeight > el.clientHeight + 1;
  }

  /** @param {boolean} force */
  scrollThreadToBottom(force) {
    const el = this.querySelector(".thread-scroll");
    if (!el) return;
    if (!force && !this._stickToBottom) return;
    requestAnimationFrame(() => {
      const box = this.querySelector(".thread-scroll");
      if (!box) return;
      box.scrollTop = box.scrollHeight;
      this.updateThreadOverflow();
    });
  }

  handleScroll = () => {
    const el = this.querySelector(".thread-scroll");
    if (!el) return;
    this.updateThreadOverflow();
    const near =
      el.scrollHeight - el.scrollTop - el.clientHeight <= PIN_THRESHOLD_PX;
    this._stickToBottom = near;
    this._stickBump += 1;
  };

  jumpToLatest = () => {
    this._stickToBottom = true;
    this._stickBump += 1;
    requestAnimationFrame(() => {
      const el = this.querySelector(".thread-scroll");
      if (!el) return;
      el.scrollTop = el.scrollHeight;
      this.updateThreadOverflow();
    });
  };

  onDraftInput = (e) => {
    this._draft = /** @type {HTMLTextAreaElement} */ (e.target).value;
    this.#resetPromptHistoryNavigation();
  };

  onKeydown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (this.canSend) this.onSend();
      return;
    }
    if (this.#handlePromptHistoryKeydown(e)) {
      return;
    }
    if (PROMPT_HISTORY_EDITING_KEYS.has(e.key) && !e.isComposing) {
      this.#resetPromptHistoryNavigation();
    }
  };

  #onComposerPointerDown = () => {
    this.#resetPromptHistoryNavigation();
  };

  #resetPromptHistoryNavigation() {
    this.#promptHistoryNavigation = initialPromptHistoryNavigationState();
  }

  /** @param {HTMLTextAreaElement} textarea @param {number} pos */
  #setComposerSelection(textarea, pos) {
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(pos, pos);
    });
  }

  /** @param {KeyboardEvent} e */
  #handlePromptHistoryKeydown(e) {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return false;
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey || e.isComposing) return false;
    const textarea = isTextAreaElement(e.currentTarget) ? e.currentTarget : null;
    if (!textarea) return false;

    const alreadyNavigating = this.#promptHistoryNavigation.index >= 0;
    if (alreadyNavigating && !textareaCaretAtEnd(textarea)) {
      return false;
    }
    if (e.key === "ArrowUp" && !alreadyNavigating && !textareaCaretAtStart(textarea)) {
      return false;
    }
    if (e.key === "ArrowDown" && !alreadyNavigating) {
      return false;
    }

    const next = navigatePromptHistory(
      userPromptHistoryFromRows(this._chatState.rows),
      this.#promptHistoryNavigation,
      e.key === "ArrowUp" ? "up" : "down",
      this._draft
    );
    if (!next.handled) return false;

    e.preventDefault();
    this.#promptHistoryNavigation = next.state;
    this._draft = next.draft;
    textarea.value = next.draft;
    this.#setComposerSelection(textarea, next.draft.length);
    this.requestUpdate();
    return true;
  }

  onSend = () => {
    const text = this._draft.trim();
    console.info(PARLER_UI_LOG, "onSend (start)", {
      draftLen: (this._draft || "").length,
      textLen: text.length,
      textPreview: previewString(text),
      conversationId: boundTransportString(this, "conversationId", "conversation-id") || "(empty)",
      busy: this._chatState.busy,
      disabled: this.disabled,
      canSend: this.canSend,
    });
    if (!text) {
      console.warn(PARLER_UI_LOG, "onSend aborted: empty trimmed text");
      return;
    }
    if (!this.#isAlwaysOnTransportReady()) {
      console.warn(
        PARLER_UI_LOG,
        "onSend aborted: ConnectAndBind required (no SubmitUserPrompt / parler-user-message event; AlwaysOn Transport only)."
      );
      return;
    }
    if (!boundTransportString(this, "conversationId", "conversation-id")) {
      console.warn(PARLER_UI_LOG, "onSend aborted: conversationId empty");
      return;
    }
    if (!boundTransportString(this, "agentThingName", "agent-thing-name")) {
      console.warn(PARLER_UI_LOG, "onSend aborted: agentThingName empty");
      return;
    }
    if (this._chatState.busy) {
      console.warn(PARLER_UI_LOG, "onSend aborted: busy");
      return;
    }
    if (this.disabled) {
      console.warn(PARLER_UI_LOG, "onSend aborted: disabled");
      return;
    }

    this.#resetPromptHistoryNavigation();
    this._gatewayInvokeError = "";
    this.#clearGatewayInvokeErrorTimer();

    void this.#sendViaAlwaysOnSdk(text);
  };

  _setConnectionStatus(status) {
    const s = String(status || "").trim();
    if (this.connectionStatus === s) return;
    this.connectionStatus = s;
  }

  #clearPreRequestIdAwait() {
    if (this.#preRequestIdTimer !== undefined) {
      clearTimeout(this.#preRequestIdTimer);
      this.#preRequestIdTimer = undefined;
    }
    this.#awaitingInvokeRequestId = false;
  }

  #bumpInvokeCompletionGeneration() {
    this.#invokeCompletionGeneration++;
  }

  /** Invalidate in-flight invoke UI epoch, clear pre-`request_id` wait and wire backlog when history replaces the thread. */
  #alignTransportStateForHistoryReplace() {
    this.#clearCancelStopUiTimers();
    this.#clearPreRequestIdAwait();
    this.#bumpInvokeCompletionGeneration();
    this.#wireBacklog.length = 0;
  }

  #clearCancelStopUiTimers() {
    if (this.#cancelRetryTimer !== undefined) {
      clearTimeout(this.#cancelRetryTimer);
      this.#cancelRetryTimer = undefined;
    }
    if (this.#cancelBoundedTimer !== undefined) {
      clearTimeout(this.#cancelBoundedTimer);
      this.#cancelBoundedTimer = undefined;
    }
    this.#cancelStoppingUi = false;
    this.#cancelNotActiveRetryUsed = false;
    this.#cancelTargetRid = "";
  }

  /** @param {string} rid */
  #scheduleCancelBoundedFallback(rid) {
    if (this.#cancelBoundedTimer !== undefined) return;
    if (typeof window === "undefined") return;
    this.#cancelBoundedTimer = window.setTimeout(() => {
      this.#cancelBoundedTimer = undefined;
      if (!this.#cancelStoppingUi) return;
      const stillBusy =
        this._chatState.busy && this._chatState.activeRequestId === rid;
      if (!stillBusy) {
        this.#clearCancelStopUiTimers();
        return;
      }
      const cid = boundTransportString(this, "conversationId", "conversation-id");
      this.applyUiEvent({
        type: "session.cancelled",
        requestId: rid,
        conversationId: cid,
        message: "Stop request timed out waiting for server acknowledgement.",
      });
      this.requestUpdate();
    }, CANCEL_STOP_BOUNDED_TERMINAL_MS);
  }

  /**
   * @param {string} rawText
   * @param {"initial" | "retry"} phase
   * @param {string} rid
   */
  #parseAndHandleCancelUserPromptResponse(rawText, phase, rid) {
    const rawStr = String(rawText ?? "").trim();
    const parsed = parseCancelUserPromptResult(rawStr);
    if (!parsed.ok) {
      logTransportWarn("CancelUserPrompt: unparseable result", {
        reason: parsed.reason,
        sample: rawStr.slice(0, 120),
      });
    } else if (
      parsed.status !== "accepted" &&
      parsed.status !== "not_active" &&
      parsed.status !== "already_terminal" &&
      parsed.status !== "unsupported"
    ) {
      logTransportWarn("CancelUserPrompt: unexpected status", { status: parsed.status });
    }
    const plan = planCancelUserPromptDispatch(
      parsed,
      phase,
      this.#cancelNotActiveRetryUsed
    );
    const cid = boundTransportString(this, "conversationId", "conversation-id");
    switch (plan.kind) {
      case "schedule_fallback":
        this.#scheduleCancelBoundedFallback(rid);
        return;
      case "schedule_retry":
        this.#cancelNotActiveRetryUsed = true;
        if (typeof window === "undefined") {
          this.#scheduleCancelBoundedFallback(rid);
          return;
        }
        this.#cancelRetryTimer = window.setTimeout(() => {
          this.#cancelRetryTimer = undefined;
          const dispatched = this.#invokeCancelUserPrompt("retry", rid);
          if (!dispatched) {
            this.#clearCancelStopUiTimers();
            this._gatewayInvokeError =
              "Stop could not be sent. Check connection and try again.";
            this.#scheduleGatewayInvokeErrorClear();
            this.requestUpdate();
          }
        }, CANCEL_USER_PROMPT_RETRY_MS);
        return;
      case "apply_already_terminal":
        this.applyUiEvent({
          type: "session.cancelled",
          requestId: rid,
          conversationId: cid,
          message: plan.message,
        });
        return;
      case "apply_unsupported_local":
        this.applyUiEvent({
          type: "session.cancel_unsupported_local",
          requestId: rid,
          conversationId: cid,
        });
        if (plan.message) {
          this._gatewayInvokeError = plan.message;
          this.#scheduleGatewayInvokeErrorClear();
        }
        this.requestUpdate();
        return;
      case "clear_stopping_transient":
        this.#clearCancelStopUiTimers();
        if (plan.message) {
          this._gatewayInvokeError = plan.message;
          this.#scheduleGatewayInvokeErrorClear();
        }
        this.requestUpdate();
        return;
      default:
        return;
    }
  }

  /**
   * @param {"initial" | "retry"} phase
   * @param {string} rid
   * @returns {boolean} true if the invoke was queued on the AlwaysOn client
   */
  #invokeCancelUserPrompt(phase, rid) {
    const client = this.#twAlwaysOnClient;
    const cid = boundTransportString(this, "conversationId", "conversation-id");
    const agent = boundTransportString(this, "agentThingName", "agent-thing-name");
    if (!client || !cid || !agent || this.connectionStatus !== "connected") {
      return false;
    }
    const params = buildCancelUserPromptParams(rid, agent, "user_stop");
    logTransport("CancelUserPrompt invoke (ParlerGateway)", { phase, requestIdLen: rid.length });
    client.invokeService(
      {
        entityName: cid,
        serviceName: "CancelUserPrompt",
        entityType: EntityTypes.Things,
        parameters: params,
      },
      (err, result) => {
        if (this.#cancelTargetRid !== rid) {
          return;
        }
        if (err) {
          logTransportWarn("CancelUserPrompt invoke failed", String(err));
          this.#clearCancelStopUiTimers();
          this._gatewayInvokeError =
            "Stop request failed. Check connection and try again.";
          this.#scheduleGatewayInvokeErrorClear();
          this.requestUpdate();
          return;
        }
        const raw = stringFromInvokeResult(result);
        this.#parseAndHandleCancelUserPromptResponse(raw, phase, rid);
        this.requestUpdate();
      }
    );
    return true;
  }

  /** Global Stop (gateway user stop) — gated on `GetConnectionInfo.capabilities.supportsCancellation`. */
  onStopGenerating = () => {
    if (!this.#supportsCancellationAgent) {
      return;
    }
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) {
      return;
    }
    if (!this._chatState.busy || !this._chatState.activeRequestId) {
      return;
    }
    if (this.#cancelStoppingUi) {
      return;
    }
    if (this.#awaitingInvokeRequestId) {
      return;
    }
    const rid = this._chatState.activeRequestId;
    this.#cancelNotActiveRetryUsed = false;
    this.#cancelTargetRid = rid;
    const dispatched = this.#invokeCancelUserPrompt("initial", rid);
    if (!dispatched) {
      this.#cancelTargetRid = "";
      this._gatewayInvokeError =
        "Stop could not be sent. Connect to ThingWorx and try again.";
      this.#scheduleGatewayInvokeErrorClear();
      this.requestUpdate();
      return;
    }
    this.#cancelStoppingUi = true;
    this.requestUpdate();
  };

  /** True when the primary composer control is Stop (vs Send). */
  get #showComposerStop() {
    return (
      this.#supportsCancellationAgent &&
      !!this._chatState.busy &&
      !!this._chatState.activeRequestId &&
      !this.#awaitingInvokeRequestId
    );
  }

  /**
   * Block Send and start {@link PRE_REQUEST_ID_TIMEOUT_MS} UI timeout until invoke returns `request_id`.
   * Does not remove the codec pending invoke; on timeout {@link #bumpInvokeCompletionGeneration} so a late
   * success callback is ignored and cannot double-start a turn after a retry (invoke completion generation).
   */
  #beginPreRequestIdAwait() {
    this.#clearPreRequestIdAwait();
    this.#awaitingInvokeRequestId = true;
    if (typeof window === "undefined") return;
    this.#preRequestIdTimer = window.setTimeout(() => {
      this.#preRequestIdTimer = undefined;
      if (!this.#awaitingInvokeRequestId) return;
      this.#awaitingInvokeRequestId = false;
      this.#wireBacklog.length = 0;
      logTransportWarn("Pre-request_id wait timed out (no invoke response yet)", {
        ms: PRE_REQUEST_ID_TIMEOUT_MS,
      });
      this.applyUiEvent({
        type: "session.error",
        requestId: "",
        message:
          "The platform did not return a request id for your message in time. Check AlwaysOn, agent services, and network.",
      });
      this.#bumpInvokeCompletionGeneration();
      this.requestUpdate();
    }, PRE_REQUEST_ID_TIMEOUT_MS);
  }

  /** End current turn if transport drops (agent-alwayson.md §6.4–§6.5); does not clear client ref by itself. */
  #abortActiveTurnOnTransportLoss(message) {
    const rid = this._chatState?.activeRequestId;
    if (!rid || !this._chatState?.busy) return;
    this.clearTurnWatchdog();
    this.applyUiEvent({
      type: "session.error",
      requestId: rid,
      message: message || "Transport interrupted.",
    });
  }

  /**
   * AlwaysOn failure: if a turn is active, end it; otherwise set thread-level error (e.g. ConnectAndBind idle failure).
   * @param {string} message
   */
  #surfaceAlwaysOnFailure(message) {
    const hadActiveTurn =
      !!(this._chatState?.activeRequestId && this._chatState?.busy);
    this.#abortActiveTurnOnTransportLoss(message);
    if (!hadActiveTurn) {
      this.applyUiEvent({
        type: "session.error",
        requestId: "",
        message: message || "Transport error.",
      });
      this.requestUpdate();
    }
  }

  /** Close AlwaysOn client; clear backlog. Safe to call when already disconnected. */
  disconnectAlwaysOn() {
    this.#clearPreRequestIdAwait();
    this.#clearCancelStopUiTimers();
    this.#bumpInvokeCompletionGeneration();
    this.#historyLoadEpoch++;
    this._historyPhase = "idle";
    this.#liveSessionInvalidUntilReconnect = false;
    this.#abortActiveTurnOnTransportLoss("Disconnected.");
    if (this.#twAlwaysOnClient) {
      try {
        this.#twAlwaysOnClient.close();
      } catch (e) {
        console.warn(PARLER_UI_LOG, "DisconnectAlwaysOn: client.close()", e);
      }
      this.#twAlwaysOnClient = null;
    }
    this.#activeBindContext = null;
    this.#connectInFlightContext = null;
    this.#wireBacklog.length = 0;
    this.#agentConnExt = "";
    this.#agentConnImpl = "";
    this.#supportsCancellationAgent = false;
    this._setConnectionStatus("disconnected");
  }

  /**
   * ThingWorx **ConnectAndBind** — open WS with **AppKey**, bind conversation Thing (**ReceiveMessage** subscription).
   * Call after Mashup obtains short-lived key (`docs/architecture/agent-alwayson.md`).
   */
  async connectAndBind() {
    logTransport("ConnectAndBind: invoked");

    const cid = boundTransportString(this, "conversationId", "conversation-id");
    const agent = boundTransportString(this, "agentThingName", "agent-thing-name");
    const key = boundTransportString(this, "appKey", "app-key");

    logTransport("ConnectAndBind: inputs (lengths only; appKey not logged)", {
      conversationIdLen: cid.length,
      agentThingNameLen: agent.length,
      appKeyLen: key.length,
      conversationIdPreview: cid ? previewString(cid, 48) : "(empty)",
      agentThingNamePreview: agent ? previewString(agent, 48) : "(empty)",
    });

    if (!cid) {
      logTransportWarn("ConnectAndBind aborted: conversationId empty", {
        hint: "Bind widget **ConversationId** or set attribute **conversation-id** before the service.",
      });
    }
    if (!agent) {
      logTransportWarn("ConnectAndBind aborted: agentThingName empty", {
        hint: "Bind **Agent Thing Name** or **agent-thing-name**.",
      });
    }
    if (!key) {
      logTransportWarn("ConnectAndBind aborted: appKey empty", {
        hint: "Bind **AppKey** or **app-key** (Mashup often applies this after a key service).",
      });
    }
    if (!cid || !agent || !key) {
      this._setConnectionStatus("disconnected");
      return;
    }

    const win = typeof window !== "undefined" ? window : undefined;
    const bindingRaw = boundTransportString(this, "thingworxWsUrl", "thingworx-ws-url");
    logTransport("ConnectAndBind: ThingworxWsUrl binding", {
      mashupFieldNonEmpty: !!bindingRaw,
      length: bindingRaw.length,
    });

    if (!bindingRaw && win?.location) {
      logTransport("ConnectAndBind: deriving WS URL from window.location", {
        href: win.location.href,
        protocol: win.location.protocol,
        hostname: win.location.hostname,
        port: win.location.port || "(default for protocol)",
      });
    } else if (!bindingRaw && !win?.location) {
      logTransportWarn("ConnectAndBind: no ThingworxWsUrl binding and no window.location", {});
    }

    const rawUrl = resolveThingworxWsUrlBinding(bindingRaw, win);
    logTransport("ConnectAndBind: resolved WebSocket URL string", rawUrl || "(empty)");

    if (!rawUrl) {
      logTransportWarn("ConnectAndBind aborted: could not resolve WebSocket URL", {
        hint: "Set **ThingworxWsUrl** explicitly if the page has no usable host (e.g. some embedded shells).",
      });
      this._setConnectionStatus("disconnected");
      return;
    }

    const bindContext = makeBindContext(cid, agent, rawUrl);
    if (
      this.connectionStatus === "connected" &&
      this.#twAlwaysOnClient &&
      !this.#liveSessionInvalidUntilReconnect &&
      sameBindContext(this.#activeBindContext, bindContext)
    ) {
      logTransport("ConnectAndBind: already connected for this context; skipping duplicate request", {
        conversationIdPreview: previewString(cid, 48),
        agentThingNamePreview: previewString(agent, 48),
      });
      return;
    }
    if (this.connectionStatus === "connecting" && sameBindContext(this.#connectInFlightContext, bindContext)) {
      logTransport("ConnectAndBind: connect already in flight for this context; skipping duplicate request", {
        conversationIdPreview: previewString(cid, 48),
        agentThingNamePreview: previewString(agent, 48),
      });
      return;
    }
    if (this.#recentAlwaysOnAuthFailure && this.#recentAlwaysOnAuthFailure.appKey !== key) {
      this.#recentAlwaysOnAuthFailure = null;
    }
    if (shouldSuppressRepeatedAuthFailure(this.#recentAlwaysOnAuthFailure, key, Date.now())) {
      const ageMs = Date.now() - this.#recentAlwaysOnAuthFailure.atMs;
      logTransportWarn("ConnectAndBind suppressed: previous auth failure for same appKey; refresh key before retrying", {
        ageMs,
        suppressMs: AUTH_FAILURE_RETRY_SUPPRESS_MS,
        previousMessage: this.#recentAlwaysOnAuthFailure.message,
      });
      this._setConnectionStatus("disconnected");
      return;
    }

    let parsed;
    try {
      parsed = parseThingworxWsUrl(rawUrl);
    } catch (e) {
      logTransportWarn("ConnectAndBind: parseThingworxWsUrl failed", e instanceof Error ? e.message : String(e));
      this._setConnectionStatus("disconnected");
      return;
    }

    logTransport("ConnectAndBind: parsed URL for WebSocket", {
      host: parsed.host,
      port: parsed.port,
      ssl: parsed.ssl,
      path: parsed.path,
      endpoint: parsed.endpoint,
    });

    this.disconnectAlwaysOn();
    this.#connectInFlightContext = bindContext;
    this._setConnectionStatus("connecting");
    logTransport("ConnectAndBind: status → connecting; opening AlwaysOn client…");

    let connectFailureSurfaced = false;
    try {
      const { client } = await promiseAlwaysOnConnectBind({
        parsed,
        appKey: key,
        conversationThingName: cid,
        onReceiveMessageText: (text) => this.#onAlwaysOnWireText(text),
        debugLog: logTransport,
        connectionCallback: (connected) => {
          if (!connected) {
            this.#clearPreRequestIdAwait();
            this.#bumpInvokeCompletionGeneration();
            this.#historyLoadEpoch++;
            this._historyPhase = "idle";
            this.#liveSessionInvalidUntilReconnect = false;
            this.#abortActiveTurnOnTransportLoss("Connection lost.");
            this.#twAlwaysOnClient = null;
            this._setConnectionStatus("disconnected");
          }
        },
        onClientError: (err) => {
          console.error(PARLER_UI_LOG, "AlwaysOn client error", err);
          const msg = err instanceof Error ? err.message : String(err);
          logTransportWarn("AlwaysOn client error", msg);
          this.#clearPreRequestIdAwait();
          this.#bumpInvokeCompletionGeneration();
          this.#historyLoadEpoch++;
          this._historyPhase = "idle";
          this.#liveSessionInvalidUntilReconnect = false;
          this.#surfaceAlwaysOnFailure(msg);
          connectFailureSurfaced = true;
          this.#twAlwaysOnClient = null;
          this._setConnectionStatus("disconnected");
        },
      });
      this.#twAlwaysOnClient = client;
      this.#activeBindContext = bindContext;
      this.#connectInFlightContext = null;
      this.#recentAlwaysOnAuthFailure = null;
      this._setConnectionStatus("connected");
      this.#liveSessionInvalidUntilReconnect = false;
      this._approvalComment = "";
      this._chatState = { ...this._chatState, approvalGate: null };
      this.requestUpdate();
      logTransport("ConnectAndBind: success", { connectionStatus: "connected" });
      this.#afterBindStartHistoryBootstrap(cid);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      logTransportWarn("ConnectAndBind failed (bind or network)", msg);
      this.#clearPreRequestIdAwait();
      this.#bumpInvokeCompletionGeneration();
      this.#historyLoadEpoch++;
      this._historyPhase = "idle";
      this.#liveSessionInvalidUntilReconnect = false;
      this.#activeBindContext = null;
      this.#connectInFlightContext = null;
      if (isLikelyAlwaysOnAuthFailure(msg)) {
        this.#recentAlwaysOnAuthFailure = { appKey: key, atMs: Date.now(), message: msg };
      }
      if (!connectFailureSurfaced) {
        this.#surfaceAlwaysOnFailure(msg);
      }
      this.#twAlwaysOnClient = null;
      this._setConnectionStatus("disconnected");
    }
  }

  /** ThingWorx service binding for {@link #connectAndBind}. */
  ConnectAndBind() {
    void this.connectAndBind();
  }

  /** ThingWorx service — closes AlwaysOn client created by {@link #connectAndBind}. */
  DisconnectAlwaysOn() {
    this.disconnectAlwaysOn();
  }

  #onAlwaysOnWireText(text) {
    console.info(PARLER_UI_LOG, "ReceiveMessage (wire string)", previewString(text, 200));
    if (
      this._historyPhase === "loading" &&
      this.loadHistoryOnBind &&
      !this.hasAttribute(DEMO_MOCK_SEND_ATTR)
    ) {
      const items = tryParseWireBatch(text);
      if (items) {
        const retained = [];
        for (const item of items) {
          if (!item || typeof item !== "object") continue;
          const wt = item.type;
          if (wt === "session.superseded") {
            const cid = String(item.conversation_id ?? "").trim();
            const mine = boundTransportString(this, "conversationId", "conversation-id");
            if (mine && cid && cid !== mine) continue;
            this.applyWireMessage(item);
            continue;
          }
          if (shouldDropWireTypeDuringHistoryLoad(wt)) continue;
          retained.push(item);
        }
        if (retained.length > 0) {
          const out =
            retained.length === 1 ? JSON.stringify(retained[0]) : JSON.stringify(retained);
          this.#wireBacklog.push(out);
          this.#drainWireBacklog();
        } else {
          this.requestUpdate();
        }
        return;
      }
    }
    this.#wireBacklog.push(text);
    this.#drainWireBacklog();
  }

  /**
   * Post-bind: `ParlerGateway.GetConnectionInfo` for transport version label (epoch-guarded with `#historyLoadEpoch`).
   * @param {string} cid
   * @param {string} agent
   * @param {number} epochAtStart
   */
  #invokeGetConnectionInfo(cid, agent, epochAtStart) {
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) {
      return;
    }
    const client = this.#twAlwaysOnClient;
    if (!client || !cid || !agent) {
      return;
    }
    client.invokeService(
      {
        entityName: cid,
        serviceName: "GetConnectionInfo",
        entityType: EntityTypes.Things,
        parameters: buildGetConnectionInfoParams(agent, WIDGET_PACKAGE_VERSION),
      },
      (err, result) => {
        if (connectionInfoEpochStale(epochAtStart, this.#historyLoadEpoch)) {
          return;
        }
        if (err) {
          const msg = err instanceof Error ? err.message : String(err);
          logTransportWarn("GetConnectionInfo invoke failed", msg);
          this.requestUpdate();
          return;
        }
        try {
          const jsonText = stringFromInvokeResult(result).trim();
          const parsed = parseConnectionInfoJson(jsonText);
          if (!parsed.ok) {
            if (parsed.reason === "schema") {
              logTransportWarn("GetConnectionInfo: unexpected schemaVersion", {
                detail: jsonText.slice(0, 200),
              });
            } else if (parsed.reason === "empty") {
              logTransportWarn("GetConnectionInfo: empty result string", {});
            } else {
              logTransportWarn("GetConnectionInfo: decode or parse threw", parsed.reason);
            }
            this.requestUpdate();
            return;
          }
          if (connectionInfoEpochStale(epochAtStart, this.#historyLoadEpoch)) {
            return;
          }
          this.#agentConnExt = parsed.extensionVersion;
          this.#agentConnImpl = parsed.implementationVersion;
          this.#supportsCancellationAgent = parsed.supportsCancellation;
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e);
          logTransportWarn("GetConnectionInfo: decode or parse threw", msg);
        }
        this.requestUpdate();
      }
    );
  }

  #connectionVersionSuffix() {
    return formatConnectionVersionSuffix(
      this.connectionStatus,
      this.#agentConnExt,
      WIDGET_PACKAGE_VERSION
    );
  }

  #connectionStatusTitle() {
    if (this.connectionStatus !== "connected") {
      return "AlwaysOn transport (ConnectAndBind / DisconnectAlwaysOn)";
    }
    const w = String(WIDGET_PACKAGE_VERSION || "").trim();
    const a = this.#agentConnExt;
    const ai = this.#agentConnImpl;
    let s = "AlwaysOn transport connected.";
    if (a) {
      s += ` agent=${a}`;
    }
    if (ai && ai !== a) {
      s += ` agent_build=${ai}`;
    }
    if (w) {
      s += ` widget=${w}`;
    }
    return s;
  }

  /**
   * After successful bind: optional auto history via GetConversationHistoryJson (docs/ui/load-history.md).
   * @param {string} cid conversationId / Gateway name
   */
  #afterBindStartHistoryBootstrap(cid) {
    this.#historyLoadEpoch++;
    const epochAtStart = this.#historyLoadEpoch;
    const agent = boundTransportString(this, "agentThingName", "agent-thing-name");
    this.#agentConnExt = "";
    this.#agentConnImpl = "";
    this.#supportsCancellationAgent = false;
    void this.#invokeGetConnectionInfo(cid, agent, epochAtStart);
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) {
      this._historyPhase = "ready";
      this.requestUpdate();
      return;
    }
    if (!this.loadHistoryOnBind) {
      this._historyPhase = "ready";
      this.requestUpdate();
      return;
    }
    const client = this.#twAlwaysOnClient;
    if (!client) {
      this._historyPhase = "ready";
      this.requestUpdate();
      return;
    }
    const svc =
      boundTransportString(this, "historyServiceName", "history-service-name") ||
      "GetConversationHistoryJson";
    this._historyPhase = "loading";
    this.requestUpdate();
    client.invokeService(
      {
        entityName: cid,
        serviceName: svc,
        entityType: EntityTypes.Things,
        parameters: buildGetConversationHistoryJsonParams(this.historyMaxItems),
      },
      (err, result) => {
        if (epochAtStart !== this.#historyLoadEpoch) {
          if (this._historyPhase === "loading") {
            this._historyPhase = "ready";
          }
          this.requestUpdate();
          return;
        }
        if (err) {
          const msg = err instanceof Error ? err.message : String(err);
          logTransportWarn("GetConversationHistoryJson invoke failed", msg);
          this.clearTurnWatchdog();
          this.#alignTransportStateForHistoryReplace();
          this._chatState = {
            ...this._chatState,
            busy: false,
            activeRequestId: null,
            error: msg || "Failed to load conversation history.",
          };
          this._historyPhase = "ready";
          this.requestUpdate();
          return;
        }
        try {
          const jsonText = stringFromInvokeResult(result).trim();
          if (!jsonText) {
            logTransportWarn("GetConversationHistoryJson: empty result string", {});
            this.clearTurnWatchdog();
            this.#alignTransportStateForHistoryReplace();
            this._chatState = {
              ...this._chatState,
              busy: false,
              activeRequestId: null,
              error: "Empty history response from platform.",
            };
            this._historyPhase = "ready";
            this.requestUpdate();
            return;
          }
          this.hydrateHistoryFromJsonString(jsonText);
        } catch (e) {
          const msg = e instanceof Error ? e.message : String(e);
          logTransportWarn(
            "GetConversationHistoryJson: decode or hydrate threw",
            msg
          );
          this.clearTurnWatchdog();
          this.#alignTransportStateForHistoryReplace();
          this._chatState = {
            ...this._chatState,
            busy: false,
            activeRequestId: null,
            error: msg || "Failed to load conversation history.",
          };
          this._historyPhase = "ready";
          this.requestUpdate();
          return;
        }
        this._historyPhase = "ready";
        this.requestUpdate();
      }
    );
  }

  /** @param {string} text */
  #requestIdFromWireLine(text) {
    try {
      const o = JSON.parse(text);
      const head = Array.isArray(o) ? o[0] : o;
      if (head && typeof head === "object" && head !== null) {
        return String(head.request_id ?? "").trim();
      }
    } catch {
      /* ignore */
    }
    return "";
  }

  /** @param {string} jsonText */
  #applyWireJsonLine(jsonText) {
    try {
      const parsed = JSON.parse(jsonText);
      const batch = Array.isArray(parsed) ? parsed : [parsed];
      for (const item of batch) {
        this.applyWireMessage(item);
      }
    } catch {
      /* ignore */
    }
  }

  #drainWireBacklog() {
    const rid = this._chatState.activeRequestId;
    if (!rid) return;
    let i = 0;
    let any = false;
    while (i < this.#wireBacklog.length) {
      const t = this.#wireBacklog[i];
      if (this.#requestIdFromWireLine(t) === rid) {
        this.#applyWireJsonLine(t);
        this.#wireBacklog.splice(i, 1);
        any = true;
      } else {
        i++;
      }
    }
    if (any) {
      this.requestUpdate();
    }
  }

  #isAlwaysOnTransportReady() {
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) return true;
    return this.#twAlwaysOnClient != null && this.connectionStatus === "connected";
  }

  /** @param {string} text */
  #sendViaAlwaysOnSdk(text) {
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) {
      this.#sendViaDemoMockWire(text);
      return;
    }
    const client = this.#twAlwaysOnClient;
    const cid = boundTransportString(this, "conversationId", "conversation-id");
    const agent = boundTransportString(this, "agentThingName", "agent-thing-name");
    if (!client || !cid || !agent) {
      console.warn(PARLER_UI_LOG, "Send (AlwaysOn) aborted: missing client, conversationId, or agentThingName");
      return;
    }
    this.#wireBacklog.length = 0;
    this.#invokeCompletionGeneration++;
    this.#clearCancelStopUiTimers();
    const invokeGen = this.#invokeCompletionGeneration;
    const useSubmit = !!this.useSubmitUserPromptOnConversation;
    const userTz = browserIanaTimeZone();
    const hostCtx = boundTransportStringWire(this, "hostScopeJson", "host-scope-json");
    const req = useSubmit
      ? {
          entityName: cid,
          serviceName: "SubmitUserPrompt",
          entityType: EntityTypes.Things,
          parameters: buildSubmitUserPromptParams(text, agent, userTz, hostCtx),
        }
      : {
          entityName: agent,
          serviceName: "ParlerStreamToRemoteThing",
          entityType: EntityTypes.Things,
          parameters: buildParlerStreamParams(text, cid, userTz, hostCtx),
        };

    this.#beginPreRequestIdAwait();
    this.requestUpdate();

    client.invokeService(req, (err, result) => {
      if (invokeGen !== this.#invokeCompletionGeneration) {
        logTransportWarn("invokeService callback ignored (stale invoke after timeout, disconnect, or newer send)", {
          invokeGen,
          current: this.#invokeCompletionGeneration,
        });
        return;
      }
      this.#clearPreRequestIdAwait();
      this.requestUpdate();
      if (err) {
        console.warn(PARLER_UI_LOG, "invokeService failed", err);
        this.#wireBacklog.length = 0;
        return;
      }
      const requestId = stringFromInvokeResult(result);
      if (!requestId) {
        console.warn(PARLER_UI_LOG, "invokeService returned empty request_id");
        this.#wireBacklog.length = 0;
        return;
      }

      this.clearTurnWatchdog();
      this._stickToBottom = true;
      this._stickBump += 1;
      const liveHostContext = buildLiveHostContextFromWire(hostCtx, this._chatState.rows);
      const next = startUserTurn(this._chatState, text, requestId, liveHostContext);
      this._chatState = next;
      this._draft = "";
      this.#resetPromptHistoryNavigation();
      this._watchdog = window.setTimeout(() => {
        if (!this._chatState.busy || this._chatState.activeRequestId !== requestId) {
          return;
        }
        this.applyUiEvent({
          type: "session.error",
          requestId,
          message:
            "This turn took too long or the host never completed the session. Check transport, backend, and LLM.",
        });
      }, TURN_TIMEOUT_MS);

      requestAnimationFrame(() => this.scrollThreadToBottom(true));
      this.requestUpdate();
      this.#drainWireBacklog();
    });
  }

  /** @returns {boolean} true while a decision for the open gate is in flight or already accepted */
  #approvalDecisionPending() {
    return isApprovalDecisionPending(this._chatState.approvalGate);
  }

  /** Decision-submit feedback inside the existing approval panel (no new part or token). */
  #renderApprovalSubmitStatus() {
    const gate = this._chatState.approvalGate;
    if (!gate) return nothing;
    if (gate.submitError) {
      return html`<p class="approval-submit-status approval-submit-status--error" role="alert">
        ${gate.submitError}
      </p>`;
    }
    const text = approvalSubmitStatusText(gate);
    if (!text) return nothing;
    return html`<p class="approval-submit-status" aria-live="polite">${text}</p>`;
  }

  #handleApprovalApprove = () => {
    this.#invokeSubmitParlerApprovalDecision("approve", "");
  };

  #handleApprovalCancel = () => {
    this.#invokeSubmitParlerApprovalDecision("cancel", "");
  };

  #handleApprovalRejectWithComment = () => {
    const c = this._approvalComment.trim();
    if (!c) return;
    this._approvalComment = "";
    this.#invokeSubmitParlerApprovalDecision("reject_with_comment", c);
  };

  /** @param {Event} e */
  #onApprovalCommentInput = (e) => {
    this._approvalComment = /** @type {HTMLTextAreaElement} */ (e.target).value;
  };

  /**
   * HITL: invoke {@code SubmitApprovalDecision} on the bound ParlerGateway (conversationId), same uplink as
   * {@code SubmitUserPrompt}; maps to {@code approval.decision} fields in API_CONTRACT.
   * @param {'approve'|'cancel'|'reject_with_comment'} decision
   * @param {string} comment required for {@code reject_with_comment}; empty for other decisions
   */
  #invokeSubmitParlerApprovalDecision(decision, comment) {
    const gate = this._chatState.approvalGate;
    if (!gate) return;
    if (this.hasAttribute(DEMO_MOCK_SEND_ATTR)) {
      logTransportWarn("SubmitApprovalDecision skipped in demo mock mode", {});
      return;
    }
    if (!isApprovalDecisionPending(gate)) {
      logTransport("SubmitApprovalDecision invoke (ParlerGateway)", {
        decision,
        pendingIdLen: gate.pendingId.length,
        requestIdLen: gate.requestId.length,
      });
    }
    submitApprovalDecision({
      gate,
      client: this.#twAlwaysOnClient,
      conversationId: boundTransportString(this, "conversationId", "conversation-id"),
      connected: this.connectionStatus === "connected",
      decision,
      comment,
      dispatch: (evt) => {
        this.applyUiEvent(evt);
        this.requestUpdate();
      },
      onTransportWarn: (msg, detail) => logTransportWarn(msg, detail ?? {}),
    });
  }

  /** @param {string} text */
  #sendViaDemoMockWire(text) {
    const cid = boundTransportString(this, "conversationId", "conversation-id");
    const agent = boundTransportString(this, "agentThingName", "agent-thing-name");
    if (!cid || !agent) {
      console.warn(
        PARLER_UI_LOG,
        "Demo mock Send aborted: conversationId or agentThingName empty (set both for canSend)."
      );
      return;
    }
    const requestId =
      typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
        ? crypto.randomUUID()
        : `demo-${Date.now()}-${Math.random().toString(16).slice(2)}`;

    this.#wireBacklog.length = 0;
    this.clearTurnWatchdog();
    this._stickToBottom = true;
    this._stickBump += 1;
    const hostCtx = boundTransportStringWire(this, "hostScopeJson", "host-scope-json");
    const liveHostContext = buildLiveHostContextFromWire(hostCtx, this._chatState.rows);
    const next = startUserTurn(this._chatState, text, requestId, liveHostContext);
    this._chatState = next;
    this._draft = "";
    this.#resetPromptHistoryNavigation();
    this._watchdog = window.setTimeout(() => {
      if (!this._chatState.busy || this._chatState.activeRequestId !== requestId) {
        return;
      }
      this.applyUiEvent({
        type: "session.error",
        requestId,
        message:
          "This turn took too long or the host never completed the session. Check transport, backend, and LLM.",
      });
    }, TURN_TIMEOUT_MS);

    requestAnimationFrame(() => this.scrollThreadToBottom(true));
    this.requestUpdate();
    this.#drainWireBacklog();
    void this.#runDemoMockWireReplay(requestId);
  }

  /** @param {string} requestId */
  async #runDemoMockWireReplay(requestId) {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const push = (obj) => this.applyWireMessage(obj);

    push({ type: "session.ack", request_id: requestId });
    await sleep(80);
    push({
      type: "activity",
      request_id: requestId,
      message: "Mock: thinking…",
    });
    await sleep(120);
    push({ type: "content.delta", request_id: requestId, delta: "Hello " });
    await sleep(80);
    push({
      type: "content.delta",
      request_id: requestId,
      delta: "from **mock** host.",
    });
    await sleep(100);
    push({
      type: "chart",
      request_id: requestId,
      chart: {
        kind: "line",
        title: "Sample series",
        x_label: "t",
        y_label: "v",
        series: [
          {
            name: "s1",
            x: ["2026-01-01T00:00:00Z", "2026-01-01T01:00:00Z", "2026-01-01T02:00:00Z"],
            y: [1, 3, 2],
          },
        ],
      },
    });
    await sleep(40);
    push({
      type: "table",
      request_id: requestId,
      table: {
        kind: "entity-list",
        columns: [
          { key: "name", label: "name", baseType: "STRING" },
          { key: "value", label: "value", baseType: "NUMBER" },
        ],
        rows: [
          { name: "a", value: 1 },
          { name: "b", value: 2 },
        ],
        shownRows: 2,
        totalRows: 2,
        exportStatus: "none",
        exportMessage: null,
        exportFile: null,
        exportRepository: null,
        exportDownloadUrl: null,
      },
    });
    await sleep(40);
    push({
      type: "tabular.tool_success",
      request_id: requestId,
      payload: {
        resultKind: "CACHED_TABULATE_INLINE",
        sourceCacheId: "mock-cache-id",
        insightEnvelope: {
          schemaVersion: "1",
          rowEstimate: 3,
          columns: [{ name: "t", baseType: "DATETIME" }],
        },
      },
    });
    await sleep(80);
    push({ type: "done", request_id: requestId });
  }

  ephemeralLineLastTurn() {
    const rows = this._chatState.rows;
    if (rows.length === 0) return null;
    const i = rows.length - 1;
    return activeTurnIndicatorText(this._chatState, rows[i], i, {
      presentation: this.progressPresentation,
      progressLabel: this.progressLabel,
      rateControlLabel: this.rateControlLabel,
    });
  }

  get showJumpToLatest() {
    void this._stickBump;
    return (
      this._chatState.rows.length > 0 &&
      this._threadHasOverflow &&
      !this._stickToBottom
    );
  }

  /** @param {import('./lib/types.js').ChatRow} row @param {number} i */
  renderRow(row, i) {
    if (row.kind === "user") {
      const fb = this._copyActionFeedback;
      const promptCopyStatus =
        fb?.rid === `u-${i}-prompt` && fb.kind === "copied"
          ? "Copied to clipboard."
          : fb?.rid === `u-${i}-prompt` && fb.kind === "failed"
            ? "Copy failed — check permissions or try again."
            : "";
      return html`
        <div class="turn turn-user">
          <div class="meta" part="message-meta">user</div>
          <div class="bubble user-bubble" part="user-message">
            ${this.renderUserHostContext(row, i)}
            <div class="user-message-actions" part="user-message-actions">
              <div class="user-prompt-row">
                <button
                  type="button"
                  class="assistant-action assistant-action--copy assistant-action--icon user-prompt-copy"
                  part="message-action"
                  data-action="copy"
                  aria-label="Copy user message"
                  title="Copy user message"
                  @click=${(e) => this.#onUserPromptCopy(e, row, i)}
                >
                  ${iconRowCopy()}
                </button>
                <div class="user-prompt-text">${row.text}</div>
              </div>
              ${promptCopyStatus
                ? html`<span
                    class="assistant-action-status"
                    part="notice"
                    data-severity=${fb?.kind === "copied" ? "success" : "danger"}
                    role="status"
                    >${promptCopyStatus}</span
                  >`
                : nothing}
            </div>
          </div>
        </div>
      `;
    }
    const ep =
      i === this._chatState.rows.length - 1
        ? this.ephemeralLineLastTurn()
        : null;
    const rateControlPulse =
      ep != null && row.kind === "assistant" && row.rateControlWaiting === true;
    const isActiveTurn =
      this._chatState.busy &&
      this._chatState.activeRequestId === row.requestId;
    const taskStatePanel =
      shouldRenderTaskStatePanelForRow(
        row,
        isActiveTurn,
        this.progressPresentation
      ) && row.taskState
        ? renderTaskStatePanel(row.taskState)
        : nothing;
    const eligibility = assistantRowActionsEligibility(row, this._chatState);
    const rawArtifacts =
      Array.isArray(row.artifacts) && row.artifacts.length > 0
        ? row.artifacts
        : buildArtifactsFromLegacyBuckets(row.charts, row.tables);
    const orderedArtifacts = orderArtifactsForDisplay(rawArtifacts);
    const rowHasChart = orderedArtifacts.some((a) => a.type === "chart");
    const tableCount = orderedArtifacts.filter((a) => a.type === "table").length;
    const defaultCollapsed = defaultTableCollapsed(rowHasChart, tableCount);
    /** One chart artifact: the live card, or its placeholder while expanded (C1b-3). */
    const layoutSlots = groupArtifactsForLayout(orderedArtifacts, row.groups ?? []);
    /** C3b-2a (design §8.7): a colour-shared member gets its keys, note and the group's transient highlight. */
    const renderChartArtifact = (artifact, color = null) => {
      if (this.#isChartExpanded(row, artifact.key)) {
        return html`<div
          class="chart-expand-placeholder"
          part="chart-expand-placeholder"
          data-parler-artifact-key=${artifact.key}
          data-no-print
          tabindex="-1"
        >
          Chart expanded
        </div>`;
      }
      const viewKey = chartViewStateKey(this.conversationId, row.requestId, artifact.key);
      const groupId = color?.groupId ?? null;
      return html`<parler-ui-chart
        part="chart"
        data-parler-artifact-key=${artifact.key}
        .chart=${artifact.chart}
        .viewState=${this.#chartViewStates.get(viewKey) ?? null}
        .expandable=${true}
        .colorKeys=${color?.colorKeys ?? null}
        .colorNote=${color?.colorNote ?? null}
        .highlightCategory=${groupId ? (this.#groupHighlight.get(groupId) ?? null) : null}
        @chart-view-change=${(ev) => this.#onChartViewChange(viewKey, ev)}
        @chart-view-data=${(ev) => this.#onChartViewData(ev, row, orderedArtifacts)}
        @chart-expand=${(ev) => this.#onChartExpand(ev, row, artifact.key)}
        @chart-category-highlight=${(ev) => this.#onCategoryHighlight(groupId, ev)}
      ></parler-ui-chart>`;
    };
    return html`
      <div class="turn turn-assistant">
        <div class="meta" part="message-meta">assistant</div>
        <div
          class="bubble assistant-bubble"
          part="assistant-message"
          data-parler-assistant-request=${row.requestId ?? ""}
        >
          ${taskStatePanel}
          ${layoutSlots.map((slot) => {
            if (slot.type === "chart-group" && slot.group && slot.slots) {
              // C3b-1 (design §8.5): a declared group card with ordered member slots; members share layout only.
              const group = slot.group;
              const interrupted = !group.final && !isActiveTurn;
              const stacked = group.layout === "stack";
              return html`<section
                class="chart-group"
                part="chart-group"
                data-parler-chart-group=${group.groupId}
                data-parler-chart-group-revision=${group.revision}
                data-parler-chart-group-final=${group.final ? "true" : "false"}
                data-parler-chart-group-layout=${group.layout}
                aria-label=${group.title || "Chart group"}
              >
                ${group.title ? html`<h4 class="chart-group-title" part="chart-group-title">${group.title}</h4>` : nothing}
                <div class="chart-group-slots ${stacked ? "" : "chart-grid"}" data-parler-chart-grid=${stacked ? nothing : slot.slots.length}>
                  ${slot.slots.map((s) => html`<div
                    class="chart-group-slot"
                    part="chart-group-slot"
                    data-parler-chart-group-member=${s.member.key}
                    data-parler-chart-group-state=${s.member.state}
                  >
                    ${s.artifact
                      ? renderChartArtifact(s.artifact, { colorKeys: s.colorKeys ?? null, colorNote: s.colorNote ?? null, groupId: group.groupId })
                      : html`<p class="chart-group-note" role="status">${chartGroupMemberStateText(s.member, false)}</p>`}
                  </div>`)}
                </div>
                <p class="chart-group-summary" part="chart-group-summary" role="status">
                  ${interrupted ? "Transport interrupted; the server's final result for this group is unknown. " : ""}${chartGroupSummaryText(group)}
                </p>
              </section>`;
            }
            if (slot.type === "chart-grid" && slot.charts) {
              // C3a (design §8.1): adjacent charts share a responsive grid; nothing else is shared.
              return html`<div
                class="chart-grid"
                part="chart-grid"
                data-parler-chart-grid=${slot.charts.length}
                data-parler-chart-grid-key=${slot.key}
              >
                ${slot.charts.map((artifact) => renderChartArtifact(artifact))}
              </div>`;
            }
            const artifact = slot.artifact;
            if (!artifact) return nothing;
            if (artifact.type === "chart" && artifact.chart) return renderChartArtifact(artifact);
            if (artifact.type === "table" && artifact.table) {
              const storageKey = this.#tableDisclosureStorageKey(
                row,
                artifact.key
              );
              const collapsed = this.#effectiveTableCollapsed(
                storageKey,
                defaultCollapsed
              );
              return renderTableBlock(artifact.table, {
                artifactKey: artifact.key,
                collapsed,
                summaryLabel: tableDisclosureSummaryLabel(artifact.table),
                onToggle: (e) =>
                  this.#toggleTableDisclosure(e, storageKey, defaultCollapsed),
                onKeydown: (e) =>
                  this.#onTableDisclosureKeydown(e, storageKey, defaultCollapsed),
              });
            }
            return nothing;
          })}
          ${row.insightEnvelopeLoose
            ? html`<p class="insight-envelope-hint" part="insight-hint">
                Further insight metadata
              </p>`
            : nothing}
          ${ep ? renderActiveTurnIndicator(ep, rateControlPulse) : nothing}
          <div class="md" part="markdown-content">${unsafeHTML(md.render(row.markdown))}</div>
          ${this.renderAssistantRowActions(row, i, eligibility)}
        </div>
      </div>
    `;
  }

  render() {
    const historyLoading = this.loadHistoryOnBind && this._historyPhase === "loading";
    const showEmptyState = this._chatState.rows.length === 0 && !historyLoading;
    const emptyStateMedia = this.#emptyStateMediaProjection;
    const chartExpanded = this.#expandedChartSource() !== null;
    return html`
      <div class="shell" part="shell">
        ${this.hideHeader
          ? nothing
          : html`
              <header class="hero" part="header" ?inert=${chartExpanded} aria-hidden=${chartExpanded ? "true" : nothing}>
                <h1>${this.headerTitle}</h1>
              </header>
            `}

        <div class="thread-wrap">
          <section
            class="thread-scroll"
            part="thread"
            aria-label="Chat messages"
            tabindex="0"
            ?inert=${chartExpanded}
            aria-hidden=${chartExpanded ? "true" : nothing}
            @scroll=${this.handleScroll}
          >
            <div class="thread-panel" part="thread-panel">
              ${historyLoading
                ? html`<p
                    class="history-loading"
                    part="notice"
                    data-severity="info"
                    role="status"
                  >
                    ${boundTransportString(this, "historyLoadingMessage", "history-loading-message")
                      .trim() || "Loading history…"}
                  </p>`
                : nothing}
              ${showEmptyState
                ? html`
                    <div class="empty-state" part="empty-state" aria-hidden="true">
                      ${emptyStateMedia.source
                        ? repeat(
                            [emptyStateMedia],
                            (projection) => projection.generation,
                            (projection) => html`
                              <img
                                class="empty-state-media"
                                part="empty-state-media"
                                .src=${projection.source}
                                alt=""
                                draggable="false"
                                ?hidden=${projection.phase !== "loaded"}
                                @load=${() =>
                                  this.#settleEmptyStateMedia(
                                    projection.generation,
                                    "loaded"
                                  )}
                                @error=${() =>
                                  this.#settleEmptyStateMedia(
                                    projection.generation,
                                    "failed"
                                  )}
                              />
                            `
                          )
                        : nothing}
                      ${emptyStateMedia.phase === "loaded"
                        ? nothing
                        : html`<div
                            class="empty-state-label"
                            part="empty-state-label"
                          >
                            Parler
                          </div>`}
                    </div>
                  `
                : nothing}
              ${repeat(
                this._chatState.rows,
                (row, idx) =>
                  row.kind === "user" ? `u-${idx}` : `a-${row.requestId}`,
                (row, idx) => this.renderRow(row, idx)
              )}
              ${this._gatewayInvokeError
                ? html`<p
                    class="err gateway-invoke-err"
                    part="notice"
                    data-severity="danger"
                    role="alert"
                  >
                    ${this._gatewayInvokeError}
                  </p>`
                : nothing}
              ${this._chatState.error
                ? html`<p
                    class="err"
                    part="notice"
                    data-severity="danger"
                    role="alert"
                  >
                    ${this._chatState.error}
                  </p>`
                : nothing}
            </div>
          </section>
          ${this.#renderChartExpandLayer()}
        </div>

        ${this._chatState.approvalGate
          ? html`
              <div
                class="approval-gate"
                part="approval-panel"
                role="region"
                aria-label="Approval required"
              >
                <h3>
                  ${this._chatState.approvalGate.summary.title || "Confirm action"}
                </h3>
                <dl>
                  ${this._chatState.approvalGate.summary.lines.map(
                    (ln) => html`<dt>${ln.label}</dt><dd>${ln.value}</dd>`
                  )}
                </dl>
                ${this._chatState.approvalGate.actions.includes("reject_with_comment")
                  ? html`
                      <label class="approval-comment-label">Comment (required to reject)</label>
                      <textarea
                        class="approval-comment-field"
                        part="approval-input"
                        rows="3"
                        aria-label="Rejection comment"
                        .value=${this._approvalComment}
                        @input=${this.#onApprovalCommentInput}
                        placeholder="Why should this action not proceed?"
                      ></textarea>
                    `
                  : nothing}
                ${this.#renderApprovalSubmitStatus()}
                <div class="approval-actions">
                  ${this._chatState.approvalGate.actions.includes("approve")
                    ? html`<button
                        type="button"
                        class="btn-approve"
                        part="approval-primary-action"
                        ?disabled=${this.#approvalDecisionPending()}
                        @click=${this.#handleApprovalApprove}
                      >
                        Approve
                      </button>`
                    : nothing}
                  ${this._chatState.approvalGate.actions.includes("cancel")
                    ? html`<button
                        type="button"
                        class="btn-cancel"
                        part="approval-secondary-action"
                        ?disabled=${this.#approvalDecisionPending()}
                        @click=${this.#handleApprovalCancel}
                      >
                        Cancel
                      </button>`
                    : nothing}
                  ${this._chatState.approvalGate.actions.includes("reject_with_comment")
                    ? html`<button
                        type="button"
                        class="btn-reject-comment"
                        part="approval-danger-action"
                        ?disabled=${this.#approvalDecisionPending() ||
                        this._approvalComment.trim().length === 0}
                        @click=${this.#handleApprovalRejectWithComment}
                      >
                        Reject with comment
                      </button>`
                    : nothing}
                </div>
              </div>
            `
          : nothing}

        <div class="status-row">
          <p
            class="conn-status conn-status--${this.connectionStatus}"
            part="connection-status"
            aria-live="polite"
            title=${this.#connectionStatusTitle()}
          >
            Transport: ${this.connectionStatus}${this.#connectionVersionSuffix()}
          </p>
          ${this.showJumpToLatest
            ? html`
                <button
                  type="button"
                  class="jump-latest"
                  part="floating-action"
                  aria-label="Jump to latest"
                  ?inert=${chartExpanded}
                  aria-hidden=${chartExpanded ? "true" : nothing}
                  @click=${this.jumpToLatest}
                >
                  Latest
                </button>
              `
            : nothing}
        </div>

        <footer class="composer" part="composer" ?inert=${chartExpanded} aria-hidden=${chartExpanded ? "true" : nothing}>
          <textarea
            class="input"
            part="composer-input"
            rows="3"
            placeholder=${this.placeholder}
            .value=${this._draft}
            ?disabled=${this._chatState.busy || this.disabled}
            @input=${this.onDraftInput}
            @keydown=${this.onKeydown}
            @pointerdown=${this.#onComposerPointerDown}
            @mousedown=${this.#onComposerPointerDown}
          ></textarea>
          ${this.#showComposerStop
            ? html`<button
                type="button"
                class="send send--stop"
                part="stop-action"
                ?disabled=${this.#cancelStoppingUi}
                title=${this.#cancelStoppingUi ? "Stopping…" : "Stop generating"}
                aria-label=${this.#cancelStoppingUi ? "Stopping" : "Stop generating"}
                @click=${this.onStopGenerating}
              >
                ${this.#cancelStoppingUi ? "…" : "Stop"}
              </button>`
            : html`<button
                type="button"
                class="send"
                part="primary-action"
                ?disabled=${!this.canSend}
                title="Send"
                aria-label="Send"
                @click=${this.onSend}
              >
                ${this._chatState.busy ? "…" : "Send"}
              </button>`}
        </footer>
        ${this.#assistantInfoOpenAid && this.#assistantInfoLines
          ? html`
              <div class="assistant-info-layer" role="presentation">
                <div
                  class="assistant-info-scrim"
                  part="overlay-scrim"
                  @click=${() => this.#closeAssistantInfo()}
                ></div>
                <div
                  class="assistant-info-popover"
                  part="popover"
                  role="dialog"
                  aria-modal="true"
                  aria-label="Turn details"
                  @click=${(e) => e.stopPropagation()}
                >
                  <div class="assistant-info-popover-head">
                    <span class="assistant-info-popover-title">Turn details</span>
                    <button
                      type="button"
                      class="assistant-info-popover-close"
                      part="popover-close"
                      aria-label="Close"
                      @click=${() => this.#closeAssistantInfo()}
                    >
                      ×
                    </button>
                  </div>
                  <dl class="assistant-info-dl">
                    ${this.#assistantInfoLines.map(
                      (ln) =>
                        html`<dt>${ln.label}</dt>
                          <dd>${ln.value}</dd>`
                    )}
                  </dl>
                </div>
              </div>
            `
          : nothing}
      </div>
    `;
  }
}

export const PARLER_UI_TAG = "parler-ui";

if (!customElements.get(PARLER_UI_TAG)) {
  customElements.define(PARLER_UI_TAG, ParlerUi);
}
