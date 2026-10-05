# Parler widget Theme design

Status: implemented. The customer-facing styling contract is [`theme-api.md`](./theme-api.md).

## 1. Design summary

Theme is a product capability of the Parler widget. The earlier fixed dark appearance is not a supported theme implementation and is not a migration baseline.

The design has eight decisions:

1. `ThemeMode` has two accepted values: `mashup` and `parler-dark`.
2. `mashup` is the default and primary product path. It consumes the active ThingWorx Mashup Style Theme through the supported platform expression resolver. Per-widget Parler Style Properties are not available on the supported ThingWorx 10.1.2 integration path.
3. `parler-dark` is an optional preset, not a compatibility mode. It is implemented only as an alternate token source and has no pixel-parity promise.
4. The supported styling surface is a layered contract: ThingWorx style dictionary/expression manifest -> Parler semantic CSS tokens -> stable semantic parts/states -> CSS and D3 renderers. Mashup Custom CSS remains an advanced escape hatch.
5. Progress appearance and progress-content projection are separate. Theme owns the working/rate-controlled colors, pulse, and safe maximum width. The Widget owns whether routine activity is detailed or compact; operational and attention-required states remain visible.
6. Coverage is exhaustive, not example-driven. Every customer-visible surface and every SVG presentation attribute has a named token/part/state/output palette or an explicit structural-fixed disposition. Extra supported hooks are acceptable; an unowned visible surface is not.
7. Empty-state content and its presentation are separate. The `EmptyStateMedia` Widget property is authored by selecting a ThingWorx Media Entity and is declared with the ThingWorx `IMAGELINK` base type. Media Entity is the stored resource; `IMAGELINK` is the string-valued property contract that references it. When the value is empty or cannot load, Parler renders the built-in `Parler` label. Theme owns the media sizing/effects and the media/label semantic parts, but it does not select the Media Entity.
8. Theme values reach Parler through a supported expression-resolution bridge. The platform recognizes the generated Parler dictionary, but its Web Component CSS path delivers through the PTCS style aggregator into an enlisted component's `shadowRoot`; Parler intentionally uses light DOM and is not enlisted. The wrapper therefore resolves the dictionary's platform expressions with `TW.getStyleManager().resolveExpressions(...)` and writes only internal `--parler-theme-*` source values on the host. It does not simulate per-widget Style Properties.

## 2. Implementation context

**Business context**

Parler is normally the central widget on a dedicated ThingWorx Mashup page. Mashup authors need Parler to look intentional inside light, dark, branded, and high-contrast Style Themes without maintaining a fork of `parler-ui.css`.

**Current implementation**

`parler-ui` is a Lit 3 light-DOM component. It normalizes and reflects `ThemeMode` and `connection-status`. The runtime and Composer wrappers consume the expression manifest token-to-expression, fan shared expressions out to every mapped token, handle unavailable/null results without polling, reject malformed internal entries loudly, and write only internal `--parler-theme-*` declarations. The registry contains all 151 public/effective tokens, 78 of which have primary platform-expression sources; the bridge manifest has those 78 entries plus the internal information-notice source and the secondary pie-outline source required by Section 8.2. The deployed Theme property table does not register a packaged `parler-color-warning` flat entry, so warning roles use their public token and fallback rather than an internal Theme source.

Portable-print CSS in `parler-ui/lib/assistantResponseActions.js` resolves the 34 no-source print tokens from the live host with their light defaults, serializes concrete palette and public typography values into the detached document, and rewrites every marked cloned-SVG palette attribute without mutating the screen SVG. The emergency fallback CSS remains the separately allowlisted degraded path. Markdown is rendered through `markdown-it` with HTML disabled, linkify enabled, and hard line breaks enabled; every configured output kind has intentional token-owned styling under the supported `markdown-content` part. Screen D3 resolves the complete color and presentation object, uses ordered modulo-24 assignment for every kind, draws and styles grids/axes, marks palette-owned nodes for print rewriting, and redraws from chart/geometry changes plus observed host `theme-mode`, `class`, and `style` changes. The observed host `style` mutation is the concrete bridge-refresh DOM signal after the wrapper replaces resolved internal Theme sources.

**Platform delivery facts (ThingWorx 10.1.2)**

`TW.updateTheme()` reads the generated dictionary through global `TW.Widget.widgetWrapper.getWidgetStyleDict('parlerui')`, maps it to the external element tag `parler-ui`, and records `$type: 'wc'`. `TW.STYLE_MGR_JSON['parler-ui']` and `widgetmetaJson['parler-ui']` contain those values, so registration, load order, and tag mapping work. Identifiers absent from the active Theme are filtered as unresolved declarations; a dictionary using known global Theme expressions produces the expected `ptcs-style-unit wc="PARLER-UI"` and `:host` selectors. Direct delivery still cannot reach Parler: the PTCS style aggregator injects generated rules into an enlisted element's `shadowRoot`, while Parler has no `shadowRoot`, does not use PTCS `BehaviorStyleable`, and is not enlisted. The platform's static `StyleProps` registry also has no `parler-ui` entry and exposes no supported extension registration path, so per-widget Parler Style Properties are unavailable. The exported `TW.getStyleManager().resolveExpressions(...)` API resolves the same platform expressions, and the wrapper lifecycle delivers deterministic initial and Theme-change callbacks.

**Terms and contracts**

`ThemeMode` selects a source of visual tokens; it does not change wire behavior. A *platform theme source* is a value resolved from the active ThingWorx Style Theme through the bridge. A *public token* is a supported `--parler-*` CSS custom property that an application may override. An *effective token* is the internal resolved value consumed by Parler CSS and D3. A *semantic part* is a stable `part="..."` hook in Parler light DOM and is targeted with `[part="..."]`, not `::part()`. Theme does not change `CONTRACTS/UI_CLIENT_PROTOCOL.md`, chart wire JSON, or any other wire contract.

**Ownership**

The implementation spans `parler-ui`, `parler-ui-widget`, and the existing `twx-wc-sdk-utility` build behavior. `parler-ui-widget/input/widgets/parler-ui.json` owns the Composer property surface. `input/styles/parlerui/` owns the SDK expression manifest and flat fallback values. The runtime and IDE wrappers own the smallest supported expression-resolution bridge and refresh hooks; they do not copy or reimplement the platform Theme evaluator. `parler-ui` owns tokens, semantic hooks, states, and chart resolution.

**Scope**

In scope: native Style Theme participation through the expression-resolution bridge, `ThemeMode`, semantic CSS tokens, stable style hooks, complete Markdown/output/notice/overlay coverage, D3 color and non-color presentation, portable print theming, dynamic theme refresh, the active-turn status/pulse and its safe maximum width, `CustomClass`, the adjacent `ProgressPresentation` view policy, and the adjacent ThingWorx Media-backed empty-state property. Out of scope: per-widget Parler Style Properties on ThingWorx 10.1.2, switching Parler to Shadow DOM, wholesale conversion to PTCS components, generic ThingWorx State Definition formatting, changing reducer/wire semantics, suppressing approval/error/attention-required information, parsing or injecting raw SVG/XML/HTML, and copying or patching the platform Theme engine.

**Design constraints**

`mashup` is the default. `parler-dark` is optional with no compatibility or pixel-parity requirement. Light DOM remains. The expression-resolution bridge supports automatic Mashup Theme tokens but not per-widget Parler Style Properties. Semantic tokens and stable `[part]`/state hooks are the supported advanced interface; Custom CSS is last-resort customization. Theme coverage is exhaustive by visible surface: omitted customizable roles are defects, while additional semantic hooks are acceptable. D3 consumes the same resolved color and non-color presentation object as the rest of the widget, and pie uses render-order slots rather than label hashing. Print uses a separately resolved portable light palette instead of inheriting screen colors. Ordinary working and provider rate-control waiting remain visibly distinct Theme states. Progress detail suppression is a Widget view setting, not a Theme effect; compact mode never hides rate-control, approval, error, or attention-required status. `EmptyStateMedia` selects optional empty-state content through ThingWorx Media; Theme styles that content but never changes which entity is selected.

**Code layout**

The customer-facing styling specification is `docs/ui/theme-api.md`. Its exact token names, groups, effects, defaults, value constraints, parts, states, and output rules stay mechanically aligned with the implementation registry and this design in every change to a supported styling point.

Host properties, empty-state Media rendering, markup parts/states, and print invocation are in `parler-ui/parler-ui.js`; ordinary visual styling is in `parler-ui/styles/parler-ui.css`; portable-print CSS/serialization is in `parler-ui/lib/assistantResponseActions.js`. `parler-ui/lib/themeTokens.mjs` is the single expanded public/theme/effective/print token inventory. `IMAGELINK` normalization and image-lifecycle state live in `parler-ui/lib/emptyStateMedia.mjs`. Chart host/redraw work is in `parler-ui/components/parler-ui-chart.js`; render-object resolution is in `parler-ui/components/chart-theme.js`, while assignment and SVG drawing remain in `parler-ui/components/chart-draw.js`. Progress projection is in `parler-ui/lib/activeTurnIndicator.mjs` and `parler-ui/lib/taskStatePanelVisibility.mjs`, with tests beside these modules. ThingWorx metadata, including the `EmptyStateMedia` `IMAGELINK`, is in `parler-ui-widget/input/widgets/parler-ui.json`; Theme inputs are in `parler-ui-widget/input/styles/parlerui/`. The expression-resolution/token-refresh bridge is under `parler-ui-widget/input/ui/parlerui/`. It uses `TW.getStyleManager().resolveExpressions(...)`, consumes the manifest in token-to-expression direction so one resolved expression fans out to every matching internal `--parler-theme-*` name, clears only values it previously wrote before applying a changed non-null result, and refreshes on initial render plus the platform's Composer/runtime Theme lifecycle. A `null` result means the widget or variant is temporarily unavailable: the bridge leaves prior values untouched and waits for the next deterministic lifecycle callback without polling. It writes only internal `--parler-theme-*` values onto the host; it does not write public `--parler-*`, reproduce Theme expression semantics, scrape generated CSS, patch `ptcs-style-unit`, poll, monkey-patch platform globals, or depend on an unexported SDK local. The wrapper has no static `getWidgetStyle*` methods; the platform consumes the generated global `widgetWrapper.config` registry, and its legacy fallback would misinterpret the full variant array such static methods return.

### 2.1 Implementation orientation

The shipped path is one widget extension around one light-DOM web component:

```text
Mashup Style Theme + widget properties
                 |
                 v
parler-ui-widget/input/widgets/parler-ui.json
  Composer metadata (`ThemeMode`, `EmptyStateMedia`, progress properties)
                 |
                 v
twx-wc-sdk-utility generic wrapper + input/styles/parlerui/
                 |
                 v
<parler-ui> in parler-ui/parler-ui.js
  light-DOM markup + parler-ui/styles/parler-ui.css
                 |
          +------+------+
          |             |
          v             v
 ordinary CSS      <parler-ui-chart> + D3 SVG
```

The code contains the bridge, the 151-entry public-token registry, the 80-entry generated expression manifest, two activity states, progress projection, empty-state Media projection, message/Markdown presentation, token-owned container/thread/selection presentation, the stable light-DOM part/state surface, the screen-chart render path in both modes, and the independent portable-print path. All chart kinds share the 61-token screen resolver/D3/redraw behavior, while the 34 no-source print tokens own detached-document colors and cloned-chart recoloring.

The current component declares public properties in `ParlerUi`'s `static properties` block, assigns defaults in its constructor, returns `this` from `createRenderRoot()`, and injects `parler-ui.css` from `connectedCallback()`. Therefore Theme selectors and public CSS custom properties operate in light DOM. Do not introduce Shadow DOM, `::part()`, or a second component tree. The main stylesheet is the ordinary path; the inline fallback in `injectParlerUiFallbackCss()` is only the degraded path when that stylesheet fails.

The generic wrapper reads `parler-ui-widget/input/widgets/parler-ui.json`, maps each PascalCase Composer property to its `src` web-component property, and preserves an explicitly declared `baseType`. `twx-wc-sdk-utility/src/compile.js` derives the widget identity `parlerui`; only `input/styles/parlerui/style.dict.json` and `style.properties.json` become the live Theme input.

The empty state is rendered in `ParlerUi.render()` when `_chatState.rows.length === 0 && !historyLoading`. It uses the supported `empty-state` root plus `empty-state-media|empty-state-label` children and contains only a decorative `<img>` or the built-in `Parler` label. Its generation-guarded View state ignores stale image events and does not enter `chatSession.js`, `wireAdapter.js`, or `historyHydrate.js`.

Active-turn text is derived by `lib/activeTurnIndicator.mjs`; terminal task-panel visibility is derived by `lib/taskStatePanelVisibility.mjs`; domain state remains owned by the reducer and history/wire adapters. Theme adds projection and semantic styling only. It does not change `CONTRACTS/`, wire JSON, task-state enums, or stored snapshots.

Charts are custom D3 SVG, not ThingWorx chart widgets. CSS can style their light-DOM host, but D3-written SVG attributes require the resolved render object in Sections 7 and 10. Printing creates a detached document through `lib/assistantResponseActions.js`, so print values are resolved and serialized separately from the screen Theme.

`docs/ui/theme-api.md` is the customer contract, not an implementation note. Exact public token names, defaults, constraints, parts, states, and the `EmptyStateMedia` behavior change there in the same change as the registry/design/code. Generated `tmp/`/bundle output is not hand-edited source.

Local verification runs `npm test` and `npm run build:tw` under `parler-ui`, followed by `npm run sync` and the repository-local `twx-wc-sdk-utility/bin/cli.js` under `parler-ui-widget` (Section 15).

## 3. Goals and non-goals

### 3.1 Goals

- Make a Parler instance follow the active Mashup Style Theme by default.
- Let Mashup authors customize individual Parler instances through scoped public tokens and stable semantic parts/states without simulating unavailable per-widget Style Properties.
- Keep text, controls, messages, status surfaces, tables, and D3 charts visually coherent.
- Cover every current customer-visible surface, including Markdown variants, notices, overlays, charts, and print output, with a named customization or explicit fixed disposition.
- Give advanced authors a coherent, stable semantic-token and semantic-hook API.
- Let authors safely constrain the active-turn indicator without changing final answer width.
- Let a Widget instance show detailed progress or a compact preset label without discarding received progress state.
- Let a Widget instance display a ThingWorx Media image, including SVG, in the empty state while preserving a reliable built-in fallback.
- Support a theme change at run time without reloading the Mashup.
- Preserve usable standalone rendering when the component runs outside ThingWorx.
- Keep the design accessible under both built-in and application-provided palettes.

### 3.2 Non-goals

- Reproduce the current dark appearance exactly.
- Expose every CSS declaration as a Composer property.
- Promise stability for internal DOM structure or undocumented class names.
- Make Parler a generic renderer for ThingWorx State Definitions.
- Adopt Shadow DOM or PTCS controls as a prerequisite.
- Change layout ownership between the Mashup container and Parler.
- Add wire fields or change chart payload semantics.
- Use Theme to hide or replace business/operational content.
- Treat arbitrary raw SVG/XML/HTML as an empty-state input or execute content from a Media Entity.
- Offer a fully hidden progress mode that leaves a busy turn without visible status.

## 4. Platform and repository facts

The design depends on these established facts:

- The Web Component SDK derives `parlerui` from the custom element name `parler-ui` and looks for `input/styles/parlerui/style.dict.json` and `style.properties.json`.
- A style dictionary models widget, variant, part, state/state-host, and CSS declarations. Flat style properties provide defaults for identifiers referenced by the dictionary. The vendored utility embeds both structures in generated `parlerui.config.js`; ThingWorx 10.1.2's global `TW.Widget.widgetWrapper.getWidgetStyleDict(name)` reads that config directly. Static widget-constructor registration methods are not required.
- ThingWorx ordinary styling precedence includes per-widget Style Property, Mashup Custom CSS, Style Theme Custom CSS, then Style Theme, subject to CSS specificity. The platform's static `StyleProps` registry does not contain `parler-ui`, and no supported extension registration path was found, so the per-widget layer is not part of the Parler contract.
- Parler and its chart component use light DOM. Mashup CSS can reach descendants directly. `::part()` is not the supported syntax for this implementation.
- `TW.updateTheme()` maps the external widget key `parlerui` to element tag `parler-ui`, stores the full dictionary under `TW.STYLE_MGR_JSON['parler-ui']`, and marks `widgetmetaJson['parler-ui']` as `$type: 'wc'`. A dictionary using known platform expressions produces the correct `ptcs-style-unit wc="PARLER-UI"` and `:host(...)` selectors. Identifiers that are not properties of the active Theme are filtered.
- The PTCS style aggregator delivers a `ptcs-style-unit` by adding its generated stylesheet to an enlisted element's `shadowRoot`. Parler has no `shadowRoot`, does not use PTCS `BehaviorStyleable`, and is not present in the aggregator map. Direct dictionary CSS therefore cannot reach Parler's host or light-DOM parts without violating the Shadow DOM non-goal or patching platform internals.
- The exported `TW.getStyleManager().resolveExpressions(theme, widgetName, variant, fallback)` API resolves known Style Theme expressions without duplicating Theme semantics. Runtime `themeUpdated`/`changeThemeName` and the corresponding Composer render/update lifecycle provide deterministic refresh points; no polling is required.
- D3 writes SVG presentation attributes and currently owns a separate fixed palette. It must receive resolved concrete colors; it cannot inherit the platform chart widget palette automatically.
- The emergency fallback stylesheet is used when the main stylesheet fails to load. It must stay independent of ThingWorx Theme availability.
- A ThingWorx Media Entity stores the image resource. `IMAGELINK` is the distinct string base type used by widget properties and infotable fields to reference an image, including a Media Entity. `IMAGE` is binary image data and is not the selected contract for this property. The bundled SDK utility preserves an explicitly declared widget-property base type.
- Standard ThingWorx widget state stores Media selections as `IMAGELINK` strings such as `/Thingworx/MediaEntities/<name>`. The checked-in SDK normalizes an `IMAGELINK` for rendering with `window.TW.convertImageLink(value)` before assigning it to an image control.

Implementation must keep resolver inputs and lifecycle behavior covered locally, then verify the resulting host tokens and visible surfaces in the deployed ThingWorx version. It must not assume that a green unit test proves Composer behavior.

### 4.1 Platform style-delivery channels

The design treats Theme value resolution and CSS delivery as separate mechanisms. Each channel's supported contract:

| Channel | Platform delivery path | Light-DOM contract |
|---|---|---|
| Dictionary expression manifest | generated `widgetWrapper.config('parlerui').styleDict` -> bridge input | Declares the mapping from Parler internal source tokens to platform Theme expressions; it is not a claim that generated CSS reaches light DOM |
| Expression-resolution token bridge | `TW.getStyleManager().resolveExpressions(...)` -> internal `--parler-theme-*` declarations on `<parler-ui>` | Supported automatic Mashup Theme path; it does not provide per-widget Style Properties or direct part declarations |
| Style Theme Custom CSS | platform-mounted document stylesheet | Reaches light DOM through ordinary host/`[part]` selectors |
| Mashup Custom CSS | Mashup-mounted document stylesheet | Reaches light DOM through ordinary host/`[part]` selectors |
| Public `--parler-*` tokens through scoped application CSS | ordinary CSS cascade onto `<parler-ui>` | Supported; public values remain application-owned and outrank bridged internal sources in effective-token resolution |

The implementation MUST use the exported `TW.getStyleManager().resolveExpressions(...)` surface. It MUST NOT reference an unexported `styleMgr` local, copy the Theme evaluator, scrape a generated stylesheet, patch the SDK/aggregator, add timer polling, monkey-patch platform globals, or introduce a platform shim.

### 4.2 Selected delivery outcome

The selected mechanism is the **supported expression-resolution bridge**. Direct registration, element-tag mapping, and selector generation work, but the generated Web Component stylesheet is delivered only to a PTCS-styleable component's `shadowRoot`; it cannot reach Parler's intentional light DOM. The platform also exposes no supported way for an external widget to extend the static per-widget `StyleProps` registry. Direct host/part declarations and per-widget Parler Style Properties are therefore unavailable.

The wrapper resolves the manifest's platform expressions through `TW.getStyleManager().resolveExpressions(...)` and writes only matching internal `--parler-theme-*` values onto the Parler host. It applies values after the host exists and the Theme is available, and re-resolves on deterministic Composer/runtime Theme lifecycle callbacks. After receiving a changed non-null result, it removes only the internal source declarations written by the previous bridge result before applying the new set, so a missing or changed expression falls through to the checked-in fallback instead of leaving a stale value. A `null` result for an unknown widget or variant preserves the prior declarations and is not treated as a resolved-empty set. Automatic Mashup Theme following, public tokens, `CustomClass`, Theme Custom CSS, Mashup Custom CSS, stable parts, and semantic states remain supported.

The dictionary is an expression manifest, not evidence of direct CSS reachability. It contains job-1 source mappings only: no job-2 part/state declarations, `stateHost` delivery, or simulated Style Property precedence. There are no dormant direct-delivery paths.

## 5. Architecture

```text
Active Mashup Style Theme
           |
           v
input/styles/parlerui expression manifest
           |
           v
TW.getStyleManager().resolveExpressions(...)
           |
           v
ThingWorx wrapper lifecycle bridge
           |
           v
        --parler-theme-* platform-source values
                         |
              ThemeMode selector
              /                  \
       mashup source       parler-dark preset
              \                  /
                         v
        --parler-effective-* internal values
                    /             \
                   v               v
        light-DOM CSS          D3 palette resolver
                   \               /
                    rendered Parler UI

Public --parler-* overrides enter before effective-token resolution.
Scoped Theme/Mashup Custom CSS can target documented light-DOM parts and states.
Per-widget Parler Style Properties are not part of this contract.
```

The three token namespaces have distinct ownership:

| Namespace | Owner | Stability | Purpose |
|---|---|---|---|
| `--parler-theme-*` | ThingWorx adapter | Internal | Values resolved from the active Style Theme/style dictionary |
| `--parler-*` | Public customization API | Supported | Explicit application overrides, normally scoped by `CustomClass` |
| `--parler-effective-*` | Parler | Internal | Final values selected by `ThemeMode` and consumed by CSS/D3 |

The component must never modify a public `--parler-*` property at run time. Within token resolution, effective values use the binding order public `--parler-*` -> bridged platform-source `--parler-theme-*` -> built-in fallback. The wrapper may write only resolved internal `--parler-theme-*` values onto the host. Scoped Theme/Mashup Custom CSS still follows the ordinary CSS cascade when it directly styles a documented part or state.

Representative resolution:

```css
parler-ui[theme-mode="mashup"] {
  --parler-effective-color-text:
    var(--parler-color-text,
      var(--parler-theme-color-text, #1f252b));
}

parler-ui[theme-mode="parler-dark"] {
  --parler-effective-color-text:
    var(--parler-color-text, #e8eaed);
}
```

The `mashup` fallback and portable-print values are part of the customer contract and are listed in `docs/ui/theme-api.md`. Revising one requires the corresponding registry, design, customer-specification, and fixture update. The optional `parler-dark` preset remains exempt from exact-color compatibility under Section 6.3. The resolution order is binding.

## 6. `ThemeMode`

### 6.1 Composer and component contract

`parler-ui-widget/input/widgets/parler-ui.json` adds:

| Field | Value |
|---|---|
| Name | `ThemeMode` |
| Base type | `STRING` |
| Binding target | `true` |
| Binding source | `false` |
| Default | `mashup` |
| Accepted values | `mashup`, `parler-dark` |
| Web-component property | `themeMode` |

The checked-in utility documentation and templates expose no enum/select/dropdown metadata for a `STRING` Widget property, so the property uses the documented plain-string form.

`<parler-ui>` reflects the normalized value as `theme-mode="mashup|parler-dark"`. Missing, empty, unknown, or wrongly cased values normalize to `mashup` and generate at most one development warning per distinct invalid value. Invalid input must not break rendering.

### 6.2 `mashup`

`mashup` is the acceptance-critical path. It uses:

1. the active Style Theme;
2. Style Theme Custom CSS and Mashup Custom CSS according to platform/CSS precedence;
3. public `--parler-*` values explicitly supplied by application CSS;
4. built-in accessible fallback values only when no platform value is available.

This list enumerates inputs, not their precedence. Token-level resolution is public token -> bridged platform Theme source -> built-in fallback. Concrete Theme and Mashup Custom CSS declarations follow the ordinary platform/CSS rules in Section 9. Per-widget Parler Style Properties are unavailable.

### 6.3 `parler-dark`

`parler-dark` selects a bundled set of dark effective-token defaults. It reuses every renderer, selector, part, and state used by `mashup`.

It must not:

- duplicate `parler-ui.css`;
- create a separate style dictionary;
- branch render templates or chart algorithms;
- promise exact preservation of today's pixels;
- require a migration test matrix.

Public tokens and scoped Custom CSS may still override individual rendered properties. The bridge writes Theme source values only; it has no job-2 part declarations to gate. An author who intentionally customizes the nostalgic preset uses public tokens or scoped Custom CSS. The preset stays a small token table; it never becomes a second visual architecture.

## 7. Supported semantic-token API

Only names in this section are supported public CSS custom properties. Internal effective and theme-source properties are not an application API. Public tokens are customized through scoped application CSS; they do not appear as separate Composer Style Properties.

### 7.1 Foundation and surfaces

| Public token | Meaning | ThingWorx source intent |
|---|---|---|
| `--parler-font-family` | Body font stack | global body font family |
| `--parler-font-size` | Base text size | global body font size |
| `--parler-line-height` | Base line height | no platform source; built-in fallback or public override |
| `--parler-color-canvas` | Host/page-facing background | primary background |
| `--parler-color-surface` | Main panel surface | secondary/element background |
| `--parler-color-surface-raised` | Popovers and floating controls | elevated/primary background |
| `--parler-color-surface-subtle` | Quiet rows and code/table fills | secondary background |
| `--parler-color-text` | Primary text | body text |
| `--parler-color-text-muted` | Secondary text | label/secondary text |
| `--parler-color-border` | Ordinary dividers and borders | global border line |
| `--parler-color-accent` | Links and primary action | primary core color |
| `--parler-color-on-accent` | Text/icon on accent | contrast text resolved for primary core |
| `--parler-color-focus` | Visible keyboard focus | global focus color |
| `--parler-color-danger` | Errors/destructive actions | danger core color |
| `--parler-color-warning` | Warnings/rate control | warning core color |
| `--parler-color-success` | Connected/success state | success core color |
| `--parler-color-scrim` | Modal/popover overlay scrim | no stable platform source |
| `--parler-color-placeholder` | Placeholder text in every text-entry surface | label/secondary text |
| `--parler-color-scrollbar-thumb` | Thread scrollbar thumb | global border line |
| `--parler-color-scrollbar-track` | Thread scrollbar track | main panel surface |
| `--parler-color-selection-bg` | Selected-text background inside Parler | primary core color |
| `--parler-color-selection-text` | Selected-text foreground inside Parler | contrast text resolved for primary core |

### 7.2 Messages, controls, geometry, and Markdown

| Public token | Meaning |
|---|---|
| `--parler-color-user-message` | User-message background |
| `--parler-color-on-user-message` | User-message foreground |
| `--parler-color-assistant-message` | Assistant-message background |
| `--parler-color-on-assistant-message` | Assistant-message foreground |
| `--parler-color-control-bg` | Input and secondary-control background |
| `--parler-color-control-text` | Input and secondary-control foreground |
| `--parler-color-control-border` | Input and control border |
| `--parler-color-control-hover` | Secondary-control hover fill |
| `--parler-color-control-disabled` | Disabled-control fill/foreground source |
| `--parler-content-max-width` | Maximum inner conversation width |
| `--parler-message-max-width` | Maximum message-bubble width |
| `--parler-activity-max-width` | Maximum active-turn text/pulse width, independently clamped to the available assistant surface |
| `--parler-empty-state-media-max-width` | Maximum displayed width of configured empty-state Media |
| `--parler-empty-state-media-max-height` | Maximum displayed height of configured empty-state Media |
| `--parler-empty-state-media-opacity` | Opacity of configured empty-state Media |
| `--parler-empty-state-media-filter` | CSS filter applied to configured empty-state Media |
| `--parler-radius-panel` | Large panel radius |
| `--parler-radius-message` | Message bubble radius |
| `--parler-radius-control` | Input/button radius |
| `--parler-shadow-panel` | Main/elevated panel shadow |
| `--parler-space-unit` | Base spacing scale used by compact groups |
| `--parler-markdown-heading` | Markdown heading text |
| `--parler-markdown-link` | Unvisited, hover, and focus link text |
| `--parler-markdown-link-visited` | Visited link text |
| `--parler-markdown-code-bg` | Inline and block code background |
| `--parler-markdown-code-text` | Inline and block code text |
| `--parler-markdown-code-border` | Block-code border |
| `--parler-markdown-quote-bg` | Blockquote background |
| `--parler-markdown-quote-text` | Blockquote text |
| `--parler-markdown-quote-border` | Blockquote leading rule |
| `--parler-markdown-table-header-bg` | Markdown table header background |
| `--parler-markdown-table-border` | Markdown table grid |
| `--parler-markdown-hr` | Markdown horizontal rule |
| `--parler-markdown-image-radius` | Markdown image corner radius |

Widget/container width and height remain Mashup layout responsibilities. Conversation and active-turn maximum widths are visual containment choices and are tokenized; internal flex/grid mechanics, scroll behavior, and minimum usable sizes remain structural. Density is not a first-release Widget property.

Empty-state Media keeps its intrinsic aspect ratio and is structurally contained inside the empty-state surface. The two maximum-size tokens, opacity, and filter allow an application to fit a logo and adapt a monochrome SVG to a palette without making Media selection a Theme concern. The fallback label uses the muted-text and body-typography tokens and has its own semantic part for more specific application CSS.

The Markdown renderer is `markdown-it` with HTML disabled, linkify and hard line breaks enabled. The conversion inventory is binding: paragraphs and breaks inherit message text; `h1`–`h6` use the heading token; links cover normal/visited/hover/focus; `strong`, `em`, and `s` inherit color while retaining semantic emphasis; inline code and fenced/indented `pre > code` use the code tokens, block padding, wrapping policy, and horizontal overflow; `blockquote` uses the quote tokens; ordered/unordered/nested lists inherit text and spacing; tables use the table tokens; `hr` uses its rule token; images are constrained to the message width and use the image-radius token. Tests must include every listed output kind in both light and dark fixtures. The stable advanced selector root is `[part="markdown-content"]`; element names directly produced by this renderer under that root are supported for scoped application CSS, while renderer-internal classes are not.

### 7.3 Screen chart colors

| Public token | Meaning |
|---|---|
| `--parler-chart-series-1` | Ordered categorical slot 1 |
| `--parler-chart-series-2` | Ordered categorical slot 2 |
| `--parler-chart-series-3` | Ordered categorical slot 3 |
| `--parler-chart-series-4` | Ordered categorical slot 4 |
| `--parler-chart-series-5` | Ordered categorical slot 5 |
| `--parler-chart-series-6` | Ordered categorical slot 6 |
| `--parler-chart-series-7` | Ordered categorical slot 7 |
| `--parler-chart-series-8` | Ordered categorical slot 8 |
| `--parler-chart-series-9` | Ordered categorical slot 9 |
| `--parler-chart-series-10` | Ordered categorical slot 10 |
| `--parler-chart-series-11` | Ordered categorical slot 11 |
| `--parler-chart-series-12` | Ordered categorical slot 12 |
| `--parler-chart-series-13` | Ordered categorical slot 13 |
| `--parler-chart-series-14` | Ordered categorical slot 14 |
| `--parler-chart-series-15` | Ordered categorical slot 15 |
| `--parler-chart-series-16` | Ordered categorical slot 16 |
| `--parler-chart-series-17` | Ordered categorical slot 17 |
| `--parler-chart-series-18` | Ordered categorical slot 18 |
| `--parler-chart-series-19` | Ordered categorical slot 19 |
| `--parler-chart-series-20` | Ordered categorical slot 20 |
| `--parler-chart-series-21` | Ordered categorical slot 21 |
| `--parler-chart-series-22` | Ordered categorical slot 22 |
| `--parler-chart-series-23` | Ordered categorical slot 23 |
| `--parler-chart-series-24` | Ordered categorical slot 24 |
| `--parler-chart-title-text` | Chart title text |
| `--parler-chart-legend-text` | Legend label text |
| `--parler-chart-tick-text` | Axis tick-label text |
| `--parler-chart-axis-label-text` | Footer axis-label text |
| `--parler-chart-axis` | Axis domains and tick marks |
| `--parler-chart-grid` | Horizontal grid lines |
| `--parler-chart-point-outline` | Point, bar, and pie-slice outline |
| `--parler-chart-reference-danger` | Out-of-limit reference line and label |
| `--parler-chart-reference-warning` | Warning/control-limit reference line and label |
| `--parler-chart-reference-target` | Target/reference line and label |

All chart kinds use one assignment contract. For line, scatter, and bar, series at zero-based render index `i` uses `series[i % 24]`. For pie, the filtered positive slice at zero-based render order `i` uses the same `series[i % 24]`; label hashing is removed. Legend marks use the identical slot as their plot item. The 24-slot modulus matches the inspected platform chart-series vocabulary. More than 24 items deliberately wrap, so item `n` and `n + 24` share a color and must remain distinguishable by visible legend labels and chart semantics. Changing the modulus is a breaking assignment change because it recolors existing charts beyond the prior modulus.

Platform chart-series values are used when valid for an SVG color attribute. Missing, empty, gradient, or invalid values fall back slot-by-slot to the standalone palette; render-time hue generation is forbidden.

### 7.4 Screen chart presentation

| Public token | Meaning |
|---|---|
| `--parler-chart-font-family` | SVG text family |
| `--parler-chart-line-width` | Data-series and line-legend stroke width |
| `--parler-chart-line-point-radius` | Point radius on line charts |
| `--parler-chart-scatter-point-radius` | Point radius on scatter charts |
| `--parler-chart-outline-width` | Point and pie-slice outline width |
| `--parler-chart-bar-radius` | Bar corner radius |
| `--parler-chart-axis-line-width` | Axis domain/tick stroke width |
| `--parler-chart-grid-line-width` | Horizontal grid stroke width |
| `--parler-chart-grid-opacity` | Horizontal grid opacity |
| `--parler-chart-reference-line-width` | Limit/control/warning reference-line width |
| `--parler-chart-target-line-width` | Target reference-line width |
| `--parler-chart-reference-opacity` | Reference-line opacity |
| `--parler-chart-reference-limit-dash` | Limit-line dash pattern |
| `--parler-chart-reference-control-dash` | Control-limit dash pattern |
| `--parler-chart-reference-target-dash` | Target-line dash pattern |
| `--parler-chart-reference-warning-dash` | Warning-line dash pattern |
| `--parler-chart-title-font-size` | Title size |
| `--parler-chart-title-font-weight` | Title weight |
| `--parler-chart-legend-font-size` | Legend label size |
| `--parler-chart-tick-font-size` | Axis tick-label size |
| `--parler-chart-axis-label-font-size` | Footer axis-label size |
| `--parler-chart-reference-label-font-size` | Reference-label size |
| `--parler-chart-legend-swatch-size` | Legend point/box size |
| `--parler-chart-legend-swatch-radius` | Legend box corner radius |
| `--parler-chart-legend-line-length` | Line-series legend swatch length |
| `--parler-chart-tick-length` | Axis tick-mark length |
| `--parler-chart-tick-padding` | Gap between a tick mark and its label |

Every value is parsed, validated, and clamped by role before D3 receives it. A malformed public value falls back to the platform-source value where one exists, then the role default; it must not create a zero-size or unreadable chart. Reference-line role remains encoded by label plus the four dash tokens, not color alone.

CSS lengths resolve to pixels against the live chart host before clamping. The binding safe ranges are: data/reference line widths `0.25`–`12px`; axis/grid widths `0.25`–`8px`; line-point radius `0`–`16px`; scatter radius `0.5`–`20px`; outline width `0`–`8px`; bar radius `0`–`24px`; title size `8`–`16px`; legend, tick, and reference-label sizes `6`–`12px`; axis-label size `6`–`14px`; legend swatch size `4`–`12px`; legend swatch radius `0`–`6px`; legend line length `6`–`48px`; and tick length/padding `0`–`16px`. Dash lists contain at most 16 non-negative numeric components, at least one positive component, and clamp each component to `0`–`100`. Numeric font weights clamp to `1`–`1000`; opacities clamp to `0`–`1`.

The following chart choices are structural-fixed because changing them can crop content, change data interpretation, or break responsive containment: the responsive `480+ × 240` logical view box, responsive `width:100%`/automatic height, `preserveAspectRatio`, margin algorithms, responsive five-to-ten tick density, bar band padding, pie inner radius, clip geometry, legend row/column placement, text anchors/rotation, label truncation, categorical/temporal scale algorithms, and the semantic `fill:none` on line paths. The logical width follows the measured chart host without an internal maximum and uses a `480px` floor; the parent layout and public `--parler-message-max-width` token are the only maximum-width owners. This avoids unnecessarily shrinking SVG typography in ordinary narrow embedded panels, lets deliberately wider message layouts use their available width, and retains safe minimum plot and legend geometry. Tick density increases from five through ten as logical width grows so wide charts do not merely stretch the narrow presentation. The title starts at the chart/message content edge. The y-axis gutter is estimated with D3's formatted tick strings and the resolved tick typography, then clamped to `32`–`64px`; pie charts use a symmetric `24px` edge gutter. Thus short ordinary values do not retain the former unconditional `52px` left inset, while unusually long labels cannot consume the plot. Axis tick length/padding and legend line-swatch length are public tokens rather than fixed exceptions. D3 axis-generated font family and size are always overwritten by the chart font/tick tokens after axis creation. This explicit fixed list is the only exception to the rule that every SVG presentation attribute written by D3 has an owning token.

### 7.5 Portable print palette

Print is an output surface, not a clone of the active screen palette. Its built-in defaults form a light, ink-friendly palette, and each value is independently overridable before the detached document is created:

| Public token | Meaning |
|---|---|
| `--parler-print-canvas` | Printed page background |
| `--parler-print-text` | Primary printed text |
| `--parler-print-muted` | Printed metadata and labels |
| `--parler-print-border` | Printed rules and table borders |
| `--parler-print-surface-subtle` | Printed code/table header fill |
| `--parler-print-accent` | Printed links and emphasis |
| `--parler-print-chart-series-1` | Printed chart categorical slot 1 |
| `--parler-print-chart-series-2` | Printed chart categorical slot 2 |
| `--parler-print-chart-series-3` | Printed chart categorical slot 3 |
| `--parler-print-chart-series-4` | Printed chart categorical slot 4 |
| `--parler-print-chart-series-5` | Printed chart categorical slot 5 |
| `--parler-print-chart-series-6` | Printed chart categorical slot 6 |
| `--parler-print-chart-series-7` | Printed chart categorical slot 7 |
| `--parler-print-chart-series-8` | Printed chart categorical slot 8 |
| `--parler-print-chart-series-9` | Printed chart categorical slot 9 |
| `--parler-print-chart-series-10` | Printed chart categorical slot 10 |
| `--parler-print-chart-series-11` | Printed chart categorical slot 11 |
| `--parler-print-chart-series-12` | Printed chart categorical slot 12 |
| `--parler-print-chart-series-13` | Printed chart categorical slot 13 |
| `--parler-print-chart-series-14` | Printed chart categorical slot 14 |
| `--parler-print-chart-series-15` | Printed chart categorical slot 15 |
| `--parler-print-chart-series-16` | Printed chart categorical slot 16 |
| `--parler-print-chart-series-17` | Printed chart categorical slot 17 |
| `--parler-print-chart-series-18` | Printed chart categorical slot 18 |
| `--parler-print-chart-series-19` | Printed chart categorical slot 19 |
| `--parler-print-chart-series-20` | Printed chart categorical slot 20 |
| `--parler-print-chart-series-21` | Printed chart categorical slot 21 |
| `--parler-print-chart-series-22` | Printed chart categorical slot 22 |
| `--parler-print-chart-series-23` | Printed chart categorical slot 23 |
| `--parler-print-chart-series-24` | Printed chart categorical slot 24 |
| `--parler-print-chart-outline` | Printed point/slice outline |
| `--parler-print-chart-reference-danger` | Printed danger reference line |
| `--parler-print-chart-reference-warning` | Printed warning/control reference line |
| `--parler-print-chart-reference-target` | Printed target reference line |

These tokens have no automatic ThingWorx Theme source: a dark Mashup Theme must not accidentally produce light text on white paper. The print builder resolves them from the live Parler host, using the portable defaults when not explicitly overridden, serializes the concrete values into the detached document, and redraws or rewrites every inline SVG palette-owned attribute before insertion. Print chart text uses `--parler-print-text`/`--parler-print-muted`, axes and grid use `--parler-print-border`, and chart geometry uses the validated Section 7.4 presentation values unless print layout itself must clamp them for legibility.

Within printed Markdown, headings and ordinary text use `--parler-print-text`; links use `--parler-print-accent`; code, blockquotes, and table headers combine `--parler-print-surface-subtle`, text, and border; tables and `hr` use the print border; metadata uses print muted. Printed typography resolves the public `--parler-font-family`, `--parler-font-size`, and `--parler-line-height`; image radius resolves `--parler-markdown-image-radius`. Page margins, paper-width clamping, page-break avoidance, and responsive image/SVG containment remain print-structural rules.

The fixed print literals are the named portable-default path allowed by Section 7.6; no unrelated screen literal may leak into it. Copy-to-clipboard remains semantic plain text plus Markdown HTML with no Parler palette, so the destination owns styling. A normal screen screenshot captures the current effective screen palette. Raw in-page SVG serialization uses the screen palette; only the named print operation switches to the print palette.

### 7.6 Token stability rules

- Removing or semantically redefining a public token is a documented breaking UI customization change.
- Adding a token is backward compatible but still requires tests and user-facing documentation.
- Internal DOM classes, `--parler-theme-*`, and `--parler-effective-*` may change without compatibility promises.
- Raw color literals are allowed only in the `parler-dark` preset, standalone fallback defaults, the portable print defaults, emergency stylesheet, test fixtures, and explicitly listed data-visualization fallbacks.
- A token must describe purpose, not current color or DOM placement.
- The registry source, this section, the Section 8.2 source map, resolver defaults, and generated documentation must contain the exact same expanded public-token name set. Range notation is not used for registered names.

## 8. Style dictionary, parts, states, and coverage

### 8.1 Theme package input

The Theme package input is:

```text
parler-ui-widget/input/styles/parlerui/style.dict.json
parler-ui-widget/input/styles/parlerui/style.properties.json
```

`style.properties.json` defines Parler-specific flat values only when no existing global/element theme property expresses the meaning. Prefer references to global ThingWorx properties over duplicating brand settings. Identifiers use the `parler-` prefix.

The style dictionary is the manifest of platform expressions for job 1 (source values) only. The bridge resolves those expressions through `TW.getStyleManager().resolveExpressions(...)` and assigns their concrete results to matching internal `--parler-theme-*` properties on the host. Every `--parler-theme-*` manifest value must be exactly one bare `${expression-name}`. Multi-source fallback belongs in the effective CSS `var()` chain, not in a compound manifest value. The wrapper warns and skips an invalid internal entry, and source/generated-manifest tests must fail before packaging when the checked-in registry and manifest diverge. The dictionary does not emit or simulate job-2 declarations, per-widget Style Properties, state selectors, or platform precedence. `stateHost` is not part of bridge delivery; `ThemeMode` selection remains inside Parler's effective-token cascade.

The bridge rests on these verified platform facts:

1. ThingWorx registers the external dictionary, maps `parlerui` to `parler-ui`, records `$type: 'wc'`, and can generate a correctly identified `ptcs-style-unit` from known platform expressions;
2. identifiers absent from the active Theme are filtered, rather than lost through registry or load-order failure;
3. direct job-1 and job-2 CSS cannot reach Parler because the PTCS aggregator injects into an enlisted component's `shadowRoot`, which the light-DOM Parler component does not have;
4. the platform's static `StyleProps` registry has no Parler entry and no supported extension registration surface, so Composer cannot provide per-widget Parler Style Properties;
5. `TW.getStyleManager().resolveExpressions(...)` is an exported resolver that returns concrete values for known platform expressions without reaching private locals or reproducing Theme semantics;
6. the wrapper's initial render plus Composer/runtime Theme callbacks provide deterministic refresh without timer polling; and
7. Style Theme Custom CSS and Mashup Custom CSS remain independent ordinary light-DOM authoring channels.

The bridge preserves those boundaries. It reads the generated expression manifest in token-to-expression direction and iterates tokens, because a single resolver result is keyed by expression name and may feed many internal tokens. It resolves only through the exported Style Manager, writes only its own internal host source tokens, deletes stale internally written values when a non-null resolved set changes, and delegates existing wrapper lifecycle behavior. A `null` result preserves the prior declarations and schedules no polling retry. Focused tests cover one-to-many expression fan-out and the distinct `null` versus resolved-empty behaviors. The bridge has no static `getWidgetStyle*` methods, dormant direct-delivery branches, or StyleProps registration shim.

### 8.2 Platform-source mapping

The dictionary uses the following SDK theme expressions. This table fixes semantic ownership; the dictionary adds the repetitive typography/state expressions its schema requires.

| Public token or part/state role | Primary SDK expression | Fallback or note |
|---|---|---|
| `--parler-font-family` | `${global-text-body-fontfamily}` | standalone system font stack |
| `--parler-font-size` | `${global-text-body-fontsize}` | `14px` standalone fallback |
| `--parler-line-height` | no platform source | built-in default + public/application override only |
| `--parler-color-canvas` | `${global-color-bg-primary}` | accessible neutral background |
| `--parler-color-surface`, `--parler-color-surface-subtle` | `${global-color-bg-secondary}` | canvas if secondary is absent |
| `--parler-color-surface-raised` | `${global-color-bg-primary}` | surface fallback |
| `--parler-color-text` | `${global-color-text-body}` | accessible neutral foreground |
| `--parler-color-text-muted` | `${global-color-text-label}` | primary text with reviewed fallback |
| `--parler-color-border` | `${global-color-line-border}` | `${global-color-line-divider}` |
| `--parler-color-accent` | `${global-color-core-primary}` | built-in accessible accent |
| `--parler-color-on-accent` | `${e-button-primary-fontcolor}` | reviewed accessible built-in contrast fallback if the target value is unusable |
| `--parler-color-focus` | `${global-focus-border-color}` | accent |
| `--parler-color-danger` | `${global-color-core-danger}` | built-in danger fallback |
| `--parler-color-success` | `${global-color-core-success}` | built-in success fallback |
| `--parler-color-warning` | no platform source | built-in `#8a5a00` fallback + public/application override only |
| `--parler-color-scrim` | no platform source | validated built-in translucent neutral + public/application override only |
| `--parler-color-placeholder` | `${global-color-text-label}` | muted text |
| `--parler-color-scrollbar-thumb` | `${global-color-line-border}` | border |
| `--parler-color-scrollbar-track` | `${global-color-bg-secondary}` | surface |
| `--parler-color-selection-bg` | `${global-color-core-primary}` | accent |
| `--parler-color-selection-text` | `${e-button-primary-fontcolor}` | on-accent fallback |
| `--parler-color-user-message` | `${global-color-bg-secondary}` | subtle surface fallback |
| `--parler-color-on-user-message` | `${global-color-text-body}` | primary text fallback |
| `--parler-color-assistant-message` | `${global-color-bg-secondary}` | surface |
| `--parler-color-on-assistant-message` | `${global-color-text-body}` | primary text |
| `--parler-color-control-bg` | `${global-color-bg-primary}` | canvas |
| `--parler-color-control-text` | `${global-color-text-body}` | primary text |
| `--parler-color-control-border` | `${global-color-line-border}` | border |
| `--parler-color-control-hover` | `${global-color-bg-hover}` | subtle surface |
| `--parler-color-control-disabled` | `${global-color-bg-disabled}` | subtle surface |
| `activity-status[data-activity="working"]` color | `${global-color-text-label}` | muted text |
| `activity-progress[data-activity="working"]` color | `${global-color-core-primary}` | accent |
| activity parts with `[data-activity="rate-controlled"]` | no platform warning source | warning public token + built-in fallback |
| `notice[data-severity="info"]` | `${global-color-core-info}`, `${global-color-bg-secondary}`, `${global-color-line-border}` | platform information role on subtle surface |
| `notice[data-severity="success"]` | `${global-color-core-success}`, `${global-color-bg-secondary}` | success text/border on subtle surface |
| `notice[data-severity="warning"]` | no platform warning source; `${global-color-bg-secondary}` for its surface | warning public token + built-in fallback for text/border |
| `notice[data-severity="danger"]` | `${global-color-core-danger}`, `${global-color-bg-secondary}` | danger text/border on subtle surface |
| `task-state[data-severity="info|success|warning|danger"]` | same semantic state mapping as `notice`; warning has no platform source | task surface remains labeled; color is not the only cue |
| `--parler-content-max-width` | no platform source | built-in default + public/application override only |
| `--parler-message-max-width` | no platform source | built-in default + public/application override only |
| `--parler-activity-max-width` | no platform source | built-in default + public/application override only |
| `--parler-empty-state-media-max-width` | no platform source | contained `320px` default + public/application override only |
| `--parler-empty-state-media-max-height` | no platform source | contained `160px` default + public/application override only |
| `--parler-empty-state-media-opacity` | no platform source | built-in `1` default + public/application override only |
| `--parler-empty-state-media-filter` | no platform source | built-in `none` default + public/application override only |
| `--parler-radius-panel` | `${global-border-radius-300}` | built-in radius fallback |
| `--parler-radius-message` | `${global-border-radius-200}` | built-in radius fallback |
| `--parler-radius-control` | `${global-border-radius-200}` | built-in radius fallback |
| `--parler-shadow-panel` | no platform source | built-in default + public/application override only |
| `--parler-space-unit` | no platform source | built-in default + public/application override only |
| `--parler-markdown-heading` | `${global-color-text-header}` | primary text |
| `--parler-markdown-link` | `${global-color-core-primary}` | accent |
| `--parler-markdown-link-visited` | no platform source | reviewed accessible visited-link fallback + public/application override only |
| `--parler-markdown-code-bg` | `${global-color-bg-secondary}` | subtle surface |
| `--parler-markdown-code-text` | `${global-color-text-body}` | primary text |
| `--parler-markdown-code-border` | `${global-color-line-border}` | border |
| `--parler-markdown-quote-bg` | `${global-color-bg-secondary}` | subtle surface |
| `--parler-markdown-quote-text` | `${global-color-text-body}` | primary text |
| `--parler-markdown-quote-border` | `${global-color-line-border}` | border |
| `--parler-markdown-table-header-bg` | `${global-color-bg-secondary}` | subtle surface |
| `--parler-markdown-table-border` | `${global-color-line-border}` | border |
| `--parler-markdown-hr` | `${global-color-line-divider}` | border |
| `--parler-markdown-image-radius` | `${global-border-radius-200}` | built-in radius fallback |
| `--parler-chart-series-1` | `${e-chart-series-1-color}` | reviewed standalone slot 1 fallback |
| `--parler-chart-series-2` | `${e-chart-series-2-color}` | reviewed standalone slot 2 fallback |
| `--parler-chart-series-3` | `${e-chart-series-3-color}` | reviewed standalone slot 3 fallback |
| `--parler-chart-series-4` | `${e-chart-series-4-color}` | reviewed standalone slot 4 fallback |
| `--parler-chart-series-5` | `${e-chart-series-5-color}` | reviewed standalone slot 5 fallback |
| `--parler-chart-series-6` | `${e-chart-series-6-color}` | reviewed standalone slot 6 fallback |
| `--parler-chart-series-7` | `${e-chart-series-7-color}` | reviewed standalone slot 7 fallback |
| `--parler-chart-series-8` | `${e-chart-series-8-color}` | reviewed standalone slot 8 fallback |
| `--parler-chart-series-9` | `${e-chart-series-9-color}` | reviewed standalone slot 9 fallback |
| `--parler-chart-series-10` | `${e-chart-series-10-color}` | reviewed standalone slot 10 fallback |
| `--parler-chart-series-11` | `${e-chart-series-11-color}` | reviewed standalone slot 11 fallback |
| `--parler-chart-series-12` | `${e-chart-series-12-color}` | reviewed standalone slot 12 fallback |
| `--parler-chart-series-13` | `${e-chart-series-13-color}` | reviewed standalone slot 13 fallback |
| `--parler-chart-series-14` | `${e-chart-series-14-color}` | reviewed standalone slot 14 fallback |
| `--parler-chart-series-15` | `${e-chart-series-15-color}` | reviewed standalone slot 15 fallback |
| `--parler-chart-series-16` | `${e-chart-series-16-color}` | reviewed standalone slot 16 fallback |
| `--parler-chart-series-17` | `${e-chart-series-17-color}` | reviewed standalone slot 17 fallback |
| `--parler-chart-series-18` | `${e-chart-series-18-color}` | reviewed standalone slot 18 fallback |
| `--parler-chart-series-19` | `${e-chart-series-19-color}` | reviewed standalone slot 19 fallback |
| `--parler-chart-series-20` | `${e-chart-series-20-color}` | reviewed standalone slot 20 fallback |
| `--parler-chart-series-21` | `${e-chart-series-21-color}` | reviewed standalone slot 21 fallback |
| `--parler-chart-series-22` | `${e-chart-series-22-color}` | reviewed standalone slot 22 fallback |
| `--parler-chart-series-23` | `${e-chart-series-23-color}` | reviewed standalone slot 23 fallback |
| `--parler-chart-series-24` | `${e-chart-series-24-color}` | reviewed standalone slot 24 fallback |
| `--parler-chart-title-text` | `${global-color-text-header}` | primary text |
| `--parler-chart-legend-text` | `${global-color-text-label}` | muted text |
| `--parler-chart-tick-text` | `${e-chart-tick-label-color}` | muted text |
| `--parler-chart-axis-label-text` | `${global-color-text-label}` | muted text |
| `--parler-chart-axis` | `${e-chart-ruler-color}` | border |
| `--parler-chart-grid` | `${global-color-line-divider}` | border |
| `--parler-chart-point-outline` | `${e-chart-bar-outline-color}` | `${e-chart-pie-slice-stroke-color}`, then canvas fallback |
| `--parler-chart-reference-danger` | `${global-color-core-danger}` | danger |
| `--parler-chart-reference-warning` | no platform source | built-in `#8a5a00` fallback + public/application override; the generic chart-reference identifier is reserved for target/reference |
| `--parler-chart-reference-target` | `${e-chart-reference-line-color}` | reviewed neutral target/reference fallback; nearest platform chart-reference role |
| `--parler-chart-font-family` | `${global-text-body-fontfamily}` | body font family |
| `--parler-chart-axis-line-width` | `${global-line-border-thickness}` | validated built-in fallback |
| `--parler-chart-line-width`, `--parler-chart-line-point-radius`, `--parler-chart-scatter-point-radius`, `--parler-chart-outline-width`, `--parler-chart-bar-radius` | no platform source | validated/clamped built-in defaults + public/application override only |
| `--parler-chart-grid-line-width`, `--parler-chart-grid-opacity` | no platform source | validated/clamped built-in defaults + public/application override only |
| `--parler-chart-reference-line-width`, `--parler-chart-target-line-width`, `--parler-chart-reference-opacity` | no platform source | validated/clamped built-in defaults + public/application override only |
| `--parler-chart-reference-limit-dash`, `--parler-chart-reference-control-dash`, `--parler-chart-reference-target-dash`, `--parler-chart-reference-warning-dash` | no platform source | validated dash-pattern defaults + public/application override only |
| `--parler-chart-title-font-size`, `--parler-chart-title-font-weight`, `--parler-chart-legend-font-size`, `--parler-chart-tick-font-size`, `--parler-chart-axis-label-font-size`, `--parler-chart-reference-label-font-size` | no platform source | validated/clamped SVG typography defaults + public/application override only |
| `--parler-chart-legend-swatch-size`, `--parler-chart-legend-swatch-radius`, `--parler-chart-legend-line-length` | no platform source | validated/clamped built-in defaults + public/application override only |
| `--parler-chart-tick-length`, `--parler-chart-tick-padding` | no platform source | validated/clamped built-in defaults + public/application override only |
| `--parler-print-canvas`, `--parler-print-text`, `--parler-print-muted`, `--parler-print-border`, `--parler-print-surface-subtle`, `--parler-print-accent` | no platform source | portable light print defaults + explicit public/application override only |
| `--parler-print-chart-series-1`, `--parler-print-chart-series-2`, `--parler-print-chart-series-3`, `--parler-print-chart-series-4`, `--parler-print-chart-series-5`, `--parler-print-chart-series-6`, `--parler-print-chart-series-7`, `--parler-print-chart-series-8`, `--parler-print-chart-series-9`, `--parler-print-chart-series-10`, `--parler-print-chart-series-11`, `--parler-print-chart-series-12`, `--parler-print-chart-series-13`, `--parler-print-chart-series-14`, `--parler-print-chart-series-15`, `--parler-print-chart-series-16`, `--parler-print-chart-series-17`, `--parler-print-chart-series-18`, `--parler-print-chart-series-19`, `--parler-print-chart-series-20`, `--parler-print-chart-series-21`, `--parler-print-chart-series-22`, `--parler-print-chart-series-23`, `--parler-print-chart-series-24` | no platform source | portable print categorical palette + explicit public/application override only |
| `--parler-print-chart-outline`, `--parler-print-chart-reference-danger`, `--parler-print-chart-reference-warning`, `--parler-print-chart-reference-target` | no platform source | portable print chart defaults + explicit public/application override only |

In `mashup` mode, a user message is a large content surface rather than a
primary action. Its fill therefore resolves from
`${global-color-bg-secondary}` and its foreground from
`${global-color-text-body}`. `${global-color-core-primary}` remains visible
only as the existing one-pixel bubble border. This prevents a saturated brand
primary from filling every prompt while preserving Theme identity. The public
`--parler-color-user-message` and `--parler-color-on-user-message` tokens keep
their normal highest-priority override behavior; an application may style the
supported `user-message` part when it also needs a different border.

The exact expressions above are present in the ThingWorx Web Component SDK `ptcs-base-theme` theme. An identifier that is unavailable on the deployed platform falls back at the same semantic role rather than being replaced by an unrelated color.

The inspected SDK includes historical chart theme values that may be gradients. A D3 series slot accepts only a value valid for the SVG color role. The resolver rejects gradients or otherwise unusable values for line/point/slice presentation attributes and uses that slot's standalone fallback. It must not pass a CSS gradient string to an SVG `stroke` and silently render nothing.

Every public token in Section 7 has a row in this mapping table, either with a platform source or the explicit `no platform source` status. A no-source role uses only its built-in effective fallback and an optional public/application override; it does not create a flat Theme property. Each semantic role binds to the nearest existing platform semantic identifier. A generic accent is used only when the platform has no closer role. The deployed ThingWorx 10.1.2 property table and resolver do not register an extension-supplied flat entry such as `parler-color-warning` as a Style Theme property (expression resolution returns the undeclared sentinel and the bridge stays empty), so Parler packages no flat Theme property. Every warning role therefore resolves public token -> built-in fallback, with no copied default, shim, unrelated platform color, or `--parler-theme-color-warning` declaration.

### 8.3 Stable light-DOM parts

These part names are the supported semantic selector surface. Parts are deliberately granular so no customer-visible surface is reachable only through an internal class:

| Part | Owned surface | Primary style properties |
|---|---|---|
| `shell` | Inner conversation column | color, font |
| `header` | Optional title region | color, background, border |
| `thread` | Scroll viewport | background, focus outline |
| `thread-panel` | Conversation panel | background, border, radius, shadow |
| `empty-state` | Empty conversation branding/state root | background, color, font |
| `empty-state-media` | Configured ThingWorx Media image | maximum size, opacity, filter |
| `empty-state-label` | Built-in `Parler` fallback label | color, font, opacity, letter spacing, text transform, text shadow |
| `message-meta` | Actor/time/metadata label | color, font |
| `user-message` | User bubble | background, color, accent border, radius |
| `user-message-actions` | User copy/status controls | color, background, focus |
| `host-context-disclosure` | User-side context disclosure | background, color, border, focus |
| `host-context-content` | Expanded host-context content | background, color, border, font |
| `assistant-message` | Assistant bubble | background, color, border, radius |
| `markdown-content` | Markdown output root | color, typography; descendant element contract in Section 7.2 |
| `message-actions` | Assistant action bar | color, background |
| `message-action` | Individual copy/print/feedback/info/cutoff control | color, background, border, focus |
| `notice` | Operational info/success/warning/danger banner | background, color, border |
| `activity-status` | Active-turn text/status wrapper | color, font, maximum width |
| `activity-progress` | Active-turn animated pulse/track | color, background, maximum width |
| `task-state` | Task progress panel | background, color, border |
| `insight-hint` | Further-insight metadata hint | color, font |
| `data-table-disclosure` | Structured-table disclosure control | background, color, border, focus |
| `data-table` | Structured table root | background, color, border |
| `data-table-header` | Structured table header cells | background, color, border, font |
| `data-table-cell` | Structured table body cells | background, color, border |
| `data-table-footer` | Structured table summary/export row | color, font |
| `chart` | Chart root and source for SVG palette | background, color |
| `connection-status` | Transport status line | color |
| `approval-panel` | Approval request | background, color, border |
| `approval-input` | Rejection comment input | background, color, border, focus |
| `approval-primary-action` | Approve control | background, color, border, focus |
| `approval-secondary-action` | Cancel control | background, color, border, focus |
| `approval-danger-action` | Reject control | background, color, border, focus |
| `composer` | Prompt footer | background, border |
| `composer-input` | Prompt input | background, color, border, focus |
| `primary-action` | Send primary action | background, color, border, focus |
| `stop-action` | Stop/destructive action | background, color, border, focus |
| `floating-action` | Jump-to-latest control | background, color, border, shadow, focus |
| `overlay-scrim` | Modal/popover scrim | background |
| `popover` | Turn-details dialog | background, color, border, shadow |
| `popover-close` | Turn-details close control | background, color, border, focus |

Parts may appear more than once. A part identifies a semantic role, not a unique DOM id. Internal child elements inherit from the nearest part unless they need their own supported role.

The four pre-capability `part` attributes have this explicit disposition; implementation must not retain legacy aliases or duplicate part names:

| Existing attribute | Disposition |
|---|---|
| `data-table` | retained as the supported `data-table` part |
| `task-state-panel` | renamed to the supported `task-state` part |
| `insight-envelope-hint` | renamed to the supported `insight-hint` part |
| `empty-brand` | renamed to the supported `empty-state` part |

These four attributes were undocumented and are not a compatibility surface. Focused markup tests must assert the supported set and the absence of retired aliases.

Because the component is light DOM, application examples use:

```css
.acme-parler-alert parler-ui [part="connection-status"] {
  font-weight: 700;
}
```

They must not document `parler-ui::part(...)` or selectors that depend on descendant depth.

### 8.4 Stable states

Use native states where they exist: `:hover`, `:active`, `:focus-visible`, and `:disabled`/`[disabled]`. Add semantic attributes only where a theme needs a state that native CSS cannot express:

| State owner | Attribute/value |
|---|---|
| Host | `theme-mode="mashup|parler-dark"` |
| Host/status | `connection-status="disconnected|connecting|connected"` |
| `activity-status` / `activity-progress` | `data-activity="working|rate-controlled"` |
| `notice` / `task-state` | `data-severity="info|success|warning|danger"` |
| `message-action` | `data-action="copy|print|feedback-up|feedback-down|info|cutoff|dismiss"` |
| Selectable `message-action` | `aria-pressed="true|false"` where selection semantics apply |
| Disclosure controls | `aria-expanded="true|false"` |

Do not duplicate state in a new data attribute when a correct native/ARIA attribute already exists. State attributes must reflect actual domain/UI state from the reducer; styling work must not invent a second state machine.

The host reflects the `connectionStatus` property as `connection-status`. The component test asserts `disconnected -> connecting -> connected` internal transitions so dictionary/Custom CSS selectors retain a single DOM-visible source of truth.

The notice mapping is binding: history loading is `info`; successful copy feedback is `success`; copy failure, gateway invocation failure, and chat/session errors are `danger`; print-popup blocking is `warning`. An assistant action status and its related banner both use `part="notice"`; inline versus block layout is structural.

Task-state severity uses the exact v1b task-state vocabulary and the precedence `danger > warning > success > info`. Turn `failed`, any item `failed|cancelled|expired`, or a positive summary `failed` count produces `danger`. Turn or item `blocked-by-approval`, or a positive summary `blocked` count, produces `warning` unless danger applies. Turn `completed` or satisfied items produce `success` when no danger/warning applies. Turn `idle|executing` and item `pending|in-progress|not-applicable` produce `info` otherwise. Rate control is not a task-state status; it remains the separate `data-activity="rate-controlled"` warning treatment. The visible text, ARIA role, icons where present, and task status remain the non-color cues.

Generic ThingWorx State Definition formatting remains out of scope because Parler does not expose a scalar data field whose formatting should control the whole chat widget.

### 8.5 Customer-visible surface coverage

The inventory is closed by the following table. A visible surface with no row is a design defect, not an implementation detail.

| Visible surface | Supported owner | Required disposition |
|---|---|---|
| Host, header, scroll viewport, conversation panel | `shell`, `header`, `thread`, `thread-panel` | foundation/surface tokens plus supported part selectors |
| Empty-state root, configured Media, built-in fallback label | `empty-state`, `empty-state-media`, `empty-state-label` | surface/text tokens, four empty-state Media tokens, and supported part selectors; Media selection stays a Widget property |
| Selected text anywhere inside Parler | host `::selection` | selection background/text tokens |
| Actor metadata, user bubble, prompt-copy action/status | `message-meta`, `user-message`, `user-message-actions`, `message-action`, `notice` | message/control/status tokens and native interaction states |
| Host-context disclosure, raw context, unavailable text, context copy | `host-context-disclosure`, `host-context-content`, `message-action`, `notice` | control/surface/text tokens; expanded state is `aria-expanded` |
| Assistant bubble and every Markdown output kind | `assistant-message`, `markdown-content` | message tokens plus the complete Section 7.2 conversion inventory |
| Copy, print, feedback, info, cutoff actions and their feedback | `message-actions`, `message-action`, `notice` | control tokens, `data-action`, native/ARIA states, severity mapping |
| Ordinary/rate-controlled activity and task status | `activity-status`, `activity-progress`, `task-state` | independent activity state, warning/success/danger tokens, safe width |
| Insight hint | `insight-hint` | muted text token plus part typography |
| Data-table disclosure, header/body cells, footer/export link | `data-table-disclosure`, `data-table`, `data-table-header`, `data-table-cell`, `data-table-footer` | table/surface/border and accent tokens plus disclosure state |
| Line, scatter, bar, pie, legend, axes, grid, labels, references | `chart` | all Section 7.3/7.4 roles; Section 10 assignment and fixed-geometry matrix |
| History loading, gateway/chat error, copy result, print blocking | `notice` | the binding Section 8.4 severity mapping |
| Connection status | `connection-status` plus reflected host state | state color, text, and non-color label |
| Approval summary, comment field, approve/cancel/reject controls | approval parts | surface/control/status tokens and native focus/disabled states |
| Composer, textarea, send, stop | composer/action parts | control/accent/danger tokens and native focus/disabled states |
| Placeholder text in composer and approval inputs | `composer-input`, `approval-input` `::placeholder` | placeholder token; opacity is structural |
| Thread scrollbar | `thread` | scrollbar thumb/track tokens; cross-browser width remains structural `thin` |
| Jump-to-latest | `floating-action` | raised surface/control/focus/shadow tokens |
| Turn-details dialog, close control, overlay | `popover`, `popover-close`, `overlay-scrim` | raised-surface/control tokens and `--parler-color-scrim` |
| Printed answer, Markdown, tables, metadata, cloned charts | Section 7.5 print tokens | resolved portable print palette; never implicit screen-theme inheritance |
| Clipboard plain text/HTML | destination-owned | no Parler visual contract is serialized |
| Emergency stylesheet | Section 11 degraded path | named literal allowlist; outside Theme acceptance |

This table covers both always-present and conditional markup. Focused tests must render each conditional row at least once and assert its part/state/token ownership. Decorative glyph geometry, accessibility-only text, and layout-only wrappers may inherit from their nearest listed owner and do not require separate parts.

## 9. CSS ownership and precedence

Parler CSS owns structure and safe defaults. ThingWorx owns theme values. Applications own explicit public-token and Custom CSS overrides.

Implementation rules:

- Structural declarations such as display, flex behavior, overflow, minimum size, and accessibility-only positioning stay in `parler-ui.css` and are not theme properties.
- The active-turn indicator always enforces `box-sizing: border-box`, `min-width: 0`, and `max-width: min(100%, var(--parler-activity-max-width, 100%))`. Exceeding its containing assistant surface is a component defect, not a Theme choice.
- Visual declarations consume effective tokens or inherit from a semantic part.
- Internal selectors must not use `!important` for ordinary visual styling.
- Focus-visible treatment must remain visible even when an application supplies weak colors; forced-colors mode may use system colors.
- Public-token overrides should be scoped through `CustomClass`, not globally on every `parler-ui` unless the application intentionally wants that result.
- Theme Custom CSS and Mashup Custom CSS are allowed, but the documented stable targets are only the host, public tokens, parts, and state attributes.
- The emergency fallback stylesheet may use literals and `!important` because it runs only after normal stylesheet loading fails. It must not be mistaken for the ordinary Theme path.

`CustomClass` becomes both a binding source and binding target so a Mashup may select an application-owned style scope dynamically. Multiple classes remain space-delimited per ThingWorx behavior. Parler does not parse or interpret those class names.

## 10. D3 chart integration

`chart-draw.js` must stop owning fixed presentation values. A focused resolver reads all Section 7.3/7.4 effective tokens with `getComputedStyle()` from the closest `<parler-ui>` host (or the chart host when used standalone), validates colors, lengths, numbers, opacity, font weight, and dash patterns by role, clamps numeric values to safe documented ranges, and returns a concrete render object to the drawing functions.

The drawing layer receives concrete values because SVG presentation attributes, print cloning, screenshots, and serialization are more predictable with resolved values than unresolved `var(...)` expressions. The object has this required shape:

```text
palette.series[24]
palette.text.{title,legend,tick,axisLabel}
palette.axis
palette.grid
palette.pointOutline
palette.reference.{danger,warning,target}

style.fontFamily
style.lineWidth
style.linePointRadius
style.scatterPointRadius
style.outlineWidth
style.barRadius
style.axisLineWidth
style.gridLineWidth
style.gridOpacity
style.reference.{lineWidth,targetLineWidth,opacity,limitDash,controlDash,targetDash,warningDash}
style.type.{titleSize,titleWeight,legendSize,tickSize,axisLabelSize,referenceLabelSize}
style.legend.{swatchSize,swatchRadius,lineLength}
style.axis.{tickLength,tickPadding}
```

All chart kinds—line, scatter, bar, and pie—must use the ordered assignment function in Section 7.3. Legend symbols, title, labels, axes, data marks, bar/point/slice outlines, and reference lines use the render object; no secondary hard-coded chart palette or presentation constant remains outside the explicit structural-fixed list.

Line, scatter, and bar charts render horizontal grid lines at the y-axis tick values behind data marks. Grid strokes explicitly use `palette.grid`, `style.gridLineWidth`, and `style.gridOpacity`. D3 axis domain and tick lines are explicitly recolored with `palette.axis` and sized with `style.axisLineWidth`; tick geometry uses `style.axis.tickLength`/`tickPadding`. After D3 creates an axis, the renderer overwrites D3's default font family and size with the chart font/tick tokens. Title, legend, tick, and footer-axis labels each use their distinct `palette.text` role and documented font role. Line-legend geometry uses `style.legend.lineLength`; point/box geometry uses the other legend roles. Pie has no axes/grid. Reference roles map as follows: `usl|lsl|limit` -> danger + limit dash; `ucl|lcl` -> warning + control dash; `warning` -> warning + warning dash; `target` -> target + target dash. Reference labels use the same role color and the reference-label size.

Every palette-owned SVG node receives internal `data-parler-palette-role` and, for categorical marks, `data-parler-series-slot` metadata. The role value is a whitespace-separated internal role list when one node owns more than one palette attribute; for example, a point, bar, pie slice, or outlined legend swatch carries `series point-outline` so print can rewrite both fill and stroke. These attributes are implementation markers, not supported application selectors. The print path resolves Section 7.5 values, clones the SVG, and rewrites all marked colors before serializing the detached document; a test fails if a palette-owned screen value survives. Presentation widths, radii, opacity, fonts, and dashes are also concrete inline values and are preserved after validation unless the print builder applies a documented legibility clamp.

A chart stores a signature of the complete resolved render object, including palette and presentation values. It redraws when:

- chart data or geometry changes;
- `ThemeMode` changes;
- the bridge rewrites the host `style` after a platform Theme refresh (the observed refresh signal);
- a public token on the host changes through an observed class/style update.

There is no timer polling; the chart signature check keeps redraws idempotent.

## 11. Standalone and failure behavior

Outside ThingWorx, `mashup` mode resolves to built-in accessible neutral defaults. It does not fail or become transparent because no `--parler-theme-*` values exist.

If `parler-ui.css` cannot load, the existing emergency stylesheet renders a minimal dark, readable shell. That failure path:

- does not need to honor `ThemeMode`;
- must retain keyboard focus visibility and disabled state;
- must not depend on style-dictionary output;
- is excluded from pixel-level Theme acceptance.

The `parler-dark` preset may reuse the emergency palette values, but the two paths remain conceptually separate: one is a chosen preset, the other is degraded operation.

An empty, unavailable, denied, or undecodable `EmptyStateMedia` value is a content fallback, not a stylesheet failure. It renders the built-in `Parler` label and does not affect the rest of the widget.

## 12. Accessibility requirements

- Built-in `mashup` fallback and `parler-dark` values target WCAG AA contrast for ordinary text and essential controls.
- Theme authors remain responsible for contrast when they override tokens or part rules, but Parler must not erase platform focus treatment.
- Error, warning, connected, selection, rate-control, approval, and chart reference states use text, iconography, pattern, or dash in addition to color.
- `:focus-visible` is defined for every interactive semantic part and tested in both modes.
- Forced-colors behavior uses system colors for borders, focus, and controls where ordinary tokens would disappear.
- Disabled state remains distinguishable without relying only on opacity.
- Empty-state Media and its fallback label are decorative and expose no duplicate announcement; the image uses `alt=""` and the container remains hidden from the accessibility tree.
- Twenty-four-series charts should be assessed for distinguishability, but Theme capability does not claim to make arbitrary 24-color user palettes color-blind safe automatically.

## 13. Composer authoring experience

The intended order for a Mashup author is:

1. apply or bind a Mashup Style Theme;
2. leave `ThemeMode=mashup`;
3. set documented public tokens through a scoped `CustomClass` for instance-specific semantic overrides;
4. use supported `[part]`/state rules when a token does not express the intended role;
5. use selectors outside the supported semantic surface only as an explicitly upgrade-sensitive escape hatch.

Parler follows the active Mashup Theme through resolved internal tokens. Composer does not expose Parler-specific per-widget Style Properties, and documentation or help text must not imply otherwise.

There are no dedicated color/font widget properties; `srcCssProperty` is not used to create a parallel Theme editor.

`EmptyStateMedia` is a content-selection property defined in Section 13.1. `ProgressPresentation`, `ProgressLabel`, and `RateControlLabel` are content-projection properties defined in Section 14. None is a Theme property or Style Property; Theme owns the presentation of the surfaces they select.

`docs/ui/theme-api.md` includes examples for:

- following a light and a dark Mashup theme;
- changing one instance's message/accent styling;
- dynamically binding `ThemeMode`;
- scoping public-token overrides with `CustomClass`;
- styling a supported `[part]` and semantic state;
- overriding a chart series token;
- customizing Markdown code/link/table treatment through tokens and the `markdown-content` part;
- overriding the portable print palette without coupling it to a dark screen Theme;
- selecting an SVG Media Entity for the empty state and styling its size/filter;
- stating that Parler-specific per-widget Style Properties are unavailable and showing the scoped token/part alternative;
- identifying unsupported selectors.

### 13.1 Adjacent empty-state Media property

`parler-ui-widget/input/widgets/parler-ui.json` adds one content-selection property alongside the Theme authoring surface:

| Property | Base type | Binding | Localizable | Default | Web-component property |
|---|---|---|---|---|---|
| `EmptyStateMedia` | `IMAGELINK` | target | no | empty | `emptyStateMedia` |

The names describe different layers and are not alternatives:

| Term | Meaning in this design |
|---|---|
| Media Entity | The ThingWorx server entity that stores the uploaded image/SVG and is selected by the Mashup author |
| `IMAGELINK` | The Widget property's ThingWorx base type; its JavaScript value is a string image reference, commonly `/Thingworx/MediaEntities/<name>` for a Media selection |
| `TW.convertImageLink(value)` | The platform runtime normalizer used by the checked-in SDK to turn an `IMAGELINK` representation into the URL passed to an image renderer |
| `<img src>` | Parler's final browser rendering boundary |

Therefore the binding contract is: **select a Media Entity in Composer, declare `EmptyStateMedia` as `IMAGELINK`, normalize it with the platform image-link function when available, and render the result through `<img>`.** The design does not use the binary `IMAGE` base type. SVG is recommended for a scalable brand mark, while any browser-decodable image referenced by the `IMAGELINK` may render.

Composer or widget help may label the property editor as **Media Entity** because that is what the author selects. That label is not the literal `baseType` value for `parler-ui.json`: the metadata must say `"baseType": "IMAGELINK"`; it must not say `MEDIA ENTITY`, `IMAGE`, or `STRING`.

For each non-empty trimmed property value, resolution is deterministic:

1. when `globalThis.TW?.convertImageLink` is a function, call it with the raw `IMAGELINK` value, matching the checked-in SDK's `IMAGELINK` renderer;
2. accept a non-empty string result as the `<img src>` value;
3. when the platform function is absent, use the trimmed raw value so standalone direct/relative image URLs continue to work;
4. when conversion throws or returns no usable string, enter the normal failed/fallback state and render `Parler`.

Parler does not fetch and parse the resource, insert SVG/XML/HTML into light DOM, use `unsafeSVG`, or execute Media content. `TW.convertImageLink` is the sanctioned platform conversion; an implementation must not construct `/Thingworx/MediaEntities/...` URLs itself or add a Media REST adapter.

The behavior contract is:

- the Media or fallback label appears only under the existing empty-state condition: no chat rows and history is not loading;
- an empty or whitespace-only value renders the built-in `Parler` label;
- a non-empty value creates the configured image with `alt=""` because this branding is decorative, but keeps the image visually hidden and shows `Parler` until the browser reports a successful load;
- a successful load atomically replaces the fallback label with the image; a load failure keeps `Parler` and never exposes a broken-image indicator;
- changing `EmptyStateMedia` clears the prior loaded/failure state and attempts the new value immediately, without reloading the Mashup; load/error events from an older value are ignored;
- rows arriving, history loading, reset, and history hydration keep their existing ownership of whether the empty state is visible;
- the property selects content only. `empty-state`, `empty-state-media`, `empty-state-label`, and the four empty-state Media tokens own presentation.

The bundled SDK utility emits `EmptyStateMedia` as `IMAGELINK`, and Composer provides its normal Media Entity selection experience.

## 14. Adjacent Widget progress presentation

This section is intentionally outside the Theme token cascade. It is documented here because it customizes how the newly themed active-turn surface presents already-received state.

### 14.1 Composer and component contract

`parler-ui-widget/input/widgets/parler-ui.json` adds:

| Property | Base type | Binding | Localizable | Default | Accepted/meaning |
|---|---|---|---|---|---|
| `ProgressPresentation` | `STRING` | target | no | `detailed` | `detailed` or `compact` |
| `ProgressLabel` | `STRING` | target | `isLocalizable: true` | `Thinking...` | compact ordinary-working label |
| `RateControlLabel` | `STRING` | target | `isLocalizable: true` | `Waiting for capacity...` | compact rate-control label |

The corresponding component properties are `progressPresentation`, `progressLabel`, and `rateControlLabel`. `ProgressPresentation` missing/empty/invalid values normalize to `detailed`. Empty or whitespace-only labels use their defaults so a busy turn never loses its accessible status text.

### 14.2 Projection rules

The reducer and history/wire adapters continue receiving and retaining the same sanitized `assistant.activity`, `task.state`, and `rate_control.status` data in both modes. Only View projection changes.

`detailed`:

- render the current `assistant.activity` text, falling back to the existing ordinary working label;
- render active task-state detail and terminal attention-required detail according to the unified rules below;
- render rate-control with the rate-controlled Theme state.

`compact`:

- replace ordinary `assistant.activity` text with `ProgressLabel`;
- suppress routine active task-state item/title/summary detail while retaining it in UI state;
- when `rateControlWaiting` is true, display `RateControlLabel`, not `ProgressLabel`, and use the rate-controlled Theme state;
- continue rendering attention-required task states in detail: turn `failed|blocked-by-approval`; item `failed|blocked-by-approval|cancelled|expired`; or a summary with positive `failed|blocked` counts;
- never suppress the approval panel, connection/error status, user-action requirement, or final assistant result.

There is no `hidden` mode in this capability. A busy assistant turn always has a visible `role="status"` surface. The compact label is announced when it or the semantic activity state changes; rerenders with the same label must not repeatedly flood the `aria-live` region.

The implementation must extract one shared attention-required predicate from `taskStatePanelVisibility.mjs` rather than duplicate its status list in the renderer. “With task state” means an assistant row whose `taskState` is a non-null object and whose `status` is a string that remains non-empty after trimming; a missing/malformed snapshot or empty status never renders, including on the active turn. After that validity guard, the exact semantics supersede the current legacy root-status list:

1. an active assistant row with task state remains visible;
2. an inactive task state requires attention when the v1b turn status is `failed|blocked-by-approval`, any item status is `failed|blocked-by-approval|cancelled|expired`, or the summary has a positive `failed|blocked` count;
3. otherwise the inactive task state does not require attention.

The predicate is used by detailed-mode terminal-panel visibility and compact-mode preservation. Task-state severity must consume the same centralized status classification rather than maintain a second literal list. The legacy root checks `expired|cancelled|rejected` are removed because those values are not valid v1b turn statuses. Item-aware retention deliberately extends detailed-mode visibility: for example, a completed turn with a cancelled or expired item and zero failed/blocked summary counts remains visible. This is a tested View behavior change. Reducer state and history/wire hydration remain byte-for-byte unaffected; “retention” here means rendered terminal-panel retention, not stored snapshot retention.

### 14.3 Relationship to Theme

Both presentation modes render the same `activity-status` and `activity-progress` parts and the same `working|rate-controlled` attributes. Theme controls their typography, color, pulse, motion preference, and `--parler-activity-max-width`; it does not select the text or mode.

This section, the shared attention-required predicate and task severity change
View projection only: reducer, wire, and history state remain unchanged.

## 15. Verification

### 15.1 Automated local checks

```text
cd parler-ui
npm test
npm run build:tw

cd ../parler-ui-widget
npm run sync
node ../twx-wc-sdk-utility/bin/cli.js
```

Focused tests verify:

- `ThemeMode` default, accepted values, reflection, and invalid-value fallback;
- `ProgressPresentation` normalization, detailed/compact projection, label fallback, and stable `aria-live` behavior;
- compact mode suppresses only routine detail and preserves rate-control, approval, error, and every attention-required task state;
- the shared attention predicate removes legacy invalid root-only triggers, adds v1b item-aware detailed/compact retention, and leaves reducer/hydration state unchanged;
- `EmptyStateMedia` empty, whitespace, Media-entity reference, direct/relative URL, present/absent/throwing `TW.convertImageLink`, loading, valid image, failed image, stale load/error event, cached image, and runtime-change cases; no raw SVG/XML/HTML insertion; empty-state visibility remains row/history-owned;
- every documented public token appears in the registry and has an effective fallback;
- the Section 7 registry contains exactly 151 expanded public-token names, with no range aliases, and exactly matches the Section 8.2 source-disposition set and `docs/ui/theme-api.md` token table;
- every registry default and value constraint exactly matches the customer specification, including platform-derived versus fixed fallback behavior;
- every style-dictionary token reference exists in `style.properties.json` or is a documented global Theme reference;
- every supported part appears in rendered markup or an explicitly conditional fixture, and every Section 8.5 surface has an owner assertion;
- generated `parlerui.config.js` carries the expected non-empty expression-manifest structures, and the generated manifest loads the bridge-bearing wrappers with the correct Composer/runtime flags;
- only supported platform expression resolution writes internal `--parler-theme-*` values, and no job-2/Style Property claim or dormant direct-delivery branch remains;
- generated widget metadata carries `EmptyStateMedia` as a binding-target `IMAGELINK` with an empty default, not binary `IMAGE` or plain `STRING`;
- ordinary production CSS, auxiliary action/print CSS, and D3 contain no unallowlisted visual literals; portable print defaults and emergency defaults are reported separately;
- token-level public > bridged platform-source > built-in resolution, plus scoped-CSS behavior;
- no job-2 declarations or per-widget Parler Style Properties are exposed or simulated;
- activity text/pulse use distinct ordinary and rate-controlled mappings, and activity width can never exceed its container;
- `parler-dark` uses the same render path as `mashup`;
- every listed Markdown output kind consumes its documented tokens and remains usable in light/dark fixtures, including fenced-code horizontal overflow and responsive images;
- operational notices carry the binding severity, overlay scrim consumes its token, and popover/floating/disclosure surfaces expose their documented parts and states;
- placeholders, thread scrollbar colors, and selected text consume their dedicated tokens across every owning surface;
- every D3 SVG presentation attribute is traced to the render object or the exact structural-fixed allowlist;
- line/scatter/bar series and filtered positive pie slices use ordered modulo-24 assignment; legend/mark slots agree and >24 wrapping remains labeled;
- line/scatter/bar draw y-grid lines; grid, axis domains, ticks, text, marks, outlines, legends, and reference roles use their documented values;
- D3 axis-generated font defaults are overwritten, and tick length/padding plus legend line length consume their tokens;
- complete render-object signature changes trigger exactly the necessary redraw;
- print resolution ignores screen Theme defaults, honors explicit print overrides, emits portable values into the detached document, and leaves no screen palette-owned SVG attribute in a cloned print chart;
- clipboard HTML contains no Parler palette while raw screen SVG serialization retains the current screen palette.

Generated bundles and `tmp/` output are verification artifacts, not hand-edited sources.

### 15.2 Manual ThingWorx checks

Live acceptance in Composer and Mashup Runtime covers: Base, PTC Convergence, a custom light and a custom dark Theme on every Section 8.5 surface; the bridge writing only internal `--parler-theme-*` values; public-token precedence; run-time Theme and `ThemeMode` switching (including chart redraw); activity width and states; `ProgressPresentation`; `EmptyStateMedia` with Media Entities and direct URLs; `CustomClass` scoping; focus, disabled, forced-colors and narrow layouts; placeholder/scrollbar/selection tokens; printing from light and dark screen Themes; more than 24 series/slices; and the emergency shell when the main CSS asset is unavailable.

### 15.3 Browser diagnostic snippets

The following DevTools snippets are diagnostic only. Run them in the Mashup
runtime; if it contains multiple Parler instances, replace each
`document.querySelector("parler-ui")` lookup with a selector for the intended
instance. Do not add the snippets to product code.

#### Bridge application and refresh

After a system reset or extension import, reload every long-lived Runtime tab
before attributing an empty bridge result to product code; a document loaded
before the import keeps the old CombinedExtensions resource until reload.

Run this capture before applying any inline public-token override. The initial
result must contain non-empty internal values for the available platform
expressions and no `publicInlineDeclarations`. Compare the values and visible
surface colors with the active Theme. Then switch to a deliberately distinct
Style Theme at run time and call `finish()`. At least the roles changed by that
Theme must appear in `changedThemeTokens`; a fallback-only render with no
console error is not positive bridge evidence.

```js
{
  const tokenNames = [
    "--parler-theme-font-family",
    "--parler-theme-font-size",
    "--parler-theme-color-canvas",
    "--parler-theme-color-surface",
    "--parler-theme-color-surface-raised",
    "--parler-theme-color-surface-subtle",
    "--parler-theme-color-text",
    "--parler-theme-color-text-muted",
    "--parler-theme-color-border",
    "--parler-theme-color-accent",
    "--parler-theme-color-on-accent",
    "--parler-theme-color-focus",
    "--parler-theme-color-danger",
    "--parler-theme-color-success",
    "--parler-theme-color-control-bg",
    "--parler-theme-color-control-text",
    "--parler-theme-color-control-border",
    "--parler-theme-color-control-hover",
    "--parler-theme-color-control-disabled",
    "--parler-theme-color-placeholder",
    "--parler-theme-color-scrollbar-thumb",
    "--parler-theme-color-scrollbar-track",
    "--parler-theme-color-selection-bg",
    "--parler-theme-color-selection-text",
    "--parler-theme-color-user-message",
    "--parler-theme-color-on-user-message",
    "--parler-theme-color-assistant-message",
    "--parler-theme-color-on-assistant-message",
    "--parler-theme-radius-panel",
    "--parler-theme-radius-message",
    "--parler-theme-radius-control",
    "--parler-theme-markdown-heading",
    "--parler-theme-markdown-link",
    "--parler-theme-markdown-code-bg",
    "--parler-theme-markdown-code-text",
    "--parler-theme-markdown-code-border",
    "--parler-theme-markdown-quote-bg",
    "--parler-theme-markdown-quote-text",
    "--parler-theme-markdown-quote-border",
    "--parler-theme-markdown-table-header-bg",
    "--parler-theme-markdown-table-border",
    "--parler-theme-markdown-hr",
    "--parler-theme-markdown-image-radius",
    "--parler-theme-chart-series-1",
    "--parler-theme-chart-series-2",
    "--parler-theme-chart-series-3",
    "--parler-theme-chart-series-4",
    "--parler-theme-chart-series-5",
    "--parler-theme-chart-series-6",
    "--parler-theme-chart-series-7",
    "--parler-theme-chart-series-8",
    "--parler-theme-chart-series-9",
    "--parler-theme-chart-series-10",
    "--parler-theme-chart-series-11",
    "--parler-theme-chart-series-12",
    "--parler-theme-chart-series-13",
    "--parler-theme-chart-series-14",
    "--parler-theme-chart-series-15",
    "--parler-theme-chart-series-16",
    "--parler-theme-chart-series-17",
    "--parler-theme-chart-series-18",
    "--parler-theme-chart-series-19",
    "--parler-theme-chart-series-20",
    "--parler-theme-chart-series-21",
    "--parler-theme-chart-series-22",
    "--parler-theme-chart-series-23",
    "--parler-theme-chart-series-24",
    "--parler-theme-chart-title-text",
    "--parler-theme-chart-legend-text",
    "--parler-theme-chart-tick-text",
    "--parler-theme-chart-axis-label-text",
    "--parler-theme-chart-axis",
    "--parler-theme-chart-grid",
    "--parler-theme-chart-point-outline",
    "--parler-theme-chart-reference-danger",
    "--parler-theme-chart-reference-target",
    "--parler-theme-chart-font-family",
    "--parler-theme-chart-axis-line-width",
    "--parler-theme-notice-info",
    "--parler-theme-chart-point-outline-secondary"
  ];
  const read = () => {
    const host = document.querySelector("parler-ui");
    if (!host) {
      return {hostFound: false};
    }
    const computed = getComputedStyle(host);
    const themeValues = Object.fromEntries(tokenNames.map((name) => [
      name,
      computed.getPropertyValue(name).trim()
    ]));
    const inlineNames = Array.from(
      {length: host.style.length},
      (_, index) => host.style.item(index)
    );
    const threadPanel = host.querySelector('[part~="thread-panel"]');
    const composerInput = host.querySelector('[part~="composer-input"]');
    const primaryAction = host.querySelector('[part~="primary-action"]');
    return {
      hostFound: true,
      themeValues,
      nonEmptyThemeTokens: tokenNames.filter((name) => themeValues[name]),
      emptyThemeTokens: tokenNames.filter((name) => !themeValues[name]),
      publicInlineDeclarations: inlineNames.filter((name) =>
        name.startsWith("--parler-") && !name.startsWith("--parler-theme-")),
      surfaces: {
        hostColor: computed.color,
        hostBackground: computed.backgroundColor,
        threadBackground: threadPanel
          ? getComputedStyle(threadPanel).backgroundColor
          : null,
        inputBackground: composerInput
          ? getComputedStyle(composerInput).backgroundColor
          : null,
        primaryBackground: primaryAction
          ? getComputedStyle(primaryAction).backgroundColor
          : null
      }
    };
  };
  const before = read();
  globalThis.parlerThemeBridgeCapture = {
    before,
    finish() {
      const after = read();
      return {
        before,
        after,
        changedThemeTokens: before.hostFound && after.hostFound
          ? tokenNames.filter((name) =>
              before.themeValues[name] !== after.themeValues[name])
          : []
      };
    }
  };
  before;
}
// Switch the active Style Theme, then run:
globalThis.parlerThemeBridgeCapture.finish();
```

Record which expressions are intentionally unavailable. For example, a custom
Parler warning property absent from the active Theme may remain empty and use
its checked-in fallback; that does not excuse all platform-backed roles being
empty. A successful switch proves both the resolver input and the inherited
`themeUpdated`/`changeThemeName` path reach the wrapper.

## 16. Risks and controls

| Risk | Control |
|---|---|
| Platform dictionary CSS targets a render root Parler does not provide | Keep the selected resolver bridge and light DOM; do not add Shadow DOM, PTCS BehaviorStyleable, aggregator patches, or dormant direct-delivery code |
| A filtered declaration for an unknown Theme identifier is mistaken for registration failure | `parlerui` maps to `parler-ui`, known expressions produce the correct unit, and unknown Theme identifiers are filtered (Section 8.1) |
| The bridge depends on private SDK locals or reimplements Theme semantics | Use only exported `TW.getStyleManager().resolveExpressions(...)`, deterministic wrapper lifecycle hooks, and internal host tokens |
| A compound or malformed source-token manifest entry is silently skipped | Require exactly one bare `${expression-name}` per internal source token, warn at runtime, fail registry/manifest parity tests before packaging, and keep multi-source fallback in effective CSS |
| A Theme refresh leaves stale internal source values | Iterate the token-to-expression manifest so shared expressions fan out; track only bridge-owned declarations; preserve them on `null`; remove the previous set before applying a changed non-null result; and test missing expressions plus Theme switches |
| Per-widget Style Properties are accidentally reintroduced as a customer promise | Keep `StyleProps` absence and the bridge-only contract explicit in canonical/customer docs and tests; use scoped public tokens/parts for instance customization |
| Light-DOM application CSS couples to internal structure | Document only host, public tokens, `[part]`, and semantic states as stable |
| Runtime Theme switch recolors CSS but not existing SVG | Resolve a complete render-object signature and trigger chart redraw from the observed platform refresh signal |
| A visible Markdown/notice/overlay/control surface remains coupled to an internal literal or class | Treat Section 8.5 as a closed inventory; require owner fixtures and fail the literal/part completeness checks |
| A chart token exists but no SVG node consumes it, or a D3 presentation literal is missed | Trace every written SVG presentation attribute to the render object or structural-fixed allowlist; require grid/axis and per-kind fixtures |
| Pie colors collide unpredictably or legends disagree with marks | Use filtered render order modulo 24 for every kind and one shared assignment helper; test mark/legend slot identity and >24 wrap |
| Dark screen colors become illegible in a light print document | Resolve the independent portable print palette and rewrite every marked SVG palette role before serialization |
| `parler-dark` grows into a second implementation | Token table only; remove it if it needs structural branches or duplicate style rules |
| Theme colors reduce accessibility | Accessible built-in fallbacks, forced-colors rules, non-color state cues, manual theme checks |
| Public token list grows without discipline | Require semantic naming, tests, and documentation for every addition |
| Customer Theme specification drifts from implementation after a fix or enhancement | Mechanically compare exact names, groups, defaults, constraints, parts, and states in `docs/ui/theme-api.md` with the registry and canonical design in every styling change |
| Compact progress hides information needed for action or diagnosis | Suppress routine detail only; preserve rate-control, approval, error, and the shared attention-required task-state class |
| Configurable activity width causes overflow or unintentionally resizes final answers | Independent activity token plus unconditional `min-width: 0` / `max-width: 100%` structural guard |
| A missing, denied, or invalid Media Entity leaves a broken image or stale failure state | Load-error fallback to `Parler`; reset the failure state on every property change; test empty/loading/row transitions |
| SVG content becomes a script/style injection path | Use only `<img src>` with decorative empty alt; never fetch, parse, inline, or pass Media content to an unsafe template directive |
| A Media Entity, `IMAGELINK`, and browser URL are mistaken for interchangeable values | Keep the four-layer terminology/algorithm in Section 13.1; emit `IMAGELINK`, call `TW.convertImageLink` when available, and test the observed stored/bound/converted values |
| Platform and checked-in SDK/utility versions differ | Treat official documentation as intent; validate generated output and actual target behavior |

## 17. References

Repository reference:

- [Parler Theme API](./theme-api.md)
- [`parler-ui/styles/parler-ui.css`](../../parler-ui/styles/parler-ui.css)
- [`parler-ui/components/chart-draw.js`](../../parler-ui/components/chart-draw.js)
- [`parler-ui-widget/input/widgets/parler-ui.json`](../../parler-ui-widget/input/widgets/parler-ui.json)
- [`twx-wc-sdk-utility/src/compile.js`](../../twx-wc-sdk-utility/src/compile.js)
- ThingWorx Web Component SDK styling and theming documentation (PTC Help Center)

Official PTC reference:

- [Style Themes](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Mashup_Builder/Theming/StyleTheme.html)
- [Using the Style Properties Panel](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Mashup_Builder/Theming/UsingTheStylePropertiesPanel.html)
- [Styling a Container](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Mashup_Builder/Theming/StylingAContainer.html)
- [Applying Custom CSS Styling to Web Component Widgets](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Mashup_Builder/Theming/ApplyingCustomCSSToWebComponents.html)
- [Web Component SDK: Styling](https://support.ptc.com/help/thingworx_hc/web_component_sdk/Styling/STYLING.html)
- [Web Component SDK: Theming](https://support.ptc.com/help/thingworx_hc/web_component_sdk/Styling/THEMING.html)
- [Packaging a Web Component as a ThingWorx Widget](https://support.ptc.com/help/thingworx_hc/web_component_sdk/Extension_Tool/Packaging_A_Web_Component_As_A_ThingWorx_Widget.html)
- [ThingWorx Property Base Types](https://support.ptc.com/help/thingworx/platform/r9.6/en/ThingWorx/Help/Composer/Things/ThingProperties/PropertyBaseTypes.html)
- [`ptcs-style-unit`](https://support.ptc.com/help/thingworx_hc/web_component_sdk/Web_Components/ptcs-style-unit/index.html)
