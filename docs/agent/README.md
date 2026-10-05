# Agent documentation (`docs/agent/`)

**Implementation and deep-dive docs** for the ThingWorx Java extension under **`parler-agent/`**. All live in **this directory** — there is no nested **`parler-agent/docs/`**. Cross-cutting **Parler wire / AlwaysOn / UI** architecture is under **[`../architecture/`](../architecture/)**. Normative contracts are in **[`../../CONTRACTS/`](../../CONTRACTS/)** (repo root).

## Core references

- **`AGENT-CONTEXT.md`** — contributor-facing overview of the extension: architecture, agent loop, LLM clients, built-in tool registry, `AgentSettings`, build system, file map.
- **`LLM_CONTEXT.md`** — what is sent to the LLM at runtime (system prompt, bundled routing guides, stable prefix, per-turn suffix).
- **`AGENT-ALWAYSON-TWX.md`** — AlwaysOn on ThingWorx: `ParlerGateway`, `SubmitUserPrompt`, `ReceiveMessage`, HITL audit logging.
- **`parler-gateway-design.md`** — transient `ParlerGateway` session Things, bind naming, HA.
- **`AGENT-TAXONOMY.md`** — taxonomy field semantics, `identity-types.json`, `query_entities_by_taxonomy`.
- **`LLM-PERSISTENCE.md`** — `AgentThreadDataTable` / `AgentMessageStream` persistence and the in-memory conversation list.
- **`CUSTOMIZED-SKILLS.md`**, **`CUSTOMIZED-TOOLS.md`** — repository skills and extended tools.
- **`configuration-repository.md`** — FileRepository-backed AgentThing configuration root: skills, taxonomy Markdown, `invoke_service` HITL allow policy, extended tools, `GetAgentRuntimeSnapshot`, `ValidateAgentConfigurationRepository`.
- **`data-operation-solution.md`** — the write path and HITL: gated tools, pending approvals, approval principal, protocol and UI.
- **`all-tools.md`** — model-facing tool inventory and per-tool context cost; tool admission.

## Tools and platform semantics

- **`key-resolution.md`** — model key resolution, QIT counting/listing, direct **`thingTemplate`** breakdowns.
- **`time-interpretation.md`** — structured time intent (`timeSpec` semantics), metadata, caps, heuristic rejection for DATETIME literals.
- **`property-history-unification.md`** — unified **`query_property_history`** (numeric + value-stream lanes).
- **`query-construction.md`** — shared **QUERY** JSON for QIT, Data Tables, Streams; metadata-before-QUERY steering.
- **`query-spec.md`** — canonical ThingWorx-shaped query JSON for **`tabulate_cached_result`** (`filters` / `sorts` / `maxItems` / `offset` / `fields`; legacy grammar **rejected**).
- **`entity-schema-description.md`** — facet-bounded **`describe_entity_schema`** for ThingTemplate / ThingShape / DataShape.
- **`thing-member-discovery.md`** — concrete Thing member discovery (**`discover_thing_members`**).
- **`legacy-discovery-executor-only.md`** — legacy **`discover_*` / `get_service_definition`** vs the **merged LLM tool list** (**executor-only** / **`advertiseLegacyServiceDiscoveryTools`**), **`extended_tools.json`** **`executorOnly`**, **`GetAgentRuntimeSnapshot`** observability.
- **`model-tool-admission-guardrails.md`** — **`query_entities.query`** predicate admission and empty skill-catalog **`get_agent_skill`** model-facing suppression.
- **`entity-set-analysis.md`** — **`analyze_entity_set`**: deterministic cached entity-list set algebra over **`difference`**, **`intersection`**, **`union`**, and **`symmetric_difference`**.
- **`hierarchy-composite-retirement.md`** — replacement paths for the removed hierarchy composite tools.
- **`document-chunk-tools.md`** — built-in tools **`search_document_chunks`** and **`get_document_chunk`** for document-grounded recommendations over already-converted PDF/manual packages.

## Cached tables, results, and charts

- **`cached_tabular_tools.md`** — `tabulate_cached_result` / `summarize_cached_result`.
- **`cached-table-decision-tools.md`** — deterministic decision modes over cached tables (filters, top-N, `group_metric`, distributions).
- **`tabular_insight_envelope.md`**, **`cached_tabular_golden.md`** — `insightEnvelope` and the golden/negative scenario catalog.
- **`tool-result-egress-control.md`** — unified egress (`ToolResultEgressGateway`) before LLM replay, UI wire, Stream, and caches.
- **`large-table-replay-control.md`** — split-lane `fetch_cached_result`, compact persist/rehydrate.
- **`history-overlay-chart.md`** — **`build_history_overlay_chart`** (absolute, elapsed, and normalized X axes; reference lines).

## Context, prompts, and conversation state

- **`context-compaction.md`** — context planner, replay compaction, storage normalization, Stream rehydration.
- **`anthropic-breakpoint.md`** — prompt-cache prefix stability, the volatile suffix, and Anthropic cache markers.
- **`conversation-continuity.md`** — rebuilding LLM history from `AgentMessageStream` after restart; `ClearConversation` semantics.
- **`task-state.md`** — per-turn **`AgentTaskState`** evidence ledger, skill task checklists, and UI progress surface.
- **`evidence-grounded.md`** — final-answer evidence rules on top of task-state evidence; the evidence-grounding eval suite.
- **`llm-usage-stream-telemetry.md`** — `AgentMessageStream` usage/cache telemetry (`llmUsageJson`) plus eval reset modes.
- **`AgentLlmCallStream` / `AgentLlmUsageHelper`** — independent LLM call ledger Stream and read-only report/CSV helper; spec in **`nearterm/context-compaction-adv.md`** CC-7; registered in **`AGENT-CONTEXT.md`** §5.6. Not a replacement for message-stream `llmUsageJson`.
- **`connection-version-handshake.md`** — `ParlerGateway.GetConnectionInfo` and the agent/widget version status line.

## Playbooks

- **`playbook-engine.md`** — Playbook runtime; `playbook.json` V1 format in §4.
- **`playbook-34-35.md`** — service-orchestration Playbook capabilities (normalization, envelope extraction, **`$infotable`** binding, nested payload + stringify, time-window derivation, optional branches). Runner fixtures: **`parler-agent/src/test/resources/playbook-34-35-fixture/`**.
- **`playbook-input-resolution.md`** — Playbook user-facing identifiers normalized through **`resolve_thing`** (optional type hint) and deterministic row matching.
- **`playbook-tool-artifact-wire-emission.md`** — Playbook internal tool results emit the same **`type: "chart"`** / **`type: "table"`** wire as top-level chat tools.
- **`playbook-customer-readiness.md`** — Playbook runtime snapshot, validator reports, evidence compression, empty/failure semantics, authoring ergonomics.
- **`playbook-converter/`** — Skill-to-Playbook converter templates (`SKILL.md`, knowledge, golden conversion reports).
- **`playbook-eval-pack/`** — Playbook eval packs.
- **`cross-region-health-current-values-evidence.md`** — `cross_region_health` playbook: **`summarize_current_values_by_region`** derive + evidence for **`llm_summary`**.

## Evaluation and diagnostics

- **`agent-evaluation-harness.md`** — `uv run agent-eval` (live ThingWorx conversation tests, multi-turn scoring, tool-trace assertions, provider matrix runs, partial reports, `failureKind`, assertion groups). Suites live in **`evals/`**.
- **`collection-tool.md`** — `uv run parler-collect-live` support bundles (ApplicationLog, AgentMessageStream, AgentThing status).
- **`live-diagnostics.md`** — live diagnostics recipes.

## Near-term topic documents (`nearterm/`)

- **[`cache-correctness-foundation.md`](nearterm/cache-correctness-foundation.md)** — file-backed artifact cache kernel and tool correctness foundation.
- **[`cache-missing-improvement.md`](nearterm/cache-missing-improvement.md)** — cached-table column roles, projection feedback, and source echo.
- **[`chart-enhancement.md`](nearterm/chart-enhancement.md)** — chart cards, chart kinds, and chart groups.
- **[`computing-enhancement.md`](nearterm/computing-enhancement.md)** — measurement modes of `tabulate_cached_result`.
- **[`context-compaction-adv.md`](nearterm/context-compaction-adv.md)** — request budgets, fixed overhead, and the LLM call ledger.
- **[`deterministic-insight-kernel.md`](nearterm/deterministic-insight-kernel.md)** — deterministic statistics kernel and `analyze_cached_result`.
- **[`fleet-rca.md`](nearterm/fleet-rca.md)** — fleet benchmarking and evidence-ranked RCA.
- **[`semantics-evidence-foundation.md`](nearterm/semantics-evidence-foundation.md)** — application semantic profile and evidence foundation; authoring walkthrough in [`semantics-evidence-foundation-app-authoring-walkthrough.md`](nearterm/semantics-evidence-foundation-app-authoring-walkthrough.md).
- **[`service-provider-resilience.md`](nearterm/service-provider-resilience.md)** — service capability metadata and LLM provider resilience; operator notes in [`service-provider-resilience-operator-runbook.md`](nearterm/service-provider-resilience-operator-runbook.md).
- **[`tabular-reach.md`](nearterm/tabular-reach.md)** — turning tool results into cached tables for analysis and charts.
- **[`time-quality-join.md`](nearterm/time-quality-join.md)** — time transforms, time-series quality, and exact join over cached tables.
- **[`tool-cache-integration.md`](nearterm/tool-cache-integration.md)** — tools and the file-backed `ArtifactCache`.

Other files in this directory cover individual tools and features (for example `invoke_service_design.md`, `metadata_discovery.md`, `rate-control.md`, `skill-management.md`, `system-prompt-cache.md`, `llm-api-provider.md`); their file names describe their scope.
