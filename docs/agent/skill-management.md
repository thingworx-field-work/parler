# Skill Management

Status: **implemented in `parler-agent`**. The extension registers **repository-backed** skills only under **`AgentSettings.configurationRepository`** (`/skills/<id>/SKILL.md`). `RefreshPromptContextCache` appends registry + configuration-repository diagnostics; catalog and bodies use **`SkillRegistryLoader`**. Legacy **`_skill_*` Service skills** and **`skillRepository`** are **not** used.

Document type: agent extension design. Normative file layout and authoring services: **`./configuration-repository.md`**. This file records refresh semantics, caps, and task-state interactions.

### Refresh failure semantics (normative)

These rules are authoritative for skill, repository, and prompt-context refresh failures.

1. **Per-entry best-effort during refresh / registry build**  
   Any number of failing repository skill entries, or failure of the configured repository source, must **not** abort the overall refresh commit. **Skip** the failing entry or source, **log at ERROR** in the Application Log, and continue with successfully loaded material.

2. **`configurationRepository` setting**  
   - **Empty or unset:** Do **not** scan; this is **not** an error. **No** Application Log line and **no** diagnostic solely for emptiness.  
   - **Non-empty but wrong Thing:** If the Thing **does not exist**, log **ERROR** and skip file-backed discovery. If the Thing **exists** but is **not** a FileRepository Thing (`FileRepositoryThing` in Java), log **ERROR** and skip. **Java resolution order:** look up the Thing by name; if missing → log; if present → ensure it is a `FileRepositoryThing` (type check / cast); if not assignable → log.

3. **Taxonomy reads during stable prompt-context refresh**  
   Asset taxonomy retrieval / table assembly failures are **best-effort** in the same spirit: **log ERROR**, omit cached taxonomy for that refresh, **do not** fail the **entire** snapshot build **only** because taxonomy failed. (See `docs/agent/system-prompt-cache.md` — Refresh Service.)

3b. **GenericThing template-name list and `GetAlertPrompt` during the same refresh**  
   Failures while building the cached GenericThing ThingTemplate name list, or while invoking **`GetAlertPrompt`** (the Markdown block that instructs the LLM on **ThingWorx alerts** tools — see `docs/agent/system-prompt-cache.md`), are **best-effort** the same way: **log ERROR**, omit that portion of the cached suffix for that refresh, **do not** abort the whole snapshot **solely** for that sub-step.

4. **OOM / process-level catastrophe**  
   Not modeled here; no separate Parler normative behavior.

## Purpose

Parler skills are long-form instructions for the LLM. They are not tools. A skill can describe workflow, evidence requirements, checklist rows, answer constraints, and when to use narrower tools.

The implementation registers **repository-backed** skills only:

- AgentThing setting: **`configurationRepository`** (FileRepository Thing name)
- Layout: `/skills/<SkillId>/SKILL.md` (see **`./configuration-repository.md`**)

Service-backed **`_skill_*`** skills are **not** merged into the registry and are **not** loadable through **`get_agent_skill`**.

## Core Invocation Rule

A registered skill is available through **both** invocation paths:

1. **User-explicit slash loading**: `/SkillName`
2. **Model-initiated loading**: `get_agent_skill({"skill_name":"SkillName"})`

This is a hard product requirement. Many ordinary users will not remember skill ids, so the LLM must be able to discover applicable skills from the metadata catalog and load the full body by calling `get_agent_skill`.

The repository source changes how the skill is authored and read internally. It must not change the invocation model.

Slash syntax is only a user convenience path. It is not the primary or exclusive skill activation mechanism.

## Goals

- Author skills as Markdown files under **`/skills/<id>/`** on the **`configurationRepository`** FileRepository.
- Keep progressive disclosure: metadata catalog in the system prompt, full body only through `/SkillName` or `get_agent_skill`.
- Keep task-state v1b.2 dynamic skill merge and HITL continuation compatible with repository-backed short ids.
- Surface repository diagnostics to administrators without turning scan problems into chat UI noise.

## Non-goals

- Do not make skills executable code.
- Do not register FileRepository skills as tools.
- Do not recursively load every file in a skill directory into the prompt.
- No implicit skill auto-loading.
- Do not add a new UI widget error surface for skill repository diagnostics.
- Do not change the direct PASSWORD protection boundary.

## Skill Identity

Every skill has a stable short id:

```text
[A-Za-z][A-Za-z0-9_-]*
```

The short id is used by:

- `/SkillName`
- `get_agent_skill.skill_name`
- task-state v1b.2 dynamic skill merge
- HITL continuation dynamic skill references

For repository-backed skills, the short id is the child directory name under **`/skills/`**.

The short id is case-sensitive. Two ids that differ only by case are distinct by the grammar, but repository scan should warn when it sees case-only collisions because they are confusing across filesystems and user input. `/SkillName` matching remains exact and case-sensitive.

Display titles and frontmatter `name` fields are not durable ids.

## Unified Registry

`SkillRegistrySnapshot` holds `refreshedAt`, `descriptorsByShortId` (repository-backed metadata only), and `diagnostics`. It does not store full `SKILL.md` bodies.

All skill entry points use the same registry snapshot:

1. system prompt skill catalog  
2. `/SkillName` allowlist  
3. `/SkillName` body loading  
4. `get_agent_skill`  
5. task-state v1b.2 dynamic merge body loading  
6. HITL continuation dynamic skill rebuild  

Body loads go through **`SkillRegistryLoader.loadBody`** (repository `LoadText` only).

## Removed: Service-backed `_skill_*` skills

Earlier designs described **`_skill_*`** AgentThing services. Those services are **not** harvested into **`SkillRegistrySnapshot`** and **cannot** satisfy **`get_agent_skill`**. Use **`/skills/<id>/SKILL.md`** on the **`configurationRepository`** FileRepository instead (see **`./configuration-repository.md`**). The Java enum **`SkillSourceKind.SERVICE`** remains only as a defensive type hook.

## Repository-backed skills (`configurationRepository`)

### Agent Setting

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `configurationRepository` | `THINGNAME` (FileRepository) | empty | FileRepository Thing holding `/skills/...`, `/tools/extended_tools.json`, `/policies/invoke_service.json`, and `/taxonomies/type-taxonomy.md`. Empty disables all repository-backed skills, extended tools, repository taxonomy prefix, and file-backed invoke_service policy. |

The configured Thing must exist and must be a `FileRepositoryThing`. Resolution and logging follow **`### Refresh failure semantics (normative)`** above.

When a refresh skips the repository source, the rest of the snapshot still **commits** where possible; only **catastrophic** failures of the refresh assembly itself keep the previous last-good snapshot unchanged.

### Directory Layout

Repository-backed skill discovery scans direct child directories under **`/skills/`**.

Each candidate skill directory must contain:

```text
/skills/<SkillId>/SKILL.md
```

Examples:

```text
/skills/CrossRegionRobotDiagnosis/SKILL.md
/skills/OrderWorkflow/SKILL.md
```

The directory name is the skill short id and must match the short-id grammar. Hidden directories and invalid names are skipped.

The implementation must construct repository paths only from validated `SkillId` plus the fixed suffix `/SKILL.md`. Do not accept `..`, encoded separators, absolute user-supplied paths, or any dynamic file path from the model.

### Standard Skill Directory

The root contract is `SKILL.md`. Other files may exist for human authoring, but Parler reads only `SKILL.md`.

Allowed supporting folders, ignored by Parler:

```text
/skills/<SkillId>/references/
/skills/<SkillId>/assets/
/skills/<SkillId>/scripts/
```

Parler does not auto-load supporting files. If a skill needs supporting content, the author summarizes the required instructions in `SKILL.md`.

### Frontmatter: Simple Key/Value, Not YAML

`SKILL.md` may start with a frontmatter slab:

```markdown
---
name: CrossRegionRobotDiagnosis
title: Cross-region robot diagnosis
description: Use when the user asks to compare robot health, alerts, or operating conditions across regions.
skill_meta_version: 1
---

# Cross-region robot diagnosis

Follow this workflow...
```

This is **not** full YAML.

Frontmatter is the slab between the first two `---` lines at the start of the file, after an optional UTF-8 BOM. Lines inside the slab use the same line-oriented rules as **`KeyValueDescriptionParser`** (shared with service description slabs elsewhere in the agent):

- line-oriented `key: value`
- only the first `:` splits key and value
- keys are lower-cased
- blank lines and lines starting with `#` are ignored
- lines that do not match the simple shape are ignored

Structured YAML constructs are not supported: arrays, nested objects, anchors, and block scalars are ignored as ordinary unsupported lines.

If there is no initial frontmatter slab, metadata is empty and the whole file is the body.

If a second frontmatter-looking block appears later in the Markdown, it is normal body text.

Frontmatter fields:

| Field | Required | Meaning |
|-------|----------|---------|
| `name` | Optional | If present, must equal the directory short id exactly. Mismatch invalidates the skill. |
| `title` | Optional | Display title. Defaults to directory short id. |
| `description` | Recommended | Routing hint, equivalent to `when_to_use`. |
| `when_to_use` | Optional | Routing hint. If both `description` and `when_to_use` exist, `when_to_use` wins. |
| `skill_meta_version` | Optional | Parser/format version; accepted but not interpreted. |

Unknown frontmatter keys are ignored. A `parameters` or tool-schema-like key has no runtime meaning and does not turn a skill into a tool.

If both `description` and `when_to_use` are missing, the skill may still register with an empty hint, but discovery records a warning.

If the Markdown body after frontmatter is empty, the skill may register and return an empty string. This is treated as a successful load with no checklist, matching current `get_agent_skill` task-state semantics.

### Body Text

The full body returned by `get_agent_skill` is the Markdown after frontmatter.

The body may include one `parler-task-checklist-v1` fenced block for task-state v1b.2.

The frontmatter should not be included in the LLM-visible body because the catalog already exposes title and routing hint.

### Repository Scan Caps

Caps (pinned so tests are executable):

| Constant | Value | Unit |
|----------|-------|------|
| `MAX_REPOSITORY_SKILLS` | `100` | registered repository skill descriptors |
| `MAX_SKILL_MD_CHARS` | `100_000` | Java `String.length()` UTF-16 code units |
| `MAX_SKILL_TITLE_CHARS` | `200` | UTF-16 code units |
| `MAX_SKILL_WHEN_TO_USE_CHARS` | `1_000` | UTF-16 code units |
| `MAX_SKILL_DIAGNOSTICS_CHARS` | `20_000` | UTF-16 code units |

Oversized `SKILL.md` files are skipped with diagnostics.

Recommended scan shape:

1. List **`/skills`** (top-level directories).
2. Consider only direct child directories with valid short ids.
3. Process candidates in stable lexical order.
4. For each candidate up to `MAX_REPOSITORY_SKILLS`, verify/read `/skills/<SkillId>/SKILL.md` using FileRepository services.
5. Entries beyond the cap are skipped with one aggregate diagnostic count.

The scan should not perform unbounded recursive listing.

## Duplicate IDs

If two repository directories resolve to the same short id (including case-only collisions), keep one deterministic winner and skip the other with diagnostics. There is **no** Service-backed path that overrides repository ids.

## Cache, Refresh, and Concurrency

Do not put full skill bodies into the stable prompt-context cache.

Fold `SkillRegistrySnapshot` into `PromptContextCacheSnapshot` or an equivalent object committed under the same `_promptCacheLock` and the same success-only replace discipline used by prompt-context refresh.

The rule:

- one lock
- one atomic commit boundary
- **Whole-refresh failure** (an exception propagating out of the snapshot builder after all best-effort sub-steps) keeps the last good prompt-context snapshot and skill registry snapshot
- **Sub-step / per-skill / per-repository-source failures** follow **`### Refresh failure semantics (normative)`**: skip + **ERROR** log + continue; they do **not** by themselves abort the commit once the unified registry and folded refresh path are fully wired
- first submit lazily refreshes under the same lock when the registry is empty
- concurrent first submits may wait on the same lock; double-checked locking under `_promptCacheLock` (see `ensurePromptContextCacheForTurn`) makes duplicate refresh commits structurally single-flight for a given AgentThing instance; correctness remains last-good atomicity if a refresh throws after partial work

Manual `RefreshPromptContextCache` rebuilds:

1. stable prompt-context blocks
2. skill registry metadata and diagnostics

It returns a single human-readable string:

```text
<stable system prompt text>

---
## Skill registry diagnostics

- skills: 5 repository-backed
- skipped invalid directory: /123BadName
```

The delimiter and heading must make clear that diagnostics are not part of the stable prompt text. Prompt assembly never injects diagnostics into the LLM stable row.

If this return shape changes, update `docs/agent/system-prompt-cache.md` in the same change.

In-flight turns use the registry snapshot captured at `buildLlmTurnContext` start. A manual refresh during a turn affects later turns only.

**Discovery cadence:** Repository directory listings under **`/skills`** are harvested **at refresh time** (lazy first LLM submit or `RefreshPromptContextCache`), not re-scanned on every turn. New or edited **`SKILL.md`** files appear in the catalog only after the next successful refresh.

## Skill Catalog and LLM Discovery

The system prompt catalog lists metadata only. Its job is to let the LLM decide whether a skill applies and then call `get_agent_skill` for the full body.

Example:

```markdown
## Agent skills (metadata only - full text via /SkillName or get_agent_skill)

- **Cross-region robot diagnosis** (`CrossRegionRobotDiagnosis`, source `repository`)
  - When: Use when the user asks to compare robot health, alerts, or operating conditions across regions.
- **Order workflow** (`OrderWorkflow`, source `repository`)
  - When: Use when the user asks about order lifecycle or cancellation rules.

To load full instructions for a skill, call built-in tool **get_agent_skill** with JSON {"skill_name":"<id>"} where `<id>` is the short id (same id as `/SkillName`).
```

The model should always use the short id in `skill_name`.

`allowImplicitInvocation` is a reserved AgentSettings field with no runtime effect; skill bodies load only through `/SkillName` or `get_agent_skill`.

## Slash Loading

`/SkillName` parsing uses the unified registry.

Rules:

- only registered ids are stripped from the user message
- unregistered slash-like text stays in the user text
- multiple valid skills load in first-seen order and are deduplicated
- full body load uses **`SkillRegistryLoader`** (`LoadText` on the configured FileRepository)

If a slash-requested skill fails to load:

- log a warning with repository path
- do not throw to the client
- add a compact ephemeral system note for the model if practical, such as:

```text
Skill /Foo was requested but could not be loaded. Continue without that skill or ask the user/admin to refresh the skill repository.
```

This is better than silent omission because the user explicitly requested the skill. It is still not a UI widget error.

## get_agent_skill

`get_agent_skill` keeps the same external schema:

```json
{
  "skill_name": "CrossRegionRobotDiagnosis"
}
```

The executor resolves `skill_name` through the registry snapshot:

1. validate non-empty string
2. lookup descriptor by short id
3. load body via **`SkillRegistryLoader`** (repository `LoadText`)
4. return body string on success
5. return structured JSON error on failure

Recommended error shape:

```json
{
  "status": "error",
  "code": "SKILL_NOT_FOUND",
  "message": "Skill CrossRegionRobotDiagnosis is not registered."
}
```

Useful codes:

| Code | Meaning |
|------|---------|
| `BAD_REQUEST` | Missing/invalid arguments (e.g. empty `skill_name`). |
| `SKILL_NOT_FOUND` | No descriptor for `skill_name` in the unified registry. |
| `SKILL_REGISTRY_UNAVAILABLE` | Prompt-context snapshot (and thus registry) not available — run `RefreshPromptContextCache` or retry after the first LLM submit populates the cache. |
| `SKILL_LOAD_FAILED` | Descriptor exists but body read/invocation failed, frontmatter mismatch at load, or runtime `SKILL.md` over size limit. |

The tool does not emit UI-specific wire frames. The LLM sees the structured error as a tool result and can explain or ask for refresh/admin action.

## Failure Surfaces

### Repository configuration or scan errors

Examples:

- `configurationRepository` Thing not found
- configured Thing is not a FileRepository
- `/skills` listing fails
- invalid directory name
- missing `SKILL.md`
- frontmatter parse failure
- `name` mismatch

Behavior:

- record diagnostics in the refresh result (when the unified registry + diagnostics appendix are implemented)
- log **ERROR** for each skipped repository **source** or **skill entry** (and for missing / wrong-type `configurationRepository` Thing when non-empty); empty `configurationRepository` produces **no** log
- a **successful** refresh commit after partial skips keeps the new snapshot; **whole-refresh** failure keeps the previous last-good snapshot
- do not send a chat UI error automatically

### Runtime body load errors

Examples:

- FileRepository `LoadText` fails
- file was removed after catalog scan

Behavior:

- `get_agent_skill`: return structured JSON error
- `/SkillName`: log warning and optionally add a compact ephemeral system note
- task-state v1b.2: failed `get_agent_skill` remains a tool failure and does not merge checklist rows

### Why no UI widget error by default

Skill repository problems are mostly configuration or authoring problems. Showing them directly in chat would surprise app users and make normal conversations noisy.

Preferred admin surfaces:

- `RefreshPromptContextCache` return diagnostics
- Application Log

## Task-state and HITL Continuation

Task-state v1b.2 stores dynamic skill identity as short ids against the repository-backed registry.

When `get_agent_skill` succeeds:

- v1b generic tool progress marks the `get_agent_skill` call satisfied
- v1b.2 parses the returned body for one `parler-task-checklist-v1` fence
- accepted checklist rows append as dynamic skill rows
- duplicate, invalid, or over-budget checklists reject only the incoming checklist

When a turn enters HITL after dynamic skills were merged:

- pending metadata stores ordered short ids only
- continuation rebuild re-reads current skill bodies from the unified registry
- if a repository skill cannot be re-read, log a warning and continue with available skills
- do not store parsed task items, full skill bodies, or repository file contents in `PendingApprovalRecord`

**Snapshot replay before registry read:** Continuation must replay preserved `slashSkillShortNamesSnapshot` and `dynamicSkillShortNamesSnapshot` into the freshly constructed `AgentTaskState` live runtime lists before registry-backed body re-read. These live lists drive idempotency for post-continuation `get_agent_skill` and nested HITL pending. Preserve the current `recordMergedDynamicSkillShortName` lineage from task-state v1b.2.

Continuation re-reads bodies from the current registry; if a skill file changed on disk between approval and resume, the new file content is what the user gets (same as any other registry refresh).

## Security and Trust

Repository-backed skills are instructions. Repository write permission is prompt-authoring permission.

Security rules:

- Do not treat repository skill files as trusted code.
- Do not execute scripts from skill directories.
- Do not auto-load supporting files.
- Do not use repository skill content to widen PASSWORD protection semantics.
- Do not expose repository file read errors as protected-value events.
- Ignore unknown frontmatter keys; tool-schema-like frontmatter has no runtime effect.

ThingWorx permissions control who can configure the repository and who can edit files in it. Parler defines no administrator roles or deployment ACLs of its own.

## Implementation

Classes:

| Class | Responsibility |
|-------|----------------|
| `SkillRegistrySnapshot` | Immutable snapshot of descriptors and diagnostics (`com.thingworx.things.agent.skillregistry`). |
| `SkillRegistryDescriptor` | Source-neutral descriptor (`SkillSourceKind`). |
| `SkillRegistryBuilder` | Builds registry at refresh from repository scan (`RepositorySkillScanner`) only. |
| `SkillRegistryLoader` | Loads bodies through the snapshot (`LoadText`); used by catalog consumers. |
| `RepositoryReader` | Directory listing / `LoadText` seam; production: `FileRepositoryRepositoryReader` uses `BrowseDirectory` for listing. |
| `SkillMarkdownParser` | Parses `SKILL.md` frontmatter (same key rules as Service descriptions; implementation duplicated to keep lightweight tests free of ThingWorx-heavy static init). |

Repository source should expose a fake-friendly seam:

```java
interface RepositoryReader {
    InfoTable getFileListing(String path, String nameMask) throws Exception;
    String loadText(String path) throws Exception;
}
```

Production builds `RepositoryReader` from a resolved FileRepository Thing. Registry builds use `FileRepositoryRepositoryReader.forConfiguration` (`processServiceRequest`: the current user or the System user needs ServiceInvoke on `BrowseDirectory` / `LoadText`); `get_agent_skill` loads the skill body with `forCurrentUser` (`processAPIServiceRequest`: the calling user needs Visibility on the repository and ServiceInvoke on `LoadText`). Tests inject a fake reader. This mirrors the fake-friendly seam used by table export.

Call sites:

1. `AgentBaseThing.AgentSettings`: **`configurationRepository`** (`THINGNAME` FileRepository).
2. Prompt-context refresh path: rebuild prompt context plus `SkillRegistrySnapshot` under the same lock and commit boundary.
3. `AgentThing.buildLlmTurnContext`: use registry descriptors.
4. `AgentThing.buildSlashLoadedSkillsBlock`: load body through registry.
5. `GetAgentSkillExecutor`: lookup/load through registry.
6. `SkillChecklistParser.unionFromSlashSkills`, `SkillChecklistContinuationMerge`, and `TaskProgressV1bDynamicMerge`: call **`SkillRegistryLoader.loadBody`**.

## Tests

### Unit tests

- Repository-backed valid `SKILL.md` registers and loads body without frontmatter.
- Repository directory invalid id is skipped.
- Missing `SKILL.md` is skipped.
- Frontmatter `name` mismatch invalidates the skill.
- Optional BOM and CRLF do not break frontmatter detection.
- `description` maps to `whenToUse`; `when_to_use` wins when both exist.
- Empty body after frontmatter loads as empty string.
- Case-only repository duplicates produce diagnostics.
- Repository skill cap and file-size cap produce diagnostics.
- `get_agent_skill` returns structured JSON error for missing or failed skill.
- `/SkillName` allowlist strips only registered ids.
- HITL continuation replays dynamic short ids into live runtime lists before body re-read.

### Manual checks on a ThingWorx server

- Configure **`configurationRepository`** to a real FileRepository with two skills under **`/skills/`**.
- Run `RefreshPromptContextCache` and inspect diagnostics.
- Ask a prompt that should use a repository skill through model-initiated `get_agent_skill` without `/SkillName`.
- Ask with `/RepoSkillName` and confirm body affects the turn.
- Edit/save AgentThing and verify registry reload or lazy refresh behavior.
- Remove `SKILL.md`, refresh, and verify the skill disappears with a diagnostic.
