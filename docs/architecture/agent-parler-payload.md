# Agent / Parler — AlwaysOn payload notes

**Architecture:** [`agent-alwayson.md`](./agent-alwayson.md) (especially §6). **Bind target:** transient **`ParlerGateway`** whose Thing name = **`conversationId`**.

---

## 1. `ReceiveMessage` (Remote service on the bound session Thing, `ParlerGateway`)

**Direction:** platform → **codec-based AlwaysOn client** (**`AlwaysOnParlerClient`** in **`parler-ui`**) after **Bind** to **`conversationId`**.

The **payload contract** below is independent of the edge client implementation; any AlwaysOn edge that answers the same inbound services (**`agent-alwayson.md`** §4) receives the same payloads.

### 1.1 Call granularity (aligned with §6.1)

| Mode | Contract |
|------|----------|
| **Default** | **One** `ReceiveMessage` carries **one** Parler **wire** object (`type` per **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)** — e.g. **`activity`**, **`content.delta`**, **`chart`**, **`done`**, **`error`**, **`session.ack`**, …). For **end-of-turn**, use **`error`** / **`done`**, not **`session.error`** / **`session.done`**. |
| **Optional batch** | One invoke carries a **JSON array** of wire objects, **same schema per element**. Widget **must** iterate and run the reducer **once per element**. Use only after **documented** agreement and profiling. |

### 1.2 Wire object identity (aligned with §6.2)

Each object sent through **`ReceiveMessage`** must include:

- **`request_id`** — required for any frame that participates in turn / assistant-row semantics ([`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md)).  
- **`conversation_id`** — **required on every** object under the **AlwaysOn `ReceiveMessage` profile** (snake_case, [`ALWAYON_WIRE_CONVENTIONS.md`](../../CONTRACTS/ALWAYON_WIRE_CONVENTIONS.md)). This is **stricter** than the **base** Parler WebSocket contract in **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)**, where **`conversation_id`** is only **required** on **`session.superseded`**; see **`API_CONTRACT.md`** subsection *“`conversation_id` on other frames”* and **`agent-alwayson.md`** §6.2.

Per-**`type`** fields otherwise follow **[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)** (server → client). **`session.error` / `session.done` are UiEvents** after **`wireToUiEvent`**, not wire `type` strings.

### 1.3 AlwaysOn envelope vs inner JSON

The **Remote service** takes one **`STRING`** parameter named **`payload`** and returns **`NOTHING`** (`ParlerReceiveMessageSupport`); the **inner** body is JSON matching §1.1–1.2.

### 1.4 Wire `type` for end-of-turn (server in-band)

- **Failure:** wire **`{ "type": "error", "request_id": "…", "message": "…" }`** (optional **`code`**) → **`wireToUiEvent`** → **`session.error`**.  
- **Success / complete:** wire **`{ "type": "done", "request_id": "…" }`** → **`session.done`**.

Do **not** send **`"session.error"`** or **`"session.done"`** as wire **`type`** for failure/completion; use **`error`** / **`done`**. (**`session.ack`**, **`session.superseded`**, etc. remain valid **wire** types where the contract defines them — see **`wireAdapter.js`**.)

### 1.5 Transport fallback (widget mandatory)

On **remote invoke failure**, **timeout**, **socket drop**, etc., the widget must clear **`busy`** (see **`agent-alwayson.md`** §6.4).

### 1.6 Synthetic frames and `request_id` (reducer contract)

While **`busy`** with **`activeRequestId` set**, **`reduceUiEvent`** **ignores** **`session.error`** / **`session.done`** if **`evt.requestId !== activeRequestId`** (**`parler-ui/lib/chatSession.js`**).

**Rule:** transport fallback **must** use the **current turn id**:

- **Wire path:** synthetic **`{ "type": "error", "request_id": "<activeRequestId>", "message": "…" }`** (or **`"done"`**) → **`applyWireMessage`**; or  
- **Bypass path:** **`applyUiEvent({ type: 'session.error', requestId: <activeRequestId>, message })`** (or **`type: 'session.done'`**).

A **wrong or missing** id leaves **`busy`** stuck.

### 1.7 Downlink failure vs recovery sends

If **`ReceiveMessage`** itself fails (transport, edge handler error, bind loss), **do not rely** on issuing **more** **`ReceiveMessage`** invocations to deliver **`error`** / **`done`** — the same path is often **already broken**. The **widget** must still end the turn using **§1.5–1.6** / **`agent-alwayson.md`** §6.4–**§6.5**. Server code should **stop** further **`ReceiveMessage`** attempts for that turn after the first failure and **log**; a **single** recovery pair is only reasonable when **no** prior **`ReceiveMessage`** failure occurred in that turn.

**Last `done` frame:** If the **`type: "done"`** invoke fails (after a successful **`content.delta`** or **`error`**), the client may be stuck **`busy`** without **`session.done`** — same fallback as above. Server implementations **must** treat **`wireDone`** **`send`** like any other frame (**boolean** result + **WARN** log).

---

## 2. Other payloads

Add sections here for additional AlwaysOn services if needed.
