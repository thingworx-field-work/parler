# Day 4 eval pack

`customer-evals/` holds the recommended shape of an application-specific eval pack (Appendix L). It runs on the
product's own live eval runner, `uv run agent-eval` (`test_scripts/agent_eval.py`), from the repository root:

```bash
cp .env.example .env
# set DEV_SERVER, DEV_KEY and the AGENT_EVAL_AGENT_* names for your AgentThings

uv run agent-eval --suite training/workshop/day4/eval/customer-evals/smoke.yaml --agent-matrix env
```

The runner calls `AgentThing.Chat(...)` through ThingWorx REST, then reads `AgentMessageStream` to evaluate final
answers, tool calls, tool arguments and tool results. It is not a UI test. The runner and the product's sample
suites are described in [`docs/agent/agent-evaluation-harness.md`](../../../../docs/agent/agent-evaluation-harness.md);
the sample suites live in [`docs/agent/evals/`](../../../../docs/agent/evals/).

## Useful commands

```bash
S=training/workshop/day4/eval/customer-evals
uv run agent-eval --suite $S/smoke.yaml --agent-matrix env
uv run agent-eval --suite $S/workflows.yaml --agent-matrix env --agent-filter gpt_5_4
uv run agent-eval --suite $S/workflows.yaml --agent-matrix env --case asset_current_status
uv run agent-eval --suite $S/workflows.yaml --agent-matrix env --reset-mode stable_clear
uv run agent-eval --suite $S/workflows.yaml --agent-matrix env --out-dir $S/reports
```

Reports are written to `tmp/agent-eval/<timestamp>/` by default, or under the `--out-dir` base when supplied.
