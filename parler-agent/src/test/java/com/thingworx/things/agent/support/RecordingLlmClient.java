package com.thingworx.things.agent.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;

/** A scripted provider that records every request the agent loop sends (shared test helper). */
public final class RecordingLlmClient implements LlmClient {
    public final List<LlmChatRequest> requests = new ArrayList<>();
    private final Queue<LlmResponse> scripted = new ArrayDeque<>();
    private final LlmUsageWireIds ids = new LlmUsageWireIds("pt", "tpl", "openai-chat-completions-v1", "mdl");
    private Runnable beforeChatReturn;

    public void enqueue(LlmResponse r) {
        scripted.add(r);
    }

    public void setBeforeChatReturn(Runnable r) {
        this.beforeChatReturn = r;
    }

    @Override
    public LlmResponse chat(LlmChatRequest request) throws Exception {
        requests.add(request);
        LlmResponse r = scripted.poll();
        if (r == null) {
            throw new IllegalStateException("no scripted response");
        }
        if (beforeChatReturn != null) {
            beforeChatReturn.run();
        }
        return r;
    }

    @Override
    public LlmUsageWireIds usageWireIds() {
        return ids;
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    public static LlmResponse textResponse(String text) {
        return new LlmResponse(text, Collections.emptyList(), LlmResponse.FinishReason.STOP,
                1, 2, 1, 2, 0, 0, 0, "rid", 0L);
    }

    public static LlmResponse toolCallsResponse(List<ToolCall> calls) {
        return new LlmResponse("", calls, LlmResponse.FinishReason.TOOL_CALLS,
                3, 4, 3, 4, 0, 0, 0, "rid2", 0L);
    }
}
