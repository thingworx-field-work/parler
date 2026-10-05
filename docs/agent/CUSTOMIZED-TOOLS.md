# Extended LLM tools (configuration repository)

**Supersedes:** the old `_tool_*` prefix-scan model on `AgentThing`. Custom capabilities exposed to the model are **not** discovered by scanning Services whose names start with `_tool_`.

## Current model

- Configure **`AgentSettings.configurationRepository`** on the AgentThing to a **FileRepository** Thing.
- Author **`/tools/extended_tools.json`** in that repository (see **`./configuration-repository.md`**).
- Each entry maps an **LLM tool name** to a **concrete Thing** + **service** the agent may invoke (subject to HITL and PASSWORD discovery rules documented there).

Built-in tools remain implemented in Java (`BuiltInTools`, `ToolRegistry`). The merged tool list for each turn is **built-ins** plus **registered extended tools** from the prompt-context snapshot — not prefix-harvested Services.

### Optional `executorOnly`

Per entry in **`/tools/extended_tools.json`**, an optional boolean **`executorOnly`** (default **`false`**) means: register the tool for **execution** (same target service and HITL semantics) but **omit** it from the **merged LLM tool list**. When the field is present it **must** be a JSON boolean; any other type **invalidates the entire manifest** (same strictness as **`hitl`** / **`playbookSafe`**). Normative behavior and snapshot reporting: **`./legacy-discovery-executor-only.md`**; repository operator notes: **`./configuration-repository.md`** (`GetAgentRuntimeSnapshot`).

## When INFOTABLE shaping is awkward

If **`invoke_service`** or schema generation rejects an INFOTABLE parameter (VARIANT, depth, unresolvable shape, etc.), the error message includes a hint to implement a **normal** ThingWorx service with a supported parameter layout and register it through **`/tools/extended_tools.json`** instead of relying on removed `_tool_*` patterns.

## JSON return conventions

Services registered through **`/tools/extended_tools.json`** may return **JSON** instead of an INFOTABLE when the model needs more than a table (scope, statistics, evidence gaps). These conventions are guidance for new tools, not validation: a result that does not follow them is still consumed as plain JSON, never fails the call, and never invalidates the manifest. Existing INFOTABLE services do not need to be rewritten.

1. **Return one JSON object and state its status.** Prefer `status` values `success`, `empty`, `error`; on failure add a short `code` and `message`. Distinguish "no data" from "execution failed". `status` may be omitted by older tools.
2. **Put the single main table in a root `rows` array.** Use flat objects with the same column names and a consistent value type per column. Use JSON numbers for numbers, ISO-8601 strings with a timezone for timestamps, and `null` for unknown values. Do not put nested objects or arrays in table cells.
3. **Keep descriptive fields next to `rows`.** `scope`, equipment, time range, `stats`, `evidenceGaps`, and similar fields are welcome; do not duplicate them into every row. State the unit and the denominator of each metric; do not fill unknown values with zero.
4. **Declare paging or truncation.** Provide `totalRows` and `returnedRows`, plus `hasMore` or `truncated`; keep `offset` for offset paging. Never describe one page as the whole result.
5. **Composite results may use named sub-objects** (for example `machineCoverage`, `stateSummary`), each with its own status and `rows`; use `included: false` for parts not requested and a root `status` of `partial`. Composite results are for reading. For a chart, call the tool that returns the needed single table; the agent does not guess the main table or merge sub-tables.
6. **Use JSON only when needed.** Keep INFOTABLE when a table is all that is required. When changing a return format, check every downstream use: text answers, tables, and charts.

Minimal single-table example:

```json
{
  "status": "success",
  "rows": [
    {"state": "Running", "durationSeconds": 1200},
    {"state": "Down", "durationSeconds": 2400}
  ],
  "stats": {},
  "evidenceGaps": []
}
```

**Chart eligibility (`build_chart_from_tabular_result` with `source: "last_invoke"`):** a JSON result becomes the turn's latest qualifying tabular source when the decoded `result` object has `status` absent or `success`, a non-empty root `rows` array whose elements are all objects, and no explicit partial-page signal (`hasMore: true`, `truncated: true`, numeric `offset > 0`, or numeric `totalRows` different from `returnedRows`). Results with at most `InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD` rows (currently 20) qualify on those checks alone. Results with **more** rows also require positive paging evidence (`hasMore: false`, `truncated: false`, or equal numeric `totalRows`/`returnedRows`); every paging field that is present must have its declared type (booleans as booleans, counts as numbers) and every count that is present must equal the number of rows delivered — `returnedRows` alone proves nothing, because every page reports its own length. The whole result envelope must be at most 65,536 characters (the same cap that turns a larger `invoke_service` result into `LARGE_JSON`; it applies to extended tools too), and must also fit the tabular cell budget and the cache storage byte budget (long column names are repeated on every row) before promotion; when promoted they gain a `cacheId` on the tool envelope while the model still sees at most 20 sample rows in egress. Anything else is read as plain JSON and leaves the previous qualifying source unchanged. Column types are inferred from the rows by the existing converter (the first row's keys define the columns; ISO strings stay STRING). For proportions over large detail sets, return an aggregated single table rather than the first page. See **`./prompt-to-chart.md`** §7.6 for `last_invoke` semantics.

## Authoring constraints

Extended tools are called directly by the LLM, so their contract is narrower than an ordinary ThingWorx service:

- A tool is not registered when its service takes a `PASSWORD` input, returns `PASSWORD`, or declares a `PASSWORD` column in its result DataShape (`ProtectedValuePolicy.shouldOmitExtendedToolFromDiscovery`). Protection is based on the platform `BaseType` only, not on names that look sensitive. A service whose definition cannot be resolved is handled as an ordinary lookup / invocation failure, not as PASSWORD protection.
- Prefer simple parameter types. Wrap complex `INFOTABLE`, `QUERY`, `VARIANT`, or deep JSON inputs in a narrower business service.
- A tool that can fail should return a JSON failure envelope such as `{"status": "error", "message": "…"}`, so task state can tell "tool failed" from "tool succeeded with no data". Plain-text success results are fine, but then do not match them with `match.resultKind` (see `CUSTOMIZED-SKILLS.md` §8).
- A mutating tool must make its side effect clear in its name and description; HITL policy handles confirmation.

## References

- **Normative layout and policies:** **`./configuration-repository.md`**
- **`invoke_service` design:** **`./invoke_service_design.md`**
- **Agent architecture:** **`./AGENT-CONTEXT.md`**
