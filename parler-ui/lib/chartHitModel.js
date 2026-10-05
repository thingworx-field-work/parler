/**
 * Hit model for chart point query (chart-enhancement design §4.2, §4.4, §11.1): the renderer
 * emits every drawn mark's logical plot coordinates and tooltip lines; the card component turns
 * pointer and keyboard actions into lookups here. Pure; no DOM, no platform calls.
 *
 * @typedef {object} ChartHitItem
 * @property {"point" | "bar" | "slice" | "reference"} type
 * @property {number} series series index; `-1` for reference lines
 * @property {number} index point / category / slice / reference index
 * @property {number} cx logical x inside the plot group
 * @property {number} cy logical y inside the plot group
 * @property {string[]} lines tooltip text lines (plain text, never markup)
 * @property {number} [x] bar band start
 * @property {number} [width] bar band width
 * @property {number} [startAngle] slice start (radians, clockwise from 12 o'clock)
 * @property {number} [endAngle] slice end
 *
 * @typedef {object} ChartHitLayout
 * @property {number} viewBoxW
 * @property {number} viewBoxH
 * @property {number} marginLeft
 * @property {number} marginTop
 * @property {number} innerW
 * @property {number} innerH
 * @property {{ cx: number, cy: number, r: number }} [pie]
 */

const DEFAULT_MAX_DISTANCE = 24;

/** Largest index whose value is <= target in an ascending array (binary search). */
function lowerIndex(sorted, target, key) {
  let lo = 0;
  let hi = sorted.length - 1;
  let best = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (key(sorted[mid]) <= target) {
      best = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return best;
}

/**
 * @param {object} input
 * @param {"line" | "bar" | "scatter" | "pie" | "histogram" | "boxplot" | "heatmap"} input.kind
 * @param {ChartHitItem[][]} input.series items per series in data order
 * @param {ChartHitItem[]} input.references reference-line items (cy set; cx filled on demand;
 *   for horizontal bars cx is set and cy is filled on demand)
 * @param {ChartHitLayout} input.layout
 * @param {"vertical" | "horizontal"} [input.orientation] bar only (design §7.3); bands run down
 *   the plot when horizontal, so `nearest` tests `py` against `y`/`height` and keyboard steps keep
 *   their data meaning (left/right adjacent category, up/down adjacent series and references)
 */
export function createChartHitModel({ kind, series, references, layout, orientation = "vertical" }) {
  const sorted = series.map((items) => [...items].sort((a, b) => a.cx - b.cx));
  const cartesian = kind === "line" || kind === "scatter";
  const horizontal = kind === "bar" && orientation === "horizontal";
  /** Position along the category run: cy for horizontal bars, cx otherwise. */
  const along = (it) => (horizontal ? it.cy : it.cx);

  /** A reference line placed at the current item's position along the category run. */
  function referenceAt(refIndex, at) {
    const ref = references[refIndex];
    if (!ref) return null;
    return horizontal ? { ...ref, cy: at } : { ...ref, cx: at };
  }

  /** Nearest item to a logical plot position, bounded by `maxDistance` logical px. */
  function nearest(px, py, maxDistance = DEFAULT_MAX_DISTANCE) {
    if (!Number.isFinite(px) || !Number.isFinite(py)) return null;
    if (kind === "pie") {
      const pie = layout.pie;
      const items = series[0] ?? [];
      if (!pie || !items.length) return null;
      const dx = px - pie.cx;
      const dy = py - pie.cy;
      const dist = Math.hypot(dx, dy);
      if (dist > pie.r) return null;
      let angle = Math.atan2(dx, -dy);
      if (angle < 0) angle += Math.PI * 2;
      return (
        items.find(
          (it) => angle >= (it.startAngle ?? 0) && angle < (it.endAngle ?? 0)
        ) ?? null
      );
    }
    if (kind === "heatmap") {
      // Cells are rectangles on both axes: the containing cell, else the nearest cell edge.
      let best = null;
      let bestDist = Infinity;
      for (const items of series) {
        for (const it of items) {
          const x = it.x ?? it.cx;
          const y = it.y ?? it.cy;
          const dx = px < x ? x - px : px > x + (it.width ?? 0) ? px - x - (it.width ?? 0) : 0;
          const dy = py < y ? y - py : py > y + (it.height ?? 0) ? py - y - (it.height ?? 0) : 0;
          const d = Math.hypot(dx, dy);
          if (d < bestDist) {
            best = it;
            bestDist = d;
          }
        }
      }
      return best && bestDist <= maxDistance ? best : null;
    }
    if (kind === "bar" || kind === "histogram" || kind === "boxplot") {
      let best = null;
      let bestDist = Infinity;
      for (const items of series) {
        for (const it of items) {
          let d;
          let tie;
          if (horizontal) {
            const y = it.y ?? it.cy;
            const hgt = it.height ?? 0;
            const inside = py >= y && py <= y + hgt;
            d = inside ? 0 : Math.min(Math.abs(py - y), Math.abs(py - (y + hgt)));
            tie = best ? Math.abs(px - it.cx) < Math.abs(px - best.cx) : false;
          } else {
            const x = it.x ?? it.cx;
            const w = it.width ?? 0;
            const inside = px >= x && px <= x + w;
            d = inside ? 0 : Math.min(Math.abs(px - x), Math.abs(px - (x + w)));
            tie = best ? Math.abs(py - it.cy) < Math.abs(py - best.cy) : false;
          }
          if (d < bestDist || (d === bestDist && tie)) {
            best = it;
            bestDist = d;
          }
        }
      }
      return best && bestDist <= maxDistance ? best : null;
    }
    // Cartesian: from the index position, widen along x in both directions while a mark could
    // still beat the best distance (distance >= |dx|), so duplicate x values, steep neighbours
    // and marks beyond the two nearest x positions are all considered within the bound.
    let best = null;
    let bestDist = maxDistance;
    const consider = (it) => {
      const d = Math.hypot(it.cx - px, it.cy - py);
      if (d < bestDist || (d === bestDist && best === null)) {
        best = it;
        bestDist = d;
      }
    };
    for (const items of sorted) {
      if (!items.length) continue;
      const at = lowerIndex(items, px, (it) => it.cx);
      for (let i = at; i >= 0 && px - items[i].cx <= bestDist; i--) consider(items[i]);
      for (let i = at + 1; i < items.length && items[i].cx - px <= bestDist; i++) consider(items[i]);
    }
    return best;
  }

  function positionWithin(item) {
    if (item.type === "reference") return -1;
    const items = cartesian ? sorted[item.series] : series[item.series];
    return items ? items.findIndex((it) => it.index === item.index) : -1;
  }

  function itemAt(seriesIndex, position) {
    const items = cartesian ? sorted[seriesIndex] : series[seriesIndex];
    if (!items || !items.length) return null;
    return items[Math.max(0, Math.min(items.length - 1, position))] ?? null;
  }

  /** First queryable item: the first drawn point of the first series. */
  function first() {
    for (let si = 0; si < series.length; si++) {
      const it = itemAt(si, 0);
      if (it) return it;
    }
    return null;
  }

  /**
   * Keyboard step. left/right: adjacent item in the same series (data order along x);
   * up/down: same position in the adjacent series, then the reference lines above the last
   * series; home/end: first/last item of the current series. Returns the current item when a
   * step is not possible.
   * @param {ChartHitItem | null} current
   * @param {"left" | "right" | "up" | "down" | "home" | "end"} direction
   */
  function step(current, direction) {
    if (!current) return first();
    if (current.type === "reference") {
      if (direction === "up") return referenceAt(current.index + 1, along(current)) ?? current;
      if (direction === "down") {
        if (current.index > 0) return referenceAt(current.index - 1, along(current)) ?? current;
        for (let si = series.length - 1; si >= 0; si--) {
          const items = cartesian ? sorted[si] : series[si];
          if (!items?.length) continue;
          const at = lowerIndex(items, along(current), along);
          return items[Math.max(0, at)] ?? current;
        }
        return current;
      }
      return current;
    }
    const pos = positionWithin(current);
    if (pos < 0) return current;
    switch (direction) {
      case "left":
        return itemAt(current.series, pos - 1) ?? current;
      case "right":
        return itemAt(current.series, pos + 1) ?? current;
      case "home":
        return itemAt(current.series, 0) ?? current;
      case "end":
        return itemAt(current.series, Number.MAX_SAFE_INTEGER) ?? current;
      case "up": {
        if (kind === "pie") return current;
        for (let si = current.series + 1; si < series.length; si++) {
          const it = itemAt(si, pos);
          if (it) return it;
        }
        return referenceAt(0, along(current)) ?? current;
      }
      case "down": {
        if (kind === "pie") return current;
        for (let si = current.series - 1; si >= 0; si--) {
          const it = itemAt(si, pos);
          if (it) return it;
        }
        return current;
      }
      default:
        return current;
    }
  }

  /**
   * Re-find an item by identity after a redraw. `cx`/`cy` restore a reference line's position
   * along the category run (cx for vertical layouts, cy for horizontal bars).
   */
  function find(type, seriesIndex, index, cx, cy) {
    if (type === "reference") return referenceAt(index, horizontal ? cy ?? 0 : cx ?? 0);
    const items = series[seriesIndex];
    return items?.find((it) => it.index === index) ?? null;
  }

  return { kind, orientation: horizontal ? "horizontal" : "vertical", layout, series, references, nearest, step, first, find };
}
