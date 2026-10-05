package com.thingworx.things.agent.playbook;

import java.util.concurrent.atomic.AtomicBoolean;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Parler AlwaysOn stream scope slice for Playbook slash turns: wire ids, gateway {@link Thing}, and optional live
 * downlink health. {@link com.thingworx.things.agent.AgentThing#bindPlaybookSlashTurnContext} delegates here so Chat
 * slash paths that pass {@code downlinkOkOrNull == null} stay free of a bogus downlink gate.
 */
public final class PlaybookParlerStreamBindings {

    private PlaybookParlerStreamBindings() {}

    public static void bindForPlaybookSlash(
            String requestId,
            String remoteThingName,
            Thing remoteConversation,
            AtomicBoolean downlinkOkOrNull) {
        if (requestId != null && remoteThingName != null && !remoteThingName.isEmpty()) {
            AgentToolContext.setParlerStreamIds(requestId, remoteThingName);
        }
        if (remoteConversation != null) {
            AgentToolContext.setParlerRemoteConversation(remoteConversation);
        }
        AgentToolContext.setParlerDownlinkOk(downlinkOkOrNull);
    }
}
