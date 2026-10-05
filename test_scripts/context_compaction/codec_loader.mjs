/**
 * Resolve @xudesheng/alwayson-js-codec through parler-ui's declared dependency.
 */

import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const PARLER_UI_LIB = join(REPO_ROOT, "parler-ui", "lib");
const CODEC_ANCHOR = join(PARLER_UI_LIB, "alwaysOnParlerClient.js");

/** @returns {Promise<typeof import("@xudesheng/alwayson-js-codec")>} */
export async function loadAlwaysOnJsCodec() {
  const require = createRequire(pathToFileURL(CODEC_ANCHOR).href);
  const codecPath = require.resolve("@xudesheng/alwayson-js-codec");
  return import(pathToFileURL(codecPath).href);
}
