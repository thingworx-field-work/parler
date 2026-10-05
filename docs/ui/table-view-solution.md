# Chat tables: structured `table` wire, CSV export and history

**Use case:** [`docs/ui/table-view.md`](table-view.md).
**Normative wire shape:** [`CONTRACTS/TABLE_CONTRACT.md`](../../CONTRACTS/TABLE_CONTRACT.md) (with
[`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) and
[`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md)).
**Related:** [`docs/ui/table-title.md`](table-title.md), [`docs/ui/turn-actions-and-table-export.md`](turn-actions-and-table-export.md).

This document describes how Parler shows list-style results as compact tables in the chat bubble, how large
tables are exported to a FileRepository as CSV, and how tables are rebuilt from conversation history.

---

## 1. Principles

| Principle | Meaning |
|------|------|
| **List rows come from tools** | Rows of a list-class table always come from the structured result of a ThingWorx tabular tool (`InfoTable`, `rootEntityList`, `sampleRows`, `cacheId`, as each tool's success JSON defines). The assistant never carries the same factual rows as Markdown. Columns are whatever the tool returns; for taxonomy queries they are projected by `CriticalProperties` / `AdditionalProperties` (see [`docs/agent/AGENT-TAXONOMY.md`](../agent/AGENT-TAXONOMY.md)). |
| **Cached transforms are not column projectors** | `tabulate_cached_result` sorts, filters, groups and aggregates a cached table (see [`docs/agent/cached_tabular_tools.md`](../agent/cached_tabular_tools.md)); column selection for list results comes from the query tools. |
| **Non-factual Markdown tables are allowed** | Illustrations, tutorials and hypothetical comparisons may use GFM pipe tables in the assistant text. They are styled like structured tables (§9.1) but are never treated as data. |
| **No dual source** | In one turn, the same factual rows are not sent both as a `type: "table"` frame and as a Markdown table (`TABLE_CONTRACT.md` §5). The assistant text keeps a short lead-in sentence. |

---

## 2. Architecture: a separate `table` frame

- The downlink frame **`type: "table"`** mirrors **`type: "chart"`**: it carries the display payload (column
  definitions, rows, export pointers).
- **`tabular.tool_success`** / **`insightEnvelope`** remain the metadata channel (`resultKind`,
  `sourceCacheId`, `rowEstimate`, `columns`) and never carry full table rows.
- In `parler-ui`, the `insightEnvelopeLoose` D7 rule (presence only; no deep reads of sub-fields to drive UI)
  is unchanged. Tables arrive as `assistant.table` → `tables[]` on the assistant row, next to `charts[]`.
- Frame order for one qualifying tabular success: display frames (`chart`, `table`) first, then
  `tabular.tool_success`, then the `activity` summary line (see `API_CONTRACT.md`).

### 2.1 Where the table is decided and rendered

- **`parler-agent`** decides whether to emit a table: after a tool succeeds and before the wire is written,
  `ParlerToolTableWireUtil` builds a `TableBlock` from the tool's success JSON and `AgentThing` emits it.
  A table needs at least one column (otherwise no frame is sent), and each row is filtered to the declared
  column keys. When no table is sent, the assistant may still explain in text, but never invents rows.
- **`parler-ui`** renders a factual table only from a `table` frame; it never infers a table body from
  Markdown. The reducer maps `assistant.table` to `tables[]`; the row renderer draws them next to Markdown
  and charts. Chart and table may share a bubble; their vertical order is fixed in the client and documented
  in `UI_CLIENT_PROTOCOL.md`.

### 2.2 Tools that produce a `table` frame

| Tool | Table source |
|---|---|
| `query_entities_by_taxonomy` | INLINE / LARGE (with sample rows) results |
| `list_entities_by_type` | INLINE / LARGE (with sample rows) results |
| `query_entities` | INLINE / LARGE (with sample rows) results |
| `invoke_service` | `INFOTABLE` / `INFOTABLE_LARGE` results |
| `fetch_cached_result` | Page results (`offset`, `returnedRows`, `hasMore`, `totalRows`, `cacheId`, `rows`, and `columns[]` with `baseType`, written by `InvokeServiceExecutor.doFetchCached` from the cached `InfoTable`) |
| `tabulate_cached_result` | Successful transform results (`CACHED_TABULATE_*` projection) |
| `analyze_entity_set` | Successful entity-set results |

Empty results such as `ENTITY_*_EMPTY` produce no table (no columns).

### 2.3 Avoiding redundant offers

The model cannot see what the client has rendered. The routing guide therefore tells it to treat emitted
charts as delivered: do not offer to create them again, do not claim they are still being drawn, and do not
refer to them by screen position.

---

## 3. Validation

| Path | Check |
|------|------|
| List / structured | Tool JSON schema and platform error codes (for example `CACHE_MISS`, `TABLE_TOO_LARGE_FOR_TRANSFORM`). Rows come from the `InfoTable` or the tool's defined structure, never from the model. |
| Before a `table` frame | At least one column; rows filtered to declared columns (§2.1). |
| Non-factual Markdown | `MarkdownIt({ html: false })` blocks raw HTML; pipe tables are rendered by markdown-it (§9); no fact check. |

---

## 4. Wire shape

`CONTRACTS/TABLE_CONTRACT.md` is normative. Example:

```json
{
  "type": "table",
  "request_id": "<uuid>",
  "table": {
    "kind": "entity-list",
    "columns": [
      { "key": "name", "label": "name", "baseType": "STRING" },
      { "key": "PTCDisplayName", "label": "display name", "baseType": "STRING" },
      { "key": "PTCSerialNumber", "label": "SN", "baseType": "STRING" }
    ],
    "rows": [ { "name": "…", "PTCDisplayName": "…", "PTCSerialNumber": "…" } ],
    "shownRows": 8,
    "totalRows": 8,
    "sourceCacheId": "<uuid>",
    "cacheId": null,
    "exportRepository": "MyFileRepositoryThing",
    "exportFile": "/<user>/<yyyyMMdd>/<timestamp>_<requestId>.csv",
    "exportStatus": "ok",
    "exportMessage": null
  }
}
```

- `key` / `label` / `baseType`: `label` equals the field name (`key`); a separate optional presentation
  title is described in [`table-title.md`](table-title.md).
- Large results: `rows` may be a sample, `totalRows` is the full count, and `cacheId` points to a cache that
  `fetch_cached_result` can page.
- Export fields: `exportStatus` (`none`, `ok`, `repo_missing`, `repo_error`, `write_error`, `skipped_limit`,
  `path_collision`), `exportMessage` (short human-readable text shown under the table), `exportFile` (path
  inside the repository, as passed to `SaveText`), `exportRepository` (FileRepository Thing name).
  `exportDownloadUrl` is supported by the client and preferred when present; the agent currently leaves it
  `null`, so the client builds the link from `exportRepository` + `exportFile` (§5.5).
- `tabular.tool_success` → `insightEnvelopeLoose` (D7 unchanged); `table` → `tables[]` and rendered.

---

## 5. Row limits and CSV export

Reference implementation: `ParlerTableFileExportHook`, called from the `AgentThing` list-class table paths.
All export outcomes are logged (warn level for failures) and reported in the `table` payload through
`exportStatus` / `exportMessage` (and `exportFile` on success). **Export never blocks the conversation or the
table rendering**: rows are sent and drawn according to the normal sampling rules whatever the export result.

### 5.1 Settings

| `AgentSettings` field | Type | Meaning |
|----|----|----|
| `exportFileRepository` | `THINGNAME` (FileRepository) | FileRepository Thing that receives the CSV through `SaveText`. Empty disables export; a table that would otherwise be exported gets `exportStatus: repo_missing`. |
| `tableCsvExportRowThreshold` | integer, default **200** | Export is attempted when `totalRows` exceeds this value. |
| `tableCsvExportMaxChars` | integer, default **50_000_000** (clamped 1_000_000..200_000_000) | Maximum CSV length (UTF-16 code units) before `SaveText`; larger → `skipped_limit`. |

Export is attempted (with a non-empty repository) when `totalRows > tableCsvExportRowThreshold`, **or** the
wire carries a partial sample (`totalRows > rows.length` and `rows.length > 0`). The CSV is built from the full
cached `InfoTable` when the table's `cacheId` resolves; otherwise from inline `rows` if they are complete;
otherwise the export is skipped with `skipped_limit` ("requires a conversation cacheId for the complete
table"). An empty CSV yields `write_error`. A table whose `exportStatus` is already set (for example a table
rebuilt from the stream sidecar, §6.1) is not exported again.

### 5.2 Path template and collisions

Default path, built in UTC by `ParlerTableFileExportHook.buildExportPath` (Joda-Time patterns in braces):

```text
/{sanitizedPrincipal}/{yyyyMMdd}/{yyyyMMdd'T'HHmmss'Z'}_{sanitizedRequestId}.csv
```

- `sanitizedPrincipal` is the current ThingWorx user (`anonymous` when none).
- `sanitizedRequestId` is the `request_id` of the current assistant downlink (the same id as the `table`,
  `activity` and `content.delta` frames). `SaveText` truncates an existing file, so a second-precision
  timestamp alone would let two exports in the same second overwrite each other; the request id makes the
  default name unique per turn.
- Sanitizing: every run of characters outside `[a-zA-Z0-9._-]` becomes `_`; an empty result becomes `x`;
  segments are cut to 120 characters. UUID hyphens are kept.
- Collision check: before `SaveText`, the agent calls `GetFileListing` on the parent directory and matches the
  file name. If the path is taken, it inserts `_` plus random hex before `.csv` and retries (at most 8
  times). If no free path is found: `exportStatus: path_collision`, no file written. `exportFile` always
  reflects the path actually written.
- Table exports use `.csv`.

### 5.3 Failure outcomes

| Condition | Outcome |
|------|------|
| `exportFileRepository` empty | `repo_missing`; no write; message names the empty setting. |
| Repository Thing not found or not a Thing | `repo_error`; no write. |
| CSV too large | `skipped_limit`; the log records `totalRows`, inline row count, CSV length, cap, `conversation_id` and `request_id`. |
| Partial sample without a resolvable full table | `skipped_limit`. |
| No free path after retries | `path_collision`; no write. |
| `SaveText` or other write failure | `write_error` ("CSV export unavailable."). |
| Success | `ok`; `exportRepository` and `exportFile` set. |

### 5.4 Ordering: export before the single `table` frame

When a table needs an export, the agent performs at most one synchronous export attempt in the same turn
(build CSV → `SaveText`) and **then** emits the single `type: "table"` frame, which carries the final
`exportStatus` (and `exportMessage`, and `exportFile` / `exportRepository` on success). There is no second
frame that patches the download state later, so the client renders the export footer in one reducer step.

### 5.4.1 Temporary `activity` line before writing

Before `SaveText`, the agent sends one `type: "activity"` frame on the same `request_id` with the text
"Large table: writing CSV export to FileRepository…". It uses the same channel as other in-progress lines
(`assistant.activity` → the row's `activity`, shown in the ephemeral status line).

- Order: `activity` → (blocking) CSV write → `tabular.tool_success` → the single `table` frame → text
  `content.delta`.
- The line is transient. The reducer clears `activity` on the first non-blank `content.delta`, on `chart`, on
  `session.done` / `session.error` / `session.superseded`, and — symmetric with `assistant.chart` — when
  `assistant.table` appends a table.
- It is never persisted: `AgentMessageStreamHistoryExporter` writes `activity: null` on assistant rows.

### 5.5 Download links

ThingWorx serves repository files over two GET entry points. The client builds the link from `table` payload
fields only (`exportRepository` + `exportFile`, or `exportDownloadUrl`), never from a guess about which
repository the mashup is bound to.

**Method A — path form** (default):

```text
{ThingWorxOrigin}/Thingworx/FileRepositories/{repositoryThingName}{filePathInRepo}
```

`filePathInRepo` is the same path as `SaveText`'s `path` and starts with `/`. Path segments with spaces or
special characters must be URL-encoded. The response is sent as an attachment.

**Method B — query form:**

```text
{ThingWorxOrigin}/Thingworx/FileRepositoryDownloader?download-repository={repositoryThingName}&download-path={pathInRepo}
```

Use `/` as the separator in `download-path`. Adding `directRender=true` renders the file inline instead of
downloading it.

**Permissions:** the download entry points check that the caller may invoke `GetFileListing` on the
repository, so mashup users need the corresponding permission on the target FileRepository.

**`parler-ui`:** when `exportStatus` is `ok` and there is no `exportDownloadUrl`, the widget renders a GET link:
Method A by default (repository name encoded with `encodeURIComponent`, path with `encodeURI`); Method B when
`exportFile` contains `?` or `#`, which would break a path-style URL. Without `window` it falls back to plain
text (`TABLE_CONTRACT.md` §4).

---

## 6. History replay

Tables are never rebuilt from assistant Markdown.

### 6.1 How history tables are built

- The `AgentMessageStream` persists the tool JSON of `role: tool` rows. The live `type: "table"` frame is not
  stored as a separate copy; as with charts, history derives structured blocks from the tool payload.
- **Export metadata (stream rows only):** for list-class table tools, `parler-agent` may add a reserved root
  key **`_parlerTableExport`** to the tool JSON written to the stream (a snapshot of `exportStatus`,
  `exportRepository`, `exportFile`, `exportMessage`, `exportDownloadUrl`). `GetConversationHistoryJson` then
  rebuilds `tables[]` with the same export fields as the live table, without a second `SaveText`. The
  `ChatMessage` sent to the LLM uses the original JSON without this key; only the stream copy is enriched by
  `ParlerToolStreamTableExportSidecar`. See
  [`docs/ui/AGENT_MESSAGE_STREAM_TO_AI_PARLER.md`](AGENT_MESSAGE_STREAM_TO_AI_PARLER.md).
- `AgentMessageStreamHistoryExporter` writes `tables[]` on each turn's assistant row, using the same
  projection as the live wire (for example
  `ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson` for `tabulate_cached_result`).
- [`docs/ui/AI_PARLER_HISTORY.md`](AI_PARLER_HISTORY.md) defines `tables`; `parler-ui/lib/historyHydrate.js`
  parses them into state, symmetric with `charts[]`.

### 6.2 Coverage

Every tool in §2.2 writes history `tables[]` with the same projection as its live `table` frame. Empty
results (`ENTITY_*_EMPTY` and similar) produce no table. Non-factual content stays Markdown (§9).

### 6.3 Relation to export

Export fields in history follow `TableBlock` / `AI_PARLER_HISTORY.md`. The assistant row's `activity` stays
`null` in history JSON (§5.4.1).

---

## 7. Chart and table in one bubble

Charts and tables may share an assistant bubble. The vertical order is fixed in `parler-ui` and recorded in
`CONTRACTS/UI_CLIENT_PROTOCOL.md`, together with the order relative to explanatory Markdown.

---

## 8. PASSWORD columns

- `table.columns[].baseType` and `key` form a column map; cells do not repeat their type.
- Table rows are built from tool JSON in which PASSWORD cells are already written as `***` (below).
- The UI masks as a second line of defense: in `parler-ui`, a column with `baseType === "PASSWORD"` always
  renders `••••` (`TABLE_CONTRACT.md` §4).
- Tool text sent to the LLM is masked the same way: a scalar `PASSWORD` result of `invoke_service` is written
  as `***` by `InvokeServiceExecutor.formatToolResult`. Where rows are serialized — `INFOTABLE` /
  `INFOTABLE_LARGE`, `fetch_cached_result`, `tabulate_cached_result` — `InvokeServiceExecutor` and
  `CachedTabularToolsExecutor` write `***` for every column whose `DataShapeDefinition` base type is PASSWORD
  (`ParlerInfotableJsonUtil.isPasswordColumn`). The `InfoTable` in the conversation cache is not rewritten.
- Queries run in the caller's security context.

---

## 9. Non-factual Markdown tables

`parler-ui/parler-ui.js` uses `new MarkdownIt({ html: false, linkify: true, breaks: true })`. Standard GFM
pipe tables are parsed by markdown-it into `<table>` without any plugin.

### 9.1 Styling

`parler-ui/styles/parler-ui.css` styles `[part="markdown-content"] table`, `th` and `td` with the same thin
borders and compact density as structured tables (`--parler-effective-markdown-table-border`,
`--parler-effective-markdown-table-header-bg`). The structured table (`.parler-data-table-*`) keeps its own
compact body and subtle surface.

---

## 10. List-class use case

| User intent | Required capability | Table |
|------------------|---------------------------|------------|
| Enumerate assets of a taxonomy type with named columns (for example the name, display name and serial number of every Stacking Robot) | `query_entities_by_taxonomy` with the taxonomy row's `CriticalProperties` / `AdditionalProperties` passed through unchanged (column projection per `AGENT-TAXONOMY.md`). Later sorting, top-N or grouping of the cached table uses `tabulate_cached_result` modes from `cached_tabular_tools.md`. | Yes |

If an earlier turn returned only a count and no rows, a follow-up that asks for the list runs a list query
again; rows are never reconstructed from a count.

---

## 11. Not supported

- Editable cells, inline Excel/CSV preview, cross-session export queues.
- Pivot / free aggregation UI (use `summarize_cached_result` / `tabulate_cached_result` output instead).
- Custom column widths and drag-to-sort.
- Heuristic detection of factual tables in assistant Markdown.
- Markdown table syntax beyond GFM pipe tables.

---

## 12. FileRepository write service choice

The export writes through the FileRepository Thing's `SaveText` service. Relevant platform behavior of the
write services (paths are relative to the repository root):

| Service | Behavior | Use for export |
|--------|------|----------------------|
| `SaveText` | `path`, `content` (STRING); content must be non-empty; writes UTF-8; creates parent directories; no overwrite flag — an existing file is truncated and rewritten; returns Boolean. | **Used.** UTF-8 and nested directories in one call. Uniqueness comes from the path template and collision check in §5.2. |
| `SaveBinary` | `path`, `content` (BLOB); non-empty; creates parent directories. | Alternative for byte content; not used. |
| `CreateTextFile` | `path`, `data`, `overwrite` (default `false`); encodes with the JVM default charset; creates parent directories. | Not used: encoding is not fixed to UTF-8 and existing files raise an error by default. |
| `WriteToTextFile` | Requires an existing file and a byte offset. | Not suitable for new files. |
| `AppendToTextFile` | Appends. | Not used. |
| `CreateFolder` / `CreateFolderInParent` | Create directories explicitly. | Not needed; the write services create parents. |

Because `SaveText` rejects empty content, a CSV always contains at least the header line.
