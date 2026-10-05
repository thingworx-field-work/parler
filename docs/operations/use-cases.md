# User intent and tool mapping

Sample user prompts and the `parler-agent/` built-in tools that typically answer them. Tool
contracts are in `docs/agent/all-tools.md` and `docs/agent/AGENT-CONTEXT.md`; when tool semantics
change, follow the `parler-agent/` implementation and update
**`CONTRACTS/API_CONTRACT.md` / `CONTRACTS/CHART_CONTRACT.md` / `CONTRACTS/UI_CLIENT_PROTOCOL.md`**
if wire or UI is affected.

---

## 1. Sample user prompts

| # | Sample user prompt |
|---|-------------------------|
| 1 | How many devices are in the system? |
| 2 | Is there a SteamSensor in the system? |
| 3 | What tags or properties does SteamSensor have? |
| 4 | Show me the trend of SteamSensor’s Pressure over the last 5 minutes |
| 5 | From a statistics perspective, help me look at SteamSensor’s Temperature |
| 6 | If the limit is 440, how many times did Temperature exceed the limit in the past day? |

Device / property names are ThingWorx Thing and property names, e.g. `SteamSensor`, `Pressure`.

---

## 2. Prompt → typical tool chain

| # | Typical calls (illustrative; the model chooses) |
|---|----------------------|
| 1 | `query_entities` on the relevant ThingTemplate / ThingShape (or `resolve_asset_type` → `query_entities_by_taxonomy` for application asset types) → answer with the count |
| 2 | `resolve_thing` or `spotlight_search` |
| 3 | `discover_thing_members` (properties facet) |
| 4 | `query_property_history(relativeDuration="5m")` → automatic `type:"chart"` frame for numeric trends |
| 5 | `query_property_history` with aggregate `actions` (mean, min, max, stddev, median, …), or `summarize_cached_result` / `analyze_cached_result` on the cached series |
| 6 | `query_property_history(calendarPhrase / relativeDuration)` → `analyze_cached_result` `threshold_crossing` on the cached series (or a `y_reference_lines` limit at 440 on the chart) |

---

## 3. Guidance the tools and system prompt enforce

- **Time:** relative phrases (“last 5 minutes”, “past day”) are resolved by the server from
  `relativeDuration` / `calendarPhrase` in the user's time zone and executed in UTC
  ([`times-solution.md`](../architecture/times-solution.md)); the model does not invent “now”.
- **Names:** `thingName` must be canonical; non-canonical labels return
  `IDENTITY_RESOLUTION_REQUIRED` and the model resolves them with `resolve_thing`.
- **Payload size:** large results go to the session cache (`cacheId`) and are summarized or
  analyzed there instead of flooding the model with points.
- **Honesty:** numeric answers follow tool results; device lists, property lists and trend data are
  never fabricated.
