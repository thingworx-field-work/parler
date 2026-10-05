package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.AgentBaseThing;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * Thin dispatch layer for document-knowledge built-ins.
 */
public final class DocumentKnowledgeToolsExecutor {

    private DocumentKnowledgeToolsExecutor() {}

    public static String executeSearchDocumentChunks(ToolCall call) throws Exception {
        return DocumentKnowledgeRuntime.executeSearchDocumentChunks(call, AgentToolContext.getAgentThing());
    }

    public static String executeGetDocumentChunk(ToolCall call) throws Exception {
        return DocumentKnowledgeRuntime.executeGetDocumentChunk(call, AgentToolContext.getAgentThing());
    }

    public static String executeResolveDocumentSet(ToolCall call) throws Exception {
        return DocumentKnowledgeRuntime.executeResolveDocumentSet(call, AgentToolContext.getAgentThing());
    }
}
