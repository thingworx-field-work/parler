# AgentThing and LLM Provider configuration

This chapter describes the Parler-owned Composer configuration fields in **Agent 0.1.248**, used with **Widget 0.1.97**. Defaults below are the extension's defaults for new Things; an imported Thing can carry different values. Application-specific ThingShapes can add configuration tables beyond this inventory.

Open the Agent Thing (often named `AIAgent`) in Composer and select **Configuration → AgentSettings**. Open the Thing named by `llmApiProviderRef` to configure its Provider-specific table and **LLMAPIProviderRateControl** table. The Agent controls the task; the Provider controls the upstream connection and request defaults. Several Agents can share one Provider Thing.

## 1. Understand the budgets before changing them

A **token** is a model's unit of text processing, not a character, word, row, or chart point. One user prompt can cause several LLM requests: choose a tool, inspect its result, choose another tool, then write an answer. Each request has its own output allowance and repeats some input context.

| Setting | What it bounds | What it does not bound |
| --- | --- | --- |
| Agent `maxTokens` / Provider output-token default | Output for one LLM request | Entire conversation cost, source-table rows, chart resolution |
| Agent `llmContextMaxChars` | Character budget used to plan model-facing input | The model's actual token window or FileRepository size |
| Agent `maxIterations` | Agent-loop iterations | Number of tool calls inside a single returned batch |
| Agent `agentTimeout` | Agent-loop elapsed-time budget | A guaranteed immediate interruption of a running platform Service |
| Provider `timeoutMs` | HTTP connection/read timeouts | The whole multi-request user turn |
| Provider `LLMAPIProviderRateControl` | Local token/request/concurrency admission | Vendor quota shared by other Things, processes, or applications |

### Output-limit precedence: why a Provider edit may appear ineffective

The Provider resolves an output allowance in this order:

1. A positive request override; normal Agent-loop requests use `AgentSettings.maxTokens` here.
2. A positive value in the Provider's native output setting.
3. The Provider code default: **4096** for OpenAI/Azure Chat v4; **8192** for Chat v5 and Anthropic.

Thus Agent `maxTokens = -1` delegates to the Provider; it does **not** request unlimited output. Zero and negative values at the request or Provider-default level mean unset. Parler still resolves a positive allowance and sends it upstream. A Provider setting is a **default**, not an administrator-enforced ceiling: a positive Agent override wins, even if larger.

For example, Provider `maxCompletionTokens = 8192` and Agent `maxTokens = 2048` produce a request capped at **2048**. Raising only the Provider default to 16384 changes nothing for that Agent. Set the Agent to `-1` if the Provider should own the allowance. Diagnostic probes can deliberately use a small request override.

### `maxCompletionTokens`: visible text shares space with reasoning

OpenAI/Azure Chat v5 sends this allowance as `max_completion_tokens`. It covers visible output **and reasoning tokens**, so 8192 is not a promise of 8192 tokens of answer text. More reasoning can leave less space for tool-call arguments or the final answer. This is the API's definition, not an extra Parler deduction. See the [OpenAI Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).

**Raise the effective allowance** when evidence shows a response stopping at its output limit or a reasoning-heavy request lacking room to finish. This provides room for completion, but can increase generated-token cost and latency if used. It neither repairs a wrong tool schema nor makes more chart points available. The upstream model can reject an allowance it does not support.

**Lower it** to constrain per-request output exposure and discourage very long responses. Too low a value can cut off explanations or structured tool arguments, leading to failure or more rounds. A lower per-request cap therefore does not guarantee a cheaper completed task. A larger cap is permission to generate more, not a requirement to consume it.

Illustration only: an 8192-token allowance with 6000 tokens spent reasoning leaves at most 2192 tokens for other completion output. Raising the allowance may help; lowering `reasoningEffort` may instead reduce reasoning work. Neither gives a fixed allocation in advance.

### `reasoningEffort`, temperature, and output length are different controls

Chat v5 defaults `reasoningEffort` to **`low`**. A nonblank request override wins over the Provider value; blank values fall back to the code default. Parler passes nonblank effort strings through without local validation or warnings for unfamiliar values; the selected model/API is the validator.

Higher effort can help difficult planning and analysis, at the cost of more reasoning work and possible latency. Lower effort can speed straightforward tasks, but may reduce planning quality. It is not a token count, verbosity setting, or accuracy guarantee. Supported values vary by model; do not assume every value listed by the API works on every deployment. The [OpenAI API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) describes that model dependence.

Temperature controls sampling variation where the client sends it. Low temperature often gives more consistent wording and tool choices, but does not make an incorrect result correct or guarantee identical runs. **Parler's v5 clients omit temperature entirely**, regardless of the Agent value. Anthropic sends it only in the cases described below.

### Output budget also competes with input and concurrency

For OpenAI/Azure, the default local reservation strategy is `input_plus_requested_output`. Raising the output allowance can reserve more of a shared Provider's token bucket even when the eventual answer is short. It may cause longer waits or local `single_request_too_large` rejection.

In `enforce` mode, when an effective single-request cap exists (`maxSingleRequestTokens > 0`, or `tokensPerMinuteLimit > 0` as its fallback), the context planner also accounts for that cap. Output reservation is subtracted only with `input_plus_requested_output`, not `input_only` (Anthropic's default). Under those conditions a larger output reservation can leave less room for history and evidence in the next request. Raising `llmContextMaxChars` cannot override a smaller Provider-derived budget. This is why tuning output, input, and rate settings separately without checking their interaction can make a previously working prompt fail.

## 2. Every AgentSettings field

### Connection, task execution, and instructions

| Field | Default | Purpose and effect of changing it |
| --- | --- | --- |
| `llmApiProviderRef` | Empty; required for LLM calls | Provider Thing implementing `LLMAPIProviderShape`. Changing it changes the connection/model defaults used for turns. Endpoint and credentials belong on that Thing. |
| `temperature` | `0.1` | Sampling request value; Composer describes 0–2. Increase for variation; decrease for consistency. OpenAI/Azure v4 sends it; v5 omits it. Anthropic clamps to 0–1 when it sends temperature. |
| `maxTokens` | `-1` | Per-request output override. A positive value overrides the Provider default. Raising/lowering effects and reasoning interaction are in §1. |
| `maxIterations` | `10` | Maximum loop iterations. Increase for tasks needing more tool rounds, with greater latency/cost and more opportunity to repeat unproductive calls. Decrease to stop runaway work earlier, but valid multi-step tasks can terminate before answering. Use a positive value. |
| `agentTimeout` | `3600000` ms (1 hour) | Overall loop timeout. Increase to tolerate long tasks; decrease for a shorter wait budget. Too short can stop a task between useful steps. The loop checks deadlines; an in-flight operation may finish after the nominal deadline. |
| `systemPrompt` | Bundled short ThingWorx assistant instruction | Establishes role and task guidance. A `Chat` call can supply an override. More text can improve domain guidance but adds input and can introduce conflicting instructions; shorter text saves context but may omit necessary rules. This is model-visible text, not an access-control mechanism. |
| `appendBuiltInToolRoutingGuide` | `true` | Adds bundled tool-routing guidance to the system prompt. Disabling reduces instruction volume but removes useful selection rules. Files such as `AGENT-CONTEXT.md` in a developer checkout are not automatically loaded. |
| `enableBuiltInTools` | `true` | Enables the full built-in tool set. When false, `get_agent_skill` and `load_tool_schemas` remain registered while the other built-ins are omitted; repository extended tools are a separate surface. Turning tools off does not teach an equivalent capability to the model. |
| `allowImplicitInvocation` | `false` | Reserved configuration. Setting it true does not currently implement automatic skill-body loading. Full skill text is loaded through an explicit `/SkillName` or `get_agent_skill`. |
| `hitlAuditDebugAll` | `false` | Promotes normally DEBUG-tier rejected-HITL-decision diagnostics to WARN for this Agent. True helps lab diagnosis but increases log noise; it neither grants approval nor changes the approval policy. Restart the Thing after changing it. |

The default `systemPrompt` is: “You are a ThingWorx AI assistant. Use the available tools to help the user interact with the ThingWorx platform.” Repository configuration and runtime instructions can add substantially more context.

### Repositories, taxonomy, and table export

| Field | Default | Purpose and effect of changing it |
| --- | --- | --- |
| `configurationRepository` | Empty | FileRepository for taxonomy, skills, policies, extended tools, and other repository-backed configuration. Empty disables repository-backed configuration. Changing it changes the Agent's application knowledge/configuration source. |
| `artifactCacheFileRepository` | Empty; required for user turns | Dedicated production Artifact Cache FileRepository. Missing or unavailable storage rejects user turns; there is no in-memory fallback. Use a dedicated repository rather than the configuration or export repository. |
| `taxonomyPromptInjection` | `full_table` | `full_table` injects optional repository Markdown from `/taxonomies/type-taxonomy.md` when present; it does not generate a table from taxonomy JSON, and injects nothing when that Markdown is absent. `resolver_guidance_only` supplies resolver instructions instead; `none` omits this injection. Context savings depend on the Markdown content/size; removing useful guidance can require extra resolution calls or reduce understanding. It does not prevent tools from returning taxonomy/entity information. Unknown values fall back to `full_table`. |
| `exportFileRepository` | Empty | Optional destination for table CSV exports. Empty disables export. It is a user-download destination, not the Artifact Cache. |
| `tableCsvExportRowThreshold` | `200` | Attempt CSV export when total rows exceed this threshold **or** the inline rows are only a sample. Lower positive values cause more exports; higher values reduce exports of otherwise complete inline tables. Raising it does not suppress the partial-sample condition or change LLM sampling. Values below 1 fall back to 200. |
| `tableCsvExportMaxChars` | `50000000` | Materialized CSV size ceiling in Java UTF-16 code units; clamped to 1,000,000–200,000,000. Raising permits larger exports with more heap/storage pressure. Lowering protects resources but more exports report `skipped_limit`. This is not a row, byte, or LLM-token limit; platform `SaveText` limits still apply. |

### Context and advertised tools

| Field | Default | Purpose and effect of changing it |
| --- | --- | --- |
| `llmContextMaxChars` | `750000` | Input planning ceiling, clamped to 10,000–2,000,000 characters. Raise to allow more replay if Provider limits also permit it; cost and latency can increase. Lower to compact/trim earlier, with less historical evidence retained and possible failure if mandatory current context cannot fit. It does not enlarge the model's context window. |
| `advertiseLegacyServiceDiscoveryTools` | `false` | True advertises legacy `discover_services` and `get_service_definition`, increasing schema volume and overlapping choices. False keeps those out of the merged advertised list; `discover_properties` remains executor-only either way. This is an advertisement setting, not an authorization boundary. |
| `toolAdmissionMode` | `off` | `off` advertises the merged tool set; `narrow` removes irrelevant buckets using deterministic context/intent signals while keeping a core; `lazy` starts with core tools and `load_tool_schemas`, so further schemas load on demand. Narrow/lazy can save input tokens; narrow can miss a useful bucket and lazy can add a discovery round. Blank/unknown means `off`. |
| `documentTurnToolNarrowingDisabled` | `false` | False allows the special document-search turn narrowing. True disables that narrowing and restores the broader tool list at that stage, increasing context and choice. Other admission rules can still apply. The negative name matters: **false means narrowing is enabled**. |

Replay compaction is part of normal runtime behavior, not a current Composer enable/disable field. Character-to-token planning uses an approximation (3.5 characters/token), model registry information where available, and enforced local rate bounds. An unknown deployment/model name does not give Parler automatic knowledge of its true context limit. See [Context and compaction](./F-context-and-compact.md).

### Document knowledge

These fields configure Parler's repository document tools; they do not upload an entire repository to the LLM at startup.

For the numeric fields below, values above the maximum clamp to that maximum. Values below the minimum reset to the listed default, rather than the minimum: for example, `documentKnowledgeIndexTtlSeconds = 10` becomes **300**, not 30. The exception is `documentKnowledgeSearchMaxLimit`: a value below the effective search default becomes that effective default. These rules differ from the nearest-bound clamping of `tableCsvExportMaxChars` and `llmContextMaxChars`.

| Field | Default / effective bounds | Purpose and tuning trade-off |
| --- | --- | --- |
| `documentKnowledgeBuiltinsEnabled` | `false` | True registers `search_document_chunks`, `get_document_chunk`, and `resolve_document_set`. False leaves these built-in names unregistered, allowing an application to provide extended tools with those names. |
| `documentKnowledgeRepository` | Empty | FileRepository containing document packages. Empty disables the repository-backed document source. It is distinct from `configurationRepository`. |
| `documentKnowledgeRootPath` | `/document-knowledge` | Folder below that repository containing packages. Changing it changes the search corpus; a wrong path can make expected documents unavailable. |
| `documentKnowledgeIndexTtlSeconds` | `300`; 30–86400 | Index cache lifetime. Increase to reduce repeated indexing but keep stale index information longer. Decrease for faster visibility of changes at greater indexing cost. |
| `documentKnowledgeMaxDocuments` | `100`; 1–10000 | Manifest scan cap. Increase for wider corpus coverage with more scan/memory work; decrease to bound work but risk excluding packages. |
| `documentKnowledgeMaxChunks` | `10000`; 1–1000000 | Total indexed-chunk cap. Increase for coverage and memory use; decrease for a smaller index with possible missing evidence. |
| `documentKnowledgeSearchDefaultLimit` | `5`; 1–100 | Default search hit count. More hits expose more candidate evidence and consume more context; fewer are cheaper but can miss useful passages. |
| `documentKnowledgeSearchMaxLimit` | `10`; default-limit–100 | Maximum requested search hits; effective minimum is the configured default limit. Raise for wider explicit searches; lower to contain output while restricting recall. |
| `documentKnowledgeSearchSnippetMaxChars` | `400`; 50–10000 | Maximum snippet length per match. Increase for richer evidence before fetching a chunk; decrease for smaller responses but less context around each hit. |
| `documentKnowledgeChunkMaxChars` | `6000`; 500–500000 | Maximum Markdown returned by a chunk read. Raise to allow longer passages, with greater exposure/context cost; lower can omit needed detail. Tool-result egress may compact it further, so this is not a promise that every character reaches the model. |

## 3. Every concrete Provider table

This extension contains **five** built-in Provider templates. The v4/v5 names select Parler's request shape; they are not a blanket promise that every similarly named model supports it. Responses, Gemini, and Mistral Provider templates are not shipped in this baseline. Confirm the selected deployment accepts the request shape and parameters.

### AzureOpenAIChatV4Settings and AzureOpenAIChatV5Settings

| Field | Present in | Default | Purpose |
| --- | --- | --- | --- |
| `endpoint` | Both | Empty; required | Azure resource endpoint. Parler builds the deployment-specific Chat Completions URL; this is not a deployment name. |
| `apiKey` | Both | Empty; required | Password field containing the Azure API credential. |
| `deployment` | Both | Empty; required | Deployment name in that Azure resource, which may differ from the underlying model id. Changing it changes model behavior, limits, and availability. |
| `apiVersion` | Both | `2025-01-01-preview` | Azure API-version query parameter. This is Parler's configured default, not a guarantee for every deployment; use the version accepted by your endpoint. |
| `timeoutMs` | Both | `120000` | HTTP timeout in milliseconds; see §4. |
| `maxTokens` | v4 only | `4096` | Default output allowance sent as `max_tokens`; Agent positive override wins. See §1 for raise/lower effects. |
| `maxCompletionTokens` | v5 only | `8192` | Default combined reasoning/output allowance sent as `max_completion_tokens`; see §1. |
| `reasoningEffort` | v5 only | `low` | Reasoning effort sent as `reasoning_effort`; see §1 for quality/latency trade-offs and model compatibility. |

### OpenAIChatV4Settings and OpenAIChatV5Settings

| Field | Present in | Default | Purpose |
| --- | --- | --- | --- |
| `baseUrl` | Both | `https://api.openai.com/v1` | API root, including `/v1`; client appends `/chat/completions`. A different host must actually support the chosen request shape. |
| `apiKey` | Both | Empty; required | Password field containing the OpenAI API credential. |
| `model` | Both | Empty; required | Model id sent upstream. Changing it can change capability, context/output limits, latency, and cost. |
| `organizationId` | Both | Empty | Optional `OpenAI-Organization` HTTP header; omitted when blank. |
| `projectId` | Both | Empty | Optional `OpenAI-Project` HTTP header; omitted when blank. |
| `timeoutMs` | Both | `120000` | HTTP timeout in milliseconds; see §4. |
| `maxTokens` | v4 only | `4096` | Default `max_tokens`; overridden by positive Agent/request value. |
| `maxCompletionTokens` | v5 only | `8192` | Default `max_completion_tokens`, including reasoning; see §1. |
| `reasoningEffort` | v5 only | `low` | Default reasoning effort; see §1. |

### AnthropicMessagesSettings

| Field | Default | Purpose |
| --- | --- | --- |
| `baseUrl` | `https://api.anthropic.com` | API host/root to which the client appends `/v1/messages`. Use a deployment-compatible root when using a partner endpoint. |
| `apiKey` | Empty; required | Password field containing the Provider credential. |
| `model` | Empty; required | Exact model id understood by that endpoint; not every model supports identical sampling/thinking options. |
| `anthropicVersion` | `2023-06-01` | Value of the `anthropic-version` HTTP header. It is an API contract version, not the model version. |
| `timeoutMs` | `120000` | HTTP timeout in milliseconds; see §4. |
| `maxTokens` | `8192` | Default `max_tokens`; positive Agent override wins. More room can help completion but allows more output work. Too little can truncate a response or conflict with the thinking budget. |
| `thinkingBudgetTokens` | `0` | Zero omits Parler's explicit thinking object. Positive values send manual extended thinking; detailed constraints below. |
| `samplingParametersMode` | `legacy` | `legacy` sends temperature only when manual thinking is off; `omit` suppresses it. An invalid mode prevents Provider initialization. This controls request compatibility, not the amount of reasoning. |

For `thinkingBudgetTokens`, Parler normalizes negatives to zero and requires a positive value to be **at least 1024 and strictly less than both the configured Provider max and the effective request max**. For example, Provider `maxTokens = 8192`, thinking budget 4096, and Agent override 2048 are incompatible even though the Provider's two values alone are valid.

A higher manual budget allows more reasoning work but can increase latency and reduce room for the answer within the output cap. A lower positive budget allows less reasoning; zero omits the request field rather than universally proving that the selected model performs no reasoning. Models differ in support for manual thinking. The [Anthropic extended-thinking documentation](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) describes the vendor constraints; Parler implements manual `type: enabled`, not an adaptive-thinking configuration field.

**Current Parler limitation:** preservation of assistant thinking blocks across multi-round tool use is not implemented by this adapter. Keep `thinkingBudgetTokens = 0` for the ordinary tool-using Agent workflow; a positive setting can fail on a subsequent tool round. `TestConnection` uses probe mode, which omits manual thinking, so a successful probe does not validate this combination. This is an adapter limitation, not a claim that Anthropic cannot use tools with thinking.

In `legacy` mode with thinking off, the client sends Agent temperature clamped to **0–1**. With positive thinking it omits temperature. `omit` also omits it when thinking is off, useful for deployments that reject sampling parameters. No `top_p` or `top_k` field is emitted by this adapter; choosing `omit` is not a request to set either to zero.

## 4. Every shared LLMAPIProviderRateControl field

All five Provider Things inherit **LLMAPIProviderRateControl**. It coordinates callers of that Provider Thing locally. Two Provider Things using the same upstream quota do not become one shared limiter; neither do two ThingWorx processes. Local limits do not purchase or change vendor quota.

| Field | Default | Meaning and adjustment effects |
| --- | --- | --- |
| `rateControlMode` | `disabled` | `disabled`: no local admission. `observe`: passes calls without blocking; it neither debits buckets nor counts admissions or logs local rejections, and emits estimate-bearing `LLM_RATE_ADMISSION action=allow` diagnostics only with `logAdmissions = true`. Use both settings together to observe estimates, not to simulate enforced bucket consumption. `enforce`: acquire local capacity or reject; this can reduce bursts but introduces local waits/rejections. It cannot eliminate upstream 429s. |
| `tokensPerMinuteLimit` | `0` | Token bucket capacity/refill budget; zero disables this check. Raising permits more local token traffic but can exceed real quota. Lowering smooths load but can delay/reject normal requests. |
| `requestsPerMinuteLimit` | `0` | Request bucket capacity; zero disables this check. Raising permits more calls; lowering throttles multi-round tasks sooner. One user turn can consume several requests. |
| `maxConcurrentRequests` | `0` | In-flight upstream call limit; zero disables this check. Raising a positive limit improves parallel throughput but increases contention/bursts. Lowering serializes more work. **1 is a possible lab policy, not a code-only allowed value.** |
| `maxLocalWaitMs` | `0` | Maximum local wait for capacity in enforce mode; zero fails fast. Raising can absorb short bursts but makes the user wait longer and consumes the turn's time budget. Lowering gives faster feedback but more transient rejections. An impossible oversized reservation is rejected immediately rather than waiting for refill. |
| `tokenReserveStrategy` | Empty | Empty uses the template default: OpenAI/Azure `input_plus_requested_output`, Anthropic `input_only`. Including output is more conservative but reserves more capacity; input-only can admit more work but underestimates quotas that include output. It does not change what is sent in the prompt. |
| `estimateSafetyMultiplier` | `1.15`; minimum 1.0 | Multiplies estimated token requirements. Raise for more quota headroom at lower throughput; lower toward 1 for tighter packing with more estimation risk. It does not reduce actual API usage or cost. |
| `maxSingleRequestTokens` | `0` | Ceiling for one local reservation. Zero uses TPM when enabled, otherwise no single-request token ceiling. Raise to admit larger requests but allow one request to consume more shared capacity. Lower for fairness/resource protection, with possible context trimming or local rejection. It is not the LLM output cap. |
| `logAdmissions` | `false` | True logs every allowed/waited admission, useful for tuning but noisy. Rejections are always logged; waited admissions are still visible when false. |

Use nonnegative integers for rate limits/waits; negative values normalize to zero. Use the documented mode/strategy spellings. These settings are separate from Provider `timeoutMs`: local queueing happens before HTTP dispatch. Raising `timeoutMs` tolerates slow responses but holds a concurrency slot longer; lowering it detects stalled requests sooner but can abort useful generation. The 120000-ms default is 2 minutes, not 120000 seconds. A non-positive `timeoutMs` falls back to 120000 ms in the HTTP clients; it cannot disable the timeout. It is not a total user-turn stopwatch.

## 5. A practical tuning sequence

1. Identify the Agent and actual Provider Thing, record the selected model/deployment and effective output override. Avoid changing an unused Provider.
2. Save configuration and restart the edited Agent/Provider Thing when applying these changes. Their runtime initialization captures settings; do not assume editing a form changes an already running client. Reload repository-backed configuration separately when its files change.
3. Test connection, then manually run a representative tool-using task. A probe does not test long-context admission, multiple tool rounds, or chart construction.
4. For truncated completion, check the effective output cap and reasoning usage. For local rejection, check `LLM_RATE_REJECTION`; for input-budget failure, check `LLM_CONTEXT_PLAN_FAIL`; for upstream errors, inspect `LLM_HTTP_FAILURE`. Wrong column mappings need a mapping fix, not a token increase.
5. To inspect local reservation estimates without enforcement, use `rateControlMode = observe` together with `logAdmissions = true`; observe alone produces no admission-estimate log.
6. Change one relevant setting, rerun the same class of tasks, and compare completion rate, elapsed time, input/output usage, tool rounds, and local waits. Do not use one successful prompt as proof that a setting fits every workload.

The [next chapter](./22-prompt-to-response-and-chart.md) follows these budgets and data boundaries through a complete example.
