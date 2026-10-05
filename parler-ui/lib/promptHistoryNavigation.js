/**
 * Composer prompt-history navigation helpers.
 * Index 0 means latest user prompt, 1 means one prompt older, and so on.
 */

/** @typedef {{ index: number, scratch: string }} PromptHistoryNavigationState */

/** @returns {PromptHistoryNavigationState} */
export function initialPromptHistoryNavigationState() {
  return { index: -1, scratch: "" };
}

/**
 * @param {import('./types.js').ChatRow[]} rows
 * @returns {string[]}
 */
export function userPromptHistoryFromRows(rows) {
  if (!Array.isArray(rows)) return [];
  const out = [];
  for (const row of rows) {
    if (row && row.kind === "user" && typeof row.text === "string" && row.text.length > 0) {
      out.push(row.text);
    }
  }
  return out;
}

/**
 * @param {string[]} history chronological user prompt history
 * @param {PromptHistoryNavigationState} state
 * @param {'up' | 'down'} direction
 * @param {string} currentDraft
 * @returns {{ handled: boolean, draft: string, state: PromptHistoryNavigationState }}
 */
export function navigatePromptHistory(history, state, direction, currentDraft) {
  const safeHistory = Array.isArray(history) ? history : [];
  const safeState =
    state && Number.isInteger(state.index)
      ? state
      : initialPromptHistoryNavigationState();
  if (safeHistory.length === 0) {
    return {
      handled: false,
      draft: currentDraft,
      state: initialPromptHistoryNavigationState(),
    };
  }

  if (direction === "up") {
    const scratch = safeState.index < 0 ? currentDraft : safeState.scratch;
    const nextIndex = Math.min(safeState.index + 1, safeHistory.length - 1);
    return {
      handled: true,
      draft: safeHistory[safeHistory.length - 1 - nextIndex],
      state: { index: nextIndex, scratch },
    };
  }

  if (safeState.index < 0) {
    return { handled: false, draft: currentDraft, state: safeState };
  }
  if (safeState.index === 0) {
    return {
      handled: true,
      draft: safeState.scratch,
      state: initialPromptHistoryNavigationState(),
    };
  }

  const nextIndex = safeState.index - 1;
  return {
    handled: true,
    draft: safeHistory[safeHistory.length - 1 - nextIndex],
    state: { index: nextIndex, scratch: safeState.scratch },
  };
}
