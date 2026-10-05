package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;

/**
 * Whether the merged LLM tool surface should advertise {@code get_agent_skill} (non-empty repository skill catalog).
 * See {@code docs/agent/model-tool-admission-guardrails.md} Slice B.
 */
public final class ModelFacingSkillAdmission {

    private ModelFacingSkillAdmission() {}

    /** Conservative: false on null snapshot, null registry, or zero skill descriptors. */
    public static boolean hasModelFacingSkills(PromptContextCacheSnapshot snap) {
        if (snap == null) {
            return false;
        }
        SkillRegistrySnapshot reg = snap.getSkillRegistry();
        if (reg == null) {
            return false;
        }
        return !reg.descriptorsByShortId().isEmpty();
    }
}
