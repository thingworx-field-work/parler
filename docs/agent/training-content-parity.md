# Training content parity

The training course in [`training/`](../../training/) and the final SCPA utilization sample in
[`dev_data/scpa_utilization/`](../../dev_data/scpa_utilization/) describe the same application. This document states
which of them is authoritative for what and how drift between them is caught. The stage contracts are in
[`training-stage-configuration-contracts.md`](./training-stage-configuration-contracts.md).

## 1. Authority

| Source | Authoritative for |
| --- | --- |
| `dev_data/scpa_utilization/` | The final configuration: the four utilization tools, skills, Playbooks, policies, taxonomies and host contexts. |
| `training/workshop/day4/` | Nothing of its own for utilization: its tools, skills and Playbooks are copies of the final sample. |
| `training/workshop/day1`–`day3` | The intermediate stages the course teaches before the LLM-friendly interface. |
| `training/src/` | Course prose. Chapter 16 owns the derivation of the four tools and the upgraded skill, Playbook and eval examples. |
| `docs/agent/evals/utilization_v1.yaml` | The product's utilization eval suite on the final four tools. |

## 2. Course progression

| Day | Goal | Chapters | Payload | Utilization layer |
| --- | --- | --- | --- | --- |
| 1 | Setup, first prompts, identity and asset types | 1–8 | `workshop/day1/` taxonomies and policy | none |
| 2 | Built-in tools to a first skill | 9–11, appendix E | `workshop/day2/skills/asset_pair_health/` | none |
| 3 | Playbooks, extended tools, wrappers, policies | 12–14, chapter 15 first pass | `workshop/day3/playbooks/cross_asset_pair_health/`, utilization helper entity | seven underlying services as prose; optional labelled service-aligned exercise |
| 4 | Evidence grounding, LLM-friendly interface, evals, final utilization | 16, chapter 15 pointer | `workshop/day4/` final payloads and the eval pack | four tools only |

Rules:

1. Before chapter 16, prose may name the seven underlying ThingWorx services. Service-aligned model-facing tool names
   appear only in exercises labelled `pre-llm-friendly first pass`, never as the final upload target.
2. Chapter 13 teaches the `extended_tools.json` mechanics with a minimal manifest; the full four-tool manifest belongs
   to chapter 16.
3. Chapter 15 may show a first-pass utilization skill on the service-aligned surface when labelled, and ends with a
   pointer to chapter 16.
4. After chapter 16, skill checklists, Playbook `tool_call` nodes, eval `toolsCalledSubsequence` and the Day 4
   `extended_tools.json` use only the four final tool names.
5. A Day 3 exercise manifest must not be named `extended_tools.json` while a Day 4 final manifest exists.

## 3. Drift guard

`node scripts/check-training-content-parity.mjs` (also run by `node scripts/check-training-stage-contracts.mjs`) fails
when:

- a Day 4 utilization tool, skill or Playbook file differs from its `dev_data/scpa_utilization/` counterpart;
- any training or final-sample `extended_tools.json`, `playbook.json` or `SKILL.md` checklist fence does not parse;
- a retired service-aligned tool name (`utilization_records`, `utilization_aggregate_by_state`,
  `utilization_stats_for_aggregate`, …) appears in post-final course material outside the labelled first-pass files;
- the final sample manifest does not contain exactly the four final tools;
- a utilization eval suite names a retired tool.

When the final sample changes, copy the changed files into `training/workshop/day4/` in the same change and update
chapter 16 if the tool surface changed.
