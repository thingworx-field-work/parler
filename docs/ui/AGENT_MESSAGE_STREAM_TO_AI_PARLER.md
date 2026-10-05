# AgentMessageStream → AI Parler history (mapping spec)

**Target:** JSON acceptable to **`loadHistoryJson`** / **`hydrateHistoryFromJsonString`** — see [`AI_PARLER_HISTORY.md`](./AI_PARLER_HISTORY.md) (`format` + `rows`).

---

## 1. Source: ThingWorx `AgentMessageData` (Stream row)

Typical fields (from extension entity shape):

| Field | Role |
|--------|------|
| `conversationId` | Thread key; must match the widget’s bound id for that instance. |
| `role` | `user` \| `assistant` \| `tool` (and possibly `system` — usually **not** stored in Stream for this extension). Two **internal** roles are also persisted and are **never** part of this mapping: `ui_feedback` (append-only thumbs state) and `context_checkpoint` (conversation continuity envelope — see `docs/core/advanced-compact.md` §9.1). Both are removed before turn segmentation, so the wire shape is unchanged by their presence. |
| `content` | Main text (`TEXT`). For some `role=tool` rows, Parler may append a reserved top-level JSON key **`_parlerTableExport`** (export snapshot for history parity — see §2.1); this is **stream persistence only** and is **not** present on the in-memory `ChatMessage` passed to the LLM. |
| `toolCallId` | For `role=tool`, correlates to a tool result. |
| `toolCalls` | For `role=assistant`, JSON array of tool calls when the model requested tools. |

Rows are **ordered** by Stream semantics (timestamp + insertion order). Your API should return rows **chronologically** for the requested `conversationId`.

---

## 2. Target: `ai-parler-history-v1` `rows[]`

Each element is either:

```json
{ "kind": "user", "text": "..." }
```

When the user row’s Stream copy includes **`hostContextSnapshotJson`**, the exporter adds nested **`hostContext`** (parsed JSON object, same schema as Stream).

or

```json
{
  "kind": "assistant",
  "requestId": "optional",
  "markdown": "...",
  "charts": [],
  "tables": [],
  "activity": null
}
```

**Charts:** derived from numeric-history tool results (§3); other charts are emitted only on the **live** wire, so `charts: []` is common.

**Tables:** Parler **`AgentMessageStreamHistoryExporter`** derives **`tables[]`** from **`tabulate_cached_result`**, **`query_entities_by_taxonomy`**, **`list_entities_by_type`**, **`query_entities`**, **`invoke_service`** INFOTABLE success JSON (**`resultKind`** **`INFOTABLE`** / **`INFOTABLE_LARGE`**), and **`fetch_cached_result`** paging success JSON (**`offset`**, **`returnedRows`**, **`hasMore`**, **`totalRows`**, **`cacheId`**, **`rows`** — no **`resultKind`** / **`sourceCacheId`**; same **`TableBlock`** as live **`type: "table"`**). **`invoke_service`** / **`fetch_cached_result`** tool JSON **`columns[]`** includes **`baseType`** from the cached **`InfoTable`** **`DataShapeDefinition`** when declared. Other tools yield **`tables: []`**.

### 2.1 `_parlerTableExport` (stream-only)

For list-class tools that can trigger **`ParlerTableFileExportHook`**, **`parler-agent`** may persist on the **stream** copy of the TOOL `content` JSON an extra object at the root:

- Key: **`_parlerTableExport`**
- Value: snapshot of **`exportStatus`**, **`exportMessage`**, **`exportRepository`**, **`exportFile`**, **`exportDownloadUrl`** (same semantics as **`CONTRACTS/TABLE_CONTRACT.md`** / live **`type: "table"`**).

**Why:** live **`wireTable`** is enriched after the hook runs; the raw tool JSON alone did not carry **`export*`** fields, so **`GetConversationHistoryJson`** could not reconstruct download metadata. The sidecar fixes **`tables[]`** parity for new stream rows. Server-side **`Parler*TableWire`** parsers ignore unknown root keys; the in-memory tool message used for the next model turn **does not** include **`_parlerTableExport`**.

---


## 3. Normative mapping (v1) — ThingWorx `AgentMessageStreamHistoryExporter`

First **drop** every `system`, `ui_feedback`, and `context_checkpoint` row — the internal roles are excluded explicitly rather than falling through as unrecognised, because segmentation treats any non-`user` row as turn tail and would otherwise emit a turn for one. Then split the remainder chronologically by `user` rows into **turn segments**. For each segment:

| Stream rows in segment | Action |
|------------------------|--------|
| `user` | Emit `{ "kind": "user", "text": content ?? "" }` when non-empty after trim. |
| `assistant` / `tool` in tail | Emit **one** `{ "kind": "assistant", "markdown", "charts", "tables", … }` per segment. **`markdown`** = **`content`** of the **last** `assistant` row in the tail only (final post-loop reply; **omit** intermediate tool-calling assistant rows from markdown; **do not** embed `toolCalls` or raw tool JSON). |
| `tool` | For each `tool` row: if **`ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult`** succeeds, append to **`charts[]`** (same schema as live `type: "chart"`). For **`tables[]`**, build via **`ParlerToolTableWireUtil.tableBlockFromListClassToolJson`** (same try-order as above wires). If the tool JSON root contains **`_parlerTableExport`**, **`ParlerTableExportSidecar.mergeToolRootSidecarIntoTable`** merges **`export*`** onto the **`TableBlock`** before serialization (parity with live **`wireTable`**). Otherwise omit that slot when no table matches. |

**Window truncation:** if `QueryStreamData` returns a prefix that starts mid-turn, the first segment may have **no** `user` row; emit only the trailing `assistant` row for that fragment. This is why the internal-role drop above is normative rather than cosmetic: a bounded window whose oldest rows are a `context_checkpoint` would otherwise produce a userless segment, and therefore an assistant turn the conversation never had — a window containing only internal rows must export **no** rows at all.

**Ordering:** preserve turn order. **No incremental/delta** semantics — the client calls **`loadHistoryJson`** once per load.

---

## 4. `requestId` for assistant rows

Stream rows may not store Parler `request_id`. Options:

1. **Omit `requestId`** — client generates `hist-0`, `hist-1`, … (current `parler-ui` behavior).
2. **Stable synthetic id** — server sets `requestId` to a stable value (for example Stream `timestamp` + index).

---

## 5. Versioning

- History document: `format: "ai-parler-history-v1"`.
- **Normative:** **chart replay** via **`charts[]`** (derived from numeric-history tool results) and **table replay** via **`tables[]`** (derived from **`tabulate_cached_result`**, **`query_entities_by_taxonomy`**, **`list_entities_by_type`**, **`query_entities`**, **`invoke_service`** INFOTABLE, and **`fetch_cached_result`** tool JSON) are **part of `ai-parler-history-v1`**. A new `format` string is reserved for **breaking** payload changes (e.g. explicit **`kind: "tool"`** rows in **`rows[]`**, renamed fields, incompatible **`ChartBlock`** / **`TableBlock`**).
