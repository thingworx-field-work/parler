# ThingWorx Agent Extension — AI Agent Context Document

> This document is written for AI agents (not humans) to quickly understand the project,
> its architecture, and implementation guidance. Be precise; skip pleasantries.

---

## 1. PROJECT IDENTITY

- **Name**: parler-agent (ThingWorx extension package; monorepo path **`parler-agent/`**)
- **Purpose**: A ThingWorx Extension that brings AI Agent capabilities inside the ThingWorx platform, enabling JavaScript developers to interact with LLMs through standard ThingWorx Services and Events.
- **Language**: Java 21
- **Build**: Gradle + ThingWorx Gradle plugins + Shadow JAR
- **Target**: ThingWorx 9.5+, deployed as Extension ZIP via Composer import

### 1.1 ThingWorx platform reference

**Who this file is for:** contributors and IDE assistants. It is **not** sent to the LLM at runtime. For what the model actually sees, see **./LLM_CONTEXT.md** (system prompt + optional bundled **`llm_tool_routing_guide.txt`**).

**Build vs. reference lookup (stable policy):**

- **Compilation** for this extension stays on the **published ThingWorx Extension SDK / Maven artifacts** (`thingworx-common`, `thingworx-platform-common`, version in `gradle/libs.versions.toml`, or the local `twx-lib` set when configured).
- **Platform behavior** (service invocation, `ValueCollection` + `QUERY`, DataTable query patterns,
  security context, FileRepository behavior, private/public Service visibility, etc.): consult the
  **ThingWorx Extension SDK** (its Javadoc and published API surface) and the **public ThingWorx
  documentation** (PTC Help Center) for the ThingWorx version in
  `parler-agent/gradle/libs.versions.toml`. Do this before proposing a Parler shim, test-only
  Service, REST wrapper, or live probe. Parler local tests establish its adapter behavior; live
  checks are reserved for deployed configuration, permissions, providers, real data, and
  representative end-to-end wiring.

---

## 2. ARCHITECTURE OVERVIEW

### 2.1 The Bridge Pattern

This extension follows the same bidirectional bridge pattern as a ThingWorx MQTT Extension:

```
MQTT Extension:                          Agent Extension:
  JS Developer                             JS Developer
    │ calls Publish Service                  │ calls Chat Service
    ▼                                        ▼
  MQTTThing ──► MQTT Broker               AgentThing ──► LLM API
    │                                        │
    │ ◄── incoming message                   │ ◄── tool_call from LLM
    ▼                                        ▼
  MQTTSubscriptionEvent                    Tool execution (internal Java API)
    │                                        │
    ▼                                        │ ──► result back to LLM
  JS Event Handler                           │ ◄── final response
                                             ▼
                                           AgentResponseEvent
                                             │
                                             ▼
                                           JS Event Handler
```

Key difference: The Agent Extension implements an **agentic loop** internally — the LLM may request multiple tool calls before producing a final response. This loop is fully managed by the extension.

**Prompt assembly (v1):** Turn messages are built in **`AgentThing.buildLlmTurnContext`**. A deployment-stable prefix (optional repository **`/taxonomies/type-taxonomy.md`** Markdown, **GenericThing** ThingTemplate names, **`GetAlertPrompt`**, the unified skill/playbook workflow catalog, **`ParlerTimeAnchor.STABLE_TIME_GUIDANCE`**, and the final-answer evidence rule) is folded into the **leading** system row by **`LeadingStablePromptComposer`**. Application taxonomy for tools and key resolution is **`/taxonomies/identity-types.json`**. The snapshot also holds **`skillRegistry`** metadata and drives slash-body loading. Slash bodies, host scope, data-only task evidence, conditional retrieval-coverage guidance, and the one always-present time-values row are framed before planning with **`ParlerSuffixFraming`** and stable-partitioned into the terminal suffix. Anthropic carries them as ordered user-content blocks; OpenAI/Azure retains `system` authority at the terminal position. Normative detail: **`./system-prompt-cache.md`**, **`./skill-management.md`**, **`./LLM_CONTEXT.md`**.

### 2.2 Thing Templates

| Template | Class | Purpose |
|----------|-------|---------|
| `AIAgent` | `AgentThing` | Self-contained agent with own LLM connection. Exposes Chat/ChatAsync/ParlerStreamToRemoteThing, TestConnection, ClearConversation. |
| `ParlerGateway` | `ParlerGateway` | Transient **Gateway** for AlwaysOn §2.2; **SubmitUserPrompt** validates **`AgentThreadDataTable`**, then **`AIAgent.ParlerStreamToRemoteThing`**. |

### 2.3 Services Exposed to JS Developers

**`AIAgent` (`AgentThing`):**

| Service | Mode | Description |
|---------|------|-------------|
| `Chat(message, systemPrompt?, conversationId?, hostContext?)` | Sync | Send a non-null, non-blank message, run agent loop, return result. Conversation ownership is checked before the blank-message precondition; blank input fails before Artifact Cache admission or turn work. Requires ready `AgentSettings.artifactCacheFileRepository`; admission failure throws the stable cache code/message before Provider/LLM/history. Optional **`hostContext`** (UTF-8 **`key + context`** JSON): same **`HostContextUplink`** validation as **`ParlerStreamToRemoteThing`**; registered keys render repository templates, unregistered parseable keys render a **generic fenced-JSON fallback** (`UNREGISTERED_GENERIC_FALLBACK`), both as ephemeral system prompt fragments (fail-open on hard rejects). **No** server-side auto-binding into tool args (`requiredTools`, document-scope injection, etc.) for generic fallback. |
| `ChatAsync(message, systemPrompt?, conversationId?, hostContext?)` | Async | Requires a non-null, non-blank message after conversation-id/ownership checks and before queue/thread creation. Returns immediately, fires **`AgentResponseEvent`**. Cache rejection/fatal failure uses `status=ERROR`, safe `response`, optional `errorCode`, and no successful assistant row. Optional **`hostContext`** — same semantics as **`Chat`**. |
| `ParlerStreamToRemoteThing(message, systemPrompt?, remoteConversationThingName, userTimezone?, hostContext?)` | Async | Requires a non-null, non-blank message (the existing path trims it before use). **AlwaysOn §2.1:** pushes Parler **wire JSON** through **`ReceiveMessage`** on the bound **ParlerGateway** (or conversation Thing) named `remoteConversationThingName` (must be connected). **`AgentThreadDataTable`** ownership for that name is enforced (same as **`ParlerGateway.SubmitUserPrompt`**). Optional **`hostContext`** = UTF-8 **`key + context`** JSON per **`CONTRACTS/API_CONTRACT.md`**; **`HostContextUplink`** validates / fail-open per **[`host-context.md`](../architecture/host-context.md)** (**`rejectDetail`** + structured **`RejectReason`**; **`ParlerStreamToRemoteThing`** logs **`hostScopeField=` / `hostScopeCode=` / …** when present). Registered-template **`ACCEPTED`** and **`UNREGISTERED_GENERIC_FALLBACK`** turns insert rendered host-context prompt as ephemeral system content for the LLM; generic fallback does **not** trigger registered-template server-side side effects. **HITL** pending snapshots: **`ParlerHitlPendingSnapshotHelper`** copies the active list then strips ephemerals via **`EphemeralSystemStripHelper`** using **`ParlerEphemeralSystemIndices`** before **`PendingApprovalRecord`**. Returns **`request_id`** immediately. **§2.2:** prefer **`SubmitUserPrompt`** on **`ParlerGateway`**. See **parler** `../architecture/agent-alwayson.md` / **`./AGENT-ALWAYSON-TWX.md`**. |
| `ClearConversation(conversationId)` | Sync | Clear conversation history |
| `TestConnection()` | Sync | Verify LLM endpoint is reachable |

**`ParlerGateway`:**

| Service | Mode | Description |
|---------|------|-------------|
| `SubmitUserPrompt(message, agentThingName, systemPrompt?, userTimezone?, hostContext?)` | Async | **AlwaysOn §2.2:** **`AgentThreadDataTable`** row for **`getName()`** must exist and match caller **`username`**; requires **`agentThingName`** template **derived from** **`AIAgent`**, then **`ParlerStreamToRemoteThing`** on that agent with **`remoteConversationThingName`** = this Thing’s name. Optional **`hostContext`** (**`key + context`**) — same **`HostContextUplink`** validation as **`ParlerStreamToRemoteThing`**. Returns **`request_id`** immediately. |
| `GetConversationHistoryJson(maxItems?)` | Sync | **AlwaysOn / parler-ui:** same **`AgentThreadDataTable`** ownership as **`SubmitUserPrompt`**; **`QueryStreamData`** on **`AgentMessageStream`** by **`source`** (optional **`maxItems`**, default 500 cap); returns **`result`** STRING = **`ai-parler-history-v1`** JSON (**one assistant per user turn**: final assistant **`content`**, **`charts`** from numeric-history tool rows via **`ParlerChartWireSupport`**, optional sanitized **`llmUsage`** from the final assistant Stream row’s **`llmUsageJson`**, no raw tool dumps). Edge: **`stringFromInvokeResult`** + **`hydrateHistoryFromJsonString`** (parler **`../ui/load-history.md`**). |
| `GetConnectionInfo(agentThingName, widgetPackageVersion?)` | Sync | **Connection version handshake:** same row ownership as **`SubmitUserPrompt`**; **`agentThingName`** **MUST** equal the thread row’s **`agentName`**; returns **`result`** STRING = **`parler.connection-info.v1`** JSON (Composer **`artifactVersion`** line + optional **`implementationVersion`**, **`serverTime`**). Optional **`widgetPackageVersion`** is log-only (**`PARLER_CONNECTION_INFO`**, sanitized). Edge: **`stringFromInvokeResult`** + **`buildGetConnectionInfoParams`**. **`CONTRACTS/API_CONTRACT.md`** § **`GetConnectionInfo`**. |

### 2.4 Events

| Event | DataShape | Fired When |
|-------|-----------|------------|
| `AgentResponseEvent` | `AgentResponseEventData` | Agent completes (async mode) |
| `AgentToolCallEvent` | `AgentToolCallEventData` | Declared for tool-call observability; the extension does not fire it |

---

## 3. THE AGENT LOOP

Core file: `src/main/java/com/thingworx/things/agent/AgentLoop.java`

```
Input: messages[] + tools[]
Loop (max N iterations, timeout T):
  1. LlmClient.chat(messages, tools) → LlmResponse
  2. If response has no tool_calls → return response.content (DONE)
  3. For each tool_call in response:
     a. ToolRegistry.executeTool(toolCall) → result string
     b. Append tool result message to messages[]
  4. Go to 1
Output: AgentResult { content, status, iterations, token counts }
```

Safety controls:
- `maxIterations` (default 10): Prevents runaway loops
- `agentTimeout` (default 3600s): Total wall-clock timeout
- Error in tool execution: Caught and returned as error JSON to LLM (loop continues)

---

## 4. LLM API SUPPORT

The extension must support 4 API styles. All share the OpenAI function-calling schema for tools.

### 4.1 OpenAI Chat Completions API

```
POST https://api.openai.com/v1/chat/completions
Headers: Authorization: Bearer {api_key}
Body: { model, messages[], tools[], temperature, max_tokens or max_completion_tokens }
Response: { choices[0].message.content, choices[0].message.tool_calls[], usage }
```

### 4.2 OpenAI Responses API

```
POST https://api.openai.com/v1/responses
Headers: Authorization: Bearer {api_key}
Body: { model, input (messages or string), tools[], temperature, max_output_tokens }
Response: { output[] (array of items: message, function_call, function_call_output), usage }
```

Key differences from Chat:
- `input` instead of `messages`
- `output` is an array of typed items, not a single message
- Supports built-in tools (web_search, file_search, code_interpreter) — not relevant here
- Response items have types: `message`, `function_call`, `function_call_output`
- The API can manage the tool loop itself (if tools are defined inline), but for our use case we manage the loop externally

### 4.3 Azure OpenAI API

```
POST https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version={version}
Headers: api-key: {api_key}
Body: Same as OpenAI Chat Completions
Response: Same as OpenAI Chat Completions
```

Differences from OpenAI:
- Different URL pattern (resource + deployment in URL, not model in body)
- `api-key` header instead of `Authorization: Bearer`
- `api-version` query parameter required
- Model field in body is ignored (deployment determines model)

### 4.4 Azure AI Foundry (Model Inference)

```
POST https://{resource}.services.ai.azure.com/models/chat/completions
Headers: Authorization: Bearer {api_key}  OR  api-key: {api_key}
Body: Same as OpenAI Chat Completions (with model field)
Response: Same as OpenAI Chat Completions
```

**IMPORTANT**: Azure AI Inference SDK (`azure-ai-inference`) is deprecated as of 2026, retiring May 30, 2026. Azure Foundry is effectively OpenAI Chat API with a different base URL and auth; our Direct HTTP client can target it without a separate SDK.

### 4.5 Implementation Strategy

The extension uses **Direct HTTP + Jackson** only:

- **Apache HttpClient** and **Jackson** (ThingWorx runtime, `compileOnly`) — no extra dependencies, no shadow JAR relocations
- One HTTP client per provider: URL and auth vary (OpenAI Chat / Azure OpenAI / Azure Foundry / OpenAI Responses); request/response JSON is serialized and parsed manually
- Tool-call parsing and retry/error handling are implemented in the extension

---

## 5. TOOL SYSTEM

Custom tools (developer-defined Services on AgentThing) are described in **./CUSTOMIZED-TOOLS.md**.

### 5.1 Design Philosophy (from mcp-twx)

The `mcp-twx` project (Rust MCP Server for ThingWorx) demonstrated that ThingWorx's hundreds of APIs can be consolidated into a **small set of meta-tools**. This consolidation is critical: dumping 100+ tools into the LLM context destroys reasoning quality.

### Built-in tool registry (this extension) — status

| Tool | Purpose | Status |
|------|---------|--------|
| `invoke_service` | Generic service call: for **Thing** workflows prefer **`discover_thing_members`** / **`describe_entity_schema`** (and skills / extended tools for app-specific paths). After **`discover_services`** / **`get_service_definition`** only when those names are **model-facing** (**`advertiseLegacyServiceDiscoveryTools=true`**) or for **replay / executor** follow-ups; not for listing entities by type (use **`list_entities_by_type`**). Success bodies **> 64 kB** rejected with **`INVOKE_SERVICE_RESULT_TOO_LARGE`**; tabular **`INFOTABLE*`** / **`cacheId`** results exempt; tool description steers away from platform full-metadata dump services | **Implemented** (`InvokeServiceExecutor`) |
| `fetch_cached_result` | Page rows from large-result cache (`invoke_service`, **query_entities**, **list_entities_by_type**) | **Implemented** |
| `discover_services` | List instance services (paging, name prefix). **Thing** paths delegate to **`discover_thing_members`**; **non-Thing** targets remain valid toward **`get_service_definition`** / **`invoke_service`** when reached via **executor** or when **`advertiseLegacyServiceDiscoveryTools`** restores the legacy pair to the **merged LLM** list. **Default:** **executor-only** for LLM merge (omitted from merged **`ToolDefinition`** list). | **Implemented** (`MetadataDiscoveryExecutor`) |
| `get_service_definition` | Parameter + result metadata for one service (Thing paths share **`discover_thing_members`** engine). **Default:** **executor-only** for LLM merge unless **`advertiseLegacyServiceDiscoveryTools`** is **`true`**. | **Implemented** |
| `discover_properties` | Property list on a **Thing instance** only — input **`thingName`** (prefers `GetPropertyDefinitions`, fallback `getInstancePropertyDefinitions`); not-found → **`IDENTITY_RESOLUTION_REQUIRED`**; delegates to **`discover_thing_members`**. **Always** **executor-only** for LLM merge (never re-advertised). | **Implemented** |
| `discover_thing_members` | **Preferred** concrete **Thing** member discovery (properties / services / events / subscriptions facets); visibility-aware | **Implemented** (`DiscoverThingMembersExecutor`) |
| `get_property_values` | Batch read current Thing property values | **Implemented** (`PropertyToolsExecutor`) |
| `query_property_history` | Property value history (numeric NUMBER/INTEGER/LONG vs other logged types); optional aggregates on numeric branch; after the Thing gate, absent property names return **`PROPERTY_NOT_FOUND`** (no history query) and definition-read failures fail closed | **Implemented** |
| `build_chart_from_tabular_result` | Build **ChartBlock** from last tabular tool result or a **`cacheId`** (`line` / `bar` / `scatter`); Parler chart wire | **Implemented** (`BuildChartFromTabularResultExecutor`, **parler** **`../architecture/flexible-chart-solution.md`**) |
| `declare_chart_group` | Declare the one chart group of a request (title, 2–6 named slots, layout hint, optional `sharedCategoryDimension` for shared category colours — C3b-2a) before building its members with `build_chart_from_tabular_result` + `groupMemberKey`; downlinks `chart_group` manifest revisions and persists the final manifests in the final assistant row's `chartGroupsJson` (chart-enhancement design §8.5, C3b-1) | **Implemented** (`DeclareChartGroupExecutor`, `ChartGroupState`, `ChartGroupTurnHooks`) |
| `build_history_overlay_chart` | Unified **2–6** series history overlay (`absolute_time` / `elapsed_time` / `normalized_time` X); replaces retired PoP / multi-series built-ins | **Implemented** (`BuildHistoryOverlayChartExecutor`, **`./history-overlay-chart.md`**, **`CONTRACTS/CHART_CONTRACT.md` §3.0e**) |
| Cached-table column roles (CM-0/CM-1/CM-2) | Cache writers MAY declare optional `timeColumn` / `valueColumn` roles on the conversation-scoped runtime `SourceDescriptor` (validated by `ColumnRoles`; kept on same-artifact rebuilds, never inherited by derived tables). The numeric-history writer (`NumericHistoryCacheWriter`, used by `build_history_overlay_chart` and numeric `query_property_history`) declares them and returns `StoredSeriesCache`, which producers copy into `columns[]` / `timeColumn` / `valueColumn`. `analyze_cached_result` column-projection misses keep `status:"ERROR"` / `reason:"ARGUMENT_MISSING"` and add `rejectedParameter`, `parameterSource`, `side` (relationship), `cacheId`, a bounded `schema` (`columnCount`, ≤32 exact column names / 2,000 chars, `truncated`) and `columnSuggestion` only from a unique case-insensitive match or a validated declared role (shared-parameter gate first). No G18 action, no server retry, no handle guessing. RELATIONSHIP envelopes record both operands in `sourceCacheIds[]` (left, right, each once; one id when both sides read the same cache). CM-4: the same descriptor may carry a writer-declared `subjectThingName` / `subjectPropertyName` (declared by the numeric-history writer from what it knows at store time; kept on same-artifact rebuilds, dropped on derived tables), and analyze SUCCESS / NO_FINDING / INSUFFICIENT_EVIDENCE results add `sources[]` — one row per operand with `role`, `cacheId`, the columns actually projected, and `thingName` / `propertyName` when the cache declared them. Cross-asset pairs are never rejected; this is visibility, not selection. | **Implemented** (`source/ColumnRoles`, `tools/NumericHistoryCacheWriter`, `cache/UnknownProjectedColumnException`, `tools/AnalyzeProjectionDiagnostics`, **`./nearterm/cache-missing-improvement.md`**) |
| `get_entity` | Full combined schema metadata JSON (`properties` / `services` / `events` with definitions) for **ThingTemplate**, **ThingShape**, or **DataShape** only — **Thing** hard-rejected (`UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY`). **Prefer `describe_entity_schema` facets** for normal reads; **`get_entity`** is the Tier B / replay escape hatch. **Option B (shipped):** omitted from the merged LLM **`ToolDefinition`** list — registered via **`ToolRegistry.registerExecutorOnly`** so **`executeTool("get_entity", …)`** still serves persisted replay. | **Implemented** (`GetEntityExecutor`; stamped **`parler.entity.metadata.v1`**; Tier B → **`parler.entity.metadata.summary.v1`** — **`./context-compaction.md`** §16) |
| `describe_entity_schema` | Facet-bounded schema reads (summary, paginated property/service/event lists, **DataShape** fields, singular **public** service detail) for **ThingTemplate** / **ThingShape** / **DataShape**; **`scope=local`** requires non-null **`getInstanceShape()`** (otherwise **`DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE`**); **effective** **`getEffective*Definitions()`** results unwrap **ThingShapeDefinition** collections; **throws** during local or effective template/shape **list** member resolution also map to **`DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE`**; list **`offset`** clamped so **`returned` ≥ 0**; visibility-aware **`EntityUtilities.findEntity`**; **no** **`parler.entity.metadata.v1`** stamp | **Implemented** (`DescribeEntitySchemaExecutor`; see **`./entity-schema-description.md`**) |
| `query_entities` | List Things for a **ThingTemplate** or **ThingShape**; prefers **QueryImplementingThingsOptimizedWithTotalCount** | **Implemented**; see **./query_capability.md** §8, **./query_entities_design.md** |
| `list_entities_by_type` | List metadata entities by collection type via **EntityServices** `GetEntityList` / `GetEntityListByRegEx` (**Thing** rejected) | **Implemented** (`ListEntitiesByTypeExecutor`); **./query-construction.md** §2.1 |
| `spotlight_search` | Fuzzy metadata search via `SearchFunctions.SpotlightSearchV2` (in-JVM, same as REST) | **Implemented** (`SpotlightSearchExecutor`) |
| `set_property_value` | Write a Thing property | **Parler AlwaysOn + HITL:** enqueues pending approval; after approve, **`SetPropertyValueExecutor`** writes via **`UpdatePropertyValues`** as the approving user (ServiceInvoke + PropertyWrite checked; read-only properties return **`PROPERTY_READ_ONLY`**). **Non-Parler Chat:** returns `status:blocked`, `code:PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT` (no write); non-bypassed **`invoke_service`** and **`hitl: true`** extended tools likewise return `code:APPROVAL_REQUIRES_PARLER_CONTEXT`. See **`./data-operation-solution.md`**, **`./AGENT-ALWAYSON-TWX.md`**. |
| `get_agent_skill` | Load full skill body for registered short id **`<id>`** from **`/skills/<id>/SKILL.md`** on **`configurationRepository`**; returns **metadata-free** Markdown for the LLM | **Implemented** (`GetAgentSkillExecutor`, **`SkillRegistryLoader`**); see **./CUSTOMIZED-SKILLS.md**, **./skill-management.md** |

**Extended tools:** Additional LLM tools are registered from **`/tools/extended_tools.json`** on the same FileRepository (**`configurationRepository`**), not from **`_tool_*`** Service name scanning. See **./CUSTOMIZED-TOOLS.md**, **./configuration-repository.md**.

**Skills:** Repository files only (**`/skills/<id>/SKILL.md`**). Metadata lives in **`PromptContextCacheSnapshot.skillRegistry`**; catalog and bodies use **`SkillRegistryLoader`** via **`/SkillName`** or **`get_agent_skill`** (**./CUSTOMIZED-SKILLS.md**, **./skill-management.md**).

**Spotlight implementation note:** The platform exposes the **SearchFunctions** system **Resource** by name; the concrete Java class lives in the ThingWorx platform libraries (e.g. under `com.thingworx.resources`). The extension resolves `RootEntity` / `IServiceProvider` by name and calls `processAPIServiceRequest("SpotlightSearchV2", …)` — no HTTP.

**`spotlight_search` result shape (minimal for LLM):** Each hit includes **`name`** and **`entityType`** (or equivalent column from the platform). **`description`** is included when present (truncated). **`thingTemplate`** is included when the row supplies it (useful for **Things**). This keeps tokens low while still disambiguating type and template.

**mcp-twx-style reference:** `invoke_service`, `describe_entity_schema`, `discover_thing_members`, `get_entity` (replay / executor-only), list/query/search variants, `get_property_values`, property write — fuzzy (**`spotlight_search`**), Thing-under-template/shape (**`query_entities`**), metadata-by-type (**`list_entities_by_type`**).

**Listing metadata entities by collection type (ThingTemplate, ThingShape, Mashup, …):** use **`list_entities_by_type`** (wraps **EntityServices**). **`invoke_service`** is for discovered/follow-up services, not this catalog path. See **./query-construction.md** §2.1 (which tool for which listing job).

### 5.2 Internal vs External Execution

**mcp-twx (external, REST-based)**:
```
LLM → MCP protocol → mcp-twx process → HTTP REST → ThingWorx → HTTP response → mcp-twx → MCP → LLM
```
- Network round-trip per tool call (~50-200ms)
- AppKey authentication required
- Limited to REST-exposed APIs
- Cannot access internal ThingWorx Java APIs

**Agent Extension (internal, Java API)**:
```
LLM → Agent Extension (in JVM) → PlatformAccess (permission-checked platform entry) → result → LLM
```
- Zero network overhead (<1ms per tool call)
- Inherits current user's SecurityContext, and ThingWorx authorizes every call against it
- In-process Java APIs, but only through entries that apply ThingWorx permissions: tools never use the
  permission-free `*Direct` APIs or getters that accept the System user
  (see **`./data-operation-solution.md`** §1.4)

### 5.3 Key ThingWorx Java APIs for Tool Implementation

```java
// Look up an entity the user or the model named (current user's Visibility)
Thing thing = (Thing) PlatformAccess.findAsUser(thingName, ThingworxRelationshipTypes.Thing);

// Invoke a service for a tool call (current user's ServiceInvoke)
ValueCollection params = new ValueCollection();
params.put("param1", new StringPrimitive("value"));
InfoTable result = PlatformAccess.invokeAsUser(thing, serviceName, params);

// Read current property values (current user's PropertyRead; unreadable ones are left out)
IPrimitiveType value = PropertyValueReads.readCurrentValue(thing, propertyName);

// Write a property: only through set_property_value's approved UpdatePropertyValues call

// List entities (EntityServices Resource, invoked as the current user)
IServiceProvider es = (IServiceProvider) PlatformAccess.findAsUser("EntityServices",
        ThingworxRelationshipTypes.Resource);
InfoTable entities = PlatformAccess.invokeAsUser(es, "GetEntityList", listParams);

// Security context (tool calls inherit the calling user's context)
SecurityContext ctx = ThreadLocalContext.getSecurityContext();

// Transaction management (required for writes)
TransactionFactory.beginTransactionRequired();
try {
    // ... operations ...
    ThreadLocalContext.setTransactionSuccess(true);
} finally {
    TransactionFactory.endTransactionRequired();
}
```

### 5.3.1 ThingWorx calls — permission / visibility flags (implementation audit)

When adding or reviewing wrappers around ThingWorx APIs: if a parameter expresses **whether permission or visibility is included / honored** (e.g. `include*Permission*`, `*Permissions*` — exact names depend on the SDK / service), **always pick the value that keeps the call aligned with the current user `SecurityContext` and the platform’s own enforcement** — for the usual **positive** “include / consider permissions” flag, that is **`true`**. Do **not** disable these switches to see extra rows or avoid checks. If the API uses an **inverted** flag (e.g. `skipPermissionCheck`), choose **`false`** so behavior stays permission-aware. Parler does **not** second-guess platform permission details in product logic; defer to the platform. **Rationale** (invalid-scope UX, DataInsight vs coding assistant, unified user-visible errors): **`../architecture/entity-hierarchy.md`** §5.

### 5.4 Resource System (Knowledge Injection)

Deployment knowledge reaches the LLM through repository files on **`configurationRepository`**, not through a separate retrieval system: taxonomy Markdown (**`/taxonomies/type-taxonomy.md`**) and the skill/playbook catalog are folded into the stable prompt prefix, and skill bodies load on demand via **`/SkillName`** or **`get_agent_skill`** (§2.1, **./configuration-repository.md**, **./skill-management.md**).

### 5.5 Conversation Persistence (LlmSessionStore Helper)

**LlmSessionStore** is a **concrete helper class** (not an interface): it is directly instantiated and implemented using **AgentThreadDataTable** (DataTable) and **AgentMessageStream** (Stream). There is no separate interface or abstract class in the current design.

**Calling convention:** Chat / Agent code invokes the **single instantiated LlmSessionStore helper** (e.g. one shared instance). No indirection via interface.

**API (concrete class):**

| Operation | Method | Description |
|-----------|--------|-------------|
| **Thread** | | |
| Create or get thread | `createOrGetThread(conversationId, username, agentName, title?)` | Ensure a thread row exists; create if missing. |
| Get thread | `getThread(conversationId)` | Return thread metadata or null. |
| Update thread | `updateThread(conversationId, title?, updatedAt?)` | Update thread row. |
| Delete thread | `deleteThread(conversationId)` | Remove thread row. |
| **Messages** | | |
| Append message | `appendMessage(conversationId, ChatMessage)` | Append one message to the thread. |
| Get messages | `getMessagesForThread(conversationId)` | Return `List<ChatMessage>` in order. |
| Clear messages | `clearMessagesForThread(conversationId)` | **Current behavior:** deletes the thread only (no per-message cleanup). |

Full CRUD details, entity layout, and Java examples: **./LLM-PERSISTENCE.md**.

**Turn terminal status:** `AgentMessageData` has no terminal-status column (no `turnStatusJson`, `status` or `metadata` field). How a user stop is recorded on the existing rows and rehydrated by the UI is specified in **`docs/agent/turn-cancellation-control.md`** §11.

### 5.6 LLM call ledger (`AgentLlmCallStream`, topic `context-compaction-adv`)

**Purpose:** Durable per-attempt LLM call records for cost and compaction measurement. This is **not** the message transcript Stream (`AgentMessageStream`) and **not** the legacy per-message `llmUsageJson` telemetry documented in **`llm-usage-stream-telemetry.md`**.

| Entity / class | Role |
|----------------|------|
| **`AgentLlmCallStream`** (Stream Thing) | Value stream; row shape **`AgentLlmCallData`**. |
| **`AgentLlmCallData`** (DataShape) | Fixed envelope: call identity, dispatch/outcome, usage snapshot JSON, context-plan snapshot, cancel observation. |
| **`AgentLlmUsageHelper`** (ThingTemplate; user-created instance) | **`GetLlmUsageReport`** (JSON) and **`ExportLlmUsageCsv`** (CSV text plus FileRepository **`SaveText`**) over the new Stream only. Java: **`AgentLlmUsageHelperThing`**. Parameters: required **`StartTime`** / **`EndTime`** (`DATETIME`, half-open on `callStartedAt`), optional **`conversationId`**, **`agentThing`**, **`model`**; export adds **`FileRepository`** (default **`SystemRepository`**) and **`FileName`** (default placeholder expands to `/LLM-USAGE{YYYYmmddHHMM}.csv` in server default timezone). |

**Runtime wiring:** Built-in HTTP clients route through **`llm/usage/LlmRecordedHttpChat`** so usage is captured before response-body parsing. **`LlmChatRequest`** carries optional non-wire **`LlmCallContext`**; **`AgentLoop`**, checkpoint, and playbook paths attach context before **`chat()`**. **`LlmCallRecorder`** is the single write API; tests use **`LlmCallRecorder.resetForTest()`** with an in-memory sink.

**Helper instance:** The Extension ships the `AgentLlmUsageHelper` template and Java services, but no default helper Thing XML. Create a separately named Thing from that template when reports are needed; configure `Settings.usagePricesJson` on that user-managed instance. **`ExportLlmUsageCsv`** writes the generated CSV to the selected FileRepository via platform **`SaveText`** (`path` and `content` passed through verbatim; no Parler path sanitization).

**Import:** Entities ship in the Extension ZIP under **`Entities/`**; the extension must be imported before live writes succeed. Missing Stream → explicit collector gap in reports (no fallback to message Stream or Application Log).

Normative field semantics and acceptance matrix: **`docs/agent/nearterm/context-compaction-adv.md`** CC-7.

---

## 6. THINGWORX EXTENSION BUILD SYSTEM

### 6.1 Required Components

```
parler-agent/
├── build.gradle              # Gradle build with ThingWorx plugins + Shadow
├── settings.gradle           # rootProject.name
├── metadata.xml              # Extension package, ThingPackages, ThingTemplates
├── gradle/libs.versions.toml # Version catalog
├── src/main/java/            # Java source (ThingWorx-annotated classes)
├── Entities/                 # XML entity definitions (DataShapes, etc.)
└── build/                    # Output: extension ZIP (+ stable alias parler-agent.zip)
```

### 6.2 Build Chain

```
shadowJar → collectJars → updateMetadata → extensionZip
```

1. `shadowJar`: Creates fat JAR with relocated dependencies
2. `collectJars`: Records JAR filename
3. `updateMetadata`: Updates metadata.xml with version, build number, JAR references
4. `extensionZip`: Packages metadata.xml + lib/common/*.jar + Entities/ into ZIP

### 6.3 Extension ZIP Structure

```
parler-agent-0.1.0.0-SNAPSHOT.zip   (versioned; plus stable copy build/parler-agent.zip)
├── metadata.xml
├── lib/common/
│   └── parler-agent-0.1.0.0-SNAPSHOT-all.jar
└── Entities/
    └── DataShapes/
        ├── AgentResponseEventData.xml
        ├── AgentToolCallEventData.xml
        └── (thread/message persistence: AgentThreadData, AgentMessageData, AgentThreadDataTable, AgentMessageStream — see ./LLM-PERSISTENCE.md)
```

### 6.4 Shadow JAR

The extension uses Direct HTTP + Jackson only; HttpClient and Jackson are `compileOnly` (ThingWorx runtime). No additional dependencies are bundled, so **no relocations** are required. If a future implementation adds third-party LLM SDKs, conflicting packages must be relocated.

### 6.5 ThingWorx Annotations Reference

```java
// Configuration tables (appear in Composer Thing configuration)
@ThingworxConfigurationTableDefinitions(tables = {
    @ThingworxConfigurationTableDefinition(name = "...", isMultiRow = false,
        dataShape = @ThingworxDataShapeDefinition(fields = {
            @ThingworxFieldDefinition(name = "...", baseType = "STRING|NUMBER|BOOLEAN|PASSWORD|TEXT|INTEGER",
                aspects = { "defaultValue:...", "selectOptions:..." }, ordinal = 0)
        }))
})

// Services (callable from JS, REST, Mashups)
@ThingworxServiceDefinition(name = "...", description = "...")
@ThingworxServiceResult(name = "result", baseType = "STRING")
public String MyService(
    @ThingworxServiceParameter(name = "param1", baseType = "STRING") String param1
) throws Exception { ... }

// Events (subscribable from Composer)
@ThingworxEventDefinitions(events = {
    @ThingworxEventDefinition(name = "...", description = "...", dataShape = "DataShapeName")
})

// Firing events (requires transaction context)
ThreadLocalContext.setSecurityContext(SecurityContext.createSuperUserContext());
TransactionFactory.beginTransactionRequired();
ValueCollection eventData = new ValueCollection();
eventData.put("field", new StringPrimitive("value"));
EventDefinition def = (EventDefinition) getInstanceEventDefinitions().get("EventName");
fireEvent(def, new DateTime(), eventData);
ThreadLocalContext.setTransactionSuccess(true);
TransactionFactory.endTransactionRequired();
ThreadLocalContext.dispatchQueuedEvents();
ThreadLocalContext.clearSecurityContext();
```

---

## 7. CONFIGURATION TABLES

`AgentBaseThing` (and therefore **`AIAgent`**) exposes a single **`AgentSettings`** configuration table. **LLM endpoint, API keys, and model/deployment** are **not** configured on the Agent — they live on a **Provider Thing** selected by **`AgentSettings.llmApiProviderRef`** (see **`docs/agent/llm-api-provider.md`** §5–6).

### 7.1 AgentSettings

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `llmApiProviderRef` | THINGNAME (`LLMAPIProviderShape`) | — | Provider Thing used for every upstream LLM call (`Chat`, `ChatAsync`, Parler, HITL continuations). |
| `temperature` | NUMBER | `0.1` | Sampling temperature |
| `maxTokens` | INTEGER | `4096` | Max response tokens (requested cap) |
| `maxIterations` | INTEGER | `10` | Agent loop safety limit |
| `agentTimeout` | NUMBER | `3600000` | Total loop timeout (ms) |
| `systemPrompt` | TEXT | (built-in default) | Default system instruction |
| `appendBuiltInToolRoutingGuide` | BOOLEAN | `true` | Append bundled **`llm_tool_routing_guide.txt`** to the system message (see **./LLM_CONTEXT.md**) |
| `enableBuiltInTools` | BOOLEAN | `true` | When `false`, other built-in tools are omitted but **`get_agent_skill`** is still registered |
| `advertiseLegacyServiceDiscoveryTools` | BOOLEAN | `false` | When `true`, **`discover_services`** and **`get_service_definition`** are included in the merged LLM tool list; **`discover_properties`** stays executor-only for merge. See **`docs/agent/legacy-discovery-executor-only.md`**. |
| `allowImplicitInvocation` | BOOLEAN | `false` | Reserved; currently has no effect (implicit skill-body injection is not active) |
| `hitlAuditDebugAll` | BOOLEAN | `false` | When `true`, elevate DEBUG-tier PARLER_HITL lines to WARN for this agent |
| `exportFileRepository` | THINGNAME (`FileRepository`) | — | Optional repository for Parler table CSV export |
| `artifactCacheFileRepository` | THINGNAME (`FileRepository`) | — | **Required production prerequisite.** Dedicated Artifact Cache repository; blank/unavailable rejects Chat/ChatAsync/AlwaysOn/Playbook/HITL before LLM work. Edit/save the AgentThing after correction. |
| `tableCsvExportRowThreshold` | INTEGER | `200` | Row-count threshold for optional CSV export |
| `tableCsvExportMaxChars` | INTEGER | `50000000` | Max **`String.length()`** (UTF-16 code units) for one CSV before **`skipped_limit`** (clamped **1_000_000**..**200_000_000**) |
| `configurationRepository` | THINGNAME (`FileRepository`) | — | Repository for skills, taxonomy Markdown, policies, extended tools |
| `llmContextMaxChars` | INTEGER | `750000` | Model-facing context budget cap (see **`docs/agent/context-compaction.md`**) |

Replay compaction (Tier A / Tier 0 / Tier B) is **always-on** for normal AgentThings; suppress only via JVM diagnostic **`com.thingworx.parler.llmReplayCompaction.disableUnsafe=true`** (see **`LlmReplayCompactionGate`**).

---

## 8. LLM CLIENT IMPLEMENTATION

The extension uses **Direct HTTP + Jackson** only:

- **Apache HttpClient** (`compileOnly`, ThingWorx runtime) for HTTP
- **Jackson** (`compileOnly`, ThingWorx runtime) for JSON request/response
- No extra dependencies, no shadow relocations, minimal extension size
- **Azure:** `AzureOpenAILlmClient` — deployments + `api-version` query param
- **OpenAI-compatible:** `OpenAiChatCompletionsClient` — Bearer auth, same message/tool JSON shape as Azure chat completions
- **Anthropic:** `AnthropicMessagesLlmClient` — system string, `tool_use` / `tool_result` blocks, `input_schema` tools

---

## 9. SIBLING PROJECT: mcp-twx

This is an MCP (Model Context Protocol) Server for ThingWorx, written in Rust. It runs OUTSIDE ThingWorx and communicates via REST API.

### 9.1 What to Reuse from mcp-twx

**Tool definitions**: The tool schemas (names, descriptions, parameter schemas) from mcp-twx can be directly ported. The tools are documented in `mcp-twx/src/tools/`.

Porting status (vs mcp-twx concepts):
- [x] `invoke_service` → Universal service execution
- [x] `fetch_cached_result` → Large INFOTABLE paging (extension-specific)
- [x] `discover_services` / `get_service_definition` / `discover_properties` / `discover_thing_members` → Metadata discovery (extension-specific; Thing paths delegate)
- [x] `get_property_values` / `query_property_history` → Property read & history
- [x] `build_chart_from_tabular_result` → Tabular **ChartBlock** + Parler **wireChart** (not for `query_property_history` numeric auto-chart path; that path auto-charts)
- [x] `set_property_value` → Parler HITL + post-approve write (`SetPropertyValueExecutor`); non-Parler path blocked (`PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT`)
- [x] `get_entity` → Entity metadata retrieval (`GetEntityExecutor`, **`parler.entity.metadata.v1`**)
- [x] `describe_entity_schema` → Facet-bounded schema reads (`DescribeEntitySchemaExecutor`; **`./entity-schema-description.md`**)
- [x] `spotlight_search` → `SearchFunctions.SpotlightSearchV2` (in-process)
- [x] `query_entities` → ThingTemplate / ThingShape + Optimized QIT (+ optional `query` / `offset` / `namePrefix`)
- [x] `list_entities_by_type` → EntityServices GetEntityList* (metadata types; not Thing)

**Entity type system**: `mcp-twx/src/types/entity.rs` defines 33 entity types with their collection names, capabilities, and query patterns. This mapping supports **`query_entities`** and CRUD-style tools.

### 9.2 What NOT to Reuse

- **HTTP client code**: mcp-twx uses REST; the Agent Extension uses direct Java API calls
- **MCP protocol handling**: Not needed (the extension uses ThingWorx Services, not MCP)
- **Multi-environment support**: Not needed (the extension runs inside one ThingWorx instance)
- **Cross-environment tools**: Not applicable for an internal extension

---

## 10. IMPLEMENTATION STATUS

- [x] `LlmClient`: `AzureOpenAILlmClient`, `OpenAiChatCompletionsClient`, `AnthropicMessagesLlmClient` (`LlmClientFactory`)
- [x] **invoke_service** + **fetch_cached_result**: `InvokeServiceExecutor` (see **./invoke_service_design.md**)
- [x] **discover_services** / **get_service_definition** / **discover_properties**: `MetadataDiscoveryExecutor` (see **./metadata_discovery.md**)
- [x] **get_property_values** / **query_property_history**: `PropertyToolsExecutor` (see **./property_value.md**)
- [x] **build_chart_from_tabular_result**: tabular **ChartBlock** + Parler **`wireChart`** (`BuildChartFromTabularResultExecutor`, **parler** **../architecture/flexible-chart-solution.md** v1)
- [x] **AgentLoop** + **Chat** / **ChatAsync** end-to-end
- [x] **Extended tools**: `/tools/extended_tools.json` on **`configurationRepository`** (merged with built-ins in `AgentThing.getMergedToolDefinitions`)
- [x] **Conversation persistence (partial)**: in-memory `_conversations` + **AgentThreadDataTable** threads + **AgentMessageStream** append (see **./LLM-PERSISTENCE.md**)
- [x] **AgentMessageStream read / Parler UI history**: **`ParlerGateway.GetConversationHistoryJson`** + **`AgentMessageStreamHistoryExporter`** — `ai-parler-history-v1` JSON for **parler-ui** widget bind bootstrap (see **parler** `../ui/load-history.md`, **./AGENT-ALWAYSON-TWX.md**)
- [x] **`get_entity`**: `GetEntityExecutor` — full metadata stamped **`parler.entity.metadata.v1`** (recovery path after Tier B **`parler.entity.metadata.summary.v1`**)
- [x] **`describe_entity_schema`**: `DescribeEntitySchemaExecutor` — facet-bounded schema tool (v1: no entity-metadata **`$format`** stamp; visibility-aware **`findEntity`**)
- [x] **`set_property_value`**: Parler gate + real write after approval; non-Parler returns `blocked` JSON (`PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT`)
- [x] Built-in tools in §5.1 table are implemented

---

## 11. FILE MAP

```
src/main/java/com/thingworx/things/agent/
├── AgentBaseThing.java          # Base class: config tables, LLM client init, tool registry
├── AgentThing.java              # Main Thing: Chat/ChatAsync/ParlerStreamToRemoteThing, TestConnection, events
├── AgentLoop.java               # Core agentic loop: LLM ↔ tool execution cycle
├── llm/
│   ├── LlmClient.java          # Interface: chat(messages, tools) → LlmResponse
│   ├── LlmClientFactory.java   # Factory: creates correct client from config
│   ├── LlmProvider.java        # Enum: AZURE_OPEN_AI, CHATGPT, ANTHROPIC, GEMINI
│   ├── LlmResponse.java        # Response model: content, tool_calls, usage
│   ├── ChatMessage.java         # Message model: role, content, tool_call_id
│   ├── ToolCall.java            # Tool call model: id, function_name, arguments
│   └── ToolDefinition.java      # Tool schema: name, description, parameters JSON Schema
└── tools/
    ├── ToolExecutor.java        # Functional interface: execute(ToolCall) → String
    ├── ToolRegistry.java        # Registry: maps tool names to definitions + executors
    └── BuiltInTools.java        # Built-in ThingWorx tools (invoke_service, discover_thing_members, describe_entity_schema, …); get_entity executor-only

Entities/DataShapes/
├── AgentResponseEventData.xml   # Event data for async responses
├── AgentToolCallEventData.xml   # Event data for the declared (unfired) tool-call event
└── (persistence: AgentThreadData, AgentMessageData, AgentThreadDataTable, AgentMessageStream — see ./LLM-PERSISTENCE.md)
```
