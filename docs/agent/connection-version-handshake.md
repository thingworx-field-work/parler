# Connection version handshake

Status: **implemented** — Gateway **`GetConnectionInfo`**, UI status line, widget version codegen, tests.

## Normative cross-references

| Subject | Where it is specified |
|--------|------------------------|
| Gateway name, bind surface, uplink locus | **`docs/agent/parler-gateway-design.md`**, **`docs/architecture/agent-alwayson.md`** (Phase F, §2.0) |
| Thread ownership (`conversationId` row, caller principal) | **`AgentThreadDataTableSupport`** (used from **`ParlerGateway`**) |
| Streaming wire / history shapes | **`CONTRACTS/API_CONTRACT.md`**, **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** — this topic adds a **non-stream** Gateway service only |
| Widget build → extension | **`parler-ui-widget/README.md`**, repo-root **`README.md`** build entrypoints |

## Overview

The visible product outcome is small: after the widget connects, the connection status should include the loaded
`parler-agent` extension version and the loaded `parler-ui-widget` extension version, for example:

```text
Transport: connected, 0.1.176:0.1.76
```

The feature is a **handshake**, not a status-label tweak, because the useful diagnostic
is "what is actually loaded in this live system?" rather than "what version did the JavaScript source expect?"

## Background

During live testing and workshop support, the first question is often whether the browser widget and the server-side
agent extension are the expected pair. Today the widget only shows:

```text
Transport: connected
```

That proves the AlwaysOn socket is connected and bound, but it does not prove which `parler-agent` package ThingWorx
loaded or which `parler-ui-widget` package delivered the current UI bundle.

Hard-coding both versions in UI source would be simpler, but it would produce misleading diagnostics when the server and
widget are upgraded independently. A widget source constant can only prove the widget package version. The agent version
must come from the running agent.

Existing pieces:

- `parler-ui-widget/input/widgets.json` owns the widget extension package version, currently the human-facing widget
  package version.
- `parler-agent/build.gradle` owns `revisionVersion` and writes `metadata.xml` `packageVersion`.
- `ParlerPackageVersion.fromAnchorClass(...)` (**`parler-agent/.../ParlerPackageVersion.java`**) reads the shadow JAR
  manifest: **`Package-Version`** first, else **`Implementation-Version`**. The releasable Gradle pipeline may set a
  **`version`** string on the JAR that still carries **`-SNAPSHOT`** or build suffixes while **`updateMetadata`** writes
  **`metadata.xml` → `packageVersion`** from **`project.ext.artifact_version`** (`major.minor.revision`). For the
  connection label, the human-facing value should match **Composer import identity** (`artifact_version` / metadata), not
  necessarily the raw manifest line.
- **`AgentThing.GetAgentRuntimeSnapshot`** (not on the Gateway) already exposes
  **`agent.extensionVersion`** from **`ParlerPackageVersion.fromAnchorClass(AgentThing.class)`**. That remains a broad
  operator snapshot (skills, repo fingerprints, etc.). The normal chat status line must **not** depend on it.

## Goals

- Show `agentVersion:widgetVersion` immediately after a successful `ConnectAndBind`, without waiting for the first user
  prompt.
- Make the agent version runtime-derived from the selected `AgentThing`.
- Make the widget version derived from the deployed widget extension package, not from `parler-ui/package.json`.
- Avoid exposing prompt, skill, playbook, policy, taxonomy, or tool metadata just to render a status label.
- Keep connection-info retrieval non-blocking: failure to read versions must not break chat.
- Make mismatched deployments visible during support and live testing.

## Non-goals

- No LLM prompt changes.
- No model-facing tool changes.
- No `session.ack` streaming-frame change.
- No attempt to enforce agent/widget compatibility in the browser.
- No hard block when versions are missing or mismatched.
- No replacement for full live diagnostics / collection tooling.

## UX

The status line remains compact:

```text
Transport: disconnected
Transport: connecting
Transport: connected, 0.1.176:0.1.76
```

Display rules:

| State | Display |
|-------|---------|
| Not connected | `Transport: disconnected` / `Transport: connecting` |
| Connected, widget known, agent pending | `Transport: connected, ?:0.1.76` |
| Connected, both known | `Transport: connected, 0.1.176:0.1.76` |
| Connected, widget unknown, agent known | `Transport: connected, 0.1.176:?` |
| Connection-info call failed | Keep `connected`, show whatever version side is known, log a transport warning |

The element `title` / tooltip should carry the expanded labels:

```text
AlwaysOn transport connected. agent=0.1.176 widget=0.1.76
```

## Version Sources

### Agent Version

The UI must not hard-code the agent version.

Preferred source:

- a generated agent runtime resource produced from `parler-agent/build.gradle` `artifact_version`;
- exposed through a narrow connection-info service on `ParlerGateway`;
- fallback to the current manifest reader only when the generated resource is absent.

Rationale: `metadata.xml` `packageVersion` is the ThingWorx import identity users recognize during deployment. The JAR
manifest can contain Gradle snapshot/build suffixes that are correct build metadata but noisy for workshop diagnosis.

Implementation shape:

- Add a small generated resource, for example:

  ```properties
  artifactVersion=0.1.176
  implementationVersion=0.1.176.0-SNAPSHOT
  ```

- Add a helper, for example `ParlerRuntimeVersion`, with:

  ```java
  String displayVersion();      // artifactVersion, e.g. 0.1.176
  String implementationVersion(); // optional raw/build version
  ```

- Keep `ParlerPackageVersion` behavior available for backwards compatibility or make it delegate to the new helper.

### Widget Version

The widget version should be the ThingWorx widget extension package version from:

```text
parler-ui-widget/input/widgets.json -> version
```

Do **not** display `parler-ui/package.json` here. That is the web component/npm package version and is not the imported
Composer widget package version users see during ThingWorx deployment.

The literal is not hand-maintained: a small widget-version JS module is generated from `input/widgets.json` during
widget sync/build (see Implementation).

## Service Contract

Add a narrow **`@ThingworxServiceDefinition`** on **`com.thingworx.things.agent.ParlerGateway`** (same Java class as
**`SubmitUserPrompt`**, **`GetConversationHistoryJson`**, etc.). ThingWorx picks up new services from the annotated class;
**`metadata.xml`** already declares the **`ParlerGateway`** thingPackage (**`parler-agent/metadata.xml`**). Mashup
designers must grant invoke permission for the new service on the transient Gateway Things (same pattern as existing
Gateway services).

```text
GetConnectionInfo(agentThingName: STRING, widgetPackageVersion: STRING optional) -> result : STRING
```

**ThingWorx:** **`@ThingworxServiceResult(name = "result", baseType = "STRING")`** — same **`result`** / **`STRING`** column as **`GetConversationHistoryJson`** and **`SubmitUserPrompt`**. The widget **MUST** use **`stringFromInvokeResult(result)`** before **`JSON.parse`**, and **MUST** build parameters with **`buildGetConnectionInfoParams`** (**`parler-ui/lib/alwaysOnInvokeParams.js`**) so the codec emits a valid single-row **InfoTable** (see **`CONTRACTS/API_CONTRACT.md`**).

JSON response:

```json
{
  "schemaVersion": "parler.connection-info.v1",
  "conversationId": "SCPA_DEMO_ID",
  "agent": {
    "thingName": "SCPA_Demo_Agent",
    "extensionVersion": "0.1.176",
    "implementationVersion": "0.1.176.0-SNAPSHOT"
  },
  "serverTime": "2026-06-06T16:45:00.000Z"
}
```

Semantics:

- `extensionVersion` is the display/import package version (**`ParlerRuntimeVersion.displayVersion()`**, i.e. Gradle **`artifact_version`** with manifest fallback).
- `implementationVersion` is optional and included only when it differs from **`extensionVersion`** (build / snapshot detail).
- The optional **`widgetPackageVersion`** request parameter is **not** returned in JSON; it is **sanitized** (trim, collapse whitespace, strip ISO controls, length cap) and emitted **only** in the **`PARLER_CONNECTION_INFO`** log line for correlation.
- The service must not refresh runtime configuration.
- The service must not include skills, playbooks, tools, policies, taxonomy, prompt text, configuration repository file
  metadata, or loaded repository content.

Security and validation:

- **Ownership (MUST):** **`AgentThreadDataTableSupport.loadConversationMetadataForCurrentUser(getName(), prefix)`** (same ownership gate as **`GetConversationHistoryJson`**) before reading versions.
- **Bound-agent match (MUST):** **`agentThingName`** (trimmed) **MUST** equal the thread row’s **`agentName`**. The server resolves the **`AgentThing`** from that bound name only — this closes a cross-agent probe vector.
- **Lookup path:** visibility-aware **`EntityUtilities.findEntity`** (**`PlatformAccess.findAsUser`**) + **`ThingTemplateSupport.ensureThingDerivedFromTemplate(..., AIAgent)`**, same as **`SubmitUserPrompt`** after the name match.
- **Response surface:** **`schemaVersion`**, **`conversationId`**, **`agent`** ( **`thingName`**, **`extensionVersion`**, optional **`implementationVersion`** ), **`serverTime`** only.

Contract placement:

- This is not a downstream **`ReceiveMessage`** frame and does not change **`session.ack`**.
- Normative JSON + ThingWorx **`result`** rules: **`CONTRACTS/API_CONTRACT.md`** § **`GetConnectionInfo`**; bundle **`CONTRACTS/CONTRACT_VERSION.md`** bumped with the shipped behavior.
- **`docs/architecture/agent-alwayson.md`**: **§4.4** documents post-**`ConnectAndBind`** **`GetConnectionInfo`** and the **`#historyLoadEpoch`** guard (reuse the history epoch — do **not** add a sibling epoch unless every bump site in **`parler-ui/parler-ui.js`** is duplicated).

## UI Lifecycle

After `ConnectAndBind` succeeds:

1. Set `connectionStatus = "connected"` immediately, as today.
2. Initialize status versions:
   - `widgetPackageVersion` from widget-injected value;
   - `agentExtensionVersion = ""`.
3. Fire `GetConnectionInfo` asynchronously against the bound `conversationId` Gateway, passing:
   - `agentThingName`;
   - local `widgetPackageVersion`.
4. On success:
   - require **`schemaVersion === "parler.connection-info.v1"`** exactly; any other value is a **connection-info failure** for UI state (log, keep partial **`?:widget`** display);
   - store **`agent.extensionVersion`**;
   - store optional **`implementationVersion`** for tooltip/debug logs when present.
5. On failure:
   - leave transport connected;
   - log a concise transport warning;
   - keep the version segment partial (`?:0.1.76`) rather than showing stale agent data.
6. On `DisconnectAlwaysOn` or transport loss:
   - clear the agent version;
   - keep the widget version because it is local to the current UI bundle.
7. On matching **`session.superseded`** (same **`conversation_id`**): clear **`#agentConnExt`** / **`#agentConnImpl`** together with the **`historyLoadEpoch`** bump so the transport line does not show a **stale** agent version while **`liveSessionInvalidUntilReconnect`** blocks Send (symmetry with disconnect).

The connection-info call must be **epoch guarded** by reusing **`#historyLoadEpoch`** in **`parler-ui/parler-ui.js`** (same capture/compare pattern as **`GetConversationHistoryJson`**): capture the epoch immediately after the post-bind bump inside **`#afterBindStartHistoryBootstrap`**, and ignore late callbacks when the epoch no longer matches.

## Implementation

- **Agent version:** Gradle task **`generateParlerRuntimeVersionProps`** writes **`parler-runtime-version.properties`** from **`artifact_version`** / **`project.version`**; **`ParlerRuntimeVersion`** (**`displayVersion()`** / **`implementationVersion()`**) reads it, with manifest fallback. **`GetAgentRuntimeSnapshot.agent.extensionVersion`** is unchanged and stays manifest-derived (**`ParlerPackageVersion`**) for operator forensics.
- **Gateway service:** **`ParlerGateway.GetConnectionInfo`** (**`result`** STRING JSON) enforces **`loadConversationMetadataForCurrentUser`** plus the bound-agent match, returns **`parler.connection-info.v1`** JSON without widget echo fields, and logs **`PARLER_CONNECTION_INFO`** with the widget version sanitized by **`ParlerConnectionInfoSanitizer`**.
- **Widget version:** **`parler-ui/lib/widgetPackageVersion.js`** is generated from **`parler-ui-widget/input/widgets.json`** by **`parler-ui/scripts/gen-widget-package-version.mjs`** (run by **`parler-ui`** **`build:tw`** and **`parler-ui-widget`** **`sync`**); **`parler-ui.js`** imports **`WIDGET_PACKAGE_VERSION`** for the status line and the **`GetConnectionInfo`** invoke.
- **UI:** `<parler-ui>` keeps `agentExtensionVersion` and optional `agentImplementationVersion`, invokes `GetConnectionInfo` after a successful bind, renders the compact status text, guards stale responses, and falls back with transport logging on failure.

## Verification

Local build checks:

- `cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true`
- `cd parler-ui && npm run build:tw`
- `cd parler-ui-widget && npm run sync`

Collection-tool evidence:

- Validate **`GetConnectionInfo`** JSON (or **`PARLER_CONNECTION_INFO`** in **`application-log.json`**) after **`ConnectAndBind`** — **do not** require **`GetAgentRuntimeSnapshot.agent.extensionVersion`** to match the transport label (the snapshot remains manifest-oriented).
- `stream.json` should not need a prompt just to prove connection version display.
- `application-log.json` should include `PARLER_CONNECTION_INFO` when the handshake runs.

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| UI shows expected agent version, not actual agent version | Agent version must come from runtime service, never UI constant |
| Widget version literal drifts from `widgets.json` | Generate/inject from `widgets.json` and add a verification check |
| Snapshot service becomes a hidden dependency | Add narrow `GetConnectionInfo`; do not use `GetAgentRuntimeSnapshot` for normal UI status |
| Connection is delayed by diagnostics | Run connection-info call after status becomes `connected`; never block Send/history |
| Late response updates wrong connection | Guard by bind context / epoch |
| Users confuse snapshot/build suffixes | Display import package version; keep raw implementation version only in tooltip/logs |

## Behavior Decisions

1. **Bound agent:** **`agentThingName`** **MUST** equal **`AgentThreadDataTable`** row **`agentName`** (trimmed); resolve **`AgentThing`** from that bound name only.
2. **`GetAgentRuntimeSnapshot.agent.extensionVersion`:** **No semantic change**; **`GetConnectionInfo`** owns the Composer display string.
3. **Failure UX:** keep partial **`?:<widget>`** with transport **`WARN`** on failure / unknown **`schemaVersion`**.
4. **`schemaVersion`:** strict — anything other than **`parler.connection-info.v1`** is a connection-info **failure** for UI state (log observed string).
5. **Mismatch policy:** display-only (no major/minor WARN).
