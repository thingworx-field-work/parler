# Playbook catalog routing (model-facing symmetry)

Status: **implemented** — loaded playbooks appear beside skills in the leading-stable workflow catalog, and `start_playbook` is registry-driven.

---

## 0. Pointers and touchpoints

### 0.1 Related docs

| Doc | Why |
| --- | --- |
| `docs/agent/playbook-engine.md` | Playbook runtime; §3.1–3.2 describe what the LLM sees. |
| `docs/agent/playbook-directory-packaging.md` | Merged `playbook.json` metadata. |
| `docs/agent/skill-management.md` | Skill registry and workflow catalog source. |
| `docs/agent/system-prompt-cache.md` | Leading-stable catalog composition and dynamic prompt layers. |
| `docs/agent/model-tool-admission-guardrails.md` | Conditional tool advertisement precedent. |

### 0.2 Scope boundary

**In scope:** the same short id under `/skills/<id>` and `/playbooks/<id>` — the playbook wins; the skill is not model-visible; the playbook **is** model-visible via the catalog and the dynamic `start_playbook` tool.

**Out of scope:** a skill and a playbook with **different ids** but similar purpose (e.g. `region_health` vs `cross_region_health`). App developers MAY deploy both deliberately. Routing between them is **configuration / product choice**, not agent-runtime policy. There are no overlap heuristics, no `supersedesSkill` field, and no warnings for different-id pairs.

### 0.3 Java sites

| Area | Path / type |
| --- | --- |
| Leading stable LLM context | `AgentThing.assembleLeadingStableSystemPrompt` / `LeadingStablePromptComposer` |
| Skill catalog | `SkillRegistryCatalogFormatter` |
| Combined catalog | `skillregistry.AgentWorkflowCatalogFormatter` |
| Playbook catalog | `playbook.PlaybookCatalogFormatter` |
| Playbook registry | `PlaybookRegistrySnapshot`, `PlaybookCatalogEntry`, `PlaybookRegistryBuilder` |
| Merged LLM tools | `AgentThing.getMergedToolDefinitions` |
| `start_playbook` definition | `PlaybookStartToolDefinitionBuilder` |
| Same-id shadowing | `RepositorySkillScanner` — `PLAYBOOK_SKILL_NAME_CONFLICT` |
| Runtime snapshot | `AgentThing` — `GetAgentRuntimeSnapshot` |

### 0.4 Contract scope

- No `CONTRACTS/*` surface. Catalog Markdown and dynamic tool descriptions are agent-internal.
- There is no `get_playbook` tool and no inlined playbook JSON in the prompt.

### 0.5 Prompt path

`AgentThing.assembleLeadingStableSystemPrompt` renders the combined catalog from the loaded skill/playbook snapshots and passes it to `LeadingStablePromptComposer`. `resolveConversation` receives `skillCatalogForNewThread = null` and `ephemeralCatalogIdx` is always `-1`: the catalog is refreshed with the stable row rather than appended per turn.

**Param validation boundary:** the **advertised** `start_playbook` JSON Schema has no per-playbook property keys. Execution validates `params` against each playbook's `inputSchema` from `playbook.json`.

---

## 1. Background

| Shape | Planner | Model routing | Executor |
| --- | --- | --- | --- |
| **Skill** | LLM | Leading-stable catalog + `get_agent_skill` / slash | Agent loop |
| **Playbook** | Runtime DAG | Leading-stable catalog + registry-driven `start_playbook` / structured slash | `PlaybookRunner` |

Without a playbook catalog, a playbook that shadows a same-id skill would be invisible to natural-language routing: the skill is removed from the model surface, so the playbook side must be visible.

---

## 2. Same-id and different-id behavior

### 2.1 Same id — agent responsibility

When `/skills/foo/SKILL.md` and `/playbooks/foo/playbook.json` both exist:

| Surface | Behavior |
| --- | --- |
| Skill registry | Skill **not** registered (`PLAYBOOK_SKILL_NAME_CONFLICT` logged at scan time) |
| Skill catalog | No entry for `foo` |
| `/foo` slash (skill) | Skill body not loaded; reserved for the playbook |
| `get_agent_skill({"skill_name":"foo"})` | **Not found** |
| Playbook catalog | Lists `foo` |
| `start_playbook` | Mentions `foo` from the registry |
| Structured slash `/foo {json}` | Playbook |

### 2.2 Different id — not agent responsibility

If `/skills/bar/` and `/playbooks/baz/` both load, both appear in the combined catalog. The agent does not judge whether they overlap. Developers who want playbook-only routing use **the same id** (or remove the skill from the repository).

---

## 3. Goals

1. **Playbook catalog parity:** when the playbook registry is loaded, the prompt carries metadata-only Markdown for every loaded playbook (`id`, `title`, `whenToUse`).
2. **Dynamic `start_playbook`:** description and `playbook_id` helper text come from the loaded catalog; no hardcoded id list; generic `params` object.
3. **Same-id invariant:** a shadowed skill is fully **non-model-visible** — not in the catalog, slash, or `get_agent_skill`. The playbook is the only model-visible workflow for that id.
4. **Operator snapshot:** `prompt.playbookCatalog`.
5. **Execution unchanged:** same-id conflict rules, slash priority, and the structured slash path behave as before; only advertisement changes.

---

## 4. Non-goals

- `supersedesSkill` or any cross-id linkage field on `playbook.json`.
- Authoring warnings for different-id `whenToUse` overlap.
- Automatic skill deletion or demotion at runtime.
- Resolving routing when skill and playbook use **different ids**.
- `get_playbook`; provider playbooks; tool admission budget work.
- A Playbooks block in `llm_tool_routing_guide.txt`; the catalog plus the dynamic tool description carry playbook routing.

---

## 5. Design

### 5.1 Combined workflow catalog

- `PlaybookCatalogFormatter` — sorted `PlaybookCatalogEntry` → Markdown or empty.
- `AgentWorkflowCatalogFormatter` — concatenates the skill and playbook sections into one stable catalog block. `LeadingStablePromptComposer` owns its final placement.

**Section order:** skills first (existing header), then `---`, then playbooks when the playbook registry is loaded and non-empty. Either section may be omitted alone.

**Playbook section shape:**

```markdown
## Agent playbooks (metadata only — start via start_playbook or structured slash)

- **Title** (`playbook_id`)
  - When: …
  - Start: **start_playbook** `{"playbook_id":"…","params":{…}}` or slash `/playbook_id {"…"}`.

Call **start_playbook** once per turn to run a playbook. Playbook steps are executed by the runtime, not by loading skill text.
```

Rules:

- One bullet per loaded playbook; the `When:` line is omitted if blank.
- Playbook `description` is omitted (skill-catalog precedent: `id`, `title`, `whenToUse` only).
- The catalog does not tell the model to prefer playbooks over **unrelated** skills (different ids).

Composition reads the playbook registry snapshot and the cached skill registry only; there is no per-turn repository I/O.

### 5.2 Dynamic `start_playbook`

`PlaybookStartToolDefinitionBuilder.build(PlaybookRegistrySnapshot registry)` supplies the tool in `getMergedToolDefinitions`.

1. Registry not loaded → `null`; the tool is omitted.
2. **`playbook_id`:** the description lists **every** loaded id (sorted). This enumeration is routing-critical and never silently drops ids; any id that cannot fit in the tool schema still appears in the per-turn playbook catalog (§5.1).
3. **Top-level description:** purpose line + per-playbook `• {id}: {whenToUse}`, with **whenToUse** prose truncated to **240** chars per line and the combined descriptive block capped at **4000** chars. Truncation never removes or hides callable ids.
4. **`params`:** generic object only:

```json
{
  "type": "object",
  "description": "Inputs for the selected playbook_id per that playbook's inputSchema; invalid params fail at start_playbook.",
  "additionalProperties": true
}
```

**Provider-schema conformance:** the generic `params` object is tested against every provider adapter path Parler uses for `start_playbook` (Anthropic Messages `input_schema`, OpenAI/Azure Chat Completions function tools, including strict mode) — the tests assert the emitted `ToolDefinition` is one the provider accepts, not merely one the builder produces.

### 5.3 Operator snapshot

When `includePrompt=true`, `prompt.playbookCatalog` carries the `PlaybookCatalogFormatter` output (playbook section only, parallel to `prompt.skillCatalog`): an empty string when the registry is loaded with zero entries, `null` when `includePlaybooks=false` or the registry is not loaded.

Same-id conflicts are visible as `PLAYBOOK_SKILL_NAME_CONFLICT` error log lines at skill scan time and as human-readable lines in `SkillRegistrySnapshot.diagnostics()`; the snapshot has no structured list of shadowed skills.

### 5.4 No hardcoded playbook ids

The `start_playbook` definition and the routing guide do not hardcode demo playbook ids (`cross_region_health`, `cross_asset_pair_health`) or demo param property keys; the model learns playbook ids only from the loaded registry. `playbook-engine.md` §3.1 and the `start_playbook` section of `all-tools.md` describe the registry-driven behavior.

---

## 6. Tests

| Test | Asserts |
| --- | --- |
| `PlaybookCatalogFormatterTest` | Sort, empty, omit blank `whenToUse` / `description` |
| `AgentWorkflowCatalogFormatterTest` | Skills then playbooks; either section alone |
| `PlaybookStartToolDefinitionBuilderTest` | All ids in `playbook_id` description; no demo param keys; unloaded → null; truncation never drops ids |
| `PlaybookStartToolDefinitionProviderWireTest` | Chat Completions + Anthropic wire nodes carry generic `params`; provider-acceptable shape |
| `PlaybookSameIdShadowingTest` | `/skills/foo` + `/playbooks/foo`: playbook in catalog; skill absent; `get_agent_skill(foo)` fails; slash `/foo` → playbook |

Regression: `RepositorySkillScannerTest`, playbook slash tests.

```bash
cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true
```

---

## 7. Prompt assembly

```text
[Leading stable system row]
  ## Agent skills …
  ---
  ## Agent playbooks …

[Ephemeral — stripped after turn]
  ## Skill instructions (slash) …
  Host scope

[History]
[User message]
```

---

## 8. Summary

Loaded playbooks appear beside skills in the leading-stable workflow catalog, and `start_playbook` is registry-driven. Same id: the skill stays off the model surface and the playbook appears. Different id: both may appear — that is allowed and out of scope.
