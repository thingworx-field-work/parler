# Playbook built-in capability expansion

Status: **implemented** — built-in tools are Playbook-capable through `ToolDefinition.playbookSafe`, with the
explicit exceptions in §6.3.

## 1. Background

Parler Playbooks have this product shape:

- repository-backed Playbook catalog,
- static `PlaybookJson`,
- DAG validation,
- `tool_call`, `fan_out`, `derive`, `condition`, and final `llm_summary` nodes,
- task progress frames,
- compact evidence handoff,
- existing V1a/V1b examples such as `cross_region_health` and
  `cross_asset_pair_health`.

The first Playbook runtime allowed only four built-in tools in `tool_call` nodes
(`query_entities_by_taxonomy`, `query_alert_summary`, `get_property_values`,
`query_property_history`). That was too narrow for a general workflow runtime: when a working
Skill is converted into a Playbook, the workflow often needs tools the normal chat loop already
uses successfully.

The training example is `asset_pair_health`:

```text
Skill route:
resolve assets
query alert history over the past 24h
query current alert summary
select alert-driving properties
query property history
produce ranked health assessment
```

A Playbook route that cannot use `query_alert_history` has to approximate durable alert
history with `query_alert_summary`, which changes the workflow meaning. Built-in capability
expansion removes that class of mismatch. It moves Playbooks from:

```text
Playbook as vertical-slice demo
```

to:

```text
Playbook as an extensible workflow runtime
```

Built-in capability expansion gives Playbooks enough tool surface to preserve Skill
semantics; the generic ops in `docs/agent/playbook-generic-ops-foundation.md` make those
workflows easier to express without business-specific Java derive operations.

Related context:

- `docs/agent/playbook-engine.md`
- `docs/agent/playbook-engine-v1a-tool-allowlist.md`
- `docs/agent/legacy-discovery-executor-only.md`
- `docs/agent/tool-result-egress-control.md`
- `docs/agent/time-interpretation.md`
- `docs/agent/task-state.md`

## 2. Delivery boundary

The boundary is deliberately finite: a usable Playbook authoring path matters more than
open-ended risk scope.

Playbook validation is a structural and capability validator. It is not a new policy
engine.

Rules:

- Playbook tool calls must go through the same normal tool executors used by chat tool
  calls.
- Those executors keep using the visibility / permission-aware ThingWorx APIs already
  selected by Parler.
- Tool-specific parameters such as `limit`, `maxRows`, and `maxItems` are not rejected
  merely because a generic Playbook validator thinks they are large.
- Runtime budgets remain the applicable controls: `timeoutSeconds`, `maxNodes`,
  `maxToolCalls`, `maxEvidenceBytes`, fan-out `maxItems`, and the existing provider TPM /
  rate-control path.
- There is no Playbook-specific HITL model, approval language, or write-operation policy.

The validator answers:

```text
Can this Playbook graph be executed by this runtime with declared capabilities?
```

It does not try to answer:

```text
Should every possible customer allow this operation in every business context?
```

That second question belongs to existing tool executors, existing policies, operator
configuration, and customer resource choices.

## 3. Goal

Built-in tools are Playbook-capable by default unless a concrete, documented exception
exists (§6.3).

This means:

- **`ToolDefinition.playbookSafe` on merged definitions is the normative Playbook gate** (what
  `PlaybookValidator` enforces).
- **`PlaybookToolAllowlist.TOOL_NAMES` is a compatibility / diagnostics anchor** only: it must
  match the playbook-safe name set from `PlaybookToolDefinitionsMerge.merge(defaultBuiltIns, missingExt)`
  (see `PlaybookValidatorTest.playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns`). Do not hand-maintain a divergent second policy.
- The Playbook validator accepts static Playbooks that use normal built-ins.
- Static and converter-generated Playbooks can preserve Skill workflows more faithfully.
- Exceptions are explicit, not implicit "not in allowlist" leftovers.

## 4. Non-goals

Built-in capability expansion does not:

- add generic ops such as `group_by`, `top_n`, or `build_targets` (see `playbook-generic-ops-foundation.md`);
- build the Skill-to-Playbook converter (see `playbook-converter/README.md`);
- redesign Playbook persistence or restart resume;
- redesign task-state wire shape;
- create a new policy model for writes or HITL;
- remove existing executor-only or legacy tool behavior;
- make all tool result evidence perfect.

It adds only the minimal evidence projection needed to make newly capable tools usable in
Playbook final summaries (§9).

## 5. Capability gate

The four-tool list blocked three things: resolver-first Playbooks (forcing candidate listing
and custom matching instead of the identity tools used by chat), time-window alert workflows
(`query_alert_history`), and reuse of metadata, cached-table, search, and service tools. The
gate is now the `playbookSafe` flag on each built-in `ToolDefinition`.

### 5.1 Implementation anchors (Java)

Paths below are under `parler-agent/`.

| Concern | Where |
| --- | --- |
| Built-in `ToolDefinition` + `playbookSafe` flags | `src/main/java/com/thingworx/things/agent/tools/BuiltInTools.java` |
| Static Playbook tool name anchor (must match merged `playbookSafe` set) | `src/main/java/com/thingworx/things/agent/playbook/PlaybookToolAllowlist.java` |
| Graph validation (`tool_call`, unknown tool, `playbookSafe=false`) | `src/main/java/com/thingworx/things/agent/playbook/PlaybookValidator.java` |
| Merged tool definitions + executor-alias shadow defs for validation | `src/main/java/com/thingworx/things/agent/playbook/PlaybookToolDefinitionsMerge.java` |
| Extended / repository-declared tools | `src/main/java/com/thingworx/things/agent/configrepo/ExtendedToolsManifest.java` — `playbookSafe` comes from the configuration repository; merged definitions must not disagree on eligibility for the same logical tool. |

**Tests:** `PlaybookValidatorTest.playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns` asserts
`PlaybookToolAllowlist.TOOL_NAMES` equals the playbook-safe names from the default merged registry
(including executor aliases).

## 6. Target capability matrix

The matrix is pinned in code by `PlaybookToolAllowlist.TOOL_NAMES` (test-checked against the
`playbookSafe` flags) and documented here.

Decision values:

- `playbook-capable`: validator accepts the tool in `tool_call` nodes.
- `deferred`: intentionally not enabled; reason given.
- `excluded`: not a valid Playbook node target; reason given.

### 6.1 Playbook-capable built-ins

| Tool | Why it is Playbook-capable |
| --- | --- |
| `resolve_asset_type` | Playbooks need the same asset-type normalization as chat. |
| `resolve_thing` | Playbooks need canonical Thing names without custom candidate matching for every workflow. |
| `list_asset_types` | Useful clarification and discovery support for typed workflows. |
| `query_entities_by_taxonomy` | Taxonomy-bound entity listing. |
| `query_entities` | General entity-set workflows need the same query surface as chat. |
| `list_entities_by_type` | Bounded discovery/list workflows need it. |
| `spotlight_search` | Useful lightweight search path for operator-driven workflows. |
| `get_property_values` | Current property values. |
| `query_property_history` | Property history (also reachable through the aliases in §6.1a). |
| `query_stream_data` | Stream/table workflows should not require chat-only execution. |
| `query_alert_summary` | Current alert state. |
| `query_alert_history` | Required to preserve time-window alert semantics in Playbooks. |
| `acknowledge_alerts` | Product stance: this built-in does not require HITL. Playbook should not invent a stricter rule. |
| `discover_thing_members` | Canonical concrete-Thing metadata discovery. |
| `describe_entity_schema` | Canonical schema/entity description surface. |
| `fetch_cached_result` | Read cached evidence produced by prior tools. |
| `tabulate_cached_result` | Deterministic table transform, central to analysis Playbooks. |
| `summarize_cached_result` | Deterministic table summary, central to analysis Playbooks. |
| `analyze_cached_result` | Deterministic series analysis over a cached result. |
| `analyze_entity_set` | Entity-set analysis workflows should be expressible in Playbook form. |
| `build_chart_from_tabular_result` | Chart-producing workflows should be able to reuse cached/table evidence. |
| `build_history_overlay_chart` | History overlay charts in Playbook workflows. |
| `invoke_service` | Validator accepts the tool when `playbookSafe=true`. Runtime uses the same `invoke_service` path as chat, including allow-policy **bypass** gating for HITL enqueue. When the dispatcher would raise **`ApprovalPendingException`**, `PlaybookRunner` fails the node with **`errorCode=PLAYBOOK_HITL_REQUIRED`** (explicit, not `playbook_internal_error`). Playbooks have no AlwaysOn-style pause/resume. |

### 6.1a Executor aliases (property history)

`query_numeric_property_history` and `query_value_stream_property_history` are **executor aliases**
for `query_property_history` (`BuiltInTools.registerAll`). They have no row in `ToolRegistry#getAllDefinitions()`,
so `PlaybookToolDefinitionsMerge` appends **synthetic** `ToolDefinition` copies (same schema, `playbookSafe=true`)
when the canonical tool is playbook-capable. Static Playbooks and replay rows may reference either the canonical
name or an alias without `unknown tool` drift.

### 6.2 Compatibility / legacy metadata built-ins

The legacy discovery names are **not** Playbook-capable. Static Playbooks use only the canonical names:

| Legacy tool | Canonical path |
| --- | --- |
| `discover_properties` | `discover_thing_members(facet="properties")` |
| `discover_services` | `discover_thing_members(facet="services")` for Things; `describe_entity_schema` for schema entities |
| `get_service_definition` | `discover_thing_members(facet="service")` for Things; schema description or app-specific wrappers otherwise |

### 6.3 Deferred or excluded built-ins

Exceptions are explicit:

| Tool | Decision | Reason |
| --- | --- | --- |
| `start_playbook` | excluded | A Playbook node does not start another Playbook. **`start_playbook` is not present in `ToolRegistry#getAllDefinitions()`**, so validation fails with **`unknown tool`** if referenced. Any registered definition must keep **`playbookSafe=false`**. |
| `get_agent_skill` | excluded | Skill loading is prompt/authoring support, not a deterministic business-data node. |
| `get_entity` | deferred | Full entity JSON is intentionally a legacy/high-volume surface. Use `describe_entity_schema` and `discover_thing_members`. |
| `set_property_value` | deferred | `AgentThing` always routes through HITL enqueue before any write; `PlaybookRunner` has no `ApprovalPendingException` continuation contract with `AgentLoop`. Stays **`playbookSafe=false`** because Playbooks have no HITL pause/resume. |

## 7. HITL and write/service tools

Sensitive built-ins run through the **same** `AgentThing` dispatch stack as chat. There is no
Playbook-specific HITL policy language, approval UX, or Playbook-local policy override.

### 7.0 Playbook vs chat: `ApprovalPendingException`

`AgentThing.dispatchExecuteToolCallWithoutRepetitionGuard` throws **`ApprovalPendingException`**
when a tool must pause for Parler HITL (`invoke_service` without bypass, `set_property_value`,
non-bypass extended tools). `AgentLoop` maps that to **`AWAITING_APPROVAL`**. **`PlaybookRunner`**
does not implement loop continuation, and it does **not** flatten those throws into a generic
`playbook_internal_error`.

**Side effect:** `ParlerHitlStreamScopedEnqueue` (called from `AgentThing.tryEnqueueParlerHitlPending`)
**always** calls `PendingApprovalStore.put(rec)` **before** throwing `ApprovalPendingException` when Parler AlwaysOn
context is present (`parlerRequestId`, active messages). A naive catch that only maps the
exception to `PLAYBOOK_HITL_REQUIRED` would leave a **live pending record** with no `AWAITING_APPROVAL`
downlink — incoherent audit state and a risk that a later approval executes the gated tool **after**
the Playbook already reported failure. **`PlaybookRunner`** therefore calls **`PendingApprovalStore.remove(pendingId)`**
immediately when converting the exception to a terminal failed tool node, rolling back the enqueue
that the static Playbook path cannot honor.

**Resolution:**

- **`invoke_service`:** `playbookSafe=true` so realistic app Playbooks validate. When dispatch
  would enqueue HITL, `PlaybookRunner` catches **`ApprovalPendingException`** at the tool node
  and returns **`status=failed`** with **`errorCode=PLAYBOOK_HITL_REQUIRED`** (includes `pendingId`).
  Bypass-allowed invocations (existing allow policy) still run synchronously like chat.
- **`set_property_value`:** remains **`playbookSafe=false`** and **deferred** (§6.3): every path
  hits unconditional HITL enqueue in `AgentThing`; there is no synchronous bypass-only subset to
  expose safely in static Playbooks without continuation.

### 7.1 `acknowledge_alerts`

Product stance: `acknowledge_alerts` does not require HITL.

Implementation:

- Playbook-capable.
- Executes through the normal built-in executor, with normal argument validation and result shape.
- Covered by Playbook validator tests.

### 7.2 `invoke_service`

See §7.0 (including **`PendingApprovalStore.remove`** on terminal `PLAYBOOK_HITL_REQUIRED`).
Validator + `playbookSafe=true`; runtime preserves allow policy and surfaces
`PLAYBOOK_HITL_REQUIRED` instead of swallowing `ApprovalPendingException` as an internal error.

Tests cover:

- validator accepts a Playbook containing `invoke_service`;
- the runner routes through the normal tool executor for bypass-eligible calls;
- a forced `ApprovalPendingException` path yields **`PLAYBOOK_HITL_REQUIRED`** on the tool node
  and **`PendingApprovalStore` holds no record** for that `pendingId` afterward
  (`PlaybookRunnerHitlRequiredToolCallTest`).

### 7.3 `set_property_value`

Deferred per §6.3 / §7.0: `playbookSafe=false`, because there is no Playbook HITL continuation.
Chat executor semantics are unchanged.

## 8. Validator responsibilities

Playbook validation checks:

- graph structure,
- known node kinds,
- known derive ops,
- known tools,
- tool is Playbook-capable,
- references resolve,
- budgets and node/fan-out limits exist and are structurally valid,
- `llm_summary.evidenceRefs` reference existing nodes.

It does not:

- reject a tool argument because a generic number appears large;
- infer broad input risk categories;
- duplicate existing tool executor validation;
- duplicate existing invoke-service policy;
- duplicate existing HITL behavior.

## 9. Evidence expectations

Playbook-capable tools must be usable enough for `llm_summary`:

- Tool node evidence records include tool name, status, row/count/cache/chart/table refs
  when applicable.
- Existing compact result formats remain visible to `PlaybookEvidenceFormatter`.
- Tools do not force raw, large JSON into final evidence.
- `evidence.includeToolOutputRootFields` surfaces scalar tool JSON **root** keys (e.g.
  `query_alert_history` `appliedStartTime` / `rowCount`) before `evidence.table` row projection
  (`docs/agent/playbook-engine.md` §4.2). The root fields also render when **`rowCount`** is **0**
  (empty **`rows`**) or when **`includeToolOutputRootFields`** is used **without** **`evidence.table`**,
  so the applied window is not lost on no-alert outcomes.

Per-tool evidence:

- `query_alert_history`: include applied time window, row counts, sourceProperty grouping
  where available.
- `query_entities` / `list_entities_by_type`: include total/returned counts, cache/table
  refs where available.
- cached-result tools: include transform mode, output counts, answer completeness, cache
  refs.
- `invoke_service`: preserve existing compact/oversize envelopes and avoid special
  Playbook-only rewrites.

## 10. Reference Playbook

The reference `cross_asset_pair_health` Playbook (`dev_data/sample_scpa_utilization_agent_configuration/playbooks/`) uses
`query_alert_history` for durable alert history and keeps the current-summary node as separate
current-state evidence. CI also pins a minimal static Playbook,
`parler-agent/src/test/resources/playbook-builtin-capability-expansion/minimal_alert_history.playbook.json`,
and asserts it validates with merged built-ins (`PlaybookAlertHistoryFixtureValidationTest`).

## 11. Tests

```text
PlaybookValidatorTest.playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns
PlaybookValidatorTest.executorAliasQueryNumericPropertyHistory_passesValidationWithMergedDefs
PlaybookRunnerHitlRequiredToolCallTest
PlaybookAlertHistoryFixtureValidationTest
PlaybookRunnerAlertHistoryEvidenceTest
PlaybookEvidenceFormatterTest
PlaybookRunnerStreamHitlRollbackTest
ParlerHitlStreamScopedEnqueueTest
```

Related Playbook tests:

- `PlaybookValidatorTest`
- `PlaybookReferenceParityTest`
- `PlaybookAlwaysOnSlashWireTest`
- `PlaybookTaskProgressTest`
- `PlaybookTerminalHandoffTest`
