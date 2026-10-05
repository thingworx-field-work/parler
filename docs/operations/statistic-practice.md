# Industrial statistics & SPC — practice overview

This document is **descriptive** (industry context). The Parler implementation lives in **`parler-agent/`** (`query_property_history` aggregate actions, `summarize_cached_result`, and the deterministic analysis methods of `analyze_cached_result` — see [`docs/agent/nearterm/deterministic-insight-kernel.md`](../agent/nearterm/deterministic-insight-kernel.md)). Use this file when extending analytics semantics or aligning tool outputs with industrial practice.

---

## 1. Common descriptive statistics (time series / batches)

Typical building blocks used in manufacturing analytics dashboards:

- **Central tendency:** mean (average), median, trimmed mean (robust to outliers).
- **Spread:** range, sample / population standard deviation, variance, IQR (Q3−Q1).
- **Order statistics:** min, max, quantiles (P5, P10, P50, P90, P95, P99).
- **Endpoints (series):** first / last sample in a window (with timestamps).
- **Counts:** N, count of good/bad (when quality labels exist), uptime samples.

**Parler:** `query_property_history` numeric aggregate actions `mean`, `min`, `max`, `sum`, `stddev`, `variance`, `median`, `count`, `first`, `last`; `summarize_cached_result` column stats over a cached table (null counts, numeric min/max/mean and capped p50/p95, categorical cardinality/top).

---

## 2. Process capability (summary)

Used when specifications exist (USL / LSL):

- **Cp / Cpk** (and **Pp / Ppk** using long-term variation) — compare voice of process to tolerance.
- **Sigma level** (defects per million opportunities) in Six Sigma programs.

Parler does not compute capability indices.

---

## 3. Western Electric rules (sensitizing rules)

Run on **control charts** (often with 3σ control limits). They detect **non-random** patterns beyond a single out-of-limit point. Classic Western Electric rules (paraphrased):

1. One point beyond **3σ** from the center line.
2. **Two of three** consecutive points beyond **2σ** on the same side.
3. **Four of five** beyond **1σ** on the same side.
4. **Eight** consecutive points on the **same side** of the center line.

These supplement the basic “any point outside control limits” check.

---

## 4. Nelson rules

Another widely taught set (similar intent — pattern detection on control charts), including:

- One point > **3σ**.
- **Nine** points in a row on the **same side** of the mean.
- **Six** points in a row **steadily increasing or decreasing** (trend).
- **Fourteen** points in a row **alternating** up/down (over-adjustment / stratification hint).
- **Two of three** beyond **2σ** (same side).
- **Four of five** beyond **1σ** (same side).
- **Fifteen** points in a row within **1σ** (stratification / wrong subgrouping).
- **Eight** points in a row beyond **1σ** (both sides, mixed).

Exact definitions vary slightly by reference; pick a standard reference and freeze definitions when you implement.

**Parler:** `analyze_cached_result` SPC uses individuals (I-MR) limits with a versioned run-rule catalog equal to the four Western Electric rules above (`spc_run_r1` … `spc_run_r4`); only R1 (one point beyond 3σ) is enabled by default.

---

## 5. Control charts — common families

### 5.1 Variables data (continuous measurements)

- **Individuals (I) chart** with **MR (moving range)** chart — one measurement per rational subgroup; very common in IoT / sensor streams.
- **X̄ and R** or **X̄ and S** charts — subgroup averages and ranges / std dev within subgroups.

### 5.2 Attributes data (counts / rates)

- **p chart** — proportion defective, varying sample size OK.
- **np chart** — count defective, **fixed** sample size.
- **c chart** — count of defects per **fixed** inspection unit.
- **u chart** — defects per unit when unit size varies.

Choosing the right chart depends on whether you track **defectives** (binomial) vs **defects** (Poisson-like), and whether n is fixed.
