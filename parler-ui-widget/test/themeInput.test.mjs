import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";

import {
  THEME_SOURCES,
  THEME_TOKENS,
} from "../../parler-ui/lib/themeTokens.mjs";

const widget = JSON.parse(fs.readFileSync("input/widgets/parler-ui.json", "utf8"));
const styleDict = JSON.parse(
  fs.readFileSync("input/styles/parlerui/style.dict.json", "utf8")
);
const styleProperties = JSON.parse(
  fs.readFileSync("input/styles/parlerui/style.properties.json", "utf8")
);

function createHost() {
  const values = new Map();
  const writes = [];
  const removals = [];
  return {
    style: {
      getPropertyValue: (name) => values.get(name) ?? "",
      removeProperty: (name) => {
        removals.push(name);
        const old = values.get(name) ?? "";
        values.delete(name);
        return old;
      },
      setProperty: (name, value) => {
        writes.push([name, value]);
        values.set(name, value);
      },
    },
    values,
    writes,
    removals,
  };
}

function evaluateWrapper(relativePath, isIde, options = {}) {
  const config = {
    elementName: "parler-ui",
    imports: { "parler-ui": { src: "parler-ui/parler-ui.tw.js" } },
    styleDict: options.styleDict ?? styleDict,
    styleFlatDict: styleProperties.map(({ id }) => ({ id })),
    defaultStyleFlat: Object.fromEntries(
      styleProperties.map(({ id, defaultValue }) => [id, defaultValue])
    ),
  };
  const host = createHost();
  const loadedImports = [];
  const lifecycleCalls = [];
  const resolverCalls = [];
  const warnings = [];
  let resolverResult = options.resolverResult ?? {};
  const currentTheme = { name: "ThemeA" };
  const TW = {
    IDE: { Widgets: {} },
    Runtime: { Widgets: {} },
    Widget: {
      widgetWrapper: {
        config: () => config,
        inject: (_elementName, instance) => {
          instance.jqElement = [host];
          instance.afterRender = function() {
            lifecycleCalls.push(["afterRender", ...arguments]);
            return "after-render-result";
          };
          instance.afterSetProperty = function() {
            lifecycleCalls.push(["afterSetProperty", ...arguments]);
            return "after-set-result";
          };
          instance.changeThemeName = function() {
            lifecycleCalls.push(["changeThemeName", ...arguments]);
            return "change-theme-result";
          };
        },
        loadImports: (imports) => loadedImports.push(imports),
      },
    },
    currentTheme,
    getStyleManager: () => ({
      resolveExpressions: (...args) => {
        resolverCalls.push(args);
        if (resolverResult instanceof Error) {
          throw resolverResult;
        }
        return resolverResult;
      },
    }),
    log: {
      error: () => {},
      warn: (message) => warnings.push(message),
    },
  };

  vm.runInNewContext(fs.readFileSync(relativePath, "utf8"), { TW });
  const Widget = (isIde ? TW.IDE.Widgets : TW.Runtime.Widgets).parlerui;
  const instance = new Widget();
  return {
    config,
    currentTheme,
    host,
    instance,
    lifecycleCalls,
    loadedImports,
    resolverCalls,
    warnings,
    setResolverResult: (value) => {
      resolverResult = value;
    },
    Widget,
  };
}

function resolvedInitialExpressions(prefix = "resolved") {
  return Object.fromEntries(
    THEME_SOURCES
      .map((entry) => [entry.expression, `${prefix}:${entry.expression}`])
  );
}

test("ThemeMode metadata uses the reviewed one-way target contract", () => {
  assert.deepEqual(widget.properties.ThemeMode, {
    baseType: "STRING",
    isBindingTarget: true,
    isBindingSource: false,
    defaultValue: "mashup",
    description: "Theme source: mashup | parler-dark",
    src: "themeMode",
  });
});

test("progress presentation metadata exposes the reviewed target-only contract", () => {
  assert.deepEqual(widget.properties.ProgressPresentation, {
    baseType: "STRING",
    isBindingTarget: true,
    isBindingSource: false,
    defaultValue: "detailed",
    description: "Active-turn detail: detailed | compact",
    src: "progressPresentation",
  });
  assert.deepEqual(widget.properties.ProgressLabel, {
    baseType: "STRING",
    isBindingTarget: true,
    isBindingSource: false,
    isLocalizable: true,
    defaultValue: "Thinking...",
    description: "Compact ordinary-working label",
    src: "progressLabel",
  });
  assert.deepEqual(widget.properties.RateControlLabel, {
    baseType: "STRING",
    isBindingTarget: true,
    isBindingSource: false,
    isLocalizable: true,
    defaultValue: "Waiting for capacity...",
    description: "Compact provider rate-control label",
    src: "rateControlLabel",
  });
});

test("empty-state Media metadata preserves the IMAGELINK content contract", () => {
  assert.deepEqual(widget.properties.EmptyStateMedia, {
    baseType: "IMAGELINK",
    isBindingTarget: true,
    isBindingSource: false,
    defaultValue: "",
    description: "Optional Media Entity image for an empty conversation",
    src: "emptyStateMedia",
  });
});

test("CustomClass is bindable in both directions for scoped authoring", () => {
  assert.equal(widget.properties.CustomClass.isBindingTarget, true);
  assert.equal(widget.properties.CustomClass.isBindingSource, true);
});

test("the active dictionary is a host-only expression manifest for the current inventory", () => {
  const inactiveFiles = fs.existsSync("input/styles/aiparler")
    ? fs.readdirSync("input/styles/aiparler")
    : [];
  assert.deepEqual(inactiveFiles, []);
  assert.equal(styleDict.length, 1);
  assert.equal(styleDict[0].variant, "");
  assert.equal(styleDict[0].parts.length, 1);
  assert.equal(styleDict[0].parts[0].part, "");
  assert.equal(styleDict[0].parts[0].states.length, 1);
  assert.equal(styleDict[0].parts[0].states[0].stateHost, "");

  const manifest = styleDict[0].parts[0].states[0].styles;
  const expected = Object.fromEntries(
    THEME_SOURCES
      .map((entry) => [entry.themeName, `\${${entry.expression}}`])
  );
  assert.deepEqual(manifest, expected);
  assert.ok(Object.keys(manifest).every((name) => name.startsWith("--parler-theme-")));
});

test("unsupported extension flat properties are not packaged", () => {
  assert.deepEqual(styleProperties, []);
  assert.equal(
    THEME_TOKENS.find((entry) => entry.publicName === "--parler-color-warning")
      ?.expression,
    null
  );
});

test("runtime bridge delegates lifecycle and fans resolved expressions out by token", () => {
  const runtime = evaluateWrapper("input/ui/parlerui/parlerui.runtime.js", false, {
    resolverResult: resolvedInitialExpressions(),
  });

  assert.equal(runtime.instance.afterRender("initial"), "after-render-result");
  assert.deepEqual(runtime.lifecycleCalls, [["afterRender", "initial"]]);
  assert.equal(runtime.resolverCalls.length, 1);
  assert.equal(runtime.resolverCalls[0][0], runtime.currentTheme);
  assert.equal(runtime.resolverCalls[0][1], "parler-ui");
  assert.equal(runtime.resolverCalls[0][2], "");
  assert.equal(runtime.resolverCalls[0][3]("missing"), "");

  assert.equal(runtime.host.values.size, THEME_SOURCES.length);
  for (const entry of THEME_SOURCES) {
    assert.equal(
      runtime.host.values.get(entry.themeName),
      `resolved:${entry.expression}`
    );
  }
  assert.ok([...runtime.host.values.keys()].every((name) => name.startsWith("--parler-theme-")));
  assert.ok([...runtime.host.values.keys()].every((name) => !/^--parler-(?!theme-)/.test(name)));

  assert.equal(runtime.instance.changeThemeName("ThemeB", true), "change-theme-result");
  assert.equal(runtime.resolverCalls.length, 2);
  assert.deepEqual(runtime.loadedImports, [runtime.config.imports]);
});

test("one expression populates every token mapped to it", () => {
  const styles = Object.fromEntries(
    Array.from({ length: 10 }, (_, index) => [
      `--parler-theme-fanout-${index + 1}`,
      "${global-color-bg-secondary}",
    ])
  );
  const fanoutDict = [
    {
      variant: "",
      parts: [{ part: "", states: [{ state: "", stateHost: "", styles }] }],
    },
  ];
  const runtime = evaluateWrapper("input/ui/parlerui/parlerui.runtime.js", false, {
    styleDict: fanoutDict,
    resolverResult: { "global-color-bg-secondary": "#123456" },
  });

  runtime.instance.afterRender();
  assert.equal(runtime.host.values.size, 10);
  for (const tokenName of Object.keys(styles)) {
    assert.equal(runtime.host.values.get(tokenName), "#123456");
  }
});

test("malformed internal manifest entries warn and remain on fallback", () => {
  const malformedDict = [
    {
      variant: "",
      parts: [
        {
          part: "",
          states: [
            {
              state: "",
              stateHost: "",
              styles: {
                "--parler-theme-valid": "${global-color-bg-primary}",
                "--parler-theme-invalid":
                  "var(--other, ${global-color-bg-secondary})",
                color: "${global-color-text-body}",
              },
            },
          ],
        },
      ],
    },
  ];

  for (const [fileName, isIde] of [
    ["parlerui.runtime.js", false],
    ["parlerui.ide.js", true],
  ]) {
    const wrapper = evaluateWrapper(`input/ui/parlerui/${fileName}`, isIde, {
      styleDict: malformedDict,
      resolverResult: { "global-color-bg-primary": "#abcdef" },
    });
    assert.equal(wrapper.warnings.length, 1, fileName);
    assert.match(wrapper.warnings[0], /--parler-theme-invalid/);
    assert.match(wrapper.warnings[0], /one bare \$\{expression\}/);

    wrapper.instance.afterRender();
    assert.deepEqual(
      Object.fromEntries(wrapper.host.values),
      { "--parler-theme-valid": "#abcdef" },
      fileName
    );
  }
});

test("null and resolver errors preserve bridge-owned declarations without polling", () => {
  const runtime = evaluateWrapper("input/ui/parlerui/parlerui.runtime.js", false, {
    resolverResult: resolvedInitialExpressions("first"),
  });
  runtime.instance.afterRender();
  const initial = new Map(runtime.host.values);
  const initialWrites = runtime.host.writes.length;

  runtime.setResolverResult(null);
  runtime.instance.changeThemeName("UnknownTheme", true);
  assert.deepEqual(runtime.host.values, initial);
  assert.equal(runtime.host.writes.length, initialWrites);
  assert.deepEqual(runtime.host.removals, []);

  runtime.setResolverResult(new Error("Theme not ready"));
  runtime.instance.changeThemeName("StillUnavailable", true);
  assert.deepEqual(runtime.host.values, initial);
  assert.equal(runtime.host.writes.length, initialWrites);
  assert.deepEqual(runtime.host.removals, []);

  runtime.setResolverResult({});
  runtime.instance.changeThemeName("ResolvedEmptyTheme", true);
  assert.equal(runtime.host.values.size, 0);
  assert.deepEqual(runtime.host.removals.sort(), [...initial.keys()].sort());
});

test("Composer bridge refreshes after render and property-update lifecycle", () => {
  const ide = evaluateWrapper("input/ui/parlerui/parlerui.ide.js", true, {
    resolverResult: resolvedInitialExpressions("composer"),
  });

  assert.equal(ide.instance.afterRender(), "after-render-result");
  assert.equal(ide.instance.afterSetProperty("ThemeMode", "mashup"), "after-set-result");
  assert.deepEqual(ide.lifecycleCalls, [
    ["afterRender"],
    ["afterSetProperty", "ThemeMode", "mashup"],
  ]);
  assert.equal(ide.resolverCalls.length, 2);
  assert.deepEqual(ide.loadedImports, [ide.config.imports]);
});

test("probe-era static registration methods are absent", () => {
  const runtime = evaluateWrapper("input/ui/parlerui/parlerui.runtime.js", false);
  const ide = evaluateWrapper("input/ui/parlerui/parlerui.ide.js", true);

  for (const name of [
    "getWidgetStyleFlatDict",
    "getWidgetStyleDict",
    "getDefaultWidgetStyleFlat",
  ]) {
    assert.equal(runtime.Widget[name], undefined, `runtime ${name}`);
    assert.equal(ide.Widget[name], undefined, `IDE ${name}`);
  }
});
