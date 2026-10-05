# Playbook Regression Eval Commands

See also: [`../playbook-eval-pack/`](../playbook-eval-pack/) for per-Playbook eval packs.

This is an operator run list, not a suite-composition file. The current `agent-eval` runner does not implement `includes:` or multi-suite manifests.

Run the existing focused Playbook suites directly:

```bash
uv run agent-eval --suite docs/agent/evals/cross_region_health_v1a.yaml --agent-filter gpt_5_5
uv run agent-eval --suite docs/agent/evals/cross_asset_pair_health_v1b.yaml --agent-filter gpt_5_5
```

Both suites map `gpt_5_5` to `SCPA_Demo_Agent`; add `--agent-matrix env` with `AGENT_EVAL_AGENT_GPT_5_5` for another
AgentThing name.

Add new no-alert fixture cases to `cross_asset_pair_health_v1b.yaml`, not to a duplicate Playbook suite.
