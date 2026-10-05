# Tags (model tags) in the Agent tool chain

## 1. Behavior by path

| Path | Platform tag filtering? | Extension behavior today |
|------|----------------------|--------------|
| **`list_entities_by_type`** (`EntityServices.GetEntityList*`) | Yes: `tags` (`TagCollection`); **empty set = no filter** (`TagCollection.matches` returns true for size 0) | Always passes an **empty `TagCollection`** — equivalent to no tag filter |
| **`query_entities`** (`QueryImplementingThingsOptimized*` etc.) | Yes: service param **`tags`** (`TAGS`, aspect `ModelTags`) | Supports optional **`modelTags`**; when omitted, passes an **empty set** (no filter) |
| **`invoke_service`** | Depends on service inputs | **`InvokeServiceExecutor`** parses `TAGS` inputs into a `TagCollection` per the convention below (§3) |
| **`query_entities` → `query` JSON** | Yes: **`TAGGED` / `NOTTAGGED`** etc. in `query.filters` (field often `tags`) | Does not use `TagCollection` param; uses **`Query` object** — **different path** from service-level `tags` |
| **`spotlight_search`** | Platform may have model-tag inputs | Tool side **`entityTypes` etc. still reserved** — tags not wired |

## 2. Does this materially increase LLM error rate?

- **Moderate-to-low risk** if:  
  - **Single convention, documented**: one JSON shape only (see §3);  
  - **Optional**: omitting = same as today (no filter);  
  - **Clear errors on validation failure** (e.g. `INVALID_TAGS` + example), no silent wrong types.  
- **Still elevated where**:  
  - The model confuses **service param `tags`** vs **tag filters inside `query`** — add one line in tool description: **“Entity-level tag filtering: `modelTags` / `tags`; property/row conditions: `query`.”**  
  - `vocabulary` / `vocabularyTerm` typos or Composer mismatch → **0 rows** (semantic, not a crash).

## 3. Recommended LLM ↔ Agent wire format

**Recommended (only supported shape): JSON array** of objects; keys match platform / `TagCollection.fromJSON`:

```json
"modelTags": [
  { "vocabulary": "Applications", "vocabularyTerm": "MyApp" },
  { "vocabulary": "Plants", "vocabularyTerm": "Sedona" }
]
```

- **`list_entities_by_type`** param name: **`tags`** (aligned with `GetEntityList`).  
- **`query_entities`** param name: **`modelTags`** (avoids clashing with `tags` on result rows).  

**Secondary (`invoke_service` `parameters`):** same shape; if a param `baseType` is `TAGS`, value is **array** or **string** parseable by platform `TagCollection.fromString` (multi-tag platform list delimiter — generally not recommended for LLMs).

## 4. Implementation (code)

- Parse/convert: **`TagJsonCodec`** (`com.thingworx.things.agent.tools`)  
- **`InvokeServiceExecutor.jsonToPrimitive`**: `TAGS` branch  
- **`ListEntitiesByTypeExecutor`** / **`QueryEntitiesExecutor`**: optional tag params  
