# LLM API Provider Parameters

Status: **implemented**.

This document defines parameter ownership for the Providers described in `docs/agent/llm-api-provider.md`. `AgentThing` points at one `LLMAPIProviderThing`, and adding a supported Provider must not require `AgentThing` code changes; each concrete Provider owns its own settings.

## 1. Problem

A single shared connection table on the abstract base (the old `LLMAPIProviderConnection`) would carry:

- `baseUrl`
- `apiKey`
- `modelOrDeployment`
- `apiVersion`
- `timeoutMs`

Such a table is too generic. It leaks Azure terminology into Anthropic, hides important OpenAI v4/v5 differences, and makes Composer show fields that are meaningless for the selected Provider. It also creates an implementation hazard when a concrete Provider tries to redeclare the same table name with a different field set.

The user-facing failure mode is clear: when configuring a Provider, the operator should see the vocabulary of that upstream API, not an invented lowest-common-denominator form.

## 2. Decision

Provider parameters belong to concrete Provider ThingTemplates.

`LLMAPIProviderThing` remains the abstract runtime base and marker shape owner, but it must not declare provider-specific connection/model configuration tables. Each concrete Provider declares its own single-row settings table, with native field names and descriptions.

This is deliberately not an infinitely generic provider framework. The built-in Providers cover these API families:

- Azure OpenAI Chat Completions v4
- Azure OpenAI Chat Completions v5
- OpenAI Chat Completions v4
- OpenAI Chat Completions v5
- Anthropic Messages API

OpenAI-compatible vendors, local LLM servers, failover routing, multi-provider selection, and arbitrary request adapters are out of scope for this document.

Out-of-tree extensions may still ship additional ThingTemplates that implement `LLMAPIProviderShape`. This document defines the in-tree Provider set and the runtime contract those Providers should follow; it does not attempt to design a general provider marketplace.

## 3. Invariants

1. `AgentThing` references a Provider Thing by `llmApiProviderRef`.
2. `AgentThing` does not know whether the Provider is Azure, OpenAI, or Anthropic.
3. Adding a built-in Provider requires a new Provider ThingTemplate and Provider client code, not an `AgentThing` field or switch-case change.
4. The abstract base does not declare a shared provider-parameter table.
5. Concrete Providers do not override same-named inherited configuration tables.
6. Each Provider uses native parameter names where that reduces operator confusion.
7. Secret values stay `PASSWORD`.
8. Provider Things created with an older table layout are not migrated automatically.

## 4. Base Class Responsibility

`LLMAPIProviderThing` should own only the common runtime contract:

- marker shape: `LLMAPIProviderShape`
- `TestConnection`
- client lifecycle / lazy rebuild after initialize
- Provider identity for telemetry:
  - `providerThingName`
  - `providerTemplateName`
  - `apiShapeId`
  - effective model or deployment label
- provider-neutral request-option resolution:
  - effective max output tokens
  - effective reasoning effort
- optional common helper methods for reading configuration data
- abstract hooks implemented by concrete subclasses

Shape (abridged):

```java
public abstract class LLMAPIProviderThing extends Thing {
    public abstract String getApiShapeId();
    public abstract String getEffectiveModelLabel();
    public abstract int resolveMaxOutputTokens(LlmChatRequest request);
    public abstract Optional<String> resolveReasoningEffort(LlmChatRequest request);

    protected abstract LlmClient buildDelegateClient();

    public final LlmClient getLlmClient() { ... }

    public Boolean TestConnection() { ... }
}
```

`getEffectiveModelLabel()` returns the exact string the Provider sends upstream as model identity: Azure returns `deployment`; OpenAI and Anthropic return `model`. The same string fills telemetry such as `LlmUsageWireIds.model`, `LLM_USAGE`, `LLM_HTTP_FAILURE`, `LLM_RATE_LIMIT`, and rate-control diagnostics.

`resolveMaxOutputTokens(...)` and `resolveReasoningEffort(...)` are not rate-control policy. They are the single source of truth for request-option resolution. The Provider HTTP client and the admission/rate estimator use the same resolved values so HTTP payloads, usage telemetry, and local admission estimates cannot diverge.

`resolveMaxOutputTokens(...)` must always return a positive integer. It must never return `0` or negative values; those are only unresolved inputs from Agent configuration or request construction. Values above `Integer.MAX_VALUE` must throw `IllegalArgumentException` (see `ProviderRequestResolution.toPositiveResolvedMaxOutput`). This deliberately avoids pushing fallback logic back into delegate clients and gives rate control a concrete output-token reservation value. Provider code defaults are therefore explicit local defaults, not hidden upstream defaults.

The base should not contain fields named `baseUrl`, `modelOrDeployment`, `apiVersion`, `maxTokens`, or similar. Those are concrete Provider concerns.

## 5. Provider Settings Tables

Each concrete Provider uses one primary single-row table named after the Provider family. Keep table names explicit and stable; do not reuse `LLMAPIProviderConnection`.

### 5.1 Azure OpenAI Chat v4

ThingTemplate: `AzureOpenAIChatV4Provider`

Table: `AzureOpenAIChatV4Settings`

Fields:

| Field | BaseType | Notes |
| --- | --- | --- |
| `endpoint` | STRING | Azure resource endpoint, e.g. `https://your-resource.openai.azure.com/`. |
| `apiKey` | PASSWORD | Azure OpenAI API key. |
| `deployment` | STRING | Azure deployment name, not model version. |
| `apiVersion` | STRING | Required; example `2025-01-01-preview`; validate against the deployed Azure API shape. |
| `timeoutMs` | INTEGER | Default `120000`. |
| `maxTokens` | INTEGER | Default `4096`; Chat v4 native output cap field. |

Runtime request field: `max_tokens`.

### 5.2 Azure OpenAI Chat v5

ThingTemplate: `AzureOpenAIChatV5Provider`

Table: `AzureOpenAIChatV5Settings`

Fields:

| Field | BaseType | Notes |
| --- | --- | --- |
| `endpoint` | STRING | Azure resource endpoint. |
| `apiKey` | PASSWORD | Azure OpenAI API key. |
| `deployment` | STRING | Azure deployment name. |
| `apiVersion` | STRING | Required; the working current deployment uses `2025-01-01-preview`; validate against the deployed Azure API shape. |
| `timeoutMs` | INTEGER | Default `120000`. |
| `maxCompletionTokens` | INTEGER | Default `8192`; includes visible output plus reasoning tokens on v5. |
| `reasoningEffort` | STRING | Default `low`; allowed values should match the model family, e.g. `minimal`, `low`, `medium`, `high`. |

Runtime request field: `max_completion_tokens`. Chat v5 clients **omit** `temperature` (same as OpenAI Chat v5).

### 5.4 OpenAI Chat v4

ThingTemplate: `OpenAIChatV4Provider`

Table: `OpenAIChatV4Settings`

Fields:

| Field | BaseType | Notes |
| --- | --- | --- |
| `baseUrl` | STRING | Default `https://api.openai.com/v1`. |
| `apiKey` | PASSWORD | OpenAI API key. |
| `model` | STRING | OpenAI model id. |
| `organizationId` | STRING | Optional `OpenAI-Organization` request header; omit when blank. |
| `projectId` | STRING | Optional `OpenAI-Project` request header; omit when blank. |
| `timeoutMs` | INTEGER | Default `120000`. |
| `maxTokens` | INTEGER | Default `4096`; Chat v4 native output cap field. |

Runtime request field: `max_tokens`.

### 5.5 OpenAI Chat v5

ThingTemplate: `OpenAIChatV5Provider`

Table: `OpenAIChatV5Settings`

Fields:

| Field | BaseType | Notes |
| --- | --- | --- |
| `baseUrl` | STRING | Default `https://api.openai.com/v1`. |
| `apiKey` | PASSWORD | OpenAI API key. |
| `model` | STRING | OpenAI model id. |
| `organizationId` | STRING | Optional `OpenAI-Organization` request header; omit when blank. |
| `projectId` | STRING | Optional `OpenAI-Project` request header; omit when blank. |
| `timeoutMs` | INTEGER | Default `120000`. |
| `maxCompletionTokens` | INTEGER | Default `8192`; includes visible output plus reasoning tokens on v5. |
| `reasoningEffort` | STRING | Default `low`. |

Runtime request field: `max_completion_tokens`. Chat v5 clients **omit** `temperature` on the wire; current GPT-5 / reasoning models often reject non-default temperature (use vendor default).

### 5.7 Anthropic Messages

ThingTemplate: `AnthropicMessagesProvider`

Table: `AnthropicMessagesSettings`

Fields:

| Field | BaseType | Notes |
| --- | --- | --- |
| `baseUrl` | STRING | Default `https://api.anthropic.com`. |
| `apiKey` | PASSWORD | Anthropic API key. |
| `model` | STRING | Anthropic model id. |
| `anthropicVersion` | STRING | Default `2023-06-01`. This is the `anthropic-version` header; the client reads this field rather than a hardcoded constant. |
| `timeoutMs` | INTEGER | Default `120000`. |
| `maxTokens` | INTEGER | Default `8192`; Anthropic native output cap field. |
| `thinkingBudgetTokens` | INTEGER | Default `0` (omit thinking). When positive, must be `>= 1024` and `< maxTokens`; sent as Anthropic `thinking.budget_tokens`. Extended thinking omits `temperature` on the wire (Anthropic requires its default). |
| `samplingParametersMode` | STRING | Default `legacy`; values `legacy` or `omit`. `legacy` sends `temperature` when thinking is off. `omit` suppresses Anthropic sampling parameters; today this suppresses only `temperature`, because no other sampling fields are emitted. Use `omit` for deployments such as Azure AI Foundry Opus 4.8 that reject `temperature`. |

Anthropic does not expose Azure `apiVersion`, and Composer should not show one for this Provider.

Runtime request field: `max_tokens`.

**Limitation:** positive `thinkingBudgetTokens` is wired for **single-turn** requests without tools. Multi-turn AgentLoop iterations that use tools require preserving prior assistant `thinking` blocks per Anthropic's tool-use + extended-thinking contract; Parler does not preserve them. Operators who enable a positive budget on an Agent that uses tools may see upstream errors on the second tool round. Default remains `0`.

### 5.10 Choosing a Chat template for an endpoint (Azure OpenAI, Azure AI Foundry, compatible gateways)

The built-in Chat clients are narrow adapters, not a generic HTTP proxy. Each one builds a fixed URL from its root field and sends a fixed auth header:

| Upstream wire shape | ThingTemplate | Root field and URL built | Auth header |
| --- | --- | --- | --- |
| Classic Azure deployment `…/openai/deployments/{deployment}/chat/completions?api-version=…` | `AzureOpenAIChatV4Provider` / `AzureOpenAIChatV5Provider` (`AzureOpenAILlmClient`) | `endpoint` = **resource root** only (for example `https://<resource>.openai.azure.com`); one trailing `/` is stripped, then `/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}` is appended | `api-key` |
| OpenAI-compatible `…/v1/chat/completions`, including an Azure / Foundry `…/openai/v1` root | `OpenAIChatV4Provider` / `OpenAIChatV5Provider` (`OpenAiChatCompletionsClient`) | `baseUrl` = the full `…/v1` root (empty defaults to `https://api.openai.com/v1`); `/chat/completions` is appended | `Authorization: Bearer <apiKey>` |
| Anthropic Messages `…/v1/messages` | `AnthropicMessagesProvider` (`AnthropicMessagesLlmClient`) | `baseUrl` = root such that `baseUrl + "/v1/messages"` is the invoke URL (default `https://api.anthropic.com`) | `x-api-key` plus `anthropic-version` |

Operator rules:

- Never put `/openai/v1` into an Azure template's `endpoint`: the client appends the deployment path and produces a malformed URL such as `…/openai/v1/openai/deployments/…`. If the portal documents only an OpenAI-compatible `/openai/v1` root, use an OpenAI Chat template with that root as `baseUrl`.
- For the Azure templates, `deployment` is the deployment name from the portal (not the model marketing name unless they match), and `apiVersion` must be a revision the resource supports.
- Pick the template whose auth header the gateway accepts; a mismatched scheme returns 401 even when the URL is correct.
- A Claude-family model can be reached through the Anthropic template only when the gateway is wire-compatible with the Anthropic Messages API (path, JSON shape, and `x-api-key` semantics). If the gateway exposes it only through `/chat/completions` with an OpenAI-style payload, use an OpenAI Chat template. Gateways that require Entra ID tokens, a different path, or vendor-specific headers are not supported by configuration alone.
- When both shapes exist in one organization, create one Provider Thing per shape and point each `AIAgent`'s `llmApiProviderRef` at the one it should use.
- After changing configuration, run the Provider Thing's `TestConnection` (or one short agent turn) and inspect `ApplicationLog` / the HTTP failure diagnostics.

## 6. Effective Request Options

The agent loop should keep passing a provider-neutral `LlmChatRequest`. The Provider translates it into native JSON.

Output-token handling belongs in the Provider because the field name and semantics are API-shape specific:

| API shape | Provider setting | Native request field |
| --- | --- | --- |
| Chat v4 | `maxTokens` | `max_tokens` |
| Chat v5 | `maxCompletionTokens` | `max_completion_tokens` |
| Anthropic Messages | `maxTokens` | `max_tokens` |

Provider max-output settings are **defaults only**, not hard caps. `maxTokens` / `maxCompletionTokens` never mean both default and ceiling.

Max-output resolution order:

1. Request-level override from the agent loop, if `requestedMaxOutputTokens > 0`.
2. Provider setting from the concrete table, if the request value is `<= 0` and the Provider setting is positive.
3. Provider code default, if both request and table are unset.

`AgentSettings.maxTokens` defaults to `-1` so normal AgentThing configuration uses the Provider's native max-output default. A positive Agent value overrides Provider settings on every request. Request builders do not map `<= 0` to a hardcoded value before the Provider sees the request; that fallback lives inside each Provider.

Provider code defaults must be positive and documented by the concrete Provider via `providerCodeDefaultMaxOutputTokens()` on each built-in subclass. Built-in code defaults:

| Provider | Max-output default | Reasoning default |
| --- | ---: | --- |
| Azure / OpenAI Chat v4 | `4096` | omit |
| Azure / OpenAI Chat v5 | `8192` | `low` |
| Anthropic Messages | `8192` | omit |

Even where an upstream API can omit the output-token field, Parler's Provider path should resolve a local positive default for deterministic HTTP payloads, diagnostics, and rate admission. A table value of `0`, blank, or missing means "unset", not "send zero".

Reasoning effort follows the same default/override shape:

1. Request-level `reasoningEffort`, if non-blank.
2. Provider table `reasoningEffort`, if non-blank.
3. Provider code default (`providerCodeDefaultReasoningEffort()`), if non-blank; Chat v5 built-ins use `low`.
4. Otherwise omit the field and let the vendor/model default apply.

For STRING fields that look enum-like, such as `reasoningEffort`, Provider code should warn and pass through non-blank values rather than silently dropping them. The upstream API then remains the final validator and returns the clearest provider-native error.

**Anthropic extended thinking:** see §5.7 — positive `thinkingBudgetTokens` is single-turn-safe only, because thinking blocks are not preserved across tool loops.

## 7. Runtime Construction

Client construction lives in concrete Provider subclasses; there is no central factory or provider enum.

Pattern (simplified from `AzureOpenAIChatV5Provider`):

```java
public class AzureOpenAIChatV5Provider extends LLMAPIProviderThing {
    private volatile Snapshot settings;

    @Override
    protected LlmClient buildDelegateClient() {
        Snapshot s = loadSettings();   // reads AzureOpenAIChatV5Settings
        this.settings = s;
        return new AzureOpenAILlmClient(s.endpoint, s.apiKey, s.deployment, s.apiVersion, s.timeoutMs, true);
    }
}
```

The base does not need a central switch on provider type. That switch would recreate the enum problem under a different name.

Concrete Providers should retain their parsed settings on the Provider instance after initialization. Do not only pass values into the `LlmClient` constructor and discard them. Runtime code needs stable access to the effective model label and max-output defaults, and rate-control code does not re-read configuration tables or use class-name introspection on every request.

`ProviderLlmClientBridge.chat()` is the runtime choke point for Provider resolution. It resolves max-output and reasoning effort exactly once through the owning Provider, then passes an augmented request to the delegate `LlmClient`. Delegate clients must treat `requestedMaxOutputTokens` as the final positive value and must not apply another `<= 0 -> default` fallback.

Augmentation shape:

```java
public static LlmChatRequest copyWithProviderAugmentation(
    LlmChatRequest base,
    LlmUsageWireIds usageWireIdsOverride,
    int resolvedMaxOutputTokens,
    String resolvedReasoningEffort);
```

Concrete Provider clients own the native request field (`max_tokens` or `max_completion_tokens`) at compile time; there is no request-level flag that forces a field name. `ProviderLlmClientBridge.healthCheck()` may continue to pass a small positive request override for cheap probes; that naturally wins through the same resolution order.

**`LlmChatRequest.probeMode`:** production callers never set this flag. Diagnostic paths (`ProviderLlmClientBridge.healthCheck()`, delegate `healthCheck()` implementations) set `probeMode=true` so vendors can make conservative probe requests. Today Anthropic skips extended thinking on probes even when `thinkingBudgetTokens` is configured on the Provider Thing. Other delegate clients ignore the flag until a vendor-specific probe behavior is needed.

## 8. Composer UX

The Composer form should make the selected Provider obvious:

- Azure shows `deployment` and `apiVersion`.
- OpenAI shows `model`, optional `organizationId`, optional `projectId`, and no Azure deployment wording.
- Anthropic shows `model` and `anthropicVersion`, not `apiVersion`.
- Chat v4 shows `maxTokens`.
- Chat v5 shows `maxCompletionTokens`.

This is the practical reason for per-Provider tables. The table names and descriptions are part of the operator experience, not just Java internals.

## 9. Test Plan

### 9.1 Metadata / Import

- Extension imports cleanly.
- Every Provider ThingTemplate is creatable.
- Every Provider ThingTemplate implements `LLMAPIProviderShape`.
- `AgentThing.llmApiProviderRef` can select every Provider Thing.
- No concrete Provider redeclares a same-named inherited configuration table.

### 9.2 Composer Configuration

- Anthropic Provider has no Azure `apiVersion`.
- Azure Providers use `deployment`, not `modelOrDeployment`.
- OpenAI Providers use `model`, not `deployment`.
- v4/v5 Providers expose the correct max-output field.
- `apiKey` fields are `PASSWORD`.

### 9.3 Runtime

- `TestConnection` works for each configured Provider.
- `AgentSettings.maxTokens` default `-1` allows Provider max-output defaults to take effect.
- Azure Chat v4 emits `max_tokens`.
- Azure Chat v5 emits `max_completion_tokens`.
- Anthropic emits `anthropic-version` header and `max_tokens`.
- OpenAI optional headers map `organizationId` to `OpenAI-Organization` and `projectId` to `OpenAI-Project`, omitting blank values.
- Telemetry includes Provider Thing name, template name, `apiShapeId`, model/deployment label, and usage fields where the upstream API returns them.

### 9.4 Regression

- Existing `AgentThing` chat path still calls through `llmApiProviderRef`.
- No `AgentThing` code change is needed when adding a Provider class.
- Existing eval harness can run the same suite against multiple AgentThings backed by different Provider Things.
- With `AgentSettings.maxTokens=-1` and Provider `maxCompletionTokens=8192`, the emitted v5 request uses `8192`.
- With explicit legacy `AgentSettings.maxTokens=4096` and Provider `maxCompletionTokens=8192`, the emitted v5 request uses `4096`; existing explicit Agent values keep winning until an operator changes them.

## 11. Non-goals

- No migration from old Provider Things.
- No same-table inheritance override.
- No provider marketplace.
- No runtime provider failover.
- No per-user provider selection.
- No rate-control semantics in this document; Provider-level rate control is described in [`rate-control.md`](rate-control.md) (`LLMAPIProviderRateGate`).

## 12. Rate-Control Compatibility

Provider-level rate control depends on these parameter contracts:

1. Rate control reads model identity through `getEffectiveModelLabel()`, not by inspecting provider-specific settings tables.
2. Rate control reserves output tokens using the resolved max-output value (`resolveMaxOutputTokens(LlmChatRequest)`), the same value used by the HTTP request builder.
3. Rate control does not need `instanceof AzureOpenAIChatV5Provider` to find `maxCompletionTokens`; the Provider resolver hides native field names.
4. Concrete Providers keep parsed settings on the Provider instance so rate control can access effective values without table re-reads.

RPM/TPM fields, reserve strategies, retry policy, and queue behavior are not part of this document.
