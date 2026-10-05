# Playbook Engine — built-in `playbookSafe` surface

Normative design: `docs/agent/playbook-engine.md` §4.2.

**Source of truth:** each built-in’s `ToolDefinition` in `BuiltInTools` passes `playbookSafe=true`
when the tool may appear in static Playbook `tool_call` nodes. `PlaybookValidator` consults the
**merged** definition list from `PlaybookToolDefinitionsMerge.merge(...)` (built-ins + extended
tools + synthetic rows for **executor aliases** such as `query_numeric_property_history`).

**Compatibility anchor:** `PlaybookToolAllowlist.TOOL_NAMES` mirrors the playbook-safe **built-in**
name set (including executor aliases) for diagnostics and tests; it must stay equal to the
playbook-safe names from `merge(defaultBuiltInRegistry, missingExt)` — see
`PlaybookValidatorTest.playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns`.

## Opted-in built-ins (current)

Alphabetical (same order as `PlaybookToolAllowlist`):

| Tool |
|------|
| `acknowledge_alerts` |
| `analyze_entity_set` |
| `build_chart_from_tabular_result` |
| `describe_entity_schema` |
| `discover_thing_members` |
| `fetch_cached_result` |
| `get_property_values` |
| `invoke_service` |
| `list_asset_types` |
| `list_entities_by_type` |
| `query_alert_history` |
| `query_alert_summary` |
| `query_entities` |
| `query_entities_by_taxonomy` |
| `query_numeric_property_history` (alias → `query_property_history`) |
| `query_property_history` |
| `query_stream_data` |
| `query_value_stream_property_history` (alias → `query_property_history`) |
| `resolve_asset_type` |
| `resolve_thing` |
| `spotlight_search` |
| `summarize_cached_result` |
| `tabulate_cached_result` |

## Explicitly not opted in (static Playbooks)

- `set_property_value` — not opted in: static Playbooks have no HITL pause/resume (`docs/agent/playbook-builtin-capability-expansion.md` §6.3 / §7).
- `start_playbook` — excluded (no nested playbooks); not in the merged definitions, so a reference fails validation with `unknown tool`.
- `get_agent_skill` — authoring / prompt support, not a deterministic playbook data node.
- `get_entity` — not opted in (high-volume legacy surface).
- Legacy metadata names (`discover_properties`, default executor-only `discover_services`, …) — prefer canonical replacements; see `docs/agent/playbook-builtin-capability-expansion.md` §6.2.

`PlaybookValidatorTest` and `PlaybookToolAllowlist` must stay aligned with this table via the parity test above.
