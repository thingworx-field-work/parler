# Appendix: Workshop support workflow

A workshop produces two kinds of support requests: questions about the material, and reports that a live prompt did
not behave as expected. This appendix describes how an instructor can collect both and answer them with the product's
own tools.

## Collecting questions

Use whatever channel the class already has (a shared tracker, a chat channel, or a form). Ask participants to include:

- the chapter or exercise they were working on;
- the exact prompt they sent and the conversation id shown in the widget;
- the approximate time of the failure;
- the `parler-agent` and `parler-ui-widget` versions they imported;
- what they expected and what they saw (a screenshot of the chat is usually enough).

A consistent template saves a round trip for most reports.

## Collecting runtime diagnostics

When a participant reports a live chat problem, the useful evidence is spread across three places:

- `ApplicationLog` rows: what the agent loop, provider, tool router, HITL, taxonomy resolver, and playbook runner logged;
- `AgentMessageStream` rows: what the conversation actually persisted (tool calls, tool results, final answers, usage);
- the AgentThing runtime snapshot: which skills, playbooks, extended tools, taxonomy, and policy state were loaded when
  it answered.

The third item matters most in a workshop: a file in the configuration repository can look correct while the AgentThing
ran with a stale, missing, or invalid loaded snapshot.

From a checkout of this repository, collect all three into one support bundle:

```bash
uv run parler-collect-live -o logs
uv run parler-collect-live --window 30m --conversation-id <conversationId> -o logs
```

The command reads `DEV_SERVER` and `DEV_KEY` from the environment or from a `.env` file in the current directory. See
[`docs/agent/collection-tool.md`](../../docs/agent/collection-tool.md) and
[`docs/agent/live-diagnostics.md`](../../docs/agent/live-diagnostics.md) for the options and the bundle layout.

To compare a participant's configuration repository with the course version, download it with
`uv run load-file-tree -d -i <local-folder> -t <RepositoryThing>`; upload a corrected tree with
`uv run load-file-tree -i <local-folder> -t <RepositoryThing>`.

Treat server URLs and application keys as sensitive. Do not commit them, paste them into a shared channel, or include
them in a support bundle you pass on. Review a bundle before sharing it: it contains conversation content.

## Triage

| Request type | Support action |
|--------------|----------------|
| course material confusion | point to the relevant chapter or appendix in this training material |
| Parler behavior question | check the product docs and `CONTRACTS/` in this repository |
| live prompt failure | collect the log, stream, and runtime snapshot, then analyze |
| confirmed product bug | file it with the product team, attaching the reviewed support bundle |

## Instructor rule

An AI-assisted answer is a support accelerator, not a substitute for instructor judgment. If the diagnosis implies a
product bug or a risky workaround, review it before asking participants to change their environment.
