# Vendor directory

Runtime and `npm run build:tw` use **`@xudesheng/alwayson-js-codec`** (npm) and **`lib/alwaysOnParlerClient.js`** for the AlwaysOn transport. Nothing under `vendor/` participates in the bundle.

## Build outputs

`npm run build:tw` emits **`parler-ui.tw.js`** and **`alwayson_js_codec_bg.wasm`** next to it. Deploy both next to the widget script in the extension.

Do not commit large third-party trees under `vendor/` unless a future task explicitly requires it.
