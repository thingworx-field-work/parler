# Parler time and time zones — Implementation spec (Widget ↔ Java Agent)

**Audience:** **`parler-ui`** (`<parler-ui>`) widget and **ThingWorx `parler-agent` (Java Agent extension)**.  
**Scope:** **User time-zone context** and **UTC execution semantics** on the AlwaysOn / logical `chat.request` path.  
**Non-goals:** Unifying arbitrary **REST** time fields, multi-tenant deployment-level time-zone policy, and other HTTP APIs not fixed in this file.

---

## 1. Problem and goals

1. **Interpretation:** User fuzzy times (e.g. `today`, `8am`, `last 30 minutes`) must be resolved under **clear, testable** rules and **the user’s current time zone**.  
2. **Execution:** **ThingWorx / history / stream / cache** query boundaries are **UTC only**; must not change because of local UI display.  
3. **Presentation:** Chart axes and helper copy may format in **browser local time**, but “display wording” must not be mistaken for “query semantics”.  
4. **Alignment:** **Parler widget** time-zone signals must match **Java Agent** signals under **one contract** (field names, when sent, default behavior).

---

## 2. Core principles (must follow)

**Input in local time, execute in UTC, present in local time.**

| Stage | Meaning |
|------|------|
| **Interpret** | Unless the user specifies another time zone, interpret fuzzy phrases using the **IANA time zone from the session**. |
| **Execute** | Once a **concrete time window** exists, convert immediately to **UTC `start` / `end`** (or a single Instant), then call platform and data layers. |
| **Present** | Times in results may follow local convention; **ChartBlock** time-like dimensions’ **on-wire truth** stays **UTC ISO** (e.g. `…Z`), per [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md). |

**Hard boundaries:**

- **A — UTC execution:** The “time basis” for history queries, filters, sorts, and cache comparison is always UTC.  
- **B — Browser time zone** is only for **interpretation** and **presentation**, not “query the DB by local wall clock”.  
- **C — No LLM as authoritative UTC:** The model may help intent and disambiguation; **final UTC windows** must come from **one** unit-testable parser (Agent library or dedicated tool).  
- **D — Explicit time zone wins:** `UTC`, named zones, ISO with offset, etc.—**explicit user** expressions beat browser default (order §4).  
- **E — IANA as primary signal:** Sessions should carry IDs like `Europe/Berlin`; **must not** use only `+08:00` instead of region rules (DST boundaries go wrong). Offset-only is **degraded**: log and move to IANA when possible.

---

## 3. Three-layer model

### 3.1 Interpretation layer

**Input:** User natural language, **`user_timezone` (IANA)**, **current UTC instant**, **current user local instant** (derived from same IANA + UTC).  
**Output:** **Structured intermediate form** for a **closed or half-open time window** under that IANA (§6), or **errors / follow-ups** for the user (unparseable, missing tz with relative time, etc.).

### 3.2 Execution layer

**Input:** Window from interpretation with **time-zone bounds** (or absolute Instant).  
**Output:** **UTC** bounds; **only UTC** enters `QueryPropertyHistory`, custom cache keys, stream filters, etc.

### 3.3 Presentation layer

Charts, Markdown restating windows: prefer **local** narrative; if ambiguous, lightly note “interpreted in your machine / bind time zone”. **Do not** rewrite an already-issued query based on display.

---

## 4. Resolution priority (implementations must match)

1. User explicit **UTC** → interpret that fragment in UTC.  
2. User explicit **named zone / ISO with offset / `Z`** → interpret with that explicit semantics (allowed syntax should be listed in implementation notes or Agent release notes; avoid fuzzy abbreviations).  
3. Else → use **`payload.user_timezone`** (IANA).  
4. **If there is neither explicit time zone nor `user_timezone`, and context-dependent relative time appears** → **must not** silently fall back to server OS time zone; behavior per [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) § `payload.user_timezone` (reject or ask for absolute time / zone).

---

## 5. LLM and time

- **Injection:** Stable interpretation rules live in the leading system prompt. Each provider round receives one `[Parler server time context]` suffix row: **`now_utc`** is always present; **`now_local`** and **`user_timezone`** are present only when the IANA id is valid. `ParlerTimeAnchor` formats the values and `LlmUtcClockInjector` owns the ephemeral row lifecycle.
- **Model may:** Decide which duration is meant, whether to call a time-parse tool, output **structured slots** (not hand-written UTC timestamps).  
- **Model must not:** Be the **sole** source of truth for final `startUtc` / `endUtc`; DST and “today at midnight” must be computed by parser code.

---

## 6. Intermediate representation (implementation convention)

Fix one shape on the Agent side (field names may follow code, semantics must match):

- `interpretation_timezone`: IANA (zone used to interpret the window)  
- `start_local`: ISO-8601 **with offset** or explicit **Z**, window start  
- `end_local`: same  
- `end_exclusive`: boolean, whether `[start, end)` (**must be consistent repo-wide**)

A **single** module converts this object to **`start_utc` / `end_utc`** for all tools.

---

## 7. Widget ↔ Java Agent alignment checklist

| Item | Widget (`parler-ui`) | Java Agent (`parler-agent`) |
|----|------------------------|--------------------------------------|
| IANA source | `Intl.DateTimeFormat().resolvedOptions().timeZone` (or host equivalent) | Parse **`userTimezone`** (camelCase) from ThingWorx service args; same semantics as logical JSON **`user_timezone`** |
| When to send | **Every** `chat.request` (or TW-equivalent user-turn payload) **should include when available** | `AgentThing` passes the canonical value into `AgentLoop` as explicit turn context; the time injector does not read a thread local |
| When missing | Text may still be sent; **relative time** returns error/clarification (§4 item 4) | Must not pretend server local time is the user time zone |
| UTC queries | N/A | All TW query bounds use converted UTC only |
| Charts | Localized axis ticks | `ChartBlock` time dimension still UTC ISO; matches CHART_CONTRACT |

**ThingWorx integration:** Optional **`userTimezone`** (IANA string) on **`ParlerGateway.SubmitUserPrompt`** and **`AgentThing.ParlerStreamToRemoteThing`** aligns with logical payload **`user_timezone`**; see [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md), [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md).

---

## 8. Observability and troubleshooting (strongly recommended)

For “time-related” tool calls (debug level): log **original user phrase**, **IANA used**, **local window**, **UTC window**. Off-by-one-hour issues are hard to assign without this quadruple.

---

## 9. Common failure modes (forbidden)

1. UI shows local clock but treats English **`today`** as UTC day boundaries for queries.  
2. Widget sends time zone but Agent tool layer ignores it and uses defaults.  
3. Using offset instead of IANA and querying the wrong day on DST change days.  
4. Treating ChartBlock X as “local strings” and shifting twice (**on-wire is still UTC**).  
5. Silently using server time zone for **`yesterday`** etc. when `user_timezone` is missing.

---

## 10. Contract fields

Field tables and examples for **`user_timezone` / `userTimezone`** are authoritative in [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) and [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md). This file keeps principles and architecture.

---

## 11. Relationship to REST

**REST query parameters, headers, and time-zone unification** are out of scope here; any such interface must follow §2–§4 and keep **ThingWorx execution UTC** unchanged.

---

## 12. References (do not replace this document)

- [`CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md) — UTC recommendation for time-like `x`  
- [`agent-alwayson.md`](./agent-alwayson.md) — AlwaysOn payload and `conversation_id`  
- [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) — **`user_timezone`** and ThingWorx **`userTimezone`**  
- [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) — Widget collection and uplink obligations
