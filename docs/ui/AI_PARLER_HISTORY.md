# AI Parler — one-shot history hydration

Use this when loading **persisted thread** data (e.g. ThingWorx `AgentMessageStream` via your REST API). It **replaces** the in-widget transcript; it does **not** replay wire deltas.

## JSON shape (`ai-parler-history-v1`)

Top-level object:

| Field | Required | Description |
|--------|-----------|-------------|
| `format` | No | If set, must be `ai-parler-history-v1` (recommended for forward compatibility). |
| `rows` | Yes | Array of **user** / **assistant** rows in display order. |

### User row

```json
{ "kind": "user", "text": "Hello" }
```

Optional nested **`hostContext`** (**`parler-host-context-snapshot-v1`**) when the server exported Stream **`hostContextSnapshotJson`** — see **`docs/architecture/host-context-turn-state.md`** §4.1 and **`CONTRACTS/API_CONTRACT.md`**.

```json
{
  "kind": "user",
  "text": "how about now?",
  "hostContext": {
    "schema": "parler-host-context-snapshot-v1",
    "accepted": true,
    "outcome": "ACCEPTED",
    "key": "asset_monitoring.query_scope",
    "hash": "sha256:…",
    "utf8Bytes": 335,
    "changedFromPreviousUserTurn": false,
    "rawJsonStored": false
  }
}
```

Empty or whitespace-only `text` rows are **skipped**.

### Assistant row

```json
{
  "kind": "assistant",
  "requestId": "optional-stable-id",
  "markdown": "## Reply\\n…",
  "charts": [],
  "tables": [],
  "activity": null
}
```

| Field | Required | Description |
|--------|-----------|-------------|
| `markdown` | No | Defaults to `""`. You may use `content` as an alias. |
| `requestId` | No | Defaults to `hist-0`, `hist-1`, … by position. |
| `charts` | No | Array of **`ChartBlock`** objects (same schema as WebSocket `chart`); invalid entries are skipped. |
| `tables` | No | Array of **`TableBlock`** objects (same schema as WebSocket `table` — **[`TABLE_CONTRACT.md`](../../CONTRACTS/TABLE_CONTRACT.md)**); invalid entries are skipped. |
| `activity` | No | Ephemeral label; usually omit for stored history. |
| `assistantMessageId` | No | Stable final-assistant Stream id when known; required for feedback/cutoff row actions. |
| `completedAt` | No | ISO-8601 UTC completion instant when known; used for history cutoff. |
| `llmUsage` | No | Sanitized token / provider usage object when the server exports Stream **`llmUsageJson`** (numeric + short id strings only). Same key subset as optional live **`done.llm_usage`**. |
| `feedbackRating` | No | When present, **`up`** or **`down`** — last persisted thumbs state from **`ui_feedback`** replay (see **`AgentMessageStreamHistoryExporter`**). |

## Widget / element API

- **`hydrateHistoryFromJsonString(jsonString)`** — parse + replace state (on parse/validation error, sets `error` on the widget and clears busy).
- **`hydrateHistoryFromObject(obj)`** — same, already-parsed object.
- **`loadHistoryJson(jsonString)`** — ThingWorx service alias for `hydrateHistoryFromJsonString`.

## Server mapping notes

Normative mapping: **[`AGENT_MESSAGE_STREAM_TO_AI_PARLER.md`](./AGENT_MESSAGE_STREAM_TO_AI_PARLER.md)**.

AlwaysOn naming: **[`ALWAYON_WIRE_CONVENTIONS.md`](../../CONTRACTS/ALWAYON_WIRE_CONVENTIONS.md)**.
