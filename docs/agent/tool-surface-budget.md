# Tool surface budget

Status: per-round tool exposure is controlled by `toolAdmissionMode` (`off` | `narrow` | `lazy`) — see
[`../operations/tool-schema-admission-control.md`](../operations/tool-schema-admission-control.md). This document keeps
the budget view: why the model-facing tool surface is a fixed request cost, how to measure it, and the authoring rules
that keep it small.

"Surface" means the whole model-visible tool area: tool schemas, tool descriptions, routing-guide overlap, and the policy
for which tools are offered on each round. "Budget" ties it to measurable context and rate-control constraints.

## Motivation

The provider-aware context planner shows that fixed overhead can dominate a request. Typical magnitudes on an Azure
provider with the full tool set offered:

- `effectiveRequestCapChars` about 134k.
- `stableChars` about 24k.
- `toolSchemaChars` about 64k.
- `ephemeralChars` about 1k to 2k.
- The remaining history/evidence budget often in the 25k to 45k range before user evidence is considered.

A successful context planner can therefore spend most of the request budget before any conversation history, tool
results, or current task evidence enters the request. Every added first-party tool makes this worse unless the tool
surface is budgeted.

## Problem

Offering the broad first-party tool set on every tool-capable round is reliable but expensive:

- Most offered tools are idle in a given round.
- Model-facing schemas repeat across rounds.
- Long descriptions and examples can duplicate routing-guide text.
- Rare tools still pay fixed overhead in common turns.
- More tools increase provider request size and local TPM reservation, even when the model calls only one tool.

## Telemetry

- `LLM_CONTEXT_PLAN.toolSchemaChars`
- `LLM_CONTEXT_PLAN.historyBudgetChars`
- `LLM_TOOL_SCHEMA_USAGE.schemaCount`
- `LLM_TOOL_SCHEMA_USAGE.schemaTools`
- `LLM_TOOL_SCHEMA_USAGE.calledTools`
- `LLM_TOOL_SCHEMA_USAGE.idleTools`

`LLM_TOOL_SCHEMA_USAGE` is logged once per LLM completion round (`LlmUsageTelemetry.logToolSchemaUsage`); see
[`llm-token-budget.md`](./llm-token-budget.md). Admission decisions are logged as `TOOL_ADMISSION` (see the admission
control document).

## Principles

1. **Measure before shrinking.** Use `LLM_TOOL_SCHEMA_USAGE` and `LLM_CONTEXT_PLAN` over representative traffic to rank
   tool schema cost, call frequency, and idle frequency.
2. **Deterministic exposure.** Exposure decisions come from a small set of deterministic signals, not per-turn arbitrary
   pruning, so they are testable and observable.
3. **Keep a stable core.** Basic entity resolution, cached-result continuation, and safe finalization support do not
   disappear from ordinary rounds.
4. **Move prose out of schemas only when safe.** Shorter schemas help, but tool parameters still need enough detail for
   correct calls.
5. **Treat rare side-effecting tools carefully.** Low call count is not enough reason to hide approval, alert, or
   service-invocation tools if their routing is safety-critical.
6. **Never rely on hidden tools.** The model is not expected to call a tool it was not offered.

## Schema Authoring Rules

- Do not repeat examples that the routing guide already carries.
- Keep descriptions from restating parameter names.
- Keep error-code and result-shape detail in docs / the routing guide unless the model needs it to construct arguments.
- Prefer enum constraints and concise parameter descriptions over long prose.
- Do not put generic secret-redaction language in every tool schema; protection policy is centralized.

## Routing Guide Relationship

- The routing guide says when to use a tool family.
- The schema says how to call the exposed tool.
- Admission decides which tool families are exposed.

If the routing guide says "use X" but admission can hide X, that condition must be explicit in the admission logic and
covered by tests.

## Provider And Rate-Control Relationship

Provider-aware caps from `context-compaction.md` remain the outer safety layer. Reducing the tool surface lowers the
fixed part of the request before history/evidence is trimmed:

```text
stable prompt + ephemeral rows + tool schemas + current user + active batch reserve + history/evidence <= effective cap
```

Success is not only "no `single_request_too_large`". It also means:

- lower p50/p95 `toolSchemaChars`
- higher p50/p95 `historyBudgetChars`
- fewer local rate-admission waits caused by oversized reserved prompt tokens
- no regression in common tool workflows
