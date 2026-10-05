# Playbook customer readiness

Status: **implemented** — runtime capability snapshot, structured validation, evidence contract,
empty/failure semantics, authoring helpers, Skill-to-Playbook converter, and eval / live-debug
support. Operator recipes: `live-diagnostics.md` Recipe 5 (Playbook diagnosis) and Recipe 5b
(Playbook readiness bundle).

## 0. Intent

The Playbook runtime supports broad first-party tool execution, generic row/list transforms,
chart artifact emission, directory packaging, input resolution, and the service-orchestration
primitives in `playbook-34-35.md`. Running a DAG is therefore not the main risk for customer
projects. The risk is the authoring and diagnosis loop:

```text
A Skill works.
An AI assistant generates a plausible Playbook.
The Playbook partly validates or partly runs.
The author cannot tell whether the issue is version, capability, JSON shape, tool
configuration, evidence compression, empty data, or final-answer grounding.
```

The capabilities in this document close that loop, so Playbooks behave predictably in customer
projects rather than only in demos.

## 1. Product stance

The Playbook JSON format is primarily an **AI authoring target**, not a format we expect
application developers to hand-author at large scale.

Students and app developers should learn:

- what a Playbook is;
- how a DAG differs from a Skill;
- what tools, nodes, evidence, gaps, and budgets mean;
- how to review a generated Playbook;
- how to provide better tools, Skills, and examples so AI can generate a better graph.

They should **not** be expected to manually write or maintain huge Playbook JSON files.
The intended authoring path is:

```text
App developer writes LLM-friendly tools.
App developer writes and tests a Skill.
AI assistant converts the Skill into a Playbook candidate.
Runtime validator checks it.
Live diagnostics explain failures.
Human reviews and installs the result.
```

This stance changes the priority of "debuggability". The goal is **not** a rich in-product
Playbook debugger UI; it is to make AI coding assistants and support assistants good at
live-debugging Playbooks from collected runtime evidence.

## 2. Scope boundary

Customer readiness covers:

1. Runtime capability snapshot.
2. Actionable validator and conversion reports.
3. Evidence compression contract.
4. Empty / failure / clarification semantics.
5. Authoring ergonomics for common deterministic transforms.
6. AI-assisted Skill-to-Playbook converter.
7. Eval and live-debug readiness for the generated Playbooks.

Out of scope:

- Full visual Playbook debugger / replay UI.
- JavaScript DAG factory.
- Provider service that dynamically generates `PlaybookJson`.
- Durable background Playbook runs, restart resume, scheduling, or cross-turn Playbook
  state.
- Reopening general model-facing tool count reduction as the default solution.

These do not block customer readiness as directly as AI-assisted authoring, evidence,
validator reporting, and live-debug signals.

## 3. Related source of truth

Related documents:

- `docs/agent/playbook-engine.md` — canonical Playbook model and shipped runtime catalog.
- `docs/agent/playbook-34-35.md` — service-orchestration primitives.
- `docs/agent/playbook-generic-ops-foundation.md` — row/list ops, caps, gaps, evidence
  discipline.
- `docs/agent/playbook-input-resolution.md` — resolver-first patterns.
- `docs/agent/collection-tool.md` — live evidence collection used during support.

### 3.1 Capability map

| Area | What exists |
| --- | --- |
| Packaged playbooks | `GetAgentRuntimeSnapshot` with `includePlaybooks: true` exposes `playbooks.loaded`, catalog ids, per-playbook `nodeCount`, `diagnostics[]`, and `playbooks.lastRun`; sibling **`playbookRuntime`** is the capability inventory (§5). |
| Validation | `PlaybookValidator` fail-closed with structured `issues[]`; **`ValidatePlaybookDocument`** and `ValidateAgentConfigurationRepository` (`playbookValidations[]`) return the §6.2 report with stable codes (§6.3.1). |
| Evidence | `PlaybookEvidenceFormatter` + per-node ledger in `PlaybookRunner`, budgets via `maxEvidenceBytes`; the result-family contract is §7. |
| Empty / partial runs | `needs_clarification`, `empty_rows_if_skipped`, optional branches, fan-out child gaps, `continueOnChildGap`; one support-facing matrix in §8. |
| Authoring helpers | Generic ops from `playbook-generic-ops-foundation.md`, orchestration ops from `playbook-34-35.md`, and the §9.5 helpers. |
| Converter | Skill + knowledge pack in `docs/agent/playbook-converter/` with an honest conversion report (§10). |
| Live debug | `parler-collect-live` captures `playbookRuntime`, structured document validations, and the last run outcome (§11). |

Capability lists and limits come from runtime sources of truth (for example `PlaybookValidator`
derive-op sets, `PlaybookToolDefinitionsMerge`, `PlaybookGenericOpsConstants`,
`PlaybookInfotableBindingPolicy`), not from duplicate catalogs in docs.

### 3.2 Code and doc map

| Area | Primary code / operator surfaces | Canonical docs |
| --- | --- | --- |
| Capability snapshot | `AgentThing.GetAgentRuntimeSnapshot`, `test_scripts/live_diagnostics/collect.py` | `docs/agent/configuration-repository.md`, `docs/agent/collection-tool.md`, this file §5 |
| Validation | `PlaybookValidator`, `ValidateAgentConfigurationRepository`, **`ValidatePlaybookDocument`** | `docs/agent/playbook-engine.md` (validation), this file §6 |
| Evidence + failure semantics | `PlaybookEvidenceFormatter`, `PlaybookRunner`, derive/tool envelope shapes | `docs/agent/playbook-generic-ops-foundation.md`, `docs/agent/playbook-engine.md` (evidence), this file §7–8 |
| Authoring helpers | `PlaybookValidator`, `PlaybookGenericDeriveOps`, tests + fixtures | `docs/agent/playbook-generic-ops-foundation.md`, this file §9 |
| Converter | `docs/agent/playbook-converter/` Skill + knowledge files | `docs/agent/playbook-engine.md` (authoring stance), this file §10 |
| Eval / live debug | `parler-collect-live`, eval fixtures under `parler-agent/src/test/resources/` | `docs/agent/collection-tool.md`, `docs/agent/live-diagnostics.md`, this file §11 |

**Wire / UI contracts:** AlwaysOn chat wire is unchanged. The JSON fields here live on
operator/diagnostic surfaces (`GetAgentRuntimeSnapshot`, validation services, collection
bundle schema).

## 4. Required outcome

A support agent can answer a playbook issue with a concrete diagnosis such as:

```text
This Playbook requires 0.1.191+ but the collected AgentThing is 0.1.186.
The runtime does not support `$infotable`, so the generated node cannot run.
Upgrade first; the JSON is otherwise structurally valid.
```

or:

```text
The Playbook loaded, but node `machine_uids` produced an empty row set.
The downstream app-service node was skipped by the optional-input branch.
The final answer is correct to report "no machine UID evidence".
Fix the source Skill/tool if that is not the desired behavior.
```

or:

```text
The generated Playbook is invalid because `group_by.keys` is a scalar string.
This runtime expects an array. Use `"keys": ["status"]`.
The validator reports this directly; no Java log spelunking is required.
```

That level of diagnosis is the acceptance bar.

## 5. Runtime capability snapshot

### 5.0 Snapshot sections

`GetAgentRuntimeSnapshot` supports `includePlaybooks` (see `AgentThing.buildAgentRuntimeSnapshotJson`).
When enabled, the snapshot exposes **loaded catalog metadata** under `playbooks`:

- `discoveryPattern`, `loaded`, `loadedAtUtc`, `catalogIds`, `documentIds`, `reservedSlashIds`
- `diagnostics[]` (backward-compatible string lines)
- `playbooks[]` rows with `id`, `path`, `nodeCount`
- `lastRun` — the last Playbook run outcome on this AgentThing (§8.2 run-level shape)

`parler-collect-live` requests `includePlaybooks: true`. The sibling **`playbookRuntime`** section
carries the effective execution catalog (derive ops, tools, bindings, limits) derived from
validator/runner classes — see §5.2.

### 5.1 Goal

Expose an AI-readable snapshot of what the current AgentThing can execute.

This is a runtime/version compatibility surface. AI-generated Playbooks must be checked
against the installed AgentThing, not only against the latest docs.

### 5.2 Data to include

Fields (illustrative — **live values come from runtime introspection**, not this example
list):

```json
{
  "playbookRuntime": {
    "agentVersion": "0.1.191",
    "schemaVersion": "parler-playbook-v1",
    "nodeKinds": ["tool_call", "derive", "fan_out", "condition", "llm_summary"],
    "deriveOps": [
      "project",
      "filter",
      "group_by",
      "normalize_resolved_things",
      "extract_from_tool_output",
      "build_nested_object",
      "json_stringify",
      "resolve_time_window_for_playbook",
      "empty_rows_if_skipped"
    ],
    "builtInTools": [
      { "name": "resolve_thing", "playbookSafe": true },
      { "name": "query_property_history", "playbookSafe": true }
    ],
    "extendedTools": [
      {
        "name": "alarm_events",
        "playbookSafe": true,
        "hitl": false,
        "executorOnly": false
      }
    ],
    "bindings": {
      "table": true,
      "infotable": true,
      "infotableForInvokeService": false
    },
    "limits": {
      "maxFanOutConcurrency": 1,
      "maxGenericInputRows": 10000,
      "maxGenericOutputRows": 10000,
      "maxGenericTargets": 200,
      "maxCollectGapsItems": 64,
      "maxPackagedPlaybooks": 32
    },
    "evidence": {
      "nodeEvidenceLines": true,
      "toolTableProjection": true,
      "rootScalarProjection": true,
      "artifactForwarding": true
    }
  }
}
```

Field rules:

- **`deriveOps`**: exact set from `PlaybookValidator` **`DERIVE_OPS`** (union of `V1A_DERIVE_OPS` +
  `V1B_DERIVE_OPS` + `GENERIC_DERIVE_OPS` — there is no separate orchestration constant;
  service-orchestration ops live in `GENERIC_DERIVE_OPS`).
- **`builtInTools` / `extendedTools`**: merged effective playbook-safe surface from
  `PlaybookToolDefinitionsMerge` (include `playbookSafe`, `hitl`, `executorOnly` on extended rows).
- **`bindings`**: from a package-visible accessor on `PlaybookInfotableBindingPolicy` so snapshot
  and validator share one source; nested `invoke_service.parameters.$infotable` is **false**
  (not supported).
- **`limits`**: mirror `PlaybookGenericOpsConstants`, `PlaybookIds.MAX_PACKAGED_PLAYBOOKS`, and
  fan-out concurrency (see introspection table below).
- **`agentVersion`**: same string as `agent.extensionVersion` (manifest-derived).
- **`schemaVersion`**: `PlaybookIds.SCHEMA_V1`.
- **`nodeKinds`**: `PlaybookValidator` node-kind allowlist.
- **`evidence`**: compile-time capability flags for formatter features in this extension
  version (single builder method; not hand-maintained in docs).

#### Introspection sources

Every `playbookRuntime` field is populated from a named runtime source, so the snapshot cannot
drift from the validator.

| Field | Runtime source |
| --- | --- |
| `deriveOps` | `PlaybookValidator` `DERIVE_OPS` via `supportedDeriveOps()` |
| `nodeKinds` | `PlaybookValidator` `NODE_KINDS` via `supportedNodeKinds()` |
| `schemaVersion` | `PlaybookIds.SCHEMA_V1` |
| `builtInTools` / `extendedTools` | `PlaybookToolDefinitionsMerge` + registry snapshot |
| `bindings.*` | `PlaybookInfotableBindingPolicy.bindingCapabilities()` |
| `limits.maxFanOutConcurrency` | `MAX_FAN_OUT_CONCURRENCY` constant shared with `PlaybookValidator.validateFanOut` |
| `limits.maxGeneric*` / `maxCollectGapsItems` | `PlaybookGenericOpsConstants` |
| `limits.maxPackagedPlaybooks` | `PlaybookIds.MAX_PACKAGED_PLAYBOOKS` |
| `evidence.*` | `PlaybookRuntimeEvidenceCapabilities` |

**Compatibility model:** `playbookRuntime` is an **inventory surface** (what ops/tools/bindings
exist on this AgentThing). It is **not** a substitute for validating a specific `playbook.json`.
**`ValidatePlaybookDocument`** (§6.5) is the authoritative per-document compatibility check.
Converters SHOULD use inventory for pre-checks and version warnings, then always validate the
candidate document before marking it runnable.

### 5.3 Where to expose it

**`playbookRuntime`** is emitted when `includePlaybooks: true` (no separate flag), alongside the
`playbooks` catalog section. **It is present even when zero playbooks are loaded or the catalog
is invalid** — capability diagnosis is most useful when authoring is broken.

- `parler-collect-live` captures `playbookRuntime` in `agent-status.json` (no `refresh: true`
  required for support bundles).
- The `playbooks` catalog shape is stable.

There is no UI surface; the consumers are AI live-debug and support assistants.

### 5.4 Guarantees

- Runtime snapshot includes the installed `parler-agent` version.
- `playbookRuntime` is emitted when `includePlaybooks: true` **even if** `playbooks.loaded` is
  false or `playbooks.diagnostics[]` reports errors.
- `playbookRuntime.deriveOps` and `bindings` prove whether service-orchestration features are
  available on the collected AgentThing.
- `playbookRuntime.extendedTools` lists effective Playbook-safe extended tools after repository
  merge (respecting `executorOnly` / HITL flags relevant to playbook admission).
- Collection tool captures the snapshot without refreshing the AgentThing.
- **CI invariant:** tests assert `playbookRuntime.deriveOps` equals
  `PlaybookValidator.supportedDeriveOps()` set-for-set, and tool rows match
  `PlaybookToolDefinitionsMerge` output for the test registry — adding an op to the validator
  without updating the snapshot builder fails CI.

## 6. Actionable validator and conversion reports

### 6.0 Surfaces

- **`PlaybookValidator.Result`**: `valid`, legacy `errors`, and structured **`issues[]`**
  (`code`, `path`, `nodeId`, `recoveryHint`) via `PlaybookValidationCollector`.
- **`ValidatePlaybookDocument`** operator service and **`playbookValidations[]`** on
  `ValidateAgentConfigurationRepository` (including `INVALID_JSON` / `PACKAGE_UNREADABLE`).
- **`playbookRuntime`** on `GetAgentRuntimeSnapshot` when `includePlaybooks: true`.
- Stable validation **`code`** catalog in §6.3.1 (normative for converter and support tools).

Document validation reporting is unified with registry diagnostics and recovery hints for
common authoring mistakes.

### 6.1 Goal

Validator failure must become usable feedback for AI-assisted authoring.

A technically correct but low-level validator error is not enough for a converter or support
assistant. The report shape below is returned by validation services, logged, and captured.

### 6.2 Error shape

`status` enum: **`valid`** | **`invalid`**. A document with only warnings and no errors is still
**`valid`** (warnings array may be non-empty). Invalid-request cases (both/neither input modes)
use **`invalid`** with a single `INVALID_REQUEST` error.

`ValidatePlaybookDocument` validates against the **running** agent runtime (built-in tools,
extended-tool registry when configured, and validator allowlists). Unsupported features for that
runtime are reported as **errors**, not warnings; `warnings[]` is typically empty.

Success example:

```json
{
  "status": "valid",
  "playbookId": "cross_asset_pair_health",
  "agentVersion": "0.1.191",
  "errors": [],
  "warnings": []
}
```

Invalid example:

```json
{
  "status": "invalid",
  "playbookId": "customer_alarm_events",
  "agentVersion": "0.1.191",
  "errors": [
    {
      "code": "UNSUPPORTED_DERIVE_OP",
      "severity": "error",
      "path": "nodes[7].op",
      "nodeId": "duration_stats",
      "message": "Derive op `window` is not supported by this runtime.",
      "recoveryHint": {
        "action": "replace_or_remove_node",
        "supportedAlternatives": ["add_computed_fields", "aggregate"]
      }
    }
  ],
  "warnings": []
}
```

### 6.3 Validator error classes

The validator reports:

- invalid JSON / missing root fields;
- unsupported schema version;
- unsupported node kind;
- unsupported derive op;
- unsupported binding (`$infotable`, `$table`, `$ref`, `$item`) or invalid placement;
- unknown node reference;
- invalid `finalNode`;
- multiple or non-final `llm_summary`;
- fan-out child shape invalid;
- fan-out concurrency greater than 1;
- tool not known;
- tool not Playbook-safe;
- extended tool not loaded;
- extended tool HITL conflict;
- unsupported `invoke_service` `$infotable` placement;
- missing `dataShapeName`;
- invalid argument type for common fields such as `group_by.keys`;
- budget / graph-size violation;
- evidence reference points to an unknown node.

### 6.3.1 Stable validation `code` catalog (normative)

Converters and support tools MUST key on these **`errors[].code`** values (not message text).
Adding a code is a documented change to this catalog.

| Code | When emitted | Typical `recoveryHint` |
| --- | --- | --- |
| `INVALID_REQUEST` | Both/neither `playbookJson` / `packageDirId`; repository unavailable for package read | — |
| `INVALID_JSON` | `playbook.json` parse failure | `action`: `fix_json_syntax` |
| `PACKAGE_UNREADABLE` | Repository load ≠ `CONTENT` for a discovered package | fields: `packageDirId`, `path`, `loadKind` |
| `UNSUPPORTED_SCHEMA_VERSION` | `schema` ≠ `parler-playbook-v1` | `expectedSchema` |
| `UNSUPPORTED_PROVIDER_FIELD` | Top-level `provider` on document or catalog entry | — |
| `UNSUPPORTED_NODE_KIND` | Unknown `nodes[].kind` | — |
| `UNSUPPORTED_DERIVE_OP` | Derive `op` not in runtime allowlist | `unsupportedOp`, `supportedAlternatives` |
| `INVALID_GROUP_BY_KEYS` | `group_by.args.keys` shape or element (scalar, non-string, dotted segment, `_other`, duplicate) | `fix_group_by_keys`, `example` |
| `INVALID_DEDUPE_KEYS` | `dedupe.args.keys` shape or element (scalar, non-string, blank, dotted segment, duplicate) | `fix_dedupe_keys`, `example` |
| `INVALID_MATCH_CANDIDATES_MODE` | `match_candidates.args.onZero` or `onMultiple` not in allowed enum | `fix_match_candidates_mode`, `allowedValues` |
| `INVALID_MATCH_CANDIDATES_FIELD` | `match_candidates.args.candidateField` not a single top-level segment | `fix_single_segment_field` |
| `INVALID_LIMIT_ROWS_MAX` | `limit_rows.args.maxRows` not an integer 0…`MAX_GENERIC_OUTPUT_ROWS` | `fix_limit_rows_max`, `maxAllowed` |
| `INVALID_FORMAT_EVIDENCE_MAX_LINES` | `format_evidence_lines.args.maxLines` not an integer 1…cap | `fix_format_evidence_max_lines`, `maxAllowed` |
| `INVALID_NORMALIZE_TEXT_MODE` | `normalize_text.args.mode` not in `trim\|lower\|collapse_ws\|identifier` | `fix_normalize_text_mode`, `allowedValues` |
| `UNKNOWN_TOOL` | `tool_call.tool` not in merged registry | `tool` (hint; `nodeId` is the call node) |
| `TOOL_NOT_PLAYBOOK_SAFE` | Known tool with `playbookSafe=false` | `tool` |
| `UNSUPPORTED_BINDING` | `$table` / `$infotable` / `$ref` / `$item` placement or shape invalid | `action` |
| `MISSING_DATASHAPE_NAME` | `$infotable` binding without `dataShapeName` (when not shadowed by binding placement error) | — |
| `UNKNOWN_NODE_REFERENCE` | `dependsOn`, `finalNode`, condition `then`/`else`, or `$ref` to missing node | — |
| `INVALID_FINAL_NODE` | Missing or unknown `finalNode` | — |
| `INVALID_LLM_SUMMARY` | Not exactly one `llm_summary`, or not final | — |
| `INVALID_FAN_OUT_CHILD` | `fan_out` child not `tool_call` or missing | — |
| `INVALID_FAN_OUT_CONCURRENCY` | `maxConcurrency` > 1 | `maxAllowed` |
| `BUDGET_VIOLATION` | Graph size, tool-call cap, generic row/group caps | — |
| `UNKNOWN_EVIDENCE_REF` | `llm_summary.evidenceRefs` points to unknown node | — |
| `PLAYBOOK_VALIDATION` | Residual validator message without a dedicated code (should trend to zero) | — |

**Implementation note:** some validator sites pass an explicit code through the
`col.node(..., code, ..., hint)` overload; others are classified from the validator message at
the collector boundary (`PlaybookValidationCollector.classifyCode`), with `PLAYBOOK_VALIDATION`
as the residual.

### 6.4 Conversion report

The converter emits a separate conversion report; it is not validator output. Baseline
sections (the full required schema is §10.6.3):

```json
{
  "sourceSkill": "asset_pair_health",
  "targetPlaybook": "asset_pair_health",
  "requiredAgentVersion": "0.1.191",
  "converted": [
    "resolve both assets",
    "query alert history",
    "group alert rows by sourceProperty"
  ],
  "partiallyConverted": [
    {
      "step": "compare trends statistically",
      "reason": "uses generic trend summary; no dedicated compare_series op"
    }
  ],
  "notConverted": [],
  "runtimeRequirements": [
    "query_property_history playbookSafe",
    "build_targets",
    "flatten_fan_out_rows"
  ],
  "authorActions": [
    "Install playbook at /playbooks/asset_pair_health/playbook.json",
    "RefreshPromptContextCache",
    "Run the suggested smoke prompt"
  ],
  "suggestedLiveTests": [
    "please compare the health status between ORD Contacting 02 and ORD Contacting 01 over the past 24 hours"
  ]
}
```

### 6.5 Emission surfaces

Structured validation is available from:

1. **`ValidateAgentConfigurationRepository`** — per-playbook items when a packaged
   `playbook.json` fails validation (extend existing item list; do not regress severity split
   between cap/warning vs parse/schema error).
2. **`ValidatePlaybookDocument`** — `AgentThing` operator service that accepts exactly one of:
   - raw `playbookJson` text, or
   - `packageDirId` (catalog id) resolving to `/playbooks/<id>/playbook.json` in the configured
     repository (read-only; does not mutate repository).

   Both or neither returns `INVALID_REQUEST`. The service runs the same `PlaybookValidator` +
   merged tool/extended-tool context as repository admission and returns the §6.2 structured
   report shape. It exists because the Skill-based converter has no local Java/CLI; staging a
   full repository package just to obtain structured validation output is not an acceptable
   authoring loop.

3. **Runtime logs** — single-line summary plus structured JSON blob at INFO for support
   (redact secrets).

Conversion reports (§6.4) remain **authoring-tool output**, not Java validator output.

### 6.6 Guarantees

- Validation produces stable machine-readable codes and JSON paths.
- Common authoring mistakes produce recovery hints (see §9.3 `group_by.keys` example).
- Reports are captured by `ValidateAgentConfigurationRepository` (`playbookValidations[]`) and
  `parler-collect-live` (`playbookDocumentValidations` + `playbookRuntime` in snapshot).
- The converter can include validator output in its final report without parsing log text.
- Tests: golden files for error classes in §6.3 (including unsupported derive op, invalid
  `group_by.keys`, unknown tool, `$infotable` placement, invalid `finalNode`).

## 7. Evidence compression contract

### 7.1 Goal

Final Playbook answers must be grounded in compact, predictable evidence. The generated
Playbook should not rely on raw JSON replay or accidental formatter behavior.

### 7.2 Node evidence baseline

Every executed node has a compact evidence record, even if the formatter only includes
selected pieces in `llm_summary`.

Baseline fields:

```json
{
  "nodeId": "alert_history_by_asset",
  "kind": "tool_call",
  "status": "ok",
  "label": "Read alert history for each asset",
  "tool": "query_alert_history",
  "rowCount": 40,
  "totalCount": 242,
  "sampleOnly": true,
  "cacheId": "cache-123",
  "chartRefs": [],
  "tableRefs": [],
  "gapCount": 1,
  "errorCode": null,
  "elapsedMs": 132,
  "evidenceLines": [
    "ORD Contacting 02 returned 20 sampled alert rows.",
    "ORD Contacting 01 returned 20 sampled alert rows."
  ]
}
```

### 7.3 Result-family projections

Each row below is the **compact evidence contract** for that result family. Projections feed `PlaybookEvidenceFormatter` (final
`llm_summary` prompt) and `PlaybookTaskProgress` / collection — not raw tool JSON.

| Result family | Primary mechanism | Compact fields in evidence / node JSON | Formatter behavior |
| --- | --- | --- | --- |
| Resolver (`resolve_thing` + `normalize_resolved_thing`) | Derive ops attach `evidenceLines`; ambiguous/not-found → `needs_clarification` | `status`, `message`, `output.candidates` (bounded), evidence lines | Lines verbatim; run stops on `needs_clarification` |
| Entity / taxonomy rows | `tool_call` + optional `evidence.table` | `rowCount`, `totalCount`, `sampleOnly`, projected columns | Table projection caps; root scalars via `includeToolOutputRootFields` |
| Alert summary / history | `tool_call` + `evidence.table` + root fields | `appliedStartTime`, `rowCount`, grouped columns | Root lines before table; empty rows → gap lines (§8.2) |
| Property values / history | `tool_call` + `evidence.table`; chart tools add artifact refs | `rowCount`, chart metadata roots (not series payloads) | `uiArtifact.chart` compact lines when configured |
| Cached table transforms | `$table` handoff; derive `project`/`filter`/… | `evidenceLines` from derive | Formatter reads explicit lines only |
| Extended-tool `$infotable` | Extended `tool_call` with `$infotable` binding | Projected INFOTABLE rows per `dataShapeName` | Same as tool table projection |
| Service orchestration (`build_nested_object`, `json_stringify`, `empty_rows_if_skipped`) | Derive attaches `evidenceLines`; optional branch via `ctx.skipNodes` | `output.object` or `output.rows`, `skipped` flag | Lines + structured output shell |
| `fan_out` | Formatter expands child `tool_call` evidence per node spec | Per-child `rowCount`, table projection, `gapCount` | Nested expansion under parent `nodeId` |
| Skipped optional branch | Condition `else` not taken → `skipNodes` | Source node absent; `empty_rows_if_skipped` emits `skipped:true`, zero rows | Explicit gap line in evidence |
| Clarification / gap / failed | Node `status` envelope (§8.2) | `status`, `message`, `errorCode`, `output.gaps[]` | Run may stop (`needs_clarification`/`failed`) or continue with gaps |

**Example — tool_call with table projection (alert history):**

```json
{
  "nodeId": "alert_history_by_asset",
  "kind": "tool_call",
  "status": "ok",
  "tool": "query_alert_history",
  "rowCount": 40,
  "totalCount": 242,
  "sampleOnly": true,
  "evidenceLines": [
    "query_alert_history: 40 of 242 rows (sampled).",
    "appliedStartTime=2026-06-19T00:00:00Z"
  ]
}
```

**Example — derive with explicit lines (generic `group_by`):**

```json
{
  "nodeId": "alerts_by_property",
  "kind": "derive",
  "status": "ok",
  "op": "group_by",
  "evidenceLines": [
    "group_by: 12 groups from 40 input rows (maxGroups=20)."
  ],
  "output": { "rows": [ { "sourceProperty": "Temperature", "count": 8 } ] }
}
```

**Example — resolver clarification (run stops):**

```json
{
  "nodeId": "resolve_asset_b",
  "kind": "derive",
  "status": "needs_clarification",
  "message": "Multiple things matched 'ORD Contacting'; specify EquipmentUID.",
  "output": {
    "row": null,
    "totalCount": 0,
    "returned": 0,
    "gaps": [],
    "candidates": [
      { "name": "ORD-Contacting-01", "EquipmentUID": 101 },
      { "name": "ORD-Contacting-02", "EquipmentUID": 102 }
    ]
  },
  "evidenceLines": [
    "normalize_resolved_thing: ambiguous resolve_thing result (2 candidates)."
  ]
}
```

**Example — fan_out parent summary line:**

```json
{
  "nodeId": "history_per_asset",
  "kind": "fan_out",
  "status": "ok",
  "gapCount": 1,
  "evidenceLines": [
    "fan_out: 2 children ok, 1 child gap (asset C not resolved)."
  ]
}
```

**Not part of the evidence contract:** validator admission; derive op catalog (§9); token
usage on slash turns (see `docs/agent/playbook-engine.md` §9 and
`docs/agent/training-stage-configuration-contracts.md` §7).

### 7.4 Evidence overflow

Do not solve overflow by dumping more JSON into the LLM. Use existing budgets:

- `maxEvidenceBytes`;
- per-node table/sample projection caps;
- deterministic gaps when evidence is truncated;
- final-answer instruction that limitations must be preserved.

**Invariant:** overflow handling is **op-configurable** (soft truncate vs hard fail), but
**every** truncation path MUST produce a deterministic visible outcome — a structured gap and/or
`evidenceLines` entry for soft truncation, or an explicit hard failure (`status: failed` /
`errorCode`) for fail-closed nodes. **Silent omission of material limitations is forbidden.**

### 7.5 Guarantees

- Every common node family has documented compact evidence semantics (§7.3).
- `llm_summary` can produce a grounded answer without raw tool payloads.
- Evidence truncation produces a visible gap, not silent data loss.
- Tests cover final evidence size and selected references for at least:
  - `cross_asset_pair_health` (`playbook-packaging-fixture/cross_asset_pair_health`);
  - `alarm_events` fixture (`playbook-34-35-fixture/alarm_events`);
  - `kpi_values` fixture (`playbook-34-35-fixture/kpi_values`).

## 8. Empty, failure, and clarification semantics

### 8.1 Goal

Playbooks behave consistently when evidence is missing or partial. AI converters rely on
these semantics so they do not guess whether to stop, continue, or ask the user.

### 8.2 Standard cases

Behavior (tested):

| Case | Default behavior | Notes |
| --- | --- | --- |
| no entity match | `needs_clarification` or gap by op config | Resolver-normalization ops already expose both patterns; document the default per op. |
| multiple entity matches | `needs_clarification` unless configured otherwise | Do not silently pick first. |
| empty alert/history rows | continue with explicit gap | Final answer should say no evidence in the window. |
| empty optional branch | skip and emit explicit empty rows | Use `empty_rows_if_skipped` pattern. |
| failed fan-out child | record child gap; continue only when op config allows | Required data should fail closed. |
| top N has fewer than N | continue with gap | Do not invent missing items. |
| required node failed | fail run with structured error | Include failed node id and code. |
| evidence too large | truncate / gap or fail according to evidence budget | Never silently omit material limitations. |

**Node result envelope:** every executed node stores a JSON object on the run
context. These fields are stable for collection and AI support:

```json
{
  "status": "ok",
  "message": "",
  "errorCode": null,
  "output": { },
  "evidenceLines": ["human-readable compact line(s)"],
  "evidenceText": "optional joined form; formatter prefers evidenceLines"
}
```

| `status` | Run effect | When |
| --- | --- | --- |
| `ok` | Continue | Normal completion; may include `output.gaps[]` for soft gaps |
| `needs_clarification` | **Stop** run → `NEEDS_CLARIFICATION` | Resolver ambiguity, `pick_one`/`build_nested_object`/`resolve_time_window` config |
| `failed` | **Stop** run → `FAILED` | Required node error; includes `errorCode` |

#### Gap object contract

Structured gap **objects** in `output.gaps[]` use **`code`** (stable machine id) + **`message`**
(human-readable text) as the external contract, emitted at **source** — there is no
collection-layer translation shim. Discriminators such as `reason`, `kind`, or `type` in gap
objects are **not** part of the contract.

| Entry kind | Shape | Notes |
| --- | --- | --- |
| Structured gap object | `{ "code": "<STABLE_CODE>", "message": "…" }` | Required pair; optional context keys below |
| String gap entry | `"human-readable truncation or note"` | Allowed in `gaps[]` at source; collection export **MUST** wrap as `{code:"GAP_NOTE", message:…}` |
| Optional context | `input`, `region`, `candidates`, `arrayPath`, `sourceNodeId`, `childIndex`, `detail` | Preserved when already emitted; not required for support diagnosis |

Gap sources:

| Source | Gap discriminator |
| --- | --- |
| `PlaybookResolveThingEnvelope.gapEntry` | `code` |
| `PlaybookOrchestrationDeriveOps.extractGap` / `gapJson` | `code` |
| `PlaybookTimeWindowDeriveOps` gap rows | `code` |
| `PlaybookDeriveOps` region gaps | `code` |
| `PlaybookGenericCollectGaps` structured input | `code` (plus `message`; `kind` is not an allowed structured key) |
| Generic derive string gaps (`group_by`, `aggregate`, …) | plain string, wrapped as `GAP_NOTE` on export |

**Soft gap (continue) — empty alert window:**

```json
{
  "status": "ok",
  "output": {
    "rows": [],
    "totalCount": 0,
    "gaps": [
      {
        "code": "NO_ROWS_IN_WINDOW",
        "message": "query_alert_history returned 0 rows for the requested window."
      }
    ]
  },
  "evidenceLines": [
    "No alert history rows in window; final answer must state absence of evidence."
  ]
}
```

**Optional branch skipped (`empty_rows_if_skipped`):**

```json
{
  "status": "ok",
  "output": {
    "skipped": true,
    "rows": [],
    "sourceNodeId": "product_filter_branch"
  },
  "evidenceLines": [
    "Optional product filter branch skipped; downstream uses empty rows."
  ]
}
```

**Fan-out child gap (parent continues when `continueOnChildGap: true`):**

```json
{
  "status": "ok",
  "output": {
    "childResults": [
      { "childIndex": 0, "status": "ok", "rowCount": 20 },
      { "childIndex": 1, "status": "gap", "gap": { "code": "RESOLVE_FAILED", "message": "Asset C not found." } }
    ]
  },
  "evidenceLines": [
    "fan_out: 1 of 2 children ok; 1 gap recorded."
  ]
}
```

Fan-out nodes accept optional **`continueOnChildGap`** (boolean, default **`false`**). When **`false`**, a failed child fails the parent (fail-closed). When **`true`**, child failures are recorded as **`status: "gap"`** entries in **`output.childResults[]`** and the parent continues.

**Required node failure:**

```json
{
  "status": "failed",
  "message": "Extended tool alarm_events returned HTTP 500.",
  "errorCode": "EXTENDED_TOOL_FAILED",
  "evidenceLines": []
}
```

**Run-level outcome** (slash / `start_playbook` result; exported as `playbooks.lastRun` on the
snapshot and `lastPlaybookRun` in the collection bundle):

```json
{
  "playbookId": "cross_asset_pair_health",
  "runId": "…",
  "status": "completed",
  "failureCode": null,
  "nodeCount": 9,
  "toolCallCount": 4,
  "llmCallCount": 1,
  "elapsedMs": 8200
}
```

`status` is one of `completed` | `needs_clarification` | `failed` | `cancelled`. Collection and
`parler-collect-live` surface the run `status`, terminal `message`, and per-node
`evidenceLines` / gaps sufficient for an AI support agent to explain continue vs stop (§8.3).

### 8.3 Guarantees

- Each standard case has a stable status / gap / clarification shape.
- Runtime logs and reports identify the branch chosen.
- Collection output lets an AI support agent explain why the Playbook continued or
  stopped.

## 9. Authoring ergonomics

### 9.1 Goal

Reduce the number of times an AI converter must invent awkward graph fragments for common
deterministic work.

This is not arbitrary scripting; it is a small set of bounded helpers for recurring
customer-style Playbooks.

### 9.2 Helper ops

| Op | Purpose |
| --- | --- |
| `normalize_text` | normalize case, whitespace, punctuation for identifier comparison |
| `match_candidates` | generic exact / normalized candidate matching with ambiguity reporting |
| `dedupe` | stable dedupe by key fields |
| `limit_rows` | keep bounded samples while preserving total count and gap |
| `format_evidence_lines` | deterministic evidence-line templates over rows/aggregates |

Not available as derive ops: `rank_with_ties`, `compare_series`, `template_summary`, `pivot`,
`window`, `subplaybook`, `provider_hook`.

### 9.3 `group_by` ergonomics

Scalar or malformed `group_by.keys` → validator error with `recoveryHint`
(`"keys must be an array; use [\"status\"]"`). **No silent normalization:** the runtime does
not accept vague malformed structures.

### 9.4 Guarantees

- Each shipped helper has validator coverage, runtime tests, evidence behavior, and docs.
- Helpers are generic enough for multiple customer-style workflows.
- Unsupported helpers produce actionable report entries rather than vague validation
  failure.

### 9.5 Helper op arguments

| Op | Args (required bold) | Output highlights |
| --- | --- | --- |
| `normalize_text` | **`text`**, `mode` (`trim` \| `lower` \| `collapse_ws` \| `identifier`) | `output.text`, `output.original` |
| `match_candidates` | **`needle`**, **`rows`**, `candidateField`, `normalize`, `onZero`, `onMultiple` | `output.row` or clarify/gap |
| `dedupe` | **`rows`**, **`keys`** (string array) | `output.rows`, `logicalCount`, string gaps when dupes removed |
| `limit_rows` | **`rows`**, **`maxRows`** | `totalCount` = logical input, `returned` = clipped |
| `format_evidence_lines` | **`template`** (`{field}` tokens), **`rows`** xor **`object`**, `maxLines` | `output.lines` + `evidenceLines` |

## 10. AI-assisted Skill-to-Playbook converter

### 10.1 Goal

The converter is a Skill plus knowledge files (`docs/agent/playbook-converter/`), not a Java
service.

The converter's job is not to guarantee perfection. Its job is to create a useful
candidate and an honest report.

### 10.2 Inputs

Input bundle:

- source `SKILL.md`;
- optional example prompts;
- optional known-good tool path from logs/stream;
- runtime capability snapshot;
- current playbook docs / op catalog;
- optional existing Playbook to revise.

### 10.3 Outputs

Outputs:

- packaged `/playbooks/<id>/playbook.json`;
- conversion report;
- minimum live-test prompts;
- expected node/tool path;
- known gaps and author actions;
- required `parler-agent` version.

### 10.4 Converter rules

- Prefer generated Playbooks that validate on the provided runtime snapshot.
- If the runtime is older than required, emit a version warning before generating
  unsupported nodes.
- Preserve the Skill's evidence rules; do not only preserve its tool order.
- Do not invent app services or extended tools.
- Do not mark a Playbook as runnable unless it passes validation or the report clearly
  labels it as a draft.
- For app-service workflows, prefer extended tools with `playbookSafe: true`; use
  `invoke_service` only when policy and argument shape make it appropriate.

### 10.5 Expected results

- The converter produces a candidate for `asset_pair_health`.
- It produces or honestly rejects a utilization-style Skill.
- It uses the service-orchestration primitives when the runtime supports them.
- Different AI assistants produce broadly similar candidates from the same Skill and
  snapshot.
- Reports are good enough for AI live-debug without reading Java source.

### 10.6 Converter specification

#### 10.6.1 Delivery shape

- A **Skill** plus **knowledge files** (markdown / JSON templates the Skill reads); no Java
  operator service.
- The Skill orchestrates: read inputs → check runtime snapshot → draft `playbook.json` → call
  **`ValidatePlaybookDocument`** (or include validator output from a prior call) → emit §6.4
  conversion report + author actions.
- Authors stage output under `/playbooks/<id>/playbook.json` in the configuration repository
  themselves; the converter does not mutate the repository.
- Golden examples: `docs/agent/playbook-converter/examples/` (`asset_pair_health` golden
  `playbook.json` + report, `region_health` honest partial report, `version_warning`
  version-mismatch report), validated by `PlaybookConverterFixtureTest`.

#### 10.6.2 Reference Skills

| Skill id | Role | Honest-report expectation |
| --- | --- | --- |
| `asset_pair_health` | **Primary golden** — produces a validator-clean candidate | `converted` covers resolve + alert/history path; `partiallyConverted` only for true gaps (e.g. trend compare without `compare_series`) |
| `region_health` | Secondary — regional aggregate workflow | May be `partiallyConverted` where fan-out / region derive differs from Skill prose |
| `utilization_summary` | Secondary — utilization-style extended-tool path | May land in `notConverted` or `partiallyConverted` when extended tools are not `playbookSafe` on target runtime |
| `bad_skill` (optional negative) | Adversarial / malformed source | Report must label **draft** and list validator failures — not pretend runnable |

Example Skill roots (user-maintained): `dev_data/scpa_utilization/skills/<id>/SKILL.md`.

#### 10.6.3 Conversion report schema (normative)

§6.4 is the baseline. **Required top-level fields:**

| Field | Required | Semantics |
| --- | --- | --- |
| `schema` | yes | `"parler-playbook-conversion-report-v1"` |
| `sourceSkill` | yes | Skill id |
| `targetPlaybook` | yes | Playbook `id` / package dir |
| `requiredAgentVersion` | yes | Minimum **`parler-agent` version the generated Playbook requires** (from derive ops, bindings, and tool surface used — e.g. `$infotable` or the service-orchestration ops raise it). **Not** an echo of `playbookRuntime.agentVersion` from the snapshot. |
| `runnable` | yes | `true` only when embedded validation `status` is `valid`; else `false` |
| `validation` | yes | Full §6.2 structured report from `ValidatePlaybookDocument` (or equivalent) |
| `converted` | yes | string[] — Skill steps fully represented |
| `partiallyConverted` | yes | `{step, reason}[]` — honest partials |
| `notConverted` | yes | `{step, reason}[]` — explicit non-runnable steps |
| `deriveOpsUsed` | yes | Every distinct derive `op` on `kind: derive` nodes in the generated `playbook.json` (includes §9.5 helpers **and** other runtime derive ops such as `match_identifier_in_rows`, `group_by`, service-orchestration ops) |
| `runtimeRequirements` | yes | tools / extended tools / snapshot fields required |
| `authorActions` | yes | install path, cache refresh, smoke prompt; **plus** version-mismatch note when snapshot `agentVersion` < `requiredAgentVersion` |
| `suggestedLiveTests` | yes | at least one prompt per golden Skill |

**Overlap note (`match_candidates` vs `match_identifier_in_rows`):** when the Skill step is
taxonomy-bound identifier resolution, prefer **`match_identifier_in_rows`** (or existing
orchestration ops). Use **`match_candidates`** only for generic row needle match with
`onZero` / `onMultiple` semantics. The report MUST list both ops in **`deriveOpsUsed`** when
used so reviewers can audit the identifier-selection path.

Example (complete required fields):

```json
{
  "schema": "parler-playbook-conversion-report-v1",
  "sourceSkill": "asset_pair_health",
  "targetPlaybook": "asset_pair_health",
  "requiredAgentVersion": "0.1.191",
  "runnable": true,
  "validation": {
    "status": "valid",
    "playbookId": "asset_pair_health",
    "agentVersion": "0.1.191",
    "errors": [],
    "warnings": []
  },
  "deriveOpsUsed": ["normalize_text", "match_identifier_in_rows", "dedupe", "format_evidence_lines"],
  "converted": ["resolve asset type", "resolve both assets", "read alert history"],
  "partiallyConverted": [],
  "notConverted": [],
  "runtimeRequirements": [
    "query_property_history playbookSafe",
    "resolve_asset_type",
    "match_identifier_in_rows"
  ],
  "authorActions": [
    "Install playbook at /playbooks/asset_pair_health/playbook.json",
    "RefreshPromptContextCache",
    "Run the suggested smoke prompt"
  ],
  "suggestedLiveTests": [
    "please compare the health status between ORD Contacting 02 and ORD Contacting 01 over the past 24 hours"
  ]
}
```

#### 10.6.4 Converter rules (additions)

- MUST call **`GetAgentRuntimeSnapshot`** with `includePlaybooks: true` before drafting; MUST
  not emit derive ops absent from `playbookRuntime.deriveOps`.
- MUST key validation remediation on §6.3.1 `errors[].code` — not message text.
- MUST NOT mark `runnable: true` when `validation.status` ≠ `valid`.
- Version warning: when snapshot `playbookRuntime.agentVersion` (from the converter's
  pre-draft snapshot read) is **less than** `requiredAgentVersion` (the minimum version the
  generated Playbook needs), the report MUST include an `authorActions` entry explaining the
  mismatch before suggesting install.

## 11. Eval and live-debug readiness

### 11.1 Goal

Make generated Playbooks testable before customer use.

This is not a CI gate; it is a repeatable authoring-time evaluation pack and diagnostics
workflow. The eval pack for the golden Playbook is
`docs/agent/playbook-eval-pack/cross_asset_pair_health.md`; the diagnosis workflow is
`live-diagnostics.md` Recipe 5 and the readiness bundle Recipe 5b.

### 11.2 Eval pack

For each converted Playbook, keep:

- source Skill id;
- source prompts;
- generated Playbook id;
- expected input extraction;
- expected node/tool path;
- expected chart/table artifacts where applicable;
- expected final-answer evidence features;
- expected gaps / limitations.

### 11.3 Live-debug collection requirements

`parler-collect-live` captures enough to let a support AI diagnose:

- installed agent/widget versions;
- runtime capability snapshot;
- loaded Playbook catalog and validation errors;
- raw current repository `playbook.json` files when available;
- loaded-vs-current drift checks;
- node-level run summary or failure report;
- final evidence pack summary, or enough logs to reconstruct it;
- tool artifacts emitted from Playbook internal tool calls.

### 11.4 Diagnosis coverage

- An issue about a failed Playbook can be diagnosed from the collection bundle
  plus docs.
- The assistant can distinguish:
  - version mismatch (`playbookRuntime.agentVersion` vs playbook `requiredAgentVersion` when present);
  - stale repository file (loaded vs current fingerprint drift);
  - validator failure (**structured validation report** for loaded/current playbook in the bundle — not only logs or raw `playbook.json`);
  - runtime node failure (runner status / logs);
  - missing evidence (formatter truncation / gaps);
  - model final-answer issue (stream `llm_summary` vs evidence pack).
- The golden Playbook has a documented eval pack (`cross_asset_pair_health`).

## 12. Non-goals

Customer readiness does not include:

- a visual DAG editor;
- arbitrary JavaScript execution inside Playbooks;
- a scheduler;
- cross-turn durable workflow state;
- a full planner;
- a second runtime;
- broad model-facing tool count reduction.

The customer-readiness goal is narrower:

```text
AI can generate a Playbook.
Runtime can validate it.
Collection can explain it.
Final answers can trust compact evidence.
Humans can review and ship it without hand-writing huge JSON.
```
