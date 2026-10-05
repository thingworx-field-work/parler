# Repository skills for the AI Agent

Agent **skills** are long-form instructions for the LLM. The shipped Parler extension registers **repository-backed** skills only: **`SKILL.md`** under **`/skills/<SkillId>/`** on the **`configurationRepository`** FileRepository (see **`./configuration-repository.md`**).

- **Extended LLM tools** (replace legacy `_tool_*`): **`./CUSTOMIZED-TOOLS.md`**
- **Agent loop and tool list:** **`./AGENT-CONTEXT.md`**
- **Refresh semantics, caps, task-state:** **`./skill-management.md`**

---

## 1. Layout and discovery

| Item | Rule |
|------|------|
| **Repository root** | `AgentSettings.configurationRepository` → FileRepository Thing |
| **Layout** | `/skills/<SkillId>/SKILL.md` |
| **Short id** | Directory name `<SkillId>`; grammar **`[A-Za-z][A-Za-z0-9_-]*`** (`SkillShortIdGrammar`) |
| **Discovery** | `RepositorySkillScanner` during **`RefreshPromptContextCache`** / lazy first LLM submit; metadata stored in **`PromptContextCacheSnapshot.skillRegistry`** |

Service-backed **`_skill_*`** AgentThing services are **not** merged into the registry and **cannot** satisfy **`get_agent_skill`**.

---

## 2. Frontmatter (`SKILL.md`)

Same line-oriented **`key: value`** slab as documented in **`skill-management.md`** (between leading `---` lines). Keys are normalized to lower case.

Minimum routing metadata: **`when_to_use`** (or **`description`**; if both exist, **`when_to_use`** wins). Optional **`title`**, **`skill_meta_version`**, optional **`name`** (must match directory id).

---

## 3. Per-turn behavior

1. **Registry** — metadata from **`PromptContextCacheSnapshot.skillRegistry`**.
2. **Catalog** — Markdown from **`SkillRegistryCatalogFormatter`** (metadata only); continuing threads get a temporary catalog row stripped after the turn (**`docs/agent/system-prompt-cache.md`**).
3. **`/SkillName`** — bodies loaded via **`SkillRegistryLoader.loadBody`**; whitelisted tokens stripped from user text.
4. **`get_agent_skill`** — same loader; returns LLM-visible body (frontmatter stripped) or structured JSON error (`SKILL_NOT_FOUND`, `SKILL_REGISTRY_UNAVAILABLE`, `SKILL_LOAD_FAILED`, …).

---

## 4. Built-in tool `get_agent_skill`

| Field | Value |
|-------|-------|
| **Arguments** | `{"skill_name":"<shortId>"}` — short id only (same as **`/SkillName`**). |
| **Result** | Full Markdown body for the LLM, or structured error JSON. |

---

## 5. Settings (`AgentSettings`)

| Field | Role |
|-------|------|
| **`configurationRepository`** | FileRepository Thing for `/skills/`, `/tools/extended_tools.json`, `/policies/invoke_service.json`, `/taxonomies/type-taxonomy.md`. |
| **`allowImplicitInvocation`** | Reserved; bodies load via **`/`** or **`get_agent_skill`** only today. |

---

## 6. Source files

| Piece | Location |
|-------|----------|
| Registry build / scan | `skillregistry/` (`SkillRegistryBuilder`, `RepositorySkillScanner`, …) |
| Body load | `SkillRegistryLoader.java` |
| Slash parse | `SkillSlashParser.java` |
| Tool executor | `GetAgentSkillExecutor.java` |
| Turn wiring | `AgentThing.java`, `AgentToolContext.java` |

---

## 7. `invoke_service`

Skills are **not** LLM tools. Use **`get_agent_skill`** (or **`/`** + catalog) rather than **`invoke_service`** for loading skill text.

---

## 8. Authoring task checklists (`parler-task-checklist-v1`)

A checklist lists the evidence a turn must show; it is not a second planner.

- **`match.resultKind`** works only for tools whose **success** result is a structured JSON envelope with a declared result kind (for example `invoke_service`, `fetch_cached_result`). Do not put `match.resultKind` on **`get_agent_skill`**: its success result is the skill body as plain text, so no stable result kind exists. If a plain-text tool truly needs result-kind matching, change that tool to return a structured success envelope first.
- Do not use **`match.targetName`** to express a hierarchy label (for example `USA`, `Germany`) on `query_entities_by_taxonomy`. `targetName` comes from resolved tool arguments, not from result content. Use a clear requirement `id` and a description per region, and rely on declaration order for multi-region flows.
- **Dynamic skill merge is forward-only.** A checklist loaded through `get_agent_skill` affects only tool calls made after the merge; earlier calls are not re-matched. Loading the same skill again in the same turn is skipped (`DYNAMIC_SKILL_ALREADY_MERGED`, log only). A body with no checklist fence, including an empty body, is a successful load with no new checklist (`DYNAMIC_SKILL_NO_CHECKLIST`). Multiple fences or schema errors (`DYNAMIC_SKILL_CHECKLIST_INVALID`), an id that duplicates an existing requirement (`DYNAMIC_SKILL_DUPLICATE_ID`), or a merge that would exceed the checklist budget (`DYNAMIC_SKILL_BUDGET_EXCEEDED`) reject only the incoming checklist; existing task state is unchanged.
- Task state records progress and evidence summaries only — never raw rows, property values, protected values, hidden prompts, or chain-of-thought.
