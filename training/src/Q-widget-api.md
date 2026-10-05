# Widget API reference

This appendix describes the public API of the ThingWorx **AI Parler** widget shipped in **`parler-ui-widget` 0.1.92**. It is a lookup companion to Chapter **20**, not a wire-protocol specification.

## Naming layers

Composer exposes PascalCase property and service names. The underlying `<parler-ui>` web component uses the mapped camelCase property or method.

| Layer | Example |
|---|---|
| ThingWorx Composer property | `ThemeMode` |
| Web-component property | `themeMode` |
| CSS public token | `--parler-color-accent` |
| Semantic surface | `[part~="primary-action"]` |

The Mashup should normally use the Composer API. Application Custom CSS uses the public token and semantic-surface API.

---

## Composer properties

**Direction** uses `In` for a binding target and `Out` for a binding source.

### Presentation and content

| Property | Web component | Type | Direction | Default | Contract |
|---|---|---|---|---|---|
| `ThemeMode` | `themeMode` | `STRING` | In | `mashup` | `mashup` follows the active Style Theme; `parler-dark` uses the bundled dark preset. |
| `ProgressPresentation` | `progressPresentation` | `STRING` | In | `detailed` | `detailed` or `compact`; invalid values use `detailed`. |
| `ProgressLabel` | `progressLabel` | `STRING` | In, localizable | `Thinking...` | Compact ordinary-working label. |
| `RateControlLabel` | `rateControlLabel` | `STRING` | In, localizable | `Waiting for capacity...` | Compact provider rate-control label. |
| `EmptyStateMedia` | `emptyStateMedia` | `IMAGELINK` | In | empty | Optional Media Entity image for an empty conversation. |
| `CustomClass` | host class | `STRING` | In/Out | empty | Application-owned CSS scope; multiple classes are space-delimited. |
| `Placeholder` | `placeholder` | `STRING` | In | `Ask about device / property history…` | Prompt-entry placeholder. |
| `Disabled` | `disabled` | `BOOLEAN` | In | `false` | Disables prompt entry. |
| `HideHeader` | `hideHeader` | `BOOLEAN` | In | `true` | Hides the title region for a compact embedded UI. |
| `HeaderTitle` | `headerTitle` | `STRING` | In | `AI Parler` | Rendered only when `HideHeader=false`. |
| `HeaderSubtitle` | `headerSubtitle` | `STRING` | In | empty | Deprecated compatibility property; not rendered. |

### Transport, identity, Host Context, and history

| Property | Web component | Type | Direction | Default | Contract |
|---|---|---|---|---|---|
| `ThingworxWsUrl` | `thingworxWsUrl` | `STRING` | In | empty | Full `ws://` or `wss://` AlwaysOn URL. Empty derives the URL from the page host plus `/Thingworx/WS`. |
| `AppKey` | `appKey` | `STRING` | In | empty | Application key supplied before `ConnectAndBind`; protect it as a credential and prefer deployment-appropriate short-lived access. |
| `AgentThingName` | `agentThingName` | `STRING` | In | empty | Name of the `AIAgent` Thing that owns the turn. |
| `UseSubmitUserPromptOnConversation` | `useSubmitUserPromptOnConversation` | `BOOLEAN` | In | `true` | `true`: send through `ParlerGateway.SubmitUserPrompt`; `false`: legacy/debug `ParlerStreamToRemoteThing` path. |
| `ConnectionStatus` | `connectionStatus` | `STRING` | Out | `disconnected` | `disconnected`, `connecting`, or `connected`. |
| `ConversationId` | `conversationId` | `STRING` | In/Out | empty | Conversation key and transient `ParlerGateway` bind target. It also scopes persisted history. |
| `HostScopeJson` | `hostScopeJson` | `STRING` | In | empty | Optional UTF-8 JSON text shaped as `key + context`; read synchronously when Send is invoked. |
| `LoadHistoryOnBind` | `loadHistoryOnBind` | `BOOLEAN` | In | `true` | Loads conversation history after a successful bind. |
| `HistoryServiceName` | `historyServiceName` | `STRING` | In | empty | Empty uses `GetConversationHistoryJson`; set only for a compatible gateway service. |
| `HistoryMaxItems` | `historyMaxItems` | `NUMBER` | In | `500` | Maximum requested Stream rows; server clamps the value to `1..20000`. |
| `HistoryLoadingMessage` | `historyLoadingMessage` | `STRING` | In | empty | Optional history-loading banner; empty uses the built-in message. |

### Required connection order

Before invoking `ConnectAndBind`, bind non-empty values for:

1. `AppKey`;
2. `ConversationId`; and
3. `AgentThingName`.

Also set `ThingworxWsUrl` when the current page host cannot correctly derive `/Thingworx/WS`.

The default send path requires `ConversationId` to identify a valid conversation owned through `AgentThreadDataTable`. It is not the name of a persistent Conversation RemoteThing.

---

## Composer services

| Service | Web-component method | Purpose and constraints |
|---|---|---|
| `ConnectAndBind` | `connectAndBind()` | Opens the AlwaysOn WebSocket, authenticates, binds to `ConversationId`, and receives messages. Invoke only after required properties are bound. |
| `DisconnectAlwaysOn` | `disconnectAlwaysOn()` | Closes the AlwaysOn client opened by `ConnectAndBind`. |
| `ApplyLiveJson` | `applyLiveJson(json)` | Applies one wire JSON string. This is a fallback or hybrid-test path when embedded `ReceiveMessage` is not used. |
| `ResetChat` | `resetChat()` | Clears the widget's local thread state. It does not define a server-side history-deletion contract. |
| `LoadHistoryJson` | `loadHistoryJson(json)` | Replaces the local thread from a one-shot history JSON document. It is ignored while a turn is busy or an approval gate is open. |

`ApplyLiveJson` and `LoadHistoryJson` accept a bound string payload when invoked from Composer.

## Events deliberately not exposed

The widget does **not** expose:

- a `UserPrompt` Composer property;
- a `CleanUserPrompt` service;
- a `SubmitUserPrompt` event; or
- a `parler-user-message` event.

User turns run only through the embedded AlwaysOn transport after `ConnectAndBind`. Do not build a parallel Mashup event path around the prompt composer.

---

## Theme modes and precedence

| Mode | Resolution order |
|---|---|
| `mashup` | Scoped public token → active Mashup Style Theme role → documented fallback |
| `parler-dark` | Scoped public token → bundled dark preset → documented fallback |

Invalid, empty, or differently cased `ThemeMode` values resolve to `mashup`.

Rules should normally be scoped to an application-owned class:

```css
.customer-chat parler-ui {
  --parler-color-accent: #005ea8;
}
```

---

## Public CSS token inventory

Only the public names below are supported. Prefix every listed suffix with `--parler-`.

### Foundation, surfaces, and controls

| Family | Supported tokens |
|---|---|
| Typography | `font-family`, `font-size`, `line-height` |
| Surfaces | `color-canvas`, `color-surface`, `color-surface-raised`, `color-surface-subtle` |
| Text and borders | `color-text`, `color-text-muted`, `color-border` |
| Semantic colors | `color-accent`, `color-on-accent`, `color-focus`, `color-danger`, `color-warning`, `color-success`, `color-scrim` |
| Controls | `color-control-bg`, `color-control-text`, `color-control-border`, `color-control-hover`, `color-control-disabled`, `color-placeholder` |
| Viewport | `color-scrollbar-thumb`, `color-scrollbar-track`, `color-selection-bg`, `color-selection-text` |

### Messages, geometry, and empty state

| Family | Supported tokens |
|---|---|
| Message colors | `color-user-message`, `color-on-user-message`, `color-assistant-message`, `color-on-assistant-message` |
| Widths | `content-max-width`, `message-max-width`, `activity-max-width` |
| Empty-state Media | `empty-state-media-max-width`, `empty-state-media-max-height`, `empty-state-media-opacity`, `empty-state-media-filter` |
| Shape and spacing | `radius-panel`, `radius-message`, `radius-control`, `shadow-panel`, `space-unit` |

Important defaults include `content-max-width: 1200px`, `message-max-width: min(92%, 960px)`, panel and message radii of `12px`, control radius of `8px`, and spacing unit of `4px`.

### Markdown

| Family | Supported tokens |
|---|---|
| Headings and links | `markdown-heading`, `markdown-link`, `markdown-link-visited` |
| Code | `markdown-code-bg`, `markdown-code-text`, `markdown-code-border` |
| Quotes | `markdown-quote-bg`, `markdown-quote-text`, `markdown-quote-border` |
| Tables and rules | `markdown-table-header-bg`, `markdown-table-border`, `markdown-hr` |
| Images | `markdown-image-radius` |

### Screen charts

| Family | Supported tokens |
|---|---|
| Series | `chart-series-1` through `chart-series-24` |
| Text and structure colors | `chart-title-text`, `chart-legend-text`, `chart-tick-text`, `chart-axis-label-text`, `chart-axis`, `chart-grid`, `chart-point-outline` |
| Reference colors | `chart-reference-danger`, `chart-reference-warning`, `chart-reference-target` |
| Font and marks | `chart-font-family`, `chart-line-width`, `chart-line-point-radius`, `chart-scatter-point-radius`, `chart-outline-width`, `chart-bar-radius` |
| Axes and grid | `chart-axis-line-width`, `chart-grid-line-width`, `chart-grid-opacity` |
| Reference geometry | `chart-reference-line-width`, `chart-target-line-width`, `chart-reference-opacity`, `chart-reference-limit-dash`, `chart-reference-control-dash`, `chart-reference-target-dash`, `chart-reference-warning-dash` |
| Typography | `chart-title-font-size`, `chart-title-font-weight`, `chart-legend-font-size`, `chart-tick-font-size`, `chart-axis-label-font-size`, `chart-reference-label-font-size` |
| Legend and ticks | `chart-legend-swatch-size`, `chart-legend-swatch-radius`, `chart-legend-line-length`, `chart-tick-length`, `chart-tick-padding` |

Chart colors accept solid CSS colors. Numeric presentation values are validated and safely clamped. Series slot 25 wraps to slot 1.

### Print

| Family | Supported tokens |
|---|---|
| Page | `print-canvas`, `print-text`, `print-muted`, `print-border`, `print-surface-subtle`, `print-accent` |
| Chart series | `print-chart-series-1` through `print-chart-series-24` |
| Chart roles | `print-chart-outline`, `print-chart-reference-danger`, `print-chart-reference-warning`, `print-chart-reference-target` |

Print colors use fixed light defaults and do not automatically inherit screen Theme colors. Printed typography uses the public body font, size, and line-height tokens; printed Markdown images use `markdown-image-radius`.

---

## Semantic parts

Select parts with `[part~="name"]`, not `::part()`.

| Surface | Parts |
|---|---|
| Structure | `shell`, `header`, `thread`, `thread-panel`, `empty-state`, `empty-state-media`, `empty-state-label` |
| Messages | `message-meta`, `user-message`, `user-message-actions`, `assistant-message`, `markdown-content`, `message-actions`, `message-action` |
| Host Context | `host-context-disclosure`, `host-context-content` |
| Status and task state | `notice`, `activity-status`, `activity-progress`, `task-state`, `connection-status` |
| Structured output | `insight-hint`, `data-table-disclosure`, `data-table`, `data-table-header`, `data-table-cell`, `data-table-footer`, `chart` |
| Approval | `approval-panel`, `approval-input`, `approval-primary-action`, `approval-secondary-action`, `approval-danger-action` |
| Prompt composer | `composer`, `composer-input`, `primary-action`, `stop-action` |
| Floating and overlay UI | `floating-action`, `overlay-scrim`, `popover`, `popover-close` |

## Semantic states

| Owner | Attribute | Supported values |
|---|---|---|
| Host | `theme-mode` | `mashup`, `parler-dark` |
| Host | `connection-status` | `disconnected`, `connecting`, `connected` |
| `activity-status`, `activity-progress` | `data-activity` | `working`, `rate-controlled` |
| `notice`, `task-state` | `data-severity` | `info`, `success`, `warning`, `danger` |
| `message-action` | `data-action` | `copy`, `print`, `feedback-up`, `feedback-down`, `info`, `cutoff`, `dismiss` |

Native `:hover`, `:active`, `:focus-visible`, `:disabled`, `[disabled]`, `aria-pressed`, and `aria-expanded` are supported where applicable.

Task-state severity precedence is `danger`, then `warning`, then `success`, then `info`. Provider rate-control waiting is a separate `data-activity="rate-controlled"` state and uses the warning role.

## Unsupported styling dependencies

The following are outside the public API:

- `::part()`;
- internal `--parler-theme-*` and `--parler-effective-*` properties;
- internal class names, element nesting, generated chart SVG nodes, or `:nth-child()` selectors;
- Shadow DOM selectors or assumptions that `parler-ui` has a `shadowRoot`; and
- undocumented parts, attributes, state values, or output attributes.

Parler-specific per-widget ThingWorx Style Properties are not provided. Use `ThemeMode`, the active Mashup Style Theme, `CustomClass`, public tokens, and supported semantic selectors.

## Fixed presentation behavior

The following stay under widget control for responsive behavior, accessibility, and compatibility:

- minimum usable size, overflow, scrolling, flex/grid layout, and responsive containment;
- chart scale algorithms, clipping, view-box behavior, label placement, and tick-density rules;
- placeholder opacity and scrollbar width;
- print margins, paper-width clamping, page-break avoidance, and media containment; and
- accessibility-only positioning and forced-colors behavior.

The parent Mashup container owns the widget's external width and height.
