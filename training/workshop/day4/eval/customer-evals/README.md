# Customer eval pack template

This directory is the starting point for a customer App-specific eval pack.

The sample YAML files are intentionally small. Replace the sample Thing names, asset types, properties, and wrapper
tool names with values from the customer App.

Recommended progression:

1. Start with `smoke.yaml` and make the environment checks pass.
2. Add real user labels to `identity.yaml`.
3. Add asset-class language to `asset-types.yaml`.
4. Add the top business workflows to `workflows.yaml`.
5. Add wrapper-service checks to `extended-tools.yaml`.
6. Add empty / missing / invalid / protected behavior to `errors.yaml`.
7. Add stable DAG workflows to `playbooks.yaml`.

Use `fixtures.md` to record every data assumption.
