# Rate Control

Status: implemented, for the `LLMAPIProviderThing` architecture.
Document type: specification for provider-level local admission control.

## 1. Purpose

`parler-agent` has provider telemetry:

- `LLM_HTTP_FAILURE` and `LLM_RATE_LIMIT` identify upstream 429s and safe rate-limit headers.
- `LLM_USAGE` records provider/model identity, token usage, cache usage, request ids, and replay compaction metrics.
- `AgentMessageStream.llmUsageJson` persists per-call usage so the eval harness can compare models.

Telemetry alone does not stop a heavy turn from calling the upstream provider until it returns 429, which makes the
failure happen at the most expensive point: after request assembly, HTTP dispatch, and provider rejection. **Local
admission control** fixes that:

- one local rate gate per **LLM API Provider Thing**
- configurable token-per-minute / request-per-minute / concurrency limits
- fail-fast or bounded-wait admission before the upstream HTTP call
- success accounting from actual provider usage
- 429 feedback from safe provider headers
- diagnostics that prove whether a rejection was local or upstream

Typical configured limits: 30000 TPM for an Anthropic Sonnet Provider (matching a 30K input-TPM account cap) and
50000 TPM for Azure OpenAI GPT Providers. With those limits enforced, heavy turns wait or fail locally instead of
producing avoidable upstream 429s.

Vendor 429 behavior that shaped the design:

- Azure/OpenAI 429 headers expose `x-ratelimit-limit-tokens` and a negative `x-ratelimit-remaining-tokens` under token
  pressure. The response body says a generic `"Too Many Requests"` / `"too_many_requests"` even when the pressure is
  token-based (`x-ratelimit-remaining-requests` still positive).
- Anthropic 429 headers expose `anthropic-ratelimit-input-tokens-limit`, `anthropic-ratelimit-input-tokens-remaining`,
  and `retry-after`.
- With bounded wait enabled, multi-tool smoke turns complete through local waits (waits of roughly 20 s on GPT
  Providers and up to about 55 s on a 30K-TPM Sonnet Provider) with no upstream 429s. `estimateSafetyMultiplier=1.0`
  is enough for Sonnet under a 30K ITPM cap; a cache-aware Anthropic estimator is not used.

Related log hygiene: replay compaction passes non-JSON tool results (such as Markdown skill bodies) through without
logging `LLM_COMPACT_MALFORMED`.

## 2. Scope

### In scope

- A single-row rate-control configuration table on `LLMAPIProviderThing`.
- An in-memory `LLMAPIProviderRateGate` owned by each Provider Thing instance.
- All upstream LLM calls route through that gate from the Provider-owned client path.
- Token reservations are estimated before a call.
- The reservation is reconciled after success using `LlmResponse` usage.
- The gate is marked temporarily exhausted after an upstream 429 using structured safe headers.
- Provider services for inspection and reset.
- Local logs let eval and operators tell:
  - admitted locally
  - rejected locally
  - waited locally
  - rejected upstream
- Replay-compaction preflight: non-JSON tool results are not logged as malformed JSON.

### Not supported

- Per-user quotas.
- Tenant quotas.
- Monthly or daily cost budgets.
- Conversation-level caps.
- Scheduler-based counter resets.
- Cross-node coordination in clustered ThingWorx.
- Retry loops after 429.
- Background queues.
- New `task.state` fields (the live wait indicator is the separate `rate_control.status` event in `rate-control-ui-status.md`).
- Friendly user-facing 429 masking.
- Per-tool rate limits.
- Shared gates across Provider Things (for example several Providers on one Azure resource or API key).

The gate protects the provider bucket only.

## 3. Ownership

Rate control belongs to the **Provider Thing**, not the AgentThing.

An AgentThing references one Provider through `AgentSettings.llmApiProviderRef`. Multiple AgentThings can share one Provider. The upstream vendor sees the API key and model/deployment behind that Provider, not the AgentThing names or conversation ids. Therefore every token-spending decision for that API key/model boundary must pass through one Provider-owned gate.

The old idea of `AgentThingRateGate` is obsolete. The current design uses:

```text
AgentThing
  -> LLMAPIProviderResolver.resolve(llmApiProviderRef)
  -> LLMAPIProviderThing.getLlmClient()
  -> ProviderLlmClientBridge.chat(request)
  -> LLMAPIProviderRateGate.acquire(...)
  -> delegate LlmClient.chat(...)
  -> LLMAPIProviderRateGate.accountSuccess(...) or accountFailure(...)
```

The implementation should keep the gate in the Provider layer. AgentThing should not duplicate provider limits.

## 4. Configuration

Add one single-row configuration table to the abstract `LLMAPIProviderThing` via
`@ThingworxConfigurationTableDefinitions`, inherited by every concrete Provider Template:

```text
LLMAPIProviderRateControl
```

The table is `isMultiRow=false`. Rate-control fields (`tokensPerMinuteLimit`, `requestsPerMinuteLimit`, concurrency)
are vendor-neutral and therefore belong on the abstract `LLMAPIProviderThing`. Native connection/request settings remain
per-template tables as specified in `docs/agent/llm-api-provider-parameters.md`.

Fields:

| Field | Base type | Default | Meaning |
| --- | --- | ---: | --- |
| `rateControlMode` | STRING | `disabled` | `disabled`, `observe`, or `enforce`. |
| `tokensPerMinuteLimit` | INTEGER | `0` | Provider token bucket capacity. `0` disables token checks. |
| `requestsPerMinuteLimit` | INTEGER | `0` | Provider request bucket capacity. `0` disables RPM checks. |
| `maxConcurrentRequests` | INTEGER | `0` | Max in-flight upstream calls for this Provider. `0` disables concurrency checks. |
| `maxLocalWaitMs` | INTEGER | `0` | If capacity is not currently available, wait up to this many ms. `0` means fail fast. |
| `tokenReserveStrategy` | STRING | blank | Blank means provider default. Supported values: `input_only`, `input_plus_requested_output`. |
| `estimateSafetyMultiplier` | NUMBER | `1.15` | Multiplier applied to estimated tokens before admission. Minimum effective value is `1.0`. |
| `maxSingleRequestTokens` | INTEGER | `0` | Optional hard cap for one reservation. `0` means `tokensPerMinuteLimit` when TPM is enabled; otherwise no single-request token cap. |
| `logAdmissions` | BOOLEAN | `false` | When true, log every allow/wait decision. Rejections are always logged. |

Operational starting points:

| Provider family | Suggested `estimateSafetyMultiplier` | Notes |
| --- | ---: | --- |
| OpenAI / Azure OpenAI Chat v4/v5 | `1.10`-`1.15` | Conservative default for GPT deployments with 50K+ TPM headroom. |
| Anthropic Sonnet 4.6 | `1.0` | Better fit for a 30K ITPM cap with cache-heavy Parler prompts. `1.15` caused `single_request_too_large` before dispatch. |

`maxSingleRequestTokens=0` is intentionally simple, but it is a sharp edge. When TPM is enabled, it allows one request to
reserve the whole minute's token budget for this Provider. That can be acceptable for dedicated eval Providers, but on a
shared Provider it can block small follow-up calls until refill catches up. For shared development Providers, start with
an explicit value such as `floor(tokensPerMinuteLimit * 0.6)` and tune from `LLM_RATE_REJECTION` / `LLM_USAGE`
telemetry. There is no implicit percentage default; operators see the cap they chose.

Mode semantics:

| Mode | Behavior |
| --- | --- |
| `disabled` | No local rate decision. Current upstream telemetry remains unchanged. |
| `observe` | Compute estimates and log summary metrics, but never block. Useful for baseline collection. |
| `enforce` | Acquire local capacity before the upstream call. If capacity cannot be acquired within `maxLocalWaitMs`, fail locally. |

Recommended provider defaults:

| Provider template | `defaultTokenReserveStrategy()` |
| --- | --- |
| `AnthropicMessagesProvider` | `input_only` |
| OpenAI Chat / Azure OpenAI Chat v4/v5 | `input_plus_requested_output` |

Blank `tokenReserveStrategy` means "use the Provider Template default." Implement this as a non-blank hook on
`LLMAPIProviderThing`:

```java
protected String defaultTokenReserveStrategy()
```

The base implementation may return `input_plus_requested_output`; `AnthropicMessagesProvider` overrides it to
`input_only`. This is parallel in spirit to the existing Provider hooks for wire-shape defaults such as
chat-completions max-token behavior. The gate must not hardcode strategy by string-matching Provider Thing names.

The Azure/OpenAI default is deliberately conservative because those APIs commonly reserve quota using prompt size plus
requested maximum output tokens. If telemetry shows this is too conservative for a deployment, an operator can set
`tokenReserveStrategy=input_only`.

Local rejection reasons are a closed enum, not free strings:

```text
LlmRateLimitAdmissionReason =
  tokens_per_minute
  requests_per_minute
  concurrency
  upstream_blocked
  single_request_too_large
```

The same enum values appear in `LLM_RATE_REJECTION`, `TestRateControlAdmission`, and
`LlmRateLimitAdmissionException`.

## 5. In-memory State

Each Provider Thing owns one gate instance. The gate state is not persisted.

State:

- token bucket:
  - capacity = `tokensPerMinuteLimit`
  - refill rate = `tokensPerMinuteLimit / 60000.0` tokens per ms
  - may go slightly negative after success reconciliation if the estimate was too low
  - negative debt is clamped at `-tokensPerMinuteLimit` when TPM is enabled; the bucket cannot owe more than one minute
    of quota
  - reservation/debit/refund arithmetic uses `long`; bucket levels may use `double` for refill math, but must never
    wrap through `int`
- request bucket:
  - capacity = `requestsPerMinuteLimit`
  - refill rate = `requestsPerMinuteLimit / 60000.0` requests per ms
- concurrency counter:
  - current in-flight calls
  - max = `maxConcurrentRequests`
- upstream block:
  - `blockedUntilEpochMs`
  - set from `Retry-After` or provider reset headers after upstream 429
- recent metrics:
  - local admissions
  - local waits
  - local rejections
  - upstream 429s
  - max wait
  - last rejection reason
  - last safe header snapshot

Threading:

- The gate must be thread-safe.
- The gate uses a simple per-Provider monitor (`synchronized`).
- Do not hold the lock while performing the upstream HTTP call.
- Concurrency slots must be released in `finally`.
- Use `System.nanoTime()` for refill delta calculations. Use `System.currentTimeMillis()` only for
  `blockedUntilEpochMs` and operator-facing timestamps.

Restart behavior:

- On Provider restart, buckets start full and metrics reset.
- On Provider edit/save (`initializeThing` rebuild), rebuild the gate alongside the Provider's bound client. This means save is a soft reset of in-memory rate state.
- Provider edit/save during active upstream calls may temporarily over-admit until those old in-flight calls complete,
  because the old gate releases old `inFlight` counters while the new gate starts fresh. Avoid Provider rate-setting
  edits during heavy eval or production traffic.
- State is not exact across restart, save, or cluster nodes.

## 6. Admission Algorithm

For every `ProviderLlmClientBridge.chat(request)` call:

1. Resolve Provider request options exactly as the bridge already does:
   - `resolvedMax = owner.resolveMaxOutputTokens(request)`
   - `resolvedReasoning = owner.resolveReasoningEffort(request)`
   - `augmented = LlmChatRequest.copyWithProviderAugmentation(...)`
   - model/deployment identity from `owner.getEffectiveModelLabel()` / Provider usage wire ids
2. If `augmented.isProbeMode()` is true, bypass local rate admission and call the delegate with `augmented`. This keeps
   Provider `TestConnection` usable even after a heavy eval run; the probe still consumes upstream quota and still logs
   upstream HTTP diagnostics if the provider rejects it.
3. Resolve current `LLMAPIProviderRateControl`.
4. Build `RateEstimate` from the **augmented** request, not the raw inbound request:
   - estimated input tokens
   - resolved requested max output tokens
   - reserved tokens
   - requested request count = 1
5. If mode is `disabled`, call upstream immediately.
6. If mode is `observe`, log estimate if enabled and call upstream immediately.
7. If mode is `enforce`:
   - if an upstream 429 block is still active and the remaining block time exceeds `maxLocalWaitMs`, reject
     immediately with `upstream_blocked`; otherwise wait for the block to expire within the local budget, then continue
   - reject immediately if a single request reservation exceeds `maxSingleRequestTokens`
   - atomically check and acquire concurrency, request bucket, and token bucket capacity under one gate lock
   - wait and re-check only up to `maxLocalWaitMs`
   - if still unavailable, throw `LlmRateLimitAdmissionException`
8. Call the delegate `LlmClient.chat(augmented)`.
9. On success, reconcile actual usage and release concurrency.
10. On failure, release concurrency. If the exception carries structured 429 headers, update `blockedUntilEpochMs` and
    mark token/request buckets exhausted until reset. Otherwise refund the pre-call token and request reservations.

Local rejection is not a retry. It is a deliberate pre-flight refusal.

Acquisition must be all-or-nothing. Do not decrement one dimension and then discover that a later dimension fails unless
the earlier decrement is rolled back before returning. The implementation uses one `synchronized` gate method
that checks all active dimensions, decrements the buckets / increments in-flight only when all checks pass, then releases
the lock before the upstream HTTP call. The concurrency slot is released in `finally`.

Implementation shape:

```java
RateReservation reservation;
synchronized (this) {
    if (!hasTokenCapacity(reservedTokens)) {
        throw rejection(tokens_per_minute);
    }
    if (!hasRequestCapacity(1)) {
        throw rejection(requests_per_minute);
    }
    if (concurrencyCap > 0 && inFlight >= concurrencyCap) {
        throw rejection(concurrency);
    }
    debitTokenBucket(reservedTokens);
    debitRequestBucket(1);
    inFlight++;
    reservation = new RateReservation(reservedTokens, 1);
}
try {
    LlmResponse response = delegate.chat(augmented);
    synchronized (this) {
        reconcileAfterSuccessAndRelease(reservation, response); // includes inFlight-- and notifyAll()
    }
    return response;
} catch (LlmHttpFailureException e) {
    synchronized (this) {
        accountHttpFailureAndRelease(reservation, e); // 429 blocks; non-429 refunds; includes notifyAll()
    }
    throw e;
} catch (Exception e) {
    synchronized (this) {
        refundReservationAndRelease(reservation); // includes inFlight-- and notifyAll()
    }
    throw e;
}
```

The upstream HTTP call is outside the lock. Success reconciliation, non-429 refund, upstream-429 feedback, and
concurrency release happen inside short synchronized blocks after the call returns or fails. Gate accounting helpers must
be defensive and must not leak `inFlight` if an internal accounting error occurs; log the accounting error and still
release the concurrency slot.

After a successful acquire, rollback/reconcile rules are:

| Outcome | Concurrency | Token bucket | Request bucket |
| --- | --- | --- | --- |
| HTTP success | release in `finally` after reconcile | reconcile actual debit vs reserved (§8) | keep the one debited request |
| HTTP failure, non-429 | release in `finally` | refund full `reservedTokens` | refund one request |
| Upstream 429 with structured headers | release in `finally` | empty both buckets and set block (§9) | empty both buckets and set block (§9) |

For `maxLocalWaitMs > 0`, waiting threads re-enter the same synchronized check after bounded `wait(deadlineMs)`. If
refill capacity is available for only some waiters, only those waiters acquire; the rest continue waiting until their
deadline or local rejection. Every reservation refund, concurrency release, gate reset, or upstream-block expiry update
that changes capacity must call `notifyAll()` inside the same synchronized block. Spurious wakeups are harmless because
capacity is always rechecked before acquisition. No background queue is introduced.

## 7. Token Estimation

The gate needs a pre-call estimate. Exact tokenizers are useful but not required.

Provider subclasses should expose:

```java
RateEstimate estimateRateUsage(LlmChatRequest request, RateControlConfig config)
```

`RateControlConfig` is already normalized before estimation: blank `tokenReserveStrategy` has been resolved through the
Provider Template's `defaultTokenReserveStrategy()`. Provider-specific estimators may improve token counting, but they do
not replace the normalized strategy decision unless this document is explicitly revised.

Built-in Provider Templates do not need a custom tokenizer. A shared default estimator on the Provider gate or a
protected `LLMAPIProviderThing` helper is used; per-template overrides are optional.

The estimator is built from the post-resolution `augmented` `LlmChatRequest` plus the owning `LLMAPIProviderThing`.
In the Provider-owned runtime path, `AgentLoop` normally passes `modelOverride=null`; the active model/deployment label is
`LLMAPIProviderThing.getEffectiveModelLabel()`, the same string that fills `LlmUsageWireIds.model` in telemetry. Do not
rely on `request.getModelOverride()` for Provider identity.

Estimation order:

1. Use provider-specific tokenizer when available.
2. Otherwise use a conservative character-based estimate over:
   - all message roles and content
   - tool names, descriptions, JSON schema text
   - model/deployment overhead constant
   - fallback ratio: `ceil(charCount / 3.5)`
3. Apply `estimateSafetyMultiplier`.
4. Apply `tokenReserveStrategy`:
   - `input_only`: reserved = estimated input
   - `input_plus_requested_output`: reserved = estimated input + resolved `requestedMaxOutputTokens`

The estimate does not try to reproduce provider internals. The gate only needs to prevent obvious overload and learn from actual usage.

Known limitation: reasoning-capable OpenAI/Azure models can consume internal completion/reasoning tokens beyond the
requested visible output. With `input_plus_requested_output`, the gate may still admit one oversize reasoning turn, then
success reconciliation drives the bucket negative and blocks subsequent turns until refill catches up. There is no
reasoning-output multiplier.

## 8. Success Reconciliation

After a successful response:

```text
actualInput = response.promptTokens
actualOutput = response.completionTokens
```

The actual token debit uses the same strategy as the reservation:

| Strategy | Actual debit |
| --- | --- |
| `input_only` | `actualInput` |
| `input_plus_requested_output` | `actualInput + actualOutput` |

If actual debit is lower than the reserved amount, refund the difference to the local token bucket. If actual debit is
higher, debit the difference; the bucket may go negative, clamped at `-tokensPerMinuteLimit` when TPM is enabled. A
negative bucket blocks subsequent calls until refill catches up.

Cache fields are not discounted. Use `promptTokens`, not raw provider `inputTokens`, because `promptTokens` is already the legacy aggregate that includes Anthropic cache read/create fields. This is conservative and aligns with the current `LLM_USAGE` total.

## 9. Upstream 429 Feedback

Local rate control should reduce upstream 429s, not hide them.

When an upstream HTTP failure occurs, clients should throw a structured exception, for example:

```java
final class LlmHttpFailureException extends RuntimeException {
    int status;
    boolean rateLimited;
    Map<String, String> safeHeaders;
    String bodyPreview;
}
```

`LlmHttpDiagnostics` should keep the current user-facing message text and logs, but the exception must carry the safe fields so `ProviderLlmClientBridge` can call:

```java
rateGate.accountHttpFailure(exception)
```

`bodyPreview` must use the same bounded preview length as `LlmHttpDiagnostics`; do not carry full response bodies through
the exception.

For 429:

- parse `Retry-After` first when present
- else parse provider reset headers:
  - Anthropic: `anthropic-ratelimit-input-tokens-reset`, `anthropic-ratelimit-requests-reset`
  - OpenAI/Azure: `x-ratelimit-reset-tokens`, `x-ratelimit-reset-requests`
- set `blockedUntilEpochMs` to the most conservative applicable reset
- empty both token and request buckets
- increment upstream 429 counters

The HTTP body's generic `"Too Many Requests"` / `"too_many_requests"` text is not enough to classify the failed
dimension. Use safe headers for diagnostics:

- Azure/OpenAI token pressure: `x-ratelimit-remaining-tokens <= 0`, often with `x-ratelimit-remaining-requests > 0`.
- Azure/OpenAI request pressure: `x-ratelimit-remaining-requests <= 0`, especially when token remaining is still positive.
- Anthropic input-token pressure: `anthropic-ratelimit-input-tokens-remaining <= 0`.
- If headers are missing or contradictory, classify as `unknown_429_dimension` in diagnostics and apply the conservative local block.

There is no fragile per-dimension bucket mutation. Even when diagnostics can classify the likely dimension, the gate
still empties both token and request buckets after upstream 429. Existing `LLM_RATE_LIMIT` logs retain the safe header
detail for operators.

Azure/OpenAI notes:

- Azure documentation describes deployment-level TPM/RPM enforcement: assigning TPM to a deployment maps to TPM and RPM
  rate limits for that deployment.
- The captured headers include `x-ratelimit-key=<deployment/model-name>` and per-call deployment-looking
  `x-ratelimit-limit-*` values, which is consistent with deployment-level pressure.
- However, shared resource / subscription / region / model quota can still affect multiple deployments through quota
  allocation. Provider-local gates do not coordinate across multiple Provider Things that share one Azure resource or
  API key.

For non-429 failures:

- release concurrency
- if a local reservation was acquired, refund full `reservedTokens` and one request
- do not set `blockedUntilEpochMs`

## 10. Logs

Existing logs remain:

- `LLM_HTTP_FAILURE`
- `LLM_RATE_LIMIT`
- `LLM_USAGE`
- `LLM_TOOL_SCHEMA_USAGE`

Add local-gate logs:

### `LLM_RATE_ADMISSION`

Emitted when:

- local enforcement waits (`action=wait`, always logged even when `logAdmissions=false`)
- local enforcement admits after waiting (`action=wait`)
- `logAdmissions=true` and a call is allowed without waiting (`action=allow`, `waitMs=0`)

When `logAdmissions=false` (default), no-wait allows are not logged; waited admissions are still logged so
ApplicationLog shows every wait without verbose per-call noise.

Fields:

```text
LLM_RATE_ADMISSION
providerThingName=...
providerTemplateName=...
apiShapeId=...
model=...
mode=enforce|observe
action=allow|wait
estimatedInputTokens=...
reservedTokens=...
requestedMaxOutputTokens=...
tokensPerMinuteLimit=...
requestsPerMinuteLimit=...
maxConcurrentRequests=...
tokensRemaining=...
requestsRemaining=...
inflight=...
waitMs=...
```

`waitMs` is the **actual** elapsed local wait for this admission (sum of `Object.wait` durations), not the requested
slice length. Early `notifyAll()` wakeups from concurrent releases therefore do not inflate the log.

### `LLM_RATE_REJECTION`

Always emitted for local rejection.

Fields:

```text
LLM_RATE_REJECTION
providerThingName=...
providerTemplateName=...
apiShapeId=...
model=...
reason=tokens_per_minute|requests_per_minute|concurrency|upstream_blocked|single_request_too_large
estimatedInputTokens=...
reservedTokens=...
tokensPerMinuteLimit=...
requestsPerMinuteLimit=...
maxConcurrentRequests=...
tokensRemaining=...
requestsRemaining=...
retryAfterMs=...
waitMs=...
maxLocalWaitMs=...
```

`waitMs` is the **actual** elapsed local wait before this rejection (same semantics as admission `waitMs`). Use it to
distinguish immediate rejections (`waitMs=0`, e.g. `single_request_too_large`) from budget-exhausted rejections
(`waitMs` near `maxLocalWaitMs`).

### `LLM_RATE_STATE`

Optional service-triggered or periodic diagnostic line. It should not log on every call by default.

Fields:

```text
LLM_RATE_STATE
providerThingName=...
tokensRemaining=...
requestsRemaining=...
inflight=...
blockedUntil=...
localAdmissions=...
localRejections=...
upstream429s=...
```

Do not log API keys, request bodies, or raw unsafe headers.

## 11. Error Surface

Local rejection throws `LlmRateLimitAdmissionException`.

Minimum fields:

- provider thing name
- `LlmRateLimitAdmissionReason` enum value
- retry-after ms
- `waitMs` (local time spent trying to admit before rejection)
- estimated input tokens
- reserved tokens
- configured limits

Suggested message:

```text
LLM provider local rate limit would be exceeded: provider=<name> reason=<reason> retryAfterMs=<n>
```

This surfaces as a service failure on `Chat` / AlwaysOn paths; local rejections are deliberately visible failures, not rewritten into a friendly assistant message.

## 12. Services

Add services on `LLMAPIProviderThing`:

### `GetRateControlState`

Returns an `INFOTABLE` with one row and an inline DataShape, not an opaque JSON string.

Minimum columns:

| Column | BaseType |
| --- | --- |
| `mode` | STRING |
| `tokenReserveStrategy` | STRING |
| `tokensPerMinuteLimit` | INTEGER |
| `requestsPerMinuteLimit` | INTEGER |
| `maxConcurrentRequests` | INTEGER |
| `maxLocalWaitMs` | INTEGER |
| `maxSingleRequestTokens` | INTEGER |
| `estimateSafetyMultiplier` | NUMBER |
| `tokensRemaining` | NUMBER |
| `requestsRemaining` | NUMBER |
| `inflight` | INTEGER |
| `blockedUntil` | DATETIME |
| `retryAfterMs` | LONG |
| `lastRejectionReason` | STRING |
| `localAdmissions` | LONG |
| `localWaits` | LONG |
| `localRejections` | LONG |
| `upstream429s` | LONG |
| `maxWaitMs` | LONG |
| `lastSafeHeaderSnapshot` | STRING |

`maxWaitMs` is the maximum **total** local wait observed for any single enforce admission (same semantics as
`LLM_RATE_ADMISSION waitMs` for that call), not the longest individual `wait(slice)` request.

### `ResetRateControlState`

Clears in-memory buckets and counters:

- buckets refill to capacity
- in-flight count resets to zero only if no call is in progress; otherwise fail with a clear error
- blocked-until clears
- counters reset
- do not reset while an upstream HTTP call is in flight
- call `notifyAll()` after a successful reset so bounded-wait callers re-evaluate immediately

Returns an `INFOTABLE` with one row:

| Column | BaseType |
| --- | --- |
| `success` | BOOLEAN |
| `message` | STRING |
| `inflight` | INTEGER |

### `TestRateControlAdmission`

Inputs:

- `estimatedInputTokens` LONG
- `requestedMaxOutputTokens` LONG
- `dryRun` BOOLEAN, default true

Returns the decision the gate would make without mutating bucket state or incrementing `inFlight`. Admission is always
peek-only: the `dryRun` parameter is retained for API compatibility but ignored. The service performs the same
monotonic-time refill step before evaluating capacity, so consecutive probes reflect time-based recovery (but do not
perform bounded `wait()` — Composer probes stay non-blocking).

Returns an `INFOTABLE` with one row:

| Column | BaseType |
| --- | --- |
| `wouldAllow` | BOOLEAN |
| `reason` | STRING |
| `estimatedInputTokens` | LONG |
| `requestedMaxOutputTokens` | LONG |
| `reservedTokens` | LONG |
| `tokensPerMinuteLimit` | INTEGER |
| `requestsPerMinuteLimit` | INTEGER |
| `maxConcurrentRequests` | INTEGER |
| `tokensRemaining` | NUMBER |
| `requestsRemaining` | NUMBER |
| `inflight` | INTEGER |
| `retryAfterMs` | LONG |

`reason` is blank/null when allowed; otherwise it is one `LlmRateLimitAdmissionReason` enum value.

## 13. Entry Points

All upstream LLM calls must pass through the same Provider gate:

- `AgentThing.Chat`
- `AgentThing.ChatAsync`
- `ParlerGateway.SubmitUserPrompt`
- `AgentThing.ParlerStreamToRemoteThing`
- every `AgentLoop` LLM iteration
- eval harness calls through `Chat`

The implementation rule is simple: do not call a raw vendor `LlmClient` from Agent code. Always use the Provider-owned client returned by `LLMAPIProviderThing.getLlmClient()`.

Provider `TestConnection` / `healthCheck()` is exempt from the local gate. `healthCheck()` creates a
`LlmChatRequest` with `probeMode=true` and calls `ProviderLlmClientBridge.chat(probe)`, so the bridge must explicitly
bypass local admission for `request.isProbeMode()`. It should still run Provider max-output / reasoning resolution and
then call the delegate. This keeps the probe as a short operator diagnostic after a heavy eval run. It still consumes
upstream quota and still emits existing upstream HTTP diagnostics if the provider rejects it; it just does not
participate in local admission control.

## 14. HITL and Conversation Locks

There is no per-user concurrency gate and no queue. Therefore HITL does not need special rate-control handling.

The provider concurrency slot is held only around the upstream HTTP call. It is never held while waiting for HITL approval. Existing conversation locks remain an AgentThing concern and should not be moved into the Provider gate.

## 15. Operator Validation

Run the eval smoke suite against Providers with configured limits in each mode:

```bash
uv run agent-eval --suite docs/agent/evals/smoke.yaml --agent-matrix yaml --reset-mode stable_hard_reset --timeout 600 --stream-buffer-wait-s 11
```

Capture the report directory plus `LLM_USAGE`, `LLM_HTTP_FAILURE`, `LLM_RATE_LIMIT`, `LLM_RATE_ADMISSION`,
`LLM_RATE_REJECTION`, and prompt-context cache lines.

`rateControlMode=disabled` shows the upstream-failure baseline (upstream 429s under heavy turns).

`rateControlMode=observe`:

- no behavior change
- no local rejection
- estimates logged or available through `GetRateControlState`
- upstream 429 behavior remains visible

`rateControlMode=enforce` with `maxLocalWaitMs=0`:

- heavy turns that would exceed local Provider TPM fail before upstream HTTP dispatch
- local failures emit `LLM_RATE_REJECTION`
- avoidable upstream 429 count drops
- any remaining upstream 429 updates Provider gate state through structured safe headers
- easy low-token probes still pass
- `GetRateControlState` reports consumed buckets and rejection counters

Bounded wait: set `maxLocalWaitMs` slightly above a known reset interval and verify the gate waits and admits after
refill (keep this out of default eval because it slows the suite).

## 16. Design Invariants

- Fail-fast enforcement and structured upstream-429 feedback work as a pair. Enforcement without structured 429
  feedback would admit the next call after an upstream 429 that was reported as a generic exception and rolled back as
  non-429, recreating the 429 cascade. The OpenAI, Azure OpenAI, and Anthropic HTTP delegate clients therefore throw a
  structured `LlmHttpFailureException` carrying status, `rateLimited`, safe headers, and capped `bodyPreview` from
  `LlmHttpDiagnostics`, and 429 headers set `blockedUntilEpochMs`.
- Non-429 upstream failures refund token/request reservations.
- Bounded wait (`maxLocalWaitMs > 0`) uses synchronized `wait(sliceMs)` / refill / re-check loops before upstream
  dispatch; there is no background queue and no post-429 retry loop. `LLM_RATE_ADMISSION action=wait` records actual
  waited milliseconds, and `localWaits` / `maxWaitMs` counters update on successful waits. `notifyAll()` runs on
  reservation release, gate reset, upstream-block expiry during refill, and upstream-429 accounting.
  `maxLocalWaitMs=0` keeps fail-fast local rejection.
- Replay-compaction JSON preflight: a tool-result body that is not JSON-like after trim (`{` or `[`) passes through
  without `LLM_COMPACT_MALFORMED`; a JSON-like body that fails to parse keeps the warning; null or empty bodies pass
  through silently and must not throw.

## 17. Tests

Unit tests:

- config parsing defaults
- blank `tokenReserveStrategy` resolves through Provider `defaultTokenReserveStrategy()`
- disabled mode bypass
- observe mode does not mutate blocking behavior
- token bucket refill
- request bucket refill
- concurrency acquisition/release in success and exception paths
- fail-fast token rejection
- single-request-too-large rejection
- success reconciliation refund
- success reconciliation negative debt
- token bucket negative debt clamps at `-tokensPerMinuteLimit`
- `TestRateControlAdmission` refills before evaluating capacity
- non-429 upstream failure refunds token/request reservation
- upstream 429 header parsing
- structured exception carries safe headers only
- obviously non-JSON tool-result body passes through replay compaction without `LLM_COMPACT_MALFORMED`
- null or empty tool-result body passes through replay compaction without throwing
- JSON-looking but malformed tool-result body still emits `LLM_COMPACT_MALFORMED`

Integration/manual tests:

- Provider `TestConnection` remains exempt from local gate under disabled/observe/enforce.
- One low-token chat passes under a small but sufficient TPM.
- Eval smoke suite under configured test TPMs (§15).
- Confirm local rejection logs appear before any upstream HTTP request for rejected calls.
- Confirm a 30K-TPM Sonnet Provider no longer depends on Anthropic returning 429 to reveal pressure.
- Confirm each built-in Provider Thing shows the inherited `LLMAPIProviderRateControl` table plus its native
  per-template settings table in Composer.
- Grep for raw `LlmClient` field declarations / direct `chat()` calls outside `ProviderLlmClientBridge` and confirm Agent runtime paths resolve through the bridge.
