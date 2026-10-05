# parler-ui

Standalone **Lit 3** web component (`<parler-ui>`): markdown, charts, activity line, composer, **ThingWorx AlwaysOn** transport via **`@xudesheng/alwayson-js-codec`**.

- **ThingWorx bundle:** `npm run build:tw` → **`parler-ui.tw.js`** + **`alwayson_js_codec_bg.wasm`** next to it (WASM URL resolves beside the script). See **`docs/architecture/agent-alwayson.md`** and **`docs/agent/`** for bind / `ParlerGateway` / `SubmitUserPrompt`.
- **No local demo server:** `index.html` is a short pointer only. Use **`parler-ui-widget`** + Composer for end-to-end testing.
- **History:** `hydrateHistoryFromJsonString` / `loadHistoryJson` — **`docs/ui/AI_PARLER_HISTORY.md`**. Wire **`format`** remains **`ai-parler-history-v1`** (unchanged).

From **`parler-ui-widget/`**: `npm run sync`, then **mub** — **`docs/ui/ai-parler-design.md`**.
