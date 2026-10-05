/**
 * Heatmap colour scale (chart-enhancement design §7.4 C2b-3). Derived from the existing Theme with no
 * new token: an all-non-negative or all-non-positive matrix uses a sequential ramp from the plot
 * surface to series slot 0; a matrix crossing zero uses a diverging ramp symmetric about 0 from
 * series slot 1 through the surface to series slot 0; a constant matrix takes the midpoint colour.
 * "Surface → colour" is realised as the series colour at rising opacity, so the ramp sits on any
 * surface the host paints and the print rewriter only has to swap the series colour. Pure.
 *
 * @typedef {"sequential" | "diverging" | "constant"} HeatScaleKind
 * @typedef {{ kind: HeatScaleKind, min: number, max: number, ticks: number[] }} HeatScale
 */

/** Lowest opacity of the ramp (the near-surface end stays visibly a cell, never transparent). */
export const HEAT_MIN_OPACITY = 0.08;
/** Opacity of the midpoint colour of a constant matrix and the neutral centre of a diverging ramp. */
export const HEAT_MID_OPACITY = 0.5;

/**
 * Decide the scale from the finite values of the matrix.
 * @param {(number | null)[][]} values
 * @returns {HeatScale}
 */
export function heatScale(values) {
  let min = Infinity;
  let max = -Infinity;
  for (const row of values ?? []) {
    for (const v of row ?? []) {
      if (typeof v === "number" && Number.isFinite(v)) {
        if (v < min) min = v;
        if (v > max) max = v;
      }
    }
  }
  if (!Number.isFinite(min)) return { kind: "constant", min: 0, max: 0, ticks: [0] };
  if (min === max) return { kind: "constant", min, max, ticks: [min] };
  if (min < 0 && max > 0) return { kind: "diverging", min, max, ticks: [min, 0, max] };
  return { kind: "sequential", min, max, ticks: [min, max] };
}

/**
 * Normalised position of a value on the scale: `[0, 1]` for sequential (min → max), `[-1, 1]` for
 * diverging (−|extent| → 0 → +|extent|, symmetric about zero so equal magnitudes get equal
 * strength), `0` for constant.
 * @param {number} v @param {HeatScale} scale
 */
export function heatPosition(v, scale) {
  if (scale.kind === "constant") return 0;
  if (scale.kind === "diverging") {
    const extent = Math.max(Math.abs(scale.min), Math.abs(scale.max));
    return extent > 0 ? Math.max(-1, Math.min(1, v / extent)) : 0;
  }
  const span = scale.max - scale.min;
  return span > 0 ? Math.max(0, Math.min(1, (v - scale.min) / span)) : 0;
}

/**
 * Fill colour and opacity for a normalised position: the series slot (0 for the positive/sequential
 * end, 1 for the negative end) and an opacity rising from {@link HEAT_MIN_OPACITY} at the surface end.
 * @param {number} t position from {@link heatPosition} @param {HeatScaleKind} kind
 * @param {{ series: string[] }} palette any palette with 24 series colours (screen or print)
 * @returns {{ fill: string, opacity: number, slot: number }}
 */
export function heatPaint(t, kind, palette) {
  if (kind === "constant") return { fill: palette.series[0], opacity: HEAT_MID_OPACITY, slot: 0 };
  if (kind === "diverging") {
    const slot = t < 0 ? 1 : 0;
    const strength = Math.min(1, Math.abs(t));
    return { fill: palette.series[slot], opacity: HEAT_MIN_OPACITY + (1 - HEAT_MIN_OPACITY) * strength, slot };
  }
  const clamped = Math.max(0, Math.min(1, t));
  return { fill: palette.series[0], opacity: HEAT_MIN_OPACITY + (1 - HEAT_MIN_OPACITY) * clamped, slot: 0 };
}

/** Gradient stop positions (0..1 along the bar) and their scale positions for a legend colour bar. */
export function heatBarStops(kind) {
  if (kind === "constant") return [{ offset: 0, t: 0 }, { offset: 1, t: 0 }];
  if (kind === "diverging") return [{ offset: 0, t: -1 }, { offset: 0.5, t: 0 }, { offset: 1, t: 1 }];
  return [{ offset: 0, t: 0 }, { offset: 1, t: 1 }];
}
