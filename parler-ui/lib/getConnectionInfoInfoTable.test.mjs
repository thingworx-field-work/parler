import assert from "node:assert/strict";
import test from "node:test";
import { buildGetConnectionInfoParams } from "./alwaysOnInvokeParams.js";
import { getConnectionInfoInfoTable } from "./parlerInfotableJson.js";

test("getConnectionInfoInfoTable: shape — two String columns, agent then widget", () => {
  const t = getConnectionInfoInfoTable("MyAgent", "0.1.77");
  const entries = Object.keys(t.datashape.entries);
  assert.deepEqual(entries, ["agentThingName", "widgetPackageVersion"]);
  assert.equal(t.datashape.entries.agentThingName.baseType, "String");
  assert.equal(t.datashape.entries.widgetPackageVersion.baseType, "String");
  assert.equal(t.rows.length, 1);
  assert.equal(t.rows[0].fields.length, 2);
  assert.equal(t.rows[0].fields[0].kind, "String");
  assert.equal(t.rows[0].fields[0].value[1], "MyAgent");
  assert.equal(t.rows[0].fields[1].value[1], "0.1.77");
});

test("buildGetConnectionInfoParams matches getConnectionInfoInfoTable", () => {
  const a = getConnectionInfoInfoTable("A", "W");
  const b = buildGetConnectionInfoParams("A", "W");
  assert.deepEqual(a, b);
});

test("getConnectionInfoInfoTable: optional empty widget", () => {
  const t = getConnectionInfoInfoTable("OnlyAgent", "");
  assert.equal(t.rows[0].fields[1].value[1], "");
});
