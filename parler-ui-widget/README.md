# parler-ui-widget

ThingWorx extension project for **`parler-ui`** (`<parler-ui>`).

## Prereqs

- [ThingWorx Web Component SDK Utility](https://support.ptc.com/help/thingworx_hc/web_component_sdk/) (`mub`) installed and on your `PATH`, or invoke it via `npx`.
- **`../parler-ui`**: run **`npm install`** there once so **`@xudesheng/alwayson-js-codec`** (and WASM) resolve for **`build:tw`**.

## Build

From this directory:

```bash
npm run build:extension
```

That runs **`sync`** (which runs **`build:tw`** in `../parler-ui`, verifies WASM, copies into **`tmp/parler-ui`**) and then **`mub`**. Extension ZIPs → **`dist/`**:

- **Versioned:** **`parler-ui-widget-<version>.zip`** (from **`input/widgets.json`** `version`, e.g. **`parler-ui-widget-0.1.75.zip`**) — same idea as Gradle’s default **`parler-agent-…​.zip`** name before the stable copy.
- **Stable:** **`parler-ui-widget.zip`** — copied for scripts and docs that expect a fixed filename (same pattern as **`parler-agent/build/parler-agent.zip`**).

Alternatively, run the steps separately:

```bash
npm run sync
npm run mub
```

`sync` produces **`parler-ui.tw.js`** + **`alwayson_js_codec_bg.wasm`** in `../parler-ui`, then syncs into **`tmp/parler-ui`** (omits `node_modules`; **`dereference: true`** on Windows). **`tmp/`** is gitignored. **`sync`** also runs **`parler-ui/scripts/gen-widget-package-version.mjs`**, which writes **`parler-ui/lib/widgetPackageVersion.js`** from **`input/widgets.json`** `version` so the shipped `<parler-ui>` transport line can show the Composer widget package version next to the agent version (**`GetConnectionInfo`** handshake — see **`CONTRACTS/API_CONTRACT.md`**).

**Note:** `mub` must resolve to this widget project (run commands from **`parler-ui-widget`**). If your global `mub` jumps to another folder, fix your PTC SDK utility / shell `cwd`, or invoke the utility the way your team documents for this repo.

Import the ZIP in Composer (**Import → Extension**).

## Widget API (summary)

| Mashup | Web component |
|--------|----------------|
| **Transport** | `thingworxWsUrl`, `appKey`, `agentThingName`, `useSubmitUserPromptOnConversation` (default **true** = **`SubmitUserPrompt`** on gateway; **false** = **`ParlerStreamToRemoteThing`** on agent — extension still enforces **AgentThreadDataTable** for `conversationId`), `connectionStatus` (out), `conversationId` |
| Service **ConnectAndBind** / **DisconnectAlwaysOn** | Embedded WebSocket + bind + **ReceiveMessage** |
| Property **ThemeMode** | `themeMode`: **`mashup`** (default, follows the active Mashup Style Theme) or **`parler-dark`** (bundled dark preset) |
| Property **CustomClass** | Application-owned CSS scope; bindable as input and output |
| Property **ProgressPresentation** / **ProgressLabel** / **RateControlLabel** | `detailed` or `compact` active-turn presentation plus localizable compact labels |
| Property **EmptyStateMedia** | `emptyStateMedia`: optional binding-target **`IMAGELINK`** selected as a Media Entity in Composer |
| Property **Placeholder** / **Disabled** / **HideHeader** / **HeaderTitle** | UI chrome |
| Property **HeaderSubtitle** | Deprecated (not rendered) |
| Service **ApplyLiveJson** | `applyLiveJson(string)` — fallback wire push |
| Service **ResetChat** | `resetChat()` |
| Service **LoadHistoryJson** | `loadHistoryJson(string)` |

Parler-specific per-widget Style Properties are not exposed. For an
instance-specific override, set `CustomClass` to an application-owned value
such as `customer-chat` and scope public Theme tokens or supported light-DOM
parts to it:

```css
.customer-chat parler-ui {
  --parler-color-accent: #005ea8;
  --parler-chart-series-1: #005ea8;
}

.customer-chat parler-ui [part~="notice"][data-severity="warning"] {
  font-weight: 600;
}
```

The complete customer contract and examples are in
**`../docs/ui/theme-api.md`**.

**Not exposed (AlwaysOn-only widget):** **UserPrompt**, **no `CleanUserPrompt` service**, **no `events`** — no **SubmitUserPrompt** / **`parler-user-message`**. User turns run only through embedded Transport after **ConnectAndBind**.

See **`../docs/ui/ai-parler-design.md`**, **`../docs/architecture/agent-alwayson.md`**, **`../CONTRACTS/UI_CLIENT_PROTOCOL.md`**, and **`../docs/ui/load-history.md`** (Composer **`model`** binding: host assigns the element implementation on `<parler-ui>` per ThingWorx Web Components).
