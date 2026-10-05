import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveEmptyStateMediaSource,
  settleEmptyStateMediaProjection,
  startEmptyStateMediaProjection,
} from "./emptyStateMedia.mjs";

test("empty and malformed IMAGELINK values use the fallback", () => {
  for (const value of [undefined, null, "", "   ", 42, {}, []]) {
    assert.deepEqual(resolveEmptyStateMediaSource(value), {
      value: "",
      source: "",
    });
  }
});

test("standalone direct and relative URLs are trimmed and preserved", () => {
  for (const value of [" /brand.svg ", " https://example.test/brand.png "]) {
    const trimmed = value.trim();
    assert.deepEqual(resolveEmptyStateMediaSource(value), {
      value: trimmed,
      source: trimmed,
    });
  }
});

test("ThingWorx conversion receives the normalized IMAGELINK", () => {
  const calls = [];
  const result = resolveEmptyStateMediaSource(
    "  /Thingworx/MediaEntities/ParlerLogo  ",
    (value) => {
      calls.push(value);
      return `  /converted${value}  `;
    }
  );
  assert.deepEqual(calls, ["/Thingworx/MediaEntities/ParlerLogo"]);
  assert.deepEqual(result, {
    value: "/Thingworx/MediaEntities/ParlerLogo",
    source: "/converted/Thingworx/MediaEntities/ParlerLogo",
  });
});

test("throwing or unusable ThingWorx conversion uses the fallback label", () => {
  for (const converter of [
    () => {
      throw new Error("conversion unavailable");
    },
    () => "   ",
    () => null,
    () => ({ src: "/not-a-string" }),
  ]) {
    assert.deepEqual(resolveEmptyStateMediaSource("/media.svg", converter), {
      value: "/media.svg",
      source: "",
    });
  }
});

test("projection settlement ignores stale and duplicate image events", () => {
  const loading = startEmptyStateMediaProjection("/one.svg", undefined, 7);
  assert.equal(loading.phase, "loading");
  assert.equal(settleEmptyStateMediaProjection(loading, 6, "loaded"), loading);

  const loaded = settleEmptyStateMediaProjection(loading, 7, "loaded");
  assert.equal(loaded.phase, "loaded");
  assert.equal(settleEmptyStateMediaProjection(loaded, 7, "failed"), loaded);

  const failed = settleEmptyStateMediaProjection(
    startEmptyStateMediaProjection("/two.svg", undefined, 8),
    8,
    "failed"
  );
  assert.equal(failed.phase, "failed");
});
