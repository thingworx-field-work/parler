# Agent configuration repository

Status: **implemented in `parler-agent`** (extension **`configurationRepository`** FileRepository, `/skills`, `/tools/extended_tools.json`, `/policies/invoke_service.json`, `/taxonomies/type-taxonomy.md`).

This document defines the FileRepository-backed configuration surface for `AgentThing`.

The goal is to make application-level agent configuration easy to author, inspect, validate, and promote. The repository replaces ad-hoc service naming conventions for skills and custom tools, and gives `invoke_service` a clear allow policy for bypassing HITL.

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
| `configurationRepository` | `THINGNAME` | empty | Name of a FileRepository Thing containing AgentThing configuration files. Empty means no repository-backed skills, no taxonomy Markdown, no extended tools, and no `invoke_service` allow rules. |

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
/taxonomies/type-taxonomy.md
/taxonomies/identity-types.json
/semantics/semantic-profile.json
/policies/invoke_service.json
/tools/extended_tools.json
/providers/route_profiles.json
```

Directories are independent. A repository may contain only one subset.

Missing directories or files disable that feature without granting authority:

| Path | Missing behavior |
|------|------------------|
| `/skills` | repository skill count is zero |
| `/taxonomies/type-taxonomy.md` | no direct taxonomy Markdown is injected |
| `/taxonomies/identity-types.json` | when missing/empty/invalid for the configured mode, the affected resolver paths are unavailable; v3 identity array and v3 **`asset-types.json`** are validated independently (see **`CONTRACTS/TAXONOMY_RESOLVER.md`**); legacy prompt-table taxonomy unchanged |
| `/semantics/semantic-profile.json` | on a cold start / empty prior snapshot: semantic-role resolution unavailable; exact-name tools retain existing contracts. On a **running** AgentThing that already loaded a valid profile, deleting or invalidating this file retains the prior snapshot as `stale` until restart (same retention as taxonomy; see `docs/agent/nearterm/semantics-evidence-foundation-app-authoring-walkthrough.md` §3) |
| `/policies/invoke_service.json` | every `invoke_service` call requires HITL unless another existing hard block applies |
| `/tools/extended_tools.json` | no file-backed extended tools are registered |
| `/providers/route_profiles.json` | U7 / G16: no ordered Provider fallback route; Agent keeps the primary `llmApiProviderRef` path and existing Provider-owned rate control. Path locked in SPR-0; load/validate in SPR-4. Profiles reference Provider Thing names only (no credentials). |

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

## Taxonomy Markdown

Optional direct LLM-facing taxonomy prose comes from:

```text
/taxonomies/type-taxonomy.md
```

When present, it is inserted into the **stable** leading system prompt body when **`AgentSettings.taxonomyPromptInjection`** is **`full_table`** (repository Markdown **only** — there is **no** generated Markdown pipe table from a Composer service).

Important boundary:

- `/taxonomies/type-taxonomy.md` is **LLM-visible semantic Markdown only**.
- It is **not** the structured source of truth for application types.
- Structured taxonomy comes from **`/taxonomies/identity-types.json`** and optionally **`/taxonomies/asset-types.json`**: **v2** is a root object with **`version: 2`** (flattened rows for legacy tooling); **v3** is a root JSON array of identity rules and/or a non-empty **`asset-types.json`** object map — the two files load **independently** (warnings for a missing companion, not a global invalidation of the other file). Runtime and **`ValidateAgentConfigurationRepository`** use the same v2/v3 discrimination. See **`docs/agent/taxonomy.md`**, **`docs/agent/AGENT-TAXONOMY.md`**, and **`CONTRACTS/TAXONOMY_RESOLVER.md`**.

Missing file means empty direct taxonomy text. A zero-byte file is treated as empty direct taxonomy text and should be distinguished from missing only in diagnostics. Read failure logs `ERROR` and the file is ignored for that prompt-context refresh.

The file has a 32 KB v1 size limit after UTF-8 decoding and optional UTF-8 BOM removal. Oversized files are ignored with diagnostics; do not truncate Markdown because truncation can break fenced blocks and make the prompt harder to reason about.

Operators are responsible for keeping `/taxonomies/type-taxonomy.md` consistent with **`identity-types.json`** product intent. This topic does not mechanically reconcile Markdown prose against JSON rows.

The `/taxonomies` directory also holds the structured taxonomy files listed above.

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
| `resultTextCapChars` | no | Positive integer: the text cap of this tool's result (the size above which one text field is cut once the result must be reduced). An integer outside 500..50000 is clamped (`CONFIG_VALUE_CLAMPED`); a non-integer or non-positive value skips the entry. It never raises `toolResultSoftCapChars`, and it is clamped to that cap minus 1,024. It is not a capability field: a legacy entry with only `name`, `whenToUse`, `target` and this field stays valid and `risk` does not become required. Agents before `tool-result-budget/1` ignore it. |

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

- Malformed JSON, a root that is not an object, an unsupported or missing `version`, or a `tools` value that is not an array invalidates the entire file.
- A per-entry defect skips only that entry (logged); the other entries still register. Per-entry defects: an entry that is not an object; a missing or invalid `name`; a duplicate name or a collision with a built-in; a missing or empty `whenToUse`; a missing `target`, `target.entityName` or `serviceName`; a non-boolean `hitl`, `playbookSafe` or `executorOnly`; an invalid `resultTextCapChars`; invalid capability metadata; a target that does not resolve, or a service that is missing, PASSWORD-protected or whose schema cannot be built.
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

This payload is **operator and authoring diagnostics**, not normative AlwaysOn / widget client wire. Additive fields
(for example runtime **model-facing suppression** reasons under the **`model-tool-admission-guardrails`** topic in
**`./model-tool-admission-guardrails.md`**) do not require a **`CONTRACTS`** bump unless Parler later promotes snapshot JSON
to a normative external API.

The top-level `features` array is always present, independently of include-options, prompt-cache readiness, repository configuration, or an active turn. Its deterministic order and guarantees are:

| Feature | Guarantee and availability |
| --- | --- |
| `topic-load/1` | Complete bounded `load_topic` text is supported by the Java built-in. Present only when the actual built-in registry contains the loader: both `enableBuiltInTools` and `documentKnowledgeBuiltinsEnabled` must be enabled. A same-named extended tool does not qualify. |
| `active-method-tools/1` | Installed method-recognition behavior bypasses document-turn narrowing for loaded topics/non-document skills, survives approval, and protects active topic bodies or reports the existing context-budget failure. Always present in this runtime, including with document built-ins disabled. It does not override upstream admission, permission, or approval policies and does not guarantee dynamic skill-body retention. |

| `entity-document-scope/1` | The runtime derives a default document scope from the conversation's newest single `resolve_thing` result. Present exactly when `topic-load/1` is, because the Java document built-ins are registered together. It does not mean a given Thing maps to documents; that needs a `ResolveDocumentSet` override or a matching `assetModels` entry (`document-chunk-tools.md` §6.5). |
| `topic-offer/1` | A Java search result with a topic match lifts document-turn narrowing for the rest of the uninterrupted turn, and loadable topic matches carry `loadWith`. Present exactly when `topic-load/1` is. It keeps only upstream-admitted tools, is not an active method, and is not carried over an approval pause. |
| `topic-lane/1` | A Java search scoped to documents can return a top-level `topics` array of methods ranked without document-level evidence, and a non-empty array is a topic offer. Present exactly when `topic-load/1` is. It does not mean a given search finds a topic: the lane needs a document scope and a qualifying match. See `document-chunk-tools.md` §5.3. |
| `search-unit-scope/1` | A Java search that names a unit in `assetContext.thingName`, and has no scope otherwise, establishes that unit's document scope for the current execution segment, and a rejected name adds `UNIT_SCOPE_NOT_ESTABLISHED`. Present exactly when `topic-load/1` is. It does not mean the model supplies a name, or that the scope outlasts the segment. See `document-chunk-tools.md` §6.5. |
| `topic-signal-exact/1` | The topic lane treats a multi-word signal as a whole: all of its tokens must be in the query, and a first word alone earns nothing. Present exactly when `topic-load/1` is. The manual ranker is unaffected. See `document-chunk-tools.md` §5.3. |
| `topic-markdown-file/1` | `load_topic` reads a topic's working text from `<root>/<docId>/topics/<chunkId>.md` and never from `<chunkId>.txt`. Present exactly when `topic-load/1` is. It does not mean the runtime renders, validates or transforms Markdown. See `document-chunk-tools.md` §6.6. |
| `tool-result-json-node/1` | The build supports `AgentSettings.toolResultJsonMode=node`, which delivers a JSON service result as a JSON object under `result` instead of one string. Always present, whatever the topic-loader state; it announces support, not the active mode, which `tools.resultJsonMode` reports. See "Tool result settings" below. |
| `tool-result-budget/1` | `AgentSettings.toolResultSoftCapChars` and `toolResultTextCapChars` and the per-tool `resultTextCapChars` set the budget of a tool result, resolved once when a call starts executing; a result at or under the soft cap is never cut in text, and the first model append never exceeds the soft cap (except an error envelope, and the minimal overflow envelope when an author's classification stamp alone is too large). `tools.resultBudget` shows the values and their sources. Always present. |
| `tool-result-table-recovery/1` | A table too large for the soft cap keeps whole rows, in source order, filled greedily (possibly none), with a `cacheId` on both invocation paths or `recovery: "unavailable"` when no cache exists; cells are never cut. Always present. |
| `document-index-refresh/1` | The `RefreshDocumentKnowledgeIndex` service invalidates this agent's document index so the next search rebuilds it, and the snapshot carries the `documentKnowledge` block. Always present. See `document-chunk-tools.md` §10. |
| `tool-result-completeness/1` | A tool result whose envelope root shows a reduction, by the egress gateway or by the producer (a table sample, a cut page, read-limit evidence), carries a root `completenessNote`, also after a person approves a gated tool. Always present. It does not mean the model reports the reduction. See `CONTRACTS/TOOL_RESULT_COMPLETENESS.md`. |

With the Java loader registered the value is `["topic-load/1","active-method-tools/1","entity-document-scope/1","topic-offer/1","topic-lane/1","search-unit-scope/1","topic-signal-exact/1","topic-markdown-file/1","tool-result-json-node/1","tool-result-completeness/1","tool-result-budget/1","tool-result-table-recovery/1","document-index-refresh/1"]`; otherwise it is `["active-method-tools/1","tool-result-json-node/1","tool-result-completeness/1","tool-result-budget/1","tool-result-table-recovery/1","document-index-refresh/1"]`. The round-6 names are appended last whatever the loader state. A producer reads the topic file name from three states: `topic-load/1` with `topic-markdown-file/1` means `<chunkId>.md`; `topic-load/1` alone is the legacy loader of agents 0.1.251 to 0.1.254, which reads `<chunkId>.txt`; without `topic-load/1` no Java topic loader is registered and no file name is established (built-ins may be enabled later), so the producer publishes both unchanged copies or waits until the loader is registered. No topic file or skill must be loaded to report the installed behavior. Unknown skill front-matter keys remain ignored and are not features. See `document-chunk-tools.md` for the package handoff and loader errors.

### Tool result settings

These `AgentSettings` fields decide how a tool result reaches the model. They are read live; a value that is out of range is adjusted and leaves a `CONFIG_VALUE_CLAMPED` warning.

| Field | Default | Meaning |
| --- | --- | --- |
| `toolResultJsonMode` | `string` | `string`: a JSON service result is one string field, `{status, resultKind:"JSON", result:"<json text>"}` (the shape before this setting existed). `node`: the JSON value is an object under `result`, `{status, resultKind:"JSON", result:{...}}`; a value that cannot be read as JSON stays a string, with no error. A `STRING` result stays a string in both modes. Any other value resets to `string`. Both invocation paths (`invoke_service` and extended tools) honour it. In `node` mode the result obeys the same gateway rules as any object result, including sampling of arrays longer than 20 items: a tool that returns a list should return a table. |

| `toolResultSoftCapChars` | `8192` | The serialized size (UTF-16 code units, escaping, markers, note and classification included) above which a tool result is reduced before the model reads it, and which the first model append never exceeds. 8,192 to 65,536: below the minimum resets to the default, above the maximum clamps. The minimum is 8,192 so a `load_topic` carrier (at most 8,192) is never reduced; 65,536 is a chosen ceiling, not a recommendation. |
| `toolResultTextCapChars` | `2000` | The size above which one text field is cut once a result must be reduced. 500 to 50,000, and at most `toolResultSoftCapChars` minus 1,024. A tool may declare its own `resultTextCapChars`; the soft cap is never raised for a tool or a page. |

The snapshot's **`tools.resultJsonMode`** reports the active mode (`string` or `node`), and **`tools.resultBudget`** reports `{softCapChars, softCapSource, textCapChars, textCapSource, perTool:[{name, textCapChars, source}], warnings}` (`source` is `default`, `setting` or `tool`). The warning `CONFIG_PAGE_EXCEEDS_RESULT_CAP` appears when `documentKnowledgeChunkMaxChars` plus 1,500 exceeds the soft cap; it ignores escaping, so its absence promises nothing.

The whole **`tools`** block is built from the prompt-context snapshot, and that snapshot is built at the agent's first model submit (or by a refresh). Until then the service returns **`tools: null`**; `null` means "the snapshot is not built yet", not "the defaults are in force", and it is the state after an import or a restart. To read the budget and the mode at once, call `GetAgentRuntimeSnapshot` with `options` `{"includeTools": true, "refresh": true}`, which rebuilds the snapshot first; there is no need to run a tool.

**How a result is judged.** One budget is resolved when a call starts executing (also for a call a person approved) and is used by the formatter and the gateway; a setting changed meanwhile does not affect that call. Arrays longer than 20 items are sampled first (with today's exemptions; a table's rows are limited to 20 but each kept row stays whole, nested cells included, and a sampled root array is wrapped with its markers and note); if the result, with its markers, note and classification stamp, is at or under the soft cap it arrives with its text uncut. Otherwise a table (`columns` with `rows` or `sampleRows`) keeps whole rows in source order while the envelope fits; any other result is cut at the text cap, then at 512 characters with arrays of 5, then by the last-resort pass. What still does not fit becomes `resultKind: "RESULT_OVERFLOW"`: `rawChars`, `cacheId` or `recovery: "unavailable"`, `completenessNote`, a hint, and the `columnNames` (table) or `fieldNames` that fit, with `columnNamesOmitted` / `fieldNamesOmitted`; read-limit evidence is kept. Consequences: results up to the soft cap now arrive whole, so a turn's context can grow by up to the soft cap per result (the replay compactor and `llmContextMaxChars` still bound the conversation); a result that no pass can fit, such as a worst-case 25-Thing alert summary of about 13,500 characters, now arrives as the overflow response at the default cap, where before it arrived above the cap, so an installation that needs it whole raises `toolResultSoftCapChars`. An approved result goes through the same gateway: one over the soft cap is now reduced where it used to arrive whole; one that fits arrives whole as before.

When **`includeTools`** is true and the snapshot is non-null, **`tools.modelFacingSuppressed`** (array of **`{ "name", "reason" }`**) lists built-in tools that remain **registered / executable** but are **omitted from the merged LLM tool list** for the current prompt-context state. Today this is used when **`get_agent_skill`** is suppressed because the skill catalog is empty (**`reason`**: **`empty_skill_catalog`**). See **`docs/agent/model-tool-admission-guardrails.md`**.

**Tool visibility diagnostics:** the **`tools`** object reports **model-facing** vs **executor-only** built-ins (**`tools.executorOnly`**, **`tools.executorAliases`**; **`discover_properties`** is always executor-only in the LLM merge, and **`discover_services`** / **`get_service_definition`** follow **`advertiseLegacyServiceDiscoveryTools`**) and, for each extended tool, **`executorOnly`** per manifest plus the derived **`modelAdvertisable`** and **`playbookEligible`** flags. See **`./legacy-discovery-executor-only.md`** §**8**; the example below abbreviates the `tools` object.

**Stable prompt source (external whole-block override):** the **`prompt`** object always includes:

| Field | Meaning |
|-------|---------|
| `stablePromptSource` | `default` or `external_file` — whether the leading stable row uses bundled assembly or a single `/SystemPrompt/*.md` whole-block replacement. |
| `externalSystemPromptPath` | Repository-relative path of the active Markdown file when `stablePromptSource=external_file`; otherwise JSON `null`. |
| `externalSystemPromptFallback` | Diagnostic when the latest refresh attempted external selection but fell back to default (for example `multiple_files: a.md, b.md`); otherwise JSON `null`. |
| `stableSystemPrompt` | Present only when `includePrompt: true`; the effective model-facing leading stable text after refresh (external file text or default assembly). |

**External-file operator note:** whole-block mode does **not** append the default stable suffix blocks. If the candidate prompt should retain product-invariant guidance, the Operator must copy it into the Markdown file explicitly — especially **stable time guidance** (`ParlerTimeAnchor.STABLE_TIME_GUIDANCE`) and the **final-answer evidence rule** (`LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE`). Use `GetAgentRuntimeSnapshot` with `includePrompt: true` while `stablePromptSource=default` to obtain the current default assembled text as an editing baseline before promoting a file to `/SystemPrompt/`.

**Default-path verification (after removing an external override):** delete or move every file out of `/SystemPrompt/`, call `RefreshPromptContextCache`, then `GetAgentRuntimeSnapshot` with `includePrompt: true` and confirm `stablePromptSource=default`, `externalSystemPromptPath=null` and `externalSystemPromptFallback=null`. A non-null fallback means an invalid file was still present; that is a setup failure, not a default-path measurement. The default assembly is the concise form (see `./system-prompt-cache.md` §Stable Prompt Ordering); compare the returned `stableSystemPrompt` structure with the last external candidate before a comparison run.

**Refresh / snapshot diagnostics:** `RefreshPromptContextCache` appends an operator-only `## External system prompt selection` section (after the model-facing stable body) reporting `stablePromptSource`, active path, or fallback reason. The same facts appear in `prompt.diagnostics` lines prefixed with `externalSystemPrompt.`.

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
    "stablePromptSource": "default",
    "externalSystemPromptPath": null,
    "externalSystemPromptFallback": null,
    "stableSystemPrompt": "...",
    "skillCatalog": "...",
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
    ]
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

**`skills[].source`:** `repository` only. **`skills[].status`** and **`tools.extended[].status`:** `registered`. **`tools.builtIn`:** names of tools with a static built-in `ToolDefinition` in the agent registry (same source as `ToolRegistry.getAllDefinitions()` — sorted for display). This is **not** guaranteed to list every name the LLM may see on a given turn: for example, when the playbook registry is loaded the merged LLM tool list can also include **`start_playbook`**, which is appended at merge time and is **not** duplicated in `tools.builtIn`. **`tools.executorAliases`:** map of executor-only **alias** names (replay / historic tool-call rows) to their canonical built-in name — keys are not LLM schema tools; omit or `{}` when none are registered. **`tools.executorOnly`:** sorted names registered with **`ToolRegistry.registerExecutorOnly`** (executable for replay, no merged **`ToolDefinition`**), **excluding** keys that appear in **`tools.executorAliases`** so alias rows are not duplicated (e.g. **`get_entity`** after Option B); omit or `[]` when none.

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

`ValidateAgentConfigurationRepository` success means the repository contents are valid for the next load. It does not mean the current prompt-context snapshot or the current turn has already loaded those contents.

## Removed Extension Points

The configuration repository replaces several older extension points, which no longer exist:

- `AgentSettings.skillRepository`
- service-backed `_skill_*`
- legacy direct-string taxonomy override
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
