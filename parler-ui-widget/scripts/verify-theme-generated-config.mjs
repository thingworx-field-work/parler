import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

import { THEME_SOURCES } from "../../parler-ui/lib/themeTokens.mjs";

const generatedPath = "tmp/ui/parlerui/parlerui.config.js";
const generatedManifestPath = "tmp/configfiles/metadata.xml";
assert.ok(
  fs.existsSync(generatedPath),
  `Missing ${generatedPath}; run npm run sync and the repository-local SDK utility first.`
);
assert.ok(
  fs.existsSync(generatedManifestPath),
  `Missing ${generatedManifestPath}; run the repository-local SDK utility first.`
);

const configLine = fs
  .readFileSync(generatedPath, "utf8")
  .split("\n")
  .find((line) => line.trimStart().startsWith("const config = "));
assert.ok(configLine, "Generated config does not contain its config object");

const json = configLine.trim().slice("const config = ".length, -1);
const config = JSON.parse(json);

assert.equal(config.widgetName, "parlerui");
assert.equal(config.properties.ThemeMode.defaultValue, "mashup");
assert.equal(config.properties.ThemeMode.src, "themeMode");
assert.deepEqual(config.properties.ProgressPresentation, {
  baseType: "STRING",
  isBindingTarget: true,
  isBindingSource: false,
  defaultValue: "detailed",
  description: "Active-turn detail: detailed | compact",
  src: "progressPresentation",
});
assert.deepEqual(config.properties.ProgressLabel, {
  baseType: "STRING",
  isBindingTarget: true,
  isBindingSource: false,
  isLocalizable: true,
  defaultValue: "Thinking...",
  description: "Compact ordinary-working label",
  src: "progressLabel",
});
assert.deepEqual(config.properties.RateControlLabel, {
  baseType: "STRING",
  isBindingTarget: true,
  isBindingSource: false,
  isLocalizable: true,
  defaultValue: "Waiting for capacity...",
  description: "Compact provider rate-control label",
  src: "rateControlLabel",
});
assert.deepEqual(config.properties.EmptyStateMedia, {
  baseType: "IMAGELINK",
  isBindingTarget: true,
  isBindingSource: false,
  defaultValue: "",
  description: "Optional Media Entity image for an empty conversation",
  src: "emptyStateMedia",
});
assert.equal(config.properties.CustomClass.isBindingTarget, true);
assert.equal(config.properties.CustomClass.isBindingSource, true);
assert.equal(config.styleDict.length, 1);
assert.equal(config.styleDict[0].parts.length, 1);
assert.equal(config.styleDict[0].parts[0].part, "");
assert.deepEqual(config.styleFlatDict, []);
assert.deepEqual(config.defaultStyleFlat, {});

const generatedStyles = config.styleDict[0].parts[0].states[0].styles;
const expectedStyles = Object.fromEntries(
  THEME_SOURCES
    .map((entry) => [entry.themeName, `\${${entry.expression}}`])
);
assert.deepEqual(generatedStyles, expectedStyles);

function verifyGeneratedWrapper(fileName, isIde) {
  const generatedWrapperPath = `tmp/ui/parlerui/${fileName}`;
  assert.ok(fs.existsSync(generatedWrapperPath), `Missing ${generatedWrapperPath}`);

  const values = new Map();
  const resolved = Object.fromEntries(
    THEME_SOURCES
      .map((entry) => [entry.expression, `generated:${entry.expression}`])
  );
  const host = {
    style: {
      removeProperty: (name) => values.delete(name),
      setProperty: (name, value) => values.set(name, value),
    },
  };
  const TW = {
    IDE: { Widgets: {} },
    Runtime: { Widgets: {} },
    Widget: {
      widgetWrapper: {
        config: () => config,
        inject: (_elementName, instance) => {
          instance.jqElement = [host];
          instance.afterRender = () => {};
          instance.afterSetProperty = () => {};
          instance.changeThemeName = () => {};
        },
        loadImports: () => {},
      },
    },
    currentTheme: { name: "GeneratedVerificationTheme" },
    getStyleManager: () => ({ resolveExpressions: () => resolved }),
    log: {
      error: () => {},
      warn: () => {},
    },
  };
  vm.runInNewContext(fs.readFileSync(generatedWrapperPath, "utf8"), { TW });
  const Widget = (isIde ? TW.IDE.Widgets : TW.Runtime.Widgets).parlerui;
  const instance = new Widget();
  instance.afterRender();
  assert.equal(values.size, Object.keys(generatedStyles).length);
  assert.ok([...values.keys()].every((name) => name.startsWith("--parler-theme-")));

  for (const name of [
    "getWidgetStyleFlatDict",
    "getWidgetStyleDict",
    "getDefaultWidgetStyleFlat",
  ]) {
    assert.equal(Widget[name], undefined, `${fileName} retains ${name}`);
  }
}

verifyGeneratedWrapper("parlerui.runtime.js", false);
verifyGeneratedWrapper("parlerui.ide.js", true);

const generatedManifest = fs.readFileSync(generatedManifestPath, "utf8");
assert.ok(
  generatedManifest.includes(
    'file="parlerui.config.js" isDevelopment="true" isRuntime="true" type="JS"'
  ),
  "Generated manifest must load parlerui.config.js in Composer and at runtime"
);
assert.ok(
  generatedManifest.includes(
    'file="parlerui.ide.js" isDevelopment="true" isRuntime="false" type="JS"'
  ),
  "Generated manifest must load parlerui.ide.js in Composer"
);
assert.ok(
  generatedManifest.includes(
    'file="parlerui.runtime.js" isDevelopment="false" isRuntime="true" type="JS"'
  ),
  "Generated manifest must load parlerui.runtime.js at runtime"
);

console.log(
  "Verified generated parlerui progress/Media metadata, expression manifest, bridge-bearing wrappers, and manifest resources."
);
