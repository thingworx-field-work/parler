# Time interpretation (Parler agent)

This document defines how Parler interprets time in agent tool calls: the natural-time fields curated
tools accept, how Java resolves them to UTC bounds, the rejection of unsafe DATETIME literals, the
error envelope, and the metadata that tells users and follow-up tools which window was actually applied.
The agent must not mishandle relative time, must not silently drop a time constraint after a parse
failure, and must not present a result as correct when the time semantics changed without saying so.

## 0. Product context

1. **Audience — end-user chat.**
   Users say "last 30 minutes" or "yesterday"; they do not think in raw platform parameter bags. Parler
   hides fragile DATETIME string conventions behind structured tool arguments and clear error messages
   (for example: the time window cannot be dropped; ask the user or fix the input), not integrator-style
   workarounds in chat.

2. **Runtime — in-process Java on ThingWorx.**
   Parler resolves time in pure Java (offline unit-testable), inspects `ServiceDefinition` parameter
   `BaseTypes`, builds `ValueCollection` / `DatetimePrimitive` / `JSONPrimitive` inputs, and attaches
   metadata without an extra HTTP hop. Declared parameter types come first: `ServiceDefinition.getParameters()`
   identifies real `DATETIME` fields; the §8 name heuristics apply only to untyped JSON / VARIANT
   parameters.

## 1. Why this matters

The failure mode this design prevents:

1. A user asks for a time-bounded result, such as "last 30 minutes".
2. The agent sends relative text such as `now-5m`, `now`, `last 30m`, or a wall-clock phrase directly into
   a ThingWorx DATETIME parameter.
3. ThingWorx rejects the value, or the tool cannot parse it.
4. The agent retries without the time condition.
5. The final answer looks successful but is semantically wrong.

This is worse than an ordinary tool error. A failed discovery query usually looks wrong; a dropped time
window produces a plausible but false result.

## 2. Components

| Area | Behavior |
|------|----------|
| Timezone contract | [`../architecture/times-solution.md`](../architecture/times-solution.md), `CONTRACTS/API_CONTRACT.md`, and `CONTRACTS/UI_CLIENT_PROTOCOL.md`: input in local time, execute in UTC, present in local time. |
| Client context | `userTimezone` is accepted by `ParlerGateway.SubmitUserPrompt` and `AgentThing.ParlerStreamToRemoteThing`; invalid IANA ids are logged and ignored. |
| Per-round LLM time context | `ParlerTimeAnchor.formatTimeValues` supplies `now_utc` plus, for a valid zone, `now_local` and `user_timezone`; `LlmUtcClockInjector` materializes one framed time row per round. Stable guidance is `ParlerTimeAnchor.STABLE_TIME_GUIDANCE` in the leading prompt (§9). |
| Shared resolver | `ParlerTimeResolver` (package `com.thingworx.things.agent.time`): duration grammar, closed-open relative ranges, English calendar-day resolution, unsupported-phrase rejection (§4). |
| Built-in natural-time fields | `BuiltInToolNaturalTimeWindow`: `calendarPhrase` / `relativeDuration` validation, mutual exclusion, resolution (§4.5). |
| Explicit ISO bounds | `ToolJsonTimeBounds` (JSON shape), `ExplicitIsoTimeBounds` (ISO-8601 parse and pairing). |
| Alert history window | `AlertHistoryTimeRange`: explicit ISO, `timePreset`, implicit default window, natural-language sources. |
| Applied-window metadata | `ParlerAppliedTimeWindowJson` writes `applied_time_window` (§6). |
| Error envelopes | `BuiltInToolTimeErrorJson` (natural-time and bound errors), `InvokeServiceErrorJson` (`UNSUPPORTED_RELATIVE_LITERAL`) (§7). |
| Generic service defense | `InvokeServiceDatetimeLiteralDefense`, `InvokeServiceArgumentCoercion`, `UnsupportedRelativeLiteralException` (§5, §8). |
| Custom `_tool_*` services | `CustomToolHarvester`, `CustomToolDateTimePairResolver`, `CustomToolNaturalTimeException` (§12). |

## 3. Design principles

- **The LLM identifies intent; Java computes the window.** The model may pick a structured field such as
  `relativeDuration: "30m"`; it is not the authoritative UTC calculator for relative time. Relative
  phrases, local calendar boundaries, and daylight-saving transitions are easy to get subtly wrong.
- **A small grammar instead of a growing preset list.** Presets are convenient aliases, but "last 30m"
  must not be approximated by the nearest preset. The `<positive-integer><unit>` grammar covers any span.
- **One captured anchor per tool call.** Each resolution uses one `nowUtc` instant.
- **Reject, never approximate.** Unsupported phrases produce a stable error code; they are never widened
  or mapped to a nearby duration (for example, "this month" is never treated as `30d`).
- **Echo the applied window.** Tools that resolve time return the window they executed (§6).

## 4. Grammar and semantics

### 4.1 Resolver

`ParlerTimeResolver` is independent of ThingWorx runtime services. It takes an injected
`java.time.Instant nowUtc` and, for calendar phrases, a `java.time.ZoneId` derived from the host
`user_timezone`. Tool executors call it with the platform clock (`Instant.now()`); tests inject fixed
instants. Existing ThingWorx call sites convert to Joda `DateTime` only at the boundary
(`DatetimePrimitive`, `AlertHistoryTimeRange`).

The resolver's relative-range entry point can also take an explicit anchor instant (`anchor_utc`)
instead of `now`; passing both is rejected with `ANCHOR_AND_ANCHOR_UTC_CONFLICT`. Curated tools anchor
relative durations at the call's clock; `build_history_overlay_chart` passes its shared `anchorTime`
(shifted by a series' `anchorOffset`) as that clock.

### 4.2 Duration grammar and calendar-day phrases

Duration grammar:

```text
<positive-integer><unit>
```

| Unit | Meaning |
|------|---------|
| `s` | seconds |
| `m` | minutes |
| `h` | hours |
| `d` | days |
| `w` | weeks, exactly 7 days (not calendar-week boundaries) |

- `mo` and `y` are calendar-relative and are rejected with `UNSUPPORTED_UNIT`.
- Zero, negative, empty, or otherwise malformed values are rejected with `INVALID_DURATION_GRAMMAR`.
- Magnitudes that overflow or leave the `Instant` range are rejected with `INVALID_DURATION_GRAMMAR`; no
  unchecked exception escapes the resolver.
- A relative duration resolves to the closed-open range `[now − duration, now)`.

**English calendar day (`today` / `yesterday` / `tomorrow`).** With `nowUtc` and a valid `user_timezone`
IANA id, `ParlerTimeResolver.tryResolveLocalCalendarDayEnglish` resolves the phrase to the closed-open
range `[start of that local calendar day, start of the next local day)` in UTC. This uses `java.time`
zone rules, so a day across a daylight-saving transition is 23 or 25 hours, never a naive `24h`. Rules:

- The phrase must name exactly one of the three words (word-boundary match). Mixing two ("yesterday and
  today") is `UNSUPPORTED_CALENDAR_PHRASE`.
- Surrounding plain context is allowed (possessives, "show … alerts").
- Wording that also implies wall-clock or finer calendar semantics is rejected with
  `UNSUPPORTED_CALENDAR_PHRASE` rather than widened to a full day: the §4.3 phrases, `at <digit>`, clock
  fragments such as `14:30`, `after|before|since|until|from <digit>`, `between <digit>`, and a day word
  combined with `morning` / `afternoon` / `evening` / `night` (`rejectIfLocalCalendarDayPhraseHasUnsupportedResidue`).
- Without a valid zone the tool returns `MISSING_OR_INVALID_USER_TIMEZONE` with
  `rejectedParameter: "calendarPhrase"`, so the model can switch to `relativeDuration` or explicit ISO
  bounds (§7).

### 4.3 Unsupported calendar and wall-clock phrases

`ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase` rejects, case-insensitively, phrases outside the
grammar with `UNSUPPORTED_CALENDAR_PHRASE`:

- `this month`, `last month`, `next month`, `this week`, `last week`, `next week`
- `this morning` / `afternoon` / `evening` / `night`, `tonight`
- `noon`, `midnight`
- clock times such as `8am`, `8:30 pm`

The error message steers the model to ask for an explicit ISO range or a supported relative duration.

### 4.4 Boundary semantics

Parler's requested window is closed-open:

```text
[start, end)
```

ThingWorx services may treat `endDate` as inclusive or otherwise differ per service, and no service's
edge behavior has been audited. Parler therefore does not claim inclusive or exclusive platform
behavior: every `applied_time_window` it emits carries `"requested_semantics_only": true` next to
`start_utc` / `end_utc`, stating that the bounds describe Parler's intent, not verified platform edge
semantics.

### 4.4a Zone-less dates and times (normative)

A date or clock time the user writes **without a zone** — `2025-09-10`, "on the 10th", `14:00` — is as
ambiguous as `yesterday`, and is resolved the same way:

1. **Interpret** it in **`user_timezone`** when available, or in the zone the user names when they name one
   (including UTC). Interpretation comes first; UTC is an output format, not the default meaning.
2. **Then convert** the resolved instants to UTC for the call. Converting the *expression* to `Z` must not
   move the *day boundary*: one calendar day in a zone runs from that zone's local midnight, using **that
   date's actual offset**, which differs across daylight-saving transitions. Never hard-code a fixed offset,
   and never substitute the UTC day for a zone-local day.
3. **Resolve omitted parts by precedence, then stay consistent.** Explicit dates and user corrections take
   precedence. For omitted date parts (typically the year), inherit the period established for the same topic
   or follow-up in this conversation; use supplied current-time context only for parts not established there.
   A new topic has no inherited window. Keep the resolved window consistent across related queries — the same
   semantic date must not be sent as a zone-local window for one query and a UTC-day window for the next —
   and when the user corrects it, update the affected queries rather than keeping the earlier interpretation.
4. A supplied **`user_timezone`** is the zone for all unqualified times: do not query or offer an alternative zone
   to rule out ambiguity, and do not ask which zone applies. When **`user_timezone`** is absent and no documented
   fallback applies, state the zone used or ask — do not pick one silently.
5. The **end bound** follows the target tool's own contract (§4.4, §5). Do not apply one inclusive/exclusive
   convention to every service.

This governs how the **requested window is chosen**. It does not change
`InvokeServiceArgumentCoercion`, which parses an explicit DATETIME literal as written: a caller that already
supplied a valid zoned instant is taken at its word and never silently re-offset. The same rule is stated to
the model in `ParlerTimeAnchor.STABLE_TIME_GUIDANCE`; the two must not drift apart.

### 4.5 Built-in natural-time fields

Curated tools that expose them accept two optional fields alongside explicit ISO bounds:

- **`calendarPhrase`** — JSON string naming one local calendar day (§4.2). Requires `user_timezone`.
- **`relativeDuration`** — JSON string in the §4.2 grammar; closed-open `[now − duration, now)`.

Mutual exclusion (checked by `BuiltInToolNaturalTimeWindow` before any resolution):

| Combination | Code |
|-------------|------|
| both `calendarPhrase` and `relativeDuration` | `TIME_PHRASE_COMBINED_INVALID` |
| either field with `timePreset` (`query_alert_history`) | `TIME_PHRASE_VS_PRESET_CONFLICT` |
| either field with an explicit bound (`startTime` / `endTime`, or the aliases `start` / `end`) | `TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT` |
| a non-string JSON value for either field | `INVALID_TIME_SPEC_SHAPE` |

A field that is absent, `null`, or blank after trimming counts as not supplied.

`invoke_service` does not accept these fields; its raw DATETIME slots are protected by the
`UNSUPPORTED_RELATIVE_LITERAL` defense (§5, §8).

## 5. Tool mapping and the `invoke_service` defense order

Natural-time resolution applies only where Parler owns the mapping from resolved bounds to service
parameters. All natural-time fields share the grammar in §4.2 and the rules in §4.5.

| Tool | Accepted time inputs | Platform parameters |
|------|----------------------|---------------------|
| `query_alert_history` | `calendarPhrase`, `relativeDuration`, ISO `startTime` / `endTime`, `timePreset` | `QueryAlertHistory` `startDate`, `endDate` |
| `query_property_history` | `calendarPhrase`, `relativeDuration`, ISO `startTime` / `endTime` (aliases `start` / `end`) | `QueryNumberPropertyHistory` (and aggregates) for NUMBER / INTEGER / LONG properties; `QueryPropertyHistory` for other logged types; `startDate`, `endDate` |
| `query_stream_data` | `calendarPhrase`, `relativeDuration`, ISO `startTime` / `endTime` (aliases `start` / `end`) | `Stream.QueryStreamData` `startDate`, `endDate` |
| `build_history_overlay_chart` | per series: `calendarPhrase`, `relativeDuration`, `anchorOffset`, ISO `startTime` / `endTime`; shared `anchorTime` | per-series numeric property history; see [`history-overlay-chart.md`](history-overlay-chart.md) |
| Custom `_tool_*` service with a recognized DATETIME pair | synthetic `calendarPhrase`, `relativeDuration`, or the explicit pair | the service's own `startDate` / `endDate` or `startTime` / `endTime` (§12) |

Window rules per tool:

- **`query_alert_history`:** `startTime` and `endTime` are each optional. A missing `endTime` means now.
  `timePreset` accepts `last_1h`, `last_24h`, `last_7d`; an unknown value is `INVALID_TIME_PRESET`, and a
  preset combined with an explicit bound is `INVALID_TIME_RANGE`. With no bound, preset, or natural-time
  field, the tool applies an implicit default window of the last 7 days. `startTime` after `endTime` is
  `INVALID_TIME_RANGE`.
- **`query_property_history`, `query_stream_data`, custom pairs:** explicit bounds must be supplied both
  or neither; one alone is `INVALID_TIME_RANGE`. With neither and no natural-time field, the platform
  service's default window applies and no `applied_time_window` is emitted.
- Present-but-unparseable ISO bounds are `INVALID_TIME_RANGE`; they are never treated as absent.

Services that accept both a QUERY and time bounds (for example Stream or Data Table query services): build
filters and sorts per [`query-construction.md`](query-construction.md), and resolve DATETIME bounds with
this document. Relative-time text never goes inside QUERY filter values.

**Generic `invoke_service` — defense order.** `invoke_service` does not resolve natural time. Before
invoking, `InvokeServiceArgumentCoercion` applies:

1. **Curated tools first.** Time-aware questions are routed to the curated tools above; the stable
   guidance and the rejection message steer the model there.
2. **Declared DATETIME parameters.** For each argument whose `ServiceDefinition` parameter is
   `BaseTypes.DATETIME`, a raw relative or calendar literal (§8) is rejected before parsing with
   `UNSUPPORTED_RELATIVE_LITERAL`. The message steers toward ISO-8601 or a curated tool.
3. **Untyped JSON / VARIANT parameters (fallback).** When the declared type is `JSON` or `VARIANT` and the
   parameter name is in the §8 DATETIME-like list, its textual value gets the same check. When the value
   is a JSON object, its top-level keys are scanned one level deep: each key in the §8 list with a textual
   value is checked, and a rejection reports the dotted path (for example `payload.startDate`). Deeper
   nesting is intentionally not scanned; a service with nested time data needs a curated tool, not
   deeper guessing.

A parameter is never treated as a time bound merely because it is named `startDate`; the name list is
used only for rejection, never for mapping.

ThingWorx log services (`LogRetriever.QueryApplicationLog` and similar) are reachable through
`invoke_service`, with this defense on their DATETIME parameters and without natural-time resolution.

## 6. Applied time-window metadata

Tools that resolve and execute a bounded window return `applied_time_window`, written by
`ParlerAppliedTimeWindowJson`:

```json
{
  "applied_time_window": {
    "source": "NATURAL_LANGUAGE_CALENDAR_DAY",
    "start_utc": "2026-05-06T04:00:00Z",
    "end_utc": "2026-05-07T04:00:00Z",
    "requested_semantics_only": true,
    "timezone_basis": "America/New_York"
  }
}
```

- `start_utc` / `end_utc`: the closed-open window Parler requested.
- `requested_semantics_only`: always `true` (§4.4).
- `timezone_basis`: present only when the window came from `calendarPhrase` (the `user_timezone` used).

`source` values:

| Tool | `source` values |
|------|-----------------|
| `query_alert_history` | `EXPLICIT_ISO`, `PRESET`, `IMPLICIT_DEFAULT_WINDOW`, `NATURAL_LANGUAGE_CALENDAR_DAY`, `NATURAL_LANGUAGE_RELATIVE_DURATION` |
| `query_property_history`, `query_stream_data` | `EXPLICIT_ISO`, `NATURAL_LANGUAGE_CALENDAR_DAY`, `NATURAL_LANGUAGE_RELATIVE_DURATION` |

`query_alert_history` additionally returns its established fields `appliedStartTime`, `appliedEndTime`,
`timeRangeSource` (same value as `source`), `appliedTimePreset` for presets, and
`implicitDefaultWindowDays` for the implicit window. Custom `_tool_*` services do not emit
`applied_time_window`.

## 7. Error policy

**Rule:** after a time error, the agent must not retry without the time condition unless the user
explicitly asks for an unbounded query.

**Validation order (built-in tools).** JSON shape first (`INVALID_TIME_SPEC_SHAPE`, for example a bound
or natural-time field that is not a string), then natural-time mutual exclusion and resolution, then ISO
parse and pairing of explicit bounds (`INVALID_TIME_RANGE`).

**Wire envelope.** All time errors from curated tools, custom `_tool_*` services, and the
`invoke_service` defense share one shape:

| Field | Type | Present | Meaning |
|-------|------|---------|---------|
| `status` | string | always | `"error"` |
| `code` | string | always | A code from the table below. |
| `message` | string | always | English detail for both humans and the model. |
| `rejectedParameter` | string | when one field is at fault | Parameter name (`calendarPhrase`, `relativeDuration`, `startTime`, `start`, …) or dotted path for object-bag rejections (`payload.startDate`). Omitted — not `null` — when the error spans several fields (the mutual-exclusion codes, a missing second bound, `timePreset` with a bound, start after end). |
| `rejectionReason` | string | `UNSUPPORTED_RELATIVE_LITERAL` only | Classifier from `InvokeServiceDatetimeLiteralDefense.RejectionReason`: `CALENDAR_OR_WALL_CLOCK`, `INFORMAL_RELATIVE`, `DAY_TOKEN`. Consumers treat unknown values as opaque; new pattern families get new values. |

`rejectedParameter` keeps the alias the caller used (`start` vs `startTime`) and, for custom services,
the service-declared parameter name. Object-bag paths are at most two segments because the bag scan is
one level deep (§5).

The raw rejected value is never on the wire. It appears only, truncated, in the server `INFO` log line
for the rejection (with entity, service, parameter, and reason), which also serves as telemetry for new
patterns.

`BuiltInToolTimeErrorJson` and `InvokeServiceErrorJson` are public so custom-tool execution in
`AgentThing` reuses the same envelopes.

| Code | Meaning |
|------|---------|
| `INVALID_TIME_SPEC_SHAPE` | A time field has the wrong JSON type (for example not a string). |
| `INVALID_DURATION_GRAMMAR` | A duration does not match `<positive-integer><unit>`, or its magnitude is out of range. |
| `UNSUPPORTED_UNIT` | Unit `mo` or `y`. |
| `UNSUPPORTED_CALENDAR_PHRASE` | A phrase outside the grammar (§4.2, §4.3). Never mapped to a nearby duration; ask for an explicit range. |
| `MISSING_OR_INVALID_USER_TIMEZONE` | `calendarPhrase` without a valid host `user_timezone`. `rejectedParameter` is `calendarPhrase`. |
| `TIME_PHRASE_COMBINED_INVALID` | Both `calendarPhrase` and `relativeDuration` set. |
| `TIME_PHRASE_VS_PRESET_CONFLICT` | A natural-time field combined with `timePreset`. |
| `TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT` | A natural-time field combined with an explicit bound. |
| `INVALID_TIME_RANGE` | Explicit bounds present but not parseable as ISO-8601, only one bound where both are required, `timePreset` with a bound, or start after end. Distinct from `INVALID_TIME_SPEC_SHAPE`. |
| `INVALID_TIME_PRESET` | Unknown `timePreset` on `query_alert_history`. |
| `UNSUPPORTED_RELATIVE_LITERAL` | Raw relative or calendar text in a DATETIME-like slot of `invoke_service` or a custom service (§5, §8). The message steers to ISO-8601 or a curated tool with natural-time fields. |

`ParlerTimeErrorCode` also defines `ANCHOR_AND_ANCHOR_UTC_CONFLICT` and `RANGE_START_AFTER_END` for
resolver-level failures (§4.1); curated tools do not normally surface them. `build_history_overlay_chart`
uses the same envelope but reports every per-series window failure as `HISTORY_OVERLAY_INVALID_TIME_WINDOW`
(with the underlying message and `rejectedParameter`), plus `HISTORY_OVERLAY_INVALID_ANCHOR_TIME` for an
unparseable `anchorTime` (see [`history-overlay-chart.md`](history-overlay-chart.md)).

## 8. Heuristic rejection for generic service calls

**Scope:** §8 is the rejection classifier used by §5 steps 2 and 3, and for custom-service DATETIME
parameters (§12). It rejects; it never maps a value to a window.

DATETIME-like parameter names (case-insensitive), used for untyped JSON / VARIANT parameters and
object-bag keys:

```text
startDate, endDate, startTime, endTime, timestamp, from, to, at, date, time, eventTime, since, until
```

`InvokeServiceDatetimeLiteralDefense.classify` rejects, case-insensitively:

```text
^now\b                                                  (now, now-5m, …)
\bnow\s*[-+]
^(last|past)\s+[1-9]\d*\s*[smhdw]$
^[1-9]\d*[smhdw]$                                       (bare duration, e.g. 30m)
^[1-9]\d*\s+(seconds?|minutes?|hours?|days?|weeks?)\s+ago$
\b(today|yesterday|tomorrow)\b                          (classified DAY_TOKEN)
```

plus every §4.3 calendar and wall-clock phrase (classified `CALENDAR_OR_WALL_CLOCK`). The other forms
classify as `INFORMAL_RELATIVE`.

Notes:

- Relative-form digits use `[1-9]\d*`, so zero or leading-zero durations (`last 0h`, `00s`) are not
  matched here and fall through to the ISO parser's own error.
- The `(last|past)` form requires a unit and an end anchor, so `last 2 alerts` or `past 2 months` pass the
  classifier and fail only as unparseable instants.
- Wall-clock fragments such as `08:00 am` are still rejected on the daypart suffix.
- The day tokens are valid `calendarPhrase` values for curated tools but never valid as a single instant
  in a DATETIME slot, so they are rejected here to steer the model to the curated fields.

## 9. LLM guidance and time context

`ParlerTimeAnchor` owns the stable time guidance and the deterministic value formatting:

- **Stable guidance** (`STABLE_TIME_GUIDANCE`, in the leading provider-cacheable prompt): interpret
  unqualified times in `user_timezone`; interpret first, then convert to UTC without moving the day
  boundary; precedence and consistency rules (§4.4a); prefer `calendarPhrase` / `relativeDuration` where a
  tool offers them; otherwise derive ISO-8601 UTC `startTime` / `endTime` from the resolved window; the
  end-bound convention follows the target service.
- **Per-round values:** each round receives one fresh `now_utc`; `now_local` and `user_timezone` are
  included only for a valid IANA zone. `LlmUtcClockInjector` materializes this as one framed row, counts it
  in the context budget before provider serialization, and never persists it in conversation history.

`AgentThing` canonicalizes the client `userTimezone` and passes it to `AgentLoop` explicitly for the turn
(AlwaysOn / `ParlerStreamToRemoteThing`). A HITL continuation uses the timezone stored in
`PendingApprovalRecord`. Synchronous and asynchronous chat pass no zone but still receive UTC. Tool
executors read the zone from `AgentToolContext.getUserIanaTimezone()`; the clock builder does not read
thread-local tool context.

## 10. Tests

| Area | Tests |
|------|-------|
| Duration grammar, calendar-day resolution (including DST), unsupported phrases, overflow | `ParlerTimeResolverTest` |
| Natural-time mutual exclusion and `rejectedParameter` | `BuiltInToolNaturalTimeWindowTest`, `BuiltInToolTimeErrorJsonTest` |
| Explicit ISO parse, pairing, alias-preserving field labels | `ExplicitIsoTimeBoundsTest` |
| Alert history presets, default window, natural-language sources | `AlertHistoryTimeRangeTest` |
| `invoke_service` classifier, wire envelope, bag scan (incl. `jsonObjectBagNestedNotDefended`) | `InvokeServiceDatetimeLiteralDefenseTest`, `InvokeServiceErrorJsonTest`, `InvokeServiceExecutorDatetimeDefenseWiringTest` |
| Custom `_tool_*` pairs | `CustomToolDateTimePairResolverTest`, `CustomToolB17SharedNaturalTimeProseTest` |
| Time guidance and per-round row | `ParlerTimeAnchorTest`, `LlmUtcClockInjectorTest`, `ZonelessDateTimeGuidanceTest` (asserts §4.4a and the prompt state the same rule) |

## 11. Rules

1. Do not rely on the LLM for UTC arithmetic of "last N minutes" as the source of truth; use
   `relativeDuration` where offered.
2. Do not let a failed time parse lead to an unbounded retry.
3. Do not choose the nearest preset when the user asked for a different duration.
4. Do not use server local time for `today`, `yesterday`, or `8am`; use `user_timezone` (§4.4a).
5. Do not pass raw strings like `now-5m` into ThingWorx DATETIME parameters.
6. Do not claim inclusive or exclusive end-bound behavior for a service that has not been verified.
7. Do not invent per-tool time metadata or error codes; reuse `ParlerAppliedTimeWindowJson`,
   `BuiltInToolTimeErrorJson`, and `InvokeServiceErrorJson`.
8. Prefer IANA zone ids over numeric offsets; send `userTimezone` on every client turn when available.

## 12. Custom `_tool_*` services

App-defined services on the AgentThing get the same natural-time language as curated tools when they
declare a recognized DATETIME range pair.

- **Pair detection** (`CustomToolDateTimePairResolver.detect`): `startDate` + `endDate`, or `startTime` +
  `endTime`, matched case-insensitively, with **both** parameters typed `BaseTypes.DATETIME`. When both
  pairs are declared, `startDate` / `endDate` wins. Only flat parameters are considered.
- **Schema augmentation** (`CustomToolHarvester`): the harvested `ToolDefinition` gains synthetic
  `calendarPhrase` and `relativeDuration` fields with short descriptions; the shared natural-time
  contract text appears once on the tool description. The recognized pair fields are removed from the
  schema's `required` list so `{"calendarPhrase":"today"}` alone is a valid call.
- **No shadowing:** if the service itself declares a parameter named `calendarPhrase` or
  `relativeDuration`, augmentation is skipped; the pair still gets the §8 defense.
- **Resolution** (`CustomToolDateTimePairResolver.resolveInPlace`): runs through
  `BuiltInToolNaturalTimeWindow` (same mutual exclusion and grammar as §4.5), writes the resolved ISO-8601
  instants onto the service's own pair parameters, and strips the synthetic fields before the platform
  call. When no natural-time field is supplied (including blank or `null` values), the explicit pair is
  validated with `ExplicitIsoTimeBounds.parseOptionalPair` — both or neither — and failures keep the
  service-declared parameter name in `rejectedParameter`.
- **Errors:** `CustomToolNaturalTimeException` is converted by `AgentThing.executeCustomTool` into the
  `BuiltInToolTimeErrorJson` envelope (§7).
- **Defense:** every custom DATETIME parameter, paired or lone, runs
  `InvokeServiceDatetimeLiteralDefense.throwIfRejected` before parsing; a raw `today` or `now-5m` returns
  `UNSUPPORTED_RELATIVE_LITERAL` through `InvokeServiceErrorJson`. A lone DATETIME parameter gets this
  defense only, never pair semantics.

## 13. Implementation components

1. **Shared resolver** — `ParlerTimeResolver` with an injected `Instant` clock (§4.1–§4.3), tested offline.
2. **Curated built-in natural-time fields** — `BuiltInToolNaturalTimeWindow` wired into
   `AlertToolsExecutor` (`query_alert_history`), `PropertyToolsExecutor` (`query_property_history`),
   `StreamValueStreamToolsExecutor` (`query_stream_data`), and the history overlay chart's per-series
   window resolver; `AgentToolContext.getUserIanaTimezone()` supplies the zone (§4.5, §5).
3. **`invoke_service` DATETIME defense** — `InvokeServiceArgumentCoercion` runs
   `InvokeServiceDatetimeLiteralDefense` before any ISO parse for declared DATETIME parameters and, as the
   fallback, for DATETIME-like JSON / VARIANT parameters and one level of object-bag keys (§5 step 2 and
   step 3, §8). Rejection throws the typed `UnsupportedRelativeLiteralException` carrying the parameter
   path and `RejectionReason`; `InvokeServiceErrorJson` builds the wire envelope and the executor logs the
   truncated value at `INFO`.
4. **Applied-window metadata** — `ParlerAppliedTimeWindowJson` on the three range tools (§6).
5. **Model guidance** — `ParlerTimeAnchor.STABLE_TIME_GUIDANCE`, `LlmUtcClockInjector`, and per-tool
   `ToolDefinition` descriptions; the tool schemas are the source of truth for which tools expose
   natural-time fields (§9).
6. **Custom `_tool_*` DATETIME pairs** — §12.
7. **`query_alert_summary` is time-unbounded by design** — it is a current-state snapshot; time-bounded alert
   questions use `query_alert_history`.

## 14. References

- Timezone architecture: [`../architecture/times-solution.md`](../architecture/times-solution.md)
- Runtime LLM context: [`LLM_CONTEXT.md`](LLM_CONTEXT.md)
- Query construction for QUERY-plus-time services: [`query-construction.md`](query-construction.md)
- History overlay chart windows: [`history-overlay-chart.md`](history-overlay-chart.md)
- Stable time guidance and value formatting: `parler-agent/src/main/java/com/thingworx/things/agent/ParlerTimeAnchor.java`
- Per-round time row: `parler-agent/src/main/java/com/thingworx/things/agent/llm/LlmUtcClockInjector.java`
- Resolver: `parler-agent/src/main/java/com/thingworx/things/agent/time/ParlerTimeResolver.java`
  (`tryResolveLocalCalendarDayEnglish`, `rejectIfUnsupportedCalendarPhrase`,
  `rejectIfLocalCalendarDayPhraseHasUnsupportedResidue`, `resolveRelativeDurationClosedOpen`)
- Built-in natural-time fields: `parler-agent/src/main/java/com/thingworx/things/agent/tools/BuiltInToolNaturalTimeWindow.java`;
  envelope `BuiltInToolTimeErrorJson.java`
- `applied_time_window`: `parler-agent/src/main/java/com/thingworx/things/agent/tools/ParlerAppliedTimeWindowJson.java`
- Explicit ISO bounds: `ToolJsonTimeBounds.java`, `ExplicitIsoTimeBounds.java` (same package)
- Alert history: `AlertHistoryTimeRange.java`, `AlertToolsExecutor.java`
- Property history and streams: `PropertyToolsExecutor.java`, `StreamValueStreamToolsExecutor.java`
- Custom tools: `CustomToolDateTimePairResolver.java`, `CustomToolNaturalTimeException.java`,
  `CustomToolHarvester.java`; envelope routing in `AgentThing.executeCustomTool`
- `invoke_service` defense: `InvokeServiceDatetimeLiteralDefense.java`, `UnsupportedRelativeLiteralException.java`,
  `InvokeServiceArgumentCoercion.java`, `InvokeServiceErrorJson.java`

## 15. Tool coverage matrix

Status meanings:

- **Wired** — accepts `calendarPhrase` / `relativeDuration` and uses the shared envelope (§7).
- **Defended** — no natural-time fields; the §8 defense rejects raw relative literals in DATETIME-like
  parameters.
- **Excluded** — no time-window semantics by design.

| Tool | Time semantics | Status | Notes |
|------|----------------|--------|-------|
| `query_alert_history` | Ranged alert event timeline | **Wired** | Plus ISO bounds and legacy `timePreset`; `applied_time_window` and legacy applied fields (§6). |
| `query_property_history` | Ranged property history (NUMBER / INTEGER / LONG vs other logged types) | **Wired** | ISO bounds with `start` / `end` aliases; `applied_time_window`. Non-numeric properties return compact `VALUE_STREAM_HISTORY_INLINE` evidence. The former names `query_numeric_property_history` / `query_value_stream_property_history` remain executor-only aliases for replay and tests. |
| `query_stream_data` | Ranged stream query (`QueryStreamData`) | **Wired** | ISO bounds with aliases; `applied_time_window`. |
| `build_history_overlay_chart` | Per-series history windows from a shared anchor | **Wired** | Per-series `calendarPhrase` / `relativeDuration` / `anchorOffset`; see [`history-overlay-chart.md`](history-overlay-chart.md). |
| Custom `_tool_*` services with a recognized DATETIME pair | App-defined ranged queries | **Wired** | §12. No `applied_time_window`. |
| Custom `_tool_*` services with lone DATETIME parameters | App-defined | **Defended** | §12. |
| `invoke_service` | Any platform service, including ThingWorx log services | **Defended** | §5, §8. Time-aware questions should use a curated tool instead. |
| `query_alert_summary` | Current-state alert snapshot | **Excluded** | Time-unbounded by design (§13 item 7); ranged questions use `query_alert_history`. |
| `acknowledge_alerts`, `set_property_value` | Write operations | **Excluded** | Operate on current state; `set_property_value` has a HITL approval gate. |
| `get_property_values` | Current values | **Excluded** | Point-in-time read; history uses `query_property_history`. |
| `query_entities`, `query_entities_by_taxonomy`, `list_entities_by_type`, `list_asset_types`, `resolve_asset_type`, `resolve_thing`, `spotlight_search` | Metadata listing and search | **Excluded** | No temporal axis. |
| `get_entity`, `describe_entity_schema`, `discover_thing_members`, `discover_services`, `get_service_definition`, `discover_properties` | Metadata read | **Excluded** | No time. |
| `fetch_cached_result`, `tabulate_cached_result`, `summarize_cached_result`, `inspect_cached_payload`, `extract_nested`, `analyze_entity_set`, `build_chart_from_tabular_result`, `declare_chart_group`, and the `*_cached_result` compute tools | Operate on a prior cached result | **Excluded** | Time bounds come from the upstream tool that produced the cache. Compute tools that take windows over cached rows (for example `period_compare_cached_result`) take explicit ISO-8601 instants, not natural-time fields. |
| `get_agent_skill` | Skill text loader | **Excluded** | No time. |
