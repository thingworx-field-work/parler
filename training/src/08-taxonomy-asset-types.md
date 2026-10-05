# Taxonomy: `asset-types.json`

## Symptom

Prompt example:

> how many **`Cutting`** assets do you have?

```
how many Cutting assets do you have?
```



The agent cannot answer because **“Cutting”** is a **business label**, while ThingWorx instances are typed by **ThingShapes** / **ThingTemplates** your solution actually uses.

From the screenshot, you can see that the LLM has identified "Cutting" as a potential asset type and it has tried to use `resolve_asset_type` to resolve it but failed.

<img src="./__images__//image-20260531143757183.png" alt="image-20260531143757183" style="zoom:50%;" />

So, at the end, it can't give you any meaningful response.

<img src="./__images__//image-20260531143858669.png" alt="image-20260531143858669" style="zoom:50%;" />



## The solution

The solution to address the asset type resolving issue is to define an `asset-types.json` file. The sample chunk is below, and you can expand it to cover all of your asset types. 

The asset type definition for the current SCPA solution has been provided.

```JSON
{
  "Stacking Robot": {
    "aliases": [
      "stacking robots",
      "stacker robot",
      "palletizing stacker",
      "cell stacker"
    ],
    "entityType": "ThingShape",
    "entityName": "PTCTDD.CellfabDataset.StackingRobot_TS",
    "criticalProperties": [
      "PTCDisplayName",
      "PTCMake",
      "PTCModel",
      "PTCSerialNumber"
    ]
  },
  "Sealing": {
    "aliases": [
      "sealer",
      "seal machine",
      "cell sealing",
      "sealing station"
    ],
    "entityType": "ThingShape",
    "entityName": "PTCTDD.CellfabDataset.Sealing_TS",
    "criticalProperties": [
      "PTCDisplayName",
      "PTCMake",
      "PTCModel",
      "PTCSerialNumber"
    ]
  },
  "Cutting": {
    "aliases": [
      "cutter",
      "cutting machine",
      "cell cutting",
      "cutting station"
    ],
    "entityType": "ThingShape",
    "entityName": "PTCTDD.CellfabDataset.Cutting_TS",
    "criticalProperties": [
      "PTCDisplayName",
      "PTCMake",
      "PTCModel",
      "PTCSerialNumber"
    ]
  }
}
```

This example includes the `Cutting` asset type because the later workshop prompts use Cutting assets. If your local
demo data uses a different business asset class, add the matching entry here before testing that prompt.

`criticalProperties` means **extra projected/context properties returned with each matched Thing**. It is not a
health-property list and does not say that a property is alerting. The four SCPA values above are identity/display
fields by this sample's convention. Another application may deliberately project operational context, so never
generalize that every future critical property is identity-only.

What you need to do is to upload the `asset-types.json` file to the same `taxonomies` folder as a sibling to `identity-types.json`. 

<img src="./__images__//image-20260531150122156.png" alt="image-20260531150122156" style="zoom:50%;" />



You have to call the service: `RefreshTaxonomyCache` on your `AIAgent` Thing to refresh the agent's memory.

<img src="./__images__//image-20260531150052332.png" alt="image-20260531150052332" style="zoom:50%;" />



And then you should click the `cut-off` icon on the previous message before you submit the same prompt again.

<img src="./__images__//image-20260531150248865.png" alt="image-20260531150248865" style="zoom:50%;" />

Now, you can see that the agent can pull out all 8 `Cutting` assets. 

<img src="./__images__//image-20260531150233740.png" alt="image-20260531150233740" style="zoom:50%;" />

**`Caution`**: The table will show no more than 5 rows.

## Quick review

Author **`/taxonomies/asset-types.json`**. It maps **canonical asset type labels** (and aliases) to **ThingShape** and/or **ThingTemplate** keys the runtime uses with **`resolve_asset_type`** and **`query_entities_by_taxonomy`**. The file lives beside **`identity-types.json`** in the **`/taxonomies/`** folder of the configured FileRepository. Maintainers can cross-check repository-layout details in **[`docs/agent/configuration-repository.md`](../../docs/agent/configuration-repository.md)** in this repository.

## SCPA note

SCPA’s physical types are often expressed as **ThingShapes**. Your class should ship a **small sample `asset-types.json`** that matches the **imported** SCPA shapes—students copy it into the **`configurationRepository`** **`/taxonomies/`** folder.



## Relationship to chapter 7

- **`identity-types.json`** — “this nickname is **that one Thing**.”
- **`asset-types.json`** — “this **class phrase** maps to **these** template/shape filters for **many** Things.”
- **`criticalProperties`** — “return these extra columns with the candidates”; it neither defines identity matching
  nor health semantics by itself.
