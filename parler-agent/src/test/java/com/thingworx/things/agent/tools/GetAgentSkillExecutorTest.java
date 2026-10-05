package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

/** Offline-safe tests for {@link GetAgentSkillExecutor} (no {@link com.thingworx.things.agent.AgentThing} construction). */
class GetAgentSkillExecutorTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void noAgentContext_returnsBadRequest() throws Exception {
        String out = GetAgentSkillExecutor.execute(new ToolCall("c1", "get_agent_skill", "{\"skill_name\":\"X\"}"));
        assertTrue(out.contains("\"code\":\"BAD_REQUEST\""));
        assertTrue(out.contains("Agent context"));
    }
}
