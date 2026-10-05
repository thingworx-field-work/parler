package com.thingworx.things.agent.taskstate;

import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.ParlerReceiveMessageSupport;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Emits {@code type: "task.state"} on the Parler AlwaysOn downlink when {@link TaskProgressV1b} is dirty.
 */
public final class TaskProgressWireEmitter {

    private TaskProgressWireEmitter() {}

    /**
     * Terminal failed turn: {@link TaskProgressV1b#applyTurnEnd}{@code (false, false)} then flush (before
     * {@link AgentToolContext#clear()}). Used when the agent loop throws; safe when no RemoteThing is bound (no-op
     * send).
     */
    public static void emitFailedTurnEnd() {
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        v.applyTurnEnd(false, false);
        flushSnapshot(st);
    }

    /**
     * Sends a snapshot if {@link TaskProgressV1b#isDirty()} and stream context is bound; clears dirty only after a
     * successful {@link ParlerReceiveMessageSupport#send}.
     */
    public static void flushSnapshot(AgentTaskState state) {
        if (AgentToolContext.isPlaybookTaskProgressActive()) {
            return;
        }
        if (state == null) {
            return;
        }
        TaskProgressV1b v = state.getTaskProgressV1b();
        if (v == null || !v.isDirty()) {
            return;
        }
        Thing remote = AgentToolContext.getParlerRemoteConversation();
        if (remote == null) {
            return;
        }
        String rid = AgentToolContext.getParlerRequestId();
        String cid = AgentToolContext.getParlerRemoteThingName();
        if (cid == null || cid.isEmpty()) {
            return;
        }
        String json = buildWireJson(rid, cid, v);
        if (json.length() > TaskProgressV1b.MAX_FRAME_UTF16) {
            json = buildMinimalWireJson(rid, cid, v);
        }
        if (ParlerReceiveMessageSupport.send(remote, json)) {
            v.clearDirty();
        }
    }

    static String buildWireJson(String requestId, String conversationId, TaskProgressV1b v) {
        JSONObject summary = v.buildSummaryCounts();
        org.json.JSONArray items = v.buildItemsArrayForWire();
        String title = v.getWireTitle();
        return ParlerReceiveMessageSupport.wireTaskState(
                requestId,
                conversationId,
                1,
                v.getTurnWireStatus(),
                title,
                summary,
                items);
    }

    private static String buildMinimalWireJson(String requestId, String conversationId, TaskProgressV1b v) {
        JSONObject summary = v.buildSummaryCounts();
        org.json.JSONArray items = new org.json.JSONArray();
        JSONObject one = new JSONObject();
        try {
            one.put("id", "__frame_overflow__");
            one.put("source", "tool");
            one.put("kind", "tool");
            one.put("label", "Task state truncated");
            one.put("status", "not-applicable");
            one.put("summary", "snapshot exceeded maxFrameChars");
            one.put("updatedAtEpochMillis", System.currentTimeMillis());
            items.put(one);
        } catch (Exception ignored) {
            // defensive
        }
        return ParlerReceiveMessageSupport.wireTaskState(
                requestId,
                conversationId,
                1,
                v.getTurnWireStatus(),
                null,
                summary,
                items);
    }
}
