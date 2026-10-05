# Eval fixtures — taxonomy

## `identity-types-minimal.json`

Version **`2`** sample used by **`IdentityTypesJsonParser`** unit tests and operator eval setup (`docs/agent/evals/taxonomy_v2.yaml`). Mirrors **`/taxonomies/identity-types.json`** layout (`entities[]` / `types[]`), including:

- two **`shape_as_type`** rows that share the **`robot`** alias (ambiguous resolution case)
- one **`shape_as_type`** row with **`queryParent`** ThingTemplate **`PTC.MfgModel.DefaultWorkunit_TT`** and **`name`/`description`** identity fields (template-first QIT + shape membership path)
- one **`template_as_type`** row with ThingTemplate **`membership.entityName`** **`PTC.MfgModel.DefaultWorkunit_TT`** (same template as the **`queryParent`** example so typical SCPA-style dev hosts resolve parents on live refresh)

**`dev_data/taxonomies/identity-types.json`** (and other operator-deployed trees) **may differ** from this fixture — eval fixtures target schema + regression cases; dev_data reflects a specific deployment.

After changing this file, sync **`parler-agent/src/test/resources/taxonomy/identity-types-minimal.json`** (same bytes).
