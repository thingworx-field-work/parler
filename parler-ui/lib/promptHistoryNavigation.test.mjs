import assert from "node:assert/strict";
import test from "node:test";

import {
  initialPromptHistoryNavigationState,
  navigatePromptHistory,
  userPromptHistoryFromRows,
} from "./promptHistoryNavigation.js";

test("extracts chronological user prompt history from chat rows", () => {
  assert.deepEqual(
    userPromptHistoryFromRows([
      { kind: "user", text: "first" },
      { kind: "assistant", requestId: "r1", markdown: "", charts: [], tables: [] },
      { kind: "user", text: "second" },
      { kind: "user", text: "" },
    ]),
    ["first", "second"]
  );
});

test("up navigates from scratch draft to latest and then older prompts", () => {
  const history = ["one", "two", "three"];
  let state = initialPromptHistoryNavigationState();

  let res = navigatePromptHistory(history, state, "up", "scratch");
  assert.equal(res.handled, true);
  assert.equal(res.draft, "three");
  assert.deepEqual(res.state, { index: 0, scratch: "scratch" });

  state = res.state;
  res = navigatePromptHistory(history, state, "up", "three");
  assert.equal(res.draft, "two");
  assert.deepEqual(res.state, { index: 1, scratch: "scratch" });

  state = res.state;
  res = navigatePromptHistory(history, state, "up", "two");
  assert.equal(res.draft, "one");
  assert.deepEqual(res.state, { index: 2, scratch: "scratch" });

  state = res.state;
  res = navigatePromptHistory(history, state, "up", "one");
  assert.equal(res.draft, "one");
  assert.deepEqual(res.state, { index: 2, scratch: "scratch" });
});

test("down navigates back toward latest and restores scratch draft", () => {
  const history = ["one", "two", "three"];
  let state = { index: 2, scratch: "scratch" };

  let res = navigatePromptHistory(history, state, "down", "one");
  assert.equal(res.handled, true);
  assert.equal(res.draft, "two");
  assert.deepEqual(res.state, { index: 1, scratch: "scratch" });

  state = res.state;
  res = navigatePromptHistory(history, state, "down", "two");
  assert.equal(res.draft, "three");
  assert.deepEqual(res.state, { index: 0, scratch: "scratch" });

  state = res.state;
  res = navigatePromptHistory(history, state, "down", "three");
  assert.equal(res.draft, "scratch");
  assert.deepEqual(res.state, initialPromptHistoryNavigationState());
});

test("down outside navigation mode is not handled", () => {
  const res = navigatePromptHistory(
    ["one"],
    initialPromptHistoryNavigationState(),
    "down",
    "scratch"
  );
  assert.equal(res.handled, false);
  assert.equal(res.draft, "scratch");
});
