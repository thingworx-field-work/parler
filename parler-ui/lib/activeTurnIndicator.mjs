export const DEFAULT_ACTIVE_TURN_WORKING = "Working";
export const DEFAULT_PROGRESS_LABEL = "Thinking...";
export const DEFAULT_RATE_CONTROL_LABEL = "Waiting for capacity...";

/**
 * @param {unknown} value
 * @returns {"detailed" | "compact"}
 */
export function normalizeProgressPresentation(value) {
  return value === "compact" ? "compact" : "detailed";
}

/**
 * @param {unknown} value
 * @param {string} fallback
 * @returns {string}
 */
export function normalizeProgressLabel(value, fallback) {
  const text = typeof value === "string" ? value.trim() : "";
  return text || fallback;
}

/**
 * UI-only tail indicator for the active assistant turn.
 *
 * Shows while the turn is still busy, including while final markdown streams.
 * Intermediate task-state panels, charts, tables, and partial markdown do not
 * suppress the polite status; render order decides where it sits.
 *
 * @param {import("./types.js").ChatUiState} state
 * @param {import("./types.js").ChatRow} row
 * @param {number} index
 * @param {string | {
 *   presentation?: unknown,
 *   progressLabel?: unknown,
 *   rateControlLabel?: unknown,
 *   detailedFallback?: string
 * }} [options]
 * @returns {string | null}
 */
export function activeTurnIndicatorText(
  state,
  row,
  index,
  options = DEFAULT_ACTIVE_TURN_WORKING
) {
  if (!row || row.kind !== "assistant") return null;
  if (!state?.busy || state.activeRequestId !== row.requestId) return null;
  if (index !== state.rows.length - 1) return null;

  if (options && typeof options === "object") {
    if (normalizeProgressPresentation(options.presentation) === "compact") {
      return row.rateControlWaiting === true
        ? normalizeProgressLabel(
            options.rateControlLabel,
            DEFAULT_RATE_CONTROL_LABEL
          )
        : normalizeProgressLabel(options.progressLabel, DEFAULT_PROGRESS_LABEL);
    }
    const fallback = normalizeProgressLabel(
      options.detailedFallback,
      DEFAULT_ACTIVE_TURN_WORKING
    );
    const activity = row.activity?.trim();
    return activity && activity.length > 0 ? activity : fallback;
  }

  const act = row.activity?.trim();
  const fallback = normalizeProgressLabel(options, DEFAULT_ACTIVE_TURN_WORKING);
  return act && act.length > 0 ? act : fallback;
}
