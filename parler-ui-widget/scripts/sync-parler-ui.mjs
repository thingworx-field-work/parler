/**
 * Build ThingWorx ESM bundle (inlines markdown-it + d3; keeps lit external for mub),
 * then copy ../parler-ui → tmp/parler-ui for mub packaging (omit node_modules).
 */
import { execSync } from "child_process";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const widgetRoot = path.resolve(__dirname, "..");
const srcRoot = path.resolve(widgetRoot, "..", "parler-ui");
const dstRoot = path.join(widgetRoot, "tmp", "parler-ui");

/** @param {string} p */
function rmrf(p) {
  fs.rmSync(p, { recursive: true, force: true });
}

/** @param {string} from @param {string} to */
function shouldCopy(from) {
  const norm = from.split(path.sep).join("/");
  if (norm.includes("/node_modules/") || norm.endsWith("/node_modules")) {
    return false;
  }
  return true;
}

if (!fs.existsSync(srcRoot)) {
  console.error("Missing source:", srcRoot);
  process.exit(1);
}

try {
  execSync("node scripts/gen-widget-package-version.mjs", { cwd: srcRoot, stdio: "inherit" });
} catch {
  process.exit(1);
}
try {
  execSync("npm run build:tw", { cwd: srcRoot, stdio: "inherit" });
} catch {
  process.exit(1);
}
const twBundle = path.join(srcRoot, "parler-ui.tw.js");
if (!fs.existsSync(twBundle)) {
  console.error("Expected bundle missing after build:tw:", twBundle);
  process.exit(1);
}
const wasmArtifact = path.join(srcRoot, "alwayson_js_codec_bg.wasm");
if (!fs.existsSync(wasmArtifact)) {
  console.error("Expected codec WASM missing after build:tw:", wasmArtifact);
  process.exit(1);
}

rmrf(dstRoot);
fs.mkdirSync(path.dirname(dstRoot), { recursive: true });
// dereference: true — vendor symlinks (if any) copy as files; avoids EPERM on Windows without Developer Mode.
fs.cpSync(srcRoot, dstRoot, {
  recursive: true,
  dereference: true,
  filter: (f) => shouldCopy(f),
});

console.log("Synced parler-ui →", dstRoot);
