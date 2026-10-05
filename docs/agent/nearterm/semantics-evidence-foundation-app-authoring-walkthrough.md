# Semantic profile — App authoring and operator walkthrough

This guide is for App Developers who author the application semantic profile and for operators who
run it. The runtime rules are described in
[`semantics-evidence-foundation.md`](./semantics-evidence-foundation.md) Part A. Reference fixtures
are in `parler-agent/src/test/resources/nearterm/semantics/`.

## 1. What the App Developer authors

One file in the Agent configuration FileRepository:

```text
/semantics/semantic-profile.json
```

(`ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON`)

| Field | Rule |
|---|---|
| `schema` | exactly `parler-semantic-profile-v1` |
| `profileId` / `version` | App-stable strings |
| `assetTypes` keys | **exact** taxonomy `assetTypeKey` values from `/taxonomies` (no alias folding, no slug rewrite) |
| Property role | `binding.kind=PROPERTY` + `propertyName` + `unit`/`dimension`/`grain` (optional `expectedCadence`, `aliases`) |
| Service role | `binding.kind=SERVICE` + exact `thingName` + `serviceName` + `resultField` + `unit`/`dimension`/`grain` |

Start from:

- the template `parler-agent/src/test/resources/nearterm/semantics/semantic-profile.template.json`;
- the realistic example `stacking-robot.example.json` in the same folder. It uses the simplified
  key `StackingRobot`; a production profile must use the site taxonomy's literal key (for example
  `"Stacking Robot"` when that is how the taxonomy spells it).

Caps, enforced by `SemanticProfileBuilder`: 64 asset types, 128 roles per asset type, 8 aliases per
role, 256 KiB of UTF-8. Unknown JSON fields are rejected, and any error rejects the whole file.

## 2. Authoring checklist

1. Every `assetTypes` key exists in the loaded taxonomy. Check with
   `ValidateAgentConfigurationRepository` (the `semanticProfile` section).
2. Every unit/dimension pair is in the closed vocabulary and agrees (for example `Cel` belongs to
   `temperature`, `kWh` to `energy`).
3. Every grain is one of `sample`, `cycle`, `batch`, `event`, `window`, `aggregate`; a cadence, if
   set, is a positive ISO-8601 duration such as `PT5S`.
4. PROPERTY: the exact property name the live Thing exposes (no fuzzy match).
5. SERVICE: the exact Thing name, Service name and result field. At use, the live Thing name must
   equal `thingName`.
6. No role may point at a PASSWORD property, a Service with PASSWORD parameters or results, or a
   PASSWORD result field; those fail preflight.
7. Run the offline fixtures and validation before promoting DEV → QA → PROD.

Offline test suite:

```bash
cd parler-agent
./gradlew test --no-daemon -PuseLocalTwxLib=true \
  --tests 'com.thingworx.things.agent.semantics.*' \
  --tests 'com.thingworx.things.agent.taxonomy.*'
```

## 3. Runtime behavior operators must know

### 3.1 Invalid refresh retains the prior valid snapshot

If `/semantics/semantic-profile.json` is edited into an invalid document, a refresh keeps the
previously **loaded** profile and marks it `stale` (`SemanticProfileSnapshot.staleFromPrior`).
Exact-name tools continue to work, and diagnostics report the failure. This is the same retention
model as the taxonomy: an invalid refresh never replaces the active valid snapshot.

Use `RefreshSemanticProfileCache` to reload the file and `GetSemanticProfileDiagnostics` to read
the current status (`snapshotStatus`, `stale`, `digest`, `diagnostics[]`).

### 3.2 Deleting the profile file also retains a stale snapshot until restart

Deleting `/semantics/semantic-profile.json` on a running AgentThing does **not** immediately turn
semantic roles off. A missing file parses as `not_configured`; when a prior loaded profile exists,
the refresh or prompt-cache rebuild keeps that prior profile active as `stale` until the AgentThing
is restarted (or the cache is otherwise rebuilt with no prior snapshot).

To clear semantic roles after removing the file, either restore a valid profile or restart the
AgentThing / ThingWorx so no prior snapshot is retained. Do not assume "file gone means semantics
off" on a live process.

### 3.3 Provenance attach is opt-in and exact

`query_numeric_property_history` attaches semantic provenance (`propertyRoleRef`, `unitRef`,
`grainRef`, `cadenceRef`, profile id/version/digest) to the cached series only when:

1. the live Thing's taxonomy `assetTypeKey` is uniquely proven (`TaxonomyAssetTypeProof`);
2. exactly one PROPERTY role under that key binds the queried `propertyName`; and
3. Property preflight passes.

A missing Thing, an unproven or ambiguous type, or a name collision across types leaves the
provenance absent. Wrong provenance is never preferred over none.

### 3.4 Security boundary unchanged

Profile validity never grants permission. Live Property and Service access still runs under the
current `SecurityContext`, and PASSWORD / protected fields fail preflight.

## 4. Core and App responsibilities

| Owner | Owns |
|---|---|
| Core | schema and caps, vocabulary, snapshot and loader, resolver, preflight, provenance attach, fixtures, diagnostics |
| App | profile id and version, exact taxonomy keys, role ids and aliases, Property/Service bindings, unit/grain/cadence facts, DEV/QA/PROD promotion |

A new role that fits the Property/Service binding contract needs **no** Core Java change. A new
binding kind would require a Core code change.
