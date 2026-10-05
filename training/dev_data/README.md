# Course runtime assets

These assets are the ThingWorx overlay used by the training course. The current target is
`parler-agent` **0.1.224** and `parler-ui-widget` **0.1.92**.

## Default import

Run `uv run import-dev --apply --import_control import_training` from the repository root after installing the target extensions and the
solution dependencies named by the project exports. The import is deliberately an overlay: it does not install
extensions, create an ApplicationKey, load test data, or publish anything.

The default import uses:

- `Parler_SCPA_Demo.xml` — Mashup project synchronized with Parler's canonical demo export;
- `Parler_SCPA_Guidance.xml` — Agent/provider/repository project with every Agent bound to
  `AIArtifactRepository`;
- `Things_AIArtifactRepository.xml` — the mandatory dedicated Artifact Cache FileRepository;
- `Things_ShiftHelper.xml` and `tools/extended_tools.json` — the course's read-only shift-window example;
- the course-specific Acme taxonomy and invoke-service policy.

`Parler_SCPA_Guidance_CellFeb.xml` is an alternate CellFab/utilization project export and is not loaded by the
default import. It is kept synchronized to the same Agent/widget dependency baseline, Artifact Cache setting,
and credential policy.

## Intentional differences from `dev_data/scpa_utilization`

Do not replace this directory byte-for-byte with the final sample in [`../../dev_data/scpa_utilization`](../../dev_data/scpa_utilization):

- `taxonomies/asset-types.json` intentionally teaches the Acme Filter/Mixing Tank scenario;
- `extended_tools.json` intentionally teaches `resolve_shift_windows_for_thing`, not the utilization Service set;
- skills and Playbooks are introduced separately by the course/workshop and are not duplicated here.

## Credential rule

All Provider `apiKey` values in committed XML are empty. Configure credentials in ThingWorx after import through
the normal protected configuration surface. Never add DEV keys, provisioning keys, Provider keys, tokens, or
ApplicationKey entities to these files.
