/**
 * Normalize the string-valued ThingWorx IMAGELINK boundary to a browser image
 * source. The caller supplies TW.convertImageLink when the platform exposes it.
 *
 * @param {unknown} value
 * @param {((value: string) => unknown) | undefined} convertImageLink
 * @returns {{ value: string, source: string }}
 */
export function resolveEmptyStateMediaSource(value, convertImageLink) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!normalized) {
    return Object.freeze({ value: "", source: "" });
  }
  if (typeof convertImageLink !== "function") {
    return Object.freeze({ value: normalized, source: normalized });
  }
  try {
    const converted = convertImageLink(normalized);
    const source = typeof converted === "string" ? converted.trim() : "";
    return Object.freeze({ value: normalized, source });
  } catch {
    return Object.freeze({ value: normalized, source: "" });
  }
}

/**
 * @param {unknown} value
 * @param {((value: string) => unknown) | undefined} convertImageLink
 * @param {number} generation
 */
export function startEmptyStateMediaProjection(
  value,
  convertImageLink,
  generation
) {
  const resolved = resolveEmptyStateMediaSource(value, convertImageLink);
  return Object.freeze({
    ...resolved,
    generation,
    phase: resolved.source ? "loading" : "fallback",
  });
}

/**
 * Settle only the current loading generation so events from a replaced image
 * cannot reveal stale content.
 *
 * @param {{ value: string, source: string, generation: number, phase: string }} projection
 * @param {number} generation
 * @param {"loaded" | "failed"} outcome
 */
export function settleEmptyStateMediaProjection(
  projection,
  generation,
  outcome
) {
  if (
    projection.generation !== generation ||
    projection.phase !== "loading" ||
    (outcome !== "loaded" && outcome !== "failed")
  ) {
    return projection;
  }
  return Object.freeze({ ...projection, phase: outcome });
}
