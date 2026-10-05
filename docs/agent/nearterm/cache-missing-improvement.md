# Cache Column Roles, Projection Feedback, and Source Echo

This document specifies how cached tables tell the model which columns to use, how
`analyze_cached_result` reports a wrong column name, and how analysis results echo the operands
they actually used. It covers:

- **CM-0** — optional writer-declared column roles (`timeColumn` / `valueColumn`) on the runtime
  `SourceDescriptor`;
- **CM-1** — real column names, types, and roles returned by the numeric-history producers
  (`build_history_overlay_chart` `seriesCaches[]` and numeric `query_property_history`);
- **CM-2** — structured feedback when `analyze_cached_result` cannot project a requested column;
- **CM-B1** — relationship lineage that records both operands;
- **CM-4** — optional writer-declared subject identity (`subjectThingName` /
  `subjectPropertyName`) and the `sources[]` echo on analysis results.

Related documents: [`tool-cache-integration.md`](./tool-cache-integration.md) (`SourceDescriptor`,
`TabularArtifactHub`, `TypedTabularStream`), [`deterministic-insight-kernel.md`](./deterministic-insight-kernel.md)
(`analyze_cached_result`), [`docs/agent/history-overlay-chart.md`](../history-overlay-chart.md)
§9.2.1 (`seriesCaches`), and [`docs/agent/AGENT-CONTEXT.md`](../AGENT-CONTEXT.md).

## 1. Purpose and boundaries

In multi-turn data sessions the model first charts or queries history, then runs deterministic
calculations on the same data. Calculation tools accept only cache handles, so the model must
name the cached table's columns. Without column metadata, models tended to pass the property name
(for example `operationalVoltage`) where the cache column is `value`, received a bare
`ARGUMENT_MISSING`, and re-queried the history instead of correcting the call.

The mechanism is general: any producer that writes a table to the conversation cache may declare
column roles at write time, and the consumer reads only that metadata and the visible schema.

Boundaries of the implemented behavior:

- Roles are never inferred from `sourceRouteId`, tool names, column names such as `timestamp` or
  `value`, or "the only numeric column".
- No system-prompt or routing-guide text is involved.
- A wrong `cacheId` is not guessed or replaced; `CACHE_MISS` keeps its existing shape.
- No G18 `recoveryActions` (such as `PATCH_ARGUMENT`) and no `retryable` flag are emitted, and
  the server never re-runs the call with a suggested column. A suggestion is a column-selection
  hint only; it does not prove that the user chose the right metric or handle.
- Column errors of `tabulate_cached_result`, `summarize_cached_result`, and `exact_join` keep
  their existing shapes (`CONTRACTS/TABULAR_INSIGHT.md` §1).
- `SourceDescriptor` carries no device, property, or time-window index, and there is no index or
  registry outside the cache scope.
- `ChartBlock`, UI wire, tool registration, admission, execution policy, budgets, history
  compaction, and tool-result egress are unchanged.

## 2. Runtime context

| Surface | Behavior |
|---|---|
| Numeric-history cache writer | `tools/NumericHistoryCacheWriter` is the single writer for numeric-history caches used by both `build_history_overlay_chart` (through `HistorySeriesComposerSupport.storeNumericHistoryPointsInConversationCache`) and numeric `query_property_history` (through `PropertyToolsExecutor.cacheNumericHistorySeries`). It builds a two-column table — `timestamp` (STRING, ISO-8601) and `value` (NUMBER) — declares roles and identity, stores it, and returns `StoredSeriesCache{cacheId, columns, timeColumn, valueColumn}`. |
| Descriptor route ids | `build_history_overlay_chart` and `query_numeric_property_history` (a source record only; consumers never branch on it; `query_numeric_property_history` is an executor alias, not a model-visible tool name). The history path also attaches property semantics with `SemanticSourceHandoff.attachPropertyProvenance`. Both paths start from `SourceDescriptorSupport.forPrimaryStore`, whose completeness is `UNKNOWN`. |
| Descriptor index | `TabularArtifactHub.store(table, descriptor)` records the descriptor in a runtime index keyed by scope id + `cacheId`. It is cleared with the scope (`invalidateScope*`), empty after a JVM restart, and read through `lookupDescriptorForConversation` under the namespace check. Descriptors are not written into the artifact payload. |
| Column projection | `TypedTabularStream.open(cacheId, projectedColumns, maxRows)` resolves the ref (missing → `CACHE_MISS`), opens the artifact, reads the full visible schema (PASSWORD columns excluded), looks up the descriptor, and then projects. Only `analyze_cached_result` passes a projection list; other readers pass `null`. |
| Analyze projection order | `dispatch` projects the left table with `[timeColumn, valueColumn]`; RELATIONSHIP then projects the right table with `[timeColumn, rightValueColumn]`. `timeColumn` is shared by both sides; an omitted `rightValueColumn` inherits `valueColumn`. |
| Error egress | Error JSON goes through the compact error envelope: arrays are sampled, text over 512 characters is excerpted, and arrays named `columns` are copied unsampled. The size of the CM-2 diagnostics is therefore bounded at the analyze serialization boundary (§5 CM-2 item 4), not by egress. |

## 3. Design principles

1. **Data before prompts.** Producer results and cache metadata carry the facts the model needs.
2. **Writers declare, consumers read.** Consumers use descriptor metadata and the visible schema,
   never tool names, column names, or types. One mapping per write feeds both the success result
   and the error feedback.
3. **Suggest only what is provable.** A unique case-insensitive match is a spelling fix; a
   validated role is a structural fact. Neither is a business-semantics proof, and neither is
   applied automatically.
4. **Additive only.** Existing outer fields and their meanings are unchanged.
5. **Same lifecycle as the cache.** Roles and identity live on the descriptor and are cleared with
   the scope; derived tables do not inherit them.

## 4. Descriptor rebuild classification

`SourceDescriptor` is rebuilt in two situations, and roles and identity follow the same rule:

| Entry | Kind | Roles and subject identity |
|---|---|---|
| `SourceDescriptorSupport.withSemanticProvenance`, `appendParent`, `withReadLimitFact`, `DerivedArtifactLineage.withCompleteness` | metadata decoration of the same artifact and schema | **kept** (`SourceDescriptor.copyColumnRoles` and `copySubjectIdentity`, called beside and independently of `copySemanticProvenance`) |
| `forPrimaryStore`, `forDerivedStore` / `composeDerived`, `DerivedArtifactLineage.forJoin` / `forTransform`, `CachedTabularToolsExecutor.descriptorForScannedSource`, `CachedPayloadInspect`, `JsonArtifactHub` | new table or new result | **not set**; derived results (tabulate, summarize, join, analyze outputs) carry roles or identity only if their writer declares them, and currently none do |

`copySemanticProvenance` copies only property-semantics fields and never roles or identity.
`withColumnRoles` and `withSubjectIdentity` share the internal `copyAll`, so declaring one never
clears the other.

## 5. Specification

### CM-0 Writer-declared column roles

`SourceDescriptor` has an optional closed pair `timeColumn` / `valueColumn` (builder methods of the
same name; `null` when absent). Values are real column names of the written table. The pair is
separate from `propertyRoleRef` (a property-semantics reference, not a column role). New roles, if
ever added, would be additional optional keys on the same descriptor.

**Validation (`source/ColumnRoles`, one rule used by writers and consumers).** Roles are trusted
only when both are non-blank after trimming, both name a visible non-PASSWORD column (exact
match), and they differ. Otherwise the table has no trusted roles; this is never an error.

- `SourceDescriptorSupport.withColumnRoles(base, timeColumn, valueColumn, visibleNames)` clears
  both roles and sets them only when the pair validates against the columns about to be written.
  An invalid declaration omits the roles from both the descriptor and the success JSON; the write
  itself still succeeds.
- Consumers validate again with `ColumnRoles.fromDescriptor` against the schema actually read, and
  only after the cache access check has succeeded.

**Writer.** `NumericHistoryCacheWriter.store` declares `timestamp` / `value`;
`storeWithRoles(table, sourceRouteId, timeColumn, valueColumn, …)` lets any producer store any
table with its own roles. Roles are stamped on the `forPrimaryStore` descriptor before any
decoration (`attachPropertyProvenance`, `withReadLimitFact`), and those decorations keep them.

### CM-1 Producer column descriptions

**Overlay `seriesCaches[]`.** Each entry keeps `label`, `thingName`, `propertyName`, `cacheId`,
`totalRows` (in that order, plus `readLimitReached: true` when the platform read limit was hit)
and adds:

| Field | Source | Example |
|---|---|---|
| `columns[]` | `StoredSeriesCache.columns` — the written columns as `{name, baseType}` | `[{"name":"timestamp","baseType":"STRING"},{"name":"value","baseType":"NUMBER"}]` |
| `timeColumn` / `valueColumn` | validated roles from the same write; omitted when the declaration was invalid | `"timestamp"` / `"value"` |
| `windowStart` / `windowEnd` | the series' resolved **request window** (ISO-8601 UTC), not data coverage | `"2026-09-15T02:00:00Z"` |
| `resolvedTimeZone` | the resolved IANA zone; omitted when blank | `"America/New_York"` |

The request window appears only in `seriesCaches[]` and is not written to the descriptor. Entries
exist only on success and only for series with points.

**Numeric `query_property_history` result.** `columns[]` comes from `StoredSeriesCache.columns`
(the canonical two-column shape when nothing was stored), and the result adds `timeColumn` /
`valueColumn` when roles were stamped. The rest of the envelope (`$format`, `resultKind`,
`sampleRows`, `aggregates`, `chartBlock`, …) is unchanged.

No tool input schema changes; each overlay entry grows by roughly 150–200 characters.

### CM-2 Structured feedback for analyze projection errors

Applies only when an `analyze_cached_result` `timeColumn`, `valueColumn`, or `rightValueColumn`
does not match a column of the target cache. Other argument errors, `CACHE_MISS`, budget errors,
and protected-column errors keep their shapes.

1. **Projection exception.** `TypedTabularStream.open` reads the schema and descriptor before
   projecting. An unknown column throws `UnknownProjectedColumnException` (a subclass of
   `IllegalArgumentException`; message unchanged: `unknown projected column: X`) carrying the
   rejected name, its index in the projection list, the visible schema, and the descriptor (may
   be `null`). The reader is closed on the way out and the artifact is not reopened. Callers that
   pass a `null` projection and other catchers of `IllegalArgumentException` are unaffected; only
   `AnalyzeCachedResultExecutor` maps the extra fields.
2. **Error JSON.** The outer shape stays `status:"ERROR"`, `reason:"ARGUMENT_MISSING"`, `detail`,
   `mayPublish:false`, with these additive fields:

   | Field | Meaning |
   |---|---|
   | `rejectedParameter` | from the projection index: index 0 → `timeColumn`; index 1 → `valueColumn` on the left side, `rightValueColumn` on the right side. If `timeColumn` and `valueColumn` are the same wrong string, index 0 (`timeColumn`) is reported. |
   | `parameterSource` | `explicit`, or `inherited:valueColumn` when the right side's value column was inherited |
   | `side` | `left` / `right` for RELATIONSHIP; omitted for single-input operations |
   | `cacheId` | the handle of the failing side |
   | `schema` | `{columnCount, columns:[{name, baseType}], truncated}` for the failing side only — the first failure is reported; a right-side failure does not reopen the already-projected left table, and on a left-side failure the right table was never opened |
   | `columnSuggestion` | optional `{<parameter>: <real column name>}`, only per item 3 |

3. **Suggestion rule** (evaluated in order; the first step that decides "no suggestion" ends the
   evaluation):
   - **Shared-parameter gate:** `side="right"` and `rejectedParameter="timeColumn"` → no
     suggestion (the left side already projected with that name, so renaming would break it).
   - **Spelling fix:** exactly one column of the failing side's full visible schema equals the
     rejected name case-insensitively → that column's real name, even if it is not a role
     column. Two or more such columns → no suggestion, and the role step is not tried.
   - **Validated role:** the failing side's descriptor has trusted roles (CM-0) → `timeColumn`
     maps to the role `timeColumn`; `valueColumn` and `rightValueColumn` map to the role
     `valueColumn`.

   A left-side `timeColumn` suggestion describes only the left table; the right side is then
   projected with the same name and, if it fails, reports its own schema and roles. Uniqueness
   and role validation always use the full in-memory schema, never the bounded display.
4. **Bounded `schema`** (`tools/AnalyzeProjectionDiagnostics`). `columnCount` is the full visible
   column count. `columns[]` lists columns in source order, at most **32** entries, and the
   serialized `schema` object is at most **2,000** characters. Column names are never clipped:
   a column that does not fit is omitted whole, and a name longer than the error-egress excerpt
   length (512 characters) is treated as unrepresentable and omitted. `truncated` is true
   whenever any visible column is missing from the list. A suggested column keeps its place
   (taking the last slot if it is beyond the first 32, and other columns are dropped first); if
   even the suggested column cannot fit, `columnSuggestion` is omitted.
5. No G18 fields, no `RetryLedger` use, and no new serialization entry point;
   `ErrorRecoveryMapper` and `TypedToolErrorJson` are not involved. The descriptor object stays
   inside Java; only the fields above are serialized.
6. Error egress is unchanged (§2): `schema.columns` is not sampled, so item 4 is the only size
   bound. `seriesCaches` is not a priority field for last-resort compaction; overlay results are
   far below the compaction target, and if compaction ever applies, the sidecar follows the
   normal non-priority array rule.
7. No `CONTRACTS/*` clause covers the analyze error shape, the overlay result, or the numeric
   history result. `CONTRACTS/CHART_CONTRACT.md` requires clients and the history hydrator to
   tolerate additive top-level tool-result fields, and `CONTRACTS/TABULAR_INSIGHT.md` §1 (the
   tabulate/summarize error envelope) is not affected.

**Model-visible descriptions** (apply to every cache):

- `analyze_cached_result` `timeColumn` / `valueColumn`: "Use the exact column name declared by
  that cache's result (`columns[]`, and `timeColumn` / `valueColumn` when the result declares
  it); do not infer it from a property label."
- `build_history_overlay_chart` description ends with: "Result `seriesCaches[]` lists each
  series' `cacheId`, columns, `timeColumn` / `valueColumn` and the resolved request window (not
  coverage) for reuse by cached-result tools."

### CM-B1 Relationship lineage records both operands

For RELATIONSHIP, `analysisEnvelope.sourceCacheIds[]` lists the left and right handles in that
order, each once (`U5AnalysisEnvelopeFactory.fromG2(result, leftCacheId, rightCacheId,
completeness)`). When both sides read the same cache (different columns) it holds one id, so the
array length is not the operand count. This applies to SUCCESS and INSUFFICIENT_EVIDENCE
(zero matched pairs) alike; computation, alignment, metrics, and single-input operations are
unchanged. It makes the output auditable; it does not prevent choosing the wrong asset.

### CM-4 Subject identity and the `sources[]` echo

The purpose is visibility: a calculation result states which inputs it actually used, so a
mismatched pair (for example one Thing's voltage with another Thing's force) is visible in the
result. It does not reject or warn about cross-asset pairs — those are legitimate requests and
the server has no "expected subject" to check against.

**Descriptor fields.** `SourceDescriptor` has an optional closed pair `subjectThingName` /
`subjectPropertyName` (builder, accessors, blank-to-null), with the same lifecycle as roles: not
persisted in the payload, held in the runtime descriptor index, cleared with the scope.

**Declared from the write path only.**

| Writer | Identity source |
|---|---|
| `build_history_overlay_chart` | `HistoryOverlayChartBuilder.ResolvedSeriesInput.thingName` / `propertyName`, passed by `BuildHistoryOverlayChartExecutor.publishSeriesCaches` to `storeNumericHistoryPointsInConversationCache(points, thingName, propertyName, readLimit)` |
| numeric `query_property_history` | the queried `thing.getName()` and `propertyName` in `cacheNumericHistorySeries` |
| any other producer | optional identity arguments of `NumericHistoryCacheWriter.store` / `storeWithRoles`; omitted when unknown |

Identity is never derived from analyze request text, natural language, `seriesCaches[]` JSON, or
later results, and no platform query is made to verify it.
`SourceDescriptorSupport.withSubjectIdentity(base, thingName, propertyName)` clears both fields
and sets them only when both are non-blank; a missing or half-blank pair leaves both `null`
while roles, semantic provenance, and lineage are kept, and the write succeeds. Keep/drop rules
are those of §4.

**`sources[]`.** Every `analyze_cached_result` result whose envelope status is not ERROR
(SUCCESS, NO_FINDING, INSUFFICIENT_EVIDENCE, …) adds `sources[]` next to `analysisEnvelope`, one
row per operand actually used:

| Key | Source |
|---|---|
| `role` | `left` / `right` for RELATIONSHIP; a single `left` row for single-input operations |
| `cacheId` | the handle actually projected |
| `timeColumn` / `valueColumn` | the column names actually projected (not the descriptor roles) |
| `thingName` / `propertyName` | the cache descriptor's `subjectThingName` / `subjectPropertyName`; both omitted when not declared, while the row remains |

When both sides read the same cache, the two rows share a `cacheId` and differ in columns.
`sources[]` (one row per operand) and `sourceCacheIds[]` (unique ids) are separate fields.
Error results, including CM-2 projection errors, carry no `sources[]`. Subject metadata describes
where a table came from; it is not a semantic proof about arbitrary projected columns and never
changes the columns the caller selected.

## 6. Code locations

| Location | Role |
|---|---|
| `source/SourceDescriptor` | `timeColumn` / `valueColumn`, `subjectThingName` / `subjectPropertyName`, `copyColumnRoles`, `copySubjectIdentity` |
| `source/SourceDescriptorSupport` | `withColumnRoles`, `withSubjectIdentity`; decorations keep both (§4) |
| `source/ColumnRoles` | the single role-validation rule |
| `analysis/DerivedArtifactLineage` | `withCompleteness` keeps; `forJoin` / `forTransform` drop |
| `tools/NumericHistoryCacheWriter`, `tools/StoredSeriesCache` | the numeric-history writer and its store-time result |
| `HistorySeriesComposerSupport`, `tools/BuildHistoryOverlayChartExecutor.publishSeriesCaches` | overlay store and `seriesCaches[]` fields |
| `tools/PropertyToolsExecutor` | numeric history store and result `columns[]` / roles |
| `cache/TypedTabularStream`, `cache/UnknownProjectedColumnException` | descriptor-before-projection and the projection exception |
| `tools/AnalyzeCachedResultExecutor`, `tools/AnalyzeProjectionDiagnostics` | CM-2 error mapping, bounded schema, suggestions, `sources[]` |
| `analysis/U5AnalysisEnvelopeFactory` | relationship lineage (CM-B1) |
| `tools/AnalyzeCachedResultToolSchema`, `BuiltInTools.buildHistoryOverlayChartDef` | the two description sentences |

## 7. Tests

| Test | Covers |
|---|---|
| `SourceDescriptorColumnRolesTest` | role and identity storage; kept on `withSemanticProvenance` (resolved and unresolved), `appendParent`, `withCompleteness`; dropped on `forDerivedStore` / `forJoin`; `withSubjectIdentity` replace semantics |
| `SemanticSourceHandoffTest` | provenance attachment keeps roles and identity |
| `TabularArtifactHubTest` | stored roles readable through the descriptor lookup; unreadable after scope invalidation |
| `M4S4SeriesCachesStoreTest` | returned columns equal the stored schema; roles name real columns; invalid roles omitted while the write succeeds; identity stamped |
| `M4S4SeriesCachesOrderingTest` | existing overlay fields and order unchanged; new fields present; no `seriesCaches` on failure; each series' descriptor carries its own identity |
| `PropertyToolsExecutorNumericHistoryCompactTest` | history result `columns[]` and roles come from the same store as the `cacheId` |
| `TypedTabularStreamProjectionErrorTest` | exception carries schema and index; PASSWORD columns absent; `null` projection unchanged; message unchanged |
| `AnalyzeProjectionDiagnosticsTest` | the schema bound measured on the serialized object |
| `AnalyzeSourcesEchoTest` | `sources[]` for two inputs with identity, same cache on both sides, inherited right column, single input, undeclared identity, none on errors |
| `RelationshipLineageTest` | both operands in `sourceCacheIds[]` |
| `Dik5PackagingTest` | other success keys unchanged |

`AnalyzeCachedResultProjectionErrorTest` covers the CM-2 cases:

- (a) a producer outside any allow-list declares roles `observedAt` / `reading` with extra plain
  columns → `{valueColumn:"reading"}`;
- (b) the same two columns `timestamp` / `value` with no roles → schema only, no suggestion;
- (c) a single numeric column with an unrelated name and no roles → schema only;
- (d) missing, conflicting (`time == value`), nonexistent, or PASSWORD roles → no role
  suggestion;
- (e) a unique case-insensitive match → spelling suggestion; a case collision → none;
- (f) overlay handle with a property name as the column → `ARGUMENT_MISSING` plus
  `rejectedParameter`, `schema`, and `{valueColumn:"value"}`;
- (g) relationship with different column names failing on the right → `side:"right"`, the right
  schema and roles, and the left table not reopened;
- (h) omitted `rightValueColumn` whose inherited value is missing on the right →
  `parameterSource:"inherited:valueColumn"`;
- (i) shared `timeColumn` missing on the right → no suggestion;
- (j) `timeColumn` and `valueColumn` both the same wrong string → `timeColumn` (index 0)
  reported;
- (k) a bad `cacheId` → the existing `CACHE_MISS`;
- (l) success JSON unchanged;
- (m) right-side `timeColumn` miss with a unique case-insensitive match → no suggestion (the gate
  precedes spelling);
- (n) spelling match different from the role column → the spelling match is suggested;
- (o) case collision with trusted roles → no suggestion (no role fallback);
- (p) a wide schema from any source (for example 80 columns with long names) → full
  `columnCount`, `columns[]` within the bounds, `truncated:true`, names intact; the suggested
  column is listed when it fits, otherwise no `columnSuggestion`.
