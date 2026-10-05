const DANGER_ITEM_STATUSES = new Set(["failed", "cancelled", "expired"]);
const WARNING_STATUS = "blocked-by-approval";

/** @param {unknown} value */
function positiveNumber(value) {
  const number = Number(value ?? 0);
  return Number.isFinite(number) && number > 0;
}

/** @param {unknown} taskState */
export function validTaskState(taskState) {
  return Boolean(
    taskState &&
      typeof taskState === "object" &&
      typeof taskState.status === "string" &&
      taskState.status.trim()
  );
}

/**
 * @param {unknown} taskState
 * @returns {{
 *   requiresAttention: boolean,
 *   severity: "info" | "success" | "warning" | "danger"
 * }}
 */
export function classifyTaskState(taskState) {
  if (!validTaskState(taskState)) {
    return { requiresAttention: false, severity: "info" };
  }
  const ts = /** @type {import('./types.js').TaskStateSnapshot} */ (taskState);
  const turnStatus = ts.status.trim();
  const items = Array.isArray(ts.items) ? ts.items : [];
  const statuses = items.map((item) =>
    item && typeof item.status === "string" ? item.status.trim() : ""
  );
  const summary =
    ts.summary && typeof ts.summary === "object" ? ts.summary : {};
  const danger =
    turnStatus === "failed" ||
    statuses.some((status) => DANGER_ITEM_STATUSES.has(status)) ||
    positiveNumber(summary.failed);
  const warning =
    turnStatus === WARNING_STATUS ||
    statuses.includes(WARNING_STATUS) ||
    positiveNumber(summary.blocked);
  const success = turnStatus === "completed" || statuses.includes("satisfied");
  return {
    requiresAttention: danger || warning,
    severity: danger
      ? "danger"
      : warning
        ? "warning"
        : success
          ? "success"
          : "info",
  };
}

/**
 * Shared v1b attention-required classification for detailed and compact View
 * projection. Turn-level `expired`, `cancelled`, and `rejected` are not valid
 * v1b root statuses; the corresponding item-level states remain actionable.
 *
 * @param {unknown} taskState
 */
export function taskStateRequiresAttention(taskState) {
  return classifyTaskState(taskState).requiresAttention;
}

/**
 * @param {unknown} taskState
 * @returns {"info" | "success" | "warning" | "danger"}
 */
export function taskStateSeverity(taskState) {
  return classifyTaskState(taskState).severity;
}

/**
 * Whether to render the v1b task-state process panel for an assistant row.
 *
 * @param {import('./types.js').ChatRow} row
 * @param {boolean} isActiveTurn {@code busy && activeRequestId === row.requestId} for assistant rows
 * @param {"detailed" | "compact"} [presentation]
 * @returns {boolean}
 */
export function shouldRenderTaskStatePanelForRow(
  row,
  isActiveTurn,
  presentation = "detailed"
) {
  if (!row || row.kind !== "assistant" || !validTaskState(row.taskState)) {
    return false;
  }
  const requiresAttention = taskStateRequiresAttention(row.taskState);
  return isActiveTurn && presentation !== "compact"
    ? true
    : requiresAttention;
}
