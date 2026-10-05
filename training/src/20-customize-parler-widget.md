# Customize the Parler widget

**Prerequisites:** Chapters **4–6** (extensions, configuration, and first run) and Chapter **19** (embedding Parler in a mashup).

## Goal

Chapter **19** connected Parler to an existing mashup. This chapter makes that embedded experience fit the host application without forking the widget.

You will learn how to customize:

- the visible header, prompt placeholder, empty state, and progress presentation;
- colors, typography, spacing, message widths, controls, Markdown, charts, and print output;
- one widget instance without affecting other Parler instances; and
- supported semantic surfaces such as warnings, approval panels, and rate-control activity.

The complete Composer-facing property and service contract, plus the supported CSS token, part, and state inventory, is in **Appendix Q: Widget API reference**.

---

## Choose the right customization layer

Parler has four supported customization layers. Start at the top and move down only when the higher layer cannot express the result you need.

| Layer | Use it for | Typical mechanism |
|---|---|---|
| Mashup layout | Size, placement, expand/collapse behavior | Parent container and contained-mashup bindings |
| Widget properties | Content and behavior that Composer should bind | `HideHeader`, `HeaderTitle`, `Placeholder`, `ProgressPresentation`, `EmptyStateMedia`, and related properties |
| Mashup Style Theme | Application-wide light or dark visual language | `ThemeMode=mashup` and the active ThingWorx Style Theme |
| Parler Theme API | Intentional instance-level visual overrides | `CustomClass`, public `--parler-*` tokens, and documented `[part]` / state selectors |

Transport, conversation identity, history loading, and Host Context are widget configuration, but they are not visual customization. Do not change them merely to alter presentation. See Chapter **19** and Appendix **Q** for those bindings.

## Start with the Mashup Style Theme

The default `ThemeMode` is `mashup`. In this mode, Parler follows supported roles from the active ThingWorx Mashup Style Theme and falls back to its documented defaults when a role is unavailable.

This should be the normal customer configuration:

1. Select the application Style Theme in Composer.
2. Leave `ThemeMode` set to `mashup`.
3. Verify messages, controls, notices, charts, focus indicators, and disabled states in the mashup.
4. Add Parler-specific overrides only for differences that are intentional.

Set `ThemeMode` to `parler-dark` only when this instance should use Parler's bundled dark preset instead of the current Mashup Style Theme colors. `ThemeMode` is bindable, so a mashup parameter or service result can switch it at run time. Use only the exact values `mashup` and `parler-dark`; invalid or differently cased values resolve to `mashup`.

Changing the mode changes presentation only. It does not clear history or reconnect the transport.

## Configure visible content in Composer

Use widget properties for changes that are content or behavior rather than CSS.

| Requirement | Property | Guidance |
|---|---|---|
| Show a title region | `HideHeader=false` and `HeaderTitle` | Keep the header hidden for compact embedded panels; show it when the chat needs its own identity. |
| Change the prompt hint | `Placeholder` | Make it short, localizable, and relevant to the host application. |
| Disable prompt entry | `Disabled=true` | Use a binding when another page condition controls availability. |
| Reduce routine progress detail | `ProgressPresentation=compact` | Critical warnings, failures, approvals, and rate-control waiting remain visible. |
| Localize compact progress text | `ProgressLabel`, `RateControlLabel` | Both are localizable binding targets. Empty values use their built-in defaults. |
| Brand the empty conversation | `EmptyStateMedia` | Select an SVG or another browser-decodable ThingWorx Media Entity. |

`HeaderSubtitle` remains only for compatibility with existing mashup imports and is not rendered.

### Compact progress example

For a narrow operations panel, set:

```text
ProgressPresentation = compact
ProgressLabel = Working…
RateControlLabel = Waiting for capacity…
```

Compact mode suppresses routine task detail, not data. It never hides provider rate-control waiting, approval requests, errors, user-action requirements, or task detail that requires attention.

### Empty-state Media

Select a Media Entity for `EmptyStateMedia` in Composer. The property type is `IMAGELINK`; ThingWorx converts that reference into a browser-loadable image URL.

SVG is recommended for scalable branding. If the value is empty, inaccessible, or cannot be decoded, the built-in `Parler` label remains visible instead of a broken-image indicator. The Media is decorative and does not replace an accessible text label.

Use Theme tokens to control its presentation:

```css
.customer-chat parler-ui {
  --parler-empty-state-media-max-width: 240px;
  --parler-empty-state-media-max-height: 120px;
  --parler-empty-state-media-opacity: 0.9;
  --parler-empty-state-media-filter: grayscale(20%);
}
```

---

## Scope visual overrides to one instance

Do not place an unscoped override on every `parler-ui` element unless that is deliberately an application-wide rule.

For one instance:

1. Set its `CustomClass` property to an application-owned name, such as `customer-chat`.
2. Add rules to Mashup Custom CSS or Style Theme Custom CSS under that class.
3. Give other Parler instances a different class or no class.

```css
.customer-chat parler-ui {
  --parler-color-accent: #005ea8;
  --parler-color-user-message: #003b5c;
  --parler-color-on-user-message: #ffffff;
  --parler-message-max-width: 720px;
}
```

`CustomClass` is both an input and an output binding. Multiple class names are space-delimited.

Public tokens take priority over both the active Mashup Style Theme and the bundled dark preset. This makes a scoped override stable across Theme changes.

## Customize semantic surfaces

Parler renders in light DOM and exposes supported semantic `part` attributes. Select them with `[part~="name"]`.

For example, strengthen warning notices and make rate-control waiting more visible:

```css
.customer-chat parler-ui [part~="notice"][data-severity="warning"] {
  border-width: 2px;
  font-weight: 600;
}

.customer-chat parler-ui [part~="activity-status"][data-activity="rate-controlled"] {
  text-decoration: underline;
}
```

Semantic selectors are useful when a token is intentionally shared by several surfaces or when a state has no dedicated public token. Common surfaces include:

- `user-message`, `assistant-message`, and `markdown-content`;
- `notice`, `activity-status`, `task-state`, and `connection-status`;
- `approval-panel` and its action controls;
- `composer`, `composer-input`, `primary-action`, and `stop-action`;
- `data-table`, `chart`, `message-action`, and `popover`; and
- `empty-state`, `empty-state-media`, and `empty-state-label`.

Appendix **Q** lists every supported part and state value.

## Customize Markdown and structured output

Assistant responses can contain Markdown, structured tables, and charts. Customize those through their public roles rather than generated HTML or SVG structure.

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

Markdown images remain within the message width. Code blocks scroll horizontally when needed. Those containment behaviors are fixed so content cannot accidentally break the chat layout.

### Chart palette and geometry

Screen charts expose 24 ordered series-color slots. A series and its legend mark always use the same slot; item 25 wraps to slot 1.

```css
.customer-chat parler-ui {
  --parler-chart-series-1: #005ea8;
  --parler-chart-series-2: #d1495b;
  --parler-chart-line-width: 2.5px;
  --parler-chart-scatter-point-radius: 5px;
  --parler-chart-grid-opacity: 0.5;
}
```

Colors, line widths, point sizes, grid opacity, reference lines, typography, and legend measurements are configurable. Scale algorithms, clipping, responsive view-box behavior, tick-density rules, and chart geometry remain fixed.

## Give print output its own palette

Copy and print are different output paths:

- copied plain text and HTML do not include Parler colors;
- printed output uses a separate, portable light palette by default; and
- screen Theme changes do not silently recolor print output.

Override print tokens on the normal widget scope:

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

Print page margins, paper-width clamping, page-break avoidance, and responsive image and SVG containment are fixed.

---

## Supported and unsupported customization

The supported styling contract consists of:

- the `parler-ui` host;
- public `--parler-*` tokens listed in Appendix **Q**;
- documented `[part]` selectors and state attributes; and
- native interaction states such as `:hover`, `:active`, `:focus-visible`, `:disabled`, `aria-pressed`, and `aria-expanded` where applicable.

Do not depend on:

- `::part()` selectors;
- internal `--parler-theme-*` or `--parler-effective-*` properties;
- internal class names or element nesting;
- generated chart SVG nodes or positional selectors such as `:nth-child()`;
- Shadow DOM selectors or an assumed `shadowRoot`; or
- undocumented parts, attributes, or state values.

Light DOM may make an internal selector appear to work. It is still upgrade-sensitive and has no compatibility guarantee.

Parler-specific per-widget ThingWorx Style Properties are not exposed. Use the Mashup Style Theme for application defaults and a `CustomClass`-scoped token or semantic selector for deliberate exceptions.

## Accessibility and verification checklist

After customization, verify at least:

1. keyboard focus remains visible on message actions, the composer, approval controls, and dialogs;
2. text and icons have sufficient contrast in normal, hover, selected, disabled, warning, and danger states;
3. both `mashup` and `parler-dark` modes remain readable if the application exposes both;
4. narrow embedded panels still allow prompt entry, scrolling, approval, and stop actions;
5. Markdown tables and code blocks remain usable;
6. line, scatter, bar, and pie charts retain distinguishable series and reference lines;
7. print output remains readable independently of the screen Theme; and
8. an unavailable `EmptyStateMedia` falls back cleanly.

The parent Mashup container owns width and height. Parler deliberately keeps minimum usable sizing, overflow, scrolling, responsive containment, and accessibility-only positioning fixed.

## API reference

Use **Appendix Q: Widget API reference** when building Composer bindings or maintaining Custom CSS. It records:

- all Composer properties, directions, types, and defaults;
- all callable widget services;
- the deliberate absence of user-turn events;
- the complete supported CSS token families; and
- all supported semantic parts and states.
