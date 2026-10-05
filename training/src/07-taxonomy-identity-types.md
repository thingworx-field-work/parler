# Taxonomy: `identity-types.json`

## Symptom

Prompt example:

> For Thing **`ORD Contacting 01`**, what is the current value of property currentDraw?

The agent **spends many tool rounds** and still cannot read the value.

```
For Thing ORD Contacting 01, what is the current value of property currentDraw?
```



<img src="./__images__//image-20260531142909734.png" alt="image-20260531142909734" style="zoom:50%;" />



<img src="./__images__//image-20260531142939952.png" alt="image-20260531142939952" style="zoom:50%;" />



<img src="./__images__//image-20260531143026988.png" alt="image-20260531143026988" style="zoom:50%;" />

## Diagnosis

The model **does not know** how the **short display label** maps to the **canonical ThingWorx `name`**. Resolver tools return **`IDENTITY_RESOLUTION_REQUIRED`**, **`NOT_FOUND`**, or ambiguous candidates until taxonomy exists.

## Fix

Author **`/taxonomies/identity-types.json`** on the **`configurationRepository`** FileRepository. The file is a JSON array of identity rules: each rule names the **ThingTemplate** scope, the properties used for matching, and the critical properties to return. Maintainers can cross-check the normative resolver contract in **[`CONTRACTS/TAXONOMY_RESOLVER.md`](../../CONTRACTS/TAXONOMY_RESOLVER.md)** and the human-facing taxonomy notes in **[`docs/agent/AGENT-TAXONOMY.md`](../../docs/agent/AGENT-TAXONOMY.md)** in this repository.



```JSON
[
  {
    "identityProperties": [
      {
        "name": "name",
        "match": "suffix"
      },
      {
        "name": "PTCDisplayName",
        "match": "equals"
      },
      {
        "name": "PTCSerialNumber",
        "match": "equals"
      }
    ],
    "baseThingTemplate": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": []
  }
]

```



<img src="./__images__//image-20260530222208367.png" alt="image-20260530222208367" style="zoom:50%;" />



After you upload the `identity-types.json` file, please go to your `AIAgent` Thing and invoke the `RefreshTaxonomyCache` service to reload the file from the file system.

<img src="./__images__//image-20260531143216626.png" alt="image-20260531143216626" style="zoom:50%;" />

**`Caution`**: Before you run the same prompt again after you load the taxonomy file, you should clean up the chat history. Otherwise, the LLM may be confused by the previous answer before it checks for any newly available tools.

What you can do is to click the `cut-off` icon under the final response.

<img src="./__images__//image-20260531143250100.png" alt="image-20260531143250100" style="zoom:50%;" />

Once you confirm, the history will not go to the LLM again. But don't worry, the history is still persisted in the ThingWorx system unless you manually delete it.

<img src="./__images__//image-20260531143314178.png" alt="image-20260531143314178" style="zoom:50%;" />



You can submit the same prompt again and see how the LLM responds. In the progress window, you can see the LLM can successfully resolve the Thing now and therefore it can discover what properties are available.

<img src="./__images__//image-20260531143355812.png" alt="image-20260531143355812" style="zoom:50%;" />



With the proper entity name and property definition, the LLM can grab the right information and respond to you.

<img src="./__images__//image-20260531143427432.png" alt="image-20260531143427432" style="zoom:50%;" />





## Teaching point

**Identity taxonomy** is about **unique Thing resolution** from messy human labels—not about counting assets by class (that is **`asset-types.json`**, next chapter).
