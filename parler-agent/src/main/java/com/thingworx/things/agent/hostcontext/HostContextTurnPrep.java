package com.thingworx.things.agent.hostcontext;

import com.thingworx.things.agent.AgentThing;

/**
 * Per-turn host-context evaluation product for AlwaysOn / Chat paths.
 */
public final class HostContextTurnPrep {

    public final HostContextUplink.Decision decision;
    public final String rawWireBytesOrNull;
    public final String snapshotJson;
    public final String llmEphemeralPromptOrNull;

    HostContextTurnPrep(HostContextUplink.Decision decision, String rawWireBytesOrNull, String snapshotJson,
            String llmEphemeralPromptOrNull) {
        this.decision = decision;
        this.rawWireBytesOrNull = rawWireBytesOrNull;
        this.snapshotJson = snapshotJson;
        this.llmEphemeralPromptOrNull = llmEphemeralPromptOrNull;
    }

    public static HostContextTurnPrep prepare(String hostContextRaw, AgentThing agent,
            HostContextPreviousSnapshot previous) {
        HostContextUplink.Decision decision = HostContextUplink.evaluate(hostContextRaw, agent);
        String wire = (hostContextRaw == null || hostContextRaw.isEmpty()) ? null : hostContextRaw;
        String snapshot = HostContextSnapshotBuilder.buildSnapshotJson(decision, wire, previous);
        String llm = null;
        if (decision.outcome == HostContextUplink.Outcome.ACCEPTED
                || decision.outcome == HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK) {
            String hash = HostContextSnapshotBuilder.sha256Prefixed(wire);
            boolean changed = HostContextSnapshotBuilder.changedFromPreviousUserTurn(
                    decision.outcome, hash, previous);
            llm = HostContextFreshnessPrompt.combineWithRendered(changed, decision.renderedPromptOrNull());
        }
        return new HostContextTurnPrep(decision, wire, snapshot, llm);
    }

    /**
     * Rendered host-context prompt only — no freshness block, no snapshot persistence (single-turn Chat).
     */
    public static HostContextTurnPrep renderedOnly(String hostContextRaw, AgentThing agent) {
        HostContextUplink.Decision decision = HostContextUplink.evaluate(hostContextRaw, agent);
        String wire = (hostContextRaw == null || hostContextRaw.isEmpty()) ? null : hostContextRaw;
        return new HostContextTurnPrep(decision, wire, null, decision.renderedPromptOrNull());
    }
}
