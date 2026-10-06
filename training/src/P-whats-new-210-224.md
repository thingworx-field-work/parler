# What's new: Agent 0.1.210 → 0.1.224 and widget 0.1.89 → 0.1.92

This appendix is the returning-reader map for the current course baseline: what shipped in each version and where
the course teaches it.

## Baseline

- Current lab target: **`parler-agent` 0.1.224**, **`parler-ui-widget` 0.1.92**.
- Version labels such as "0.1.206+" elsewhere in the course mark the first version with a feature; they stay accurate
  on the current baseline.

## Shipped feature map

| Version | Shipped behavior | Where this course teaches it |
| --- | --- | --- |
| 0.1.211 | FileRepository-backed Artifact Cache kernel; bounded taxonomy completeness; reserved built-in authority; typed property-write/HITL correctness; alert no-write gating; history row-limit precedence; one model-facing history overlay surface | Chapters 5, 10, 14; Appendices E, O |
| 0.1.212–0.1.213 | conversation-scoped tabular/large-JSON hubs, bounded nested extraction, public completeness/count evidence, cache-backed overlay/replay; FileRepository parent-write correction | Appendices E, G and cache exercises |
| 0.1.214 | application semantic profiles, shared evidence/recovery vocabulary, final-answer safeguards | Chapter 15 and Appendix J |
| 0.1.215 | deterministic time transforms, quality assessment, and governed exact joins over cached evidence | Appendix M |
| 0.1.216 | deterministic anomaly, relationship, change, and bounded trend evidence through `analyze_cached_result` | Appendix M |
| 0.1.217 | permission-aware fleet comparison and bounded evidence-ranked RCA through governed App/Playbook paths | Appendix N; reference material, not a resident fleet tool lesson |
| 0.1.218 | extended-Service capability metadata and runtime policy: risk, admission, HITL/Playbook eligibility, dry-run, idempotency, data classification, availability | Chapters 13–14; Appendices C and I |
| 0.1.219 | mandatory `artifactCacheFileRepository` and channel-consistent readiness faults | Chapters 4–5; Appendix O |
| 0.1.220 | ConversationCheckpoint continuity and planner transcript-pairing correction | Appendix F |
| 0.1.221 | stable leading prompt, classified volatile suffix, advancing Provider prompt-cache frontier | Appendices F–G |
| 0.1.222 | bounded per-Thing alert attribution in multi-Thing Playbook evidence | Chapters 12 and 15; Appendix J |
| 0.1.223 | pressure-gated Tier B replay promotion preserves the provider prompt-cache frontier within the storage cap | Appendices F–G |
| 0.1.224 | successful arbitrary-language final answers pass through unchanged; deterministic enforcement remains on typed tool/data boundaries | Appendix O |
| widget 0.1.90 | Mashup Style Theme integration, public tokens/parts, 24 chart slots, compact progress, empty-state Media | Chapters 6 and 19 |
| widget 0.1.91 | responsive messages/charts and compact non-overlaying `Latest` row | Chapters 6 and 19 |
| widget 0.1.92 | user-message secondary-surface treatment in Mashup Theme mode | Chapters 6 and 19 |

## Boundaries that matter

- Artifact Cache, Provider prompt cache, and ConversationCheckpoint are three different mechanisms. Artifact Cache
  stores scoped tool artifacts; Provider cache reuses identical prompt prefixes; checkpointing preserves bounded
  task navigation after transcript loss.
- A `cacheId` is transient and permission/scoping rules still apply. It is not durable application storage.
- Semantic profiles bind exact application roles, units, grain, and cadence. They are not a free-form ontology and
  do not make every property analytically comparable.
- Deterministic analysis produces evidence, not causal proof. Association is not causation; insufficient evidence
  is not “no finding”; forecast/threshold claims stay within admitted horizons.
- Fleet/RCA operates over authorized comparable members and bounded candidate catalogs. Partial coverage must be
  visible, and “no supported cause” is a valid result.
- Provider route/fallback profiles are not taught as a shipped open-ended user capability in this course. Do not
  infer production routing from configuration classes or tests alone.

## Reference material by area

| Shipped area | Reference | Where this course teaches it |
| --- | --- | --- |
| SCPA grounding and Playbook attribution | [`dev_data/sample_scpa_utilization_agent_configuration`](../../dev_data/sample_scpa_utilization_agent_configuration), Agent 0.1.222 changelog | Chapters 8, 12, 15; Appendices H, J |
| Final-answer runtime boundary | Agent 0.1.224 changelog; [`docs/agent/evidence-grounded.md`](../../docs/agent/evidence-grounded.md) | Appendix O |
| Artifact Cache and cache-backed tools | Agent 0.1.211–0.1.213 and 0.1.219 changelog; [`docs/operations/file-artifact-cache-retention.md`](../../docs/operations/file-artifact-cache-retention.md) | Chapters 4–5; Appendices E, G, O |
| Semantic/evidence foundation | Agent 0.1.214 changelog; [`docs/agent/evidence-grounded.md`](../../docs/agent/evidence-grounded.md) | Chapter 15; Appendix J (reference exercise) |
| Deterministic analysis and fleet/RCA | Agent 0.1.215–0.1.217 changelog; [`docs/agent/cached_tabular_tools.md`](../../docs/agent/cached_tabular_tools.md) | Appendices M, N (structural exercises, no frozen live values) |
| Capability governance | Agent 0.1.218 changelog; [`docs/agent/CUSTOMIZED-TOOLS.md`](../../docs/agent/CUSTOMIZED-TOOLS.md) | Chapters 13–14; Appendices C, I |
| Long-conversation continuity and prompt caching | Agent 0.1.220–0.1.221 changelog; [`docs/agent/context-compaction.md`](../../docs/agent/context-compaction.md); official Provider docs | Appendices F, G |
| Widget 0.1.90–0.1.92 | [`parler-ui-widget/CHANGELOG.md`](../../parler-ui-widget/CHANGELOG.md); [`docs/ui/theme-api.md`](../../docs/ui/theme-api.md) | Chapters 6 and 19 |
| Diagnostics collector | `uv run parler-collect-live` ([`test_scripts/live_diagnostics`](../../test_scripts/live_diagnostics)); [`docs/agent/collection-tool.md`](../../docs/agent/collection-tool.md) | Appendix K |

The Agent changelog is [`parler-agent/CHANGELOG.md`](../../parler-agent/CHANGELOG.md).
