/*
 * ThingWorx widget bundle: parler-ui.tw.js
 * Bundles @xudesheng/alwayson-js-codec (WASM). Lit stays external for mub.
 */
import * as esbuild from "esbuild";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { alwaysonCodecWasmPlugin } from "./esbuild-alwayson-wasm-plugin.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, "..");

const amdGuardGlobal =
  "typeof globalThis!=='undefined'?globalThis:typeof window!=='undefined'?window:self";
const banner = {
  js: `;(function(g){g.__aiParlerTwAmdDefine=g.define;g.define=void 0;})(${amdGuardGlobal});`,
};
const footer = {
  js: `;(function(g){var s=g.__aiParlerTwAmdDefine;delete g.__aiParlerTwAmdDefine;if(s!==void 0)g.define=s;})(${amdGuardGlobal});`,
};

const result = await esbuild.build({
  entryPoints: [path.join(root, "parler-ui.js")],
  bundle: true,
  format: "esm",
  platform: "browser",
  target: "es2022",
  outfile: path.join(root, "parler-ui.tw.js"),
  logLevel: "warning",
  metafile: true,
  banner,
  footer,
  plugins: [alwaysonCodecWasmPlugin()],
  loader: { ".wasm": "file" },
  assetNames: "[name]",
  external: [
    "lit",
    "lit/directives/repeat.js",
    "lit/directives/unsafe-html.js",
    "lit/directives/ref.js",
  ],
  nodePaths: [path.join(root, "node_modules")],
  alias: {
    util: path.join(root, "shims/node-util-stub.js"),
    events: path.join(root, "shims/events-stub.js"),
  },
});

const wasmSrc = path.join(
  root,
  "node_modules",
  "@xudesheng",
  "alwayson-js-codec",
  "alwayson_js_codec_bg.wasm"
);
const wasmOut = path.join(root, "alwayson_js_codec_bg.wasm");
if (!fs.existsSync(wasmSrc)) {
  console.error("esbuild-tw: missing codec wasm at", wasmSrc);
  process.exit(1);
}
fs.copyFileSync(wasmSrc, wasmOut);

writeBundledLicenses(result.metafile, path.join(root, "THIRD_PARTY_LICENSES.txt"));

/** Collects the license text of every npm package inlined into the bundle. */
function writeBundledLicenses(metafile, outFile) {
  const packageDirs = new Set();
  for (const key of Object.keys(metafile.inputs)) {
    const input = key.replace(/^[\w-]+:(?=\/)/, "");
    const m = input.match(/^(.*node_modules\/(?:@[^/]+\/)?[^/]+)\//);
    if (m) packageDirs.add(path.resolve(root, m[1]));
  }
  const sections = [...packageDirs].sort().map((dir) => {
    const pkg = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
    const licenseFile = fs
      .readdirSync(dir)
      .find((name) => /^(licen[sc]e|copying)([-.][\w.-]*)?$/i.test(name));
    if (!licenseFile) {
      console.error(`esbuild-tw: no license file in bundled package ${pkg.name}`);
      process.exit(1);
    }
    const text = fs.readFileSync(path.join(dir, licenseFile), "utf8").trim();
    return `${pkg.name}@${pkg.version} (${pkg.license})\n\n${text}\n`;
  });
  const header =
    "Third-party packages bundled into parler-ui.tw.js and their license texts.\n";
  fs.writeFileSync(outFile, [header, ...sections].join("\n" + "-".repeat(72) + "\n\n"));
}
