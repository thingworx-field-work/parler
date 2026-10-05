# Agent / Parler — AlwaysOn (`parler-agent`)

> On the session side, **C** is **Transient `ParlerGateway`** (**Thing name = `conversationId`**, **single-name bind**, **`ReceiveMessage` / `SubmitUserPrompt`** live on the Gateway). There is no persistent conversation Thing. Design details: **`./parler-gateway-design.md`** and **`../architecture/agent-alwayson.md`**.

---

This repo implements the **ThingWorx** Java side of Parler (**`AgentThing`**, **`ParlerGateway`**, entity XML, **`AgentMessageStream`**).

**Where to read what**

| Concern | Normative source |
|---------|------------------|
| **Session Thing (`ParlerGateway`), bind, HA / ephemeral** | **`./parler-gateway-design.md`** |
| **Wire JSON, payload shapes, widget decode / §6-style behavior** | **parler** **`../architecture/agent-alwayson.md`** (**§0.4** split; **§4** inbound **`GetMetadata`**, **`SynchronizeModelState`**, **`ReceiveMessage`**) and **`agent-parler-payload.md`** |

**`agent-alwayson.md` §2** remains useful **orchestration / wire** context; **session ownership and bind naming** follow **`ParlerGateway`** + **`AgentThreadDataTable`** (not the older **C** labels in §2).

Parler on ThingWorx is **AlwaysOn only** (**`ParlerGateway`** + **`ReceiveMessage`** wire).

**`ParlerGateway`:** extends **`SDKGateway`**; no conversation **`username`** / **`agentName`** Thing properties — **ownership** is enforced in **`SubmitUserPrompt`**, **`SubmitApprovalDecision`**, and **`GetConversationHistoryJson`** via **`AgentThreadDataTable`** (**`conversationId` = Gateway Thing name**). Assistant/tool/UI updates are **only** via **`ReceiveMessage`** wire. **`GetConversationHistoryJson`** returns persisted transcript JSON (**`AgentMessageStream`**) for the **`parler-ui`** widget after bind — see parler **`../ui/load-history.md`**.

---

## AlwaysOn — invoke on **AIAgent** (§2.1)

| Piece | Role |
|-------|------|
| **Client** | Bind **one** name (**`conversationId`** = transient Gateway). **Bind OK** enables **Send** per **parler** **`agent-alwayson.md`** §4.3; inbound **`GetMetadata`** / **`SynchronizeModelState`** / **`ReceiveMessage`** answer when the platform calls — **`SynchronizeModelState`** is **not** a second Send gate. Invoke **`SubmitUserPrompt`** / **`SubmitApprovalDecision`** on **C** or **`ParlerStreamToRemoteThing`** on **A** — **both** enforce **`AgentThreadDataTable`** for **`conversationId`**. Handle **`ReceiveMessage`** on that name. |
| **C** | **Transient `ParlerGateway`** (template **`ParlerGateway`**); edge **`ReceiveMessage`**. |
| **`AgentThing`** | Runs the agent loop; **`ParlerReceiveMessageSupport`** calls **`RemoteThing#callService("ReceiveMessage", …)`**. Failed sends set **`downlinkOk`** false (**`../architecture/agent-alwayson.md`** §6.5). |

**Artifact Cache prerequisite:** the selected AIAgent must have a resolvable dedicated
`AgentSettings.artifactCacheFileRepository`. After synchronous message/ownership/target/connection
validation, the worker sends `session.ack` and then checks readiness. Blank configuration yields
`ARTIFACT_CACHE_NOT_CONFIGURED`; missing/wrong-type/unavailable configuration yields
`ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`. Either is one terminal `error` on the same request id,
with no LLM/Playbook frames and no following `done`. Runtime repository-wide failure uses the same
error-only terminal. Post-HITL continuation retains the original request id and does not resume the
LLM when readiness fails.

---

## AlwaysOn — **`SubmitUserPrompt`** on **C** (§2.2)

| Piece | Role |
|-------|------|
| **Client** | On **C** (Gateway): **`SubmitUserPrompt(message, agentThingName, systemPrompt?)`**. |
| **C** | **`ParlerGateway`**: **`AgentThreadDataTable`** row for **`getName()`** must exist and **`username`** must match caller; resolves **A** from **`agentThingName`** (**must** derive from **`AIAgent`**), then **`ParlerStreamToRemoteThing(..., getName())`**. |

**Build:** **`compileOnly`** platform / communications artifacts for **`SDKGateway`** / **`RemoteThing`**.

**HITL observability (v1):** application logs may include lines prefixed **`PARLER_HITL`**: **`INFO`** for `pending_enqueued`, `decision_consumed`, `continuation_outcome`, `pending_expired`; **`decision_rejected`** is **`WARN`** for identity/security/state failures and **`DEBUG`** for `MISSING_*`, `INVALID_DECISION`, and **`GATEWAY_SUBMIT_CONV_MISMATCH`** unless **`AIAgent` → `AgentSettings` → `hitlAuditDebugAll`** is **true** (then those DEBUG-tier reasons are **also logged at WARN** for that agent; when **`agent_thing`** is not yet known, elevation applies if **any** AIAgent has the flag — lab / Composer convenience). **Changing `hitlAuditDebugAll`:** takes effect after **restarting the AIAgent Thing** (or any action that re-runs **`initializeThing`**); saving configuration alone may leave the internal registry stale. **First integration / Composer:** use **`hitlAuditDebugAll`** and/or set **`com.thingworx.things.agent.tools.ParlerHitlAuditLog`** to **DEBUG**. See **`ParlerHitlAuditLog`**, **`AgentBaseThing`** / **`AgentSettings`**, and **`./data-operation-solution.md`** §6.

---

## Related files

- `src/main/java/com/thingworx/things/agent/AgentThing.java` — **`ParlerStreamToRemoteThing`**, **`Chat`**, **`ChatAsync`**
- `src/main/java/com/thingworx/things/agent/ParlerGateway.java` — **`SubmitUserPrompt`**, **`SubmitApprovalDecision`**, **`GetConversationHistoryJson`**
- `src/main/java/com/thingworx/things/agent/AgentMessageStreamHistoryExporter.java` — **`AgentMessageStream`** → **`ai-parler-history-v1`** JSON
- `src/main/java/com/thingworx/things/agent/AgentThreadDataTableSupport.java` — shared **`AgentThreadDataTable`** ownership checks
- `src/main/java/com/thingworx/things/agent/ThingTemplateSupport.java` — template-chain validation
- `src/main/java/com/thingworx/things/agent/ParlerReceiveMessageSupport.java` — wire JSON + **`ReceiveMessage`**
- `src/main/java/com/thingworx/things/agent/tools/ParlerHitlAuditLog.java` — **`PARLER_HITL`** structured **INFO** lines for approvals
- `metadata.xml` — **`ParlerGateway`** package + template (**`baseThingTemplate="SDKGateway"`**)
