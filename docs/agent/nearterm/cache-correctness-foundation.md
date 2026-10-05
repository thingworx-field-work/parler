# Cache and Tool Correctness Foundation

This document specifies two foundations of the Parler Agent:

- **Part A — the file-backed artifact cache kernel.** An opaque payload store on a dedicated
  ThingWorx FileRepository with a current-JVM index. Every cached tool result (tabular, JSON,
  text) is written and read through it.
- **Part B — tool correctness rules.** Honest completeness for taxonomy queries, one authority
  for reserved tool names, strict typed values before property-write approval, one row-limit
  precedence for property history, and the bounded specific-alert acknowledgment.

Related documents:

- [`tool-cache-integration.md`](./tool-cache-integration.md) — the public `cacheId`, the tabular
  and JSON adapters (`TabularArtifactHub`, `JsonArtifactHub`), `SourceDescriptor`, and the cached
  result tools built on this kernel.
- [`docs/operations/file-artifact-cache-retention.md`](../../operations/file-artifact-cache-retention.md)
  — operator setup and retention runbook for the cache repository.
- [`CONTRACTS/API_CONTRACT.md`](../../../CONTRACTS/API_CONTRACT.md) — normative wire text for the
  cache readiness terminal error and for `query_entities_by_taxonomy` completeness.

Code: `parler-agent/src/main/java/com/thingworx/things/agent/cache/` (Part A) and the `tools/`
package (Part B).

## Part A — File-backed artifact cache

### A1. Model

The cache stores each artifact as one immutable, opaque payload file plus one immutable metadata
record held in memory. The in-memory index is the only publication and discovery boundary:

- a writer streams one payload file, closes it, and only then inserts one metadata record into the
  index;
- no handle is returned before index insertion succeeds;
- a payload file without an index record is never discovered or reopened;
- a JVM restart (or AgentThing restart) starts with an empty index, so callers re-fetch or
  recompute.

There is no sidecar manifest, persisted catalog, checksum, startup scan, or recovery. Payload
files left on disk after a restart, invalidation, or failed write are removed by administrators
(§A7), never by the Extension.

The kernel does not interpret payload bytes. The payload format (for example the InfoTable JSON
encoding used by `TabularArtifactHub`) belongs to the adapter that writes and reads it.

### A2. Configuration and turn admission

**Setting.** `AgentSettings.artifactCacheFileRepository` (THINGNAME, FileRepository template)
names a dedicated FileRepository Thing. It is required: there is no in-memory fallback in
production. One cache instance exists per distinct repository Thing name in the JVM
(`FileArtifactCacheRegistry`); `ArtifactCacheCore.requireCache` resolves it for the current
AgentThing. `InMemoryArtifactPayloadStore` exists only as a package-private unit-test seam and is
never selected by production wiring.

**Readiness.** `ArtifactCacheCore.readiness` classifies the configuration without side effects:

| Readiness | Condition | Public error code |
|---|---|---|
| `READY` | the trimmed name resolves to an existing FileRepository Thing | — |
| `NOT_CONFIGURED` | the setting is blank | `ARTIFACT_CACHE_NOT_CONFIGURED` |
| `REPOSITORY_UNAVAILABLE` | the Thing is missing, is not a FileRepository, or resolution throws | `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE` |

Readiness never creates a cache, opens or writes a repository file, or remembers a failure. It is
evaluated again on every turn, so after correcting the setting an operator edits/saves the
AgentThing and retries.

**Admission.** `ArtifactCacheTurnAdmission` applies readiness at every public entry point before
any Provider/LLM call, Playbook, Stream/history write, task-state change, or cache work:

| Entry path | Behavior when not ready |
|---|---|
| `Chat` | the Service throws `[<code>] <message>` |
| `ChatAsync` | an `AgentResponseEvent` with `status=ERROR` and the matching error code |
| AlwaysOn (`ParlerStreamToRemoteThing`) | `session.ack`, then one terminal `error` carrying the code; no `done` follows |
| Post-HITL continuation (`ParlerApprovalContinuation`) | same terminal error for the original request id; no approved-tool execution and no resumed LLM call |

AgentThing initialization logs one `ARTIFACT_CACHE_READINESS` line (`INFO` when ready, `ERROR`
otherwise) but does not prevent the Thing from starting. Each rejected request logs one bounded
`ARTIFACT_CACHE_TURN_REJECTED` warning; logs and client messages contain no paths, payloads,
namespace keys, or stack traces.

**Runtime outage.** When a cache operation inside a tool call fails with the typed
`REPOSITORY_UNAVAILABLE` fault (found anywhere in a bounded cause chain by
`ArtifactCacheTurnFaults`), that tool receives a paired error result, sibling tool calls in the
same batch that were not executed receive a `status:"skipped"` row, and the turn ends with the
public `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE` terminal error without another LLM round. Other
cache faults are artifact-local and are returned to the model as a bounded tool error
(`status`, `code`, `message`).

**Lab service.** The AgentThing Service `ParlerArtifactCacheLabTextRoundTrip(text)` performs an
opaque create/write/close/publish/open round-trip on the configured repository and returns
`{"ok":true,"artifactId":…,"byteCount":…,"kind":"TEXT","payloadMatch":true}` or
`{"ok":false,"code":…,"message":…}`. Operators use it to prove the repository works before
production use; it is not a product wire API.

### A3. Internal contract

```java
interface ArtifactCache {
    ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
                          ArtifactIoLimits limits) throws ArtifactCacheException;
    ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context) throws ArtifactCacheException;
    ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context,
                        ArtifactIoLimits limits) throws ArtifactCacheException;
    void invalidate(ArtifactRef ref, ArtifactAccessContext context);
    void invalidateScope(ArtifactAccessContext context);
}
```

There is no path-returning method, startup loader, file-delete method, lease, or pin.

- `ArtifactRef` holds only the opaque Core-generated artifact id (a lowercase UUID). It is not an
  authorization token. The public `cacheId` returned to the model is the same UUID text; the
  mapping and namespace lookup are owned by the adapters (see `tool-cache-integration.md`).
- `ArtifactAccessContext` is created by Core from the current ThingWorx principal and a trusted
  opaque scope id (for tool results: a deterministic id derived from the conversation id). Model
  or App input can never supply a username or scope id. The index key is the injective namespace
  key of (principal, scope) plus the artifact id.
- `ArtifactIoLimits` bounds one create or open operation (§A3.1.5). Adapters map the invocation
  budget (`BudgetVector`) into these limits.
- `ArtifactCacheException` is unchecked and carries an `ArtifactCacheFaultCode` (§A8).

#### A3.1 Opaque storage kernel

##### A3.1.1 Responsibilities

The kernel owns exactly:

1. **Access, namespace, backend.** Core-created access contexts; the configured FileRepository as
   the sole production backend; Core-generated path layout and atomic exclusive create (§A7).
   Repository-relative paths and index internals never cross the cache API.
2. **Opaque streaming I/O.** Writers and readers exchange opaque byte chunks through fixed,
   small internal windows. The kernel never materializes a complete artifact to validate or
   return it and never decodes payload semantics.
3. **Current-JVM metadata and lifecycle.** Immutable `ArtifactRecord`; uniform miss; logical
   expiry; invalidation; scope invalidation; restart-empty; shutdown (§A5).
4. **Storage limits.** Byte, item, wall-time, and fixed-buffer limits with checked arithmetic.
5. **PASSWORD-before-create.** A typed schema proof is walked before any file is created (§A3.1.4).

It does not own payload codecs, decoded-object memory budgets, paging, or analysis; those belong
to the adapters and tools that consume it.

##### A3.1.2 Writer state machine

```text
OPEN → CLOSED_UNPUBLISHED | ABORTED | FAILED
CLOSED_UNPUBLISHED → PUBLISHED | ABORTED | FAILED
PUBLISHED, ABORTED, FAILED are terminal.
```

`ArtifactWriter` operations:

| Operation | Rule |
|---|---|
| `writeBytes(chunk)` | legal only in `OPEN`; empty chunk is a no-op; a chunk larger than the internal window is streamed in slices without a second full copy |
| `addProducerItemDelta(n)` | legal only in `OPEN`; `n ≥ 0`; checked add; rejected before it would exceed `maxItems` or overflow. The sum at close is the record's `itemCount` (0 if never called). The kernel never parses the payload to count items |
| `close()` | `OPEN` → `CLOSED_UNPUBLISHED` with `byteCount` = bytes written and `itemCount` = accumulated deltas; on failure → `FAILED` (stream released, then one best-effort delete) |
| `abort()` | cancel from `OPEN` or `CLOSED_UNPUBLISHED`: release the stream, set `ABORTED`, best-effort delete the unpublished file; no-op in any terminal state and never deletes after `PUBLISHED` |

Publication and abort share one private per-writer mutex (`publishAbortLock`). Under it,
`publish(writer)`:

- in `PUBLISHED` returns the stored `ArtifactRef` (idempotent; never deletes);
- in `OPEN`, `ABORTED`, or `FAILED` refuses with `INVALID_REQUEST` and returns no handle;
- in `CLOSED_UNPUBLISHED` inserts the record into the index while still holding the mutex; on
  success stores the ref and sets `PUBLISHED`; on an id collision sets `FAILED`, best-effort
  deletes the unpublished file, and fails with `CREATE_COLLISION`.

A concurrent `abort()` waiting on the mutex observes the final state; if `PUBLISHED` won, abort
does nothing. A failed writer is never retried; the caller creates a new writer. Publishing
requires the same cache instance and the same namespace as the writer. A writer dropped without
publish or abort leaves an undiscoverable file for administrator cleanup.

##### A3.1.3 Reader semantics

`ArtifactReader.record()` returns the immutable metadata view. It never exposes the
repository-relative path or the namespace key.

`readBytes(maxLen)` requires `maxLen > 0` and reads at most
`min(maxLen, remaining operation bytes, maxInternalBufferBytes, unread claimed bytes)`:

- the operation byte budget exhausted while claimed bytes remain unread → `IO_LIMIT_EXCEEDED`
  (never reported as end of file);
- the file ends before the claimed byte count → `PAYLOAD_FAULT` (truncated or missing payload);
- after all claimed bytes are read, a required one-byte terminal probe runs: any extra byte →
  `PAYLOAD_FAULT` (overlong or inconsistent payload); no byte → an empty array (natural end of
  file);
- wall time is rechecked around blocking reads.

`close()` before natural end of file only releases the stream. It does not drain the unread
suffix, does not run the probe, and makes no claim about the unread bytes.

##### A3.1.4 Create request and PASSWORD preflight

`ArtifactCreateRequest` carries a producer format label (`ArtifactKind`: `TABULAR`, `JSON`,
`TEXT` — metadata only; the kernel never branches on it), a **required** typed schema proof
(`ArtifactSchemaNode`), an optional structure/format hint, producer, lineage, `complete`,
`truncated`, `sampled`, and `logicalExpiryEpochMilli` (default: never expires).

Before exclusive create, `PasswordSchemaPreflight` walks the proof:

- `maxDepth = 32`, `maxNodes = 4096` (every visited node counts once);
- nesting level = the number of INFOTABLE-typed nodes on the path from the root, inclusive. A
  root INFOTABLE is level 1, a non-INFOTABLE root is level 0; a non-INFOTABLE child inherits its
  parent's level. A scalar under a level-32 INFOTABLE is accepted; an INFOTABLE at level 33 is
  rejected;
- any node with `BaseTypes.PASSWORD` → `PASSWORD_REJECTED`;
- untyped-bytes proof → `PASSWORD_REJECTED` (it cannot prove absence of PASSWORD);
- missing proof, depth over 32, or the 4,097th node → `INVALID_REQUEST`.

Every rejection happens before any file is created. The optional hint is not walked and is not a
PASSWORD proof. The kernel does not scan payload bytes for secrets.

##### A3.1.5 I/O limits

`ArtifactIoLimits.of(maxBytes, maxItems, maxWallTimeMillis, maxInternalBufferBytes)`; all values
must be positive and `maxInternalBufferBytes` must be in `1..MAX_U1A_INTERNAL_BUFFER_BYTES`
(`65536`). Writers reject bytes and items beyond the limits and recheck wall time; readers apply
the byte budget, window, and wall time as in §A3.1.3. No untrusted or persisted length drives an
allocation before it is checked against the remaining byte budget and the fixed window. The
limits bound storage work only, not decoded-object heap; decoder and projection memory are
bounded by the consuming adapter.

##### A3.1.6 Verified properties

The cache test suite (`FileArtifactCacheTest`, `FileArtifactCacheLifecycleTest`,
`ArtifactIoLimitsTest`, `ArtifactAccessContextTest`) covers:

- opaque multi-chunk round-trip under fixed windows; a caller chunk larger than the window
  without a whole-chunk copy;
- exact-length read confirmed by the probe; truncated file → `PAYLOAD_FAULT`; overlong file →
  `PAYLOAD_FAULT`; byte budget exhausted with unread bytes → `IO_LIMIT_EXCEEDED`; early close
  without drain or probe;
- byte, item, wall-time, and window bounds with checked allocation and checked-add overflow;
- close-before-index publication; abort from `OPEN` and from `CLOSED_UNPUBLISHED`; repeated
  abort; repeated publish returning the same ref; abort after publish (no delete); publish
  failure → `FAILED` and refused retry; forced publish-wins and abort-wins interleavings;
- path composition, caps, and case-fold non-collision; isolation between repositories and
  namespaces; `record()` exposing neither path nor namespace key;
- PASSWORD: missing/untyped proof and PASSWORD discovery with zero files created; depth 32/33 and
  node 4096/4097 boundaries;
- blank or unavailable repository failing closed; concurrent same-id exclusive create;
- TTL, invalidation, scope invalidation, restart-empty, shutdown, and the open/remove race (§A5).

### A4. Write, publish, and read

1. Validate the `ArtifactCreateRequest`, including the PASSWORD preflight, before creating a file.
2. Build the Core path (§A7) and exclusively create it. On a create collision, retry with a fresh
   id; after three attempts fail with `CREATE_COLLISION`. An existing file is never overwritten
   and the repository is never listed.
3. Stream opaque bytes and producer item deltas under the I/O limits.
4. `close()` the writer (or `abort()` it).
5. `publish()` inserts the record under the writer mutex and returns the `ArtifactRef` only after
   the writer is `PUBLISHED`.

`open()` resolves the record through the index (§A5), opens the stored path, and returns a reader
bound to the record and the operation limits.

The FileRepository adapter (`FileRepositoryArtifactPayloadStore`) creates the file with the
platform's binary-file create with overwrite disabled; an "already exists" failure maps to
`CREATE_COLLISION`, other failures to `REPOSITORY_UNAVAILABLE`. The platform creates parent
directories.

### A5. Lookup and lifecycle

Every `open` validates handle syntax, looks up the current namespace key plus artifact id, and
checks logical expiry. Absent, malformed, guessed, foreign-namespace, expired, invalidated,
scope-invalidated, and pre-restart handles all produce the same `CACHE_MISS`
("Artifact not found in current-JVM cache; re-fetch or recompute"). An expired record is evicted
on the lookup that finds it.

A handle that is still indexed but whose payload file was deleted by an administrator produces
`PAYLOAD_FAULT`, not `CACHE_MISS`.

| Event | Effect |
|---|---|
| `invalidate(ref)` | removes that index record |
| `invalidateScope(context)` | removes every record of that namespace; the AgentThing Services `ClearConversation` and `SetConversationHistoryCutoff` invalidate the conversation's scope (through `TabularArtifactHub.invalidateScopeForConversation`), which also drops that scope's runtime descriptors |
| logical expiry | checked on every access; kernel default is no expiry, and the current tool adapters do not set one, so tool caches live until scope invalidation or restart |
| restart | the index starts empty; retained files are not rediscovered |
| shutdown | rejects new operations (`INVALID_REQUEST`) and drops index metadata |

None of these deletes a payload file. An open racing with removal has two outcomes only: the
reader was admitted and finishes within its own limits, or removal won and the open misses.

`ArtifactCacheLiveness.isIndexedForConversation(conversationId, cacheId)` answers whether a
handle is usable right now (indexed, same namespace, unexpired, complete) without scanning the
repository, opening a reader, or resurrecting anything. It never throws; any fault answers
`false`. Checkpoint evidence uses it (see [`docs/core/advanced-compact.md`](../../core/advanced-compact.md)).

### A6. PASSWORD and the ThingWorx boundary

ThingWorx authorizes the containing Service call and owns authentication, FileRepository
permissions, physical placement, encryption, backup, and administrator access. The Extension
neither probes nor attests to those properties and adds no second authorization layer; the cache
namespace check is not a substitute for ThingWorx Service authorization.

The Extension's single cache-persistence rule is that a value whose ThingWorx BaseType is
`PASSWORD`, including nested fields, is rejected before any file is created (§A3.1.4).

### A7. Path layout and administrator retention

Relative path: `<encoded-username>/<UTC yyyy-MM-dd>/<artifact-id>.payload`

- `encoded-username` is the UTF-8 bytes of the current principal name in **lowercase
  hexadecimal** (`[0-9a-f]` only). Distinct usernames always give distinct segments, and the
  alphabet has no case pairs, so segments cannot collide on case-insensitive stores (for
  example `aaa` → `616161`, `aaG` → `616147`; a Base64url encoding would fold `YWFh`/`YWFH`
  together).
- `artifact-id` is a Core-generated lowercase UUID; the UTC date is the creation date.
- Caps, counted in ASCII bytes after encoding: encoded username ≤ **192** (≤ 96 raw UTF-8 bytes);
  `<artifact-id>.payload` segment ≤ **64**; full relative path ≤ **256**. Exceeding a cap fails
  create with `PATH_OVERFLOW`; identities are never truncated or folded.

The Extension implements no quota, sweeper, or retention job. Logical removal affects only the
index. Administrators delete complete date directories older than a site-selected period using
tools appropriate to the FileRepository backend; see
[`file-artifact-cache-retention.md`](../../operations/file-artifact-cache-retention.md).

### A8. Fault codes

`ArtifactCacheFaultCode` values are internal and appear to the model only inside bounded tool
errors:

| Code | Meaning |
|---|---|
| `CACHE_MISS` | uniform miss (§A5) |
| `PASSWORD_REJECTED` | PASSWORD or untyped proof (§A3.1.4) |
| `PATH_OVERFLOW` | path cap exceeded (§A7) |
| `IO_LIMIT_EXCEEDED` | byte, item, or wall-time limit |
| `CREATE_COLLISION` | exclusive create failed after three fresh ids, or index id collision |
| `REPOSITORY_UNAVAILABLE` | repository missing, wrong type, or failing; escalates to the turn terminal (§A2) |
| `PAYLOAD_FAULT` | missing, truncated, or overlong payload |
| `INVALID_REQUEST` | bad proof or bounds, wrong writer state, foreign writer, or shut-down cache |
| `INTERNAL` | unexpected internal state |

### A9. Consumer boundary

Adapters and tools use the cache only through `ArtifactCache`, the opaque reader/writer, and
`ArtifactRecord`. They never read repository paths or the index, construct namespaces, recover
handles across a restart, bypass the PASSWORD preflight, or keep a private cache. Tests install
an in-memory cache and principal explicitly through test-only fixtures
(`TabularArtifactHub.setTestArtifactCache`, `ArtifactCacheTestFixtures`);
`ArtifactCacheProductionWiringGuardTest` prevents production code from constructing a fallback
cache.

## Part B — Tool correctness rules

### B1. Overview

| Id | Rule | Authority |
|---|---|---|
| E1 | `query_entities_by_taxonomy` reports bounded-result completeness honestly on both intersect and non-intersect paths | `BoundedQueryCompleteness` (§B7.1) |
| E2 | every built-in, executor-only, and dynamic tool name is reserved through one set before App tools are admitted | `ReservedBuiltinToolNames` (§B7.2) |
| E3 | a `set_property_value` request is resolved and strictly typed before any HITL approval exists | `SetPropertyValueExecutor.gateSetPropertyValueForHitl` (§B7.3) |
| E10 | property-history row-limit arguments resolve through one precedence rule | `HistoryRowLimitPrecedence` (§B3.4) |
| S7 | specific-alert acknowledgment probes at most 501 rows and never writes more than 500 | `AlertSpecificAckPolicy` (§B7.4) |

### B2. Fixed boundaries

- `QIT_MAX_ITEMS = 5000` for taxonomy listings and the history read cap of 5,000 rows are
  unchanged by these rules.
- `totalCount` on taxonomy results keeps its meaning: returned match rows.
- These rules do not weaken ThingWorx security context, RBAC, risk classification, or HITL
  requirements, and they do not widen which property types may be written.

### B3. Rules by tool

#### B3.1 Taxonomy query completeness (E1)

`QueryEntitiesByTaxonomyExecutor` reads one implementor listing (at most 5,000 rows) and then
applies `LookupProperties` and any hierarchy intersect. Candidate-scan completeness, listed-page
match completeness, and overall intersect completeness are separate states; a malformed listing
is an error rather than an empty success. The full rule is §B7.1; the normative wire text is
`CONTRACTS/API_CONTRACT.md` (`query_entities_by_taxonomy` completeness).

#### B3.2 Reserved tool names (E2)

`AgentThing.builtinToolDefinitionNames()` delegates to `ReservedBuiltinToolNames.fromRegistry`,
and runtime manifest loading, configuration-repository authoring validation
(`ConfigurationRepositoryAuthoringJson`), and Playbook document validation
(`PlaybookDocumentValidation`) all consume it. Details in §B7.2.

#### B3.3 Typed property writes before HITL (E3)

`set_property_value` resolves the Thing and property, rejects protected, read-only, and
mismatched types, and canonicalizes the requested value strictly before the approval card or
pending record is created. The approved write uses that canonical value. Details in §B7.3.

#### B3.4 Property-history row-limit precedence (E10)

`query_property_history` and its executor-only aliases `query_numeric_property_history` and
`query_value_stream_property_history` all reach the numeric branch
(`PropertyToolsExecutor.doQueryNumericPropertyHistory`) or the value-stream branch
(`doQueryValueStreamPropertyHistoryCompact`). Both branches resolve the row limit through
`HistoryRowLimitPrecedence.resolve(root, 5000)`:

| Arguments present | Limit source |
|---|---|
| `maxItems` (published) | `maxItems`, regardless of aliases |
| no `maxItems`, `maxRows` present | `maxRows` (wins over `maxPoints`) |
| only `maxPoints` | `maxPoints` |
| none | default `1000` |

The selected value is clamped to `[1, 5000]`. Success extras echo `maxItemsRequested` (selected
value before clamping), `maxItemsEffective` (after clamping), `maxItemsSource` (`maxItems`,
`maxRows`, `maxPoints`, or `default`), and the retained `maxRowsRequested` (equal to the effective
limit). The helper is pure and is the only place the precedence is decided.

#### B3.5 Specific-alert acknowledgment bound (S7)

For `acknowledge_alerts` with `mode: specific_alerts` (the default), `AlertToolsExecutor` first probes unacknowledged
summary rows with `maxItems = AlertSpecificAckPolicy.PROBE_MAX_ITEMS` (501) through
`AlertSummaryAckProbe`, classifies the count, and only then writes. Details in §B7.4.

#### B3.6 Related surfaces

- Custom tools come only from configuration-repository extended-tool manifests
  (`CustomToolHarvester.toToolDefinitionForExtendedTool`). Service-name prefix discovery
  (`_tool_*`) is not supported.
- `build_history_overlay_chart` treats two series windows as equal when both ends differ by at
  most `HistoryOverlayChartBuilder.WINDOW_EQUALITY_TOLERANCE_SECONDS` (1 second). Unit
  compatibility uses `PeriodOverPeriodPopSupport.validateUnitCompatibility`, and period
  resolution uses `PeriodOverPeriodPeriodResolver`.

### B4. App Developer boundary

Parler Core owns completeness meanings and their public fields, the reserved-name authority,
property metadata resolution, strict parsing, canonicalization, authorization, stale checking,
HITL, write execution, and the 500/501 acknowledgment rule.

An App Developer may supply Things, DataShapes, taxonomy structures, and Services; define App
tool names that pass the collision and schema checks; configure property permissions and risk
through supported ThingWorx and Parler policy; and add site evaluation cases.

An App Developer cannot redefine `truncated` or completeness semantics, shadow a built-in or
dynamic tool name, bypass canonicalization for core property writes, show a different value on
the approval card than the value Core writes, or raise the acknowledgment limits.

### B5. Error codes

| Tool | Code | When |
|---|---|---|
| `query_entities_by_taxonomy` | `TAXONOMY_LISTING_MALFORMED` | the implementor listing is missing or unrecognized |
| `set_property_value` | `MISSING_PROPERTY_NAME`, `MISSING_VALUE` | required argument absent (a JSON `null` value counts as absent) |
| `set_property_value` | `PROPERTY_NOT_FOUND` | the property does not exist on the resolved Thing |
| `set_property_value` | `BASETYPE_HINT_MISMATCH` | caller `base_type`/`baseType` hint disagrees with the actual type |
| `set_property_value` | `INVALID_BOOLEAN_VALUE`, `INVALID_NUMBER_VALUE`, `INVALID_INTEGER_VALUE`, `INVALID_LONG_VALUE`, `INVALID_DATETIME_VALUE`, `INVALID_VALUE` | the requested value fails strict parsing |
| `set_property_value` | `UNSUPPORTED_BASE_TYPE` | a type other than the supported set (§B7.3) |
| `set_property_value` | protected-value write block (`ProtectedValuePolicy`) | PASSWORD or otherwise protected property |
| `set_property_value` | `STALE_TARGET_VALUE` | the observed value changed between request and approval; nothing is written |
| `acknowledge_alerts` | `ACK_MATCHES_EXCEED_LIMIT` | more than 500 unacknowledged matches |

### B6. Verification

Focused tests guard each authority: `BoundedQueryCompleteness` and taxonomy executor matrices
(intersect/non-intersect, empty, below/at/above 5,000, missing/malformed/inconsistent totals,
evaluation loss, filtered/unfiltered); `ReservedBuiltinToolNamesTest` parity across every
definition source and every validator, including the absent-Playbook case and case sensitivity;
`SetPropertyValue` canonicalization matrices and requested-track identity; both history branches
and all three entry names for the precedence rule; `AlertSpecificAckPolicyTest` and
`AlertSpecificAckExecutorGateTest` for 0, 1–500, 501, and provider failure.

### B7. Invariants

#### B7.1 E1 completeness invariants

1. `QIT_MAX_ITEMS = 5000` is unchanged. No second match-count scan past 5,000 and no offset total
   probe are performed for taxonomy queries.
2. Output row count (`totalCount`), query-side candidate-scan completeness, listed-page match
   completeness, and overall intersect completeness are distinct concepts.
3. `totalCount` is the number of returned match rows after `LookupProperties` and any intersect.
4. Intersection-only fields (`preIntersectMatchCount`, `intersectedRowCount`, `queryHasMore`,
   `expandHasMore`) keep their intersection-only meaning.
5. A non-intersect bounded result exposes completeness through `truncated`,
   `totalUnderlyingCount`, and `hasMore` as below.
6. `truncated: true` requires positive evidence that **matching** rows were omitted. Unknown
   completeness is never promoted to `truncated: true`.
7. Receiving exactly 5,000 listing rows does not by itself prove truncation. A malformed or
   missing listing is an error, not an empty page.
8. Completeness is never inferred from serialized size or cache state.

**Listing validity.** `QueryEntitiesExecutor.parseImplementingThingsOutput` classifies the
platform result as `VALID_TABLE` (a recognized optimized-total shape whose nested implementor
table is present, possibly with zero rows — a genuine empty page) or `MISSING_OR_UNRECOGNIZED`
(null/empty outer result, null wrapper row, or unrecognized wrapper). `MISSING_OR_UNRECOGNIZED`
returns `status:"error"`, `code:"TAXONOMY_LISTING_MALFORMED"`.

**Platform total carrier.** The listing's total is carried as presence (`ABSENT` | `PRESENT`),
parse status (`OK` | `MALFORMED`, only when present), and the parsed value (only when `OK`). A
present total is `OK` only when it is finite, integral (`10.0` is OK), non-negative, and at most
`Long.MAX_VALUE`, checked exactly with `BigDecimal`/`BigInteger`; fractions, NaN, infinities,
negatives, and larger magnitudes are `MALFORMED`. For a valid listing with `listingRows` rows:

- **usable** — `PRESENT` + `OK` + `parsedTotal ≥ listingRows`;
- **missing** — `ABSENT`;
- **inconsistent** — `PRESENT` + `MALFORMED`, or `PRESENT` + `OK` with `parsedTotal < listingRows`.

**Evaluation loss.** `evaluationLossCount` counts listing rows that could not be evaluated to a
definite match or non-match: a row without a usable name, a failed Thing resolution, or every row
when the listing has no usable name column. Evaluation loss makes listed-page completeness
unknown.

**Query-side candidate scan** (before `LookupProperties` and intersect):

| Platform total | Listing rows | Candidate scan |
|---|---|---|
| usable, `parsedTotal == listingRows` | any (including 0 and 5,000) | complete |
| usable, `parsedTotal > listingRows` | any | truncated |
| missing | `< 5000` (including 0) | complete (last-page rule) |
| missing | `== 5000` | unknown |
| inconsistent | any | unknown |

**Listed-page match completeness and non-intersect emission.** "Filtered" means a non-empty
`LookupProperties` object was applied.

| Case | Listed-page completeness | Non-intersect fields |
|---|---|---|
| unfiltered, candidate complete, no evaluation loss | complete | omit `truncated`, `totalUnderlyingCount`, `hasMore` |
| unfiltered, candidate truncated, no evaluation loss | truncated | `truncated: true`, `totalUnderlyingCount: <parsedTotal>`, `hasMore: true` |
| unfiltered, candidate unknown, no evaluation loss | unknown | `hasMore: true` only |
| filtered, candidate complete, no evaluation loss | complete | omit all three |
| filtered, candidate truncated or unknown | unknown | `hasMore: true` only |
| any evaluation loss, or inconsistent total | unknown | `hasMore: true` only |

When `hasMore` is true and `truncated` is absent, match completeness is unknown. The pair
`truncated` / `totalUnderlyingCount` reuses the field names of
[`CONTRACTS/TAXONOMY_RESOLVER.md`](../../../CONTRACTS/TAXONOMY_RESOLVER.md) §4, but here it
describes listed-page match omission, not implementor-scope truncation for identifier resolution.

**Overall intersect completeness.** When a hierarchy intersect is active, listed-page
`truncated` is not treated as final (omitted candidates may not survive the intersect). Overall
intersect completeness is complete only when listed-page completeness is complete and
`expandHasMore` is false; otherwise it is unknown and never truncated. The intersect path never
emits `truncated` / `totalUnderlyingCount`, and
`EntityHierarchyIntersectHelper.writeIntersectSuccessFields` writes:

```text
hasMore = queryHasMore || expandHasMore || completenessUnknown
```

where `queryHasMore` is true when the candidate scan is not complete and `completenessUnknown` is
true when overall intersect completeness is unknown. A complete candidate page with evaluation
loss therefore still yields `hasMore: true`.

`BoundedQueryCompleteness.evaluate(listingValidity, listingRows, platformTotal, queryLimit,
filtered, evaluationLossCount, intersectActive, expandHasMore)` returns the three named states
(`querySide`, `listedPageMatch`, `overallIntersect`) and the emission flags;
`writeNonIntersectFields` writes the non-intersect vocabulary. The helper is pure and does not
depend on the artifact cache.

#### B7.2 E2 reserved-name invariants

1. One production authority returns every reserved tool name for admission:
   `ReservedBuiltinToolNames.fromRegistry(registry)`.
2. The set contains all registry definitions, all executor-only names and aliases (including the
   `load_tool_schemas` meta tool and replay aliases such as `query_numeric_property_history`),
   and `alwaysReservedDynamicNames()` (`start_playbook`).
3. A dynamic name is reserved even when its optional provider is disabled or absent during
   validation; `start_playbook` is reserved although the registry does not define it and the LLM
   only sees it when Playbook capability is present.
4. Runtime manifest loading, authoring validation, and Playbook document validation consume the
   same set.
5. Comparison is exact, case-sensitive string equality on the registered name, with no case
   folding, locale lowercasing, or whitespace normalization. `Foo` and `foo` are distinct.
6. A collision fails before tool registration with a stable diagnostic that reveals no secrets.
7. App tools cannot override or shadow a reserved name; there is no App-side way to remove a core
   reservation.
8. A new dynamic name that is not added to the authority fails the parity test.

#### B7.3 E3 typed HITL invariants

1. The Thing (canonical name) and property metadata are resolved before a pending approval
   exists.
2. The property's actual `BaseType` is authoritative. An optional caller hint must match or the
   call fails with `BASETYPE_HINT_MISMATCH`. Protected (PASSWORD) and read-only properties are
   rejected at this step.
3. The value is parsed once with strict type-specific rules; invalid input fails before a card or
   pending record exists.
4. **BOOLEAN:** a JSON boolean, or exactly the trimmed text `true` or `false`. Everything else —
   `True`, `FALSE`, `1`, `0`, `yes`, empty string — fails with `INVALID_BOOLEAN_VALUE`; nothing
   falls through to `false`.
5. Other types: NUMBER must be finite; INTEGER and LONG must be valid integers in range; DATETIME
   must parse; STRING, HTML, TEXT, HYPERLINK, GUID, IMAGELINK, JSON, XML, and TAGS are kept as
   text (non-text JSON is serialized). Any other type fails with `UNSUPPORTED_BASE_TYPE`.
6. The gated `ToolCall` is rewritten with the canonical Thing name, property name, base type
   (both snake_case and camelCase keys), and the JSON-typed canonical value.
7. **Two tracks, never conflated.**
   - *Requested-value track:* the approval card "New value" line, the pending/gated `ToolCall`,
     and the approved write all use the one canonical requested value. Approved execution does
     not reparse the original request.
   - *Observation track:* `snapshotPropertyValueForStaleCheck` reads the live value as canonical
     JSON at request time (shown on the card as "Observed value (server, at request)") and again
     at approval time. If the two observations differ the write is not performed and the result
     is `STALE_TARGET_VALUE`. Observations are never compared with the requested value.
8. `ParlerHitlAuditLog` stays metadata-only (identifiers, decision, outcome — no property value).
9. Secrets and PASSWORD values remain governed by the existing redaction and denial policy.

#### B7.4 S7 acknowledgment invariants

1. The probe reads at most 501 candidate rows (`PROBE_MAX_ITEMS = MAX_BATCH_ROWS + 1`) with
   `onlyUnacknowledged = true`.
2. More than 500 candidates returns `ACK_MATCHES_EXCEED_LIMIT` before any acknowledgment write.
3. Zero candidates returns a success result with `acknowledgedCount: 0` and no write.
4. Otherwise at most 500 rows are acknowledged, derived from the bounded, validated probe:
   through `AcknowledgeAlertFromSummary`, or through a narrow `AcknowledgeAlert` when exactly one
   unacknowledged row matches `propertyName` and no `alertName` was given.
5. These invariants apply to `mode: specific_alerts` (the default). The explicit `property_all`
   mode is a separate bulk path on one property.

#### B7.5 Cross-cutting invariants

- Each rule has one authority; call sites delegate rather than reimplementing it.
- Each rule applies to every input and entry route of its tool, not to particular prompts.
