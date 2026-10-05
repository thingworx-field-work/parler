/**
 * One-shot release pair for Docker / local TWX deploys:
 *   1) parler-ui: npm run build:tw → parler-ui.tw.js + alwayson_js_codec_bg.wasm
 *   2) parler-agent: Gradle extensionZip → build/parler-agent.zip (stable alias)
 *
 * Usage (from parler repo root):
 *   node scripts/build-twx-release-pair.mjs
 *   node scripts/build-twx-release-pair.mjs --bump   # increment parler-ui patch + extension revisionVersion first
 *
 * Extension path: env TWX_AGENT_EXTENSION_ROOT, else ${parlerRoot}/parler-agent
 *
 * Gradle: mirrors root ./build-extension.sh — when twx-lib/all has more than ten
 * *.jar files, passes -PuseLocalTwxLib=true so the build does not hit PTC
 * Artifactory for ThingWorx libs; otherwise -PuseLocalTwxLib=false.
 */
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const parlerRoot = path.resolve(__dirname, "..");
const parlerUiRoot = path.join(parlerRoot, "parler-ui");
const extRoot =
  process.env.TWX_AGENT_EXTENSION_ROOT?.trim() ||
  path.join(parlerRoot, "parler-agent");

const args = process.argv.slice(2);
const doBump = args.includes("--bump");

function bumpPatch(ver) {
  const m = String(ver).match(/^(\d+)\.(\d+)\.(\d+)$/);
  if (!m) throw new Error(`Cannot bump version: ${ver}`);
  return `${m[1]}.${m[2]}.${Number(m[3]) + 1}`;
}

/** Same heuristic as `build-extension.sh` (see how-to-build.md). */
function gradleUseLocalTwxLibFlag(extensionRoot) {
  const libDir = path.join(extensionRoot, "twx-lib", "all");
  if (!fs.existsSync(libDir) || !fs.statSync(libDir).isDirectory()) {
    return "-PuseLocalTwxLib=false";
  }
  const jars = fs.readdirSync(libDir).filter((n) => n.endsWith(".jar"));
  if (jars.length > 10) {
    console.log(
      `build-twx-release-pair: twx-lib/all has ${jars.length} jar(s); using -PuseLocalTwxLib=true`
    );
    return "-PuseLocalTwxLib=true";
  }
  return "-PuseLocalTwxLib=false";
}

function bumpGradleRevision(buildGradleText) {
  const replaced = buildGradleText.replace(
    /(revisionVersion\s*=\s*)(\d+)/,
    (_, pfx, n) => `${pfx}${Number(n) + 1}`
  );
  if (replaced === buildGradleText) {
    throw new Error("revisionVersion not found in build.gradle");
  }
  return replaced;
}

if (doBump) {
  const pkgPath = path.join(parlerUiRoot, "package.json");
  const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
  pkg.version = bumpPatch(pkg.version);
  fs.writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`, "utf8");
  console.log("Bumped parler-ui version →", pkg.version);

  const gradlePath = path.join(extRoot, "build.gradle");
  const gradle = fs.readFileSync(gradlePath, "utf8");
  fs.writeFileSync(gradlePath, bumpGradleRevision(gradle), "utf8");
  console.log("Bumped extension revisionVersion in", gradlePath);
}

if (!fs.existsSync(path.join(parlerUiRoot, "package.json"))) {
  console.error("Missing parler-ui at", parlerUiRoot);
  process.exit(1);
}
if (!fs.existsSync(path.join(extRoot, "gradlew.bat")) && !fs.existsSync(path.join(extRoot, "gradlew"))) {
  console.error("Missing parler-agent (or extension) at", extRoot);
  console.error("Set TWX_AGENT_EXTENSION_ROOT if using a different checkout.");
  process.exit(1);
}

console.log("Building parler-ui (build:tw)…");
execSync("npm run build:tw", { cwd: parlerUiRoot, stdio: "inherit" });

const isWin = process.platform === "win32";
const gradlew = path.join(extRoot, isWin ? "gradlew.bat" : "gradlew");
const localTwx = gradleUseLocalTwxLibFlag(extRoot);
console.log("Building extension (extensionZip)…");
execSync(`"${gradlew}" extensionZip --no-daemon ${localTwx}`, {
  cwd: extRoot,
  stdio: "inherit",
  windowsHide: true,
});

const zipStable = path.join(extRoot, "build", "parler-agent.zip");
const twJs = path.join(parlerUiRoot, "parler-ui.tw.js");
const wasm = path.join(parlerUiRoot, "alwayson_js_codec_bg.wasm");

console.log("\nDone. Deploy artifacts:\n");
for (const p of [twJs, wasm, zipStable]) {
  const ok = fs.existsSync(p);
  console.log(`  ${ok ? "✓" : "✗"} ${p}`);
}
if (!fs.existsSync(zipStable)) {
  console.warn("\nIf zip path differs, check build/libs/*.zip and build/tmp/");
}
