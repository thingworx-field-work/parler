# Parler UI — design (ThingWorx widget)

This document describes how the **`<parler-ui>`** Lit component and **`parler-ui-widget`** extension fit together. Validation is **Composer + staging**, root **`build-widget.*`**, or **`scripts/build-twx-release-pair.mjs`**.

## 1. Folders

| Path | Role |
|------|------|
| **`parler-ui/`** | Lit 3 web component: thread UI, composer, markdown, D3 charts, activity line. **ThingWorx bundle** `npm run build:tw` → **`parler-ui.tw.js`** + **`alwayson_js_codec_bg.wasm`** (WASM next to the JS URL). Uses the **`@xudesheng/alwayson-js-codec`** package + the in-repo AlwaysOn client **`lib/alwaysOnParlerClient.js`**. |
| **`parler-ui-widget/`** | Extension metadata: `input/widgets.json`, per-widget JSON, `npm run sync` copies built assets into `tmp/` for **mub** (`twx-wc-sdk-utility`). |

## 2. In-element state

Domain logic lives in **`parler-ui/lib/`** (JavaScript, no TS build step):

- **`reduceUiEvent` / `startUserTurn`** — `lib/chatSession.js`
- **Wire → UI** — `lib/wireAdapter.js` (`session.ack`, `activity`, `content.delta`, `chart`, `done`, `error`, `session.superseded`)
- **Types (JSDoc)** — `lib/types.js` (`ChartBlock`, etc.)

When the protocol changes, update **`CONTRACTS/UI_CLIENT_PROTOCOL.md`**, **`CONTRACTS/API_CONTRACT.md`**, **`CONTRACTS/CHART_CONTRACT.md`**, and **`parler-ui/lib/*`** together.

## 3. Transport

- **ThingWorx path:** mashup sets **`ws-url`**, **`app-key`**, **`conversation-id`**, **`agent-thing-name`**, then **`ConnectAndBind`**. **Send** uses **`SubmitUserPrompt`** on **`ParlerGateway`** by default (`use-submit-user-prompt-on-conversation`); see **`docs/architecture/agent-alwayson.md`** and **`docs/agent/`** (e.g. **`AGENT-ALWAYSON-TWX.md`**, **`parler-gateway-design.md`**).
- **No `parler-user-message` DOM event** for user text — removed in favor of AlwaysOn + gateway services.

## 4. Charts

**`components/parler-ui-chart.js`**, **`components/chart-draw.js`**, **`styles/parler-ui.css`** — render **`ChartBlock`** from the wire per **`CONTRACTS/CHART_CONTRACT.md`**.

## 5. Build & release

```bash
cd parler-ui && npm install && npm run build:tw
cd ../parler-ui-widget && npm run sync && node ../twx-wc-sdk-utility/bin/cli.js
```

From repo root, **`build-widget.sh` / `build-widget.bat`** run the same sequence (see **`README.md`**). **`node scripts/build-twx-release-pair.mjs`** builds the UI bundle and **`parler-agent`** extension ZIP.

## 6. Further reading

- **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** — reducer contract
- **`parler-ui/README.md`** — package-focused notes
