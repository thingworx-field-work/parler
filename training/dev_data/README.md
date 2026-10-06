# Course runtime assets

These assets are the ThingWorx overlay used by the training course. The current target is
`parler-agent` **0.1.224** and `parler-ui-widget` **0.1.92**.

## Importing the overlay

Install the Parler extensions and `ParlerAgentBasic.xml` first ([chapter 4](../src/04-import-parler-extensions.md)),
together with the solution dependencies named by the project exports. Then, in Composer:

1. Import `Things_AIArtifactRepository.xml`, `Things_ShiftHelper.xml`, `Parler_SCPA_Demo.xml` and
   `Parler_SCPA_Guidance.xml` with **Import > From File > Entities**.
2. Upload `policies/invoke_service.json`, `taxonomies/identity-types.json`, `taxonomies/asset-types.json` and
   `tools/extended_tools.json` to the same folders in `ConfigurationRepository`.

The overlay does not install extensions, create an ApplicationKey, load test data, or publish anything. It contains:

- `Parler_SCPA_Demo.xml` — Mashup project synchronized with Parler's canonical demo export;
- `Parler_SCPA_Guidance.xml` — Agent/provider/repository project with every Agent bound to
  `AIArtifactRepository`;
- `Things_AIArtifactRepository.xml` — the mandatory dedicated Artifact Cache FileRepository;
- `Things_ShiftHelper.xml` and `tools/extended_tools.json` — the course's read-only shift-window example;
- the course-specific Acme taxonomy and invoke-service policy.

`Parler_SCPA_Guidance_CellFeb.xml` is an alternate CellFab/utilization project export and is not loaded by the
default import. It is kept synchronized to the same Agent/widget dependency baseline, Artifact Cache setting,
and credential policy.

## Intentional differences from `dev_data/sample_scpa_utilization_agent_configuration`

Do not replace this directory byte-for-byte with the final sample in [`../../dev_data/sample_scpa_utilization_agent_configuration`](../../dev_data/sample_scpa_utilization_agent_configuration):

- `taxonomies/asset-types.json` intentionally teaches the Acme Filter/Mixing Tank scenario;
- `extended_tools.json` intentionally teaches `resolve_shift_windows_for_thing`, not the utilization Service set;
- skills and Playbooks are introduced separately by the course/workshop and are not duplicated here.

## Credential rule

All Provider `apiKey` values in committed XML are empty. Configure credentials in ThingWorx after import through
the normal protected configuration surface. Never add DEV keys, provisioning keys, Provider keys, tokens, or
ApplicationKey entities to these files.
