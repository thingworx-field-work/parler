# Skill-to-Playbook converter

Status: **implemented** (skill templates and golden examples). Normative design: [`../playbook-customer-readiness.md`](../playbook-customer-readiness.md) §10.

## Staging (user-owned `configurationRepository`)

Copy this tree into the Agent FileRepository (do **not** commit secrets to `dev_data/`):

```text
/skills/skill_to_playbook_converter/SKILL.md          ← from ./SKILL.md
/skills/skill_to_playbook_converter/knowledge/        ← from ./knowledge/
```

Refresh: **`RefreshPromptContextCache`**, then invoke **`/skill_to_playbook_converter`** or **`get_agent_skill`**.

## Workflow

1. Load source **`SKILL.md`** (e.g. `dev_data/scpa_utilization/skills/asset_pair_health/SKILL.md` — user-maintained).
2. Call **`GetAgentRuntimeSnapshot`** with `includePlaybooks: true`.
3. Draft `playbook.json` using only ops/tools present in the snapshot.
4. Call **`ValidatePlaybookDocument`** with the draft JSON.
5. Emit **`parler-playbook-conversion-report-v1`** (see `knowledge/report-schema.json`).
6. Author stages `/playbooks/<id>/playbook.json` manually.

## Golden examples (CI + workshop)

| Path | Purpose |
| --- | --- |
| `examples/asset_pair_health/` | Primary golden — `playbook.json` + `conversion-report.json` (install at `/playbooks/cross_asset_pair_health/`) |
| `examples/region_health/` | Honest partial conversion report |
| `examples/version_warning/` | §10.6.4 version-mismatch `authorActions` |

Classpath mirrors: `parler-agent/src/test/resources/playbook-converter-fixture/`.

## Tests

```bash
cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true \
  --tests 'com.thingworx.things.agent.playbook.PlaybookConverterFixtureTest'
```
