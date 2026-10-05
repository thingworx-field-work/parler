package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Default alert text for {@link com.thingworx.things.agent.AgentThing#GetAlertPrompt()} (Parler built-in). Kept in
 * one place to match {@code docs/operations/alert-solution.md} §7.
 *
 * <p>{@link #DEFAULT_ALERT_PROMPT_MARKDOWN} is the concise stable-prompt block. The longer skill body
 * ({@link #SKILL_ALERT_QUERY_MARKDOWN}) is kept for repository-backed alert skills under {@code /skills/.../SKILL.md}
 * and is not part of the default stable prompt.</p>
 *
 * <p>String is built without Java text blocks: extension compiles with {@code -source 11} under the ThingWorx
 * Gradle plugin.</p>
 */
public final class AlertPromptDefaults {

    private AlertPromptDefaults() {}

    /**
     * Concise default alert section (the same shape as the alert block of the bundled routing guide family).
     * Owned separately from {@link #SKILL_BODY_LINES}: the on-demand skill body keeps the decision tree, field
     * dictionaries and QUERY examples for repository skills; the stable prompt carries only these bullets.
     */
    private static final String[] ROUTING_GUIDE_LINES = new String[] {
            "- Current alerts: `query_alert_summary`, with `thingNames` containing 1–25 canonical Things. Default includes acknowledged and unacknowledged alerts; use `ackState: \"unacknowledged\"` when requested. `sort` (`timestamp_*` / `priority_*`) overrides `advancedQuery.sorts` for the call. Summary is volatile and may be empty after restart; history is the persistent record.",
            "- Resolve named assets or hierarchy scope first. Summary has no `hierarchyNodeName`. For comparisons, batch the resolved Things in one call when they fit; otherwise split or narrow without silently dropping assets. One Thing returns an INFOTABLE envelope; multiple Things return `ALERT_SUMMARY_MULTI` with `byThing[]`, counters and `completeness`. Treat partial completeness as incomplete until its gaps are resolved.",
            "- Past alerts: `query_alert_history` with scalar `thingName`. Prefer ISO `startTime` / `endTime`; alternatively use `calendarPhrase` (today/yesterday/tomorrow with user timezone) or `relativeDuration` (for example 30m/24h), mutually exclusive with ISO bounds. Without a supplied window, the tool uses the preceding seven days. Use `order: \"newest_first\"` or `\"oldest_first\"`.",
            "- Acknowledgement: `acknowledge_alerts` with scalar `thingName`. Default `specific_alerts` requires `propertyName`, optionally `alertName`. Exactly one matching unacknowledged alert may be acknowledged with a property-scoped call. `property_all` requires explicit intent to acknowledge every alert on that property. Never interpret vague “ack it” as property_all. `thing_all` is unsupported. Never call `AcknowledgeAlert` without `sourceProperty`, since that acknowledges all alerts on the Thing.",
            "- Use these dedicated tools for their common cases. For complex filters beyond their supported parameters, use `Resources[\"AlertFunctions\"]` services via `invoke_service` with confirmed inputs and skill guidance.",
            "- AlertSummary fields: `timestamp`, `source`, `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `ackTimestamp`, `ack`, `ackBy`, `duration`.",
            "- AlertHistory fields: `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `eventName`, plus `source`, `timestamp`. Do not invent alert fields. QUERY predicates use `type`, `fieldName`, `value`; AND/OR composites contain `filters`. Sort entries use `fieldName`, `isAscending`.",
    };

    private static final String[] SKILL_BODY_LINES = new String[] {
            "### Skill-style decision tree",
            "",
            "- \"What's active / unacknowledged / current?\" → **query_alert_summary**",
            "- \"What happened / timeline / during this period?\" → **query_alert_history**",
            "- \"Ack / silence / mark as handled\" → **acknowledge_alerts**",
            "- Empty summary after restart is normal; use **query_alert_history** for durable past events.",
            "- History is the persistent record; summary is the volatile snapshot.",
            "",
            "### Field dictionaries",
            "",
            "**AlertSummary:** `timestamp`, `source`, `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `ackTimestamp`, `ack`, `ackBy`, `duration`",
            "",
            "**AlertHistory:** `sourceProperty`, `alertType`, `name`, `description`, `priority`, `message`, `eventName` plus stream fields `source`, `timestamp`",
            "",
            "Do not invent field names. These are the only valid fields for QUERY construction and result interpretation.",
            "",
            "### QUERY examples (invoke_service fallback)",
            "",
            "```json",
            "{\"filters\": {\"type\": \"GE\", \"fieldName\": \"priority\", \"value\": 5}}",
            "```",
            "",
            "```json",
            "{\"filters\": {\"type\": \"AND\", \"filters\": [",
            "  {\"type\": \"GT\", \"fieldName\": \"priority\", \"value\": 1},",
            "  {\"type\": \"EQ\", \"fieldName\": \"alertType\", \"value\": \"Above\"}",
            "]}}",
            "```",
            "",
            "```json",
            "{\"sorts\": [",
            "  {\"fieldName\": \"priority\", \"isAscending\": false},",
            "  {\"fieldName\": \"timestamp\", \"isAscending\": false}",
            "]}",
            "```",
            "",
            "### Ack warnings",
            "",
            "- **specific_alerts** = row-level precision (safe default). When exactly one unacked row matches **propertyName** without an **alertName** filter, the executor may use a narrow **AcknowledgeAlert** call (same property scope).",
            "- **property_all** = every alert on the property (explicit intent only)",
            "- **AcknowledgeAlert** without **sourceProperty** acknowledges everything on the Thing — the executor must never call it this way",
            "- Never map vague \"ack it\" to **property_all**",
            "- **thing_all** is not available in v1",
            "",
            "### Fan-out",
            "",
            "**query_alert_summary** accepts **`thingNames[]`** (1–25 canonical names). Use a **single call** for cross-Thing comparison when the set fits; N≥2 returns **`ALERT_SUMMARY_MULTI`**. History and acknowledge remain scalar **`thingName`**. When more than **25** Things need summary reads, batch **`thingNames[]`** or narrow scope.",
    };

    /**
     * Markdown block injected per LLM turn when {@code GetAlertPrompt} is not overridden to empty (§7.1). Concise by
     * design; the fuller skill-style body ({@link #SKILL_ALERT_QUERY_MARKDOWN}) is no longer folded into the stable
     * prompt and remains available for repository skills.
     */
    public static final String DEFAULT_ALERT_PROMPT_MARKDOWN = String.join("\n", concatLines(
            new String[] {
                    "## Alerts",
                    "",
            },
            ROUTING_GUIDE_LINES));

    /**
     * Skill body (§7.2): decision tree, field dictionaries, QUERY examples, ack warnings and fan-out. Not part of
     * {@link #DEFAULT_ALERT_PROMPT_MARKDOWN}; suitable for {@code /skills/.../SKILL.md} in a configuration repository.
     */
    public static final String SKILL_ALERT_QUERY_MARKDOWN = String.join("\n", SKILL_BODY_LINES);

    private static String[] concatLines(String[]... chunks) {
        List<String> out = new ArrayList<>();
        for (String[] c : chunks) {
            out.addAll(Arrays.asList(c));
        }
        return out.toArray(new String[0]);
    }
}
