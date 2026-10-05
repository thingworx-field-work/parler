# Playbook Directory Packaging

**Status:** Implemented
**Scope:** `parler-agent` configuration repository playbook discovery, validation, diagnostics, docs, and fixtures

**Relationship to other docs:** The repository layout described here is the playbook packaging model the runtime loader uses. `docs/agent/playbook-engine.md` describes the Playbook document and engine.

## 1. Background

An earlier packaging model used a central catalog plus one or more playbook body files:

```text
/playbooks/playbooks.json
/playbooks/<id>.playbook.json
```

That model caused friction in training and app-developer workflows. Every new playbook required two coordinated edits:

1. Add or update the playbook JSON file.
2. Add or update the corresponding row in `playbooks.json`.

That double-entry model is fragile. A user can upload a valid playbook file and still fail to load it because the central catalog was not updated, or update the catalog with the wrong `playbookPath`, id, title, or input schema. This is especially painful during training, where the developer is already writing JSON-heavy DAG definitions and should not also maintain a second JSON registry by hand.

Skills already have a more natural authoring model: each unit can be reasoned about as its own file or directory artifact. Playbooks follow the same model: a playbook is an independently uploadable package, not a row in a global catalog.

## 2. Design Goal

Playbooks are discovered by directory, with no central catalog:

```text
/playbooks/<playbook-id>/playbook.json
```

Each immediate child directory under `/playbooks` represents one candidate playbook package. The only effective runtime file in that directory is exactly:

```text
playbook.json
```

Examples:

```text
/playbooks/cross_asset_pair_health/playbook.json        # effective
/playbooks/cross_asset_pair_health/README.md            # ignored
/playbooks/cross_asset_pair_health/playbook_v1.json      # ignored
/playbooks/cross_asset_pair_health/playbook_v2.json      # ignored
/playbooks/cross_asset_pair_health/fixtures/sample.json  # ignored
/playbooks/playbooks.json                                # ignored
/playbooks/cross_asset_pair_health.playbook.json         # ignored
/playbooks/.scratch/playbook.json                        # ignored (dot-prefixed package dir)
```

The goal is a clean mental model:

- To add a playbook, add one directory containing `playbook.json`.
- To remove a playbook, remove that directory or remove its `playbook.json`.
- To draft alternate versions, keep them outside the effective filename.
- To activate a different draft, rename or copy it to `playbook.json`.

There is no runtime version suffix. If developers want `playbook_v1.json`, `playbook_v2.json`, or branch-specific variants during authoring, that is a source-control or local-file concern. The runtime loads only one active file per playbook directory.

## 3. Non-Goals

- No support for the old central `/playbooks/playbooks.json` format.
- No migration layer that reads both old and new formats.
- No warning or error when legacy files exist. They are simply not part of discovery.
- No support for multiple active versions of the same playbook id.
- No recursive playbook discovery below nested subdirectories.
- No broad change to the playbook DAG schema or engine semantics.
- **`whenToUse`** stays a **single JSON string** (`optString` parse); there is no array-of-strings form.

The clean break is intentional: carrying both formats would preserve the confusion the directory layout removes.

## 4. Discovery Contract

The repository loader discovers playbooks using this exact pattern:

```text
/playbooks/*/playbook.json
```

Discovery rules:

1. Enumerate the immediate child directories of `/playbooks` (same repository listing seam as skill discovery: `RepositoryReader.getFileListing` over `/playbooks`, as `RepositorySkillScanner` does for `/skills`).
2. **Exclude** any child whose directory name is empty or whose **first code unit is `.` (U+002E)**. Dot-prefixed package dirs (for example `/playbooks/.scratch/`) are authoring-only sandboxes: **no** `playbook.json` load attempt and **no** loader diagnostics (same silence class as legacy flat files).
3. For each remaining child directory, attempt to load `<child>/playbook.json`.
4. Ignore any other files or nested directories under those packages.
5. Sort candidate directory names lexicographically (**Unicode code-point / `String::compareTo`**) before loading attempts, so runtime snapshots and diagnostics are deterministic.
6. Validate each discovered `playbook.json` independently.
7. Load all valid playbooks; report diagnostics for discovered but invalid `playbook.json` files only for **non–dot-prefixed** package directories that passed step 3.

Files and directories outside this pattern **do not produce playbook-loader diagnostics**. This includes old catalog files, draft playbook files, README files, fixtures, and any authoring scratch files.

**Deterministic ordering:** sort discovered package directory names lexicographically by Unicode code-point order of the directory segment (simple `String::compareTo` style), independent of host default locale, so snapshots and logs match across JVMs.

## 5. Id And Path Rules

The directory name and playbook document id must be the same:

```text
/playbooks/cross_asset_pair_health/playbook.json
```

```json
{
  "id": "cross_asset_pair_health"
}
```

This is a hard validation rule for discovered `playbook.json` files. If the directory is `cross_asset_pair_health` but the document says `"id": "other_id"`, the playbook is invalid and skipped with a diagnostic that includes the exact path.

Reasoning:

- It gives operators a fast way to inspect the repository.
- It prevents invisible aliasing.
- It avoids needing a separate `playbookPath` field.
- It makes duplicate ids structurally difficult.

The id character set is conservative: lower-case letters, digits, and underscores, per the validator's id rule.

## 6. Single-File Metadata

Metadata that the old catalog held lives in `playbook.json`.

The effective playbook document contains enough metadata for both runtime admission and LLM-facing tool descriptions:

```json
{
  "schema": "parler-playbook-v1",
  "id": "cross_asset_pair_health",
  "title": "Cross Asset Pair Health",
  "description": "Compare two assets using alerts, current summaries, and recent property trends.",
  "whenToUse": "Use when the user asks to compare the operational health of two assets, or which of two assets is more urgent or degraded.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "assetA": { "type": "string" },
      "assetB": { "type": "string" },
      "window": { "type": "string", "default": "24h" }
    },
    "required": ["assetA", "assetB"]
  },
  "execution": {
    "mode": "sync"
  },
  "budgets": {
    "maxToolCalls": 20
  },
  "nodes": [],
  "finalNode": "final"
}
```

There is no separate catalog row and no authored `playbookPath`. The internal `PlaybookCatalogEntry` is derived from `playbook.json` and the discovered path.

### 6.1 Merged root JSON — fields and validation delta

Catalog-era playbooks split metadata (`playbooks.json` row) from the DAG document (`*.playbook.json`). The directory layout **merges** them into one `playbook.json` root object. **`PlaybookDocument.parse`** reads **both** the DAG fields and the former catalog fields from that root:

| Area | JSON fields | Notes |
|------|-------------|--------|
| Schema / identity | `schema`, `id` | `schema` MUST equal the constant `PlaybookIds.SCHEMA_V1` (`parler-playbook-v1`). `id` MUST equal the parent directory name (§5). |
| Former catalog metadata | `title`, `description`, `whenToUse`, `inputSchema`, `execution` | `whenToUse` is a **scalar string** (`optString` parse). `inputSchema` and `execution` are objects; use empty `{}` when absent, matching catalog defaults. |
| DAG body | `budgets`, `nodes`, `finalNode` | Unchanged semantics; existing `PlaybookDocument` graph parsing applies. |

**Admission and diagnostics (normative):**

- **`PlaybookValidator.validateDocument`** (with the admission pass invoked for each discovered file) enforces the metadata that catalog validation used to check:
  - **Invalid package (diagnostic, skipped):** JSON parse failure; `schema` ≠ `parler-playbook-v1`; `id` missing / grammar / **directory mismatch**; DAG validation failures; unsafe tool/operation references; missing **`finalNode`** / other graph rules.
  - **Metadata for LLM / tool admission:** non-blank **`title`** for snapshot and routing surfaces; **`inputSchema`** as a JSON object; **`description`** and **`whenToUse`** MAY be empty strings (`optString` with default `""`).
- **Collection cap:** at most **`PlaybookIds.MAX_PACKAGED_PLAYBOOKS` (32)** packages may load successfully. When more than 32 valid directories exist after sorting, the registry **loads the first 32** in lexicographic directory-name order and emits a **diagnostic** for the cap (operators see truncation; no silent drop of already-loaded rows).
- **`inputSchema`:** absent or non-object JSON values parse as **`{}`** (via `JSONObject.optJSONObject` semantics). Parameters are not validated against it as a JSON Schema; **`start_playbook`** receives the caller JSON.

### 6.2 `PlaybookRegistrySnapshot` flags (partial success)

Directory discovery validates **per package**, so the registry supports partial success:

- **`isLoaded()`** is **true** iff **at least one** playbook was loaded successfully after discovery (success count ≥ 1). It MUST **not** become true merely because `/playbooks` was readable if every package failed validation.
- **`reservedSlashIds()`** returns exactly the set of **successfully loaded** playbook ids (the same keys as the in-memory success maps). **Invalid** discovered package ids appear **only** in `diagnostics()`; they MUST **not** reserve slash names and MUST **not** suppress repository skills with the same short id.

**Consumers of these flags:**

- `AgentThing.getMergedToolDefinitions` — register `start_playbook` only when `isLoaded()` is true (implies ≥ 1 valid playbook).
- `AgentThing.buildLlmTurnContext` — skill short-id suppression uses `reservedSlashIds()` (must not include failed packages).
- `AgentThing.tryExecutePlaybookSlashTurn` — returns early when `!isLoaded()`; playbook slash routing uses `reservedSlashIds()` only.
- `AgentThing` `GetAgentRuntimeSnapshot` playbooks section — `loaded`, `catalogIds`, `documentIds`, `reservedSlashIds`, and per-row paths reflect **successful** loads; diagnostics list failed packages.
- `SkillRegistryBuilder.build(..., playbookRegistry.reservedSlashIds())` — receives only successful playbook ids.
- `ConfigurationRepositoryLoadedFileCaptures` — union paths for captures reflect discovered effective `playbook.json` files (not legacy catalog pair).

**Normative empty catalog:** If zero playbooks load successfully, `isLoaded()` MUST be **false**. Then there MUST be **no** model-facing `start_playbook` tool registration and **no** playbook slash ids reserved — even when `diagnostics()` is non-empty due to failed sibling packages.

## 7. Validation Semantics

Validation distinguishes between ignored files and discovered invalid playbooks:

Ignored without diagnostics:

- `/playbooks/playbooks.json`
- `/playbooks/*.playbook.json`
- `/playbooks/<id>/README.md`
- `/playbooks/<id>/playbook_v1.json`
- `/playbooks/<id>/fixtures/*`
- any file not named exactly `/playbooks/<id>/playbook.json`

Reported as diagnostics:

- `/playbooks/<id>/playbook.json` cannot be parsed as JSON.
- document id is missing or invalid.
- document id does not match `<id>`.
- required metadata for LLM/tool admission is missing.
- input schema is invalid.
- DAG nodes fail existing playbook validation.
- referenced tools or operations fail existing playbook safety/admission checks.

The loader prefers partial success. If one discovered playbook is invalid and two are valid, the valid playbooks still load. The invalid playbook appears in diagnostics with its path and error summary.

If `/playbooks` exists but **zero** packages yield a successfully loaded playbook (including “no candidate dirs after dot-prefix filter” and “every discovered `playbook.json` invalid”), the runtime MUST treat the registry as **not loaded** for tool and slash purposes: **`isLoaded() == false`**, no `start_playbook` in the merged tool list, and **`reservedSlashIds()` empty**. Diagnostics MAY still list per-path failures from invalid siblings.

## 8. Runtime Snapshot And Diagnostics

`GetAgentRuntimeSnapshot` exposes enough information to prove what was loaded without inspecting repository files manually. The playbook snapshot area:

```json
{
  "playbooks": {
    "loaded": true,
    "discoveryPattern": "/playbooks/*/playbook.json",
    "catalogIds": ["cross_asset_pair_health"],
    "documentIds": ["cross_asset_pair_health"],
    "documents": [
      {
        "id": "cross_asset_pair_health",
        "path": "/playbooks/cross_asset_pair_health/playbook.json",
        "title": "Cross Asset Pair Health"
      }
    ],
    "diagnostics": []
  }
}
```

The term `catalog` no longer implies a loaded `playbooks.json` file: **`catalogIds`** is the derived list of **successfully loaded** ids. **`discoveryPattern`** and per-document **`path`** show the directory-discovery model.

`ValidateAgentConfigurationRepository` validates discovered `playbook.json` files only. It does not warn about legacy files or unrelated files under `/playbooks`.

**Authoring `items[]` severity:** invalid discovered packages (JSON parse failure, root schema rejection, document validation, unreadable file where a load was attempted) MUST surface as **`severity: "error"`** and increment **`summary.errors`**. The **32-package cap** and **diagnostics-list truncation** (when very many invalid packages would flood diagnostics) remain **`severity: "warning"`**. Runtime **`playbooks.diagnostics[]`** remains a list of human-readable strings derived from the same structured diagnostic records (`path`, `code`, severity).

The collection tool captures:

- the discovered playbook paths,
- loaded playbook ids,
- invalid discovered playbook diagnostics,
- the `discoveryPattern`.

It does not collect ignored draft files.

## 9. Contract coupling

The detailed `GetAgentRuntimeSnapshot` playbook subsection is not duplicated in `CONTRACTS/API_CONTRACT.md`. If snapshot field names are promoted to normative wire text, update **`CONTRACTS/*.md`** and bump **`CONTRACTS/CONTRACT_VERSION.md`** in the **same** change as the Java that emits those fields.

## 10. Implementation

- `PlaybookRegistryBuilder` — directory discovery via `RepositoryReader.getFileListing("/playbooks", "")` (the same visibility-aware listing seam `RepositorySkillScanner` uses for `/skills`); filter, sort, then `RepositoryTextLoads.loadText` per `playbook.json`; builds runtime entries with deterministic ordering and partial success (§6.2).
- `PlaybookIds` — `PLAYBOOK_ROOT = "/playbooks"`, `PLAYBOOK_FILE_NAME = "playbook.json"`, `DISCOVERY_PATTERN = "/playbooks/*/playbook.json"`, `MAX_PACKAGED_PLAYBOOKS = 32`, `SCHEMA_V1`, plus helpers that derive the path from an id and the id from a path.
- `PlaybookCatalogEntry` — derived runtime metadata, not a row from `playbooks.json`.
- `PlaybookValidator` — validates document `id` against the directory id and the merged metadata (§6.1), plus the DAG, input schema, tool, operation, and budget guards.
- `AgentThing` — runtime snapshot rows and `start_playbook` description text.
- `ConfigurationRepositoryLoadedFileCaptures` — captures discovered effective playbook files.

## 11. Tests

Unit and integration tests cover:

1. A single valid `/playbooks/<id>/playbook.json` is discovered and loaded.
2. Multiple valid playbook directories are discovered in deterministic order.
3. `/playbooks/playbooks.json` is ignored without warning.
4. `/playbooks/<id>.playbook.json` is ignored without warning.
5. Draft files such as `/playbooks/<id>/playbook_v2.json` are ignored.
6. Nested files such as `/playbooks/<id>/drafts/playbook.json` are ignored.
7. Invalid discovered `playbook.json` reports a path-specific diagnostic.
8. Directory id and document id mismatch reports a path-specific diagnostic.
9. One invalid playbook does not prevent other valid playbooks from loading.
10. **Zero successfully loaded playbooks** (empty tree, all-invalid packages, or only dot-prefixed dirs) results in `isLoaded() == false` and **no** model-facing `start_playbook` tool advertisement.
11. A dot-prefixed sibling directory (for example `/playbooks/.draft/playbook.json`) is ignored **without** diagnostics.
12. Runtime snapshot reports `discoveryPattern` and loaded paths for successful packages.
13. `ValidateAgentConfigurationRepository` reports only discovered invalid playbooks (non–dot-prefixed packages).

Manual validation on a ThingWorx server:

1. Upload one playbook directory only, without `playbooks.json`.
2. Refresh the agent configuration.
3. Confirm runtime snapshot lists the playbook.
4. Run a prompt that should select the playbook.
5. Add a second playbook directory, refresh, and confirm both are available.
6. Remove one playbook directory or its `playbook.json`, refresh, and confirm it disappears.
7. Add ignored draft files and confirm they do not affect diagnostics.
