# Cached tabular tools — golden & negative catalog

**Purpose:** a catalog of golden-style scenarios for `tabulate_cached_result` / `summarize_cached_result`,
positive and negative, with argument fixtures.
**Execution:** these files are **spec + argument fixtures**. JVM tests that construct `com.thingworx.types.InfoTable` are **not** reliable on the stock JUnit classpath (ThingWorx static initialization), so the full round trip is checked in a Composer / agent session with a real cached table, not by the standard offline test suite.

**Multi-turn `cacheId` platform check (Composer):** [`cached_tabular_g4_harness.md`](./cached_tabular_g4_harness.md). **Last-cache `TOKEN`:** [`p2_last_tabular_cache.md`](./p2_last_tabular_cache.md) (wired in **`CachedTabularToolsExecutor`**; offline mirror behavior is covered by **`AgentToolContextLastQualifyingTabularCacheTest`**, see the mirror row in **§Repo baseline**).

**Fixture directory:** [`golden_cached_tabular/`](./golden_cached_tabular/) — tool **arguments** (and small **`expect`** fragments where useful).

**Repo baseline (counts only):** from the repository root, **`node scripts/report-cached-tabular-baseline.mjs`** verifies that **`Count (current):`** matches the **`| G-xx |` / `| N-xx |`** table rows and that the on-disk **`*.json`** count matches the **Fixture files** line below; see **§Repo baseline** (not a platform pass rate). The mirror test row is checked with **`node scripts/check-p2-mirror-junit-count.mjs`**.

**Count (current):** **15** scenarios — **8** positive (**G-01**–**G-08**), **7** negative (**N-01**–**N-07**).

**Fixture files:** **14** JSON files under [`golden_cached_tabular/`](./golden_cached_tabular/) — **G-04** has **no** file (manual LARGE setup only); **N-06** / **N-07** document **`rawArguments`** for harness-injected payloads where the file body is not the literal `arguments` string.

---

## Platform pass-rate

The platform metric is the multi-turn explicit `cacheId` carry rate: how often the model passes the exact
`cacheId` of an earlier tabular result in a later turn of the same conversation. The planning threshold is
**70%**. It can only be measured against a real ThingWorx session cache with tool calls, using the fixtures
below and the procedure in [`cached_tabular_g4_harness.md`](./cached_tabular_g4_harness.md). No measured value is
recorded in this repository; never enter an unmeasured percentage.

---

## Golden catalog

### Positive (representative)

| ID | Scenario | Tool | Fixture |
|----|----------|------|---------|
| G-01 | `sort_topn` desc, tie-break on original row index | `tabulate_cached_result` | [`sort_topn_tie_args.json`](./golden_cached_tabular/sort_topn_tie_args.json) |
| G-02 | `group_count` two keys | `tabulate_cached_result` | [`group_count_basic_args.json`](./golden_cached_tabular/group_count_basic_args.json) |
| G-03 | `summarize_cached_result` with explicit `percentileColumns` | `summarize_cached_result` | [`summarize_percentile_explicit_args.json`](./golden_cached_tabular/summarize_percentile_explicit_args.json) |
| G-04 | `CACHED_TABULATE_LARGE` when output rows &gt; `LARGE_TABLE_ROW_THRESHOLD` | `tabulate_cached_result` | Build a cache with &gt;20 rows, `sort_topn` returning same row count; expect `resultKind`, `cacheId`, `sampleRows`, **`insightEnvelope`**. |
| G-05 | `group_aggregate` `sum` | `tabulate_cached_result` | [`group_aggregate_sum_args.json`](./golden_cached_tabular/group_aggregate_sum_args.json) |
| G-06 | `sort_topn` with explicit `direction: asc` | `tabulate_cached_result` | [`sort_topn_asc_args.json`](./golden_cached_tabular/sort_topn_asc_args.json) |
| G-07 | `CACHED_SUMMARY_EMPTY` (zero-row cached table) | `summarize_cached_result` | [`summarize_empty_table_args.json`](./golden_cached_tabular/summarize_empty_table_args.json) |
| G-08 | `CACHED_TABULATE_EMPTY` (zero-row cached table, `sort_topn`) | `tabulate_cached_result` | [`tabulate_empty_sort_args.json`](./golden_cached_tabular/tabulate_empty_sort_args.json) |

### Negative

| ID | Expected `code` | Fixture / notes |
|----|-------------------|-----------------|
| N-01 | `CACHE_MISS` | Unknown `cacheId` — [`cache_miss_args.json`](./golden_cached_tabular/cache_miss_args.json) |
| N-02 | `INVALID_PARAMETERS` | `percentileColumns` not an array — [`summarize_percentile_not_array_args.json`](./golden_cached_tabular/summarize_percentile_not_array_args.json) |
| N-03 | `INVALID_PARAMETERS` | `percentileColumns` element not string — [`summarize_percentile_non_string_args.json`](./golden_cached_tabular/summarize_percentile_non_string_args.json) |
| N-04 | `MISSING_CACHE_ID` | Empty args `{}` — [`missing_cache_id_summarize_args.json`](./golden_cached_tabular/missing_cache_id_summarize_args.json) |
| N-05 | `UNKNOWN_MODE` | Invalid `mode` — [`unknown_mode_args.json`](./golden_cached_tabular/unknown_mode_args.json) |
| N-06 | `INVALID_PARAMETERS` | Root `arguments` not valid JSON — [`malformed_root_args.json`](./golden_cached_tabular/malformed_root_args.json) (`rawArguments` for harness). |
| N-07 | `INVALID_PARAMETERS` | Root `arguments` valid JSON but **not a JSON object** (e.g. array) — [`non_object_root_args.json`](./golden_cached_tabular/non_object_root_args.json) (`rawArguments` for harness). |

## Success invariants (all positives)

- Root **`status`** = `"success"`.
- Root **`sourceCacheId`** present (input `cacheId`).
- Root **`insightEnvelope`** present (the current implementation includes it on every success for the `resultKind` set below). `schemaVersion` **`"1"`**, matching `sourceCacheId`, and `columns` / `rowEstimate` per [`tabular_insight_envelope.md`](./tabular_insight_envelope.md). Clients **should tolerate** a future server omitting `insightEnvelope` if the contract is relaxed.

**`resultKind` values that include `insightEnvelope`:** `CACHED_TABULATE_EMPTY`, `CACHED_TABULATE_INLINE`, `CACHED_TABULATE_LARGE`, `CACHED_SUMMARY_EMPTY`, `CACHED_SUMMARY_INLINE`.

---

## Repo baseline (counts only)

Repository-local checks. They do not replace a platform pass rate (§Platform pass-rate).

| Item | Value |
|----|----------------|
| Golden scenarios in this catalog | **15** (**8** positive **G-01**–**G-08**, **7** negative **N-01**–**N-07**) |
| `golden_cached_tabular/*.json` files | **14** (matches the catalog; **G-04** has no fixture file) |
| JSON syntax guard | From the repository root, **`node scripts/check-golden-cached-tabular-json.mjs`** → prints **`OK 14 JSON file(s) under docs/agent/golden_cached_tabular/`** |
| Baseline summary | **`node scripts/report-cached-tabular-baseline.mjs`** (cross-checks **`Count (current):`** against the **`| G-xx |` / `| N-xx |`** rows and **`Fixture files:`** against the **\*.json** count) |
| Last-cache mirror offline tests (**not** a full golden round trip) | **`AgentToolContextLastQualifyingTabularCacheTest`**: **13** **`@Test`** methods (including invalid JSON for **`noteTokenMirrorFromSummarizeCachedResultJson`**); `cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true --tests 'com.thingworx.things.agent.tools.AgentToolContextLastQualifyingTabularCacheTest'`; **`node scripts/check-p2-mirror-junit-count.mjs`** cross-checks this number against the **`@Test`** lines in the source |

When tests are added to or removed from **`AgentToolContextLastQualifyingTabularCacheTest`**, update the mirror row. Composer-side checks of the mirror (`CACHE_MISS` pruning and summarize → `TOKEN`) are in **`cached_tabular_g4_harness.md` §5**.
