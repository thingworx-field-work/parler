# AlwaysOn / live bus — naming and `conversation_id`

This note aligns **ThingWorx AlwaysOn** (or any pub/sub JSON bus) with the Parler wire shapes in **[`API_CONTRACT.md`](./API_CONTRACT.md)**.

## 1. Use `conversation_id` (snake_case) on the wire

**Recommendation:** use **`conversation_id`** in JSON payloads, matching:

- WebSocket `chat.request` → `payload.conversation_id`
- Server control frame → `session.superseded` → required `conversation_id`

**Why snake_case:** same as existing REST/WebSocket JSON in this repo and typical Python backends. Mashup / JavaScript may bind to camelCase **`conversationId`** on `<ai-parler>`; only the **serialization layer** should map.

## 2. Should every frame include `conversation_id`?

| Approach | Pros | Cons |
|----------|------|------|
| **Every frame** carries `conversation_id` | Works with **topic-per-service** or **single shared topic**; easy logging/audit; subscribers can filter without connection state; resilient if messages reorder. | Slightly larger payloads. |
| **Only on connect / first frame** | Smaller messages. | Subscribers must track session state; easy to mis-route after reconnect; harder to debug. |

**Recommendation:** for a **broadcast** AlwaysOn topic shared by many threads, **include `conversation_id` on every frame** (or use **one topic per `conversation_id`**, which makes per-frame id redundant but shifts complexity to topic management).

For a **dedicated socket per thread** (like today’s Parler WebSocket), the connection already implies the thread; you may still send `conversation_id` on control frames such as **`session.superseded`** so the same JSON shape works everywhere.

## 3. Relationship to `request_id`

- **`conversation_id`**: stable **thread** key (ThingWorx conversation, Stream partition, etc.).
- **`request_id`**: one **turn** / one model invocation within that thread.

Both may appear on the same message where useful (e.g. superseded while a turn is in flight).

## 4. Control type: `session.superseded`

See **[`API_CONTRACT.md`](./API_CONTRACT.md)** — used when the server decides another client is now the sole **live** UI for that `conversation_id`. UIs should show `message`, clear busy state, and expect the connection to close.
