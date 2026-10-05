/**
 * Local-time presentation helpers for chart axes and tooltips (chart-enhancement design §5.2).
 * Wire instants stay UTC ISO; the reference client presents browser-local wall time
 * (docs/architecture/times-solution.md, "present in local time"). Pure; no DOM.
 */

const pad2 = (n) => String(n).padStart(2, "0");

/** @param {Date} d */
export function localDateKey(d) {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** Offset of the browser-local zone at the instant, e.g. `UTC+08:00`. @param {Date} d */
export function localOffsetLabel(d) {
  const minutes = -d.getTimezoneOffset();
  const sign = minutes >= 0 ? "+" : "-";
  const abs = Math.abs(minutes);
  return `UTC${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)}`;
}

/** IANA name of the display zone when the runtime exposes it; empty otherwise. */
export function displayTimeZoneName() {
  try {
    return String(Intl.DateTimeFormat().resolvedOptions().timeZone ?? "");
  } catch {
    return "";
  }
}

/** Display-zone label for tooltips: IANA name plus offset, or the offset alone. @param {Date} d */
export function displayTimeZoneLabel(d) {
  const name = displayTimeZoneName();
  const offset = localOffsetLabel(d);
  return name ? `${name} (${offset})` : offset;
}

const pad3 = (n) => String(n).padStart(3, "0");

/** Local wall-clock key to the second, used to detect repeated local times. @param {Date} d */
function localSecondKey(d) {
  return `${localDateKey(d)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

/**
 * Full local wall-clock time with seconds and, when present, milliseconds, e.g.
 * `2026-09-12 14:03:05` or `2026-09-12 14:03:05.100`; query text keeps the precision the instant
 * carries (design §4.2) while axes may round.
 * @param {Date} d
 */
export function formatFullLocalTime(d) {
  const ms = d.getMilliseconds();
  return `${localSecondKey(d)}${ms ? `.${pad3(ms)}` : ""}`;
}

/**
 * Axis tick labels chosen by the span the ticks cover:
 * - one local calendar day: `HH:mm` (`HH:mm:ss` when any tick carries seconds);
 * - more than one day: `MM-DD HH:mm`, or `MM-DD` when every tick sits at local midnight;
 * - when two distinct instants would print the same label (repeated local time around a DST
 *   fall-back), every label carries its UTC offset so the repetition is visible.
 *
 * @param {Date[]} ticks
 * @returns {{ labels: string[], crossDay: boolean, showOffset: boolean }}
 */
export function timeTickLabels(ticks) {
  const dates = ticks.filter((t) => t instanceof Date && Number.isFinite(t.getTime()));
  const crossDay = new Set(dates.map(localDateKey)).size > 1;
  const allMidnight = dates.every(
    (t) => t.getHours() === 0 && t.getMinutes() === 0 && t.getSeconds() === 0 && t.getMilliseconds() === 0
  );
  // Precision: enough to tell distinct instants apart (minutes, seconds, then milliseconds).
  const distinct = [...new Map(dates.map((t) => [t.getTime(), t])).values()];
  const minuteKeys = new Set(distinct.map((t) => `${localSecondKey(t).slice(0, 16)}|${t.getTimezoneOffset()}`));
  const secondKeys = new Set(distinct.map((t) => `${localSecondKey(t)}|${t.getTimezoneOffset()}`));
  const needMillis = secondKeys.size < distinct.length || dates.some((t) => t.getMilliseconds() !== 0);
  const needSeconds =
    needMillis || minuteKeys.size < distinct.length || dates.some((t) => t.getSeconds() !== 0);
  // A DST fall-back repeats a local wall-clock time under a different offset.
  const byWallClock = new Map();
  let showOffset = false;
  for (const t of distinct) {
    const key = localSecondKey(t) + (needMillis ? `.${pad3(t.getMilliseconds())}` : "");
    const offset = t.getTimezoneOffset();
    const seen = byWallClock.get(key);
    if (seen !== undefined && seen !== offset) showOffset = true;
    byWallClock.set(key, offset);
  }
  const base = (t) => {
    let hm = `${pad2(t.getHours())}:${pad2(t.getMinutes())}`;
    if (needSeconds) hm += `:${pad2(t.getSeconds())}`;
    if (needMillis) hm += `.${pad3(t.getMilliseconds())}`;
    if (crossDay) {
      const md = `${pad2(t.getMonth() + 1)}-${pad2(t.getDate())}`;
      return allMidnight ? md : `${md} ${hm}`;
    }
    return hm;
  };
  let labels = dates.map(base);
  if (showOffset) labels = labels.map((label, i) => `${label} ${localOffsetLabel(dates[i])}`);
  return { labels, crossDay, showOffset };
}

/**
 * Choose datetime ticks that fit the plot: labels are formatted for each candidate tick set,
 * the tick count is reduced until adjacent formatted labels no longer overlap (using the
 * renderer's average glyph width), and ticks whose centred label would run past the space
 * available beside the plot are dropped. Returns at least one tick when the scale has any.
 *
 * @param {object} input
 * @param {(count: number) => Date[]} input.ticksFor tick candidates for a count hint
 * @param {(d: Date) => number} input.xFor logical x of an instant inside the plot
 * @param {number} input.desired starting tick-count hint
 * @param {number} input.innerW plot width
 * @param {number} input.leftRoom space left of the plot a label may use
 * @param {number} input.rightRoom space right of the plot a label may use
 * @param {number} input.charWidth average glyph width in logical px
 * @param {number} [input.gap] minimum gap between adjacent labels
 * @param {(x: number) => Date} [input.instantAt] inverse of `xFor`, used to synthesise a
 *   plot-centre tick when no generated tick can be shown without clipping
 * @returns {{ ticks: Date[], format: (d: Date) => string, crossDay: boolean, showOffset: boolean, labelWidth: number }}
 */
export function fitTimeTicks({ ticksFor, xFor, desired, innerW, leftRoom, rightRoom, charWidth, gap = 8, instantAt }) {
  /** Format a tick set and keep only the ticks whose centred label stays inside the gutters. */
  const evaluate = (ticks) => {
    const fmt = timeTickFormatter(ticks);
    const labels = ticks.map(fmt.format);
    const longest = labels.reduce((m, l) => Math.max(m, l.length), 0);
    const labelWidth = longest * charWidth;
    const half = labelWidth / 2;
    const kept = ticks.filter((t) => {
      const x = xFor(t);
      return Number.isFinite(x) && x - half >= -leftRoom && x + half <= innerW + rightRoom;
    });
    return { ticks: kept, format: fmt.format, crossDay: fmt.crossDay, showOffset: fmt.showOffset, labelWidth };
  };
  const overlaps = (result) => {
    const xs = result.ticks.map(xFor).sort((a, b) => a - b);
    for (let i = 1; i < xs.length; i++) {
      if (xs[i] - xs[i - 1] < result.labelWidth + gap) return true;
    }
    return false;
  };

  let n = Math.max(1, Math.floor(desired));
  let last = evaluate(ticksFor(n));
  for (let attempts = 0; attempts < 24 && last.ticks.length > 0 && overlaps(last) && n > 1; attempts++) {
    const affordable = Math.floor(innerW / (last.labelWidth + gap));
    n = Math.max(1, Math.min(n - 1, affordable));
    last = evaluate(ticksFor(n));
  }
  if (last.ticks.length > 0) return last;

  // Every generated tick would clip. Look for an interior tick in finer candidate sets, choosing
  // the contained tick nearest the plot centre; each candidate set is formatted and checked on
  // its own, so the chosen label really fits.
  const centre = innerW / 2;
  for (const count of [2, 4, 8, 16, 32]) {
    const result = evaluate(ticksFor(count));
    if (!result.ticks.length) continue;
    const nearest = result.ticks.reduce((best, t) =>
      Math.abs(xFor(t) - centre) < Math.abs(xFor(best) - centre) ? t : best
    );
    return { ...result, ticks: [nearest] };
  }
  // Last resort: the instant at the plot centre, if its own label fits there.
  if (instantAt) {
    const centreTick = instantAt(centre);
    if (centreTick instanceof Date && Number.isFinite(centreTick.getTime())) {
      const result = evaluate([centreTick]);
      if (result.ticks.length) return result;
    }
  }
  // Nothing can be shown without clipping: no tick labels rather than a clipped one.
  return last;
}

/**
 * Tick formatter bound to a concrete tick set, so span-dependent choices are made once for the
 * whole axis rather than per label.
 * @param {Date[]} ticks
 * @returns {{ format: (d: Date) => string, crossDay: boolean, showOffset: boolean }}
 */
export function timeTickFormatter(ticks) {
  const { labels, crossDay, showOffset } = timeTickLabels(ticks);
  const byInstant = new Map(ticks.map((t, i) => [t.getTime(), labels[i]]));
  return {
    crossDay,
    showOffset,
    format: (d) => byInstant.get(d.getTime()) ?? timeTickLabels([d]).labels[0] ?? "",
  };
}
