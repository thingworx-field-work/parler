# Eval pack: `cross_asset_pair_health`

Source Skill: **`asset_pair_health`** (`dev_data/scpa_utilization/skills/asset_pair_health/SKILL.md` — user-maintained).

Generated Playbook: **`cross_asset_pair_health`** — golden converter output at
[`../playbook-converter/examples/asset_pair_health/`](../playbook-converter/examples/asset_pair_health/).

Conversion report: `conversion-report.json` in that directory (`schema: parler-playbook-conversion-report-v1`).

## Source prompts

From [`../evals/cross_asset_pair_health_v1b.yaml`](../evals/cross_asset_pair_health_v1b.yaml):

| Case id | Prompt pattern |
| --- | --- |
| `cross_asset_pair_jetdryer_nl` | NL start via `start_playbook` — Jet Dryer pair, 24h, alerts + trends |
| `cross_asset_pair_jetdryer_slash` | Structured `/cross_asset_pair_health {assetType, assetIdentifierA, assetIdentifierB, timeWindow}` |
| `asset_pair_health_skill_baseline` | Skill baseline `/asset_pair_health …` (pre-playbook reference) |

Suggested smoke (converter report): compare ORD Contacting 02 vs ORD Contacting 01 over 24h.

## Expected input extraction

| Input | Required | Example |
| --- | --- | --- |
| `assetIdentifierA` | yes | `ORD JetDryer 02` |
| `assetIdentifierB` | yes | `AC JetDryer 01` (platform `PTCDisplayName`; extra spaces in compound tokens fail `resolve_thing` `equals`) |
| `assetType` | no | `Jet Dryer` (taxonomy hint) |
| `timeWindow` | no | `24h` → trend `relativeDuration` |

## Expected node / tool path

High-level graph (golden `playbook.json`):

1. `pick_taxonomy_row` → optional asset type
2. `resolve_thing` ×2 → `normalize_resolved_thing` ×2 → `flatten_pair_assets`
3. `fan_out` → `query_alert_summary` per asset
4. `group_alerts_by_source_property` → `select_primary_problem_property` → `union_property_names`
5. `fan_out` → `get_property_values` per asset
6. `trend_targets` → `fan_out` → `query_property_history` → `trend_summary`
7. `summarize_asset_pair_health` → `llm_summary` (`final_summary`)

Tools (playbookSafe): `resolve_thing`, `query_alert_summary`, `get_property_values`, `query_property_history`.

## Expected final-answer evidence

- Resolved asset names in pair summary evidence
- Alert grouping by `sourceProperty`
- Current property values when readable
- Trend min/max/mean when history returned
- Explicit evidence gaps when alerts/history missing (§8.2)

## Expected gaps / limitations

- No dedicated `compare_series` — trend compare is generic summary
- Trend skipped when no alert-driven primary property
- Protected values → gap note, not silent “normal”

## Live-debug collection

Run:

```bash
uv run parler-collect-live --window 30m --conversation-id <id> -o logs
```

Inspect bundle (see [`../live-diagnostics.md`](../live-diagnostics.md) § Playbook diagnosis):

| Symptom | Bundle fields |
| --- | --- |
| Version mismatch | `agent-status.json` → `playbookRuntime.agentVersion` vs conversion report `requiredAgentVersion` |
| Stale playbook file | `repository-files/` vs loaded fingerprint |
| Validator failure | `playbookDocumentValidations[]`, snapshot `playbookRuntime` |
| Runtime node failure | `lastPlaybookRun` / ApplicationLog playbook runner lines |
| Missing evidence | `lastPlaybookRun` node outcomes, stream `llm_summary` vs evidence |
| Model answer issue | `AgentMessageStream` final row vs playbook evidence refs |

## Regression command

```bash
uv run agent-eval --suite docs/agent/evals/cross_asset_pair_health_v1b.yaml --agent-matrix env --agent-filter gpt_5_5
```

## Author checklist

- [ ] Install golden or converter output at `/playbooks/cross_asset_pair_health/playbook.json`
- [ ] `RefreshPromptContextCache`
- [ ] `ValidatePlaybookDocument` → `valid`
- [ ] Smoke prompt returns alert/trend evidence or honest gaps
- [ ] Collect a bundle for postmortem — see [`../live-diagnostics.md`](../live-diagnostics.md) Recipe 5b
