# Appendix: Extended tools and policies syntax

This appendix is the **syntax reference** for the two configuration-repository files you author most after taxonomy:

```text
/tools/extended_tools.json        # register your own (wrapper) tools
/policies/invoke_service.json     # allow specific invoke_service calls without HITL
```

Chapter 13 teaches *why* and *how* to design wrappers; this appendix lists *every field and every accepted shape*,
including all variant forms and exactly what the loader rejects. The source of truth is the Parler agent's loaders
(`ExtendedToolsManifest`, `InvokeServiceAllowPolicy`) and `docs/agent/configuration-repository.md`.

For the **post-Ch16 final** SCPA utilization manifest (four LLM-facing tools), see Chapter **16** and the canonical
**`parler`** reference at **`dev_data/sample_scpa_utilization_agent_configuration/tools/extended_tools.json`**. The syntax example below uses a
generic lab-readings tool shape (same pattern as Chapters **13** and **14**).

After editing either file, refresh the AgentThing prompt-context cache and start a fresh conversation. Use
**`ValidateAgentConfigurationRepository`** / **`GetAgentRuntimeSnapshot`** to confirm what actually loaded — both files
are **fault-oriented**: a parse or validation failure registers **nothing** for that file and grants **no** bypass; it
never falls back to a previous good version.

---

## 1. `tools/extended_tools.json`

Registers your own tools, each backed by a ThingWorx service. The root is a **JSON object**:

```json
{
  "version": 1,
  "tools": [
    {
      "name": "query_lab_readings_by_device",
      "title": "Query lab readings by device",
      "whenToUse": "Use when the user asks for lab sensor readings for one device over a time range.",
      "target": {
        "entityName": "Lab_Readings_helper",
        "serviceName": "QueryReadingsByDevice"
      },
      "hitl": false,
      "playbookSafe": true,
      "executorOnly": false,
      "risk": "READ_ONLY",
      "purpose": "Read bounded lab readings for one device.",
      "dryRun": { "supported": false },
      "idempotency": { "mode": "NONE" },
      "admission": "DEFAULT",
      "enabled": true
    }
  ]
}
```

**Root fields:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `version` | integer | yes | Must be exactly `1`. Any other value (or missing) rejects the whole file. |
| `tools` | array | yes | Array of tool objects. May be empty (`[]`) — valid, registers no tools. |

**Tool object fields:**

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `name` | string | **yes** | — | The LLM-facing tool name. Pattern: a letter, then letters/digits/underscores — **no hyphens, no spaces, ≤ 64 chars** (`[A-Za-z][A-Za-z0-9_]{0,63}`). Must be unique in the file and must not collide with a built-in tool name. |
| `whenToUse` | string | **yes** | — | Routing guidance shown to the model. Must be non-empty after trimming. This is prompt surface — write it for the model. |
| `target` | object | **yes** | — | The service binding (see below). |
| `target.entityName` | string | **yes** | — | The Thing that owns the service, **or** the exact value `"me"` (see §1.1). |
| `target.serviceName` | string | **yes** | — | The service to invoke on that Thing. |
| `title` | string | no | `""` | Human label for diagnostics only. |
| `hitl` | boolean | no | `true` | Approval requirement. **Omitted ⇒ approval required.** See §1.2. |
| `playbookSafe` | boolean | no | `false` | Whether deterministic playbooks may call it. **Only effective when `hitl` is `false`.** See §1.3. |
| `executorOnly` | boolean | no | `false` | When `true`, register the tool for execution but hide it from the model-facing tool list. See §1.4. |
| `risk` | string | no for legacy entries; **required when any capability field is present** | — | `READ_ONLY`, `MUTATING`, `DESTRUCTIVE`, or `ADMIN`. See §1.5. |
| `purpose` | string | no | — | Operator-facing business purpose for the governed capability. |
| `dryRun` | object | no | unsupported | `{ "supported": false }`, or `supported:true` plus the real BOOLEAN Service parameter name. |
| `idempotency` | object | no | undeclared | `NONE` (no parameter) or `CALLER_KEY` plus the real Service parameter name. |
| `dataClassification` | string array | no | `[]` | Non-empty labels when declared; used by governed Provider eligibility. |
| `admission` | string | no | `DEFAULT` | `OFF`, `NARROW`, `LAZY`, or `DEFAULT`; `OFF` removes model advertisement. |
| `enabled` | boolean | no | `true` | Disabled capabilities are not advertised or executed. |
| `inputSemantics` / `outputSemantics` | object | no | — | Optional schema/profile references; output may declare stable `businessErrors`. |

Unknown extra fields outside the supported legacy and capability sets have no defined course meaning. Do not use
unknown keys as hidden policy.

### 1.1 `target.entityName` — the two forms

```json
"entityName": "SCPA_Utilization_helper"   // an explicit Thing name
"entityName": "me"                         // the AgentThing itself (lowercase "me" only)
```

`"me"` resolves to the AgentThing's own name at load time — handy when the service lives on the agent. No other special
token works (`self`, `${me}`, `""`, `.` are all just literal names and will fail to resolve).

### 1.2 `hitl` — approval (note the default)

| You write | Effect |
|---|---|
| `"hitl": false` | Bypass HITL for **this tool** — it runs without an approval card. (Does **not** grant `invoke_service` permission for the same service; that is a separate policy, §2.) |
| `"hitl": true` | Require an approval card before each call. |
| *(omitted)* | **Same as `true` — approval required.** |
| a non-boolean (e.g. `"false"`) | **Rejects the whole file.** |

Read-only lab tools normally set `"hitl": false`. If you forget it, the tool still works but every call prompts for
approval.

### 1.3 `playbookSafe` — only with `hitl: false`

A tool is playbook-eligible only when **both** `playbookSafe: true` **and** `hitl: false`. The effective rule is
`playbookSafe && (hitl == false)`.

| `playbookSafe` | `hitl` | Playbook-eligible? |
|---|---|---|
| `true` | `false` | **Yes** |
| `true` | `true` or omitted | No (the loader logs a warning) |
| `false` or omitted | anything | No |

So a tool intended for a playbook needs **both** flags set.

`executorOnly` is orthogonal to this rule. An executor-only tool is hidden from the model's open-ended tool list, but a
static playbook may still call it when the same tool also has `playbookSafe: true` and `hitl: false`. This is useful
when you want the agent to run a stable workflow without advertising the underlying helper as a general-purpose chat
tool.

### 1.4 `executorOnly`

| You write | Effect |
|---|---|
| `"executorOnly": true` | Registered and executable, but **omitted from the model's tool list** (used for replay / orchestration / executor-only paths). |
| `"executorOnly": false` or omitted | Normal model-facing tool. |

### 1.5 Capability descriptors and runtime enforcement (0.1.218-era capability slice)

Capability fields are additive to the legacy `hitl` / `playbookSafe` flags, but they are enforced at load and
execution time rather than treated as documentation:

- Declaring any capability field requires a valid `risk`; invalid shapes skip that tool entry.
- `READ_ONLY` may remain Playbook-eligible when the legacy flags allow it. Capability-bearing `MUTATING` tools
  require HITL and are not Playbook-eligible in the current runtime.
- `DESTRUCTIVE` and `ADMIN` are fail-closed with `CAPABILITY_POLICY_BLOCKED`; there is no shipped G20 admit path.
- A declared dry-run parameter must exist on the live ServiceDefinition. Calls pass through dry-run enforcement;
  the manifest cannot invent a parameter the Service does not own.
- `CALLER_KEY` idempotency likewise names a real Service parameter. `NONE` must omit `parameter`.
- ThingWorx permissions remain authoritative. A descriptor never grants RBAC and never makes `hitl:false` a
  generic `invoke_service` bypass.

The SCPA utilization services are read-only. Their current manifest therefore declares `risk:READ_ONLY`,
`dryRun.supported:false`, `idempotency.mode:NONE`, `admission:DEFAULT`, and `enabled:true`; it does not claim
unsupported dry-run or caller-key parameters.

### 1.6 What is rejected vs. what is skipped

**Whole file rejected (nothing registers, logged ERROR):** root not an object; `version != 1`; `tools` not an array;
a tool that is not an object; missing/invalid `name` (bad pattern); duplicate `name` or collision with a built-in;
missing/empty `whenToUse`; missing `target` / `entityName` / `serviceName`; a non-boolean `hitl`, `playbookSafe`, or
`executorOnly`.

**Single tool skipped (the rest of the file still loads, logged WARN):** invalid capability metadata;
`target.entityName` does not resolve; the
resolved entity is not a Thing; `serviceName` not found on the Thing (including inherited); the service is
PASSWORD-protected; the tool's JSON schema cannot be generated from the service definition.

### 1.6 All boolean variant forms at a glance

```json
{ "name": "minimal", "whenToUse": "…", "target": { "entityName": "T", "serviceName": "S" } }
// hitl→required, playbookSafe→no, executorOnly→no, title→""

{ …, "hitl": false }                       // runs without approval
{ …, "hitl": false, "playbookSafe": true } // usable in playbooks
{ …, "executorOnly": true }                // hidden from the model, still executable
{ …, "hitl": false, "playbookSafe": true, "executorOnly": true } // playbook-only helper
{ …, "target": { "entityName": "me", "serviceName": "S" } } // service on the AgentThing
```

---

## 2. `policies/invoke_service.json`

Lists **allow rules** that let the built-in `invoke_service` tool call specific services **without** an approval card.
It is **allow-only**: if no rule matches, the call requires HITL. The root is a **JSON object**:

```json
{
  "version": 1,
  "rules": [
    {
      "id": "allow-datatable-reads",
      "priority": 100,
      "description": "Allow common DataTable read services.",
      "match": {
        "entityTypes": ["Thing"],
        "entityNames": ["*"],
        "serviceNames": ["GetDataTableEntries", "QueryDataTableEntries"]
      }
    }
  ]
}
```

**Root fields:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `version` | integer | yes | Must be exactly `1`. |
| `rules` | array | yes | Array of rule objects. May be empty (`[]`) — valid, allows nothing (everything needs HITL). |

**Rule object fields:**

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | yes | Stable diagnostic id. Non-empty; **unique** within the file. |
| `priority` | integer | yes | **Lower matches first**; ties break by file order. |
| `match` | object | yes | The three pattern arrays below. |
| `match.entityTypes` | array of strings | yes | Entity type patterns (e.g. `"Thing"`). Non-empty. |
| `match.entityNames` | array of strings | yes | Entity-name patterns. Non-empty. |
| `match.serviceNames` | array of strings | yes | Service-name patterns. Non-empty. |
| `description` | string | no | Operator note; no runtime effect. |

### 2.1 How a rule matches

A call is described by its `entityType`, `entityName`, and `serviceName`. A rule matches when **all three** categories
match (AND), and within each category **any one** pattern matches (OR):

```text
match  ==  any(entityTypes) AND any(entityNames) AND any(serviceNames)
```

Rules are evaluated by ascending `priority` (then file order); the **first matching rule wins** and the call is allowed
(HITL bypassed). If no rule matches, the call requires HITL.

### 2.2 Patterns — glob only, all forms

| Form | Example | Matches |
|---|---|---|
| exact | `"GetDataTableEntries"` | that exact name |
| any | `"*"` | anything (you must write `["*"]` explicitly — there is no implicit wildcard) |
| prefix | `"Get*"` | `GetData`, `GetStatus`, … |
| suffix | `"*Count"` | `QueryCount`, `ItemCount`, … |
| middle | `"Query*Data"` | `QueryOrderData`, … |
| multiple | `["GetX", "QueryY"]` | either name (OR within the array) |

Rules and limits:

- Only the `*` glob is supported. **No regex.** `**` (double asterisk) is **rejected** and invalidates the file.
- Matching is **case-sensitive**.
- Each of the three arrays must be **non-empty**; every element must be a non-blank string. A missing array, empty
  array, blank string, or non-string element invalidates the file.
- `match.entityTypes` entries that contain no `*` must be **real root entity type names** (e.g. `Thing`, `Group`,
  `Organization`); an unknown literal type invalidates the file. A pattern containing `*` skips that check.
- The special value `"me"` has **no meaning here** — in a policy, use the resolved Thing name or `"*"`.

### 2.3 Multiple rules and priority

```json
{
  "version": 1,
  "rules": [
    {
      "id": "allow-utilization-reads",
      "priority": 10,
      "match": {
        "entityTypes": ["Thing"],
        "entityNames": ["SCPA_Utilization_helper"],
        "serviceNames": ["Get*"]
      }
    },
    {
      "id": "allow-datatable-reads",
      "priority": 100,
      "match": {
        "entityTypes": ["Thing"],
        "entityNames": ["*"],
        "serviceNames": ["GetDataTableEntries", "QueryDataTableEntries"]
      }
    }
  ]
}
```

Priority `10` is checked before `100`. Because the policy is allow-only, ordering affects *which rule's match is
reported*, not allow-vs-deny — there is no deny rule; "not matched" simply means HITL.

### 2.4 Invalid or missing policy

A malformed or invalid policy (bad `version`, missing `match`, empty/blank patterns, `**`, unknown literal entity type,
duplicate/empty `id`, missing `priority`) is logged ERROR and registers **no** allow rules — **every** `invoke_service`
call then requires HITL. A missing file behaves the same way (everything requires HITL). Policies never weaken the hard
blocks: PASSWORD protection, invalid parameters, and unresolved service metadata still stop a call regardless of any
allow rule.

---

## 3. Wrapper design (why you usually need `extended_tools.json`)

Wrap a service when the original was built for Mashups rather than for an LLM. Prefer wrapper inputs that the model can
produce reliably:

- `STRING`, `NUMBER`, `BOOLEAN`, `DATETIME`, `THINGNAME`, and small bounded arrays / enums.
- For a Thing identifier, use `THINGNAME` — Parler then preflights labels and steers the model toward `resolve_thing`.
- For a time window, use predictable pairs: `startDate`/`endDate` or `startTime`/`endTime`.

Avoid asking the model to build: arbitrary `INFOTABLE` values; DataShape-specific row objects; "empty table means all"
conventions; or hidden UI selection flags.

| Service shape | Teach as |
|---|---|
| scalar date range, no complex table input | can be called direct via `invoke_service` (+ policy) |
| machine parameter typed as `STRING` | wrap to `THINGNAME` |
| input is an `INFOTABLE` | wrap |
| output is raw, huge, or not answer-ready | wrap or redesign |
| returns successful `null` for invalid business input | wrap and return a structured error/empty status |

A good wrapper returns small, answer-ready rows and uses a `whenToUse` that names the parameters and the business
intent — see Chapter 13 for the full worked example.

## 4. See also

- Chapter 13 — Extended tools and wrappers (design walkthrough).
- Chapter 16 — Final utilization four-tool manifest (upload target).
- Chapter 12 — Playbooks (where `playbookSafe` tools are used).
- Appendix C — Configuration repository (paths, upload, validation/snapshot services).
- Parler `docs/agent/CUSTOMIZED-TOOLS.md` and `docs/agent/configuration-repository.md` — normative detail.
