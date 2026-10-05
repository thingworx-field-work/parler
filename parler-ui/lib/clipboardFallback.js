/**
 * Legacy clipboard fallback for ThingWorx mashups that run in non-secure or
 * restricted browser contexts where `navigator.clipboard` is unavailable.
 */

/**
 * @param {Document} doc
 * @param {{ html: string, plain: string }} parts
 * @returns {boolean}
 */
export function writeClipboardWithSelectionFallback(doc, parts) {
  if (!doc || typeof doc.createElement !== "function") return false;
  if (typeof doc.execCommand !== "function") return false;
  const body = doc.body;
  if (!body) return false;

  const plain = parts?.plain == null ? "" : String(parts.plain);
  const html = parts?.html == null ? "" : String(parts.html);

  const node = doc.createElement("div");
  node.setAttribute("contenteditable", "true");
  node.setAttribute("aria-hidden", "true");
  node.style.position = "fixed";
  node.style.left = "-10000px";
  node.style.top = "0";
  node.style.width = "1px";
  node.style.height = "1px";
  node.style.overflow = "hidden";
  node.innerHTML = html;

  /** @type {Range[]} */
  const previousRanges = [];
  const selection = typeof doc.getSelection === "function" ? doc.getSelection() : null;
  if (selection) {
    for (let i = 0; i < selection.rangeCount; i++) {
      previousRanges.push(selection.getRangeAt(i).cloneRange());
    }
  }

  const onCopy = (ev) => {
    const data = ev.clipboardData;
    if (!data) return;
    data.setData("text/plain", plain);
    data.setData("text/html", html);
    ev.preventDefault();
  };

  body.appendChild(node);
  doc.addEventListener("copy", onCopy);
  try {
    if (selection && typeof doc.createRange === "function") {
      const range = doc.createRange();
      range.selectNodeContents(node);
      selection.removeAllRanges();
      selection.addRange(range);
    }
    return !!doc.execCommand("copy");
  } catch {
    return false;
  } finally {
    doc.removeEventListener("copy", onCopy);
    try {
      node.remove();
    } catch {
      body.removeChild(node);
    }
    if (selection) {
      selection.removeAllRanges();
      previousRanges.forEach((range) => selection.addRange(range));
    }
  }
}
