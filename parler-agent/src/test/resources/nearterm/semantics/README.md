# U3S semantic-profile fixtures (M0)

Canonical path in a configuration FileRepository: `/semantics/semantic-profile.json`
(`ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON`).

Schema id: `parler-semantic-profile-v1` (unknown fields rejected).

| File | Purpose |
|---|---|
| `semantic-profile.template.json` | Minimal annotated shape (see comments in this README) |
| `stacking-robot.example.json` | Realistic App example (Property + Service roles) |
| `invalid-unknown-field.json` | Rejects unknown JSON fields |
| `invalid-unit-mismatch.json` | Unit/dimension disagreement |
| `ambiguous-alias.json` | Alias owned by two roles |
| `invalid-service-target.json` | SERVICE binding missing `resultField` |
| `invalid-schema.json` | Wrong schema id |

## Required root fields

- `schema` — must be `parler-semantic-profile-v1`
- `profileId` — App-authored stable id
- `version` — App-authored version string
- `assetTypes` — map of taxonomy `assetTypeKey` → `{ "propertyRoles": { ... } }`

## Per-role fields

- `binding.kind` — `PROPERTY` (`propertyName`) or `SERVICE` (`thingName`, `serviceName`, `resultField`)
- `unit`, `dimension`, `grain` — closed vocabulary (`SemanticProfileVocabulary`)
- `expectedCadence` — optional ISO-8601 duration (`PT5S`)
- `aliases` — optional string array (lookup aids only)

Caps: 64 asset types, 128 roles/asset type, 8 aliases/role, 256 KiB UTF-8.

## M2 source handoff (App example)

`stacking-robot.example.json` is the end-to-end App fixture for PROPERTY (`Wrst1` →
`operating_temperature`) and SERVICE (`GetEnergyPerCycle` → `energy_per_cycle`) roles.

Runtime path (U3S M2):

1. Profile loads into `PromptContextCacheSnapshot.getSemanticProfile()` (M1).
2. `query_numeric_property_history` stores a tabular cache via
   `SemanticSourceHandoff.attachPropertyProvenance` only when (a) the live Thing's taxonomy
   `assetTypeKey` is uniquely proven (`TaxonomyAssetTypeProof`), (b) a PROPERTY role under
   that key binds the queried `propertyName` (e.g. `Wrst1`), and (c) Property preflight
   passes. Null Thing, unproven type, or cross-type name collision → no SP5 provenance.
3. SP5 fields land on `SourceDescriptor` (`propertyRoleRef`, `unitRef`, `grainRef`,
   `cadenceRef`, profile id/version/digest) and are preserved across derive/rebuild.
4. `SemanticBindingPreflight` rejects PASSWORD / missing Property or Service targets at
   invocation time; SERVICE bindings also require the live Thing name to equal the SP3
   `thingName`. Profile validity never grants permission.

## M3 closeout

- App authoring + operator walkthrough (incl. delete-file→stale retention):
  `docs/agent/nearterm/semantics-evidence-foundation-app-authoring-walkthrough.md`
- Taxonomy join regression: `SemanticProfileTaxonomyJoinRegressionTest` (cellfab-v3 exact keys)

Note: unit fixtures here may use the simplified key `StackingRobot`. Production profiles MUST
use the site taxonomy’s literal `assetTypeKey` (e.g. cellfab `"Stacking Robot"`).
