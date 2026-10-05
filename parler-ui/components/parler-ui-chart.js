import { LitElement, html, nothing, svg } from "lit";
import { ref, createRef } from "lit/directives/ref.js";
import { select } from "d3-selection";
import { appendHeatLegend, chartLegendItems, drawChart,
  createTextMeasurer,
} from "./chart-draw.js";
import {
  chartThemeSignature,
  DEFAULT_CHART_RENDER_THEME,
  resolveChartTheme, resolveSeriesSlot } from "./chart-theme.js";
import { chartDataNotes, chartDataPage, chartDataRows } from "../lib/chartDataNotes.js";
import {
  describeChartView,
  isChartViewDefault,
  isSeriesHidden,
  reconcileChartViewState,
  resetChartView,
  setNotesOpen,
  setSelectedRange,
  setViewXDomain,
  setYDomainPolicy,
  showAllSeries,
  toggleSeriesVisibility,
  toggleSliceFocus,
} from "../lib/chartViewState.js";
import { X_VIEW_STEPS, clampXRange, clampXSelection, formatXRange, formatXValue } from "../lib/chartXDomain.js";

/** Resolution of the range control over the full X domain (§4.5: 0–1000, minimum span 1). */
const RANGE_STEPS = X_VIEW_STEPS;
/** Minimum horizontal pointer travel, in logical px, before an in-plot drag counts as a zoom. */
const DRAG_ZOOM_MIN_PX = 8;

/**
 * Chart card for the parler-ui thread (light DOM), in the order of design §4.1: title; meta
 * line (window, values, limits); plot; legend; actions; folded data notes. Title, legend and
 * notes are ordinary DOM so they wrap on narrow hosts and never scale with the SVG; their color
 * and type come from the chart tokens through the effective CSS variables, so ThemeMode, token
 * overrides and ordinary `[part]` rules all apply.
 *
 * The plot area is a single keyboard stop for point query (§4.2). The legend toggles series
 * (line/bar/scatter) or focuses a slice (pie); the actions bar switches the Y-domain policy and
 * restores all series (§4.3). View state (§4.4) is owned by the widget instance through the
 * `viewState` property and `chart-view-change` events; without a host the card keeps a local
 * copy so it still works standalone. "View data" dispatches a cancelable `chart-view-data`
 * event so the widget can locate and expand the chart's direct parent table; when nobody
 * handles it, the card opens its own paged view of the values the chart received (§4.1).
 * When the owning widget sets `expandable`, the actions bar offers "Expand" (or "Close" while
 * `expanded`), which dispatches a cancelable `chart-expand` event; the widget owns the expansion
 * (§4.5 C1b-3) and the card itself never moves or resizes anything.
 * Hover, focus and the data view are transient and never printed.
 * @typedef {import('../lib/types.js').ChartBlock} ChartBlock
 * @typedef {import('./chart-draw.js').ChartLegendItem} ChartLegendItem
 * @typedef {import('../lib/chartHitModel.js').ChartHitItem} ChartHitItem
 * @typedef {import('../lib/chartViewState.js').ChartViewState} ChartViewState
 */
export class ParlerUiChart extends LitElement {
  static properties = {
    chart: { type: Object, attribute: false },
    viewState: { type: Object, attribute: false },
    /** Set by the owning widget when it can expand this card inline. */
    expandable: { type: Boolean, attribute: false },
    /** True for the instance the widget renders inside its expand layer. */
    expanded: { type: Boolean, reflect: true },
    /** C3b-2a (design §8.7): the group's shared category keys when this card is a colour-shared member, else null. */
    colorKeys: { type: Array, attribute: false },
    /** C3b-2a: the note for a categorical member the cap left on per-chart colours, else null. */
    colorNote: { type: String, attribute: false },
    /** C3b-2a: the category key highlighted across the group (transient, owned by the widget), else null. */
    highlightCategory: { type: String, attribute: false },
    _localView: { state: true },
    _active: { state: true },
    _hover: { state: true },
    /** Last draw result; the template reads its X domain for the range control. */
    _drawn: { state: true },
    _dataOpen: { state: true },
    _dataPage: { state: true },
  };

  constructor() {
    super();
    /** @type {ChartBlock | undefined} */
    this.chart = undefined;
    /** Host-owned view state; `null` lets the card keep a local copy. @type {ChartViewState | null} */
    this.viewState = null;
    /** @type {ChartViewState | null} */
    this._localView = null;
    this._divRef = createRef();
    this._areaRef = createRef();
    /** @type {ResizeObserver | null} */
    this._ro = null;
    /** The card element: the stable outer measurement the size policy decides from (§4.6). */
    this._cardRef = createRef();
    /** Last measured available width `W`; only a change of this value triggers a re-size. @type {number | null} */
    this._availableWidth = null;
    /** @type {MutationObserver | null} */
    this._themeObserver = null;
    this._themeSignature = "";
    /** Theme last used to draw; the legend marks render from it. */
    this._renderTheme = DEFAULT_CHART_RENDER_THEME;
    /** @type {ReturnType<typeof drawChart>} */
    this._drawn = null;
    /** Keyboard-selected item. @type {ChartHitItem | null} */
    this._active = null;
    /** Pointer-hovered item. @type {ChartHitItem | null} */
    this._hover = null;
    /** In-plot drag gesture in progress (logical plot px). @type {{ fromPx: number, toPx: number } | null} */
    this._drag = null;
    this.expandable = false;
    this.expanded = false;
    /** In-card chart-data view (fallback when no parent table is located). */
    this._dataOpen = false;
    this._dataPage = 0;
    this._viewDataRef = createRef();
    this._heatLegendRef = createRef();
    this._dataHeadingRef = createRef();
  }

  createRenderRoot() {
    return this;
  }

  /** The effective view state for the current chart snapshot. @returns {ChartViewState} */
  #view() {
    return reconcileChartViewState(this.viewState ?? this._localView, this.chart);
  }

  /** Apply a view change: keep it locally and tell the owning widget. @param {ChartViewState} next */
  #commit(next) {
    this._localView = next;
    this.dispatchEvent(
      new CustomEvent("chart-view-change", { detail: { state: next }, bubbles: true, composed: true })
    );
  }

  render() {
    const chart = this.chart;
    const theme = this._renderTheme;
    const title = typeof chart?.title === "string" ? chart.title.trim() : "";
    const legend = chart ? chartLegendItems(chart, theme, this.colorKeys ?? null) : [];
    const view = this.#view();
    const notes = chart ? this.#notesWithColorContext(chartDataNotes(chart)) : null;
    const viewText = chart ? describeChartView(view, chart, legend) : "";
    const shown = this._hover ?? this._active;
    const cartesian = chart?.kind === "line" || chart?.kind === "bar" || chart?.kind === "scatter";
    const multi = cartesian && legend.length > 1;
    const hiddenCount = view.hiddenSeriesKeys.length;
    const drawable = Boolean(chart && this._drawn);
    const xDomain = this._drawn?.xDomain ?? null;
    const zoomable = drawable && xDomain !== null;
    const placement = this._drawn?.plotSize?.legendPlacement ?? "below";
    const legendWidth = this._drawn?.plotSize?.legendWidth ?? 0;
    const pieRows = chart?.kind === "pie" && placement === "aside" ? pieLegendValues(chart) : null;
    const legendBlock = legend.length
      ? html`<ul class="chart-legend" part="chart-legend" aria-label="Legend">
          ${legend.map((item, index) => this.#legendItem(item, index, theme, view, pieRows?.[index] ?? null))}
        </ul>`
      : nothing;
    return html`<figure
      ${ref(this._cardRef)}
      class="chart-card"
      data-legend-placement=${placement}
      style=${legendWidth ? `--parler-chart-legend-width:${legendWidth}px` : nothing}
    >
      ${title
        ? html`<figcaption class="chart-card-title" part="chart-title">${title}</figcaption>`
        : nothing}
      ${notes?.meta ? html`<p class="chart-card-meta" part="chart-meta">${notes.meta}</p>` : nothing}
      <div
        ${ref(this._areaRef)}
        class="chart-plot-area"
        data-orientation=${this._drawn?.orientation ?? nothing}
        data-kind=${this._drawn?.kind ?? nothing}
        tabindex="0"
        role="group"
        aria-label=${title ? `${title}. Chart data; use arrow keys to read points.` : "Chart data; use arrow keys to read points."}
        @keydown=${this.#onKeydown}
        @mousedown=${this.#onMouseDown}
        @mousemove=${this.#onMouseMove}
        @mouseup=${this.#onMouseUp}
        @mouseleave=${this.#onMouseLeave}
        @blur=${this.#onBlur}
      >
        <div ${ref(this._divRef)} class="chart-root" part="chart-plot"></div>
        ${shown ? this.#tooltip(shown) : nothing}
      </div>
      ${placement === "aside" ? legendBlock : nothing}
      ${this._drawn?.heat
        ? html`<div class="chart-heat-legend" part="chart-heat-legend" ${ref(this._heatLegendRef)}></div>`
        : nothing}
      ${zoomable ? this.#rangeControl(xDomain, view) : nothing}
      ${viewText ? html`<p class="chart-view-note" part="chart-view-note" role="status">${viewText}</p>` : nothing}
      ${placement === "aside" ? nothing : legendBlock}
      ${drawable
        ? html`<div class="chart-actions" part="chart-actions" data-no-print>
            <button
              type="button"
              class="chart-action"
              ${ref(this._viewDataRef)}
              aria-pressed=${this._dataOpen ? "true" : "false"}
              @click=${this.#onViewData}
            >View data</button>
            ${multi
              ? html`<button
                  type="button"
                  class="chart-action"
                  aria-pressed=${view.yDomainPolicy === "visible" ? "true" : "false"}
                  @click=${() => {
                    const current = this.#view();
                    this.#commit(setYDomainPolicy(current, current.yDomainPolicy === "visible" ? "full" : "visible"));
                  }}
                >Fit Y axis to visible series</button>`
              : nothing}
            ${multi && hiddenCount > 0
              ? html`<button type="button" class="chart-action" @click=${() => this.#commit(showAllSeries(this.#view()))}>Show all series</button>`
              : nothing}
            ${chart.kind === "pie" && Number.isInteger(view.focusedSlice)
              ? html`<button type="button" class="chart-action" @click=${() => {
                  const current = this.#view();
                  this.#commit(toggleSliceFocus(current, current.focusedSlice));
                }}>Clear slice focus</button>`
              : nothing}
            ${zoomable
              ? html`<button type="button" class="chart-action" @click=${() => {
                  const current = this.#view();
                  const full = this._drawn?.xDomain?.full;
                  if (full) this.#commit(setSelectedRange(current, clampXRange(current.viewXDomain, full) ?? full));
                }}>Select visible range</button>`
              : nothing}
            ${zoomable && clampXSelection(view.selectedRange, xDomain.full)
              ? html`<span class="chart-selection-text">Selected: ${formatXRange(xDomain.mode, clampXSelection(view.selectedRange, xDomain.full))}</span>
                  <button type="button" class="chart-action" @click=${() => this.#commit(setSelectedRange(this.#view(), null))}>Clear selection</button>`
              : nothing}
            ${!isChartViewDefault(view)
              ? html`<button type="button" class="chart-action" @click=${() => this.#commit(resetChartView(this.#view()))}>Reset view</button>`
              : nothing}
            ${this.expandable
              ? html`<button type="button" class="chart-action chart-expand-toggle" aria-pressed=${this.expanded ? "true" : "false"} @click=${this.#onExpandToggle}>${this.expanded ? "Close" : "Expand"}</button>`
              : nothing}
          </div>`
        : nothing}
      ${drawable && this._dataOpen ? this.#dataView(chart) : nothing}
      ${notes
        ? html`<details
            class="chart-notes"
            part="chart-notes"
            ?open=${view.notesOpen}
            @toggle=${this.#onNotesToggle}
          >
            <summary class="chart-notes-summary">Data notes</summary>
            <dl class="chart-notes-list">
              ${notes.rows.map((row) => html`<dt>${row.label}</dt><dd>${row.value}</dd>`)}
            </dl>
            <details class="chart-notes-diagnostics">
              <summary>Diagnostics</summary>
              <dl class="chart-notes-list">
                ${notes.diagnostics.map((row) => html`<dt>${row.label}</dt><dd>${row.value}</dd>`)}
              </dl>
            </details>
          </details>`
        : nothing}
    </figure>`;
  }

  /** @param {Event} e @param {ChartViewState} view */
  /**
   * "View data": let the widget locate and expand the direct parent table (it cancels the event
   * when it did); otherwise toggle the in-card paged view of the chart's own values.
   */
  #onViewData = () => {
    if (this._dataOpen) {
      this.#closeDataView();
      return;
    }
    const handled = !this.dispatchEvent(
      new CustomEvent("chart-view-data", {
        detail: { chart: this.chart },
        bubbles: true,
        composed: true,
        cancelable: true,
      })
    );
    if (handled) return;
    this._dataOpen = true;
    this._dataPage = 0;
    void this.updateComplete.then(() => this._dataHeadingRef.value?.focus?.());
  };

  #closeDataView() {
    this._dataOpen = false;
    this._dataPage = 0;
    void this.updateComplete.then(() => this._viewDataRef.value?.focus?.());
  }

  /** Open the in-card data view directly (the widget uses this after closing an expansion). */
  openDataView() {
    this._dataOpen = true;
    this._dataPage = 0;
    void this.updateComplete.then(() => this._dataHeadingRef.value?.focus?.());
  }

  /** "Expand" / "Close": the owning widget decides; a card without a host does nothing. */
  #onExpandToggle = () => {
    this.dispatchEvent(
      new CustomEvent("chart-expand", {
        detail: { chart: this.chart, expanded: this.expanded === true },
        bubbles: true,
        composed: true,
        cancelable: true,
      })
    );
  };

  /** Paged table of the values this chart received (never the full source record set). */
  #dataView(chart) {
    const { columns, rows } = chartDataRows(chart);
    const page = chartDataPage(rows, this._dataPage);
    const summary = page.total
      ? `Showing ${page.from}–${page.to} of ${page.total} points received by this chart; not the full source record set.`
      : "This chart received no points.";
    return html`<section class="chart-data" part="chart-data" data-no-print aria-label="Chart data">
      <p class="chart-data-heading" ${ref(this._dataHeadingRef)} tabindex="-1">${summary}</p>
      <table class="chart-data-table">
        <thead>
          <tr>${columns.map((c) => html`<th scope="col">${c}</th>`)}</tr>
        </thead>
        <tbody>
          ${page.rows.map((r) => html`<tr><td>${this.#dataSeriesSwatch(chart, r.series)}${r.series}</td><td>${r.x}</td><td>${r.y}</td></tr>`)}
        </tbody>
      </table>
      <div class="chart-data-nav">
        <button type="button" class="chart-action" ?disabled=${page.page === 0} @click=${() => { this._dataPage = page.page - 1; }}>Previous</button>
        <span class="chart-data-page">Page ${page.page + 1} of ${page.pageCount}</span>
        <button type="button" class="chart-action" ?disabled=${page.page >= page.pageCount - 1} @click=${() => { this._dataPage = page.page + 1; }}>Next</button>
        <button type="button" class="chart-action" @click=${() => this.#closeDataView()}>Close</button>
      </div>
    </section>`;
  }

  /** C3b-2a: the swatch before a series / slice name in the data view, from the same slot resolution as the marks. */
  #dataSeriesSwatch(chart, seriesName) {
    if (!chart || (chart.kind !== "pie" && !(Array.isArray(chart.series) && chart.series.length >= 2))) return nothing;
    const list = chart.kind === "pie" ? (chart.series?.[0]?.x ?? []) : chart.series.map((s) => s.name);
    const at = list.findIndex((v, i) => (String(v ?? "").trim() || (chart.kind === "pie" ? `Item ${i + 1}` : `Series ${i + 1}`)) === seriesName);
    if (at < 0) return nothing;
    let fallback = at;
    if (chart.kind === "pie") {
      fallback = 0;
      for (let i = 0; i < at; i++) {
        const val = Number(chart.series[0].y[i]);
        if (Number.isFinite(val) && val > 0) fallback += 1;
      }
    }
    return this.#slotSwatch(resolveSeriesSlot(list[at], fallback, this.colorKeys ?? null));
  }

  /** @param {Event} e */
  #onNotesToggle = (e) => {
    const open = /** @type {HTMLDetailsElement} */ (e.currentTarget).open;
    const current = this.#view();
    if (open !== current.notesOpen) this.#commit(setNotesOpen(current, open));
  };

  /** @param {ChartHitItem} item */
  #tooltip(item) {
    const pos = this.#tooltipPosition(item);
    return html`<div
      class="chart-tooltip"
      part="chart-tooltip"
      role="status"
      aria-live="polite"
      data-no-print
      style=${pos}
    >
      ${this.colorNote ? html`<span class="chart-tooltip-line chart-tooltip-note">${this.colorNote}</span>` : nothing}
      ${item.lines.map((line, i) => html`<span class="chart-tooltip-line">${i === 0 && Number.isInteger(item.slot) ? this.#slotSwatch(item.slot) : nothing}${line}</span>`)}
    </div>`;
  }

  /** C3b-2a: a tiny colour sample for a palette slot, carrying the print palette role and slot like the marks. */
  #slotSwatch(slot) {
    const theme = this._renderTheme;
    const color = theme.palette.series[slot] ?? theme.palette.series[0];
    return svg`<svg class="chart-slot-swatch" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false"><rect x="0" y="0" width="10" height="10" rx="2" fill=${color} data-parler-palette-role="series" data-parler-series-slot=${slot}></rect></svg>`;
  }

  /** C3b-2a: data-notes rows stating whether this card's colours are shared across its group. */
  #notesWithColorContext(notes) {
    if (!notes) return notes;
    if (this.colorNote) return { ...notes, rows: [...notes.rows, { label: "Shared colours", value: this.colorNote }] };
    if (Array.isArray(this.colorKeys) && this.colorKeys.length) {
      return { ...notes, rows: [...notes.rows, { label: "Shared colours", value: "Each category keeps the same colour in every chart of this group" }] };
    }
    return notes;
  }

  /** C3b-2a: tell the widget which category is under the pointer / keyboard so sibling members can highlight it. */
  #emitHighlight(key) {
    const next = typeof key === "string" && key ? key : null;
    if (next === this._emittedHighlight) return;
    this._emittedHighlight = next;
    this.dispatchEvent(new CustomEvent("chart-category-highlight", { detail: { key: next }, bubbles: true, composed: true }));
  }

  /** C3b-2a: dim every mark and legend item whose category differs from the highlighted one; clear when null. */
  #applyHighlight() {
    const key = typeof this.highlightCategory === "string" && this.highlightCategory ? this.highlightCategory : null;
    const shared = Array.isArray(this.colorKeys) && this.colorKeys.length > 0;
    const host = this._divRef.value;
    const marks = host ? host.querySelectorAll("[data-parler-category]") : [];
    for (const mark of marks) {
      const dim = key !== null && shared && mark.getAttribute("data-parler-category") !== key;
      if (dim) mark.setAttribute("data-parler-category-dimmed", "");
      else mark.removeAttribute("data-parler-category-dimmed");
    }
    for (const li of this.querySelectorAll("li.chart-legend-item[data-parler-category]")) {
      const dim = key !== null && shared && li.getAttribute("data-parler-category") !== key;
      if (dim) li.setAttribute("data-parler-category-dimmed", "");
      else li.removeAttribute("data-parler-category-dimmed");
    }
  }

  /** Screen position of the tooltip anchor for a logical plot point. @param {ChartHitItem} item */
  #tooltipPosition(item) {
    const pos = this.#markCssPosition(item);
    if (!pos) return "left:0;top:0";
    const top = `top:${Math.max(0, Math.round(pos.y - 10))}px`;
    const areaWidth = this._areaRef.value?.clientWidth || this._areaRef.value?.getBoundingClientRect?.().width || 0;
    if (areaWidth > 0 && pos.x > areaWidth / 2) {
      return `right:${Math.round(areaWidth - pos.x + 10)}px;${top}`;
    }
    return `left:${Math.round(pos.x + 10)}px;${top}`;
  }

  /** @param {KeyboardEvent} event */
  #onKeydown = (event) => {
    const hit = this._drawn?.hit;
    if (!hit) return;
    const key = event.key;
    if (key === "Escape") {
      if (this._active || this._hover) {
        this._active = null;
        this._hover = null;
        this.#paintFocus();
        event.preventDefault();
      }
      return;
    }
    const direction = {
      ArrowLeft: "left",
      ArrowRight: "right",
      ArrowUp: "up",
      ArrowDown: "down",
      Home: "home",
      End: "end",
    }[key];
    if (!direction) return;
    event.preventDefault();
    this._hover = null;
    this._active = hit.step(this._active, direction);
    this.#paintFocus();
  };

  /**
   * Dedicated range control (§4.5 C1b-2): two native range inputs over the full X domain, the
   * required keyboard path for zooming. Start never passes end; the minimum span is one step.
   * @param {{ mode: import('../lib/chartXDomain.js').ChartXMode, full: [number, number], view: [number, number] }} xDomain
   * @param {ChartViewState} view
   */
  #rangeControl(xDomain, view) {
    const [flo, fhi] = xDomain.full;
    const span = fhi - flo;
    const toPos = (v) => (span > 0 ? Math.round(((v - flo) / span) * RANGE_STEPS) : 0);
    const current = clampXRange(view.viewXDomain, xDomain.full) ?? xDomain.full;
    const startPos = Math.min(toPos(current[0]), RANGE_STEPS - 1);
    const endPos = Math.max(toPos(current[1]), startPos + 1);
    const valueText = (pos) => formatXValue(xDomain.mode, flo + (pos / RANGE_STEPS) * span);
    return html`<div class="chart-range" part="chart-range" data-no-print>
      <label class="chart-range-field">
        <span class="chart-range-label">View start</span>
        <input
          type="range"
          class="chart-range-input"
          min="0"
          max=${RANGE_STEPS}
          step="1"
          .value=${String(startPos)}
          aria-valuetext=${valueText(startPos)}
          @input=${(e) => this.#onRangeInput("start", e)}
          @change=${(e) => this.#onRangeInput("start", e)}
        />
      </label>
      <label class="chart-range-field">
        <span class="chart-range-label">View end</span>
        <input
          type="range"
          class="chart-range-input"
          min="0"
          max=${RANGE_STEPS}
          step="1"
          .value=${String(endPos)}
          aria-valuetext=${valueText(endPos)}
          @input=${(e) => this.#onRangeInput("end", e)}
          @change=${(e) => this.#onRangeInput("end", e)}
        />
      </label>
      <span class="chart-range-text">${view.viewXDomain ? `View: ${valueText(startPos)} – ${valueText(endPos)}` : "Full range"}</span>
    </div>`;
  }

  /** @param {"start" | "end"} which @param {Event} e */
  #onRangeInput(which, e) {
    const xDomain = this._drawn?.xDomain;
    if (!xDomain) return;
    const [flo, fhi] = xDomain.full;
    const span = fhi - flo;
    if (!(span > 0)) return;
    const toPos = (v) => Math.round(((v - flo) / span) * RANGE_STEPS);
    const state = this.#view();
    const current = clampXRange(state.viewXDomain, xDomain.full) ?? xDomain.full;
    let startPos = toPos(current[0]);
    let endPos = toPos(current[1]);
    const pos = Math.max(0, Math.min(RANGE_STEPS, Number(/** @type {HTMLInputElement} */ (e.currentTarget).value)));
    if (!Number.isFinite(pos)) return;
    if (which === "start") startPos = Math.min(pos, endPos - 1);
    else endPos = Math.max(pos, startPos + 1);
    startPos = Math.max(0, startPos);
    endPos = Math.min(RANGE_STEPS, endPos);
    /** @type {HTMLInputElement} */ (e.currentTarget).value = String(which === "start" ? startPos : endPos);
    const full = startPos === 0 && endPos === RANGE_STEPS;
    this.#commit(setViewXDomain(state, full ? null : [flo + (startPos / RANGE_STEPS) * span, flo + (endPos / RANGE_STEPS) * span]));
  }

  /** Logical plot x for a pointer event, or null when the host has no layout. @param {MouseEvent} event */
  #plotX(event) {
    const hit = this._drawn?.hit;
    const host = this._divRef.value;
    if (!hit || !host) return null;
    const rect = host.getBoundingClientRect();
    if (!(rect.width > 0)) return null;
    const scale = rect.width / hit.layout.viewBoxW;
    return { px: (event.clientX - rect.left) / scale - hit.layout.marginLeft, py: (event.clientY - rect.top) / scale - hit.layout.marginTop, hit };
  }

  /** Optional in-plot horizontal drag zoom (§4.5 C1b-2); the range control remains the required path. */
  #onMouseDown = (event) => {
    if (event.button !== 0 || !this._drawn?.xDomain) return;
    const at = this.#plotX(event);
    if (!at) return;
    this._drag = { fromPx: at.px, toPx: at.px };
  };

  /** @param {MouseEvent} event */
  #onMouseMove = (event) => {
    const at = this.#plotX(event);
    if (!at) return;
    if (this._drag) {
      this._drag.toPx = at.px;
      if (Math.abs(this._drag.toPx - this._drag.fromPx) >= DRAG_ZOOM_MIN_PX) {
        this._hover = null;
        return;
      }
    }
    this._hover = at.hit.nearest(at.px, at.py);
  };

  #onMouseUp = () => {
    const drag = this._drag;
    this._drag = null;
    const xDomain = this._drawn?.xDomain;
    if (!drag || !xDomain || Math.abs(drag.toPx - drag.fromPx) < DRAG_ZOOM_MIN_PX) return;
    const innerW = this._drawn.hit.layout.innerW;
    const state = this.#view();
    const [vlo, vhi] = clampXRange(state.viewXDomain, xDomain.full) ?? xDomain.full;
    const toValue = (px) => vlo + (Math.max(0, Math.min(innerW, px)) / innerW) * (vhi - vlo);
    const a = toValue(Math.min(drag.fromPx, drag.toPx));
    const b = toValue(Math.max(drag.fromPx, drag.toPx));
    if (b > a) this.#commit(setViewXDomain(state, clampXRange([a, b], xDomain.full)));
  };

  #onMouseLeave = () => {
    this._hover = null;
    this._drag = null;
  };

  #onBlur = () => {
    this._hover = null;
  };

  /** Draw or clear the focus ring for the keyboard-selected item (never printed). */
  #paintFocus() {
    const host = this._divRef.value;
    if (!host) return;
    const plot = select(host).select("svg > g");
    plot.selectAll(".chart-focus-ring").remove();
    const item = this._active;
    if (!item || plot.empty()) return;
    plot
      .append("circle")
      .attr("class", "chart-focus-ring")
      .attr("data-no-print", "")
      .attr("cx", item.cx)
      .attr("cy", item.cy)
      .attr("r", 7);
    this.#scrollActiveIntoView(item);
  }

  /** CSS-px position of a mark inside the plot host. @param {import('../lib/chartHitModel.js').ChartHitItem} item */
  /**
   * Mark position in CSS px: `x`/`y` relative to the plot area's content box (the tooltip's
   * positioning ancestor), which includes the plot host's offset inside the area when the host
   * is narrower than the area and centred (§4.6); `clientX`/`clientY` in viewport coordinates.
   */
  #markCssPosition(item) {
    const host = this._divRef.value;
    const area = this._areaRef.value;
    const layout = this._drawn?.hit.layout;
    if (!host || !layout) return null;
    const rect = host.getBoundingClientRect();
    const scale = rect.width > 0 ? rect.width / layout.viewBoxW : 1;
    const inHostX = (layout.marginLeft + item.cx) * scale;
    const inHostY = (layout.marginTop + item.cy) * scale;
    const areaRect = area ? area.getBoundingClientRect() : rect;
    const offX = rect.left - areaRect.left + (area?.scrollLeft ?? 0);
    const offY = rect.top - areaRect.top + (area?.scrollTop ?? 0);
    return {
      x: offX + inHostX,
      y: offY + inHostY,
      clientX: rect.left + inHostX,
      clientY: rect.top + inHostY,
      hostRect: rect,
      scale,
    };
  }

  /** Rendered tooltip size, or an estimate from its lines before layout. @param {import('../lib/chartHitModel.js').ChartHitItem} item */
  #tooltipSize(item) {
    const tip = this.querySelector(".chart-tooltip");
    const w = tip instanceof HTMLElement ? tip.offsetWidth : 0;
    const h = tip instanceof HTMLElement ? tip.offsetHeight : 0;
    if (w > 0 && h > 0) return { width: w, height: h };
    const lines = item?.lines?.length ?? 3;
    return { width: 220, height: Math.ceil(lines * 12 * 1.35 + 14) };
  }

  /**
   * Keep the queried mark and the room its tooltip needs visible (design §7.3): the reveal is
   * applied to every scrolling ancestor, from the plot area up through the card, the expand
   * layer and the thread, without moving focus away from the plot's single keyboard stop.
   * @param {import('../lib/chartHitModel.js').ChartHitItem} item
   */
  #scrollActiveIntoView(item) {
    const area = this._areaRef.value;
    const host = this._divRef.value;
    if (!area || !host) return;
    const tip = this.#tooltipSize(item);
    const margin = 12;
    for (let node = area; node instanceof HTMLElement; node = node.parentElement) {
      if (!(node.scrollHeight > node.clientHeight + 1)) continue;
      const pos = this.#markCssPosition(item);
      if (!pos) return;
      const markTop = pos.clientY;
      const wantTop = markTop - tip.height - margin;
      const wantBottom = markTop + margin;
      const box = node.getBoundingClientRect();
      const maxScroll = node.scrollHeight - node.clientHeight;
      if (wantTop < box.top) {
        node.scrollTop = Math.max(0, node.scrollTop - (box.top - wantTop));
      } else if (wantBottom > box.top + node.clientHeight) {
        node.scrollTop = Math.min(maxScroll, node.scrollTop + (wantBottom - (box.top + node.clientHeight)));
      }
    }
  }

  /**
   * The visible region of the plot area in its own content coordinates: the intersection of the
   * area's box with every scrolling ancestor's box, offset by the area's scroll position.
   */
  #visibleRegion() {
    const area = this._areaRef.value;
    if (!area) return null;
    const box = area.getBoundingClientRect();
    let top = box.top;
    let bottom = box.top + (area.clientHeight || box.height);
    let left = box.left;
    let right = box.left + (area.clientWidth || box.width);
    for (let node = area.parentElement; node instanceof HTMLElement; node = node.parentElement) {
      if (!(node.scrollHeight > node.clientHeight + 1)) continue;
      const b = node.getBoundingClientRect();
      top = Math.max(top, b.top);
      bottom = Math.min(bottom, b.top + node.clientHeight);
      left = Math.max(left, b.left);
      right = Math.min(right, b.left + (node.clientWidth || b.width));
    }
    return {
      top: top - box.top + area.scrollTop,
      bottom: bottom - box.top + area.scrollTop,
      left: left - box.left + area.scrollLeft,
      right: right - box.left + area.scrollLeft,
    };
  }

  /**
   * Place the rendered tooltip fully inside the visible region: above the mark when that fits,
   * else below, clamped to the region's edges; horizontally beside the mark, flipped or clamped
   * when it would leave the region. Runs after every render that shows a tooltip.
   */
  #placeTooltip() {
    const tip = this.querySelector(".chart-tooltip");
    const item = this._hover ?? this._active;
    if (!(tip instanceof HTMLElement) || !item) return;
    const pos = this.#markCssPosition(item);
    const region = this.#visibleRegion();
    if (!pos || !region) return;
    const size = this.#tooltipSize(item);
    const gap = 10;
    let top = pos.y - gap - size.height;
    if (top < region.top) top = pos.y + gap;
    if (top + size.height > region.bottom) top = Math.max(region.top, region.bottom - size.height);
    let left = pos.x + gap;
    if (left + size.width > region.right) left = pos.x - gap - size.width;
    if (left < region.left) left = Math.max(region.left, Math.min(region.right - size.width, pos.x + gap));
    tip.style.right = "";
    tip.style.left = `${Math.round(Math.max(0, left))}px`;
    tip.style.top = `${Math.round(Math.max(0, top))}px`;
  }

  /**
   * Legend entry: a toggle button for cartesian series (pressed = shown) or a focus button for
   * a pie slice (pressed = focused). Hidden entries keep their mark and slot so the mapping
   * between legend and plot never changes.
   * @param {ChartLegendItem} item @param {number} index
   * @param {typeof DEFAULT_CHART_RENDER_THEME} theme @param {ChartViewState} view
   */
  #legendItem(item, index, theme, view, valueText = null) {
    const pie = this.chart?.kind === "pie";
    const hidden = !pie && isSeriesHidden(view, index);
    const pressed = pie ? view.focusedSlice === index : !hidden;
    const label = pie ? `Focus slice ${item.label}` : `${hidden ? "Show" : "Hide"} series ${item.label}`;
    // Read the view at click time so rapid clicks between renders never act on a stale copy.
    const onClick = () => {
      const current = this.#view();
      this.#commit(pie ? toggleSliceFocus(current, index) : toggleSeriesVisibility(current, index));
    };
    return html`<li class="chart-legend-item" data-parler-series-slot=${item.slot} data-parler-category=${item.category ?? nothing} ?data-hidden=${hidden}>
      <button
        type="button"
        class="chart-legend-toggle"
        part="chart-legend-toggle"
        aria-pressed=${pressed ? "true" : "false"}
        aria-label=${label}
        @click=${onClick}
        @mouseenter=${() => this.#emitHighlight(item.category ?? null)}
        @mouseleave=${() => this.#emitHighlight(null)}
        @focus=${() => this.#emitHighlight(item.category ?? null)}
        @blur=${() => this.#emitHighlight(null)}
      >
        ${this.#legendSwatch(item, theme)}
        <span class="chart-legend-label">${item.label}</span>
        ${valueText ? html`<span class="chart-legend-value">${valueText}</span>` : nothing}
      </button>
    </li>`;
  }

  /**
   * Legend mark as a tiny inline SVG carrying the same palette roles and slot as the plot marks,
   * so answer printing recolors it with them.
   * @param {ChartLegendItem} item @param {typeof DEFAULT_CHART_RENDER_THEME} theme
   */
  #legendSwatch(item, theme) {
    const { palette, style } = theme;
    const size = style.legend.swatchSize;
    const pad = Math.max(1, style.outlineWidth);
    const width = item.mark === "line" ? style.legend.lineLength : size;
    const height = item.mark === "line" ? Math.max(size, style.lineWidth) : size;
    let mark;
    if (item.mark === "line") {
      mark = svg`<line x1="0" x2=${width} y1=${height / 2} y2=${height / 2}
        stroke=${item.color} stroke-width=${style.lineWidth}
        data-parler-palette-role="series" data-parler-series-slot=${item.slot}></line>`;
    } else if (item.mark === "circle") {
      mark = svg`<circle cx=${size / 2} cy=${size / 2} r=${size / 2}
        fill=${item.color} stroke=${palette.pointOutline} stroke-width=${style.outlineWidth}
        data-parler-palette-role="series point-outline" data-parler-series-slot=${item.slot}></circle>`;
    } else {
      mark = svg`<rect x="0" y="0" width=${size} height=${size} rx=${style.legend.swatchRadius}
        fill=${item.color} stroke=${palette.pointOutline} stroke-width=${style.outlineWidth}
        data-parler-palette-role="series point-outline" data-parler-series-slot=${item.slot}></rect>`;
    }
    return html`<svg class="chart-legend-swatch" width=${width + 2 * pad} height=${height + 2 * pad}
      viewBox=${`${-pad} ${-pad} ${width + 2 * pad} ${height + 2 * pad}`}
      aria-hidden="true" focusable="false">${mark}</svg>`;
  }

  connectedCallback() {
    super.connectedCallback();
    if (this.hasUpdated) {
      void this.updateComplete.then(() => {
        if (this.isConnected) this._installObservers();
      });
    }
  }

  firstUpdated() {
    this._installObservers();
  }

  /**
   * The available width `W` (§4.6): the card's content-box width. `null` when the card has no
   * layout yet, in which case the renderer's own fallback applies. Never the plot host's width,
   * which the policy itself sets.
   */
  #measureAvailableWidth() {
    const card = this._cardRef.value;
    if (!card) return null;
    const rect = card.getBoundingClientRect?.();
    const width = Number(rect?.width) > 0 ? Number(rect.width) : Number(card.clientWidth) || 0;
    return width > 0 ? Math.round(width) : null;
  }

  _installObservers() {
    const el = this._divRef.value;
    if (!el) return;
    this._availableWidth = this.#measureAvailableWidth();
    this._draw(true);
    if (!this._ro) {
      // Only a change of the card's width re-sizes the plot; height-only changes (a taller
      // legend, an opened disclosure) never feed back into the size decision.
      this._ro = new ResizeObserver(() => {
        const width = this.#measureAvailableWidth();
        if (width === this._availableWidth) return;
        this._availableWidth = width;
        this._draw(true);
      });
      this._ro.observe(this._cardRef.value ?? el);
    }
    if (!this._themeObserver) {
      const themeSource = this.closest("parler-ui") ?? this;
      this._themeObserver = new MutationObserver(() => this._draw(false));
      this._themeObserver.observe(themeSource, {
        attributes: true,
        attributeFilter: ["class", "style", "theme-mode"],
      });
    }
  }

  updated(changed) {
    if (changed.has("chart")) {
      this._active = null;
      this._hover = null;
      this._dataOpen = false;
      this._dataPage = 0;
      this._draw(true);
    } else if (changed.has("viewState") || changed.has("_localView") || changed.has("colorKeys")) {
      this._draw(true);
    }
    if (changed.has("_hover") || changed.has("_active") || changed.has("_drawn")) this.#placeTooltip();
    this.#syncHeatLegend();
    if (changed.has("_hover") || changed.has("_active")) {
      const item = this._hover ?? this._active;
      this.#emitHighlight(item && typeof item.category === "string" ? item.category : null);
    }
    if (changed.has("highlightCategory") || changed.has("_drawn") || changed.has("colorKeys")) this.#applyHighlight();
  }

  /** Fill (or refill) the heatmap colour-bar legend from the last draw; a no-op for other kinds. */
  #syncHeatLegend() {
    const host = this._heatLegendRef.value;
    const heat = this._drawn?.heat;
    if (!host || !heat) return;
    appendHeatLegend(host, heat, this._renderTheme, this.ownerDocument);
  }

  _draw(force) {
    const el = this._divRef.value;
    if (!el || !this.chart) return;
    const theme = resolveChartTheme(this);
    const signature = chartThemeSignature(theme);
    if (!force && signature === this._themeSignature) return;
    const themeChanged = signature !== this._themeSignature;
    this._themeSignature = signature;
    const expandBody = this.expanded ? this.closest(".chart-expand-body") : null;
    const bodyHeight = expandBody instanceof HTMLElement ? expandBody.clientHeight : 0;
    this._drawn = drawChart(el, this.chart, theme, this.#view(), {
      availableWidth: this._availableWidth ?? undefined,
      context: this.expanded ? "expand" : "card",
      availableHeight: bodyHeight > 0 ? bodyHeight : undefined,
      colorKeys: this.colorKeys ?? null,
    });
    applyPlotSizeToHost(el, this._drawn?.plotSize ?? null);
    const hadHeat = this._heatShown === true;
    this._heatShown = Boolean(this._drawn?.heat);
    if (this._heatShown !== hadHeat) this.requestUpdate();
    const previous = this._active;
    this._active =
      previous && this._drawn
        ? this._drawn.hit.find(previous.type, previous.series, previous.index, previous.cx, previous.cy)
        : null;
    this._hover = null;
    this.#paintFocus();
    if (themeChanged) {
      this._renderTheme = theme;
      this.requestUpdate();
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._ro?.disconnect();
    this._ro = null;
    this._themeObserver?.disconnect();
    this._themeObserver = null;
    this._themeSignature = "";
    this._drawn = null;
    const el = this._divRef.value;
    if (el) select(el).selectAll("svg").remove();
  }
}

const SVG_NS = "http://www.w3.org/2000/svg";

/** @param {Document} doc @param {ChartLegendItem} item @param {typeof DEFAULT_CHART_RENDER_THEME} theme */
function printLegendSwatch(doc, item, theme) {
  const { palette, style } = theme;
  const size = style.legend.swatchSize;
  const pad = Math.max(1, style.outlineWidth);
  const width = item.mark === "line" ? style.legend.lineLength : size;
  const height = item.mark === "line" ? Math.max(size, style.lineWidth) : size;
  const svgEl = doc.createElementNS(SVG_NS, "svg");
  svgEl.setAttribute("class", "chart-legend-swatch");
  svgEl.setAttribute("width", String(width + 2 * pad));
  svgEl.setAttribute("height", String(height + 2 * pad));
  svgEl.setAttribute("viewBox", `${-pad} ${-pad} ${width + 2 * pad} ${height + 2 * pad}`);
  svgEl.setAttribute("aria-hidden", "true");
  let mark;
  if (item.mark === "line") {
    mark = doc.createElementNS(SVG_NS, "line");
    mark.setAttribute("x1", "0");
    mark.setAttribute("x2", String(width));
    mark.setAttribute("y1", String(height / 2));
    mark.setAttribute("y2", String(height / 2));
    mark.setAttribute("stroke", item.color);
    mark.setAttribute("stroke-width", String(style.lineWidth));
    mark.setAttribute("data-parler-palette-role", "series");
  } else {
    mark = doc.createElementNS(SVG_NS, item.mark === "circle" ? "circle" : "rect");
    if (item.mark === "circle") {
      mark.setAttribute("cx", String(size / 2));
      mark.setAttribute("cy", String(size / 2));
      mark.setAttribute("r", String(size / 2));
    } else {
      mark.setAttribute("x", "0");
      mark.setAttribute("y", "0");
      mark.setAttribute("width", String(size));
      mark.setAttribute("height", String(size));
      mark.setAttribute("rx", String(style.legend.swatchRadius));
    }
    mark.setAttribute("fill", item.color);
    mark.setAttribute("stroke", palette.pointOutline);
    mark.setAttribute("stroke-width", String(style.outlineWidth));
    mark.setAttribute("data-parler-palette-role", "series point-outline");
  }
  mark.setAttribute("data-parler-series-slot", String(item.slot));
  svgEl.append(mark);
  return svgEl;
}

/**
 * Build a complete, detached chart card for answer printing from the chart artifact alone
 * (chart-enhancement design §4.5 C1b-1, §10.2): title, meta line, the plot drawn with the
 * default view (full X domain, all series, `full` Y policy, no slice focus), a legend with every
 * series, the data notes expanded, and, when `viewSummary` is non-empty, a print note stating
 * that the print shows the full analysis and what the screen view differed in. It never reads
 * live DOM; the caller supplies the resolved chart Theme, the width and the screen view summary.
 *
 * @param {object} input
 * @param {ChartBlock} input.chart
 * @param {typeof DEFAULT_CHART_RENDER_THEME} [input.theme]
 * @param {number} [input.width] logical plot width in CSS px; falls back to 640
 * @param {string} [input.viewSummary] plain-text on-screen view description, empty when none
 * @param {Document} [input.doc]
 * @returns {HTMLElement} `figure.chart-card`
 */
export function renderChartPrintCard({ chart, theme = DEFAULT_CHART_RENDER_THEME, width, viewSummary = "", doc = globalThis.document, colorKeys = null }) {
  const figure = doc.createElement("figure");
  figure.className = "chart-card";
  const title = typeof chart?.title === "string" ? chart.title.trim() : "";
  if (title) {
    const caption = doc.createElement("figcaption");
    caption.className = "chart-card-title";
    caption.setAttribute("part", "chart-title");
    caption.textContent = title;
    figure.append(caption);
  }
  const notes = chartDataNotes(chart);
  if (notes.meta) {
    const meta = doc.createElement("p");
    meta.className = "chart-card-meta";
    meta.setAttribute("part", "chart-meta");
    meta.textContent = notes.meta;
    figure.append(meta);
  }
  const area = doc.createElement("div");
  area.className = "chart-plot-area";
  const root = doc.createElement("div");
  root.className = "chart-root";
  root.setAttribute("part", "chart-plot");
  area.append(root);
  figure.append(area);
  const w = Number(width);
  // The root is detached, so category labels are measured through a probe in `doc` (the
  // rendering document) rather than estimated; the probe is removed once the chart is drawn.
  const measureText = createTextMeasurer(root, theme, doc);
  const drawn = drawChart(root, chart, theme, null, {
    availableWidth: Number.isFinite(w) && w > 0 ? w : 640,
    context: "print",
    measureText,
    colorKeys: Array.isArray(colorKeys) ? colorKeys : null,
  });
  if (typeof measureText.dispose === "function") measureText.dispose();
  if (drawn?.orientation) area.setAttribute("data-orientation", drawn.orientation);
  if (drawn?.kind) area.setAttribute("data-kind", drawn.kind);
  applyPlotSizeToHost(root, drawn?.plotSize ?? null);
  if (drawn?.heat) {
    const legendHost = doc.createElement("div");
    legendHost.className = "chart-heat-legend";
    legendHost.setAttribute("part", "chart-heat-legend");
    appendHeatLegend(legendHost, drawn.heat, theme, doc);
    figure.append(legendHost);
  }
  const placement = drawn?.plotSize?.legendPlacement ?? "below";
  figure.setAttribute("data-legend-placement", placement);
  if (drawn?.plotSize?.legendWidth) figure.style.setProperty("--parler-chart-legend-width", `${drawn.plotSize.legendWidth}px`);
  const pieRows = chart?.kind === "pie" && placement === "aside" ? pieLegendValues(chart) : null;
  if (viewSummary) {
    const note = doc.createElement("p");
    note.className = "chart-print-note";
    note.setAttribute("part", "chart-print-note");
    note.textContent = `Printed with the full analysis range and all series; on screen: ${viewSummary}.`;
    figure.append(note);
  }
  const legend = chartLegendItems(chart, theme, Array.isArray(colorKeys) ? colorKeys : null);
  if (legend.length) {
    const ul = doc.createElement("ul");
    ul.className = "chart-legend";
    ul.setAttribute("part", "chart-legend");
    ul.setAttribute("aria-label", "Legend");
    legend.forEach((item, index) => {
      const li = doc.createElement("li");
      li.className = "chart-legend-item";
      li.setAttribute("data-parler-series-slot", String(item.slot));
      li.append(printLegendSwatch(doc, item, theme));
      const label = doc.createElement("span");
      label.className = "chart-legend-label";
      label.textContent = item.label;
      li.append(label);
      if (pieRows?.[index]) {
        const value = doc.createElement("span");
        value.className = "chart-legend-value";
        value.textContent = pieRows[index];
        li.append(value);
      }
      ul.append(li);
    });
    // Aside legends sit beside the disc, so they follow the plot area directly (same as the card).
    if (placement === "aside") area.insertAdjacentElement("afterend", ul);
    else figure.append(ul);
  }
  const details = doc.createElement("details");
  details.className = "chart-notes";
  details.setAttribute("part", "chart-notes");
  details.setAttribute("open", "");
  const summary = doc.createElement("summary");
  summary.className = "chart-notes-summary";
  summary.textContent = "Data notes";
  details.append(summary);
  const dl = (rows) => {
    const list = doc.createElement("dl");
    list.className = "chart-notes-list";
    for (const row of rows) {
      const dt = doc.createElement("dt");
      dt.textContent = row.label;
      const dd = doc.createElement("dd");
      dd.textContent = row.value;
      list.append(dt, dd);
    }
    return list;
  };
  details.append(dl(notes.rows));
  const diag = doc.createElement("details");
  diag.className = "chart-notes-diagnostics";
  diag.setAttribute("open", "");
  const diagSummary = doc.createElement("summary");
  diagSummary.textContent = "Diagnostics";
  diag.append(diagSummary, dl(notes.diagnostics));
  details.append(diag);
  figure.append(details);
  return figure;
}

customElements.define("parler-ui-chart", ParlerUiChart);


/**
 * Write the size policy's plot width onto the plot host (§4.6): the host gets exactly `w` CSS px
 * and is centred by the stylesheet, while the SVG keeps filling the host, so 1 logical px stays
 * 1 CSS px and the host-based coordinate conversions hold.
 * @param {HTMLElement} host @param {import('../lib/chartSizePolicy.js').ChartPlotSize | null} size
 */
function applyPlotSizeToHost(host, size) {
  if (!host) return;
  if (!size) {
    host.style.removeProperty("width");
    host.removeAttribute("data-plot-width");
    return;
  }
  host.style.width = `${size.w}px`;
  host.setAttribute("data-plot-width", String(size.w));
}

/**
 * Pie legend value texts in the legend's entry order, `value (pct%)`, from the same numbers and
 * formatting as the slice tooltip (original analysis denominator; §4.3).
 * @param {ChartBlock} chart @returns {string[]}
 */
export function pieLegendValues(chart) {
  const s = Array.isArray(chart?.series) ? chart.series[0] : null;
  if (!s || !Array.isArray(s.x) || !Array.isArray(s.y)) return [];
  const values = [];
  for (let i = 0; i < s.x.length; i++) {
    const val = Number(s.y[i]);
    if (Number.isFinite(val) && val > 0) values.push(val);
  }
  if (values.length <= 1) return [];
  const total = values.reduce((sum, v) => sum + v, 0);
  return values.map((v) => `${v} (${(total > 0 ? (100 * v) / total : 0).toFixed(1)}%)`);
}
