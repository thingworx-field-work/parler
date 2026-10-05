# Alert tools: tests that need a running ThingWorx server

The unit tests in `parler-agent` run offline: there is no full ThingWorx runtime, no Composer and no
connected `<parler-ui>` page. This document lists the alert-tool behaviors that those tests can only verify
partially. Run them on a server where the Parler extension and widget are installed and real alert data and
platform services are available.

The behavior itself is described in [`alert-solution.md`](alert-solution.md); when this list and that
document or the code disagree, the code wins.

---

## 1. Platform runtime and class path

- **End-to-end ThingWorx runtime:** offline, some paths touch `ValueCollection`, logging and provider
  initialization that stubs cannot satisfy. On a running server, exercise `AlertToolsExecutor` and the full
  acknowledge path (`executeAcknowledgeAlerts` → `doAcknowledgeAlerts`).
- **Alert function resolution on real entities:** resolve the alert services for real Things, Thing
  Templates and inheritance chains, including a Thing without a template, multiple templates, and a caller
  without permission on some of them.

---

## 2. Resource-backed `QueryAlertHistory`

- **Resource path** (reported as `historyQueryResource` in the tool result): on a Thing that has alert
  history, check row counts, the column set, and `severity` / `message` / `source` values.
- **Resource versus Thing-local history:** compare the resource-backed `QueryAlertHistory` with the Thing's own
  `QueryAlertHistory` for the same query, in particular whether the platform has already applied filtering.
- **Edge cases:** empty results, very large time windows and an invalid `thingName`, checking error codes and
  empty-table output.

---

## 3. `acknowledge_alerts` end to end

- **Real `AcknowledgeAlert` calls:** one row and several rows, by `alertId` and by row index; confirm the state
  change on the platform side (through Composer or REST).
- **Summary rows used for acknowledgement:** with real `QueryAlertSummaryForThing` data, check that the summary
  rows and the acknowledgeable rows match.
- **Platform errors and partial failure:** check that errors mapped by `AlertAcknowledgePlatformErrors` match
  what the platform actually returns.
- **Repeated or concurrent acknowledgement:** the same `alertId` acknowledged twice, and several users
  acknowledging at the same time.
- **Insufficient permission:** the error returned to the model and the message shown in the UI for summary,
  history and acknowledge calls.

---

## 4. `query_alert_summary` and `query_alert_history` options

- **Sorting and limits:** `oldestFirst` and summary sort order on realistic data volumes, including the first
  page, the last page and a single page.
- **Time presets** (`LAST_1H`, `LAST_24H`, `LAST_7D`) and the default window: check boundary instants against
  the server and user time zones, including daylight-saving transitions where they apply.
- **`GetAlertPrompt` and runtime overrides:** check that replace and merge behavior on a real agent request
  matches the implementation and `docs/agent/LLM_CONTEXT.md`.

---

## 5. `<parler-ui>` and streaming

- **Alert result tables in a streamed answer:** rendering, collapsing, and complete history after a reconnect.
- **Large tables (`INFOTABLE_LARGE`):** browser performance and the truncation behavior.
- **Follow-up analysis:** `tabulate_cached_result`, `summarize_cached_result` and charts over a cached alert
  query result.

---

## 6. Versions

- **ThingWorx versions:** differences in `AcknowledgeAlert` signature or behavior between supported platform
  versions (check the PTC release notes).
- **Widget and extension versions:** behavior when the widget and the extension are at different versions,
  sampled according to the supported-version policy.

---

## 7. Logs and performance

- **ApplicationLog:** when an alert service call fails, the log line carries enough fields for an operator to
  find it.
- **Latency baseline (optional):** P95 latency of summary and history queries on a typical Thing, for later
  comparison.
