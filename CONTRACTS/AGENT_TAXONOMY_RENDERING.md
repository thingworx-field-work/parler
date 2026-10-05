# Agent taxonomy Markdown rendering (normative)

**Scope:** Optional Markdown loaded from the AgentThing **`configurationRepository`** FileRepository path **`/taxonomies/type-taxonomy.md`**, when that file is present, readable, and within the documented size cap. This file is the **normative source** for how that **repository-derived** fragment is trimmed and placed into the **stable** leading system prompt body (see `parler-agent` **`LeadingStablePromptComposer`** and **`docs/agent/system-prompt-cache.md`**).

**Non-scope:** There is **no** generated Markdown pipe table from a Composer-overridable **Infotable taxonomy-table** service on **`AgentThing`** (legacy assembly removed). Structured taxonomy (**`/taxonomies/identity-types.json`**) is consumed for resolver tools, **`TaxonomyRow`** projection, and Playbook injection — not as a Markdown table in the stable prompt. Wire JSON to `parler-ui`, AlwaysOn payloads, and chart blocks — those are covered elsewhere in `CONTRACTS/`.

**Field semantics** for application taxonomy (membership, identity rules, **`criticalProperties`**, resolver tools): **`docs/agent/AGENT-TAXONOMY.md`**, **`CONTRACTS/TAXONOMY_RESOLVER.md`**.

---

## 1. Repository fragment (optional)

When non-blank UTF-8 text exists at **`/taxonomies/type-taxonomy.md`** within the v1 size cap, implementations **must**:

1. Trim leading and trailing whitespace on the logical file string.
2. Strip a leading UTF-8 BOM when present.
3. Place the result into the prompt-context snapshot’s **`taxonomySystemBlock`** when **`AgentSettings.taxonomyPromptInjection`** is **`full_table`** (legacy name: “full” stable suffix mode without a generated table — repository Markdown only).

When the file is missing, empty, unreadable, or exceeds the documented cap, **`taxonomySystemBlock`** **must** be empty for that segment. **No** fallback to removed services or synthetic tables.

The platform **does not** repair unclosed Markdown fences in the file-derived segment.

---

## 2. Prompt cache and operational note

Any normative change in §1 (path semantics, trimming, BOM handling) changes the **byte prefix** of the injected taxonomy segment and typically **invalidates** prompt-cache entries for deployments that rely on stable prefixes. Release notes should mention cache warm-up or acceptance of a short-term miss spike.

---

## Changelog

| Bundle (see `CONTRACT_VERSION.md`) | Change |
|-----------------------------------|--------|
| `0.1.63` | **Cross-ref:** **`TAXONOMY_RESOLVER.md` 1.0.8** — **`TaxonomyRow` / Phase −1** vs **`entityHint`** entity-level aliases (see **`CONTRACT_VERSION.md`**). |
| `0.1.62` | **Non-scope / synonym SoT:** clarify legacy removed surface as Composer **Infotable taxonomy-table** assembly; **`TaxonomyRow`** Phase −1 synonyms = type key + type **`aliases[]`** only (**`AGENT-TAXONOMY.md`**). |
| `0.1.61` | **Scope collapse:** optional **`type-taxonomy.md`** only; remove normative pipe-table assembly tied to legacy Composer taxonomy-table services. |
| `0.1.50` | §4 (historical): repository **`/taxonomies/type-taxonomy.md`** prefix; scope paragraph aligned. |
| `0.1.2` | Field semantics SoT → `docs/agent/AGENT-TAXONOMY.md`; remove ephemeral review-doc references. |
| `0.1.1` | Initial normative extraction from `AgentThing` implementation. |
