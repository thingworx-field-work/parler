# Tool Schema Admission Control

**Status:** Implemented. `toolAdmissionMode` (`off` | `narrow` | `lazy`; default `off`) on the
AgentThing controls which tool schemas are advertised to the model each round. Per-tool schema-size
telemetry (M1), `narrow` (M2) and `lazy` (M3) are implemented; there is no budget-aware `auto` mode.

---

## 0. Background

### 0.2 How tools reach the model, and where the budget is spent

Every agent turn builds an LLM request whose `tools` array carries one wire object per
model-facing tool:

```json
{ "name": "...", "description": "...", "input_schema": { ... } }
```

The agent assembles this set in `AgentThing.getMergedToolDefinitions()` from:

- **built-in tools** (`parler-agent/.../tools/BuiltInTools.java` and the document-knowledge
  tool builders), gated by agent runtime config (e.g. `documentKnowledgeBuiltinsEnabled`,
  `advertiseLegacyServiceDiscoveryTools`),
- **repository extended tools** harvested from the config repo
  (`/tools/extended_tools.json`; `ExtendedToolsManifest` / `CustomToolHarvester`) — these
  include the `utilization_*` family,
- **`start_playbook`** when playbooks are loaded.

The request is sized by **`ContextBudgetPlanner`** (`parler-agent/.../compaction/`). It
computes a per-request budget and logs `LLM_CONTEXT_PLAN` / `LLM_CONTEXT_PLAN_FAIL` with:
`stableChars` (system preamble), **`toolSchemaChars`** (the whole tools array),
`ephemeralChars` (per-round system rows), `activeBatchReserveChars`, `evidenceChars`,
`transcriptChars`, `configuredCapChars`, and **`effectiveRequestCapChars`** — the real
per-request input ceiling the provider/runtime allows. Tool schema is **fixed overhead for
the round**: unlike transcript/evidence, history compaction cannot reclaim it.

### 0.3 Existing tool-narrowing machinery (admission generalizes it)

`DocumentTurnToolNarrowing` (the "D2" gate) restricts the tool surface to the document tools
on document turns, controlled by the agent flag `documentTurnToolNarrowingDisabled`. Admission
control generalizes that idea from one hard-coded case to a configurable,
host-context-driven policy.

The provider Thing owns the **caps** — context window, output reserve, and therefore
`effectiveRequestCapChars`. The agentThing owns the **tool surface and its narrowing flags**
(§2.1).

### 0.4 Motivating failure

A Mashup card turn (prompt *"compare the health status of assets between USA and Germany"*) can
fail **before any history is admitted**:

```text
LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP
messages=28 tools=34 stableChars=31638 toolSchemaChars=68049 ephemeralChars=5242
currentUserChars=59 activeBatchReserveChars=13857 transcriptChars=0 evidenceChars=8372
configuredCapChars=750000 effectiveRequestCapChars=118622
historyBudgetChars=-223 historyClampedToZero=1
```

Non-history overhead alone (`31638 + 68049 + 5242 + 59 + 13857 = 118845`) exceeds the
**effective** cap (`118622`), so the planner throws rather than ship a doomed request. The per-tool
analysis is in **`docs/agent/all-tools.md`**. In that turn 34 tools were advertised and only
`query_alert_summary` fired; `toolSchemaChars = 68049` is the single largest controllable overhead
(top 10 tools ≈ 59%). This is not a conversation-history problem; **the fixed tool overhead is the
lever.**

---

## 1. Problem statement

A turn fails (or wastes budget/latency) because the agent advertises a **broad, mostly
idle** tool surface whose **fixed schema overhead** is large relative to the provider's
**effective per-request cap**. Two distinct effects:

1. **Per-request admission (`OVERHEAD_EXCEEDS_CAP`).** When fixed overhead exceeds
   `effectiveRequestCapChars`, the request cannot be built — independent of TPM, caching,
   or history. **Reducing the advertised tool schema fixes this unconditionally.**
2. **Rate-wait latency (`rateWaitMs`).** Smaller requests consume fewer tokens, easing
   rate-limit waits. The magnitude depends on the provider's token-accounting regime (for
   Claude-on-Foundry, prompt-cache reads are likely *excluded* from the input-tokens-per-
   minute limit, so the win concentrates on cold/cache-create/cache-miss rounds and
   output) — see `docs/agent/all-tools.md`. Reducing tokens never increases wait.

A budget-independent third benefit: advertising 33 irrelevant tools invites tool-selection
errors and distraction; narrowing to relevant tools tends to **improve correctness**, not
just budget — so this work has value even when the cap is generous.

---

## 2. Design — responsibility split + mode spectrum

### 2.1 Responsibility split (provider vs agentThing)

- **Provider Thing owns the *constraint facts*:** context window, output reserve →
  `effectiveRequestCapChars`. The provider does **not** own tool policy.
- **AgentThing owns the *policy*:** the admission `mode`, the always-on **core set**, and
  the **bucket → host-context/intent mapping**. This is consistent with the existing
  agent-level `documentTurnToolNarrowingDisabled` flag and with where the tool surface is
  already defined.

**No cross-Thing runtime coupling:** the modes that change behavior (`narrow`, `lazy`) are
**purely agentThing-side**; admission does not read the provider cap at runtime. Each knob is
single-owned: cap = provider; policy = agentThing.

### 2.2 The mode spectrum

| mode | behavior | round-trips |
|---|---|---|
| `off` | advertise all tools — **current behavior**; default; the A/B baseline and fallback | 0 |
| `narrow` | drop irrelevant buckets up front by host-context/intent; keep the core set | 0 |
| `lazy` | advertise the core set + a catalog (name + `whenToUse`) for the long tail; the model calls `load_tool_schemas(names[])`, which returns the schemas and **registers** those tools so the next round can call them natively | +1 (when the tail is needed) |

- `off` is the control/fallback (zero cost) and the default.
- `narrow` is the round-trip-free fix: it addresses `OVERHEAD_EXCEEDS_CAP` for the common case
  and reduces tokens with no latency risk; it is safe at any cap.
- `lazy` gives the most context headroom at the cost of an extra round-trip when the tail is
  needed.

`lazy` is **opt-in, never default**. Its cost is an extra round-trip that busts the warm
prompt-cache prefix (§4.2); on a generous cap it can be net-negative.

### 2.3 Bucket taxonomy (the narrowing unit; from `docs/agent/all-tools.md`)

| bucket | tools (illustrative) |
|---|---|
| Identity / routing | `resolve_thing`, `resolve_asset_type`, `list_asset_types`, `list_entities_by_type` |
| Entity set query | `query_entities`, `query_entities_by_taxonomy`, `analyze_entity_set` |
| Current values / trends | `get_property_values`, `query_property_history`, `query_stream_data`, chart/table tools |
| Alerts | `query_alert_summary`, `query_alert_history`, `acknowledge_alerts` |
| Metadata exploration | `describe_entity_schema`, `discover_thing_members`, `invoke_service` |
| Documents | `resolve_document_set`, `search_document_chunks`, `get_document_chunk` |
| Skills / playbooks | `get_agent_skill`, `start_playbook` |
| Utilization (extended) | `utilization_*` |

The **core set** (always advertised, never narrowed/deferred) is a small, configurable
list of high-frequency, low-cost routing tools. **Default core:**

- **Always core:** `resolve_thing` (and the identity/routing minimum). When taxonomy host
  context is present, `resolve_asset_type` / `list_asset_types` join the core.
- **Conditionally core when loaded:** `get_agent_skill`, `start_playbook` — small,
  route-entry points; included only when a skill/playbook is actually loaded.
- **Never core by default:** the heavy tools — `query_entities`, `tabulate_cached_result`,
  `invoke_service`, `describe_entity_schema`, `discover_thing_members`, the document tools,
  and the `utilization_*` extended family. (`query_entities` + `tabulate_cached_result` +
  `invoke_service` are ~16K chars combined — putting them in core would defeat the purpose.)

Conservative principle per `docs/agent/all-tools.md`: **do not hide a tool unless a
deterministic route signal (§2.4) says it is irrelevant for this turn.** `invoke_service` in
particular is too broad/expensive to be always-on, but it **must** be admitted when an
explicit service prompt, a host-context `requiredTools` declaration (§2.5), a playbook, or a
policy-backed extended workflow prompt asks for it.

### 2.4 Admission signals are deterministic — no LLM router

The `bucket → host-context/intent` mapping (§2.2 `narrow`) resolves from **auditable,
deterministic signals only**. There is **no** second LLM call to classify intent. Permitted signals, evaluated against a simple, testable rule table:

- host-context key / template key (the Mashup card's host-context registry key),
- structured host-context registry metadata (including `requiredTools` / `requiredBuckets`, §2.5),
- explicit slash command or playbook invocation (e.g. `/document_search`),
- loaded skill / playbook metadata,
- narrow lexical hints **only** when listed in the rule table (no free-form prompt classifier).

The rule table distinguishes an **advertise** decision (put the bucket's schemas on the
request) from an **auto-invoke** decision (the agent calls a tool itself) — admission only
governs *advertise*; it never auto-invokes (the server never calls tools based on user-message
keywords — `document-retrieval-stability.md` §2).

### 2.5 Host-context templates may require tools / buckets

A host-context prompt fragment can itself instruct the model to use a specific tool (e.g.
`invoke_service` against a wrapper service with JSON parameters) — common for embedded-widget
use cases. Admission **must not** drop a tool the host-context template references. The
host-context registry therefore carries a first-class declaration:

```json
{
  "key": "PTCTS.AssetMonitoring.ContainedAssetListParler_MU",
  "requiredTools": ["invoke_service"],
  "requiredBuckets": ["entity_set_query"]
}
```

`requiredTools` / `requiredBuckets` are unioned into the admitted set after bucket selection
and are never narrowed away.

### 2.6 Admission pipeline order (precedence)

The order, which keeps the interaction with the existing D2 document narrowing unambiguous:

```text
AgentThing.getMergedToolDefinitions()
  → tool admission (mode=narrow: bucket filter ∪ core ∪ requiredTools/requiredBuckets)
  → DocumentTurnToolNarrowing.filterForRound   (the existing D2 per-turn gate)
  → ContextBudgetPlanner                        (sizing / OVERHEAD_EXCEEDS_CAP enforcement)
```

Admission runs **before** D2 and the planner. D2 must not assume admission already removed a
tool, and admission must not assume D2's narrowing. `off` is a no-op pass-through that leaves
this pipeline byte-for-byte as it is today.

### 2.7 Concrete `narrow` rule table

This is the deterministic rule table the `narrow` engine (`ToolAdmissionPolicy`) implements. It is **code-level, auditable, and unit-tested**
(`ToolAdmissionPolicyTest`); the agentThing owns the `mode` knob (AgentSettings property), and
host-context `requiredTools`/`requiredBuckets` (§2.5) are the per-deployment override hooks.

**Buckets are partitioned into three classes:**

- **Operational base — always advertised in `narrow`** (the workhorses for operator Q&A):
  `IDENTITY_ROUTING`, `ENTITY_SET_QUERY`, `CURRENT_VALUES_TRENDS`, `ALERTS`. Plus `OTHER`
  (unknown / deployment-specific tools) — never hidden, per the conservative principle.
- **Gated — dropped unless a deterministic signal selects them:** `DOCUMENTS`, `UTILIZATION`,
  `METADATA_EXPLORATION`, `SKILLS_PLAYBOOKS`.
- **Core — always advertised regardless of bucket class:** `resolve_thing`; `+
  resolve_asset_type`/`list_asset_types` when taxonomy is loaded; `+ get_agent_skill` when
  skills are loaded; `+ start_playbook` when a playbook is loaded.

**Gating signals (deterministic; no LLM):**

| gated bucket | admitted when |
|---|---|
| `DOCUMENTS` | a slash command is active, **or** a document scope was injected this turn, **or** the host-context key contains `document`, **or** `requiredBuckets` includes it |
| `UTILIZATION` | the host-context key contains `utilization`, **or** `requiredBuckets` includes it |
| `METADATA_EXPLORATION` | `requiredBuckets` includes it (individual tools like `invoke_service` are admitted directly via `requiredTools`) |
| `SKILLS_PLAYBOOKS` | the entry point is loaded (handled by the core set), **or** `requiredBuckets` includes it |

**Force-admit (always win):** host-context `requiredTools` admit those exact tools; host-context
`requiredBuckets` admit those whole buckets — both regardless of the gating above (§2.5; covers
the embedded-widget `invoke_service` case).

**Signal sources** (read at round-filter time, no new plumbing): host-context key from the
uplink JSON (`AgentToolContext.getHostContextJson()` → `key` → `HostContextTemplateRegistry`),
slash from `snapshotSlashSkillShortNamesForPending()`, document scope from
`getInjectedDocumentScopeIds()`, skills from `ModelFacingSkillAdmission`, playbook from the
playbook snapshot, taxonomy from the application-semantic-taxonomy snapshot.

**Safety:** if narrowing would empty the tool list, the engine reverts to the full merged set
(logged `reverted=1`) — a turn is never left tool-less. Lexical host-key hints (`document`,
`utilization`) are the only string matching, and only against the **host-context key** (not
free-form prompt text), matching §2.4's "table-listed lexical hints only".

### 2.8 `lazy` mechanics

`lazy` is the most aggressive mode: the first request advertises **only** the core set (§2.3)
+ host-context `requiredTools`/`requiredBuckets` (§2.5), plus one meta-tool
**`load_tool_schemas(names[])`** whose description carries a **catalog** of the deferred tail
(each entry `name: whenToUse`). The model reads the catalog, calls `load_tool_schemas` with the
names it needs, and on the **next** round those tools are advertised with their full schemas
natively (+1 round-trip only when the tail is needed).

Implementation:

- **Selection** — `ToolAdmissionPolicy.lazy(merged, signals, registered)` (pure): advertise
  `core ∪ requiredTools ∪ requiredBuckets-tools ∪ registered`; everything else becomes a
  `CatalogEntry(name, blurb)`. `blurb` = the tool's description, trimmed to 160 chars.
- **Catalog cap** — `renderCatalog` caps the catalog at **`LAZY_CATALOG_MAX_CHARS = 8000`** and
  notes omissions, so `lazy` cannot itself trip `OVERHEAD_EXCEEDS_CAP` on a tight deployment.
- **The meta-tool** — `load_tool_schemas` is registered **executor-only**
  (`ToolRegistry.registerExecutorOnly`), so it is dispatchable but **never advertised in
  `off`/`narrow`**; the `lazy` round filter builds and appends its definition (with the per-turn
  catalog) only when there is a non-empty tail.
- **Registration across rounds** — `LoadToolSchemasExecutor` returns the requested tools'
  `input_schema`s as the tool result and records their names in `LazyToolRegistrationRegistry`,
  a **per-turn registry keyed by `resolveCurrentTurnKey()`** (mirroring the existing per-turn
  guards — correct even when tool execution runs off the agent-loop thread; cleared at turn end
  alongside the other guard registries). The `lazy` round filter unions the registered names
  into the advertised set each round.
- **Guard composition** — `load_tool_schemas` is not a document tool, so it does not touch the
  shipped saturation/coverage guards; distinct `names[]` produce distinct argument hashes, so
  the repetition guard does not false-trip (an identical repeated load is correctly blocked on
  the 3rd try). Admission still runs **before** D2 (§2.6); the meta-tool is just another
  advertised tool to the planner.
- **Default** — `lazy` is opt-in (`toolAdmissionMode=lazy`); never the default.

---

## 3. Components

- **M1 — Schema-size telemetry.** `LLM_TOOL_SCHEMA_USAGE` reports per-tool schema sizes through a
  shared `ToolSchemaSizer` (the single sizing path used by *both* the budget planner's total and
  this line): `toolSchemaChars` (= the planner's charge), `toolSchemaSizesSum`,
  `toolSchemaFramingChars` (total − sum; small, positive), and `toolSchemaSizes=name:chars,…`
  ordered largest-first, plus advertised tool count and `schemaCount` / `idleCount`. Unknown-name
  calls appear as `unknownCalls=…`. Other comparison fields live in sibling telemetry keyed by
  `parlerRequestId`: cached/uncached input tokens + round count (`LLM_USAGE`), `rateWaitMs` + wall
  time (`LLM_TURN_PERFORMANCE`), and `OVERHEAD_EXCEEDS_CAP` (`LLM_CONTEXT_PLAN_FAIL`).
  Diagnostic-only, no wire-contract change.
- **M2 — `narrow` mode.** AgentThing config `toolAdmissionMode` (off|narrow|lazy; default off). The
  per-turn candidate set = **core ∪ buckets selected by deterministic signals ∪ host-context
  `requiredTools`/`requiredBuckets`** (the §2.7 rule table), applied in the §2.6 pipeline position
  (admission → D2 → planner). Engine: `ToolBuckets` (name→bucket), `ToolAdmissionMode`,
  `ToolAdmissionSignals`, `ToolAdmissionPolicy` (pure), resolved + wired in `AgentThing`. **The
  admission decision is logged**, not just final counts:

  ```text
  TOOL_ADMISSION agent=... mode=narrow admittedBuckets=... droppedBuckets=...
  toolsBefore=N toolsAfter=M toolSchemaCharsBefore=X toolSchemaCharsAfter=Y droppedTools=...
  ```

  so a live debugger can see *why* a bucket disappeared. In `lazy` the line reports `toolsBefore`,
  `advertised`, `catalog`, `registered`, `toolSchemaCharsBefore/After`.
- **M3 — `lazy` mode.** Opt-in. Advertises core + host-context-required tools with full schemas,
  plus the `load_tool_schemas(names[])` meta-tool whose description carries a **size-capped
  catalog** (name + whenToUse) of the deferred tail (§2.8). No UI wire change.

### 3.1 Example: Mashup health-compare turn under `narrow`

For the Mashup health-compare class (*"compare the health status of assets between USA and
Germany"*):

| bucket | `narrow` decision | why |
|---|---|---|
| Identity / routing | **admit (core)** | always-on route minimum |
| Alerts | **admit** | the turn's actual work (`query_alert_summary`) |
| Entity set query | admit | operational compare over an asset set |
| Current values / trends | admit | health/status values |
| Metadata exploration | **drop** | not a schema-exploration turn |
| Documents | **drop** | not a document turn |
| Utilization (extended, 7 tools ≈ 9.7K) | **drop** | no utilization ask in prompt/host context |
| Skills / playbooks | drop unless loaded | route-entry only when present |

Dropping utilization alone (~9.7K) clears the §0.4 overflow; dropping utilization + documents +
metadata exploration adds margin.

---

## 4. Tests and operating guidance

### 4.1 Offline tests

- **M1:** `ToolSchemaSizerTest` asserts per-tool sizes reconcile to the planner total within array
  framing (exact for Chat Completions, small+positive for Anthropic);
  `LlmUsageTelemetryToolSchemaTest` asserts the line emits `toolSchemaChars` (= the sizer total),
  `toolSchemaSizesSum`, `toolSchemaFramingChars`, and a size-descending `toolSchemaSizes`.
- **M2:** `ToolAdmissionPolicyTest` asserts the health-compare turn drops
  utilization/documents/metadata while keeping the operational base + core; that
  `requiredTools`/`requiredBuckets` force-admit; that `OTHER` is never hidden; and the
  empty→revert safety. `ToolBucketsTest` / `ToolAdmissionModeTest` cover the taxonomy and parsing.
  `ToolAdmissionPlannerIncidentTest`: the full surface overflows a chosen effective cap
  (`historyBudgetChars < 0`, `historyClampedToZero=1`) and the post-narrow surface yields
  `historyBudgetChars > 0` at the **same** cap. `off` is a no-op pass-through.
- **M3:** `ToolAdmissionPolicyLazyTest` asserts the first round advertises only core (deferring the
  rest to the catalog), that `requiredTools`/`requiredBuckets` and already-`registered` names are
  advertised with full schemas, that the catalog is capped with an omission note, and that `lazy`
  advertises **strictly fewer** full schemas than `narrow`. `LoadToolSchemasExecutorTest` covers
  the envelope (known names → `input_schema` returned + registration; unknown → `not_found`) and
  `names[]` parsing. `LazyToolRegistrationRegistryTest` covers cross-round persistence, turn
  isolation, and cleanup.

### 4.2 Choosing a mode

Measured on a tight-cap deployment (fixed full-advertise overhead `toolSchemaChars=68049` across 34
tools; `effectiveRequestCapChars ≈ 103765`), with the same prompts under `off` and `lazy`:

| Health-compare turn | `off` | `lazy` |
|----|-------|--------|
| Tool schemas / round | 34 tools, **68049 chars** | 6–7 tools, **9548–11257 chars** |
| Outcome | **`OVERHEAD_EXCEEDS_CAP` at round 4 — no answer** | full answer (5 rounds) |

| Turn that already fits | `off` | `lazy` |
|----|-------|--------|
| Rounds / tool calls | 2 / 1 | 3 / 2 (one extra `load_tool_schemas` round) |
| Gross promptTokensTotal | 60399 | 47625 |
| Full-price tokens (input + cacheCreate) | ≈32700 | ≈34300 |
| Prompt-cache rebuilds | 1 | 2 |

- `lazy`'s value is **context headroom, not token savings**: it rescues turns whose fixed overhead
  approaches the cap, but does not reduce full-price token cost on turns that already fit.
- **Prompt-cache churn is the `lazy` cost:** every `load_tool_schemas` expansion mid-turn
  invalidates and rebuilds the cached prefix.
- **Deferral does not break tool access:** executors stay registered, so a call to a catalog-only
  tool still executes (logged as `unknownCalls=…`); admission only trims the advertised schema.
- Use `off` when the cap is generous; `narrow` for a round-trip-free reduction on tight caps; `lazy`
  when maximum headroom matters more than cache stability.

---

## 5. Out of scope

- A budget-aware `auto` mode, the agent reading the provider cap at runtime, or a provider-level
  cap floor.
- The provider deployment's `effectiveRequestCapChars` configuration (context window / output
  reserve) — an operations setting.
- Rewriting individual tool **schemas** to be smaller — admission narrows *which tools are
  advertised*, it does not re-author schemas.
- Making `lazy` the default.
- Changing executor-only behavior — executor-only tools (`discover_*`, `get_entity`,
  `get_service_definition`) already aren't model-facing and stay that way.
