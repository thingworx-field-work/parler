package com.thingworx.things.agent.playbook;

import java.util.function.Consumer;

import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.ParlerReceiveMessageSupport;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Emits playbook {@code task.state} snapshots during {@link PlaybookRunner} execution.
 */
public final class PlaybookTaskProgressEmitter {

    private static final ThreadLocal<PlaybookTaskProgress> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> COALESCE_DEPTH = ThreadLocal.withInitial(() -> 0);
    /** Test-only wire capture; when set, {@link #flush} records JSON instead of calling {@link ParlerReceiveMessageSupport#send}. */
    private static volatile Consumer<String> testWireSink;

    private PlaybookTaskProgressEmitter() {}

    static void setTestWireSink(Consumer<String> sink) {
        testWireSink = sink;
    }

    static void clearTestWireSink() {
        testWireSink = null;
    }

    public static void begin(PlaybookDocument document, String playbookId) {
        PlaybookTaskProgress progress = new PlaybookTaskProgress(playbookId, document);
        ACTIVE.set(progress);
        AgentToolContext.setPlaybookTaskProgressActive(true);
        progress.onValidated();
        flush(progress);
    }

    public static void end(boolean success) {
        PlaybookTaskProgress progress = ACTIVE.get();
        if (progress != null) {
            progress.onTurnEnd(success);
            flush(progress);
        }
        ACTIVE.remove();
        AgentToolContext.setPlaybookTaskProgressActive(false);
    }

    static void flush(PlaybookTaskProgress progress) {
        if (progress == null) {
            return;
        }
        int depth = COALESCE_DEPTH.get();
        if (depth > 0) {
            return;
        }
        String rid = AgentToolContext.getParlerRequestId();
        String cid = AgentToolContext.getParlerRemoteThingName();
        if (cid == null || cid.isEmpty()) {
            return;
        }
        Consumer<String> sink = testWireSink;
        String json = sink != null
                ? progress.buildWireJsonForUnitTest(rid, cid)
                : progress.buildWireJson(rid, cid);
        if (json.length() > PlaybookTaskProgress.MAX_FRAME_UTF16) {
            json = minimalOverflowFrame(rid, cid, progress);
        }
        if (sink != null) {
            sink.accept(json);
            return;
        }
        Thing remote = AgentToolContext.getParlerRemoteConversation();
        if (remote == null) {
            return;
        }
        COALESCE_DEPTH.set(depth + 1);
        try {
            ParlerReceiveMessageSupport.send(remote, json);
        } finally {
            COALESCE_DEPTH.set(depth);
        }
    }

    private static String minimalOverflowFrame(String requestId, String conversationId, PlaybookTaskProgress progress) {
        org.json.JSONArray items = new org.json.JSONArray();
        org.json.JSONObject one = new org.json.JSONObject();
        try {
            one.put("id", "__frame_overflow__");
            one.put("source", "playbook");
            one.put("kind", "evidence");
            one.put("label", "Playbook progress truncated");
            one.put("status", "not-applicable");
            one.put("summary", "snapshot exceeded maxFrameChars");
            items.put(one);
        } catch (Exception ignored) {
            // defensive
        }
        return ParlerReceiveMessageSupport.wireTaskState(
                requestId,
                conversationId,
                1,
                progress.turnWireStatus(),
                null,
                progress.buildSummaryCounts(),
                items);
    }

    public static PlaybookTaskProgress getActive() {
        return ACTIVE.get();
    }

    public static void onNodeStarted(String nodeId) {
        PlaybookTaskProgress p = ACTIVE.get();
        if (p != null) {
            p.onNodeStarted(nodeId);
            flush(p);
        }
    }

    public static void onNodeCompleted(String nodeId, String summary) {
        PlaybookTaskProgress p = ACTIVE.get();
        if (p != null) {
            p.onNodeCompleted(nodeId, summary);
            flush(p);
        }
    }

    public static void onNodeFailed(String nodeId, String summary) {
        PlaybookTaskProgress p = ACTIVE.get();
        if (p != null) {
            p.onNodeFailed(nodeId, summary);
            flush(p);
        }
    }

    public static void onSummarizing() {
        PlaybookTaskProgress p = ACTIVE.get();
        if (p != null) {
            p.onSummarizing();
        }
    }

    public static void onFanOutProgress(String parentNodeId, int completed, int total) {
        PlaybookTaskProgress p = ACTIVE.get();
        if (p != null) {
            p.onFanOutProgress(parentNodeId, completed, total);
            flush(p);
        }
    }
}
