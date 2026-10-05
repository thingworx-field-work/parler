# Collection tool

Status: **v1 shipped in-repo** — `uv run parler-collect-live` (`test_scripts/live_diagnostics/collect.py`) plus `GetAgentRuntimeSnapshot` extensions (`includePlaybooks`, `includeRepositoryFiles`, repository file fingerprints on prompt-cache refresh) and bounded raw configuration repository file collection.

## Problem

Live support is still too manual. When a workshop participant says "this prompt did not work", the useful evidence is
spread across at least three places:

- `ApplicationLog`: what the AgentLoop, LLM provider, tool router, HITL, taxonomy resolver, and playbook runner logged.
- `AgentMessageStream`: what the conversation actually persisted, including tool calls, tool results, final assistant
  rows, request ids, usage sidecars, and table/chart sidecars.
- AgentThing runtime status: what skills, playbooks, extended tools, taxonomy, and policy state the AgentThing had loaded
  when it answered.

The third item is the recurring workshop failure mode: the file in `ConfigurationRepository` looks correct, but the
AgentThing actually ran with a stale, missing, invalid, or different loaded snapshot.

The collection tool should make a support bundle that answers both questions:

1. What happened in this conversation and time window?
2. What configuration did the AgentThing actually use, and how does that compare with the repository files that exist now?

## Scope

This topic is scoped to the `parler` project.

### In scope

- A `parler` repo script exposed through root `pyproject.toml`.
- One command that collects log, stream, and AgentThing status.
- Reuse of existing `test_scripts.agent_eval` HTTP/env helpers where practical, rather than duplicating URL joining,
  `.env`, app-key POST, and ThingWorx InfoTable extraction logic.
- Read-only AgentThing service improvements if current status surfaces are incomplete.
- Read-only collection of configured `ConfigurationRepository` file metadata.
- Output schema for the support bundle.

## Command Contract

Root script:

```bash
uv run parler-collect-live -o logs
uv run parler-collect-live --window 30m --conversation-id demo_conversationId -o logs
```

Only two query dimensions are exposed:

| Option | Default | Meaning |
|--------|---------|---------|
| `--window <duration>` | `1h` | Look back from collection start time. Accept `15m`, `1h`, `2h`, or bare integer minutes. |
| `--conversation-id <id>` | all conversations | Exact `AgentMessageStream.source` filter. When omitted, collect all stream rows in the time window. |

Output control:

| Option | Required | Meaning |
|--------|----------|---------|
| `-o, --output-folder <path>` | yes | Parent folder where the timestamped collection directory is created. |

In this repo, use `logs` for ad hoc live support bundles. `/logs/` is gitignored, so repeated collection does not
pollute source status. External installs may choose any explicit parent folder.

Do not add an `--agent-thing` query dimension. AgentThing names should be discovered from stream rows. As an emergency
fallback when the stream window has no agent rows, the script may read `PARLER_DIAG_AGENT_THING` from the environment.

## Environment

The command loads credentials from the execution directory:

1. Existing process environment variables.
2. `./.env` in the current working directory, without overriding process environment variables.

Required:

| Name | Meaning |
|------|---------|
| `DEV_SERVER` | ThingWorx server base URL. Both `https://host` and `https://host/Thingworx` are valid. |
| `DEV_KEY` | ThingWorx app key sent as the `appKey` header. |

Optional operational environment variables:

| Name | Default |
|------|---------|
| `PARLER_DIAG_STREAM_THING` | `AgentMessageStream` |
| `PARLER_DIAG_LOG_NAME` | `ApplicationLog` |
| `PARLER_DIAG_MAX_ITEMS` | `20000` |
| `PARLER_DIAG_TIMEOUT_S` | `120` |
| `PARLER_DIAG_AGENT_THING` | empty |
| `PARLER_DIAG_COLLECT_REPOSITORY_FILES` | `true` |
| `PARLER_DIAG_REPOSITORY_FILE_MAX_BYTES` | `1048576` |

The output files must never include `DEV_KEY`.

## Output Directory

Each run creates a timestamped UTC subdirectory:

```text
<output-folder>/<yyyymmddHHMMSS>/
  application-log.json
  agent-message-stream.json
  agent-status.json
  <AgentThing>/
    configurationRepository/
      <RepositoryThing>/
        manifest.json
        listings/
          taxonomies.json
          skills.json
          playbooks.json
          policies.json
          tools.json
        files/
          taxonomies/
          skills/
          playbooks/
          policies/
          tools/
          host-contexts/
```

If the timestamp directory already exists, append a numeric suffix:

```text
20260604131507-2/
```

The three schema JSON files use JSON schema version `1` and include the same `collectionId`, whole-run
`collectionStartedAt`, whole-run `collectionFinishedAt`, and common time window in ISO UTC. Individual phase timings, if
needed, belong under the per-file `meta.service` object; the top-level collection timestamps identify the bundle as one
run.

Each downloaded repository subtree includes its own `manifest.json` because raw files are not self-describing. The
manifest records bounded listing status, download status, byte size, SHA-256, and comparison against runtime snapshot
hashes. Repository file collection is read-only and never triggers an AgentThing refresh.

## Query Order

### 1. AgentMessageStream

Use `QueryStreamEntriesWithData` as the primary stream service:

```text
{DEV_SERVER}/Thingworx/Things/AgentMessageStream/Services/QueryStreamEntriesWithData
```

Payload baseline:

```json
{
  "startDate": "<window-start-iso-z>",
  "endDate": "<window-end-iso-z>",
  "oldestFirst": true,
  "maxItems": 20000
}
```

When `--conversation-id` is provided, add:

```json
{
  "source": "<conversationId>"
}
```

Do not send explicit JSON `null` for optional ThingWorx service parameters.

Rationale:

- `QueryStreamEntriesWithData` returns stream-entry metadata plus nested `values`, so the collector can preserve
  `conversationId` / `source`, stream entry id, timestamp, and the actual `AgentMessageData` fields.
- `QueryStreamEntries` has metadata but not the message payload.
- `QueryStreamData` has the message payload but can lose entry/source metadata in all-conversation mode.

Fallback:

- If `QueryStreamEntriesWithData` is missing or unusable on an older server, the collector may fall back to
  `QueryStreamData`.
- The fallback is degraded and must be recorded in `agent-message-stream.json.meta.service.fallbackService` plus a
  warning.

AgentThing discovery:

1. Prefer nested `values.agentThing`.
2. Fall back to normalized top-level `agentThing`.
3. Fall back to `PARLER_DIAG_AGENT_THING` only when stream rows do not identify an agent.

If multiple AgentThings appear in the window, collect status for each.

### 2. AgentThing status

Call AgentThing status services after stream collection and before the log query.

Existing useful surfaces:

| Service | Refreshes cache? | Current usefulness |
|---------|------------------|--------------------|
| `GetAgentRuntimeSnapshot(options)` | only if `refresh:true` | Good current runtime snapshot for loaded skills, built-in tools, extended tools, invoke_service policy status, and taxonomy status. |
| `ValidateAgentConfigurationRepository(repositoryName)` | no | Good read-only validation of repository files using runtime parsers. |
| `GetTaxonomyDiagnostics()` | no | Useful fallback / supplement for current taxonomy cache status. |

Collector payload for `GetAgentRuntimeSnapshot`:

```json
{
  "options": "{\"includePrompt\":false,\"includeSkills\":true,\"includeTools\":true,\"includePolicies\":true,\"includeTaxonomy\":true,\"includePlaybooks\":true,\"includeRepositoryFiles\":true,\"refresh\":false}"
}
```

Rules:

- Never call `RefreshPromptContextCache`.
- Never call `RefreshTaxonomyCache`.
- Never pass `refresh:true`.
- Call `ValidateAgentConfigurationRepository` with `repositoryName: ""` only when the runtime snapshot reports a
  configured repository.
- Call `GetTaxonomyDiagnostics` only when useful as a compatibility fallback or to make taxonomy status clearer.

### 3. ApplicationLog

Use stream-discovered request ids to improve log filtering.

```text
{DEV_SERVER}/Thingworx/Logs/ApplicationLog/Services/QueryLogEntries
```

Payload baseline:

```json
{
  "startDate": "<window-start-iso-z>",
  "endDate": "<window-end-iso-z>",
  "fromLogLevel": "ALL",
  "toLogLevel": "ALL",
  "instance": "",
  "origin": "",
  "thread": "",
  "user": "",
  "isRegex": true,
  "maxItems": 20000,
  "searchExpression": ".*.*"
}
```

When `--conversation-id` is provided, build a best-effort regex union from:

- escaped `conversationId`
- `requestId` / `request_id` found in stream entry metadata, nested `values`, `content` JSON, `toolCalls` JSON, and
  `llmUsageJson`

Record `filterMode: "conversation_text_best_effort"` when filtering this way. When no conversation id is provided,
record `filterMode: "all"`.

## AgentThing Status

The base `GetAgentRuntimeSnapshot(refresh:false)` output covers:

- extension version
- configured `configurationRepository` Thing and availability
- loaded repository skills
- loaded built-in and extended tools
- loaded `invoke_service` policy status and rule count
- loaded taxonomy metadata when `includeTaxonomy:true`

The support bundle also needs:

- loaded playbook registry metadata
- compact file inventory for the configured `ConfigurationRepository`
- comparison-friendly metadata that tells whether repository files changed after the loaded runtime snapshot
- loaded-side per-artifact identity (per-file hashes, not only registry-level loaded times)

Snapshot extensions that provide them:

- Extend `GetAgentRuntimeSnapshot`; do not create a separate broad diagnostics service unless the implementation proves
  that extending the existing JSON is too awkward.
- Add options:
  - `includePlaybooks`, default `false`
  - `includeRepositoryFiles`, default `false`
- The collector passes both new options as `true`; existing callers keep current low-I/O behavior unless they opt in.
- Add top-level `playbooks` from current `_playbookRegistrySnapshot`:
  - `loaded`
  - `loadedAtUtc`
  - `catalogIds`
  - `documentIds`
  - `reservedSlashIds`
  - `diagnostics`
  - optional per-playbook metadata: `id`, `path`, node count, required input keys
- Add `configurationRepository.files` metadata for known Parler config files:
  - `/skills/*/SKILL.md`
  - `/playbooks/*/playbook.json`
  - `/tools/extended_tools.json`
  - `/taxonomies/type-taxonomy.md`
  - `/taxonomies/identity-types.json`
  - `/taxonomies/asset-types.json`
  - `/policies/invoke_service.json`
- For each file, return metadata only:
  - `path`
  - `exists`
  - `status`: `present`, `missing`, `read_error`, `oversized_for_hash`
  - `byteSize`
  - `modifiedAt` if cheaply available
  - `sha256` for small files
- Add loaded-side artifact identity as part of the prompt-context / playbook load path for the same known config files:
  - `loadedSha256`
  - `loadedModifiedAt`
  - `loadedAtUtc`
  - `loadedPath`
- Loaded-side hashes are metadata captured when the AgentThing builds its runtime
  snapshot, not a collector-side reread of old file contents. This makes drift detection a real loaded-vs-current
  comparison instead of only a timestamp heuristic.
- Hashing is bounded: compute `sha256` only for files within the same size limits used for repository parsing /
  validation. Oversized or unreadable files get status-only metadata and no hash.
- Loaded-side and current-side hashes must use the same canonical input. Use the raw file bytes as read from the
  FileRepository, with no line-ending, whitespace, JSON, Markdown, or parser normalization on either side.
- Store the loaded hash metadata on the runtime snapshot structures that `GetAgentRuntimeSnapshot(refresh:false)`
  already serializes; reading status must not reread old file contents or refresh the snapshot.
- The support comparison should prefer `loadedSha256 != sha256` when both hashes are present. Timestamp comparisons such
  as `modifiedAt > loadedAtUtc` are only heuristics and must be labeled as such in output, because platform clocks and
  partial refreshes can make timestamps misleading.

Do not return raw file bodies in the status service or collector output.

The support value is the contrast:

- runtime status says what the AgentThing has loaded
- repository validation/file metadata says what exists now
- log/stream says what the failing prompt actually did

The collector should summarize that contrast under each agent as `driftChecks[]`, for example:

```json
{
  "kind": "skill_file_hash_mismatch",
  "path": "/skills/RegionHealth/SKILL.md",
  "loadedSha256": "loaded...",
  "currentSha256": "current...",
  "confidence": "hash"
}
```

When only timestamps are available, use `confidence: "timestamp_heuristic"` and avoid implying a definitive mismatch.

**v1 collector note:** `parler-collect-live` emits hash-confidence `driftChecks[]` only when both `loadedSha256` and `sha256` are present. Files in `oversized_for_hash` / missing-hash states produce **no** drift row (no silent `timestamp_heuristic` substitute yet); operators should compare `configurationRepository.files[]` status fields manually for those paths until a later revision adds an explicit low-confidence signal.

## Output Schemas

### `application-log.json`

```json
{
  "schema": "parler.liveDiagnostics.applicationLog",
  "schemaVersion": 1,
  "meta": {
    "collectionId": "20260604T131507Z-7f3a",
    "collectionStartedAt": "2026-06-04T13:15:07.123Z",
    "collectionFinishedAt": "2026-06-04T13:15:08.456Z",
    "window": {
      "duration": "1h",
      "startDate": "2026-06-04T12:15:07.123Z",
      "endDate": "2026-06-04T13:15:07.123Z"
    },
    "conversationId": "demo_conversationId",
    "service": {
      "kind": "ApplicationLog",
      "maxItems": 20000,
      "filterMode": "conversation_text_best_effort",
      "requestIdsFromStream": []
    },
    "returnedRows": 0,
    "truncatedByMaxItems": false
  },
  "warnings": [],
  "rows": [
    {
      "timestamp": "2026-06-04T13:14:11.001Z",
      "level": "INFO",
      "thread": "TWEventProcessor-1",
      "logger": "com.thingworx.things.agent.AgentLoop",
      "content": "Agent loop iteration 1/10 ...",
      "conversationMatch": true,
      "requestIdMatches": [],
      "raw": {}
    }
  ]
}
```

Row `timestamp` values are normalized from ThingWorx epoch milliseconds (or seconds), including numeric strings, to
ISO UTC Z using the same ms-vs-seconds threshold as `test_scripts/GetApplicationLog.py` (`normalize_timestamp`).

### `agent-message-stream.json`

```json
{
  "schema": "parler.liveDiagnostics.agentMessageStream",
  "schemaVersion": 1,
  "meta": {
    "collectionId": "20260604T131507Z-7f3a",
    "collectionStartedAt": "2026-06-04T13:15:07.123Z",
    "collectionFinishedAt": "2026-06-04T13:15:08.456Z",
    "window": {
      "duration": "1h",
      "startDate": "2026-06-04T12:15:07.123Z",
      "endDate": "2026-06-04T13:15:07.123Z"
    },
    "conversationId": "demo_conversationId",
    "service": {
      "thingName": "AgentMessageStream",
      "primaryService": "QueryStreamEntriesWithData",
      "fallbackService": null,
      "maxItems": 20000
    },
    "returnedRows": 0,
    "truncatedByMaxItems": false
  },
  "warnings": [],
  "rows": [
    {
      "timestamp": "2026-06-04T13:14:10.500Z",
      "conversationId": "demo_conversationId",
      "source": "demo_conversationId",
      "sourceType": "Thing",
      "streamEntryId": "abc123",
      "role": "assistant",
      "agentThing": "AIAgent",
      "requestId": "turn-request-id",
      "assistantMessageId": "msg-uuid",
      "content": "",
      "contentJson": null,
      "toolCalls": "[...]",
      "toolCallsJson": [],
      "llmUsageJson": "{...}",
      "llmUsage": {},
      "hostContextSnapshotJson": "{...}",
      "hostContextSnapshot": {},
      "values": {},
      "parseWarnings": [],
      "raw": {}
    }
  ]
}
```

Normalization:

- `timestamp` prefers stream entry metadata timestamp, then nested `values.timestamp`, normalized to ISO UTC Z (epoch
  ms/seconds or numeric strings). If no timestamp is present, the collector uses collection-time UTC (same as the prior
  v1 behavior).
- `conversationId` is stream entry `source` when available.
- **`content`**, **`toolCalls`**, **`llmUsageJson`**, and **`hostContextSnapshotJson`** on each normalized row are **verbatim** from the platform row
  (top-level fields, or the same fields under **`values`** when the platform only nests them there). This preserves
  internal training / workshop evidence (including parse failures, which still append **`parseWarnings`**).
- **`contentJson`**, **`toolCallsJson`**, **`llmUsage`**, and **`hostContextSnapshot`** are parsed companions when JSON parsing succeeds.
  **`toolCallsJson`** expands OpenAI-style JSON strings inside **`function.arguments`** / **`arguments`** into objects
  for readability. Companions (and **`agent-status.json`** parsed **`json`**) pass through **narrow** key-name redaction
  only (see Redaction).
- **`values`** is a defensive copy of nested **`AgentMessageData`**: when **`content`** / **`toolCalls`** /
  **`llmUsageJson`** are empty in **`values`** but present at the row top level, those top-level strings are copied in
  so nested mirrors stay aligned.
- **`raw`** is a shallow copy of the original platform row (same verbatim policy as the live stream query).

### `agent-status.json`

```json
{
  "schema": "parler.liveDiagnostics.agentStatus",
  "schemaVersion": 1,
  "meta": {
    "collectionId": "20260604T131507Z-7f3a",
    "collectionStartedAt": "2026-06-04T13:15:07.123Z",
    "collectionFinishedAt": "2026-06-04T13:15:08.456Z",
    "window": {
      "duration": "1h",
      "startDate": "2026-06-04T12:15:07.123Z",
      "endDate": "2026-06-04T13:15:07.123Z"
    },
    "conversationId": "demo_conversationId",
    "agentThingDiscovery": {
      "mode": "stream_rows",
      "agentThings": ["AIAgent"]
    }
  },
  "warnings": [],
  "agents": [
    {
      "agentThing": "AIAgent",
      "runtimeSnapshot": {
        "service": "GetAgentRuntimeSnapshot",
        "refresh": false,
        "ok": true,
        "json": {}
      },
      "configurationRepositoryValidation": {
        "service": "ValidateAgentConfigurationRepository",
        "ok": true,
        "json": {}
      },
      "taxonomyDiagnostics": {
        "service": "GetTaxonomyDiagnostics",
        "ok": true,
        "json": {}
      },
      "driftChecks": [],
      "capabilityGaps": [
        "runtime_snapshot_playbooks_unavailable",
        "runtime_snapshot_repository_file_inventory_unavailable"
      ]
    }
  ]
}
```

## Redaction

The default bundle targets **internal training / workshop support**: preserve prompts, tool arguments, tool results,
Thing names, taxonomy rows, log lines, and **LLM usage telemetry** (including `*Tokens` counters).

Deterministic safeguards:

- Never write `DEV_KEY`.
- When structured JSON is parsed (stream companions and **`agent-status.json`** service **`json`**), redact **only**
  object keys that match a small **exact** credential-name set (case-insensitive), for example: `apiKey`, `appKey`,
  `password`, `secret`, `authorization`, `access_token`, `refresh_token`, `id_token`, `client_secret`, `private_key`,
  `x-api-key`. Do **not** treat generic substrings such as `token` inside `promptTokens` / `completionTokens` as secrets.
- **`content`**, **`toolCalls`**, **`llmUsageJson`**, and **`raw`** stay **verbatim** from ThingWorx for stream rows;
  companions carry the narrow redaction above for accidental credential keys in structured JSON.
- Do not bulk-redact message bodies or business fields.

Treat bundles as sensitive operational data (they can still contain secrets inside opaque strings or parse failures).

## Failure Behavior

Exit codes:

| Code | Meaning |
|------|---------|
| `0` | Schema files written; one or more may contain warnings. |
| `2` | Missing `DEV_SERVER` or `DEV_KEY`. |
| `3` | Output directory cannot be created or written. |
| `4` | All platform queries failed. |

If one query succeeds and another fails, still write all schema files. Failed phases should write valid JSON with
`warnings[]`, empty rows/agents, and the short error details needed for support.

Stdout should stay short:

```text
Wrote logs/20260604131507
ApplicationLog rows: 312
AgentMessageStream rows: 18
Agent status snapshots: 1
```
