# Parler Agent metrics

Parler Agent registers the metrics below through ThingWorx MetricsV2. Configure the platform's OpenTelemetry metrics export to send data to your backend. The tables list the registered names. ThingWorx can add a `thingworx_` prefix, and the backend may further transform the names.

## Turn and tool metrics

These metrics use Parler execution events and do not depend on provider-reported usage.

| Metric | Type | Dimensions | What it measures |
| --- | --- | --- | --- |
| `parler_turns_total` | Counter | `entry_point`, `outcome` | One count per completed segment of an admitted turn. |
| `parler_turn_duration_ms` | Histogram | `entry_point`, `outcome` | Elapsed time from admitted execution through synchronous terminal work and cleanup, in milliseconds. |
| `parler_turns_in_flight` | Observable gauge | `entry_point` | Currently executing turn segments on the node, including zero values. |
| `parler_tool_executions_total` | Counter | `tool_kind`, `outcome` | One count per completed dispatcher call or approved gated attempt, including terminal blocks. |
| `parler_tool_duration_ms` | Histogram | `tool_kind`, `outcome` | Elapsed time through the dispatcher or approved attempt, in milliseconds. |

- `entry_point` is `chat`, `chat_async`, `stream`, or `approval_resume`. Turn `outcome` is `success`, `error`, `cancelled`, or `awaiting_approval`.
- Approval pauses end the current segment. A continuation starts a new segment. Queue time, initial conversation lock wait, and time spent waiting for approval are outside turn duration. Rejected admissions contribute no turn sample.
- `tool_kind` is `built_in`, `custom`, `workflow`, or `other`. Tool `outcome` is `success` or `error`. A tool waiting for approval contributes a sample only if the approved dispatch is attempted. A workflow's duration can overlap with the durations of tools it dispatches.

## LLM provider metrics

These metrics cover requests dispatched through the built-in HTTP clients for OpenAI Chat Completions, Azure OpenAI Chat Completions, and Anthropic Messages. Each dispatched retry or fallback is a separate attempt. Rejections before HTTP dispatch contribute no sample.

| Metric | Type | Dimensions | Provider dependency |
| --- | --- | --- | --- |
| `parler_llm_attempts_total` | Counter | `provider_family`, `call_kind`, `outcome` | Counts each dispatched attempt at its terminal outcome, including transport failures. |
| `parler_llm_attempt_duration_ms` | Histogram | `provider_family`, `call_kind`, `outcome` | Measures HTTP dispatch through response receipt or transport failure, in milliseconds. Parse failures retain the HTTP duration. |
| `parler_llm_tokens_total` | Counter | `provider_family`, `call_kind`, `token_kind` | Adds each valid provider token count as its own `token_kind` series. Missing fields and invalid usage contribute no tokens. |
| `parler_llm_usage_samples_total` | Counter | `provider_family`, `call_kind`, `usage_status` | Counts one usage status per dispatched attempt, including responses with missing or invalid usage and transport failures. Use it to assess token coverage. |

- `provider_family` is `openai`, `azure_openai`, `anthropic`, or `other` for an unrecognized API shape on the instrumented path. `call_kind` is `agent_round`, `checkpoint`, `playbook`, or `probe`.
- Provider `outcome` is `success`, `http_error`, `parse_error`, `timeout`, or `transport_error`. `token_kind` is one of the values in the token kind table below.
- `usage_status` is `complete`, `partial`, `unavailable`, or `invalid` for dispatched attempts.
- Input and output counts are recorded independently. Anthropic's normalized input total includes input, cache-read, and cache-creation tokens when all three counts are available. A provider error can contribute tokens if its response contains valid usage.

### Token kinds

Parler registers one token counter. Each `token_kind` series counts one defined usage field and receives observations only when the selected provider supplies valid data for it. OpenAI and Azure OpenAI share the Chat Completions mapping. An unrecognized API shape (`provider_family=other`) uses the same mapping.

| `token_kind` | OpenAI / Azure OpenAI | Anthropic |
| --- | --- | --- |
| `input` | `prompt_tokens` | Uncached input plus cache read plus cache creation, when all three are reported |
| `output` | `completion_tokens` | `output_tokens` |
| `total` | `total_tokens` | Derived `input` plus `output_tokens`, when all underlying counts are present |
| `input_uncached` | `prompt_tokens` minus `prompt_tokens_details.cached_tokens`, when both are reported | `input_tokens` |
| `input_cache_read` | `prompt_tokens_details.cached_tokens` | `cache_read_input_tokens` |
| `input_cache_write` | Not reported | `cache_creation_input_tokens` |
| `input_cache_write_5m` | Not reported | `cache_creation.ephemeral_5m_input_tokens` |
| `input_cache_write_1h` | Not reported | `cache_creation.ephemeral_1h_input_tokens` |
| `reasoning` | `completion_tokens_details.reasoning_tokens` | `output_tokens_details.thinking_tokens`, when reported |
| `input_audio` | `prompt_tokens_details.audio_tokens` | Not reported |
| `output_audio` | `completion_tokens_details.audio_tokens` | Not reported |
| `output_accepted_prediction` | `completion_tokens_details.accepted_prediction_tokens` | Not reported |
| `output_rejected_prediction` | `completion_tokens_details.rejected_prediction_tokens` | Not reported |

- Series overlap. `input` includes cached input, `input_cache_write` includes its TTL breakdowns, and `reasoning` can be part of `output`. Do not sum across `token_kind`; select series by meaning and use `provider_family` when comparing providers.
- A missing field adds nothing and is not treated as zero. Parler does not reconstruct absent totals or infer Anthropic TTL splits.
- A reported `0` is a measurement and can create a zero-valued series.
- A usage object with any invalid allowlisted count is `invalid` in `parler_llm_usage_samples_total` and adds no token series, including `input` and `output`. Other provider usage keys are ignored.

## Deployment and interpretation

Turn and provider duration histograms use millisecond bucket boundaries of `10, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000`. Tool duration uses `1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000`.

The platform adds a `platformid` attribute. Parler uses bounded dimensions and excludes Thing names, tool names, model and deployment names, users, conversation IDs, and request contents. Use the existing logs and LLM usage Streams for per-call investigation and historical reporting.

After updating or re-importing the extension in a running JVM, restart ThingWorx so the in-flight gauge observes the current extension runtime. If instrument registration fails, Parler logs a diagnostic and disables recording for that extension runtime.
