# File ArtifactCache retention

Status: operator procedure for the opaque file-backed Artifact Cache. The Extension
indexes only the current JVM; retained payload files are never scanned or rediscovered after
restart.

## Responsibility boundary

Parler runs inside ThingWorx, which remains the overall security boundary and backstop. Cache
files live in a dedicated ThingWorx FileRepository. ThingWorx and platform/operating-
system administrators own repository permissions, physical placement, encryption, backup, and
retention. The Parler Extension does not discover whether the repository is backed by local
disk, S3, Azure, or another store, and it does not attest to or gate on those deployment
properties.

## Mandatory setup and recovery

Create and permission a dedicated FileRepository, size it for the site's workload and retention
window, and set every production AIAgent's `AgentSettings.artifactCacheFileRepository` to that
Thing. The Extension requires repository name/type resolution at every turn but does not create a
health-probe file during admission. Run `ParlerArtifactCacheLabTextRoundTrip` to prove actual
create/write/close/read operations before production use.

Blank configuration is `ARTIFACT_CACHE_NOT_CONFIGURED`. Missing, wrong-type, resolution failure,
or a repository-wide runtime operation is `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`; neither is a
`CACHE_MISS`. After repairing or changing configuration, edit/save the AgentThing so ThingWorx
restarts it and reloads the setting. The restart intentionally begins with an empty current-JVM
index; retained payload files are not rediscovered.

The Extension has one cache-file persistence rule: a ThingWorx `BaseTypes.PASSWORD` value is
rejected before any payload or temporary-file write. This runbook adds no broader
Extension-side storage-security policy.

## Date-organized logical layout

The logical FileRepository path is:

```text
<encoded-username>/<UTC-yyyy-MM-dd>/<opaque-artifact-id>.payload
```

This logical path is relative to the configured dedicated FileRepository root. The
FileRepository Thing is the cache root; the Extension does not create an additional
`<cache-root>` logical path segment.

`encoded-username` is the current ThingWorx principal username encoded as **lowercase
hexadecimal** of its UTF-8 bytes (alphabet `[0-9a-f]` only). Example: UTF-8 `aaa` → `616161`,
`aaG` → `616147` (no case-fold collision on case-insensitive backends). Do not use Base64url
or mixed-case encodings for this segment.

`ArtifactRef` does not encode or expose this path. Each ThingWorx/JVM restart creates an empty
in-memory index. The Extension ignores retained payload files: it does not scan, reindex,
migrate, purge, or quarantine them. Logical TTL, invalidate, and scope invalidation remove
index entries only — they never delete payloads.

## Administrator cleanup

Administrators choose a retention period that fits the deployment. Thirty days is an example,
not a Parler default or Extension property. Use the ThingWorx, cloud-storage, or operating-
system tooling appropriate to the actual FileRepository backend:

1. Calculate the UTC cutoff date from the administrator-selected retention period.
2. Enumerate date directories below each encoded-username directory.
3. Dry-run and report complete date directories whose date is older than the cutoff.
4. Delete only those complete date-directory subtrees; do not inspect payloads or reconstruct
   cache entries.
5. Schedule the same procedure at the site's chosen cadence and monitor its own failures through
   the site's normal operations tooling.

Conceptually:

```text
cutoff = utc_today - retention_days
for each username/date directory:
    if directory_date < cutoff:
        delete the complete date subtree
```

Distinguish two outcomes when retention deletes payload files:

- If the current-JVM index entry is already gone (TTL expiry, invalidate, scope invalidation,
  restart-empty, or shutdown), a later lookup uses the uniform bounded `CACHE_MISS` path.
- If an administrator deletes a payload that a long-running JVM **still indexes**, the next
  `open`/`read` path may surface a bounded `PAYLOAD_FAULT` (storage-level missing/unopenable
  bytes under a live index record). That is still acceptable for transient cache data; the
  caller re-fetches or recomputes. Prefer a retention period longer than the expected
  cache-use window so live JVMs are unlikely to hold index entries for directories you delete.

## What the Extension does not implement

- no per-entry, per-scope, per-user, or repository storage quota;
- no quota reservation/settlement or capacity-driven LRU eviction;
- no background file sweeper;
- no startup deletion of retained payload files;
- no physical-backend, encryption, backup, or retention detection.

Logical TTL may invalidate an in-memory handle, but it does not delete the file. Per-operation
byte/item/time/fixed-buffer `ArtifactIoLimits` remain computation and I/O bounds, not disk
quotas.
