# Multi-LLM Provider Support

Status: **implemented** — `AnthropicMessagesSettings.samplingParametersMode` (`legacy` / `omit`) for deployments that reject `temperature` (e.g. Azure AI Foundry Claude Opus 4.8).

Document type: specification for a narrow `parler-agent` Provider compatibility option. This document is not a wire contract; it covers Java extension behavior only.

## 1. Purpose

Some Anthropic Messages-compatible deployments (for example Azure AI Foundry Claude Opus 4.8) reject the `temperature` field:

```text
`temperature` is deprecated for this model.
```

`samplingParametersMode` lets an operator suppress sampling parameters for such a deployment while existing Anthropic Provider Things keep their default behavior.

## 2. Scope

- `AnthropicMessagesProvider` serves both direct Anthropic and Azure AI Foundry Claude deployments that accept Anthropic Messages-compatible JSON.
- One Provider-level configuration option controls whether Anthropic sampling parameters such as `temperature` are emitted.
- `TestConnection` exercises the same request-construction behavior as normal chat.

Not covered: Composer dropdown / `selectOptions` validation, rate control, OpenAI / Azure OpenAI reasoning options, Anthropic thinking / effort options, context-window planning, a separate Foundry-specific Provider type, and model discovery or routing.

## 3. Request construction

`AnthropicMessagesProvider` builds `AnthropicMessagesLlmClient`, which calls `AnthropicMessagesApi.buildRequestPayload(...)`. `healthCheck()` uses the same client `chat(...)` path as normal traffic, with `probeMode` forcing extended thinking off for small probes.

Temperature rule:

```text
if samplingParametersMode == legacy and effectiveThinkingBudgetTokens == 0:
    send clamped temperature
else:
    omit temperature
```

`effectiveThinkingBudgetTokens` is the value the client uses after applying probe behavior, so `healthCheck()` and normal chat share one request-construction path. Health checks are not special-cased: a model that rejects `temperature` passes both `TestConnection` and normal Agent turns once the operator sets `samplingParametersMode=omit`. A positive `thinkingBudgetTokens` always omits `temperature` and emits Anthropic `thinking`.

## 4. Configuration

`AnthropicMessagesSettings` field:

| Field | BaseType | Default | Values | Meaning |
| --- | --- | --- | --- | --- |
| `samplingParametersMode` | `STRING` | `legacy` | `legacy`, `omit` | Controls whether Anthropic sampling fields such as `temperature` are sent when thinking is not active. |

Modes:

- `legacy`: when effective thinking is off, send clamped `temperature`.
- `omit`: never send `temperature`. Use this for deployments that reject sampling parameters.

`omit` suppresses only `temperature` because that is the only Anthropic sampling parameter this Provider emits. If the Anthropic Messages request ever gains `top_p`, `top_k`, or another sampling field, `omit` must suppress those fields too.

The field renders as a plain string configuration value; runtime parsing is authoritative.

### 4.1 Parsing and compatibility

- `AnthropicSamplingParametersMode.parse` trims and case-folds the value; null or blank resolves to `legacy`, so existing Provider Things keep the same request shape.
- An unknown non-blank value fails in `AnthropicMessagesProvider.loadSettings()` with a clear message, so invalid settings fail when the Provider client is built rather than during a later request.
- The resolved mode is stored in the Provider settings snapshot and passed to `AnthropicMessagesLlmClient` and `AnthropicMessagesApi.buildRequestPayload(...)`. Overloads without a mode default to `legacy`.

## 5. Tests

Unit tests cover:

- Default / missing mode keeps sending `temperature` when thinking is off.
- `legacy` keeps sending `temperature` when thinking is off.
- `omit` does not send `temperature`.
- Positive thinking still omits `temperature`.
- Probe-style payload with `omit` also omits `temperature`.
- Parser handles null, blank, case folding, and rejects unknown values with a clear message.

Operator check for a Foundry deployment: create an `AnthropicMessagesProvider` Provider Thing, set `samplingParametersMode=omit`, run `TestConnection` and one no-tool prompt, and confirm `LLM_HTTP_FAILURE` no longer reports deprecated `temperature`. Unit tests prove the payload omits `temperature`; only the live deployment confirms it accepts the request.

## 6. Non-Goals

- No automatic model-prefix `auto` mode.
- No Composer dropdown / enum validation.
- No changes to OpenAI, Azure OpenAI, rate control, context planning, or Anthropic effort/thinking.
- No new Provider type unless an endpoint requires different auth, connection semantics, or request/response shape.
