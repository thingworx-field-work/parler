package com.thingworx.things.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ChartGroupState;

/**
 * C3b-1 turn end (chart-enhancement design §8.5): converge every still-pending member of the request's chart
 * group ({@code cancelled} on a user stop, else {@code error} with {@code MEMBER_NOT_PRODUCED} /
 * {@code DOWNLINK_FAILED}), downlink the {@code final} manifest revision when a downlink exists, and keep the
 * final manifests for the {@code chartGroupsJson} Stream field of the final assistant row.
 *
 * <p><b>Ordering.</b> Every entry point runs {@link AgentToolContext#clear()} in the {@code finally} of its
 * {@code loop.run} block, and decides whether the turn is terminal only afterwards. {@code clear()} drops the
 * chart-round state (the group), the remote conversation, the downlink flag and the stream ids, so the group
 * must be taken out of the context <em>before</em> that clear: call {@link #captureBeforeContextClear()} as the
 * first statement of the {@code finally}, then {@link #onTurnEnd} where the terminal branch is known. A turn
 * that pauses for approval, ends on an artifact-cache terminal or throws never reaches {@code onTurnEnd}; its
 * capture is simply replaced by the next turn's capture on that thread.</p>
 */
public final class ChartGroupTurnHooks {
    private ChartGroupTurnHooks() {}

    /** What {@link #onTurnEnd} needs from the turn, taken before {@link AgentToolContext#clear()}. */
    static final class TurnEndCapture {
        final ChartGroupState group;
        final AtomicBoolean downlinkOk;
        final String requestId;
        final String conversationId;
        /** Sends one wire JSON text; {@code null} when the turn has no downlink (REST chat). */
        final Predicate<String> sender;

        TurnEndCapture(ChartGroupState group, AtomicBoolean downlinkOk, String requestId, String conversationId,
                Predicate<String> sender) {
            this.group = group;
            this.downlinkOk = downlinkOk;
            this.requestId = requestId;
            this.conversationId = conversationId;
            this.sender = sender;
        }
    }

    private static final ThreadLocal<TurnEndCapture> CAPTURED = new ThreadLocal<>();

    /**
     * Take the request's chart group and its downlink out of {@link AgentToolContext}. MUST run before
     * {@link AgentToolContext#clear()} on the thread that later calls {@link #onTurnEnd}. Always replaces the
     * previous capture, so a turn without a group leaves nothing behind.
     */
    public static void captureBeforeContextClear() {
        ChartGroupState group = AgentToolContext.tabularChartRoundStateOrNull() != null
                ? AgentToolContext.tabularChartRoundStateOrNull().getChartGroup()
                : null;
        if (group == null) {
            CAPTURED.remove();
            return;
        }
        final Thing remote = AgentToolContext.getParlerRemoteConversation();
        Predicate<String> sender = remote == null ? null : json -> ParlerReceiveMessageSupport.send(remote, json);
        CAPTURED.set(new TurnEndCapture(group, AgentToolContext.getParlerDownlinkOk(),
                AgentToolContext.getParlerRequestId(), AgentToolContext.getParlerRemoteThingName(), sender));
    }

    public static void onTurnEnd(AgentLoop.AgentResult result) {
        boolean cancelled = result != null && result.getStatus() == AgentLoop.AgentResult.Status.CANCELLED;
        onTurnEnd(cancelled);
    }

    public static void onTurnEnd(boolean userCancelled) {
        TurnEndCapture captured = CAPTURED.get();
        CAPTURED.remove();
        String groupsJson = finish(captured, userCancelled);
        if (groupsJson != null) {
            AgentToolContext.setChartGroupsJsonForFinalAssistantRow(groupsJson);
        }
    }

    /**
     * Converge, send the {@code final} revision through the captured sender and return the JSON array text for
     * the final assistant row; {@code null} when there is no group or its final revision already went out.
     */
    static String finish(TurnEndCapture captured, boolean userCancelled) {
        if (captured == null || captured.group == null || captured.group.isFinalSent()) {
            return null;
        }
        ChartGroupState group = captured.group;
        group.convergePending(userCancelled);
        JSONObject fin = group.nextManifest(true);
        JSONArray all = new JSONArray();
        all.put(fin);
        AtomicBoolean downlinkOk = captured.downlinkOk;
        if (captured.sender != null && downlinkOk != null && downlinkOk.get()) {
            String frame = ParlerReceiveMessageSupport.wireChartGroup(captured.requestId, captured.conversationId,
                    fin);
            if (!captured.sender.test(frame)) {
                downlinkOk.set(false);
            }
        }
        return all.toString();
    }
}
