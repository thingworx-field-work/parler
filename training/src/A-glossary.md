# Glossary

| Term | Meaning |
|------|---------|
| **`AIAgent`** | ThingTemplate for the Parler agent; Java class **`AgentThing`**. |
| **`AgentThing`** | Java implementation backing each **`AIAgent`** instance. |
| **`AgentLoop`** | Java agentic loop: LLM response → optional **`tool_call`**s → append tool results → repeat until final content or limits. |
| **Parler** | ThingWorx extension that runs an AI agent inside the platform, answers through tools grounded in real platform data, and can be customized with taxonomy, tools, skills, and playbooks. |
| **`ParlerGateway`** | ThingTemplate for AlwaysOn gateway; validates thread ownership and forwards **`SubmitUserPrompt`**. |
| **`AlwaysOn`** | ThingWorx persistent channel used for streaming Parler **wire JSON**. |
| **`configurationRepository`** | **`THINGNAME`** of a **FileRepository** holding **`/taxonomies`**, **`/skills`**, **`/playbooks`**, **`/tools/extended_tools.json`**, **`/policies`**, etc. |
| **`exportFileRepository`** | **`THINGNAME`** of a **FileRepository** for export outputs (`AgentSettings.exportFileRepository`). |
| **`identity-types.json`** | Taxonomy file for **identity resolution** (labels → canonical Thing **`name`**). |
| **`asset-types.json`** | Taxonomy file mapping **asset type** labels to **ThingShape** / **ThingTemplate**. |
| **`extended_tools.json`** | Declares **repository-backed extended tools** targeting real **`serviceName`** on **`entityName`**. |
| **Skill** | **`/skills/<SkillId>/SKILL.md`** — procedural Markdown guidance for the model. |
| **Playbook** | Registered DAG executed by runtime; often entered via **`start_playbook`** tool. |
| **`cross_region_health`** | Reference playbook id (V1a). |
| **`region_health`** | Reference skill id used as playbook baseline in evals. |
| **`cross_asset_pair_health`** | Workshop day3 playbook id used in the asset-pair health story. |
| **`asset_pair_health`** | Workshop day2/day4 skill id used in the asset-pair health story. |
| **`HierarchyNode_DS`** | DataShape for hierarchy node rows (**`id`**, **`name`**). |
| **`EntityList`** | DataShape for **`name`** + **`description`** rows (asset lists under a node). |
| **Five hierarchy services** | **`GetFlattenNameDescription`**, **`ResolveNetworkID`**, **`GetRootNode`**, **`GetAssetList`**, **`GetChildNodes`**. Maintainers can cross-check **[`docs/architecture/hierarchy-network-services.md`](../../docs/architecture/hierarchy-network-services.md)** in this repository. |
| **`hostContext`** | Optional UTF-8 JSON uplink on **`Chat`** / **`ParlerStreamToRemoteThing`** / **`SubmitUserPrompt`**: **`key`** selects a **`host-contexts/*.json`** template in the configuration repository; **`context`** carries structured mashup state. Registered templates render as an ephemeral system prompt fragment. Unregistered parseable keys on **parler-agent 0.1.206+** use generic fenced-JSON fallback (`UNREGISTERED_GENERIC_FALLBACK`). Classpath built-in templates are **not** used at runtime. Per-user-turn Host Context metadata persists from **0.1.193+** (see chapter **19**). |
| **`THINGNAME`** | ThingWorx base type for parameters that must hold a **canonical Thing `name`**. |
| **History overlay chart** | Built-in **`build_history_overlay_chart`** (**parler-agent 0.1.205+**) overlays **2–6** live property-history series on one chart via **`xAxisMode`** (`absolute_time`, `elapsed_time`, `normalized_time`). Replaces retired **`build_period_over_period_chart`** / **`build_multi_series_history_chart`**. See appendix **E** and Parler **`docs/agent/history-overlay-chart.md`**. |
| **Evidence-grounded answer** | Final text whose factual claims trace to **tool results** in the same turn, not hallucinated numbers. |

## Additional Glossary

| Term       | Meaning |
| ---------- | ------- |
| **`SCPA`** | **Smart Connected Products Accelerator** — a PTC ThingWorx application/solution for remote monitoring of connected assets (role-based security, flexible asset hierarchy). The learning environment used throughout this curriculum; see chapter 3. |
