# P2 — `last_tabular` cache handle

**Status:** implemented in `CachedTabularToolsExecutor` (per-turn handle, conversation fallback, mirror lifecycle, **`CACHE_MISS`** mirror prune, **`summarize_cached_result`** mirror-from-**`sourceCacheId`**): `cacheId` may be the literal sentinel. Resolution order: **`TabularChartRoundState.getLastCacheId()`** (same **agent turn** as `build_chart_from_tabular_result` **`last_invoke`** when the qualifying tool produced a **`cacheId`**), then **`AgentToolContext.getConversationLastQualifyingTabularCacheId()`** (conversation-scoped mirror — persistent **`conversation_id`** key, or **`__single_turn__` + U+0001 + `request_id`** when AlwaysOn request id is bound). **`ClearConversation`** removes the mirror for a cleared thread id. **Not** guaranteed across agent restarts.  
**Normative tool JSON:** [`cached_tabular_tools.md`](./cached_tabular_tools.md). **Contract:** [`CONTRACTS/TABULAR_INSIGHT.md`](../../CONTRACTS/TABULAR_INSIGHT.md) + [`CONTRACTS/CONTRACT_VERSION.md`](../../CONTRACTS/CONTRACT_VERSION.md).

---

## 1. Problem

Models sometimes omit the literal `cacheId` string on a follow-up even when a **qualifying** tabular tool succeeded. **G4a** documents explicit `cacheId` + **`CACHE_MISS`**. **P2** adds a deterministic escape hatch so **`tabulate_cached_result` / `summarize_cached_result`** can address “the last qualifying tabular cache” **without** a second UUID namespace.

---

## 2. Token (stable wire string)

| Constant | Value |
|----------|--------|
| **`CachedTabularLastCacheHandle.TOKEN`** | **`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`** |

**Rules:**

1. **Case-sensitive** match after `trim()` on the JSON **`cacheId`** string.  
2. **Only** valid for **`tabulate_cached_result`** and **`summarize_cached_result`** (not `fetch_cached_result` — paging must stay explicit).  
3. If the tool arguments carry a **normal** UUID / opaque cache id, that value **always wins** over the token (no merge).  
4. **`TabularChartRoundHooks`**: on each **qualifying** tabular success (tools listed in implementation), updates **`TabularChartRoundState`** and the **conversation mirror** (`AgentToolContext#noteLastQualifyingTabularCacheIdForConversation` / `#clearLastQualifyingTabularCacheIdForConversation`). **`summarize_cached_result`** success updates **only** the mirror from **`sourceCacheId`**, without treating summarize as a row chart source for **`last_invoke`**.

---

## 3. Resolution algorithm (executor)

When **`cacheId`** equals **`TOKEN`** after trim:

1. **`effectiveCacheId` = `TabularChartRoundState#getLastCacheId()`** (per-turn last-wins).  
2. If blank → **`effectiveCacheId` = `AgentToolContext#getConversationLastQualifyingTabularCacheId()`** (last qualifying tool with a **`cacheId`** for the mirror key derived from wire **`conversation_id`** or **`__single_turn__` + `request_id`**; **cleared** when the last qualifying result was **inline-only**).  
3. If still blank → error JSON **`code`:** **`LAST_TABULAR_CACHE_UNAVAILABLE`**.  
4. Else **`lookupCachedInfotable(effectiveCacheId)`** (then **`CACHE_MISS`** if expired / wrong conversation; if the mirror still pointed at that id, **`AgentToolContext#pruneTabularTokenMirrorIfPointsTo`** removes it).

**Explicit `cacheId`:** unchanged behavior.

---

## 4. Tool schema / OpenAPI surface

- **`cacheId`** remains **required** in the tool definition JSON.  
- **`BuiltInTools`** + **`llm_tool_routing_guide.txt`** describe the sentinel, per-turn scope, and conversation fallback.

---

## 5. Tests

| Layer | What |
|-------|------|
| **Offline** | `CachedTabularLastCacheHandleTest` — token detection + trim. **`AgentToolContextLastQualifyingTabularCacheTest`** — conversation map set / clear / `setConversationId` switch. Executor + **`InfoTable`** path: **platform**. |
| **Platform** | Composer: **same message** and **next message** with **`TOKEN`**; assert success when conversation mirror populated, **`CACHE_MISS`** / explicit UUID when cache expired. Record in **`cached_tabular_golden.md` §Platform pass-rate**. |

---

## 6. References

- [`cached_tabular_golden.md`](./cached_tabular_golden.md) **§Platform pass-rate** / **§Repo baseline**  
- [`docs/agent/cached_tabular_g4_harness.md`](./cached_tabular_g4_harness.md)  
- Java: **`CachedTabularLastCacheHandle`**, **`CachedTabularToolsExecutor`**, **`AgentToolContext`**, **`TabularChartRoundHooks`**
