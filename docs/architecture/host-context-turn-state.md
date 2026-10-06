# Host Context Turn State and Direct Use of System IDs

This document describes how Host Context behaves across the turns of one conversation: the snapshot
persisted on each user row, the freshness prompt, how history and the UI show it, and how system ids
that arrive in Host Context are passed straight to tools.

It builds on [`host-context.md`](./host-context.md), which covers template registration and rendering:

```text
HostScopeJson
  -> key + context
  -> agent finds the registered template
  -> agent renders the prompt fragment
  -> fragment is added to the current LLM turn as an ephemeral system message
```

Unregistered keys are covered in [`host-context-generic-fallback.md`](./host-context-generic-fallback.md).
Normative wire rules are in [`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) (§ `hostContext`,
§ History export) and [`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) (§ Host context).

## 1. Background

`parler-ui-widget` is embedded in Mashups such as Asset Monitoring, Asset Detail, dashboards, and
customer pages. Each page has its own state: selected asset types, the selected hierarchy node, active
filters, the Thing shown on a detail page, the current tab, time window, and visible result set.

With a stable `conversationId`, the user keeps talking while the page state changes:

```text
How many assets are here?
How about now?
Show the selected asset's alert history.
What is the current status under this node?
Summarize this page.
```

Host Context is a per-turn sideband. It is not user input, not a permission mechanism, and not a
server-side scope injection. On top of rendering, a multi-turn conversation needs three things:

1. **Traceability.** Each user prompt records the Host Context it was asked under.
2. **Freshness.** When the page state changes, the LLM is told not to reuse answers computed under the
   old state.
3. **Direct ids.** Values from the page are usually real ThingWorx ids (ThingName, ThingShape or
   ThingTemplate name, hierarchy node id). They are passed to tools as ids, not sent through
   natural-language resolvers.

## 2. What this provides

- every user row in `AgentMessageStream` carries a Host Context snapshot;
- history export, the UI, and the collection tool can see the snapshot's key, hash, raw JSON, and
  change state;
- each turn tells the LLM whether Host Context changed since the previous user turn;
- for "here", "now", "current view", "selected", "this page" style prompts, the LLM is steered to the
  current Host Context;
- system ids from Host Context go straight to tools; hierarchy nodes use `hierarchyNodeId`;
- the UI shows a collapsed Host Context bar above each user prompt, with compact copy icons for the
  prompt text and for the expanded raw JSON.

Design boundaries:

- no server-side Host Scope injection into tool arguments;
- Host Context is not a permission mechanism;
- no business schema shared across all Mashups;
- no JSON diff, semantic diff, field selection for hashing, or volatile-field filtering;
- no setting to turn off raw Host Context persistence.

## 3. Turn snapshot

### 3.1 Snapshot on every user row

Every user row written to `AgentMessageStream` carries the field `hostContextSnapshotJson` (TEXT). It
is written on `role=user` rows only; assistant and tool rows never repeat it. The value is a JSON
object with schema `parler-host-context-snapshot-v1`:

```json
{
  "schema": "parler-host-context-snapshot-v1",
  "accepted": true,
  "outcome": "ACCEPTED",
  "key": "asset_monitoring.query_scope",
  "hash": "sha256:1f4f...",
  "utf8Bytes": 335,
  "changedFromPreviousUserTurn": true,
  "rawJsonStored": true,
  "rawJson": "{\"key\":\"asset_monitoring.query_scope\",\"context\":{...}}"
}
```

The snapshot is written for every outcome, including absent and rejected uplinks, so that the next
turn can tell "the previous user row had no accepted Host Context" apart from "there is no previous
user row".

Fields by outcome:

| Outcome | `accepted` | Fields |
| --- | --- | --- |
| `ACCEPTED` | `true` | `outcome`, `key`, `hash`, `utf8Bytes`, `changedFromPreviousUserTurn`, `rawJsonStored`, `rawJson` (only when `rawJsonStored` is `true`) |
| `UNREGISTERED_GENERIC_FALLBACK` | `true` | same as `ACCEPTED`, plus `genericFallback: true`, `templateFound: false`, `renderTruncated` |
| `ABSENT` | `false` | `outcome`, `changedFromPreviousUserTurn` (§3.3) |
| rejected: `OVERSIZE`, `INVALID_JSON`, `MISSING_KEY`, `SCHEMA_REJECT`, `RENDER_FAILED` | `false` | `outcome`, `rejectCode`, `rejectDetail`, `utf8Bytes` (only when measured: `OVERSIZE` and empty generic fallback), `changedFromPreviousUserTurn: false` |

Raw JSON storage:

- `rawJson` is the uplink string exactly as received.
- It is stored only on accepted rows where `changedFromPreviousUserTurn` is `true` (the anchor rows).
  The first accepted snapshot in the effective history range is always an anchor.
- Unchanged rows omit `rawJson` (`rawJsonStored: false`); their `hash` points back to the anchor row.
- Rejected uplinks never store raw JSON. A rejected Host Context does not affect the answer, so the
  outcome and reject detail are enough.

Example — `UNREGISTERED_GENERIC_FALLBACK` (parseable key, no registered template, generic fragment
inserted):

```json
{
  "schema": "parler-host-context-snapshot-v1",
  "accepted": true,
  "genericFallback": true,
  "templateFound": false,
  "outcome": "UNREGISTERED_GENERIC_FALLBACK",
  "key": "asset_monitoring.query_scope",
  "hash": "sha256:…",
  "utf8Bytes": 184,
  "changedFromPreviousUserTurn": true,
  "renderTruncated": false,
  "rawJsonStored": true,
  "rawJson": "{…}"
}
```

`accepted: true` means a prompt fragment was inserted. It does not mean a registered template was
used: registered-template side effects (tool-admission hints, document-scope injection) never run on
generic fallback. Use `outcome` or `genericFallback` to tell the two apart.

Example — `ABSENT` after an absent previous row:

```json
{
  "schema": "parler-host-context-snapshot-v1",
  "accepted": false,
  "outcome": "ABSENT",
  "changedFromPreviousUserTurn": false
}
```

Example — rejected (`MISSING_KEY`):

```json
{
  "schema": "parler-host-context-snapshot-v1",
  "accepted": false,
  "outcome": "MISSING_KEY",
  "rejectCode": "missing_or_empty",
  "rejectDetail": "key: missing_or_empty",
  "changedFromPreviousUserTurn": false
}
```

Debugging reads the Stream. The anchor-only storage model relies on collection and live debugging
reading `hostContextSnapshotJson` from `AgentMessageStream`, not from UI history, which can be cut by
`historyClearedAt` or paging. A "raw JSON unavailable" gap in the UI (§4.1) never hides the anchor row
from the Stream.

### 3.2 Hash of the raw string

```text
hash = "sha256:" + lowercase hex of SHA-256(UTF-8 bytes of the Host Context string as received)
```

There is no canonical JSON normalization, no key sorting, and no field selection. Host Context is not
hand-written: the App Developer builds a JavaScript object from page state and calls `JSON.stringify`,
so field order is fixed by the Mashup expression. Hashing the raw string is easy to explain, easy to
debug, and matches exactly what the agent received and what the Stream stores.

If a Mashup puts timestamps, nonces, scroll offsets, or similar fields into `HostScopeJson`, every turn
will report `changed`. Fix that in the Mashup: remove those fields from `HostScopeJson` or use a
different template key.

### 3.3 Change computation

For each user turn on the full paths (`ParlerStreamToRemoteThing`, `ChatAsync`, and `Chat` with a
conversation id) the agent:

1. evaluates the Host Context uplink;
2. computes the raw-string hash when a prompt fragment is inserted (`ACCEPTED` or
   `UNREGISTERED_GENERIC_FALLBACK`);
3. takes the snapshot of the previous user row in the effective history range;
4. computes `changedFromPreviousUserTurn`.

Single-turn `Chat` (no conversation id) persists no snapshot and adds no freshness block.

Finding the previous snapshot is bounded:

- **In-memory carry (normal path).** Each AgentThing keeps, per conversation id, the snapshot of the
  last user row it persisted. The next turn compares against it in O(1).
- **Stream lookup (cold start).** When there is no carry entry (for example after a restart), the agent
  queries `AgentMessageStream` for the conversation, starting after `historyClearedAt`. It reads the
  newest rows in growing windows (256, 1024, 4096 rows, then the history exporter's hard cap) and scans
  newest to oldest for the latest user row with a non-empty snapshot. It stops early when a window
  returns fewer rows than requested. It never scans the full history on every turn.
- After the user row is appended, the carry is updated, so later turns use the O(1) path.
- `ClearConversation` and `SetConversationHistoryCutoff` drop the carry for that conversation, so the
  comparison respects the new history boundary.

Rules:

| Current Host Context | Previous user row | `changedFromPreviousUserTurn` |
| --- | --- | --- |
| accepted or generic fallback | none in range | `true` |
| accepted or generic fallback | accepted, same hash | `false` |
| accepted or generic fallback | accepted, different hash | `true` |
| accepted or generic fallback | absent or rejected | `true` |
| absent | none, or absent | `false` |
| absent | accepted | `true` |
| absent | rejected | `false` |
| rejected | any | `false` (metadata only; no freshness block) |

"Accepted" for the previous row means its snapshot has `accepted: true`, which includes generic
fallback.

### 3.4 Freshness block

When a fragment is inserted (on the full paths), the agent places a fixed freshness block in front of
the rendered Host Context fragment, in the same ephemeral system message:

```text
Host page context freshness:
- The host page context below is the current page state for this user turn.
- For "here", "now", "current view", "selected", "this page", and similar prompts, prefer this current host page context over prior assistant answers.
- If the host page context changed since the previous user turn, re-query evidence before answering counts, lists, summaries, or comparisons.
- Explicit user text still wins over host page context.
- Host page context changed since the previous user turn.
```

When unchanged, the last line is:

```text
- Host page context is unchanged since the previous user turn.
```

This is LLM steering, not a hard constraint. It does not guarantee the model re-queries, but it makes
reuse of a stale answer to "how about now?" much less likely.

Prompt-cache stability: the first five lines are byte-stable and always precede the single variable
line. Do not insert variable text into the stable prefix; provider prefix caching depends on it.

## 4. History and UI

### 4.1 History wire shape

`GetConversationHistoryJson` (`ai-parler-history-v1`) copies the Stream field
`hostContextSnapshotJson` into a nested `hostContext` object on each user row, with the same field
names. A snapshot that fails to parse is omitted from the row.

```json
{
  "kind": "user",
  "text": "how about now?",
  "hostContext": {
    "schema": "parler-host-context-snapshot-v1",
    "accepted": true,
    "outcome": "ACCEPTED",
    "key": "asset_monitoring.query_scope",
    "hash": "sha256:1f4f...",
    "utf8Bytes": 335,
    "changedFromPreviousUserTurn": true,
    "rawJsonStored": true,
    "rawJson": "{...}"
  }
}
```

An unchanged row has no `rawJson`:

```json
{
  "kind": "user",
  "text": "and the ones with issues?",
  "hostContext": {
    "schema": "parler-host-context-snapshot-v1",
    "accepted": true,
    "outcome": "ACCEPTED",
    "key": "asset_monitoring.query_scope",
    "hash": "sha256:1f4f...",
    "utf8Bytes": 335,
    "changedFromPreviousUserTurn": false,
    "rawJsonStored": false
  }
}
```

**Anchor lookup.** To show raw JSON for a row with `rawJsonStored: false`, the UI scans the earlier user
rows of the loaded thread, newest first, for a row with the same `hash`, `rawJsonStored: true`, and a
non-empty `rawJson`, and uses that row's `rawJson`. If the loaded history starts after the anchor row
(history clear or paging), no anchor is found and the UI shows `raw JSON unavailable in loaded history`.
This is an accepted display gap in the UI only; collection and live debugging read the Stream (§3.1),
where the anchor row is always available.

### 4.2 UI disclosure and copy icons

`parler-ui` shows a disclosure bar at the top of every user prompt whose snapshot has an `outcome`
other than `ABSENT`. It is collapsed by default.

Accepted rows:

```text
Host context: asset_monitoring.query_scope · changed · 335 bytes
Host context: asset_monitoring.query_scope · unchanged · 335 bytes
```

Other outcomes show the lowercased outcome and the reject code, for example
`Host context: missing_key · missing_or_empty · 0 bytes` or
`Host context: unregistered_generic_fallback · 184 bytes`.

Expanding the bar shows the raw JSON (pretty-printed for display), resolved through the anchor lookup
in §4.1, for example:

```json
{
  "key": "asset_monitoring.query_scope",
  "context": {
    "page": "Asset Monitoring",
    "queryParameters": {
      "selectedEntityTypes": [
        { "EntityType": "ThingShape", "EntityName": "PTCTDD.CellfabDataset.Contacting_TS" }
      ]
    }
  }
}
```

Rules:

- Host Context belongs to the user turn, not to the assistant answer; it is never inserted into the
  assistant's text;
- the bar is collapsed by default so it takes little space;
- the user prompt has a compact copy icon that copies the prompt text;
- the expanded raw JSON has a compact copy icon that copies the displayed JSON;
- when raw JSON is unavailable, the panel shows `raw JSON unavailable in loaded history` and no copy
  icon;
- the copy icons take no extra vertical space.

**Live turns.** On Send, the UI attaches a best-effort snapshot built from the same wire string it
sends: `ACCEPTED` when the string parses and has a non-blank `key` (otherwise `MISSING_KEY` or
`INVALID_JSON`), `changed` by comparing with the previous user row's raw JSON, and the raw JSON stored.
This live snapshot does not know about server-side template lookup or rejection. The server's Stream
row is authoritative and replaces it when history is reloaded.

## 5. System IDs in Host Context

### 5.1 Principle

Host Context comes from the page, not from the user's words. Many of its values are already real
ThingWorx ids:

```json
{
  "selectedEntityTypes": [
    { "EntityType": "ThingShape", "EntityName": "PTCTDD.CellfabDataset.Contacting_TS" }
  ],
  "networkName": "PTCTDD.Cellfab.AssociationNetwork_NW",
  "selectedNetworkNode": "SE.CellFab.Model.Region.Germany",
  "thingName": "SE.CellFab.Model.Workunit.ORD-Contacting-01"
}
```

These are used directly:

```text
Host Context system id    -> use directly
User text / display name  -> resolve
```

| Case | From user text | From Host Context system id |
| --- | --- | --- |
| Thing | user says `ORD Contacting 01` → `resolve_thing` | canonical `thingName` → pass as `thingName` |
| Asset type | user says `Contacting` → `resolve_asset_type` | `EntityType` / `EntityName` → pass as-is |
| Hierarchy node | user says `Germany` → `hierarchyNodeName` → `ResolveNetworkID` | node id → `hierarchyNodeId` |
| Mashup service parameters | LLM builds parameters from user text | fenced JSON parameter block from Host Context |

This is not a permission bypass. Every tool call still runs under the current user's ThingWorx
visibility, permissions, policy, and HITL. It only avoids sending already-resolved ids through
natural-language resolution.

### 5.2 No generic `id` field

There is no universal `{"id": "..."}` abstraction, because each kind of id is consumed differently:

- a ThingName goes to `thingName`;
- a ThingShape or ThingTemplate goes to `EntityType` / `EntityName` or an existing parent argument;
- a hierarchy node id goes to `hierarchyNodeId` (which calls `GetAssetList`);
- a service parameter block may become `invoke_service.parameters`.

Instead:

```text
Host Context keeps the real system ids
The template tells the LLM which field goes to which tool argument
Built-in tools accept the needed direct-id arguments
```

### 5.3 Asset type direct path

An Asset Monitoring `selectedEntityTypes` entry

```json
{ "EntityType": "ThingShape", "EntityName": "PTCTDD.CellfabDataset.Contacting_TS" }
```

maps directly to the `EntityType` / `EntityName` arguments of `query_entities_by_taxonomy`. No
separate `thingShape` / `thingTemplate` arguments exist or are needed.

### 5.4 Hierarchy node id: `hierarchyNodeId` vs `hierarchyNodeName`

`query_entities` and `query_entities_by_taxonomy` accept two hierarchy arguments:

| Argument | Source | Server path |
| --- | --- | --- |
| `hierarchyNodeName` | a display-name fragment from the user or dialog, e.g. `Germany` | `ResolveNetworkID(name)` → exactly one row → `GetAssetList(resolvedId)` → intersect |
| `hierarchyNodeId` | a node id (NetworkID) from the page, e.g. `SE.CellFab.Model.Region.Germany` | `GetAssetList(hierarchyNodeId)` → intersect; no `ResolveNetworkID` |

An Asset Monitoring page supplies `selectedNetworkNode`, which is already a node id. It must be passed
as `hierarchyNodeId`, never as `hierarchyNodeName`.

Evaluation order, applied before the tool's own listing runs:

1. If `intersectThingNames` is a non-empty array, use it as-is.
2. Otherwise, if `hierarchyNodeId` is non-blank, call `GetAssetList(hierarchyNodeId)` and use the
   resulting Thing names as the intersect set.
3. Otherwise, if `hierarchyNodeName` is non-blank, use `ResolveNetworkID` → `GetAssetList`.
4. Otherwise, no hierarchy scope.

```text
intersectThingNames > hierarchyNodeId > hierarchyNodeName > unscoped
```

When both `hierarchyNodeId` and `hierarchyNodeName` are given, `hierarchyNodeId` wins. On success the
expanded names are written to `intersectThingNames`, and both hierarchy arguments are removed from the
executed input. The tool then intersects its listing with that set; `query_entities` and
`query_entities_by_taxonomy` apply the intersection at the same stage for both hierarchy arguments.

Failures on the direct-id path (it never calls `ResolveNetworkID`, so no `HIERARCHY_RESOLVE_*` codes):

| Condition | Tool `status` | `code` |
| --- | --- | --- |
| `GetAssetList(hierarchyNodeId)` throws or the service fails | `error` | `HIERARCHY_ASSET_LIST_FAILED` |
| `GetAssetList` succeeds but returns no usable Thing `name` rows | `error` | `HIERARCHY_SCOPED_EMPTY` |
| more than 5000 names after expansion | `error` | `INTERSECT_LIST_TOO_LARGE` |

Once `hierarchyNodeId` is selected, a failure is returned as-is. The tool never falls back to
`hierarchyNodeName` or to an unscoped global listing.

`networkName` is not checked. A node id from Host Context is assumed to be unique in the ThingWorx
hierarchy/network context the page uses; cross-network disambiguation is not supported.

Normative argument semantics are in [`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)
(`query_entities` arguments and the server-side intersect augment). The hierarchy services themselves
are described in [`hierarchy-network-services.md`](./hierarchy-network-services.md).

### 5.5 Template guidance

Because the agent does not bind Host Context to tool arguments, the template must tell the LLM how to
use the ids. For an Asset Monitoring template:

```text
- If the fenced JSON includes selectedNetworkNode, it is a hierarchy node id from the page.
- For query_entities or query_entities_by_taxonomy, pass selectedNetworkNode as hierarchyNodeId.
- Do not pass selectedNetworkNode to hierarchyNodeName.
- If selectedEntityTypes contains EntityType and EntityName, use those exact values for query_entities_by_taxonomy.
- Do not call resolve_asset_type for selectedEntityTypes from host context.
```

Sample templates with this guidance are in `dev_data/sample_scpa_utilization_agent_configuration/host-contexts/` (for example
`PTCTS.AssetMonitoring.ContainedAssetListParler_MU.json`).

## 6. Implementation map

Agent (`parler-agent`, package `com.thingworx.things.agent.hostcontext` unless noted):

| Class | Role |
| --- | --- |
| `HostContextUplink` | Evaluates the uplink; exposes outcome, key, measured UTF-8 bytes, reject reason and detail, rendered fragment. |
| `HostContextTurnPrep` | Per-turn product: decision, raw wire string, snapshot JSON, and the LLM ephemeral text (freshness block + fragment). |
| `HostContextSnapshotBuilder` | Builds `hostContextSnapshotJson`; computes the hash and `changedFromPreviousUserTurn`. |
| `HostContextPreviousSnapshot`, `HostContextTurnCarryStore`, `HostContextPreviousSnapshotLookup` | Previous-snapshot baseline: in-memory carry and bounded Stream lookup. |
| `HostContextFreshnessPrompt` | The fixed freshness block. |
| `AgentMessageStreamAppender` (agent package) | Writes `hostContextSnapshotJson` on user rows. |
| `AgentMessageStreamHistoryExporter` (agent package) | Emits nested `hostContext` on history user rows. |
| `HierarchyQueryEntitiesIntersectAugment` (`hierarchy` package) | `hierarchyNodeId` / `hierarchyNodeName` intersect expansion. |
| `BuiltInTools` (`tools` package) | `hierarchyNodeId` argument schema on `query_entities` and `query_entities_by_taxonomy`. |

UI (`parler-ui`): `lib/hostContextRow.js` (snapshot parsing, disclosure label, anchor lookup, live
snapshot) and the user-row rendering in `parler-ui.js`.

Collection: `parler-collect-live` returns `hostContextSnapshotJson` verbatim on normalized Stream rows,
plus a parsed `hostContextSnapshot` companion (see [`docs/agent/collection-tool.md`](../agent/collection-tool.md)).

## 7. Operator verification

To check turn state on a page that supplies Host Context, use one `conversationId` for several turns:

1. Ask `how many assets are here?`. The user row shows a Host Context bar; the Stream user row has a
   snapshot with `changedFromPreviousUserTurn: true` and `rawJson`; the tool call is based on the page
   context.
2. Change the page filters and ask `how about now?`. The hash changes, the snapshot reports `changed`,
   the freshness block says the context changed, and the LLM re-queries instead of repeating the
   earlier number.
3. Ask again without changing the page. The snapshot reports `unchanged` with `rawJsonStored: false`;
   the UI still shows the raw JSON through the anchor lookup.
4. Select a hierarchy node and ask `how many assets are there under the selected node?`. The tool
   arguments use `hierarchyNodeId`, the server calls `GetAssetList` directly, and `ResolveNetworkID` is
   not called with the node id.

The log lines, Stream fields, and collection commands for these checks are listed in
[`docs/agent/live-diagnostics.md`](../agent/live-diagnostics.md) (Host Context turn-state recipe).

## 8. Design decisions

1. Rejected Host Context is recorded (outcome, reject code, detail) but its raw JSON is not persisted.
2. Every user row stores snapshot metadata; raw JSON is stored only on changed (anchor) rows. The UI
   gap when an anchor is outside the loaded history is accepted.
3. Collection and live debugging read the Stream, which is the authoritative source of raw Host
   Context, not UI history.
4. There is no option to disable raw Host Context persistence; even a Host Context listing many
   ThingNames is small.
5. The nested `hostContext` on history user rows is part of the wire contract
   (`API_CONTRACT.md`, `UI_CLIENT_PROTOCOL.md`).
6. `hierarchyNodeId` reuses the existing hierarchy error codes and never falls back to
   `hierarchyNodeName` or an unscoped listing.
7. The change comparison is bounded: in-memory carry, plus a bounded Stream lookup on cold start.
8. `networkName` is not validated for `hierarchyNodeId`.
9. Copy icons are limited to the user prompt text and the expanded raw Host Context JSON.
