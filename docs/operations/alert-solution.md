# Alert query & acknowledgement — complete solution

---

## 1. Scope

**In scope:** How the agent reads current alert state, reads alert history in a bounded time window, and acknowledges alerts — without over-broad or ambiguous platform calls. How to compose those steps with generic invocation, entity resolution, and cached tabular follow-up.

**Out of scope:** Permission, visibility, or organization misconfiguration. This solution assumes the caller has appropriate runtime access to every Thing, property, and `Resources["AlertFunctions"]`. Operational ACL topics are handled outside this design.

---

## 2. Problem

For DataInsight-style conversations, three alert operations are high-frequency and foundational:

1. Read the current alert state of a Thing.
2. Read the alert history of a Thing within a time window.
3. Acknowledge alerts safely.

The platform already supports all three, but the raw service surface is easy for a model to misuse:

- Wrong service (summary vs history vs global vs Thing-local variants).
- Wrong parameter names (`source` vs `name`, `property` vs `sourceProperty`).
- Confusing AlertSummary (current in-memory snapshot) with AlertHistory (stream over time).
- Unbounded history reads.
- Over-broad acknowledge (property-wide or thing-wide when the user meant one row).

The solution optimizes for: low error rate on the common paths; explicit semantics; reuse of existing agent patterns for cached tabular results and follow-up analysis; no alert-specific over-design for cross-Thing cohort questions.

---

## 3. Design principles

**Keep the primary surface small.** Three tools — `query_alert_summary`, `query_alert_history`, `acknowledge_alerts` — are the default path for the three common operations. Generic invocation remains as a fallback, not the normal route.

**Separate summary from history.** Current alert state and historical alert events are different data products with different shapes, different guards (time bounds on history), and different user meaning. They must not be merged into one tool with a `mode` switch.

**Make bulk actions explicit.** Acknowledge must default to the narrowest safe behavior. Bulk actions are valid but must be explicit, not inferred from vague language.

**Put complexity in executor logic, not model memory.** The model should not directly reason about service variants, parameter name differences, or low-level query wiring. The agent layer standardizes on a single internal calling convention and hides the raw surface differences. Executors preserve platform semantics rather than partially reimplementing them in prompt text, and always return explicit metadata about what was actually done. The assistant should never need to reconstruct effective time window, limit, or ack mode from memory.

**Treat cohort questions as composition problems.** "Show alerts for all robots in factory A" combines cohort resolution and alert reads. After resolving the Thing list, prefer **one `query_alert_summary` call with `thingNames[]`** (up to **25** names) so cross-Thing comparison returns **`ALERT_SUMMARY_MULTI`** rollups instead of N separate tool-result pairs. Split batches or narrow scope when the cohort is larger. History and acknowledge remain single-Thing tools.

---

## 4. Platform semantics

### 4.1 Summary vs history — two different data planes

| Concept | Meaning | Resource operation |
|---------|---------|----------------------------|
| **Summary** | In-memory active alert snapshot (`AlertSummary` shape). After a platform restart, the summary is empty until alerts fire again. Bounded in size (platform uses a concurrent map with a configurable cap, typically on the order of tens of thousands of entries). Not the durable history stream. | `QueryAlertSummaryForThing` |
| **History** | Time-ordered stream of past alert events / transitions. Requires a time window and a max row cap. Survives platform restarts. | `QueryAlertHistory` |

"What is firing now?" → summary. "What happened between time A and B?" → history. An empty summary is not proof that nothing ever fired.

### 4.2 Resource vs Thing-local paths

Prefer `Resources["AlertFunctions"]` with an explicit `thingName` argument — stable contract. Resource `QueryAlertHistory` applies platform stream filtering (`isFiltered` / summary-manager integration); Thing-local `QueryAlertHistory` uses a different stream path. If behaviour differs, trust the resource-backed wrapper as the agent default.

### 4.3 Ack filter pitfall

Platform exposes `onlyAcknowledged` and `onlyUnacknowledged` booleans. When both are true, `onlyUnacknowledged` wins (if/else-if chain). Tool surface: one enum `ackState` = `all` | `acknowledged` | `unacknowledged`. Never expose two independent booleans.

### 4.4 Ack semantics

- `AcknowledgeAlertFromSummary`: AlertSummary-shaped INFOTABLE input; acknowledges by `source` + `sourceProperty` + `name` per row — **row-level** precision.
- `AcknowledgeAlert`: `source` + `sourceProperty` — acknowledges **every** alert on that property (broad). Without `sourceProperty`, acknowledges **everything** on the Thing. Edge cases (missing property, no alerts on property) depend on platform version; normalize errors in the executor.

Default user language ("ack this") maps to row-level first. Property-wide is a separate explicit mode.

### 4.5 History QUERY power

History supports rich ThingWorx QUERY JSON (filters, sorts, nesting). Powerful and easy to get wrong. Prefer typed tool parameters for common filters; expose raw QUERY JSON only as `advancedQuery` or via generic invoke.

---

## 5. Tool contracts

All three tools map to `Resources["AlertFunctions"]` (not Thing-level services). Executors resolve the resource, build `ValueCollection` from tool params, call `processAPIServiceRequest` (the resource is looked up with the user's Visibility and the call needs the user's ServiceInvoke), and format the result as standard INFOTABLE JSON (`columns[]` with `baseType` + `rows[]`). Results flow through the existing `type: "table"` wire and tabular pipeline.

Executors should narrow, not widen: typed filters become explicit service inputs or well-formed QUERY fragments; `specific_alerts` stays precise; ambiguous acknowledge requests must not widen into bulk behavior. Pass through platform filtering order; do not re-implement platform semantics in prompt text alone.

### 5.1 `query_alert_summary`

Current active alert state for one or more Things.

**Maps to:** `QueryAlertSummaryForThing` (server-side per-Thing loop when N≥2)

| Param | Type | Required | Default | Platform mapping |
|-------|------|----------|---------|------------------|
| `thingNames` | STRING[] | yes | — | `name` (THINGNAME) per element |
| `ackState` | ENUM | no | `all` | `onlyAcknowledged` / `onlyUnacknowledged` |
| `propertyName` | STRING | no | null | `property` |
| `alertName` | STRING | no | null | QUERY EQ on `name` |
| `alertType` | STRING | no | null | QUERY EQ on `alertType` |
| `priorityMin` | INTEGER | no | null | QUERY GE on `priority` |
| `priorityMax` | INTEGER | no | null | QUERY LE on `priority` |
| `sort` | ENUM | no | `default` | `default` / `timestamp_asc` / `timestamp_desc` / `priority_asc` / `priority_desc` — merged into the typed QUERY; mutually exclusive with `advancedQuery.sorts` |
| `limit` | INTEGER | no | 100 | `maxItems` (hard cap 500) |
| `advancedQuery` | STRING (JSON) | no | null | `query` — merged AND with typed filters |

**Why this shape:**
- `ackState` defaults to `all` to avoid silent data loss from hiding acknowledged-but-still-relevant current alerts. The model narrows to `unacknowledged` when the user explicitly asks for active-only.
- Common filters (`alertName`, `priorityMin`, etc.) are first-class typed fields so the model does not need raw QUERY JSON for everyday work.
- `advancedQuery` exists for exceptional cases but is not the primary story.
- `sort: default` preserves the platform ordering (timestamp descending).

**Result metadata:** N=1 — flat **`INFOTABLE`** (`thingName`, `ackState`, …). N≥2 — **`ALERT_SUMMARY_MULTI`** (`byThing[]`, `completeness`, counters). Normative detail: **`CONTRACTS/TAXONOMY_RESOLVER.md`** §7.2 and **`docs/operations/multi-thing-alert-query.md`**.

### 5.2 `query_alert_history`

Alert event timeline for one Thing within a bounded time window.

**Maps to:** `QueryAlertHistory`

| Param | Type | Required | Default | Platform mapping |
|-------|------|----------|---------|------------------|
| `thingName` | STRING | yes | — | `name` (THINGNAME) |
| `startTime` | STRING (ISO-8601) | no | server default (7 days before `endTime`) | `startDate` |
| `endTime` | STRING (ISO-8601) | no | now | `endDate` |
| `calendarPhrase` / `relativeDuration` | STRING | no | null | Server-resolved window (same convention as other time-bounded tools) |
| `timePreset` | ENUM | no | null | `last_1h` / `last_24h` / `last_7d`; cannot be combined with `startTime` / `endTime` (`INVALID_TIME_PRESET` on bad values) |
| `alertName` | STRING | no | null | QUERY EQ on `name` |
| `propertyName` | STRING | no | null | QUERY EQ on `sourceProperty` |
| `alertType` | STRING | no | null | QUERY EQ on `alertType` |
| `priorityMin` | INTEGER | no | null | QUERY GE on `priority` |
| `priorityMax` | INTEGER | no | null | QUERY LE on `priority` |
| `order` | ENUM | no | `newest_first` | `oldest_first` / `newest_first` → `oldestFirst`; an error when it disagrees with an explicit `oldestFirst` |
| `oldestFirst` | BOOLEAN | no | false | `oldestFirst` (legacy form of `order`) |
| `limit` | INTEGER | no | 100 | `maxItems` (hard cap 500) |
| `advancedQuery` | STRING (JSON) | no | null | `query` — merged AND with typed filters |

**Why this shape:**
- History queries without enforced time discipline are the easiest way for the model to do the wrong thing.
- Returning the applied window (`appliedStartTime`, `appliedEndTime`) removes ambiguity in follow-up turns.
- Typed filters reduce dependence on raw QUERY JSON.
- Use `startTime` / `endTime` naming consistently with other time-bounded agent tools so the model learns one pattern.

**Time discipline:** The tool must never execute an unbounded history read. If `startTime` and `endTime` are both omitted, the executor applies a documented default window. The response always returns `appliedStartTime` and `appliedEndTime`.

**Result metadata:** `thingName`, `appliedStartTime`, `appliedEndTime`, `oldestFirst`, `limitApplied`, row count.

### 5.3 `acknowledge_alerts`

Acknowledge alerts — row-level by default, property-wide only when explicit.

**Maps to:** `AcknowledgeAlertFromSummary` (default) and `AcknowledgeAlert` (bulk).

| Param | Type | Required | Default | Notes |
|-------|------|----------|---------|-------|
| `thingName` | STRING | yes | — | |
| `mode` | ENUM | no | `specific_alerts` | `specific_alerts` / `property_all` |
| `propertyName` | STRING | no | null | Required for `property_all`; narrows `specific_alerts` |
| `alertName` | STRING | no | null | For `specific_alerts`: ack the named alert |
| `message` | STRING | no | null | Ack message |

**`specific_alerts` (default):**
1. Requires `propertyName` (with or without `alertName`). Without it: reject with error.
2. Internally queries the current summary to get matching rows, builds AlertSummary-shaped INFOTABLE, and calls `AcknowledgeAlertFromSummary` — row-level precision.

3. Narrow shortcut: when the summary probe finds **exactly one** matching unacknowledged row **and** the probe was not narrowed by `alertName`, the executor calls `AcknowledgeAlert` with `source` + `sourceProperty` instead (`AlertNarrowAckPolicy`); otherwise other unacknowledged alerts on the same property could be widened into the ack. This logic stays inside `specific_alerts` — never a separate broad mode.

**`property_all`:**
1. Requires `propertyName`.
2. Calls `AcknowledgeAlert` with `source` = `thingName`, `sourceProperty` = `propertyName`.
3. Acknowledges ALL alerts on that property — deliberate bulk action.

**`thing_all` is not exposed.** Too easy to trigger from vague language.

**Result metadata:** `mode`, `thingName`, `propertyName`, `requestedCount`, `acknowledgedCount`, skipped/failed items. The assistant can explain partial success without guessing.

---

## 6. Generic invoke (escape hatch)

Generic service invocation remains necessary for:

- Purge, delete-from-summary, rare diagnostics.
- Highly custom QUERY not worth first-class parameters.
- Cohort and membership resolution (tags, orgs, custom tables, mashup services) when no built-in entity listing matches the tenant.
- Version-skew where the installed platform differs from shipped wrappers.

It must not be the default for current summary reads, historical reads, or normal acknowledge actions.

---

## 7. Routing guide and Skill

The alert prompt content is delivered to the agent via a `GetAlertPrompt` service. The default stable prompt carries a concise block derived from §7.1 and the §7.2 field dictionaries; the full skill body below is kept for repository skills. See §10 item 5 for the override mechanism.

### 7.1 Routing guide text

```
Alert state, history, and acknowledgement
- Current active alerts on one Thing: query_alert_summary. Default returns all (acked and
  unacked); narrow with ackState "unacknowledged" for active-only. Summary is in-memory —
  may be empty after restart. For historical record, use query_alert_history.
- Alert timeline / past events: query_alert_history. Always provide startTime and endTime
  (ISO-8601); the tool applies a default window if omitted but results may be narrower than
  expected.
- Acknowledge: acknowledge_alerts. Default mode specific_alerts requires propertyName (and
  optional alertName); property_all requires explicit user intent. Never default vague
  "ack it" to property_all.
- Complex filters (composite AND/OR, non-standard fields): use invoke_service on
  Resources["AlertFunctions"] with Skill guidance for QUERY JSON.
- Do not use invoke_service for the three common cases above.
- Cross-thing: resolve the Thing cohort first, then call alert tools per Thing. If the
  cohort exceeds ~5 Things, narrow or use a server-side aggregate service.
```

### 7.2 Skill content (`_skill_alert_query`)

The Skill teaches the model how to choose the right tool and when to stop and narrow the task. It should not read like a query grammar manual.

**Decision tree:**
- "What's active / unacknowledged / current?" → `query_alert_summary`
- "What happened / timeline / during this period?" → `query_alert_history`
- "Ack / silence / mark as handled" → `acknowledge_alerts`
- Empty summary after restart is normal, not a permission error
- History is the persistent record; summary is the volatile snapshot

**Field dictionaries:**

AlertSummary: `timestamp`, `source`, `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `ackTimestamp`, `ack`, `ackBy`, `duration`

AlertHistory: `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `eventName` + stream implicit: `source`, `timestamp`

Do not invent field names. These are the only valid fields for QUERY construction and result interpretation.

**QUERY examples (for `invoke_service` fallback):**

These should be copied from integration-style tests or verified manually against the installed server.

```json
{"filters": {"type": "GE", "fieldName": "priority", "value": 5}}

{"filters": {"type": "AND", "filters": [
  {"type": "GT", "fieldName": "priority", "value": 1},
  {"type": "EQ", "fieldName": "alertType", "value": "Above"}
]}}

{"sorts": [
  {"fieldName": "priority", "isAscending": false},
  {"fieldName": "timestamp", "isAscending": false}
]}
```

**Ack warnings:**
- `specific_alerts` = row-level precision (safe default)
- `property_all` = every alert on the property (explicit intent only)
- `AcknowledgeAlert` without `sourceProperty` acknowledges everything on the Thing — the executor must never call it this way
- Never map vague "ack it" to `property_all`
- `thing_all` is not available

**Fan-out guidance:** Prefer **`thingNames[]`** batches (max **25**) over per-Thing summary loops. History and acknowledge remain scalar **`thingName`**. When more than **25** Things need summary reads, batch or narrow scope.

---

## 8. Composition playbooks

### One Thing, current state

Call `query_alert_summary` with optional `ackState` and `propertyName`. No new helper needed. Empty summary after restart is normal; use history for the past.

### One Thing, compare two windows

Call `query_alert_history` once per window with explicit bounds. Cache both results. Compare through existing cached-tabular analysis (tabulate / summarize / chart). Do not merge unrelated time ranges into a single history call.

### Many Things by attribute (factory, asset type)

1. Resolve the Thing cohort (entity tools / taxonomy / tags / generic invoke — tenant-specific).
2. Call **`query_alert_summary`** with **`thingNames[]`** for the resolved canonical names (batch when >25).
3. Combine via rollup fields, cached tabular / chart steps, or drill-down via per-Thing **`cacheId`** when needed.

The alert layer owns step 2. Multi-Thing summary is **`query_alert_summary`** with **`thingNames[]`**, not a separate fleet tool.

### Many Things by membership (region, org, roll-up)

Same pattern: resolve membership to a Thing list, then **`query_alert_summary`** with **`thingNames[]`**. Membership is not an alert field.

### Cross-cutting verdict

The three-tool split is sufficient for single-Thing status and bounded history. Multi-Thing **summary** comparison uses **`thingNames[]`** on **`query_alert_summary`** (see **`docs/operations/multi-thing-alert-query.md`**). A separate fourth "fleet alert" tool is not required for summary comparison.

---

## 9. Pipeline reuse

Alert reads are rarely the end of the conversation — they are the first step before analysis.

All alert read tools return standard INFOTABLE-shaped JSON, the same shape as `invoke_service` INFOTABLE results. Small results inline; large results cached with stable cache keys. This makes alert results immediately compatible with:

- `type: "table"` wire rendering
- History `tables[]` replay
- Paging cached results
- `tabulate_cached_result` (sort, Top-N, group)
- `summarize_cached_result` (column stats)
- `build_chart_from_tabular_result`
- CSV file export
- PASSWORD column masking

No alert-specific rendering or aggregation tools needed. Fetch first, analyze cached results next — there is no dedicated "diff alerts" or "fleet alert" tool.

---

## 10. Product decisions

1. **`ackState` default.** `all` (avoid silent data loss); the model narrows to `unacknowledged` when the user asks for active-only.
2. **`AcknowledgeAlert` inside `specific_alerts`.** Used only under the single-row, no-`alertName` rule of §5.3, which cannot widen to all alerts on the property.
3. **`thing_all` exposure.** Not exposed.
4. **Default history window.** 7 days.
5. **Alert prompt delivery and override (`GetAlertPrompt`).**

   The three alert tools are built-in — every DataInsight scenario needs alert support. The question is how the alert-specific system prompt (§7 routing guide + skill content) reaches the agent.

   **Mechanism:** A `GetAlertPrompt` service with no parameters, returning a single STRING containing the complete, ready-to-use alert system prompt section.

   **Default implementation:** Returns the concise `## Alerts` block (`AlertPromptDefaults.DEFAULT_ALERT_PROMPT_MARKDOWN`): the §7.1 routing rules, acknowledgement scope, the `invoke_service` fallback, and the §7.2 field dictionaries plus the QUERY predicate/sort shape, without the decision tree or the standalone QUERY examples. The full §7.2 skill body remains available as `AlertPromptDefaults.SKILL_ALERT_QUERY_MARKDOWN` for repository skills. Out of the box, the agent has a working alert prompt without any App-level configuration.

   **Override:** App developers can override `GetAlertPrompt` to return a completely different prompt section — for example, restricting ack modes, adjusting fan-out thresholds, adding tenant-specific QUERY examples, or replacing the field dictionaries for a custom alert schema. The override **replaces** the default content entirely (not merged or appended). Ensuring format completeness is the App developer's responsibility.

   **Agent behavior:** The agent calls `GetAlertPrompt` during initialization, takes whatever STRING is returned, and places it at the designated position in the system prompt. It does not inspect, validate, or merge the content. If the service returns empty, no alert prompt section is injected.

   This differs from structured taxonomy configuration, which defaults to missing until operators ship **`identity-types.json`**. `GetAlertPrompt` ships with a working default because alert support is universally required.

When platform defaults, QUERY evaluation, or error codes differ by platform version, the installed platform behaviour wins over this document.

---

## 11. Implementation details

| Item | Behavior |
|------|----------|
| **`timePreset` for history** | `last_1h` / `last_24h` / `last_7d`, resolved offline by `AlertHistoryTimeRange`; mutually exclusive with explicit `startTime` / `endTime`. Without any bound the default 7-day window applies and the applied bounds are returned. |
| **`order` enum vs `oldestFirst` boolean** | Both accepted (`AlertHistorySortOrder`); `order` (`oldest_first` / `newest_first`) is preferred and must agree with an explicit `oldestFirst`. |
| **`sort` on summary** | `default` / `timestamp_asc` / `timestamp_desc` / `priority_asc` / `priority_desc`, merged into the typed QUERY (`AlertQueryFilterBuilder`); mutually exclusive with `advancedQuery.sorts`. |
| **Direct row references vs internal re-query for `specific_alerts`** | Always re-queries the current summary (`AlertSummaryAckProbe`); row identifiers from earlier results are not accepted. |
| **Narrow `AcknowledgeAlert` inside `specific_alerts`** | Only when exactly one matching unacknowledged row exists and the probe was not narrowed by `alertName` (`AlertNarrowAckPolicy`). |
| **Ack error normalization** | Platform exceptions on the probe and ack paths become stable tool JSON (`status: error`, `code`, `message`) with codes `PLATFORM_ALERT_PERMISSION`, `PLATFORM_ALERT_NOT_FOUND`, `PLATFORM_ALERT_BAD_INPUT`, or `PLATFORM_ALERT_SERVICE_ERROR` (`AlertAcknowledgePlatformErrors`). |
| **`isFiltered` behavior on resource vs Thing-local path** | The executors use the resource path (§4.2) only. |

---

## 12. Non-recommendations

- Do not rely on generic invoke alone for the three high-frequency operations.
- Do not default vague "ack" to property-wide or thing-wide.
- Do not expose two summary ack booleans to the model.
- Do not make raw QUERY JSON the primary input for history.
- Do not allow unbounded history reads.
- Do not introduce an MCP Resource layer only for alerts.
- Do not build alert-specific compare, fleet, or cohort tools.
- Do not build alert configuration, real-time subscription, or arbitrary multi-sort controls.
- Do not build a merged `summary|history` read tool.

---

## 13. Summary

The solution is: three dedicated alert tools; narrow schemas with typed common filters; enforced time bounds for history; precise acknowledge as the default; generic invocation only as fallback; a `GetAlertPrompt` service with a working default and full App-level override; standard cached-tabular reuse for analysis; no alert-specific overreach for cohort problems; skill playbooks for multi-step flows.

That is the smallest solution that is still complete. It addresses the real failure modes, preserves platform semantics, fits the existing agent architecture, and leaves cross-Thing scale problems to the correct abstraction layer instead of forcing them into the alert tools.

---

## 14. HITL and `acknowledge_alerts`

On Parler AlwaysOn, `set_property_value` and `invoke_service` enqueue **human-in-the-loop** approval (`approval.required` / `SubmitApprovalDecision`) before execution. The dedicated **`acknowledge_alerts`** built-in does **not** use that gate; its mitigations are schema-narrowing (`specific_alerts` default, explicit `property_all`, batch limits, prompt routing), not an approval UI. Acknowledgement follows §5.3.
