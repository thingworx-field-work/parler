import assert from "node:assert/strict";
import test from "node:test";

// Fixed display zone so labels are deterministic; Node re-reads TZ at runtime.
process.env.TZ = "Asia/Shanghai";

const {
  displayTimeZoneLabel,
  fitTimeTicks,
  formatFullLocalTime,
  localOffsetLabel,
  timeTickFormatter,
  timeTickLabels,
} = await import("./chartTimeFormat.js");

const at = (iso) => new Date(iso);

test("same-day ticks show time only, with seconds when any tick carries them", () => {
  const { labels, crossDay, showOffset } = timeTickLabels([
    at("2026-09-12T00:00:00Z"),
    at("2026-09-12T01:00:00Z"),
    at("2026-09-12T02:00:00Z"),
  ]);
  assert.deepEqual(labels, ["08:00", "09:00", "10:00"]);
  assert.equal(crossDay, false);
  assert.equal(showOffset, false);
  const withSeconds = timeTickLabels([at("2026-09-12T00:00:00Z"), at("2026-09-12T00:00:30Z")]);
  assert.deepEqual(withSeconds.labels, ["08:00:00", "08:00:30"]);
});

test("ticks spanning more than one local day carry the date", () => {
  const spanning = timeTickLabels([
    at("2026-09-12T10:00:00Z"),
    at("2026-09-12T22:00:00Z"),
    at("2026-09-13T10:00:00Z"),
  ]);
  assert.deepEqual(spanning.labels, ["09-12 18:00", "09-13 06:00", "09-13 18:00"]);
  assert.equal(spanning.crossDay, true);
  const midnights = timeTickLabels([
    at("2026-09-11T16:00:00Z"),
    at("2026-09-12T16:00:00Z"),
    at("2026-09-13T16:00:00Z"),
  ]);
  assert.deepEqual(midnights.labels, ["09-12", "09-13", "09-14"], "local midnights drop the time");
});

test("a local day boundary in the display zone, not UTC, decides the cross-day format", () => {
  // 15:30Z and 16:30Z are both 2026-09-12 in UTC but 23:30 and 00:30 next day in Asia/Shanghai.
  const { labels, crossDay } = timeTickLabels([at("2026-09-12T15:30:00Z"), at("2026-09-12T16:30:00Z")]);
  assert.equal(crossDay, true);
  assert.deepEqual(labels, ["09-12 23:30", "09-13 00:30"]);
});

test("repeated local times around a DST fall-back get their UTC offsets", () => {
  process.env.TZ = "America/New_York";
  try {
    const ticks = [at("2026-11-01T05:30:00Z"), at("2026-11-01T06:30:00Z"), at("2026-11-01T07:30:00Z")];
    const { labels, showOffset } = timeTickLabels(ticks);
    assert.equal(showOffset, true);
    assert.deepEqual(labels, ["01:30 UTC-04:00", "01:30 UTC-05:00", "02:30 UTC-05:00"]);
    const fmt = timeTickFormatter(ticks);
    assert.equal(fmt.format(ticks[1]), "01:30 UTC-05:00");
    assert.equal(fmt.showOffset, true);
    assert.equal(localOffsetLabel(ticks[0]), "UTC-04:00");
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});

test("full local time and display zone label for tooltips", () => {
  const d = at("2026-09-12T06:03:05Z");
  assert.equal(formatFullLocalTime(d), "2026-09-12 14:03:05");
  const label = displayTimeZoneLabel(d);
  assert.match(label, /UTC\+08:00/);
  assert.match(label, /Asia\/Shanghai/);
});

test("formatter maps its own ticks and falls back for other instants", () => {
  const ticks = [at("2026-09-12T00:00:00Z"), at("2026-09-12T01:00:00Z")];
  const fmt = timeTickFormatter(ticks);
  assert.equal(fmt.format(ticks[0]), "08:00");
  assert.equal(fmt.format(at("2026-09-12T00:30:00Z")), "08:30");
  assert.equal(fmt.crossDay, false);
});

test("subsecond instants keep milliseconds and are not mistaken for a DST repeat", () => {
  const ticks = [at("2026-09-12T12:00:00.100Z"), at("2026-09-12T12:00:00.900Z")];
  const { labels, showOffset } = timeTickLabels(ticks);
  assert.deepEqual(labels, ["20:00:00.100", "20:00:00.900"]);
  assert.equal(showOffset, false, "same offset: precision, not DST");
  assert.equal(formatFullLocalTime(ticks[0]), "2026-09-12 20:00:00.100");
  assert.equal(formatFullLocalTime(at("2026-09-12T12:00:00Z")), "2026-09-12 20:00:00", "whole seconds stay short");
  const sameMinute = timeTickLabels([at("2026-09-12T12:00:10Z"), at("2026-09-12T12:00:40Z")]);
  assert.deepEqual(sameMinute.labels, ["20:00:10", "20:00:40"]);
});

test("fitTimeTicks reduces the count until formatted labels fit and drops edge-clipped ticks", () => {
  const start = at("2026-11-01T04:00:00Z").getTime();
  const end = at("2026-11-01T08:00:00Z").getTime();
  const innerW = 260;
  const xFor = (d) => ((d.getTime() - start) / (end - start)) * innerW;
  const ticksFor = (n) => {
    const count = Math.max(2, Math.min(9, n + 1));
    return Array.from({ length: count }, (_, i) => new Date(start + ((end - start) * i) / (count - 1)));
  };
  process.env.TZ = "America/New_York";
  try {
    const fit = fitTimeTicks({ ticksFor, xFor, desired: 5, innerW, leftRoom: 40, rightRoom: 20, charWidth: 7.44 });
    const kept = fit.ticks.map(fit.format);
    assert.equal(new Set(kept).size, kept.length, "kept ticks never print identical labels");
    const wideX = (d) => ((d.getTime() - start) / (end - start)) * 4000;
    const dense = fitTimeTicks({ ticksFor, xFor: wideX, desired: 8, innerW: 4000, leftRoom: 40, rightRoom: 20, charWidth: 7.44 });
    assert.equal(dense.showOffset, true, "when the repeated 01:30 pair is kept, offsets disambiguate it");
    const xs = fit.ticks.map(xFor);
    for (let i = 1; i < xs.length; i++) {
      assert.ok(xs[i] - xs[i - 1] >= fit.labelWidth + 8, "adjacent labels do not overlap");
    }
    for (const x of xs) {
      assert.ok(x - fit.labelWidth / 2 >= -40 && x + fit.labelWidth / 2 <= innerW + 20, "labels stay inside the gutters");
    }
    assert.ok(fit.ticks.length >= 1);
    assert.ok(fit.ticks.length < 9, "the narrow plot cannot keep the full nine-tick candidate set");
    const roomyX = (d) => ((d.getTime() - start) / (end - start)) * 2000;
    const roomy = fitTimeTicks({ ticksFor, xFor: roomyX, desired: 5, innerW: 2000, leftRoom: 40, rightRoom: 20, charWidth: 7.44 });
    assert.equal(roomy.ticks.length, 6, "a wide plot keeps the requested density");
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});

test("fitTimeTicks fallback never returns a clipped label", () => {
  const ticksFor = () => [at("2026-09-12T00:00:00Z"), at("2026-09-12T01:00:00Z"), at("2026-09-12T02:00:00Z")];
  const xFor = (d) => (d.getHours() - 8) * 10; // 0, 10, 20 across a 20px plot; labels are 50px wide
  const nothing = fitTimeTicks({ ticksFor, xFor, desired: 5, innerW: 20, leftRoom: 0, rightRoom: 0, charWidth: 10 });
  assert.equal(nothing.ticks.length, 0, "no tick label rather than a clipped one");
  const contained = (fit, w, l, r) =>
    fit.ticks.every((t) => xFor(t) - fit.labelWidth / 2 >= -l && xFor(t) + fit.labelWidth / 2 <= w + r);
  const centred = fitTimeTicks({
    ticksFor,
    xFor,
    desired: 5,
    innerW: 60,
    leftRoom: 0,
    rightRoom: 0,
    charWidth: 10,
    instantAt: (x) => new Date(at("2026-09-12T00:00:00Z").getTime() + (x / 10) * 3600 * 1000),
  });
  assert.equal(centred.ticks.length, 1, "the plot-centre instant is used when generated ticks all clip");
  assert.ok(contained(centred, 60, 0, 0));
  assert.equal(centred.format(centred.ticks[0]), "11:00");
});

test("fitTimeTicks with real D3 ticks over a subsecond cross-day range keeps its only label inside the gutters", async () => {
  const { scaleTime } = await import("d3-scale");
  const innerW = 260;
  const scale = scaleTime()
    .domain([at("2026-09-12T03:59:59.900Z"), at("2026-09-12T04:00:00.000Z")])
    .range([0, innerW]);
  process.env.TZ = "America/New_York";
  try {
    const fit = fitTimeTicks({
      ticksFor: (n) => scale.ticks(n),
      xFor: (d) => scale(d),
      instantAt: (x) => scale.invert(x),
      desired: 5,
      innerW,
      leftRoom: 40,
      rightRoom: 20,
      charWidth: 12 * 0.62,
    });
    assert.ok(fit.ticks.length >= 1, "a readable tick is kept");
    for (const t of fit.ticks) {
      const x = scale(t);
      assert.ok(x - fit.labelWidth / 2 >= -40, `left edge of ${fit.format(t)} inside the gutter`);
      assert.ok(x + fit.labelWidth / 2 <= innerW + 20, `right edge of ${fit.format(t)} inside the gutter`);
    }
    assert.match(fit.format(fit.ticks[0]), /^09-1[12] \d\d:\d\d:\d\d\.\d{3}$/, "cross-day context and precision kept");
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});
