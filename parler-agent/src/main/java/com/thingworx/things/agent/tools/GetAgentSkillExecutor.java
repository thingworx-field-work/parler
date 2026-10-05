package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistryLoader;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistryUnavailableException;

/**
 * Built-in tool: load full skill body by short id from the configuration repository ({@code /skills/<id>/SKILL.md})
 * or other registry sources resolved by {@link SkillRegistryLoader}.
 */
public final class GetAgentSkillExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GetAgentSkillExecutor() {}

    public static String execute(ToolCall toolCall) throws Exception {
        AgentThing agent = AgentToolContext.getAgentThing();
        if (agent == null) {
            return errorJson("BAD_REQUEST", "Agent context not available for get_agent_skill", null);
        }
        String argsJson = toolCall.getArguments();
        if (argsJson == null || argsJson.isBlank()) {
            return errorJson("BAD_REQUEST", "Missing arguments; provide {\"skill_name\":\"YourSkillId\"}", null);
        }
        JsonNode root = MAPPER.readTree(argsJson);
        if (!root.isObject() || !root.has("skill_name") || root.get("skill_name").isNull()) {
            return errorJson("BAD_REQUEST", "Missing required string field skill_name", null);
        }
        String skillName = root.get("skill_name").asText(null);
        if (skillName == null || skillName.isBlank()) {
            return errorJson("BAD_REQUEST", "skill_name must be non-empty", null);
        }
        String id = skillName.trim();
        try {
            PromptContextCacheSnapshot snap = agent.getPromptContextSnapshot();
            SkillRegistrySnapshot reg = snap != null ? snap.getSkillRegistry() : null;
            String body = SkillRegistryLoader.loadBody(agent, reg, id);
            return body != null ? body : "";
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Skill not registered.";
            return errorJson("SKILL_NOT_FOUND", msg, playbookShadowHint(agent, id));
        } catch (SkillRegistryUnavailableException e) {
            return errorJson("SKILL_REGISTRY_UNAVAILABLE",
                    e.getMessage() != null ? e.getMessage() : SkillRegistryLoader.MSG_REGISTRY_UNAVAILABLE, null);
        } catch (IllegalStateException e) {
            return errorJson("SKILL_LOAD_FAILED", e.getMessage() != null ? e.getMessage() : "Failed to load skill", null);
        } catch (Exception e) {
            return errorJson("SKILL_LOAD_FAILED", e.getMessage() != null ? e.getMessage() : "Failed to load skill", null);
        }
    }

    /** S8: when a Playbook shares the requested id, steer toward start_playbook / slash. */
    static String playbookShadowHint(AgentThing agent, String id) {
        if (agent == null || id == null || id.isBlank()) {
            return null;
        }
        PlaybookRegistrySnapshot pb = agent.getPlaybookRegistrySnapshot();
        if (pb == null || !pb.isLoaded()) {
            return null;
        }
        if (!pb.catalogById().containsKey(id)) {
            return null;
        }
        return "A Playbook with the same id \"" + id
                + "\" is registered — use start_playbook (or /" + id + "), not get_agent_skill.";
    }

    private static String errorJson(String code, String message, String recoveryHint) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "error");
            m.put("code", code);
            m.put("message", message);
            if (recoveryHint != null && !recoveryHint.isBlank()) {
                m.put("recoveryHint", recoveryHint);
            }
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"error\",\"message\":\"error\"}";
        }
    }
}
