# Rate-control UI Status

Status: implemented.
Document type: specification for a small UI affordance over provider local rate-control waits.

## 1. Purpose

Long skill and playbook turns can pause for provider-local rate control before the next LLM request is allowed. With only the normal active-turn working pulse, a TPM/RPM wait is indistinguishable from tool execution, network delay, or model latency.

The feature is a narrow, transient status signal:

- when the provider rate gate starts a local wait, the active-turn working indicator switches to a brighter "rate-control wait" tone;
- when the wait ends and the LLM request resumes, the indicator returns to the normal working tone;
- if terminal output arrives first, the UI clears the rate-control tone as a fallback.

The feature is intentionally visual and operational. It does not retry failed requests, mask 429s, change rate-control policy, or persist extra history.

## 2. Existing Signals Are Not Enough

`docs/agent/rate-control.md` already defines local admission logs:

- `LLM_RATE_ADMISSION`
- `LLM_RATE_REJECTION`
- provider HTTP 429 telemetry

Those logs are useful for operators and eval reports, but they are not a real-time UI protocol. The UI also has `activity` and `task.state`, but neither is the right carrier:

- `activity` is human text and would require brittle string parsing or message conventions.
- `task.state` describes task progress and should not become a transport for provider wait mechanics.
- history hydration should not replay an old rate-control wait; the wait only matters while the turn is live.

Therefore it uses a small structured wire event rather than overloading existing text.

## 3. Wire Event

Optional server-to-client frame:

```json
{
  "type": "rate_control.status",
  "conversation_id": "scpa_talk",
  "request_id": "active-turn-request-id",
  "status": "waiting",
  "reason": "tokens_per_minute",
  "wait_ms": 20500,
  "retry_after_ms": 20500
}
```

Resume frame:

```json
{
  "type": "rate_control.status",
  "conversation_id": "scpa_talk",
  "request_id": "active-turn-request-id",
  "status": "resumed"
}
```

Fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `type` | Yes | Literal `rate_control.status`. |
| `conversation_id` | Yes | Same AlwaysOn profile rule as other downstream frames. |
| `request_id` | Yes | Active assistant turn id. |
| `status` | Yes | `waiting` or `resumed`. |
| `reason` | Only on `waiting` when known | Existing waitable `LlmRateLimitAdmissionReason` wire value, e.g. `tokens_per_minute`, `requests_per_minute`, `concurrency`, `upstream_blocked`. |
| `wait_ms` | Optional | Current known wait estimate or bounded wait slice. UI must not rely on precision. |
| `retry_after_ms` | Optional | Provider/header-derived retry hint or local bucket retry estimate. UI must not rely on precision. |

The UI only uses `status`. Other fields are safe operational metadata, not display contract. Do not include prompts, tool payloads, raw headers, token bodies, API keys, or user data.

Clients that do not know this `type` ignore it and keep the normal working pulse.

## 4. Agent Integration

The rate gate sits below `AgentLoop`:

```text
AgentLoop
  -> LlmChatRequest
  -> ProviderLlmClientBridge.chat(...)
  -> LLMAPIProviderRateGate.execute(...)
  -> provider HTTP client
```

Because the wait happens inside `LLMAPIProviderRateGate`, the status signal must be available at the LLM request layer before the gate blocks.

Implementation:

1. `LlmChatRequest` carries an optional `RateControlStatusSink` (`llm/ratecontrol/RateControlStatusSink`); rate control belongs to provider request execution, not tool context, so the sink does not ride `AgentToolContext`.
2. `copyWithProviderAugmentation(...)` and `copyWithProbeMode(...)` preserve the sink.
3. `AgentLoop` takes the sink as an optional constructor argument and passes it into `LlmChatRequest.forAgentRound(...)`.
4. `AgentThing.ParlerStreamToRemoteThing` and post-HITL continuation paths create a sink that emits `ParlerReceiveMessageSupport.wireRateControlStatus(...)` to the current `remoteConversation`.
5. Health checks, `TestConnection`, non-UI direct calls, and tests may leave the sink null; the rate gate then logs only.
6. Probe-mode requests must not trigger UI status even if a sink is present. Probe calls are diagnostics/background checks, not user-visible active turns.

The sink is best-effort:

- failed `ReceiveMessage` does not fail the LLM call;
- failed `ReceiveMessage` produces a safe operator log line: `LLM_RATE_UI_STATUS_DROPPED conversationId=... requestId=... status=... reason=...`;
- the event is not persisted to `AgentMessageStream`;
- no retry loop for the UI event itself.

### Gate Emission Points

For each LLM HTTP admission attempt, where "LLM HTTP admission attempt" means one `LlmClient.chat(...)` invocation, not the entire multi-round assistant turn:

- emit `waiting` before a local bounded wait starts, and only when the gate is about to actually wait (`slice > 0`);
- emit `resumed` once the gate has acquired capacity and the HTTP call is about to run;
- emit `resumed` before throwing a local admission exception after a previous `waiting`, so the UI does not stay bright when the turn moves to terminal error;
- do not emit `waiting` for non-waitable rejection reasons such as `single_request_too_large`; that path throws immediately without a wait;
- do not emit for `observe` mode, `disabled` mode, probe mode, or requests with no status sink.

The gate may loop through several wait slices. To avoid noisy UI events, emit only the transition into waiting once per `LlmClient.chat(...)` call and the transition out once per `LlmClient.chat(...)` call. A tool-calling turn may therefore show several wait/resume cycles if several separate LLM rounds hit the gate. Logs keep the detailed wait accounting.

The gate never makes slow or blocking UI sends while holding the rate-gate monitor. It uses a capture-and-emit-outside pattern:

1. inside the synchronized gate section, compute whether the next action is a wait and capture a small immutable transition object;
2. leave the synchronized section;
3. emit `waiting` best-effort through the sink;
4. re-enter the gate monitor, **re-run `refill` + `peek`**, and only then compute `slice` and call `Object.wait(slice)` when still denied — if the new peek shows capacity, reserve and return **without** sleeping on the stale `peek` / `slice` from step 1. Another thread may have satisfied the bucket (and called `notifyAll`) while the UI sink ran outside the monitor; calling `wait` on a slice computed **before** that window can miss the notification and, under `maxLocalWaitMs` pressure, turn an already-admissible request into a false local rejection.
5. similarly capture `resumed` or clear transitions and emit them after leaving the synchronized section.

Do not call `ReceiveMessage` or other potentially slow code while holding the gate monitor. Also do not emit `waiting` only after the wait has completed; that is too late for the UI affordance. The product requirement is visual status during the wait, not exact millisecond-level accounting.

## 5. UI Behavior

The wire adapter maps:

```text
rate_control.status waiting -> assistant.rateControlStatus { requestId, waiting: true, reason? }
rate_control.status resumed -> assistant.rateControlStatus { requestId, waiting: false }
```

Reducer behavior:

- set `rateControlWaiting: true` on the matching active assistant row when `waiting`;
- set `rateControlWaiting: false` or remove it when `resumed`;
- ignore the event if `requestId` does not match a known assistant row;
- ignore stale events when the turn is no longer active;
- drop `waiting` if `state.activeRequestId` no longer equals the event `requestId` or if the matching assistant row is already completed.

Safety clearing:

- `assistant.append` with non-whitespace text clears `rateControlWaiting`;
- `assistant.chart`, `assistant.table`, and `assistant.taskState` may clear it when they indicate forward progress after the wait;
- `session.done`, `session.error`, and `session.superseded` must clear it.

View behavior:

- `renderActiveTurnIndicator(...)` adds a modifier class while the active assistant row has `rateControlWaiting`;
- normal working pulse remains the default;
- the rate-control wait pulse (class `working working--rate-control-wait`, `data-activity="rate-controlled"`) uses a distinct warm tone so it reads as "waiting for capacity" rather than normal processing;
- when `resumed` arrives, the normal pulse returns immediately.

Text policy: the indicator text is unchanged; only the tone changes. User-facing copy does not mention TPM internals.

## 6. Relationship To Existing UI Blocks

The rate-control status applies only to the active-turn working indicator. It does not change:

- task panel placement or content;
- chart/table rendering;
- row action buttons;
- turn details modal;
- feedback/cutoff history behavior.

When charts or tables have already appeared and the active working pulse is rendered below them, the same color switch applies wherever the pulse currently sits. The feature does not move UI blocks.

## 7. Contracts

The event touches the wire protocol and UI state; these surfaces change together:

- `CONTRACTS/API_CONTRACT.md`: `rate_control.status` server-to-client frame and examples.
- `CONTRACTS/UI_CLIENT_PROTOCOL.md`: wire union, UiEvent mapping, ChatRow state, reducer rules, and clearing rules.
- `CONTRACTS/CONTRACT_VERSION.md`.
- `parler-ui/lib/types.js`, `wireAdapter.js`, `chatSession.js`, and active-turn view/CSS.
- `parler-agent` wire builder and rate-control request path.

No `AgentMessageStream` schema change is required because this status is live-only.

## 8. Tests

Agent tests:

- `ParlerReceiveMessageSupport.wireRateControlStatus(...)` builds valid `waiting` and `resumed` JSON with `conversation_id` and `request_id`.
- `LlmChatRequest.copyWithProviderAugmentation(...)` preserves the optional sink.
- rate gate emits `waiting` then `resumed` for a bounded wait path.
- rate gate emits no UI status in disabled/observe mode.
- local rejection after a wait emits a final `resumed` or equivalent clear event before the exception escapes.

UI tests:

- `wireToUiEvent` maps `rate_control.status` waiting/resumed frames.
- reducer sets and clears `rateControlWaiting` on the matching assistant row.
- reducer ignores stale or unknown request ids.
- `assistant.append`, chart, table, task-state, done, error, and superseded clear the flag.
- active-turn indicator renders the modifier class when the row is waiting.

Manual test:

1. Configure one provider with `rateControlMode=enforce` and a low enough TPM to force a bounded local wait.
2. Run a long skill turn from `parler-ui`.
3. Confirm the working pulse switches to vivid wait tone during `LLM_RATE_ADMISSION action=wait`.
4. Confirm it returns to normal after admission resumes and before/when content continues.
5. Confirm no old wait status appears after reconnect/history load.

## 9. Non-goals

- No new retry policy.
- No queue UI.
- No friendly rewriting of 429 or local rejection errors.
- No persisted rate-control history rows.
- No cross-session resume.
- No task-state schema change.
- No attempt to expose exact TPM/RPM accounting in the UI.
