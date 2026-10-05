# parler-agent changelog

Notable changes to the Parler ThingWorx Java extension (`parler-agent`), newest first.

Each version is the extension's `major.minor.revision` version, set in `parler-agent/build.gradle`
(`majorVersion`, `minorVersion`, `revisionVersion`) and written to `metadata.xml` as the
`ExtensionPackage` `packageVersion` shown in ThingWorx after import. Entries note the contract-bundle
version (`CONTRACTS/CONTRACT_VERSION.md`) and the matching widget version where they matter; widget
changes are recorded in `parler-ui-widget/CHANGELOG.md`. Unreleased changes collect under
`[Unreleased]` until the next version is cut. Some revision numbers have no entry of their own.

---

## [Unreleased]

No unreleased changes.

---

## [0.1.250] — 2026-09-27

**Summary:** Every platform lookup and Service call now honors ThingWorx permissions, writes that need
approval are refused where no approval channel exists, and document search no longer keys on product
brand names.

### Security

- **Every platform call is permission-checked.** The agent no longer uses the platform's
  permission-free lookup and service-invocation paths. Targets named by the user or the model (tools,
  extended tools, approved writes, property history and stream queries, key resolution, discovery,
  alert and search Resources, and the Agent Thing a Gateway request names) are looked up with the
  user's Visibility and invoked as through REST, so the user needs ServiceInvoke. Parler's own
  streams, thread DataTable, FileRepositories, provider and connection Things and the Agent Thing's
  overridable services require the permission on the current user or the System user. Only the
  PASSWORD guards read metadata without Visibility, to mask or block values.
- **Property reads check the user's PropertyRead only.** `get_property_values`, taxonomy projection
  and lookup, `resolve_thing` identity matching and the `set_property_value` stale-snapshot read have
  no System-user fallback; a property the user may not read is reported as unreadable.
- **Tool-reached Services run as the user.** The Agent's overridable `ResolveDocumentSet` and the
  `GetPropertyDefinitions` calls of `get_entity` and `describe_entity_schema` run as the calling user
  with no programmatic retry.
- **Repository reads run as the user.** Document-index loads and `get_agent_skill` skill bodies call
  `BrowseDirectory` / `LoadText` as the calling user; configuration loads are unchanged. A refused
  document-index rebuild returns nothing and leaves the shared index untouched. Within its TTL the
  shared index is reused without a new repository call.
- **Approved property writes respect read-only.** `set_property_value` returns `PROPERTY_READ_ONLY`
  for a read-only property, both before approval and at the approved write.
- **No unapproved writes outside Parler conversations.** On `Chat` / `ChatAsync`, a non-bypassed
  `invoke_service` call or gated extended tool now returns `status: "blocked"`,
  `code: "APPROVAL_REQUIRES_PARLER_CONTEXT"` and does not run, like `set_property_value`.
- **Pending expiry runs as the pending's creator.** The TTL sweep handles each expired record under
  the security context captured at creation, so permission-checked lookups still succeed.

### Changed

- **Document search no longer keys on product brand names.** The scorer's CO2-capture identity check
  now reacts only to the generic words `chiller`, `co2` and `capture`. Queries naming only a brand no
  longer take that path.
- **`set_property_value` description** no longer points to a design document outside the product.
  Schema and behavior are unchanged.
- **`AgentToolCallEvent` description** now states that the event is declared but not fired. No event
  behavior changes.
- **Extended-tool approval cards name the target.** The `approval.required` summary shows "Confirm
  service invocation" with the tool, target Thing, Service and a redacted parameter preview.

### Fixed

- **Table CSV exports get a readable time stamp.** Export files are named
  `20260928T050709Z_<requestId>.csv` instead of `20260928THHmmss+0000_<requestId>.csv`. The folder and
  the request-id suffix are unchanged.
- **Cadence drift warns only on real drift.** The built-in quality profile flagged every series with
  regular sampling as drifting. It now warns when the recent sampling interval is at least twice, or
  at most half, the expected cadence, and a profile's drift factor must be greater than 1.

### Compatibility

- Contract bundle 0.1.184. Users now need Visibility and ServiceInvoke on what the agent touches for
  them; Parler infrastructure needs the permission on the user or the System user.

---

## [0.1.249] — 2026-09-27

**Summary:** Corrects the error codes the model is told to expect from `query_entities`.

### Fixed

- **`query_entities` description names the real error codes.** Passing both or neither of
  `thingTemplate` / `thingShape` returns `BOTH_TEMPLATE_AND_SHAPE` or `MISSING_TEMPLATE_OR_SHAPE`, not
  `INVALID_PARAMETERS` as the description claimed. Runtime behavior is unchanged.

### Compatibility

- Contract bundle 0.1.179.

---

## [0.1.248] — 2026-09-20

**Summary:** `tabulate_cached_result` gains mode `calendar_bucket` (`calendar_bucket_v1`), which adds
local calendar day/hour labels to cached tables.

### Added

- **Calendar labels for aggregation.** Given a time column, time zone and day/hour granularity, the
  mode appends `bucketStart`, `bucketEnd`, `bucketLabel` and `bucketSeconds`, keeping source rows and
  values, so `group_metric` can count or sum by label. An event crossing midnight belongs wholly to the
  day of its selected timestamp; this is not duration allocation.
- **Calendar boundaries and validation.** Buckets use real elapsed lengths across daylight saving and
  partial-hour transitions; repeated hours get offset-qualified labels. Numeric fixed-offset zone ids
  are refused; UTC aliases and geographic zones are supported. Missing or invalid times keep their rows
  with empty bucket cells. Unsupported ranges return a typed refusal with no partial output.
- **Bounded, source-aware results.** 100,000-row limit with one shared deadline and cancellation guard.
  Source completeness and read-limit reasons are kept. A day without rows is absent, not zero.
  Disabling the mode removes it and its parameters from the tool schema.

### Known limitations

- Week/month buckets, shift calendars, business-date rules, empty-bucket generation and per-bucket
  coverage are not supported.

### Compatibility

- Contract bundle 0.1.178 (`TABULAR_INSIGHT.md` §6.6). No new dependency library, ThingWorx Analytics
  installation or widget change is required.

---

## [0.1.247] — 2026-09-20

**Summary:** `tabulate_cached_result` gains counter increments, rolling statistics and time-weighted
integrals over cached observations, keeping source completeness and read-limit disclosures.

### Added

- **`counter_delta`** computes increments between cumulative-counter readings with caller-supplied
  rollover, rate, gap and reset rules. Exact segments and reset-related lower bounds are totaled
  separately; conflicting readings and unexplained negative jumps get no invented increment. Readings
  must be below `2^53`.
- **`rolling_stats`** returns one statistic per record (mean, sum, min, max, sample standard deviation,
  valid-value count or record count) over observation-count or elapsed-duration windows, optionally
  partitioned by entity. `warmedUp` describes the window frame, not data coverage.
- **`time_weighted`** integrates one numeric property-history series with an explicit step-hold or
  trapezoid rule, maximum gap and time unit. Segments distinguish observed, held and unknown time; a
  limited or partial source cancels the hold beyond its last reading.
- **Bounded execution.** Each mode enforces input/work limits, a shared deadline and cancellation, and
  returns typed failures instead of partial tables. Scope warnings survive result compaction.
  Disabling a mode removes it from the schema and rejects direct calls.

### Known limitations

- Exact rolling percentiles/medians, cumulative windows, deduplicated event counts and shift resets
  are not supported.

### Compatibility

- Contract bundle 0.1.176 (`TABULAR_INSIGHT.md` §6.3–6.5). No new dependency library, ThingWorx
  Analytics installation or widget change is required.

---

## [0.1.246] — 2026-09-20

**Summary:** A history or Stream read that stops at its row limit now says so. ThingWorx returns no
total for these queries, so more rows may exist; previously the agent answered silently from what it
got. No limits change and nothing is re-read.

### Added

- **Read-limit mark.** When `query_property_history`, `query_stream_data` or a series of
  `build_history_overlay_chart` returns at least the effective row limit, the result carries
  `readLimitReached: true` and a `readLimitNote` telling the model more rows may exist and to say so or
  offer a narrower window. The overlay lists affected series in `readLimitReachedSeries` and now echoes
  `maxItemsRequested` / `maxItemsEffective` (5000).
- **The cached table remembers it.** Completeness stays `UNKNOWN` with reason `READ_LIMIT_REACHED`,
  kept by every derivation. The mark comes from the reader only and is never inferred from table size.
- **The hint survives evidence compaction.** The three read-limit fields are kept in compacted tool
  results even for very wide tables.

### Changed

- **`union_rows` carries the completeness reasons of all inputs**, in order without duplicates, not
  only the first input's.

### Known limitations

- A chart drawn from a marked table does not show the mark.

### Compatibility

- Contract bundle 0.1.168 (`TABULAR_INSIGHT.md` §5.2).

---

## [0.1.245] — 2026-09-20

**Summary:** More data reaches a table the agent can analyse and chart: complete JSON results above 20
rows become cached tables, cached tables can be appended, and history queries for a non-existent
property are refused.

### Added

- **Complete JSON tables above 20 rows are cached.** A `resultKind: JSON` result whose `result` has a
  root `rows` array of objects gets a `cacheId` when the service proves completeness (`hasMore: false`,
  `truncated: false`, or `totalRows` equal to `returnedRows`); `returnedRows` alone proves nothing.
  Nothing is paged automatically; the model still sees at most 20 sample rows. Applies to
  `invoke_service` and extended tools, including the approved path. Guidance for service authors:
  `docs/agent/CUSTOMIZED-TOOLS.md`.
- **`tabulate_cached_result` mode `union_rows`.** Appends 2 to 31 cached tables in order and adds a
  string label column, so per-day or per-device results can be compared in one summary or chart.
  Inputs must match in column names, order, base types and nested `INFOTABLE` shape; rows are never
  de-duplicated. Completeness is the worst of the inputs. An empty union clears the chartable target.
- **Limits.** 31 inputs, 100,000 rows, 2,000,000 cells, plus the invocation's storage budget (default
  32,000,000 bytes) and wall time (default 60 s). A refusal caches nothing. New `union_rows` error
  codes: `MISSING_SOURCE_CACHE_IDS`, `TOO_MANY_UNION_INPUTS`, `INVALID_SOURCE_CACHE_IDS`,
  `MISSING_LABEL_COLUMN`, `INVALID_LABEL_VALUES`, `SOURCE_ROW_COUNT_UNKNOWN`,
  `LABEL_COLUMN_COLLISION`, `UNION_COLUMN_MISMATCH`, `UNION_TIME_BUDGET_EXCEEDED`.

### Fixed

- **`query_property_history` for a non-existent property.** The platform returns the whole value
  stream for an unknown property name. The tool now checks the Thing's property definitions first and
  returns `PROPERTY_NOT_FOUND` with a hint to use `discover_thing_members`; if definitions cannot be
  read it fails with `QUERY_PROPERTY_HISTORY_ERROR`.

### Changed

- The `tabulate_cached_result` schema requires only `mode`, since `union_rows` uses `sourceCacheIds`.
  Other modes still return `MISSING_CACHE_ID` without `cacheId`.

### Compatibility

- Contract bundle 0.1.164 (`TABULAR_INSIGHT.md` §5.1). Widget 0.1.96 is unchanged; only the agent
  extension needs re-importing.

---

## [0.1.244] — 2026-09-19

**Summary:** A chart built from a computed table is no longer blocked by a chart an earlier tool sent
in the same turn. On 0.1.243, a histogram request that needed a fresh history query produced a line
chart and bin counts as text.

### Fixed

- **Histogram, boxplot and grouped charts after a history query.** `query_property_history` sends its
  own line chart, which used to close the chart-building round after `bin_numeric`, `box_summary` or
  `group_metric`. The round now opens when the turn has a complete chartable table that no sent chart
  was built from (matched by `source.sourceCacheId`). The one-round-per-turn rule, six-chart budget and
  forced text summary are unchanged.

### Compatibility

- Contract bundle stays 0.1.161; widget 0.1.96 unchanged. Only the agent extension needs re-importing.

---

## [0.1.243] — 2026-09-19

**Summary:** Charts in one declared group can share colours by category, so the same state has the
same colour in every chart of the group.

### Added

- **Shared category colours.** `declare_chart_group` accepts an optional `sharedCategoryDimension`
  (1–80 characters). The agent keeps an append-only list of category keys per group and sends it in
  the `chart_group` manifest as `sharedCategories`; a key's position is its fixed palette slot. Keys
  are positive pie slice labels and series names of multi-series bar, line and scatter charts. Members
  using the shared slots carry `colorShared`.
- **No recolouring.** A member's new keys are sent before its chart frame; later members reuse
  assigned slots and append only new categories.
- **Limits.** At most 24 shared keys per group; a member that would exceed the cap keeps its own
  colours with a note. Sharing is never inferred without the parameter.
- **History.** The mapping is saved with the final manifest and replays unchanged.

### Compatibility

- Contract bundle 0.1.161. Pair with widget 0.1.96 for shared slots and same-category highlight.
  Older clients show per-chart colours.

---

## [0.1.242] — 2026-09-19

**Summary:** A chart group replays from history with its charts inside the group card.

### Fixed

- **Replayed chart groups could not find their charts.** History export removed `chartId` from
  replayed tabular charts, so members showed "Loading …" and charts rendered outside the card. Export
  now keeps the saved `chartId` and never invents one. Conversations recorded by 0.1.241 replay
  correctly without re-running.

### Compatibility

- Contract bundle 0.1.160 (`CHART_CONTRACT` §2.6: exported `charts[]` keep the saved `chartId`).
  Widget 0.1.95 unchanged; only the agent extension needs re-importing.

---

## [0.1.241] — 2026-09-19

**Summary:** Chart groups finish correctly. On 0.1.240 a group's final state was never sent or saved,
so after reload every member showed as failed.

### Fixed

- **Chart group final state lost at end of turn.** The final `chart_group` revision is now sent before
  `done`, unfinished members converge to `error` or `cancelled`, and the final manifest is saved in
  `chartGroupsJson`. Applies to `Chat`, `ChatAsync`, streaming (including user cancel) and approval
  resume. Groups recorded by 0.1.240 stay as saved; re-run the request for a correct record.

### Compatibility

- Contract bundle stays 0.1.159; widget 0.1.95 unchanged. Only the agent extension needs re-importing.

---

## [0.1.240] — 2026-09-18

**Summary:** Distribution analysis and richer charts: two distribution modes on
`tabulate_cached_result`, histogram, boxplot and heatmap charts, stacked and percent bars, and
declared chart groups that replay from history.

### Added

- **Distribution modes.** `bin_numeric` bins a numeric column by explicit edges or equal width (last
  bin right-closed; out-of-range values counted separately). `box_summary` returns per-group
  five-number summaries (type-7 quartiles, Tukey 1.5 × IQR whiskers, up to 20 listed outliers). Both
  scan up to 100,000 rows and register as chart sources. New error `TOO_MANY_GROUPS` above 24 groups.
- **New chart kinds in `build_chart_from_tabular_result`:** `histogram` (`histogramMode` `count` or
  `density`), `boxplot`, and `heatmap` over a two-key long table (up to 24 × 48; missing cells stay
  missing). Contradictory source statistics are rejected with `SOURCE_SHAPE_MISMATCH`.
- **`distribution` intent** resolves to histogram or boxplot, or returns
  `DISTRIBUTION_REQUIRES_BINNED_SOURCE`. The chart tool never bins implicitly.
- **Stacked bars.** `stackMode` `stacked` or `percent` for multi-series bars; `percent` rejects
  negatives with `STACK_PERCENT_NEGATIVE`.
- **Chart groups.** New tool `declare_chart_group` (one group per request, 2–6 members) and
  `groupMemberKey` on the chart tool. A `chart_group` manifest reports member states `pending`,
  `ready`, `no-data`, `error`, `cancelled`. Final manifests persist in the new `chartGroupsJson`
  Stream field and export as `groups[]`. The tool is not playbook-safe.

### Known limitations

- Members of a user-cancelled turn show `cancelled` live but replay as `error` / `TURN_INCOMPLETE`.

### Compatibility

- Contract bundle 0.1.159. Pair with widget 0.1.95. The import adds one field to the
  `AgentMessageData` DataShape. Clients unaware of a new chart kind or `chart_group` drop only that
  chart or group.

---

## [0.1.239] — 2026-09-18

### Added

- **Cache subject identity and `sources[]` echo.** Numeric-history caches written by
  `build_history_overlay_chart` and `query_property_history` record the Thing and property they hold.
  Non-error `analyze_cached_result` results carry an additive `sources[]` array with one row per
  operand used (`role`, `cacheId`, `timeColumn` / `valueColumn`, and `thingName` / `propertyName` when
  known). Derived tables do not inherit identity.

### Fixed

- **Relationship analysis lineage.** `relationship` results list both left and right caches in
  `sourceCacheIds[]`; previously only the left was recorded.

---

## [0.1.238] — 2026-09-18

### Added

- **Declared cache column roles and clearer column errors.** Numeric-history caches declare
  `timeColumn` / `valueColumn`; overlay `seriesCaches[]` and numeric history results report the
  written `columns[]` and roles, and the overlay also reports the request window (`windowStart`,
  `windowEnd`, `resolvedTimeZone`). When `analyze_cached_result` rejects a column name, the
  `ARGUMENT_MISSING` error adds `rejectedParameter`, `parameterSource`, `side`, the failing
  `cacheId`, a bounded `schema` (up to 32 column names) and a `columnSuggestion` from a unique
  case-insensitive match or a declared role. No server-side retry.

### Changed

- **Supplied user time zone is authoritative.** The default prompt's time guidance says a supplied
  `user_timezone` applies to all unqualified times and the model must not query alternative zones or
  ask which zone applies. The rule for a missing zone is unchanged.

---

## [0.1.237] — 2026-09-17

### Changed

- **Concise default system prompt.** The bundled routing guide, replay-format guide, default alert
  block (`GetAlertPrompt`) and time guidance are rewritten; fixed prose drops from about 38k to 18k
  characters. Time guidance states a precedence: explicit date or correction, then the period
  established for the topic, then current time. The default alert block no longer embeds the
  decision tree and QUERY examples. Deployment-dependent blocks and configuration semantics
  (`appendBuiltInToolRoutingGuide`, `taxonomyPromptInjection`, external `/SystemPrompt/*.md`) are
  unchanged.

---

## [0.1.236] — 2026-09-16

### Added

- **External system prompt file.** A single Markdown file directly under the configuration
  repository's `/SystemPrompt/` replaces the whole stable system prompt and takes precedence over the
  per-call base prompt. It loads with the prompt-context cache and reloads via
  `RefreshPromptContextCache`; an active or approval-paused turn keeps its captured prompt.
- **Diagnostics and fallback.** Runtime inspection reports the prompt source, active file and fallback
  reason. Multiple, unreadable or blank files fall back to the default assembly. Runtime time values,
  tool policies and provider cache markers are unchanged; authors must include any routing, time and
  evidence guidance they want. See `docs/agent/system-prompt-cache.md`.

---

## [0.1.235] — 2026-09-15

### Changed

- **Shorter tool descriptions.** Descriptions for seven priority built-in tools and their parameters
  are shortened; schema structure, tool order and behavior are unchanged.

### Fixed (agent evaluation tools)

- The AlwaysOn endpoint uses `/Thingworx/WS` with the REST server-root setting; an explicit WebSocket
  URL override remains. Usage queries no longer send unset filters as the literal string "null".

---

## [0.1.234] — 2026-09-15

### Added (agent evaluation tools)

- A user-operated ten-turn evaluation driver (`uv run` Python entrypoint with an AlwaysOn adapter)
  records per-turn evidence and usage. No runtime behavior change in the extension.

---

## [0.1.233] — 2026-09-13

### Changed

- **Usage report parameters.** `GetLlmUsageReport` and `ExportLlmUsageCsv` take `StartTime` and
  `EndTime` DATETIME parameters instead of a `timeRange` JSON object (inclusive start, exclusive end).
- **CSV export to a FileRepository.** Export writes through the selected repository's `SaveText` and
  returns the CSV text. Defaults: `SystemRepository` and `/LLM-USAGEyyyyMMddHHmm.csv` in the JVM
  default time zone. Explicit paths pass unchanged to ThingWorx. A missing target returns
  `REPORT_EXPORT_TARGET_NOT_FOUND`; save failures propagate. Prices are configured per helper Thing in
  `Settings.usagePricesJson`.

### Fixed

- `latency_checkpoint` diagnostics no longer prevent pricing of otherwise supported usage; missing
  cache counts are still not treated as zero.
- Generated CSV filenames follow the current JVM time zone, including across year boundaries.

---

## [0.1.232] — 2026-09-13

### Fixed

- **Small JSON tables get their own chart-source handles**, including on approval continuations, so
  the model can query several tables before charting each one.
- **Chart numbering survives approval pauses**, preventing duplicate chart IDs in widget controls.
- **Time zone guidance.** Dates and times without a stated zone are interpreted in the user's time
  zone using the target date's actual daylight-saving offset; explicitly zoned values keep their
  meaning.

---

## [0.1.231] — 2026-09-13

### Fixed

- **Service-call guidance** tells the model to discover unknown inputs with a tool suited to the
  target and bind the user's machine, time range and filters, while allowing no-argument calls.
- **Chart guidance** describes `last_invoke` as the latest qualifying table in the current request,
  keeps explicit cache handles across requests, and explains how to recover from a cache miss.
- **Multi-chart guidance** charts sources in order, counts successful charts as done, and does not
  rebuild them after a later failure.

---

## [0.1.230] — 2026-09-13

### Fixed

- **Charting JSON results.** Chart tool instructions accept eligible JSON single-table results as
  `last_invoke`, so the model no longer re-queries to get an INFOTABLE.
- **Chart sources survive approval pauses.** The source is snapshotted on the pending approval record
  and restored on continuation; compressed history cannot substitute sampled rows or select an older
  table.

---

## [0.1.229] — 2026-09-13

### Fixed

- **Small JSON tables are chartable.** JSON results (object or serialized string) with a small root
  `rows` table can feed `build_chart_from_tabular_result(source="last_invoke")`. Business failures,
  partial pages and results above the inline row limit do not register a source. Source errors point
  the model to an existing single-table tool or a text answer.

### Added

- JSON return guidance for tool developers in `docs/agent/CUSTOMIZED-TOOLS.md`.

---

## [0.1.228] — 2026-09-12

### Changed

- **Default `AgentLlmUsageHelper` Thing no longer bundled.** The ThingTemplate and its
  `GetLlmUsageReport` / `ExportLlmUsageCsv` services remain; create a separately named Thing from the
  template when usage reporting is needed.

---

## [0.1.227] — 2026-09-12

**Summary:** Tabular charts support explicitly requested horizontal bars.

### Added

- **Horizontal bars.** `build_chart_from_tabular_result` accepts `orientation: "horizontal"` for
  `bar` charts (wide tables and long tables with `seriesColumn`). The ChartBlock carries the
  orientation; vertical bars omit it.
- **Strict validation.** Orientation must be exactly `vertical` or `horizontal` and only with `bar`;
  other values return `INVALID_PARAMETERS`. The model requests horizontal bars only when asked.

### Compatibility

- Contract bundle 0.1.152. Pair with widget 0.1.93 for horizontal rendering; older clients render the
  same data vertically.

---

## [0.1.226] — 2026-09-12

**Summary:** An LLM call ledger and a usage helper make token usage, unknown usage and configured
costs auditable per call.

### Added

- **Call usage ledger.** New `AgentLlmCallStream` and `AgentLlmCallData` record call identity,
  provider/model, outcomes, raw and normalized usage, and collection gaps for agent rounds,
  checkpoints, playbook summaries and provider health probes. Recording failures never replace the
  response or trigger another LLM call.
- **Usage reporting helper.** `AgentLlmUsageHelper` provides reports and CSV exports filtered by time,
  conversation, Agent Thing and model. Reports separate known from missing usage, deduplicate events
  and compute costs only where usage and configured prices support them.

### Fixed

- **Provider usage accuracy.** Missing, incomplete, invalid and legitimately zero usage are handled
  distinctly; contradictory cache totals are excluded from trusted sums and costs. Usage anomalies do
  not affect answer text, tool calls or finish reasons.

### Compatibility

- Widget and contract bundle unchanged. `AgentMessageStream` / `AgentMessageData` are unchanged; no
  history migration is required.

---

## [0.1.225] — 2026-08-26

**Summary:** A provider response with no tool calls and no text can no longer end a turn as a false
success.

### Fixed

- **Empty final answers.** An empty or whitespace-only no-tool response gets one same-turn,
  no-tools finalization retry within the existing timeout. A second blank response ends the turn with
  error code `EMPTY_FINAL_ANSWER`. Non-empty answers and other terminal paths are unchanged.
- **Diagnostics.** `LLM_EMPTY_FINAL_RESPONSE` logs provider/request identity, finish and token facts
  and the retry action, without response text, thinking or tool content.

### Compatibility

- Widget and contract bundle unchanged.

---

## [0.1.224] — 2026-08-26

**Summary:** Final answers in any language pass through unchanged; enforcement stays at typed tool,
data, authorization, cache, schema, wire and egress boundaries.

### Fixed

- **Answer-guard truncation removed.** The final-prose rewrite hook is removed from `Chat`,
  `ChatAsync`, `ParlerStreamToRemoteThing` and approval continuation. Relationship and fleet
  root-cause answers are no longer replaced by English-only phrase matching.
- **Evidence boundary preserved.** Evidence assessment, deterministic analysis, PASSWORD protection,
  tool admission, approvals, artifact cache, schema/wire validation, server-authored tables and
  charts, argument redaction and egress controls are unchanged. Parler does not claim to detect
  secret-looking free text.

### Compatibility

- Widget and contract bundle unchanged.

---

## [0.1.223] — 2026-08-25

**Summary:** Tier B replay compaction now runs only under storage pressure, so ordinary turns that
stay within the cap keep the provider prompt cache intact.

### Fixed

- **Prompt-cache frontier retreat.** Post-turn replay normalization no longer rewrites every
  eligible historic tool-result body after each turn. Tier B compaction is skipped while the
  unmodified replay is at or below `llmContextMaxChars` and runs only above it, followed by the
  usual checkpoint-eligibility check and deterministic trim. History stays byte-identical during
  normal growth on Anthropic and OpenAI-family providers.

### Compatibility

- Widget package and contract bundle unchanged.

---

## [0.1.222] — 2026-08-25

**Summary:** Playbook alert evidence now keeps per-Thing attribution through multi-Thing grouping.

### Fixed

- **Playbook alert attribution.** The `group_alerts_by_source_property` derive now records which
  Thing raised which alert. Extracted alert rows carry the parent `thingName`, and the output adds a
  bounded `alertAttribution` projection (region, `thingName`, `alertName`, `sourceProperty`; at most
  40 rows, with an `alertAttributionOmitted` count) rendered as per-Thing evidence lines for the
  summarizing LLM call. Previously alerts were reduced to source-property counts and could be
  misattributed. Group counts, caps and gap notes are unchanged.
- **Accurate omission wording.** The attribution overflow line now reports only the omitted count.
  A per-Thing top-alerts gap mentions a `cacheId` for the remainder only when that Thing's rollup
  actually kept one; otherwise it says the remainder was not retained.

---

## [0.1.221] — 2026-08-25

**Summary:** More stable provider prompt-cache prefixes: a byte-stable leading prompt, volatile rows
moved to a fixed terminal suffix, and Anthropic history cache breakpoints.

### Changed

- **Prompt-cache frontier.** Volatile prompt rows now stay in a fixed terminal suffix for both
  provider shapes. Anthropic agent loop requests use stable user block arrays with breakpoints on
  the current and previous history; probe, summary and playbook requests are unchanged. Unknown
  non-leading system rows keep their authority and emit content-free diagnostics (Anthropic, OpenAI,
  Azure OpenAI). The framed task-evidence row is capped at 8,000 characters.
- **Stable leading prompt.** Workflow-catalog metadata and natural-time guidance moved into the
  leading system row. Per-round time values now occupy one suffix row derived from a single instant
  (UTC always; local time and IANA zone id optional). The old per-turn time-anchor and mid-list
  clock rows were removed.
- **Non-empty user content.** `Chat` and `ChatAsync` reject null or blank input before any work is
  queued. A slash-only turn (`/SkillName`) keeps the trimmed directive as the model-facing text. The
  Anthropic serializer fails closed on any remaining empty user row.

---

## [0.1.220] — 2026-08-24

**Summary:** Conversation checkpoints for long-conversation continuity, plus a fix to per-round
transcript pairing in the context planner.

### Added

- **Conversation checkpoints.** When the post-turn storage trim would delete transcript rows, the
  server makes one bounded, tool-free summary call with the turn's provider and stores a versioned
  `parler.conversation_checkpoint.v1` envelope: model-generated state, a server-authored evidence
  manifest and an exact retained tail. Requests carry only the newest checkpoint; older events stay
  in the Stream for audit. Checkpoints are stored as `role=context_checkpoint` rows in
  `AgentMessageStream` and are ignored by UI history, usage export, evaluation and trace
  completeness. On restart the newest valid checkpoint and its tail are restored, with `cacheId`
  liveness recomputed. A failed checkpoint never affects a completed answer; deterministic trim
  remains the fallback. Injection is skipped on providers that reject a leading assistant row
  (Anthropic); `CHECKPOINT_CANNOT_FIT` telemetry is emitted when a checkpoint cannot fit.

### Fixed

- **`CANNOT_FIT_AFTER_TRIM` on fittable requests.** The outbound planner now pairs a historic user
  row with the next kept assistant prose row when choosing droppable pairs, so a tool-backed turn
  whose evidence was already dropped no longer blocks trimming. Drop order, protected rows and
  fail-closed behavior are unchanged.

---

## [0.1.219] — 2026-08-24

**Summary:** A file-backed Artifact Cache is now mandatory, with explicit configuration and
repository-failure reporting across all channels.

### Changed

- **Mandatory Artifact Cache repository.** Every production AgentThing needs a configured,
  resolvable FileRepository before Chat, ChatAsync, AlwaysOn, structured Playbook or post-HITL work
  starts. Blank or invalid configuration fails visibly without calling the LLM; there is no
  in-memory fallback.
- **Single cache.** Automatic JVM memory-cache selection was removed; tabular, JSON/nested, derived
  insight, time-quality/join and fleet/RCA consumers all use the file-backed cache.
- **Accurate cache faults.** Non-miss faults are no longer reported as `CACHE_MISS`.
  Repository-wide failures end the turn without another LLM round; already emitted tool calls keep
  their paired results.
- **New error codes.** `ARTIFACT_CACHE_NOT_CONFIGURED` and `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`
  are returned consistently for Chat, ChatAsync events and AlwaysOn terminal errors.
  `AgentResponseEventData` gains an optional `errorCode` field.

### Compatibility

- Breaking upgrade: configure a valid FileRepository on each AgentThing before use. Contract bundle
  0.1.150. Widget package unchanged.

---

## [0.1.218] — 2026-08-21

Version-only release; no behavior, entity schema, contract or widget changes.

---

## [0.1.217] — 2026-07-20

**Summary:** Permission-aware fleet benchmarking and bounded, evidence-ranked root-cause
investigation for governed Apps and Playbooks.

### Added

- **Fleet cohorts.** Paged batch sources, frozen membership, comparability gates on semantics and
  data quality, explicit permission-limited coverage, and cohort results that make no per-member
  model calls and never disclose unauthorized identities.
- **Fleet position evidence.** Bounded distributions, competition ranking, comparable-member
  metrics, coverage counts and explicit partial/empty outcomes.
- **Bounded root-cause analysis.** Incident anchors, App-owned candidate catalogs, deterministic
  candidate resolution, supporting/weakening/unsearched scorecards, next-check ordering and explicit
  "no cause found" outcomes, with shared budgets, search limits and non-causal answer safeguards.
- **App and Playbook integration.** Demo peer and RCA adapters, fleet and investigation App
  runners, governed profiles and a reference Playbook driver. No new resident tool.

### Compatibility

- Widget package and contract bundle unchanged.

---

## [0.1.216] — 2026-07-20

**Summary:** Deterministic anomaly, relationship and bounded trend analysis over cached time-series
evidence.

### Added

- **Detection.** Robust IQR and robust-Z outliers, bounded binary-segmentation change candidates,
  and I-MR control limits with versioned run rules.
- **Relationships.** Pair alignment, Pearson/Spearman association, simple OLS with residuals, and
  lag scanning, all worded as non-causal.
- **Trends and thresholds.** OLS and Theil–Sen slopes, and threshold-crossing estimates only within
  approved horizons, with explicit outside-horizon and insufficient-evidence outcomes.
- **`analyze_cached_result` tool.** Handle-only tool with App profiles that returns deterministic
  finding rows; source rows and values are never exposed in the model-visible schema.

### Changed

- **Evidence-grounded narration.** Analysis assessments feed task state and final-answer guards so
  association is not narrated as causation, insufficient evidence does not become "no finding", and
  threshold claims stay within their horizon.
- **Status folding.** A sole-source `NO_FINDING`, `SUCCESS` or `COMPLETE` status is no longer
  downgraded during evidence aggregation.

### Compatibility

- Widget package and contract bundle unchanged.

---

## [0.1.215] — 2026-07-20

**Summary:** Deterministic time transforms, time-series quality assessment and governed exact joins
over cached tabular artifacts.

### Added

- **Analysis result envelope.** Deterministic analysis results carry method, evidence,
  completeness, lineage, window, budget and derived-artifact metadata.
- **Streaming cached tables.** Analysis operations read and publish cached artifacts through
  bounded typed streams instead of loading whole files.
- **Time and quality operations.** Resampling, rolling calculations, rate of change, period
  comparison, and gap/duplicate/order/flatline/drift quality checks over half-open time windows.
- **Exact joins.** Typed INNER/LEFT joins with declared key types, cardinality and collision policy,
  unmatched counts and bounded cache publication.

### Changed

- **New `tabulate_cached_result` modes.** `exact_join`, `quality`, `resample`, `rolling`,
  `rate_of_change` and `period_compare` are advertised and executed only when the corresponding App
  profile is admitted. Defined in `CONTRACTS/TABULAR_INSIGHT.md`.

### Compatibility

- Contract bundle 0.1.149. Widget package unchanged.

---

## [0.1.214] — 2026-07-20

**Summary:** Application semantic profiles, shared evidence and recovery vocabulary, and
evidence-grounded final-answer safeguards.

### Added

- **Application semantic profiles.** Authored in the ConfigurationRepository and loaded as
  immutable snapshots: exact Property/Service role bindings, units, grain, cadence and
  taxonomy-backed asset identity, with diagnostics.
- **Evidence assessment.** Typed evidence status, completeness and method references, aggregation
  and contradiction-aware answer guards.
- **Recovery vocabulary.** Typed tool-error categories, recovery actions, cache-miss classification
  and a bounded retry ledger.
- **App-developer guidance.** Semantic-profile examples, validation fixtures and an authoring
  walkthrough.

### Changed

- **Evidence-grounded answers.** Final answers apply BLOCK and CORRECT outcomes before delivery and
  persistence; WARN is diagnostic only. Cache and tool results carry semantic and completeness
  evidence into task state.

### Compatibility

- Widget package and contract bundle unchanged.

---

## [0.1.213] — 2026-07-19

**Summary:** FileRepository artifact writes no longer log false folder-creation errors.

### Fixed

- **False `Unable to create folder` errors.** Artifact writes now rely on `CreateBinaryFile` to
  create missing parent directories instead of calling `CreateFolder` first, so successful writes no
  longer log Application Log errors when the directory already exists. Exclusive create
  (`overwrite=false`) and `CREATE_COLLISION` behavior are unchanged.

### Compatibility

- Widget package and contract bundle unchanged.

---

## [0.1.212] — 2026-07-19

**Summary:** Scoped tabular and large-JSON artifact caches, bounded nested drill-in, completeness and
count evidence, and cache-backed history-overlay and replay fixes.

### Added

- **Scoped artifact caches.** Conversation-bound tabular and JSON caches with opaque UUID `cacheId`
  handles, bounded large-JSON inspection and lineage-preserving drill-in.
- **New resident tools.** `inspect_cached_payload` and `extract_nested`. Tabular paging and decision
  tools now run through the shared artifact cache.
- **Safeguards.** PASSWORD values are never persisted; payload and row caps are enforced.

### Changed

- **Tabular evidence.** `tabulate_cached_result` returns bounded `completeness` and `counts` evidence
  for supported operations.
- **Tool schemas.** `analyze_entity_set.operation` is constrained; advertised schemas and prose now
  match executable capabilities, and unsupported aliases were removed.
- **Replay and history overlay.** Replay no longer resurrects live cache entries. Overlay source
  series are cached as ordinary tabular artifacts, misses report `CACHE_MISS`, and compatible
  alert-summary replay groups are merged. The chart wire shape is unchanged.

### Compatibility

- Contract bundle 0.1.146. Widget package unchanged.

---

## [0.1.211] — 2026-07-17

**Summary:** A FileRepository-backed artifact storage layer, plus fixes to taxonomy completeness,
approval identity, history row limits, alert mutation gating and the history-chart tool surface.

### Added

- **Artifact cache storage.** Per-principal and per-scope isolation, streaming writes and bounded
  reads, TTL and invalidation, and a stable fault vocabulary. Payloads containing PASSWORD fields
  are rejected before any file is created.

### Changed

- **Taxonomy completeness.** `query_entities_by_taxonomy` distinguishes malformed listings, complete
  pages, truncation and unknown completeness. Non-intersect results may report `truncated`,
  `totalUnderlyingCount` and `hasMore`; intersect results combine completeness conservatively. API
  revision 2.4.39.
- **Mutation correctness.** Property writes keep the requested value separate from the canonical
  typed value across HITL approval. Alert acknowledgement rejects invalid writes before execution.
  Runtime and replay use the same max-row precedence.
- **Sample definitions.** Utilization samples use `THINGNAME` for `Machine` and `BOOLEAN` for
  `IncludeStats`.
- **History charts.** The retired period-over-period and multi-series chart builders were removed;
  `build_history_overlay_chart` is the only history-chart tool.

### Compatibility

- Cache files live under the configured ThingWorx FileRepository. After a restart the in-memory
  index starts empty; administrators may delete age-expired files manually. Contract bundle 0.1.144.
  Widget package unchanged.

---

## [0.1.210] — 2026-07-15

**Summary:** Utilization Playbooks now handle JSON-string extended-tool results and keep canonical
machine identity.

### Changed

- **Utilization Playbook fixes.** `$ref` traversal parses a JSON-string `toolOutput.result` when it
  needs nested fields. `match_identifier_in_rows` recognizes `machineName`, `displayName` and
  `description` listing rows while keeping canonical-name safeguards. `machine_utilization_summary`
  passes the resolved canonical machine to `get_utilization_state_summary`.

### Compatibility

- Widget package and contracts unchanged.

---

## [0.1.209] — 2026-07-06

**Summary:** Playbook orchestration additions: optional-branch row merging with `merge_row_sets` and
configurable `fan_out` item binding.

### Added

- **`merge_row_sets` derive op.** Concatenates several optional-branch `output.rows` sources in
  stable order for use by `build_nested_object`, and reports `sourceCounts`, `totalCount`,
  `returned` and bounded `gaps`. The validator requires dotted `$ref` paths.
- **Authoring recipes.** Playbook documentation covers nested `result.Table0` extraction,
  multi-name fan-out, service-error `condition` guards and primitive `string[]` item binding.

### Changed

- **`fan_out` primitive items.** Non-object items are wrapped under the `itemVar` key (default
  `value`) instead of a hardcoded `region` key. Invalid `itemVar` values are rejected.

### Compatibility

- Widget package and contracts unchanged.

---

## [0.1.208] — 2026-07-06

**Summary:** Playbooks can project nested extended-tool evidence, new derive ops support
service-orchestration payloads, and training-stage checks were added.

### Added

- **Nested tool-output evidence.** Optional `evidence.includeToolOutputPaths` (bounded dot paths
  from the `toolOutput` root) and `evidence.table.path` for JSON-envelope extended tools whose fields
  live under `toolOutput.result.*`.
- **Service-orchestration derive ops.** Optional-branch empty rows, bounded nested-object
  construction, JSON stringification and time-window / quick-interval resolution (for example
  `time_window -> build_nested_object -> json_stringify -> CallGetKPIs`).
- **Training-stage checks.** `scripts/check-training-stage-contracts.mjs` plus utilization smoke
  scripts under `test_scripts/` (live runs need `DEV_SERVER` / `DEV_KEY`).

### Changed

- **SCPA utilization sample playbooks.** In `dev_data/scpa_utilization`, `utilization_summary`,
  `utilization_overview` and `machine_utilization_summary` declare nested evidence so `llm_summary`
  receives utilization percent, event count, state rows and machine coverage. The obsolete sample
  trees `dev_data/scpa_utilization_old_tools` and `dev_data/scpa_utilization_updated_skills` were
  removed.

### Compatibility

- Widget package and contracts unchanged.

---

## [0.1.207] — 2026-07-04

**Summary:** Loaded playbooks are now visible to the model through a per-turn catalog and a
registry-driven `start_playbook` tool definition.

### Changed

- **Playbook catalog routing.** A per-turn Agent playbooks catalog is sent together with the skill
  catalog in one system row. `start_playbook` gets its description and generic `params` schema from
  the registry. The operator snapshot includes `prompt.playbookCatalog` when
  `includePlaybooks: true`. Skill shadowing of same-id playbooks is unchanged, and Playbook
  execution is unaffected.

### Compatibility

- Widget package and contracts unchanged.

---

## [0.1.206] — 2026-07-04

**Summary:** Host Context falls back to a generic rendering for unregistered keys; built-in
classpath templates were removed.

### Changed

- **Host Context generic fallback.** Built-in `host-contexts/*.json` classpath templates are no
  longer used. A parseable unregistered key renders a bounded generic JSON prompt fragment
  (`UNREGISTERED_GENERIC_FALLBACK`) with diagnostics in `ValidateHostContext` and turn state. The
  fallback does not drive `requiredTools`, `requiredBuckets` or document-scope injection.
  Unregistered `key` values are JSON-quoted.

### Compatibility

- Contract bundle 0.1.140. Widget package unchanged.

---

## [0.1.205] — 2026-07-03

**Summary:** `build_history_overlay_chart` replaces the period-over-period and multi-series history
chart tools, with absolute, elapsed and normalized time axes.

### Added

- **`build_history_overlay_chart`.** Per-series windows, line or scatter `chart_kind`,
  `yReferenceLines`, mixed cross-Thing elapsed overlays, unequal-duration elapsed domains and a fair
  5,000-point downsample. X-axis modes: `absolute_time`, `elapsed_time` and `normalized_time`
  (wire `xAxisMode: "normalized"` with `normalizedDomain {0,1}`; chosen only explicitly).
  Defined in `CONTRACTS/CHART_CONTRACT.md` §3.0e. The `POP_MULTI_SERIES_UNAVAILABLE` redirect is
  retired.

### Added (parler-ui)

- **Normalized overlay rendering.** Percent axis ticks; the wire adapter rejects fixed-domain modes
  on chart kinds other than line/scatter and normalized domains other than `{0,1}`.

### Removed

- **`build_period_over_period_chart` and `build_multi_series_history_chart`** are no longer
  model-facing. Routing guidance and playbook allowlists now point to the overlay tool.

### Compatibility

- Contract bundle 0.1.139. Pair with widget 0.1.89.

---

## [0.1.204] — 2026-07-01

**Summary:** Same-window multi-series charts: a long-format `seriesColumn` pivot for tabular
line/scatter charts and a cross-Thing history chart tool.

### Added

- **Tabular long-format pivot.** `build_chart_from_tabular_result` accepts `seriesColumn` with
  `kind: line` or `scatter`: sparse per-series pivot, first-seen series order, at most 6 series,
  `DUPLICATE_SERIES_CATEGORY` error. `CONTRACTS/CHART_CONTRACT.md` §3.0b.
- **`build_multi_series_history_chart`.** Same-window cross-Thing line/scatter history charts with a
  shared `requested_time_range`, fair 5,000-point downsample and `missingSeries[]`.
  `POP_MULTI_SERIES_UNAVAILABLE` now includes `redirectTool`. `CONTRACTS/CHART_CONTRACT.md` §3.0f.
- **Routing guidance.** A data-source rule tells the model when to chart from the tabular cache
  versus live history. An eval suite (`multi_series_v1.yaml`) was added.

### Changed

- **History fetch errors.** Period-over-period and multi-series tools map fetch failures to their
  own error codes; duplicate Things are rejected with `MULTI_SERIES_DUPLICATE_THING`.

### Compatibility

- Contract bundle 0.1.138. Widget package unchanged.

---

## [0.1.203] — 2026-07-01

**Summary:** `build_period_over_period_chart` overlays the same property across comparable windows
on an elapsed-time axis, with UI support for elapsed axes.

### Added

- **`build_period_over_period_chart`.** Same-Thing elapsed overlays with `anchorOffset`; cross-device
  `periods[].thingName` with unit validation (`POP_UNIT_MISMATCH`); same-window cross-device requests
  return `POP_MULTI_SERIES_UNAVAILABLE`. Fair per-series downsampling. Routing guidance and an eval
  suite (`period_over_period_v1.yaml`) were added. Elapsed mode is defined in
  `CONTRACTS/CHART_CONTRACT.md`.
- **Elapsed axes (parler-ui).** The widget renders elapsed-time axes and preserves `sourceWindow`.

### Compatibility

- Contract bundle 0.1.136. Widget package remains 0.1.88.

---

## [0.1.202] — 2026-07-01

**Summary:** `query_alert_summary` compares alerts across several Things through `thingNames[]`.
Scalar `thingName` is no longer accepted by this tool.

### Added

- **Multi-Thing alert summary.** `query_alert_summary` takes `thingNames[]` (1–25 names). With one
  name the existing `INFOTABLE` / `INFOTABLE_LARGE` result is returned; with two or more it returns
  `ALERT_SUMMARY_MULTI` with per-Thing rollups, partial identity/service failure handling, and
  `byThing` / `identityErrors` preserved through egress. `CONTRACTS/TAXONOMY_RESOLVER.md` §7.2.
- **Guidance and samples.** Routing guide, `region_health` and `asset_pair_health` skills, and
  `cross_region_health` / `cross_asset_pair_health` playbooks use a single `thingNames[]` alert
  step.

### Changed

- **Source-property grouping.** `group_alerts_by_source_property` on `ALERT_SUMMARY_MULTI` emits
  `regionAlertGaps` when a Thing's `totalAlerts` exceeds its inline `topAlerts` sample, so breakdowns
  are marked approximate instead of silently truncated.

### Compatibility

- Breaking: single-Thing calls must pass a one-element `thingNames` array. Contract bundle 0.1.136.
  Widget package unchanged.

---

## [0.1.201] — 2026-06-28

**Summary:** Tool-schema admission control limits the tool-schema overhead advertised per turn
(the `LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP` failure) with per-tool size telemetry and
opt-in `narrow` and `lazy` modes. The default is `off`, so behavior is unchanged unless configured.

### Added

- **Per-tool schema-size telemetry.** `LLM_TOOL_SCHEMA_USAGE` adds `toolSchemaChars`,
  `toolSchemaSizesSum`, `toolSchemaFramingChars` and a per-tool `toolSchemaSizes` breakdown (joined
  by `parlerRequestId`), using the same sizing as the planner's overhead estimate.
- **`narrow` mode.** `toolAdmissionMode=narrow` drops irrelevant tool buckets using deterministic
  host-context and intent signals (no LLM router). The core and operational-base buckets are always
  kept, host-context `requiredTools` / `requiredBuckets` are honored, and it falls back to
  advertising all tools if narrowing would leave none. Emits a `TOOL_ADMISSION` log line.
- **`lazy` mode.** `toolAdmissionMode=lazy` advertises only the core set plus a size-capped
  `load_tool_schemas` meta-tool that loads other tool schemas on demand for the turn.

### Changed

- **New AgentSettings field.** `toolAdmissionMode` (`STRING`, default `off`; values `off`, `narrow`,
  `lazy`) is editable in Composer. Unknown or blank values mean `off`.

---

## [0.1.200] — 2026-06-27

**Summary:** Document-retrieval convergence: a saturation guard ends repeated
search/fetch loops with a grounded answer that states its coverage, and heading chunks rank below
substantive content.

### Changed

- **Retrieval-saturation guard.** The document-search loop guard now tracks the substantive chunk
  content surfaced in the turn (search results and fetched bodies); `get_document_chunk` no longer
  resets it. After several retrieval rounds add no new content (once at least one full chunk has
  been fetched), the agent must answer from the evidence it has. This fixes broad prompts about
  symptoms a manual does not list running to the iteration cap with no answer. Heading-only
  `signal-*` chunks do not count as progress.
- **Coverage-stating answers.** When the guard fires, the final round tells the model to cite the
  chunks and PDF page links already found, answer every covered sub-question, and state which
  requested symptoms, causes or remedies the manual does not cover. That cited evidence is protected
  from context trimming.
- **Search ranking.** `search_document_chunks` ranks substantive content above heading/marker
  `signal-*` chunks. Document selection, document scoring and BM25 are unchanged.

---

## [0.1.199] — 2026-06-27

**Summary:** Knowledge-retrieval pipeline: a metadata-driven document-set resolver with an overridable
`ResolveDocumentSet` service, host-context document scoping, BM25/IDF chunk ranking, and always-included
cross-cutting documents.

### Added

- **`resolve_document_set` tool.** Advertised with the other document-knowledge tools when
  `documentKnowledgeBuiltinsEnabled` is on. Takes an asset/Thing `key` and returns the in-scope
  `documents[]` plus `resolverSource` (`custom | default-match | default-empty | none`). The built-in
  matcher scopes only on a normalized exact match against manifest `documentProfile.assetModels[]`
  (and root `assetModels[]`); otherwise it falls through.
- **`documentIds` parameter on `search_document_chunks`.** The optional `documentIds: string[]` filter
  is now in the tool schema (reported as `selectionMode: documentIds-filter`); pass
  `resolve_document_set.documents[].documentId` to scope a search.
- **Overridable `ResolveDocumentSet` service.** New `isAllowOverride` service
  `ResolveDocumentSet(key STRING): INFOTABLE<ResolvedDocument>` on the agent entity (new
  `ResolvedDocument` DataShape: `documentId`, `alwaysInclude`, `appliesToMany`). An override that
  returns at least one valid `documentId` wins (`resolverSource: custom`); an empty or unusable result
  falls through to the built-in matcher.
- **Host-context document scoping.** When the turn's host context carries `context.thingName`, the
  document set is resolved at turn start and used as the default `documentIds` for searches in that
  turn (Chat, ChatAsync, `ParlerStreamToRemoteThing`). It applies only when the model omits
  `documentIds`, reports `documentScopeSource: host-context-resolver`, and fails open to unscoped search.
- **BM25/IDF ranking signal.** `search_document_chunks` adds an in-process BM25/IDF boost computed over
  the scoped candidate chunks, down-weighting common terms. The boost is capped so it cannot override
  identity/signal scores; each match reports `bm25Boost`.
- **`alwaysInclude` documents.** Rows a custom override flags `alwaysInclude` (e.g. general safety
  documents) are always unioned into the scoped `documentIds`. The built-in matcher never sets the flag.
  `resolve_document_set` rows expose `alwaysInclude` and `appliesToMany` (the latter has no effect yet).
  Operator guide: `docs/operations/knowledge-retrieval-pipeline.md`.

---

## [0.1.198] — 2026-06-26

**Summary:** Document retrieval stability: document-aware ranking, a document-search loop guard, and
wider tool narrowing for document turns.

### Added

- **Document-aware search ranking.** Two-phase document + chunk scoring with per-document
  diversification, asset-context input, optional manifest `documentProfile`, and bounded diagnostics
  (`documentScores`, `selectedDocIds`, `selectionMode`).
- **Document-search loop guard.** Forces a final tool-free summary round after four zero-progress
  `search_document_chunks` calls, or three calls with unchanged results, without a `get_document_chunk`.
- **Wider document-tool narrowing.** After the model's first `search_document_chunks` or
  `get_document_chunk` call, later rounds may narrow to document tools even without `/document_search`,
  as long as no non-document tool has run.

### Changed

- **Search scoring.** Detects operational-trouble intent from query tokens and boosts troubleshooting
  chunks; favors matching document kind/asset family (competitor-chunk penalties removed); supports
  prefix matching on signal tokens. Top-k diversification now backfills to `limit` when no other
  document has chunks left.

### Changed (conversion tooling)

- **`convert_pdf.py`.** Trouble chapters emit `contentType: troubleshooting` with heading-derived
  signals; manifests emit optional `documentProfile` with derived `domainTerms` (single-token fragments
  of multi-word phrases are omitted).
- **`validate_package.py`.** Adds troubleshooting-section typing checks and a guard against over-fit
  `documentProfile.domainTerms`.

### Compatibility

- Pair with widget 0.1.87. Contracts unchanged.

---

## [0.1.197] — 2026-06-26

**Summary:** Document-index enhancement: deterministic ranking adjustments, safer document-only turns,
and conversion/validation improvements.

### Fixed

- **Document-turn loop safety.** After two or more `REPETITION_BLOCKED` tool results in one turn, the
  agent forces a tool-free summary round so the model finalizes instead of burning iterations.

### Changed

- **Document-turn tool narrowing.** When the turn skill is only `document_search` and no non-document
  tool has run, the model sees only `get_agent_skill`, `search_document_chunks` and
  `get_document_chunk`. New setting `AgentSettings.documentTurnToolNarrowingDisabled` (default `false`;
  `true` restores the full tool list).
- **Diagnostics.** `GetAgentRuntimeSnapshot` exposes `documentTurnToolNarrowingDisabled` and
  `documentKnowledgeBuiltinsEnabled`; narrowing logs include `conversationId`.
- **Bundle document penalty.** Manifests may declare optional `documentRole`; chunks from
  `documentRole: bundle` packages get a score penalty so individual manuals outrank compilation bundles.
- **Damage-intent ranking.** Generic document id/title token affinity and boosts for
  `damage-before-operation` tags/signals on damage-related queries.

### Changed (conversion tooling)

- **`convert_pdf.py`.** Emits chapter-level register-code section chunks for install-spec and
  operating-manual packages, `damage-before-operation` troubleshooting chunks, and
  `documentRole: bundle` for compilation manuals. `validate_package.py` checks weak headings,
  register-section presence, and heading/body alignment.

### Compatibility

- Widget unchanged. Contracts unchanged.

---

## [0.1.196] — 2026-06-24

**Summary:** Fixes `invoke_service` parameter coercion so JSON inputs such as Mashup host-context query
payloads reach target services as real JSON values.

### Fixed

- **`invoke_service` typed parameters.** JSON, XML, GUID and LOCATION parameters now use the platform
  converters instead of being stringified; structured VARIANT inputs are wrapped as JSON rather than
  read as `{baseType,value}` envelopes. Target services no longer ignore JSON inputs and fall back to
  broad result sets.

### Compatibility

- Widget unchanged. Contracts unchanged.

---

## [0.1.195] — 2026-06-23

**Summary:** Refines the empty-thread brand placeholder in the widget.

### Changed (parler-ui)

- **Empty thread brand.** The large block-art placeholder is replaced by a smaller, muted text watermark.

### Compatibility

- Widget 0.1.86 (`parler-ui` 0.1.8). Contracts unchanged.

---

## [0.1.194] — 2026-06-23

**Summary:** Fixes cache scoping for HITL-approved `invoke_service` calls and adds an empty-thread
brand placeholder to the widget.

### Fixed

- **HITL approval continuation.** Approved gated tools now run bound to the real conversation, so large
  `invoke_service` INFOTABLE results are cached under the actual `conversationId` (not
  `__single_turn__`) and same-turn `tabulate_cached_result` / `summarize_cached_result` can read them.

### Added (parler-ui)

- **Empty thread brand.** A subdued `Parler` word mark shows when the thread is empty and history is not
  loading, in both connected and disconnected states.

### Compatibility

- Widget 0.1.85 (`parler-ui` 0.1.7). Contracts unchanged.

---

## [0.1.193] — 2026-06-23

**Summary:** Host context is stored per user turn and shown in the widget, and entity-listing tools
accept hierarchy node system ids.

### Added

- **`hierarchyNodeId` argument.** `query_entities` and `query_entities_by_taxonomy` accept an optional
  `hierarchyNodeId` that intersects directly via `GetAssetList`. Precedence: explicit
  `intersectThingNames`, then `hierarchyNodeId`, then `hierarchyNodeName`.
- **Host context turn state.** User Stream rows persist `hostContextSnapshotJson`; history export and
  change detection use it. See `docs/architecture/host-context-turn-state.md`.

### Added (parler-ui)

- **Host context disclosure.** User rows show collapsed host-context metadata; live sends attach a
  best-effort snapshot; prompts and raw host-context JSON have copy actions (raw JSON reports
  unavailable when history starts after the anchor row).

### Compatibility

- Contract bundle 0.1.135. Pair with widget 0.1.84 (`parler-ui` 0.1.6).

---

## [0.1.192] — 2026-06-22

**Summary:** Host Context v2 replaces the `kind`-based Host Scope model with `key + context` templates,
bounded formatter rendering, a `ValidateHostContext` service, and prompt-only Mashup page guidance.

### Added

- **Host Context v2.** Templates in `host-contexts/*.json` render `key + context` through bounded
  formatters (`format.jsonFence` with `blockName` and a security preamble, `typedList`, `filters`,
  `timeWindow`, `hierarchy`, `list`, `kv`). Uplink fails open. Built-in templates for Asset Detail and
  Asset Monitoring. New `ValidateHostContext` service. Architecture: `docs/architecture/host-context.md`;
  also covered in the training material.

### Security

- **Fence and template hardening.** `jsonFence` escapes every backtick, truncation is fence-safe,
  formatters are validated at load time, and context values are substituted as literals.

### Removed

- **v1 Host Scope.** The v1 Host Scope JSON uplink, server-side `hostContext` hierarchy injection, and
  the Host Scope system-prompt block are removed. Hierarchy intersection in entity queries now applies
  only to an explicit `hierarchyNodeName` (no Mashup fallback).

### Compatibility

- Contract bundle 0.1.133 (`API_CONTRACT.md` version history marks superseded entries). Pair with
  widget 0.1.82 for the updated host-context widget metadata.

---

## [0.1.191] — 2026-06-20

**Summary:** Playbook readiness: runtime capability snapshots, structured validation, run-outcome and gap
diagnostics, new authoring derive ops, and a Skill-to-Playbook converter pack.

### Added

- **Playbook runtime snapshot.** `GetAgentRuntimeSnapshot` with `includePlaybooks: true` returns
  `playbookRuntime` (even when the catalog is empty or invalid, including available `deriveOps`) and
  `playbooks.lastRun`.
- **`ValidatePlaybookDocument` service.** Validates a raw `playbookJson` or a `packageDirId` and returns
  a structured report. `ValidateAgentConfigurationRepository` adds `playbookValidations[]` with a row for
  every discovered package, including parse/read failures (`INVALID_JSON`, `PACKAGE_UNREADABLE`).
- **Structured validation issues.** Issues carry `code`, `path`, `nodeId` and `recoveryHint`. New codes
  include `INVALID_DEDUPE_KEYS`, `INVALID_MATCH_CANDIDATES_*`, `INVALID_LIMIT_ROWS_MAX` and
  `INVALID_FORMAT_EVIDENCE_MAX_LINES`.
- **Run outcome and gaps.** Runs report a run-level `runOutcome`; gaps are `{code, message}` objects
  (legacy `reason`/`kind`/`type` keys removed; plain-string gaps normalize to `GAP_NOTE`). `fan_out`
  exposes `output.childResults[]` and supports `continueOnChildGap`.
- **Authoring derive ops.** `normalize_text`, `match_candidates` (`onZero`/`onMultiple`), `dedupe`,
  `limit_rows` (`maxRows`) and `format_evidence_lines` (`maxLines`), with validator coverage;
  `group_by` gives a `recoveryHint` for malformed `keys`.
- **Skill-to-Playbook converter.** A converter pack (skill, knowledge, golden `playbook.json` and
  conversion reports) under `docs/agent/playbook-converter/`.
- **Live collection.** `parler-collect-live` reports `playbookDocumentValidations`, the snapshot
  `playbookRuntime`, and `lastPlaybookRun`.

---

## [0.1.190] — 2026-06-20

**Summary:** Service-orchestration playbook ops: resolver fan-out normalization, extraction from tool
outputs, `$infotable` binding, nested payload building, JSON serialization, time-window resolution,
optional-branch handling, and analytics helpers.

### Added

- **Orchestration derive ops.** `normalize_resolved_things` and `extract_from_tool_output` (structured
  gaps with the equipment `input`, `no_where_match` when `where` removes all rows, clarify mode passes
  normalize `candidates`; duplicate `fields[].as` aliases are rejected).
- **`$infotable` binding.** Playbook tool calls can build InfoTable arguments from derive `$ref` rows
  plus a `dataShapeName`. Supported for repository extended tools only; validation fails closed against
  the extended-tool registry. Nested `invoke_service.parameters.$infotable` is not supported.
- **Nested payload ops.** `build_nested_object` (`$map`, `$path`, `$src` inside `$map.each` only,
  `$nodeRef`, `$literal`; caps; `minParents` yields `needs_clarification`) and `json_stringify`
  (`maxBytes`, error `JSON_STRINGIFY_TOO_LARGE`). `$nodeRef` paths are validated at authoring time and
  resolution errors are no longer swallowed.
- **`resolve_time_window_for_playbook`.** Matches quick-interval rows or resolves an explicit UTC range;
  defaults to Today; `onUnsupported` chooses clarify or gap.
- **`empty_rows_if_skipped`.** Produces empty rows when optional branch nodes are skipped; `$ref` /
  `$nodeRef` to skipped nodes resolve to null.
- **Analytics derive ops.** `add_computed_fields` (datetime difference in minutes, numeric arithmetic,
  `onNull` modes; `expr.left`/`expr.right` paths validated), `collect_values` and `join_values`.

---

## [0.1.189] — 2026-06-18

**Summary:** Document answers now cite PDF sources as markdown links built from tool-returned links.

### Changed

- **Citation grounding.** The routing guide and `get_document_chunk` description require final answers
  to cite sources as markdown links using tool-returned `sourceLinks[].href` (including `#page=`). The
  document-knowledge eval checks for `[label](/Thingworx/FileRepositories/...#page=N)` links.

---

## [0.1.188] — 2026-06-18

**Summary:** Document chunk tools v1: default-off `search_document_chunks` and `get_document_chunk`
built-ins backed by a FileRepository chunk-package index, with deterministic search, source PDF page
links, stale-cache fallback and routing guidance.

### Added

- **Document chunk tools.** New setting `AgentSettings.documentKnowledgeBuiltinsEnabled` (default
  `false`) registers `search_document_chunks` and `get_document_chunk`. When disabled the names are not
  reserved, so extended tools may use them.
- **Repository and index settings.** `documentKnowledgeRepository`, root path, TTL, document/chunk
  caps, and search/snippet/chunk character limits. Out-of-range values are clamped with a
  `CONFIG_VALUE_CLAMPED` warning.
- **FileRepository index.** Bounded in-memory index with TTL cache and aggregated warnings; manifest
  errors distinguish `MANIFEST_INVALID_JSON` and `MANIFEST_INVALID_SHAPE`; source links come only from
  manifest paths. On rebuild failure the previous index is served with `INDEX_REBUILD_FAILED_USING_STALE`.
  Cache hit/miss/rebuild/stale events are logged at info level.
- **Deterministic search.** Metadata + lexical scoring with snippet budgets; out-of-range limits are
  clamped with `LIMIT_CLAMPED`; results include scores, snippets and source links.
- **Routing guidance.** Check live status first, search after a concrete issue, use
  `get_document_chunk` for citations, never invent links. Design: `docs/agent/document-chunk-tools.md`.
- **Eval suite.** `docs/agent/evals/document_knowledge_v1.yaml`; the live-status-first case is gated by
  `AGENT_EVAL_DOCUMENT_KNOWLEDGE_LIVE_STATUS=1`.

### Fixed

- **FileRepository resolution.** Missing platform classes now degrade to an empty result instead of
  throwing (affects document-knowledge, taxonomy, playbook and configuration loading).

---

## [0.1.187] — 2026-06-11

**Summary:** End-to-end turn cancellation: `ParlerGateway.CancelUserPrompt` stops parked (awaiting
approval) and running turns, `GetConnectionInfo` advertises `capabilities.supportsCancellation: true`,
and the widget gains a Stop button.

### Added

- **`ParlerGateway.CancelUserPrompt` (parked turns).** Removes the pending approval, writes synthetic
  `TURN_CANCELLED` tool rows for replay pairing, emits `approval.resolved`
  (`hitl_resolution_source: "gateway_user_stop"`) then `session.cancelled`; no post-approval LLM call.
- **Running-turn cooperative cancel.** Returns `accepted` (with `alreadyRequested` on repeat). The loop
  checks for cancellation after each LLM response and before/after each tool call. Tools that already
  ran keep their real results; unrun sibling tool calls get `TURN_CANCELLED` rows. The turn ends with
  `session.cancelled` and clears the busy state.
- **Statuses.** A repeat stop within 5 minutes returns `already_terminal`; with no pending or running
  turn, `not_active` is returned promptly without waiting on the active turn's lock; `wrong_agent` for a
  mismatched agent.
- **Capability flag.** `GetConnectionInfo` emits `capabilities.supportsCancellation: true` (strict JSON
  boolean). See `docs/agent/turn-cancellation-control.md`.

### Added (parler-ui)

- **Stop button.** Shown when `supportsCancellation` is strictly `true`. Calls `CancelUserPrompt`, shows a
  stopping state, retries once after 300 ms on `not_active`, and falls back to `session.cancelled` after
  15 s. An `unsupported` reply produces `session.cancel_unsupported_local`; a late `session.done` for
  that request still merges, while `session.error` / `session.superseded` clear it.

### Fixed

- **Cancel/approval races.** A stop can no longer be followed by a stray approval card or an
  `AWAITING_APPROVAL` transcript; a cancel that arrives as an approval is created returns cancelled; a
  replaced or expired pending approval cannot be consumed, and expiry side effects run only once.

### Known limitations

- Cancellation during an in-flight provider call takes effect only after that call returns.

### Compatibility

- Contract bundle 0.1.130 (`API_CONTRACT.md` 2.4.38; `UI_CLIENT_PROTOCOL.md` §1.1 rules 7, 7c, 8, 9).
  Import together with widget 0.1.80.

---

## [0.1.186] — 2026-06-08

**Summary:** Playbook input resolution: pair-health playbooks resolve human asset identifiers through
`resolve_thing`, and machine-utilization playbooks map machine labels to canonical Thing names.

### Added

- **`normalize_resolved_thing` derive op.** Maps `resolve_thing` outputs (unique, large, ambiguous,
  not found, taxonomy errors) to success rows or `needs_clarification` with bounded `candidates`.
  Authoring validation requires `sourceNodeId` to point at a `resolve_thing` tool call.
- **`pick_taxonomy_row`** accepts `whenAssetTypeMissing: "empty_taxonomy"` when no asset type is given.
- **`match_identifier_in_rows`** (deterministic row matching over `$ref` rows; `$table` rejected) and
  **`pick_branch_output`** (merges exclusive `condition` branches).

### Changed

- **`cross_asset_pair_health`.** Now runs `resolve_thing` → normalize → pair flattening; `assetType` is
  optional. The `start_playbook` description no longer implies both assets must share a type.
- **`machine_utilization_summary`.** Resolves the machine via `resolve_thing` or a machine-listing
  fallback before calling `utilization_records_by_machine` with the canonical `Machine`.
- **Safer identifier matching.** Suffix matching is one-directional (a short user suffix may match a
  canonical name, not the reverse), and a match must yield a canonical Thing-name column (`name`,
  `Name`, `ThingName`); otherwise the result is `needs_clarification`.

---

## [0.1.185] — 2026-06-08

**Summary:** Playbooks load from self-contained `/playbooks/<id>/playbook.json` packages with partial
loading and structured diagnostics; also clarifies `primary_property` evidence wording.

### Changed

- **Playbook directory packages.** Playbooks are discovered as `/playbooks/<id>/playbook.json`
  (dot-prefixed directories skipped). The registry loads if at least one package is valid; a malformed
  package no longer aborts the others. A maximum package count applies. `GetAgentRuntimeSnapshot`
  reports `discoveryPattern`; `ValidateAgentConfigurationRepository` reports per-package diagnostics
  (severity, path, code) and applies playbook ids as reserved slash ids before skills load. The legacy
  `playbooks.json` is no longer tracked in the configuration fingerprint.
- **`primary_property` evidence wording.** Evidence states when a property was selected from alerts,
  labels the numeric name match as a trend-planning hint, and notes when numeric history confirms the
  property, reducing conflation in model summaries.

---

## [0.1.184] — 2026-06-07

**Summary:** Playbook final summaries now receive evidence from internal tool artifacts, so prose does
not contradict emitted charts or numeric history.

### Changed

- **`llm_summary` evidence.** Expands `fan_out` child tool outputs, adds compact `uiArtifact.chart`
  lines from chart metadata (no series data, also when the child has no evidence config), and reconciles
  the `primary_property` name hint with properties that have proven numeric history.

### Fixed

- **Evidence byte budget.** Evidence truncation is UTF-8 byte-safe, so multibyte names and values can no
  longer exceed `maxEvidenceBytes`; fan-out evidence stays within the cap.

---

## [0.1.183] — 2026-06-07

**Summary:** Completes the generic playbook derive ops needed for the cross-asset health workflow.

### Added

- **Generic derive ops.** `filter`, `sort`, `top_n`, `group_by`, `aggregate`, `build_targets`,
  `join_by_key`, `pick_one`, `collect_gaps` and `flatten_fan_out_rows`.
- **`flatten_fan_out_rows`.** Injects columns from the fan-out `item` via `injectFromItem` (`from`/`as`)
  and reports logical `totalCount` separately from `returned` when capped.
- **Reference workflow.** A reference playbook shows the cross-asset pair-health workflow built from
  generic ops instead of a business-specific op.

---

## [0.1.182] — 2026-06-07

**Summary:** Playbook-internal tool calls emit the same chart/table UI artifacts as top-level tools,
persist Stream rows for history replay, and are skipped when rebuilding LLM context.

### Changed

- **Internal tool artifacts.** Playbook-internal tools use the same UI artifact emission path as model
  tool calls. Their Stream `tool` rows are marked `_parlerPlaybookInternalOmitFromLlmRehydrate`, so
  conversation rehydration skips them and history export strips the marker before chart/table hydration.
- **Durability.** Rows are appended to the Stream before live emission, so replay does not depend on
  live client health; slash-command turns on the AlwaysOn path also emit artifacts.

---

## [0.1.181] — 2026-06-07

**Summary:** Adds the generic playbook derive-op foundation with a first `project` op.

### Added

- **`project` derive op.** Strict object row handling (a lone object `rows` is accepted), JSON null for
  missing fields, `default` applied before `dropNullOnlyRows`, and `GENERIC_INPUT_TOO_LARGE` when input
  exceeds the row cap. Validator checks and evidence counts included. Design:
  `docs/agent/playbook-generic-ops-foundation.md`.

---

## [0.1.180] — 2026-06-07

**Summary:** Playbooks can use more built-in tools, including `invoke_service` under the existing
policy and HITL rules. Playbook tool steps can also project root-level evidence fields, and a
Playbook that hits a HITL gate no longer leaves a dangling pending approval.

### Added

- **More Playbook-safe built-ins.** `playbookSafe=true` now covers `invoke_service` plus the cached,
  entity, alert, schema and chart tools. The executor aliases `query_numeric_property_history` and
  `query_value_stream_property_history` are accepted in Playbooks. `set_property_value` stays
  `playbookSafe=false`.
- **Root-field evidence for Playbook tool steps.** A new optional
  `evidence.includeToolOutputRootFields` on `tool_call` lists scalar keys from the tool JSON root
  (for example `query_alert_history` `appliedStartTime` / `rowCount`). They are printed before any
  `evidence.table` rows, and still appear when `rowCount` is 0 or when no `evidence.table` is set.
  An empty array is rejected at validation.

### Fixed

- **HITL inside Playbooks.** A tool that requires approval during a Playbook now fails that node with
  `PLAYBOOK_HITL_REQUIRED` and removes the pending-approval row, so terminal Playbook failures leave
  no live pending approvals. AlwaysOn HITL enqueue behaves the same as in normal agent turns.

---

## [0.1.179] — 2026-06-08

**Summary:** A shared ThingName preflight now gates property reads, alert tools, Thing-target
`invoke_service` and `set_property_value`, and Thing discovery tools. Blank or non-canonical Thing
names return structured errors before any platform call.

### Added

- **ThingName preflight for property tools.** `get_property_values` and `query_property_history`
  check the `thingName` with a visibility-aware lookup. Blank values return
  `THINGNAME_VALUE_REQUIRED`; non-canonical or invisible names return `IDENTITY_RESOLUTION_REQUIRED`,
  with a `recoveryHint` only when `resolve_thing` can recover this turn. Successful calls use and echo
  the canonical Thing name; history queries gate once per call.
- **Alert tools.** `query_alert_summary`, `query_alert_history` and `acknowledge_alerts` apply the
  same preflight and do nothing on the platform until it passes.
- **`invoke_service` and `set_property_value`.** Thing-target `invoke_service` gates `entityName`
  (null/empty returns `THINGNAME_VALUE_REQUIRED`). `set_property_value` gates the Thing before HITL,
  snapshot, protected-value checks and the approved write.
- **Discovery tools.** `discover_thing_members` and the Thing path of `discover_properties`,
  `discover_services` and `get_service_definition` return the same two error codes (older
  `THING_NOT_FOUND_OR_NOT_VISIBLE` errors are remapped where applicable).

### Compatibility

- `CONTRACTS/TAXONOMY_RESOLVER.md` §7–§7.6, resolver revision 2.0.15, contract bundle 0.1.111.
- Tool `thingName` schema descriptions changed (model-facing).

---

## [0.1.178] — 2026-06-07

**Summary:** Adds a connection version handshake: `ParlerGateway.GetConnectionInfo` reports the
agent and extension version for a conversation.

### Added

- **`ParlerGateway.GetConnectionInfo`.** Returns `parler.connection-info.v1` JSON as a STRING
  `result` with `schemaVersion`, `conversationId`, `agent` (`thingName`, `extensionVersion`, and
  `implementationVersion` when it differs from the display version) and `serverTime`. The caller must
  own the thread, and the trimmed `agentThingName` must match the thread's `agentName` on an
  `AIAgent`-based Thing. Calls are logged as `PARLER_CONNECTION_INFO`; the widget's echo is sanitized
  before logging.
- **Build version metadata.** The extension JAR carries a generated
  `parler-runtime-version.properties` (`artifact_version` / `implementationVersion`).

### Compatibility

- `CONTRACTS/API_CONTRACT.md` revision 2.4.24, contract bundle 0.1.103.

---

## [0.1.177] — 2026-06-06

**Summary:** Adds admission checks for model tool calls: `query_entities` queries are validated
before execution, and `get_agent_skill` is hidden from the model when no skills are configured.

### Added

- **`query_entities` query validation.** Object and stringified `query` values are validated before
  execution: leaf `type` values, operators (`UNSUPPORTED_OPERATOR`), composite `NOT`
  (`UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES` with a fixed `recoveryHint`), leaf/depth caps
  (`TOO_MANY_PREDICATES`), per-type `value` / `tags` / `IN` / `BETWEEN` / `NEAR` requirements and a
  `LIKE` pattern length cap. `MATCHES` / `NOTMATCHES` are not supported.
- **Hidden `get_agent_skill`.** The tool is omitted from the model's tool list when the skill catalog
  is empty (it still executes). `GetAgentRuntimeSnapshot` adds `tools.modelFacingSuppressed` as
  `[{ "name", "reason" }]` with reason `empty_skill_catalog`.
- Operator notes: `docs/agent/model-tool-admission-guardrails.md`.

---

## [0.1.176] — 2026-06-06

**Summary:** The legacy discovery tools are no longer advertised to the model by default; they still
execute for replay and direct calls.

### Changed

- **Legacy discovery is executor-only.** `discover_properties`, `discover_services` and
  `get_service_definition` are omitted from the model's tool list. The new AgentSettings field
  `advertiseLegacyServiceDiscoveryTools` (default `false`) restores `discover_services` and
  `get_service_definition`; `discover_properties` stays hidden in all modes.

### Added

- **`executorOnly` in `/tools/extended_tools.json`.** Optional per-tool boolean (default `false`).
  When `true` the tool executes but is not shown to the model; Playbook validation still sees it.
  A non-boolean value invalidates the manifest.
- **Snapshot fields.** `GetAgentRuntimeSnapshot` adds `tools.advertiseLegacyServiceDiscoveryTools`
  and `tools.extended[].executorOnly`.

---

## [0.1.175] — 2026-06-06

**Summary:** New built-in `analyze_entity_set` performs set algebra over two cached entity lists
(`difference`, `intersection`, `union`, `symmetric_difference`) and always returns a fresh `cacheId`.

### Added

- **`analyze_entity_set`.** Takes two explicit cached operand tables and an `operation`. Only the
  keys `operation`, `left`, `right`, `projectColumns`, `maxItems` and `offset` are accepted; any other
  key returns `INVALID_PARAMETERS`. PASSWORD columns are refused. Numeric keys are canonicalized
  across operands.
- **Operation semantics.** `difference` and `intersection` project from the left operand and fail
  with `ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS` only when duplicate left keys disagree on projected
  columns (the right operand is membership-only). `union` and `symmetric_difference` check
  duplicates per operand, require overlap agreement for `union`, pad heterogeneous `projectColumns`
  with nulls, and order output keys lexicographically by canonical key string.
- **Routing.** Grouping and charting stay with `tabulate_cached_result` /
  `build_chart_from_tabular_result`; the routing guide documents the set-then-tabulate flow.
  `analyze_entity_set` results are not used for per-turn chart resolution.

### Compatibility

- `CONTRACTS/ENTITY_SET_TOOL.md` revision 1.0.6, contract bundle 0.1.102.

---

## [0.1.169] — 2026-06-06

**Summary:** `get_entity` is no longer advertised to the model, and tool descriptions now steer
metadata work to `describe_entity_schema` and `discover_thing_members`.

### Changed

- **`get_entity` is executor-only.** It is omitted from the model's tool list (25 advertised
  built-ins) but still executes. `GetAgentRuntimeSnapshot` adds `tools.executorOnly`, a sorted list
  of executor-only tool names (aliases excluded).
- **Model-facing copy.** Descriptions of `discover_properties`, `describe_entity_schema` and
  `get_entity` point normal work to `describe_entity_schema` / `discover_thing_members`. The
  `INVOKE_SERVICE_RESULT_TOO_LARGE` envelope no longer lists `get_entity` in
  `recoveryHint.alternatives`. The replay routing guide, the `get_entity` unsupported-Thing message
  and the `enableBuiltInTools` setting description were updated.

---

## [0.1.168] — 2026-06-06

**Summary:** Stronger model guidance toward `discover_thing_members` for Things.

### Changed

- **Routing and tool descriptions.** The routing guide and the descriptions of
  `discover_thing_members`, `discover_properties`, `discover_services` and `get_service_definition`
  separate the generic `discover_services` → `get_service_definition` → `invoke_service` chain from
  Thing-first `discover_thing_members`, which is the only built-in that covers events and
  subscriptions on Things. No tools were removed.

---

## [0.1.167] — 2026-06-07

Version bump for ThingWorx re-import; no functional changes from 0.1.166.

---

## [0.1.166] — 2026-06-06

**Summary:** Tool guidance now prefers `discover_thing_members` for concrete Thing metadata.

### Changed

- **Soft deprecation of legacy discovery.** The routing guide and the descriptions of
  `discover_properties`, `discover_services` and `get_service_definition` present
  `discover_thing_members` as the default for Thing metadata; the legacy tools remain available and
  use the same engine. Schemas and registrations are unchanged.

---

## [0.1.165] — 2026-06-06

**Summary:** New built-in `discover_thing_members` for bounded metadata discovery on concrete Things;
the legacy Thing discovery tools delegate to it.

### Added

- **`discover_thing_members`.** Facets `properties` / `property`, `services` / `service`, `events` /
  `event` and `subscriptions` (configured multi-event subscriptions). Only members visible to the
  caller are returned. Singular lookups match exactly, then case-insensitively. An optional
  `dataShape` filter applies to `properties` and `events`. Event metadata is read-only (no firing).
  List facets are paginated; service rows include category and `resultBaseType`.
- **Errors.** `{status, code, message, recoveryHint?}` with codes such as
  `EVENT_NOT_FOUND_OR_NOT_VISIBLE`, `INVALID_FACET`, and `UNSUPPORTED_TOOL_PARAMETER` (for
  `memberName` on list facets, `includePrivateServices`, `subscriptionScope` or unknown keys).
  Not-found hints suggest `namePrefix`.

### Changed

- **Delegation.** `discover_properties` and Thing-targeted `discover_services` /
  `get_service_definition` use the same implementation with their existing response shapes. Routing
  and oversize-recovery text prefer `discover_thing_members` for Thing workflows.

---

## [0.1.164] — 2026-06-05

**Summary:** New built-in `describe_entity_schema` for bounded schema reads on ThingTemplates,
ThingShapes and DataShapes.

### Added

- **`describe_entity_schema`.** Facets: `summary`, paginated `properties` / `services` / `events`,
  DataShape `fields`, and singular public `service` detail. Only visible entities are read.
  `includePrivateServices` and subscriptions are rejected. `scope=local` reads the template's or
  shape's own members; `effective` reads merged members. If the platform cannot supply the data, the
  tool returns `DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE` instead of an empty success.
- `discover_properties` and `INVOKE_SERVICE_RESULT_TOO_LARGE` recovery text mention the new tool.

### Changed

- **Snapshot docs.** `GetAgentRuntimeSnapshot.tools.builtIn` is the static built-in list; merged
  tools such as `start_playbook` may not appear there (`docs/agent/configuration-repository.md`).

---

## [0.1.163] — 2026-06-06

**Summary:** `GetAgentRuntimeSnapshot` separates schema tools from replay aliases, and a missing
Playbook catalog is logged at INFO.

### Changed

- **Runtime snapshot.** `tools.builtIn` lists only LLM schema built-ins; `tools.executorAliases` maps
  executor-only property-history names to `query_property_history`.
- **Playbook catalog logging.** A missing or blank `/playbooks/playbooks.json` logs at INFO instead
  of ERROR; a catalog `READ_ERROR` still logs at ERROR.

---

## [0.1.162] — 2026-06-05

**Summary:** A single `query_property_history` tool replaces the separate numeric and value-stream
history tools on the model's tool list.

### Changed

- **Unified property history.** `query_property_history` routes by property base type
  (NUMBER/INTEGER/LONG vs other logged types). `query_numeric_property_history` and
  `query_value_stream_property_history` remain executor-only aliases and are reserved names for
  extended tools.
- **Value-stream evidence.** Non-numeric history returns `VALUE_STREAM_HISTORY_INLINE` compact
  evidence (`parler.value_stream_history.compact.v1`, always a `cacheId` and capped `sampleRows`),
  which Stream history rehydration accepts. Non-numeric `actions` are rejected with
  `NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE` (non-empty array) or `INVALID_ACTIONS_SHAPE`
  (null / non-array).
- **Playbooks.** The Playbook allowlist and derived evidence handle compact numeric bodies without
  `points`.
- **SCPA utilization sample.** The skill and playbooks in `dev_data/scpa_utilization` use
  `query_property_history`.

### Compatibility

- Contract bundle 0.1.95 (`CONTRACTS/CHART_CONTRACT.md`). Skills and Playbooks that name the old
  history tools should move to `query_property_history`.

---

## [0.1.161] — 2026-06-05

**Summary:** Retires the hierarchy composite built-ins.

### Removed

- **`query_asset_count_under_hierarchy_node` and `compare_alert_status_between_hierarchy_nodes`.**
  Use `resolve_asset_type`, `query_entities*` with `hierarchyNodeName`, the alert tools and the
  cached tabular tools instead (`docs/agent/hierarchy-composite-retirement.md`).

---

## [0.1.160] — 2026-06-05

**Summary:** Context planning now accounts for the Provider Thing's single-request token limit, so
tool-heavy history is trimmed before the LLM call instead of failing with `single_request_too_large`.

### Changed

- **Provider-aware request cap.** In rate-control `enforce` mode, providers expose an input size cap
  derived from max-output tokens, reserve strategy, estimate safety multiplier,
  `maxSingleRequestTokens` (or TPM) and a safety margin. The effective request cap is the minimum of
  the model input cap, AgentSettings `llmContextMaxChars` and this provider cap; older tool evidence
  is dropped first. `configuredCapChars` telemetry still reports the AgentThing setting.

---

## [0.1.159] — 2026-06-05

Internal refactoring; no behavior change.

---

## [0.1.158] — 2026-06-05

**Summary:** Bounds the storage of numeric chart sidecars, cleans chart bookkeeping out of replayed
history, and applies one sample/large-result policy across tabular and list tools.

### Changed

- **Chart sidecar budget.** Compact numeric-history Stream rows store `chartBlock` only within a
  per-row budget; over-budget rows keep compact evidence plus budget metadata and do not restore the
  full series after reload.
- **Cleaner replay.** Rehydrated assistant prose strips `chartBlock` and `chartBlock*` fields before
  LLM replay, keeping cache continuity markers.
- **Shared sample policy.** `invoke_service` INFOTABLE results, `query_entities`,
  `query_entities_by_taxonomy`, `list_entities_by_type` and taxonomy identifier evidence use one
  policy to decide between inline and large (cached) evidence.

---

## [0.1.157] — 2026-06-05

**Summary:** Numeric history charts survive history export and reload without replaying full points
to the LLM.

### Added

- **Chart history hydrate and reload restore.** Compact numeric-history rows can carry a persisted
  `chartBlock`. History export hydrates charts only when `chartEmitted=true`, and Stream rehydration
  restores the full `timestamp` / `value` series into the original conversation `cacheId`, stripping
  `chartBlock` from replayed prose.

### Changed

- **Neutral evidence framing.** Replayed compact tool evidence uses a neutral prose prefix instead of
  fetch-specific wording.

---

## [0.1.156] — 2026-06-05

**Summary:** Stream history rehydration accepts compact numeric-history evidence.

### Added

- **Numeric-history rehydrate.** `parler.numeric_history.compact.v1` and numeric
  `parler.infotable.matrix.v1` evidence (`NUMERIC_HISTORY_INLINE` / `NUMERIC_HISTORY_AGGREGATES`) are
  restored as assistant prose rather than orphan tool rows, with `parlerRehydratedCacheHistorical` /
  `parlerRehydratedCacheLive` cache markers.
- **Guards.** Numeric evidence that still carries a `points` array is rejected, PASSWORD columns
  still block restoration, and oversized sample arrays stay capped.

---

## [0.1.155] — 2026-06-05

**Summary:** Oversized tool results are compacted before LLM replay and Stream persistence, and
numeric history returns compact evidence with exact aggregates while charts keep full points.

### Added

- **Tool-result compaction.** Oversized tool success JSON is compacted structurally: small answer
  fields such as `aggregates` and schema arrays are kept, other data arrays are sampled. Logs report
  `rawReplayChars`, `replayChars` and `compactRatio`. The full JSON stays available for live
  table/chart downlinks, and tool-specific full bodies take precedence over the generic fallback.
- **Compact numeric history.** `query_numeric_property_history` returns `NUMERIC_HISTORY_INLINE` /
  `NUMERIC_HISTORY_AGGREGATES` evidence (`columns`, capped `sampleRows`, exact `aggregates`,
  `cacheId`); full points go only to the live chart when a trend chart is emitted.

---

## [0.1.154] — 2026-06-05

**Summary:** Anthropic Messages provider can omit sampling parameters for deployments that reject
them.

### Added

- **`samplingParametersMode` on `AnthropicMessagesProvider`.** `legacy` (default) keeps the current
  request shape; `omit` drops `temperature` from `TestConnection` and chat requests. Use `omit` for
  deployments such as Azure AI Foundry Claude Opus 4.8, which reject sampling parameters.

---

## [0.1.153] — 2026-06-04

**Summary:** Live diagnostics collector redaction fix.

### Changed (tooling)

- **`parler-collect-live`.** Credential redaction matches exact field names, fixing over-redaction of
  `*Tokens` LLM counters. Stream `content`, `toolCalls`, `llmUsageJson` and `raw` are kept verbatim;
  redaction applies to parsed companions and `agent-status.json` `json`. See
  `docs/agent/collection-tool.md`.

---

## [0.1.152] — 2026-06-04

**Summary:** Repository file fingerprints now match for binary files, plus live diagnostics
collector redaction and timestamp fixes.

### Fixed

- **`FileRepository.LoadBinary` results.** InfoTable results with `Content` / `result` BLOB cells are
  unwrapped, so `configurationRepository.files[]` `sha256` matches the loaded content.

### Changed (tooling)

- **`parler-collect-live`.** Stream rows redact keys recursively, including inside
  `toolCalls[].function.arguments` JSON strings, and `rows[].raw` carries the same redacted strings.
  ApplicationLog and stream timestamps are normalized to ISO UTC.

---

## [0.1.151] — 2026-06-04

**Summary:** Tables built from tool results get a server-authored `presentationTitle` based on the
tool that actually ran.

### Added

- **`executedToolName` on tool rows.** Tool message rows record the executed tool name (ignored on
  older DataShapes that lack the field). It is kept through history export, cached-fetch rewrites,
  stream export sidecars and HITL/synthetic tool rows.
- **Table titles.** Infotable/invoke table titles resolve from the executed tool name, then the body
  `tool` field, then invoke `entityName` / `serviceName`, then literal `invoke_service`. Tool names
  are no longer inferred from the result's shape.

---

## [0.1.150] — 2026-06-01

**Summary:** Fixes offline packaging so the extension JAR no longer bundles ThingWorx platform and
build/test libraries. Also covers changes since 0.1.140: decoupled taxonomy files, reasoning-token
telemetry, an `invoke_service` result size cap and narrower metadata tool inputs.

### Fixed

- **Extension packaging.** The deployable JAR now contains only extension-owned runtime dependencies
  (currently `commons-math3`), not ThingWorx platform JARs, Gradle plugins, JUnit or logging stacks.
  The build fails if forbidden classes appear in the JAR.
- **Extension ZIP metadata.** The shared JAR is declared once under `ExtensionPackage` →
  `JarResources`; per-`ThingPackage` entries are removed to avoid duplicate manifest entries that
  could misbehave on platform restart. ZIP layout is unchanged (`lib/common/`).

### Changed

- **Independent taxonomy files.** `identity-types.json` (v3 array) and `asset-types.json` (v3 object
  map) load independently; a missing or empty companion file produces scoped warnings instead of
  failing the whole cache. With no asset rows, `list_asset_types` / `resolve_asset_type` return
  `ASSET_TYPES_NOT_CONFIGURED` and `resolve_thing` with `assetTypeKey` returns
  `ASSET_TYPE_NOT_FOUND`.
- **Runtime and validation parity.** `ValidateAgentConfigurationRepository` and the runtime agree
  when one file is invalid: a usable companion loads with the other side's errors downgraded to
  warnings; a malformed asset file with no identity file reports `unavailable` (or
  `assetTypesJson.status: invalid`) with diagnostics. `TAXONOMY_LEGACY_FILE_PRESENT` is no longer
  attached in these cases.
- **`invoke_service` size cap.** Non-tabular success bodies over 64 kB return
  `INVOKE_SERVICE_RESULT_TOO_LARGE` with a recovery hint to narrower tools. Tabular bodies
  (`resultKind=INFOTABLE*` or a top-level `cacheId`) are exempt and page via `fetch_cached_result`.
  The tool description warns against full-metadata platform services such as `GetMetadata`.
- **Narrower metadata inputs.** `discover_properties` is Thing-only: `entityType` is removed,
  `thingName` is required, and not-found returns `IDENTITY_RESOLUTION_REQUIRED`. `get_entity`
  accepts only ThingTemplate, ThingShape and DataShape; Things return
  `UNSUPPORTED_ENTITY_TYPE_FOR_GET_ENTITY`.

### Added

- **Reasoning-token telemetry.** OpenAI/Azure `usage.completion_tokens_details.reasoning_tokens` is
  reported as optional per-round `reasoningTokens` (`LLM_USAGE`, assistant-row usage JSON) and
  turn-level `reasoningTokensTotal` (`LLM_TURN_PERFORMANCE`, `done.llm_usage`, UI turn details).
  `completionTokens` keeps its meaning (total provider output).

### Compatibility

- `CONTRACTS/TAXONOMY_RESOLVER.md` 2.0.7, contract bundle 0.1.92. Pair with widget 0.1.74 for
  reasoning-token display.

---

## [0.1.140] — 2026-05-26

**Summary:** Table wires keep `cacheId` so the UI can pair tables with charts; charts are restored
from history; context compaction (budget planning, replay trimming, storage trimming) is on by
default; semantic taxonomy v3 replaces the old taxonomy tools; multi-chart turns, a presentation
phase, repetition blocking and turn-performance telemetry are added.

### Added

- **Table `cacheId` and presentation titles (contract bundle 0.1.87).** Inline table wires from
  `tabulate_cached_result`, `invoke_service`, `list_entities_by_type` and `query_entities` keep the
  top-level `cacheId`, so the UI can pair an aggregated table with a chart whose
  `chart.source.sourceCacheId` matches. Tables may carry an optional `presentationTitle` taken from
  structured tool metadata; `invoke_service` INFOTABLE results include `entityName`/`serviceName`.
- **Context budget planning.** Before every LLM call the agent logs `LLM_CONTEXT_PLAN` with
  per-bucket character counts (stable system, tools, ephemeral, current user, active batch,
  transcript, evidence), configured and effective caps and the derived history budget. New setting
  `AgentSettings.llmContextMaxChars` (default 750000, clamped 10000..2000000); the effective cap
  also respects a built-in per-model input limit for common Anthropic and OpenAI models.
  `LLM_USAGE` and `LLM_TOOL_SCHEMA_USAGE` log `parlerRequestId=` (also in assistant
  `llmUsageJson`) to join with `LLM_CONTEXT_PLAN.requestId`.
- **Per-round context trimming.** Before each provider round the outbound copy drops the oldest
  historic evidence batches, then the oldest transcript pairs, while keeping the system prompt,
  protected ephemeral rows, the current user message and the active tool batch. If the context
  cannot fit, the turn fails closed with `LLM_CONTEXT_PLAN_FAIL` (for example
  `CANNOT_FIT_AFTER_TRIM`, with cumulative drop counts).
- **Replay compaction always on.** Tier B replay compaction is always active. The JVM property
  `com.thingworx.parler.llmReplayCompaction.disableUnsafe=true` suppresses it for the process
  (logged as a warning).
- **Shrink-only Tier B promotion.** Matrix and cohort-bundle rewrites are skipped unless they are
  strictly smaller (`LLM_TIER_B_SKIP reason=no_shrink`; `LLM_TIER_B_PROMOTED` adds
  `skippedNoShrink`). `get_entity` is now fully implemented; its stamped
  `parler.entity.metadata.v1` bodies are compacted in replay to `parler.entity.metadata.summary.v1`
  (property names and base types, service and event names only), counted as
  `entityMetadataPromoted`.
- **Persisted-conversation compaction.** After a successful `Chat`, `ChatAsync`,
  `ParlerStreamToRemoteThing` or post-tool loop, stored conversations get Tier B replay promotion
  and are then trimmed (historic evidence, then transcript pairs) to fit `llmContextMaxChars`
  (`CONVERSATIONS_STORAGE_TRIM`). Both steps are skipped while a HITL approval is pending for the
  conversation.
- **Compact `fetch_cached_result` persistence.** Compact persisted `fetch_cached_result` Stream
  bodies are stamped `$format=parler.fetch_cached_result.compact.v1` and are restored on rehydration
  as framed assistant prose rather than orphan tool rows.
- **Multiple charts per turn (contract bundle 0.1.82).** Small INFOTABLE results from extended tools
  and direct invokes may include a top-level `cacheId` (same key space as `fetch_cached_result`).
  Pending charts flush in order, so several charts can be emitted in one turn; telemetry adds
  `parlerChartWireEmittedCount`. See `CHART_CONTRACT.md` §2.4–2.5.
- **Presentation phase (contract bundle 0.1.84).** After a complete tabular answer, a presentation
  round may call `build_chart_from_tabular_result` for registered chartable results, up to 6 calls
  (`PRESENTATION_ACTION_LIMIT` beyond that), followed by a forced tool-free summary round. Telemetry:
  `presentationPhaseEntered`, `presentationActionsRequested`, `presentationActionsExecuted`,
  `presentationActionsBlocked`.
- **Repetition blocking (contract bundle 0.1.80).** A third consecutive identical tool call (same
  tool, arguments and result) within one turn returns a synthetic `REPETITION_BLOCKED` result instead
  of executing again; telemetry adds `repetitionBlockedCount`.
- **Semantic taxonomy v3 (contract bundle 0.1.77–0.1.79, `TAXONOMY_RESOLVER.md` 2.0.x).** Built-in
  tools `list_asset_types`, `resolve_asset_type` and `resolve_thing` replace `list_taxonomy_*` /
  `resolve_taxonomy_*`; `AgentThing` services `ListAssetTypes`, `ResolveAssetType` and
  `ResolveThing` replace `ListTaxonomyAssetTypes`, `ResolveTaxonomyAssetType` and
  `ResolveTaxonomyAssetIdentifier`. v3 uses an array `identity-types.json` plus a required
  `asset-types.json`. `resolve_thing` uses the first matching identity rule; `IDENTITY_AMBIGUOUS`
  returns at most 10 `candidates` with `ambiguousCandidatesTruncated`. ThingShape asset types no
  longer require the rule template to implement the shape.
- **Thing-name preflight for extended tools (contract bundle 0.1.80).** Configuration-repository and
  playbook extended tools reject non-canonical `THINGNAME` arguments with
  `IDENTITY_RESOLUTION_REQUIRED` (with `recoveryHint`, and `assetTypeKey` when known) or
  `THINGNAME_VALUE_REQUIRED` when a required value is blank. In the SCPA utilization sample
  (`dev_data/scpa_utilization/tools/extended_tools.json`), the `utilization_records_by_machine`
  guidance stresses canonical Thing names.
- **Pie and grouped bar charts (contract bundle 0.1.74–0.1.76).** `build_chart_from_tabular_result`
  supports `kind: pie` (`pieSliceMode`, `pieMaxSlices`) and `seriesColumn` for grouped bars from long
  tables. `group_metric` adds `percent_of_total` and `percent_of_group` derived ops. Charts carry an
  optional `ChartBlock.source`.
- **Complete-answer marker for `group_metric`.** `CACHED_GROUP_METRIC_INLINE` may carry
  `answerSetComplete`, `sampleOnly`, `rowsOmitted` and `returnedRows` when the result has at most 50
  rows, 8 output columns, 8192 bytes of rows and no PASSWORD column; 21–50-row results may now be
  returned inline. `totalRows` is always the post-`having` group count, and `answerSetComplete` is
  omitted whenever `maxItems`/`offset` truncates. JVM property `parler.agent.answerSetComplete.enabled`
  (default true) disables the extended inline path. See `docs/agent/query-spec.md` §11.
- **Turn-performance telemetry (contract bundle 0.1.73).** `LLM_TURN_PERFORMANCE` and terminal
  `llm_usage` report `toolExecutionMaxConcurrency` (1; tools run serially), `multiToolCallRoundsCount`,
  `requestedMaxOutputTokensTotal` and `toolProtocolViolation` (`tool_call_after_tool_none`);
  `parallelToolUseDisabled` is removed. `AgentSettings.maxTokens` is passed through with no implicit
  2048 cap.

### Changed

- **Charts restored from history (contract bundle 0.1.86).** `build_chart_from_tabular_result`
  stores the full `chartBlock` on `CHART_EMITTED` results, and history export rebuilds `charts[]`
  from them. The prompt-regex end-of-turn chart rescue and the `chartRequestedButBuilderNotInvoked`
  telemetry were removed; the agent no longer inspects user text for chart keywords.
- **Chart telemetry is behavior-based (contract bundle 0.1.83).** `chartExpectedButMissing` is true
  only when `build_chart_from_tabular_result` ran this turn and no chart was emitted. After a
  recoverable chart failure with a chartable source, the chart tool stays available for one more
  round, and at most one end-of-turn chart retry runs after prose (`chartRescueAttempted`).
  `tool_choice: none` applies only when a round offers no tools.
- **Chart source resolution.** `source:last_invoke` resolves to the latest qualifying tabular result
  in the turn (`AMBIGUOUS_LAST_INVOKE` is removed). Any tool returning INFOTABLE or INFOTABLE_LARGE
  counts as a chart source. `CACHED_GROUP_METRIC_INLINE` caches the grouped table and returns its
  `cacheId` next to `sourceCacheId`.
- **Chart builder validation (contract bundle 0.1.75).** Grouped bars fail with
  `DUPLICATE_SERIES_CATEGORY` on a duplicate (x, series) pair; pies fail with `DUPLICATE_SLICE_LABEL`
  on duplicate non-zero labels (error includes `recoveryHint`). `percent_of_group` fails with
  `ZERO_DENOMINATOR` when a partition has no usable measure sum. `CHART_EMITTED` results mirror
  `ChartBlock.source` and report `truncationApplied`/`truncated`.
- **INFOTABLE null handling.** A missing required column is distinguished from an explicit JSON
  `null`; explicit `null` is rejected only for primary-key fields.
- **`get_service_definition` example for extended tools.** When the requested service is the target
  of a configured extended tool, `invokeExample` names that tool and uses top-level parameter
  placeholders instead of `invoke_service`.
- **Cached-table steering.** The routing guide prefers `tabulate_cached_result` /
  `summarize_cached_result` when cached data already has the needed columns. The measure `filters`
  description says to omit `filters` for unconditional measures; a `{"type":"TRUE"}` leaf now gets an
  `INVALID_PREDICATE` recovery hint (parser behavior unchanged).
- **Configuration repository validation.** `ValidateAgentConfigurationRepository` follows the same
  v2/v3 taxonomy branching as runtime and reports asset diagnostics against
  `/taxonomies/asset-types.json`. `TAXONOMY_LEGACY_FILE_PRESENT` fires only when a non-empty
  `asset-types.json` sits next to a v2 object identity file.

### Fixed

- **Tool rounds without tools.** OpenAI and Azure rejected `tool_choice` without `tools` (HTTP 400).
  Rounds with no tools now omit both fields; when tools are present, `tool_choice` none is sent with
  them. Provider requests no longer send `parallel_tool_calls` / `disable_parallel_tool_use`.
- **HITL pause with parallel tool calls.** When approval pauses a batch, the remaining sibling calls
  are stored and, on approve, reject, cancel or expiry, each receives a synthetic tool reply
  (`status: skipped`, `code: HITL_PAUSE_INTERRUPTED_BATCH`), so every `tool_call.id` gets a response.
- **HITL synthetic tool rows persisted.** Synthetic tool outcomes from HITL continuations are now also
  written to `AgentMessageStream`, not only to in-memory conversations.
- **Repetition registry cleanup.** The repetition registry is cleared on HITL cancel/reject and
  approval expiry.

### Removed

- `AgentSettings.enableLlmReplayCompaction` is no longer a setting; a stale ConfigurationTable key is
  ignored (logged at INFO).

### Compatibility

- Contract bundle 0.1.87. Pair with widget 0.1.73.
- Taxonomy tools and services were renamed (see Added); configuration repositories using v3 must
  provide `asset-types.json`. The sample import files (`dev_data/import_control.yaml`,
  `import_scpa_utilization.yaml`) upload `asset-types.json` together with the identity file.

---

## [0.1.139] — 2026-05-24

**Summary:** Filtered `sum` in `group_metric` now returns 0 when no row matches the measure filter,
and the model is steered to use `having` for grouped threshold questions.

### Fixed

- **Filtered `sum` with no matches.** In `tabulate_cached_result` `group_metric`, a `sum` with
  measure-level `filters` returns `0` when the group has source rows but none pass the filter. A plain
  `sum` over all-null cells still returns `null`; `avg` with no matches returns `null`.

### Changed

- **`having` steering.** The routing guide and the `having` parameter description direct the model
  to use `group_metric` with `having` for metric equality/threshold questions, and state that
  `sampleRows` is a preview and must not be treated as the full answer set (`totalRows` is the output
  count).

---

## [0.1.138] — 2026-05-19

**Summary:** `tabulate_cached_result` adopts ThingWorx-style query JSON (`filters`, `sorts`,
`maxItems`, `offset`) and rejects the old keys; tool schemas are fixed for strict providers; the
widget table preview is capped at five rows.

### Changed

- **Query JSON for `tabulate_cached_result`.** Decision modes and `group_metric` accept root
  `filters`, optional `sorts` (`fieldName`, `isAscending`, `isCaseSensitive`), `maxItems` and `offset`,
  plus root `fields` projection for `sort_topn`, `filter_*` and `group_metric`. Legacy keys (`where`,
  `sort`, `limit`, `sortBy`, `direction`, `op_name`) are rejected. Filters support `NOT`, `LIKE` and
  Parler extensions, with numeric-string column coercion. See `docs/agent/query-spec.md`.
- **Sort validation.** `sorts[].isAscending` is optional (defaults to ascending); a non-boolean value
  or a duplicate `sorts[].fieldName` returns `INVALID_PARAMETERS`.
- **PASSWORD checks first.** Legacy predicate column keys are also checked, so
  `PROTECTED_TABULAR_COLUMN_BLOCKED` is reported before legacy-shape errors.
- **Predicate schema discoverability.** Filter and `having` descriptions include canonical JSON
  examples and the allowed `op` and composite keys, reducing retries with wrong key names.
- **`percentile` error code.** `p` outside [0,100] is documented as `INVALID_PARAMETERS`.
- **Table preview (parler-ui).** Tables render at most 5 body rows; the footer shows
  `Showing N of totalRows`. Wire rows and `shownRows` are unchanged.

### Fixed

- **Strict provider schemas.** `tabulate_cached_result` array parameters (`measures`, `derived`,
  `sort`) now declare `items`; Azure OpenAI rejected arrays without them. All built-in tool schemas
  are checked for compatibility. The `yReferenceLines` item schema documents `y`, `label`, `role`.
- **Predicate guide example.** A typo in the composite predicate example was fixed.

---

## [0.1.137] — 2026-05-19

**Summary:** `group_metric` gains more measure types, `first`/`last` keep null `orderBy` values last
in both directions, and `mode` rejects text columns. Widget unchanged (0.1.61).

### Added

- **More `group_metric` measures.** `count_distinct` (10k cap), `weighted_avg` (`weightColumn`;
  negative weights → `INVALID_WEIGHT`), `median`, `percentile` (`p` in [0,100]), population
  `variance`/`stddev`, `first`/`last` (`orderBy`, `direction` default asc), and `mode` (numeric,
  boolean, datetime).

### Changed

- **Validation.** `mode` on STRING/TEXT columns returns `TYPE_MISMATCH`. `sum_values`/`multiply`
  `inputs` must all be non-blank strings. Explicit output sorts break ties by type-aware group keys.
- **PASSWORD guard.** PASSWORD columns are rejected in measure `weightColumn` and `orderBy`.

### Fixed

- **Null ordering in `first`/`last`.** Rows with a null `orderBy` value stay last for both asc and
  desc.

---

## [0.1.136] — 2026-05-19

**Summary:** Utilization playbooks pass `evidence.table` aggregates into `llm_summary` prompts, and
token usage from playbook summaries is now recorded on slash-command turns. Widget unchanged.

### Added

- **Playbook token usage.** Usage from each `llm_summary` step is accumulated and persisted on
  structured slash turns (`AgentMessageStream`, `done.llm_usage`, `ChatAsync` result). The
  `LLM_PLAYBOOK_RUN` log line includes token totals and `llmUsageJsonPresent`.

### Changed

- **Playbook summaries.** Utilization playbooks project `evidence.table` aggregates/stats into
  `llm_summary` prompts.
- **Usage merging.** Combined usage sums numeric fields and keeps the latest non-empty string
  metadata.

---

## [0.1.135] — 2026-05-19

**Summary:** Fixes INFOTABLE row-shape resolution for extended tools and playbook registration.
Widget unchanged.

### Fixed

- **INFOTABLE DataShape resolution.** INFOTABLE parameters and nested INFOTABLE columns now resolve
  their DataShape through the platform metadata utilities, restoring schema generation for
  helper-backed utilization tools (`utilization_aggregate_by_state`,
  `utilization_stats_for_aggregate`, `utilization_machine_listing`,
  `utilization_machine_listing_with_dates`) and validation of playbooks that use them.

---

## [0.1.134] — 2026-05-22

**Summary:** Large-table replay: CSV export runs once for `fetch_cached_result`, so live UI and
history share the same export metadata, and replay-guard state is always cleaned up at turn end.
Widget 0.1.61; contract bundle 0.1.71 unchanged.

### Changed

- **Large-table replay control.** `fetch_cached_result` replays a compact body to the LLM while the
  live UI receives the full page; cached-page overclaims in answers are guarded.
- **Single CSV export.** The table export runs once on the full page and its `_parlerTableExport`
  metadata is copied onto the compact persisted row, keeping live and history CSV metadata aligned.
- **Turn cleanup.** Replay-guard state is cleared on every turn completion except when awaiting HITL
  approval.
- **History hydration.** Compact `fetch_cached_result` rows may be reconstructed as sample tables.

### Compatibility

- Contract bundle 0.1.71. Pair with widget 0.1.61.

---

## [0.1.133] — 2026-05-19

**Summary:** The UI shows when a turn is waiting on LLM rate control. Widget 0.1.60; contract bundle
0.1.71 unchanged.

### Added

- **Rate-control status on the wire.** Optional `rate_control.status` frames (`waiting` /
  `resumed`) are sent while a request waits on the provider rate gate. Delivery failures log
  `LLM_RATE_UI_STATUS_DROPPED`.
- **Rate-control status in parler-ui.** The chat shows an amber waiting pulse
  (`rateControlWaiting`), cleared on append, done, error or supersede; `rate_control.status` is
  ignored when loading history.

### Fixed

- **Rate gate race.** After emitting `waiting`, the gate re-checks capacity before reserving or
  waiting, closing a lost-notification race.

### Compatibility

- Contract bundle 0.1.71. Pair with widget 0.1.60.

---

## [0.1.132] — 2026-05-22

**Summary:** Normal streaming turns now include `llm_usage` on the terminal `done` frame, and usage
JSON only includes cache fields relevant to the provider. Contract bundle 0.1.70.

### Changed

- **`llm_usage` on `done`.** `ParlerStreamToRemoteThing` includes sanitized `llm_usage` on the
  terminal `done` frame (with `assistant_message_id`) when telemetry exists, matching the post-HITL
  path.
- **Provider-specific usage fields.** Anthropic cache read/create counts appear only for Anthropic
  providers and `cachedPromptTokens` only for OpenAI/Azure OpenAI; unused fields (including a
  hard-coded `reasoningTokens`) are omitted.

### Compatibility

- Contract bundle 0.1.70 (`API_CONTRACT.md`: `done` should include non-empty `llm_usage` when
  telemetry exists). Pair with widget 0.1.59.

---

## [0.1.131] — 2026-05-22

**Summary:** Token usage is available on post-HITL `done` frames and on history rows, and the widget
shows it in turn details. Contract bundle 0.1.69.

### Added

- **Usage on the wire and in history.** Post-HITL terminal `done` frames may carry sanitized
  `llm_usage`; `ai-parler-history-v1` assistant rows carry `llmUsage`.

### Changed (parler-ui)

- **Turn details.** The turn-details popover shows token telemetry; popover width and structured
  table styling were refined.

### Compatibility

- Contract bundle 0.1.69 (`API_CONTRACT.md`, `UI_CLIENT_PROTOCOL.md`: `llm_usage` / `llmUsage`
  shapes). Pair with widget 0.1.58.

---

## [0.1.130] — 2026-05-21

**Summary:** Table CSV export relies on the platform to create folders and gains a configurable size
cap; the gateway row actions become a compact icon strip with a turn-details popover. Contract bundle
0.1.68.

### Added

- **`AgentSettings.tableCsvExportMaxChars`.** CSV export size cap (default 50000000, clamped
  1000000..200000000), compared to the built CSV length. Skipped exports (`skipped_limit`, reasons
  `no_cache_for_full_table` / `max_chars`) are logged at WARN with row counts and wire ids.

### Changed

- **CSV export folders.** The `CreateFolder` preflight was removed; `SaveText` on the file repository
  creates parent folders.

### Changed (parler-ui)

- **Row actions.** Gateway row actions are icon-only; an Info popover shows conversation, request and
  assistant ids, `completedAt`, `feedbackRating`, chart and table counts and a task-state summary.
  Repeated thumb clicks pulse.

### Compatibility

- Contract bundle 0.1.68 (`TABLE_CONTRACT.md` CSV size cap; `UI_CLIENT_PROTOCOL.md` turn-details
  rules). Pair with widget 0.1.57.

---

## [0.1.129] — 2026-05-21

**Summary:** Gateway turn actions (thumbs up/down and history cutoff) with persisted feedback, and
product-safe table export messages. Contract bundle 0.1.65–0.1.67.

### Added

- **Assistant message ids.** `AgentMessageStream` rows carry `assistantMessageId`; final assistant
  messages get a UUID, sent on the AlwaysOn `done` frame as `assistant_message_id` with
  `completed_at` (UTC ISO).
- **History cutoff and feedback services.** `AgentThing.SetConversationHistoryCutoff` and
  `AgentThing.RecordAssistantFeedback`, plus `ParlerGateway.SetConversationHistoryCutoff`
  (`cutoffAtIso` STRING) and `ParlerGateway.RecordAssistantFeedback`.
- **Feedback persistence.** Feedback is stored as `role=ui_feedback` Stream rows; a persistence
  failure raises `FEEDBACK_PERSIST_FAILED`.

### Changed

- **History export.** `ai-parler-history-v1` assistant rows include `assistantMessageId`,
  `requestId`, optional `completedAt` and optional `feedbackRating` (`up`/`down`, last one wins).
  `ui_feedback` rows are skipped in LLM replay and rehydration.
- **Direct `AIAgent` services.** The direct cutoff and feedback services only check that the thread
  exists (ThingWorx permissions apply) and do not check the thread's agent binding; feedback is
  recorded against the agent on the matched stream row. The gateway path keeps current-user and
  bound-agent checks.

### Changed (parler-ui)

- **Turn actions.** Gateway conversations show thumbs and cutoff row actions; history hydration reads
  `assistantMessageId`, `completedAt` and `feedbackRating`; table footers map `exportMessage` to an
  allowlist of product-safe texts; row-action errors (feedback persistence, cutoff blocked by pending
  HITL) appear in the thread.

### Compatibility

- Contract bundle 0.1.65–0.1.67 (`API_CONTRACT.md`: gateway vs direct `AIAgent`,
  `FEEDBACK_PERSIST_FAILED`, `CUTOFF_BLOCKED_HITL_PENDING`; `UI_CLIENT_PROTOCOL.md`:
  `feedbackRating`; `TABLE_CONTRACT.md`: `exportMessage` allowlist). Pair with widget 0.1.56.

---

## [0.1.128] — 2026-05-19

**Summary:** Playbooks can now call extended (repository-defined) tools, hand INFOTABLE results from
one node to the next with `$table` references, and cite retained raw tables as evidence. The SCPA
utilization sample playbooks use `$table` handoffs and ship a three-entry catalog.

### Added

- **Extended tools in playbooks.** Entries in `/tools/extended_tools.json` accept an optional
  `playbookSafe` flag (effective only with `hitl: false`). Playbook `tool_call` validation uses the
  merged built-in and extended tool registry instead of the built-in allowlist. A warning is logged
  for inconsistent `playbookSafe`/`hitl` settings, and an info log when `hitl: false` is set but
  `playbookSafe` is omitted.
- **Larger playbook catalog.** The static catalog accepts up to 32 playbook ids, each following the
  id grammar and the canonical `/playbooks/<id>.playbook.json` path. Catalog and playbook JSON
  reject a `provider` field.
- **`$table` INFOTABLE handoff.** A top-level `tool_call.args` parameter set to exactly
  `{ "$table": "<nodeId>.result" }` passes a retained INFOTABLE result into an extended tool's
  INFOTABLE input, with a check for named DataShape mismatches. References to unknown node ids fail
  validation; misplaced or nested references fail with `TABLE_REF_NOT_ALLOWED_HERE` or
  `TABLE_REF_INVALID_FORMAT`; built-in tools given an INFOTABLE input fail with
  `PLAYBOOK_TOOL_NOT_EXTENDED`.
- **Raw-table retention.** Extended-tool nodes retain the raw INFOTABLE whenever the service returns
  INFOTABLE, even without INFOTABLE inputs, so `$table` works from the first node. Per-run
  `budgets.maxRawTableRows` defaults to 100000 (hard cap 1000000). `fan_out` synthetic nodes are not
  retained.
- **Table evidence for `llm_summary`.** A capped `evidence.table` projection falls back from the raw
  table to `rows` to noted `sampleRows`. The evidence formatter notes configured evidence columns
  that have no displayed values.
- **Eval script.** `test_scripts/agent_eval.py` gained a `toolsCalledSubsequence` assertion.
- **SCPA utilization sample playbooks.** In `dev_data/scpa_utilization/playbooks`,
  `utilization_summary` and `machine_utilization_summary` use `$table` handoffs, and a three-entry
  `playbooks.json` catalog is included for isolated upload testing.

### Compatibility

- Pair with widget 0.1.55.

---

## [0.1.127] — 2026-05-21

**Summary:** The `cross_region_health` playbook now reports live current values per region alongside
alert evidence, within the per-node evidence size limit.

### Changed

- **Current values in `cross_region_health`.** A new derive, `summarize_current_values_by_region`,
  rolls up the `get_property_values` fan-out per region. The shipped playbook adds a
  `current_value_stats` node and `llm_summary` prompt/evidence so final answers can cite live values
  separately from alert-only evidence.
- **`summarize_current_values_by_region` behavior.** `propertyNames` may be a JSON array, a
  collection or a semicolon-separated string. `maxExamplesPerProperty` limits examples only.
  `successfulReads` counts only `ok` cells and `failedReads` counts `ok: false` cells. Property
  rows with no reads and no failures are omitted; optional `excludePropertyNames` is supported; only
  `evidenceLines` are attached to stay within the 8 KiB node guard.
- **Shipped playbook caps.** `maxPropertiesPerRegion` 12, `maxExamplesPerProperty` 1, and
  `PTCDisplayName`, `PTCMake`, `PTCModel`, `PTCSerialNumber` excluded, so typical two-region runs
  on the SCPA sample stay under 8 KiB and complete `llm_summary`.

### Compatibility

- Pair with widget 0.1.54 (rebuilt UI bundle).

---

## [0.1.126] — 2026-05-20

**Summary:** Structured taxonomy now comes only from the resolver file `identity-types.json`;
`GetAssetTaxonomyForAgent` and `TaxonomyDataShape` are removed.

### Changed

- **Taxonomy service removed.** `GetAssetTaxonomyForAgent` and the `TaxonomyDataShape` entity are no
  longer shipped. Stable prompt taxonomy Markdown is only the optional `/taxonomies/type-taxonomy.md`
  (no generated pipe table). Taxonomy rows used by key resolution and playbooks are projected from
  `identity-types.json`; they are empty when the taxonomy is unavailable, with no live-service
  fallback (including in the key resolver).
- **`criticalProperties` authoring key.** Asset-type rows in resolver JSON use a
  `criticalProperties` array; identifier match rows use a `criticalProperties` object (name to
  string value).
- **Synonym source.** Cached taxonomy synonyms use only the normalized type key and
  `types[].aliases[]`; `entities[].aliases[]` apply only to `entityHint` matching. Sample skills and
  `identity-types.json` were updated accordingly.

### Compatibility

- Contract bundle 0.1.63 (`CONTRACTS/TAXONOMY_RESOLVER.md` 1.0.8,
  `CONTRACTS/AGENT_TAXONOMY_RENDERING.md`). Pair with widget 0.1.53.

## [0.1.125] — 2026-05-19

**Summary:** Semantic taxonomy moves to `identity-types.json` version 2 with optional `entityHint`
resolution; final-answer evidence rules cover cache misses; `get_property_values` returns a stable
`ENTITY_NOT_FOUND`.

### Added

- **identity-types v2.** The semantic taxonomy is read only from `/taxonomies/identity-types.json`
  (`version: 2`); a legacy `asset-types.json` is reported as `TAXONOMY_LEGACY_FILE_PRESENT`. The
  parser enforces `template_as_type` for ThingTemplates and `shape_as_type` for ThingShapes. Types
  carry their representation and entity aliases, `queryParent` is supported, and identifier queries
  derive their parent from the representation.
- **`entityHint`.** The `resolve_taxonomy_asset_type` tool and the
  `AgentThing.ResolveTaxonomyAssetType` service accept an optional `entityHint` (declared not
  required, so Composer and REST treat it as optional).
- **Cache-miss evidence rule.** The per-turn final-answer evidence rule includes a `CACHE_MISS` line.

### Changed

- **`get_property_values`.** A missing or non-Thing target returns `ENTITY_NOT_FOUND` (was
  `GET_PROPERTY_VALUES_ERROR`), consistent with other tools.
- **Eval script.** `test_scripts/agent_eval.py` `skipUnlessEnv` accepts one env var name or a list
  (skip unless all are set).
- **Eval taxonomy fixture.** The `template_as_type` row of `identity-types-minimal.json` now uses
  `PTC.MfgModel.DefaultWorkunit_TT`, which resolves on the SCPA sample.

### Fixed

- **Taxonomy projection and identifier matching.** Identifier matching uses projected identity and
  display columns instead of scanning each Thing; PASSWORD properties are excluded, and matching and
  display are limited to safe identity and critical-property fields. `name` + `exact` lookups honor
  template inheritance. Empty `propertyNames` are passed instead of null.
- **Taxonomy result envelopes.** Results are truncated only when the platform or inferred total
  exceeds 5000; `truncated` appears only when true; `totalUnderlyingCount` is omitted when unknown.
  `stale` is `false` on a fresh cache and omitted on `TAXONOMY_UNAVAILABLE`; tool failures return
  `TAXONOMY_RESOLVE_FAILED` with `stale` when applicable. A failed refresh keeps the stale cache with
  current diagnostics.

### Compatibility

- Contract bundle 0.1.60 (`CONTRACTS/TAXONOMY_RESOLVER.md` 1.0.6).

---

## [0.1.123] — 2026-05-18

**Summary:** Adds the `cross_asset_pair_health` playbook and an explicit derive-owned evidence model
for playbook summaries.

### Added

- **`cross_asset_pair_health` playbook.** Static DAG with identifier matching, primary-problem
  selection, optional numeric trends (`FIRST`/`LAST`/`MIN`/`MAX`/`MEAN`) and a pair comparison
  summary; listed in the catalog alongside `cross_region_health`.
- **Explicit node evidence.** Derive nodes attach `evidenceLines` / `evidenceText`, and the evidence
  formatter reads only that compact evidence.

### Changed

- **Playbook id renamed.** The `asset_pair_health` playbook is now `cross_asset_pair_health`; the
  `asset_pair_health` skill is unchanged.

### Fixed

- **Playbook validation and evidence.** `condition` nodes accept unary `value`/`left`; orphan-node
  validation considers only explicit references; slash-command examples are per playbook id; trend
  evidence includes direction and aggregates, not only point counts.

---

## [0.1.122] — 2026-05-17

**Summary:** Adds a second playbook (`asset_pair_health`), `condition` nodes, multi-playbook catalog
loading, and playbook progress events.

### Added

- **`asset_pair_health` playbook.** Static DAG with fuzzy identifier matching, primary-problem
  selection and optional numeric trends, using the derives `match_entity_identifiers`,
  `require_exact_count`, `flatten_pair_assets`, `select_primary_problem_property`,
  `union_property_names`, `trend_targets` and `summarize_asset_pair_health`.
- **`condition` nodes.** Predicates `is_empty`, `eq`, `and`, `or`, `any`, `not`; skipped branches are
  planned accordingly.
- **Multi-playbook catalog.** All catalog entries are loaded, `start_playbook` accepts every
  cataloged id, and `query_numeric_property_history` is marked `playbookSafe`.
- **Playbook progress.** Playbook runs emit metadata-only `task.state` events with
  `source=playbook`; skill progress hooks are suppressed while a playbook turn is active.

### Fixed

- **Playbook slash turns.** AlwaysOn structured slash commands bind context before the slash probe;
  fan-out nodes report `N/M` progress; tool context is always cleared after the slash probe.

### Compatibility

- Contract bundle 0.1.53 (`CONTRACTS/API_CONTRACT.md` 2.4.18).

---

## [0.1.121] — 2026-05-17

**Summary:** Introduces the playbook engine with the `cross_region_health` playbook, structured slash
commands, and the `start_playbook` tool.

### Added

- **Playbook engine.** Static repository catalog (`/playbooks/playbooks.json`,
  `cross_region_health.playbook.json`) with loader, validator and runner. The registry loads before
  slash commands are parsed. `query_entities_by_taxonomy`, `query_alert_summary` and
  `get_property_values` are marked `playbookSafe`.
- **Structured slash commands.** Playbooks run directly from slash commands on `Chat`, `ChatAsync`
  and AlwaysOn, with the same streaming and conversation persistence as normal turns. A skill and
  playbook with the same name report `PLAYBOOK_SKILL_NAME_CONFLICT`.
- **`start_playbook` tool.** The model can start a playbook from natural language; the user goal is
  taken from the active messages. A successful call ends the turn in the playbook; sibling tool calls
  in the same batch are skipped and receive synthetic `PLAYBOOK_TERMINAL_HANDOFF` results so the
  transcript stays replay-safe.
- **`cross_region_health` behavior.** Per-region `alertGroups`, an evidence-based comparison (null
  when regions tie), `topProperties` capped at 5, `INFOTABLE_LARGE` alert sampling, and
  `ENTITY_TAXONOMY_QUERY_LARGE` reported as region gaps. The property union is built alert-first.
  Taxonomy variables are provided as `CriticalProperties` (semicolon string for tools) and
  `CriticalPropertiesList`. The `llm_summary` evidence includes region, alert and comparison detail.

---

## [0.1.120] — 2026-05-16

**Summary:** Adds provider-level local rate control: a per-Provider admission gate, structured
handling of upstream 429 responses, bounded local waits, and inspection services.

### Added

- **`LLMAPIProviderRateControl` table.** Configuration table on `LLMAPIProviderThing` with mode
  (`disabled` / `observe` / `enforce`), TPM, RPM and concurrency limits, reservation strategy and
  safety multiplier. Anthropic defaults to the `input_only` reservation strategy; other built-in
  Providers default to `input_plus_requested_output`.
- **Admission gate.** In-memory token and request buckets plus concurrency slots per Provider; a
  local block window after an upstream 429; usage reconciliation on success; telemetry lines
  `LLM_RATE_ADMISSION` and `LLM_RATE_REJECTION`. `TestConnection` and health-check probes bypass the
  gate. OpenAI, Azure OpenAI and Anthropic clients report 429s to the gate instead of treating them
  as generic failures.
- **Bounded local wait.** A positive `maxLocalWaitMs` waits locally for refill, block expiry or a
  free slot up to that budget, then admits or rejects (no debit on timeout). Waits are always logged
  as `LLM_RATE_ADMISSION action=wait`; `waitMs` / `maxWaitMs` report actual elapsed time and
  rejections carry `waitMs`. An upstream block longer than the budget fails fast.
- **Provider services.** `GetRateControlState`, `ResetRateControlState` and
  `TestRateControlAdmission` (always a non-debiting peek; `dryRun` is ignored).

### Fixed

- **Replay compaction preflight.** Non-JSON tool bodies (for example Markdown skill text) pass
  through without `LLM_COMPACT_MALFORMED`.
- **Retry timing.** Upstream `Retry-After` / reset headers are honored without a 60 s floor (60 s is
  used only when headers are missing). Local rejections compute `retryAfterMs` from the deficit.
- **Gate accounting.** An upstream 429 empties the token bucket to 0 rather than a negative balance;
  expired blocks are cleared during refill and `GetRateControlState` omits an expired
  `blockedUntil`; observe-mode logging works on a fresh gate.

### Known limitations

- For multi-round agent workloads under `enforce`, set `maxLocalWaitMs` to 90000–120000.

---

## [0.1.119] — 2026-05-16

**Summary:** Each built-in Provider template now has its own native settings table, and Providers
own max-output and reasoning defaults. The shared `LLMAPIProviderConnection` table is removed.

### Changed

- **Per-template Provider settings.** `AzureOpenAIChatV4Provider`, `AzureOpenAIChatV5Provider`,
  `OpenAIChatV4Provider`, `OpenAIChatV5Provider` and `AnthropicMessagesProvider` each declare a
  single-row settings table (for example `AzureOpenAIChatV4Settings`, `OpenAIChatV5Settings`,
  `AnthropicMessagesSettings`) with vendor-native fields: `deployment` / `model`, `maxTokens` or
  `maxCompletionTokens`, `anthropicVersion`, optional OpenAI organization/project headers.
- **Provider-owned output and reasoning.** The Provider resolves max output tokens and reasoning
  effort once per chat request. `AgentSettings.maxTokens` now defaults to `-1` so Provider defaults
  apply; an explicit positive Agent value still overrides them.
- **Provider defaults.** v4: `maxTokens=4096`. v5: `maxCompletionTokens=8192`,
  `reasoningEffort=low`. Anthropic: `maxTokens=8192`, `thinkingBudgetTokens=0` (no thinking block).
- **Anthropic extended thinking.** A positive `thinkingBudgetTokens` enables thinking; it must be at
  least 1024 and below `maxTokens`. `temperature` is omitted when thinking is on, and connection
  probes skip thinking.
- **`GetAvailableLlmApiProviders`.** `modelName` reports each Provider's effective model label.

### Fixed

- **Chat v5 temperature.** OpenAI and Azure v5 clients omit `temperature` when sending
  `max_completion_tokens`, fixing HTTP 400 errors on GPT-5.4 / GPT-5.4-mini with a non-default
  temperature. v4 is unchanged.
- **Max output overflow.** Requested max output tokens above the 32-bit integer range are rejected
  instead of silently wrapping.

### Known limitations

- Positive Anthropic thinking budgets with tools are only safe for single-turn use.

### Compatibility

- Provider connection settings now live in each template's settings table; the shared
  `LLMAPIProviderConnection` table no longer exists.

---

## [0.1.118] — 2026-05-16

**Summary:** Introduces LLM API Provider Things. Agents select a Provider with
`llmApiProviderRef`; the inline LLM connection tables on the Agent are removed.

### Added

- **Provider Things.** The extension ships the `LLMAPIProviderShape` marker and five Provider
  templates: `OpenAIChatV4Provider`, `OpenAIChatV5Provider`, `AzureOpenAIChatV4Provider`,
  `AzureOpenAIChatV5Provider`, `AnthropicMessagesProvider`. Credentials and model/deployment are
  configured on Provider Things. `apiVersion` is used only for Azure; OpenAI and Anthropic templates
  use a default base URL when `baseUrl` is blank.
- **`AgentSettings.llmApiProviderRef`.** Resolved per turn for `Chat`, `ChatAsync`, Parler and HITL
  continuations. `GetAvailableLlmApiProviders()` lists enabled Provider Things (non-sensitive
  columns only).
- **`TestConnection`.** Provider Things expose a BOOLEAN `TestConnection` service that runs a health
  check and logs `LLM_PROVIDER_TEST_CONNECTION` (no `LLM_USAGE` line on success).

### Changed

- **Telemetry identity.** `LLM_*` log lines and `llmUsageJson` use four dimensions:
  `providerThingName`, `providerTemplateName`, `apiShapeId` (distinguishing Chat Completions v4 and
  v5) and `model`. `LLM_HTTP_FAILURE`, `LLM_RATE_LIMIT` and `LLM_PROVIDER_TEST_CONNECTION` carry the
  same identity as `LLM_USAGE`.
- **Token field by template.** v4 templates send `max_tokens` and v5 templates send
  `max_completion_tokens`, so opaque Azure deployment names no longer pick the wrong field.

### Removed

- **Inline Agent LLM configuration.** `AgentBaseThing` ships only `AgentSettings`; the
  `ProviderSelection` and per-vendor connection tables are removed.

### Fixed

- **Provider client rebuild.** A failed client rebuild on Provider initialization no longer keeps a
  stale client.

### Compatibility

- Agents must reference a Provider Thing through `llmApiProviderRef`; configure credentials on the
  Provider Thing.

---

## [0.1.117] — 2026-05-14

**Summary:** Assistant rows in `AgentMessageStream` can persist per-round LLM usage telemetry;
`build_chart_from_tabular_result` gains an `intent` mode; AlwaysOn connection and auth handling are
more robust.

### Added

- **Usage telemetry in `AgentMessageStream`.** Assistant rows optionally persist compact
  `llmUsageJson` (provider, model, request id, token and cache fields) alongside `promptTokens` /
  `completionTokens`. The `AgentMessageData` and `ParlerConversationHistory` DataShapes and the
  Stream row shape include the field.
- **Chart intent.** `build_chart_from_tabular_result` accepts an `intent` from a closed set as an
  alternative to `kind` (the two are mutually exclusive), returning `CHART_FALLBACK` or
  `INTENT_UNKNOWN` when an intent cannot be charted. `rank` and `composition` intents can sort bars.
  Emitted `ChartBlock`s (tabular and numeric-history) carry an optional `chartId`. The routing guide
  uses neutral chart/table wording.
- **Eval script.** `test_scripts/agent_eval.py` supports reset modes `fresh`, `stable_clear` and
  `stable_hard_reset` and aggregates `llmUsageJson` per provider.

### Fixed

- **Chart tool schema.** The tool parameters schema is a single root `type: object` (no root
  `oneOf`), with `source` and `xColumn` required, so providers no longer reject the request.
- **Phase-only intents.** `distribution` and `status_timeline` return `CHART_FALLBACK` before column
  validation (instead of `INVALID_MAPPING`), while still validating `cacheId` when `source` is
  `cache_id`.
- **History replay.** Numeric-history chart blocks rebuilt from history omit `chartId`.

### Fixed (parler-ui / widget)

- **AlwaysOn `ConnectAndBind`.** Repeated calls for the same conversation, agent and WebSocket URL
  are idempotent while connected or connecting. Repeated auth failures for the same temporary
  AppKey are suppressed for 30 seconds, reducing `Missing application key` log noise.
- **Auth diagnostics.** WebSocket errors during authentication surface as auth-stage failures with
  the AppKey refresh hint.

### Compatibility

- Contract bundle 0.1.52 (`CONTRACTS/CHART_CONTRACT.md`: optional `ChartBlock.chartId`).

---

## [0.1.116] — 2026-05-13

**Summary:** `get_property_values` distinguishes unresolved property metadata from PASSWORD
protection, and `invoke_service` leaves omitted inputs to ThingWorx defaults.

### Changed

- **`get_property_values`.** Properties whose metadata cannot be resolved return
  `PROPERTY_METADATA_UNRESOLVED` and are not read; `PROTECTED_VALUE_READ_BLOCKED` applies only to
  resolved PASSWORD properties.
- **`invoke_service`.** Omitted service parameters are no longer rejected before the call, so
  platform defaults and validation apply. Supplied top-level fields are moved into `parameters` only
  when their names exactly match declared service inputs.
- **Sample `region_health` skill.** Instructs the agent to find exact property names via
  `query_alert_summary.sourceProperty`, taxonomy metadata or `discover_properties` before reading
  current values, rather than treating business labels such as `Speed` as property names.

---

## [0.1.115] — 2026-05-12

**Summary:** Adds the configuration repository: skills, extended tools, the `invoke_service` allow
policy and taxonomy Markdown are loaded from a FileRepository, with new authoring and diagnostics
services.

### Added

- **Configuration repository.** `AgentSettings.configurationRepository` (a FileRepository) holds
  `/skills/<id>/SKILL.md`, `/taxonomies/type-taxonomy.md` (stable taxonomy prompt prefix),
  `/tools/extended_tools.json` (additional LLM tools that invoke services on arbitrary Things) and
  `/policies/invoke_service.json` (allow rules that bypass HITL for matching `invoke_service` calls
  when the policy is valid).
- **Authoring services.** `GetAgentRuntimeSnapshot` and `ValidateAgentConfigurationRepository` on
  `AgentThing` return JSON for operators and authoring tools (they are not LLM tools). The snapshot
  includes skills, the skill catalog, diagnostics, `agent.extensionVersion` and the status of
  `/taxonomies/type-taxonomy.md`.
- **Diagnostics.** `RefreshPromptContextCache` appends configuration repository diagnostics.

### Changed

- **Skills.** Loaded only from the repository; duplicate ids are skipped with warnings; a missing
  `/skills` directory means zero skills.
- **Taxonomy Markdown.** Read only from `/taxonomies/type-taxonomy.md`;
  `GetAssetTaxonomyForAgentDirect` is removed. After a UTF-8 BOM is stripped, whitespace-only content is reported as `EMPTY`; leading
  whitespace in loaded content is preserved.
- **Tool set.** Built-in tools plus repository extended tools only; legacy `_tool_*` service
  harvesting is removed.
- **`invoke_service` policy.** Cached per agent run; missing files and read failures are reported
  separately; invalid policy or manifest JSON is logged at `ERROR`; allow-rule ties resolve by
  priority, then file order.
- **Extended tools.** Results use `invoke_service`-style serialization; targets whose INFOTABLE
  result DataShape has PASSWORD columns are not registered; a blank target entity or service name
  invalidates the whole manifest. Extended tools take part in task-state tracking, HITL approval
  runs the resolved target service, and audit logs include the target.
- **`AgentSettings.exportFileRepository`.** Now a `THINGNAME` restricted to the `FileRepository`
  template (friendly name "Export File Repository").

### Compatibility

- Contract bundle 0.1.51 (`CONTRACTS/TABLE_CONTRACT.md` §3.5). Pair with widget 0.1.46.
- Skills and tools previously harvested from `_tool_*` services must be moved into the configuration
  repository.

---

## [0.1.114] — 2026-05-07

**Summary:** Completes the first version of replay summaries (`parler.infotable.summary.v1`) with
pointer-only summaries, DATETIME ranges and more robust cell labeling.

### Added

- **Replay summary fields.** Summaries of results that have a `cacheId` list only column names and
  base types. DATETIME columns report `range: [isoMin, isoMax]`.
- **Routing guide.** `llm_replay_format_routing_guide.txt` describes standalone
  `parler.infotable.summary.v1` bodies.

### Changed

- **Cell labels.** String buckets and cohort row matching label text, number and boolean cells by
  value and use `(null)` / `(complex)` markers otherwise.
- **`enableLlmReplayCompaction`.** Setting description now covers Tier A, Tier 0 and Tier B
  compaction.

### Fixed

- **DATETIME `range`.** Min/max are chronological; the range is omitted if any cell in the column
  cannot be parsed; numeric offsets without a colon (`+0800`) are parsed.
- **Cohort summaries.** Inner matrices with a non-empty `cacheId` omit `memberSummaries` but keep
  `cohortDimension`.

---

## [0.1.113] — 2026-05-07

**Summary:** Adds post-turn replay promotion of historic tool results to compact summaries (Tier B),
extends cohort compaction to `query_alert_history`, and adds tool-schema size telemetry.

### Added

- **Tier B replay promotion.** With `AgentSettings.enableLlmReplayCompaction` on, historic
  `parler.infotable.matrix.v1` tool results (including cohort-bundle inner matrices) before the last
  user message are rewritten to `parler.infotable.summary.v1` after successful turns. Summaries keep
  matrix `constants`, `totalCount` and `sampleOnly`; PASSWORD columns are omitted and listed in
  `protectedOmissions`; string `top` values break ties deterministically; cohort bundles record
  `cohortDimension` (`thingName`). Logs `LLM_TIER_B_PROMOTED` (with `beforeChars` / `afterChars`)
  and `LLM_TIER_B_SKIP reason=summary_serialize` on serialization failure.
- **Cohort compaction for `query_alert_history`.** Uses the same gates as `query_alert_summary`.
- **Tool schema telemetry.** `LLM_TOOL_SCHEMA_USAGE` log line;
  `scripts/report-llm-tool-schema-usage.mjs` aggregates it.

### Compatibility

- Pair with widget 0.1.45.

---

## [0.1.112] — 2026-05-11

**Summary:** Adds cohort compaction of repeated sibling tool results and a diagnostic log for replay
size when an LLM round fails.

### Added

- **Pending replay diagnostics.** When an LLM round fails before `LLM_USAGE` is logged (for example
  on a 429), `LLM_REPLAY_PENDING_COMPACTION` reports the prior batch's `rawReplayChars`,
  `replayChars` and `compactRatio`.
- **Cohort compaction (Tier 0).** Three or more sibling `get_property_values` calls with the same
  `propertyNames`, or three or more sibling `query_alert_summary` calls with the same arguments
  apart from `thingName` and identical columns, are merged into `parler.cohort.bundle.v1` /
  `parler.cohort.member.v1`.

### Fixed

- **Cohort merge safety.** Merging is skipped, with `LLM_COHORT_SKIP`, when `query_alert_summary`
  results have non-empty `constants` (`reason=matrix_constants`), when any `get_property_values` row
  is not `ok` (`reason=non_ok_rows`) or base types differ (`reason=mixed_base_type`), and when the
  rewrite saves no characters (`reason=no_char_savings`).

### Compatibility

- Pair with widget 0.1.44.

---

## [0.1.111] — 2026-05-10

**Summary:** The replay routing guide now names all matrix row-array fields.

### Fixed

- **Matrix replay instructions.** The routing guide states that matrix rows may be under `rows`,
  `sampleRows`, `rootEntityList` or `sampleRootEntityList`, so taxonomy and other tools that keep
  entity-list keys are described correctly.

### Compatibility

- Pair with widget 0.1.43.

---

## [0.1.110] — 2026-05-07

**Summary:** Opt-in compact replay encoding for tabular tool results in the LLM conversation, and
entity query/list/taxonomy tool results now carry `columns` metadata.

### Added

- **Tool-routing guide.** A bundled `llm_replay_format_routing_guide.txt` is appended to the stable
  system prompt when `appendBuiltInToolRoutingGuide` is true.
- **Compact replay of tabular results.** When the new `AgentSettings.enableLlmReplayCompaction`
  (default `false`) is on, eligible tabular tool results are re-encoded as
  `parler.infotable.matrix.v1` in the in-memory conversation after each completed tool batch.
  Stream persistence and tool evidence still see the raw JSON. `LLM_USAGE` optionally reports
  `rawReplayChars`, `replayChars` and `compactRatio` for the round that follows a compacted batch.

### Fixed

- **Entity tool results and matrix encoding.** `query_entities`, `list_entities_by_type` and
  `query_entities_by_taxonomy` emit `columns` aligned with the row keys on inline and large-result
  paths, so matrix encoding no longer logs `LLM_COMPACT_MALFORMED` for `ENTITY_*` envelopes. Row
  arrays are recognized under `rows`, `sampleRows`, `rootEntityList` or `sampleRootEntityList`,
  keeping the original field names.

### Compatibility

- Pair with widget 0.1.42.

---

## [0.1.109] — 2026-05-07

**Summary:** LLM usage and HTTP-failure observability, and Anthropic prompt caching.

### Added

- **HTTP failure diagnostics.** Provider HTTP failures and rate limits are logged safely as
  `LLM_HTTP_FAILURE` and `LLM_RATE_LIMIT` lines, with a capped `bodyPreview` and the provider
  request id.
- **Usage telemetry.** A compact `LLM_USAGE` line per LLM round reports token usage, including
  cache read/create tokens, cached prompt tokens (OpenAI/Azure
  `prompt_tokens_details.cached_tokens`) and the provider request id. Its `messages=` count is the
  outbound message count after task-state and UTC-clock injection.
- **Anthropic prompt caching.** Messages API requests set `cache_control` on the stable first
  system block and on the last tool definition.

### Changed

- **No raw response bodies in errors.** HTTP-error and parse-failure exception messages no longer
  include raw response bodies, and the Azure client no longer logs the full body at ERROR; details
  stay on `LLM_HTTP_FAILURE` with the capped `bodyPreview`.

### Compatibility

- Pair with widget 0.1.41.

---

## [0.1.108] — 2026-05-10

**Summary:** More tool error codes survive into the task-state evidence block, the injected block
now carries a final-answer evidence rule, and deterministic guards log final answers that
contradict tool evidence.

### Changed

- **More error codes in tool evidence.** `TaskStateErrorCode` adds `CACHE_MISS`,
  `INVALID_TIME_RANGE`, `SERVICE_LOOKUP_FAILED`, `PROTECTED_VALUE_READ_BLOCKED`,
  `PROTECTED_VALUE_WRITE_BLOCKED` and `PROTECTED_VALUE_INPUT_BLOCKED`, so built-in tool error codes
  reach the Recent Tool Evidence block. Legacy `INVALID_PARAMETERS` maps to `PARAMETER_INVALID`.
- **Final-answer evidence rule.** The injected Recent Tool Evidence block now states the
  Final-answer evidence rule.

### Added

- **Answer-grounding guards (detect only).** Deterministic checks flag final answers that
  contradict the turn's evidence: empty success described as a missing target (skipped when the
  turn has `ENTITY_NOT_FOUND`, `SERVICE_NOT_FOUND` or `SERVICE_LOOKUP_FAILED`), sample-only
  overclaims, cache-miss vs. "cached data" prose, missing live-cache paging, rewritten error codes
  (skipped on turns with mixed error classes) and solicitation of protected values. Prose patterns
  are positive-only to avoid negation false positives.
- **Grounding log.** After a successful turn (AlwaysOn stream, post-HITL continuation, `Chat`,
  `ChatAsync`), an INFO line lists `contradictingGuardIds` when any guard fires. No wire, UI or
  response change.

### Compatibility

- Pair with widget 0.1.40.

---

## [0.1.107] — 2026-05-10

**Summary:** FileRepository-backed skill discovery fixes.

### Fixed

- **Skill repository changes apply without restart.** `AgentSettings.skillRepository` is read live
  during refresh, so editing the setting and running `RefreshPromptContextCache` picks up the change
  without restarting the Thing.
- **Top-level skill directories are found.** Root discovery uses `BrowseDirectory("/")` before
  loading `/<SkillId>/SKILL.md`, because `GetFileListing("/")` may return no directory rows on
  ThingWorx.

---

## [0.1.106] — 2026-05-07

**Summary:** Skills can be loaded from a FileRepository as well as from `_skill_*` services,
through one skill registry built during prompt-context refresh.

### Added

- **FileRepository-backed skills.** New `AgentSettings.skillRepository` (STRING) names a
  FileRepository Thing whose top-level directories hold `SKILL.md` files. Empty disables
  file-backed skills without logging; a missing Thing or a Thing that is not a FileRepository logs
  ERROR. Frontmatter is stripped before the body reaches the LLM. The scan is bounded (each
  `LoadText` attempt counts toward the cap) and `SKILL.md` size is capped at load time.
- **Unified skill registry.** Service and repository skills merge into one registry. The per-turn
  skill catalog (both sources, sorted by short id), `/SkillName` bodies, `get_agent_skill` and
  task-state checklists all load through it, with no fallback that bypasses it. Slash-loaded skill
  headings show `source repository` or `source service`.
- **Registry diagnostics.** `RefreshPromptContextCache` output ends with a
  `## Skill registry diagnostics` section for operators; it is not sent to the LLM.
- **`get_agent_skill` error codes.** Structured errors carry `code`: `SKILL_NOT_FOUND`,
  `SKILL_LOAD_FAILED`, `BAD_REQUEST` or `SKILL_REGISTRY_UNAVAILABLE`. The tool description is
  source-neutral.

### Changed

- **Best-effort prompt-context refresh.** A failure in the asset taxonomy fetch, GenericThing
  template discovery, `GetAlertPrompt` or skill harvesting logs ERROR and leaves that segment empty;
  the rest of the snapshot still builds.
- **Skill short-id grammar.** `_skill_*` services must have short ids matching
  `[A-Za-z][A-Za-z0-9_-]*`; others log ERROR and are skipped.
- **Checklist load warning.** A WARN is logged when a slash-listed skill body cannot be loaded for
  its checklist.

### Compatibility

- Pair with widget 0.1.37.

---

## [0.1.105] — 2026-05-09

**Summary:** The task-state panel is hidden on successfully completed assistant messages.

### Changed (parler-ui)

- **Task-state panel visibility.** The panel shows during the active turn. After completion it
  stays only when the wire `status` needs attention or `summary.failed` / `summary.blocked` is
  positive; it is hidden on successful rows, including ad-hoc-only and satisfied-checklist
  snapshots.
- **Assistant message order.** Task-state panel, then charts, tables, the Further-insight hint and
  the final Markdown.

### Compatibility

- Pair with widget 0.1.36.

---

## [0.1.104] — 2026-05-07

**Summary:** Per-turn task state: a tool-evidence ledger injected into the LLM prompt,
skill-defined checklists, a `task.state` wire event with a progress panel in the widget, and HITL
continuation support.

### Added

- **Recent Tool Evidence.** A per-turn ledger of `invoke_service` and `fetch_cached_result` results
  is rendered as an ephemeral `## Recent Tool Evidence` system block before each LLM round (ahead of
  the UTC clock snippet) and removed afterwards. Rows record `in-progress`, `ok`, `error` or
  `blocked-by-approval`. Each line is length-capped, and an "Older evidence omitted" sentinel
  appears when the row cap trims history.
- **Skill checklists.** A skill may include a `parler-task-checklist-v1` fenced block. Checklists
  from all slash skills are combined and validated (unknown keys, duplicate ids, `maxSkillItems`,
  `cardinality`, allowed `match` keys, `kind` of `evidence`, `guidance` or `synthesis`). Tool calls
  are matched to checklist items; unmatched calls become ad-hoc items.
- **Mid-turn checklist merge.** A checklist in a skill loaded with `get_agent_skill` during the turn
  is appended. Duplicate, invalid, multi-fence or over-budget checklists produce a compact ad-hoc
  summary instead.
- **No-evidence tools.** For tools without evidence parsing, a plain or empty string result counts
  as success and a `{"status":"error"}` envelope counts as failure.
- **`task.state` wire event.** A metadata-only snapshot (no raw arguments or results) is sent after
  lifecycle events and at turn end, including failed turns before `session.error`. See
  `CONTRACTS/API_CONTRACT.md` § task.state and `CONTRACTS/UI_CLIENT_PROTOCOL.md`
  (`assistant.taskState`).
- **Task-state panel (parler-ui).** The reducer stores `taskState` on the matching assistant row
  (full replace) and the panel renders labels, status and summary.

### Changed

- **HITL continuation.** After approve, reject or cancel, task state is rebuilt from history using
  the slash and dynamically loaded skills captured when the approval was queued. Approved
  `invoke_service` and `set_property_value` results and reject/cancel decisions are seeded as
  evidence that matches `correlationKey`. HITL expiry by the scheduler does not emit `task.state`.
- **Evidence uses resolved arguments.** `invoke_service` evidence records the arguments after
  entity-type resolution, also on early errors (`INVALID_PARAMETERS`, blocked PASSWORD input,
  blocked NamedVTQ PASSWORD write).

### Fixed

- **Checklist matching.** `summary.inProgress` excludes `pending`; `one_or_more` items re-match;
  rows that differ only in `match.resultKind` wait for the result before binding; evidence parsing
  is null-safe and sets `totalCountInferred` when `totalRows` is absent.
- **Activity line (parler-ui).** The ephemeral `activity` line is no longer hidden when `taskState`
  is present.

### Compatibility

- Contract bundle 0.1.46 (`task.state` introduced in 0.1.45). Widget 0.1.34 adds the task-state
  panel.

---

## [0.1.98] — 2026-05-08

**Summary:** A cleared conversation is no longer partly restored when a pending approval expires.

### Fixed

- **Clear survives HITL expiry.** When a pending approval expires after the conversation was
  cleared (`historyClearedAt` later than the approval's creation), the pre-clear message snapshot is
  no longer restored into memory. Pending approvals now record their creation time.

---

## [0.1.97] — 2026-05-07

**Summary:** Conversation continuity fixes for rollback, clearing and rehydration.

### Fixed

- **Rollback checkpoint.** Failed and rehydrated turns roll back to a point after the leading
  system row.
- **`ClearConversation` access.** The caller no longer has to match the thread row's `username`;
  access is governed by permissions (the row must exist and match the agent).
- **Expired approvals.** Expired pending approvals no longer block `ClearConversation`.
- **Rehydration.** A WARN is logged when the Stream Thing is missing, and user/assistant rows with
  empty `content` are skipped.

---

## [0.1.96] — 2026-05-07

**Summary:** Conversation continuity: a durable server-side clear marker and rehydration of LLM
context from the message Stream.

### Added

- **`historyClearedAt`.** The `AgentThreadData` DataShape (`AgentThreadDataTable`) gains a nullable
  DATETIME field `historyClearedAt`.
- **Server-side `ClearConversation`.** Takes the conversation lock, refuses while a HITL approval is
  pending, and writes `historyClearedAt`.
- **History export honors the clear.** `GetConversationHistoryJson` omits messages before the clear
  marker.
- **Stream rehydration.** On the AlwaysOn stream path (`ParlerStreamToRemoteThing`), conversation
  context missing from memory is rebuilt from the message Stream transcript. Not enabled for `Chat`
  or `ChatAsync`.

---

## [0.1.95] — 2026-05-08

**Summary:** PASSWORD values are protected across tool inputs and outputs, persistence and caching.

### Security

- **PASSWORD reads, writes and inputs.** `get_property_values`, `set_property_value` and
  `invoke_service` (when the ServiceDefinition resolves) block PASSWORD values with
  `PROTECTED_VALUE_*` codes. Custom `_tool_*` services omit PASSWORD parameters and results.
  Protection events are audited.
- **Redaction.** Message Stream persistence and the tool registry redact protected values;
  persisted tool arguments are redacted from metadata, with `PERSIST_REDACTION_FAILED_JSON` on
  failure.
- **NamedVTQ.** Writes to protected properties through NamedVTQ are blocked; a read `value` is
  masked when the Thing resolves and the name maps to a protected property.
- **Cached tabular and chart tools.** PASSWORD columns are refused with
  `PROTECTED_TABULAR_COLUMN_BLOCKED` (`CONTRACTS/TABULAR_INSIGHT.md`).

### Changed

- **No blanket masking.** When the ServiceDefinition does not resolve, `parameters` are not masked
  recursively and `PROTECTED_VALUE_INPUT_BLOCKED` is not raised.
- **Uncacheable results.** If cache sanitization fails, the result is not cached, audit event
  `CACHE_NAMEDVTQ_UNCACHEABLE_ON_SANITIZE_FAILURE` is recorded, and an `INFOTABLE_LARGE` result may
  have a null `cacheId` (`CONTRACTS/TABLE_CONTRACT.md`). The `invoke_service`,
  `fetch_cached_result` and `query_stream_data` descriptions document this.

### Compatibility

- Contract bundle 0.1.44.

---

## [0.1.94] — 2026-05-08

**Summary:** Stricter `invoke_service` argument shape with automatic repair, an `invokeExample` from
`get_service_definition`, and entity-type normalization preserved across HITL.

### Changed

- **`invoke_service` arguments.** The tool schema sets `additionalProperties: false` and service
  inputs belong under `parameters`. Misplaced top-level inputs are moved into `parameters` before
  HITL and reported as `parametersNormalized`; unrepairable calls fail early with
  `INVALID_PARAMETERS`. The repair is preserved across HITL approval.
- **`invokeExample`.** `get_service_definition` returns an example `invoke_service` call. DATETIME
  inputs use the placeholder `<ISO-8601_DATETIME>` and a note says placeholders are not
  authoritative.
- **Entity-type resolution.** The root `entityType` is checked first, the GenericThing template list
  comes from the prompt-context snapshot, and `entityTypeNormalized` is preserved across HITL.

---

## [0.1.93] — 2026-05-07

**Summary:** Service-target `entityType` validation and a lazily built prompt-context cache.

### Changed

- **Service-target `entityType`.** `invoke_service`, `discover_services`,
  `get_service_definition` and `discover_properties` restrict `entityType` to ThingWorx root entity
  types. GenericThing-derived template names such as `DataTable` normalize to `Thing`; other
  non-root names fail with `UNSUPPORTED_ENTITY_TYPE_FOR_SERVICE_TARGET`. Arguments are rewritten
  before HITL, and success JSON includes `entityTypeNormalized` when applicable.
- **Prompt-context cache.** The cache is built on the first chat turn instead of at Thing start.

---

## [0.1.92] — 2026-05-07

**Summary:** The automatic prompt-context cache rebuild moved from `initializeThing` to
`processStartNotification`, so `GetAssetTaxonomyForAgent*` and `GetAlertPrompt` run once the Thing
is running (fixes "Thing is not running" on the first chat). A manual `RefreshPromptContextCache`
still logs ERROR and rethrows on failure; the automatic rebuild keeps the previous snapshot.

---

## [0.1.90] — 2026-05-07

**Summary:** Consolidated release: a stable system-prompt context cache, model-key resolution via
GenericThing dependencies, natural-time handling for built-in and custom tools, new Stream and
value-stream history tools, and QUERY parameter validation.

### Added

- **Prompt-context cache.** An in-memory snapshot (taxonomy Markdown and rows, sorted GenericThing
  ThingTemplate names, `GetAlertPrompt` text) is built at Thing initialization and by
  `RefreshPromptContextCache`, and folded into the leading provider system row so providers can
  prefix-cache it. The per-turn skill catalog and UTC clock snippet stay outside the cached prefix.
  For OpenAI/Azure, `messages[0]` is sent alone as the first system row. A failed refresh logs ERROR
  and keeps the previous snapshot. Model-key resolution uses the cached taxonomy and logs a
  throttled WARN when it falls back to live services.
- **GenericThing model-key resolution.** After a taxonomy miss, `query_entities` parent keys
  (`Stream`, `DataTable`, ...) are matched by exact name via
  `GenericThing.GetIncomingDependencies(maxItems=2000)`, reported as
  `GENERIC_THING_INCOMING_DEPENDENCY` with `keyResolution.resolvedName`. `list_entities_by_type`
  with a model key as collection `type` returns `ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY`
  repair JSON.
- **Taxonomy key resolution.** `Synonyms` are checked across all rows before row validity; a
  malformed matched row gives `TAXONOMY_ROW_INVALID` and an unresolvable entity gives
  `TAXONOMY_ENTITY_UNRESOLVED`, with no further fallback. Results carry `keyResolution`.
- **New tools.** `query_stream_data` (`QueryStreamData` on a Stream Thing) and
  `query_value_stream_property_history` (non-numeric properties; numeric stays on
  `query_numeric_property_history`), both with natural-time fields, ISO validation and large-table
  caching.
- **Natural-time fields.** `query_alert_history` and `query_numeric_property_history` accept
  `calendarPhrase` (`today`, `yesterday`, `tomorrow` as local-midnight windows in the user's time
  zone, DST-safe) and `relativeDuration`, and report `applied_time_window`. Day phrases mixed with
  dayparts, clock times or partial-day prepositions are rejected with `UNSUPPORTED_CALENDAR_PHRASE`
  rather than widened. The UTC clock snippet steers the model toward these fields.
- **Strict explicit bounds.** Explicit time bounds must be JSON strings (`INVALID_TIME_SPEC_SHAPE`)
  and valid ISO-8601 (`INVALID_TIME_RANGE`); there is no silent fallback to platform defaults.
  Conflicts use `TIME_PHRASE_COMBINED_INVALID`, `TIME_PHRASE_VS_PRESET_CONFLICT` and
  `TIME_PHRASE_VS_EXPLICIT_BOUND_CONFLICT`; duration overflow gives `INVALID_DURATION_GRAMMAR`.
- **Time error envelope.** Time errors return `status`, `code`, `message` and, for single-field
  errors, `rejectedParameter` with the caller's alias preserved (`start` vs `startTime`). It is
  omitted for cross-field conflicts. Raw values never appear on the wire.
- **`invoke_service` DATETIME literal defense.** Relative or informal literals (`now-1h`, `last 2h`,
  `3 days ago`, `today`, clock times) in DATETIME parameters, and in JSON/VARIANT parameters or
  their top-level object keys with DATETIME-like names, fail with `UNSUPPORTED_RELATIVE_LITERAL`.
  The error carries a path-style `rejectedParameter` (e.g. `payload.startDate`) and a
  `rejectionReason` (`CALENDAR_OR_WALL_CLOCK`, `INFORMAL_RELATIVE`, `DAY_TOKEN`; treat unknown
  values as opaque). An INFO log records `reason=` and a truncated value.
- **Natural time for custom `_tool_*` services.** Services with a DATETIME pair
  (`startDate`/`endDate` or `startTime`/`endTime`) gain synthetic `calendarPhrase` and
  `relativeDuration` fields (skipped if the service already declares those names); the pair is no
  longer required, and values are resolved to ISO instants before the call. A lone explicit bound
  fails with `INVALID_TIME_RANGE` naming the service's own parameter. All custom DATETIME parameters
  get the literal defense, with the same error envelopes as built-in tools.
- **QUERY parameters.** `invoke_service` parses and validates QUERY-typed parameters.

### Known limitations

- The log query tools (`query_application_log`, `query_script_log`, `query_communication_log`,
  `query_security_log`) do not support natural-time fields.

---

## [0.1.52] — 2026-04-25

**Summary:** Hierarchy service parameters are now required, and the hierarchy service reference
clarifies asset-list and empty-result semantics.

### Changed

- **Required hierarchy parameters.** The `name` / `id` parameters of `ResolveNetworkID`,
  `GetAssetList` and `GetChildNodes` on `AgentThing` are now marked required. `HierarchyNode_DS`
  fields carry descriptions.
- **Hierarchy service reference.** `docs/architecture/hierarchy-network-services.md` now states that
  `GetAssetList` returns the union of assets across the node's subtree, and distinguishes an empty
  `intersectThingNames` from a scoped query that legitimately matches nothing.

---

## [0.1.51] — 2026-04-25

**Summary:** Added `HierarchyNode_DS` and fixed the hierarchy service signatures.

### Added

- **`HierarchyNode_DS`.** New DataShape with `id` (primary key) and `name`.

### Changed

- **Hierarchy service signatures.** `ResolveNetworkID(name)`, `GetAssetList(id)` and
  `GetChildNodes(id)`. Default implementations return `HierarchyNode_DS` for
  `GetFlattenNameDescription`, `ResolveNetworkID`, `GetRootNode` and `GetChildNodes`, and
  `EntityList` for `GetAssetList`.

---

## [0.1.50] — 2026-04-25

**Summary:** Hierarchy Network parameter keys now come from configuration.

### Changed

- **Configured Network keys.** The hierarchy services read their Network parameter keys from
  configuration (the same source as `networkName`), falling back to `displayName` and `networkId`.

---

## [0.1.49] — 2026-04-25

**Summary:** Default, overridable Hierarchy Network services on `AgentThing`.

### Added

- **Hierarchy Network services.** `GetFlattenNameDescription`, `ResolveNetworkID`, `GetRootNode`,
  `GetAssetList` and `GetChildNodes` are available on `AgentThing` for tenant override. Each returns
  an empty `EntityList` INFOTABLE by default.

---

## [0.1.48] — 2026-04-16

**Summary:** Defined the override surface for the five Hierarchy Network services.

### Added

- **Hierarchy Network override surface.** The five services a tenant can override are specified in
  `docs/architecture/hierarchy-network-services.md`.

---

## [0.1.47] — 2026-04-16

**Summary:** `query_entities_by_taxonomy` reports intersect paging flags on empty results.

### Fixed

- **Intersect flags on empty listings.** When `intersectThingNames` is set, `query_entities_by_taxonomy`
  now returns `queryHasMore` / `expandHasMore` / `hasMore` even when the listing is empty, matching
  `query_entities`.

### Added

- **Dropped-entry note.** Invalid entries in the `intersectThingNames` array are dropped and reported
  in an optional `note`. Tool schemas describe `preIntersectMatchCount`; a debug log records the
  intersect name column.

### Compatibility

- `API_CONTRACT` 2.4.12.

---

## [0.1.46] — 2026-04-16

**Summary:** Intersect results report query-side and expand-side paging separately.

### Changed

- **Intersect paging flags.** Intersect success adds `queryHasMore` and `expandHasMore`.
- **`INTERSECT_LIST_TOO_LARGE` ordering.** The error is now returned after template/shape validation
  and before the entity query runs.

### Fixed

- **`query_entities` `queryHasMore`.** Now computed from the page size before intersection.

### Compatibility

- `API_CONTRACT` 2.4.11.

---

## [0.1.45] — 2026-04-16

**Summary:** Entity queries can be intersected with an expanded set of Thing names.

### Added

- **Intersect parameters.** `query_entities` and `query_entities_by_taxonomy` accept optional
  `intersectThingNames` and `intersectExpandHasMore` to restrict results to an expanded row set
  (for example, the Things under a hierarchy node).

### Compatibility

- `API_CONTRACT` 2.4.10.

---

## [0.1.44] — 2026-04-16

**Summary:** Structured host-scope rejection logging.

### Changed

- **Host-scope logs.** `ParlerStreamToRemoteThing` logs host-scope decisions as `hostScope*=` fields
  including the reject reason. Accepted payloads whose invalid `kv` pairs were dropped also record a
  reason.

### Fixed

- **HITL pending snapshots.** The pending-approval message snapshot is always an independent copy.

### Compatibility

- `API_CONTRACT` 2.4.9.

---

## [0.1.43] — 2026-04-16

**Summary:** Host-scope validation outcomes carry a reject reason code.

### Changed

- **Reject reasons.** Each host-scope validation decision now includes a machine-readable reject
  reason.

### Compatibility

- `API_CONTRACT` 2.4.8.

---

## [0.1.42] — 2026-04-16

**Summary:** Host-scope contract clarifications.

### Changed

- **`kv` sizing and `rejectDetail`.** Size limits for `kv` apply to the uplink wire bytes;
  `rejectDetail` is human-readable text for logs only. An empty `kv` pair list is accepted.

### Compatibility

- `API_CONTRACT` 2.4.7.

---

## [0.1.41] — 2026-04-16

**Summary:** Host-scope validation is more lenient for individual `kv` pairs.

### Changed

- **`kv` pair handling.** Pairs with an invalid value type are dropped individually instead of
  rejecting the payload; oversize pairs still reject the whole payload. Validation outcomes carry
  a `rejectDetail`. `hierarchy_scope.path: []` is accepted.

---

## [0.1.40] — 2026-04-16

**Summary:** HITL approval snapshots no longer keep per-turn system injections.

### Fixed

- **Pending approval snapshots.** Messages stored with a pending approval no longer include the
  per-turn ephemeral system injections (skill catalog, slash command, time anchor, taxonomy, alert
  prompt, host scope), so they are not duplicated when the turn resumes.

---

## [0.1.39] — 2026-04-16

**Summary:** Validation of the host-scope JSON uplink.

### Added

- **Host-scope validation.** Host-scope payloads are validated in order (size, JSON, `kind`, schema).
  Validation fails open with structured warnings. A valid payload adds a short system line to the
  `ParlerStreamToRemoteThing` turn.

---

## [0.1.38] — 2026-04-24

**Summary:** Optional host context on AlwaysOn prompts.

### Added

- **`hostContext` parameter.** `ParlerGateway.SubmitUserPrompt` and
  `AgentThing.ParlerStreamToRemoteThing` accept an optional `hostContext` (see
  `CONTRACTS/API_CONTRACT.md`, hostContext). It is available to tools for the active turn; payloads
  larger than 16384 UTF-8 bytes are dropped with a warning.

---

## [0.1.37] — 2026-04-17

Internal maintenance; no user-visible changes.

---

## [0.1.36] — 2026-04-17

Internal refactoring; no behavior change.

---

## [0.1.35] — 2026-04-17

**Summary:** Consistent error codes for `acknowledge_alerts` probe failures.

### Fixed

- **Probe error normalization.** When the `specific_alerts` summary probe
  (`QueryAlertSummaryForThing`) fails, `acknowledge_alerts` now returns the same normalized
  `PLATFORM_ALERT_*` errors as the acknowledge services. The tool description mentions this.

---

## [0.1.34] — 2026-04-17

**Summary:** Safer single-alert acknowledgement, stable alert error codes and a shipped alert skill.

### Added

- **`_skill_alert_query`.** Skill service on `AgentThing` with guidance for alert queries.
- **Narrow acknowledge.** For `specific_alerts`, when exactly one unacknowledged row matches and no
  `alertName` filter is given, the tool calls `AcknowledgeAlert` directly. The result reports
  `platformAckService` and a branch-specific `note`.
- **Stable error codes.** Acknowledge platform failures return `PLATFORM_ALERT_*` codes.

### Changed

- **History metadata.** `query_alert_history` results include `historyQueryResource`.
- **Prompt and descriptions.** The default alert routing text is split from the skill text;
  `GetAlertPrompt` output is unchanged. Descriptions of `query_alert_history`, `acknowledge_alerts`
  and `get_agent_skill` were updated.

---

## [0.1.33] — 2026-04-17

**Summary:** `query_alert_summary` gained a `sort` argument.

### Added

- **`sort`.** Values `default`, `timestamp_*` and `priority_*`, merged into the typed query. It cannot
  be combined with `advancedQuery.sorts` (`INVALID_SUMMARY_QUERY`). When a non-default sort is
  applied, results include `appliedSummarySort`.

---

## [0.1.32] — 2026-04-17

**Summary:** `query_alert_history` gained an `order` argument.

### Added

- **`order`.** `newest_first` or `oldest_first`, alongside the legacy `oldestFirst`. Conflicting
  values return `INVALID_HISTORY_ORDER`.

---

## [0.1.31] — 2026-04-17

**Summary:** `query_alert_history` gained time presets.

### Added

- **`timePreset`.** `last_1h`, `last_24h` or `last_7d`. A preset cannot be combined with explicit
  bounds. Without bounds or preset, an implicit 7-day window applies. Results include
  `timeRangeSource` and, where relevant, `appliedTimePreset` or `implicitDefaultWindowDays`.

---

## [0.1.30] — 2026-04-17

**Summary:** `acknowledge_alerts` response fields are documented in the tool description.

### Changed

- **Tool description.** Describes `countsAvailable`, `ackMessage` and the `message` returned when
  nothing matches. The 500-row batch limit uses a single threshold throughout.

---

## [0.1.29] — 2026-04-17

**Summary:** `acknowledge_alerts` echoes the acknowledge message.

### Added

- **`ackMessage`.** Success results for `property_all` and `specific_alerts` include `ackMessage`
  when the `message` argument was supplied.

---

## [0.1.28] — 2026-04-17

**Summary:** Batch guard for `specific_alerts` acknowledgement and clearer count metadata.

### Fixed

- **Batch guard.** The `specific_alerts` probe considers only unacknowledged alerts; if more than 500
  rows match, the tool returns `ACK_MATCHES_EXCEED_LIMIT` instead of silently acknowledging the first
  500.
- **Java 11 compatibility.** The default alert prompt no longer uses a Java 15 text block.

### Changed

- **Count metadata.** `specific_alerts` success adds `countsAvailable` and a `note`; `property_all`
  reports `countsAvailable: false`.

---

## [0.1.27] — 2026-04-17

**Summary:** Built-in alert tools and an overridable alert prompt.

### Added

- **Alert tools.** `query_alert_summary`, `query_alert_history` and `acknowledge_alerts`, backed by
  the `AlertFunctions` resource (`QueryAlertSummaryForThing`, `QueryAlertHistory`,
  `AcknowledgeAlertFromSummary`, `AcknowledgeAlert`). Queries accept typed filters, which are ANDed
  with an optional `advancedQuery`. Results use the standard INFOTABLE shape.
- **`GetAlertPrompt`.** Overridable service on `AgentThing` whose Markdown (with a built-in default)
  is injected each turn after the taxonomy block.
- **Charting.** `query_alert_summary` and `query_alert_history` results qualify for the tabular cache
  used by `build_chart_from_tabular_result`, like `invoke_service`.

---

## [0.1.26] — 2026-04-17

Internal refactoring; no behavior change.

---

## [0.1.25] — 2026-04-17

**Summary:** Table export fields survive history replay, and PASSWORD columns are masked for the LLM.

### Added

- **Export sidecar in history.** Table export fields are stored with the tool message
  (`_parlerTableExport`) so history `tables[]` keep `export*` fields on replay. Export is not
  repeated when its fields are already set.

### Security

- **PASSWORD masking.** In tool JSON sent to the LLM (`rows[]`, `sampleRows`), PASSWORD columns are
  replaced with `***`. The cached InfoTable is unchanged.

---

## [0.1.24] — 2026-04-17

**Summary:** The UI shows a download link for exported table CSV files.

### Compatibility

- Contract bundle 0.1.25 (`CONTRACTS/TABLE_CONTRACT.md` §4): `parler-ui` renders `exportStatus: ok`
  as a `/Thingworx/FileRepositories/...` download link. Pair with widget 0.1.16.

---

## [0.1.23] — 2026-04-17

**Summary:** Table CSV export fix and file-name collision handling.

### Fixed

- **CSV export.** Writing the CSV via `SaveText` on the export repository Thing now works.
- **Path collisions.** If the target file already exists, a unique path is chosen (`path_collision`).

### Compatibility

- Contract bundle 0.1.24 (`CONTRACTS/TABLE_CONTRACT.md` §3.5).

---

## [0.1.22] — 2026-04-16

**Summary:** Optional CSV export of large or sampled tables to a FileRepository.

### Added

- **Table CSV export.** When `totalRows` exceeds the threshold or the table is a partial sample, the
  full table is written as UTF-8 CSV via `SaveText` to the repository named by `exportFileRepository`.
  The table carries `exportStatus`, `exportMessage`, `exportFile` and `exportRepository`. CSV output
  is capped at 5,000,000 characters.
- **Settings.** `AgentSettings` gained `exportFileRepository` and `tableCsvExportRowThreshold`
  (default 200).

### Compatibility

- Contract bundle 0.1.23 (`CONTRACTS/TABLE_CONTRACT.md` §3.5).

---

## [0.1.21] — 2026-04-16

**Summary:** `fetch_cached_result` paging fixes.

### Fixed

- **Offset past end.** `returnedRows` is no longer negative when `offset` exceeds `totalRows`; the
  response still echoes the requested `offset`.
- **Empty pages.** An empty page with column metadata now renders as an empty table.

### Compatibility

- Contract bundle 0.1.21 (`CONTRACTS/TABLE_CONTRACT.md`).

---

## [0.1.20] — 2026-04-16

**Summary:** `fetch_cached_result` results render as tables.

### Added

- **Table rendering.** Successful `fetch_cached_result` calls send a live table and appear in history
  `tables[]`. The result includes `columns[]` with `baseType` from the cached InfoTable.

### Compatibility

- Contract bundle 0.1.20.

---

## [0.1.19] — 2026-04-16

**Summary:** `invoke_service` INFOTABLE results include column base types.

### Changed

- **Column metadata.** Inline and large INFOTABLE results list `columns[]` with `name` and `baseType`
  (ThingWorx base type name), consistent with other tabular tools.

### Compatibility

- Contract bundle 0.1.19 (`CONTRACTS/TABLE_CONTRACT.md`).

---

## [0.1.18] — 2026-04-16

**Summary:** `invoke_service` INFOTABLE results render as tables.

### Added

- **Table rendering.** Results with `resultKind` `INFOTABLE` or `INFOTABLE_LARGE` send a live
  `entity-list` table and appear in history `tables[]`.

### Compatibility

- Contract bundle 0.1.18.

---

## [0.1.17] — 2026-04-16

**Summary:** Table view: entity listings render as tables in the chat UI.

### Added

- **Table wire.** AlwaysOn `type: "table"` messages (`CONTRACTS/TABLE_CONTRACT.md`).
- **Entity-list tables.** Results of `tabulate_cached_result`, `query_entities_by_taxonomy`,
  `list_entities_by_type` and `query_entities` send an `entity-list` table before
  `tabular.tool_success` (ordering per `CONTRACTS/API_CONTRACT.md`).
- **History replay.** Assistant rows in `ai-parler-history-v1` include `tables[]` rebuilt from these
  tool results.

---

## [0.1.15] — 2026-04-16

Documentation updates only.

---

## [0.1.14] — 2026-04-16

Internal maintenance; no user-visible changes.

---

## [0.1.13] — 2026-04-16

Internal maintenance; no user-visible changes.

---

## [0.1.12] — 2026-04-16

**Summary:** Keeps the last-tabular-cache reference consistent after cache misses.

### Changed

- **Cache-miss pruning.** A `CACHE_MISS` from `tabulate_cached_result` or `summarize_cached_result`
  clears the conversation's last-qualifying-cache reference if it pointed at the missing id.
- **Summarize updates the reference.** A successful `summarize_cached_result` sets the reference from
  its `sourceCacheId`, without affecting chart state.

### Compatibility

- Contract bundle 0.1.10 (`TABULAR_INSIGHT` 1.0.6).

---

## [0.1.11] — 2026-04-16

**Summary:** Lifecycle rules for the conversation-scoped last-tabular-cache reference.

### Changed

- **Lifecycle.** `ClearConversation` removes the reference for that conversation. Single-turn calls
  are keyed by the Parler `request_id` and cleared at the end of the turn, so persistent threads
  keep the reference across messages.

### Compatibility

- Contract bundle 0.1.9 (`TABULAR_INSIGHT` 1.0.5).

---

## [0.1.10] — 2026-04-16

**Summary:** The last-tabular-cache token works across messages in a conversation.

### Changed

- **Cross-message fallback.** For `tabulate_cached_result` / `summarize_cached_result`, the
  last-cache token first resolves to the current turn's last qualifying `cacheId`, then falls back to
  the last qualifying `cacheId` in the conversation. A qualifying inline-only result clears that
  fallback. The `LAST_TABULAR_CACHE_UNAVAILABLE` message text was updated.

### Compatibility

- Contract bundle 0.1.8. Pair with widget 0.1.10.

---

## [0.1.9] — 2026-04-16

**Summary:** Cached tabular tools accept a token for "the last qualifying cache".

### Added

- **Last-cache token.** `tabulate_cached_result` and `summarize_cached_result` accept the literal
  `__PARLER_LAST_QUALIFYING_TABULAR_CACHE__` as `cacheId`, resolving to the last qualifying cache of
  the current turn (the same source `build_chart_from_tabular_result` uses). If it cannot resolve,
  the tool returns `LAST_TABULAR_CACHE_UNAVAILABLE`. `sourceCacheId` in results is the resolved id.
  Tool descriptions and the routing guide were updated.

### Compatibility

- Contract bundle 0.1.7 (`CONTRACTS/TABULAR_INSIGHT.md` §2). Pair with widget 0.1.9.

---

## [0.1.8] — 2026-04-16

**Summary:** Fixed empty rows in `query_entities_by_taxonomy` results.

### Fixed

- **Empty row objects.** Rows in `rootEntityList` were sometimes serialized as `{}` even though
  `matchedRows` / `totalCount` were correct. Projected values such as `name` and `CriticalProperties`
  now appear in tool output and in the `firstRowPreview` log.

### Compatibility

- Pair with widget 0.1.8.

---

## [0.1.7] — 2026-04-16

**Summary:** More stable `query_entities_by_taxonomy` rows for the LLM.

### Fixed

- **String-like nulls.** Projected STRING, TEXT, HTML and GUID columns with no readable value are
  emitted as `""` instead of being omitted, so each row matches the taxonomy column set. Non-text
  nulls still omit the key.

### Added

- **`firstRowPreview` log.** An INFO log (`context=inline` or `large-sample`) records the JSON size
  and a truncated sample of the first matched row.

### Compatibility

- Pair with widget 0.1.7.

---

## [0.1.6] — 2026-04-15

**Summary:** `query_entities_by_taxonomy` keeps `CriticalProperties` columns that the property
definitions omit.

### Fixed

- **Missing projected columns.** If a `CriticalProperties` property is missing from the instance
  property definitions but still readable, its base type is inferred from a live read instead of
  dropping the column.

### Changed

- **Model guidance.** The routing guide and tool descriptions tell the model to pass
  `CriticalProperties` from the taxonomy row when listing names or display/serial fields, and not to
  claim a value is unset without a tool result.

### Compatibility

- Pair with widget 0.1.6.

---

## [0.1.5] — 2026-04-15

**Summary:** Fixed reading the asset taxonomy from overridden services.

### Fixed

- **Taxonomy INFOTABLE shape.** `GetAssetTaxonomyForAgent` results are normalized the same way as
  `invoke_service`. Scripted or reflection-style overrides often return the taxonomy rows as the
  top-level InfoTable rather than a one-row `result` wrapper; this was previously treated as a
  failure even though the service returned a valid table.

---

## [0.1.4] — 2026-04-15

**Summary:** The injected asset taxonomy takes precedence over ad-hoc entity inference.

### Changed

- **Taxonomy precedence.** When a taxonomy is injected, the routing guide and the descriptions of
  `query_entities`, `query_entities_by_taxonomy` and `list_entities_by_type` require counts and parent
  selection to follow the taxonomy table, using `query_entities_by_taxonomy` with `EntityType` /
  `EntityName` from its rows instead of unrelated ThingTemplate catalogs.

### Added

- **Taxonomy log.** The final per-turn taxonomy Markdown is logged at INFO (character count and body).

---

## [0.1.3] — 2026-04-15

**Summary:** The asset taxonomy services can be overridden.

### Fixed

- **Overridable taxonomy services.** `GetAssetTaxonomyForAgent` and
  `GetAssetTaxonomyForAgentDirect` allow override, and the agent invokes them through the platform
  service dispatch so overrides take effect.

---

## [0.1.2] — 2026-04-15

**Summary:** Built-in transforms and summaries over cached tabular results, with an insight envelope
and an optional UI downlink.

### Added

- **`tabulate_cached_result`.** Deterministic `sort_topn` (stable tie-break by row index),
  `group_count` and `group_aggregate` over the full cached table for a `cacheId`. Large inputs are
  rejected with `TABLE_TOO_LARGE_FOR_TRANSFORM`. Large outputs are stored under a new `cacheId` and
  include `sourceCacheId`.
- **`summarize_cached_result`.** Per-column statistics: numeric min/max/mean and p50/p95 (for
  `percentileColumns`, or the first 8 numeric columns), categorical `cardinality` / `top`, datetime
  min/max, and `unsupportedStatsReason` for other types.
- **`insightEnvelope`.** Success results of both tools include `insightEnvelope` (`schemaVersion` 1,
  `sourceCacheId`, `rowEstimate`, `columns[]`). Clients should tolerate its absence.
- **`tabular.tool_success` downlink.** When a tool result contains `insightEnvelope`, AlwaysOn clients
  receive `tabular.tool_success`; `parler-ui` exposes it as `assistant.insightEnvelope`.
- **Routing guide.** A section on cached tabular tools: use them for aggregations and prefer the cache
  over re-querying. Tool reference: `docs/agent/cached_tabular_tools.md`.

### Changed

- **Charting.** `tabulate_cached_result` results qualify as a source for
  `build_chart_from_tabular_result`. The `fetch_cached_result` and chart tool descriptions clarify
  paging versus transforms.
- **Group limits.** For `group_count` / `group_aggregate`, an explicit `limit` must be 1–500,
  otherwise `LIMIT_OUT_OF_RANGE`; without `limit`, at most 500 groups are returned.
- **Strict arguments.** `percentileColumns` must be an array of non-empty strings naming numeric
  columns; unparseable or non-object `arguments` return `INVALID_PARAMETERS`.

### Compatibility

- Contract bundle 0.1.6 (`CONTRACTS/TABULAR_INSIGHT.md`, `CONTRACTS/UI_CLIENT_PROTOCOL.md`,
  `CONTRACTS/API_CONTRACT.md`). Pair with widget 0.1.5.

---

## [0.1.1] — pending first deployment

**Summary:** Initial baseline of the Parler ThingWorx extension.

### Added

- **`AgentThing`.** LLM chat loop, built-in tool registry, custom skills (`_skill_*` services),
  conversation and thread persistence, and per-turn system injections (time anchor, taxonomy block,
  skill catalog).
- **Asset taxonomy.** `TaxonomyDataShape` with `GetAssetTaxonomyForAgent` /
  `GetAssetTaxonomyForAgentDirect`; rendering rules in `CONTRACTS/AGENT_TAXONOMY_RENDERING.md`.
- **Built-in tools.** `invoke_service`, `fetch_cached_result`, `discover_services`,
  `get_service_definition`, `discover_properties`, `get_entity` (stub), `query_entities`,
  `query_entities_by_taxonomy`, `list_entities_by_type`, `get_property_values`,
  `query_numeric_property_history`, `set_property_value`, `spotlight_search`,
  `build_chart_from_tabular_result` and `get_agent_skill`. `query_entities_by_taxonomy` takes a single
  ThingTemplate or ThingShape parent, ORs `LookupProperties` keys, projects columns and returns
  `ENTITY_TAXONOMY_QUERY_EMPTY`, `_INLINE` or `_LARGE`.
- **`ParlerGateway`.** AlwaysOn streaming gateway.
- **Entities.** Thread DataTable, message Stream and conversation DataShapes.
