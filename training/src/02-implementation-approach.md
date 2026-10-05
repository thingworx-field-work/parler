# Implementation approach (iteration)

This chapter is the **spine** of how we extend Parler for a concrete project. It is intentionally **iterative**: each layer fixes a class of user questions the previous layer cannot answer safely.

## Two orthogonal tracks: taxonomy vs hierarchy

Parler treats **taxonomy** and **hierarchy** as **separate first-class concerns**. Taxonomy answers "what kind of thing is this?" and "which concrete Thing does this label mean?" Hierarchy answers "where does this Thing sit in a tree?" The deeper architecture note lives in **[`docs/architecture/entity-hierarchy.md`](../../docs/architecture/entity-hierarchy.md)** in this repository for maintainers; this chapter includes the working facts students need.

| Track | Question it answers | Typical configuration |
| -------- | ---------------------- | ------------------------ |
| **Taxonomy** | *What kind of asset is this?* How do short labels map to **ThingShapes**, **ThingTemplates**, or resolver rules? | **`/taxonomies/identity-types.json`**, **`/taxonomies/asset-types.json`**, optional **`/taxonomies/type-taxonomy.md`**. |
| **Hierarchy** | *Where in the tree is this asset?* Which Things live under **USA** vs **Germany**? | Embed Parler + **Host Context** templates (chapter **19**) plus explicit **`hierarchyNodeId`** / **`hierarchyNodeName`** tool args, and **five overridable services** on the Thing that backs hierarchy (see chapter 9). |

**Analysis habit:** scope by **hierarchy** first when the user names a region or site, then run **taxonomy-scoped** listing (`query_entities_by_taxonomy`, intersect, and related tools) inside that scope.

## Recommended iteration order

We teach—and ship—in roughly this order. Each step removes a predictable failure mode.

### Step A — Taxonomy: identity resolution

**Problem:** The user says a **display label** or internal short name (for example a line nickname); the model does not know the **canonical Thing `name`**.

**Fix:** Author **`/taxonomies/identity-types.json`** so resolver tools can map labels to Things. Reload repository-backed configuration on the **`AIAgent`** Thing.

**Outcome:** Property and service questions on a resolved Thing become reachable without the model guessing **`name`**.

### Step B — Taxonomy: asset types

**Problem:** The user asks in business language (“how many **stacking robots**?”) but ThingWorx types are expressed as **ThingShapes** / **ThingTemplates**.

**Fix:** Author **`/taxonomies/asset-types.json`** (canonical labels, aliases, mapping to **ThingShape** / **ThingTemplate**). Import samples into the configuration repository, reload.

**Outcome:** `query_entities_by_taxonomy` and related flows have a stable notion of **asset type**.

### Step C — Hierarchy

**Problem:** The user compares **regions** or sites (“USA vs Germany”). Listing *all* robots globally is wrong or too large.

**Fix:** Implement the **five hierarchy network services** on the **`AgentThing`** (or the designated facade Thing). Embed Parler in the mashup and bind **`HostScopeJson`** with **`key + context`** (chapter **[19 — embed Parler + Host Context](./19-embed-parler-in-mashup.md)**) so the agent sees page scope in the rendered prompt, and teach the model to pass explicit **`hierarchyNodeId`** for page-selected system node ids, **`hierarchyNodeName`** for user-entered labels, or **`intersectThingNames`** for exact bounded Thing lists — the server does **not** auto-inject mashup scope into tools. Reload.

**Outcome:** Tools can **resolve** and **expand** hierarchy nodes and **intersect** taxonomy query results with a bounded Thing set.

### Step D — Atomic tools (extended tools)

**Problem:** Domain services already exist on Things, but their **parameter shapes** do not match what the LLM tool layer accepts (for example **INFOTABLE** without **`dataShape`**, non-standard date parameter names, **STRING** instead of **`THINGNAME`** for Thing targets).

**Fix:** Register **`/tools/extended_tools.json`** entries that point at **real** `serviceName` / `entityName` targets. When needed, add **thin wrapper services** on a Thing you control so the **outer** signature matches Parler’s extended-tool rules. Reload.

**Outcome:** The agent gains **fast, single-call** capabilities for utilization (or any domain) without rewriting core Java for every service.

### Step E — First skill from built-ins

**Problem:** The model needs **procedural guidance** (checklist, preferred tool order, domain wording) for repeatable multi-step questions.

**Fix:** Add the first **`/skills/<SkillId>/SKILL.md`** file using **built-in tools only**. The first skill should grow
out of a task students already solved by hand with several built-in calls.

**Outcome:** Students understand the core idea: a skill is not a service. It is repository-backed procedure text that
makes a repeated tool route easier and more stable.

### Step F — App services as extended tools and wrappers

**Problem:** Domain services already exist in the app, but they were often designed for Mashups: `INFOTABLE` inputs,
selection flags, broad services, or names that describe implementation details rather than user intent.

**Fix:** Register app services in **`/tools/extended_tools.json`**. Add wrapper services when the LLM-facing shape should
be simpler than the original ThingWorx service shape.

**Outcome:** Parler can use application-specific capabilities without Java extension changes, while wrapper services keep
fragile app contracts away from the model.

### Step G — LLM-friendly interface design

**Problem:** Exposing every existing service one-to-one may work, but it can leave the model managing internal service
pipelines and typed table handoffs.

**Fix:** Derive a smaller semantic service surface from user intents. For the SCPA utilization track, this means using
the seven existing services to reason toward roughly four LLM-facing operations.

**Outcome:** Better tool names, simpler schemas, answer-ready outputs, and fewer opportunities for the model to confuse
"empty selection" with "all machines".

### Step H — Playbooks

**Problem:** Some workflows are already **stable graphs of steps**—in playbook terms, **DAGs** (**directed acyclic graphs**): dependencies are fixed and the sequence does not need to be re-invented on every user message. Letting the LLM plan **each micro-step** on every turn adds **round-trip latency** and **token** cost (one model decision per transition) without changing the underlying workflow. When the shape is stable, it is more efficient to **encode that DAG once** in a registered playbook and let the **runtime** execute it.

**Fix:** Register a **playbook** (for example the workshop **`cross_asset_pair_health`** playbook, or a project-specific playbook such as **`cross_region_health`**) and invoke it via the **`start_playbook`** tool. The **runtime** executes the DAG; the model mainly writes the **final natural-language answer** over a **compact evidence ledger**.

**Outcome:** Fewer LLM rounds for the same business question, with visible **task state** progress where supported.

### Step I — Evidence grounding and hardening

**Problem:** Even with skills and tools, final answers can vary. Extended tools and **`invoke_service`** can also be too
powerful; models can mis-invoke or expose shape details.

**Fix:** Add explicit evidence rules to skills, tighten **`whenToUse`**, use **`THINGNAME` preflight** behavior, use
**`/policies/invoke_service.json`** for allow-listed read paths, and add wrapper services that narrow arguments.

**Outcome:** Safer defaults and more trustworthy answers without removing capability.

## How this maps to the hands-on chapters

- **7–9** — Taxonomy and **hierarchy** configuration.
- **10** — Exercise **built-in** tools (**`query_alert_summary`**, **`query_alert_history`**) with and without **scope + asset type** so students see the default tool surface before adding Java-side services.
- **11** — Compose **built-in** tools into the first small **skill**.
- **12** — **Playbooks** (`start_playbook`) for deterministic multi-step runs.
- **13** — **`extended_tools.json`** and **wrapper** patterns (utilization and similar).
- **14** — **Policies** and HITL for generic service invocation.
- **15** — Optional extended-tool skills plus **evidence-grounded** answer rules.
- **16** — **LLM-friendly** application interfaces that narrow what the model must sequence.
- **17** — Outlook and governance.
