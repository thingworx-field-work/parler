# Prompt-cache prefix stability and the Anthropic breakpoint

**Status:** implemented, including the pressure-gated Tier B correction (Bug 014) and the non-empty
interactive user-text rule (§6.5).

**Scope:** provider-neutral prompt assembly and stored-history mutation — a complete cacheability disposition for **every** per-turn and
per-round injection — plus Anthropic cache-control changes, specified as a closed
wire design for the two target surfaces: Claude Sonnet 4.6 on Microsoft Foundry's native Anthropic Messages
endpoint, and OpenAI / Azure OpenAI Chat Completions.

**Evidence rule (normative for this document):** every material wire/cache rule cites formal vendor evidence from
the §2 matrix; repository evidence covers Parler's own lifecycle facts. Fixtures and evals supplement vendor
documentation and never substitute for it.

## 1. Problem

Parler's serialized provider prefix is volatile on **both** wires — not because of two isolated injectors, but
systematically. `AgentThing.buildLlmTurnContext(...)` appends per-turn `SYSTEM` rows (workflow catalog, slash-skill
block, time anchor, optional host-scope block) after stored history on every user turn and
`applySkillTurnMutationFinish(...)` strips them at turn end; `ParlerTimeAnchor.buildSystemInstruction(...)` embeds
`Instant.now()` as `now_utc`/`now_local`; per round, `LlmUtcClockInjector` (fresh instant, moving position) and
`TaskStateLlmInjector` (mutating evidence view, moving position) insert two more `SYSTEM` rows, and `AgentLoop`
conditionally inserts byte-constant round instructions for retrieval saturation
(`DocumentCoverageSummaryInjector`) or one bounded empty-final recovery (`EmptyFinalAnswerRetryInjector`). Their
per-round appearance would still change the serialized `system` field on the Anthropic wire without suffix carriage.

Provider consequences (what caches is the serialized request, not the Java list):

- **Anthropic wire:** `AnthropicMessagesApi.putAnthropicSystemField` hoists **every** `SYSTEM` row into the
  top-level `system` field, and the cache identity covers `tools` → `system` → `messages` as one prefix. Any
  changed byte in `system` — a timestamp above all — invalidates the messages cache (`system_changed` in Anthropic's
  cache diagnostics; "breakpoint after a timestamp" is the guide's named common mistake). With the per-turn rows
  hoisted, the prefix diverges every round **and** every turn. [V2][V3]
- **OpenAI / Azure OpenAI wire:** `ChatCompletionsApiMessages` keeps later system rows in their logical positions;
  the implicit prefix cache reuses bytes up to the first difference. The per-turn rows sit between stored history
  and the new user message, so each new turn diverges at the previous turn's ephemeral position and loses the whole
  final turn; the per-round rows truncate reuse inside the turn. [V6][V7]

Observed incident (development server, 2026-08-25 00:38–00:39 UTC, `claude-sonnet-4-6`,
`anthropic-messages-v1`): constant `cacheRead=30946` (tools + leading stable system, the only stable prefix),
`cacheCreate=0`, per-round uncached input 14,990 → 22,912, then HTTP 429 on
`UserByModelByMinuteUncachedInputTokens` (50,000/60 s, `retry-after=18`). The enforce-mode local gate admitted every
request against its 200k total-token budget; it does not model the uncached-input dimension, and this design is
mitigation, not gate coverage. Reproduction prompts: `dev_data/scpa_utilization/429_prompts.txt`.

The reset 0.1.222 replay exposed a second, independent prefix-instability class after the suffix fix: routine
post-turn Tier B promotion changed tool-result bodies that earlier requests had already sent and cached. A
`promoted=1` boundary was followed by `cacheRead` 38,139 → 33,790; a `promoted=7` boundary was followed by
66,453 → 32,045 and approximately 35,927 cache-creation tokens, after which the next append-only request recovered
to `cacheRead=67,972`. Planner drop counters were zero at both boundaries. Bug 014 therefore pressure-gates Tier B
on the existing storage cap: within-cap stored history is append-only; an over-cap Tier B/checkpoint/trim pass is
one intentional bounded-memory cache epoch (Bug 014).

## 2. Vendor evidence matrix

Checked 2026-08-25. Each rule cites its row; each row records what the source does **not** cover.

| Id | Surface | Source | Establishes | Does not cover |
| --- | --- | --- | --- | --- |
| V1 | Claude on Microsoft Foundry | [Deploy and use Claude models in Foundry](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-claude); [Claude model capabilities](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/claude-models) | The target speaks the native `/anthropic/v1/messages` API; Sonnet 4.6 and prompt caching are supported there. | Any OpenAI-compatible Claude surface; features Anthropic gates to other models (see V5). |
| V2 | Anthropic cache identity | [Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) | Cache key covers `tools` → `system` → `messages`; a read requires an earlier request to have **written an identical, still-live prefix** (default 5 m TTL, refreshed on use); tool-definition or `system` changes invalidate everything after them; ≤ 4 `cache_control` breakpoints; breakpoints add no cost — charges follow writes (1.25×) and reads (0.1×); cache-hit lookback searches at most ~20 content blocks behind a breakpoint, so a moving final breakpoint alone is valid only under <20-block growth; otherwise retain an additional breakpoint near the prior write; empty text blocks cannot be cached. | Uncached-input rate-limit accounting of any hosting platform; any guarantee that a marker produces a read. |
| V3 | Anthropic cache diagnostics | [Cache diagnostics](https://platform.claude.com/docs/en/build-with-claude/cache-diagnostics) | `system_changed` is the named failure for a timestamp in `system`; `messages_changed` means earlier messages were altered/reordered/removed instead of appended. The remedies are a byte-stable `system`, dynamic data after the breakpoint, and append-only/verbatim history. | — |
| V4 | Anthropic tool continuation | [Handle tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls); [Messages type reference](https://platform.claude.com/docs/en/api/typescript/messages) | A client `tool_result` follows its `tool_use` in the next user message; `tool_result` blocks come first in that message and text blocks may follow **when the assistant turn contains only client tools**; an unresolved server-tool call requires a tool-result-only user message; `tool_result` supports `cache_control`; assistant messages carry AI-generated content and `tool_use`. | Parler server-tool enablement; the target Parler request builder currently emits ordinary client tool definitions only. |
| V5 | Anthropic mid-conversation system | [Mid-conversation system messages](https://platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages) | A late system lane exists **only** for named Fable/Mythos/Opus models on Claude API / Bedrock / Google Cloud. | Sonnet 4.6; Microsoft Foundry. **Parler MUST NOT use it for the incident target.** |
| V6 | Azure OpenAI caching | [Azure OpenAI prompt caching](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/prompt-caching) | Identical initial prefixes cache; a one-character change misses; stable/repeated first, dynamic last, append-only history; `cached_tokens` is the signal; the **complete `messages` array is cacheable** — an identical repeat can read through the request tail, including volatile content, so a warm-identical read is not a prefix-only baseline; hits start at 1,024 tokens; on **GPT-5.5-and-earlier model families** hits then grow in 128-token increments — a rounding the same document (updated 2026-07-17) states does **not** apply to GPT-5.6 and later, which have different rounding/reporting rules; 128 is also the **currently configured target deployment's measured** granularity, recorded as deployment evidence, never a provider-wide constant. | Anthropic-style `cache_control` is never applicable to this wire; Azure/OpenAI's own model-specific explicit-breakpoint fields on eligible GPT-5.6+ deployments remain the declared v1 non-goal pending per-deployment capability verification (V7, §9). |
| V7 | OpenAI caching and roles | [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching); [Chat Completions reference](https://developers.openai.com/api/reference/cli/resources/chat/subresources/completions) | Timestamps belong after the stable prefix; GPT-5.6+ adds capability-scoped explicit breakpoints; a `tool` message is tied to `tool_call_id`; system-role messages are positionally accepted mid-list; an `assistant` message represents model-generated content. | Azure deployment-level capability of explicit breakpoints (verify per deployment before any use). |
| V8 | Anthropic input text validity | [Messages API reference](https://platform.claude.com/docs/en/api/messages/create); official Anthropic SDK reports [Python #461](https://github.com/anthropics/anthropic-sdk-python/issues/461) and [Go #377](https://github.com/anthropics/anthropic-sdk-go/issues/377) | A string message is shorthand for a one-element text-block array; the formal reference provides no permission for an empty input text block. Anthropic's own SDK repositories record that replaying an empty text block is rejected and show a current 400 with `minimum string length is 1`. Parler therefore treats non-empty user text as an outbound invariant and MUST NOT bless or send an empty ordinary user text block. | A Microsoft Foundry-specific error string or evidence that Foundry relaxes the native Anthropic schema. Parler does not depend on either. |

## 3. Cacheability classes and the complete injection inventory

Classes are assigned on the **serialized provider request**, after each serializer has reordered fields:
deployment-stable / turn-stable / append-only / round-volatile, with the assembly rules: the reusable prefix must be
byte-identical [V2][V6]; dynamic content serializes after the frontier [V2][V3][V6][V7]; on the Anthropic wire **no
`SYSTEM` row may carry changing content** (hoisting puts every `SYSTEM` row into the prefix) [V2][V3]; history stays
append-only across rounds and ordinary within-cap turns [V3][V6][V7]. A deliberate over-cap
Tier B/checkpoint/trim pass is one bounded-memory cache epoch, after which append-only growth resumes.

### 3.1 Inventory of every injected row (normative disposition)

| Row (today) | Source | Content stability | New carriage |
| --- | --- | --- | --- |
| Leading stable system row | `LeadingStablePromptComposer` / `applyLeadingStableSystemRow` | deployment-stable (rebuilds only on config change) | unchanged; keeps its breakpoint |
| Workflow catalog `SYSTEM` row | `AgentWorkflowCatalogFormatter` from the prompt-context snapshot | deployment-stable content, wrongly carried per turn | **merged into the leading stable system row** via `LeadingStablePromptComposer` — one stable row, existing breakpoint site unchanged; config change rebuilds the row (today's documented refresh semantics) |
| Time anchor `SYSTEM` row | `ParlerTimeAnchor.buildSystemInstruction(tz)` — static guidance + `now_utc`/`now_local` + timezone | mixed | **split**: the static guidance sentences join the leading stable row; the timezone and time values join the volatile time block in the suffix (§4) |
| Round UTC clock `SYSTEM` row | `LlmUtcClockInjector`, fresh instant + deployment-stable tool/time guidance | mixed | **split**: its stable guidance is de-duplicated with the time-anchor guidance in the leading stable row; its instant becomes `now_utc` in the **single volatile time block** (§4) |
| Task-evidence `SYSTEM` row | `TaskStateLlmInjector` / `AgentTaskStateRenderer` | round-volatile data + stable rule text | **split** (§5): the `Final-answer evidence rule` text moves to the leading stable row; the data-only view joins the suffix |
| Slash-skill block `SYSTEM` row | `buildSlashLoadedSkillsBlock` from `/SkillName` tokens in the current user message | turn-volatile by nature (derives from this user message) | suffix (§4); user-content carriage on Anthropic is honest here — the user invoked the skills. When valid slash tokens consume the whole cleaned message, §6.5 preserves the trimmed user-authored slash directive as the non-empty durable user text. |
| Host-scope `SYSTEM` row | validated rendered host-context prompt | turn-volatile | suffix (§4) |
| Coverage-summary `SYSTEM` row | `DocumentCoverageSummaryInjector`, conditionally inserted per round by `AgentLoop` when the C1 retrieval-saturation guard fires; removed in the same round's `finally` | byte-constant text, round-conditional presence | suffix (§4), after the evidence view and before the time block, framed as a **server control instruction** — it directs the finalize and must not be softened to an observations framing |
| Empty-final recovery `SYSTEM` row | `EmptyFinalAnswerRetryInjector`, inserted only for the single no-tools finalization retry after a no-tool blank provider response; removed in the same round's `finally` | byte-constant text, anomaly-conditional presence | suffix (§4), after the evidence view and before the time block, framed as a **server control instruction**; it never joins durable history or the stable system prefix |
| Taxonomy / alert slots | reserved `ParlerEphemeralSystemIndices` slots (other injection paths) | — | any current or future ephemeral injection MUST declare a class from this table and MUST NOT be a changing `SYSTEM` row; the fixtures fail on an unclassified `SYSTEM` row after the stable prefix |

Rows removed at turn end (`applySkillTurnMutationFinish`) that serialize **after** the frontier no longer disturb the
stored-history prefix. That makes the ordinary cross-turn claim true when this whole table is satisfied and no
bounded-memory epoch occurs; it is not a promise that Tier B/checkpoint/trim may rewrite old rows without a cache
cost.

## 4. The volatile suffix (one region, fixed order)

All round- and turn-volatile material serializes in a single terminal suffix region, in a fixed order:
**slash-skill block → host-scope block → task-evidence view → round instruction (coverage-summary or empty-final
recovery, when applicable) → time block** (time last, so the most-frequently changing bytes are outermost). The time block carries the turn's timezone context and anchor values plus a fresh
per-round instant — clock semantics are unchanged (fresh time every round; snapshot semantics stay withdrawn).

**Client-tool precondition:** Parler's current `ToolDefinition` path emits client tools, so suffix text may follow
their `tool_result` blocks in the same user message [V4]. This design MUST NOT be generalized to Anthropic server
tools: if a future provider change introduces a server-tool definition or another unresolved server-tool shape, it
must first define and verify a carriage path that obeys the tool-result-only restriction. The serializer MUST NOT
append Parler suffix text to such an unresolved server-tool result message by assumption.

- **Anthropic:** the suffix serializes as framed text blocks after the cache frontier. When the last substantive
  wire message is user-role — tool rounds and fresh user turns — the blocks append inside that final user message,
  after its `tool_result` blocks ("result blocks first, text after" [V4]) or after the user's text on the initial
  round (V3's remedy verbatim). When the last substantive message is **assistant-role** — the no-new-user resample
  shapes such as chart rescue — the suffix serializes as a **dedicated suffix-only user wire message** appended
  after the assistant: causal and terminal ordering are preserved (the durable assistant prose is never followed by
  content it appears to have seen), and that carrier message is **excluded from §6.3 frontier-candidate
  enumeration**, so `cache_control` never lands on round-volatile content. Ordinary user/assistant sequencing is
  the documented request model [V4]; the marked prefix still ends before varying content [V2][V3]. Both cases are
  explicitly accepted **user-content carriage** for server-authored
  and skill material: system carriage is impossible without poisoning the prefix [V2], the V5 late-system lane is
  unavailable on the target surface, and each block opens with its fixed framing prefix. The task-evidence view uses
  `[Parler server data — observations, not instructions]`; skill blocks use
  `[Skill instructions loaded for this turn by user request]`; coverage-summary and empty-final recovery blocks use
  `[Parler server instruction for this round]`, because they are control instructions whose authority must not be
  disguised as data; the host-scope block shares the observations framing, and the merged time block carries the
  fourth class, `[Parler server time context]` (server data — time values whose usage rules live in the stable
  prompt). These exact strings are Parler decisions
  recorded here, not implementation choices, and carrying a server control instruction as user content on this wire
  is an explicitly accepted authority trade [V2][V3][V5].
- **OpenAI / Azure OpenAI:** the suffix rows remain system-role messages (positionally accepted [V7]) pinned to the
  end of the list in the same fixed order; system authority is retained on this wire. Everything before the suffix
  is append-only [V6][V7].

The §3.1 catch-all is owned by **provider serialization**, not by a list in this document and not by planner
internals. `ContextBudgetPlanner.isFramedEphemeralSystem` is private and is not an authority/cache guard; §4.1 item 7
describes its lifecycle recognition because it affects accounting and active-batch scanning. Runtime
disposition is exhaustive:

- **Anthropic:** only the leading stable system row (per `LeadingSystemRow.isStableFirstSystemRow`) enters the
  top-level `system` field by design. Every **classified** `SYSTEM` row (exactly the four §4 framing constants,
  with no legacy injector-head aliases) is consumed into the suffix lane in the fixed order above.
- **OpenAI / Azure OpenAI:** the same rule relocates every classified non-leading `SYSTEM` row to the end of the
  list in the same order, retaining system role.
- **Unclassified rows keep their authority (Parler trust policy, not a vendor question).** A non-leading `SYSTEM`
  row matching no classified prefix retains **today's carriage on both wires** — hoisted into the Anthropic
  `system` field, left at its original position on OpenAI — preserving its system authority at the documented cost
  of invalidating that request's cache below it [V2][V6]. It is never silently demoted to user content and never
  given an invented framing. Cache preservation must not outrank instruction authority for content whose trust
  class is unknown.
- **Diagnostics ownership (serializers stay pure).** `AnthropicMessagesApi` and `ChatCompletionsApiMessages` are
  deliberately offline-testable helpers with no platform logger, and they stay that way: classification **returns**
  a small diagnostics result naming any unclassified rows, and the provider clients that already own logging —
  `AnthropicMessagesLlmClient` and the OpenAI/Azure clients (`OpenAiChatCompletionsClient` /
  `AzureOpenAILlmClient`) — emit one `WARN` per unrecognized row from it. No logging enters the pure helpers.
- **Guard fixture:** a fixture drives `AgentLoop`'s real round assembly with the complete ephemeral set on both
  wires and **fails on any unclassified non-leading `SYSTEM` row**, so a new injector must declare its class before
  shipping; the runtime policy above is itself asserted on both serializers (authority retained, diagnostics
  returned, client `WARN` emitted).

The **runtime disposition** is total because every row either matches one of the four constants or follows the
unknown-row fallback. Fixture coverage is deliberately narrower: it proves only the real assembly paths it drives
and MUST NOT be described as total detection of every future construction path.

### 4.1 The classification channel, end to end (closed)

Classification travels **in the row content itself, materialized before planning** — no metadata, and none of the
alternatives a fresh session might otherwise have to weigh (a `ChatMessage` field, `LlmChatRequest` extension,
planner index remapping, or thread-local inspection) is needed or permitted:

1. **Constants owner and closed predicate.** One holder in the `llm` package — `ParlerSuffixFraming` — owns the
   four framing strings of §4 as constants and the one classifier. A non-leading `SYSTEM` row is classified if
   and only if its content starts with exactly one of those constants; legacy heads such as
   `LlmUtcClockInjector.PREFIX`, `TaskStateLlmInjector.SECTION_HEADER`, and
   `DocumentCoverageSummaryInjector.PREFIX` and `EmptyFinalAnswerRetryInjector.PREFIX` are never aliases. Injectors,
   turn assembly, and both serializers reference the same constants; no string is retyped anywhere. The holder's
   Javadoc MUST state that using a framing constant is an explicit declaration of that row's authority class and
   serializer carriage, so reuse is a deliberate act rather than a harmless formatting choice.
   Inside the injector owners, the legacy heads also guard row-unique removal (item 2).
2. **Materialization and lifecycle points (all before `ContextBudgetPlanner` runs):** the time block is built with its framing by
   its builder (replacing `LlmUtcClockInjector.PREFIX` as the recognizable head); the task-evidence view by
   `TaskStateLlmInjector`/`AgentTaskStateRenderer`; the coverage instruction by `DocumentCoverageSummaryInjector`;
   the empty-final recovery instruction by `EmptyFinalAnswerRetryInjector`;
   the slash block by `buildSlashLoadedSkillsBlock`; and the **host-scope block is wrapped with the observations
   framing by `buildLlmTurnContext` at construction** — this matters because host scope is rendered from an
   arbitrary registered repository template (or the generic fallback) and has no universal prefix of its own, so
   the wrapper is what makes it classifiable at all. Any injected row whose framing changes its content head MUST
   update its round-end removal guard in the same change, and the guard MUST remain row-unique rather than matching
   only an authority-class prefix: the clock guard matches the time framing plus `\n- now_utc: `, the task-state
   guard the observations framing plus `\n` plus `TaskStateLlmInjector.SECTION_HEADER`, and the coverage guard the
   instruction framing plus `\n` plus `DocumentCoverageSummaryInjector.PREFIX`; the empty-final remover similarly
   matches the instruction framing plus `\n` plus `EmptyFinalAnswerRetryInjector.PREFIX`. Slash and host-scope rows continue
   to use their index-only turn-end stripping. Fixtures run consecutive rounds and assert that clock,
   task-state, and coverage rows occur exactly once while the provider call is assembled and leave no residue after
   `finally`; a shifted-index negative asserts that the task-state remover cannot delete an observations-framed
   host row.
3. **Planner sees final bytes.** Because every framed string exists in `ChatMessage` content before planning,
   `ContextBudgetPlanner` / `MessageCharEstimator` count the exact outbound characters; `LLM_CONTEXT_PLAN` and the
   provider cap include the framing. `PlannedOutbound` needs no new fields — it remains the drop-only
   `List<ChatMessage>` plus metrics, and no `ParlerEphemeralSystemIndices` remapping is required because the
   serializers classify by content prefix, not by position (the index bundle is pre-planning state and is absent on
   some continuation paths — it plays no role in this channel).
4. **Serializers relocate only.** Both serializers classify the planned copy solely by the canonical prefixes and
   perform pure role/block relocation; they add **zero prompt text**. Provider wire envelopes (JSON syntax, the
   suffix-only carrier's message wrapper) remain outside character accounting, exactly as today.
5. **Stable-partition ordering.** Four authority classes deliberately do not distinguish host scope from task
   evidence (both are observations). The executable ordering rule is therefore a **stable partition**: serializers
   sort suffix rows by class rank — skill → observations → instruction → time — and preserve **source order within
   a class**. Construction guarantees the observations-class order (host scope is a turn row appended before the
   round-level evidence row, and the planner's drop-only copy preserves relative order), so host-before-evidence is
   an assembly invariant, not a serializer inference. Fixtures cover zero, one, and both observation rows. No fifth
   framing string and no metadata are needed.
6. **Accounting invariant, stated executably.** There is no single planned "content character total" to
   compare (`Metrics` partitions fields and `MessageCharEstimator.rowChars` adds envelope constants for tool rows),
   so the invariant is textual, not numeric: **every planned `ChatMessage` content string is emitted byte-for-byte
   exactly once as wire prompt text, and every wire prompt-text value originates from a planned content string** —
   the serializers create none. The fixture asserts this on a tool-heavy shape (the incident class), not a
   conveniently tool-free one. A second, cap-boundary fixture proves framing participates in admission: a planned
   shape that fits without its framing characters but exceeds the cap with them must show the planner's drop
   behavior change. Tool schemas and provider JSON envelopes stay outside the invariant on both wires, exactly as
   they are outside it today.
7. **Planner lifecycle predicate (not a serializer alias).**
   `ContextBudgetPlanner.isFramedEphemeralSystem` is private but operational: it feeds ephemeral-character
   accounting and the active assistant/tool-batch scan, and it uses the shared `ParlerSuffixFraming` classifier.
   The predicate deliberately matches **all four** framed classes, including the turn-level slash and host-scope
   rows; it is a complete framed-ephemeral classifier, not a round-only classifier. This is safe and intentional:
   `computeEphemeralChars` suppresses index-bundle double counts, its `bundle == null` fallback already counts
   every later `SYSTEM` row, and counting a framed row on the `NONE` path is more accurate; slash/host rows are
   constructed near the front and therefore cannot become trailing false positives in active-batch scanning. The
   predicate is never consulted by either serializer and does not weaken item 1's exact four-prefix predicate.
   `ContextBudgetPlannerTest` pins the predicate for accounting and active-batch detection.

### 4.2 The single time block — data path and lifecycle (closed)

- **Owner and signature.** `LlmUtcClockInjector` remains the round-time owner. Its builder becomes
  `buildTimeBlockContent(String canonicalIanaId, Instant nowUtc)` — the deterministic-test entry — with a
  production wrapper supplying `Instant.now()`. A null `nowUtc` is a programmer error and the deterministic entry
  fails explicitly; production never supplies it. No data-only formatter exists today
  (`ParlerTimeAnchor.buildSystemInstruction` returns values plus stable guidance), so
  `ParlerTimeAnchor.formatTimeValues(String canonicalIanaId, Instant nowUtc)` provides it, reusing the existing
  Joda formatting. It **always** returns `- now_utc: ...` for a non-null instant. When
  `normalizeIanaOrNull(canonicalIanaId)` succeeds, it then appends `- now_local: ...` and
  `- user_timezone: ...` in today's byte order and formatting; when the zone is absent or invalid, it omits only
  those two local-zone lines. It has no header or guidance text. This deliberately does **not** preserve
  `buildSystemInstruction`'s old null-zone suppression: it preserves the independent clock row's unconditional UTC
  behavior while merging two rows with different presence conditions.
- **Stable guidance split and de-duplication.** `ParlerTimeAnchor.STABLE_TIME_GUIDANCE` becomes the single owner of
  one de-duplicated stable paragraph consumed by `LeadingStablePromptComposer`. Its exact text is:

  ```text
  For ambiguous relative time (today, yesterday, 8am, last hour), assume user_timezone when available unless the user specifies another zone or UTC explicitly. Do not invent UTC windows by mental arithmetic; use tool / platform time resolution. For tools that expose **calendarPhrase** (today / yesterday / tomorrow) or **relativeDuration** (e.g. 30m, 24h), pass those fields as documented — the server resolves them using **user_timezone** when available and the shared time resolver; do not substitute a UTC-day window from the current time context for those phrases. For other tools or when only ISO bounds exist, compute startTime/endTime from the current time context when needed. Use ISO-8601 in UTC with a Z suffix unless a tool schema explicitly requires otherwise.
  ```

  This is the content-preserving union of both shipped rule sets: it retains the complete legal calendar-phrase
  list, the duration examples, the stronger platform-resolution instruction, and the ISO-only fallback. The only
  semantic qualifier added is `when available`, required by the no-valid-timezone behavior above; references to
  `this instant` become `the current time context` because the instant now travels in the suffix. The documented
  tension between platform resolution and ISO-only fallback is not resolved or reversed by this carriage design.
  Tests pin this constant byte-for-byte. `LlmUtcClockInjector` carries none of those stable sentences. The time block emits
  one row headed by `[Parler server time context]` plus the data-only output; **`now_utc` is the one fresh round
  instant**, and `now_local` (when available) derives from that same instant.
- **How the timezone reaches it.** `AgentThing` already resolves `userIanaTimezone` at turn setup (it is a
  `buildLlmTurnContext` parameter today); it passes the canonical value into `AgentLoop` as a turn-construction
  parameter, and `AgentLoop` hands it to the builder on every round. Reading
  `AgentToolContext.getUserIanaTimezone()` inside the injector is **rejected** — a hidden thread-local dependency
  that breaks offline determinism.
- **Lifecycle.** `ParlerTimeAnchor` stops producing a per-turn row: the `timeAnchorIdx` slot is retired (always
  absent), its stable guidance sentences move to `LeadingStablePromptComposer`, and the old
  `LlmUtcClockInjector.PREFIX` head is replaced by the framing constant (round-end removal uses §4.1 item 2's
  row-unique framed `now_utc` head).
  The merged time row remains present on every provider round even when no valid user timezone exists; a null/invalid
  timezone fixture on both wires asserts `now_utc` is present and `now_local` / `user_timezone` are absent.
  Retaining two time rows, or merging them during serialization, is **rejected** — both contradict the §4.1
  materialize-before-planning and zero-serializer-text rules.

### 4.3 Classification diagnostics — the exact seam (closed)

- **Value object.** `SuffixClassificationDiagnostics` (in the `llm` package): an immutable list of entries
  `{plannedIndex, role, charCount, contentDigest}`. The digest is **SHA-256 over the complete UTF-8 row content,
  hex-encoded and truncated to one code-owned prefix length** (`DIGEST_HEX_LENGTH = 16`, a constant asserted by the
  pure-helper tests so two implementations cannot produce different log identities). The safety property is that
  **no raw row text is logged** — the digest is not claimed to be irreversible, since low-entropy content can be
  guessed. Unknown rows are exactly where host or customer data may appear, so entries carry no row text at all.
  Canonical framing names may appear in logs only for recognized rows, which never generate this warning.
- **Anthropic route.** New `AnthropicMessagesApi.buildRequestPayloadWithDiagnostics(...)` returns a small result
  (payload map + diagnostics); the existing `buildRequestPayload(...)` delegates to it and discards the
  diagnostics, so current callers and tests compile unchanged. `AnthropicMessagesLlmClient` switches to the new
  entry and emits one `WARN` per entry.
- **OpenAI route.** New `ChatCompletionsApiMessages.toApiMessagesWithDiagnostics(...)` returns
  {messages, diagnostics}; new `ChatCompletionsApi.buildRequestBodyWithDiagnostics(...)` threads the diagnostics
  through to the callers; `OpenAiChatCompletionsClient` and `AzureOpenAILlmClient` log. Existing entry points
  delegate-and-discard identically.
- **Test seams.** Serializer-level diagnostics and relocation: `ChatCompletionsApiMessagesTest` (the existing
  pure-body suite) and the Anthropic assertions inside `llm/PromptPrefixStabilityChainTest`; API-level threading:
  `ChatCompletionsApiTest`; client-level `WARN`: extend `AnthropicMessagesLlmClientPhase1Test`, and create
  `llm/ChatCompletionsClientDiagnosticsTest` for the OpenAI/Azure clients — **no test currently exercises either
  OpenAI client**, so this suite is new coverage by necessity, asserting the log emission through a small
  package-visible seam without HTTP.

Cross-turn property: with §3.1 fully applied, an ordinary successful turn whose pre-normalization replay remains at
or below the existing storage cap replays stored history byte-identically through the previous frontier on both
wires; §10's next-user-turn fixture asserts it with the **full** ephemeral set present. An over-cap post-turn
normalization is explicitly outside that equality: Tier B, optional checkpoint replacement, and deterministic trim
form one intentional cache epoch, and the first later request can read only the unchanged prefix before its earliest
historic mutation [V2][V3][V6][V7].

## 5. Evidence rules/data split — exact landing

- `AgentTaskStateRenderer` becomes a **data-only** renderer: it stops emitting the `Final-answer evidence rule`
  section and renders only observations. The 8,000-character cap applies to the complete provider row:
  the renderer subtracts `TaskStateLlmInjector.FRAMING_PREFIX.length()` before truncation and includes the
  truncation suffix inside that reduced allowance, so framing cannot push the emitted row past the cap.
- `LeadingStablePromptComposer` becomes the owner of the evidence rule text (and of the relocated time-anchor
  guidance and workflow catalog per §3.1).
- Related docs: `docs/agent/task-state.md` (rendering boundary and carriage) and `docs/agent/evidence-grounded.md`
  (the rule is delivered in the stable system prompt, not in the `Recent Tool Evidence` block).

## 6. Anthropic history markers — conditional semantics and stateless selection

### 6.1 What a marker can and cannot do

A `cache_control` marker defines a potential write point and opens a lookback window; **it never guarantees a
read**. A read additionally requires that an earlier request wrote a byte-identical prefix, that the entry is still
live (5 m TTL, refreshed on use), and that nothing above it in the `tools` → `system` → `messages` hierarchy changed
[V2]. All claims below are conditional on those vendor semantics.

### 6.2 Marker placement

- **Current frontier marker** on the newest vendor-eligible stable block: the last `tool_result` block of the final
  **substantive** user message on tool rounds [V4], or the user's text block when its content is non-empty — always
  before the volatile suffix, and never on a suffix-only carrier message [V2][V3]. Supported interactive assembly
  never produces zero-length durable user text (§6.5), and the Anthropic serializer fails locally rather than emit
  an unsupported empty block [V8].
- **Previous frontier marker** at the prior request's write point, because Parler bounds neither tool calls per
  round nor parallel fan-out (`docs/agent/task-state.md`; `docs/agent/llm-performance.md`) and one round can exceed
  the ~20-block lookback [V2]. Selected statelessly by the §6.3 rule from the current planned wire; when the
  earlier candidate does not exist (first request) or coincides with the current frontier, only one history marker
  is emitted.

Marker counts are **conditional on the request shape and eligible user frontiers**: with tools and an ordinary
initial user text (including §6.5's non-empty slash-only fallback), 3 on a conversation's first request and 4 in
steady state; without tools, 2 and 3. `AgentLoop` deliberately changes or removes `defsForRound` on filtered,
post-marker, chart-rescue, and summary rounds; a tool-set change invalidates the `system` and `messages` caches
[V2], so across such transitions the history markers are valid request shape but **no cache read is expected** —
the fixtures and acceptance tables distinguish "bounded marker count" from "read expected". The global assertion
counts all markers plus any automatic slot and enforces ≤ 4.

**Why Anthropic automatic caching is not used, although the vendor recommends it for ordinary multi-turn histories
and it is supported on the target surface:** automatic caching marks the final block of the request. Parler's
terminal suffix is **intentionally volatile** (§4), so an automatic final-block marker is exactly the documented
changing-suffix anti-pattern — it re-writes a different prefix every round and never produces the reusable write
this design needs [V2]. It would also consume one of the four breakpoint slots while providing neither required
write: the current **pre-suffix** frontier, nor the retained previous window that unbounded >20-block growth
demands [V2]. The explicit two-marker scheme is therefore not a stylistic preference over the vendor default; it is
the only shape that caches Parler's request structure.
`docs/agent/context-compaction.md` §12 and `docs/agent/llm-token-budget.md` describe the same "2 stable + up to 2
history, ≤ 4 total, counts conditional on tools" marker budget.

### 6.3 Stateless previous-frontier selection

The vendor contract needs no memory: each breakpoint opens a backward search window, a read happens only if an
earlier request wrote an identical still-live prefix, and a miss is simply a write [V2]. Parler therefore keeps
**no cross-round cache state**. After planning and Anthropic message grouping, the serializer applies one
deterministic rule to the planned wire shape:

- **Frontier candidate of a substantive user-role wire message:** its last `tool_result` block, else its text
  block only when that text has non-zero length (the same vendor-eligibility rule that defines the current frontier,
  §6.2). **Substantive** means it carries tool results or original user input; a serializer-constructed suffix-only
  carrier (§4, assistant-tail case) is excluded from enumeration entirely. A zero-length ordinary user row is not
  a supported wire candidate: §6.5 prevents it at interactive assembly and the Anthropic serializer rejects any
  residual/direct-caller occurrence before constructing a request [V8].
- §6.5's trim-empty guard is strictly stronger than this retained zero-length candidate predicate and executes
  first, so supported interactive requests never expose whitespace-only content to candidate selection.
- **Current frontier** = the last eligible candidate across substantive user-role wire messages. **Previous
  candidate** = the second-to-last eligible candidate, when one exists and is distinct.
- Mark both. The frontier **advances only when a new eligible candidate appears**. When it advanced, the
  previous candidate is structurally the block the preceding request marked as its current frontier whenever that
  request's prefix survives unchanged, and a read is found there within a zero-distance lookback [V2]. Shipped
  **no-new-user resamples** exist and behave differently: on the chart-rescue continuation
  (`eligibleSingletonChartRescueAfterProse`), `AgentLoop` appends the assistant prose and immediately resamples
  without a new user or tool-result row, so on the Anthropic wire the suffix rides in the dedicated suffix-only
  carrier after the assistant (§4) and is excluded from enumeration — the **current** candidate is unchanged and is
  itself the preceding request's write point. Marking it again suffices, the second-to-last candidate is an older
  harmless write point, and because that round also changes `defsForRound`, the §6.2 tool-set rule already records
  **no read expected** across it. The rule's output is identical in both cases; only the "previous candidate =
  prior write point" explanation is conditional on the frontier having advanced.

Miss semantics are the vendor's, not Parler's: a changed prefix is a new write, never corrupted cache state. It is
nevertheless not operationally harmless on a cache-aware uncached-input limiter, because cache creation can replace
tens of thousands of tokens that would otherwise have been reads [V1][V2][V3]. Parler therefore does not run Tier B
after every success. `ConversationsReplayNormalization` first measures the unmodified post-turn list against the
existing storage cap: within cap it skips Tier B; over cap it invokes the existing all-candidate promoter once, then
recomputes checkpoint eligibility and trim. This batches the necessary Tier B/checkpoint/trim mutations into one
bounded-memory cache epoch without a new threshold or cache state.

Other intentional or external boundaries — request-copy planner drops, rehydration, `ClearConversation`, JVM
restart, a failed prior request, TTL expiry, or a tool-set/system change — can still shorten or eliminate a read. If
an earlier block was removed entirely, the marker selects whatever now occupies the second-to-last user position,
which is a valid new write point. No `AgentThing` cache holder, hashes, or lifecycle hooks exist; this keeps the "no
generalized cache subsystem" boundary intact. State may be reintroduced only if later live evidence demonstrates a
concrete shape that pressure-gated epochs plus conservative rate admission cannot serve safely — the shape first,
then the state.

OpenAI / Azure OpenAI: **no explicit cache fields** (message ordering follows §4); `cached_tokens`
is the live signal [V6][V7]; GPT-5.6+ explicit breakpoints remain out of scope pending per-deployment capability
verification.

### 6.4 Anthropic user `content` representation (closed)

`ChatMessage` remains a single-string model. Anthropic user representation is a **request-kind invariant**, never a
function of whether §6.3 currently selects a row for a marker:

The request kind reaches serialization through the **existing nullable `LlmChatRequest toolPolicy` parameter** on
the full `AnthropicMessagesApi.buildRequestPayload(...)` overload; no signature or message-model change is needed.
`AnthropicMessagesLlmClient` already passes the real request through that parameter. The serializer derives the
kind exactly as `toolPolicy != null && toolPolicy.isEnableCacheControl()`. Therefore a null request — including the
shorter parameter-style overloads that delegate with `(LlmChatRequest) null` — is the **false** request kind:
ordinary user text stays a string, the two stable markers remain as applicable, and no history marker is added.
Null MUST NOT default to true or silently convert compatibility callers to block arrays.

- When `enableCacheControl=true` (the AgentLoop request kind), **every** substantive user-role wire message uses a
  content array for its entire serialized lifetime, whether it is current, previous, older and unmarked, or carries
  suffix blocks. An ordinary text row's first and only non-suffix block is exactly
  `{"type":"text","text":<resolved model-facing content>}`; selection adds `cache_control` to that existing
  block without changing its representation. The resolved content is non-empty under §6.5; the serializer rejects
  a violation before emitting any message row [V8]. Classified suffix text blocks follow in §4 order and never
  receive a marker.
- When `enableCacheControl=false` (probe, summary, playbook, and connection-probe request kinds in §7), ordinary
  text user rows retain today's string form, `{"role":"user","content":"..."}`. Those callers carry no Parler
  volatile suffix. Block-required tool-result rows remain arrays as today. This compatibility rule is keyed only to
  the request flag, not to message position or marker selection.
- A tool-result user row is already an array: its existing `tool_result` blocks remain first, the selected last
  result alone receives the marker, and suffix text blocks follow [V4].
- A serializer-created suffix-only carrier is an array of suffix text blocks, is not substantive, and has no marker.

The flag-keyed rule preserves both cache identity and §4.1's byte-exact-once accounting invariant. Fixtures pin the
false-kind string case; true-kind marked and unmarked text arrays; text plus suffix; tool results plus suffix; and
the suffix-only carrier. A multi-request initial → tool follow-up → next-iteration chain additionally snapshots
each previously emitted user message and asserts its role, array/string kind, block order, block types, and prompt
bytes remain identical as the current/previous frontier advances past it; the raw JSON diff is limited exactly to
`cache_control` annotations on the two blocks selected in that request. In particular, an old text row never
demotes from array to string when it becomes unmarked. Prompt bytes are never duplicated, dropped, or altered.

### 6.5 Non-empty interactive user text and slash-only normalization

Anthropic's input schema cannot be treated as accepting an empty ordinary text block [V8]. Parler closes that
provider-shape boundary with one provider-neutral interactive policy and one Anthropic
fail-closed guard; it does not omit history rows, invent placeholder prompt text, or wait for a Provider 400.

**Raw interactive ingress.** `Chat`, `ChatAsync`, and `ParlerStreamToRemoteThing` require a non-null user message
whose `trim()` value is non-empty. `ParlerStreamToRemoteThing` already enforces this exact rule and retains its
existing error. The same rule applies to `Chat` after its existing conversation ownership check and before
artifact-cache admission, and to `ChatAsync` after its existing conversation-id/ownership checks and before queue
or thread creation. This preserves current authorization/error precedence while ensuring no admission, turn state,
Stream append, or Provider work occurs for blank input. Their
exact new errors are `[<agent>] Chat: message is empty.` and `[<agent>] ChatAsync: message is empty.`. Validation
does not otherwise trim or rewrite normal Chat/ChatAsync input; AlwaysOn retains its existing trimmed `userMessage`
behavior. This is an interactive service precondition, not a new setting or generalized validation subsystem.

**Slash-only fallback.** `SkillSlashParser` remains a parser: for `/Demo` against a registered `Demo`, its
`cleanedMessage()` remains the zero-length string and its selected-skill list remains `[Demo]`. At
`AgentThing.buildLlmTurnContext`, one new package-visible deterministic helper,
`AgentThing.resolveModelFacingUserContent(String rawUserMessage, SkillSlashParser.Result slash)`, chooses the
durable/model-facing user content. It delegates to the package-private pure `AgentUserMessagePolicy` so ordinary
JUnit can exercise the policy without loading ThingWorx's runtime-bound `AgentThing` class:

1. when `slash.cleanedMessage()` is non-empty, return it unchanged (today's behavior);
2. when the cleaned value is empty and at least one registered slash skill was selected, return
   `rawUserMessage.trim()` — the non-empty user-authored slash directive, not a server-authored placeholder; and
3. otherwise throw `IllegalArgumentException("user message is empty")` as an internal invariant guard (the public
   services reject this case first with their stable service-specific errors).

The slash instructions still travel once in the classified skill suffix. The raw fallback neither duplicates the
skill body nor claims that loading succeeded; if a selected repository skill later fails to load, the model still
receives exactly the user's slash directive rather than an invented instruction. The raw Stream/UI row is unchanged.
The in-memory durable `ChatMessage.user` and `AgentTaskState` Goal use the same resolved non-empty content; for a
slash-only turn the Goal is therefore the trimmed slash directive. Skill body text still MUST NOT author, append
to, or replace the Goal.

**Anthropic fail-closed guard.** Before grouping messages, `AnthropicMessagesApi.buildAnthropicMessages(...)`
checks every ordinary `USER` `ChatMessage` and throws
`IllegalArgumentException("Anthropic user text content must be non-empty")` when `getContent()` is null or
`getContent().trim().isEmpty()`, for both true and false request kinds. The check does not otherwise trim or rewrite
content. It emits neither an empty string message nor an empty text block. Omission is
rejected because silently removing a durable user row can merge adjacent assistant turns and change conversation
semantics. The non-empty frontier predicate remains explicit, but supported interactive requests satisfy it by
construction. Tool-result blocks and assistant output are outside this narrow user-text guard.

The false-kind compatibility inventory is non-empty by construction: `PlaybookRunner` prefixes its body with
`User goal: `; `ConversationCheckpointGenerator.renderPayload(...)` always appends its excerpt heading; provider
connection/health probes use the literal `Reply with exactly: OK`; and the context-planning cap path supplies no
messages and is not sent. The shared guard therefore closes directly constructed or legacy-invalid input without
changing a supported compatibility request.

**History and restart disposition.** New slash-only rows are durable as their trimmed raw directives, so every
later request replays non-empty user content. Existing AlwaysOn Stream persistence already stores the raw user row,
and `AgentConversationRehydrator` already skips null/empty user rows; that skip is defensive for legacy malformed
rows, not the omission policy rejected above. Supported AlwaysOn rows are raw non-empty user text and do not rely
on rehydration omission to preserve conversation semantics. Chat/ChatAsync history is in-memory only and is rebuilt
under the new rule after extension restart. A residual/directly constructed empty history row fails locally at the
Anthropic guard instead of producing an external 400. No migration, provider probe, cache state, Stream schema, or
UI/wire contract change is introduced.

## 7. `enableCacheControl` — agent-round history markers and stable user representation

The two shipped stable markers (leading stable system, last tool) stay unconditional; breakpoints add no cost and
one-shot requests can still read pre-warmed stable prefixes [V2]. The inert `LlmChatRequest.enableCacheControl`
field becomes the request-kind switch for history markers and §6.4's stable user-row representation; it does not
govern the two stable markers.

**Construction is explicit at the AgentLoop boundary.** The shared `forAgentRound(...)` overload family remains
false because it is also used by context-cap planning and provider health/connection probes. `LlmChatRequest` gains
`copyWithCacheControl(LlmChatRequest base, boolean enableCacheControl)`, which copies every other request field
unchanged. Immediately after either `forAgentRound(...)` branch, `AgentLoop` applies
`copyWithCacheControl(chatRequest, true)`; its later conditional `copyWithToolPolicy(...)` already preserves the
base cache-control value. This AgentLoop-only production step is the sole true-kind construction path. Direct
constructors used by `PlaybookRunner` and `ConversationCheckpointGenerator`, the shared factory's context-cap and
health-check callers, and all three provider connection-test callers remain false. The serializer MUST consume the
resulting field only: it MUST NOT infer kind from probe mode, overload arity, caller identity, or provider.

The full Anthropic serializer overload reads `isEnableCacheControl()` from its existing nullable
`LlmChatRequest toolPolicy` parameter exactly as §6.4 specifies. Parameter-style overloads pass null and are false;
the provider client passes its non-null request. This is plumbing through an existing channel, not a new API.

| Caller (complete inventory) | Value | Anthropic serialization |
| --- | --- | --- |
| `AgentLoop` agent rounds (`forAgentRound`, then AgentLoop-only `copyWithCacheControl(..., true)`) | `true` | stable markers + history markers per §6; every user wire message is an array |
| `PlaybookRunner` direct step requests | `false` in v1 | stable markers only; ordinary text users remain strings (telemetry may revisit) |
| `ConversationCheckpointGenerator` one-shot summaries | `false` | stable system marker only (no tools); ordinary text users remain strings |
| Provider connection probes (`AzureOpenAILlmClient` and peers) | `false` | stable markers only; ordinary Anthropic text users remain strings; OpenAI probes serialize no cache fields |

Negative fixtures assert exactly this split — never "zero markers". No new AgentSetting.

## 8. Provider-visible shapes and the cache frontier

The byte-identical arrows in this section describe append-only rounds/turns between bounded-memory cache epochs.
On an over-cap epoch, both provider families can reuse only the common prefix before the earliest Tier B/checkpoint/
trim mutation; the first new shape is written, and these ordinary sequences resume afterwards [V2][V3][V6][V7].

Anthropic (`F` = current frontier marker, `P` = previous frontier marker per the §6.3 rule, `~` =
volatile suffix in fixed order; `sys*` = leading stable row now containing catalog, time-anchor guidance, and the
evidence rule):

```text
1. Initial round:      system[sys* ✓] | tools[…, last ✓] | user[ non-empty text(F) , ~suffix ]
1a. Slash-only /Demo:  system[sys* ✓] | tools[…, last ✓] | user[ text("/Demo")(F) , ~skill/time suffix ]
2. Tool round:         … | assistant[tool_use×n] | user[ tool_result₁ … tool_resultₙ(F) , ~suffix ]   (P per §6.3 rule)
3. Next iteration:     prefix byte-identical through old F; new assistant[tool_use…] | user[ …(F) , ~suffix ]   (P = old F)
4. Next user turn:     prefix byte-identical through last F; user[ text(F) , ~suffix ]   (P = last F per §6.3 rule)
5. Probe / summary:    own system (stable marker), own tools if any (tool marker); no history markers; no suffix
6. HITL continuation:  … | assistant[tool_use×n] | user[ synthetic/approved tool_result rows in original order (F) , ~suffix ]   (P per §6.3 rule across the approval gap)
7. Chart-rescue resample: … | user[ …(F) , (no suffix here) ] | assistant[durable prose, byte-unchanged] | user[ ~suffix only — excluded from candidates, no markers ]
```

OpenAI / Azure OpenAI (implicit; frontier = last append-only message): same sequences with the suffix as trailing
system rows; shapes 3, 4, and 6 assert byte-identical prefixes through the last append-only row.

`P` is selected by the §6.3 stateless rule (the second-to-last user message's candidate), never from stored state.
On a saturation round the suffix additionally carries the coverage-summary instruction before the time block. On
the bounded blank-final retry it instead carries the empty-final recovery instruction in that instruction slot.

Shape 6 remains first-class: synthetic rows appended by `HitlSyntheticToolResultAppender` outside the loop's own
appender, in original order; the approved execution's evidence enters the next request's suffix view; synthetic
reject/expire/interrupted-sibling rows are not tracked executions and contribute no evidence entries.

## 9. Non-goals

- No rate-control changes (the incident's enforce-mode gate admitted the rejected request; this design does not
  change that gate).
- No provider retry / `retry-after` handling; no thresholds or new AgentSettings; no generalized cache subsystem.
- No `ChatMessage` / message-model changes and no multipart tool-result carriage.
- No planner API, outbound model, index remapping, trimming order, protection policy, cap policy, or §6.3 planner
  state. The private lifecycle predicate is as described in §4.1 item 7 so accounting and active-batch
  detection continue to recognize the reframed rows; no other planner behavior changes.
- No Stream, UI, or protected-value behavior changes. `AgentMessageStreamAppender` already excludes every
  `ChatMessage.Role.SYSTEM` row before projection, so reframing and relocating these rows cannot make them visible
  on the Stream/UI surface; no content-head exception is needed.
- No Anthropic mid-conversation system messages on the target surface [V5]; no OpenAI explicit-breakpoint fields in
  v1 [V7].
- No Anthropic server-tool enablement; the suffix-after-results shape is scoped to Parler's current client tools
  [V4].
- The model keeps fresh per-round time, the same evidence content, the same skill instructions, and the same host
  context; only serialized carriage changes.

## 10. Verification

- **Fixture chains, both wires, with the full ephemeral set present** (catalog, anchor, slash block, host scope,
  evidence, coverage instruction, clock): initial → tool follow-up → next iteration; a separate next-user-turn
  case; the HITL case. Each asserts byte-identical prefixes through the declared frontier, the suffix as the only
  changing region, fixed suffix order, roles, pairing, ordering, and untouched tool payloads. The guard fixture
  drives `AgentLoop`'s real round assembly and fails on any **unclassified** non-leading `SYSTEM` row (the §4
  serializer-owned catch-all).
- **Lookback boundary fixtures:** the fixture **counts cacheable positions between the current breakpoint and the
  prior write** and asserts the constructed wire distance is safely outside the lookback **before** any assertion —
  "the round has about 20 blocks" is not the executable condition. Direct measurement on the target deployment
  showed ten parallel calls (20 tool blocks) still read the prior frontier without a retained marker
  (`read=1844`), while a **25-call shape (50 tool blocks)** discriminated cleanly (without the retained marker
  `read=0, create=3079`; with it `read=1846`) — the 25-call shape is the fixture baseline. Also covers first-round
  and single-candidate shapes.
- **Marker-selection fixtures:** the §6.3 rule proven over initial, tool-loop, next-user-turn, HITL-continuation,
  post-trim / post-checkpoint-rewrite, tool-set-transition, and the **chart-rescue no-new-user resample** planned
  wire shapes — each asserts exactly which blocks carry markers and the global count. The chart-rescue case
  additionally asserts the full message/content-block order: the durable assistant prose byte-unchanged and
  followed by the dedicated suffix-only user carrier, the carrier excluded from candidate enumeration, no marker on
  any carrier block, and "no read expected" recorded (tool-set change). Miss cases are documented as harmless
  writes, never asserted as vendor cache behavior.
- **Non-empty user-shape fixtures (§6.5):** slash-only parsing still proves the registered token produces
  a zero-length *cleaned* value; the deterministic AgentThing resolver converts that edge to the trimmed raw slash
  directive and rejects a raw blank/no-skill input; Chat and ChatAsync reject null/blank input before turn/admission
  work while the existing AlwaysOn error remains unchanged. Initial and later-history true-kind Anthropic shapes
  contain `/Demo` as a non-empty text block, retain array stability, select eligible current/previous markers, leave
  every suffix block unmarked, and stay at ≤ 4 total markers. Direct true- and false-kind empty user rows fail
  locally before payload construction. OpenAI/Azure serializer code remains unchanged, while the provider-neutral
  ingress/resolver invariant applies before either provider.
- **Coverage-summary fixtures (both wires):** a saturation round serializes the coverage instruction in its suffix
  position with the `[Parler server instruction for this round]` framing, prefix byte-identity through the frontier
  holds, and the row classifies through the §4 serializer lane.
- **Empty-final recovery fixtures:** the bounded recovery request exposes no tools, serializes its instruction in
  the same server-instruction suffix lane, leaves the stable prefix byte-identical, passes unknown-row diagnostics,
  and removes the row after the request.
- **Marker-count fixtures:** with-tools and no-tools counts; tool-set transition rounds assert bounded count and
  document "no read expected".
- **Accounting invariant fixtures (§4.1 item 6):** the byte-exact-once assertion on a tool-heavy shape (every
  planned content string emitted exactly once; no serializer-created prompt text), plus the cap-boundary fixture
  where materialized framing changes the planner's drop outcome.
- **Anthropic representation fixtures (§6.4):** false-kind ordinary text stays a string; every true-kind user row
  is an array whether marked or unmarked; text plus suffix and tool results plus suffix use ordered arrays without
  changing prompt bytes. Across an initial → tool follow-up → next-iteration chain, each old row's role, content
  representation, block order, and text bytes remain identical as the frontier advances; the raw JSON diff is
  limited exactly to the expected `cache_control` annotations.
- **Null/invalid-timezone fixtures (both wires):** the one time block remains present with `now_utc`; local time and
  timezone lines are absent; the stable combined time/tool guidance remains in the leading row.
- **Negative fixtures:** probes, playbook steps, checkpoint summaries — stable markers only.
- Full local build: `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`.
