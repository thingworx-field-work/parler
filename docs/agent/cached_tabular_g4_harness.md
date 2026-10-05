# Multi-turn `cacheId` platform check

**Purpose:** measure how reliably the model carries an explicit tabular **`cacheId`** across turns in a
**real ThingWorx** session (Composer or a staging agent), and check the last-cache `TOKEN` mirror there. This
does **not** replace the repository-local checks **`node scripts/check-golden-cached-tabular-json.mjs`** and
**`node scripts/report-cached-tabular-baseline.mjs`**.

**Output:** a measured pass rate for [`cached_tabular_golden.md`](./cached_tabular_golden.md) §Platform pass-rate.
Never record a number that was not measured.

---

## 1. Prerequisites

| Item | Check |
|-------|--------|
| Agent extension | Built **`parler-agent`** ZIP imported; **`tabulate_cached_result`** / **`summarize_cached_result`** enabled. |
| Golden args | Fixture JSON under [`golden_cached_tabular/`](./golden_cached_tabular/) (see [`cached_tabular_golden.md`](./cached_tabular_golden.md)). |
| Conversation | A fresh **conversationId** per run; note the cache TTL so an expired entry (**CACHE_MISS**) is not mistaken for a scope problem. |

The planning threshold for the pass rate is **70%** (§3).

---

## 2. Minimal multi-turn template (copy as a run log)

**Turn A — materialize a cache**

1. Run any qualifying tabular path that returns a **`cacheId`** (for example **`query_entities_by_taxonomy`** LARGE, or **`invoke_service`** INFOTABLE_LARGE as in **G-04**).
2. Copy the **`cacheId`** from the tool JSON → **clipboard A**.

**Turn B — explicit carry**

3. New user message: "Using the same table, run **`tabulate_cached_result`** **`sort_topn`** …" **without** pasting the id into the user text.
4. Inspect the model's tool call: **`arguments.cacheId`** must equal **clipboard A**. If the model omits it, count the attempt as a **fail**.

**Turn C — transform sanity**

5. Call **`tabulate_cached_result`** with **clipboard A** and a simple **`sort_topn`** (arguments like **G-01**).
6. Assert **`status: success`**, **`sourceCacheId`** equals **A**, and the expected **`resultKind`**.

---

## 3. Scoring

| Metric | Definition |
|--------|---------------|
| **Attempt** | One **Turn B**-style follow-up where a tabular **`cacheId`** from **Turn A** exists in the **same** conversation. |
| **Success** | The model's tool JSON includes the **exact** earlier **`cacheId`** (string match). |
| **Pass rate** | `successes / attempts` over **N ≥ 20** attempts across mixed golden-style prompts. |

**Threshold:** **70%**. The `TOKEN` sentinel (§4) is an additional way to reach the last qualifying table; it
does not change what this metric measures, which is explicit cross-turn `cacheId` carry.

---

## 4. `TOKEN` smoke check

**Same user message / agent turn:** after a qualifying tabular tool returns a **`cacheId`**, call **`tabulate_cached_result`** or **`summarize_cached_result`** with **`cacheId: "__PARLER_LAST_QUALIFYING_TABULAR_CACHE__"`** (see [`p2_last_tabular_cache.md`](./p2_last_tabular_cache.md)). Expect success and **`sourceCacheId`** equal to the resolved cache id.

**Separate user messages (same `conversation_id`):** **`TOKEN`** may resolve through the **conversation mirror** when **`TabularChartRoundState`** was reset but an earlier message left a qualifying **`cacheId`** and the **`InfoTable`** entry is still in the conversation cache (same TTL / conversation scope). After a **process restart**, a **conversation switch**, an **inline-only** last qualifying table (which clears the mirror) or an **expired** cache entry, use the explicit **`cacheId`** from the tool JSON.

---

## 5. Mirror hygiene

Operational checks of the conversation mirror. They are not part of the pass rate in §3.

### D — `CACHE_MISS` prunes a stale mirror

**Pre:** same **`conversation_id`**; the mirror holds id **X** from a qualifying tabular **`cacheId`**; the conversation cache still holds **X** (or recreate **X**, then the mirror).

1. Remove **X** from the cache (TTL expiry or eviction) so that **`lookupCachedInfotable(X)`** fails.
2. Call **`tabulate_cached_result`** (or **`summarize_cached_result`**) with explicit **`cacheId: X`**, or with **`TOKEN`** if the mirror still pointed at **X** just before step 1 — expect **`code: CACHE_MISS`**.
3. Call **`tabulate_cached_result`** again with **`TOKEN`**. Expect **`LAST_TABULAR_CACHE_UNAVAILABLE`** until a new qualifying tabular result repopulates the mirror; the mirror must **not** keep resolving to **X**.

### E — `summarize_cached_result` then `TOKEN` tabulate

**Pre:** cached table **X** exists; the mirror may already hold **X** from an earlier qualifying tool.

1. **`summarize_cached_result`** with **`cacheId: X`** (or **`TOKEN`**). Expect **`status: success`**; root **`sourceCacheId`** equals **X** (see [`cached_tabular_tools.md`](./cached_tabular_tools.md) §2.2).
2. Optional: reset the per-turn **`TabularChartRoundState`** between messages **without** a new qualifying tabular result (simulates a new agent turn).
3. **`tabulate_cached_result`** with **`TOKEN`** and a valid **`mode`**. Expect **success**; **`sourceCacheId`** resolves to **X** — summarize updates the mirror **without** advancing **`last_invoke`** or the per-turn qualifying chart count.
