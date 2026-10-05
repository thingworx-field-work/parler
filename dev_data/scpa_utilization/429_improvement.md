# 429 Cache Improvement Report

## Summary

Parler now uses the Anthropic prompt cache much better than it did during the original 429 incident.

In the latest `0.1.225` replay:

- the cache-read share increased from **75.4%** to **95.2%** after the first cold request;
- average non-read input per LLM call decreased from **10,106** to **1,978** tokens;
- the highest non-read input decreased from **22,912** to **4,692** tokens;
- the cache frontier grew with the conversation instead of staying fixed; and
- no 429 occurred.

This is strong evidence that the cache changes fixed the main cause of the original 429. It is not a guarantee that every possible long conversation can never receive a 429.

## Test scope

The comparison uses the first eight prompts from the same general `429_prompts.txt` workflow.

The data came from three live runs:

| Agent version | Date and time (UTC) | Purpose |
|---|---|---|
| `0.1.219` | 2026-08-25 00:32–00:39 | Original run that ended with a 429 |
| `0.1.222` | 2026-08-25 20:43–20:51 | Intermediate run after the suffix/cache-frontier change |
| `0.1.225` | 2026-08-26 06:15–06:20 | Latest comparison run |

The live data and tool paths were not exactly the same. The second prompt also had a small wording difference. For this reason, the report uses provider token accounting and per-call averages instead of treating response content or tool counts as identical.

## Token definitions

For this Anthropic Messages deployment:

```text
nonRead = input + cacheCreate
prompt  = input + cacheRead + cacheCreate
```

`nonRead` is the most useful value for this incident because the original Microsoft Foundry error reported the rate-limit type as `UserByModelByMinuteUncachedInputTokens`.

The tables below exclude the first cold request unless stated otherwise. A cold request must create the stable cache prefix and is expected to have no cache read.

## Direct comparison: before and after

| Metric | `0.1.219` before the fix | `0.1.225` latest | Change |
|---|---:|---:|---:|
| Common user prompts | 8 | 8 | Same |
| Successful LLM calls | 20, followed by one failed 429 call | 17 of 17 | No failed call |
| Average prompt tokens per warm call | 41,052 | 40,976 | -0.2% |
| Average cache-read tokens per warm call | 30,946 | 38,999 | **+26.0%** |
| Average non-read tokens per warm call | 10,106 | 1,978 | **-80.4%** |
| Cache-read share | 75.4% | 95.2% | **+19.8 percentage points** |
| Non-read share | 24.6% | 4.8% | **-19.8 percentage points** |
| Highest non-read value in one warm call | 22,912 | 4,692 | **-79.5%** |
| Total warm non-read tokens | 192,023 | 31,645 | **-83.5%** |
| Warm cache creation | 0 | 18,555 | Expected incremental cache writes |
| Cache-read pattern | Fixed at 30,946 | Grew from 32,376 to 49,063 | Frontier advanced |
| Tier B history rewrites | 1 | 0 | No within-cap rewrite |
| 429 responses | 1 | 0 | Improved |
| Total `rateWaitMs` | 0 | 0 | No local wait in either run |

The total warm non-read value is affected by the different number of LLM calls. The per-call values are the safer comparison. The average prompt size differed by only 0.2%, so the per-call cache comparison is useful even though the model chose a different number of rounds.

## Cold request comparison

| Metric | `0.1.219` | `0.1.225` |
|---|---:|---:|
| Prompt tokens | 33,172 | 33,186 |
| Cache read | 0 | 0 |
| Cache create | 30,946 | 32,376 |
| Input | 2,226 | 810 |
| Non-read | 33,172 | 33,186 |

The first-call total was almost identical. The latest version created a slightly larger stable prefix, but the total cold-request cost did not materially change. The improvement starts with the following calls, which can reuse that prefix.

## Three stages of the fix

| Stage | Cache behavior | Result |
|---|---|---|
| `0.1.219` original behavior | Cache read stayed at 30,946. New conversation history remained outside the reusable cache prefix. | Non-read input grew from 2,596 to 22,912, then the next call received a 429. |
| `0.1.222` intermediate behavior | The suffix fix allowed the cache frontier to grow, but routine Tier B promotion could rewrite old history and move the frontier backward. | Cache performance was much better overall, but one Tier B boundary caused a non-read value of 36,735. |
| `0.1.225` latest behavior | Volatile content stays after the stable frontier, and Tier B is skipped while stored history remains within the existing storage limit. | Cache read grew without retreat during the test, and no 429 occurred. |

## The Tier B intermediate problem

The `0.1.222` replay showed that the first cache fix was not enough by itself.

Two Tier B promotion boundaries changed old tool-result content:

| Tier B event | Cache read after the event | Effect |
|---|---:|---|
| `promoted=1` | 33,790 | About 4.3k cached tokens were lost |
| `promoted=7` | 32,045 | The read fell close to the stable system/tool prefix |
| Next append-only request | 67,972 | The cache recovered after writing the new prefix |

The provider cache was working. The problem was that Parler changed history that the provider had already cached.

The current fix only runs Tier B when stored history is over the existing storage limit. Normal within-limit turns remain append-only.

## Latest cache-read sequence

After the expected cold call, the `0.1.225` cache-read values were:

```text
0 → 32,376 → 32,748 → 33,488 → 33,710
  → 34,557 → 34,917 → 36,505 → 36,829
  → 38,816 → 39,203 → 41,576 → 42,270
  → 44,417 → 44,809 → 48,693 → 49,063
```

There was no cache-frontier retreat in this sequence.

## Why cache creation is now visible

Warm calls in `0.1.219` reported `cacheCreate=0`, but this was not a benefit. Only the fixed 30,946-token system/tool prefix was being reused. The growing conversation tail was processed as new input every time.

The latest behavior is:

```text
read the existing large prefix
    +
write the small new tail
    =
read a longer prefix on the next call
```

The latest warm calls created an average of about 1,160 cache tokens per call. At the same time, raw input fell from about 10,106 tokens per call to about 818. The combined non-read value therefore still fell by about 80%.

## Conclusion

The latest live evidence supports these conclusions:

1. Changing time, task state, and other volatile suffix data no longer limits Anthropic cache reuse to the fixed system/tool prefix.
2. The reusable cache frontier now grows with an append-only conversation.
3. Pressure-gated Tier B prevents normal within-limit turns from rewriting old cached history.
4. The highest observed non-read call fell from 22,912 to 4,692 tokens.
5. The comparable latest replay did not reproduce the original 50,000-tokens-per-60-seconds 429.

One limit remains: when a conversation really exceeds the storage limit, Parler may run one intentional Tier B/checkpoint/trim cache epoch. That request can have a temporary cache penalty. The fix removes repeated, unnecessary cache-frontier changes during ordinary turns; it does not promise that every request will always be a cache hit or that no unrelated provider limit can ever return a 429.
