# Introducing Parler

Parler adds a conversational AI agent to ThingWorx. A user asks a question in a mashup, for example "why was this
machine down yesterday?" or "compare the contact force of these two units over the last four hours". The agent
answers with text, tables and charts built from live platform data. It can also propose changes: property writes and
other actions that require confirmation wait until the user approves them.

Parler ships as two ThingWorx extensions:

- **`parler-agent`** is a Java extension. Its `AIAgent` Thing runs the agent loop against your LLM provider and
  executes tools on the platform.
- **`parler-ui-widget`** is a Composer widget. It contains the `<parler-ui>` chat component, which renders the
  streamed answers, charts, tables and approval cards.

What sets Parler apart is less what it can do than how it does it. The rest of this page covers four design choices.

## 1. A native ThingWorx extension that respects the security context

Parler is not a separate service that reaches into ThingWorx with an all-powerful key. It runs inside the platform,
and every action it takes on a user's behalf goes through the same permission checks as that user's own REST calls.

- **The agent can do only what the user can do.** Tools look up entities with the user's Visibility and invoke
  services through the permission-checked request path, so the user needs ServiceInvoke on each service. Property
  reads check the user's PropertyRead, with no fallback to a more privileged account. A thread without a security
  context sees nothing.
- **Parler's own plumbing is kept separate.** Conversation streams, the thread table, the configuration and export
  repositories and the LLM provider Things are accessed as the current user or the System user. This lets ordinary
  users use the agent without holding rights on Parler's internals, and the exception never extends to the user's
  data.
- **Approved writes run as the approver.** When a user confirms a proposed change, the write runs under that user's
  security context and needs their ServiceInvoke and PropertyWrite. An approval never lets the agent do something the
  user could not do through ThingWorx REST. Read-only properties stay read-only, checked both before approval and at
  the write.
- **Protected values stay protected.** PASSWORD properties are masked with a fixed `***` and blocked from being read,
  written or supplied as input through any tool, approval card, stream row, log or chart. The check is driven by
  property metadata, not by prompt wording.
- **It uses platform building blocks.** Conversations stream over ThingWorx AlwaysOn through a gateway Thing whose
  ownership is checked on every call. Behavior is configured through files in a ThingWorx FileRepository: skills,
  taxonomies, Playbooks, extended tools and policies. Everything is governed by ThingWorx permissions and visible in
  Composer.
- **It is enforced, not just intended.** A dedicated test fails the build if code outside one small access class
  uses the platform's permission-free lookup or invocation calls.

## 2. Trust by construction

Telling a model "don't make things up" is not a guarantee. Parler moves the parts that must be correct out of the
model and into code, so that an answer can be trusted for how it was built.

- **Numbers come from the platform.** Every table, every chart point and every figure a tool reports comes from a
  ThingWorx service result, a cached copy of one, or a deterministic server-side transform of it. The model never
  supplies chart coordinates, and charts are assembled on the server from tool data.
- **Full results stay on the server.** A large result returns a small sample plus a `cacheId`. Aggregation,
  statistics and charts run over the full cached table in Java, and the model works with the summary.
- **Completeness is reported, never assumed.** Results say whether they are complete, partial or unknown. Read limits
  and truncation are flagged (`readLimitReached`, `pointsTruncated`), and a missing flag is never taken as proof of
  completeness. Computations over time series state that they cover only the observed span, and time without data is
  reported as unknown rather than counted as zero.
- **Evidence has a status.** Each analysis is classed as success, no finding, insufficient evidence or error. "No
  finding" requires completed work over evidence proven complete; anything less is reported as insufficient.
- **Data quality comes first.** Before analysis, time series can be checked for duplicate or out-of-order samples,
  freshness, cadence and cadence drift, gaps, flat lines and range. Duplicates are never dropped silently.
- **Identities are exact.** A tool that needs a Thing accepts only a canonical name the user can see. A display name
  such as "MUC BenchScale 02" is refused with `IDENTITY_RESOLUTION_REQUIRED`; the agent then resolves it through the
  identity taxonomy and retries.
- **Time is resolved by code, or not at all.** Supported phrases, a whole local day such as "yesterday" or a relative
  duration such as "the last 30 minutes", are converted to UTC windows in Java, in the user's time zone. Finer or
  business-specific periods, such as an afternoon, a clock time or a shift, are refused
  (`UNSUPPORTED_CALENDAR_PHRASE`) rather than guessed, so the window must be given as explicit start and end times.
  Unsafe date literals are rejected, and the window actually used is reported back.
- **Gated actions require confirmed intent.** Property writes (`set_property_value`), extended tools that are declared
  as needing confirmation or as mutating, and `invoke_service` calls that the policy does not exempt are held as
  approval requests. Each is shown to the user as an approval card built from the structured request, never from
  model prose. Only the same user can confirm it, and only inside a Parler conversation; elsewhere a gated action is
  refused (`APPROVAL_REQUIRES_PARLER_CONTEXT`). Approval confirms intent; it does not grant permissions.

  Not every tool that changes platform state is gated. `acknowledge_alerts` acknowledges alerts directly, with the
  user's permissions, and a policy exemption lets a service run without confirmation whatever that service does.
- **Governance lives in files, enforcement in Java.**
  - An extended tool exists only if a manifest maps it to a concrete Thing and service. A malformed manifest is
    rejected as a whole.
  - The `invoke_service` policy can only exempt calls from confirmation. A missing file, invalid JSON or an
    unmatched call falls back to asking the user, and nothing in a policy file can grant more authority. An exemption
    does not make a service read-only, so exempt only services you would let run unconfirmed.
  - Extended tools follow a capability policy: tools declared as mutating always need confirmation, and destructive
    and administrative ones are refused.
- **Workflows are deterministic where it matters.** A Playbook is a registered workflow graph. The runtime controls
  which steps run and which tools they may use, and the model contributes only the language.
- **Tool access is narrowed.** Free-form query predicates written by the model are rejected before they reach
  ThingWorx. Tool admission can limit which tools a turn offers, and every tool result passes one central gateway
  before it reaches the model.

**What this does not cover.** Parler controls the facts the model receives and the actions it can take. It does not
check the wording of the final answer against the evidence. That grounding relies on the evidence Parler provides
and on instructions to the model, so a fluent answer still deserves a look at the data behind it. The chart, table
and approval card the user sees are built from the evidence, not from the text.

## 3. No code interpreter for model-written code

Parler does not include a code interpreter. Its own analyses never run code written by the model: no scripts, no
JavaScript or Python, no SQL, no formulas and no chart specifications. The extension contains no script engine and
starts no processes.

Instead, every built-in computation is a fixed operator implemented in Java. The model chooses an operator and
supplies parameters; the operator never evaluates model-supplied code. Examples include counter increments with
rollover handling, rolling statistics, time-weighted integrals and means, calendar bucketing, resampling, joins,
quality checks, period comparisons and correlation. Charts come from a fixed set of chart kinds rendered by the
widget.

`invoke_service` is different: it calls services that already exist on the platform, and what a service does with its
parameters is defined by that service, not by Parler. Some platform services accept expressions as parameters and
evaluate them, so a model-supplied value can be evaluated there. These calls still run with the user's permissions and
under the confirmation policy; keep a service out of reach with ThingWorx permissions, or out of the policy's
exemptions so that each call needs confirmation.

| Benefits | Costs |
| --- | --- |
| No interpreter or sandbox to maintain, and no place in Parler's own analysis path for a prompt injection to turn into code. | Only the analyses Parler implements are available. A question that needs a new kind of computation needs a new operator, or a ThingWorx service that provides it. |
| Every number an operator produces comes from tested, deterministic code, so the same data gives the same answer. | Less flexible than a code interpreter for one-off exploration. |
| Operators report completeness, coverage and unknown time consistently, which ad-hoc code rarely does. | New chart kinds need server, wire, UI and test work; arbitrary model-designed graphics are not possible. |
| Results can be reviewed and reproduced from the recorded tool calls. | Parameters still come from the model, so a well-formed but mistaken request (for example, the wrong window) is possible; the parameters used are reported back so they can be checked. |

Note that this describes how Parler's own computations run, not where data flows: summaries and small result sets are
still sent to the LLM provider so that the model can answer.

## 4. What you can audit

Parler records what the agent did in ordinary ThingWorx entities and logs, so an administrator can review a
conversation after the fact without special tooling.

| Record | What it shows |
| --- | --- |
| `AgentMessageStream` | Every message of a conversation: user prompts, assistant answers, the tool calls the model made with their arguments (protected values redacted), the tool results, and per-answer token usage. |
| `AgentThreadDataTable` | Each conversation: its owner, the agent, title, creation and update times, and when history was cleared. |
| `AgentLlmCallStream` | A ledger of every LLM call attempt: provider, model, call kind, timing and outcome, linked to the conversation and request. |
| LLM usage helper | Usage and cost reports for a time range, filtered by conversation, agent or model, priced from a configurable price table and exportable as CSV to a FileRepository. |
| Approval log (`PARLER_HITL`) | Each approval request, decision, rejected decision, continuation outcome and expiry, with the user, conversation and request ids. The outcome is also written into the conversation stream. |
| ThingWorx ApplicationLog | Tool-schema size per round, rate-limit rejections, Playbook runs, protected-value blocks (codes only, never values) and the client connection handshake. |
| Conversation history export | A conversation can be exported as JSON, with its charts and tables rebuilt from the recorded tool results. |
| Table CSV exports | Large tables shown to a user can be written as CSV files to a FileRepository. |
| Runtime snapshot | `GetAgentRuntimeSnapshot` shows the configuration the agent is actually running: prompt source, tools, skills, policies, taxonomy, Playbooks and fingerprints of the repository files they came from. |
| Configuration repository | Skills, policies, Playbooks, taxonomies and tool manifests are plain files in a FileRepository, so changes can be reviewed and versioned like any other configuration. |

Some state is deliberately not kept: pending approvals live in memory for at most 15 minutes and do not survive a
restart, and the live task-progress panel and Playbook run state are not stored. Their durable trace is the approval
log and the conversation stream.

For deeper checks, the repository includes a diagnostics collector (`uv run parler-collect-live`) that bundles logs,
stream rows and configuration for a support review, and an evaluation harness (`uv run agent-eval`) that replays
test conversations and checks the recorded tool traces.

## Where to go next

- [`how-to-build.md`](./how-to-build.md): build the two extensions.
- [`training/`](./training/): a course that builds a complete sample application step by step.
- [`docs/agent/AGENT-CONTEXT.md`](./docs/agent/AGENT-CONTEXT.md): the agent extension in depth.
- [`CONTRACTS/`](./CONTRACTS/): the normative wire, chart and table contracts.
