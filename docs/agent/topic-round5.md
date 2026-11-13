# Topic round 5 — a topic's working text is a Markdown file, `<chunkId>.md`

Topic: `topic-round5`

This document is the proposed design and implementation guide for the fifth round of method loading in the agent extension (`parler-agent/`). **Current baseline.** Rounds 1 to 4 are shipped (agent 0.1.251 to 0.1.254; `docs/agent/topic-round1.md` to `topic-round4.md`). `load_topic`, the `topics` lane, the origin marker and seven `features` names exist. Only R20 and R21 below are new work, and the change is deliberately small: the file extension of a topic's working text, and one `features` name that makes the change observable. Read round 1 sections 3 and 5 for how `load_topic` and the package layout were first specified; this document does not repeat them. Examples use invented names (`demo-guide`, `topic-demo-r1`); nothing here depends on a real deployment's data.

**Design agreement gate:** the Implementor MUST NOT start coding until every User/orchestrator-designated Reviewer agrees with this design in the latest pushed design-agreement round's `### Direction Verdict`, each stating `Fresh-thread readiness: PASS` or `REDIRECT` and an explicit `continue`, `redirect` or `stop`. Absence of a review is not agreement. The Implementor reads repo-root `AGENTS.md` and `docs/review-framework.md` first.

## Fresh-thread implementation context

Before changing files, the agent MUST read repo-root `AGENTS.md` and `docs/review-framework.md`.

- **BUSINESS_CONTEXT:** A topic is a prepared method: an indexed chunk row plus one working-text file in the unit's document package, produced by an external generator and loaded whole by `load_topic`. Besides the model, people read that file: an author checking what the generator produced, a reviewer comparing two revisions, an operator looking at what is deployed. The text is already Markdown (headings, numbered steps, tables), but the contract names the file `<chunkId>.txt`, so editors, repository viewers and review tools show it as unformatted text and the structure the model reads is invisible to the person. The change is that the file is named `<chunkId>.md`. The model sees no difference: the tool returns the same plain-text carrier with the same body.
- **CURRENT_IMPLEMENTATION:** `DocumentKnowledgeRuntime.executeLoadTopic(call, settings, agentName, TopicReaderFactory)` (`tools/`) validates the reference, the injected scope and the index row, then builds the path with `DocumentKnowledgePaths.join(packagePath, "topics/" + chunk + ".txt")` and reads it through `DocumentKnowledgeRepositoryReader.loadText` with a fresh current-user reader. That line is the only place in `parler-agent/src/main` that names the `topics/` directory; the index builder never reads it. A read that throws gives `TOPIC_LOAD_FAILED` ("Topic text could not be read."), except a platform permission denial, which gives `DOCUMENT_REPOSITORY_UNAVAILABLE`; a null or blank body gives `TOPIC_LOAD_FAILED` ("Topic text is empty."); a body over 7,000 UTF-16 code units, or a carrier at or over `TopicMethodText.MAX_RESULT_CHARS`, gives `TOPIC_TOO_LARGE`. On the real platform a missing working file makes `LoadText` throw a 404 `InvalidRequestException` ("File Does Not Exist"), which is not a permission denial (only 401/403 are), so it surfaces as `TOPIC_LOAD_FAILED` / "Topic text could not be read.". The offline fixture `TopicTestFixtures` serves the body only for the path ending `.txt`, from the resource `topic-demo-r1.txt`, and eight test classes share it. Section 2 gives the verified facts.
- **TERMS_AND_CONTRACTS:** The *working file* is `<documentKnowledgeRootPath>/<docId>/topics/<chunkId>.<ext>`; today `<ext>` is `txt`, after this round `md`. The *carrier* is the plain-text result of `load_topic` (a header with topic, document, SHA-256 and character count, then the complete body); it is not changed. A *chunk id* names a revision (`topic-<id>-r<n>`); a renamed file with unchanged text keeps its chunk id, a changed text still needs a new one (round 1). No `CONTRACTS/*.md` file mentions `topics/`, `load_topic` or the working file, so no contract edit or `CONTRACT_VERSION.md` bump is expected; if implementation finds one that must change, that edit and the bump land in one commit. `docs/agent/document-chunk-tools.md` owns the package layout and `load_topic` semantics; `docs/agent/configuration-repository.md` owns `features`. Section 10 is the glossary.
- **DEPENDENCIES_AND_HANDOFFS:** Rounds 1 to 4, already on the integration branch. The external generator and its deployment tool are the producer: after this round they must publish `<chunkId>.md` and may read `features` from `GetAgentRuntimeSnapshot` to tell which name an installation reads (R21). That reading has three states, not two: `topic-load/1` with `topic-markdown-file/1` means `.md`; `topic-load/1` without it is the legacy loader of 0.1.251 to 0.1.254, which reads `.txt`; no `topic-load/1` means no Java topic loader is registered (for example document built-ins disabled on a round-5 agent) and establishes no file name, so a producer publishes both unchanged copies or waits until the loader is registered, rather than infer a name. The generator's migration order is in section 7; it is not work in this repository. No later topic consumes other output. No prior conversation, review packet or driver file supplies implementation knowledge.
- **SCOPE_AND_BOUNDARIES:** Implement R20, R21 and P30–P34 as one phase. The loader keeps its ids, scope rule, index lookup, permission model, error codes, 7,000-unit bound, carrier format and SHA-256 rule exactly. Do not add a fallback to the old `.txt` name (the User decided on a hard switch), a setting, a second extension, a front-matter or title rule for the body, a body transformation, a manifest extension, a new search-result field, or a version or changelog edit on the topic branch. Section 1 lists what is out of scope.
- **CARRIED_DECISIONS:** The working file name is `<chunkId>.md`, read from `topics/` with the same reader as before; there is no fallback to `.txt`, so a package that has only the old file fails with `TOPIC_LOAD_FAILED`, the same code as any other unreadable body. The body is delivered exactly as read; nothing in the runtime treats it as rendered Markdown. A feature name, `topic-markdown-file/1`, tells the generator that an installation's registered loader reads `.md`; its absence means `.txt` only when `topic-load/1` is present, and nothing when `topic-load/1` is absent (R21). A rename of unchanged text keeps its chunk id and its index row. Section 9 gives the reasons.
- **IMPLEMENTATION_LANDING:** Under `parler-agent/src/main/java/com/thingworx/things/agent/`: `tools/DocumentKnowledgeRuntime` (the one path line, R20) and `RuntimeFeatures` (R21). Tests land in `parler-agent/src/test/java/com/thingworx/things/agent/tools/`: the fixture `TopicTestFixtures` and the resource `src/test/resources/document-knowledge/topic-round1/topic-demo-r1.txt` (renamed to `.md`), additions to `TopicLoadTest`, and the deliberate edit to `ActiveMethodLifecycleTest` that section 6 lists. Sections 5 and 7 give the order and the documentation paths.
- **VERIFICATION_AND_CLOSE:** Local acceptance is P30–P34, which state one expected outcome for each rule, plus `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`, using only the User-maintained `twx-lib/all` set and a compatible local Java; a missing prerequisite is reported, not replaced. The User alone imports the ZIP and runs the section 8 live check; agents prepare the checklist and record supplied outcomes. Push to `origin/topic-round5` and submit for review per the framework. The User or orchestrator merges into the integration branch `nearterm`, cuts the version and archives.

## 1 What this change is for

Round 1 fixed the working file as `<chunkId>.txt` and described its content as plain text. The text the generator writes is Markdown, and the package already stores Markdown elsewhere (`markdown/manual.md`). The extension does not help anyone: the runtime reads bytes and never interprets them, and the model reads the same characters whatever the file is called. For people the extension matters, because every ordinary viewer renders `.md` and shows a `.txt` raw. This round renames the contract and nothing else.

**What this round guarantees, and what it does not.** After it, a topic is loaded from `topics/<chunkId>.md`. It does **not** read a `.txt`, so a package published for agent 0.1.254 or earlier needs the `.md` files added before the topic loads on this version. It does not render, validate or transform the text.

### In scope (round 5)

- **R20** `load_topic` reads `topics/<chunkId>.md` and no other name.
- **R21** One `features` name, `topic-markdown-file/1`, so that an installation's behaviour is observable.

### Out of scope (do not do these)

- Reading `.txt` as a fallback, preferring one name over the other, or reading both. The User chose a hard switch; section 9 gives the consequence and the migration order that keeps it painless.
- Any rule about the body: a title line, front matter, a Markdown parser, escaping, a different size bound, or a different carrier. The body is what `LoadText` returns.
- Changing chunk ids, index rows, `chunks.jsonl`, search scoring, the topic lane, scope rules, ids validation, error codes or `TOPIC_TOO_LARGE`.
- Any model-facing text. The `load_topic` description says the result is plain text; that describes the carrier and stays true.
- Editing the published, shipped changelog entries, `topic-round1.md`'s body text, or anything under `docs/archived/`.

## 2 Verified facts this design relies on

All read at the shipped tree (`nearterm`, agent 0.1.254; re-read on `topic-round5` before review 0). Platform behaviour (items 3 and 4) is read from the read-only ThingWorx source checkout named in `AGENTS.md`, not from a live system.

1. **One read site.** `executeLoadTopic` in `tools/DocumentKnowledgeRuntime` is the only code that builds a `topics/` path; a search of `parler-agent/src/main` for `topics/` finds that one line. The search and index code read `manifest.json` and `chunks/chunks.jsonl` and never the `topics/` directory.
2. **Ids are validated first.** The reference is checked before the index and before any path exists: 1–256 UTF-16 code units, no surrounding whitespace, no `..`, `/`, backslash, control or line-separator character, not `.`. The extension is appended afterwards, so changing it changes no path-traversal property.
3. **Failure mapping** is as `CURRENT_IMPLEMENTATION` states. A missing file reaches the loader as a thrown read: the fixture throws `FileNotFoundException`, and the platform's `FileRepositoryThing.LoadText` calls `checkFileExists`, which throws `InvalidRequestException("File Does Not Exist", STATUS_NOT_FOUND)`. `PlatformAccess.isPermissionDenial` walks the cause chain and returns true only for a `GenericHTTPException` with 401 or 403, so a 404 is `TOPIC_LOAD_FAILED` / `Topic text could not be read.`, not `DOCUMENT_REPOSITORY_UNAVAILABLE`. The production reader `FileRepositoryRepositoryReader.loadText` also returns `""` when the service gives no row; that path is `TOPIC_LOAD_FAILED` / `Topic text is empty.`. Both carry `TOPIC_LOAD_FAILED`.
4. **`.md` files already live in these stores, and the platform read has no extension rule.** The package layout contains `markdown/manual.md`; `RepositorySkillScanner` reads `/skills/<dir>/SKILL.md` through the same reader type. The reader passes the path to the platform's `LoadText`, which applies `PathUtilities.validatePath` (empty-path and relative-segment checks only), checks existence and not-a-directory, and reads the file as a string; nothing in that chain looks at the extension. Whether a real deployment's upload path stores an `.md` file is still proved by the User's live check (section 8 item 1).
5. **The carrier does not carry the file name.** `TopicMethodText.format` writes topic id, document id, SHA-256 and character count, then the body. A renamed file with the same bytes therefore produces the same result and the same SHA-256.
6. **The fixture.** `TopicTestFixtures` declares `body = resource("topic-demo-r1.txt")` (line 21) and its reader answers only the path `"/document-knowledge/" + doc + "/topics/" + chunk + ".txt"` (line 59); every other unknown path throws `FileNotFoundException`. The resource directory is `src/test/resources/document-knowledge/topic-round1/` (`chunks.jsonl`, `manifest.json`, `topic-demo-r1.txt`). The resource content is a short Markdown body with a non-ASCII character.
7. **Existing coverage.** `TopicLoadTest` covers exact whole-text loading, the SHA-256 and character count, invalid ids, not-indexed / out-of-scope / missing / empty / oversized bodies, index failures, warm-index permission, and the unconfigured repository. All of it runs through the fixture, so the fixture rename carries it. The fixture is also used by `TopicOfferFlowTest`, `ConversationEntityScopeTest`, `SearchUnitScopeTest`, `TopicMethodApprovalFlowTest`, `TopicLaneTest`, `ActiveMethodLifecycleTest` (all in `tools/`) and `compaction/ActiveTopicRetentionTest`; those that load a body do so through the same fixture path, so they follow the rename without edits. `TopicRound3Fixtures` serves only `manifest.json` and `chunks.jsonl` and never a topic body. No other test reader serves a `topics/` path.
8. **Features.** `RuntimeFeatures.names(true)` is `topic-load/1, active-method-tools/1, entity-document-scope/1, topic-offer/1, topic-lane/1, search-unit-scope/1, topic-signal-exact/1`; `names(false)` is `active-method-tools/1`. `AgentThing` (`GetAgentRuntimeSnapshot`) passes `_toolRegistry.hasTool("load_topic")` to it. `ActiveMethodLifecycleTest` asserts the exact list (lines 104–106). `docs/agent/configuration-repository.md` has one row per name and a sentence with the exact value (lines 351–362).
9. **Where the path is documented.** `docs/agent/document-chunk-tools.md` line 62 (the package layout block) and line 701 (§6.6 `load_topic`) name `topics/<chunkId>.txt`. `docs/agent/topic-round1.md` names it at lines 16, 144, 246 and 335 as the original decision. `parler-agent/CHANGELOG.md` names it in the 0.1.251 entry (history; not edited). No other file under `docs/` outside `docs/archived/` and `docs/under-review/` names the path, and no `CONTRACTS/*.md` file does.
10. **Model-facing text names no file.** The `load_topic` description in `DocumentKnowledgeToolSchemas` (line 29) says the tool "Returns plain text with identity, SHA-256 …" — the carrier — and `llm_tool_routing_guide.txt` line 43 says only to load a `topic` hit with `load_topic`. Neither mentions `topics/` or an extension, so neither changes.
11. **No in-repository package to migrate.** No directory named `topics` and no topic working file exists under `dev_data/`, `data/` or `test_scripts/`, and no script in the repository writes or uploads one; the only package-shaped data are the test resources. The migration in section 7 is the external producer's alone.

## 3 Required behaviour

### R20 `load_topic` reads `<chunkId>.md`

In `executeLoadTopic` replace the file name suffix `".txt"` with `".md"`: the path is `DocumentKnowledgePaths.join(packagePath, "topics/" + chunk + ".md")`. Nothing else in the method changes, in particular the order of checks, the fresh current-user reader, the error codes and messages, the size bounds and the carrier. There is no second read: when the `.md` file is absent the call fails exactly as any unreadable body does (`TOPIC_LOAD_FAILED`, `Topic text could not be read.`), and the old `.txt` is never read, even when it is present.

The body is delivered exactly as `LoadText` returns it. Nothing interprets Markdown, strips a title, or treats a leading `#` specially.

### R21 Feature

`RuntimeFeatures.names(true)` appends `topic-markdown-file/1` after `topic-signal-exact/1`; `names(false)` is unchanged. The exact list is asserted in `ActiveMethodLifecycleTest` and is updated deliberately. The name means the installation's registered Java loader reads the working file as `<chunkId>.md`; it is present exactly when `topic-load/1` is. Because `AgentThing.GetAgentRuntimeSnapshot` passes the actual registry state (`_toolRegistry.hasTool("load_topic")`), a round-5 agent whose document built-ins are disabled reports neither name. A producer therefore reads three states:

| `features` contains | Meaning for the producer |
| --- | --- |
| `topic-load/1` and `topic-markdown-file/1` | The registered loader reads `<chunkId>.md` (this round and later) |
| `topic-load/1` without `topic-markdown-file/1` | The legacy loader of agents 0.1.251 to 0.1.254, which reads `<chunkId>.txt` |
| no `topic-load/1` | No Java topic loader is registered. No file name is established: the installation may be a round-5 agent with built-ins disabled that reads `.md` once they are enabled. Do not infer a name; publish both unchanged copies (the safe default) or wait until the loader is registered |

This qualification is documentation of the existing capability model; it adds no feature, no fallback and no registration change, and P33 is unchanged.

## 4 Scan: hazards of the same class

| # | Hazard | Handling |
| --- | --- | --- |
| 1 | A package published for 0.1.254 or earlier has only `.txt` files | The topic is still indexed and offered by the lane (the row is in `chunks.jsonl`), and `load_topic` then returns `TOPIC_LOAD_FAILED`. This is the cost of the hard switch. The remedy is to add the `.md` file, which needs no new row or chunk id (section 7) |
| 2 | A package published with `.md` only is read by an older agent | The older agent reads `.txt` and fails the same way. Publishing both files side by side during a migration is safe in both directions: each agent reads only its own name |
| 3 | The same chunk id with changed text | Unchanged rule from round 1: a changed text needs a new chunk id. Only a rename of identical text keeps the id |
| 4 | Both files present and different | The runtime reads only `.md`. The `.txt` is ignored, never merged, never used as a fallback; leaving a stale `.txt` behind has no runtime effect |
| 5 | A chunk id that already ends in `.md` or `.txt` | Allowed by the id rules; the file would be `<id>.md.md`, the same as today's `<id>.txt.txt`. No special case |
| 6 | The platform refuses or mangles a `.md` upload | Not seen in this tree (section 2 item 4); the live check proves it on the real platform. The package already holds `.md` files |
| 7 | A model or user message mentions the file name | The tool result carries no file name (section 2 item 5); no model-facing text names it |
| 8 | Index cache | The index (`chunks.jsonl`) is cached for its TTL; the body is read on every load and never cached, so a newly published `.md` is found on the next `load_topic` without waiting for the TTL |
| 9 | Another consumer of the layout | None in this repository: the index builder never reads `topics/` (section 2 item 1). The external generator is the producer (section 7) |
| 10 | Normative contracts | No contract names the path (`TERMS_AND_CONTRACTS`); no bump |
| 11 | A producer writes `.MD`, `.markdown` or `.md.txt` | The name is exactly lowercase `<chunkId>.md`. The loader requests that string and nothing else; whether a differently cased name is found depends on the repository's file system and is not promised. Producers write the exact name; no case folding or alias is added |
| 12 | The missing `.md` is misreported as an unavailable repository | The platform's missing-file error is a 404, and only 401/403 map to `DOCUMENT_REPOSITORY_UNAVAILABLE` (section 2 item 3). P31 pins the 404 case so that the hard-switch failure stays `TOPIC_LOAD_FAILED` and is not mistaken for a permission or configuration fault |
| 13 | Byte order mark or encoding of the `.md` file | Unchanged: the platform reads the file as a string exactly as it read the `.txt`, and the body is delivered as returned. A producer that copies the `.txt` bytes unchanged gets the same body and SHA-256 |
| 14 | A producer treats "no `topic-markdown-file/1`" as ".txt" | Wrong when `topic-load/1` is also absent: a round-5 agent with document built-ins disabled lists neither name and reads `.md` once they are enabled. R21's three-state table, the `configuration-repository.md` sentence and the migration order say so; publishing both copies is the safe default |

## 5 Change list

1. `tools/DocumentKnowledgeRuntime.executeLoadTopic`: the suffix (R20).
2. `RuntimeFeatures`: R21.
3. Tests and documentation (sections 6 and 7).

Implementation order: rename the fixture first (the resource and the reader's path). The existing `TopicLoadTest` cases then fail on the unmodified tree, because it still requests `.txt`; that is the red step. Then R20 turns them green, then P31 and P32, then R21.

## 6 Tests

Reuse `TopicLoadTest` and `TopicTestFixtures`. Synthetic fixtures only (`demo-guide`, `topic-demo-r1`).

| Case | What it checks |
| --- | --- |
| **P30 loads the `.md` file** | With the fixture renamed so that its reader answers only `.../topics/topic-demo-r1.md` and its resource is `topic-demo-r1.md`, the existing `TopicLoadTest` cases (whole text, repeat load idempotent, SHA-256 vector and UTF-16 character count, invalid ids, not-indexed / out-of-scope / missing / empty / oversized, index failures, warm-index permission, unconfigured repository) pass unchanged. The result for the same body bytes equals the result before the rename (same header, same SHA-256) |
| **P31 hard switch** | (a) A reader that serves the body **only** for `.../topics/topic-demo-r1.txt` and throws `FileNotFoundException` for the `.md` path: `load_topic` returns `TOPIC_LOAD_FAILED` with message "Topic text could not be read.", no active method is recognized (the `error` helper's existing check), and the `.txt` path is **never requested**. (b) A reader that serves both names with different bodies returns the `.md` body and requests the `.md` path exactly once. (c) A reader whose `.md` read throws the platform's missing-file shape, `new InvalidRequestException("File Does Not Exist", StatusCode.STATUS_NOT_FOUND)`, gives `TOPIC_LOAD_FAILED` / "Topic text could not be read.", not `DOCUMENT_REPOSITORY_UNAVAILABLE`. Fixture support, no mocking library: `TopicTestFixtures` gains `public final List<String> paths` (every `loadText` path, in order) and `public String legacyTxtBody` (default `null`; when non-null it is returned for `.../topics/<chunk>.txt`, otherwise that path throws like any unknown path). Case (c) uses a small delegating reader in the test |
| **P32 no body rule** | A body that starts with `# Title` and a line `---` is delivered byte for byte inside the carrier, with the SHA-256 and character count of those bytes |
| **P33 features** | `names(true)` ends `…, "topic-signal-exact/1", "topic-markdown-file/1"`; `names(false)` is unchanged |
| **P34 regression** | The existing document-knowledge, topic-lane, conversation-scope, host-context, handoff and approval-flow tests stay green, apart from the listed edit below; in particular every fixture user named in section 2 item 7 passes without an edit of its own. Searches and the lane are untouched, so no scoring or offer test moves |

Existing test that changes, deliberately and only this:

| Test | Why | Edit |
| --- | --- | --- |
| `ActiveMethodLifecycleTest`, the exact feature list | R21 | Add the name |

The fixture rename itself (`TopicTestFixtures` lines 21 and 59, and the resource file) is not a behaviour change of any test; use `git mv` for the resource so history follows it.

## 7 Build, verify, version, process

As earlier rounds: `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`; live work is the User's; no version, `metadata.xml` or `CHANGELOG.md` edits on the topic branch; work on the branch the User or orchestrator creates (`topic-round5`), never on `nearterm`.

Documentation to update with the code:

- `docs/agent/document-chunk-tools.md`: the package layout block (line 62: `topics/<topicChunkId>.md`) and §6.6 (line 701: the working file is `<root>/<docId>/topics/<chunkId>.md`; add that the text is Markdown by convention, is delivered unchanged, and that a rename of unchanged text keeps its chunk id; and that a package published for agent 0.1.254 or earlier needs the `.md` file added).
- `docs/agent/configuration-repository.md`: a new last row of the `features` table, after `topic-signal-exact/1`, with this substance: `topic-markdown-file/1` — `load_topic` reads a topic's working text from `<root>/<docId>/topics/<chunkId>.md` and never from `<chunkId>.txt`; present exactly when `topic-load/1` is; it does not mean the runtime renders, validates or transforms Markdown; see `document-chunk-tools.md` §6.6. The exact-value sentence becomes `["topic-load/1","active-method-tools/1","entity-document-scope/1","topic-offer/1","topic-lane/1","search-unit-scope/1","topic-signal-exact/1","topic-markdown-file/1"]` with the Java loader registered; the other value is unchanged. Directly after the exact-value sentence, add the three producer states of R21 in one or two sentences: both names mean `.md`; `topic-load/1` alone means the legacy `.txt` loader; without `topic-load/1` no Java loader is registered and no file name is established, so a producer publishes both copies or waits. The pre-existing blank line inside that table (between the `active-method-tools/1` and `entity-document-scope/1` rows) is not this round's to touch.
- `docs/agent/topic-round1.md`: add one line directly under its title, and change nothing else in it: `Superseded in one detail by topic-round5: from agent 0.1.255 the working file is topics/<chunkId>.md, not .txt.` (Name the agent version only if the User has fixed it; otherwise write "from the agent version that ships topic-round5".)
- `docs/agent/README.md`: index this design with one line after the `topic-round4.md` entry (a topic's working file is `topics/<chunkId>.md`, the hard switch from `.txt`, the `topic-markdown-file/1` feature, and local acceptance plus the User live checklist).

The design document itself (`docs/agent/topic-round5.md`) is not edited by implementation except to correct a statement the code proves wrong; such a correction is named in the implementation packet.

**Producer migration order (for the orchestrator and the external generator; not work in this repository).** Because the two names are different files, a package can carry both at once, and each agent reads only its own name. So: (1) add `<chunkId>.md` beside each existing `<chunkId>.txt`, with the same bytes, leaving every index row untouched; (2) import the agent that carries R20; (3) check `GetAgentRuntimeSnapshot.features` for `topic-markdown-file/1` and load one topic; (4) remove the `.txt` files when convenient. A generator that targets both generations reads `features` and applies the three states of R21: `.md` when both names are present, `.txt` when only `topic-load/1` is, and both copies when `topic-load/1` is absent, since a disabled loader's future file name is not observable. Changed text still needs a new chunk id.

**Release note for the integration cut (orchestrator).** The changelog entry for the version that ships this round should say: a topic's working text is read from `topics/<chunkId>.md`; the `.txt` of 0.1.251 to 0.1.254 is no longer read; add the `.md` file beside the old one, with the same bytes, before importing; no index row changes; one new `features` name.

## 8 Done means

Local acceptance: P30–P34 and the full command pass, canonical docs match the code, the slice is pushed and submitted for review.

The User's live check, after importing the ZIP, with a synthetic or test package that has one topic row and its text:

1. With **only** `<chunkId>.txt` present: a search offers the topic and `load_topic` returns `TOPIC_LOAD_FAILED` with message "Topic text could not be read." (the platform's 404; `DOCUMENT_REPOSITORY_UNAVAILABLE` here would mean a permission or configuration fault, not the hard switch). Upload `<chunkId>.md` (same bytes) beside it, without touching the row: the next `load_topic` returns the topic, and its SHA-256 equals the one the same text gave before. This also shows the platform stores and returns an `.md` file in the repository.
2. With both files present and the `.txt` edited to a different text, `load_topic` returns the `.md` text.
3. `GetAgentRuntimeSnapshot.features` lists `topic-markdown-file/1`.
4. Not claimed: that an older agent reads the `.md` file, that the runtime renders or validates Markdown, that a changed text keeps its chunk id.

What to hand back, sanitized: for each step, the `load_topic` result's header lines (`topic`, `document`, `sha256`, `chars`) or its error JSON (`code`, `message`, `docId`, `chunkId`), the `features` array, and the agent version from the snapshot. Leave out API keys, credentials, server URLs and host names, user names, and any real package's body text; the synthetic test package's ids may stay. The agent records the supplied outcome in the topic packet; it does not run the steps.

## 9 Decisions proposed for reviewer agreement, and what is deferred

- **Rename the contract; do not teach the loader two names.** The User decided on a hard switch. It leaves one rule instead of two, no precedence question and no stale-file ambiguity; the cost (hazard 1) is a one-time re-publish that needs no new row, and the migration order in section 7 removes any outage: add the `.md` beside the `.txt` first, then import. The feature is recent (0.1.251), so few packages carry topics yet; that is why the cost is acceptable now and would grow later.
- **A feature name for a file name.** An installation's registered loader reads exactly one of the two names, and a producer cannot tell which from the package. An installation without a registered loader (no `topic-load/1`) reads neither, and its future name is not observable; R21's three states say so and the producer publishes both copies there. `features` is the existing observable mechanism (rounds 3 and 4 use it the same way), costs one list entry and one test edit, and lets a deployment tool refuse to publish the wrong name. Without it the producer would have to infer from the agent version.
- **No body rule.** The generator already writes Markdown; the runtime has never interpreted the text and must not start now. A title or front-matter rule would change what the model reads and the SHA-256 for no runtime benefit.
- **Chunk ids stay.** The id names a revision of the content. A rename of identical bytes is not a new revision, so the row, the id and the SHA-256 stay; this also keeps retired-revision handling in the producer unchanged.
- **Deferred:** a read-both or fallback mode; removing stale `.txt` files from a package (the runtime ignores them); any Markdown-aware behaviour; renaming the `topics/` directory.

## 10 Glossary

- **working file** — the file in `<root>/<docId>/topics/` that holds a topic's method text; `<chunkId>.md` from this round, `<chunkId>.txt` before.
- **carrier** — the plain-text result of `load_topic`: a header (topic id, document id, SHA-256, character count) and the complete body.
- **hard switch** — the loader reads one name only; there is no fallback to the other.
- **producer** — the external generator and deployment tool that write the index row and the working file; this repository only consumes them.
