# Outlook

## Growing an App after the course

- Expand **`extended_tools.json`** coverage until the high-value questions for your App map to **one or two** tool calls.
- Grow **`/skills/`** for recurring operator questions; add **eval YAML** so regressions are caught before extension import.
- Promote only **mature** skills to **playbooks** where the DAG is stable and evidence shape is known.

## Platform and contract discipline

Any change to **wire JSON**, **history hydration**, or **tool envelopes** must ride with **`CONTRACTS/*`** updates and **`CONTRACTS/CONTRACT_VERSION.md`**. Train contributors to treat [`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) as the negotiation surface between **`parler-ui`** and **`parler-agent`**. These are maintainer pointers into this repository; workshop participants do not need to open them.

## Operations

- Centralize **reload** and **validate** procedures (`ValidateAgentConfigurationRepository`, `GetAgentRuntimeSnapshot`—see [`docs/agent/configuration-repository.md`](../../docs/agent/configuration-repository.md)).
- Keep a **version matrix**: ThingWorx platform, **`parler-agent`** extension version, **`parler-ui-widget`** version.
