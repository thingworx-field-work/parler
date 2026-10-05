# Parler Theme API

## Theme modes

| Value | Behavior |
|---|---|
| `mashup` | Uses explicit Parler token overrides, then the active Mashup Style Theme, then the fallback values in this specification. This is the default. |
| `parler-dark` | Uses explicit Parler token overrides, then the bundled dark preset. |

Invalid, empty, or differently cased values resolve to `mashup`.

The token-table defaults describe `mashup`. In `parler-dark`, screen color tokens use the bundled dark preset; its exact colors are not a compatibility guarantee. Non-color geometry, typography, and print defaults remain the values listed below. Explicit public-token overrides take priority in both modes.

For normal use, select or bind the Mashup Style Theme and leave
`ThemeMode=mashup`. A light Style Theme produces the light treatment and a
dark Style Theme produces the dark treatment through the same Parler render
path. `parler-dark` selects Parler's bundled dark preset instead of following
screen colors from the active Style Theme.

`ThemeMode` is a binding-target `STRING`, so a Mashup parameter, service
result, or other binding source may switch it at run time. Bind only the exact
values `mashup` and `parler-dark`. Switching modes changes presentation; it
does not clear the conversation or reconnect transport.

## CSS overrides

Scope overrides to a Parler instance or an application-owned class:

```css
.customer-chat parler-ui {
  --parler-color-accent: #005ea8;
  --parler-message-max-width: 720px;
}
```

Style a supported semantic part in light DOM:

```css
.customer-chat parler-ui [part="notice"][data-severity="warning"] {
  font-weight: 600;
}
```

Supported application selectors are the `parler-ui` host, the tokens, parts, and states in this specification. `::part()` is not supported.

Parler-specific per-widget Style Properties are not provided. For instance-specific customization, scope public tokens or supported `[part]`/state rules to the widget's `CustomClass` or another application-owned ancestor. `CustomClass` is available as both a binding input and output.

In `mashup` mode, Parler resolves supported Style Theme expressions when the widget first renders and whenever ThingWorx applies a Theme change. Those values populate internal source tokens only. A scoped public token remains the highest-priority value, and an unavailable Style Theme expression uses the fallback in the token table. Theme refresh does not use timer polling.

Information notices use the Style Theme information color when available and
fall back to the accent role. There is intentionally no public
`--parler-color-info` token; customize this state through
`[part="notice"][data-severity="info"]` or
`[part="task-state"][data-severity="info"]`. Success, warning, and danger
states use their corresponding public semantic tokens.

### Instance-scoped messages and accent

Set the widget's `CustomClass` property to `customer-chat`, then add this rule
to Mashup Custom CSS or Style Theme Custom CSS:

```css
.customer-chat parler-ui {
  --parler-color-accent: #005ea8;
  --parler-color-user-message: #003b5c;
  --parler-color-on-user-message: #ffffff;
  --parler-message-max-width: 720px;
}
```

The rule affects only the instance in the `customer-chat` scope. Bind
`CustomClass` to change the scope dynamically; multiple class names are
space-delimited. Use an application-owned class name and give other Parler
instances a different class or no class.

### Parts and semantic states

Parts are light-DOM attributes, so select them with `[part]`, not `::part()`.
This example strengthens warning notices and distinguishes rate-control
activity without relying on internal classes:

```css
.customer-chat parler-ui [part~="notice"][data-severity="warning"] {
  border-width: 2px;
  font-weight: 600;
}

.customer-chat parler-ui [part~="activity-status"][data-activity="rate-controlled"] {
  text-decoration: underline;
}
```

### Charts and Markdown

Override chart slots on the host. Series and legend marks share the same
ordered slot, and slot 25 wraps to slot 1.

```css
.customer-chat parler-ui {
  --parler-chart-series-1: #005ea8;
  --parler-chart-series-2: #d1495b;
}
```

Markdown code, links, and tables have dedicated tokens. The
`markdown-content` part supports root-level typography or color adjustments:

```css
.customer-chat parler-ui {
  --parler-markdown-link: #005ea8;
  --parler-markdown-link-visited: #5b2c83;
  --parler-markdown-code-bg: #eef5f9;
  --parler-markdown-code-text: #172a3a;
  --parler-markdown-table-header-bg: #dcecf5;
  --parler-markdown-table-border: #7593a6;
}

.customer-chat parler-ui [part~="markdown-content"] {
  font-variant-numeric: tabular-nums;
}
```

### Portable print colors

Print tokens are independent of the screen Style Theme and `parler-dark`.
Define them on the normal host scope; they are read when the print document is
created and do not recolor the live screen:

```css
.customer-chat parler-ui {
  --parler-print-canvas: #ffffff;
  --parler-print-text: #111111;
  --parler-print-muted: #4a4a4a;
  --parler-print-border: #767676;
  --parler-print-accent: #004f80;
  --parler-print-chart-series-1: #164f86;
}
```

### Empty-state Media

In Composer, select an SVG Media Entity for `EmptyStateMedia`. The property is
an `IMAGELINK`; Parler converts the selected reference for `<img>` rendering.
Then scope its presentation through the four Media tokens or its parts:

```css
.customer-chat parler-ui {
  --parler-empty-state-media-max-width: 240px;
  --parler-empty-state-media-max-height: 120px;
  --parler-empty-state-media-opacity: 0.9;
  --parler-empty-state-media-filter: grayscale(20%);
}

.customer-chat parler-ui [part~="empty-state-label"] {
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
```

An empty, missing, denied, or undecodable Media value keeps the built-in
`Parler` label. Media selection is content configuration, not a Theme value.

### Unsupported selectors and properties

Parler-specific per-widget Style Properties are unavailable. Use a
`CustomClass`-scoped public token or documented part/state rule instead.

The following are outside the supported styling API:

- `::part()` selectors;
- internal `--parler-theme-*` and `--parler-effective-*` properties;
- internal class names, element nesting, generated chart SVG nodes, and
  positional selectors such as `:nth-child()`;
- Shadow DOM selectors or assumptions that Parler has a `shadowRoot`; and
- undocumented parts, state values, or output attributes.

Light DOM may make incidental selectors appear to work, but they are
upgrade-sensitive and carry no compatibility guarantee.

## Progress presentation

| Widget property | Base type | Binding | Default | Behavior |
|---|---|---|---|---|
| `ProgressPresentation` | `STRING` | Input | `detailed` | `detailed` shows live activity and routine task detail; `compact` uses the configured compact labels and suppresses routine task detail |
| `ProgressLabel` | `STRING` | Localizable input | `Thinking...` | Compact label for ordinary working activity |
| `RateControlLabel` | `STRING` | Localizable input | `Waiting for capacity...` | Compact label for provider rate-control waiting |

Missing, empty, or invalid `ProgressPresentation` values use `detailed`.
Empty or whitespace-only label values use their defaults.

Both modes preserve received activity and task-state data; the properties only
change View projection. Compact mode never hides provider rate-control waiting,
approval requests, errors, user-action requirements, or task detail requiring
attention. Attention-required task detail includes a failed or
blocked-by-approval turn, an item that is failed, blocked-by-approval,
cancelled, or expired, and a positive failed or blocked summary count. Routine
active task detail is suppressed only in compact mode. Both modes retain a
visible polite status while the assistant is busy.

## Empty-state Media

| Widget property | Base type | Binding | Default | Applies to |
|---|---|---|---|---|
| `EmptyStateMedia` | `IMAGELINK` | Input | Empty | Optional ThingWorx Media image displayed when the conversation has no rows and history is not loading |

Media Entity and `IMAGELINK` identify different layers of the same property:

- Media Entity is the ThingWorx resource selected in Composer and containing the uploaded image or SVG.
- `IMAGELINK` is the property base type. Its value is a string image reference, commonly `/Thingworx/MediaEntities/<name>` for a selected Media Entity.
- ThingWorx normalizes that image-link value through `TW.convertImageLink(value)` to the browser-loadable image URL. Outside ThingWorx, a direct or relative image URL is used unchanged.

Composer may label the editor as Media Entity; the API base type remains `IMAGELINK`. The property does not use `MEDIA ENTITY`, binary `IMAGE`, or plain `STRING` as its base type. SVG is recommended for scalable branding, and any browser-decodable image referenced by the `IMAGELINK` may render. The resolved image is used only as an `<img>` source and is never inserted as SVG, XML, or HTML markup.

An empty or whitespace-only value displays `Parler`. For a configured value, `Parler` remains visible until the browser reports a successful image load, then the image replaces it. A missing, inaccessible, or undecodable image keeps the fallback without displaying a broken-image indicator. Changing the property retries the new value immediately; a response from an older value cannot replace the current selection. The image and fallback label are decorative and are not announced by assistive technology.

## Foundation and surfaces

| Token | Applies to | Default | Accepted value |
|---|---|---|---|
| `--parler-font-family` | Widget body and inherited content | Style Theme body font; fallback `"Open Sans", "Segoe UI", system-ui, sans-serif` | CSS font-family list |
| `--parler-font-size` | Widget base text size | Style Theme body size; fallback `14px` | Positive CSS length |
| `--parler-line-height` | Widget base line height | `1.5` | Positive unitless number or CSS length |
| `--parler-color-canvas` | Widget-facing page background | Style Theme primary background; fallback `#ffffff` | CSS color |
| `--parler-color-surface` | Main conversation panel | Style Theme secondary background; fallback `#f7f7f7` | CSS color |
| `--parler-color-surface-raised` | Popovers and floating controls | Style Theme primary background; fallback `#ffffff` | CSS color |
| `--parler-color-surface-subtle` | Quiet rows, code, and table fills | Style Theme secondary background; fallback `#f2f4f5` | CSS color |
| `--parler-color-text` | Primary text | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-color-text-muted` | Metadata and secondary text | Style Theme label text; fallback `#5f6b70` | CSS color |
| `--parler-color-border` | Dividers and ordinary borders | Style Theme border; fallback `#c2c7ce` | CSS color |
| `--parler-color-accent` | Links, primary actions, and selected controls | Style Theme primary color; fallback `#006f9b` | CSS color |
| `--parler-color-on-accent` | Text and icons on accent | Style Theme primary-button text; fallback `#ffffff` | CSS color |
| `--parler-color-focus` | Keyboard focus indicator | Style Theme focus color; fallback `#006f9b` | CSS color |
| `--parler-color-danger` | Errors and destructive actions | Style Theme danger color; fallback `#af3231` | CSS color |
| `--parler-color-warning` | Warnings and rate-control waiting | `#8a5a00` | CSS color |
| `--parler-color-success` | Connected and successful states | Style Theme success color; fallback `#13783a` | CSS color |
| `--parler-color-scrim` | Dialog overlay | `rgba(0, 0, 0, 0.32)` | CSS color with optional alpha |
| `--parler-color-placeholder` | Composer and approval-input placeholder text | Style Theme label text; fallback `#5f6b70` | CSS color |
| `--parler-color-scrollbar-thumb` | Thread scrollbar thumb | Style Theme border; fallback `#c2c7ce` | CSS color |
| `--parler-color-scrollbar-track` | Thread scrollbar track | Style Theme secondary background; fallback `#f7f7f7` | CSS color |
| `--parler-color-selection-bg` | Selected-text background | Style Theme primary color; fallback `#006f9b` | CSS color |
| `--parler-color-selection-text` | Selected-text foreground | Style Theme primary-button text; fallback `#ffffff` | CSS color |

## Messages, controls, geometry, and Markdown

| Token | Applies to | Default | Accepted value |
|---|---|---|---|
| `--parler-color-user-message` | User-message background | Style Theme secondary background; fallback `#f2f4f5` | CSS color |
| `--parler-color-on-user-message` | User-message foreground | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-color-assistant-message` | Assistant-message background | Style Theme secondary background; fallback `#f7f7f7` | CSS color |
| `--parler-color-on-assistant-message` | Assistant-message foreground | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-color-control-bg` | Inputs and secondary controls | Style Theme primary background; fallback `#ffffff` | CSS color |
| `--parler-color-control-text` | Input and secondary-control text | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-color-control-border` | Input and control borders | Style Theme border; fallback `#c2c7ce` | CSS color |
| `--parler-color-control-hover` | Secondary-control hover fill | Style Theme hover background; fallback `#eef4f7` | CSS color |
| `--parler-color-control-disabled` | Disabled-control fill | Style Theme disabled background; fallback `#e5e7e9` | CSS color |
| `--parler-content-max-width` | Inner conversation column | `1200px` | Positive CSS length or percentage |
| `--parler-message-max-width` | Message bubbles | `min(92%, 960px)` | Positive CSS length, percentage, or valid CSS comparison function |
| `--parler-activity-max-width` | Active-turn status and pulse | `100%` | Positive CSS length or percentage; clamped to its container |
| `--parler-empty-state-media-max-width` | Configured empty-state Media | `320px` | Positive CSS length or percentage; clamped to the empty-state width |
| `--parler-empty-state-media-max-height` | Configured empty-state Media | `160px` | Positive CSS length or percentage; clamped to the empty-state height |
| `--parler-empty-state-media-opacity` | Configured empty-state Media | `1` | Number from `0` to `1` |
| `--parler-empty-state-media-filter` | Configured empty-state Media | `none` | `none` or a valid CSS filter-function list |
| `--parler-radius-panel` | Conversation panels and popovers | Style Theme large radius; fallback `12px` | Non-negative CSS length |
| `--parler-radius-message` | Message bubbles | Style Theme medium radius; fallback `12px` | Non-negative CSS length |
| `--parler-radius-control` | Inputs and buttons | Style Theme medium radius; fallback `8px` | Non-negative CSS length |
| `--parler-shadow-panel` | Raised panels and floating controls | `0 4px 20px rgba(0, 0, 0, 0.18)` | CSS box-shadow value |
| `--parler-space-unit` | Compact internal spacing scale | `4px` | Non-negative CSS length |
| `--parler-markdown-heading` | Markdown `h1`–`h6` | Style Theme header text; fallback `#232b2d` | CSS color |
| `--parler-markdown-link` | Markdown links, including hover and focus | Style Theme primary color; fallback `#006f9b` | CSS color |
| `--parler-markdown-link-visited` | Visited Markdown links | `#6f42c1` | CSS color |
| `--parler-markdown-code-bg` | Inline and block code background | Style Theme secondary background; fallback `#f2f4f5` | CSS color |
| `--parler-markdown-code-text` | Inline and block code text | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-markdown-code-border` | Block-code border | Style Theme border; fallback `#d8dbde` | CSS color |
| `--parler-markdown-quote-bg` | Blockquote background | Style Theme secondary background; fallback `#f2f4f5` | CSS color |
| `--parler-markdown-quote-text` | Blockquote text | Style Theme body text; fallback `#232b2d` | CSS color |
| `--parler-markdown-quote-border` | Blockquote leading rule | Style Theme border; fallback `#c2c7ce` | CSS color |
| `--parler-markdown-table-header-bg` | Markdown table headers | Style Theme secondary background; fallback `#f2f4f5` | CSS color |
| `--parler-markdown-table-border` | Markdown table grid | Style Theme border; fallback `#c2c7ce` | CSS color |
| `--parler-markdown-hr` | Markdown horizontal rule | Style Theme divider; fallback `#d8dbde` | CSS color |
| `--parler-markdown-image-radius` | Markdown images | Style Theme medium radius; fallback `8px` | Non-negative CSS length |

In `mashup` mode, the default user-message fill and text use the Style Theme
secondary surface and body-text roles. The Style Theme primary color is used
only for the thin user-message border. Public user-message tokens override the
fill and text; use the supported `user-message` part to override its border.

Markdown paragraphs, hard breaks, strong text, emphasis, strikethrough, and ordered, unordered, or nested lists inherit the assistant-message foreground. Headings, normal and visited links, inline and block code, blockquotes, tables, horizontal rules, and images use the corresponding Markdown tokens. Links remain underlined, gain a visible keyboard-focus outline, and use the link color on hover. Fenced and indented block code scroll horizontally when required. Images never exceed the message width.

## Screen chart colors

Chart color tokens accept solid CSS colors only. Empty values, gradients, and invalid colors use the listed fallback.

| Token | Applies to | Default |
|---|---|---|
| `--parler-chart-series-1` | Categorical slot 1 | Style Theme chart series 1; fallback `#3363d8` |
| `--parler-chart-series-2` | Categorical slot 2 | Style Theme chart series 2; fallback `#139492` |
| `--parler-chart-series-3` | Categorical slot 3 | Style Theme chart series 3; fallback `#8a4ff7` |
| `--parler-chart-series-4` | Categorical slot 4 | Style Theme chart series 4; fallback `#eb6d00` |
| `--parler-chart-series-5` | Categorical slot 5 | Style Theme chart series 5; fallback `#2f97ff` |
| `--parler-chart-series-6` | Categorical slot 6 | Style Theme chart series 6; fallback `#f05b80` |
| `--parler-chart-series-7` | Categorical slot 7 | Style Theme chart series 7; fallback `#2a5b59` |
| `--parler-chart-series-8` | Categorical slot 8 | Style Theme chart series 8; fallback `#aa9103` |
| `--parler-chart-series-9` | Categorical slot 9 | Style Theme chart series 9; fallback `#2b387f` |
| `--parler-chart-series-10` | Categorical slot 10 | Style Theme chart series 10; fallback `#b8849a` |
| `--parler-chart-series-11` | Categorical slot 11 | Style Theme chart series 11; fallback `#8d909a` |
| `--parler-chart-series-12` | Categorical slot 12 | Style Theme chart series 12; fallback `#bb68c8` |
| `--parler-chart-series-13` | Categorical slot 13 | Style Theme chart series 13; fallback `#c33e70` |
| `--parler-chart-series-14` | Categorical slot 14 | Style Theme chart series 14; fallback `#904b6f` |
| `--parler-chart-series-15` | Categorical slot 15 | Style Theme chart series 15; fallback `#9d27b0` |
| `--parler-chart-series-16` | Categorical slot 16 | Style Theme chart series 16; fallback `#00709e` |
| `--parler-chart-series-17` | Categorical slot 17 | Style Theme chart series 17; fallback `#381394` |
| `--parler-chart-series-18` | Categorical slot 18 | Style Theme chart series 18; fallback `#8f3809` |
| `--parler-chart-series-19` | Categorical slot 19 | Style Theme chart series 19; fallback `#8e6514` |
| `--parler-chart-series-20` | Categorical slot 20 | Style Theme chart series 20; fallback `#5d5f69` |
| `--parler-chart-series-21` | Categorical slot 21 | Style Theme chart series 21; fallback `#86335e` |
| `--parler-chart-series-22` | Categorical slot 22 | Style Theme chart series 22; fallback `#490e3e` |
| `--parler-chart-series-23` | Categorical slot 23 | Style Theme chart series 23; fallback `#56226e` |
| `--parler-chart-series-24` | Categorical slot 24 | Style Theme chart series 24; fallback `#1d496e` |
| `--parler-chart-title-text` | Chart title | Style Theme header text; fallback `#232b2d` |
| `--parler-chart-legend-text` | Legend labels | Style Theme label text; fallback `#5f6b70` |
| `--parler-chart-tick-text` | Axis tick labels | Style Theme chart tick text; fallback `#5f6b70` |
| `--parler-chart-axis-label-text` | Footer axis label | Style Theme label text; fallback `#5f6b70` |
| `--parler-chart-axis` | Axis domains, ticks, and the bar zero baseline drawn when the Y domain extends below zero | Style Theme chart ruler; fallback `#6b7377` |
| `--parler-chart-grid` | Horizontal grid | Style Theme divider; fallback `#d8dbde` |
| `--parler-chart-point-outline` | Point, bar, and pie-slice outline | Style Theme chart outline; fallback `#ffffff` |
| `--parler-chart-reference-danger` | Limit reference and label | Style Theme danger color; fallback `#af3231` |
| `--parler-chart-reference-warning` | Control/warning reference and label | `#8a5a00` |
| `--parler-chart-reference-target` | Target reference and label | Style Theme chart reference line; fallback `#6e717c` |

Series, bars, and filtered positive pie slices use render order modulo 24. Item 25 repeats slot 1. Plot marks and legend marks always use the same slot. Changing the modulus is a breaking color-assignment change.

## Screen chart presentation

| Token | Applies to | Default | Accepted value |
|---|---|---|---|
| `--parler-chart-font-family` | Card title, card legend, and all SVG text | Style Theme body font; fallback `"Open Sans", "Segoe UI", system-ui, sans-serif` | CSS font-family list |
| `--parler-chart-line-width` | Data lines and line legend | `2px` | Positive CSS length, safely clamped |
| `--parler-chart-line-point-radius` | Line-chart points | `3px` | Non-negative CSS length, safely clamped |
| `--parler-chart-scatter-point-radius` | Scatter points | `4px` | Positive CSS length, safely clamped |
| `--parler-chart-outline-width` | Point, bar, and slice outlines | `1px` | Non-negative CSS length, safely clamped |
| `--parler-chart-bar-radius` | Bar corners | `3px` | Non-negative CSS length, safely clamped |
| `--parler-chart-axis-line-width` | Axis domains, ticks, and the bar zero baseline drawn when the Y domain extends below zero | Style Theme border thickness; fallback `1px` | Positive CSS length, safely clamped |
| `--parler-chart-grid-line-width` | Horizontal grid | `1px` | Positive CSS length, safely clamped |
| `--parler-chart-grid-opacity` | Horizontal grid | `0.7` | Number from `0` to `1` |
| `--parler-chart-reference-line-width` | Limit, control, and warning references | `2px` | Positive CSS length, safely clamped |
| `--parler-chart-target-line-width` | Target reference | `1.5px` | Positive CSS length, safely clamped |
| `--parler-chart-reference-opacity` | All reference lines | `0.95` | Number from `0` to `1` |
| `--parler-chart-reference-limit-dash` | Limit references | `none` | `none` or SVG dash-array number list |
| `--parler-chart-reference-control-dash` | Control-limit references | `6 4` | `none` or SVG dash-array number list |
| `--parler-chart-reference-target-dash` | Target reference | `4 3` | `none` or SVG dash-array number list |
| `--parler-chart-reference-warning-dash` | Warning reference | `6 3` | `none` or SVG dash-array number list |
| `--parler-chart-title-font-size` | Card title | `14px` | Positive CSS length, safely clamped |
| `--parler-chart-title-font-weight` | Card title | `600` | CSS font-weight |
| `--parler-chart-legend-font-size` | Card legend labels | `12px` | Positive CSS length, safely clamped |
| `--parler-chart-tick-font-size` | Axis tick labels | `12px` | Positive CSS length, safely clamped |
| `--parler-chart-axis-label-font-size` | Footer axis label | `11px` | Positive CSS length, safely clamped |
| `--parler-chart-reference-label-font-size` | Reference labels | `10px` | Positive CSS length, safely clamped |
| `--parler-chart-legend-swatch-size` | Point and box legend marks | `10px` | Positive CSS length, safely clamped |
| `--parler-chart-legend-swatch-radius` | Box legend corners | `2px` | Non-negative CSS length, safely clamped |
| `--parler-chart-legend-line-length` | Line legend marks | `14px` | Positive CSS length, safely clamped |
| `--parler-chart-tick-length` | Axis tick marks | `6px` | Non-negative CSS length, safely clamped |
| `--parler-chart-tick-padding` | Tick-to-label gap | `3px` | Non-negative CSS length, safely clamped |

CSS lengths are resolved to pixels against the live chart host and clamped before D3 draws. The safe ranges are: data/reference line widths `0.25`–`12px`; axis/grid widths `0.25`–`8px`; line-point radius `0`–`16px`; scatter radius `0.5`–`20px`; outline width `0`–`8px`; bar radius `0`–`24px`; title size `8`–`16px`; legend and tick sizes `6`–`18px`; reference-label size `6`–`12px`; axis-label size `6`–`14px`; legend swatch size `4`–`12px`; legend swatch radius `0`–`6px`; legend line length `6`–`48px`; and tick length/padding `0`–`16px`. Dash components are clamped to `0`–`100`, with at least one positive component and at most 16 components. Numeric font weights are clamped to `1`–`1000`. Opacities are clamped to `0`–`1`.

Line, scatter, vertical bar, histogram, and boxplot charts draw a horizontal grid; heatmaps draw no grid and hatch their missing cells in the grid colour; horizontal bar charts draw a vertical grid, a vertical zero baseline under the same below-zero rule, and vertical reference lines. Pie charts do not draw axes or grid lines. Reference roles retain distinct dash patterns and visible labels. Existing charts re-resolve their complete palette and presentation when chart data or size changes, `ThemeMode` changes, the ThingWorx Theme bridge updates its host values, or application code changes the Parler host's `class` or `style` attributes. Unchanged resolved values do not cause a second Theme-only redraw.

## Print palette

Print defaults are fixed light values and do not automatically inherit screen Theme colors. Explicit print-token overrides are resolved before the print document is created.

| Token | Applies to | Default | Accepted value |
|---|---|---|---|
| `--parler-print-canvas` | Printed page background | `#ffffff` | CSS color |
| `--parler-print-text` | Printed body and heading text | `#111111` | CSS color |
| `--parler-print-muted` | Printed metadata and labels | `#555555` | CSS color |
| `--parler-print-border` | Printed borders, axes, and grid | `#888888` | CSS color |
| `--parler-print-surface-subtle` | Printed code and table-header fill | `#f0f0f0` | CSS color |
| `--parler-print-accent` | Printed links and emphasis | `#005ea8` | CSS color |
| `--parler-print-chart-series-1` | Printed categorical slot 1 | `#1b4f72` | Solid CSS color |
| `--parler-print-chart-series-2` | Printed categorical slot 2 | `#117864` | Solid CSS color |
| `--parler-print-chart-series-3` | Printed categorical slot 3 | `#7d3c98` | Solid CSS color |
| `--parler-print-chart-series-4` | Printed categorical slot 4 | `#a04000` | Solid CSS color |
| `--parler-print-chart-series-5` | Printed categorical slot 5 | `#1f618d` | Solid CSS color |
| `--parler-print-chart-series-6` | Printed categorical slot 6 | `#a93226` | Solid CSS color |
| `--parler-print-chart-series-7` | Printed categorical slot 7 | `#196f3d` | Solid CSS color |
| `--parler-print-chart-series-8` | Printed categorical slot 8 | `#7d6608` | Solid CSS color |
| `--parler-print-chart-series-9` | Printed categorical slot 9 | `#34495e` | Solid CSS color |
| `--parler-print-chart-series-10` | Printed categorical slot 10 | `#884ea0` | Solid CSS color |
| `--parler-print-chart-series-11` | Printed categorical slot 11 | `#5b2c6f` | Solid CSS color |
| `--parler-print-chart-series-12` | Printed categorical slot 12 | `#21618c` | Solid CSS color |
| `--parler-print-chart-series-13` | Printed categorical slot 13 | `#7b241c` | Solid CSS color |
| `--parler-print-chart-series-14` | Printed categorical slot 14 | `#0e6251` | Solid CSS color |
| `--parler-print-chart-series-15` | Printed categorical slot 15 | `#6e2c00` | Solid CSS color |
| `--parler-print-chart-series-16` | Printed categorical slot 16 | `#4a235a` | Solid CSS color |
| `--parler-print-chart-series-17` | Printed categorical slot 17 | `#1b2631` | Solid CSS color |
| `--parler-print-chart-series-18` | Printed categorical slot 18 | `#78281f` | Solid CSS color |
| `--parler-print-chart-series-19` | Printed categorical slot 19 | `#145a32` | Solid CSS color |
| `--parler-print-chart-series-20` | Printed categorical slot 20 | `#512e5f` | Solid CSS color |
| `--parler-print-chart-series-21` | Printed categorical slot 21 | `#154360` | Solid CSS color |
| `--parler-print-chart-series-22` | Printed categorical slot 22 | `#641e16` | Solid CSS color |
| `--parler-print-chart-series-23` | Printed categorical slot 23 | `#0b5345` | Solid CSS color |
| `--parler-print-chart-series-24` | Printed categorical slot 24 | `#4d5656` | Solid CSS color |
| `--parler-print-chart-outline` | Printed point, bar, and slice outline | `#ffffff` | CSS color |
| `--parler-print-chart-reference-danger` | Printed limit reference | `#a61b1b` | CSS color |
| `--parler-print-chart-reference-warning` | Printed control/warning reference | `#8a5700` | CSS color |
| `--parler-print-chart-reference-target` | Printed target reference | `#126b3a` | CSS color |

Printed typography uses the public body font, size, and line-height tokens. Printed image radius uses the Markdown image-radius token. Print page margins, paper-width clamping, page-break avoidance, and responsive image/SVG containment are fixed.

Print colors are validated as CSS colors. An empty, malformed, gradient, URL, unresolved-variable, or CSS-wide print color falls through to the documented portable default; no screen Theme color is used as a palette fallback. The print operation clones the answer, expands its tables, and rewrites every marked chart `fill` or `stroke` from the printed role and zero-based series slot. Title text uses print text; legend, tick, axis-label, and metadata text use print muted; axes, the bar zero baseline, and grid use print border; outlines and reference roles use their corresponding print tokens. The card title and legend are ordinary DOM in the clone: print CSS colors the title with print text and the legend with print muted, keeps the validated screen chart font family, title size and weight, and legend size, wraps long titles and legend labels within the page width, and legend marks carry the same roles and slots as plot marks, so they are rewritten with them. The actions bar, the paged data view, and the on-screen view note are not printed. Every printed chart card is rebuilt from the answer's chart artifact with the full analysis range, every series, the full Y axis, and no slice focus, whatever the screen shows, and a chart that is not currently mounted in the answer still prints in its artifact position. Data notes print expanded and the meta line prints. When the screen view differs from the full analysis, a print note under the plot states the on-screen hidden series, Y-axis policy, slice focus, zoomed range, and selection; the range control is not printed. Printing while a chart is expanded prints that chart in its answer position like any other and never prints the placeholder note. A horizontal bar chart prints at its full height without scrolling. Validated screen chart widths, radii, opacity, font attributes, and dash patterns are preserved in the clone. The live screen SVG is unchanged.

Clipboard plain text and HTML do not include Parler colors. Screen captures and raw in-page SVG use the active screen palette.

## Semantic parts

Parts are selected with `[part="name"]`.

| Part | Surface | Supported styling | Default source |
|---|---|---|---|
| `shell` | Inner conversation column | Color and font | Foundation tokens |
| `header` | Optional title region | Color, background, border, and font | Foundation tokens |
| `thread` | Scroll viewport | Background, focus, and scrollbar colors | Surface, focus, and scrollbar tokens |
| `thread-panel` | Conversation panel | Background, border, radius, and shadow | Surface, border, panel-radius, and panel-shadow tokens |
| `empty-state` | Empty conversation state root | Background, color, and font | Surface and text tokens |
| `empty-state-media` | Configured ThingWorx Media image | Maximum size, opacity, and filter | Empty-state Media tokens |
| `empty-state-label` | Built-in `Parler` fallback | Color, font, opacity, letter spacing, text transform, and text shadow | Muted text and body typography tokens |
| `message-meta` | Actor, time, and metadata labels | Color and font | Muted text and body typography tokens |
| `user-message` | User-message bubble | Background, color, border, radius, and selection | User-message, accent, message-radius, and selection tokens |
| `user-message-actions` | User copy and status controls | Color, background, and focus | Control, notice, and focus tokens |
| `host-context-disclosure` | Host-context disclosure control | Background, color, border, radius, and focus | Control and focus tokens |
| `host-context-content` | Expanded host-context content | Background, color, border, radius, and font | Surface, text, border, and typography tokens |
| `assistant-message` | Assistant-message bubble | Background, color, border, radius, and selection | Assistant-message, border, message-radius, and selection tokens |
| `markdown-content` | Markdown output root | Color, typography, links, code, quotes, tables, rules, and images | Markdown and assistant-message tokens |
| `message-actions` | Assistant action bar | Color and background | Control tokens |
| `message-action` | Individual copy, print, feedback, info, cutoff, or dismiss control | Color, background, border, radius, focus, selection, and disabled states | Control, accent, focus, and radius tokens |
| `notice` | Operational information, success, warning, or error surface | Background, color, border, radius, and font | Surface, border, radius, and severity tokens |
| `activity-status` | Active-turn text | Color, font, and maximum width | Muted/warning text, typography, and activity-width tokens |
| `activity-progress` | Active-turn pulse or track | Color, background, and maximum width | Accent/warning and activity-width tokens |
| `task-state` | Task progress panel | Background, color, border, radius, and severity | Surface, border, radius, and severity tokens |
| `insight-hint` | Further-insight metadata hint | Color and font | Muted text and typography tokens |
| `data-table-disclosure` | Structured-table disclosure control | Background, color, border, radius, and focus | Control, radius, and focus tokens |
| `data-table` | Structured table root | Background, color, border, and font | Surface, text, border, and typography tokens |
| `data-table-header` | Structured table header cells | Background, color, border, and font | Subtle surface, text, border, and typography tokens |
| `data-table-cell` | Structured table body cells | Background, color, and border | Surface, text, and border tokens |
| `data-table-footer` | Structured table summary and export row | Color, font, and link color | Muted text, typography, and accent tokens |
| `chart` | Chart card root | Background and all chart presentation | Surface and chart tokens |
| `chart-title` | Chart card title | Color, font, and spacing | Chart title tokens |
| `chart-plot` | Chart plot host containing the SVG | Background and spacing | Surface tokens |
| `chart-legend` | Chart card legend list | Color, font, and spacing | Chart legend tokens |
| `chart-tooltip` | Chart point-query tooltip | Background, color, border, radius, shadow, and font | Raised surface, text, border, control radius, shadow, and chart legend tokens |
| `chart-meta` | Chart card window, values, and limits line | Color, font, and spacing | Chart legend tokens |
| `chart-legend-toggle` | Legend entry control (series show/hide or slice focus) | Color, font, radius, and focus | Chart legend, control radius, and focus tokens |
| `chart-view-note` | Chart card view summary (hidden series, Y policy, slice focus) | Color, font, and spacing | Chart legend tokens |
| `chart-actions` | Chart card actions bar | Background, color, border, radius, focus, and pressed states | Control, accent, on-accent, radius, and focus tokens |
| `chart-notes` | Chart card data-notes disclosure | Color, font, and spacing | Text, chart legend, and focus tokens |
| `chart-data` | Chart card paged data view | Background, color, border, radius, and font | Subtle surface, text, border, control radius, and chart legend tokens |
| `chart-print-note` | Printed chart card note stating the on-screen view differences | Color, font, and spacing | Chart legend tokens |
| `chart-range` | Chart card X-range control (view start and end sliders) | Color, font, spacing, accent, and focus | Chart legend, accent, and focus tokens |
| `chart-selection` | Translucent band marking the selected X range inside the plot | Fill and stroke (grid role) | Chart grid token |
| `chart-expand` | Modal layer over the thread area holding one expanded chart card | Background, color, border, radius, shadow, and spacing | Surface, text, border, panel radius, and shadow tokens |
| `chart-expand-close` | Close control in the expanded chart layer | Background, color, border, radius, and focus | Control, radius, and focus tokens |
| `chart-expand-placeholder` | Note left in the answer where an expanded chart card normally sits | Color, border, radius, font, and focus | Muted text, border, control radius, chart legend, and focus tokens |
| `chart-grid` | Responsive grid holding two or more adjacent chart cards in one answer (two columns only when each keeps 360 px after the 16 px gap, else one) | Gap, margin, and column rule; cards inside keep their own parts |
| `chart-group` | Declared chart group card: a titled section of ordered member slots | Border, radius, padding, and margin | Border and panel radius tokens |
| `chart-group-title` | Group title above the member slots | Color, font | Chart title tokens |
| `chart-group-slot` | One member slot holding its chart card or its state text | Placeholder border and spacing | Border and control radius tokens |
| `chart-group-summary` | Terminal summary line of a group ("2 / 3 ready · 1 no data") and the transport-interrupted note | Color, font | Muted text and chart legend tokens |
| `connection-status` | Transport status | Color and font | Muted, success, warning, danger, and typography tokens |
| `approval-panel` | Approval request, including the decision feedback line | Background, color, border, radius, and shadow | Surface, text, border, radius, and shadow tokens |
| `approval-input` | Rejection comment input | Background, color, placeholder, border, radius, and focus | Control, placeholder, radius, and focus tokens |
| `approval-primary-action` | Approve control | Background, color, border, radius, focus, and disabled states | Accent, on-accent, radius, focus, and disabled tokens |
| `approval-secondary-action` | Cancel control | Background, color, border, radius, focus, and disabled states | Control, radius, focus, and disabled tokens |
| `approval-danger-action` | Reject control | Background, color, border, radius, focus, and disabled states | Danger, radius, focus, and disabled tokens |
| `composer` | Prompt footer | Background and border | Canvas/surface and border tokens |
| `composer-input` | Prompt input | Background, color, placeholder, border, radius, focus, and disabled states | Control, placeholder, radius, focus, and disabled tokens |
| `primary-action` | Send control | Background, color, border, radius, focus, and disabled states | Accent, on-accent, radius, focus, and disabled tokens |
| `stop-action` | Stop control | Background, color, border, radius, focus, and disabled states | Danger, radius, focus, and disabled tokens |
| `floating-action` | Jump-to-latest control | Background, color, border, radius, shadow, and focus | Raised surface, control, radius, shadow, and focus tokens |
| `overlay-scrim` | Dialog overlay | Background | Scrim token |
| `popover` | Turn-details dialog | Background, color, border, radius, and shadow | Raised surface, text, border, radius, and shadow tokens |
| `popover-close` | Turn-details close control | Background, color, border, radius, and focus | Control, radius, and focus tokens |

## Semantic states

Native `:hover`, `:active`, `:focus-visible`, `:disabled`, `[disabled]`, `aria-pressed`, and `aria-expanded` are supported where applicable.

| Owner | Attribute | Values |
|---|---|---|
| Host | `theme-mode` | `mashup`, `parler-dark` |
| Host | `connection-status` | `disconnected`, `connecting`, `connected` |
| `activity-status`, `activity-progress` | `data-activity` | `working`, `rate-controlled` |
| `notice`, `task-state` | `data-severity` | `info`, `success`, `warning`, `danger` |
| `message-action` | `data-action` | `copy`, `print`, `feedback-up`, `feedback-down`, `info`, `cutoff`, `dismiss` |
| `chart-legend-toggle` | `aria-pressed` | `true` when the series is shown or the slice is focused; `false` otherwise |
| chart legend item | `data-hidden` | present when the series is hidden |
| chart actions control | `aria-pressed` | `true` while the Y axis is fitted to visible series |
| chart expand control | `aria-pressed` | `true` on the card inside the expand layer |
| `chart-expand` | `aria-modal` | always `true`; the thread, header, and composer carry `inert` and `aria-hidden` while it is open |

Notice severity is assigned as follows:

| Surface | Severity |
|---|---|
| History loading | `info` |
| Copy succeeded | `success` |
| Copy failed | `danger` |
| Gateway, chat, or session error | `danger` |
| Print popup blocked | `warning` |

Task-state severity is assigned as follows:

| Status | Severity |
|---|---|
| Turn `failed`; item `failed`, `cancelled`, or `expired`; positive failed summary | `danger` |
| Turn or item `blocked-by-approval`; positive blocked summary | `warning` unless danger applies |
| Turn `completed`; item `satisfied` | `success` when no danger or warning applies |
| Turn `idle` or `executing`; item `pending`, `in-progress`, or `not-applicable` | `info` otherwise |

Task-state severity precedence is `danger`, then `warning`, then `success`, then `info`.

Rate-control waiting is the separate `data-activity="rate-controlled"` state and uses the warning role.

The approval decision controls (`approval-primary-action`, `approval-secondary-action`,
`approval-danger-action`) are `:disabled` together from the moment a decision is submitted until
the request is resolved or the submission fails, so one approval request accepts one decision.
`approval-danger-action` is additionally `:disabled` while its comment is empty.

## Fixed presentation behavior

The Mashup container owns widget width and height. Parler keeps overflow, minimum usable size, flex/grid layout, scrolling, responsive containment, and accessibility-only positioning fixed.

Empty-state Media preserves its intrinsic aspect ratio, uses contained object fitting, cannot exceed the empty-state surface, and does not alter when that surface is shown. The Media Entity is selected by `EmptyStateMedia`, not by Theme.

A chart is a card: the title, the plot, and the legend are stacked in that order. Two or more charts that follow each other in an answer, with no table between them, share a responsive grid decided by the grid's own content width: two columns only when each column keeps at least 360 CSS pixels after the 16 pixel gap, never more than two, otherwise one column; cards in a grid share nothing but layout, each measures its own column width for its plot, and they print one per row at the answer width. A chart group declared by the agent renders as a titled card whose member slots keep their declared order: a ready member shows its chart card (its view state is kept when the card moves into the slot), a pending member shows "Waiting for <name>", a ready member whose chart has not arrived shows "Loading <name>", and a no-data, failed, or cancelled member shows its state text with the code; the slots reuse the grid rule unless the layout hint is stack, the summary line states the counts, and when the turn ends before the group is final the summary says the transport was interrupted without inventing member states. Charts the manifest does not reference stay outside the group. Print outputs the title, the member charts in order, and the state text of unready members. When the group declares a shared category dimension, every member marked colour-shared takes each category's colour from the group's key list (the key's position is its Theme series slot, so the print palette follows the same slots), and this one resolution feeds the marks, the legend, the tooltip and keyboard read-out swatches, and the data view; members outside the mapping, charts outside groups, and every kind without categories keep the per-chart slots exactly as before, and no new token or user colour choice is introduced. Hovering or focusing a category in one shared member (a legend item, a mark under the pointer, or the keyboard query) dims the other categories in every shared member of the group at 0.35 opacity until the pointer leaves, focus moves, or Escape is pressed; this highlight is transient and never persisted or printed. The title and legend are ordinary text outside the SVG, wrap on narrow hosts, and are never scaled with the plot; they take their color and type from the chart title and legend tokens through ordinary stylesheet rules, with the default title and legend sizes clamped to the same documented ranges as the SVG text, so `[part="chart-title"]` and `[part="chart-legend"]` rules override them like any other part. The plot's size follows a fixed policy of the chart kind, its data counts, and the card's measured content width, never of the model, tool parameters, or wire fields: line and scatter plots fill the card width at `240px` high; a pie plot is a square whose side is the card width divided by the golden ratio, clamped to `200`–`400px` (an expanded chart's cap is the layer's usable height; print keeps `400`), with an `8px` inset radius and the disc centred, and when at least `240px` remain beside the disc after a `24px` gap the legend moves beside it, at most `320px` wide, listing each slice's label, value, and percentage from the same numbers as the tooltip; a vertical bar plot is `240px` high plus the measured room below it for its rotated category labels (see below) and no wider than its margins plus the category count times a step capped at `96px` plus `32px` per extra series slot up to `256px`, clamped to `240px` and the card width. A plot narrower than the card is centred, never stretched. The plot's logical view-box height is fixed at `240` for line, scatter, and histogram charts; inside it the data area is `240 − 20 − 44 = 176` high. Vertical bar and boxplot charts keep that same `176` data area and add, below it, the room their category labels need: those labels are end-anchored at their tick and rotated by −35°, so the bottom margin is measured from the text actually drawn (tick, the label's run `width × sin 35°` plus its glyph height `× cos 35°`, a `6px` gap, the axis caption line, an `8px` inset), never below the usual `44px` and never above `160px`, which bounds the view-box height to `240`–`356`; the axis caption is drawn below the labels. The same labels run `width × cos 35°` to the left of their tick, so the left margin grows by what is missing, at most to 40% of the card width. Whatever still does not fit after both caps is ellipsized by measured pixel width, not by character count, so CJK and other wide glyphs and a larger `--parler-chart-tick-font-size` stay inside the SVG; the text that is measured is the text that is drawn, and the tooltip, keyboard query, and data view keep the full name. When a category's band is narrower than `1.75` times the tick font size, only every k-th label is drawn (the first and the last always), while every bar, box, tick mark, and query point stays. No Theme token is added for any of this. A histogram draws one rectangle per bin between its real edges on a linear numeric axis, so unequal bins are unequally wide, with the height taken from the counts or the densities as the block's mode says; it has no legend, series toggles, or range control, its query reads the bin interval (the last bin closed), the count and the density, and its data notes list the binning method with the valid, excluded, and out-of-range counts. A boxplot draws one band per group in source order under the vertical-bar band cap with a single slot, a box from the first to the third quartile with a median line, whiskers with caps to the whisker values, hollow markers at the listed outliers, and reference lines as on a bar chart; it has no legend, series toggles, or range control, its query reads a group's n, five numbers, whiskers and outlier summary, or one listed outlier, and its data notes list the summary method, the group count, the summarised, excluded and outlier totals, stating how many outliers are not shown. A heatmap draws one rectangle per cell with the row labels on the left and the column labels below; cells are at least 20 × 20 logical pixels on screen so a wide matrix scrolls inside the card (horizontally, and vertically above 720 pixels) and inside the expand layer (horizontally), text is never scaled down, and only print lifts the floor so that every column fits the page width with thinned column labels at the Theme tick size; in every context a row is at least as high as one row-label line at the current tick font size, so printed row names never overprint each other (the printed rows used to shrink to `8px` under a `12px` label). Cell colour is derived from the Theme series slots and needs no token: a one-sided matrix ramps the first series colour from faint to full, a matrix crossing zero ramps the second series colour below zero and the first above it, and a constant matrix takes the midpoint; missing cells are hatched and read as "No data", cells at least 40 × 24 show their value, and a colour bar legend states the value label, the extremes (and zero when crossed) and a "No data" sample; the print clone recomputes every cell and colour-bar stop from the print palette. A stacked bar chart draws one stack per category across the whole band, positive segments accumulating upward from zero and negative segments downward (left and right when horizontal), or under percent stacking each series' share of the category total on a fixed 0 to 100 axis with no bar for a zero-total category; hiding a series closes its gap without changing the other values, shares or the default axis, and each segment's query reads the series, category, value, share and category total. A bar chart whose block carries `orientation: "horizontal"` puts categories down the left axis in data order (first at the top) and values along the bottom axis with the same signed domain; category labels are fitted by the rendered font's measured width on screen and in the printed card alike (the print builder measures through the rendering document even though its card is built detached; only an environment that cannot render text falls back to a generous glyph-class estimate), wrapping at spaces, hyphens, underscores, and dots into at most two lines and ellipsized beyond that, inside a category gutter clamped to `48`–`160px` and at most 35% of the plot width, so wide or CJK glyphs are never cropped, while the tooltip, keyboard query, and data notes keep the full name; its height grows with the category count, the series count, and the label line count so every sub-bar stays at least about six logical pixels thick and adjacent labels never overlap, the plot area scrolls inside the card at `720px`, the expanded chart layer removes that limit, and a keyboard query scrolls every scrolling ancestor (plot area, card, expanded layer, thread) so the mark and the room for its tooltip are visible, with the tooltip placed from its rendered size inside the visible region, above the mark when that fits and below it otherwise. Otherwise its logical width equals the measured chart host so tick text renders at its configured CSS-pixel size, with a `240` minimum below which the SVG scales down. The parent layout and `--parler-message-max-width` control the physical maximum. Axis and grid density increases from five through ten ticks as width grows. A y-axis gutter is estimated from its formatted tick labels and clamped to `32`–`64px`, using the tick count of the card width before the plot width is decided and never re-estimated afterwards; on a vertical bar or boxplot the left margin may exceed that range only for the rotated category labels described above, from label text and band step alone, never from a drawn axis; a histogram's numeric X ticks are reduced, keeping d3's own format for the smaller count so neighbouring values stay distinct, until the labels fit as they are finally drawn: real tick positions, measured label widths, and an end label anchored inward when centred text would leave the SVG all enter one check that requires every label inside the SVG and `8px` between neighbours, and when not even one tick fits the axis shows none rather than a clipped one; pie charts have no gutter beyond the `8px` radius inset. Legend marks repeat the plot mark shape and palette slot of their series. The plot area is one keyboard stop: arrow keys move between adjacent points, series, and reference lines, Home and End jump within a series, and Escape closes the tooltip while keeping focus; pointer hover queries the nearest mark. The tooltip shows the series, the full local time with the display time zone (or the category, elapsed, or normalized position), the raw value with the axis caption, and the source window zone when the series carries one. The focused mark shows a ring in the focus color. Datetime axis ticks show time within one day, date and time across days, seconds or milliseconds when needed to tell instants apart, and the UTC offset when a local time repeats; the tick count is fitted to the plot width so formatted labels neither overlap nor run past the gutters; when no generated tick can be shown without clipping, the axis shows a single contained tick nearest the plot centre, or none at all rather than a clipped one. Tooltips keep the raw value text and the full available time precision. Tooltips and focus rings are not printed. Legend entries are controls: for line, bar, and scatter charts they show or hide a series while keeping its palette slot, and the Y axis keeps every series and reference line until the actions bar's fit-to-visible control is pressed; when every series is hidden the axes stay and a view note says so, with a control to show all series. For pie charts a legend entry focuses its slice and dims the others; totals and percentages keep the original analysis denominator. A view note states hidden series, the Y policy, and slice focus whenever the view differs from the full analysis. The data-notes disclosure lists the source, window, columns, transform, input rows, emitted points, top-N or sampling limits, zero-filled combinations, and missing series or periods as the chart provided them, stating `Not provided by source` otherwise, with identifiers under a nested diagnostics disclosure. Windows are shown in the display time zone and labelled with it, with both endpoint offsets when the range crosses an offset change; a source window zone is stated separately. A meta line above the plot repeats the window, the value caption, and applied limits. Line and scatter charts offer a range control under the plot with two sliders, view start and view end, that zoom the X axis within the chart's full range without changing the Y axis, any value, or the answer; the start never passes the end, and no zoom, whether from the sliders or a drag, is narrower than one thousandth of the full range. A horizontal drag inside the plot zooms as well; ordinary wheel and vertical touch scrolling stay with the chat. The actions bar offers select-visible-range, which marks the current view, including the full range when nothing is zoomed, as a translucent band with grid coloring, shows the selected start and end in the actions bar, and is display only, clear-selection, and reset-view, which restores the full range, every series, the full Y axis, and clears selection and slice focus without refetching. The view note states the zoomed range and the selection. The actions bar's expand control opens the chart in a modal layer over the widget's thread area, per widget instance and never over the whole page: the layer holds the only card instance for that chart with the same view state, a note reading `Chart expanded` stands in the answer where the card was, the thread, header, and composer are inert, focus moves to the layer's close control, and the layer is labelled with the chart title. Close, Escape, or the card's close control returns the card to the answer, restores the thread scroll position, and moves focus to that card's expand control, else the answer, else the thread. When the answer or the chart is removed while expanded, the layer disappears with it. The actions bar's view-data control locates the chart's direct parent table in the same answer, expands it, and moves focus to its disclosure; charts sharing a parent table use the one table. When the answer has no parent table, the card opens a paged view of the values the chart received (series, x, and y as received, twenty per page) that states it is not the full source record set; the view is closed with a control that returns focus to the view-data control. From an expanded card, view-data closes the expansion first and then continues in the answer. Responsive aspect handling, band and pie geometry, clipping, legend ordering and wrapping, text anchors and rotation, axis label truncation, scale algorithms, and line-path `fill:none` are fixed.

When `Latest` is visible, it shares one compact status row with the Transport label. The label truncates before the action on narrow hosts; the action does not cover or reduce the message viewport.

Thread scrollbar width is fixed to `thin`; its thumb and track colors are tokenized. Placeholder opacity is fixed; placeholder color is tokenized.

An approval request shows a feedback line inside `approval-panel` while a decision is being
submitted, once the submission is accepted, and when a submission fails. It is ordinary text in the
panel, has no part of its own, and takes the muted text color; the failure wording takes the danger
role and is announced as an alert. The accepted wording states only that the decision was received,
never that an action is running, because the same line serves approve, cancel, and reject. A failed
submission leaves the request open with its controls usable again and does not raise the error
notice.

Disabled text-entry and action controls use the disabled fill plus muted text
and an ordinary border; disabled state does not rely only on opacity. In forced
colors mode, interactive parts use system focus, border, and disabled colors.

The inner conversation column is centered and clamped to the available widget width after applying `--parler-content-max-width`. Selected text anywhere inside Parler uses the selection background and foreground tokens.
