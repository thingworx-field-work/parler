# Parler architecture

## What Parler is

Parler is a **ThingWorx extension** that adds an **AI agent** to the platform. A mashup user talks to **`<parler-ui>`**; the **`AIAgent`** Thing (`AgentThing` in Java) runs an **agent loop** against your **LLM provider**, may call **built-in tools** and **repository-backed extended tools**, and streams results over **AlwaysOn** wire JSON to the widget.

## Main components (monorepo)

| Area | Path | Role |
| --- | --- | --- |
| Java agent | `parler-agent/` | Agent loop, tool registry, `Chat` / `ParlerStreamToRemoteThing`, configuration repository readers, playbook engine. |
| Web UI | `parler-ui/` | Lit **`<parler-ui>`** — reducer, charts, AlwaysOn client. |
| Widget package | `parler-ui-widget/` | ThingWorx **mub** packaging for Composer. |
| Contracts | `CONTRACTS/` | Normative wire and tool shapes (keep code and docs aligned). |

## Bridge pattern

Mashup code calls **ThingWorx services** on **`ParlerGateway`** and **`AIAgent`**. The extension talks to the **LLM** and executes **tools** on the server. Streaming mode pushes **wire JSON** to the gateway / conversation Thing; the UI renders assistant text, charts, task state, and approvals.

## Design principles (teach first)

1. **Trust by construction** — Numeric series for charts come from platform-backed tool rows, not free-form model guesses.
2. **Human-in-the-loop (HITL)** — Mutating paths can require explicit user approval inside the Parler widget.

## Configuration surface

Project-specific behavior is often **file-driven** on the **`configurationRepository`** FileRepository Thing:

- **`/taxonomies/*.json`**,
- **`/skills/<id>/SKILL.md`**,
- **`/tools/extended_tools.json`**,
- **`/policies/invoke_service.json`**.

See **`Appendix C: Configuration Repository`**.

For a concrete example of tool evidence, server-side aggregation, chart construction and data sent to the LLM, read [From a prompt to an answer and a chart](./22-prompt-to-response-and-chart.md).
