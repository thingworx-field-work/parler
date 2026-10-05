package com.thingworx.things.agent;

import com.thingworx.things.agent.tools.SkillSlashParser;

/** Pure policy behind AgentThing's model-facing user-content resolver. */
final class AgentUserMessagePolicy {

    private AgentUserMessagePolicy() {}

    static String resolveModelFacingUserContent(String rawUserMessage, SkillSlashParser.Result slash) {
        String cleaned = slash.cleanedMessage();
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        if (!slash.skillShortNamesInOrder().isEmpty()) {
            return rawUserMessage.trim();
        }
        throw new IllegalArgumentException("user message is empty");
    }
}
