# Appendix: Taxonomy JSON syntax

This appendix is the **syntax reference** for the two taxonomy files Parler loads from the AgentThing
`configurationRepository`:

```text
/taxonomies/identity-types.json
/taxonomies/asset-types.json
```

Chapters 7 and 8 teach *why* and *when* to use each file. This appendix lists *every field and every accepted shape*,
including all variant forms and what the loader rejects. The source of truth is the Parler agent's taxonomy loader and
the taxonomy resolver contract; [`docs/agent/taxonomy.md`](../../docs/agent/taxonomy.md) in this repository is a maintainer design reference.
The examples here are taken from the workshop `day1/taxonomies/` files and the Parler test fixtures.

After uploading or editing either file, call **`RefreshTaxonomyCache`** on the AgentThing, then start a **fresh
conversation** before testing so an old failed turn does not steer the model. Use **`ValidateAgentConfigurationRepository`**
to see diagnostics before testing prompts.

The two files load **independently**. If one taxonomy file is invalid, it does not invalidate the other file or the whole
repository. Within a file, failure behavior depends on the format: v3 treats malformed entries as file-level failures;
v2 legacy identity rows are skipped with row diagnostics.

---

## 1. `identity-types.json`

`identity-types.json` maps natural user labels to a **single canonical Thing** (used by `resolve_thing`, and by the
`THINGNAME` preflight). It has **two accepted file formats**. The loader chooses by the **root JSON shape**:

| Root shape | Format | Status |
|---|---|---|
| JSON **array** `[ … ]` | **v3** | Current. Author all new files in v3. |
| JSON **object** `{ "version": 2, … }` | **v2** | Legacy. Still loaded; documented in §1.2 for existing files. |

### 1.1 v3 format (array — current)

The file is an **ordered JSON array**. **Array order is priority** — there is no `priority` field. Each element is an
**identity rule** with exactly **three** fields, all required:

```json
[
  {
    "identityProperties": [
      { "name": "name", "match": "suffix" },
      { "name": "PTCDisplayName", "match": "equals" },
      { "name": "PTCSerialNumber", "match": "equals" }
    ],
    "baseThingTemplate": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": []
  }
]
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `identityProperties` | array | yes | Properties matched against the user's text. Each entry is a **string** or an **object** (see §1.1.1). Must be non-empty. |
| `baseThingTemplate` | string | yes | Only Things implementing this ThingTemplate (directly or by inheritance) are candidates. The single generic identity scope in v3 — ThingShape is **not** accepted here (use a wrapper if you need it). |
| `criticalProperties` | array of strings | yes | Extra properties returned with each candidate. Use `[]` when none. |

Notes that affect authoring:

- `identityProperties` and `criticalProperties` must exist on `baseThingTemplate` (directly or inherited). This is **not**
  validated at load time; a bad property name surfaces as a runtime error or an omitted column when the resolver runs.
- `name` is special: it is **always returned first**, even if you omit it from `identityProperties`. If you list `name`,
  it also participates in matching with its configured `match`. Returned fields are de-duplicated in the order
  `name`, then `identityProperties`, then `criticalProperties`.
- **Zippered pairing:** the *i*-th property pairs with the *i*-th `match` only. A Thing matches when **any** pair matches
  (OR across pairs), not when all match.
- **First non-empty rule wins:** the resolver tries rules in array order; the first rule that yields candidates decides
  the outcome (UNIQUE / AMBIGUOUS) and stops. Put narrower, higher-priority rules first.

#### 1.1.1 `identityProperties` entry — all variant forms

Each entry may take any of these shapes, and a single array may **mix** them:

**Form 1 — string shorthand.** A plain string is the property name; `match` defaults to `equals`:

```json
"identityProperties": [ "PTCDisplayName", "PTCSerialNumber" ]
```

is exactly equivalent to:

```json
"identityProperties": [
  { "name": "PTCDisplayName", "match": "equals" },
  { "name": "PTCSerialNumber", "match": "equals" }
]
```

**Form 2 — object with explicit match.** `name` is required; `match` is optional and defaults to `equals`:

```json
"identityProperties": [
  { "name": "name", "match": "suffix" },
  { "name": "PTCDisplayName" }
]
```

(The second entry defaults to `match: "equals"`.)

**Form 3 — mixed** strings and objects in the same array:

```json
"identityProperties": [
  "PTCDisplayName",
  { "name": "name", "match": "suffix" }
]
```

**Rejected entry shapes** (the whole v3 `identity-types.json` file is marked invalid; no v3 identity rules from that
file are loaded):

- An empty `identityProperties` array — `[]`.
- An element that is neither a string nor an object — e.g. `42`, `true`, `null`.
- An object missing `name` (or with an empty `name`) — e.g. `{ "match": "equals" }`.
- An object whose `match` is not `equals` or `suffix` (see §1.1.2).

#### 1.1.2 `match` values

Only two values are accepted in v3. Any other value fails the v3 identity file at load time.

| `match` | Meaning |
|---|---|
| `equals` | Case-insensitive equality between the trimmed user text and the raw property value. (QIT `EQ`, `isCaseSensitive:false`.) **This is the default** when `match` is omitted or a property is given as a bare string. |
| `suffix` | Case-insensitive suffix match on the raw property value. Intended for canonical Thing `name` values that carry a namespace prefix. (QIT `LIKE` with a leading `*`.) |

> Older drafts mentioned `normalizedEquals`, `contains`, and `normalizedSuffix`. **These are withdrawn for v3** — the
> loader accepts only `equals` and `suffix`. Do not use them.

#### 1.1.3 `criticalProperties` — variant forms

`criticalProperties` is required at the rule level but may be empty.

Semantics: these are extra projected/context properties returned with identity candidates. They complement
`identityProperties`, which define how a candidate is matched; they do not independently identify the Thing and
do not define health dimensions or current-alert state. SCPA commonly projects display/make/model/serial fields,
but applications may choose other context properties.

| You write | Result |
|---|---|
| `[]` | No extra properties. |
| `["PTCMake", "PTCModel"]` | Those properties returned with each candidate. |
| (field omitted / `null`) | Treated as `[]`. |
| Array with an empty string `["PTCMake", ""]` | Empty entries dropped. |
| Array with a non-string `["PTCMake", 42]` | Non-string entries dropped. |
| A non-array, e.g. `"PTCMake;PTCModel"` | Treated as empty `[]` — **string-joined lists are not parsed**; use an array. |

### 1.2 v2 format (object — legacy)

> **Legacy.** New files should use v3 (§1.1). The loader still accepts v2 for existing deployments. v2 is recognized by a
> **root object** carrying `"version": 2`.

```json
{
  "version": 2,
  "entities": [
    {
      "key": "asset",
      "aliases": ["device", "equipment"],
      "types": [
        {
          "key": "Jet Dryer",
          "aliases": ["jet dryer", "jet dryers"],
          "representation": "shape_as_type",
          "membership": {
            "entityType": "ThingShape",
            "entityName": "PTCTDD.CellfabDataset.JetDryer_TS"
          },
          "identity": {
            "properties": ["name", "PTCDisplayName", "PTCSerialNumber"],
            "matchRules": ["exact", "normalized", "normalized"]
          },
          "criticalProperties": ["PTCDisplayName", "PTCMake", "PTCModel", "PTCSerialNumber"],
          "queryParent": {
            "entityType": "ThingTemplate",
            "entityName": "PTC.MfgModel.DefaultWorkunit_TT",
            "role": "optional_narrowing"
          }
        }
      ]
    }
  ]
}
```

**Root** (object):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `version` | integer | yes | Must be `2`. Missing or any other value fails the file. |
| `entities` | array | yes | List of entity groups. An empty or non-array value fails the file. |

**Entity** (`entities[]`):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `key` | string | yes | Non-empty; unique within the file (compared after normalization). |
| `aliases` | array of strings | no (default `[]`) | Alternate phrases. See §3 for the shared array-shape rules. |
| `types` | array | yes | Non-empty list of type definitions. |

**Type** (`entities[].types[]`):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `key` | string | yes | Non-empty; unique within the parent entity. |
| `representation` | string | yes | `template_as_type` or `shape_as_type`. (`model_serial_template` is reserved/ignored — the row is skipped.) |
| `membership` | object | yes | `{ "entityType": "ThingTemplate" \| "ThingShape", "entityName": "<name>" }`. |
| `identity` | object | yes | `{ "properties": [..], "matchRules": [..] }` (see below). |
| `aliases` | array of strings | no (`[]`) | Alternate phrases for this type. |
| `criticalProperties` | array of strings | no (`[]`) | Extra properties to return. |
| `queryParent` | object | no | Optional narrowing parent; **only** valid when `representation` is `shape_as_type` and membership is a `ThingShape`. |

**Cross-field rules:**

- `representation: "template_as_type"` requires `membership.entityType: "ThingTemplate"`.
- `representation: "shape_as_type"` requires `membership.entityType: "ThingShape"`.
- A mismatch fails the row.

**`identity`** (object):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `properties` | array of strings | yes | Non-empty list of property names. |
| `matchRules` | array of strings | yes | Paired with `properties` by index. Each value must be `exact` or `normalized`. The legacy loader does not enforce equal length, so authors should keep the arrays aligned manually. |

> v2 `matchRules` use `exact` / `normalized`. These are **not** the same names as v3's `equals` / `suffix` — do not mix
> the vocabularies between formats.

**`queryParent`** (object, optional):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `entityType` | string | yes | Must be `ThingTemplate`. |
| `entityName` | string | yes | The narrowing template's name. |
| `role` | string | no | Free-form label; no runtime effect. |

Used on a non-shape type, `queryParent` is ignored with a diagnostic.

---

## 2. `asset-types.json`

`asset-types.json` maps a **business asset class** ("Jet Dryers", "Stacking Robots") to a ThingWorx ThingShape or
ThingTemplate. It resolves an **asset-type boundary**, not a single Thing (used by `resolve_asset_type`, then
`query_entities_by_taxonomy`).

It is a **JSON object (map)**. Each **key** is the canonical, user-facing asset-type label; each **value** is a
definition object. There is no `version` field — the file is identified by its path and object shape.

```json
{
  "Stacking Robot": {
    "aliases": ["stacking robots", "stacker robot"],
    "entityType": "ThingShape",
    "entityName": "PTCTDD.CellfabDataset.StackingRobot_TS",
    "criticalProperties": ["PTCDisplayName", "PTCMake", "PTCModel", "PTCSerialNumber"]
  },
  "Workunit": {
    "aliases": ["work unit", "machine", "asset"],
    "entityType": "ThingTemplate",
    "entityName": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": ["PTCDisplayName", "PTCMake", "PTCModel", "PTCSerialNumber"]
  }
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| *(map key)* | string | yes | Canonical asset-type label. Treat it as a key, even though it often reads like a UI label. Unique per file. |
| `aliases` | array of strings | yes | Other user phrases for the same class. Use `[]` if none. |
| `entityType` | string | yes | `ThingShape` or `ThingTemplate` (case-sensitive). |
| `entityName` | string | yes | The ThingShape or ThingTemplate entity name. |
| `criticalProperties` | array of strings | yes | Properties to project when listing the set. Use `[]` if none. It does **not** define hierarchy. |

**`entityType` — the two forms:**

```json
"entityType": "ThingShape"      // membership by ThingShape  (most common in the workshop data)
"entityType": "ThingTemplate"   // membership by ThingTemplate
```

The loader derives the internal representation from this (`ThingTemplate` → template-as-type, `ThingShape` →
shape-as-type); you do not write a `representation` field here.

`aliases` and `criticalProperties` follow the shared array-shape rules in §3.

Malformed v3 asset-type entries are file-level failures. For example, an entry whose value is not an object, whose
`entityType` is not `ThingShape` or `ThingTemplate`, whose `entityName` is missing, or whose canonical key is empty after
normalization makes **`asset-types.json`** invalid; no asset-type entries from that file are loaded.

---

## 3. Shared array-shape rules (`aliases`, `criticalProperties`, v3 `criticalProperties`)

The string-array fields accept the same set of shapes everywhere they appear:

| You write | Result |
|---|---|
| field omitted | Treated as `[]`. |
| `null` | Treated as `[]`. |
| `[]` | Empty list. |
| `["a", "b"]` | Those strings, trimmed. |
| `["a", ""]` | Empty / whitespace-only entries are dropped. |
| `["a", 42, null]` | Non-string entries are dropped. |
| `"a,b"` (a non-array) | Treated as empty `[]`. **Comma/semicolon-joined strings are not parsed** — always use a JSON array. |

Additional rules for **`aliases`** specifically:

- Aliases are normalized for matching; keep them distinct. The loader flags an **alias collision** when two entries
  normalize to the same phrase.
- The normalized forms `stream`, `datatable`, and `valuestream` are **reserved** (platform terms) and are flagged if used
  as aliases.

---

## 4. What the loader accepts, ignores, or rejects

- **Unknown fields in v2** are ignored with a "future field ignored" diagnostic — they do not fail the row. **Unknown
  fields in v3** are ignored silently. Do not rely on unknown fields to smuggle data; only the documented fields have any
  effect.
- **v3 malformed entries are file-level failures.** A malformed v3 identity rule or asset-type entry rejects that whole
  taxonomy file, while leaving the other taxonomy file intact.
- **v2 malformed rows are skipped.** Legacy v2 identity rows can be dropped with a "row invalid" diagnostic while the
  rest of the v2 file continues loading.
- **Other file-level failures** include wrong root shape, missing/`!= 2` `version` in v2, empty v3 identity array, and
  malformed v3 identity-rule or asset-type entries.
- **Parent entities are not resolved at load time** by default; a missing ThingTemplate/ThingShape surfaces at runtime,
  not as a load error.
- Use **`ValidateAgentConfigurationRepository`** to read these diagnostics before you blame a prompt.

---

## 5. Identity vs asset type — quick reference

| User phrase | File | Tool path |
|---|---|---|
| "ORD-Contacting-01" (one specific unit) | `identity-types.json` | `resolve_thing` |
| "Jet Dryers" (a class) | `asset-types.json` | `resolve_asset_type`, then `query_entities_by_taxonomy` |
| "Jet Dryers in USA" | both + hierarchy | resolve asset type, resolve/expand hierarchy, intersect |

Resolver outcomes for `identity-types.json` (§1): `UNIQUE` binds the canonical `ThingName`; `AMBIGUOUS` shows candidates
and asks the user to choose (bounded, ~10 rows); `NOT_FOUND` asks for a better identifier. `AMBIGUOUS` / `NOT_FOUND` are
binding states, not tool failures.

## 6. Common mistakes

- Using v2 vocabulary (`exact` / `normalized`, `properties` / `matchRules`, `membership`, `representation`) inside a v3
  **array** file, or v3 vocabulary (`equals` / `suffix`, `identityProperties`, `baseThingTemplate`) inside a v2 **object**
  file. Pick one format per file; the root shape decides which parser runs.
- Writing `propertyName` instead of `name`, or using `contains` — neither exists in v3.
- Putting a `ThingShape` in v3 `baseThingTemplate` (v3 identity scope is ThingTemplate-only).
- Joining lists as a single string (`"a,b"`) instead of a JSON array.
- Putting one concrete unit nickname only in `asset-types.json`, or a broad class only in `identity-types.json`.
- Making aliases so broad that everything resolves AMBIGUOUS.
- Forgetting `RefreshTaxonomyCache`, or re-running a failed prompt in the same old conversation and blaming the taxonomy.

## 7. See also

- Chapter 7 — Taxonomy: identity types (concepts and walkthrough).
- Chapter 8 — Taxonomy: asset types (concepts and walkthrough).
- Appendix C — Configuration repository (paths, upload, validation services).
- Parler `CONTRACTS/TAXONOMY_RESOLVER.md` — normative resolver wire/error semantics.
- Parler `docs/agent/taxonomy.md` — maintainer design reference for taxonomy authoring and resolver construction.
