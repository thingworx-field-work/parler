# Agent / Parler — AlwaysOn integration design

This document specifies how **Parler** (ThingWorx **AI Agent** extension + **AI Parler** widget) uses **ThingWorx AlwaysOn** with a **server-side `RemoteThing` session endpoint**: a **Transient `ParlerGateway`** whose **Thing name = `conversationId`** (**§2.0**). **§0.4** is the normative split for what to read where.

**Transport (widget implementation):** **`parler-ui`** uses the **`@xudesheng/alwayson-js-codec`** package and an in-repo **`WebSocket`** client (the in-repo AlwaysOn client, `parler-ui/lib/alwaysOnParlerClient.js`). Sections that mention **`EdgeThing`** describe the **equivalent** platform handshake and service surface.

**Related code:** **`parler-agent/`** (Java + entity XML), **`parler-ui/`** / **`parler-ui-widget/`**.

**Companion:** [`docs/architecture/agent-parler-payload.md`](./agent-parler-payload.md) (envelope parameters, examples).

---

## 0. Ground rules

### 0.1 Scope — no migration

This spec does **not** cover converting existing `GenericThing` conversation instances, legacy Mashups, or import-order playbooks. Greenfield templates and new Things are assumed.

### 0.2 Connection scope

The server does **not** arbitrate a unique “live” Parler widget per conversation across multiple clients. One AlwaysOn session binds **one** conversation; downstream frames nonetheless carry **explicit** **`conversation_id`** and **`request_id`** (§6.2) so decoding stays **stateless**.

### 0.4 `ParlerGateway` session model vs §2 (**normative split**)

For the **session entity, bind surface, HA / Transient Gateway**, and **where `SubmitUserPrompt` / `ReceiveMessage` live on the platform**, use **only**:

| Topic | Normative source |
|--------|------------------|
| **Gateway name = `conversationId`, bind `names`, `gatewayType`, ephemeral / cluster** | [`parler-gateway-design.md`](../agent/parler-gateway-design.md) |
| **Widget single-name bind, session ownership** | [`parler-gateway-design.md`](../agent/parler-gateway-design.md) (§2, §7) |

**This document (`agent-alwayson.md`)** stays authoritative for **Parler wire JSON**, **payload / envelope** (with **`agent-parler-payload.md`**), **handshake patterns** after bind (**`SynchronizeModelState`**, §4), **downlink decode → UI** (**`ReceiveMessage`**, §6), and **transport fallbacks** (§6.4–§6.5), given the **bound Thing name** and **remote service target** from the two sources above (one bind name; services on **`ParlerGateway`**).

**§2** below: **§2.0** summarizes the session shape; **§2.1–§2.2** show the two uplink paths (**participant C** is the **`ParlerGateway`** named **`conversationId`**). Session/bind/HA detail remains **§0.4** + [`parler-gateway-design.md`](../agent/parler-gateway-design.md).

### 0.5 HITL — `PARLER_HITL` logs and first integration

On **ThingWorx**, **`decision_rejected`** lines for client-style mistakes (**`MISSING_*`**, **`INVALID_DECISION`**, **`GATEWAY_SUBMIT_CONV_MISMATCH`**) are logged at **DEBUG** on **`com.thingworx.things.agent.tools.ParlerHitlAuditLog`** so default **WARN** feeds stay quiet. **Alternative (no logger change):** on the **`AIAgent`** Thing, set configuration **`AgentSettings` → `hitlAuditDebugAll`** to **true** so those reasons are **also emitted at WARN** for that agent (and for **`agent_thing=-`** lines when any agent has the flag on). **After toggling `hitlAuditDebugAll`, restart the AIAgent Thing** so **`initializeThing`** re-registers the flag (save-only may be stale). **Composer / mashup wiring:** either **`hitlAuditDebugAll`** or **DEBUG** on **`ParlerHitlAuditLog`** while integrating **`SubmitApprovalDecision`**. Server summary: [`AGENT-ALWAYSON-TWX.md`](../agent/AGENT-ALWAYSON-TWX.md).

---

## 1. Why AlwaysOn

Mashup **property bindings** as a fake stream do not work (parameter timing, `detail.text` unavailable, slow iterate). The widget owns **WebSocket + Auth + Bind**, **`Client.invokeService`** to start a turn (**§2**), and **inbound remote services** on the bound session Thing for **handshake** and **`ReceiveMessage`**.

---

## 2. Architecture

**Normative split:** session/bind/ownership — **§0.4** and [`parler-gateway-design.md`](../agent/parler-gateway-design.md). **§2.0** summarizes platform + widget behavior; **§2.1–§2.2** show the two uplink paths. **C** is the bound session endpoint: the **`ParlerGateway`** named **`conversationId`**.

### 2.0 Session shape

| Element | Behavior |
|---------|----------|
| **Session Thing** | **Transient `ParlerGateway`** — **Thing name = `conversationId`**. |
| **Bind** | **Single** name **`[conversationId]`**; **`gatewayType`** **`ParlerGateway`**. |
| **Downlink** | **`ReceiveMessage`** on **`conversationId`**. |
| **Inbound (bind / sync)** | Platform → edge: **`GetMetadata`**, **`SynchronizeModelState`**, then business **`ReceiveMessage`** (§4). **`NotifyPropertyUpdate`** optional fallback. Client: **`parler-ui/lib/alwaysOnParlerClient.js`**. |
| **Uplink** | **Default:** **`SubmitUserPrompt`** on **`ParlerGateway`** (§2.2). **Alternate:** **`ParlerStreamToRemoteThing`** on **A** with **`remoteConversationThingName = conversationId`** (§2.1) — only the **invoke locus** changes; **server** enforces **`AgentThreadDataTable`** ownership on **both** paths. |
| **Ownership** | **`AgentThreadDataTable`**: row for **`conversationId`**, **non-blank `username`**, must match caller. |

### 2.1 Alternate uplink — invoke `ParlerStreamToRemoteThing` on A

```mermaid
sequenceDiagram
    participant W as Parler Widget
    participant P as ThingWorx Platform
    participant C as ParlerGateway (conversationId)
    participant A as AIAgent Thing

    W->>P: WS open → Auth (KeyID) → Bind (C)
    P->>W: SynchronizeModelState (primary; optional NotifyPropertyUpdate fallback)
    W->>P: Success responses (see §4)
    W->>P: invokeService ParlerStreamToRemoteThing on A
    Note over W,A: parameters include remoteConversationThingName = C
    A->>P: RemoteThing.callService ReceiveMessage on C
    P->>W: ReceiveMessage (payload per agent-parler-payload.md)
    W->>W: Decode → Parler wire semantics → UI
```

| Part | Role |
|------|------|
| **Widget** | **Auth**, **Bind** **C**; **`invokeService("ParlerStreamToRemoteThing")` on A** with **`remoteConversationThingName` = C** (widget property **`UseSubmitUserPromptOnConversation`** = false); **transport fallback** (§6.4–§6.5). |
| **`ParlerGateway` C** | Bound on the edge; **Remote** **`ReceiveMessage`**. |
| **AIAgent A** | **`ParlerStreamToRemoteThing`** — ownership check, LLM loop + **`ReceiveMessage`** invokes targeting **C**. |

### 2.2 Default uplink — `SubmitUserPrompt` on `ParlerGateway`

```mermaid
sequenceDiagram
    participant W as Parler Widget
    participant P as ThingWorx Platform
    participant C as ParlerGateway (conversationId)
    participant A as AIAgent Thing

    W->>P: WS open → Auth (KeyID) → Bind
    P->>W: SynchronizeModelState (primary; optional NotifyPropertyUpdate fallback)
    W->>P: Success responses (see §4)
    W->>P: SubmitUserPrompt on C
    C->>A: ParlerStreamToRemoteThing(remoteConversationThingName = C)
    A->>W: Remote ReceiveMessage on C (same payload rules)
    W->>W: Decode → Parler wire semantics → UI
```

| Part | Role (§2.2) |
|------|-------------|
| **Widget** | **Invoke** **`SubmitUserPrompt`** on **C**; same **Bind** and **`ReceiveMessage`** handling. |
| **`ParlerGateway` C** | **Local** **`SubmitUserPrompt`**; **remote** **`ReceiveMessage`**. |
| **AIAgent A** | Resolved by **C** from **`agentThingName`**; **`SubmitUserPrompt`** delegates to **`ParlerStreamToRemoteThing`** on **A** with **`remoteConversationThingName`** = **C**. |

---

## 3. Client parameters

| Parameter | Development | Production-oriented behavior |
|-----------|-----|------------------------------|
| **WebSocket URL** | Full `ws://` / `wss://` … `/Thingworx/WS` (or your path). | Same; often derived from page / config. Parse to `host`, `port`, `ssl`, `path`, `endpoint` for `Channel`. |
| **appKey** | Long-lived KeyID paste (skip `GetClientApplicationKey` for speed). | Short-lived KeyID from Mashup → **input binding**. **Auth** failures append a refresh hint; **ConnectAndBind** surfaces **`session.error`** when no turn is active. |
| **conversationId** | **`ParlerGateway`** Thing name (= **`AgentThreadDataTable`** primary key); **single** bind name. | Property binding. |
| **agentThingName** | Passed into **`SubmitUserPrompt`** on **`ParlerGateway`** (default) or **`ParlerStreamToRemoteThing`** on **A** (alternate) — same parameter shape. | **Server** exposes choices (e.g. list); **user selects**. Thing must exist and **derive from `AIAgent`**. |

**Per-turn agent:** the widget passes **`agentThingName`** into **`SubmitUserPrompt`** (**default**) or **`ParlerStreamToRemoteThing`** (**`UseSubmitUserPromptOnConversation`** false); the **server** validates **`AIAgent`** derivation. The server does not prevent different agents across turns of one conversation.

---

## 4. Handshake after bind (platform → edge)

**Parler client:** **`AlwaysOnParlerClient`** (`parler-ui/lib/alwaysOnParlerClient.js`) + **`@xudesheng/alwayson-js-codec`**. ThingWorx issues the **same inbound service names** it issues to any AlwaysOn edge.

**Required inbound services** on the bound **session Thing** (**`conversationId`** / **`ParlerGateway`** name): **`GetMetadata`**, **`SynchronizeModelState`**, and **`ReceiveMessage`** (Parler wire payloads). Omitting **`GetMetadata`** breaks typical platform sequences after **`Bind`**. **`NotifyPropertyUpdate`** is **not** part of this required set (§4.1).

**Readiness for Send (Parler widget / `AlwaysOnParlerClient`):** **WebSocket + Auth + `Bind` OK** is sufficient to enable **Send** (`connectionCallback(true)` after bind). **`GetMetadata`** and **`SynchronizeModelState`** must **succeed when the platform invokes them** — they are **not** an additional “wait for handshake sequence before first Send” gate unless a **custom Mashup** explicitly chooses that policy.

**Implementation note:** an AlwaysOn edge that does not implement a service the platform invokes answers **`NOT_FOUND`** (only **`NotifyPropertyUpdate`** is commonly tolerated when undefined). Custom or codec-based edges must therefore implement **`GetMetadata`**, **`SynchronizeModelState`**, and **`ReceiveMessage`** for the bound name — not only **`SynchronizeModelState`**.

### 4.0 `GetMetadata` (required)

- The platform invokes **`GetMetadata`** on **bound Thing names** (including **`conversationId`**) so service definitions and **`INFOTABLE`** parameter shapes are known before or alongside model sync.
- **Parler:** **`AlwaysOnParlerClient`** responds with success via **`getMetadataSuccessInfoTable(entity)`** (`parler-ui/lib/parlerInfotableJson.js`). Parameter / result tables must declare **`aspects.dataShape`** where the platform validates shapes (see §4.2).
- Treat **`GetMetadata`** as **mandatory** for any third-party edge that aims to be interchangeable with the Parler client.

### 4.1 `NotifyPropertyUpdate` (compatibility fallback)

- Treat as **older-platform fallback / compatibility path**, not the primary handshake contract.
- If a target platform emits it and expects a reply, return **success** without relying on params. **No-op success** is acceptable; **`AlwaysOnParlerClient`** does this.

### 4.2 `SynchronizeModelState` (primary model sync)

**Original platform intent:** edge reports current property values so the server initializes its model.

**Parler:** no edge property snapshot to push. Return **success** with **empty** **`NamedVTQ`** (zero rows). **Not** used by the widget as a **Send-enable** barrier after **`Bind`** — only as a **required successful response** when the platform calls the service.

**Inbound (platform → edge):** The server invokes the edge service with one parameter row shaped like **`ModelStateSynchronizeRequest`** (system DataShape): **`propertyInfo`** (**`INFOTABLE`**, row shape **`EdgeThingPropertyNotificationV2`**: `edgeName`, `pushType`, `pushThreshold`, `startType`, …) and **`eventInfo`** (**`INFOTABLE`**, row shape **`EdgeThingEventNotification`**: `edgeName`).

**Edge service declarations:** any **`INFOTABLE`** input or output must declare **`aspects.dataShape`** and register matching DataShape **`fieldDefinitions`** so **`GetMetadata`** and parameter validation succeed (see **`parler-ui/lib/alwaysOnParlerClient.js`**). **Register** every service the platform calls so it is not `NOT_FOUND`.

### 4.3 When to enable the composer

1. **Transport connected:** WS + Auth + **`Bind` OK** → **`AlwaysOnParlerClient`** / widget enables **Send** (subject to **`isConnected`** on the agent path for **`ParlerStreamToRemoteThing`** / **`SubmitUserPrompt`** — platform rules unchanged).  
2. **Inbound surface (when called):** **`GetMetadata`**, **`SynchronizeModelState`**, and **`ReceiveMessage`** must be implemented and return **success** (or handle errors per §6.4–§6.5) **when the platform invokes them** — including **`SynchronizeModelState`** → **OK + empty `NamedVTQ`**. This does **not** delay step (1) in the default widget; failures here are **runtime errors**, not a second gate before first Send.  
3. **`NotifyPropertyUpdate`:** if emitted, must not fail (§4.1).  
4. **Invoke shape for user turns:** **`SubmitUserPrompt`** on **`ParlerGateway`** by default (§2.2), or **`ParlerStreamToRemoteThing`** on **A** (alternate, §2.1).

### 4.4 Post-bind `GetConnectionInfo` (connection version handshake)

After **`ConnectAndBind`** succeeds, the **`parler-ui`** widget invokes **`ParlerGateway.GetConnectionInfo`** on the bound Gateway (same Thing name as **`SubmitUserPrompt`**), passing **`agentThingName`** and the locally generated widget package version string for logs. The invoke is **non-blocking** for Send and uses the same **`#historyLoadEpoch`** stale-callback guard as **`GetConversationHistoryJson`** (**`docs/ui/load-history.md`** §4.3 pattern). On **`session.superseded`** (matching **`conversation_id`**), the widget **also clears** the displayed agent version fields together with the epoch bump so the status line does not retain a **stale** left-hand version while Send remains blocked until reconnect. Normative JSON and ThingWorx **`result`** / **`STRING`** rules: **`CONTRACTS/API_CONTRACT.md`** § **`GetConnectionInfo`**.

---

## 5. Starting a turn: agent invoke vs conversation Submit

### 5.1 **`ParlerStreamToRemoteThing`** (on **AIAgent**)

- **Invoke on:** **`AIAgent`** Thing **A**.  
- **Inputs:** **`message`**, **`systemPrompt`** (optional), **`remoteConversationThingName`** (= **C** Thing name, bound and connected).  
- **Returns:** **`request_id`** immediately; wire frames follow via **`ReceiveMessage`** on **C**, per §6.  
- **Orchestration:** agent loop on **A**; frames go through **`ReceiveMessage`** on **C** (there is no property-stream path).
- **Ownership:** **`AgentThreadDataTable`** ownership for **`remoteConversationThingName`** is enforced on this service.

### 5.2 **`SubmitUserPrompt`** (on **`ParlerGateway`**)

- **Invoke on:** **`ParlerGateway`** **C** where **C**’s Thing name equals **`conversationId`** (transient **SDKGateway** subclass in **`parler-agent`**).  
- **Inputs:** **`message`**, **`agentThingName`** (**`AIAgent`** Thing name), optional **`systemPrompt`**.  
- **Server:** **`AgentThreadDataTable`** row for **`getName()`**; **`username`** non-blank and matches caller; **`agentThingName`** must derive from **`AIAgent`**; then **`ParlerStreamToRemoteThing(message, systemPrompt, remoteConversationThingName = getName())`** on that agent.  
- **Downstream:** same **`ReceiveMessage`** (Remote) on **C** → widget, per §6.

**§5.1** remains the contract for starting a turn **via A** directly; it applies the **same** **`AgentThreadDataTable`** checks as **§5.2** when **`remoteConversationThingName`** is set.

---

## 6. Downstream: server dispatch, `ReceiveMessage`, IDs, errors

### 6.0 Artifact Cache readiness terminal

Message, ownership, Agent target, RemoteThing type, and connection errors remain synchronous
pre-request validation. After a request id exists, `ParlerStreamToRemoteThing` sends
`session.ack`, then evaluates the selected Agent's mandatory
`AgentSettings.artifactCacheFileRepository` before host-context, Playbook, LLM, task-state, or
history mutation. Blank configuration terminates with `ARTIFACT_CACHE_NOT_CONFIGURED`; a
missing/wrong-type/unavailable repository terminates with
`ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`. The existing wire `type:"error"` is the terminal: it
uses the same request id and is not followed by `done`. Runtime repository-wide failures and
post-HITL continuation use that same error-only rule; post-HITL keeps the original request id.

### 6.1 Server-side dispatch — will the server send “batches”?

At the **ThingWorx AlwaysOn** layer, each **`ReceiveMessage`** is a **remote service invocation** (request/response semantics per call). What varies is **how many logical Parler wire events** you pack **inside** one invoke.

| Pattern | Description | Industry analogues |
|---------|-------------|---------------------|
| **A — one invoke, one wire object (default)** | Each `ReceiveMessage` carries **one** JSON object that is one Parler wire frame. Server/agent loop: produce event → `callService("ReceiveMessage", …)` once. | **Server-Sent Events / OpenAI-style streaming:** many small HTTP chunks, **one logical event per chunk**. **gRPC server-streaming:** sequence of **independent** protobuf messages. **SignalR:** one client method invocation per notification. **MQTT:** one publish per logical update (per topic). |
| **B — one invoke, many wire objects (optional optimization)** | Single `ReceiveMessage` carries a **JSON array** of wire objects; **widget must iterate** and apply reducer per element. | **Financial / gaming** WebSockets sometimes **batch** ticks to cut overhead; **Kafka** batches at broker but **consumers** still process **per record** with **per-message** metadata. |

**Rule:** **Pattern A** is the default — simplest mapping to AlwaysOn, easiest debugging, matches **token-stream** UX. Pattern B requires a documented **array envelope** in **`agent-parler-payload.md`**.

**Server implementation note:** whether Java **coalesces** text inside the agent (e.g. 100 ms flush) is **orthogonal**: it can still emit **one** or **many** `ReceiveMessage` calls per flush window; granularity is a **product/perf** choice under pattern A vs B.

### 6.2 Correlation: `request_id` and `conversation_id` in the payload

**Authoritative split:**

| Profile | `conversation_id` on downstream frames |
|---------|----------------------------------------|
| **Base Parler wire** ([`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) default, dedicated WebSocket server) | **Required** only where the base contract says so (today: **`session.superseded`**). Other **`type`** values **may omit** it when one thread per connection. |
| **AlwaysOn `ReceiveMessage` profile (this document)** | **Required on every** JSON object sent via **`ReceiveMessage`**, for every **`type`**, in addition to the per-**`type`** fields in **`API_CONTRACT.md`**. Same string as the bound session Thing name / **`conversationId`** (the **`ParlerGateway`** name) ([`ALWAYON_WIRE_CONVENTIONS.md`](../../CONTRACTS/ALWAYON_WIRE_CONVENTIONS.md)). |

**Industry practice:** per-message correlation ids (**AMQP**, **Kafka**, **CloudEvents**, **trace context**) motivate the AlwaysOn profile’s **per-frame** **`conversation_id`**, even when **one conversation per bind**.

**`request_id`:** every object that participates in turn / assistant-row semantics must include explicit **`request_id`** (see [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md)).

**`API_CONTRACT.md`** and [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) §2 reference this split explicitly so there is a **single** definition: base shape + **optional extension** fields; AlwaysOn adds the **mandatory `conversation_id`** rule for **`ReceiveMessage`** only. Terminal **`session.cancelled`** (user stop) follows the same profile — see **`API_CONTRACT.md`** wire revision header and **`docs/agent/turn-cancellation-control.md`**.

### 6.3 Wire vs UI events (terminology)

**Wire JSON** (what **`ReceiveMessage`** carries, what **`wireToUiEvent`** reads) uses **`type`** values such as **`error`**, **`done`**, **`content.delta`**, **`activity`**, … — see **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)** (server → client) and **[`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md)** §2–3.

**UiEvents** (what **`reduceUiEvent`** consumes) use names such as **`session.error`**, **`session.done`**, **`assistant.append`**, … — produced **only after** **`wireToUiEvent`** (or an explicit **`applyUiEvent`** bypass).

**Completion / failure:** use wire **`type: "error"`** and **`"done"`** — not **`session.error`** / **`session.done`** (those names are **UiEvents**; **`wireToUiEvent`** does not map them and returns **`null`**). Other wire frames such as **`session.ack`** or **`session.superseded`** keep their **wire** `type` as in **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)**.

### 6.4 Errors: application vs transport (mandatory widget fallback)

| Class | Responsibility |
|-------|----------------|
| **Application / server** | On failure or normal end of turn, emit **wire** objects via **`ReceiveMessage`**: **`{ "type": "error", "request_id": "…", "message": "…", … }`** and **`{ "type": "done", "request_id": "…" }`** per **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)** / **[`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md)**. The widget runs **`applyWireMessage` → wireToUiEvent → reduceUiEvent`**. |
| **Transport / client (mandatory)** | **Remote invoke failure**, **timeout**, **socket drop**, **reconnect during `busy`**, etc. must be handled **in the widget**. **Never** rely solely on the server for these paths. |

**`request_id` on transport fallback (required):** The reducer **ignores** **`session.error`** / **`session.done`** when **`busy`** if **`evt.requestId !== activeRequestId`** (**`parler-ui/lib/chatSession.js`**). While **`busy`** with a known **`activeRequestId`**, a transport fallback **must** either:

1. Emit a **synthetic wire** object **`{ "type": "error", "request_id": "<activeRequestId>", "message": "…" }`** (or **`"done"`** to end cleanly), then **`applyWireMessage` / `wireToUiEvent` → `reduceUiEvent`**; or  
2. **Bypass** wire parsing and call **`applyUiEvent`** / **`reduceUiEvent`** with the **UiEvent** shape: **`{ type: 'session.error', requestId: <activeRequestId>, message: '…' }`** (or **`session.done`**).

A **wrong or missing** **`request_id`** on path (1), or **wrong `requestId`** on path (2), leaves **`busy`** stuck.

Details: **`agent-parler-payload.md`** §1.4–1.7.

### 6.5 `ReceiveMessage` downlink failure (normative)

A failed **`ReceiveMessage`** **`callService`** means the **AlwaysOn downlink** from server to that edge client may be **unusable** for subsequent frames in the same failure window. **Do not assume** that sending another **`error`** or **`done`** wire object via **another** **`ReceiveMessage`** will reach the widget — often it **will not**.

| Situation | Expected behavior |
|-----------|-------------------|
| **Pre-turn validation** | Fail **synchronously** (e.g. **`ParlerStreamToRemoteThing`** throws if **C** is not **`RemoteThing`** or not **`isConnected`**) so the widget’s **`invokeService`** sees an error **before** **`busy`** / **`request_id`** logic depends on **`ReceiveMessage`**. |
| **Mid-turn dispatch failure** | Server **stops** further **`ReceiveMessage`** attempts for that turn once a send fails; **log** at **WARN/ERROR**. **Widget** must clear **`busy`** via **§6.4 transport fallback** (timeout, synthetic wire, or **`applyUiEvent`**). |
| **Application exception after some frames** | Server **may** attempt **one** **`error`** + **`done`** pair **only if** no prior **`ReceiveMessage`** failure was observed for that turn; if the first recovery send fails, **do not** chain more **`ReceiveMessage`** calls. |
| **Terminal `done` lost** | If **`content.delta`** or **`error`** **succeeds** but **`done`** **`ReceiveMessage`** **fails**, the widget may never get **`session.done`**; treat like downlink break — **§6.4** fallback. Server **must** check **`send`** on **`wireDone`** and **log** (same as any other failed frame). |

**`parler-agent`:** **`ParlerReceiveMessageSupport.send`** returns **`boolean`**; **`ParlerStreamToRemoteThing`** tracks **`downlinkOk`** and checks **every** invoke including **`wireDone`** (success path and recovery path).

---

## 7. Bind: `names`, `gatewayName`, `gatewayType`

**Parler widget (`alwayson-js-codec`):** **`gatewayName` = `conversationId`**; **`gatewayType`** **`ParlerGateway`**; **`names` = `[conversationId]`** (single bind). See **`lib/alwaysOnConnect.js`**.

**RemoteThing / Gateway:** The edge-backed session endpoint is a **`RemoteThing`** (**`ParlerGateway`** extends **`SDKGateway`** → **`RemoteThing`**). **`BrowserGateway`** is not used by Parler.

---

## 8. Security (brief)

- **WSS** + short-lived KeyID in production.  
- **Authorize** **`ParlerGateway`** **C** (**`conversationId`**) for **`invokeService`** (**`SubmitUserPrompt`**, default) and **AIAgent** **A** for **`ParlerStreamToRemoteThing`** (alternate); **`AgentThreadDataTable`** rows scope **`conversationId`** to the caller.  
- **Validate** **`agentThingName`** / **A** as **`AIAgent`**-derived before orchestration runs.

---

## 9. References

| Doc | Topic |
|-----|--------|
| [`agent-parler-payload.md`](./agent-parler-payload.md) | `ReceiveMessage` envelope, optional batch array |
| [`invoke_service_design.md`](../agent/invoke_service_design.md) §8.1 | Local vs remote Java services |
| [`ai-parler-design.md`](../ui/ai-parler-design.md) | Widget split |
| [`ALWAYON_WIRE_CONVENTIONS.md`](../../CONTRACTS/ALWAYON_WIRE_CONVENTIONS.md) | `conversation_id` |
| [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) | Parler UI / wire types |
| [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) | REST / Parler WS (reference) |

---

## 10. `parler-agent`: Java `RemoteThing` only

**Extension-side file map:** [`AGENT-ALWAYSON-TWX.md`](../agent/AGENT-ALWAYSON-TWX.md).

**Parler on ThingWorx** uses **transient `ParlerGateway`** (**`SubmitUserPrompt`**) and/or **`ParlerStreamToRemoteThing`** on **`AIAgent`**, with wire frames on **`ReceiveMessage`** only; **`AgentThreadDataTable`** enforces **`conversationId`** ownership on **both** invoke paths. There is no persistent conversation Thing template.

---

## 11. Implementation requirements

1. **Widget:** **Connect**, Auth, Bind, status UI; enable **Send** after **Bind OK** (§4.3). Inbound **`GetMetadata`** / **`SynchronizeModelState`** / **`ReceiveMessage`** must succeed **when the platform calls them** — **not** an extra “wait for sync before Send” gate.  
2. **Edge / codec client** (e.g. **`AlwaysOnParlerClient`**): implement **`GetMetadata`**, **`SynchronizeModelState`** (empty **NamedVTQ**), and **`ReceiveMessage`** on the bound **`conversationId`** — **all three** are required for Parler transport (§4). **`NotifyPropertyUpdate`** is an optional compatibility fallback when a target platform still depends on it.  
3. **Extension:** **`ParlerStreamToRemoteThing`** + **`ReceiveMessage`** dispatch; **`ParlerGateway`** + **`SubmitUserPrompt`**.  
4. **Server dispatch:** default **one wire object per `ReceiveMessage`**; optional **array batch** only if documented and widget fans out.  
5. **Payloads:** explicit **`request_id`** + **`conversation_id`** on every downstream wire object.  
6. **Widget:** mandatory **transport fallback**; use **wire** **`type: "error"`** (or **`applyUiEvent` `session.error`**) with **`request_id` / `requestId` === `activeRequestId`** while **`busy`** — especially when §6.5 applies.  
7. Keep **`agent-parler-payload.md`** in sync.
