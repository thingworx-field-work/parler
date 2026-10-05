/**
 * JSDoc-only module — wire/UI shapes (see CONTRACTS/CHART_CONTRACT.md, CONTRACTS/UI_CLIENT_PROTOCOL.md)
 *
 * @typedef {'line' | 'bar' | 'scatter' | 'pie' | 'histogram' | 'boxplot' | 'heatmap'} ChartKind
 *
 * @typedef {object} ChartHeatmap a row × column matrix from a two-key long table (CHART_CONTRACT §3.0h)
 * @property {string[]} rows 1–24 unique non-empty row keys in source order
 * @property {string[]} cols 1–48 unique non-empty column keys in source order
 * @property {(number | null)[][]} values `rows.length` arrays of `cols.length` finite numbers or `null` (missing)
 * @property {string} valueLabel what a cell value is (unit / measure)
 * @property {number} missingCount the number of `null` cells
 *
 * @typedef {object} ChartBoxGroup one `box_summary` group (CHART_CONTRACT §3.0g)
 * @property {string} key unique, non-empty
 * @property {number} n positive integral
 * @property {number} excludedCount non-negative integral
 * @property {number} min
 * @property {number} whiskerLow
 * @property {number} q1
 * @property {number} median
 * @property {number} q3
 * @property {number} whiskerHigh
 * @property {number} max
 * @property {number[]} outliers at most 20, each outside the whiskers and inside `[min, max]`
 * @property {number} outlierCount `outliers.length` = `min(outlierCount, 20)`
 *
 * @typedef {object} ChartBoxplot five-number summaries from `box_summary` (CHART_CONTRACT §3.0g)
 * @property {'tukey_1_5_iqr_linear_p_v1'} method
 * @property {ChartBoxGroup[]} groups 1–24 groups in source order
 *
 * @typedef {object} ChartHistogram binned distribution from `bin_numeric` (CHART_CONTRACT §3.0f)
 * @property {number[]} edges strictly increasing, `counts.length + 1`
 * @property {number[]} counts non-negative integral values, 1–50 bins
 * @property {number[]} densities same length as `counts`
 * @property {'count' | 'density'} mode which array is drawn
 * @property {number} validCount
 * @property {number} excludedCount
 * @property {number} belowRangeCount
 * @property {number} aboveRangeCount
 * @property {'explicit_edges_v1' | 'equal_width_v1'} method
 *
 * @typedef {object} ChartSeries
 * @property {string} name
 * @property {string[]} x
 * @property {number[]} y
 *
 * @typedef {object} ChartBlock
 * @property {ChartKind} kind
 * @property {string} [chartId]
 * @property {string} [title]
 * @property {string} [x_label]
 * @property {string} [y_label]
 * @property {'vertical' | 'horizontal'} [orientation] bar only; absent means vertical (CHART_CONTRACT §3)
 * @property {'stacked' | 'percent'} [stackMode] bar only, two or more series; absent means grouped (CHART_CONTRACT §3)
 * @property {'absolute' | 'elapsed' | 'normalized'} [xAxisMode]
 * @property {{ start: number, end: number }} [elapsedDomain]
 * @property {{ start: number, end: number }} [normalizedDomain]
 * @property {{ start: string, end: string }} [requested_time_range]
 * @property {{ sourceResolved?: string, sourceCacheId?: string, rowCount?: number, pointCount?: number, truncationApplied?: boolean, [key: string]: unknown }} [source]
 * @property {ChartSeries[]} [series] required for line / bar / scatter / pie; absent on histogram / boxplot
 * @property {ChartHistogram} [histogram] histogram only
 * @property {ChartBoxplot} [boxplot] boxplot only
 * @property {ChartHeatmap} [heatmap] heatmap only
 *
 * @typedef {object} TableColumn
 * @property {string} key
 * @property {string} label
 * @property {string} baseType
 *
 * @typedef {object} TableBlock
 * @property {string} kind
 * @property {TableColumn[]} columns
 * @property {Record<string, unknown>[]} rows
 * @property {number} shownRows
 * @property {number} totalRows
 * @property {string} [sourceCacheId]
 * @property {string | null} [cacheId]
 * @property {string} [presentationTitle] Server-built disclosure header (tool metadata); preferred over column list in UI
 * @property {string} exportStatus
 * @property {string | null} exportMessage
 * @property {string | null} exportFile
 * @property {string | null} exportRepository
 * @property {string | null} exportDownloadUrl
 *
 * Host Context snapshot on user rows — `ai-parler-history-v1` / Stream export (`host-context-turn-state.md` §4.1).
 * @typedef {object} HostContextSnapshotWire
 * @property {string} schema
 * @property {boolean} [accepted]
 * @property {string} [outcome]
 * @property {string} [key]
 * @property {string} [hash]
 * @property {number} [utf8Bytes]
 * @property {boolean} [changedFromPreviousUserTurn]
 * @property {boolean} [rawJsonStored]
 * @property {string} [rawJson]
 * @property {string} [rejectCode]
 * @property {string} [rejectDetail]
 *
 * @typedef {'user'} UserKind
 * @typedef {object} ChatRowUser
 * @property {'user'} kind
 * @property {string} text
 * @property {HostContextSnapshotWire} [hostContext]
 *
 * @typedef {object} TaskStateSnapshot
 * @property {number} schemaVersion Must be {@code 1} for v1b.
 * @property {string} status
 * @property {string} [title]
 * @property {Record<string, unknown>} summary
 * @property {unknown[]} items
 *
 * Sanitized LLM usage telemetry (subset of server {@code llm_usage} / history {@code llmUsage}).
 * @typedef {Record<string, number | string | boolean>} LlmUsageWire
 *
 * @typedef {object} ChartGroupMember one slot of a chart group (CHART_CONTRACT §3.5, design §8.5)
 * @property {string} key
 * @property {number} order 0-based, equal to the array position
 * @property {string} name
 * @property {'chart'} expectedType
 * @property {'pending' | 'ready' | 'no-data' | 'error' | 'cancelled'} state
 * @property {string} [chartId] present exactly when `state` is `ready`
 * @property {string} [code] present for `no-data` / `error`
 * @property {string} [message]
 * @property {boolean} [colorShared] C3b-2a: every category key of this member is in the group's `sharedCategories.keys`
 *
 * @typedef {object} ChartGroupManifest a full manifest revision carried by a `chart_group` frame (CHART_CONTRACT §2.6)
 * @property {string} groupId
 * @property {number} revision positive integer; a higher revision replaces a lower one
 * @property {string} title
 * @property {'auto' | 'stack' | 'grid'} layout
 * @property {boolean} final
 * @property {ChartGroupMember[]} members 2–6 members in `order`
 * @property {{ expected: number, ready: number, noData: number, error: number, cancelled: number, final: boolean }} summary
 * @property {{ dimension: string, keys: string[] }} [sharedCategories] C3b-2a: `keys[i]` owns palette slot `i`; append-only
 *
 * @typedef {object} RowArtifact
 * @property {'table' | 'chart'} type
 * @property {number} seq
 * @property {string} key
 * @property {ChartBlock} [chart]
 * @property {TableBlock} [table]
 *
 * @typedef {object} ChatRowAssistant
 * @property {'assistant'} kind
 * @property {string} requestId
 * @property {string} markdown
 * @property {ChartBlock[]} charts
 * @property {TableBlock[]} tables
 * @property {RowArtifact[]} [artifacts] Cross-type append order for presentation.
 * @property {ChartGroupManifest[]} [groups] Chart groups of this answer, one entry per `groupId` at its highest accepted revision (design §8.5).
 * @property {string | null} [activity]
 * @property {string} [assistantMessageId] Stable server id for feedback (final assistant rows)
 * @property {'up'|'down'} [feedbackRating] Last persisted thumbs state from history hydrate (optional)
 * @property {string} [completedAt] ISO-8601 UTC assistant terminal instant (server completion or local cancel terminal; cutoff + history)
 * @property {LlmUsageWire} [llmUsage] Token / provider usage subset from {@code done.llm_usage} or history hydrate
 * @property {{ schemaVersion: string, sourceCacheId?: string, rowEstimate?: number, columns?: unknown[] } | null} [insightEnvelopeLoose] Further Insight — reducer sets this only via **`readInsightEnvelopeLoose`** (`chatSession.js`). **Views:** presence-only checks are OK; reading **`.schemaVersion` / `.rowEstimate` / `.columns` / …** triggers the D7 passive gate — bump **`UI_CLIENT_PROTOCOL.md`** + **`CONTRACTS/CONTRACT_VERSION.md`** + **`TABULAR_INSIGHT.md`** per **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** §View — `insightEnvelopeLoose` (D7).
 * @property {TaskStateSnapshot | null} [taskState] v1b **`task.state`** wire snapshot for this assistant row (metadata-only per **`API_CONTRACT.md`** / **`docs/agent/task-state.md`**).
 * @property {boolean} [rateControlWaiting] Live-only: **`true`** while the UI shows the provider rate-gate **waiting** tone for this row; cleared on **`resumed`**, terminal session events, non-whitespace **`assistant.append`**, **`assistant.replace`**, **`assistant.chart`**, **`assistant.table`**, and **`assistant.taskState`** (see **`UI_CLIENT_PROTOCOL.md`**).
 * @property {boolean} [turnCancelled] Set when the server ends the turn with **`session.cancelled`** (user stop); optional tombstone for product rendering.
 *
 * @typedef {ChatRowUser | ChatRowAssistant} ChatRow
 *
 * @typedef {object} ApprovalGateState
 * @property {string} requestId
 * @property {string} conversationId
 * @property {string} pendingId
 * @property {string} expiresAt
 * @property {string} toolName
 * @property {{ title: string, lines: { label: string, value: string }[] }} summary
 * @property {('approve'|'cancel'|'reject_with_comment')[]} actions
 * @property {'idle'|'submitting'|'submitted'} [submitState] Client-only decision-submit substate (**`UI_CLIENT_PROTOCOL.md`** rule **10b**); never on the wire. **`idle`**: no decision in flight, decision buttons enabled. **`submitting`**: an **`approval.decision`** uplink is in flight. **`submitted`**: the uplink was accepted — the decision reached the server, which proves nothing about whether any business operation began or succeeded. Buttons are disabled while not **`idle`**. Absent is read as **`idle`**.
 * @property {string | null} [submitError] Client-only message from a failed decision-submit uplink (**`UI_CLIENT_PROTOCOL.md`** rule **10b**). Set with **`submitState`** back to **`idle`** so the user can retry; does **not** set the global **`error`** banner, clear **busy** / **`activeRequestId`**, or close the gate.
 *
 * @typedef {object} ChatUiState
 * @property {ChatRow[]} rows
 * @property {boolean} busy
 * @property {string | null} error
 * @property {string | null} activeRequestId
 * @property {ApprovalGateState | null} approvalGate
 * @property {Set<string>} cancelledRequestIds `request_id` values ended by **`session.cancelled`** (user stop); bounded (**`UI_CLIENT_PROTOCOL.md`** rule **7b**). Matching ids ignore late non-terminal assistant reducers, late **`session.done`** / **`session.error`** / **`session.superseded`**, late **`approval.required`**, and related frames per the contract.
 * @property {Set<string>} unsupportedLocalRequestIds `request_id` values that ended the active turn via client-only **`session.cancel_unsupported_local`** and **may** receive **one** late real **`session.done`** for terminal row metadata while the id remains here (**`UI_CLIENT_PROTOCOL.md`** rules **7** / **7c**); **`session.error`** / **`session.superseded`** remove the id (**rules 8–9**). Bounded (v1 cap **32**, FIFO). Cleared on widget reset. **Not** a replay tombstone like **`cancelledRequestIds`**.
 * @typedef {object} ApiChatMessage
 * @property {'system'|'user'|'assistant'|'tool'} role
 * @property {string} [content]
 *
 * @typedef {object} UiEventSessionAck
 * @property {'session.ack'} type
 * @property {string} requestId
 *
 * @typedef {object} UiEventActivity
 * @property {'assistant.activity'} type
 * @property {string} requestId
 * @property {string} text
 *
 * @typedef {object} UiEventAppend
 * @property {'assistant.append'} type
 * @property {string} requestId
 * @property {string} text
 *
 * @typedef {object} UiEventReplace
 * @property {'assistant.replace'} type
 * @property {string} requestId
 * @property {string} markdown
 *
 * @typedef {object} UiEventChart
 * @property {'assistant.chart'} type
 * @property {string} requestId
 * @property {ChartBlock} chart
 *
 * @typedef {object} UiEventTable
 * @property {'assistant.table'} type
 * @property {string} requestId
 * @property {TableBlock} table
 *
 * @typedef {object} UiEventDone
 * @property {'session.done'} type
 * @property {string} requestId
 * @property {string} [assistantMessageId]
 * @property {string} [completedAt] ISO-8601 UTC from wire when assistant_message_id present
 * @property {LlmUsageWire} [llmUsage] From optional wire {@code llm_usage} on terminal {@code done}
 *
 * @typedef {object} UiEventError
 * @property {'session.error'} type
 * @property {string} requestId
 * @property {string} message
 * @property {string} [code]
 *
 * @typedef {object} UiEventSuperseded
 * @property {'session.superseded'} type
 * @property {string} requestId
 * @property {string} message
 * @property {string} [code]
 * @property {string} conversationId
 *
 * @typedef {object} UiEventSessionCancelled
 * @property {'session.cancelled'} type
 * @property {string} requestId
 * @property {string} conversationId
 * @property {string} [reason]
 * @property {string} [message]
 *
 * Client-only: **`CancelUserPrompt`** returned **`unsupported`** — clears **busy** without **`cancelledRequestIds`** / **`turnCancelled`** (**`UI_CLIENT_PROTOCOL.md`** rule **7c**).
 * @typedef {object} UiEventSessionCancelUnsupportedLocal
 * @property {'session.cancel_unsupported_local'} type
 * @property {string} requestId
 * @property {string} conversationId
 *
 * @typedef {object} UiEventApprovalRequired
 * @property {'approval.required'} type
 * @property {string} requestId
 * @property {string} conversationId
 * @property {string} pendingId
 * @property {string} expiresAt
 * @property {string} toolName
 * @property {{ title: string, lines: { label: string, value: string }[] }} summary
 * @property {('approve'|'cancel'|'reject_with_comment')[]} actions
 *
 * @typedef {object} UiEventApprovalResolved
 * @property {'approval.resolved'} type
 * @property {string} requestId
 * @property {string} conversationId
 * @property {string} pendingId
 * @property {'approved'|'cancelled'|'expired'|'rejected'} outcome
 * @property {boolean} [executed]
 * @property {{ code?: string, message: string }} [error]
 * @property {'gateway_user_stop'} [hitlResolutionSource] From wire **`hitl_resolution_source`** when present; **only** **`gateway_user_stop`** (gateway **`CancelUserPrompt`** parked cancel — **`API_CONTRACT.md`** / **`UI_CLIENT_PROTOCOL.md`**).
 *
 * Client-only (all three): decision-submit substate for an open gate — never received from the
 * server, never sent on the wire. Each matches on **`pendingId`** only (**`UI_CLIENT_PROTOCOL.md`** rule **10b**).
 * @typedef {object} UiEventApprovalDecisionSubmitting
 * @property {'approval.decisionSubmitting'} type
 * @property {string} pendingId
 *
 * @typedef {object} UiEventApprovalDecisionAccepted
 * @property {'approval.decisionAccepted'} type
 * @property {string} pendingId
 *
 * @typedef {object} UiEventApprovalDecisionSubmitFailed
 * @property {'approval.decisionSubmitFailed'} type
 * @property {string} pendingId
 * @property {string} message
 *
 * @typedef {object} UiEventInsightEnvelope
 * @property {'assistant.insightEnvelope'} type
 * @property {string} requestId
 * @property {unknown} toolSuccessPayload Compact wire {@code tabular.tool_success} payload (object); reducer passes to {@code readInsightEnvelopeLoose} only.
 *
 * @typedef {object} UiEventTaskState
 * @property {'assistant.taskState'} type
 * @property {string} requestId
 * @property {TaskStateSnapshot} snapshot
 *
 * @typedef {object} UiEventRateControlStatus
 * @property {'assistant.rateControlStatus'} type
 * @property {string} requestId
 * @property {boolean} waiting
 * @property {string} [reason] Wire reason when **`waiting`** (e.g. **`tokens_per_minute`**); optional metadata for clients that do not render it.
 * @property {number} [waitMs]
 * @property {number} [retryAfterMs]
 *
 * @typedef {UiEventSessionAck|UiEventActivity|UiEventAppend|UiEventReplace|UiEventChart|UiEventTable|UiEventInsightEnvelope|UiEventTaskState|UiEventRateControlStatus|UiEventDone|UiEventError|UiEventSuperseded|UiEventSessionCancelled|UiEventSessionCancelUnsupportedLocal|UiEventApprovalRequired|UiEventApprovalResolved|UiEventApprovalDecisionSubmitting|UiEventApprovalDecisionAccepted|UiEventApprovalDecisionSubmitFailed} UiEvent
 */

export {};
