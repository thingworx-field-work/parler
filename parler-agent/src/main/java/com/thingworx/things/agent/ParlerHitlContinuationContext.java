package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.PendingApprovalRecord;

/**
 * Rebinds {@link AgentToolContext} for Parler AlwaysOn HITL approval continuation so gated tool execution and
 * post-approval agent loops share the pending turn's conversation / request scope (not {@code __single_turn__}).
 */
public final class ParlerHitlContinuationContext {

    private ParlerHitlContinuationContext() {
    }

    /**
     * @param agent executing {@link AgentThing}; may be {@code null} in unit tests that only assert cache scope
     * @param messages active message list for the continuation; may be {@code null} when not needed
     */
    public static void bindForGatedToolExecution(
            AgentThing agent,
            PendingApprovalRecord rec,
            Thing remoteConversation,
            AtomicBoolean downlinkOk,
            List<ChatMessage> messages) {
        if (rec == null) {
            return;
        }
        AgentToolContext.setConversationId(rec.getConversationId());
        if (agent != null) {
            AgentToolContext.setAgentThing(agent);
        }
        AgentToolContext.setUserIanaTimezone(rec.getUserIanaTimezone());
        AgentToolContext.setParlerStreamIds(rec.getRequestId(), rec.getRemoteThingName());
        if (messages != null) {
            AgentToolContext.setParlerActiveMessages(messages);
        }
        if (remoteConversation != null) {
            AgentToolContext.setParlerRemoteConversation(remoteConversation);
        }
        if (downlinkOk != null) {
            AgentToolContext.setParlerDownlinkOk(downlinkOk);
        }
        List<String> slashSnap = rec.getSlashSkillShortNamesSnapshot();
        AgentToolContext.setParlerSlashSkillShortNamesForTurn(
                slashSnap.isEmpty() ? null : new ArrayList<>(slashSnap));
    }
}
