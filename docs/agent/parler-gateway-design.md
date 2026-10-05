# Parler Gateway design (Transient / AlwaysOn / HA)

This document describes the architecture of **`parler-agent` (Java extension in this repo)** and **parler (remote widget)** in the ThingWorx AlwaysOn scenario: **uniqueness of live session connections under cluster (HA)**, **no persisted per-session RemoteThing**, **ParlerGateway** and **single-name bind**, plus security and operations notes. It captures **architectural intent and platform rationale**; code is the source of truth for details.

---

## 1. Problems we are solving (goals)

1. **Uniqueness of live session connections under HA**  
   In **cluster** deployments, the **AlwaysOn binding and downstream path** for a given session identifier (e.g. **`conversationId`**) must be **convergent** in platform semantics (first-in-wins / last-in-wins per policy), **avoiding** **logical forks** or **contention for the same conversation** across multiple clients and nodes.

2. **Less operational burden on the business side**  
   **Stop** **persisting** a per-conversation “conversation RemoteThing” in the platform (formerly **`AIConversationRemoteThing`**), so Composer / entity lists are not flooded with hard-to-explain **persistent RemoteThings** that confuse ops and business users.

3. **No reduction in protocol capability**  
   The server must still **push** Parler frames downstream over AlwaysOn (e.g. via **`ReceiveMessage`**). On ThingWorx this has long been tied to the **`RemoteThing` + bind** model; the “bindable Thing” layer cannot be omitted.

4. **Cluster-visible transient handle: must be a Gateway**  
   **Ordinary** transient `RemoteThing` (**without** the **Gateway** ThingShape) **typically** does **not** follow the same **changewatch / ephemeral sync** path as **Gateway**; the entity **exists mostly in the current node’s JVM**, making it **hard** to rely on platform mechanisms for **same-name session handles** across HA.  
   **Transient Gateway with the Gateway Shape** (**`ParlerGateway`** in this design) can enter paths such as **`addEphemeralThingChangeLog`**, so **other nodes** can create/observe **same-name Transient Gateways**, supporting **cross-HA session handle consistency**.

5. **One session, one handle**  
   A generic browser AlwaysOn client that reserves the Gateway name as a placeholder would force a **second** bind name for session services—**more than one transient handle per session**.  
   **parler** uses the **`@xudesheng/alwayson-js-codec`** package with the in-repo **`AlwaysOnParlerClient`**, whose **edge routing** aligns **`gatewayName`** and the **`ReceiveMessage`** target name as **`conversationId`** (see **§2.2**).

6. **Security and operations**  
   The widget uses **short-lived credentials** (e.g. temporary Application Key from `EntityServices.GetClientApplicationKey`) for WebSocket auth, narrowing key exposure; transient entities need **cleanup beyond restart** (explicit delete, unbind, scheduled sweeps, etc.). **Session ownership** is validated via **`AgentThreadDataTable`** and **Gateway name** (see **§2.3**).

---

## 2. Design intent overview (what it is)

| Dimension | Intent |
|-----------|--------|
| **Session ↔ Thing mapping** | **One** **`ParlerGateway` instance name** per session; name **equals `conversationId`** (or a business-agreed uniform prefix, but **bind and downstream target stay aligned**). |
| **Java class** | **`ParlerGateway` extends `SDKGateway`** (hence `RemoteThing`), **non-final** for extension; must **explicitly** implement the **Gateway** ThingShape (subclass metadata convention same as **`BrowserGateway`**). |
| **Persistence** | **`ParlerGateway` is Transient**; **do not** model conversation threads as **persistent `AIConversationRemoteThing`**. Business facts (session metadata, owner) live in **`AgentThreadDataTable`** etc.; **primary key `conversationId` = Gateway name**. |
| **Where server services live** | **`ReceiveMessage`** and **same-session-boundary services** (e.g. **`SubmitUserPrompt`**, **`SubmitApprovalDecision`** (HITL, same invoke path as `SubmitUserPrompt`); full list per `./AGENT-ALWAYSON-TWX.md` and implementation) **live on `ParlerGateway`**. |
| **Bind payload (widget)** | **`gatewayType = ParlerGateway`**, **`gatewayName = conversationId`**, **`names` contains only that one name** (same as `gatewayName`). |
| **Downstream** | **`AgentThing.ParlerStreamToRemoteThing(..., remoteConversationThingName)`** uses **`remoteConversationThingName`** = **`conversationId`** = **`ParlerGateway` name**; target must be **bound and connected**; edge **`ReceiveMessage`** is implemented on that name. |

### 2.1 Key service split (ReceiveMessage / SubmitUserPrompt / ParlerStreamToRemoteThing)

Aligned with **`./AGENT-ALWAYSON-TWX.md`**; the “session-side Thing **C**” is **`ParlerGateway`** (not a persistent RemoteThing).

| Service | Entity (target architecture) | Role |
|---------|------------------------------|------|
| **`ReceiveMessage`** | **`ParlerGateway`** (Transient, Gateway) | **Downstream entry**: platform invokes `ReceiveMessage` on the **bind name** with Parler **wire JSON** payload; **parler** edge must handle on that name. Invoked from **`AgentThing`** side via **`ParlerReceiveMessageSupport`** etc. |
| **`SubmitUserPrompt`** | **`ParlerGateway`** | **Upstream entry (session side)**: client submits user message and **`agentThingName`**; after validation calls **`AgentThing.ParlerStreamToRemoteThing(..., getName())`** with downstream target **this Gateway / `conversationId`**. |
| **`SubmitApprovalDecision`** | **`ParlerGateway`** | **HITL**: **`approve` / `cancel` / `reject_with_comment`** for downstream **`approval.required`**; **`tool_name`** must cover at least **`set_property_value`** and **`invoke_service`** (same pending framework). Fields align with parler **`CONTRACTS/API_CONTRACT.md`** **`approval.decision`**. Server **`PendingApprovalStore`** has TTL; on expiry downstream **`approval.resolved` (`expired`)** + **`done`**; when writing back to **`AgentThing`** conversation, **skip** full overwrite if **current message list no longer matches pending snapshot or has grown**, to avoid wiping post-reconnect history (**`AgentThing#applyParlerApprovalExpiredToConversation`**). |
| **`ParlerStreamToRemoteThing`** | **`AgentThing`** (**`AIAgent`**) | **Orchestration**: dispatch `ReceiveMessage` to **`remoteConversationThingName`**; must be **bound and `isConnected`**. |

### 2.2 Widget / codec side: single-name bind

- **Bind**: only **`names: [ conversationId ]`**, **`gatewayName`**, **`gatewayType`** as in the table above.  
- **Inbound**: **`GetMetadata`**, **`SynchronizeModelState`**, **`ReceiveMessage`** (and optionally **`NotifyPropertyUpdate`**) addressed to **`conversationId`** are handled **inside `AlwaysOnParlerClient`** **without** a second Edge bind name; details in parler **`../architecture/agent-alwayson.md`** §4.  
- **Send readiness**: after **successful Bind**, **Send** opens per **`agent-alwayson.md`** §4.3; **`SynchronizeModelState`** only needs **success + empty NamedVTQ** when the platform calls it—**not** as an extra gate of “full handshake before first Send”.  
- **Agent cache readiness:** `ParlerGateway` retains input/ownership/Agent resolution
  responsibility and delegates the mandatory `artifactCacheFileRepository` check to `AgentThing`.
  `ParlerStreamToRemoteThing` returns the request id, sends `session.ack`, then either proceeds or
  emits the Agent's stable terminal cache `error` on that same request id with no following `done`.
  A post-HITL cache rejection terminates that original request and does not resume the LLM.
- Handling these calls on the single bind name is the prerequisite for **one Transient handle**.

### 2.3 Session and user validation (`AgentThreadDataTable`)

When verifying “who owns this session” (hook points: **`AgentThreadDataTableSupport`**, **`ParlerStreamToRemoteThing`**, **`ParlerGateway.SubmitUserPrompt`**):

1. Look up **`AgentThreadDataTable`** by **`ParlerGateway` Thing name** (i.e. **`conversationId`**; DataShape primary key **`conversationId`** matches this name).  
2. **No row** → **reject** (error).  
3. **Row exists** but **`username`** is empty or does **not** match **current ThingWorx user** → **reject** (error).

---

## 3. Why this design (rationale and platform basis)

### 3.1 Why we cannot “drop RemoteThing entirely”

After bind, the platform attaches **`CommunicationEndpoint`** to the Thing via **`IEndpointBindingObserver#bindEndpoint`**; on the platform **only `RemoteThing` (and subclasses) implement that interface**. So **handles that participate in standard AlwaysOn downstream paths remain the RemoteThing family**.

**Transient Gateway** avoids **persisted entity store** and uses **ephemeral changewatch** instead of inventing a second “no Thing” path at the protocol layer.

### 3.2 Why Gateway + `ParlerGateway` template

- **Bind path validation**: `DispatchingServerCommunicationModule`, before **auto-creating** a missing gateway, requires **`gatewayType`’s ThingTemplate `implementsShape("Gateway")`**.
- **Cluster ephemeral**: When bind runs and **this node has no** gateway name yet, the platform **`createThingFromTemplate` + `ThingManager.addEphemeralThingChangeLog`**, so **other nodes** can create same-name Transient Gateways via changewatch / replay—same as **BrowserGateway / SDKGateway**.

So **ParlerGateway** is both a **valid Gateway type** and carries **one session, one instance, one name** namespace.

### 3.3 Relationship to `BrowserGateway` / `SDKGateway`

- **`SDKGateway`** already declares **`Gateway` ThingShape** on the class (`@ThingworxImplementedShapeDefinitions`).
- **`BrowserGateway`** extends `SDKGateway` and **re-declares** the same Gateway annotation on the subclass; **when ThingTemplate uses a separate ThingPackage pointing at the subclass**, **Java inheritance alone does not copy annotations into subclass package metadata**, so the platform uses the **subclass re-declaration** convention.
- **`ParlerGateway`** **likewise declares `Gateway` explicitly** on the subclass, with a **dedicated ThingPackage + ThingTemplate `ParlerGateway`** (base `SDKGateway` applied from `@ThingworxBaseTemplateDefinition`), so `implementsShape("Gateway")` is not spuriously false when only `extends SDKGateway`.

### 3.4 Session uniqueness and first-in-wins / last-in-wins

- **Uniqueness** means: for **one binding name** (e.g. a `conversationId`), at any time **binding policy allows only one “currently valid” endpoint** (first wins or last wins per **first-in-wins / last-in-wins**).
- This does **not** conflict with **not surfacing Transient Thing `isConnected` to business users**: the product focus is **protocol-layer connection uniqueness**, not Composer monitoring of every Transient.

### 3.5 Why REST / short AppKey steps remain

- **Transient Gateway** must be **resolvable on the server before** bind is processed (or created at bind), and **`AgentThing.ParlerStreamToRemoteThing`** checks target **`isConnected`**, so **ordering** must ensure bind completes before streaming (see `./AGENT-ALWAYSON-TWX.md`).
- **JavaScript AlwaysOn**: **WebSocket open → Auth (`AuthContext`) → session/endpointId only after success → then `BindContext`**; short AppKey fits **credentials scoped to this connection** (note **`GetClientApplicationKey` entity expiry ~15s, scheduled delete ~30s**—do not confuse with “30 seconds” in comments; follow implementation schedules).

### 3.6 HA and multiple clients

- **Same session sticky**: Often keeps **one user session**’s REST and WS on one node.
- **Different clients / browsers**: May be **different sessions, different nodes**; **unique gateway name (`conversationId`)** must distinguish sessions to avoid confusion.
- **Gateway ephemeral**: **`addEphemeralThingChangeLog`** helps **cross-node same-name Transient entities**; **which machine holds the connection** still depends on deployment and testing.

---

## 4. Recommended client–server collaboration order (summary)

1. **REST**: Write / validate **`conversationId`**, **`username`**, etc. in **`AgentThreadDataTable`**; issue **short-lived Application Key** (e.g. `GetClientApplicationKey`). **Do not** rely on creating persistent **`AIConversationRemoteThing`**.
2. **WebSocket connection**.
3. **Auth**: AlwaysOn auth with the key from step 1.
4. **Bind**: **`gatewayType = ParlerGateway`**, **`gatewayName = conversationId`**, **`names = [ conversationId ]`** (single name only).
5. **Conversation**: Client calls **`SubmitUserPrompt`** on **`ParlerGateway` name** (or REST equivalent); **Agent** **`ParlerStreamToRemoteThing(..., remoteConversationThingName = conversationId)`**.

If edge **upstream** is unavailable, keep **REST fallback** (per parler docs).

---

## 5. Transient cleanup (beyond restart)

- **`ThingManager.removeTransientThing(Thing)`**: actively destroy Transient Thing.
- **Unbind / disconnect**: platform removal path for **Transient** Things implementing **`IEndpointBindingObserver`** (may coordinate with **ephemeral removal log** in cluster).
- **Sessions that never bind successfully** are not swept by the extension.

---

## 6. Relationship to existing extension docs

- **AlwaysOn and Agent service mapping**: `./AGENT-ALWAYSON-TWX.md`.
- **Parler protocol and UI contracts**: parler repo docs (e.g. `../architecture/agent-alwayson.md`, `../architecture/agent-parler-payload.md`).
- **Release packaging (parler / widget ZIP)**: from the repo root **`node scripts/build-twx-release-pair.mjs`**, or **`parler-ui-widget`** **`npm run sync`** plus the widget build (see the repo-root `README.md`).

---

## 7. Operator notes

- **Cluster**: cross-node behavior (create session, bind, and downstream on different nodes) depends on the deployment; it is not verified by in-repo tests.
- **Permissions**: restrict who may call **`GetClientApplicationKey`** and the session creation APIs.
- **Short-lived key failures**: on AlwaysOn auth failure **`parler-ui`** shows short App Key guidance, and **ConnectAndBind** reports the failure as **`session.error`** when no turn is in progress. Exact key lifetime follows platform policy.
