# Entity set tool — `analyze_entity_set` (agent built-in)

**Contract bundle:** [`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md) (**`0.1.145`**).  
**Scope:** Normative **success and error JSON shells** for **`analyze_entity_set`** with **`operation`** one of **`difference`**, **`intersection`**, **`union`**, or **`symmetric_difference`**. Unknown **`operation`** strings MUST yield **`UNSUPPORTED_OPERATION`**. This tool produces a **new conversation `cacheId`** on every success (including empty and INLINE-sized results) so follow-up **`tabulate_cached_result`** / **`fetch_cached_result`** always have a stable handle. **Charting** is expected **via `tabulate_cached_result` → `build_chart_from_tabular_result`**, not by extending chart round-hooks for raw entity-set JSON (see **`docs/agent/entity-set-analysis.md`**).

**Implementation reference:** [`docs/agent/entity-set-analysis.md`](../docs/agent/entity-set-analysis.md), [`docs/agent/cached_tabular_tools.md`](../docs/agent/cached_tabular_tools.md) (paging bounds).

All JSON is UTF-8.

---

## 1. Error payload

When the tool returns an error string to the LLM pipeline, it MUST be a JSON object with:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`error`**. |
| `code` | string | yes | Machine code, including at least: **`INVALID_PARAMETERS`**, **`UNSUPPORTED_OPERATION`**, **`CACHE_MISS`**, **`LIMIT_OUT_OF_RANGE`**, **`TABLE_TOO_LARGE_FOR_TRANSFORM`**, **`ENTITY_SET_KEY_UNUSABLE`**, **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`**, **`PROTECTED_TABULAR_COLUMN_BLOCKED`** (same string as tabular tools), **`ENTITY_SET_ERROR`**. |
| `message` | string | yes | Human-readable detail. |

### 1.1 Root argument keys (fail-closed)

The tool arguments object MUST contain **only** the keys defined in **`AnalyzeEntitySetToolSchema`** / **`docs/agent/entity-set-analysis.md`**: **`operation`**, **`left`**, **`right`**, optional **`projectColumns`**, **`maxItems`**, **`offset`**. Any other top-level key MUST yield **`INVALID_PARAMETERS`** with a message that names the unexpected key (so hallucinated query specs, **`groupBy`**, **`sort`**, **`filters`**, etc. cannot be silently ignored).

### 1.1a Advertised `operation` JSON-Schema enum (B18)

The model-visible parameters schema for **`analyze_entity_set`** MUST declare **`operation`** as a
JSON-Schema **`type: "string"`** with an **`enum`** whose members are exactly, in this order:

1. **`difference`**
2. **`intersection`**
3. **`union`**
4. **`symmetric_difference`**

The executor acceptance set MUST be identical to that enum (bidirectional parity). Unknown
**`operation`** values remain **`UNSUPPORTED_OPERATION`** (including replay/executor-only calls that
bypass provider-side enum enforcement). This clause does **not** change success/error shells or
**`resultKind`** packaging; **`TABULAR_INSIGHT.md`** is unaffected.

### 1.2 `ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS` (**`difference`** / **`intersection`**)

For **`difference`** and **`intersection`**, success rows are projected **only from the left** operand. **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`** applies when the **left** operand carries multiple rows for the same key and **projected** non-key column values disagree across those duplicate rows. Duplicate keys on the **right** operand (including differing non-key values across duplicate rows) MUST NOT produce this error for **`difference`** / **`intersection`**: the right operand is **membership-only** for those operations.

### 1.3 **`union`** / **`symmetric_difference`**

- **`projectColumns`:** when supplied, each entry MUST name a column that exists on **at least one** operand. Output column order follows the **`projectColumns`** array. When omitted, defaults to the **intersection** of safe scalar column names on both operands (see **`docs/agent/entity-set-analysis.md`**), with the left **`keyColumn`** listed first when present in that intersection.
- **`union`:** output contains one row per distinct key in the **set union** of operand keys. Output row order follows keys sorted **lexicographically** by the canonical key string (Unicode **`String.compareTo` order**), so the ordering comparator is a **total order** with **`compare(a,b) == 0`** iff **`a.equals(b)`** — distinct canonical strings (e.g. STRING **`"1"`** vs **`"01"`**) remain distinct rows. This lexical order is **not** a numeric sort of digit strings. For keys present on **one** operand only, cells for columns missing on that side are omitted / **`null`** in row JSON. **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`** applies when duplicate rows on the **left** (resp. **right**) disagree on any projected column that exists on the **left** (resp. **right**). For keys present on **both** operands, any projected column that exists on **both** shapes MUST have **agreeing** non-null values after the tool’s cell normalization, or the tool MUST return **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`**.
- **`symmetric_difference`:** output contains one row per key in the **symmetric difference** of operand keys (keys in exactly one operand), ordered by the same **lexical canonical-key string** rule as **`union`**. Keys present in **both** operands MUST NOT appear in the output and MUST NOT trigger cross-operand value comparison. Per-operand duplicate-key ambiguity rules are the same **`union`**-style checks on the **left** and **right** for projected columns that exist on that operand.

---

## 2. Success — common fields

On success, the root object MUST include:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`success`**. |
| `operation` | string | yes | Echoed operation (**`difference`**, **`intersection`**, **`union`**, or **`symmetric_difference`**). |
| `resultKind` | string | yes | One of **`ENTITY_SET_EMPTY`**, **`ENTITY_SET_INLINE`**, **`ENTITY_SET_LARGE`**. |
| `cacheId` | string | yes | **New** transformed table id (full result stored under this id), even when `resultKind` is **`ENTITY_SET_INLINE`** or **`ENTITY_SET_EMPTY`**. |
| `matchedKeys` | number | yes | Count of keys (output rows) in the set result — **`difference`**: in **left** and not in **right**; **`intersection`**: in **both**; **`union`**: in **either**; **`symmetric_difference`**: in **exactly one** operand. |
| `totalRows` | number | yes | Output row count (same as **`matchedKeys`** when each output key maps to one row). |
| `inputsFullyScanned` | boolean | yes | Literal **`true`** on success — both operand caches were scanned in full for this call. |
| `left` / `right` | object | yes | Operand summaries: **`cacheId`**, **`keyColumn`**, **`rowCount`**, **`uniqueKeys`**, **`duplicateKeyRows`**, optional **`label`**. |
| `columns` | array | yes | Column metadata `{ "name", "baseType" }[]` for the output table. |
| `hint` | string | yes | Short follow-up guidance (typically points at **`tabulate_cached_result`** / **`fetch_cached_result`**). |

### 2.1 `answerSetComplete`, `sampleOnly`, `rowsOmitted`, `returnedRows`

These fields reuse **tabulate inline completeness semantics** (query-spec §11): **`answerSetComplete: true`** only when the **`rows`** array contains **all** output rows starting at offset **0** with no omission. LARGE samples set **`sampleOnly: true`**, **`rowsOmitted: true`**, **`answerSetComplete: false`**. **`inputsFullyScanned`** is independent and always **`true`** on success.

### 2.2 Rows vs samples

| `resultKind` | Rows in payload |
|--------------|-----------------|
| `ENTITY_SET_EMPTY` | Empty **`rows`** array. |
| `ENTITY_SET_INLINE` | **`rows`** — slice of **`[offset, offset + maxItems)`** (defaults **`maxItems=50`**, **`offset=0`**, max **`500`**). |
| `ENTITY_SET_LARGE` | **`sampleRows`** — same slice cap, bounded also by the shared INLINE row threshold (**`20`** today, tied to **`InvokeServiceExecutor.largeTableRowThreshold()`**). |

**Charting / `last_invoke`:** Success JSON carries **`cacheId`** for paging and for **`tabulate_cached_result`**. The agent **MUST NOT** treat **`analyze_entity_set`** as a per-turn qualifying tool for **`build_chart_from_tabular_result(source: "last_invoke")`** — that path is reserved for invoke/query/tabulate-style tabular successes. The **conversation** last-qualifying mirror (**`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`**) **is** updated on success so **`tabulate_cached_result`** can target the new table via the sentinel after **`analyze_entity_set`**.

### 2.3 `insightEnvelope`

Successful **`analyze_entity_set`** payloads **MUST NOT** include **`insightEnvelope`** in v1; downstream **`tabulate_cached_result`** supplies it when charts need Further Insight envelopes.

---

## Changelog (this file)

| Revision | Notes |
|----------|-------|
| 1.0.7 | §**1.1a** — advertised **`operation`** MUST be a JSON-Schema string **`enum`** of the four set ops (B18); executor/schema bidirectional parity; no **`TABULAR_INSIGHT`** change. |
| 1.0.6 | §**1.3** — **`union`** / **`symmetric_difference`** output key order: **lexical** sort on canonical key string (total order for distinct keys). |
| 1.0.5 | **`union`** / **`symmetric_difference`**; **`projectColumns`** on merge ops; §**1.3** duplicate and overlap rules; **`matchedKeys`** semantics. |
| 1.0.4 | §**1.2** — **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`** for **`difference`** / **`intersection`**: **left** operand only; **right** duplicate rows (membership-only) MUST NOT trigger this code. |
| 1.0.3 | **`operation`**: **`difference`** or **`intersection`**; **`matchedKeys`** / **`totalRows`** semantics for **`intersection`**; **`union`** / **`symmetric_difference`** remain **`UNSUPPORTED_OPERATION`**. |
| 1.0.2 | Root-level **allowlist** for tool arguments — any key outside **`operation` / `left` / `right` / `projectColumns` / `maxItems` / `offset`** → **`INVALID_PARAMETERS`**. |
| 1.0.1 | Clarify **`last_invoke`** is not driven by **`analyze_entity_set`** alone; conversation mirror for sentinel **`tabulate_cached_result`** remains. |
| 1.0.0 | Initial normative registration for Phase A **`difference`**; bundle **`0.1.96`**. |
