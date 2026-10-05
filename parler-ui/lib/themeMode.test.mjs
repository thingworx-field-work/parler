import assert from "node:assert/strict";
import test from "node:test";

import {
  createThemeModeNormalizer,
  DEFAULT_THEME_MODE,
  THEME_MODES,
} from "./themeMode.mjs";

test("ThemeMode exposes the two reviewed values and defaults to mashup", () => {
  assert.deepEqual(THEME_MODES, ["mashup", "parler-dark"]);
  assert.equal(DEFAULT_THEME_MODE, "mashup");
});

test("ThemeMode preserves exact accepted values", () => {
  const normalize = createThemeModeNormalizer({
    warn: () => assert.fail("accepted values must not warn"),
  });

  assert.equal(normalize("mashup"), "mashup");
  assert.equal(normalize("parler-dark"), "parler-dark");
});

test("ThemeMode normalizes missing, empty, unknown, and wrongly cased values", () => {
  const warnings = [];
  const normalize = createThemeModeNormalizer({ warn: (message) => warnings.push(message) });

  assert.equal(normalize(undefined), "mashup");
  assert.equal(normalize(null), "mashup");
  assert.equal(normalize(""), "mashup");
  assert.equal(normalize("system"), "mashup");
  assert.equal(normalize("PARLER-DARK"), "mashup");
  assert.equal(warnings.length, 5);
});

test("ThemeMode warns at most once for each distinct invalid value", () => {
  const warnings = [];
  const normalize = createThemeModeNormalizer({ warn: (message) => warnings.push(message) });

  normalize("system");
  normalize("system");
  normalize(3);
  normalize(3);
  normalize(3n);
  normalize(3n);

  assert.equal(warnings.length, 3);
  assert.match(warnings[0], /Invalid ThemeMode "system"/);
  assert.match(warnings[1], /Invalid ThemeMode 3/);
  assert.match(warnings[2], /Invalid ThemeMode 3/);
});
