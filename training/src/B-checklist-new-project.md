# Delivery checklist (SCPA utilization track)

Use this as a **module gate** when onboarding a new project team to Parler on ThingWorx.

## Environment

- [ ] A SCPA instance based on template:`ThingWorx 10.0 with RSE ACE SCPA Build 202 v1.1` on PTC cloud.

- [ ] **`parler-agent`** ZIP + **`parler-ui-widget`** ZIP (built from this repository or provided by your administrator) imported; **`ParlerAgentBasic.xml`** (or successor) applied.

  

## Repositories

- [ ] **`configurationRepository`** FileRepository created; **`exportFileRepository`** separate Thing created; both bound on **`AIAgent`** **(screenshot)**.

## Parler layers (iteration order)

- [ ] **`/taxonomies/identity-types.json`** present.
- [ ] **`/taxonomies/asset-types.json`** present.
- [ ] Five hierarchy services implemented on **`AIAgent`**; mashup embeds Parler and binds **`HostScopeJson`** (`key + context`, chapter **19**) and scoped labs pass explicit **`hierarchyNodeId`** for page-selected node ids, **`hierarchyNodeName`** for typed labels, or **`intersectThingNames`** for exact lists (no server-side mashup scope inject).
- [ ] **`/tools/extended_tools.json`** lists utilization tools; **wrappers** exist where **`INFOTABLE`**, **date pairs**, or **`THINGNAME`** rules required it.
- [ ] At least **two** **`/skills/<id>/SKILL.md`** files for utilization scenarios; eval notes show **evidence-grounded** answers.
- [ ] Optional: project playbook path demonstrated against its skill baseline, for example the workshop **`cross_asset_pair_health`** playbook vs **`asset_pair_health`** skill.

## Governance

- [ ] **`/policies/invoke_service.json`** reviewed if **`invoke_service`** is exposed beyond read-only demos.
- [ ] Version row recorded: **ThingWorx**, **`parler-agent`**, **`parler-ui-widget`**.
