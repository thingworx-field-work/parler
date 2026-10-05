# Tabular `insightEnvelope` (agent-side)

**Normative bundle (agent tool JSON):** [`CONTRACTS/TABULAR_INSIGHT.md`](../../CONTRACTS/TABULAR_INSIGHT.md) (**`CONTRACT_VERSION.md`**). **`parler-ui`** streaming wire linkage and the D7 gate: [`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) **§View — `insightEnvelopeLoose` (D7)**.  
**This file:** agent tool **success** JSON only, for `tabulate_cached_result` and `summarize_cached_result`.

## When it appears

**Current server behavior:** on **every success** for these **`resultKind`** values, the root object **includes** an **`insightEnvelope`** object in addition to **`sourceCacheId`**: `CACHED_TABULATE_EMPTY`, `CACHED_TABULATE_INLINE`, `CACHED_TABULATE_LARGE`, `CACHED_SUMMARY_EMPTY`, `CACHED_SUMMARY_INLINE`.

**Client tolerance:** treat `insightEnvelope` as **optional** on the wire — if a future revision omits it, consumers must still rely on root **`sourceCacheId`** for provenance. **`insightEnvelope` must not** replace root `sourceCacheId`.

## Shape (`schemaVersion` = `"1"`)

| Field | Type | Meaning |
|-------|------|--------|
| `schemaVersion` | string | Literal **`"1"`** for this revision. |
| `sourceCacheId` | string | **Same** value as root `sourceCacheId` (input `cacheId` the tool read). |
| `rowEstimate` | number | For **tabulate**: row count of the **result** table (after transform). For **summarize**: row count of the **input** table summarized. |
| `columns` | array | `{ "name", "baseType" }` per column — **tabulate**: output table columns; **summarize**: **input** table columns (declaration order when shape exists). |

## Implementation

`CachedTabularToolsExecutor.attachInsightEnvelope(root, sourceCacheId, envelopeShapeTable)` — constant **`INSIGHT_ENVELOPE_SCHEMA_VERSION`** in code.

## See also

- [`cached_tabular_tools.md`](./cached_tabular_tools.md) — parameters and error codes.  
- [`cached_tabular_golden.md`](./cached_tabular_golden.md) — golden / negative catalog for harness and manual runs.
