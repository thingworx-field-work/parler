# Embed Parler in a mashup: layout, bindings, and Host Context

**Prerequisites:** Chapters **4–6** (extensions, mashup, first run). Helpful: **9** (hierarchy services) and **13** (extended tools / wrappers).

## Goal

Chapter **6** runs Parler in a **standalone** sample mashup. Production apps usually embed Parler **inside an existing business mashup** — Asset Monitoring, utilization, alarm views — so operators can chat without leaving the page.

This chapter covers:

1. **Layout and bindings** — add a collapsible Parler panel (via the **Parler-embedded** contained mashup) and wire the agent, visibility, and page JSON into the widget.
2. **Host Context** — optional per-turn JSON so the agent knows **what the page shows** (filters, hierarchy node, selected asset) without the user retyping that state.

**Naming in this chapter:**

- **Parler** — the product (agent + UI) you are integrating.
- **`parler-ui-widget`** — the ThingWorx extension you import in chapter **4** (Composer widget bundle).
- **Parler UI widget** / **`<parler-ui>`** — the runtime chat component inside that extension.
- **Host Context** — the feature that uplinks structured page state (`mashup_context` / `HostScopeJson` → rendered prompt on Send).

---

## The Parler-embedded mashup

Import the latest **`ParlerAgentBasic.xml`**. Open project **`ParlerAgentBasic`** and confirm it includes at least the entities shown below.

<img src="./__images__//image-20260629011543641.png" alt="image-20260629011543641" style="zoom:50%;" />

The **`Parler-embedded`** mashup exposes **four parameters** the parent mashup can bind:

<img src="./__images__//image-20260629011705538.png" alt="image-20260629011705538" style="zoom:50%;" />

| Parameter | Type | Purpose |
|-----------|------|---------|
| **`parler_is_visible`** | Boolean | Whether the embedded panel is expanded in the parent mashup. |
| **`agent_thing_name`** | String | Name of an Agent Thing (based on ThingTemplate **`AIAgent`**). |
| **`mashup_key`** | String | Identifies which host-context template applies and how conversation history is scoped. |
| **`mashup_context`** | JSON | Structured page state for the agent — for example, asset types selected on Asset Monitoring, or the **`ThingName`** on an asset detail page. |

---

## SCPA demo project

Download **`Parler_SCPA_Demo.xml`** from the release download page and import it into ThingWorx. Open project **`Parler_SCPA_Demo`** and confirm it includes at least the entities below.

<img src="./__images__//image-20260629014132929.png" alt="image-20260629014132929" style="zoom:50%;" />

Edit the **`SCPA_Mashup_Helper`** Thing and open its **Configuration** page.

<img src="./__images__//image-20260629012529134.png" alt="image-20260629012529134" style="zoom:50%;" />

Assign an **`AIAgent`** Thing you created in earlier chapters as the default agent for the demo mashups (like `SCPA_Demo_Agent`, created in chapter §5).

---

## Walkthrough: embed Parler in an existing SCPA mashup

**`Parler_SCPA_Demo`** ships three example mashups — each a copy of an existing SCPA view with Parler added:

| Demo mashup | Copied from |
|-------------|-------------|
| **`PTCTS.AssetMonitoring.AssetDetailsDefaultWithAI_MU`** | **`PTCTS.AssetMonitoring.AssetDetailsDefault_MU`** |
| **`PTCTS.AssetMonitoring.ContainedAssetListParler_MU`** | **`PTCTS.AssetMonitoring.ContainedAssetList_MU`** |
| **`PTCTS.AssetMonitoring.ContainedCardsAndMapParler_MU`** | **`PTCTS.AssetMonitoring.ContainedCardsAndMap_MU`** |

This walkthrough uses **`PTCTS.AssetMonitoring.ContainedAssetList_MU`** as the **before** picture. You duplicate it as **`PTCTS.AssetMonitoring.ContainedAssetListDemo_MU`** for the exercise. (The demo project already includes **`ContainedAssetListParler_MU`**; we use a new name here so you can follow the steps yourself.)

The original list mashup looks like this:

<img src="./__images__//image-20260629014831314.png" alt="image-20260629014831314" style="zoom:50%;" />

On the Asset Monitoring page, click the **list** button in the top-right corner to open it.

<img src="./__images__//image-20260629015208932.png" alt="image-20260629015208932" style="zoom:50%;" />

**Goal:** embed the Parler UI on the right, and pass the user’s left-panel selections to the agent via **Host Context**.

### Step 1: Duplicate the mashup

Duplicate **`PTCTS.AssetMonitoring.ContainedAssetList_MU`** as **`PTCTS.AssetMonitoring.ContainedAssetListDemo_MU`**.
You can save this mashup in the imported  `Parler_SCPA_Demo` project, or any project of your choice.

> **Note:** The imported demo already contains **`PTCTS.AssetMonitoring.ContainedAssetListParler_MU`**. We use **`…Demo_MU`** here only so you can practice the full flow.

### Step 2: Add a container on the right

Select the top-level container and add a new container to its right.

<img src="./__images__//image-20260629015953030.png" alt="image-20260629015953030" style="zoom:50%;" />

<img src="./__images__//image-20260629020011544.png" alt="image-20260629020011544" style="zoom:50%;" />

Enable **`EnableExpandCollapse`**.

<img src="./__images__//image-20260629020104668.png" alt="image-20260629020104668" style="zoom:50%;" />

Then enable **`ShowExpandCollapse`**.

<img src="./__images__//image-20260629020159702.png" alt="image-20260629020159702" style="zoom:50%;" />

>Note: Uncheck the `Expanded` property if you don't want the Parler chat panel to be opened by default.

### Step 3: Add the Parler-embedded contained mashup

From the **Widgets** list, drag **Contained Mashup** into the new container.

<img src="./__images__//image-20260629020232528.png" alt="image-20260629020232528" style="zoom:50%;" />

Set the mashup name to **`Parler-embedded`**.

<img src="./__images__//image-20260629020631432.png" alt="image-20260629020631432" style="zoom:50%;" />

### Step 4: Bind `agent_thing_name`

Add **`SCPA_Mashup_Helper.GetDefaultAgentThing`** to the mashup **Data** section.

<img src="./__images__//image-20260629020907199.png" alt="image-20260629020907199" style="zoom:50%;" />

Bind the service result to **`agent_thing_name`** on the contained mashup.

<img src="./__images__//image-20260629021110402.png" alt="image-20260629021110402" style="zoom:50%;" />

### Step 5: Bind `parler_is_visible`

Select the new container and bind its **`Expanded`** property to **`parler_is_visible`** on the contained mashup.

<img src="./__images__//image-20260629021324092.png" alt="image-20260629021324092" style="zoom:50%;" />

### Step 6: Build `mashup_context`

Add an **Expression** named **`ConstructParlerContext`** (the name is arbitrary).

<img src="./__images__//image-20260629021557747.png" alt="image-20260629021557747" style="zoom:50%;" />

Set **Output Base Type** to **`JSON`** — this is **required**.

<img src="./__images__//image-20260629021816283.png" alt="image-20260629021816283" style="zoom:50%;" />

You can use the following code:

```javascript
output={
    "JSONForQuerySelections": JSONForQuerySelections,
    "VisibleColumns": ["Name","status","PTCMake","PTCModel","PTCSerialNumber"]
};
```

Add input parameters for whatever page state you want to send to the agent. In this example we use **`JSONForQuerySelection`**, which matches the parent mashup’s query-selection JSON. Build the expression **output** ( **`VisibleColumns`** below is illustrative only).

<img src="./__images__//image-20260629022249310.png" alt="image-20260629022249310" style="zoom:50%;" />

Bind the expression output to **`mashup_context`** on the contained mashup.

<img src="./__images__//image-20260629022346515.png" alt="image-20260629022346515" style="zoom:50%;" />

You should obtain the following bindings:
<img src="./__images__//image-20260630115028835.png" alt="image-20260630115028835" style="zoom:80%;" />

### Step 7: Set `mashup_key`

The **`mashup_key`** can be any string. Use a unique key per parent mashup, or share one key across mashups if you want shared conversation history. The key affects how **`conversationId`** is derived on the server and therefore how chat history is isolated or shared. The value must be set on the contained mashup (`Parler-embedded`).

<img src="./__images__//image-20260629022522645.png" alt="image-20260629022522645" style="zoom:50%;" />

We recommend using the parent mashup name: **`PTCTS.AssetMonitoring.ContainedAssetListDemo_MU`**.

### Step 8: Create the host-context template file

Create **`PTCTS.AssetMonitoring.ContainedAssetListDemo_MU.json`**. The filename must match **`mashup_key`** exactly.

Example template:

```json
{
  "schema": "parler-host-context-template",
  "key": "PTCTS.AssetMonitoring.ContainedAssetListDemo_MU",
  "description": "Context for Asset Monitoring list page query parameters and visible columns.",
  "requiredContextFields": ["JSONForQuerySelections"],
  "maxRenderedChars": 4000,
  "promptTemplate": [
    "Host page context for this turn:",
    "- Page: Asset Monitoring contained asset list",
    "- Visible list columns: {{format.list(context.VisibleColumns, \"visible column\")}}",
    "",
    "{{format.jsonFence(context.JSONForQuerySelections, \"asset-monitoring-query-parameters\")}}",
    "",
    "Tool guidance:",
    "- In this page, the current visible asset set can be queried via tool `invoke_service` with entityType=Thing, entityName=PTCTS.ConfigurationManagement.Manager, serviceName=GetAssetModelForView, and parameters shaped as `{ \"JSONForQuerySelections\": <the fenced JSON block named asset-monitoring-query-parameters> }`. Do not flatten fields such as selectedNetworkNode or selectedEntityTypes into the top-level service parameters.",
    "- The visible columns describe how the current list is displayed; they are not query filters and should not be added to the service parameters.",
    "- When the user asks about the current list, visible assets, or what is shown on this page, prefer the visible columns as the response table columns when the returned data contains those fields.",
    "- For built-in entity-listing questions, inspect the fenced JSON block named asset-monitoring-query-parameters. If it contains multiple selectedEntityTypes rows, treat them as a union of selected asset types. For each row, use its exact EntityType and EntityName with `query_entities_by_taxonomy`; do not call `resolve_asset_type` for those host-context-selected entity types.",
    "- If selectedNetworkNode is also present, that value is a hierarchy node id from the page. For each `query_entities_by_taxonomy` call, pass selectedNetworkNode as `hierarchyNodeId`; do not pass it as `hierarchyNodeName`. Union and deduplicate the per-asset-type results.",
    "- If selectedEntityTypes is absent or empty, the page scope means all asset types. Prefer the page service route (`invoke_service` with JSONForQuerySelections wrapper) for current-visible-asset questions instead of inventing an asset type.",
    "- If selectedNetworkNode is absent, treat the page as unscoped by hierarchy unless the user explicitly names a node.",
    "- Explicit user text wins over host page blocks."
  ]
}
```

The **`key`** field (line 3 above) must match **`mashup_key`** and the filename.

This file tells the Parler agent how to render **`mashup_context`** into model-facing prompt text on every turn, so the LLM knows which page the user is on.

**Why not build the prompt in the mashup or UI?** For security and auditability. The mashup sends **raw data only**; the agent renders it through a bounded template and does **not** treat uplink JSON as instructions. The template language is deliberately **not Turing-complete** (see the appendix). JSON templates are easy to review — important for enterprise deployment.

Upload the file to **`/host-contexts/`** in **`ConfigurationRepository`**.

<img src="./__images__//image-20260629023258789.png" alt="image-20260629023258789" style="zoom:50%;" />

### Step 9: Point SCPA at the new mashup

Open the **`PTCTS.AssetMonitoring.Manager`** configuration page.

<img src="./__images__//image-20260629023909000.png" alt="image-20260629023909000" style="zoom:50%;" />

Set the list mashup to **`PTCTS.AssetMonitoring.ContainedAssetListDemo_MU`**.

<img src="./__images__//image-20260629023949726.png" alt="image-20260629023949726" style="zoom:50%;" />

### Step 10: Reload the agent Thing

Edit/save or restart the Agent Thing configured in **`SCPA_Mashup_Helper`** so it picks up the new host-context template.

> If you have recently imported new versions of the Parler extensions (agent and ui widget), you may also need to restart the ThingWorx server so the new extension classes are loaded.
> Make sure that the versions you are seing in the composer "Manage extensions" page are properly reflected in the UI (versions in green text below the widget).

### Test end-to-end

Refresh and reload the SCPA solution. Open Asset Monitoring and click **list** in the top-right corner.

<img src="./__images__//image-20260629024353253.png" alt="image-20260629024353253" style="zoom:50%;" />

Click the **&lt;** control to expand the Parler panel.

<img src="./__images__//image-20260629024453162.png" alt="image-20260629024453162" style="zoom:50%;" />

Confirm the Parler UI connects.

Select an asset type on the left, click the **hasIssue** icon to list assets with issues, then ask a simple question such as **“Which one should I pay attention to first?”**

<img src="./__images__//image-20260629024704448.png" alt="image-20260629024704448" style="zoom:50%;" />

The agent should call the service described in your host-context template. If that service is not allowed by policy, the Parler UI prompts for confirmation.

<img src="./__images__//image-20260629024931825.png" alt="image-20260629024931825" style="zoom:50%;" />

The model then recommends which assets to prioritize based on the health data it retrieves.

<img src="./__images__//image-20260629025039314.png" alt="image-20260629025039314" style="zoom:50%;" />

---

## Host Context: page scope for the agent

After Parler is embedded, **Host Context** is how the mashup tells the agent what the page currently shows.

### What Host Context is

**Host Context** is optional per-turn JSON sent with the user message — selected asset, filters, hierarchy node, tab — so the user does not have to retype page state in chat.

Parler uses a **template model**. The uplink is shaped as **`key` + `context`** (or, in the **Parler-embedded** walkthrough above, **`mashup_key` + `mashup_context`** bound into the same pipeline):

```text
{ "key": "<template-id>", "context": { ... structured page state ... } }
```

The agent resolves a **`host-contexts/*.json`** template from the AgentThing **configuration repository** by **`key`**. When a matching registered template exists, it renders bounded **formatters** into a short **ephemeral system** prompt fragment. When the **`key`** is parseable but **not** registered, **parler-agent 0.1.206+** inserts a **generic fenced-JSON fallback** (`UNREGISTERED_GENERIC_FALLBACK`) — page state only, not app-specific tool guidance. The agent **does not** auto-bind uplink JSON into tool arguments. Scope is **advisory**: the model reads the rendered block and passes explicit tool args when it needs scoped queries.

Parler **no longer** loads hidden classpath host-context templates as a runtime fallback (removed in **0.1.206**). App teams **must** deploy templates under **`/host-contexts/`** in the configuration repository for app-specific behavior.

From **parler-agent 0.1.193+**, Host Context also becomes **turn state**:

- each user turn can persist Host Context metadata in history;
- the UI can show a compact collapsed Host Context row above the user prompt;
- changed Host Context adds freshness guidance so questions like **“how about now?”** prefer the current page state over stale history;
- page-provided system identifiers should be used directly, not re-resolved as natural language.

Normative architecture: **[`docs/architecture/host-context.md`](../../docs/architecture/host-context.md)**, **[`docs/architecture/host-context-turn-state.md`](../../docs/architecture/host-context-turn-state.md)**, and **[`docs/architecture/host-context-generic-fallback.md`](../../docs/architecture/host-context-generic-fallback.md)** in this repository.

### Wire shape: `key + context`

Do **not** send the old v1 **`kind`** enum (`hierarchy_scope`, `markdown_note`, `kv`). The uplink is always:

| Field | Required | Meaning |
|-------|----------|---------|
| **`key`** | yes | Selects a template file, e.g. **`asset_detail.current_asset`**. |
| **`context`** | yes | Structured page state; fields must match the template’s **`requiredContextFields`**. |

The widget copies the **exact UTF-8 bytes** of the uplink JSON into **`hostContext`** on **`SubmitUserPrompt`** / **`ParlerStreamToRemoteThing`** — no trim, no **`JSON.parse` → `JSON.stringify`** round-trip unless byte-identical.

When the turn is accepted, the server stores Host Context snapshot metadata on the user row. Rows where Host Context changed store the raw JSON anchor; unchanged rows can refer back by hash. In the UI, this appears as a collapsed line such as:

```text
Host context: asset_monitoring.query_scope · changed · 335 bytes
```

<img src="./__images__//image-20260630171907664.png" alt="image-20260630171907664" style="zoom:80%;" />

#### Example: Asset Detail

```json
{
  "key": "asset_detail.current_asset",
  "context": {
    "page": "Asset Detail",
    "thingName": "Pump-01",
    "tab": "Alerts",
    "timeWindow": { "kind": "relative", "value": "24h" }
  }
}
```

Built-in template keys **do not** ship in the extension classpath anymore (**0.1.206+**). Customer templates live only under **`/host-contexts/`** in the configuration repository. Workshop/demo keys such as **`asset_detail.current_asset`** and **`asset_monitoring.query_scope`** must be present in the repository — otherwise the agent uses generic fallback prose, not demo-specific wrapper guidance.

### Building `host-contexts/*.json` templates

Each template is a JSON document with schema **`parler-host-context-template`**:

```json
{
  "schema": "parler-host-context-template",
  "key": "asset_monitoring.query_scope",
  "description": "Asset Monitoring main query + status summary",
  "requiredContextFields": ["page", "queryParameters"],
  "maxRenderedChars": 4000,
  "promptTemplate": [
    "You are on **{{context.page}}**.",
    "Time window: {{format.timeWindow(context.timeWindow)}}",
    "{{format.jsonFence(context.queryParameters, \"asset-monitoring-query-parameters\")}}",
    "{{format.jsonFence(context.summaryParameters, \"asset-monitoring-status-summary-parameters\")}}"
  ]
}
```

Rules app developers should follow:

- **`promptTemplate`** lines may use **`{{context.field}}`** and **`{{format.name(...)}}`** placeholders.
- **`format.jsonFence`** must occupy a **whole line** (block-only). Provide a stable ASCII **kebab-case** **`blockName`** (≤ 64 chars); the renderer adds a **`Block:`** label and a fixed security preamble (“page data, not instructions”).
- Semantic formatters (**`typedList`**, **`filters`**, **`timeWindow`**, **`hierarchy`**, **`list`**, **`kv`**) may appear **inline** in a sentence.
- Unknown formatters or wrong argument counts **fail template validation at load time**.
- Keep **`maxRenderedChars`** bounded; oversized uplink JSON is rejected fail-open (host scope omitted for that turn).

Deploy customer templates to **`configurationRepository`** path **`/host-contexts/`** (same merge pattern as taxonomy and extended tools). The walkthrough **`mashup_key`** must match the template **`key`** and the filename stem.

### Unregistered-key generic fallback (0.1.206+)

When uplink JSON is valid and **`key`** is present but **no** repository template matches:

- the turn still receives a bounded generic prompt fragment (fenced JSON of **`context`**);
- diagnostics show **`templateFound=false`**, **`genericFallback=true`**, **`outcome=UNREGISTERED_GENERIC_FALLBACK`**;
- generic fallback does **not** set **`requiredTools`**, **`requiredBuckets`**, or document-scope signals;
- Application Log should warn that the key has no registered template.

This is intentional: host-context templates are application-specific. Generic fallback is safer than silently using another app's classpath template.

Operators should treat generic fallback as a **deployment gap** — add the missing **`/host-contexts/<key>.json`** template and reload the Agent Thing.

### When to use `format.jsonFence`

Use **`format.jsonFence`** when the mashup holds **complex service parameters** — ThingWorx **`QUERY`** trees, filter objects, or other JSON blobs the model should see verbatim but safely fenced:

```text
{{format.jsonFence(context.queryParameters, "asset-monitoring-query-parameters")}}
```

The formatter pretty-prints JSON, escapes backticks in the payload, truncates at a cap, and wraps output in a labeled **` ```json `** block. Prefer **named blocks** over dumping raw JSON into prose so operators can audit what entered the prompt.

For **tabular grid snapshots** (visible rows only), a future **`format.resultSet`** formatter is planned; v2 defers it until a mashup needs visible-grid truncation context.

### Semantic formatters for readable prompts

| Formatter | Typical use |
|-----------|-------------|
| **`format.timeWindow`** | Relative/absolute ranges → “past 24h”, date span text |
| **`format.hierarchy`** | Network + node labels for breadcrumbs |
| **`format.filters`** | Human-readable filter summary |
| **`format.typedList`** | Thing / template lists with type + name columns |
| **`format.kv`** | Small key-value maps from mashup state |

Template authors should write **guidance lines** telling the model which **built-in or extended tool** to call when the user asks about page scope. The important rule is:

```text
Host Context system id -> use directly
User text / display phrase -> resolve
```

For example, if Asset Monitoring sends **`selectedNetworkNode`** as a node id such as **`SE.CellFab.Model.Site.MUC-CellFab`**, the template should tell the model to call **`query_entities`** or **`query_entities_by_taxonomy`** with **`hierarchyNodeId`**, not **`hierarchyNodeName`**. If the user typed a label such as **“MUC”** or **“Germany”**, the model can still use **`hierarchyNodeName`** and let the hierarchy resolver find the node. Host Context **does not** invoke services server-side.

{{placeholder: please take a screenshot of Asset Monitoring with asset type, hierarchy, and status filters selected}}

### Mashup JavaScript: building `context`

Build **`context`** from mashup bindings (selected rows, filter widgets, service outputs). Set **`key`** to the template id for that mashup view. Refresh the uplink JSON whenever scope changes **before** Send.

```javascript
// Illustrative pattern — adapt to your mashup bindings
var hostScope = {
  key: "asset_monitoring.query_scope",
  context: {
    page: "Asset Monitoring",
    queryParameters: queryParamsFromMashup,
    summaryParameters: summaryParamsFromMashup,
    hierarchy: { networkName: net, nodeId: nodeId }
  }
};
me.setHostScopeJson(JSON.stringify(hostScope));
```

**Do not** rely on the server to infer hierarchy intersect from **`hostContext`**. The LLM still needs to pass explicit tool arguments. For page-selected hierarchy ids, use **`hierarchyNodeId`**. For user-entered hierarchy labels, use **`hierarchyNodeName`**. If an earlier step already produced a bounded Thing list, use **`intersectThingNames`**.

#### Asset Monitoring guidance pattern

For Asset Monitoring pages, a practical template guidance block is:

```text
- If the fenced JSON includes selectedEntityTypes rows with EntityType and EntityName,
  use those exact values with query_entities_by_taxonomy; do not call resolve_asset_type
  for those host-context-selected entity types.
- If the fenced JSON includes selectedNetworkNode, that value is a hierarchy node id
  from the page. Pass selectedNetworkNode as hierarchyNodeId; do not pass it as
  hierarchyNodeName.
- If selectedEntityTypes is absent or empty, do not invent an asset type from host context.
- If selectedNetworkNode is absent, treat the page as unscoped by hierarchy unless the
  user explicitly names a node.
```

### Debugging with `ValidateHostContext`

**`AgentThing.ValidateHostContext(hostScopeJson)`** (sync) validates uplink bytes, resolves the template, renders formatters, and returns diagnostics — use it in Composer or a test mashup while authoring templates.

Check:

- **`rejectReason`** / outcome when **`key`** is missing, invalid, or oversized
- For an unregistered but parseable **`key`**: **`templateFound=false`**, generic fallback preview (**0.1.206+**)
- Rendered prompt preview (includes **`Block:`** labels and jsonFence preamble)
- Formatter diagnostics and **`truncated`** flag

<img src="./__images__//image-20260630173837510.png" alt="image-20260630173837510" style="zoom:50%;" />

---

## Checklist

### Widget 0.1.92 presentation checklist

When updating an exported mashup from widget 0.1.89, inspect the actual widget instance as well as the chapter:

- set `ThemeMode=mashup` to resolve the active Style Theme, or `parler-dark` for the standalone preset;
- choose `ProgressPresentation=detailed|compact` and localize `ProgressLabel` / `RateControlLabel` when compact;
- optionally select a Media Entity for `EmptyStateMedia`;
- verify message width and chart layout in the real host container; charts use measured width with a safe 480px
  logical minimum, and the parent plus `--parler-message-max-width` control the physical maximum;
- verify the conditional `Latest` action shares the Transport row and does not cover content;
- in mashup mode, confirm the user message uses the Style Theme secondary surface/body text with a primary accent
  border, as changed in 0.1.92.

For supported CSS tokens, 24 chart-series slots, print roles, light-DOM parts, defaults, and constraints, use the
customer-facing [`docs/ui/theme-api.md`](../../docs/ui/theme-api.md); do not infer a public token from internal bridge CSS.

| Step | Done when |
|------|-----------|
| Import **`ParlerAgentBasic`** + **`Parler_SCPA_Demo`** | Required entities and **`Parler-embedded`** mashup are present |
| Duplicate mashup + SCPA menu/config | Production menu opens the Parler-enabled mashup |
| Collapsible container + **Parler-embedded** | Panel expands/collapses; agent connects |
| **`agent_thing_name`**, **`mashup_key`**, **`parler_is_visible`** | Correct agent; expand/collapse stays in sync |
| **`ConstructParlerContext`** → **`mashup_context`** | Page selections feed context JSON |
| Host-context JSON in **`/host-contexts/`** | Filename, **`key`**, and **`mashup_key`** all match |
| Unregistered **`key`** on **0.1.206+** | Generic fallback diagnostics only — add repository template for production |
| Document tool routing in **`promptTemplate`** | Model knows which service/tool matches page scope |
| Run **`ValidateHostContext`** | Rendered preview matches intent; no load-time formatter errors |
| Reload Agent Thing | New template is loaded |
| History and debug | User prompt shows collapsed Host Context metadata when applicable |
| Hierarchy-scoped questions | Model uses **`hierarchyNodeId`** for page-selected node ids, **`hierarchyNodeName`** for user-entered labels, or explicit intersect — not hidden server inject |

## Further reading (product repository)

- **`docs/architecture/host-context.md`** — architecture SoT
- **`docs/architecture/host-context-turn-state.md`** — per-turn persistence, freshness, UI disclosure, and system-id direct use
- **`docs/architecture/host-context-generic-fallback.md`** — unregistered-key generic fallback (**0.1.206+**)
- **`CONTRACTS/API_CONTRACT.md`** — **`hostContext`** wire rules
- **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** — widget / reducer semantics
- **`docs/agent/AGENT-CONTEXT.md`** — **`HostContextUplink`**, **`ValidateHostContext`** services
