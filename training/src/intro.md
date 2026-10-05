# Introduction

## What is Parler?

**Parler is an AI agent that runs inside ThingWorx, delivered as a platform extension.** It lets people ask about
their ThingWorx data and Thing Model in plain natural language through a chat widget, and it can answer with text,
tables, and charts.

Parler is not a generic chatbot placed next to ThingWorx. It works by calling **tools** that read real platform data,
so its numbers, tables, and charts are grounded in the actual ThingWorx model. When an operation may change data or
call a sensitive service, Parler can also require human approval before the action proceeds.

Parler is built to be extended without changing its Java code. Application teams teach it their domain through
configuration:

- **taxonomy**, so it knows which Things and asset types users mean;
- **extended tools**, so it can call application services through LLM-friendly interfaces;
- **skills**, so recurring business tasks have reusable guidance;
- **playbooks**, so stable multi-step workflows can run as deterministic DAGs.

Turning a capable platform agent into one that reliably supports **your** ThingWorx application is the purpose of this
book.

This mdBook is a **structured four-session curriculum** for internal teams who extend **Parler** inside a **ThingWorx**
solution.

The target audience already knows ThingWorx application development, Mashups, and JavaScript services. The training
therefore does not spend much time proving ThingWorx basics. It does spend time on AI-agent basics: tool schemas,
context, evidence, skills, playbooks, and DAGs.

The narrative follows a real teaching path:

1. connect Parler and run simple prompts;
2. teach the agent customer vocabulary with **identity** and **asset type** taxonomy;
3. use **built-in tools** to solve a real multi-step task;
4. turn that repeated task into the first **built-in-only skill**;
5. expose app services through **extended tools** and **wrapper services**;
6. redesign app services into a more **LLM-friendly interface**;
7. promote stable workflows into **playbooks**;
8. add **evidence grounding** after students have seen answer variance.

The current workshop line uses the live **Parler agent** and **UI widget** versions shown by the connection status in
the mashup. Screenshots may show older patch versions; treat the displayed runtime pair as the source of truth.



## Configuration and chart reference baseline

The configuration and chart reference chapters describe **Agent 0.1.248 / Widget 0.1.97**:

- [Every Agent and Provider configuration field, with tuning effects](./21-agent-and-provider-configuration.md).
- [A worked prompt-to-response example and the LLM data boundary](./22-prompt-to-response-and-chart.md).
- [Supported chart types, variants, limits and trade-offs](./23-supported-charts-and-tradeoffs.md).

Earlier workshop screenshots, historical release notes and import assets can show older versions. The new reference baseline does not mean those assets were regenerated or live-tested with this pair. Use the runtime handshake to identify your installed versions.

## How to build this book

From the repository root:

```bash
mdbook build
mdbook serve --open

# or, to auto-reload on changes:

mdbook watch --open
```
