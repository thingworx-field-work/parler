# Tool result completeness

**Revision 1.1.1.** Normative for `parler-agent`: how a tool result tells the model that it was reduced before the
model read it, and which copy of a tool result each statement is about. Feature name:
`tool-result-completeness/1`; the budget rules of §7 are `tool-result-budget/1` and `tool-result-table-recovery/1`
(all in `GetAgentRuntimeSnapshot.features`).

## 1. The four places a tool result lives

| Place | What it holds |
| --- | --- |
| **First model append** | The tool message the agent loop appends toward the model after a tool ran (or after a person approved a gated tool). This contract is about this copy. |
| **Later replay** | The same message on later model rounds; the replay compactor may reduce it again. Not covered here. |
| **Stream persistence** | The `AgentMessageStream` tool row. It starts identical to the first model append (so it carries the same note) and may then gain a CSV export sidecar, a chart block or a `$format` stamp. It never holds a full body the first append did not hold. |
| **UI downlink** | The table/chart lane of the widget. It may hold the full body the gateway kept aside; it is not what the model read. |

A statement about "what the model saw" refers to the first model append.

## 2. Reduction signs

A **reduction** is anything that makes the model see less than the tool returned. It is read **at the root of the
result envelope** only. Any one of these signs means the result was reduced:

- `truncated: true`
- `sampleOnly: true`
- `rowsOmitted: true`
- `resultKind` equal to `INFOTABLE_LARGE` or `RESULT_OVERFLOW`
- `readLimitReached: true`
- a `warnings[]` entry whose `code` is `CHUNK_MARKDOWN_TRUNCATED`
- `_egress.reducedFields` or `_egress.rawChars` present

Not a reduction:

- `_egress` alone: it may carry only `_egress.dataClassification`.
- A field named `truncated` (or any other sign name) nested inside a tool's own business object, for example
  `result.truncated`. Only the envelope root is the transport contract.
- A `cacheId`. It is a handle to a stored full result; extended tools carry one on every small table.

A **gateway reduction** is one the egress gateway made (text cut, arrays sampled, fields omitted). A **producer
reduction** is one the tool or its formatter already made (a large table's 20-row sample, a page cut at the configured
maximum, a read that hit its row limit). The model cannot tell them apart, so both get the note.

## 3. The completeness note

Whenever the envelope root shows a reduction, the first model append carries a root text field
**`completenessNote`**, at most 1,200 characters. It is kept through every reduction pass. It is added to a producer
reduction even when the gateway changes nothing else, and an annotated result replaces the raw one even when it is
longer. Error envelopes (`status: "error"` or a root `error` key) do not carry the note.

Variants (text begins as shown; each ends with the recovery clause of §4):

| Variant | When | Text begins |
| --- | --- | --- |
| Gateway cut | the gateway reduced the result | "This result was reduced before you saw it (see truncated, rowsOmitted, _egress.reducedFields). Say in your answer which values or rows did not arrive, and say the same in any file you save. Do not present it as complete." |
| Table sample | a table-shaped root (`columns` and `rows` or `sampleRows`) whose total is known shows a row reduction | "This result is a sample of N of M rows." (N rows present, M the `totalRows` or `rowCount`) |
| Page | a `warnings[]` entry `CHUNK_MARKDOWN_TRUNCATED` | "This page was cut at the configured maximum." |
| Producer partial | any other producer sign, such as `readLimitReached` | "This result is partial: the tool returned only part of the data." |
| Overflow | `resultKind: "RESULT_OVERFLOW"` | "This result was too large to show; only its shape is given." |

## 4. Recovery clause

The note never points at a route that does not exist:

- With a `cacheId` on a table-shaped result: ask the tool for less, or read the cached result with
  `tabulate_cached_result` (`mode: "filter_sort_topn"`, a `sorts` entry and `fields`; every column named, for up to
  `maxItems` rows, default 50 and at most 500, paged with `offset`), listed **before** `fetch_cached_result`, whose
  pages over 4,096 characters of row text are themselves reduced to a sample. It does not promise all rows.
- With a `cacheId` on any other result: ask the tool for less, or inspect the stored copy with `inspect_cached_payload`.
- Without a `cacheId`: ask the tool for less (fewer fields, fewer rows or a narrower request); the cache is not
  mentioned.
- For a document page: search for the missing passage or read a neighbouring chunk.

## 5. What an answer keeps apart

An answer built from a reduced result keeps three states apart and does not merge them:

1. **Evidence the model did not see**: rows, fields or text left out by a reduction. The answer says they did not
   arrive; it does not describe them.
2. **A value the source does not have**: an empty or null value that did arrive.
3. **A value recovered by a later read**: a cached-result read or a narrower tool call that returned it.

The routing guide and the final-answer evidence rule repeat the instruction in one sentence; that sentence is not
applied when an external system prompt replaces the built-in guide, so the note in the result is the guidance that
always travels with the evidence. Neither makes a model comply.

## 6. Approved (HITL) results

A tool a person approved executes in the approve continuation, not in the agent loop. Its result is presented once,
before it is appended to the in-memory history and the message stream: it passes through the same egress as an ordinary
result (§7) under the budget captured when the approved execution started, so it gets the same reductions, overflow
response and note; a result that fits arrives whole, and one over the soft cap is reduced where before it arrived
whole. The chart handle, the evidence seed, the completed gated result and the chart and task hooks receive the raw
result. Cancel, reject, expiry, unsupported-gate and interrupted-sibling rows are synthetic and unchanged.

## 7. Budget and ceiling

- **Budget.** The effective soft cap (`AgentSettings.toolResultSoftCapChars`, 8,192 to 65,536, default 8,192) and the
  effective text cap (`toolResultTextCapChars`, 500 to 50,000 and at most the soft cap minus 1,024, default 2,000; an
  extended tool may declare its own `resultTextCapChars`) are resolved once when a call starts executing and used by the
  formatter and the egress gateway. The soft cap is never raised for a tool or a page.
- **Entry.** Sizes are serialized UTF-16 code units, escaping, markers, the note and the classification stamp included.
  Arrays longer than 20 items are sampled first (schema arrays, bounded read-limit series and the multi-Thing
  alert-summary fields excepted). A root that is itself an array and was sampled is wrapped as
  `{status, resultKind: "LARGE_JSON_COMPACT", sample}` with its root markers (`_egress.reducedFields.$root`) and the
  note, never returned as a bare shorter array. A result that then fits the soft cap arrives with its text uncut, byte
  for byte when nothing was sampled, annotated or stamped. Plain (non-JSON) text over the soft cap becomes
  `LARGE_TEXT_COMPACT` with its excerpt shortened until the envelope, classification stamp included, fits.
- **Tables.** A root with `columns` and `rows` or `sampleRows` is reduced only by dropping whole rows: rows are kept
  in source order while the envelope fits, possibly none; a cell is never cut, array-valued and nested cells included
  (only the outer rows array is limited to 20), and there is no partial row. It states
  `returnedRows`, the total (`totalRows` or `rowCount`) and `rowsOmitted: true` when any row is left out. A formatter
  table whose size plus 1,536 exceeds the soft cap gets a `cacheId` before reduction on both invocation paths; when rows
  are left out and no `cacheId` exists the result carries `recovery: "unavailable"`.
- **Other results.** Text is cut at the text cap with arrays of 20, then at 512 with arrays of 5, then by the
  last-resort pass, while still over the soft cap.
- **Overflow response.** What still does not fit becomes `{status: "success", resultKind: "RESULT_OVERFLOW", rawChars,
  cacheId | recovery: "unavailable", completenessNote, hint}` plus the classification stamp and any read-limit evidence,
  then `columnNames` (a table) or `fieldNames` (the root's field names) added one at a time, in source order, while the
  envelope fits, with `columnNamesOmitted` / `fieldNamesOmitted` when some are left out. When no name fits, the hint is
  dropped and the read-limit evidence is kept as far as it fits (all of it, then `readLimitReached` with
  `readLimitNote`, then `readLimitReached` alone).
- **Ceiling.** The first model append is at most the soft cap, with two exceptions: an error envelope (`status:
  "error"` or a root `error` key), whose entry (over 8,192 characters or containing `[`) and single pass (arrays 5,
  text 512) are unchanged; and a result whose minimal overflow envelope (the fixed fields, the note and the retained
  author-supplied `dataClassification` stamp, with no names and no hint) exceeds the cap, which is returned with
  `_egress.reducedFields.overBudget: true` only when that envelope was measured above the cap (it keeps
  `readLimitReached` when the source read was limited). The classification is preserved, not bounded.
- **Upgrade consequence.** A result that no pass can bring under the soft cap, and that earlier agents delivered above
  it, now arrives as the overflow response at the default cap. Example: a worst-case multi-Thing alert summary for 25
  Things (about 13,500 characters; `byThing` is never sampled). An installation that needs such a result whole raises
  `toolResultSoftCapChars` (at 16,384 it arrives whole).

## Changelog

| Revision | Notes |
| --- | --- |
| 1.1.1 | Topic round 6, review-3 corrections to §7: a table's cells are never cut, array-valued and nested cells included; a sampled root array is wrapped with markers and the note; classified plain text is measured with its stamp; the overflow response keeps read-limit evidence when no name fits and sets `overBudget` only when measured; the alert-summary upgrade consequence is stated. |
| 1.1.0 | Topic round 6, slice S2: §7 budget and ceiling (settings and per-tool text cap resolved once per call, entry from serialized size after sampling, whole-row table reduction with `recovery: "unavailable"`, the budgeted `RESULT_OVERFLOW` response, the two exceptions to the ceiling); §6 approved results pass through the same egress under the captured budget. |
| 1.0.0 | First revision (topic round 6, slice S1): reduction signs at the envelope root, the `completenessNote` variants, the recovery clause, the four places, and the approved-result presentation. |
