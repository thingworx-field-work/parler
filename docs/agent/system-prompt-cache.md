# System prompt cache

## Goal

Improve provider-side prompt-cache reuse on OpenAI-compatible Chat Completions and Anthropic Messages by separating stable `AgentThing` context from per-turn/per-round dynamic context, while reducing repeated ThingWorx service work.

This design targets `parler-agent` prompt assembly and resolver-side metadata reads. It does not change Parler wire JSON, chart/table payloads, or UI reducer contracts.

Provider scope:

- **OpenAI Chat Completions / Azure OpenAI:** the leading row is stable and every framed volatile row is relocated to the terminal system-role suffix.
- **Anthropic Messages:** the leading stable row remains in the top-level `system` field; every framed volatile row is consumed into terminal user-content carriage, and history cache markers follow the request kind (see `anthropic-breakpoint.md` and the serializer notes below).

## Problem

Runtime LLM input is assembled in `AgentThing.buildLlmTurnContext` and sent by `AgentLoop`.

Without a cache, mostly-static blocks would be rebuilt per turn or per tool call:

- `/taxonomies/type-taxonomy.md` (configuration repository) when loaded into the stable taxonomy block
- `GetAlertPrompt`
- `GenericThing.GetIncomingDependencies` through model-key resolution

The implementation separates stable prompt rules from volatile values. `ParlerTimeAnchor` owns stable guidance and deterministic value formatting, while `LlmUtcClockInjector` materializes one framed time row per provider round. `LeadingStablePromptComposer` owns the stable final-answer evidence rule; slash skill bodies, host scope, task evidence, conditional coverage guidance, and time values are all framed before planning and relocated as one terminal suffix.

## Prompt Layers

Every system-shaped input is classified:

| Input | Stability | Treatment |
|-------|-----------|--------------|
| `AgentSettings.systemPrompt` | Config-stable | First stable segment; per-call override may replace it. |
| Built-in tool routing guide + replay-format guide | Deployment-stable | Appended when `appendBuiltInToolRoutingGuide=true`. Concise form (`## `-headed sections: identity/scope, discovery and service calls, cached calculations, charts and property history, document knowledge; then the five compact `$format` rules). One owner per rule; per-tool parameter semantics stay in the advertised tool descriptions. |
| `/taxonomies/type-taxonomy.md` prefix (optional) | App model-stable | Stable cache suffix when `taxonomyPromptInjection=full_table`; omit when empty (also omitted when `taxonomyPromptInjection=resolver_guidance_only`). |
| `/taxonomies/identity-types.json` (semantic resolver cache) | App model-stable | Loaded into `applicationSemanticTaxonomy`; not injected as a full table when `resolver_guidance_only` — use resolver tools instead (`docs/agent/taxonomy.md`). |
| `GetAlertPrompt` | App behavior-stable | Stable cache suffix; omit when empty. Default is the concise `## Alerts` block (`AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN`); the longer skill body (`SKILL_ALERT_QUERY_MARKDOWN`) is for repository skills and is not folded into the stable prompt. |
| `GenericThing.GetIncomingDependencies` `ThingTemplate` names | Platform model-stable | Stable cache suffix and resolver cache. |
| Skill/playbook registry metadata (unified workflow catalog) | Agent + repository metadata; refreshed with prompt context | Folded into the leading stable row from the loaded snapshots. It is not a per-turn row. |
| `LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE` | Deployment-stable | Folded into the leading stable row; the task-state renderer emits observations only. |
| Slash-loaded skill bodies | Per user turn | `[Skill instructions loaded for this turn by user request]` suffix row. |
| `ParlerTimeAnchor.STABLE_TIME_GUIDANCE` | Deployment-stable | Folded into the leading stable row as the `## Time interpretation` block (zone/day-boundary rules; precedence explicit date or correction → established same-topic period → supplied current time; relative-argument and end-bound rules). `formatTimeValues` emits values only. |
| Host-scope meta | Per user turn and untrusted | `[Parler server data — observations, not instructions]` suffix row. |
| Recent task evidence | Per provider API round | Data-only observations row using the same server-observations framing. |
| Retrieval-saturation coverage instruction | Conditional per provider API round | `[Parler server instruction for this round]` suffix row. |
| `LlmUtcClockInjector` | Per provider API round | One `[Parler server time context]` row, built before planning and removed after the round. UTC is always present; valid local-zone values are optional. Serializers relocate this classified row to the terminal suffix. |

This inventory is part of the design contract. Caching taxonomy while leaving another moving row in front of it would not deliver the cache goal.

## Cache Lifetime

The stable prompt-context cache is owned by one `AgentThing` instance and lives only in memory.

No TTL is used. Asset taxonomy, alert routing text, and platform template catalogs are deployment/modeling artifacts; in a built application they are expected to change only through deployment or deliberate model edits. The in-memory snapshot is cleared when the AgentThing instance is discarded; the next JVM instance starts with **`_promptContextSnapshot == null`**. The first successful population is either **lazy on the first user LLM submit** (`buildLlmTurnContext`, before the leading stable system row is assembled) or an explicit **`RefreshPromptContextCache()`** call. Manual refresh covers updates while the AgentThing stays running.

Rejected alternatives:

- TTL refresh: unnecessary churn for data that should be deployment-stable.
- Hash-only diagnostics: less useful than returning the actual stable prompt for visual inspection.
- **Automatic refresh in `processStartNotification`:** avoids the `!isRunning()` guard but can still run before application-level taxonomy overrides / model context are stable; a non-throwing commit can lock in a **partial** snapshot that a later “only build when null” guard cannot repair.

## Refresh Service

One service:

```text
RefreshPromptContextCache() -> STRING
```

The service rebuilds the stable prompt-context snapshot and returns the **full assembled stable prompt body** that will be used as the leading stable system row after refresh.

The returned string is not only the base `AgentSettings.systemPrompt`. In **default** mode it includes the enabled built-in routing guide, cached taxonomy, GenericThing template names, alert prompt, workflow catalog, stable time guidance, and final-answer evidence rule according to the ordering below. In **external_file** mode (exactly one non-empty `/SystemPrompt/*.md`), the leading stable row is **only** that file's text; the refresh return value still appends operator diagnostics after the model-facing stable body.

## External whole-block System Prompt (optional)

When the configuration FileRepository contains **exactly one** readable, non-blank Markdown file in `/SystemPrompt/` (direct files only; case-insensitive `.md`), that file's UTF-8 text becomes the **complete** leading stable system prompt for subsequent turns after the refresh commits. The runtime does **not** append the default assembled suffix blocks in this mode.

- **Multiple** `.md` files, list/read failures, or whitespace-only content: log an error, record a fallback diagnostic, and use the default assembly for that refresh (do not silently retain a prior external selection after a successful refresh that finds an invalid directory state).
- **Per-call** `systemPrompt` override is **ignored** when external-file mode is active; the file owns the entire stable block.
- Selection is cached in `PromptContextCacheSnapshot.externalSystemPrompt` and reloaded only through prompt-context refresh (lazy first submit or `RefreshPromptContextCache()`), not on every Provider round.
- Operator inspection: `GetAgentRuntimeSnapshot` exposes `prompt.stablePromptSource`, `prompt.externalSystemPromptPath`, and `prompt.externalSystemPromptFallback` (see `docs/agent/configuration-repository.md`).
- **Operator authoring:** external whole-block files must explicitly include any product-invariant guidance the Operator still wants the model to see — especially stable time guidance and the final-answer evidence rule, which default mode appends automatically. Start from `GetAgentRuntimeSnapshot` with `includePrompt: true` while still in default mode.
- **Refresh diagnostics:** after the model-facing stable body, `RefreshPromptContextCache` appends `## External system prompt selection` with source/path/fallback; matching lines also appear under `prompt.diagnostics`.

**Skill registry diagnostics appendix:** the same service return value also appends a second section after a `---` delimiter and a `## Skill registry diagnostics` heading (per `docs/agent/skill-management.md`). That appendix summarizes service vs repository skill counts and refresh-time scan messages. It is intended for **operators** inspecting the service result and is not part of the leading stable row; the model-facing workflow catalog is the separately rendered stable block in default mode only.

Manual refresh failure behavior:

- Build a complete new snapshot first.
- Replace the active snapshot only after the full rebuild succeeds.
- On failure, keep the previous snapshot unchanged.
- Log `logger.error`.
- Throw the exception to the caller.

**Asset taxonomy sub-step (best-effort):** If **`/taxonomies/type-taxonomy.md`** read throws, or structured **`identity-types.json`** load / `TaxonomyRow` projection fails, the implementation logs `logger.error`, leaves the cached taxonomy Markdown segment and structured taxonomy rows **empty** for that refresh, and **continues** building the rest of the snapshot. This matches **`### Refresh failure semantics (normative)`** in `docs/agent/skill-management.md` — taxonomy failure alone must not abort the entire refresh.

**GenericThing template-name sub-step (best-effort):** If the cached sorted GenericThing ThingTemplate name list (see `GenericThingIncomingDependencyResolver`) cannot be built, the implementation logs `logger.error`, leaves the cached GenericThing block **empty** and the structured name list **empty**, and continues.

**Alert prompt sub-step (best-effort):** **`GetAlertPrompt`** is the overridable, parameter-less AgentThing service that returns **Markdown instructions for ThingWorx alerts tooling** (for example routing for `query_alert_history` / `acknowledge_alerts`; see `docs/operations/alert-solution.md` and bundled `_skill_alert_query`). If `GetAlertPrompt` dispatch throws, the implementation logs `logger.error`, leaves the cached alert prompt block **empty** for that refresh, and continues. An empty block is equivalent to “no alert instructions in the stable suffix” for that refresh.

**Do not** refresh in `initializeThing`: platform service dispatch for this Thing’s own overrideable services is rejected while `!isRunning()`, so taxonomy / alert overrides would be skipped and the log would show *Thing is not running*.

**Lazy first-use (automatic only when empty):** immediately before the leading stable system row is assembled in `buildLlmTurnContext`, if `_promptContextSnapshot` is still `null`, the implementation acquires the prompt-cache lock, double-checks `null`, and runs the same full build + commit path as manual refresh (`commitPromptContextCacheRefreshLocked`). On that path the Thing is already **ON**, so overrideable services dispatch normally. **Once a snapshot exists, it is not auto-refreshed** — updates use `RefreshPromptContextCache()` only.

Lazy first-use failure behavior is intentionally different from the manual service:

- Log `logger.error`.
- Do not throw to the caller; the chat / stream turn continues.
- Leave `_promptContextSnapshot` unchanged (still `null` if the build failed).
- Operators may call `RefreshPromptContextCache()` to repair after fixing the underlying error.

Reason: a failed lazy build must not block user traffic; explicit manual refresh remains the repair hook.

If the active snapshot is still empty after a failed lazy build (or before any successful build), chat paths should log a throttled warning such as:

```text
Stable prompt context cache is empty; run RefreshPromptContextCache after fixing startup errors.
```

Throttle this warning per AgentThing, suggested once every 15 minutes. Reset the throttle state after a successful `RefreshPromptContextCache()` call so a later fresh failure can emit a new `WARN` immediately.

## Stable Prompt Ordering

The stable prefix should be ordered as:

```text
AgentSettings.systemPrompt or per-call systemPrompt override
---
built-in tool routing guide, when enabled
---
cached taxonomy block, when non-empty
---
cached GenericThing ThingTemplate names block, when non-empty
---
cached alert prompt block, when non-empty
---
workflow catalog, when non-empty
---
`ParlerTimeAnchor.STABLE_TIME_GUIDANCE`
---
`LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE`
```

The `built-in tool routing guide` line is two bundled resources joined by a separator: `llm_tool_routing_guide.txt` then `llm_replay_format_routing_guide.txt`. Block ownership (one owner per rule): identity/scope, discovery, cached calculations, charts and document knowledge → routing guide; compact `$format` interpretation → replay-format guide; alert routing → `GetAlertPrompt`; time precedence and zone rules → `STABLE_TIME_GUIDANCE`; answer grounding → `FINAL_ANSWER_EVIDENCE_RULE`. Deployment-dependent blocks (taxonomy Markdown, GenericThing template names, alert override, workflow catalog) are generated from the snapshot and registries and are not part of the bundled prose.

Per-call `systemPrompt` override replaces only the first base system prompt segment. It does not bypass cached taxonomy, GenericThing, or alert context.

Each per-call `systemPrompt` override changes the leading bytes for that turn and therefore should be counted as a provider-cache miss for monitoring purposes. Product paths should avoid routine per-turn overrides when prompt-cache hit rate matters.

Both taxonomy sources are optional. If the repository type-taxonomy file is absent or empty **and** structured taxonomy is unavailable (so the taxonomy Markdown segment and cached **`TaxonomyRow`** list are both empty), omit the taxonomy section entirely. Do not emit an empty heading or separator.

### Leading System Row Invariant

For OpenAI-compatible prefix caching, the assembled stable prompt must be the leading system row of the provider request. It is not enough for the cache snapshot to exist in Java memory, and it is not enough to append cached taxonomy / GenericThing / alert sections after stored conversation history.

Implementation rule:

- `messages[0]` (or the request copy's first row) must be the full stable prompt for that turn.
- Cached stable sections must be folded into that leading row before the first non-system history row.
- Continuing threads must not keep an out-of-date leading stable row forever. At turn start, use the current snapshot to ensure the leading row matches the current stable prompt.
- Recommended implementation: at chat turn entry, unconditionally construct or overwrite the request copy's `messages[0]` from the current snapshot. The cost is one string assignment per turn, which is negligible compared with the LLM call.
- Treat the leading stable row as prompt context, not user/assistant history; replacing it on refresh is allowed.
- Fold the workflow catalog, stable time guidance, and final-answer evidence rule into the row. A prompt-context or playbook-registry refresh may legitimately rebuild these deployment-stable bytes.

This invariant is the acceptance point for the cache work. If implementation stores cached blocks as later mid-conversation system rows, OpenAI/Azure prefix-cache metrics may remain flat even though the Java cache is populated.

## Taxonomy Cache

Taxonomy is cached permanently in memory until manual refresh or Thing reinitialization.

The cache should preserve two consumable forms:

- Model-visible Markdown block for stable system prompt assembly.
- Immutable structured rows for Java-side taxonomy lookup in `ModelKeyResolver`.

Do not store and share a live mutable `InfoTable` in the snapshot unless it is defensively copied and treated as read-only. Prefer converting taxonomy rows to immutable value objects during snapshot construction.

This matters because taxonomy has two current consumers:

- Prompt injection in `AgentThing`.
- Tool-side parent resolution in `ModelKeyResolver`.

If only prompt injection reads the cache, Java tool execution still pays the per-call taxonomy service cost.

## GenericThing Incoming Dependencies Cache

`GenericThing` is a `ThingTemplate`. Its incoming dependency list is used as a platform-version-specific catalog of ThingTemplate model keys that should be treated as `query_entities` `thingTemplate` parents.

Only dependency rows whose `type` is exactly `ThingTemplate` are kept, and only their `name` values are stored. Names are sorted in a stable case-insensitive order and de-duplicated before rendering or resolver use.

Empirical basis: a representative `GenericThing.GetIncomingDependencies(maxItems=2000)` response contained approximately 44 `ThingTemplate` rows, approximately 21 concrete `Thing` rows, and zero `ThingShape` rows. The `ThingTemplate` rows included the intended platform model keys such as `DataTable`, `Stream`, `ValueStream`, `IoTStream`, `RemoteThing`, `FileRepository`, `Timer`, `Scheduler`, `CacheThing`, and deployment-specific templates. Concrete `Thing` rows are not valid QIT parents and remain ignored.

The prompt block must be extremely compact, for example:

```text
## Platform ThingTemplate model keys
Known GenericThing-derived ThingTemplate names: DataTable, IoTStream, Stream, ValueStream
When the user asks to count or list Things for one of these names, use query_entities with thingTemplate exactly matching the name.
```

The Java resolver must read this cached `List<String>` rather than invoking `GenericThing.GetIncomingDependencies` during each tool call. If the snapshot is absent because initialization failed, the resolver may fall back to the current live service path as a correctness fallback, but that fallback must be explicit and logged at debug/warn level as appropriate.

The GenericThing path (key-resolution "Phase 0.5") therefore covers ThingTemplate names only; `ThingShape` rows are not part of it. The code touchpoints are `GenericThingIncomingDependencyResolver`, `IncomingDependencyMatcher`, `ModelKeyResolver`, the routing guide, and the `BuiltInTools` descriptions (see also `key-resolution.md`).

## Alert Prompt Cache

`GetAlertPrompt` follows the same override pattern as taxonomy and is read into the stable prompt-context cache. Its default is the concise `## Alerts` block: current summary versus persistent history, one-to-many `thingNames[]` batching and `completeness`, history time arguments and the seven-day default, acknowledgement scope (`specific_alerts` / `property_all`, never `AcknowledgeAlert` without `sourceProperty`), the `invoke_service` fallback on `Resources["AlertFunctions"]`, the two field dictionaries and the QUERY predicate/sort shape. The bundled routing guide carries no alert rules of its own, so an override replaces all default alert guidance and an empty override removes it.

`GetAlertPrompt` is parameter-less by contract, so caching it at the Thing level is safe. Per-user or per-turn alert content could not live in the stable snapshot.

If an application overrides `GetAlertPrompt`, operators must call `RefreshPromptContextCache()` or reinitialize the AgentThing after changing that override. Returning an empty string still disables the alert prompt block.

## Hierarchy Is Out of Scope

Hierarchy data is not folded into this stable system prompt cache. Keeping it out avoids mixing user/host scope concerns into a global prompt prefix; any hierarchy caching belongs in a Java-side cache (for example ThingWorx `CacheThing`) closer to platform execution.

## Time suffix placement

The dynamic time values must not enter the leading stable row. `LlmUtcClockInjector` uses a round-scoped insertion near the tail:

- On the initial model call for a turn, insert the UTC clock immediately before the current user message.
- On follow-up tool-result calls, insert the UTC clock immediately **after** the newest trailing tool-result block for that API round.
- Remove the exact inserted clock row after the provider call in a `finally` block.
- Do not rely on "the clock is first" removal logic.

This preserves the stable system prefix and as much conversation-history prefix as possible while preserving assistant tool-call / tool-result adjacency for OpenAI-compatible providers. Do not place a system row between an assistant message with `tool_calls` and the corresponding `tool` messages; some providers validate that sequence strictly.

The insertion helper returns the inserted index. Removal matches both `ParlerSuffixFraming.TIME_CONTEXT` and the row-unique `\n- now_utc: ` continuation; a different time-authority row cannot be removed accidentally.

The logical provider layout after serializer relocation is:

```text
[leading stable system row]
[stored conversation history]
[current user message]
[terminal suffix: skill, host observations, task observations, coverage instruction, time]
```

For tool follow-up provider calls:

```text
[leading stable system row]
[stored conversation history up to assistant tool_calls]
[trailing tool result message(s)]
[terminal suffix: skill/host when present, task observations, coverage instruction, time]
```

The UTC clock row is provider-round ephemeral and must never persist in `_conversations`, stream history, or HITL pending snapshots.

Regression tests should cover:

- single-round user turn
- multi-iteration tool loop
- multiple tool results in one round
- provider exception path
- max-iteration or timeout path
- HITL pending path, ensuring pending snapshots do not retain the UTC clock row

Provider serializer notes:

- OpenAI/Azure Chat Completions: `ChatCompletionsApiMessages` keeps **`messages[0]`** first and stable-partitions classified rows to the end while retaining their `system` role.
- Anthropic Messages: `AnthropicMessagesApi` keeps unclassified system rows in top-level `system`, but consumes classified rows into terminal user-content blocks. When an assistant is the durable tail, one suffix-only user carrier follows it.
- The suffix order is skill instructions, server observations (host before task evidence by construction), server instruction, then time. An assembly guard fails tests on any unclassified non-leading `SYSTEM` row.
- Anthropic AgentLoop requests use block arrays for every substantive user row and mark the current and previous
  substantive frontier before the suffix. Interactive entrypoints reject null/trim-empty input; a registered
  slash-only turn retains the trimmed raw directive as its durable/model-facing row. Anthropic also rejects any
  residual null/trim-empty ordinary user row before grouping. False-kind probes, playbooks, and checkpoint
  summaries retain non-empty ordinary user strings. Stable system/tool markers remain independent of that request
  kind, and the total marker count is at most four.
- Both provider serializer families return content-free diagnostics for unknown non-leading system rows. Clients
  warn with planned index, role, character count, and the first 16 hex characters of a full-content SHA-256;
  unknown content itself is never logged. The fallback stays authority-preserving: Anthropic top-level system,
  OpenAI/Azure source position.

Relative-time prompts (`last 30m`, `today`, `yesterday`) on Anthropic are a useful manual check of the clock relocation.

## Snapshot Shape

Implementation can use a single immutable snapshot referenced by `volatile`, for example:

```java
final class PromptContextCacheSnapshot {
    final String stableContextSuffix;
    final String taxonomySystemBlock;
    final List<TaxonomyRow> taxonomyRows;
    final List<String> genericThingTemplateNames;
    final String genericThingTemplateNamesBlock;
    final String alertPromptBlock;
    final Instant loadedAtUtc;
}
```

`TaxonomyRow` should be a small immutable value type carrying the fields needed by taxonomy prompt rendering and `ModelKeyResolver`: `assetType`, `entityType`, `entityName`, `synonyms`, and `criticalProperties`. Build `synonyms` as a normalized immutable `List<String>` during snapshot construction, using the same normalization rules as the current resolver. Keep `criticalProperties` as the rendered/lookup string unless implementation finds an existing typed consumer that needs a parsed form.

Refresh builds a new snapshot locally and swaps the active reference only on success. Refresh itself should be synchronized to avoid concurrent rebuilds.

Chat and tool paths should capture the snapshot reference once per turn or resolver call and then read fields from that captured object. Do not read `_promptContextSnapshot` repeatedly inside one turn, because refresh may swap the reference between reads.

No content hash is required. The refresh service returns the stable prompt text directly for human inspection and debugging.

## Operational Notes

There is intentionally no separate status service. `RefreshPromptContextCache()` is the manual inspection and repair entrypoint; running it one extra time is harmless. (`GetAgentRuntimeSnapshot` exposes prompt-source fields without returning a large prompt body.)

Operators must run `RefreshPromptContextCache()` on each Parler node / each relevant AgentThing after changing:

- asset taxonomy services or backing data
- `GetAlertPrompt`
- platform model entities relevant to `GenericThing.GetIncomingDependencies`
- deployed routing-guide resources
- AgentSettings values when the platform does not reinitialize the Thing automatically

The refresh service may return a large string on rich deployments. This is accepted because the service is explicit and diagnostic.

Logs should not print the full stable prompt by default. Log compact diagnostics only:

- refresh status
- taxonomy row count
- taxonomy block character count
- GenericThing ThingTemplate name count
- alert prompt character count
- stable prompt character count
- whether the stable prompt is likely long enough to qualify for provider prefix caching, when that can be estimated cheaply. OpenAI prompt caching currently requires at least 1024 tokens in the prompt before cached tokens can be non-zero; check current provider docs for model-specific behavior. If the stable-prefix estimate is below 1024 tokens, log that cache hits should not be expected from the stable prompt alone.
- error details on failure

Resolver fallback observability:

- First fallback to live taxonomy / GenericThing service after a missing snapshot should log `WARN`, throttled per AgentThing, suggested once every 15 minutes.
- Subsequent fallback calls within the throttle window should log at `DEBUG`.
- The warning should tell operators to run `RefreshPromptContextCache()` after fixing startup errors.
- Reset fallback throttle state after a successful `RefreshPromptContextCache()` call so a later new failure is not hidden by an older throttle window.

## Verification

Prompt-carriage changes come with focused serializer/composer/lifecycle tests and the repository's full local Java
test run. Provider/ThingWorx cache-hit measurements are taken separately on a live deployment.

## Contract Impact

There are no Parler wire JSON changes, so `CONTRACTS/API_CONTRACT.md`, `CONTRACTS/UI_CLIENT_PROTOCOL.md`, and `CONTRACTS/CONTRACT_VERSION.md` do not need updates for this cache alone.

If taxonomy Markdown rendering changes, keep `CONTRACTS/AGENT_TAXONOMY_RENDERING.md` aligned. Moving cached taxonomy earlier in the system prompt does not by itself change taxonomy rendering.

The GenericThing ThingTemplate-only narrowing is not a wire-contract change; agent docs, built-in tool descriptions, and model-visible routing guide text describe it.
