# Table contract — wire frame + `TableBlock`

**Normative:** Single source for (1) the **server → client** **`type: "table"`** wire object (including ThingWorx AlwaysOn), and (2) the nested **`table`** payload **`TableBlock`**.

**Related:** [`API_CONTRACT.md`](./API_CONTRACT.md), [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) §2–4, [`CHART_CONTRACT.md`](./CHART_CONTRACT.md) (symmetric wire pattern), product SoT [`docs/ui/table-view-solution.md`](../docs/ui/table-view-solution.md), [`docs/ui/table-title.md`](../docs/ui/table-title.md) (**`presentationTitle`** / tool identity).

**Emitter:** Production **`TableBlock`** payloads are built on the **ThingWorx** side (**`parler-agent`**), not in this repo’s reference UI.

**`invoke_service` INFOTABLE tool JSON:** For **`resultKind`** **`INFOTABLE`** / **`INFOTABLE_LARGE`**, each **`columns[]`** element **SHOULD** include **`baseType`** (uppercase ThingWorx primitive name, same vocabulary as **`TableBlock.columns[].baseType`**) taken from the result **`InfoTable`** **`DataShapeDefinition`** when the column is declared there, so clients and **`ParlerInvokeServiceInfotableTableWire`** do not rely on value-shape inference alone. When shape metadata is missing for a column, **`baseType`** **MAY** default to **`STRING`**.

For **`INFOTABLE_LARGE`**, **`cacheId`** **MAY** be omitted when the server did not persist a conversation-scoped cache entry for that result (e.g. cache preparation failure). Clients **SHOULD** tolerate a missing **`cacheId`**; **`fetch_cached_result`** **cannot** page that result.

**`fetch_cached_result` tool JSON:** Success payloads include paging fields (**`offset`**, **`returnedRows`**, **`hasMore`**, **`totalRows`**) plus **`cacheId`** and **`rows`**. They **MUST NOT** carry **`resultKind`** or **`sourceCacheId`** (those disambiguate **`tabulate_cached_result`** / other tabular tools). **`columns[]`** **SHOULD** mirror the cached table’s **`DataShapeDefinition`** (**`name`** + **`baseType`**) so **`TableBlock`** matches **`invoke_service`** / large-cache semantics. **`returnedRows`** **MUST** be non-negative; when the requested **`offset`** exceeds the cached row count, servers **SHOULD** clamp the read window so **`returnedRows`** reflects zero rows read from the tail, not a negative count. An empty **`rows`** array with non-empty **`columns[]`** is a valid **empty page** (schema without data) and **SHOULD** map to a **`TableBlock`** with **`shownRows: 0`**.

---

## 1. Purpose

**List-class** tabular results are **server-authored** structured rows: cell values are **not** inferred from assistant Markdown alone. The client (`<parler-ui>`) renders validated wire JSON as an **HTML table** (or equivalent) and optional **export / download** UI from normative export fields.

---

## 2. Wire envelope (`type: "table"`)

Each table is **one** JSON object on the server→client stream: one **`ReceiveMessage`** payload (default profile), or **one element** of a JSON array batch.

### 2.1 Required fields (all transports)

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | **Literal** `"table"`. |
| `request_id` | string | Same turn id as `session.ack`, `activity`, `content.delta`, `chart`, `done` for this assistant reply. |
| `table` | object | **`TableBlock`** — schema in **§3**. |

### 2.2 AlwaysOn / `ReceiveMessage` extension

Per **[`agent-alwayson.md`](../docs/architecture/agent-alwayson.md)** §6.2, every object sent through **`ReceiveMessage`** **MUST** also include:

| Field | Type | Description |
|-------|------|-------------|
| `conversation_id` | string | Non-empty; Conversation / thread id (snake_case). |

Dedicated Parler WebSocket (non-AlwaysOn) may omit **`conversation_id`** on **`table`** when the connection implies a single thread; see [`API_CONTRACT.md`](./API_CONTRACT.md).

### 2.3 Example (AlwaysOn)

```json
{
  "type": "table",
  "conversation_id": "agent-thread01",
  "request_id": "8fecb285-877c-4304-9d0c-0ce837830fc3",
  "table": {
    "kind": "entity-list",
    "columns": [
      { "key": "name", "label": "name", "baseType": "STRING" },
      { "key": "PTCDisplayName", "label": "display name", "baseType": "STRING" }
    ],
    "rows": [{ "name": "A", "PTCDisplayName": "Alpha" }],
    "shownRows": 1,
    "totalRows": 1,
    "sourceCacheId": "cache-uuid",
    "cacheId": null,
    "exportRepository": "MyFileRepositoryThing",
    "exportFile": "/user/20260416/20260416T153022_8fecb285-877c-4304-9d0c-0ce837830fc3.csv",
    "exportDownloadUrl": null,
    "exportStatus": "ok",
    "exportMessage": null
  }
}
```

---

## 3. `TableBlock` — JSON schema (logical)

The **`table`** property **MUST** satisfy the following.

### 3.1 Core fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `kind` | string | yes | v1 list-class tables: **`entity-list`**. Other values are reserved; clients **SHOULD** ignore unknown `kind` and skip rendering until a supported kind appears. **Reference `parler-ui` (v1):** **`wireAdapter.asTableBlock`** returns **`null`** unless **`kind`** is exactly **`entity-list`**, so unknown kinds produce **no** **`assistant.table`** event; **`renderTableBlock`** also guards on **`entity-list`** (defense in depth). |
| `columns` | array | yes | Non-empty. Each item **MUST** include **`key`**, **`label`**, **`baseType`** (all strings). v1: **`label`** SHOULD equal **`key`** unless i18n requires otherwise. |
| `rows` | array | yes | Each element **MUST** be a JSON object. Row objects **SHOULD** only use keys that appear in **`columns[].key`**; extra keys **SHOULD** be ignored by clients. |
| `shownRows` | number | no | Count of rows included in **`rows`** (may be a sample). Integer ≥ 0. |
| `totalRows` | number | no | Total logical row count when different from inline sample. Integer ≥ 0. |
| `sourceCacheId` | string | no | Effective cache id for provenance / Further Insight alignment. |
| `cacheId` | string \| null | no | When set, points at server-side paging / fetch (e.g. **`fetch_cached_result`**) and **MAY** align with **`ChartBlock.source.sourceCacheId`** for immediate-parent table/chart pairing in **`parler-ui`**. Emitters **SHOULD** copy the tool success body’s top-level **`cacheId`** whenever present (including inline / non-**`*_LARGE`** tabular result kinds), not only for large/paged payloads. Use JSON **`null`** only when the success body has no **`cacheId`**. |
| `presentationTitle` | string | no | Optional **single-line** disclosure header (`U+000A` / `U+000D` **MUST NOT** appear). Normative emission rules: **§3.1.1**. When absent or empty, clients **SHOULD** fall back to column/row-count summaries. |

### 3.1.1 `presentationTitle` emission (normative for `parler-agent`)

When a **`TableBlock`** is emitted for tabular data produced by a **successful tool invocation** and the server can determine the **executed tool’s LLM-facing name** (built-in identifier or configuration-repository extended tool name), the server **MUST** set **`presentationTitle`** to a non-empty string whose **leading segment** equals that name, optionally followed by **`:`** and a **bounded structured suffix** derived only from **structured tool-success fields**, **durable per-tool-row provenance** (see [`docs/ui/table-title.md`](../docs/ui/table-title.md) §5.4), or **replay linkage** (e.g. resolving **`tool_call_id`** to a persisted assistant **`tool_calls`** entry when row metadata is absent — legacy). The server **MUST NOT** derive the title from user prompts, assistant prose, chart titles, cell-value regex, or other natural-language sources.

For **history / export / replay**, servers **SHOULD** persist the executed tool’s LLM-facing name in a **dedicated field on each tool stream row** when appending tool results (see **`docs/ui/table-title.md`** §5.4), so **`presentationTitle`** reconstruction does **not** depend on the originating assistant row appearing in the same query window. Cross-row lookup **SHOULD** remain supported for legacy rows missing that field.

When the executed tool is **`invoke_service`**, the server **MUST** include the **values** of the **`entityName`** and **`serviceName`** fields from structured success JSON in the suffix when both are non-empty, using the canonical pattern **`invoke_service: <EntityName>.<ServiceName>`** where **`<EntityName>`** and **`<ServiceName>`** denote those **values** (JSON keys are lowercase **`entityName`** / **`serviceName`**). When only one value is present, use **`invoke_service: <EntityName>`** or **`invoke_service: <ServiceName>`** respectively (see [`docs/ui/table-title.md`](../docs/ui/table-title.md) §6.2).

**Length:** Emitters **MUST** enforce a maximum of **80 UTF-16 code units** on the wire (trim then truncate once). When truncated, the string **MUST** end with **`…`** (U+2026). Emitters **MUST** route all non-empty titles through **`ParlerTableWireFields.putPresentationTitle`** so cap and ellipsis rules stay consistent.

### 3.2 Column item

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `key` | string | yes | Stable field id; used as row object key. |
| `label` | string | yes | Header label (v1 often same as `key`). |
| `baseType` | string | yes | ThingWorx-style name (e.g. `STRING`, `NUMBER`, `DATETIME`, `BOOLEAN`). |

### 3.3 Export and download (v1, normative)

Emitters **MUST** include **`exportStatus`** on every **`TableBlock`**. **`exportMessage`** is human-readable; use **`null`** or empty string when there is nothing to show. **Product-safe text:** **`exportMessage`** **MUST NOT** carry raw Java stack traces, ThingWorx internal exception class names, **`GetFileListing`** “directory does not exist” noise used only for export preflight, or long repository path dumps; for those failure classes emit a short stable phrase such as **`CSV export unavailable.`** (full detail stays in **`ApplicationLog`**). Clients **SHOULD** map footer text through an **allowlist** of known safe **`exportMessage`** shapes (or equivalent stable short phrases) and treat anything unknown as **`CSV export unavailable.`** when concatenating table footers (**`<parler-ui>`** implements this policy in **`lib/productSafeTableExportMessage.js`**).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `exportStatus` | string | yes | Stable machine-facing status. v1 allowed values include at least: **`none`** (no file export attempted for this table), **`ok`** (file written when export was attempted), **`skipped_limit`** (export skipped by policy / size), **`repo_missing`**, **`repo_error`**, **`write_error`**, **`path_collision`**. Servers **MAY** extend with deployment-specific strings; clients **MUST** treat unknown values as **generic failure** for export UX (still render **`rows`**). |
| `exportMessage` | string \| null | no | Short user-facing explanation (errors, “Showing N of M”, etc.). |
| `exportFile` | string \| null | no | Repository-relative path when a file was written (same semantics as ThingWorx **`SaveText`** **`path`**). Empty / **`null`** when no file. |
| `exportRepository` | string \| null | no | FileRepository **Thing name** when the UI should build **`/Thingworx/FileRepositories/{name}{path}`** or **`FileRepositoryDownloader`** links. |
| `exportDownloadUrl` | string \| null | no | Absolute HTTPS URL when the server pre-builds the download link. |

**Mutual exclusion (v1):**

- If **`exportDownloadUrl`** is a non-empty string, the client **SHOULD** prefer it as the primary download affordance.
- Otherwise, if **`exportRepository`** and **`exportFile`** are both non-empty, the client **SHOULD** build or display a download path from those fields per deployment docs.
- **`exportDownloadUrl`** and **`exportRepository` + `exportFile`** **MAY** both be absent / null when **`exportStatus`** is **`none`** or no download is offered.

**`exportFile` / `exportDownloadUrl` / `cacheId`:** priority for **“where to fetch more rows”** is **`cacheId`** (tabular continuation) vs **file export** — they address different product paths; both **MAY** appear when the product requires inline sample **and** a CSV export.

### 3.4 `request_id` in file paths (product normative)

When the agent writes **`exportFile`**, the default filename segment **MUST** incorporate a **path-safe** segment derived from the same turn’s **`request_id`** (see **`docs/ui/table-view-solution.md`** §5.2) so second-precision timestamps alone do not cause silent overwrites under **`SaveText`** / **`TRUNCATE_EXISTING`**.

### 3.5 `parler-agent` optional FileRepository CSV export (v1)

Reference implementation: **`ParlerTableFileExportHook`** (invoked from **`AgentThing`** list-class **`wireTable`** paths).

**`AgentThing`** **`AgentSettings`** (Composer configuration data):

| Field | Type | Role |
|-------|------|------|
| **`exportFileRepository`** | `THINGNAME` (FileRepository) | Non-empty **FileRepository Thing** selected in Composer (`thingTemplate:FileRepository`, `friendlyName:Export File Repository` on the field aspects); receives CSV via **`SaveText`**. Empty disables export ( **`exportStatus` `repo_missing`** when policy would otherwise export). |
| **`tableCsvExportRowThreshold`** | integer | Default **200**. When **`totalRows`** exceeds this value, export **MAY** run (subject to the partial-sample rule below). |

**When export is attempted** ( **`exportFileRepository`** non-empty ) **if either** holds:

1. **`totalRows` > `tableCsvExportRowThreshold`**, or  
2. **Partial sample:** **`totalRows` > `rows.length`** and **`rows.length` > 0**.

**CSV source:**

- If **`cacheId`** on **`TableBlock`** is non-empty and the server resolves it to a full cached **`InfoTable`**, emitters **SHOULD** serialize **all** rows from that **`InfoTable`** (column keys from **`columns[].key`**).
- Else, if **not** a partial sample and **`rows`** is non-empty, emitters **MAY** serialize from inline **`rows`** only.
- Else (partial sample without resolvable full table), emitters **MUST** set **`exportStatus`** **`skipped_limit`** and **MUST NOT** pretend a full CSV was written.

**Size cap:** Implementations **MUST** enforce a maximum serialized CSV size before **`SaveText`**; overflow → **`skipped_limit`**, no file. Reference **`parler-agent`**: **`AgentSettings.tableCsvExportMaxChars`** (default **50_000_000**, clamped **1_000_000**..**200_000_000**) compared against the built CSV **`String`** **`length()`** (UTF-16 code units — practical guard before **`SaveText`**); **`ApplicationLog`** **SHOULD** record **`totalRows`**, inline row count, measured **`length()`**, cap, **`conversation_id`**, and **`request_id`** when **`skipped_limit`** is **`max_chars`**.

**`activity`:** Emitters **MAY** send one **`type: "activity"`** on the same **`request_id`** before **`SaveText`** ( **`docs/ui/table-view-solution.md`** §5.4.1 ).

**Path shape (reference `parler-agent`):** **`/{sanitizedPrincipal}/{yyyyMMdd}/{yyyyMMdd'T'HHmmss'Z'}_{sanitizedRequestId}.csv`** (UTC; for example `/Administrator/20260928/20260928T050709Z_req-1.csv`), with **`sanitizedRequestId`** per §3.4.

**Collision avoidance (`SaveText` / `TRUNCATE_EXISTING`):** Before **`SaveText`**, **`parler-agent`** **MUST** verify the target file is absent ( **`GetFileListing`** on the parent directory + match **`name`** to the final path segment). If the default path is occupied, it **MUST** try alternate names by appending **`_` + random hex** before **`.csv`** (bounded retries). If no free path is found, **`exportStatus`** **`path_collision`** and **`exportFile`** **`null`**.

---

## 4. UI rendering notes (portable)

- Render **`rows`** in **`columns`** order; header labels from **`label`**.
- When **`columns[].baseType`** is **`PASSWORD`**, clients **MUST** render a fixed mask (reference **`parler-ui`**: **`••••`**) regardless of the cell payload (defense in depth if a server mis-serializes a secret).
- Cell display: coerce primitives to string; **do not** execute cell strings as HTML.
- When **`shownRows`** / **`totalRows`** indicate a partial sample, show a short footnote using **`exportMessage`** or a client default.
- When **`exportStatus`** is **`ok`**, **`exportDownloadUrl`** is absent, and **`exportRepository`** + **`exportFile`** are set, reference **`parler-ui`** **SHOULD** offer a **`GET`** hyperlink when **`deploymentOrigin`** is known (typical mashups: **`window.location.origin`**): prefer **`/Thingworx/FileRepositories/{exportRepository}{exportFile}`** with path encoding per **`docs/ui/table-view-solution.md`** §5.5 (Method A, path-style link); when **`exportFile`** contains **`?`** or **`#`** (would corrupt a path-style URL), **SHOULD** use **`/Thingworx/FileRepositoryDownloader`** with **`download-repository`** + **`download-path`** (Method B, downloader link). When no origin is available, **SHOULD** still surface **`exportRepository`** + **`exportFile`** text per **§3.3**.
- On **`assistant.table`**, the reference client **clears** ephemeral **`activity`** on that assistant row (same as **`assistant.chart`**).

---

## 5. Invariants

1. **`columns.length` ≥ 1**.
2. **`rows`** length **SHOULD NOT** exceed server / contract **`MAX_TABLE_WIRE_ROWS`** (numeric constant lives in product doc / agent config; not duplicated here).
3. Emitters **MUST NOT** duplicate the same fact rows as a Markdown pipe table in the same turn (**no dual source**) per **`docs/ui/table-view-solution.md`**.
