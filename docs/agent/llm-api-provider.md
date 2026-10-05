# LLM API Provider: ThingTemplate-based abstraction for upstream model connections

Status: **implemented** — Provider ThingTemplate abstraction, resolver, listing, and Agent **`llmApiProviderRef`** wiring.

Document type: normative for Provider architecture. Per-template connection/settings tables, request-option resolution, and runtime client construction are specified in **`llm-api-provider-parameters.md`**. Provider-level rate control is specified in **`rate-control.md`**.

> **Provider connection parameters:** each built-in Provider ThingTemplate owns its own native settings table (`AzureOpenAIChatV4Settings`, `AzureOpenAIChatV5Settings`, `OpenAIChatV4Settings`, `OpenAIChatV5Settings`, `AnthropicMessagesSettings`). There is no shared connection table on the abstract base.

## 1. Purpose

An upstream LLM connection has four concerns that do not belong on the agent:

- **Vendor identity** (Anthropic vs OpenAI vs Azure).
- **API shape** (Chat Completions v4 vs v5 vs Anthropic Messages).
- **Account credentials** (API key, base URL, organization id, Azure deployment, etc.).
- **Model selection** (model name or deployment).

Keeping them on each `AgentThing` would cause three problems:

1. **No rate-limit aggregation across AgentThings.** Two AgentThings that share an API key and model would be treated as independent for rate accounting, while the upstream provider applies one bucket.
2. **Every new model variant forces a Parler upgrade** when the vendor changes wire fields (for example `max_completion_tokens` and `reasoning_effort` on gpt-5.x).
3. **OpenAI-compatible vendors** (Ollama, Together, Groq, corporate deployments) cannot be supported cleanly.

Parler therefore uses a **ThingTemplate-based two-tier abstraction**:

- **Provider ThingTemplate hierarchy**: a Java abstract base class `LLMAPIProviderThing extends Thing`, with one concrete subclass per API shape. Each concrete subclass becomes a ThingTemplate via parler-agent's `metadata.xml` — a `ThingPackage` (binding the Java class) paired with a `ThingTemplate(thingPackage=...)`, the same pattern `AgentThing` uses. No `@ThingworxBaseTemplateDefinition` annotation is used (§4.1).
- **Provider Thing instances**: administrators create one Thing per (API shape × API key × model) combination and fill its settings table.
- **`AgentThing` references one Provider Thing** through `AgentSettings.llmApiProviderRef` (`THINGNAME` with `thingShape:LLMAPIProviderShape` aspect). Multiple AgentThings can share one Provider; upstream rate limits aggregate at the API-key + model boundary where they physically live.

## 2. Why a ThingTemplate, not a new root entity type

ThingWorx ships provider families such as `PersistenceProviderPackage` / `QueueProviderPackage` as their own root entity types. An extension cannot do the same: `RelationshipTypes.ThingworxRelationshipTypes` is a closed platform enum with no value for an LLM provider, and the platform's import/export, model providers, and Composer all index off that enum. A `ServiceProviderEntity` subclass shipped in an extension would compile but would have no entity type the platform could file it under.

The Provider therefore follows the `AgentThing` pattern: extend `Thing`, declare configuration tables via `@ThingworxConfigurationTableDefinitions`, and bind the Java class to a ThingTemplate through a `metadata.xml` `ThingPackage` + `ThingTemplate(thingPackage=...)` pair. The separate `ParlerGateway` pattern (`@ThingworxBaseTemplateDefinition(name = "SDKGateway")`) is only needed because `ParlerGateway` inherits from a non-standard platform base template; Providers do not.

## 3. Scope and non-goals

### In scope

- A Java abstract base `LLMAPIProviderThing extends Thing` carrying the rate-control configuration table, abstract API-shape methods, the Provider services, and the runtime `LlmClient` accessor.
- Five built-in concrete subclasses (§6.1).
- `AgentSettings.llmApiProviderRef` (`THINGNAME` with `thingShape:LLMAPIProviderShape` aspect) instead of inline credentials on the agent.
- A single upstream call boundary: every agent LLM call goes through the Provider's bridged `LlmClient` (§8). Async entry points (`ChatAsync`, `ParlerStreamToRemoteThing`) call the synchronous client from their own worker threads.
- A thread-safety contract for the shared client (§5.8).
- Provider identity in telemetry (§10.2).
- A custom-extension contract for authors who ship their own ThingTemplate (§7).

### Out of scope

- Multi-provider routing within one `AgentThing`.
- Failover between Provider Things.
- Cross-Provider RPM coordination when two Provider Things share an API key (known limitation; see §5.5).
- Migration of older `AgentThing` configurations.
- Streaming output.
- Responses API, Gemini, Mistral, and AWS Bedrock templates.

## 4. ThingWorx form

### 4.1 `metadata.xml` declarations

parler-agent's `metadata.xml` declares the marker shape and, for each concrete Provider, a `ThingPackage` (binding the Java class) and a `ThingTemplate` (binding the package). The `ThingTemplate` **MUST NOT** use `baseThingTemplate=`: `ThingTemplate.fromXML` skips the `thingPackage` binding when both attributes are set, leaving the Java class binding empty. Provider Java classes simply `extends LLMAPIProviderThing`, exactly as `AgentThing extends Thing`.

```xml
<ThingShapes>
  <ThingShape name="LLMAPIProviderShape"
              description="Marker for Things that act as an LLM API Provider; filters AgentThing.llmApiProviderRef"/>
</ThingShapes>

<ThingPackages>
  <ThingPackage name="OpenAIChatV4Provider"
                description="OpenAI Chat Completions API (max_tokens family) Provider"
                className="com.thingworx.things.agent.llm.providers.openai.OpenAIChatV4Provider"
                aspect.isCreatable="true">
    <HandlerDefinitions/>
  </ThingPackage>

  <ThingPackage name="OpenAIChatV5Provider"
                description="OpenAI Chat Completions API (max_completion_tokens / reasoning_effort) Provider"
                className="com.thingworx.things.agent.llm.providers.openai.OpenAIChatV5Provider"
                aspect.isCreatable="true">
    <HandlerDefinitions/>
  </ThingPackage>

  <ThingPackage name="AzureOpenAIChatV4Provider"
                description="Azure OpenAI Chat Completions v4 Provider"
                className="com.thingworx.things.agent.llm.providers.azureopenai.AzureOpenAIChatV4Provider"
                aspect.isCreatable="true">
    <HandlerDefinitions/>
  </ThingPackage>

  <ThingPackage name="AzureOpenAIChatV5Provider"
                description="Azure OpenAI Chat Completions v5 Provider"
                className="com.thingworx.things.agent.llm.providers.azureopenai.AzureOpenAIChatV5Provider"
                aspect.isCreatable="true">
    <HandlerDefinitions/>
  </ThingPackage>

  <ThingPackage name="AnthropicMessagesProvider"
                description="Anthropic Messages API Provider (Claude Sonnet/Opus/Haiku)"
                className="com.thingworx.things.agent.llm.providers.anthropic.AnthropicMessagesProvider"
                aspect.isCreatable="true">
    <HandlerDefinitions/>
  </ThingPackage>
</ThingPackages>

<ThingTemplates>
  <!-- thingPackage required; do NOT set baseThingTemplate here. -->
  <ThingTemplate name="OpenAIChatV4Provider"      thingPackage="OpenAIChatV4Provider"/>
  <ThingTemplate name="OpenAIChatV5Provider"      thingPackage="OpenAIChatV5Provider"/>
  <ThingTemplate name="AzureOpenAIChatV4Provider" thingPackage="AzureOpenAIChatV4Provider"/>
  <ThingTemplate name="AzureOpenAIChatV5Provider" thingPackage="AzureOpenAIChatV5Provider"/>
  <ThingTemplate name="AnthropicMessagesProvider" thingPackage="AnthropicMessagesProvider"/>
</ThingTemplates>
```

The abstract base is intentionally **not** packaged as its own `ThingPackage`; only concrete subclasses are.

**Shape declaration rule:** the abstract base carries `@ThingworxImplementedShapeDefinitions(LLMAPIProviderShape)`, **and each concrete subclass repeats the declaration**. The metadata scanner is not guaranteed to propagate shape annotations from an abstract base to the concrete ThingTemplate across ThingWorx versions; one annotation per class removes that risk. This is a normative rule, not an optional hardening.

### 4.2 `AgentSettings.llmApiProviderRef` aspect

```
baseType:  THINGNAME
aspect:    thingShape:LLMAPIProviderShape
```

Composer filters the dropdown to any Thing whose ThingTemplate implements `LLMAPIProviderShape`. The dropdown filters by shape **only**; it cannot enforce `enabled == true`. An admin who selects a disabled Provider succeeds at configuration time; the resolver (§5.6) then surfaces `LLM_API_PROVIDER_DISABLED` at first chat. The runtime listing service (§5.10) does apply the enabled filter.

## 5. Data model

### 5.1 Class hierarchy

```
com.thingworx.things.Thing                       (platform base)
    │
    └─ com.thingworx.things.agent.llm.providers.LLMAPIProviderThing   (abstract)
          │     • @ThingworxImplementedShapeDefinitions(LLMAPIProviderShape)
          │     • @ThingworxConfigurationTableDefinitions: LLMAPIProviderRateControl
          │     • abstract: getApiShapeId, getEffectiveModelLabel,
          │         resolveMaxOutputTokens, resolveReasoningEffort,
          │         providerCodeDefaultMaxOutputTokens, buildDelegateClient
          │     • getLlmClient(): ProviderLlmClientBridge around the delegate
          │     • thread-safe (§5.8)
          │
          ├─ providers.openai.OpenAIChatV4Provider           (built-in)
          ├─ providers.openai.OpenAIChatV5Provider           (built-in)
          ├─ providers.azureopenai.AzureOpenAIChatV4Provider (built-in)
          ├─ providers.azureopenai.AzureOpenAIChatV5Provider (built-in)
          ├─ providers.anthropic.AnthropicMessagesProvider   (built-in)
          └─ (extension classes — same pattern, §7)
```

Each concrete subclass declares its own single-row settings table (§5.3).

### 5.2 `LLMAPIProviderThing` — abstract Java base

Abridged shape:

```java
@ThingworxImplementedShapeDefinitions(shapes = {
    @ThingworxImplementedShapeDefinition(name = "LLMAPIProviderShape")
})
@ThingworxConfigurationTableDefinitions(tables = {
    @ThingworxConfigurationTableDefinition(name = "LLMAPIProviderRateControl", isMultiRow = false /* see rate-control.md */)
})
public abstract class LLMAPIProviderThing extends Thing implements RateGateOwner, ProviderBridgeContext {

    /** Wire request/response shape id. Built-ins use frozen literals (§6.2). */
    public abstract String getApiShapeId();

    /** Exact model/deployment string sent upstream (Azure: deployment; others: model). */
    public abstract String getEffectiveModelLabel();

    /** Positive max-output value; see llm-api-provider-parameters.md §6. */
    public abstract int resolveMaxOutputTokens(LlmChatRequest request);

    public abstract Optional<String> resolveReasoningEffort(LlmChatRequest request);

    public abstract int providerCodeDefaultMaxOutputTokens();

    /** Builds the vendor HTTP client from this template's settings table. */
    protected abstract LlmClient buildDelegateClient();

    /** Lazily built, cached for the life of the Provider; wraps the delegate in ProviderLlmClientBridge. */
    public final LlmClient getLlmClient() { ... }

    // Services: TestConnection, GetRateControlState, ResetRateControlState, TestRateControlAdmission
}
```

`initializeThing` creates the Provider's rate gate and rebuilds the bound client. Configuration errors (for example a blank required settings field) throw `IllegalStateException` from `buildDelegateClient()`.

Provider services:

| Service | Result | Purpose |
| --- | --- | --- |
| `TestConnection` | BOOLEAN | Probes upstream through `getLlmClient().healthCheck()`; see §10.3. Configuration errors before a client is built return `false` without the probe log line. |
| `GetRateControlState` | INFOTABLE | Rate gate state (see `rate-control.md`). |
| `ResetRateControlState` | INFOTABLE | Clears in-memory buckets and counters when no upstream call is in flight. |
| `TestRateControlAdmission` | INFOTABLE | Evaluates whether the gate would admit a synthetic reservation. |

### 5.3 Configuration tables (per Provider Thing)

Each Provider Thing carries:

- its template's **settings table** (native field names, `PASSWORD` for secrets) — normative in **`llm-api-provider-parameters.md`** §5;
- the inherited **`LLMAPIProviderRateControl`** table — normative in **`rate-control.md`**.

### 5.4 Request path through the Provider bridge

`getLlmClient()` returns a `ProviderLlmClientBridge` around the template's delegate client. For each `chat(LlmChatRequest)` the bridge:

1. computes the Provider telemetry identity (§10.2) for the effective model;
2. resolves max-output tokens and reasoning effort once through the owning Provider (`llm-api-provider-parameters.md` §7);
3. for probe requests, calls the delegate directly;
4. otherwise, when rate control is not `disabled`, estimates usage and runs the delegate call through the Provider's rate gate; when rate control is disabled, calls the delegate directly.

### 5.5 Provider Thing deletion

parler-agent contains **no** delete-management code. `llmApiProviderRef` is a configuration-table field, so whether the platform's dependency scanner refuses deletion of a referenced Provider depends on the platform. Either outcome is safe:

| Platform behavior | Operator-facing result |
| --- | --- |
| Refuses deletion of a Provider while an AgentThing references it | Standard ThingWorx dependency error; operator unhooks dependents and retries. |
| Allows deletion of a referenced Provider | Delete succeeds; the next chat through a dependent AgentThing fails closed with `LLM_API_PROVIDER_NOT_FOUND` at resolve time (§5.6). |

Two Provider Things that share one API key are rate-accounted independently even though the upstream applies one bucket; this is a known limitation.

### 5.6 Provider resolver (Java helper)

Every code path that calls an upstream LLM first resolves `AgentSettings.llmApiProviderRef` to a live `LLMAPIProviderThing` through **`LLMAPIProviderResolver.resolve(String)`**:

```java
public static LLMAPIProviderThing resolve(String llmApiProviderRef) throws LlmProviderResolveException {
    if (llmApiProviderRef == null || llmApiProviderRef.isBlank())  → NO_LLM_API_PROVIDER_CONFIGURED
    Object rent = PlatformAccess.findProgrammatic(ref.trim(), ThingworxRelationshipTypes.Thing);
    if (!(rent instanceof Thing))                                  → LLM_API_PROVIDER_NOT_FOUND
    if (!(thing instanceof LLMAPIProviderThing))                   → LLM_API_PROVIDER_WRONG_TEMPLATE
    if (!provider.isEnabled())                                     → LLM_API_PROVIDER_DISABLED
    return provider;
}
```

Notes:

- **Not a ThingWorx service.** It is a plain Java helper, not visible in Composer and not callable via REST. Resolve failures surface to the user through the calling entry points (`AgentThing.Chat` etc.).
- **Four typed error codes** (`LlmProviderResolveErrorCode`: `NO_LLM_API_PROVIDER_CONFIGURED`, `LLM_API_PROVIDER_NOT_FOUND`, `LLM_API_PROVIDER_WRONG_TEMPLATE`, `LLM_API_PROVIDER_DISABLED`) drive both telemetry and user-facing messages.
- **The runtime listing service shares the enabled predicate** (§5.10). The Composer dropdown filters by shape only (§4.2); the resolver is the final guard.
- **No long-lived cache.** The resolver runs once per user turn (at `Chat` / `ChatAsync` / `ParlerStreamToRemoteThing` entry, via `AgentBaseThing.resolveLlmClientForTurn()`), and the resolved client is reused for every LLM call in that turn's `AgentLoop`. Edits to the Provider configuration or to the AgentThing's ref take effect on the **next** user turn.

### 5.7 `LlmClient` interface and `LlmChatRequest`

```java
public interface LlmClient {
    LlmResponse chat(LlmChatRequest request) throws Exception;
    LlmUsageWireIds usageWireIds();                       // §10.2 identity
    default LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) { ... }
    default OptionalLong contextPlanningInputCapChars(long requestedMaxOutputTokens, String modelOverride) { ... }
    boolean healthCheck();
}
```

`LlmChatRequest` carries per-call agent behavior: `messages`, `tools`, `temperature`, `requestedMaxOutputTokens`, `enableCacheControl`, `reasoningEffort`, optional `modelOverride`, `usageWireIdsOverride`, `providerResolvedOptions`, `probeMode`, `toolChoiceNone`, and a call context. The Provider owns model name, endpoint, and auth.

**Async paths use worker threads, not a separate async method.** There is no `chatAsync` and no `CompletableFuture` on the client. `ChatAsync` and `ParlerStreamToRemoteThing` call the synchronous `chat(...)` from their own worker threads. The Provider abstraction owns the **upstream call boundary**, not threading.

### 5.7.1 `reasoningEffort` resolution

Reasoning effort and max-output resolution are owned by the Provider; the order (request → Provider table → Provider code default → omit) is normative in **`llm-api-provider-parameters.md`** §6. Chat v5 Providers send `reasoning_effort`; v4 Providers never send a reasoning parameter. Anthropic extended thinking is configured separately through `AnthropicMessagesSettings.thinkingBudgetTokens` (parameters §5.7).

### 5.7.2 `LlmChatRequest` evolution policy

`LlmChatRequest` is **additive-only**:

- New fields may be added between releases.
- Existing fields MUST NOT be reordered, renamed, or removed.
- Default values for new fields must produce behavior identical to the previous release when the caller leaves the field at its default.

**Provider-mode fields:** optional **`usageWireIdsOverride`** supplies §10.2 identity for `LLM_HTTP_FAILURE` / `LLM_RATE_LIMIT` / `LLM_PROVIDER_TEST_CONNECTION` when the HTTP delegate is wrapped by **`ProviderLlmClientBridge`**. The bridge resolves max-output and reasoning once per call and passes a **`providerResolvedOptions`** request to the delegate (see **`llm-api-provider-parameters.md`** §7). Chat Completions v4 vs v5 wire field choice is compile-time per Provider delegate, not a model-string heuristic.

### 5.7.3 Who assembles `LlmChatRequest`

`LlmChatRequest` is assembled by **AgentThing / AgentLoop**, not by the Provider. Per user turn:

0. AgentThing first requires a ready `AgentSettings.artifactCacheFileRepository`. Blank or
   unavailable configuration returns the stable Artifact Cache Service/event/wire error before
   Provider resolution, so the Provider and LLM receive no call for that rejected attempt.
1. AgentThing's entry method (`Chat` / `ChatAsync` / `ParlerStreamToRemoteThing`) resolves `llmApiProviderRef` (§5.6) and obtains the Provider's bridged client.
2. AgentThing reads its own configuration: `temperature` from `AgentSettings.temperature`; `requestedMaxOutputTokens` from `AgentSettings.maxTokens` (default `-1`, meaning "use the Provider default").
3. `AgentLoop` constructs each round's `LlmChatRequest` from the current `messages` and active `tools`, then alone copies `enableCacheControl=true` before any tool-policy copy. The shared `forAgentRound` factory, probes, playbooks, and checkpoint summaries remain false.
4. The bridged client's `chat(request)` is called (§5.4).
5. The Anthropic serializer consumes `enableCacheControl` directly: true requests use stable user block arrays plus current/previous history markers; false or nullable requests retain ordinary user strings and stable markers only.

Internal callers without an AgentLoop context construct their own `LlmChatRequest` directly; `enableCacheControl` remains false unless that caller has its own request-kind contract.

### 5.7.4 `modelOverride`

Optional **`modelOverride`** on `LlmChatRequest` remains for **non-Agent** callers only. **`AgentThing` / `AgentLoop`** pass **`null`**: model/deployment is owned by the Provider Thing's settings table. **New callers must not introduce further uses of `modelOverride`.**

When the call goes through a **Provider Thing**, **`ProviderLlmClientBridge`** supplies **`usageWireIdsOverride`** with **`apiShapeId`** from the concrete Provider subclass, and the Chat Completions token field follows the **ThingTemplate** (`OpenAIChatV4Provider` → `max_tokens`, `OpenAIChatV5Provider` → `max_completion_tokens`, etc.), so opaque Azure deployment names do not silently pick the wrong wire shape.

### 5.8 Thread-safety contract

Multiple AgentThings can share one Provider Thing, and the Provider exposes one `LlmClient` instance (lazily built, reused for the lifetime of the Provider). To allow real concurrency across the sharing AgentThings:

- **Implementations of `LlmClient.chat()` MUST be thread-safe.** Built-in delegates use a thread-safe, connection-pooled HTTP client.
- **Per-call mutable state** (request body builders, response parsers, header maps) MUST be local to `chat()`. No shared instance state may be mutated by `chat()` after construction.
- **HTTP client reuse** across `chat()` calls is allowed and encouraged for connection pooling.
- **Extension Providers**: a subclass using a non-thread-safe HTTP library is responsible for its own synchronization. The platform does not serialize calls.

The Provider does not serialize calls with a provider-wide lock. Concurrency limits are enforced by the Provider rate gate (`rate-control.md`).

### 5.9 `AgentThing` configuration

LLM endpoint, credentials, and model/deployment are **not** configured on the Agent. `AgentBaseThing` exposes:

| Field (`AgentSettings`) | Base type | Aspects | Description |
| --- | --- | --- | --- |
| `llmApiProviderRef` | `THINGNAME` | `thingShape:LLMAPIProviderShape` | **Required for Chat/Parler/TestConnection:** references a Provider Thing. Composer filters the dropdown to Things whose ThingTemplate implements `LLMAPIProviderShape` (§4.2). |

#### AgentThing `initializeThing` semantics — does NOT resolve the Provider

AgentThing `initializeThing` **only reads the string value** of `llmApiProviderRef`; it does not call the resolver, does not look up the Provider Thing, and does not fail when the value is blank or unresolvable:

- Empty `llmApiProviderRef` at init time → `logger.error` line; AgentThing continues to start. Import order during entity bundle imports is platform-dependent, and AgentThing must remain usable for diagnostics / listing services even when its Provider has not yet been imported.
- Each user turn — `Chat`, `ChatAsync`, `ParlerStreamToRemoteThing` — resolves the ref at entry, **before** `AgentLoop` starts. A resolve failure surfaces as a user-visible error with the typed code from §5.6.
- The resolved client is reused for the rest of that turn; the next turn re-resolves.

Settings that stay on AgentThing (agent behavior, not connection identity):

- `temperature`
- `maxTokens` (the *requested* output-token value; `-1` defers to the Provider default)
- Skills, tools, taxonomy, HITL policy, system prompt template
- Conversation persistence settings

### 5.10 Provider listing service

Two mechanisms support "find the Providers I can choose from":

**Mechanism 1 — Composer field aspect (configuration time).** The `THINGNAME + thingShape:LLMAPIProviderShape` aspect (§4.2) filters the dropdown. This is platform-native; no Parler service is involved.

**Mechanism 2 — `AgentThing.GetAvailableLlmApiProviders()` service (runtime).** Returns an INFOTABLE for any consumer (the Parler widget, custom mashups, diagnostic scripts) that needs the filtered set programmatically. It queries the implementing Things of `LLMAPIProviderShape` as the calling user.

```
AgentThing.GetAvailableLlmApiProviders() : INFOTABLE
```

Returned columns (non-sensitive only — never settings-table contents or `apiKey`):

| Column | Value |
| --- | --- |
| `name` | Provider Thing name. |
| `displayName` | Currently the Provider Thing name. |
| `providerTemplateName` | Concrete ThingTemplate name (matches §10.2). |
| `apiShapeId` | `getApiShapeId()`. |
| `modelName` | `getEffectiveModelLabel()` — `deployment` for Azure, `model` for OpenAI / Anthropic. |
| `contextWindowTokens` | Placeholder, always `0`. |
| `enabled` | Always `true` (see below). |
| `lastHealthy` | Placeholder, always empty. |
| `description` | Provider Thing description. |

**Normative: `GetAvailableLlmApiProviders()` returns only `enabled == true` Provider Things** — the same predicate the resolver (§5.6) applies. The Composer dropdown behaves differently: it filters by shape only and **may include disabled Providers**; selecting one surfaces `LLM_API_PROVIDER_DISABLED` at first chat.

## 6. Built-in Provider ThingTemplates

### 6.1 Catalog

| Template (Java class) | `apiShapeId` | Vendor / Auth | Models (operator fills the settings table) | Reasoning behavior |
| --- | --- | --- | --- | --- |
| `OpenAIChatV4Provider` | `openai-chat-completions-v4` | OpenAI direct; `Authorization: Bearer` | `gpt-4o`, `gpt-4.1`, `gpt-4.1-mini`, `gpt-4o-mini`, etc. — anything using `max_tokens` | Never emits a reasoning parameter |
| `OpenAIChatV5Provider` | `openai-chat-completions-v5` | OpenAI direct; `Authorization: Bearer` | gpt-5.x and o-series — anything using `max_completion_tokens` + `reasoning_effort` | Sends resolved `reasoning_effort` |
| `AzureOpenAIChatV4Provider` | `azure-openai-chat-completions-v4` | Azure; `api-key` header + deployment-scoped URL | Azure deployments of the gpt-4.x family | Never emits a reasoning parameter |
| `AzureOpenAIChatV5Provider` | `azure-openai-chat-completions-v5` | Azure; `api-key` header + deployment-scoped URL | Azure deployments of gpt-5.x / o-series | Sends resolved `reasoning_effort` |
| `AnthropicMessagesProvider` | `anthropic-messages-v1` | Anthropic direct; `x-api-key` + `anthropic-version` headers | All Claude models (Sonnet / Opus / Haiku) — same API shape | Extended thinking via `thinkingBudgetTokens` (parameters §5.7) |

#### 6.1.1 Authoritative Chat Completions wire (Provider Things)

For **OpenAI** and **Azure** Chat Completions Provider Things, the **ThingTemplate** (v4 vs v5 row above) is authoritative for the HTTP request body: v4 Provider delegates always emit **`max_tokens`**; v5 delegates always emit **`max_completion_tokens`**, independent of whether the model id or Azure deployment name contains `gpt-5` / `o*` substrings. This avoids silent 400s when operators use opaque deployment aliases for gpt-5–class models. There is no runtime model-string fallback for Provider Things (see **`llm-api-provider-parameters.md`**).

### 6.2 Built-in `apiShapeId` literals are frozen

The five strings above are **frozen**. Extensions MUST NOT use them. Custom Providers MUST use a reverse-DNS (`com.acme.openai-compatible-v1`) or `x-` (`x-acme-llm-v1`) prefix. This keeps telemetry aggregations meaningful without a central registry; the platform does not enforce uniqueness.

### 6.3 OpenAI-compatible vendors reuse existing templates

Ollama, Together, Groq, and corporate deployments that speak the OpenAI Chat wire format: an admin creates an `OpenAIChatV4Provider` (or v5) Thing with `baseUrl` pointed at the vendor's `…/v1` root and `apiKey` set to the vendor credential. No new ThingTemplate and no Parler code change. See **`llm-api-provider-parameters.md`** §5.10 for choosing a template for a given endpoint.

If the vendor diverges in a way that breaks the wire shape (for example required extra headers or a different response schema), an extension subclass is needed — see §7.

### 6.4 Template split rule

The boundary between templates is **the wire request/response body structure**, not the vendor identity:

- OpenAI Chat v4 vs v5 — **separate** (different parameter names, different response shapes for reasoning models).
- Anthropic Sonnet / Opus / Haiku — **one template** (all speak the Messages API identically).
- Anthropic direct vs a gateway with different auth or envelope (for example SigV4) — **separate**.
- OpenAI direct vs Together / Groq — **same template** if the vendor tolerates the OpenAI wire format end-to-end.

## 7. Custom extension contract

An extension shipping its own Provider ThingTemplate follows the same rules as the built-ins (§4.1):

1. **Provide a Java class** that `extends LLMAPIProviderThing`. **Do not** add `@ThingworxBaseTemplateDefinition`.
2. **Declare `LLMAPIProviderShape` explicitly** on the concrete class with `@ThingworxImplementedShapeDefinitions(shapes = { @ThingworxImplementedShapeDefinition(name = "LLMAPIProviderShape") })` (§4.1 shape declaration rule).
3. **Implement the abstract methods**: `getApiShapeId()`, `getEffectiveModelLabel()`, `resolveMaxOutputTokens(...)`, `resolveReasoningEffort(...)`, `providerCodeDefaultMaxOutputTokens()`, and `buildDelegateClient()`.
4. **Use a unique `apiShapeId`** with a reverse-DNS or `x-` prefix (§6.2).
5. **Ensure thread-safety** of the delegate `LlmClient` (§5.8), or self-synchronize.
6. **Declare a native single-row settings table** on the subclass (pattern in `llm-api-provider-parameters.md`), with `PASSWORD` for secrets. The `LLMAPIProviderRateControl` table is inherited.
7. **Ship a standard ThingWorx extension** containing the Java jar plus a `metadata.xml` that declares its own `ThingPackage` (with `className=` pointing at the new class) and a `ThingTemplate(thingPackage=...)`. Do not use `baseThingTemplate=`.

Installation flow: install the extension; Composer lists the new ThingTemplate; the admin creates Provider Things of that template and references one from `AgentSettings.llmApiProviderRef`. No parler-agent change is required.

## 8. Request path — single entry point

Every agent code path that performs an upstream LLM call resolves `llmApiProviderRef` and calls the Provider's bridged `LlmClient`:

- `AgentThing.Chat` — synchronous chat
- `AgentThing.ChatAsync` — async chat
- `AgentThing.ParlerStreamToRemoteThing` — AlwaysOn stream
- Every `AgentLoop` iteration's LLM call
- Internal LLM calls: conversation checkpoint summaries (`ConversationCheckpointGenerator`) and playbook LLM steps (`PlaybookRunner`)

```
AgentThing.Chat(userPrompt)
  │
  ├─ resolve llmApiProviderRef → LLMAPIProviderThing → getLlmClient()
  │      (fail fast with a typed code if blank or unresolvable)
  │
  ├─ AgentLoop builds messages, selects tools
  │
  └─ AgentLoop loop:
       │
       ├─ client.chat(LlmChatRequest { messages, tools, temperature, requestedMaxOutputTokens, ... })
       │      │
       │      ├─ ProviderLlmClientBridge: wire ids, resolve max-output / reasoning
       │      ├─ Provider rate gate admission (unless rate control is disabled)
       │      └─ delegate LlmClient builds the vendor request, issues HTTP,
       │          parses the response into LlmResponse
       │
       ├─ if response has tool_calls → ToolRegistry → loop
       │
       └─ if final answer → return
```

## 9. Token estimation

Rate admission uses a conservative, provider-neutral character-based estimator (`RateControlTokenEstimator`, see `rate-control.md`). Estimates feed the rate gate only; cost and usage reporting use the actual usage returned in `LlmResponse`.

## 10. Telemetry identity

### 10.1 Code locations

| Concern | Class |
| --- | --- |
| Provider base | `com.thingworx.things.agent.llm.providers.LLMAPIProviderThing` |
| Built-in Providers | `llm.providers.openai.*`, `llm.providers.azureopenai.*`, `llm.providers.anthropic.*` |
| Delegate HTTP clients | `llm.AzureOpenAILlmClient`, `llm.OpenAiChatCompletionsClient`, `llm.AnthropicMessagesLlmClient` |
| Bridge | `llm.ProviderLlmClientBridge` |
| Resolver | `llm.LLMAPIProviderResolver`, `llm.LlmProviderResolveErrorCode` |
| Listing | `tools.LlmApiProviderDirectory` |
| Request value object | `llm.LlmChatRequest` |
| Telemetry identity | `llm.LlmUsageWireIds`, `llm.LlmUsageTelemetry` |

There is no vendor enum and no central client factory; each Provider subclass constructs its own delegate.

### 10.2 Telemetry fields

Every LLM telemetry surface identifies the upstream through four fields (`LlmUsageWireIds`):

| Field | Type | Source |
| --- | --- | --- |
| `providerThingName` | STRING | Provider Thing name (e.g. `"openai-prod-gpt-5.4"`) |
| `providerTemplateName` | STRING | Composer ThingTemplate name (e.g. `"OpenAIChatV5Provider"`) — the same string as the Provider's `ThingTemplate name=` declaration |
| `apiShapeId` | STRING | `provider.getApiShapeId()` |
| `model` | STRING | `getEffectiveModelLabel()` (or the effective `modelOverride` for non-Agent callers) |

There is no vendor `provider` field. If a Java fully qualified class name is ever needed, it would be a separate `providerClassName`; `providerTemplateName` is not repurposed.

Surfaces:

- `llmUsageJson` (per `llm-usage-stream-telemetry.md`) carries the four fields.
- `StreamTokenUsage` aggregate columns on `AgentMessageStream` are unchanged; the JSON column carries the four fields.
- `LLM_HTTP_FAILURE`, `LLM_RATE_LIMIT`, and `LLM_USAGE` log lines carry `providerThingName=`, `providerTemplateName=`, `apiShapeId=`, `model=`.
- `LlmHttpDiagnostics` receives the same identity.

### 10.3 `TestConnection` telemetry is separate

`TestConnection` is a diagnostic probe; its results MUST NOT pollute usage / cost aggregates:

- TestConnection **does not** emit `LLM_USAGE` and **does not** write `llmUsageJson` to `AgentMessageStream`.
- Successful and failed probes emit a dedicated log line:

  ```
  LLM_PROVIDER_TEST_CONNECTION providerThingName=<name> providerTemplateName=<tpl>
    apiShapeId=<id> model=<model> status=ok|error elapsedMs=<n> messages=<n>
    [errorClass=<exception-class> errorMsg=<short>]
  ```

- HTTP-level failures during a probe still emit `LLM_HTTP_FAILURE` and `LLM_RATE_LIMIT` when applicable, because those exist to diagnose any upstream interaction. The accompanying `LLM_PROVIDER_TEST_CONNECTION` line tags the probe as the origin so consumers can filter it out of operational usage analysis.
- Probe requests set `LlmChatRequest.probeMode=true` and bypass the rate gate.

**`LLMAPIProviderThing.TestConnection`** calls **`getLlmClient().healthCheck()`** (via **`ProviderLlmClientBridge`**) and emits **`LLM_PROVIDER_TEST_CONNECTION`** with the §10.2 identity. **`AgentThing.TestConnection`** resolves **`llmApiProviderRef`** and uses the same bridge path when a ref is set.

## 11. Configuration examples

### 11.1 Built-in template (declared in parler-agent's `metadata.xml`)

The full metadata block is shown in §4.1; this is the per-template form:

```xml
<ThingPackage name="OpenAIChatV5Provider"
              description="OpenAI Chat Completions API (max_completion_tokens / reasoning_effort) Provider"
              className="com.thingworx.things.agent.llm.providers.openai.OpenAIChatV5Provider"
              aspect.isCreatable="true">
  <HandlerDefinitions/>
</ThingPackage>

<ThingTemplate name="OpenAIChatV5Provider" thingPackage="OpenAIChatV5Provider"/>
<!-- No baseThingTemplate= here: ThingTemplate.fromXML skips the thingPackage binding
     if both attributes are set, leaving the Java class binding empty. -->
```

The corresponding Java class (abridged):

```java
@ThingworxImplementedShapeDefinitions(shapes = {
    @ThingworxImplementedShapeDefinition(name = "LLMAPIProviderShape")
})
@ThingworxConfigurationTableDefinitions(tables = { /* OpenAIChatV5Settings */ })
public class OpenAIChatV5Provider extends LLMAPIProviderThing {
    @Override public String getApiShapeId() { return "openai-chat-completions-v5"; }
    @Override public String getEffectiveModelLabel() { /* settings.model */ }
    @Override protected LlmClient buildDelegateClient() { /* OpenAiChatCompletionsClient */ }
    // resolveMaxOutputTokens / resolveReasoningEffort / providerCodeDefaultMaxOutputTokens
}
```

### 11.2 Admin creates a Provider Thing in Composer

Equivalent entity XML exported by Composer (fields per `llm-api-provider-parameters.md` §5.5):

```xml
<Thing name="openai-prod-gpt-5.4" thingTemplate="OpenAIChatV5Provider"
       description="Production OpenAI key for gpt-5.4">
  <ConfigurationTables>
    <ConfigurationTable name="OpenAIChatV5Settings">
      <DataEntry>
        <FieldDefinition name="baseUrl" value="https://api.openai.com/v1"/>
        <FieldDefinition name="apiKey" value="sk-...REDACTED..."/>
        <FieldDefinition name="model" value="gpt-5.4"/>
        <FieldDefinition name="organizationId" value="org-acme"/>
        <FieldDefinition name="timeoutMs" value="120000"/>
        <FieldDefinition name="maxCompletionTokens" value="8192"/>
        <FieldDefinition name="reasoningEffort" value="low"/>
      </DataEntry>
    </ConfigurationTable>
  </ConfigurationTables>
</Thing>
```

### 11.3 AgentThing references the Provider

Set `AgentSettings.llmApiProviderRef` on the agent Thing (Composer offers a dropdown filtered by `LLMAPIProviderShape`):

```xml
<Thing name="MyAgentThing" thingTemplate="AIAgent">
  <ConfigurationTables>
    <ConfigurationTable name="AgentSettings">
      <DataEntry>
        <FieldDefinition name="llmApiProviderRef" value="openai-prod-gpt-5.4"/>
      </DataEntry>
    </ConfigurationTable>
  </ConfigurationTables>
</Thing>
```

## 12. Rate control

Rate control lives on the Provider Thing: `LLMAPIProviderRateGate` is instance-scoped on each Provider, configured by the inherited `LLMAPIProviderRateControl` table, and inspected through the Provider rate-control services (§5.2). Because the gate is per Provider, AgentThings that share a Provider share its limits. The normative description is **`rate-control.md`**.

## 13. References

- `docs/agent/llm-api-provider-parameters.md` — per-template settings tables, request-option resolution, runtime construction.
- `docs/agent/rate-control.md` — Provider-level rate gate.
- `docs/agent/AGENT-CONTEXT.md` — agent source of truth (Provider configuration).
- `docs/agent/llm-token-budget.md` — `LLM_HTTP_FAILURE`, `LLM_RATE_LIMIT`, `LLM_USAGE` log primitives.
- `docs/agent/llm-usage-stream-telemetry.md` — usage persistence; field schema per §10.2.
- `parler-agent/metadata.xml` — the `ThingPackage` + `ThingTemplate(thingPackage=...)` declarations and the in-line warning against `baseThingTemplate=` with a required `thingPackage`.
