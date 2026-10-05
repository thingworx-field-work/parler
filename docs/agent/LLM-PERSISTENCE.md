# LLM Conversation Persistence (DataTable + Stream)

This document describes how the extension persists LLM **threads** (sessions) and **messages** using ThingWorx **DataTable** and **Stream** entities. **LlmSessionStore** is a **concrete helper class** (no interface in the current design): it is directly instantiated and uses **AgentThreadDataTable** and **AgentMessageStream**. The API contract is summarized in **./AGENT-CONTEXT.md** § 5.5.

**Calling convention:** Callers (e.g. AgentThing / Chat flow) use the **instantiated LlmSessionStore helper** directly (one shared instance).

---

## 1. Entities and DataShapes

| Entity | Type | DataShape | Purpose |
|--------|------|-----------|---------|
| **AgentThreadDataTable** | DataTable | AgentThreadData | One row per thread: conversationId (primary key), username, agentName, title, createdAt, updatedAt |
| **AgentMessageStream** | Stream | AgentMessageData | One stream entry per message; metadata `source` = conversation/thread id; row: agentThing, role, content, toolCallId, toolCalls, promptTokens, completionTokens |

**AgentThreadData** (DataShape) fields:

- `conversationId` (STRING) — thread/session id; **primary key for DataTable**. The field must have `aspect.isPrimaryKey="true"` in the DataShape definition; this is required for DataTable and must not be removed.
- `username` (STRING) — owner
- `agentName` (STRING) — Agent Thing name
- `title` (STRING) — optional
- `createdAt` (DATETIME)
- `updatedAt` (DATETIME)

**AgentMessageData** (DataShape) fields:

- `agentThing` (STRING) — **AIAgent** Thing name for this row
- `role` (STRING) — system | user | assistant | tool
- `content` (TEXT) — nullable (e.g. assistant with only tool_calls)
- `toolCallId` (STRING) — for role=tool
- `toolCalls` (TEXT) — JSON array of tool calls for role=assistant
- `promptTokens` (INTEGER) — LLM input tokens for this row’s assistant API call (0 for user/tool)
- `completionTokens` (INTEGER) — LLM output tokens for that call (0 for user/tool)

**AddStreamEntry** metadata: `source` = conversation identity (AlwaysOn **ParlerGateway** name, AgentThread **`conversationId`**, or `adhoc-*`). `sourceType` = **`Thing`** (metadata classifies the source string as a Thing name; not the **AIAgent** row field `agentThing`).

Stream entries are ordered by **timestamp** (set by AddStreamEntry or server default).

---

## 2. CRUD: Thread (DataTable)

All operations target **Things["AgentThreadDataTable"]**. Optional parameters (tags, location, source, sourceType) may be passed as null when not needed.

| Operation | Service | Parameters / Notes |
|-----------|---------|--------------------|
| **Create** | `AddDataTableEntry` | `values`: InfoTable with one row, DataShape **AgentThreadData**. Returns new row id. |
| **Read one** | `GetDataTableEntryByKey` | Input: `key` (STRING) = primary key value (e.g. conversationId). Returns InfoTable with one row or empty. |
| **Read many** | `QueryDataTableEntries` | Query/filter (e.g. by username). Returns InfoTable. **Agent extension:** `GetOrCreateConversationId` uses a platform `query` (`EQ` on `username`, optional `And` with `title`) so listing is not limited to the first N rows of the whole table. |
| **Update** | `UpdateDataTableEntry` | `values`: InfoTable with one row containing updated fields (e.g. title, updatedAt). |
| **Delete** | `DeleteDataTableEntry` | Primary key: `conversationId`. |

**Service signature (DataTableThing):**

- `AddDataTableEntry(tags, location, source, sourceType, values)` — values: INFOTABLE (DataShape AgentThreadData, one row).
- `GetDataTableEntryByKey(key)` — key is the primary key value (e.g. conversationId string). Returns InfoTable.
- `UpdateDataTableEntry(tags, location, source, sourceType, values)`.
- `DeleteDataTableEntry(conversationId)` (or equivalent by key).

---

## 3. CRUD: Messages (Stream)

All operations target **Things["AgentMessageStream"]**.

| Operation | Service | Parameters / Notes |
|-----------|---------|--------------------|
| **Append** | `AddStreamEntry` | `timestamp` (DATETIME, optional), `location`, `source`, `sourceType`, `tags`, `values`. `values`: InfoTable with one row, DataShape **AgentMessageData**. |
| **Read** | `QueryStreamData` / `QueryStreamEntriesWithData` | **`QueryStreamData`**: returns rows shaped like **AgentMessageData** (value fields only); filter **`source`**, optional time range / **`oldestFirst`** — **no `sourceType` argument** (see **`StreamThing#QueryStreamData`**). **`QueryStreamEntriesWithData`**: stream-entry rows + nested **`values`**; optional metadata filters. |

**clearMessagesForThread:** Current behavior is to **delete the thread only** (no deletion of stream entries for that conversationId). Stream entries for that thread are left in place; the thread row is removed so the conversationId is no longer considered active.

**Service signature (StreamThing):**

- `AddStreamEntry(timestamp, location, source, sourceType, tags, values)` — values: INFOTABLE (DataShape AgentMessageData, one row).
- `QueryStreamData(maxItems, source?, tags, sourceTags, startDate, endDate, oldestFirst, query)` — returns InfoTable of **value** rows (**AgentMessageData** shape for this Stream).
- `QueryStreamEntriesWithData(...)` — returns InfoTable of **stream entry** rows (metadata + nested values).

---

## 4. Mapping ChatMessage ↔ AgentMessageData

- **To Stream row**: `agentThing` + per-round token counts; `role` → role (string); `content` → content; `toolCallId` → toolCallId; `toolCalls` → JSON-serialized list to toolCalls (TEXT).
- **From Stream row**: Parse role, content, toolCallId; parse toolCalls JSON back to `List<ToolCall>` and build `ChatMessage` (system/user/assistant/tool or assistantWithToolCalls). Thread id comes from Stream **`source`**, not from the value row.

---

## 5. Java API Examples

Get the Thing and call `processServiceRequest(serviceName, params)`.

**Add thread row:**

```java
Thing dataTable = (Thing) ThingManager.getInstance().getEntityDirect("AgentThreadDataTable");
ValueCollection params = new ValueCollection();
params.put("values", infoTableWithOneRow); // DataShape AgentThreadData, one row
String id = (String) dataTable.processServiceRequest("AddDataTableEntry", params);
```

**Append message:**

```java
Thing stream = (Thing) ThingManager.getInstance().getEntityDirect("AgentMessageStream");
ValueCollection streamParams = new ValueCollection();
streamParams.put("timestamp", new DateTimePrimitive(new DateTime()));
streamParams.put("values", messageInfoTable); // DataShape AgentMessageData, one row
stream.processServiceRequest("AddStreamEntry", streamParams);
```

**Query messages for thread:** prefer **`QueryStreamData`** when you want **AgentMessageData** rows directly (same shape as REST `/Things/AgentMessageStream/Services/QueryStreamData`). Filter **`source`** = thread / Gateway name / `adhoc-*`. Alternatively **`QueryStreamEntriesWithData`** if you need stream-entry metadata; then unwrap **`values`** per row.

---

## 6. Implementation status (append)

**Implemented:** `Chat` / `ChatAsync` / Parler append messages to **AgentMessageStream** via `AddStreamEntry` (class `AgentMessageStreamAppender`). Each **user** turn, each **assistant** row with tool_calls, each **tool** result, and the final **assistant** text are written in order. **system** prompts are not written. Without `conversationId` on sync `Chat`, Stream **`source`** = `adhoc-<uuid>` for that single request. Assistant rows record **promptTokens** / **completionTokens** for the corresponding LLM API round (final assistant row uses the last completion round).

**Mandatory Artifact Cache boundary:** a readiness-rejected Chat, ChatAsync, AlwaysOn, structured
Playbook slash, or post-HITL continuation is not an accepted turn: it appends no user/tool/final
assistant Stream row and does not mutate `_conversations`. If the repository becomes unavailable
after an LLM tool batch began, the executing tool call and skipped siblings retain paired replay
rows and the in-progress user/tool evidence is stored, but no successful final-assistant row,
assistant message id, or completion metadata is fabricated.

**Parler HITL (`SubmitApprovalDecision`):** after **`approve`**, **`cancel`**, or **`reject_with_comment`**, **`AgentThing`** appends the executed or synthetic **tool** row then runs **`runParlerPostToolAgentLoop`** — further **assistant** / **tool** / final **assistant** rows from that **AgentLoop** are persisted the same way (see **`./data-operation-solution.md`** §2.2).

**Parler UI history:** `ParlerGateway.GetConversationHistoryJson` / **`AgentMessageStreamHistoryExporter`** uses **`QueryStreamData`** (`source` = Gateway name) → **`ai-parler-history-v1`** JSON (**per user turn:** final assistant **`content`**, **`charts`** from numeric-history tool rows via **`ParlerChartWireSupport`**, no raw tool text in **`markdown`**). Reloading Stream rows into the **in-memory LLM `ChatMessage`** list for a new agent turn is a separate path (distinct from widget hydrate): see **`./conversation-continuity.md`**.

---

## 7. Implementation Notes

- **Multi-user**: Scope threads by `username`; when reading or updating, filter by current user (e.g. getThread only if thread.username == currentUser).
- **createOrGetThread**: Query by conversationId (GetDataTableEntryByKey(key)); if missing, call AddDataTableEntry with conversationId, username, agentName, title, createdAt, updatedAt.
- **getMessagesForThread**: **`QueryStreamData`** (or **`QueryStreamEntriesWithData`** and map **`values`**) with Stream **`source`** = thread key; **`oldestFirst`** as needed; map each **AgentMessageData** row to `ChatMessage`.
- **clearMessagesForThread**: Implemented as **deleteThread(conversationId)** only; no per-message Stream deletion.

Reference: ThingWorx platform **DataTableThing** and **StreamThing** (e.g. AddDataTableEntry, QueryDataTableEntries, GetDataTableEntryByKey(key), AddStreamEntry, **QueryStreamData**, QueryStreamEntriesWithData).
