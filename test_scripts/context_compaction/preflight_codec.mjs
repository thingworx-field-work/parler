/**
 * Offline smoke: load AlwaysOn codec the same way live_transport does.
 */

import { loadAlwaysOnJsCodec } from "./codec_loader.mjs";

const codec = await loadAlwaysOnJsCodec();
if (typeof codec.stringFromInvokeResult !== "function") {
  console.error("codec missing stringFromInvokeResult export");
  process.exit(1);
}
