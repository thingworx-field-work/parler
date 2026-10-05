# Playbook input resolution

Status: implemented (`normalize_resolved_thing`, `match_identifier_in_rows`, `pick_branch_output`, optional asset-type hint in `pick_taxonomy_row`).

## 0. Related documents and code

**Related agent docs.**

- `docs/agent/playbook-engine.md` — Playbook JSON model, node kinds, validation, and
  repository layout for packaged playbooks.
- `docs/agent/playbook-builtin-capability-expansion.md` — Playbook-safe built-in calls
  such as `resolve_thing` inside `tool_call` nodes.
- `docs/agent/playbook-generic-ops-foundation.md` — generic derive ops (`project`,
  `filter`, `pick_one`, …) and expression mechanics; the focused ops here stay consistent
  with that foundation (caps, predicates, evidence).
- Chat tools apply the same identifier discipline: they steer toward `resolve_thing` for
  ambiguous Thing names (scalar Thing-name preflight, `CONTRACTS/TAXONOMY_RESOLVER.md` §7).
  Playbooks mirror it without requiring users to paste canonical names.

**Java runtime.**

| Area | Types / notes |
| --- | --- |
| Derive allowlist | `PlaybookValidator` — unions `V1A_DERIVE_OPS`, `V1B_DERIVE_OPS` (`match_entity_identifiers`, `flatten_pair_assets`, **`normalize_resolved_thing`**, **`match_identifier_in_rows`**, **`pick_branch_output`**, …), and `GENERIC_DERIVE_OPS`; ops are registered and arg-validated fail-closed. |
| Derive execution | `PlaybookDeriveOps` / `PlaybookDeriveOpsV1b` / `PlaybookGenericDeriveOps` — dispatch that executes `derive.op` strings. |
| Clarification stop | `PlaybookRunner` — treats derive/tool results with `status: "needs_clarification"` as a hard stop for downstream nodes; the normalize/match ops emit that shape instead of guessing. |

**Wire / UI contracts.** Input resolution leaves observable AlwaysOn / widget wire
unchanged (see §4).

## 1. Background

Parler Skills and Parler Playbooks are close enough that training material can use
the same business story in both forms:

```text
Compare the health of ORD Contacting 02 and ORD Contacting 01 over the past 24 hours.
```

The natural multi-turn version of this story does **not** ask the user to name an asset
type. It also does not state that both assets are of the same type. That is intentional:
users normally name the assets they care about and expect the agent to resolve those
labels.

A Skill supports that behavior because it can call `resolve_thing` directly. `resolve_thing` accepts an optional `assetTypeKey`, but it does not require one.
If no asset type hint is supplied, it resolves against loaded v3 identity rules globally.
Downstream built-ins such as `query_alert_history`, `query_alert_summary`,
`get_property_values`, and `query_property_history` only require canonical Thing names.

The original `cross_asset_pair_health` Playbook was less natural. It required
`assetType`, then:

1. picks a taxonomy row,
2. lists candidate Things by taxonomy,
3. matches each user identifier inside that candidate list,
4. flattens the two matches into downstream canonical Thing names.

That path works when a prompt or slash invocation supplies an asset type, but it makes
`assetType` a Playbook implementation requirement even though the tool layer does not
need it for this workflow. It is still available for other Playbooks (and the older
`dev_data/playbooks/cross_asset_pair_health` package still uses it).

The same shape appeared in utilization. The `machine_utilization_summary` Skill
told the agent to normalize a machine label before calling the utilization tool, while the
Playbook passed raw user input into `Machine`. The Playbook now normalizes that input
before calling `get_utilization_state_summary`, whose `Machine` parameter requires the canonical
ThingWorx Thing name rather than a display name, short label, alias, or serial.

These were not two separate bugs. They were one missing Playbook capability:

> A Playbook input should be allowed to be human text, and the Playbook should be able to
> normalize that text into the canonical identifier expected by downstream tools.

## 2. Problem statement

Playbook inputs involve three different concepts:

| Concept | Example | Note |
| --- | --- | --- |
| User-facing label | `ORD Contacting 02` | Natural prompt input, but not always accepted by downstream tools. |
| Optional narrowing hint | `Contacting` / `assetTypeKey` | Useful when known, but should not be required when identity rules can resolve globally. |
| Canonical runtime identifier | `SE.CellFab.Model.Workunit.ORD-Contacting-02` | Required by many built-in and extended tools. |

The engine can call `resolve_thing` inside Playbooks (`playbook-builtin-capability-expansion`).
Without resolver-aware derive ops, Playbooks stay shaped around older candidate-list matching,
with these consequences:

- A Playbook can be less capable than the equivalent Skill.
- A training story can become self-inconsistent: the multi-turn route succeeds without
  `assetType`, while the Playbook route requires it.
- Utilization Playbooks can silently depend on users typing canonical Thing names, even
  though the Skill already describes a more realistic machine-resolution route.
- Skill-to-Playbook conversion produces brittle Playbooks unless input normalization is a
  first-class Playbook pattern.

## 3. Goals

1. Make Playbooks accept user-facing identifiers where the business prompt naturally
   does so.
2. Keep asset type as an optional narrowing hint for asset-pair workflows, not a required
   input unless the workflow logically needs an asset type.
3. Normalize identifiers before downstream tool calls that require canonical names.
4. Use the existing tool executors and their existing visibility / permission-aware
   ThingWorx access paths. Input resolution adds no new security model.
5. Give ambiguous or unresolved identifiers a deterministic clarification path.
6. Keep reference Playbooks coherent with the equivalent Skills.
7. Cover the single-machine utilization path.

## 4. Non-goals

Input resolution does not:

- implement host-context pronoun resolution;
- implement arbitrary LLM planning inside Playbooks;
- require all Playbooks to avoid `assetType`;
- remove `assetType` from workflows that are genuinely type-scoped, such as
  cross-region health by asset type;
- redesign identity taxonomy files;
- introduce an automatic middleware that rewrites every identifier-shaped tool argument;
- add a general fuzzy-search engine;
- change wire contracts or UI chart contracts;
- change extended-tool authoring rules beyond using existing `playbookSafe` tools in a
  safer Playbook sequence.

## 5. Scope

### 5.1 Cross-asset pair health

Reference locations:

- `parler-agent/src/test/resources/playbook-packaging-fixture/cross_asset_pair_health/playbook.json` (resolver-first)
- `dev_data/scpa_utilization/playbooks/cross_asset_pair_health/playbook.json` (resolver-first)
- `dev_data/playbooks/cross_asset_pair_health/playbook.json` (older taxonomy candidate-list variant; `assetType` required)

Behavior:

- Required inputs:
  - `assetIdentifierA`
  - `assetIdentifierB`
  - `timeWindow` when the user supplies one; otherwise normal prompt routing may ask or
    use a documented default if the Playbook declares one.
- Optional input:
  - `assetType`, used only as a narrowing hint.
- Resolution:
  - If an asset type hint is provided, resolve it to an `assetTypeKey` using the existing
    asset-type resolver path.
  - Call `resolve_thing` for each user asset identifier, passing the resolved
    `assetTypeKey` only when one exists.
  - Convert each successful `resolve_thing` result into a canonical asset row consumed by
    existing downstream nodes.
  - On no match or multiple matches, stop with a concise clarification result. Do not
    guess.
- Downstream health logic:
  - Continue using canonical Thing names for alerts, current values, and property
    history.
  - Do not assume the two Things share the same ThingTemplate unless a downstream step
    explicitly verifies or needs that fact.

The taxonomy candidate-list path remains available for other Playbooks.

### 5.2 Machine utilization summary

Reference location:

- `dev_data/scpa_utilization/playbooks/machine_utilization_summary/playbook.json`

Behavior:

- The `machine` input is user text. It may be a canonical Thing name, display label,
  short label, equipment identifier, or other configured identity phrase.
- The Playbook normalizes `machine` before calling
  `get_utilization_state_summary`.
- Route:
  1. Call `resolve_thing` with the user text and no required asset type hint.
  2. If the result is unique, pass the canonical Thing name to
     `get_utilization_state_summary.Machine`.
  3. If `resolve_thing` cannot resolve, call `list_utilization_machines` and match the
     returned `machineName` / `displayName` / `description` rows.
  4. If the fallback match is unique, pass that canonical machine identifier downstream.
  5. If zero or multiple machines match, ask for clarification.

The fallback fields are pinned by tests against the SCPA utilization helper rows. There is
no broad fuzzy matching. Normalize case,
whitespace, punctuation, and common display-name separators; keep the matching rules
explainable.

### 5.3 Utilization overview and all-machine summary

Reference locations:

- `dev_data/scpa_utilization/playbooks/utilization_overview/playbook.json`
- `dev_data/scpa_utilization/playbooks/utilization_summary/playbook.json`

These workflows do not take a single user-supplied machine identifier, so they need no
input normalization. A variant that accepts a subset of machines should reuse the same
row-matching operation rather than passing raw labels into extended tools.

### 5.4 Cross-region health

Reference location:

- `dev_data/scpa_utilization/playbooks/cross_region_health/playbook.json`

No input normalization. Cross-region health is naturally an asset-type-scoped
workflow: "compare regions for this asset type." Keeping `assetType` required there is
self-consistent.

## 6. Engine changes

### 6.0 Control-flow and row-source contracts

1. **`$table` placement and JSON-result traversal.** `$table` is **only** valid as the sole value of a **top-level** `tool_call.args` parameter bound to an infotable (validator + `PlaybookExpressionResolver` enforce `TABLE_REF_NOT_ALLOWED_HERE` elsewhere). **Derive ops MUST NOT** take `"rows": { "$table": ... }`. Rows fed into `match_identifier_in_rows` MUST come from a **`$ref`** to a concrete JSON array on a prior **`tool_call`** result. For JSON-returning extended tools, use a path such as `machine_listing.toolOutput.result.rows`: when a continuing `$ref` path crosses `toolOutput.result` and `result` is a JSON string, the resolver parses that string before traversing the remaining segments. A terminal `...toolOutput.result` reference stays opaque, malformed JSON fails closed, and strings outside `toolOutput.result` are not parsed.

2. **Terminal `needs_clarification` vs. fallback.** `PlaybookRunner` stops the DAG immediately when any node returns **`status: "needs_clarification"`**. Therefore **`normalize_resolved_thing` is terminal-only**: it MUST run only on branches where the playbook has already decided resolution succeeded. For the utilization machine path, **`resolve_thing` failure MUST NOT** flow into a `normalize` node that emits terminal clarification and then expect a machine-listing fallback on the same path. The native V1a pattern is: **`tool_call` `resolve_thing`** (node result is always top-level `status: "ok"` with inner `toolOutput.status` / `code` / `resultKind`) **→ one or more `condition` nodes** branching on **`$ref`** paths such as `resolve_machine.toolOutput.status`, `…code`, `…resultKind` **→** either a **`tool_call` `list_utilization_machines`** + row matcher on **`$ref`** rows, **or** a terminal **`normalize_resolved_thing`** on the unique-success branch. Predicate ops are limited to what `PlaybookConditionEvaluator` supports (`eq` / `ne` / `is_empty` / `is_present` / comparisons / `and` / `or` / `any` / `not`).

3. **`normalize_resolved_thing` envelope mapping (normative).** The derive op reads **`sourceNodeId`**’s saved **`toolOutput`** from **`resolve_thing`** / **`resolve_asset_type`**-shaped JSON and maps:

   | Incoming `toolOutput` | `normalize_resolved_thing` output |
   | --- | --- |
   | `status: "success"`, `resultKind: "THING_RESOLVED_INLINE"` (or taxonomy inline), `matches.length === 1` | `status: "ok"`, `output.name` from `matches[0].name`, `output.row` = first match object, optional `output.matchedBy` echo |
   | `status: "success"`, `matches.length !== 1` (defensive) | `needs_clarification` |
   | `status: "success"`, `resultKind: "THING_RESOLVED_LARGE"` (or taxonomy large) | **`needs_clarification`** — never treat large/sample/cache envelopes as a unique canonical name |
   | `status: "error"`, `code: "IDENTITY_AMBIGUOUS"` / `ASSET_IDENTIFIER_AMBIGUOUS` | `needs_clarification` + bounded `candidates[]` (name-only projection) |
   | `status: "error"`, `IDENTITY_NOT_FOUND` / `ASSET_IDENTIFIER_NOT_FOUND` | `needs_clarification` |
   | `status: "error"`, `TAXONOMY_UNAVAILABLE` / `ASSET_TYPES_NOT_CONFIGURED` / `ASSET_TYPE_NOT_FOUND` | `needs_clarification` (configuration / operator follow-up) |
   | other errors | `needs_clarification` carrying resolver `message` when present |

4. **`pick_taxonomy_row` without a hint.** When `assetType` input is blank and the playbook sets **`whenAssetTypeMissing": "empty_taxonomy"`**, the derive seeds **`taxonomy.*`** with empty strings / empty **`CriticalPropertiesList`** instead of failing closed. **`cross_asset_pair_health`** uses this so **`resolve_thing`** can run globally without a narrowing key while optional hints still use the existing taxonomy pick path.

5. **`match_identifier_in_rows` output.** Mirror the **`normalize_resolved_thing`** success shape for reuse: `status`, `output.name` (canonical machine Thing name from `machineName`, `name`, `Name`, or `ThingName`), `output.row` (full matched listing row), `output.matchedBy` (field key + tier), ordered field evaluation (exact → case-insensitive → normalized whitespace/separators → guarded suffix on canonical name columns only). `displayName`, `description`, `EquipmentID`, and `EquipmentDesc` are match hints, never canonical downstream names. **No fuzzy scoring.**

6. **Primitive `string[]` fan-out and `$item` (C17).** When **`fan_out.items`**
   resolves to a JSON array whose elements are **strings or other non-object scalars**,
   **`PlaybookRunner`** wraps each element as **`{ "<itemVar>": "<string value>" }`** where
   **`itemVar`** is the fan-out node's declared **`itemVar`** field (single identifier segment;
   validator-enforced). When **`itemVar`** is omitted, the default wrapper key is **`value`**.
   Authors bind child **`tool_call`** args with **`{ "$item": "<itemVar>" }`** matching that key
   (or **`{ "$item": "" }`** to pass the whole wrapper object). **`itemVar`** is **not** decorative:
   it is the runtime wrapper key for primitive items. When **`items`** is an array of **objects**
   (for example normalized **`resolve_thing`** rows or derive **`output.rows`**), items pass
   through unchanged and **`$item`** navigates real fields (**`name`**, **`thingName`**, …).
   Reference: **`cross_region_health`** **`assets_by_region`** — **`itemVar: "region"`**,
   **`items`**: **`{ "$input": "regions" }`**, arg binding **`{ "$item": "region" }`**.
   Cross-ref: **`playbook-engine.md`** §4.3 / §4.4.1 (C9), §5.

### 6.1 Normalize one `resolve_thing` result

Derive op `normalize_resolved_thing` (Java: `PlaybookDeriveOpsV1b`, allowlisted in `PlaybookValidator`) converts a
`resolve_thing` tool result into a compact row shape usable by Playbook downstream nodes. `PlaybookValidator` rejects it
when `sourceNodeId` is not a `resolve_thing` `tool_call`.

Args:

```json
{
  "sourceNodeId": "resolve_asset_a",
  "label": "asset A",
  "onZero": "clarify",
  "onMultiple": "clarify"
}
```

(`onZero` / `onMultiple` are accepted but have no effect; the mapping is fixed to clarification for resolver errors as in §6.0.3.)

Expected output on success:

```json
{
  "status": "ok",
  "output": {
    "name": "SE.CellFab.Model.Workunit.ORD-Contacting-02",
    "row": { "name": "...", "matchedBy": { "field": "displayName", "rule": "...", "value": "..." } },
    "ambiguousCount": 0,
    "matchedBy": { "field": "displayName", "rule": "...", "value": "..." }
  },
  "gaps": []
}
```

The exact fields follow the real `resolve_thing` result. The required stable
field is `output.name`, because existing downstream Playbooks already use `name` as the
canonical Thing name.

Expected output on ambiguity:

```json
{
  "status": "needs_clarification",
  "message": "Multiple matches for asset A. Please choose one.",
  "candidates": [
    { "name": "..." }
  ]
}
```

The runner already halts on derive outputs with `needs_clarification` (`PlaybookRunner`).

### 6.2 Collect normalized items into a bounded list

`flatten_pair_assets` accepts any derive nodes whose `output.name` is populated; **`cross_asset_pair_health`** wires
**`matchNodeA` / `matchNodeB`** to **`normalize_*`** nodes and gets two canonical asset rows with stable slots `A` and
`B`. There is no general `collect_items` op; small non-pair collections use generic ops such as `build_targets` /
`project` (**`docs/agent/playbook-generic-ops-foundation.md`**).

### 6.3 Match one row from a tool-returned table

Derive op `match_identifier_in_rows` matches a user identifier against rows returned by an extended tool. It is a
bounded table-row identifier matcher, not utilization-specific.

**Valid row source:** pass rows as **`$ref`** to a JSON array on a prior **`tool_call`** result. The current utilization listing is a JSON envelope, so its row source is `machine_listing.toolOutput.result.rows`; the resolver performs the bounded `toolOutput.result` JSON-string traversal described in §6.0.1. **Never** use `{ "$table": ... }` inside derive args.

Current utilization args:

```json
{
  "rows": { "$ref": "machine_listing.toolOutput.result.rows" },
  "identifier": { "$input": "machine" },
  "label": "machine",
  "fields": ["machineName", "displayName", "description"],
  "onZero": "clarify",
  "onMultiple": "clarify"
}
```

Matching rules are deterministic:

- exact match first;
- case-insensitive exact match;
- normalized exact match after removing repeated whitespace and common separators;
- suffix match only for canonical Thing names when the suffix is unambiguous —
  one-directional: a user-supplied short suffix may match the END of a canonical
  Thing name (`normalize(cell).endsWith(normalize(identifier))`). The inverse (a
  longer/contaminated identifier ending with the canonical cell) MUST NOT match;
- no fuzzy scoring;
- the emitted `output.name` MUST be a canonical Thing name column
  (`machineName` / `name` / `Name` / `ThingName`). A row may be *matched* on
  `displayName` / `description` / `EquipmentID` / `EquipmentDesc`, but those are identity hints,
  not canonical names; if the
  matched row has no canonical Thing-name column, the op MUST return
  `needs_clarification` rather than pass a non-canonical identifier downstream
  (it would pass a display label to a tool that needs a canonical name — see §1 / §5.2).

### 6.4 Fallback composition

Fallback needs no new control-flow language: `condition` nodes branch on **raw** `resolve_thing` **`toolOutput.status` / `code` / `resultKind`** before any terminal **`normalize_resolved_thing`** (see §6.0.2):

```text
if resolve_thing produced one canonical name:
  normalize_resolved_thing (terminal)
else:
  call machine listing and match rows via $ref (not $table-in-derive)
```

Derive op **`pick_branch_output`** merges the exclusive **`condition`** branches so a single downstream **`tool_call`** can **`$ref`** one resolved canonical name (**`elseNodeId`** may be downstream of the condition's **`else`** root; **`thenNodeId`** must match **`then`** exactly).

## 7. Reference Playbook flows

### 7.1 Cross asset pair health

`cross_asset_pair_health` changed from:

```text
assetType required -> query_entities_by_taxonomy -> match_entity_identifiers
```

to:

```text
optional assetType hint -> resolve_thing A/B -> normalize -> downstream alert/trend flow
```

`whenToUse` does not require "same asset type". Wording:

```text
Use when the user asks to compare two individual assets by name, display name, suffix, or
serial number. If an asset type is supplied, use it only as a narrowing hint.
```

The final summary prompt states when the two Things appear to be different types or
when type evidence is unavailable. It does not invent a type match.

### 7.2 Machine utilization summary

`machine_utilization_summary` changed from:

```text
raw machine input -> get_utilization_state_summary
```

to:

```text
resolve_thing -> condition on raw toolOutput.status / resultKind ->
  normalize_resolved_thing | list_utilization_machines + match_identifier_in_rows ->
pick_branch_output -> get_utilization_state_summary.Machine ($ref)
```

The final summary includes the resolved machine identifier in evidence so the user
can see what was actually queried.

## 8. Training material

The training course under `training/` tells the same story in three steps: a natural multi-turn prompt, a Skill with
resolver-first instructions, and a Playbook with equivalent resolver-first inputs. The Day 4 utilization Playbooks under
`training/workshop/day4/` are copies of the final `dev_data/scpa_utilization/` sample; drift is caught as described in
[`training-content-parity.md`](./training-content-parity.md).

## 9. Tests

Java tests:

1. `cross_asset_pair_health` validates without `assetType` in `inputSchema.required`.
2. `cross_asset_pair_health` still validates and executes when optional `assetType` is
   supplied.
3. A mocked `resolve_thing` unique inline result (`THING_RESOLVED_INLINE`, `matches.length == 1`) is normalized to `output.name`.
4. A mocked `IDENTITY_AMBIGUOUS` / `ASSET_IDENTIFIER_AMBIGUOUS` envelope becomes `needs_clarification` with bounded `candidates`.
5. A mocked `IDENTITY_NOT_FOUND` / `ASSET_IDENTIFIER_NOT_FOUND` envelope becomes `needs_clarification`.
6. A mocked `THING_RESOLVED_LARGE` / `TAXONOMY_ASSET_IDENTIFIER_LARGE` envelope becomes `needs_clarification` (never a unique name).
7. `TAXONOMY_UNAVAILABLE` / `ASSET_TYPE_NOT_FOUND` / `ASSET_TYPES_NOT_CONFIGURED` map to `needs_clarification` with a clear operator-facing message.
8. Pair building (`flatten_pair_assets`) emits two canonical asset rows with stable slots `A` and `B` when fed `normalize_*` nodes.
9. `machine_utilization_summary` passes the resolved canonical machine name to
   `get_utilization_state_summary`.
10. Machine-listing fallback uniquely matches `displayName` / `description`; generic-op compatibility tests retain legacy `EquipmentID` / `EquipmentDesc` matching.
11. Machine-listing fallback ambiguity asks for clarification.
12. Existing `utilization_overview`, `utilization_summary`, and `cross_region_health`
    fixtures still validate.
13. `PlaybookRunnerCrossAssetPairHealthPackagingFixtureTest` completes the packaged
    `cross_asset_pair_health` DAG without `assetType` and again with an optional hint
    (stubbed tools), **capturing** each **`resolve_thing`** **`assetTypeKey`** (absent
    / blank vs. **`LineX`**) and asserting **`query_alert_summary`** /
    **`get_property_values`** / **`query_property_history`** receive the resolved
    canonical **`thingName`** values.
14. `PlaybookValidator` rejects `normalize_resolved_thing` when `sourceNodeId` is not a
    `resolve_thing` `tool_call`.

Test classes include `PlaybookDeriveOpsV1bTest`, `PlaybookRunnerMachineUtilizationSummaryFixtureTest`,
`PlaybookRunnerCrossAssetPairHealthPackagingFixtureTest`, and `Bug004PlaybookCatalogFixtureTest` (merged definitions
include `resolve_thing`).

Manual checks after import:

```text
please compare the health status between ORD Contacting 02 and ORD Contacting 01 over the past 24 hours
```

Expected: Playbook can run without user-supplied asset type and uses canonical Thing
names downstream.

```text
show utilization for ORD Contacting 01 yesterday
```

Expected: Playbook normalizes the machine label before calling the by-machine
utilization tool.

```text
show utilization for SE.CellFab.Model.Workunit.ORD-Contacting-01 yesterday
```

Expected: canonical Thing input still works.

## 10. Design rules

1. **External key:** `assetType` stays the input key; it is not in `required` and is only a narrowing hint.
2. **Derive surface:** **`normalize_resolved_thing`** + **`match_identifier_in_rows`** rather than overfitting **`flatten_pair_assets`**.
3. **Machine fallback tiers:** exact + normalized-exact + guarded suffix on canonical name fields only; **no fuzzy scoring**.
4. **Row source for matchers:** no `$table` inside derive args; use **`$ref`** to tool JSON rows (§6.0.1).
5. **Control flow:** branch on raw **`resolve_thing` `toolOutput`** with **`condition`** before terminal **`normalize_resolved_thing`** when listing fallback is needed (§6.0.2).
