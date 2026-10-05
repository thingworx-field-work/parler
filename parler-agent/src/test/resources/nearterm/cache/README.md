# Cache lifecycle / miss / PASSWORD fixture kit

Executable evidence lives in package-private JUnit tests under
`com.thingworx.things.agent.cache` (same topic as this directory):

| Fixture class | What it proves |
|---|---|
| `FileArtifactCacheLifecycleTest` | lazy TTL + no resurrection; invalidate / scope invalidate leave payloads; restart-empty; shutdown reject-new-ops; open/remove race; uniform `CACHE_MISS` matrix; deleted-but-still-indexed payload → `PAYLOAD_FAULT`; PASSWORD zero-create; blank repository fail-closed |
| `FileArtifactCacheTest` (PASSWORD / Core rows) | nestingLevel / maxNodes bounds; `ArtifactCacheCore` blank-config fail-closed |
| `ArtifactAccessContextTest` | namespace separation consumed by lifecycle lookups |

No rediscovery or reindex of retained payload bytes. Administrator retention procedure:
`docs/operations/file-artifact-cache-retention.md`.
