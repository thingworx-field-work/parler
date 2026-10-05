# Agent configuration repository

Status: **implemented in `parler-agent`** (extension **`configurationRepository`** FileRepository, `/skills`, `/playbooks`, `/tools/extended_tools.json`, `/policies/invoke_service.json`, `/taxonomies/type-taxonomy.md`).

This document defines the FileRepository-backed configuration surface for `AgentThing`.

The goal is to make application-level agent configuration easy to author, inspect, validate, and promote. The repository replaces ad-hoc service naming conventions for skills and custom tools, and gives `invoke_service` a clear allow policy for bypassing HITL.

There is **no migration requirement** for this topic. Existing development environments may be updated directly.

## Design Principles

- **One configuration root.** A single AgentThing setting points to a FileRepository Thing that holds skills, tool registry files, taxonomy prompt files, and policies.
- **Files for authoring, Java for enforcement.** Files declare intent. Runtime checks still use live ThingWorx metadata, ServiceDefinition, PASSWORD protection, HITL, and existing argument coercion.
- **Fault-oriented behavior.** Missing or invalid governance files never grant extra authority. If a policy cannot be parsed or validated, affected calls require HITL.
- **No last-good policy.** Policy parse failures do not keep using a prior successful policy.
- **No semantic guessing.** `invoke_service` allow rules and extended tool HITL settings are explicit. The agent does not infer read-only safety from service names.
- **Repository-only skills.** Skills are long-form files, not ThingWorx services.
- **Concrete execution targets.** Every extended tool executes a real service on a concrete Thing. Inherited services are allowed because execution still occurs on the concrete Thing.

## Agent Setting

Add or replace the AgentThing configuration setting:

| Setting | Type | Default | Meaning |
|---------|------|---------|---------|
| `configurationRepository` | `THINGNAME` | empty | Name of a FileRepository Thing containing AgentThing configuration files. Empty means no repository-backed skills, no playbooks, no taxonomy Markdown, no extended tools, and no `invoke_service` allow rules. |

The configuration field should use ThingWorx repository-selection aspects, following the existing FileRepository configuration pattern:

```java
@ThingworxFieldDefinition(
        name = "configurationRepository",
        description = "File Repository containing AgentThing configuration files",
        baseType = "THINGNAME",
        aspects = { "thingTemplate:FileRepository", "friendlyName:Configuration Repository" })
```

The `THINGNAME` base type and `thingTemplate:FileRepository` aspect improve Composer authoring and validation. Runtime code must still resolve the configured Thing and verify it is a FileRepository before use.

The old `skillRepository` setting is removed in this proposal. No fallback or migration behavior is required.

When `configurationRepository` is non-empty:

- resolve the name to a Thing using platform APIs
- verify it is a FileRepository Thing
- log `ERROR` when the Thing is missing or not a FileRepository
- treat the repository as unavailable for all repository-backed features

When `configurationRepository` is empty, do not log an error.

## Repository Layout

Canonical layout:

```text
/skills/<SkillId>/SKILL.md
/playbooks/<PlaybookId>/playbook.json
/taxonomies/type-taxonomy.md
/taxonomies/identity-types.json
/policies/invoke_service.json
/tools/extended_tools.json
```

Directories are independent. A repository may contain only one subset.

Missing directories or files disable that feature without granting authority:

| Path | Missing behavior |
|------|------------------|
| `/skills` | repository skill count is zero |
| `/playbooks` | playbook catalog count is zero; `start_playbook` is not advertised unless another valid playbook source is loaded |
| `/taxonomies/type-taxonomy.md` | no direct taxonomy Markdown is injected |
| `/taxonomies/identity-types.json` | when missing/empty/invalid for the configured mode, the affected resolver paths are unavailable; v3 identity array and v3 **`asset-types.json`** are validated independently: a valid identity file can load when asset types are absent or invalid, and a valid asset-type file can load when identity rules are absent or invalid. Maintainers can cross-check **[`CONTRACTS/TAXONOMY_RESOLVER.md`](../../CONTRACTS/TAXONOMY_RESOLVER.md)** in this repository; legacy prompt-table taxonomy unchanged |
| `/policies/invoke_service.json` | every `invoke_service` call requires HITL unless another existing hard block applies |
| `/tools/extended_tools.json` | no file-backed extended tools are registered |

## Skills

Skills are repository files only:

```text
/skills/<SkillId>/SKILL.md
```

Service-backed `_skill_*` registration is removed. This simplifies authoring and removes the confusing overlap where both skills and custom tools were ThingWorx services distinguished only by a prefix.

`SkillId` uses the existing short-id grammar:

```text
[A-Za-z][A-Za-z0-9_-]*
```

Skill discovery:

1. Browse `/skills`.
2. Keep child directories whose names match the short-id grammar.
3. Read `/skills/<SkillId>/SKILL.md`.
4. Parse frontmatter and body using the existing repository skill rules.
5. Register valid skill metadata into the skill registry.

Invalid skill entries are skipped with diagnostics. A bad skill file must not prevent valid sibling skills from registering.

`get_agent_skill` loads only repository-backed skills from the current registry. Slash skills and LLM-discovered skills use the same registry.

Discovery edge cases:

- `/skills/SKILL.md` directly under `/skills` is ignored; skills must live under a short-id directory.
- non-directory entries and child directories whose names do not match the short-id grammar are ignored without diagnostics.
- a valid short-id directory without `SKILL.md` is skipped with a warning diagnostic.
- companion files under a skill directory are ignored in v1.
- if frontmatter declares a skill id/name that conflicts with the directory id, the directory id is authoritative and the skill is skipped with a warning diagnostic.
- case-only path conflicts are invalid for that skill id and are skipped with diagnostics.

## Playbooks

Playbooks are repository files only:

```text
/playbooks/<PlaybookId>/playbook.json
```

Each playbook lives in its own short-id directory. The only active file in that directory is `playbook.json`.
Development variants such as `playbook_v1.json` or `playbook_draft.json` may exist outside the uploaded repository, but
the runtime ignores them.

`PlaybookId` uses the same short-id style as the playbook schema id:

```text
[A-Za-z][A-Za-z0-9_]{0,63}
```

Playbook discovery:

1. Browse `/playbooks`.
2. Consider non-hidden child directories as package candidates.
3. Read `/playbooks/<PlaybookId>/playbook.json`.
4. Validate the JSON with the same playbook validator used by runtime execution.
5. Register valid playbook metadata into the playbook catalog.

Invalid playbook entries are skipped with diagnostics. A bad playbook file must not prevent valid sibling playbooks from
registering.

**Model-facing catalog:** loaded playbooks appear in the per-turn playbook catalog and in the dynamic **`start_playbook`**
tool description. **`GetAgentRuntimeSnapshot`** with `includePrompt=true` may include `prompt.playbookCatalog` (parallel
to `prompt.skillCatalog`). When the same id exists under `/skills/<id>/` and `/playbooks/<id>/`, the skill is shadowed
and only the playbook is model-visible — see **`parler`** `docs/agent/playbook-catalog-routing.md`.

Discovery edge cases:

- `/playbooks/playbook.json` directly under `/playbooks` is ignored; playbooks must live under a short-id directory.
- legacy aggregate files such as `/playbooks/playbooks.json` are ignored.
- non-directory entries and hidden child directories are ignored.
- a package directory without `playbook.json`, or with an empty `playbook.json`, is skipped quietly.
- a package directory whose name does not match the id grammar is skipped with diagnostics when its `playbook.json` is present.
- if the root `id` inside `playbook.json` conflicts with the directory id, the playbook is skipped with diagnostics.

The playbook JSON syntax is covered in Appendix J. The workshop reference lives at:

```text
workshop/day3/playbooks/cross_asset_pair_health/playbook.json
```

## Taxonomy Markdown

Optional direct LLM-facing taxonomy prose comes from:

```text
/taxonomies/type-taxonomy.md
```

When present, it is inserted into the **stable** leading system prompt body when **`AgentSettings.taxonomyPromptInjection`** is **`full_table`** (repository Markdown **only** — there is **no** generated Markdown pipe table from a Composer service).

Important boundary:

- `/taxonomies/type-taxonomy.md` is **LLM-visible semantic Markdown only**.
- It is **not** the structured source of truth for application types.
- Structured taxonomy comes from **`/taxonomies/identity-types.json`** and optionally **`/taxonomies/asset-types.json`**: **v2** identity taxonomy is a root object with **`version: 2`** (flattened rows for legacy tooling); **v3** identity taxonomy is a root JSON array of identity rules, and v3 asset taxonomy is a non-empty **`asset-types.json`** object map — the two files load **independently** (warnings for a missing companion, not a global invalidation of the other file). Runtime and **`ValidateAgentConfigurationRepository`** use the same v2/v3 discrimination. Maintainer references: **[`docs/agent/taxonomy.md`](../../docs/agent/taxonomy.md)**, **[`docs/agent/AGENT-TAXONOMY.md`](../../docs/agent/AGENT-TAXONOMY.md)**, and **[`CONTRACTS/TAXONOMY_RESOLVER.md`](../../CONTRACTS/TAXONOMY_RESOLVER.md)** in this repository.

Missing file means empty direct taxonomy text. A zero-byte file is treated as empty direct taxonomy text and should be distinguished from missing only in diagnostics. Read failure logs `ERROR` and the file is ignored for that prompt-context refresh.

The file has a 32 KB v1 size limit after UTF-8 decoding and optional UTF-8 BOM removal. Oversized files are ignored with diagnostics; do not truncate Markdown because truncation can break fenced blocks and make the prompt harder to reason about.

Operators are responsible for keeping `/taxonomies/type-taxonomy.md` consistent with **`identity-types.json`** product intent. This topic does not mechanically reconcile Markdown prose against JSON rows.

The plural `/taxonomies` directory is reserved for future taxonomy files.

## Invoke Service Allow Policy

Path:

```text
/policies/invoke_service.json
```

This file grants bypass-HITL permission for `invoke_service`.

It is **allow-only**:

- no `defaultAction`
- no `action`
- a matching rule means allow / bypass HITL
- no matching rule means require HITL
- missing file means require HITL
- invalid JSON or invalid rule shape means the entire policy is invalid and every `invoke_service` call requires HITL

Example:

```json
{
  "version": 1,
  "rules": [
    {
      "id": "allow-datatable-reads",
      "priority": 100,
      "description": "Allow common DataTable read services.",
      "match": {
        "entityTypes": ["Thing"],
        "entityNames": ["*"],
        "serviceNames": ["GetDataTableEntries", "QueryDataTableEntries"]
      }
    }
  ]
}
```

Rule fields:

| Field | Required | Meaning |
|-------|----------|---------|
| `id` | yes | Stable diagnostic id. Must be non-empty and unique within the file. |
| `priority` | yes | Integer. Lower values match first. |
| `description` | no | Human-readable explanation for operators. |
| `match.entityTypes` | yes | Array of non-empty string patterns. |
| `match.entityNames` | yes | Array of non-empty string patterns. |
| `match.serviceNames` | yes | Array of non-empty string patterns. |

Matching semantics:

- Sort rules by ascending `priority`; tie-break by file order.
- First match wins.
- Patterns are case-sensitive.
- Only glob `*` is supported.
- `*` is a character-level wildcard; it does not have special dot-segment or path-segment semantics.
- No regex.
- Missing `match`, missing `match.*` fields, empty match arrays, blank pattern strings, duplicate rule ids, or unsupported `version` make the entire policy invalid.
- No omitted match field means wildcard; write `["*"]` explicitly.
- Match after `ServiceTargetEntityTypeResolver` and parameter normalization have produced the effective `entityType`, `entityName`, and `serviceName`.
- `entityTypes` values are the same root service-target entity type names accepted by `invoke_service` after resolver normalization. Unknown values make the policy invalid.
- The special `me` value has no meaning in policy `match.entityNames`; use the resolved AgentThing name or `*`.

Fault-oriented policy behavior:

- No last-good policy cache.
- Policy parse or validation failure logs `ERROR` and grants no bypass permission.
- Runtime policy-matching exceptions make the current call require HITL.
- Existing hard blocks still win: PASSWORD input protection, invalid parameters, unresolved service metadata, or other direct safety checks cannot be bypassed by this policy.

This policy does not control extended tools. It only applies to the generic `invoke_service` tool.

## Extended Tools

Path:

```text
/tools/extended_tools.json
```

This file replaces `_tool_*` prefix scanning. It defines which concrete Thing services should be exposed as LLM tools.

Example:

```json
{
  "version": 1,
  "tools": [
    {
      "name": "region_health_snapshot",
      "title": "Region health snapshot",
      "whenToUse": "Use when the user asks to compare current operational health between regions.",
      "target": {
        "entityName": "me",
        "serviceName": "RegionHealthSnapshot"
      },
      "hitl": false
    }
  ]
}
```

Tool fields:

| Field | Required | Meaning |
|-------|----------|---------|
| `name` | yes | LLM tool name. Grammar: `[A-Za-z][A-Za-z0-9_]{0,63}`. Do not allow `-` in v1 because provider function-name rules vary. |
| `title` | no | Human-readable name. If absent, use `name` in diagnostics. |
| `whenToUse` | yes | LLM-facing routing description; trim result must be non-empty. |
| `target.entityName` | yes | Concrete Thing name, or the exact special value `me`. |
| `target.serviceName` | yes | Service to execute on the concrete Thing. |
| `hitl` | no | Boolean. Defaults to `true`. Only explicit `false` bypasses HITL for this extended tool. |
| `risk` | no for legacy; required with capability fields | Governed risk: `READ_ONLY`, `MUTATING`, `DESTRUCTIVE`, or `ADMIN`. |
| `purpose` | no | Stable business purpose for diagnostics and snapshots. |
| `dryRun` | no | Support flag and real Service parameter when supported. |
| `idempotency` | no | `NONE` or `CALLER_KEY` with a real Service parameter. |
| `dataClassification` | no | Declared data classes for governed Provider eligibility. |
| `admission` / `enabled` | no | Model-advertisement and runtime availability gates. |

`me` semantics:

- `target.entityName: "me"` resolves to the current AgentThing name.
- Only exact lowercase `me` is supported.
- No aliases such as `self`, `${me}`, `.`, or empty string.
- Diagnostics should report both the configured value and the resolved Thing name.

Registration rules:

1. Resolve `target.entityName` to a concrete Thing (`me` first if used).
2. Verify `target.serviceName` exists in the Thing's effective services, including inherited ThingTemplate or ThingShape services.
3. Read the live `ServiceDefinition`.
4. Build the LLM tool JSON schema from the ServiceDefinition.
5. Omit the tool when `name` conflicts with a built-in tool or another extended tool.
6. Omit the tool from registration when direct PASSWORD protection applies:
   - PASSWORD input parameter
   - PASSWORD scalar result type
   - declared PASSWORD result column when a result DataShape is discoverable
7. Omit the tool when schema generation fails.

When a result DataShape cannot be discovered at registration time, static PASSWORD column detection may be incomplete. Runtime PASSWORD protection remains the final enforcement layer for actual calls.

Extended tool registration is a prompt-context snapshot. If the target service definition changes after registration, the currently active prompt may still list the previously registered tool until the next prompt-context refresh. Call-time ServiceDefinition lookup, parameter coercion, and direct PASSWORD protection remain authoritative for actual execution.

Execution rules:

- Extended tools with `hitl: true` share the same HITL queue, pending approval, resume, correlation, and argument-snapshot model as `invoke_service`; the difference is the registered tool metadata and resolved target.
- The service executes on the resolved concrete Thing.
- Existing parameter coercion, DATETIME handling, InfoTable conversion, result serialization, and PASSWORD masking rules remain Java runtime responsibilities.
- `hitl` defaults to `true`.
- `hitl: false` bypasses HITL only for this registered extended tool and its declared target service.
- `hitl: false` does **not** grant `invoke_service` permission for the same service. `invoke_service` still uses `/policies/invoke_service.json`.
- Capability-bearing `MUTATING` Services require HITL and are not current Playbook targets; `DESTRUCTIVE` and
  `ADMIN` fail closed with `CAPABILITY_POLICY_BLOCKED` until a separately reviewed admit path exists.
- Capability metadata does not grant ThingWorx permissions. Dry-run and caller-key declarations are validated
  against the live ServiceDefinition and rechecked on the approval path.

HITL pending semantics:

- Pending records store the tool call id, arguments, resolved entity name, resolved service name, and HITL decision context from enqueue time.
- Pending records are in-memory in v1. They are not rebuilt from `AgentMessageStream`, `_conversations`, or repository files after AgentThing/JVM restart.
- If the process restarts while an approval is pending, the old `pending_id` is invalid; there is no cross-restart HITL resume in this topic.
- Resume does not re-read `/tools/extended_tools.json` or reinterpret the tool registry.
- Resume still uses live platform metadata to execute the target service. If the Thing, service, parameters, or direct PASSWORD checks no longer validate, the resumed call fails with a structured tool error.

Authorization overlap:

- The same concrete service may be exposed as an extended tool and also matched by `invoke_service` allow policy.
- These are intentionally independent bypass surfaces.
- Revoking one does not revoke the other.
- v1 diagnostics may show them separately; v1 does not require an aggregated effective-authorization report.

Invalid `extended_tools.json` behavior:

- Malformed JSON, unsupported `version`, duplicate tool names, non-boolean `hitl`, missing required fields, or invalid top-level shape invalidates the entire file.
- When the file is invalid, no extended tools are registered.
- Log `ERROR` and report diagnostics.

## Runtime Services for Authoring Tools

The configuration repository should be inspectable through ordinary ThingWorx services on the AgentThing. These services are for operators and external authoring tools. They are not LLM built-in tools.

### `GetAgentRuntimeSnapshot`

Purpose: return a compact JSON snapshot of the current AgentThing runtime configuration and prompt-related state.

Input:

| Parameter | BaseType | Default | Meaning |
|-----------|----------|---------|---------|
| `options` | `STRING` | `{}` | UTF-8 JSON text: optional flags (see below). |

Supported `options` fields:

```json
{
  "includePrompt": false,
  "includeSkills": true,
  "includeTools": true,
  "includePolicies": true,
  "includeTaxonomy": false,
  "refresh": false
}
```

Behavior:

- `refresh: true` rebuilds the prompt-context snapshot before reading it.
- `includePrompt: true` may return large text and should be explicit.
- Do not return raw secrets, raw tool arguments, chain-of-thought, or hidden provider payloads.
- Access control is the normal ThingWorx service permission model. This topic does not add a custom authorization layer.

Result: UTF-8 **JSON text** returned through a ThingWorx **`STRING`**-typed result (same encoding rules as **`ValidateAgentConfigurationRepository`**).

This snapshot is **operator/authoring diagnostics**, not normative UI wire. Additive diagnostic fields may appear across
releases without changing the UI client protocol.

Example shape:

```json
{
  "agent": {
    "name": "SCPA_Agent",
    "extensionVersion": "0.1.114"
  },
  "configurationRepository": {
    "thingName": "SCPA_ConfigRepository",
    "status": "ok"
  },
  "prompt": {
    "stableSystemPrompt": "...",
    "skillCatalog": "...",
    "playbookCatalog": "...",
    "diagnostics": []
  },
  "skills": [
    {
      "id": "region_health",
      "source": "repository",
      "path": "/skills/region_health/SKILL.md",
      "title": "Region health",
      "whenToUse": "...",
      "status": "registered"
    }
  ],
  "tools": {
    "advertiseLegacyServiceDiscoveryTools": false,
    "builtIn": ["query_property_history"],
    "executorAliases": {
      "query_numeric_property_history": "query_property_history",
      "query_value_stream_property_history": "query_property_history"
    },
    "executorOnly": ["get_entity"],
    "extended": [
      {
        "name": "region_health_snapshot",
        "target": {
          "resolvedEntityName": "SCPA_Agent",
          "serviceName": "RegionHealthSnapshot"
        },
        "hitl": false,
        "executorOnly": false,
        "status": "registered"
      }
    ],
    "modelFacingSuppressed": []
  },
  "policies": {
    "invoke_service": {
      "path": "/policies/invoke_service.json",
      "status": "loaded",
      "ruleCount": 1
    }
  },
  "taxonomy": {
    "typeTaxonomyMarkdown": {
      "path": "/taxonomies/type-taxonomy.md",
      "status": "loaded",
      "charCount": 1200
    },
    "assetTaxonomyTable": {
      "status": "loaded",
      "rowCount": 20
    }
  }
}
```

**`configurationRepository.status` values:** `ok` | `unavailable` | `not_configured` (Thing missing / wrong type / empty setting).

**`policies.invoke_service.status` values:** `loaded` | `invalid` | `missing`.

**`taxonomy.typeTaxonomyMarkdown.status` values** (file `/taxonomies/type-taxonomy.md` only; not the combined taxonomy system block): when `includeTaxonomy` is true and the agent has no **`configurationRepository`** state on the snapshot, **`not_configured`**. When a repository is configured: **`missing`** | **`empty`** | **`loaded`** | **`oversized`** | **`read_error`** | **`unavailable`** (repository Thing not usable). **`empty`** includes a file that is only a UTF-8 BOM and/or only whitespace after BOM strip.

**`taxonomy.assetTaxonomyTable.status` values:** `empty` | `loaded`.

**`skills[].source`:** `repository` only. **`skills[].status`** and **`tools.extended[].status`:** `registered`.

**`tools.builtIn`** lists advertised built-ins visible in the prompt-context snapshot. **`tools.executorAliases`** is a
map keyed by executor alias name with the canonical built-in name as the value. **`tools.executorOnly`** explains
compatibility names that still execute but are not necessarily advertised. Extended tool
entries can also report **`executorOnly`**. **`tools.modelFacingSuppressed`** is diagnostic-only and helps explain why a
runtime-capable tool was not sent to the model.

**`agent.extensionVersion`:** string from the extension JAR manifest when the agent class is loaded from a JAR; JSON **`null`** when unavailable (typical IDE / non-JAR runs).

### `ValidateAgentConfigurationRepository`

Purpose: validate a FileRepository layout using the same parsers as runtime, without changing prompt-context cache state.

Input:

| Parameter | BaseType | Default | Meaning |
|-----------|----------|---------|---------|
| `repositoryName` | `STRING` | empty | Optional FileRepository Thing name. Empty means use this agent’s **`configurationRepository`** setting. |

Result: UTF-8 **JSON text** as a ThingWorx **`STRING`**-typed result (parse as JSON on the client).

Example shape:

```json
{
  "repository": {
    "thingName": "SCPA_ConfigRepository",
    "status": "ok"
  },
  "summary": {
    "errors": 0,
    "warnings": 1,
    "skillsRegistered": 2,
    "extendedToolsRegistered": 1,
    "invokeServicePolicyRules": 4
  },
  "items": [
    {
      "severity": "warning",
      "path": "/skills/bad_skill/SKILL.md",
      "code": "SKILL_MISSING_WHEN_TO_USE",
      "message": "Skill is skipped because routing metadata is missing."
    }
  ]
}
```

Validation is read-only. It must not update the prompt-context cache, tool registry, or policy state.

Validation must reuse the same repository reader, parsers, and metadata validators as runtime registration. It should not implement a looser parallel parser that can report success for files the runtime would reject.

## Refresh Diagnostics

`RefreshPromptContextCache` appends a diagnostics appendix after the stable prompt body: first **`## Skill registry diagnostics`** (from **`SkillRegistrySnapshot.formatDiagnosticsMarkdown()`**). When the agent has a non-empty **`configurationRepository`** setting (so a **`ConfigurationRepositoryState`** exists on the committed snapshot), it appends **`## Configuration repository diagnostics`** from **`PromptContextCacheSnapshot.ConfigurationRepositoryState.formatDiagnosticsMarkdown()`**.

Example (**skill registry** block):

```text
## Skill registry diagnostics

- skills: 3 repository-backed
- skipped invalid directory name: /badId
```

Example (**configuration repository** block):

```text
## Configuration repository diagnostics

- repository: ok
- extended tools: 2 registered
- invoke_service policy: loaded, 4 rules
- type taxonomy markdown: loaded (1200 chars)
```

Invalid policy example:

```text
## Configuration repository diagnostics

- repository: ok
- extended tools: **invalid** `extended_tools.json` — no extended tools registered
- invoke_service policy: **invalid** — all invoke_service calls require HITL
```

The diagnostics appendix is for operators. It is not part of the stable system prompt unless explicitly documented elsewhere.

Diagnostic output should distinguish at least:

- repository unavailable
- file missing
- validation failed
- fault-oriented fallback active

Severity values are `error`, `warning`, and `info`. Diagnostic codes should be stable enough for logs and authoring tools, but v1 does not require a closed global code registry.

## Prompt and Runtime Lifecycle

Repository-backed skills, taxonomy Markdown, and extended tool registration participate in the prompt-context refresh lifecycle:

- lazy first submit when the prompt-context snapshot is empty
- explicit `RefreshPromptContextCache`
- AgentThing restart or edit/save that causes a fresh runtime snapshot

`invoke_service` allow policy is evaluated at the HITL decision point. It must fail closed to HITL when the current policy file is missing, invalid, or does not match. Implementations may cache a successfully parsed policy only within the active in-memory AgentLoop execution, not as a last-good policy across turns or approval continuations.

Each turn constructs its LLM tool list from the prompt-context-cached extended tool registry. Invalid registry files produce no extended tools in that snapshot. The tool execution path uses the registered resolved target for that turn.

A logical turn means one user message through that turn's final assistant message. A logical turn may span multiple AgentLoop executions when HITL pauses the run and approval later resumes it. Tool-list construction is per logical turn from the prompt-context snapshot; policy cache is per active in-memory AgentLoop execution. These are intentionally different scopes, and implementations must not extend the policy cache across an approval pause.

This creates two intentional lifecycle scopes:

- **Prompt-context scope:** skills, taxonomy Markdown, and extended tools.
- **Policy decision scope:** `invoke_service` allow policy.

The two scopes are not atomic with each other. Operators who edit multiple repository files together should run `RefreshPromptContextCache` after changes are in place before expecting prompt-context-scoped files to affect new turns.

The runtime reads `/policies/invoke_service.json` at the `invoke_service` HITL decision point. A successfully parsed policy may be cached only for the active in-memory AgentLoop execution. The policy cache is not stored in `PendingApprovalRecord`; after an approval continuation starts a new AgentLoop execution, any later `invoke_service` HITL decision reads policy again. Process restart clears both policy cache and in-memory pending approvals.

`ValidateAgentConfigurationRepository` success means the repository contents are valid for a future load. It does not mean the current prompt-context snapshot or the current turn has already loaded those contents.

## Breaking Changes from Current Behavior

This proposal intentionally removes several older extension points:

- `AgentSettings.skillRepository`
- service-backed `_skill_*`
- legacy direct-string taxonomy override (removed before this document’s current baseline)
- prefix-scanned `_tool_*`

Replacement surfaces:

| Removed | Replacement |
|---------|-------------|
| `skillRepository` | `configurationRepository` |
| `/<SkillId>/SKILL.md` | `/skills/<SkillId>/SKILL.md` |
| `_skill_*` services | repository `SKILL.md` files |
| legacy direct-string taxonomy | `/taxonomies/type-taxonomy.md` (optional Markdown) + `/taxonomies/identity-types.json` (structured taxonomy) |
| `_tool_*` service scan | `/tools/extended_tools.json` |

No migration behavior is required.

## Non-goals

- YAML support.
- Last-good policy cache.
- Deny/block rules.
- Parameter-level `invoke_service` policy.
- Regex matching.
- Automatic read-only inference.
- UI editor for repository files.
- Making runtime snapshot services available as LLM built-in tools.
- Moving structured asset taxonomy authoring guidance out of this topic (see **`docs/agent/taxonomy.md`**).
- Atomic file-write or rename requirements for repository authoring.
- Aggregated effective-authorization reports.
- Closed diagnostic code registry.
- Cross-validation between taxonomy Markdown and structured **`identity-types.json`** rows (operators reconcile manually).

## Implementation Notes

- Runtime reads **`configurationRepository`** through the shared **`RepositoryReader`** abstraction (same code paths as validation).
- **`InvokeServiceAllowPolicy`** and **`ExtendedToolsManifest`** use strict JSON parsing at the file level.
- **`SkillRegistryBuilder`** tolerates per-entry failures so one bad **`SKILL.md`** does not block sibling skills.
- Bundled and canonical operator docs describe repository-only skills and **`/tools/extended_tools.json`** extended tools.
- **`ProtectedValuePolicy`** and extended-tool registration use resolved **`ServiceDefinition`** metadata for PASSWORD boundaries (not legacy service-name heuristics).
- Task-state and HITL continuation carry enqueue-time resolved targets for extended tools with **`hitl: true`**.
- Custom ThingWorx services use ordinary names; the LLM-visible tool name comes from **`extended_tools.json`**.
