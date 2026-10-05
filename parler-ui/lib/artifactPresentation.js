/**
 * Ordered assistant artifact projection and table/chart pairing.
 *
 * @typedef {import('./types.js').ChartBlock} ChartBlock
 * @typedef {import('./types.js').TableBlock} TableBlock
 *
 * @typedef {'table' | 'chart'} ArtifactType
 *
 * @typedef {object} RowArtifact
 * @property {ArtifactType} type
 * @property {number} seq Append sequence within the assistant row (cross-type).
 * @property {string} key Stable render key.
 * @property {ChartBlock} [chart]
 * @property {TableBlock} [table]
 */

/**
 * @typedef {object} ChartGroupSlot one member slot of a chart group card
 * @property {import('./types.js').ChartGroupMember} member
 * @property {RowArtifact | null} artifact the member's chart artifact when `ready` and arrived, else null
 * @property {string[] | null} colorKeys C3b-2a: the group's shared category keys when this member is colour-shared, else null
 * @property {string | null} colorNote C3b-2a: the note for a categorical member left on per-chart colours by the cap
 *
 * @typedef {object} ArtifactLayoutGroup one render slot: a single artifact, a grid of adjacent charts, or a chart group
 * @property {'artifact' | 'chart-grid' | 'chart-group'} type
 * @property {string} key stable render key (`grid:` + the first chart key for a grid; `group:` + groupId for a group)
 * @property {RowArtifact} [artifact] when `type` is `artifact`
 * @property {RowArtifact[]} [charts] when `type` is `chart-grid`: two or more chart artifacts in display order
 * @property {import('./types.js').ChartGroupManifest} [group] when `type` is `chart-group`
 * @property {ChartGroupSlot[]} [slots] when `type` is `chart-group`: the members in `order`
 */

/** Grid gap between chart cards (chart-enhancement design §4.2 / §8.1, C3a). */
export const ARTIFACT_GRID_GAP = 16;
/** Narrowest column a chart card may occupy in a grid; below it the row stays single column. */
export const ARTIFACT_GRID_MIN_COLUMN = 360;
/** At most two columns, whatever the width. */
export const ARTIFACT_GRID_MAX_COLUMNS = 2;

/**
 * C3a (design §8.1): group runs of two or more adjacent chart artifacts, in display order, into a
 * grid slot; every other artifact keeps its own slot. Only adjacency in the ordered projection
 * counts: a table between two charts (its paired table sits immediately before its chart) breaks the
 * run, so tables keep their position and no artifact is reordered, duplicated or hidden. Cards in a
 * grid share layout only, never coordinates, data, colours or filters. Pure.
 * @param {RowArtifact[]} ordered the output of {@link orderArtifactsForDisplay}
 * @returns {ArtifactLayoutGroup[]}
 */
export function groupArtifactsForLayout(ordered, groups = []) {
  /** @type {ArtifactLayoutGroup[]} */
  const out = [];
  if (!Array.isArray(ordered)) return out;
  // C3b-1 (design §8.5): a manifest moves its ready members' charts (matched by chartId) into a group slot placed
  // at the first arrived member's position, else at the end; everything else keeps the C3a rules below.
  /** @type {Map<number, ArtifactLayoutGroup>} */
  const groupAt = new Map();
  /** @type {ArtifactLayoutGroup[]} */
  const groupsAtEnd = [];
  const taken = new Set();
  for (const group of Array.isArray(groups) ? groups : []) {
    if (!group || !Array.isArray(group.members)) continue;
    let firstIndex = -1;
    const slots = group.members.map((member) => {
      let artifact = null;
      if (member.state === "ready" && member.chartId) {
        const index = ordered.findIndex((a, i) => a && a.type === "chart" && a.chart && a.chart.chartId === member.chartId && !taken.has(i));
        if (index >= 0) {
          taken.add(index);
          artifact = ordered[index];
          if (firstIndex < 0 || index < firstIndex) firstIndex = index;
        }
      }
      const shared = group.sharedCategories && Array.isArray(group.sharedCategories.keys) ? group.sharedCategories.keys : null;
      const colorKeys = shared && member.colorShared === true ? shared : null;
      const colorNote = shared && member.colorShared !== true && artifact && chartProducesCategoryKeys(artifact.chart)
        ? SHARED_COLOR_CAP_NOTE
        : null;
      return { member, artifact, colorKeys, colorNote };
    });
    const slot = { type: "chart-group", key: `group:${group.groupId}`, group, slots };
    if (firstIndex >= 0 && !groupAt.has(firstIndex)) groupAt.set(firstIndex, slot);
    else groupsAtEnd.push(slot);
  }
  let run = [];
  const flush = () => {
    if (run.length >= 2) out.push({ type: "chart-grid", key: `grid:${run[0].key}`, charts: run });
    else for (const a of run) out.push({ type: "artifact", key: a.key, artifact: a });
    run = [];
  };
  ordered.forEach((a, i) => {
    const groupSlot = groupAt.get(i);
    if (groupSlot) {
      flush();
      out.push(groupSlot);
    }
    if (taken.has(i)) return;
    if (a && a.type === "chart" && a.chart) {
      run.push(a);
      return;
    }
    flush();
    if (a) out.push({ type: "artifact", key: a.key, artifact: a });
  });
  flush();
  for (const slot of groupsAtEnd) out.push(slot);
  return out;
}

/** C3b-2a (design §8.7): the note shown on a categorical member the 24-key cap left on per-chart colours. */
export const SHARED_COLOR_CAP_NOTE = "Colours not shared: more than 24 categories in this group";

/** C3b-2a: whether a chart kind takes part in shared colours (pie, or bar / line / scatter with two or more series). */
export function chartProducesCategoryKeys(chart) {
  if (!chart || typeof chart !== "object") return false;
  if (chart.kind === "pie") return Array.isArray(chart.series) && chart.series.length >= 1;
  if (chart.kind === "bar" || chart.kind === "line" || chart.kind === "scatter") return Array.isArray(chart.series) && chart.series.length >= 2;
  return false;
}

/**
 * C3b-2a: the shared colour keys and note for one chart artifact of a row, or nulls when it is not a colour-shared
 * group member. Used by the expand layer and the print builder, which render a chart outside the group card.
 * @param {ArtifactLayoutGroup[]} layout @param {string} artifactKey
 */
export function chartColorContextFor(layout, artifactKey) {
  for (const slot of Array.isArray(layout) ? layout : []) {
    if (slot.type !== "chart-group" || !slot.slots) continue;
    for (const s of slot.slots) {
      if (s.artifact && s.artifact.key === artifactKey) return { colorKeys: s.colorKeys ?? null, colorNote: s.colorNote ?? null, groupId: slot.group?.groupId ?? null };
    }
  }
  return { colorKeys: null, colorNote: null, groupId: null };
}

/** Readable state text for a chart-group member that has no chart to show (design §8.5). */
export function chartGroupMemberStateText(member, chartArrived) {
  if (!member) return "";
  switch (member.state) {
    case "pending":
      return `Waiting for ${member.name || member.key}`;
    case "ready":
      return chartArrived ? "" : `Loading ${member.name || member.key}`;
    case "no-data":
      return `${member.name || member.key}: no data (${member.code})${member.message ? ` — ${member.message}` : ""}`;
    case "error":
      return `${member.name || member.key}: failed (${member.code})${member.message ? ` — ${member.message}` : ""}`;
    case "cancelled":
      return `${member.name || member.key}: cancelled`;
    default:
      return "";
  }
}

/** Terminal summary line of a chart group, e.g. "2 / 3 ready · 1 no data". */
export function chartGroupSummaryText(group) {
  const s = group?.summary;
  if (!s) return "";
  const parts = [`${s.ready} / ${s.expected} ready`];
  if (s.noData) parts.push(`${s.noData} no data`);
  if (s.error) parts.push(`${s.error} ${s.error === 1 ? "error" : "errors"}`);
  if (s.cancelled) parts.push(`${s.cancelled} cancelled`);
  if (!group.final) parts.push("in progress");
  return parts.join(" · ");
}

/**
 * Columns a chart grid takes for a content width (design §4.2): two only when, after the gap, each
 * column is at least {@link ARTIFACT_GRID_MIN_COLUMN} CSS px and there are at least two charts; never
 * more than {@link ARTIFACT_GRID_MAX_COLUMNS}; else one. The stylesheet's
 * `minmax(max(360px, calc(50% - 8px)), 1fr)` track rule realises the same decision from the grid's own
 * width, so this function is the testable statement of the rule, not a second layout engine. Pure.
 * @param {number} availableWidth the grid's content width in CSS px (padding already excluded)
 * @param {number} chartCount
 */
export function artifactGridColumns(availableWidth, chartCount) {
  const w = Number(availableWidth);
  const n = Math.floor(Number(chartCount) || 0);
  if (!(w > 0) || n < 2) return 1;
  const twoFit = (w - ARTIFACT_GRID_GAP) / 2 >= ARTIFACT_GRID_MIN_COLUMN;
  return twoFit ? Math.min(ARTIFACT_GRID_MAX_COLUMNS, n) : 1;
}

/** User-visible cutoff confirmation. */
export const CUTOFF_CONFIRM_MESSAGE =
  "Trim the visible conversation before this response? This does not delete durable server stream rows.";

/**
 * Default collapsed state for a table artifact (§3).
 * @param {boolean} rowHasChart
 * @param {number} tableCount
 * @returns {boolean}
 */
export function defaultTableCollapsed(rowHasChart, tableCount) {
  if (rowHasChart) return true;
  if (tableCount <= 2) return false;
  return true;
}

/**
 * @param {TableBlock} tb
 * @returns {string}
 */
export function tableArtifactStableKey(tb, seq) {
  const cid = tb.cacheId != null ? String(tb.cacheId).trim() : "";
  if (cid) return `table:${cid}`;
  const sid = tb.sourceCacheId != null ? String(tb.sourceCacheId).trim() : "";
  if (sid) return `table:src:${sid}:${seq}`;
  return `table:seq:${seq}`;
}

/**
 * @param {ChartBlock} chart
 * @param {number} seq
 * @returns {string}
 */
export function chartArtifactStableKey(chart, seq) {
  const id = chart.chartId != null ? String(chart.chartId).trim() : "";
  if (id) return `chart:${id}`;
  const src =
    chart.source?.sourceCacheId != null
      ? String(chart.source.sourceCacheId).trim()
      : "";
  if (src) return `chart:src:${src}:${seq}`;
  return `chart:seq:${seq}`;
}

/**
 * @param {ChartBlock[]} charts
 * @param {TableBlock[]} tables
 * @returns {RowArtifact[]}
 */
export function buildArtifactsFromLegacyBuckets(charts, tables) {
  /** @type {RowArtifact[]} */
  const out = [];
  let seq = 0;
  for (const table of tables) {
    out.push({
      type: "table",
      seq,
      key: tableArtifactStableKey(table, seq),
      table,
    });
    seq += 1;
  }
  for (const chart of charts) {
    out.push({
      type: "chart",
      seq,
      key: chartArtifactStableKey(chart, seq),
      chart,
    });
    seq += 1;
  }
  return out;
}

/**
 * Order row artifacts for render: immediate-parent table/chart pairing only
 * (`chart.source.sourceCacheId === table.cacheId`). Unpaired tables keep append
 * sequence; tables paired to a chart emit immediately before that chart. Unpaired
 * tables that arrived earlier in seq may still appear before a later paired group.
 *
 * Scenario B (intermediate cache invisible on the row) is out of scope until
 * `ChartBlock.source` carries an explicit lineage chain on the wire.
 *
 * @param {RowArtifact[]} artifacts
 * @returns {RowArtifact[]}
 */
export function orderArtifactsForDisplay(artifacts) {
  if (!Array.isArray(artifacts) || artifacts.length === 0) return [];

  /** @type {RowArtifact[]} */
  const tables = [];
  /** @type {RowArtifact[]} */
  const charts = [];
  for (const a of artifacts) {
    if (!a || (a.type !== "table" && a.type !== "chart")) continue;
    if (a.type === "table" && a.table) tables.push(a);
    else if (a.type === "chart" && a.chart) charts.push(a);
  }
  tables.sort((a, b) => a.seq - b.seq);
  charts.sort((a, b) => a.seq - b.seq);

  /** @type {Map<string, RowArtifact>} */
  const tablesByCacheId = new Map();
  for (const t of tables) {
    const cid = t.table?.cacheId != null ? String(t.table.cacheId).trim() : "";
    if (cid && !tablesByCacheId.has(cid)) tablesByCacheId.set(cid, t);
  }

  /** @type {Map<string, RowArtifact | null>} */
  const pairByChartKey = new Map();
  const usedTableKeys = new Set();
  /** @type {RowArtifact[]} */
  const ordered = [];

  /**
   * @param {RowArtifact} chartArt
   * @returns {RowArtifact | null}
   */
  function resolvePairedTable(chartArt) {
    const chart = chartArt.chart;
    if (!chart) return null;
    const parentId =
      chart.source?.sourceCacheId != null
        ? String(chart.source.sourceCacheId).trim()
        : "";
    if (!parentId) return null;

    return tablesByCacheId.get(parentId) ?? null;
  }

  for (const chartArt of charts) {
    pairByChartKey.set(chartArt.key, resolvePairedTable(chartArt));
  }

  const all = [...tables, ...charts].sort((a, b) => a.seq - b.seq);
  for (const a of all) {
    if (a.type === "table") {
      let pairedToChart = false;
      for (const chartArt of charts) {
        const paired = pairByChartKey.get(chartArt.key);
        if (paired && paired.key === a.key) {
          pairedToChart = true;
          break;
        }
      }
      if (pairedToChart) continue;
      if (!usedTableKeys.has(a.key)) {
        ordered.push(a);
        usedTableKeys.add(a.key);
      }
      continue;
    }
    const paired = pairByChartKey.get(a.key);
    if (paired && !usedTableKeys.has(paired.key)) {
      ordered.push(paired);
      usedTableKeys.add(paired.key);
    }
    ordered.push(a);
  }

  for (const t of tables) {
    if (!usedTableKeys.has(t.key)) ordered.push(t);
  }

  return ordered;
}

/**
 * The chart's direct parent table in the same answer: the table artifact whose `cacheId`
 * equals the chart's `source.sourceCacheId` (the same explicit relation
 * `orderArtifactsForDisplay` pairs on; never inferred from titles). Charts that share a parent
 * resolve to the same artifact, so one table serves them all.
 * @param {RowArtifact[]} artifacts
 * @param {ChartBlock | null | undefined} chart
 * @returns {RowArtifact | null}
 */
export function parentTableArtifactForChart(artifacts, chart) {
  const parentId =
    chart?.source?.sourceCacheId != null ? String(chart.source.sourceCacheId).trim() : "";
  if (!parentId || !Array.isArray(artifacts)) return null;
  const tables = artifacts
    .filter((a) => a && a.type === "table" && a.table)
    .sort((a, b) => a.seq - b.seq);
  return (
    tables.find((t) => (t.table?.cacheId != null ? String(t.table.cacheId).trim() : "") === parentId) ??
    null
  );
}

/**
 * Compact header label when no explicit table title exists (§3).
 * @param {TableBlock} tb
 * @returns {string}
 */
export function tableDisclosureSummaryLabel(tb) {
  const title =
    tb.presentationTitle != null ? String(tb.presentationTitle).trim() : "";
  if (title.length > 0) {
    return title;
  }
  const cols = Array.isArray(tb.columns) ? tb.columns : [];
  const labels = cols
    .map((c) => (c?.label != null ? String(c.label).trim() : ""))
    .filter((s) => s.length > 0);
  const colPart =
    labels.length > 0
      ? `Columns: ${labels.join(", ")}`
      : cols.length > 0
        ? `Columns: ${cols.length}`
        : "Table";
  const shown =
    typeof tb.shownRows === "number" && Number.isFinite(tb.shownRows)
      ? tb.shownRows
      : Array.isArray(tb.rows)
        ? tb.rows.length
        : 0;
  const total =
    typeof tb.totalRows === "number" && Number.isFinite(tb.totalRows)
      ? tb.totalRows
      : shown;
  return `${colPart} · ${shown}/${total} rows`;
}

/**
 * @param {ArtifactType} type
 * @param {ChartBlock | TableBlock} payload
 * @param {number} seq
 * @returns {RowArtifact}
 */
export function makeRowArtifact(type, payload, seq) {
  if (type === "chart") {
    const chart = /** @type {ChartBlock} */ (payload);
    return {
      type: "chart",
      seq,
      key: chartArtifactStableKey(chart, seq),
      chart,
    };
  }
  const table = /** @type {TableBlock} */ (payload);
  return {
    type: "table",
    seq,
    key: tableArtifactStableKey(table, seq),
    table,
  };
}

/**
 * Expand collapsed table bodies in a cloned assistant bubble for print (§4).
 * @param {HTMLElement} root
 */
export function expandTablesForPrint(root) {
  if (!root || typeof root.querySelectorAll !== "function") return;
  root.querySelectorAll(".parler-data-table-wrap--collapsed").forEach((el) => {
    el.classList.remove("parler-data-table-wrap--collapsed");
    const btn = el.querySelector(".parler-data-table-disclosure");
    if (btn) btn.setAttribute("aria-expanded", "true");
    const panel = el.querySelector(".parler-data-table-panel");
    if (panel) panel.removeAttribute("hidden");
  });
}
