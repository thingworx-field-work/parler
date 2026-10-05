/**
 * Keyboard reachability inside a container, for modal focus containment (chart-enhancement
 * design §4.5 C1b-3). Pure DOM rules, so it works in JSDOM as well as browsers: a control counts
 * only when it is enabled, not opted out with `tabindex="-1"`, not hidden or inert (itself or
 * through an ancestor), and not inside a closed `<details>` unless it is that disclosure's own
 * summary. When the browser offers `checkVisibility`, controls it reports as not rendered are
 * excluded too.
 */

const CANDIDATES =
  'a[href], area[href], button, input, select, textarea, summary, iframe, [tabindex], [contenteditable=""], [contenteditable="true"]';

/** @param {Element} el */
function isOwnSummaryOf(el, details) {
  if (el.tagName !== "SUMMARY" || el.parentElement !== details) return false;
  for (const child of details.children) {
    if (child.tagName === "SUMMARY") return child === el;
  }
  return false;
}

/**
 * True when `el` can currently receive keyboard focus by tabbing within `root`.
 * @param {Element} el @param {Element} root
 */
export function isKeyboardReachable(el, root) {
  if (!(el instanceof Element) || !root.contains(el)) return false;
  if (el.hasAttribute("disabled") || el.hasAttribute("hidden") || el.hasAttribute("inert")) return false;
  if (el.getAttribute("tabindex") === "-1") return false;
  if (el.tagName === "INPUT" && el.getAttribute("type") === "hidden") return false;
  if (el.tagName === "SUMMARY" && !(el.parentElement?.tagName === "DETAILS" && isOwnSummaryOf(el, el.parentElement))) return false;
  for (let node = el.parentElement; node && node !== root.parentElement; node = node.parentElement) {
    if (node.hasAttribute("hidden") || node.hasAttribute("inert")) return false;
    if (node.tagName === "DETAILS" && !node.hasAttribute("open") && !isOwnSummaryOf(el, node)) return false;
  }
  if (typeof el.checkVisibility === "function" && !el.checkVisibility()) return false;
  return true;
}

/**
 * Controls inside `root` that keyboard traversal can currently reach, in document order.
 * @param {Element} root @returns {HTMLElement[]}
 */
export function keyboardReachableControls(root) {
  return [...root.querySelectorAll(CANDIDATES)].filter((el) => el instanceof HTMLElement && isKeyboardReachable(el, root));
}

/**
 * Where a Tab press inside a modal container must move focus to stay contained, or `null` when
 * native traversal already stays inside: forward from the last reachable control (or from
 * outside the container) wraps to the first; backward from the first wraps to the last.
 * @param {Element} root @param {{ shiftKey: boolean, active: Element | null }} input
 * @returns {HTMLElement | null}
 */
export function focusTrapTarget(root, { shiftKey, active }) {
  const reachable = keyboardReachableControls(root);
  if (!reachable.length) return null;
  const first = reachable[0];
  const last = reachable[reachable.length - 1];
  const inside = active !== null && root.contains(active);
  if (shiftKey) return active === first || !inside ? last : null;
  return active === last || !inside ? first : null;
}
