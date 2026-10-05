# Table disclosure titles (`presentationTitle`)

**Status:** Implemented in **`parler-agent`** (stream **`executedToolName`**, carrier, history parity, tests); the widget disclosure reads the server-authored **`presentationTitle`**. Normative **`presentationTitle`** rules are **`CONTRACTS/TABLE_CONTRACT.md`** §3.1.1.

**Audience:** product / UX, `parler-agent` and `parler-ui` maintainers.

**Normative wire:** `CONTRACTS/TABLE_CONTRACT.md` §3.1 — `TableBlock.presentationTitle` (optional; server-authored; **tool identity + bounded structured suffix**; single line; length cap + ellipsis).

**Related product docs:** `docs/ui/table-view-solution.md`, `docs/ui/turn-actions-and-table-export.md`.

---

## 1. Problem

List-class tables render inside a `<details>` disclosure. The visible summary uses **`tableDisclosureSummaryLabel()`** in `parler-ui/lib/artifactPresentation.js`, which prefers **`TableBlock.presentationTitle`** when non-empty.

A **family** of built-ins returns **`INFOTABLE` / `INFOTABLE_LARGE`** success JSON shaped like **`invoke_service`** (shared formatting path, e.g. `InvokeServiceExecutor.formatBuiltinInfotableResult` callers). **`ParlerInvokeServiceInfotableTableWire`** sets **`presentationTitle`** via **`ParlerTableWirePresentationTitles.infotablePresentationTitle`**. Before **`executedToolName`** (stream row + **`ChatMessage`** carrier), titles for that envelope could only rely on **`entityName`** / **`serviceName`** and body **`tool`**; absent those, the name segment collapsed to the bare literal **`invoke_service`**, even when the **executed** tool was `query_alert_history`, `query_alert_summary`, `query_stream_data`, or **`query_property_history`** (including legacy replay rows that still carry older tool names in the body).

**User-visible failure:** multiple different tools each show **`invoke_service`**, so operators and training material misread the assistant’s actual tool chain.

---

## 2. Product requirements (non-negotiable)

| ID | Requirement |
|----|----------------|
| R1 | **Whenever a table is emitted from a tool execution** and the server knows which tool ran, **`presentationTitle` MUST reflect that executed tool’s LLM-facing name** (built-in or configuration-repository extended tool). No generic placeholder where identity is known. |
| R2 | **When the executed tool is `invoke_service`**, the title **MUST** also carry the **values** of **`entityName`** and **`serviceName`** from structured success fields (bounded), so the disclosure reads as **`invoke_service:`** plus target (see §6.2 — angle-bracket placeholders mean **values**, not literal key spellings). |
| R3 | **Single physical line:** `presentationTitle` **MUST NOT** contain newlines; the client treats it as one line. |
| R4 | **Width:** Obey **`CONTRACTS/TABLE_CONTRACT.md`** (≤ **80 UTF-16 code units**). **Overflow MUST be truncated once** at emission, using **`…`** (U+2026) as the terminal character when truncated. **No second truncation pass** in the client for this field. |
| R5 | **No natural-language inference:** titles **MUST NOT** be derived from user prompts, assistant prose, or regex over cell values. Allowed inputs: **executed tool identifier** (row field + **`ChatMessage`** carrier), **structured tool-success JSON** (validated body **`tool`**, invoke envelope), and **replay** reconstruction via the same rules in **`AgentMessageStreamHistoryExporter`** (row field + body **`tool`**; no assistant cross-row join today — §5.1 step 3). |

---

## 3. Goals (engineering alignment)

| ID | Goal |
|----|------|
| G1 | Meet **R1–R5** for **live** emission and **history / export / replay** reconstruction (**parity**), including **streaming order** and **windowed export** (tool row without adjacent assistant row). |
| G2 | **Class-level** fix for the whole **`formatBuiltinInfotableResult` infotable family** and any other path that builds **`TableBlock`** from tool results through **`ParlerInvokeServiceInfotableTableWire`** (not a one-off for `query_alert_history` only). |
| G3 | **Extended tools** that return infotable-shaped success JSON **MUST** show the **same** extended tool name the model invoked (no built-in-only allowlist that blocks extended names). |
| G4 | **Central emission:** every non-empty title **MUST** go through **`ParlerTableWireFields.putPresentationTitle`** (matches **`CONTRACTS/TABLE_CONTRACT.md`** §3.1.1 — **MUST**, not optional) so the **80**-code-unit cap and ellipsis stay one place (`ParlerTableWireFields.java`; leading tool text survives; trailing suffix truncates first). |

---

## 4. Non-goals

- Changing **column** headers, row keys, **`INFOTABLE_LARGE`** paging, or export CSV semantics.
- Encoding **full** tool arguments in the title (only bounded structured suffixes per §6).
- **Mutating LLM-visible tool-success JSON** (`content` / persisted tool body) solely to carry title provenance — use **stream row metadata** (§5.4) so compaction, cohort bundles, and evidence stay stable.
- Client-side **invention** of tool names when the server omitted linkage and body fallbacks are exhausted (keep an honest **generic** fallback or empty title + column fallback per §7).

---

## 5. Authoritative title construction

### 5.1 Resolver (single logical source for the tool **name** segment)

The executed tool’s LLM-facing **name** segment **MUST** be resolved in this order:

1. **Durable per-row field (replay / export primary):** read the executed tool name from the **tool** history/stream row’s dedicated metadata field (see §5.4 — e.g. **`executedToolName`**). This **MUST** be populated when the row is appended whenever the dispatcher knows **`ToolCall.functionName`**.

2. **Live callback / carrier (preferred on the wire path):** when **`AgentLoop`** (or equivalent) builds the tool-result **`ChatMessage`**, set **`ChatMessage.executedToolName`** (via **`ChatMessage.toolResult(toolCallId, content, executedToolName)`**) from **`ToolCall.functionName`** so **`streamSink`** / **`AgentThing`** and **`AgentMessageStreamAppender`** share one value — **do not** use a parallel thread-local map for this. **Invoke-shaped infotable live downlink** (**`AgentThing.maybeSendInvokeServiceInfotableTableDownlink`**) passes **`toolMessage.getExecutedToolName()`** into **`ParlerInvokeServiceInfotableTableWire`** when non-empty; otherwise it uses the one-arg wire path (body **`tool`**, invoke **`entityName`**/**`serviceName`**, then §7). That path **does not** scan **`AgentToolContext.getParlerActiveMessages()`** for tool identity.

3. **No cross-row replay linkage:** **`AgentMessageStreamHistoryExporter`** does **not** resolve **`tool_call_id`** on the tool row to an **assistant** message's **`tool_calls[].function.name`**. **Unstamped rows** in exported history therefore use step (4) body **`tool`**, then step (5), then §7 only.

4. **Body fallback (legacy and tests):** top-level **`tool`** on the success JSON when it passes **syntactic** tool-name validation (reject blanks, interior whitespace, non-identifier characters per implementor rule). **MUST NOT** treat arbitrary strings as tool names.

5. **True `invoke_service` envelope only:** if the success body carries **`entityName`** and **`serviceName`** (invoke formatter fields) and steps (1)–(4) did not yield a name, the name segment is **`invoke_service`** — §6.2 still applies for suffix assembly from those same fields.

If no name segment is resolved and step (5) does not apply, use §7.

**Compaction note:** LLM-side compaction may rebuild in-memory tool messages with **`ChatMessage.toolResult(id, content)`** (no executed-name field). That is acceptable because **`executedToolName`** is stamped on the **stream row at append time** and replay reads the **row column**, not the post-compaction in-memory **`ChatMessage`**.

**Design note:** Steps (1)–(2) are the primary resolver for new data; (4)–(5) cover legacy / tests. **T5** (§10) **MUST** assert live and replay titles match on the **same** fixture when **`executedToolName`** is set; **T8** covers unstamped rows with valid body **`tool`**. **T6** covers tool-only export tails with a stamped row.

### 5.2 Invariant (replay parity)

**`ParlerToolTableWireUtil`** and every parallel caller **MUST** produce identical **`presentationTitle`** for the same logical tool result. **Self-contained tool rows:** reconstruction **MUST NOT** require the originating assistant row to be present in the same export window when §5.4 is populated.

### 5.3 Discriminator hygiene (body-only suffix templates)

When choosing **which bounded suffix** to append from **JSON shape** once the tool **name** is already known from §5.1 steps (1)–(2) or body **`tool`** / invoke envelope: use **unique** keys per family (e.g. **`historyQueryResource`**, **`ackState`**, stream **`serviceInvoked`**). **Do not** infer the tool **name** from these keys alone. **`thingName` alone is NOT unique** — never choose a template from **`thingName`** presence alone.

### 5.4 Durable row metadata (`executedToolName`)

Persisted **tool** rows carry a **non-LLM-body** field — **`executedToolName`** on **`AgentMessageData`** — populated in **`AgentMessageStreamAppender`** (and any **HITL / synthetic** appenders such as **`HitlSyntheticToolResultAppender`**) at the same time as **`toolCallId`**, from **`ToolCall.functionName`**.

- **Data shape:** update the platform **`AgentMessageData`** DataShape XML **and** any inline fallback shape definition used by the appender.
- **HITL / interrupted siblings:** synthetic tool rows that carry infotable-shaped success **MUST** follow the same stamping rules when a tool name exists; rows that are not table-shaped **MUST NOT** invent titles.

**Legacy:** Cross-row **`tool_call_id` → assistant `tool_calls`** is **not** reconstructed in **`AgentMessageStreamHistoryExporter`**; use body **`tool`** (step 4) for old unstamped stream rows when present.

---

## 6. Suffix rules after the tool name

Let **`NAME`** be the executed tool identifier from §5.1. The full title is **`NAME`** optionally followed by **`:`** and a **bounded structured suffix**, then passed to **`ParlerTableWireFields.putPresentationTitle`** (**R4** / **G4**).

### 6.1 General built-ins and extended tools (not `invoke_service`)

Append a **short** structured summary only when it stays structured (e.g. **`thingName=<value>`**, **`timePreset=<p>`**). Extended tools **MAY** append **`TargetThing.TargetService`** when metadata exists; else **`NAME`** alone.

### 6.2 `invoke_service` (executed tool is `invoke_service`)

**R2 — placeholders:** In patterns below, **`<EntityName>`** and **`<ServiceName>`** mean the **string values** of the JSON fields **`entityName`** and **`serviceName`** (keys are **lowercase** in wire JSON), not XML entity types.

- When both values non-empty: **`invoke_service: <EntityName>.<ServiceName>`** (one dot between value segments).
- When only one value present: **`invoke_service: <EntityName>`** or **`invoke_service: <ServiceName>`**.
- When neither present: **`invoke_service`**.

There is no unprefixed **`EntityName.ServiceName`** title.

### 6.3 Newlines

Emitters **MUST** strip U+000A / U+000D from any assembled title before **`putPresentationTitle`**.

---

## 7. Last-resort fallback

When **no** executed name is available **and** §5.1 step (5) does not apply:

- Emit the literal **`invoke_service`** as the tool-name segment (see **`ParlerTableWirePresentationTitles.infotablePresentationTitle`** total-miss branch), **not** an empty title — so disclosure stays deterministic without inventing a fake tool id.

---

## 8. Contract and versioning

Normative rules for **`presentationTitle`** are in **`CONTRACTS/TABLE_CONTRACT.md`** §3.1.1. **`UI_CLIENT_PROTOCOL.md`** references single-line display.

Further **`parler-agent`** emission changes **MUST** land with any additional contract edits in the same change set per repo rules.

---

## 9. Implementation map (Java)

| Area | Responsibility |
|------|------------------|
| **`AgentMessageData` / `AgentMessageStreamAppender`** | **`executedToolName`**, populated from **`ToolCall.functionName`** when persisting tool rows; keep **`toolCallId`**. |
| **`HitlSyntheticToolResultAppender`** (and related synthetic paths) | Same stamping rules for approved / gated infotable results; no titles for non-tabular synthetic rows (§5.4). |
| **`AgentLoop` / tool-result carrier** | Set **`ChatMessage.toolResult(id, body, ToolCall.functionName)`** so live **`AgentThing`** and **`AgentMessageStreamAppender`** share one carrier; avoid parallel thread-local maps. |
| **`AgentThing`** | **`maybeSendInvokeServiceInfotableTableDownlink`:** pass **`toolMessage.getExecutedToolName()`** into **`ParlerInvokeServiceInfotableTableWire`** when set; else one-arg wire (body **`tool`**, invoke envelope, §7). **No** **`getParlerActiveMessages()`** tool-name scan on this path. |
| **`ParlerInvokeServiceInfotableTableWire`** | Overload(s) **`tableBlockFromInvokeServiceInfotableJson(String body, @Nullable String executedToolName)`**; retain body-only overload for legacy tests. |
| **`ParlerTableWirePresentationTitles`** | Suffix assembly, **`invoke_service:`** prefix branch (§6.2). |
| **`ParlerToolTableWireUtil`** | Thread **`executedToolName`** from row metadata or caller. |
| **`AgentMessageStreamHistoryExporter.tablesFromToolRows`** | Prefer row **`executedToolName`**; else same list-class util as live (body **`tool`**, invoke envelope, §7). **No** cross-row assistant lookup in this exporter (§5.1 step 3). |
| **`ParlerToolStreamTableExportSidecar`** | Third caller of **`tableBlockFromListClassToolJson`** — builds table **only** for export metadata and **discards** `presentationTitle`; document as **no product change required** for disclosure (avoid false “parity gap”). |
| **`LlmToolResultCohortMerger`** | Ensure merged bodies still support §5.1 step (4) when needed; **do not** rely on mutating tool JSON for §5.4. |

---

## 10. Tests

| # | Case |
|---|------|
| T1 | **`query_alert_history`**, **`query_alert_summary`**, **`query_stream_data`**, **`query_property_history`**: `presentationTitle` **does not** equal bare **`invoke_service`** when **`executedToolName`** carrier is set (**`ParlerInvokeServiceInfotableTableWireTest`**, two-arg). |
| T2 | **True `invoke_service`** with **`entityName`** / **`serviceName`**: title **starts with** **`invoke_service:`** and includes **both** values (before truncation). |
| T3 | **Extended tool** infotable result: title **starts with** the extended tool’s registered name (carrier). |
| T4 | **Truncation:** length ≤ 80 UTF-16 units; ends with **`…`** when truncated; **only** via **`putPresentationTitle`**. |
| T5 | **Live == replay:** same body + row **`executedToolName`** — **`ParlerToolTableWireUtil`** title equals **`tablesFromToolRows`** (**`AgentMessageStreamHistoryExporterTest`**). |
| T6 | **Export window:** tool row **without** assistant row in tail → title still correct when **`executedToolName`** stamped. |
| T7 | **Negative:** invalid body **`tool`** → no spoofed title. |
| T8 | **Legacy:** unstamped row → valid body **`tool`** path (history exporter has no cross-row lookup). |
| T9 | **HITL / synthetic:** **`HitlSyntheticToolResultAppender`** sets **`ChatMessage.executedToolName`** (gated + interrupted siblings). |
| T10 | **`ParlerInvokeServiceInfotableTableWireTest`** (and any test expecting unprefixed **`Entity.Service`**) asserts the **`invoke_service: …`** prefix. |
