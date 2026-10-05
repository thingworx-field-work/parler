# Derive op selection (converter knowledge)

Normative overlap rule from §10.6.3.

## Identifier matching

| Skill step shape | Prefer | Avoid |
| --- | --- | --- |
| Taxonomy-bound Thing resolution (`resolve_thing` + candidate rows from `query_entities_by_taxonomy`) | `match_identifier_in_rows`, `match_entity_identifiers`, `require_exact_count` | `match_candidates` as a drop-in for taxonomy identity |
| Generic row needle match with explicit `onZero` / `onMultiple` policy | `match_candidates` | Inventing custom filter graphs |

List every derive `op` used in **`deriveOpsUsed`** in the conversion report.

## Helper ops (§9.5)

Use when they shorten the graph without changing semantics:

- `normalize_text` — identifier comparison prep
- `dedupe` — stable key dedupe before summarize
- `limit_rows` — bounded samples with `totalCount`
- `format_evidence_lines` — deterministic evidence templates

Do not emit ops absent from `playbookRuntime.deriveOps` on the snapshot.
