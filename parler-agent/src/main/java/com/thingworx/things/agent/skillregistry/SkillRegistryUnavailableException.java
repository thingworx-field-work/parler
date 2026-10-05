package com.thingworx.things.agent.skillregistry;

/**
 * Prompt-context skill registry snapshot is missing; callers should refresh the cache or retry after lazy population.
 */
public final class SkillRegistryUnavailableException extends IllegalStateException {

    /** Stable message for logging, tests, and {@code get_agent_skill} {@code SKILL_REGISTRY_UNAVAILABLE}. */
    public static final String MESSAGE = "Skill registry unavailable; refresh prompt context cache.";

    public SkillRegistryUnavailableException() {
        super(MESSAGE);
    }
}
