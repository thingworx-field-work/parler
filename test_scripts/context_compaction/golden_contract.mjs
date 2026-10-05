/**
 * Contract-derived allowlists for golden reference payloads.
 * @see CONTRACTS/CHART_CONTRACT.md §3, §3.0a
 * @see CONTRACTS/TABLE_CONTRACT.md §3
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "../..");

/** Wire payload keys permitted in golden chart references. */
export const CHART_REFERENCE_KEYS = new Set(["kind", "source", "series"]);
export const CHART_SOURCE_KEYS = new Set([
  "sourceResolved",
  "sourceCacheId",
  "sourceToolCallId",
  "sourceResultKind",
  "sourceColumns",
  "transformSummary",
  "rowCount",
  "pointCount",
  "truncationApplied",
  "filledMissingCombinations",
  "zeroValueCategoryCount",
  "missingPeriods",
]);
export const CHART_SERIES_ITEM_KEYS = new Set(["name", "x", "y", "sourceWindow"]);

/** Wire payload keys permitted in golden table references. */
export const TABLE_REFERENCE_KEYS = new Set(["rows", "rowOrder"]);
export const TABLE_ROW_ORDER_KEYS = new Set(["sortBy", "sortOrder"]);

/** Task criteria outside wire payloads (not ChartBlock / TableBlock fields). */
export const TASK_REFERENCE_KEYS = new Set([
  "comparison",
  "explanation",
  "alerts",
  "summary",
  "statistics",
]);
export const STATISTICS_REFERENCE_KEYS = new Set(["average", "min", "max"]);

/** Keys that must never appear on golden chart/table wire expectations. */
export const FORBIDDEN_GOLDEN_WIRE_KEYS = new Set([
  "stats",
  "device",
  "utilDate",
  "timezone",
  "sortBy",
  "sortOrder",
  "suppliedJudgment",
]);

/** @param {string} field @param {string} contractText */
export function fieldOccursInContract(field, contractText) {
  return contractText.includes(`\`${field}\``);
}

/** @param {Set<string>} keys @param {string} contractText @param {string} label */
export function collectAllowlistContractViolations(keys, contractText, label) {
  /** @type {string[]} */
  const violations = [];
  for (const key of keys) {
    if (!fieldOccursInContract(key, contractText)) {
      violations.push(`${label}.${key}`);
    }
  }
  return violations;
}

/** Self-check: every allowlisted wire field name appears in the normative contract doc. */
export function collectAllowlistSelfCheckViolations() {
  const chartContract = readFileSync(join(REPO_ROOT, "CONTRACTS/CHART_CONTRACT.md"), "utf8");
  const tableContract = readFileSync(join(REPO_ROOT, "CONTRACTS/TABLE_CONTRACT.md"), "utf8");
  const tableWireKeys = new Set([...TABLE_REFERENCE_KEYS].filter((key) => key !== "rowOrder"));
  /** @type {string[]} */
  const violations = [
    ...collectAllowlistContractViolations(CHART_SOURCE_KEYS, chartContract, "CHART_SOURCE_KEYS"),
    ...collectAllowlistContractViolations(CHART_SERIES_ITEM_KEYS, chartContract, "CHART_SERIES_ITEM_KEYS"),
    ...collectAllowlistContractViolations(CHART_REFERENCE_KEYS, chartContract, "CHART_REFERENCE_KEYS"),
    ...collectAllowlistContractViolations(tableWireKeys, tableContract, "TABLE_WIRE_KEYS"),
  ];
  for (const key of FORBIDDEN_GOLDEN_WIRE_KEYS) {
    if (fieldOccursInContract(key, chartContract) || fieldOccursInContract(key, tableContract)) {
      violations.push(`FORBIDDEN_GOLDEN_WIRE_KEYS.${key}`);
    }
  }
  return violations;
}

/** @param {unknown} golden */
export function collectGoldenContractViolations(golden) {
  /** @type {string[]} */
  const violations = [];
  const turns = golden?.turns;
  if (!turns || typeof turns !== "object") return violations;

  for (const [turnId, turnGolden] of Object.entries(turns)) {
    const reference = turnGolden?.reference;
    if (!reference || typeof reference !== "object") continue;

    const chart = reference.chart;
    if (chart && typeof chart === "object") {
      for (const key of Object.keys(chart)) {
        if (FORBIDDEN_GOLDEN_WIRE_KEYS.has(key)) {
          violations.push(`turn ${turnId} chart.${key}`);
        } else if (!CHART_REFERENCE_KEYS.has(key)) {
          violations.push(`turn ${turnId} chart.${key}`);
        }
      }
      const source = chart.source;
      if (source && typeof source === "object") {
        for (const key of Object.keys(source)) {
          if (FORBIDDEN_GOLDEN_WIRE_KEYS.has(key) || !CHART_SOURCE_KEYS.has(key)) {
            violations.push(`turn ${turnId} chart.source.${key}`);
          }
        }
      }
      if (Array.isArray(chart.series)) {
        for (let i = 0; i < chart.series.length; i += 1) {
          const series = chart.series[i];
          if (!series || typeof series !== "object") continue;
          if (chart.kind === "pie" && Object.prototype.hasOwnProperty.call(series, "sourceWindow")) {
            violations.push(`turn ${turnId} chart.series[${i}].sourceWindow`);
          }
          for (const key of Object.keys(series)) {
            if (FORBIDDEN_GOLDEN_WIRE_KEYS.has(key) || !CHART_SERIES_ITEM_KEYS.has(key)) {
              violations.push(`turn ${turnId} chart.series[${i}].${key}`);
            }
          }
        }
      }
    }

    const table = reference.table;
    if (table && typeof table === "object") {
      for (const key of Object.keys(table)) {
        if (FORBIDDEN_GOLDEN_WIRE_KEYS.has(key)) {
          violations.push(`turn ${turnId} table.${key}`);
        } else if (!TABLE_REFERENCE_KEYS.has(key)) {
          violations.push(`turn ${turnId} table.${key}`);
        }
      }
      const rowOrder = table.rowOrder;
      if (rowOrder && typeof rowOrder === "object") {
        for (const key of Object.keys(rowOrder)) {
          if (!TABLE_ROW_ORDER_KEYS.has(key)) {
            violations.push(`turn ${turnId} table.rowOrder.${key}`);
          }
        }
      }
    }

    const statistics = reference.statistics;
    if (statistics && typeof statistics === "object") {
      for (const key of Object.keys(statistics)) {
        if (!STATISTICS_REFERENCE_KEYS.has(key)) {
          violations.push(`turn ${turnId} statistics.${key}`);
        }
      }
    }
  }
  return violations;
}

/** @param {unknown} golden */
export function assertGoldenMatchesContract(golden) {
  const selfCheck = collectAllowlistSelfCheckViolations();
  if (selfCheck.length > 0) {
    throw new Error(`golden allowlist is not contract-backed: ${selfCheck.join(", ")}`);
  }
  const violations = collectGoldenContractViolations(golden);
  if (violations.length > 0) {
    throw new Error(`golden reference uses non-contract wire keys: ${violations.join(", ")}`);
  }
}

/** @param {unknown} golden */
export function assertForbiddenGoldenWireKeysAbsent(golden) {
  const violations = collectGoldenContractViolations(golden);
  const forbidden = violations.filter((item) => FORBIDDEN_GOLDEN_WIRE_KEYS.has(item.split(".").pop() ?? ""));
  if (forbidden.length > 0) {
    throw new Error(`golden reference uses forbidden wire keys: ${forbidden.join(", ")}`);
  }
}
