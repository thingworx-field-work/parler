package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * Executes a tool call and returns the result as a string.
 * The result is fed back into the LLM as a tool response message.
 */
@FunctionalInterface
public interface ToolExecutor {

    String execute(ToolCall toolCall) throws Exception;
}
