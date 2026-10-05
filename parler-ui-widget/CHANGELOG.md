# Changelog — Parler UI widget (`parler-ui` bundle)

Notable changes to the Parler chat widget: the `<parler-ui>` web component packaged as a ThingWorx
Composer widget extension by `parler-ui-widget`. Each version below is the widget package version
(`version` in `parler-ui-widget/input/widgets.json`, shown as the extension's package version after
import). Changes to the Java extension are recorded separately in `parler-agent/CHANGELOG.md`.

## [0.1.98] — 2026-09-27

Released alongside Agent 0.1.249.

### Fixed

- **Third-party license texts ship with the package.** The extension now contains the `LICENSE` files
  of its packaged libraries and `parler-ui/THIRD_PARTY_LICENSES.txt` for the libraries built into
  `parler-ui.tw.js` (d3 modules, markdown-it and their dependencies).

## [0.1.97] — 2026-09-20

### Fixed

- **History loading now defaults to English:** displays `Loading history…` when
  `HistoryLoadingMessage` is empty; custom Mashup text still takes precedence.

- **Category labels no longer run into the axis caption or out of the chart.** Vertical bar and
  boxplot charts draw their category labels at an angle, but kept a fixed space below the axis, so a
  label such as `Running` or `Unavailable` was drawn over the caption or cut off at the bottom edge.
  The space below and to the left of the axis is now measured from the labels. The data area keeps
  its height; the chart becomes a little taller instead (at most 356 px).
- **Long labels are shortened by their real width.** When a label still does not fit, it ends in `…`,
  measured in pixels rather than characters, so Chinese and other wide text and a larger tick font
  size stay inside the chart. The full name remains in the tooltip, the keyboard read-out and the
  data view.
- **Many categories in a narrow card** no longer print their labels over each other: only every
  second, third, … label is drawn, the first and last always. Every bar, box and tick mark stays.
- **Histogram axis numbers** over a large base and a small range (for example 1,000,000 to
  1,000,002) no longer overlap or get cut off at the right edge: fewer ticks are shown until the
  numbers fit, without abbreviating them.
- **Printed heatmaps** keep each row at least as high as its row name, so printed row names no longer
  overlap.

### Compatibility

- Horizontal bar, line, scatter and pie charts, chart data, tooltips, colours and the data view are
  unchanged. No Agent change is needed: this widget works with Agent 0.1.245.

## [0.1.96] — 2026-09-19

### Added

- **Same category, same colour within a chart group.** When a group from Agent **0.1.243** declares
  a shared category dimension, every member chart colours a category by the group's shared slot, so
  for example `Running` and `Down` have the same colours in two per-device pies even when the slices
  are ordered differently. The legend, tooltip and keyboard read-out swatches, the data view, the
  expanded view and print use the same slots. A chart already on screen is not recoloured when a
  later member arrives.
- **Same-category highlight.** Hovering or focusing a category in one member, in the plot or the
  legend, dims the other categories in every chart of the group; keyboard navigation does the same
  and Escape clears it. The highlight is visual only: values, percentages and axes do not change, and
  it is neither saved nor printed.
- A "Shared colours" row in a member's data notes names the shared dimension. A member that could not
  join because the group reached its 24-category limit says so on its card.

### Unchanged

- Charts outside a group, groups without a declared dimension, and older saved groups keep their
  per-chart colours exactly as before. An invalid colour extension is ignored and the group still
  renders.

## [0.1.95] — 2026-09-18

### Added

- **Plot size follows the chart kind:** a pie is drawn as a square sized from the card width
  (up to 400 px) instead of a fixed 160 px disc, and on wide cards its legend moves beside the disc
  with label, value and percent. Vertical bar charts with few categories no longer stretch their
  bars across the card; the plot narrows and is centred. Line, scatter and horizontal bar charts are
  unchanged. Cards, the expanded view and print use the same sizing rule.
- **Histogram, boxplot and heatmap:** new chart kinds from Agent **0.1.240**. Histograms draw bins
  edge to edge with unequal widths kept; boxplots show quartiles, whiskers, listed outliers and
  reference lines; heatmaps colour cells from the active Theme, hatch missing cells, scroll inside
  the card when wide, and fit every column on the page in print. Each mark is reachable by pointer
  and keyboard, and "View data" opens the exact table the chart was built from.
- **Stacked and percent bars:** signed stacking and per-category shares in both orientations, with
  value, share and category total in the tooltip. Hiding a series restacks the rest without
  recomputing shares.
- **Side-by-side charts:** adjacent chart cards in one answer share a grid of at most two columns
  when each column is at least 360 px wide; tables keep their position and nothing is reordered.
- **Chart groups:** a declared group renders as one card with a title, ordered member slots,
  waiting and failure states, and a summary line. Groups survive duplicate and out-of-order frames,
  replay from history, and print in member order. An interrupted connection is shown as such; the
  client does not guess final member states.

### Changed

- Charts whose payload contradicts itself (for example a density that does not match its counts,
  or an outlier inside the whiskers) are rejected as a whole and the table stays visible.

## [0.1.94] — 2026-09-13

### Fixed

- Approval cards immediately show when a decision is being submitted and when it
  has been accepted. Decision controls stay disabled during submission and while
  waiting for the result, preventing repeat clicks during slow service execution.
- Approval submission failures are shown on the card and allow a manual retry
  without ending the active conversation. Late callbacks from an older approval
  do not change a newer card; confirmation wording also applies to cancel/reject.

## [0.1.93] — 2026-09-12

### Added

- **Chart cards and point lookup:** wrapping titles and legends sit outside the plot, with 12 px
  default tick and legend text, span-aware datetime labels, precise plain-text tooltips, and keyboard
  navigation through points, series, and reference lines. Data notes disclose available provenance,
  time windows, transformations, and limits.
- **Chart view controls:** toggle series with stable color slots, choose the full or visible-series
  Y domain, focus a pie slice, and view the direct parent table or paged chart values. Line/scatter
  charts support X-range zoom, selection, and reset without querying or changing the analysis result.
- **Expand within the widget:** a chart opens in an instance-owned layer, preserves its view state,
  contains keyboard focus, and restores focus and chat scroll position when closed.
- **Horizontal bars:** explicit horizontal requests from Agent **0.1.227** render signed and grouped
  bars with wrapped category labels, measured long-name fitting, scrolling, keyboard reveal, and
  complete printed output. Values, category order, and reference-line meaning stay unchanged.

### Fixed

- **Signed-bar geometry:** negative, mixed-sign, and zero values share a consistent domain with
  reference lines and a correctly placed zero baseline; positive-only charts avoid duplicate axes.
- **Invalid chart isolation:** live and historical chart inputs reject invalid series shapes and
  non-finite or non-number Y values as a whole chart, preserving other answer content and legitimate
  zero values. Rejection diagnostics tolerate malformed identity metadata.
- **Complete chart printing:** print cards are rebuilt from ordered answer artifacts with the full
  analysis range and all series, including charts currently expanded or not mounted in the answer.
  Output includes data notes and explains differences from the screen view; labels and chart
  typography remain readable under Theme overrides, including detached print construction.

## [0.1.92] — 2026-08-21

### Changed

- **User-message Theme treatment:** in `ThemeMode=mashup`, user prompts now use the Style Theme secondary surface and body text instead of filling the bubble with the often-saturated primary brand color. The primary color remains as a thin accent border, and the existing public user-message tokens still provide instance-scoped fill and text overrides.

## [0.1.91] — 2026-08-21

### Fixed

- **Responsive message and chart layout:** message bubbles default to `min(92%, 960px)` through the existing public width token; charts use the measured host width with a safe 480px logical minimum and no internal maximum, increase axis/grid density as they widen, align titles with message content, and size the y-axis gutter to formatted tick labels.
- **Space-efficient navigation:** the conditional `Latest` action shares the compact Transport status row instead of covering content or consuming a separate row.

## [0.1.90] — 2026-08-21

### Added

- **ThingWorx Style Theme integration:** `ThemeMode=mashup` resolves the Mashup Style Theme through the generated expression bridge; `ThemeMode=parler-dark` retains the standalone dark presentation. Public CSS tokens and documented light-DOM parts support instance-scoped application customization without exposing bridge internals.
- **Complete presentation roles:** message, Markdown, control, status, chart, and portable-print roles are independently customizable, including a 24-slot chart palette and print-only colors that do not recolor the live screen.
- **Configurable progress presentation:** `ProgressPresentation=detailed|compact` plus configurable compact/default text allows routine reasoning detail to be replaced while preserving rate-control and attention-required states.
- **Empty-state Media:** the `EmptyStateMedia` `IMAGELINK` property displays a ThingWorx Media Entity in the empty state, with a safe `Parler` fallback when unset or unusable.

### Fixed

- **Mashup font consistency:** native controls inherit the widget's resolved font family instead of retaining the browser-default control typeface.
- **Responsive chart readability:** charts use the measured host width with a safe 480px logical minimum and no internal maximum; wider charts increase axis/grid density rather than stretching a fixed five-tick layout.
- **Unobstructed navigation:** the conditional `Latest` action uses a compact, approximately 32px non-overlaying row instead of covering message or table content.
- **Partial version status:** a missing Agent or Widget handshake version is labeled as the available side instead of rendering a synthetic `?` version.

## [0.1.88] — 2026-06-28

### Fixed

- **Embedded mashup sizing:** the Composer widget runtime container now fills its parent mashup cell without enforcing a fixed minimum width, preventing overflow/clipping when Parler is embedded in constrained mashup layouts.

## [0.1.87] — 2026-06-26

### Fixed

- **Document PDF source links:** assistant markdown links to ThingWorx FileRepository PDFs now open through `FileRepositoryDownloader` with `directRender=true` and preserve `#page=N`, so source citations open inline at the cited PDF page instead of downloading or landing on page 1.

## [0.1.81] — 2026-06-11

### Fixed

- **Stop terminal row actions:** assistant rows ended by **Stop** / **`session.cancelled`** now retain a local terminal timestamp and can show the history cut-off action even when no server **`assistantMessageId`** exists. Feedback and turn-info remain gated on stable server ids.

## [0.1.80] — 2026-06-11

### Changed

- **Turn cancellation (Stop):** released together with Agent 0.1.187, which reports
  `GetConnectionInfo.capabilities.supportsCancellation: true` (`CONTRACTS/API_CONTRACT.md` 2.4.38,
  contract bundle 0.1.130). Import the Agent and widget extensions together to use Stop.

## [0.1.79] — 2026-06-08

### Changed

- **Approval card wording:** the rejection-comment placeholder now says "Why should this action not
  proceed?" instead of referring to a write, so it fits both service-invocation and property-write
  approvals.

## [0.1.78] — 2026-06-07

### Added

- **Connection version handshake:** after `ConnectAndBind`, the transport line shows
  `Transport: connected, <agent>:<widget>`, read from `ParlerGateway.GetConnectionInfo`
  (`parler.connection-info.v1`). Responses that arrive after a newer connection are ignored, and
  `session.superseded` clears a stale Agent version.
- **Widget version in the bundle:** the widget's own package version is embedded at build time from
  `input/widgets.json`.

## [0.1.77] — 2026-06-06

### Added

- **Prompt history:** `ArrowUp` at the start of the prompt box recalls earlier prompts from the
  current conversation; repeated `ArrowUp` / `ArrowDown` moves through them and restores the
  in-progress draft at the end.
- Clicking or moving the caret leaves prompt-history navigation, so a recalled multi-line prompt can
  be edited with the arrow keys as usual. Navigation continues only while the caret stays at the end
  of the recalled prompt, which also works in Firefox.

## [0.1.75] — 2026-06-04

Released together with Agent 0.1.151, which supplies table `presentationTitle` and
`executedToolName`; the widget keeps preferring the server's `presentationTitle` for table titles
(`CONTRACTS/TABLE_CONTRACT.md` §3.1). No widget code or wire contract change.

## [0.1.50] — 2026-05-13

### Fixed

- **Copy fallback:** when the browser denies or omits the async Clipboard API in a ThingWorx mashup context, fall back to a user-gesture `execCommand("copy")` path that writes both markdown/plain text and rendered HTML through the copy event.

## [0.1.49] — 2026-05-13

### Fixed

- **Print opens a print window again:** Print no longer falls back to the Blob view on every use or
  leaves stray blank tabs. (A `noopener` flag made `window.open` return `null` while the browser
  still opened a tab.)
- **Print fallback cleanup:** the fallback print view's Blob URL is released when its message
  disappears, for example after a thread reset or history reload.
- **Print window stays open:** the print window is no longer closed after printing, so the print
  preview or save-as-PDF dialog remains usable.

### Changed

- The assistant message action strip is fully visible by default on any device without hover, not
  only on coarse-pointer (touch) devices.

## [0.1.48] — 2026-05-13

### Added

- **Copy and Print on assistant messages:** Copy puts the answer on the clipboard as Markdown and
  HTML; Print opens the message in a standalone print document. See
  `docs/ui/assistant-response-actions.md`.
- **Blocked pop-ups:** if the browser blocks the print window, the message shows an inline notice
  with an "Open print view" link and "Dismiss"; the Mashup tab is never navigated away.
