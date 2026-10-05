/**
 * esbuild: wasm-bindgen expects `import * as wasm from "*.wasm"` to be instance.exports.
 * The default `loader: "file"` only yields `{ default: url }`, so `wasm.__wbindgen_start` is undefined
 * and the ThingWorx bundle throws `(void 0) is not a function` at load time.
 *
 * This plugin replaces @xudesheng/alwayson-js-codec's alwayson_js_codec_bg.wasm with a JS shim that
 * embeds the WASM bytes, links imports from alwayson_js_codec_bg.js, and re-exports instance.exports.
 *
 * **Build-time note:** `new WebAssembly.Module(bytes)` on the codec WASM can require V8 features
 * (e.g. `externref`) that some Node builds reject during esbuild. We therefore introspect exports /
 * imports with **`@webassemblyjs/wasm-parser`** (`ignoreCodeSection: true` / `ignoreDataSection: true`)
 * instead of compiling the module at esbuild time, with a **`WebAssembly.Module`** fallback if parsing fails.
 */
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { decode } = require("@webassemblyjs/wasm-parser");
const t = require("@webassemblyjs/ast");

/**
 * @param {Buffer} bytes
 * @returns {{ exportNames: string[], byModule: Record<string, string[]> }}
 */
function wasmExportImportMeta(bytes) {
  const ast = decode(bytes, {
    ignoreCodeSection: true,
    ignoreDataSection: true,
  });
  /** @type {string[]} */
  const exportNames = [];
  t.traverse(ast, {
    ModuleExport(path) {
      const n = path.node;
      if (n.name) exportNames.push(n.name);
    },
  });
  /** @type {Record<string, string[]>} */
  const byModule = {};
  t.traverse(ast, {
    ModuleImport(path) {
      const n = path.node;
      const mod = n.module;
      const name = n.name;
      if (!mod || !name) return;
      if (!byModule[mod]) byModule[mod] = [];
      byModule[mod].push(name);
    },
  });
  return { exportNames, byModule };
}

/** @returns {boolean} */
function isCodecWasmPath(resolved) {
  const n = resolved.replace(/\\/g, "/");
  return n.includes("@xudesheng/alwayson-js-codec/") && n.endsWith("alwayson_js_codec_bg.wasm");
}

export function alwaysonCodecWasmPlugin() {
  return {
    name: "alwayson-codec-wasm-sync-instantiate",
    setup(build) {
      build.onResolve({ filter: /\.wasm$/ }, (args) => {
        const resolved = path.isAbsolute(args.path)
          ? args.path
          : path.resolve(args.resolveDir, args.path);
        if (!isCodecWasmPath(resolved)) {
          return null;
        }
        return {
          path: resolved,
          namespace: "alwayson-wasm-sync",
        };
      });

      build.onLoad({ filter: /.*/, namespace: "alwayson-wasm-sync" }, (args) => {
        const wasmPath = args.path;
        const resolveDir = path.dirname(wasmPath);
        const bytes = fs.readFileSync(wasmPath);
        let exportNames;
        /** @type {Record<string, string[]>} */
        let byModule;
        try {
          const meta = wasmExportImportMeta(bytes);
          exportNames = meta.exportNames;
          byModule = meta.byModule;
        } catch {
          const wasmModule = new WebAssembly.Module(bytes);
          exportNames = WebAssembly.Module.exports(wasmModule).map((e) => e.name);
          const im = WebAssembly.Module.imports(wasmModule);
          byModule = {};
          for (const row of im) {
            if (!byModule[row.module]) {
              byModule[row.module] = [];
            }
            byModule[row.module].push(row.name);
          }
        }
        const modules = Object.keys(byModule);
        if (modules.length !== 1) {
          throw new Error(
            `alwayson-wasm-sync: expected 1 wasm import module, got ${modules.length} (${wasmPath})`
          );
        }
        const jsGlueModule = modules[0];
        if (jsGlueModule !== "./alwayson_js_codec_bg.js") {
          throw new Error(
            `alwayson-wasm-sync: unexpected wasm import module ${JSON.stringify(jsGlueModule)}`
          );
        }
        const glueNames = byModule[jsGlueModule];
        const importList = glueNames.join(",\n  ");
        const importObjectPairs = glueNames.map((n) => `${n}`).join(",\n    ");
        const b64 = bytes.toString("base64");
        const exportLines = exportNames.map((n) => `export const ${n}=_e.${n};`).join("\n");
        const contents = `import {
  ${importList},
} from ${JSON.stringify(jsGlueModule)};
function __decodeWasmBase64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const _wasmBytes = __decodeWasmBase64(${JSON.stringify(b64)});
const _wasmMod = new WebAssembly.Module(_wasmBytes);
const _wasmInst = new WebAssembly.Instance(_wasmMod, {
  ${JSON.stringify(jsGlueModule)}: {
    ${importObjectPairs},
  },
});
const _e = _wasmInst.exports;
${exportLines}
`;
        return {
          contents,
          loader: "js",
          resolveDir,
        };
      });
    },
  };
}
