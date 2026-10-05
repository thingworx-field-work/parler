# Context compaction advanced — budgets, fixed overhead, and the LLM call ledger

This document covers how the Parler Java agent (`parler-agent/`) sizes each LLM request, how it
compacts replayed history, how the fixed per-call overhead (stable system prompt and tool
definitions) is kept small and stable, and how every built-in LLM call is recorded in the
`AgentLlmCallStream` ledger and reported by the `AgentLlmUsageHelper`.

Canonical companions for shipped behavior:

| Topic | Document |
|---|---|
| Request budgeting and replay compaction | [`../context-compaction.md`](../context-compaction.md) |
| Conversation checkpoints | [`../../core/advanced-compact.md`](../../core/advanced-compact.md) |
| Anthropic cache breakpoints | [`../anthropic-breakpoint.md`](../anthropic-breakpoint.md) |
| Stable prompt assembly and cache | [`../system-prompt-cache.md`](../system-prompt-cache.md) |
| ConfigurationRepository layout | [`../configuration-repository.md`](../configuration-repository.md) |
| Routing-guide text and description ownership | [`../LLM_CONTEXT.md`](../LLM_CONTEXT.md) |
| Message-stream `llmUsageJson` (not the call ledger) | [`../llm-usage-stream-telemetry.md`](../llm-usage-stream-telemetry.md) |

Identifiers used by code and tests: **CC-1** fixed-overhead rules (CC-1.1–CC-1.4 tool
descriptions, CC-1.6 external stable system prompt), **CC-7** the LLM call ledger and usage
helper, **CC-8** the long multi-turn replay fixtures, **§11** the multi-turn validation driver
and suites.

Java paths below are relative to `parler-agent/src/main/java/com/thingworx/things/agent/` unless
stated otherwise.

## 1. Goals and boundaries

Parler serves industrial data insight: a user asks several questions in one conversation, and
each turn fetches data, aggregates, charts and compares. Every model call carries a large fixed
payload (stable system prompt plus tool schemas), and each turn adds tool evidence that is larger
than the user and assistant text.

The mechanisms here serve three goals:

1. **Cost and limits**: keep the fixed tokens per call low and the prompt-cache prefix stable.
2. **Answer correctness**: never silently lose the basis for a follow-up; grade compaction
   products by provenance (§4); never clear or rewrite the text of a final assistant answer, and
   never delete persisted user-visible history.
3. **Observability**: every observable LLM call has an identity and an outcome, missing usage is
   visible, and usage statistics come only from the call ledger (CC-7).

Boundaries:

- Bounded request copies and eviction of the oldest conversation pairs from the in-memory list
  are the existing last-resort mechanisms; they are allowed.
- No in-process TTL dataset registry replaces the FileRepository artifact cache.
- Tool admission is not controlled by prompt text.
- The call ledger is a record of observed calls and usage, not a billing system: no charging, no
  vendor bill synchronization, no background polling of providers.

## 2. Current request assembly and compaction

### 2.1 Conversation storage and per-turn assembly

- The authoritative in-memory list is `AgentThing._conversations`
  (`ConcurrentHashMap<String, List<ChatMessage>>`), including assistant tool-call rows and every
  tool-result row. Rows persist to `AgentMessageStream` and `AgentThreadDataTable`, with a
  per-row cap of `LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP = 500_000`.
- After a restart, rehydration is capped at `DEFAULT_MAX_REHYDRATE_MESSAGES = 300` and
  `DEFAULT_MAX_REHYDRATE_CHARS = 200_000` (`ConversationRehydrateSettings`). It restores user
  and final assistant text, not raw tool-call/tool-result pairs. Accepted compact tool evidence
  (compact `fetch_cached_result`, numeric history, value-stream history;
  `CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence`) is restored as assistant prose
  with a historical-source prefix and a liveness label (`AgentConversationRehydrator`).
- Each turn (`AgentThing.buildLlmTurnContext`) takes the existing list or rehydrates it, replaces
  message[0] with the stable system row from `LeadingStablePromptComposer.assemble` (or the
  external file, CC-1.6), appends the turn's framed SYSTEM rows (slash skill, host-scope
  observation) and one user message. For each round, `AgentLoop` appends and then removes (in
  `finally`) the task-state row, the UTC time row, an optional document-coverage instruction and
  an optional empty-final-answer recovery instruction. Volatile rows sit in the tail suffix so the
  cache prefix is not disturbed.
- One agent loop per turn, `maxIterations` default 10. LLM calls originate from four places: main
  loop rounds, checkpoint generation, Playbook `llm_summary`, and the Provider `healthCheck`
  probe.

### 2.2 Per-round request budget

`ContextBudgetPlanner.planForProviderRound` computes for each round:

```
effectiveRequestCapChars = min(llmContextMaxChars,
                               providerModelInputTokenLimit × 3.5,   # only when the registry knows the model
                               providerRequestCapChars)              # only when local rate control supplies it
historyBudgetChars = effectiveRequestCapChars − stableChars − toolSchemaChars − ephemeralChars
                     − currentUserChars − activeBatchReserveChars − checkpointChars
```

- `llmContextMaxChars` (AgentSettings) defaults to **750,000 characters** (not tokens), clamped
  to 10,000–2,000,000.
- `providerModelInputTokenLimit` comes from the static prefix map in
  `ProviderModelInputLimitRegistry`: `claude-3-7-sonnet`, `claude-3-5-haiku`, `claude-opus-4`,
  `claude-sonnet-4`, `claude-haiku-4` → 200,000; `gpt-4.1` → 1,047,576; `gpt-5` → 400,000;
  `gpt-4o`, `gpt-4-turbo` → 128,000; any other name has no limit and does not take part in the
  minimum. Prefixes do not distinguish generations, and Azure deployment names match only if they
  happen to start with a known prefix.
- `providerRequestCapChars` is `RateControlConfig.contextPlanningInputCapChars`, present only when
  `rateControlMode=enforce` and the effective single-request cap is positive. The effective cap is
  `maxSingleRequestTokens` when positive, otherwise `tokensPerMinuteLimit` when positive. With
  `m = max(1.0, estimateSafetyMultiplier)` (Provider default 1.15): subtract a 1,024-token
  margin; when `tokenReserveStrategy` is `input_plus_requested_output` (the alternative is
  `input_only`) also subtract `ceil(max(0, resolvedMaxOutputTokens) × m)`; divide by `m`
  (floor) and multiply by 3.5 to get characters. An exhausted input reserve yields 1 character.
- A negative history budget sets `historyClampedToZero=1`. When the fixed overhead alone exceeds
  the cap the planner throws `ContextBudgetExceededException`
  (`LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP`). Trimming is drop-only: it removes the
  oldest conversation and evidence rows from the request copy and never modifies the stored list.
- `LLM_CONTEXT_PLAN` (built by `finishPlanned`) logs the post-trim `transcriptChars`, the
  pre-trim `evidenceRawChars`, `droppedTranscript` / `droppedEvidence` (rows) and
  `droppedAssistantBatches`. Once trimming has happened the log line cannot reconstruct the
  pre-trim size; only the pre-trim `Metrics.compute` holds it.
- The effective cap can be far smaller than the configured cap when rate control is enforced.

### 2.3 Tool evidence lanes and compaction tiers

| Tier | Mechanism | Trigger and limits |
|---|---|---|
| Egress | `ToolResultEgressGateway.compactForLlmAppend` for every executed tool result in `AgentLoop` | `ARRAY_SAMPLE_LIMIT = 20`, `LLM_EVIDENCE_CHARS_SOFT_CAP = 8_192`, text excerpt ladder 2,000 / 512 / 6,000 characters. The soft cap is a shrink-and-fallback rule, not a hard ceiling. Raw JSON is kept as a sidecar. Synthetic skipped siblings on the fault path bypass the gateway. |
| Tier A / Tier 0 | `LlmToolResultMatrixSealer`, `LlmToolResultCohortMerger` | When a tool batch completes, consecutive TOOL bodies are rewritten as `parler.infotable.matrix.v1` or a cohort bundle, logging `rawReplayChars` / `replayChars` / `compactRatio`. |
| Tier B | `LlmToolResultTierBPromoter` | After the turn, historic tool bodies before the last original USER row are demoted to `parler.infotable.summary.v1` / `parler.entity.metadata.summary.v1`. Accepts only matrix.v1, cohort bundles and promotable entity metadata. Without a `cacheId` the summary keeps column statistics (numeric min/max/mean, …) as aggregate evidence; with a `cacheId` it keeps only the column schema as a refetch pointer. `insightEnvelope` and `completeness` are not copied. Runs **only when the pre-normalization list exceeds `llmContextMaxChars`**, so in-budget turns stay byte-stable. `summary.v1` exists only in memory and is never written back to the Stream. |
| Checkpoint | `ConversationCheckpointGenerator`, `ConversationCompactionBoundarySelector`, `ConversationCheckpointInstaller` | When the storage trim would delete conversation rows, one extra model call produces `{goal, constraints, progress, decisions, nextSteps, criticalContext}` that replaces the covered prefix. `MAX_SUMMARY_INPUT_CHARS = 100_000`, 2,048 output tokens, temperature 0; failure falls back to plain trimming. The checkpoint is injected with assistant provenance, never promoted to system or user; semantic fields cannot prove numbers, device state, completeness, authorization or error class. Rules: [`advanced-compact.md`](../../core/advanced-compact.md). |
| Storage trim | `ConversationsStorageBudgetTrimmer` | Drops the oldest historic assistant tool batches, then the oldest USER+ASSISTANT pairs, until `sumMessageChars ≤ llmContextMaxChars`. |

`ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore` runs the post-turn steps
in order: pressure-gated Tier B → optional checkpoint → storage trim. All three are skipped while
a HITL approval is pending, and all three use `llmContextMaxChars`, not the request-side effective
cap. The JVM property `com.thingworx.parler.llmReplayCompaction.disableUnsafe` turns compaction
off globally.

`ContextBudgetPlanner.providerCarriesInjectedCheckpoint` returns true only for
`openai-chat-completions-*` and `azure-openai-chat-completions-*` API shapes. Other shapes neither
count nor send the checkpoint (the Anthropic Messages API requires the first message to be a user
message, and the injected checkpoint would be a leading assistant message). Generation and
installation do not depend on the provider.

Consequence: request-side trimming (drop-only, effective cap) and storage-side semantic compaction
(Tier B and checkpoint, `llmContextMaxChars`) use different thresholds. A request copy can
already be dropping history while the stored list has not yet reached Tier B or checkpoint.

### 2.4 Fixed payload

- The default stable system row is `AgentSettings.systemPrompt` + the bundled
  `llm_tool_routing_guide.txt` (cross-tool routing and evidence-interpretation rules only; see
  [`LLM_CONTEXT.md`](../LLM_CONTEXT.md)) + `llm_replay_format_routing_guide.txt` + the taxonomy
  block (`taxonomyPromptInjection`: `full_table` default, `resolver_guidance_only`, `none`) +
  GenericThing template names + `GetAlertPrompt` + the workflow catalog + stable time guidance +
  the final-answer evidence rule. The routing guide is appended only when
  `_appendBuiltInToolRoutingGuide=true`.
- Tool definitions are advertised every round. The largest are `tabulate_cached_result`,
  `query_entities`, `build_chart_from_tabular_result`, `invoke_service`,
  `query_entities_by_taxonomy`, `query_property_history` and `build_history_overlay_chart`.
- `toolAdmissionMode=lazy` advertises full schemas only for the core set (`resolve_thing` plus
  conditional taxonomy/skill/playbook entry points), tools required by the host context, and tools
  already loaded in this turn, together with the `load_tool_schemas` meta-tool and a deferred
  catalog. The loaded set is tracked per turn (`LazyToolRegistrationRegistry`) and each turn
  starts from the catalog face again. Each load changes the tool array.

### 2.5 Prompt-cache prefix

- Anthropic (`AnthropicMessagesApi`): `cache_control:{type:"ephemeral"}` on the stable system
  block (only when it is the stable first row), on the last tool of the tool array, and on the two
  newest API user frontiers (a real USER text block or the last block of a tool_result batch);
  at most four breakpoints. When a new frontier is appended, the previous third candidate loses
  its marker. Top-level automatic caching is not used because the tail suffix is deliberately
  volatile. `cache_read_input_tokens` and `cache_creation_input_tokens` are read back. Details:
  [`anthropic-breakpoint.md`](../anthropic-breakpoint.md).
- OpenAI / Azure use automatic prefix caching. `ChatCompletionsApiMessages` keeps message[0] as
  the system row and moves every framed volatile row to the tail;
  `usage.prompt_tokens_details.cached_tokens` is parsed.
- On Anthropic, adding, removing or reordering tools invalidates the tools, system and messages
  layers; changing only `tool_choice` invalidates the messages layer; changing message content
  affects only what follows it. Restoring the original tool array within the TTL can hit the old
  prefix again.
- Known invalidation points: restricted rounds change the tool array (§2.6); Tier B and the
  checkpoint rewrite history bytes.
- Provider usage is a per-request total. There are no per-layer fields, so breakpoint placement
  can only be asserted on the locally serialized request.

### 2.6 Restricted rounds

- `AgentLoop` sends an empty tool list for `emptyFinalAnswerRetryRound || summaryToolNoneRound`,
  and `postMarkerToolDefinitions` for `postMarkerRound || chartRescueRound`: normally empty, or a
  single `build_chart_from_tabular_result` when chart rescue or the Answer Presentation Phase
  applies. These rounds do not send the full schema, so their prefix differs from the main loop.
- Apart from the presentation-phase chart limit (`PRESENTATION_ACTION_LIMIT = 6`), the execution
  loop calls `toolExecutor.execute` directly; dispatch goes through the general path (duplicate
  call guard, `start_playbook`, extension tools or the built-in registry). General permissions and
  HITL apply; there is no phase-level execution allowlist. The empty-tool protection
  (`tool_call_after_tool_none` protocol violation and a synthesized answer) keys off
  `defsForRound.isEmpty()`.
- Empty final answer: retried exactly once; the retry round sets the no-tool policy
  (`toolChoiceNone=true` and an empty tool list). When `tools` is empty, both wire serializers
  omit `tools` **and** `tool_choice`.

### 2.7 Chart tool lanes

On success, `BuildChartFromTabularResultExecutor` places the full `chartBlock` (series `x`/`y`
arrays) in the tool result and sends it to the UI through
`AgentToolContext.addPendingParlerChartBlock`. The LLM lane receives the egress-compacted result;
the persisted body, the sidecar (retrievable through `FetchCachedStreamLaneHelper`) and the
`AgentMessageStreamHistoryExporter` history export read the full `chartBlock`. Chart-rescue
eligibility depends on per-turn `AgentToolContext` state, not on `chartBlock` in model history.

### 2.8 Model capability sources

- The static registry in §2.2 is the only model-limit input to the planner.
- `ProviderEligibilityView` carries `contextWindowTokens`; `ProviderRouteEligibility` checks it
  when the `CONTEXT_WINDOW` capability is present and returns `context_window_insufficient` or
  `capability_missing`. `LlmApiProviderDirectory` reports `contextWindowTokens=0`. The planner
  does not read this field.
- Each round resolves wire ids and the single-request cap from that round's `model`
  (`ProviderLlmClientBridge`, `modelOverride` or `defaultModel`). Provider-level retry and fallback
  (`ProviderRouteCoordinator`, `ProviderRetryBackoff`, `ProviderRetryBudget`,
  `ProviderFallbackPlan`) are not called from the agent loop; the rate gate only admits, waits and
  records 429s.

### 2.9 Diagnostic logs and rate gates

- `LlmUsageTelemetry` writes `LLM_USAGE` lines (`model`, `apiShapeId`, `messages`, `tools`,
  `input`, `prompt`, `cacheRead`, `cacheCreate`, `cachedPrompt`, `output`, `reasoningTokens`,
  provider `requestId`, turn `parlerRequestId`, optional `rounds`, and the most recent compaction
  batch's `rawReplayChars` / `replayChars` / `compactRatio`). Join key:
  `LLM_USAGE.parlerRequestId = LLM_CONTEXT_PLAN.requestId`. `LLM_TOOL_SCHEMA_USAGE` records
  `schemaTools`, `calledTools`, `idleTools`. Assistant rows persist summed usage in
  `llmUsageJson`. These are diagnostics; **usage and cost statistics come only from the call
  ledger (CC-7)**.
- `LLMAPIProviderRateGate` estimates `ceil((32 + per-row content and role chars) / 3.5)` times
  the safety multiplier and reserves all of it per request. OpenAI TPM counts cached input
  tokens; for most Anthropic models only `input_tokens` and `cache_creation_input_tokens` count
  toward ITPM. Cache hits therefore raise effective Anthropic throughput but not OpenAI or local
  gate throughput.

### 2.10 Constraints the ledger design follows

- `ProviderLlmClientBridge.chat` wraps Provider identity, parameters and the rate gate. The HTTP
  boundary is `OpenAiChatCompletionsClient`, `AzureOpenAILlmClient` and
  `AnthropicMessagesLlmClient`; each reads the whole response body and then parses it (no
  provider SSE; AlwaysOn streaming to the UI is separate).
- The Chat Completions response parser extracts a few fields after validating `choices` and turns
  missing counts into 0; Anthropic parsing extracts only totals. Complete usage must therefore be
  captured independently, before status/content parsing.
- `ConversationCheckpointGenerator` calls the turn's `LlmClient.chat` directly, and
  `StreamTokenUsage.combine` sums Playbook calls. Neither provides per-call records, so the
  recorder sits on the shared call boundary.
- Cancellation is a boolean flag in `ParlerRunningTurnCancelRegistry` observed at existing
  `AgentLoop` checkpoints. It stores no request time and performs no HTTP abort or thread
  interrupt. HTTP connect/socket timeouts are a different mechanism.
- `AgentMessageStreamReader` reads a `maxItems`-bounded page and then filters roles, so lifecycle
  events in the message Stream would crowd out conversation rows. The ledger is a separate Stream.
- The platform `AddStreamEntry` queues the entry; `QueryStreamData` offers `maxItems`, a time
  range and a query, with no general offset parameter.

## 3. Known gaps in current behavior

| Id | Gap | Reference |
|---|---|---|
| G1 | Large fixed payload per round; tool schemas are the largest share and drive cold-cache cost and rate-gate reservations | §2.4, §2.9 |
| G2 | Request-side budget (effective cap, drop-only) and storage-side compaction (`llmContextMaxChars`) use different thresholds; model limits come from generation-blind prefixes | §2.2, §2.3, §2.8 |
| G3 | On the Anthropic path the checkpoint is not sent, so there is no semantic compaction fallback in the request | §2.3 |
| G4 | Restricted rounds change the tool array and cannot reuse the tools/system cache layers | §2.5, §2.6 |
| G5 | The LLM lane of a chart round carries a `chartBlock` data copy the model does not need, and it replays with history | §2.7 |

## 4. Principles

1. **Measure before changing.** Mechanism changes are judged with the call ledger (CC-7) and the
   long multi-turn fixtures (CC-8).
2. **Cache prefix first.** Byte stability is the default; anything that rewrites history or
   changes the tool array must be batched and explainable per cache layer.
3. **Request-side adaptation and storage-side deletion are separate decisions.**
4. **Compaction products are graded by provenance.** Deterministic matrix.v1 keeps the original
   evidence and sample semantics; a Tier B summary supports only the statistics and ranges it
   keeps, and a summary with a `cacheId` and no statistics is a refetch pointer; a model-written
   checkpoint never proves numbers, completeness or permissions.
5. **Execution limits are not enforced by prompt text.**
6. **The LLM lane and the audit lane are separate.** What the model sees may be smaller than what
   is persisted, exported or shown; the audit lane is never reduced.
7. **Cost figures are observed, not billed.** No bill is claimed and no unit price is generalized
   to unreviewed models.

## 5. Fixed overhead, ledger and fixtures

### CC-1 Fixed overhead

#### CC-1.1 Tool description editing rules

Built-in tool descriptions may be shortened only in human-readable text: a tool's top-level
`description`, genuine `description` fields inside its parameter schema, and the bundled
`llm_tool_routing_guide.txt`. The following never change as part of description work: tool
names, registration/merge/order, aliases, parameter names, types, `required`, `enum`, defaults,
numeric bounds, structural schema keywords, execution and result semantics, `toolAdmissionMode`
defaults, off/lazy admission, catalog truncation, loaded-set lifecycle. Taxonomy,
`AgentSettings.systemPrompt`, extension tools, skill/Playbook content, time/identity/evidence
rules and the replay-format guide are measured but are not edited to reduce size.

#### CC-1.2 Division of labor

- A tool's top-level description opens with a short purpose: what it does, the main input source
  and what it produces. Lazy mode builds catalog lines from this opening.
- Schema `description` fields carry the meaning, preconditions and key limits needed to call the
  tool correctly. The schema must stay self-sufficient when the routing guide is disabled.
- Cross-tool selection rules appear once, in the routing guide.
- `ToolAdmissionPolicy` builds each lazy catalog line from the full description with newlines
  replaced by spaces, truncated to 160 characters including the ellipsis
  (`LAZY_CATALOG_BLURB_MAX_CHARS`), with a total catalog cap of 8,000 characters
  (`LAZY_CATALOG_MAX_CHARS`).

Seven priority tools carry the most description text and are tracked individually:

| Tool | Semantics that must remain in its description |
|---|---|
| `tabulate_cached_result` | Transform order, operation parameters, output columns and completeness; full table versus sample |
| `query_entities` | Scope, inheritance, filter field sources, result counts and count semantics |
| `build_chart_from_tabular_result` | Valid sources, `cacheId`, kind/intent exclusivity, column shape, multiple charts and success state |
| `invoke_service` | Real Service signature, scope, input mapping, no-parameter versus empty-parameter |
| `query_entities_by_taxonomy` | Taxonomy resolution, hierarchy scope and completeness |
| `query_property_history` | Date/time zone, units, real window versus chart-only window, sample versus exact aggregate |
| `build_history_overlay_chart` | Multiple traces, per-trace windows, axis mode, units and real data-fetch limits |

The mapping from each semantic to its location and test is kept in
`parler-agent/src/test/resources/context-compaction/m1a/semantic-preservation-checklist.json`
and checked by `M1aSemanticPreservationTest`, including assertions on the actual 160-character
lazy catalog lines and on the full descriptions after `load_tool_schemas`.

#### CC-1.3 Locked census baseline

`parler-agent/src/test/resources/context-compaction/m1a/` holds:

- `census-baseline.json` — the reference census built from the registered tools for Chat
  Completions and Anthropic × off and lazy: total schema characters, per-tool characters,
  per-tool `toolSchemaEditableChars`, the seven-tool total, `routingGuideChars`, the parameter
  structure projection and the actual lazy catalog. It records the pre-reduction figures and is
  never overwritten with later values.
- `deployment-fixture.json` — fixed taxonomy, workflow and extension-tool inputs that give the
  census a deployment shape.
- `semantic-preservation-checklist.json` — see CC-1.2.

Sizes are measured with `ToolSchemaSizer` on the wire JSON, never on a Markdown rendering.
Editable characters are counted by walking JSON Schema nodes semantically
(`M1aEditableDescriptionMetrics`, `M1aDescriptionProjection`), so a business property that happens
to be named `description` is not treated as description text.

#### CC-1.4 Enforced gates

`M1aBaselinePinTest` and `M1aSemanticPreservationTest` enforce:

- **Structure**: tool names, order and every non-description schema element are identical to the
  census. The routing-guide, `documentKnowledge` and legacy-discovery switches run at their
  configured values.
- **Size**: in all four wire/mode combinations `toolSchemaChars + routingGuideChars` does not
  grow; in off mode `toolSchemaChars` alone strictly decreases on both wires; the bundled routing
  guide (UTF-8 length of the file) stays below its census value; the seven priority tools'
  editable description total is at most 28,661 characters, at least 10% below the census value of
  31,846.
- **Computing modes**: the seven-tool 10% measurement is taken with every computing mode
  withdrawn (`ComputingOperationAdmission.callWithAllDisabled`), because the census was taken
  before those modes existed. Everything else, including the census fixture and the structure
  assertions, runs with computing modes enabled. Computing-mode descriptions have their own cap:
  in `tabulate_cached_result` they add at most 2,500 characters (enabled minus disabled), checked
  by `TabulateCalendarBucketModeTest`.
- **Semantics**: every checklist entry is present with the routing guide on and off, and lazy
  catalog lines keep a recognizable purpose.
- **Regression**: `ToolSchemaSizerTest`, `BuiltInToolsLlmSchemaRegistryTest`,
  `ToolAdmissionPolicyLazyTest`, `PromptPrefixStabilityChainTest` and CC-8 keep tool membership,
  order, per-turn reset and restricted-round behavior.

These are local design gates. A character reduction does not promise a proportional token or cost
reduction, and fake-LLM tests do not prove real tool-selection quality.

#### CC-1.6 External stable system prompt

An operator can replace the whole leading stable system prompt with one Markdown file in the
ConfigurationRepository, without rebuilding the Extension.

1. On the first prompt-context fill after startup and on every `RefreshPromptContextCache()`, the
   AgentThing scans the direct files (not recursive) of `/SystemPrompt/` in its
   `configurationRepository`. The `.md` extension is case-insensitive.
2. No directory or no `.md` file → default `LeadingStablePromptComposer` assembly. Exactly one
   non-blank file → its UTF-8 text alone is the leading stable row; the default base, routing
   guide, taxonomy, alert prompt, workflow catalog, stable time guidance and final-answer evidence
   rule are **not** appended.
3. Several `.md` files, a read or list failure, or a blank file → a diagnostic and the default
   assembly. A refresh that finds an invalid selection does not keep the previous external text.
4. The selection is cached in the prompt-context snapshot; files are not read per round. The
   stable text is fixed within a logical turn, including HITL continuation; edits apply after the
   next refresh.
5. In external-file mode a per-call `systemPrompt` override is ignored. In default mode the
   override behaves as before.
6. `GetAgentRuntimeSnapshot` and `RefreshPromptContextCache` report `stablePromptSource`
   (`default` | `external_file`), `externalSystemPromptPath`, `externalSystemPromptFallback` and
   the effective stable text. The file name or hash never enters the model-facing prompt.
7. The file is static. It may contain deployment-specific taxonomy, skill or Playbook text; the
   operator keeps it in sync with configuration changes, and the runtime never splices a freshly
   generated catalog into it. The operator must include any product-invariant guidance the model
   should still see, in particular the stable time guidance (`ParlerTimeAnchor.STABLE_TIME_GUIDANCE`)
   and the final-answer evidence rule (`LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE`).
   Recommended workflow: call `GetAgentRuntimeSnapshot` with `includePrompt: true` in default
   mode, use `stableSystemPrompt` as the starting text, and save the edited file under
   `/SystemPrompt/`.

Code: `configrepo.SystemPromptFileLoader`, `configrepo.ExternalSystemPromptAssembly`,
`configrepo.ExternalSystemPromptSelection`, `PromptContextCacheSnapshot.externalSystemPrompt`,
`AgentThing.buildPromptContextSnapshotInternal` / `assembleLeadingStableSystemPrompt` /
`GetAgentRuntimeSnapshot`. Tests: `SystemPromptFileLoaderTest`, `ExternalSystemPromptAssemblyTest`,
`ExternalSystemPromptLifecycleTest`. See also
[`system-prompt-cache.md`](../system-prompt-cache.md) and
[`configuration-repository.md`](../configuration-repository.md).

### CC-7 LLM call ledger and usage statistics

#### CC-7.1 Role, compatibility and enablement

`AgentLlmCallStream` (Stream) with DataShape `AgentLlmCallData` is the **only data source** for
LLM usage statistics and analysis. It records observed facts for every built-in Parler LLM call:
success, error, timeout, and cancellation observed at existing checkpoints. Current call origins
are `AgentLoop` rounds, checkpoints, Playbook `llm_summary` and the Provider `healthCheck` probe.
Provider retry/fallback is not wired, so the ledger has no route for it. The ledger never copies
messages, tool results or chat history; ordinary tool-run events are out of scope.

Hard rules:

- `AgentMessageData.xml`, the `AgentMessageStream` Thing and its DataShape columns are unchanged;
  `promptTokens` / `completionTokens` / `llmUsageJson` keep their message semantics, history
  export, rehydration and UI wire.
- Old messages are not moved, backfilled or rewritten. The helper does not read the Application
  Log, the message Stream or any other source, and does no cross-source reconciliation or
  fallback. Statistics start when the recorder starts.
- The entities ship with the Extension and are imported by the operator; code never creates
  entities during a request or changes the persistence provider. The recorder is enabled by
  default for built-in calls. A missing or unwritable Stream degrades as described in CC-7.4; it
  never switches to another store.
- No billing, automatic bill retrieval, background provider polling, separate database or generic
  log platform. Records are call and usage facts, not financial statements.

#### CC-7.2 Fixed fields and JSON envelope

The DataShape defines stable fields once; vendor token breakdowns live in `eventJson`, so new
token kinds do not add Stream columns. Every event is an immutable snapshot; corrections are new
events, never `UpdateStreamEntry`.

| Field / metadata | Type | Meaning |
|---|---|---|
| Stream `source` | STRING metadata | **eventId**, not conversationId. Different events in the same millisecond have different sources; re-writing the same event keeps the same source. The platform entry id is not a business dedup key. |
| Stream `sourceType` | STRING metadata | Always `Thing` (the existing appender convention). The eventId in `source` is not a resolvable Thing name; readers never infer identity from `sourceType`. |
| Stream `timestamp` | DATETIME metadata | **callStartedAt**: the attempt's creation time, identical for all events of one call, so late events are found by the call's start interval. Collector control events use their `occurredAt`. |
| `eventId` | STRING | UUID generated once per event; unchanged on write retry or re-export |
| `callId`, `logicalCallId` | STRING | Attempt UUID; logical model-request UUID. Empty for collector control events, which are not counted as calls. |
| `turnRequestId`, `conversationId`, `agentThing` | STRING | Snapshot at call time. AgentThing requests fill the server turn id at entry; requests without a conversation get an ad-hoc conversation id. A Provider probe may leave them empty and is marked by `callKind`. |
| `providerThingName`, `providerFamily`, `apiShapeId`, `requestedModel` | STRING | Provider and model (or Azure deployment) resolved at call time; never recomputed from later configuration |
| `callKind` | STRING | `agent_round`, `checkpoint`, `playbook`, `probe`; a new kind requires a new envelope version |
| `eventType` | STRING | See CC-7.3 |
| `sequence` | INTEGER | Monotonic per call from 1, allocated by a thread-safe per-call recorder; causality never comes from timestamp order |
| `callStartedAt`, `occurredAt` | DATETIME | Attempt start and event time, UTC milliseconds |
| `schemaVersion` | INTEGER | `1`. Readers check it explicitly; an unknown version is never treated as an older one. |
| `eventJson` | TEXT | Full JSON envelope: raw usage, normalized counts, availability, response identity, error/diagnostics. Never enters LLM replay or UI done/history payloads. |

`eventJson` v1 members: `schemaVersion`, `identity` (a copy of the fixed identity; must match),
`roundIndex` (nullable), `attemptIndex` (1 in production; the recorder API increments it within a
logical call), `retryOfCallId`, `collectorInstanceId` (UUID per JVM start), `dispatchState`
(`not_sent` / `attempted` / `unknown`), `outcome`, `cancelObservedAt`, `endedAt`, `durationMs`,
`httpStatus`, `providerRequestId` (response header), `providerResponseId` (body id),
`responseModel`, `finishReason`, `error` (stable category and safe summary), `usage`,
`contextPlan`. Values are filled only when known; unknown optional values are absent or null,
never an empty string pretending to be a model or 0 pretending to be a token count. A sequence or
identity mismatch is reported as a bad record, never guessed.

`usage` = `{status, revision, rawUsage, normalized, presence, captureError}`. `rawUsage` is a
deep copy of the parsed vendor usage object, including unknown keys and arrays (structure is
preserved, whitespace is not). `normalized` uses nullable non-negative 64-bit integers, and
`presence` distinguishes reported / derived / absent / not_applicable. Out-of-range, negative,
non-numeric or contradictory counts keep the raw value and are marked invalid, never truncated or
clamped to zero. Derived values record their formula and are never written back into raw. Missing
usage, invalid JSON or an incomplete body never produce raw usage from the legacy parser's
default zeros.

`normalized` keys (all nullable): `inputTokensTotal`, `inputTokensUncached`,
`inputTokensCacheRead`, `inputTokensCacheWrite`, `cacheWrite5mTokens`, `cacheWrite1hTokens`,
`outputTokensTotal`, `reasoningTokens`, `totalTokens`.

- OpenAI: `inputTokensTotal = prompt_tokens`, `inputTokensCacheRead = cached_tokens`;
  `inputTokensUncached = prompt − cached` is derived only when the cache breakdown is known and
  there is no unknown write semantics.
- Anthropic: `inputTokensUncached = input_tokens`; read and write come from their top-level
  counts; `inputTokensTotal` is derived only when all three are known.
- Output is `completion_tokens` / `output_tokens`; reasoning is `reasoning_tokens` /
  `thinking_tokens`, never estimated from output length.
- Derived totals check for 64-bit overflow and negativity; unknown or inapplicable parts are
  never zero-filled to make a total. CSV keeps both the normalized fields and the vendor fields,
  and summaries add only explicitly named fields, never normalized and raw fields together.

`contextPlan` (`LlmCallContextPlanSnapshot`) copies local measurements that already exist for the
call: budget and request characters, message/tool counts, `requestedMaxOutputTokens`, restricted
round category, drop counts and compaction reason. Probe and checkpoint calls without a planner
may omit it. It never contains request bodies, tool arguments, full error bodies, API keys,
authentication headers or full URLs; error summaries reuse the existing safe diagnostic
categories.

#### CC-7.3 Lifecycle, identity and late results

| eventType | Emitted | Rules |
|---|---|---|
| `call_started` | When the attempt context is created, before request construction and local admission | `dispatchState=not_sent`; a start does not mean the provider received anything and does not count as sent |
| `call_dispatched` | After local admission, at the actual HTTP execute entry | `dispatchState=attempted`; a transmission attempt, not a claim that the vendor accepted or billed it |
| `call_cancel_requested` | When an existing `AgentLoop` checkpoint first observes the cancel flag | Records `cancelObservedAt` on the current candidate or the most recent round; not the user's submit time; does not retro-mark older calls of the turn, force an outcome, or add cancellation checks or aborts |
| `call_finished` | Parsable response, non-2xx, content parse error, connection error, timeout, or an observed cancellation exit | `outcome` = `success` / `error` / `timeout` / `not_sent`, with the complete usage snapshot known at that moment. `success` means the call succeeded; `finishReason` still shows length, refusal or tool_calls, and it does not mean the business answer succeeded. |
| `usage_observed` | The recorder API receives new usage facts after the terminal event | Same `callId`, incremented `usage.revision`, cumulative snapshot. Synchronous clients have no post-timeout network callback; late usage is exercised through the recorder API with fake callers. |
| `collector_started` / `collector_gap` | Recorder initialization; or the next writable moment after known write failures | No `callId`; records `collectorInstanceId`, the interval and the known failed-event count; not a call and no tokens |

`roundIndex` is the existing `llmRoundIndex` (same as `LLM_USAGE.rounds`). The empty-final-answer
retry, post-marker and summary-none rounds are new model calls with new `logicalCallId` and
`callId`. Each production logical call has one attempt (`attemptIndex=1`,
`retryOfCallId=null`); the bridge, rate gate and HTTP layer share one recorder and never count a
call twice. Each checkpoint and Playbook `chat` has its own `logicalCallId` and a null
`roundIndex`, even when the results are later merged into one message. The Provider `healthCheck`
is a real probe; the local `contextPlanningInputCapChars` computation sends no HTTP request and
creates no events.

The recorder API lets a future caller create a follow-up attempt in the same logical call: a new
`callId`, the next `attemptIndex`, `retryOfCallId` pointing at the previous attempt, and its own
Provider/model snapshot. Production does not use it (`LlmCallRecorderMultiAttemptTest` covers it
with a fake caller).

The collection context travels in non-wire fields of `LlmChatRequest` and survives the three
`forAgentRound` overloads and `copyWithProviderAugmentation`, `copyWithProbeMode`,
`copyWithCacheControl` and `copyWithToolPolicy`. Provider resolution, the rate gate and the HTTP
call use the same immutable identity snapshot. A built-in client called directly still creates a
recorder and marks the missing identity. The existing `LLM_USAGE` meanings are unchanged, and the
recorder does not rely on thread-local state alone or on settling at the final assistant append.

After reading the body, the HTTP client (`LlmRecordedHttpChat`) **captures usage, body model and
body id first, then runs the original status and content parsing**. A non-2xx or choice-less
response still records the usage it carried, and capture never turns a failed response into a
success. A network error without a body records the error with unknown usage. The synchronous
HTTP path has no SSE support.

Cancellation versus transport outcome:

- When a pre-round checkpoint observes cancellation, the candidate about to be called is recorded
  as started → cancel_requested → finished(`not_sent`), `dispatchState=not_sent`,
  `roundIndex=null`; known identity is kept and unresolved Provider/model stay empty. It does not
  increase `LLM_USAGE.rounds` or `sentCallCount`. When cancellation ends the turn at a tool
  checkpoint, only the most recent round gets the cancellation observation; no next candidate is
  invented.
- There is no cancellation check during HTTP and no abort. If a later checkpoint observes
  cancellation, `cancelObservedAt` is added to the round that just finished; its outcome and usage
  stay as they were. An exception path that never reaches a checkpoint may have no cancellation
  record, and none is invented.
- There is no `cancelled` transport outcome. Late usage only updates usage and never overrides an
  existing terminal event.

A call with a start and no end is reported as `unfinished`; an end without a start as `orphan`.
A restart does not turn these into timeouts or zero cost. `cancelObservedAt` is written once;
repeated checks do not add events.

#### CC-7.4 Persistence guarantees and failure boundaries

`AgentLlmCallStreamWriter` calls the platform `AddStreamEntry` directly; there is no in-memory
async queue and no sampling. The writer takes immutable snapshots and returns success or failure
to the recorder. A failed write logs a safe Application Log diagnostic and increments the JVM's
gap counter, and the next writable moment emits `collector_gap`. **The log is a diagnostic only
and never a statistics input.** A write failure never resends the LLM request, replaces the
original LLM exception, or turns a successful answer into a failure. Failed writes are not
retried automatically; duplicate delivery is deduplicated by `eventId`.

`AddStreamEntry` success means the platform accepted or queued the entry, not that the database
committed it. A missing Stream, a permission error, a later queue failure or a JVM crash can leave
gaps. There is no write-ahead log or outbox, and no zero-loss or exactly-once claim.
`collector_started` and known gaps make boundaries visible but cannot prove the absence of unseen
loss. Reports always state `captureCoverage=observed_only`, and keep `readComplete` (was this query
complete) separate from `usageStatus` (did a call receive final usage). No data is reported as
"no visible records", never as zero calls.

`eventJson` is limited to 262,144 Java characters (`LlmUsageSnapshot.MAX_EVENT_JSON_CHARS`). An
oversized envelope is never truncated into invalid JSON: it keeps the fixed identity, recognized
normalized values, the raw usage character count and SHA-256, and
`captureError=usage_oversize`, with `rawUsage` empty and `status=partial`. The limit is for usage
and small diagnostics; full responses never go into the envelope.

Complete call snapshots include the observed `dispatchState` and `cancelObservedAt`, so partial
facts survive the loss of earlier events; they cannot prove that every event was stored. A failed
`collector_gap` write does not reset the cumulative gap count, and a failed `collector_started`
write stays pending. Unreported in-memory gap information is lost on process exit, which is why
`captureCoverage=observed_only` is always kept.

The ledger does not take part in `ClearConversation` or message-history retention, and there is no
purge or migration service. Reading it requires permission on the helper services and on the
ledger Stream; the helper does not elevate the SecurityContext and does not open all
conversations through the Gateway. Operators configure platform permissions. Reading `rawUsage`
does not grant chat users cross-conversation access.

#### CC-7.5 Usage fields and merge rules

The ledger keeps the usage **actually returned**; it does not claim every vendor or model returns
every field. Supported transports: OpenAI/Azure Chat Completions and Anthropic Messages.

| Family | Known paths (relative to usage) | Counting rules |
|---|---|---|
| OpenAI / Azure | `prompt_tokens`, `completion_tokens`, `total_tokens`; `prompt_tokens_details.cached_tokens`, `.audio_tokens`; `completion_tokens_details.reasoning_tokens`, `.audio_tokens`, `.accepted_prediction_tokens`, `.rejected_prediction_tokens` | `cached` is part of prompt; reasoning/audio/prediction are breakdowns of completion and are not added to output; totals use only the top-level field or a labeled derivation. An explicit `cached_tokens: 0` is kept as 0; a missing field is not zero. |
| OpenAI extensions | e.g. `prompt_tokens_details.text_tokens`, `.image_tokens`, `.cache_write_tokens`, `completion_tokens_details.text_tokens` | Kept and exposed as extra columns when they appear; not assumed to exist, and no assumption that OpenAI never charges for cache writes |
| Anthropic | `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`; `cache_creation.ephemeral_5m_input_tokens`, `.ephemeral_1h_input_tokens`; `output_tokens_details.thinking_tokens` (optional) | The three input kinds are disjoint; TTL parts are included in `cache_creation`; thinking is included in output and never added twice. Missing optional thinking does not make otherwise complete usage partial. |
| Other fields | Unknown keys, `service_tier`, `inference_geo`, `server_tool_use`, `iterations`, … | Kept as-is; server tool counts are not tokens; `iterations` items are not added to top-level totals. An uninterpretable billing dimension is marked `costUnsupported`. |

Field semantics follow the vendors' public documentation:
[OpenAI Chat Completions](https://developers.openai.com/api/reference/java/resources/chat/subresources/completions/methods/create),
[Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching),
[Anthropic thinking breakdown](https://platform.claude.com/docs/en/build-with-claude/thinking-steering-and-cost).

`usage.status`: `pending` (awaiting response), `complete` (final usage captured with valid required
top-level fields), `partial` (only part captured or preserved), `unavailable` (finished without
usage), `invalid`, `not_applicable` (evidence that no transmission happened). `complete` does not
require every optional breakdown and does not mean the vendor bill is reconciled. Missing values
stay null / empty CSV cells; an explicit 0 stays 0. Local budget estimates live only in
`contextPlan`.

Compatible-provider classification (`LlmUsageCapture`):

| Case | `usage.status` | Values |
|---|---|---|
| `usage` missing or `usage: null` | unavailable | All missing values stay null, no zero fill |
| `usage` present but not an object (string, number, boolean, array) | invalid | `captureError` names the type (e.g. `usage_type_string`); the raw value/type is kept in `rawUsage` within the size limit |
| `usage` is an object missing required counts (including `{}`) | partial | Known valid values kept; missing ones empty |
| Required top-level counts valid; only optional cache/reasoning parts missing | complete | Missing optional values empty |
| A recognized count or its non-null container has the wrong type; negative, non-integer, overflow, or a known contradiction | invalid | Numeric strings are not accepted as integers; nothing truncated or clamped |
| Explicit valid 0 | complete | 0 kept, distinct from absent |
| Unknown extension key | kept per raw rules | Never makes usage invalid; no billing meaning guessed |

When the HTTP response and the answer protocol are valid, the call keeps its successful response,
content, tool calls and `finishReason` whatever the usage status: `outcome=success` with
`usageStatus=invalid` is possible. Usage problems never change the business result, trigger a
retry or resend the request. Invalid usage is excluded from trusted sums; when pricing is not
possible the amount is unknown.

Merging: deduplicate by `eventId`, then assemble by `callId`. The same `eventId` with different
content, or the same call with conflicting fixed identity or the same `sequence` with different
content, is a `recordConflict`, and the call is excluded from the formal amount; neither first nor
last wins silently. `usage.revision` increases only on a new valid observation; a later complete
snapshot supersedes an earlier partial one, and a later empty usage never erases known values. A
complete snapshot replaces the previous one; snapshots are never added or merged field-by-field
with max. Conflicting final snapshots keep raw and are flagged. Retries are different calls,
counted and priced separately; calls with unknown usage stay in the denominator.

#### CC-7.6 ThingWorx helper, CSV and cost

The Extension provides the `AgentLlmUsageHelper` ThingTemplate (Java `AgentLlmUsageHelperThing`)
but no helper Thing instance. An operator creates a Thing from the template, with any name, and
configures `Settings.usagePricesJson` on it. Both services share one reader and reducer:

- `GetLlmUsageReport(StartTime DATETIME, EndTime DATETIME, conversationId STRING, agentThing
  STRING, model STRING) → JSON` (reads the ledger only).
- `ExportLlmUsageCsv(StartTime DATETIME, EndTime DATETIME, conversationId STRING, agentThing
  STRING, model STRING, FileRepository THINGNAME, FileName STRING) → TEXT`: writes the CSV
  through the chosen FileRepository's `SaveText`, passing `path` and `content` unchanged (Parler
  does no file-name or path checks), and returns the same CSV text. A `SaveText` failure is
  propagated.

Filters: `StartTime` and `EndTime` are required DATETIME values, converted to UTC, applied to
**callStartedAt in [StartTime, EndTime)**; `StartTime` must be strictly earlier than `EndTime`.
Blank STRING filters mean no filter; non-blank ones match exactly. `model` matches
`requestedModel` (including an Azure deployment); `responseModel` is a separate column and is never
re-resolved from current Provider configuration. Filters combine with AND; an AgentThing that
changes Provider or model is grouped separately. Provider probes with empty identity are kept;
filtering by AgentThing naturally excludes them. An omitted filter must be omitted from the
request, not sent as the string `"null"`.

`ExportLlmUsageCsv`: `FileRepository` defaults to `SystemRepository` (`THINGNAME`,
`thingTemplate:FileRepository`). `FileName` defaults to the placeholder
`/LLM-USAGE{YYYYmmddHHMM}.csv`; when the value is null or byte-equal to
`DEFAULT_FILE_NAME_PLACEHOLDER`, it becomes `/LLM-USAGE` + `yyyyMMddHHmm` + `.csv` formatted in
the JVM default time zone. Any other value, including an empty string or a path with `..`, is
passed to `SaveText` verbatim and the platform applies its own permissions and path rules. Two
exports in the same minute with the default name overwrite each other. The target repository is
resolved (visibility-aware `findEntity`) before the ledger is read; if it is not visible or does
not exist the service fails immediately with `REPORT_EXPORT_TARGET_NOT_FOUND`.

Reading (`AgentLlmCallStreamReader`) uses only the ledger. All events of a call share the Stream
timestamp, so late usage that occurs after `EndTime` is included in a later query of the original
start interval. The service freezes `reportAsOf` when it starts and ignores events whose
`occurredAt` is later; it does not promise a database snapshot, and a repeat query of the same
interval may be more complete.

The reader never assumes that `maxItems` is the total and never sums a truncated result. It reads
bounded time slices without value filters, with `maxItems = 1001`
(`SLICE_PROBE_MAX_ITEMS`); a slice with more than 1,000 rows (`SLICE_MAX_ROWS`) is split in half
by millisecond range and re-read. Because platform endpoint inclusivity is not assumed, each
logical slice [a, b) queries [a−1 ms, b], deduplicates by `eventId`, and assigns rows to [a, b) and
the final [start, end) locally; a range where a−1 ms is not representable is rejected. The overlap
rows are also covered by the probe; a slice whose completeness cannot be proven is an error. More
than 1,000 events in one millisecond, more than 50,000 events in total (`TOTAL_EVENT_LIMIT`), a
service execution limit or a read error returns `REPORT_LIMIT_EXCEEDED` / `REPORT_READ_FAILED`
and no CSV that looks complete. A missing `eventId`/`schemaVersion`, an unknown schema version or an
invalid envelope returns `REPORT_INVALID_RECORD` with the safe entry/event identity; bad rows are
never skipped. Collector events are read by their `occurredAt` interval into `collectorDiagnostics`
and are not calls.

The JSON report contains at least: `schemaVersion`, `reportAsOf`, the filters,
`captureCoverage`, `readComplete`, `callCount`, `sentCallCount`, `notSentCount`,
`unknownDispatchCount`, `successCount` / `errorCount` / `timeoutCount` / `unfinishedCount` /
`orphanCount`, `cancelRequestedCount`, `usageCompleteCount` / `usagePartialCount` /
`usageUnavailableCount` / `usageInvalidCount`, `recordConflictCount`, `knownWriteGapCount`,
`knownSum` and `unknownCount` per numeric column, `knownCostUsd`, `unpricedCallCount`,
`costUnsupportedCount`, `priceVersion`, the `calls` detail and diagnostics.

- `callCount` counts observed attempts or candidates by `callId`, including `not_sent`; it is not
  the number sent or billed.
- Outcome counts are mutually exclusive; `cancelRequestedCount` overlaps them and counts calls with
  cancellation evidence.
- `sentCallCount` counts calls with `call_dispatched` or a terminal `dispatchState=attempted`; it
  is not the number the vendor accepted. Missing `call_dispatched` does not imply "not sent": only
  an explicit `not_sent` terminal counts in `notSentCount`; the rest without evidence count in
  `unknownDispatchCount`.
- No visible calls returns the CSV header and `no_visible_records` in the report, never a verified
  zero.

CSV (`LlmUsageCsvWriter`) has one row per `callId`. Fixed columns: `callId`, `logicalCallId`,
`conversationId`, `agentThing`, `requestedModel`, `responseModel`, `providerFamily`,
`providerThingName`, `apiShapeId`, `callKind`, `callStartedAt`, `endedAt`, `outcome`,
`dispatchState`, `cancelObservedAt`, `usageStatus`, `conflict`, `costStatus`, `knownCostUsd`,
`priceVersion`, `captureCoverage`, `reportAsOf`. Then vendor columns with vendor-qualified JSON
paths (`openai/prompt_tokens`, `openai/prompt_tokens_details/cached_tokens`,
`anthropic/cache_creation/ephemeral_5m_input_tokens`, … for every known path in the table above;
inapplicable cells empty), then numeric leaf paths newly seen in this batch's `rawUsage`, sorted,
with array elements addressed by indexed JSON Pointer; these extra columns are not treated as
summable totals. `rawUsageJson` keeps the full raw structure. UTF-8, RFC 4180 quoting and newline
escaping, and formula-injection protection for text cells. Output above 5,000,000 characters
(`MAX_CSV_CHARS`) is an error, never truncated.

Prices come from the helper Thing's `usagePricesJson`; nothing is fetched from the network. Format:

```json
{
  "version": "prices-2026-09",
  "prices": [
    {
      "providerFamily": "anthropic",
      "apiShapeId": "anthropic-messages-v1",
      "requestedModel": "claude-sonnet-...",
      "serviceTier": "standard",
      "effectiveFrom": "2026-01-01T00:00:00Z",
      "effectiveTo": "2027-01-01T00:00:00Z",
      "currency": "USD",
      "inputPerMillionUsd": 3.00,
      "outputPerMillionUsd": 15.00,
      "cacheReadPerMillionUsd": 0.30,
      "cacheWrite5mPerMillionUsd": 3.75,
      "cacheWrite1hPerMillionUsd": 6.00
    }
  ]
}
```

(Values are illustrative.) The default is the empty table with version `unpriced-v1`; an
unparsable value also falls back to it. Entries whose currency is not USD are ignored. An entry
matches a call when every field it sets matches the call's identity (`providerFamily`
case-insensitively, `apiShapeId` and `requestedModel` exactly, `serviceTier` against the raw
`service_tier`) and the call time is in `[effectiveFrom, effectiveTo)`. There is no prefix or
sibling matching, and the Provider is never inferred from a price entry. Several matching entries
give `priceConflict`; an unknown billing dimension gives `costUnsupported`; either way the amount is
empty. The price configuration is deep-copied once per service call, so one report uses one
`priceVersion`.

When a text price matches and the required counts are known:

- OpenAI: `(prompt − cached) × input + cached × cacheRead + completion × output`;
- Anthropic: `input × input + cacheRead × cacheRead + write5m × cacheWrite5m + write1h × cacheWrite1h + output × output`;

each divided by 1,000,000. For Anthropic, `cache_creation = 0` derives zero for both TTL bases;
a non-zero write without a complete TTL breakdown has unknown cost (5 minutes is not assumed).
Audio, image, new cache-write kinds, server tools and multi-model iterations without a price rule
keep full usage and are marked `costUnsupported`. Known non-billing diagnostic objects (such as
`latency_checkpoint` and its millisecond leaves in the OpenAI/Azure family) stay in raw and CSV and
do not block text pricing; an unrecognized positive billable breakdown still blocks pricing.
Amounts use `BigDecimal`, are computed per call at full precision and then summed, and are shown
with four decimals. Unknown parts are never folded into a zero total. `knownCostUsd` is the sum
over known, priceable calls; it is not a complete bill.

#### CC-7.7 Code map and test matrix

Entities (relative to `parler-agent/`): `Entities/DataShapes/AgentLlmCallData.xml` and
`Entities/DataShapes/AgentLlmCallStream.xml`. `metadata.xml` registers
`ThingPackage name="AgentLlmUsageHelper" className="com.thingworx.things.agent.AgentLlmUsageHelperThing"`
and `ThingTemplate name="AgentLlmUsageHelper" thingPackage="AgentLlmUsageHelper"`. The
`extensionZip` task already packages the whole `Entities` directory. The operator imports the
entities with the Extension.

| Area | Code |
|---|---|
| Capture and events | `llm/usage/LlmCallContext`, `LlmCallContextPlanSnapshot`, `LlmCallEvent`, `LlmCallEventType`, `LlmCallKind`, `LlmCallOutcome`, `LlmCallDispatchState`, `LlmCallRecorder`, `LlmUsageCapture`, `LlmUsageSnapshot`, `LlmRecordedHttpChat`, `AgentLlmCallStreamWriter` |
| Call sites | `AgentThing`, `AgentLoop`, `LlmChatRequest`, `ProviderBridgeContext`, `ProviderLlmClientBridge` (with `LLMAPIProviderThing` implementing `ProviderBridgeContext`), `OpenAiChatCompletionsClient`, `AzureOpenAILlmClient`, `AnthropicMessagesLlmClient`, `ConversationCheckpointGenerator`, `PlaybookRunner` |
| Reporting | `llm/usage/AgentLlmCallStreamReader`, `LlmUsageReportQuery`, `LlmUsageReportReducer`, `LlmUsageCsvWriter`, `LlmUsageCostCalculator`, `LlmUsageHelperSupport`, `LlmUsageReportException`; `AgentLlmUsageHelperThing` |

Registered in [`AGENT-CONTEXT.md`](../AGENT-CONTEXT.md) §5.6 and
[`README.md`](../README.md).

Test matrix (fake transport, sink and reader; no real provider or ThingWorx access; expected
values independent of the implementation):

1. Three HTTP clients: success with full usage, success without usage, non-2xx with usage,
   missing choices / content parse failure with usage, invalid JSON, connection failure, timeout;
   original response and exception behavior unchanged. Compatible usage variants including
   tool-call identity and `finishReason` (`LlmRecordedHttpChatTest`, `LlmUsageCaptureTest`).
2. `AgentLoop` normal / empty-final retry / restricted rounds, two Playbook calls, one checkpoint
   call, probe, local admission rejection: each attempt recorded once; message-Stream and ledger
   writes independent; tool events never mixed in (`AgentLoopCallSiteCensusTest`,
   `ProviderLlmClientBridgeTest`).
3. Distinct rounds and `logicalCallId`s for normal, empty-final retry, post-marker and
   summary-none, all with `attemptIndex=1`; a fake caller produces three attempts in one logical
   call (two failures, one success) with correct `retryOfCallId` (`LlmCallRecorderMultiAttemptTest`).
   Changing AgentThing configuration mid-way does not rewrite recorded identity.
4. Pre-round cancellation → `not_sent` + `cancelObservedAt` with no HTTP call and no extra
   `rounds`; cancellation observed after a response → `success` + `cancelObservedAt` with usage
   kept; tool-phase cancellation creates no next call; no `cancelled` outcome; fake late usage
   after a timeout is recorded once.
5. Duplicate delivery, reordering, partial→complete, complete→empty, conflicting final snapshots,
   identity conflicts, missing start/end, leftovers after restart; never counting rows as calls,
   never last-wins by timestamp, never zero-filling unknowns (`LlmUsageReportReducerTest`).
6. OpenAI breakdowns, Anthropic TTL parts and optional thinking (present and absent), unknown
   fields and arrays, legal 0, missing fields, negative/overflow/contradiction, oversized usage.
7. Missing Stream, permission failure, writer failure and recovery, delayed platform visibility and
   process restart: safe diagnostics and `collector_gap` visible (`LlmCallRecorderTest`,
   `LlmCallEventTest`).
8. Filter combinations, UTC boundaries, Provider change within one AgentThing, calls ending or
   updated after `EndTime`, many events in one millisecond, overlapping slices, open and closed
   endpoints, limits and errors, empty ranges, unknown schema; a limit never yields a successful
   CSV (`AgentLlmCallStreamReaderTest`).
9. Fixed and extension CSV columns, quoting/newlines/Unicode/formula prefixes, missing versus 0;
   synthetic prices covering exact match, unmatched siblings, unknown TTL, known subtotal with
   `unknownCount`, compute-then-round and every unsupported dimension (`LlmUsageCsvWriterTest`,
   `LlmUsageCostCalculatorTest`, `LlmUsageLatencyEvidenceTest`, `LlmUsageHelperSupportTest`,
   `LlmUsageHelperServiceTest`).
10. The four call origins compared against fake HTTP execute counts: sent attempts equal HTTP
    executes; local `not_sent` candidates counted separately; the planning probe creates no
    events; a directly called built-in client still records with missing identity.

A ten-turn fake ledger → helper fixture (`LlmUsageHelperTenTurnFixtureTest`) checks logical rounds,
attempts, checkpoint and empty-final-retry usage and unknown counts against independent
expectations.

The helper's instance price lookup and the production `SaveText` dispatch cannot run in local
JUnit (platform classes are missing); tests cover the support entry points and the `SaveText`
parameter hook. Operator check on a live system: calling `ExportLlmUsageCsv` without `FileName`
must create `/LLM-USAGE<yyyyMMddHHmm>.csv` in `SystemRepository`, not the literal placeholder or an
empty path.

### CC-8 Long multi-turn replay fixtures

`parler-agent/src/test/java/com/thingworx/things/agent/compaction/LongConversationReplayTest.java`
locks the current behavior of replay growth, drop-only trimming and prefix stability over long
conversations. Helpers: `StructuredMessageProjection` (+ `StructuredMessageProjectionTest`),
`WirePartitionHasher`, `CompactionTestFixtures`.

**Conversation shape**: 10, 30 and 60 turns from one deterministic builder; each turn is user →
assistant tool call → two TOOL results → assistant tool call → TOOL result → final assistant
text. Every TOOL body is `CompactionTestFixtures.largeCacheSampleMatrixToolJson()` (already
`parler.infotable.matrix.v1` with a `cacheId`, so Tier A/0 sealing is skipped and Tier B hits the
cacheId-only pointer branch).

**Budget matrix**: `llmContextMaxChars` 750,000 and 120,000 × `providerRequestCapChars` 0 and
118,622 × 10/30/60 turns (12 combinations). Each pins `droppedTranscript`, `droppedEvidence`,
`droppedAssistantBatches`, `effectiveRequestCapChars` and `historyBudgetChars`
(`pinnedBudgetMatrix`), computed by hand from the fixture composition (matrix body 350 characters;
60-turn transcript 102,191 characters; 120,000 cap → `0/147/98`; 118,622 cap → `0/150/100`; the
other combinations `0/0/0`). Expected values are never produced by `Metrics.compute` or the
planner under test.

**What is measured** (after `planForProviderRound`, before the provider call, per wire):

1. The full wire snapshot (`tools`, `system` or the first system row, `messages`,
   `cache_control`, `tool_choice`), used for marker and tool-array checks only.
2. A structured message projection: role, content block types and order, text, tool call names,
   arguments and ids, result pairing ids and result content. It excludes only message-level
   `cache_control` and request-level controls (`tools`, `tool_choice`, `max_tokens`,
   `temperature`, `thinking`, and `system`, which is compared separately). Keys inside tool
   arguments are content. Stability assertions use only this projection.
3. Independent hashes of the `tools`, `system` and `messages` partitions.

Local serialization order does not represent the vendor's cache prefix position; the tests only
assert whether each partition changed.

| Sequence | Trigger | Assertion |
|---|---|---|
| Plain append | No trimming, no Tier B/checkpoint, same tools | Overlapping projection prefix identical; only tail volatile and new rows differ; `tools` and `system` hashes unchanged. On the full wire, exactly the two newest candidate frontiers carry `cache_control`; the previous third loses it after an append. |
| Drop-only | `historyBudgetChars` insufficient | Projection identical before the first dropped row; drop counts match the pinned values; stored list unchanged |
| Tier B | Pre-normalization list exceeds `llmContextMaxChars` | Projection changes once, at the demotion turn; demoted rows have exactly `$format=parler.infotable.summary.v1`; stable before and after |
| Checkpoint | Storage trim would delete rows (fake client returns fixed JSON) | Projection changes only at installation; the Anthropic wire does not carry the checkpoint, the Chat Completions wire does |
| Tool-array change | Restricted round (empty or chart singleton) | `tools` partition differs from the main loop; empty round omits both `tools` and `tool_choice`; singleton round has `tools` and no `tool_choice`; `messages` projection matches the main-loop shape |

Pinned facts: first dropped turn `PINNED_FIRST_DROP_TURN_INDEX = 1`; drop-only total
`PINNED_DROP_ONLY_DROPPED_ROWS = 13`; Anthropic frontiers at `completedTurns=10`: `58:0` (latest),
`56:1` (second), `54:0` (third), where an eligible frontier is a real USER text block (excluding
the time-context row) or the last `tool_result` of a TOOL batch. A reverse fixture at
`completedTurns=3` (system 1, tool definition 1, history 2 markers) must reject markers placed on
the two oldest frontiers. The projection itself passes three reverse fixtures: identical text but a
different historic call argument (device A vs B) differs; identical text but a different call id or
result pairing id differs; moving only `cache_control` does not differ. `replayChars` is never used
as a conversation total (it is the latest compaction batch only). Vendor-side
`cacheCreate` / `cacheRead` are not asserted locally.

## 6. Operator guidance

- **Ledger**: import the Extension, which includes `AgentLlmCallData` and `AgentLlmCallStream`.
  Recording starts automatically; a missing or unwritable Stream only produces diagnostics and
  `collector_gap` events (CC-7.4).
- **Helper**: create a Thing from the `AgentLlmUsageHelper` ThingTemplate, set
  `Settings.usagePricesJson` if priced reports are wanted (CC-7.6), and grant report users
  permission on the helper services and on `AgentLlmCallStream`.
- **Querying the Stream directly**: `source` is an event id, so filter by the `conversationId`
  field (`query.filters` with `type: EQ`, `fieldName: conversationId`). A large `maxItems` is only a
  cap; if it is reached, split the time range rather than treating the result as complete.
- **External stable prompt**: start from the default `stableSystemPrompt` returned by
  `GetAgentRuntimeSnapshot`, keep the time guidance and final-answer evidence rule in the file,
  and check `stablePromptSource` / `externalSystemPromptFallback` after `RefreshPromptContextCache`
  (CC-1.6).
- **Budget**: `llmContextMaxChars` bounds both the request copy and the stored list; with
  `rateControlMode=enforce` the request-side cap can be much smaller (§2.2).

## 7. Cost scenario script

`scripts/context_compaction_cost_scenarios.py` is a zero-dependency PEP 723 script that computes
the cost of fixed ten-turn scenarios from fixed assumptions. It checks scenario arithmetic only:
it does not read the ledger, prove real cache hit rates, or replace the helper.

```bash
uv run scripts/context_compaction_cost_scenarios.py
```

Assumptions: ten turns; a **text** workload of three rounds per turn (fetch, aggregate, answer)
and a **chart** workload with one extra charting round; stable system 31,500 characters, 24-tool
schema 58,200 characters, 5,200 volatile characters per round, about 12,000 characters of history
growth per turn, 3.5 characters per token; 1,000 (text) or 1,150 (chart) output tokens per turn;
no checkpoint or retry. Cache scenarios:

| Scenario | Meaning |
|---|---|
| cold | No cache hits |
| warm | Stable prefix; only the volatile rows and the new increment miss; turn gaps within the 5-minute TTL |
| warm + TTL expiry | The first round of every turn rewrites the whole prefix |
| warm + no-tool final | The last round of every turn sends no tools and reuses no full-tool prefix (taken as zero hit, zero write) |
| lazy two-chain warm | Lazy mode with a catalog-only chain and a catalog-plus-three-schemas chain, both warm; a lower-bound illustration |

The script prices four model rows with fixed per-million-token rates, applying the Anthropic 1.25×
five-minute cache-write premium and no OpenAI write premium, and does not generalize those rates to
other models. Its output shows that the bill depends more on cache conditions than on unit price
(warm versus cold differ about fourfold), and that one no-tool round or one TTL expiry per turn
raises warm cost substantially. The assumptions that can change these conclusions — turn spacing,
restricted-round frequency, rounds per turn, chart share, evidence growth and output length — are
what the ledger (CC-7) and the fixtures (CC-8) measure.

Standalone Python helpers for this area use PEP 723 metadata and `uv run <script>`, prefer the
standard library, and declare any third-party dependency in the script itself rather than in the
root project.

## 8. Comparison notes

Behavior Parler deliberately keeps:

- Visualization data can be kept out of the model's context and referenced instead (§2.7 notes
  the current chart lane).
- Deterministic aggregation tools compute exact full-table statistics; `completeness` describes
  source coverage, and egress `resultKind` / `sampleLimit` and the `_LARGE` shape describe sample
  omission. A future sampled statistic would need an equivalent explicit marker.
- Per-call usage and lifecycle are recorded and statistics come only from the ledger (CC-7).
- A bounded semantic summary plus an exact recent tail is an acceptable fallback, provided key facts
  are not dropped unverified and the model summary is never treated as authoritative evidence.

Behavior Parler deliberately avoids:

- Deleting the agent's earlier final answers before each call; follow-ups would lose their basis.
- Treating an in-process TTL registry as the only data location. Parler's artifact cache payload is
  in a FileRepository, while handles are bound to the current JVM index and need refetching after a
  restart.
- Injecting a per-call timestamp at the end of the system message; Parler keeps the time row in
  the volatile tail.

## 9. Open measurement questions

- Actual context windows and separate input limits per model generation are not verified.
- The share of old evidence that later rounds actually reference is not measured.
- The frequency of post-marker, summary-none and retry rounds is not measured.
- The egress size of `chartBlock` is not measured.
- Vendor usage is per request; cache-layer effects can only be inferred under controlled
  conditions.
- Coverage of the compact-evidence rehydration baseline (§2.1) in conversations beyond 60 turns
  is not measured.
- Attribution of 429s across local reservations, upstream input/output limits and RPM/concurrency
  is not separated.

## 10. Verification

- Java changes: `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`.
- Usage evidence for a mechanism change comes from the ledger and the helper (local fakes or an
  operator-supplied export), reporting `captureCoverage`, `unknownCount` and `knownCostUsd`, not a
  single total. Context and tool-surface diagnostics may accompany it but are never a statistics
  input.
- Cache evidence has two kinds: local assertions on the serialized prefix and breakpoints
  (decidable), and request-level `cacheRead` / `cacheCreate` / `cachedPrompt` (layer effects can
  only be inferred).
- Compaction changes must show that protected batches, pending HITL, reference liveness and the
  provenance grading are respected; final answer text is not cleared or rewritten; persisted
  user-visible history is not deleted; bytes are stable between triggers.
- Live ThingWorx and real-provider runs are performed by the operator; the §11 driver lets one
  launch run all turns serially.

## 11. Multi-turn validation driver and suites

### 11.1 Purpose and boundaries

The driver runs a fixed multi-turn suite against a live AgentThing and records per-turn evidence
and ledger usage, so that prompt or description changes can be compared on the same conversation
script. It is a lab tool: it does not change configuration, create helpers, import or reset data,
operate Timers or call a real `healthCheck`. Local tests use fake transports and never contact a
platform or an LLM.

### 11.2 Fixed historical data

- Accurate numeric and chart comparisons use fixed historical windows restored with ThingWorx's
  native Stream / ValueStream export and import (restore entities with generator Timers off, import
  history, then turn Timers on). Timestamps are kept as recorded, never shifted to today. Checks are
  by business key and content; internal entry ids may differ after a restore.
- Current property values, active alerts and "the last 30 minutes" are dynamic scenarios and are
  not scored against fixed numbers.
- A dataset manifest records a dataset id, export file hashes (for local packages), platform and
  entity configuration versions, the actual history windows, entities/properties, time zone, and
  the reference queries and their results. Business data stays with the operator; the repository
  holds manifests and fixtures only. Tool caches and in-memory history are not data snapshots;
  every run starts a new conversation over the same history.
- If a window lacks coverage, the whole comparison moves to a new window with a new dataset id;
  configurations never pick their own windows.

### 11.3 Ten-turn suite

`test_scripts/context_compaction/ten_turns.json` (suite `context-compaction-m1a-ten-turns`) is the
default suite. It runs ten turns in order in **one** new conversation per configuration (never one
conversation per turn). Variables: A and B are two devices with data
(`SE.CellFab.Model.Workunit.ORD-Contacting-01` / `-02`), C a device for property history
(`ORD-Contacting-01`), P a validated numeric property (`CurrentDraw`), U the utilization date
(`2025-09-10`), H the history window (`2026-09-14T18:00:00Z/2026-09-14T18:30:00Z`). Prompts use
full canonical names; identity resolution is tested separately so that a missing entity is not
confused with a routing regression.

| Turn | Request | Judged on |
|---|---|---|
| 1 | UTC utilization status pie chart for A on U | Real UTC date query, full aggregation, chart delivered |
| 2 | Same chart for B on U | Same date and semantics; no device mix-up |
| 3 | Compare hours and percentages per status between the two charts | Consistent with each chart's evidence; correct denominators |
| 4 | Sort the comparison by downtime and show a table | Cached evidence may be reused; no invented columns or handles |
| 5 | Replot A for U using the America/New_York calendar date | Real UTC boundaries re-resolved, not a label change |
| 6 | Explain the differences between turns 1 and 5 | Window change distinguished from data/device change |
| 7 | Trend of P for C over H with average and extremes | Full-window statistics; correct chart, units, completeness |
| 8 | Alert history for C over H and its timing relative to the trend | Historical alerts, not the current summary; no causal claim |
| 9 | Return to turns 1/2 and show both devices again | Early dates, devices and denominators kept; stale references refetched |
| 10 | Summarize findings, data gaps and evidence limits | No alert-to-downtime overreach; multi-turn semantics kept |

Suite settings: helper capture every 30 seconds, two reads (`helperCapture`); client wait 600
seconds per turn; no gap between turns. Each turn is `pass`, `fail` or `insufficient_evidence`.
Reference values come from independent reference service results or complete fixtures, with
fields, ordering, units and rounding stated; the agent's own answers never produce the expected
values. `ten_turns_golden.json` is the synthetic golden used with the fake transport. Without a
reference the judge reports `insufficient_evidence` (`no_reference_evidence`). Empty windows or
missing data never count as analysis success; charts are judged by valid chart wire and data
consistency, with pixel rendering checked by a person. Request-level `done`, LLM success and
complete usage never substitute for a task verdict.

### 11.4 Driver

Files: `test_scripts/run_context_compaction_eval.py` (PEP 723, standard library, `uv run
--no-project`), `test_scripts/context_compaction_alwayson.mjs` (Node adapter), and
`test_scripts/context_compaction/` (`orchestrator.mjs`, `live_transport.mjs`,
`fake_transport.mjs`, `judge.mjs`, `golden_contract.mjs`, `wire_contract.mjs`,
`codec_loader.mjs`, `preflight_codec.mjs`), plus `test_scripts/context_compaction_judge.py`. The
adapter uses the in-repo AlwaysOn client and codec from `parler-ui/lib` (`alwaysOnConnect.js`,
`alwaysOnParlerClient.js`, `alwaysOnInvokeParams.js`); it adds no API server and does not change
UI product code. Node runs with `--experimental-wasm-modules`; missing dependencies are reported,
never installed by the driver.

Python owns the suite, per-turn records and helper reads; Node owns the AlwaysOn connection and
Service calls; they exchange JSON Lines over stdin/stdout. Credentials (`DEV_SERVER`, `DEV_KEY`)
come from the `--env-file` (default repository `.env`) and are passed over stdin, never on the
command line, in outputs or in debug logs. REST and AlwaysOn share the `DEV_SERVER` base; the
WebSocket URL is `<base>/Thingworx/WS`.

Options: `--suite`, `--output-dir` (required, and must be outside the repository), `--env-file`,
`--execute`, `--fake-transport`, `--agent-thing`, `--gateway-thing`, `--golden-manifest`,
`--helper-thing` (default `AgentLlmUsageHelper`; pass the name of the helper Thing the operator
created).

- Without `--execute` the driver only validates and expands the plan. With `--execute` it sends
  requests. Without `--gateway-thing` it creates a new conversation through
  `GetOrCreateConversationId` with a unique title; `--gateway-thing` selects an existing
  conversation and does not clear its history. It binds that conversation's Gateway and calls
  `GetConnectionInfo` to check the bound Agent. `SubmitUserPrompt` uses the existing parameter
  builder, keeping the UI's `userTimezone` / host-context semantics and never overriding
  `systemPrompt`. The widget must not be connected to the same conversation during a run.
- Only one turn is in flight. The event receiver is installed before sending and buffers
  acks/content that arrive before the invoke returns; events are correlated by conversationId +
  requestId, and stale `done` events never advance the next turn. The next turn starts only after
  the matching terminal event is recorded.
- `done` ends a turn and triggers judgment. `error`, `session.cancelled` and `session.superseded`
  are recorded and stop the conversation. `approval.required` stops in a waiting state and is
  never auto-approved. Disconnects, timeouts or a missing requestId are `completion_unknown` and
  stop without resubmitting (no duplicate cost or history pollution, and no claim that backend work
  was cancelled). Invoke timeout 120 seconds; client wait per turn 600 seconds unless the suite
  overrides it.
- Usage comes from the helper for the run's `StartTime`/`EndTime`, `conversationId` and
  `agentThing`, with Provider/model taken from each call's snapshot. Omitted STRING filters are left
  out of the request body, not sent as JSON `null`. Message history may be read for evidence but is
  never a token or cost fallback. A helper failure does not zero the cost or resubmit a prompt.
- **Capture rule**: after the terminal event, wait the configured interval (default 30 seconds) and
  read the helper twice, keeping both `reportAsOf` values and full reports. `knownCostUsd` is always
  an `observed_only` lower bound: it may miss calls not yet visible and never overstates. Two equal
  reads only show the lower bound was stable over the interval; they do not prove complete capture
  or a final total. Automatically captured cost is never used to claim savings in either direction,
  and two observed lower bounds support no directional conclusion. A `completion_unknown` turn may
  still have backend work running; its cost stays non-final.
- Outputs: `run.json` (suite, dataset, configuration, version, times), `events.jsonl` (every
  received event), `turns.json` (verdicts and correlation), `usage-report.json` (both helper
  reports with observation timestamps; never marked final or savings-eligible). Partial runs keep
  what was received. A restart creates a new conversation by default and never resumes an uncertain
  turn.

Local tests: `test_scripts/test_context_compaction_eval.py` (unittest) and
`test_scripts/context_compaction_alwayson.test.mjs` (Node test runner) with fake transports cover
ten serial turns, acks and terminals arriving before the invoke returns, duplicate and stale
terminals, missing charts, error/cancel/supersede/approval/disconnect/timeout, helper fields
missing and unknown cost, and the capture rule (a stable non-empty subset that is still not final,
later calls arriving, growth between reads, and observed lower bounds never marked
savings-eligible). Time is injectable so tests do not sleep.

### 11.5 Other suites

The same driver runs these suites unchanged; each has a `suite.json`, a `reference-manifest.json`
holding review criteria only (so the automatic judge reports `insufficient_evidence` /
`no_reference_evidence`), and a `README.md` with run commands and data assumptions:

| Directory | Suite | Exercises |
|---|---|---|
| `test_scripts/context_compaction/user_baseline/` | `user-context-baseline-ten-turns-v1` | Ten fixed prompts that deliberately omit the time zone and (in turns 3–5) the year; used to compare prompt configurations |
| `test_scripts/context_compaction/chart_kinds/` | `chart-kinds-ten-turns-v2` | Histogram, boxplot, heatmap, stacked/percent bars, chart groups and the empty-result guard |
| `test_scripts/context_compaction/computing_ops/` | `computing-ops-ten-turns-v1` | `tabulate_cached_result` computing operators (`counter_delta`, `rolling_stats`, `time_weighted`, `calendar_bucket`) and the read-limit marking |

A transport `done` is never a task pass; the saved `events.jsonl` is reviewed against each suite's
criteria.
