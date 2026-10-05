package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.SkillSlashParser;

class AgentUserMessagePolicyTest {

    @Test
    void ordinaryCleanedMessageRemainsTheModelFacingContent() {
        String raw = "/Demo inspect the pump";
        SkillSlashParser.Result slash = SkillSlashParser.parse(raw, Set.of("Demo"));

        assertEquals("inspect the pump", AgentUserMessagePolicy.resolveModelFacingUserContent(raw, slash));
    }

    @Test
    void registeredSlashOnlyMessageUsesTrimmedUserDirective() {
        String raw = "  /Demo   ";
        SkillSlashParser.Result slash = SkillSlashParser.parse(raw, Set.of("Demo"));

        assertEquals("", slash.cleanedMessage());
        assertEquals("/Demo", AgentUserMessagePolicy.resolveModelFacingUserContent(raw, slash));
    }

    @Test
    void blankMessageWithoutSelectedSkillFailsTheInternalInvariant() {
        String raw = " \t ";
        SkillSlashParser.Result slash = SkillSlashParser.parse(raw, Set.of("Demo"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentUserMessagePolicy.resolveModelFacingUserContent(raw, slash));
        assertEquals("user message is empty", error.getMessage());
    }
}
