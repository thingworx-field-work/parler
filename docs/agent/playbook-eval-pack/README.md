# Playbook eval packs

Normative acceptance: [`../playbook-customer-readiness.md`](../playbook-customer-readiness.md) §11.

Eval packs document how to test and diagnose a converted Playbook before customer use. They complement:

- [`../evals/playbook-regression.md`](../evals/playbook-regression.md) — `agent-eval` suite commands
- [`../live-diagnostics.md`](../live-diagnostics.md) — `parler-collect-live` playbook diagnosis
- [`../playbook-converter/`](../playbook-converter/) — golden conversion artifacts

## Packs

| Playbook id | Source Skill | Eval pack |
| --- | --- | --- |
| `cross_asset_pair_health` | `asset_pair_health` | [`cross_asset_pair_health.md`](cross_asset_pair_health.md) |

## Adding a pack

After converter output lands, copy §11.2 fields from the design doc into `{playbookId}.md` and link here.
