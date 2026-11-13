package com.thingworx.things.agent.taskstate;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.TopicMethodText;

/** Recognition hooks run independently of checklist parsing/merge early returns. */
public final class ActiveMethodHooks {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ActiveMethodHooks.class);

    private ActiveMethodHooks() {}

    public static void afterGetAgentSkill(ToolCall call, String result) {
        AgentTaskState state = AgentToolContext.getAgentTaskState();
        if (state == null || call == null || !"get_agent_skill".equals(call.getFunctionName())
                || result == null || NoEvidenceToolOutcome.isExplicitJsonErrorEnvelope(result)) {
            return;
        }
        String id = TaskProgressV1bDynamicMerge.normalizedSkillNameArgument(call);
        if (id != null && !"document_search".equals(id)) {
            state.recordActiveMethod("skill:" + id);
        }
    }

    /** Call only after the Java built-in loader, never after a same-named extended tool. */
    public static void afterLoadTopic(ToolCall call, String result) {
        AgentTaskState state = AgentToolContext.getAgentTaskState();
        if (state != null) {
            TopicMethodText.successfulReference(call, result).ifPresent(ref -> state.recordActiveMethod(ref.methodId()));
        }
    }

    /**
     * Call only after the Java built-in search, on the JSON it returned (before egress). A {@code topics} entry or a
     * topic match is an offer even if egress later removes it from the model's copy.
     */
    public static void afterSearchDocumentChunks(ToolCall call, String result) {
        AgentTaskState state = AgentToolContext.getAgentTaskState();
        if (state == null || state.isMethodOffered() || call == null
                || !"search_document_chunks".equals(call.getFunctionName()) || !hasTopicMatch(result)) {
            return;
        }
        state.recordMethodOffered();
        LOG.info("Document-turn tool narrowing lifted: topic offered conversationId={}",
                AgentToolContext.getConversationId());
    }

    private static boolean hasTopicMatch(String result) {
        if (result == null) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(result);
            if (root.path("topics").size() > 0) {
                return true;
            }
            for (JsonNode match : root.path("matches")) {
                if ("topic".equals(match.path("contentType").asText())) {
                    return true;
                }
            }
        } catch (Exception unparsable) {
            return false;
        }
        return false;
    }
}
