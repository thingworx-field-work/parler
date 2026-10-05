# Conversion workflow

1. **Load source Skill** — `get_agent_skill` or user-provided `SKILL.md` path.
2. **Runtime snapshot** — `GetAgentRuntimeSnapshot({ "includePlaybooks": true })`.
3. **Map Skill steps** → Playbook nodes (`tool_call`, `derive`, `fan_out`, `llm_summary`).
4. **Compute `requiredAgentVersion`** — max applicable row from `feature-version-table.json`.
5. **Draft `playbook.json`** — single `llm_summary` final node; evidence refs on final node.
6. **`ValidatePlaybookDocument`** — `{ "playbookJson": "<draft>" }`.
7. **Build conversion report** — schema `parler-playbook-conversion-report-v1`.
8. **Author actions** — install path, cache refresh, smoke prompt; version note if needed.

## Honest reporting

| Bucket | Use when |
| --- | --- |
| `converted` | Skill step has a faithful Playbook node path |
| `partiallyConverted` | Step approximated (e.g. generic trend summary without `compare_series`) |
| `notConverted` | No safe Playbook path (extended tool not playbookSafe, app service only) |

## Output files (author-owned)

- `/playbooks/<playbookId>/playbook.json`
- Conversion report JSON (workshop artifact; not stored by Java validator)
