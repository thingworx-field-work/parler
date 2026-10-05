package com.thingworx.things.agent.skillregistry;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * Resolves skill bodies only through the unified {@link SkillRegistrySnapshot} (no registry-bypass Service path).
 */
public final class SkillRegistryLoader {

    /** Lazy: static {@code LoggerFactory.getLogger} can pull Logback/ThingWorx init; early {@link #loadBody} exits must stay cheap for unit tests. */
    private static Logger log() {
        return LoggerFactory.getLogger(SkillRegistryLoader.class);
    }

    /** Alias for {@link SkillRegistryUnavailableException#MESSAGE}. */
    public static final String MSG_REGISTRY_UNAVAILABLE = SkillRegistryUnavailableException.MESSAGE;

    private SkillRegistryLoader() {}

    /**
     * Loads a registered skill body. {@code reg} must come from {@code agent.getPromptContextSnapshot().getSkillRegistry()}
     * when the snapshot exists; when the snapshot is absent, pass {@code null} to surface registry unavailability.
     */
    public static String loadBody(AgentThing agent, SkillRegistrySnapshot reg, String shortId) throws Exception {
        if (reg == null) {
            throw new SkillRegistryUnavailableException();
        }
        if (agent == null) {
            throw new IllegalArgumentException("agent required");
        }
        String id = shortId != null ? shortId.trim() : "";
        if (id.isEmpty()) {
            throw new IllegalArgumentException("skill_name is required");
        }
        if (!reg.hasSkill(id)) {
            throw new IllegalArgumentException("Skill " + id + " is not registered.");
        }
        SkillRegistryDescriptor d = reg.descriptorsByShortId().get(id);
        if (d == null) {
            throw new IllegalArgumentException("Skill " + id + " is not registered.");
        }
        if (d.sourceKind() != SkillSourceKind.REPOSITORY) {
            // Defensive: registry build is repository-only; SERVICE descriptors should never appear.
            throw new IllegalStateException("Unexpected non-repository skill descriptor for " + id);
        }
        Optional<FileRepositoryThing> repo = FileRepositoryThingResolver.resolveForCurrentUser(
                d.repositoryThingName(), log(), agent.getName());
        if (repo.isEmpty()) {
            throw new IllegalStateException("Skill repository unavailable for " + id);
        }
        RepositoryReader rr = FileRepositoryRepositoryReader.forCurrentUser(repo.get());
        String raw = rr.loadText(d.repositorySkillPath());
        if (raw == null) {
            raw = "";
        }
        SkillRegistryLimits.requireSkillMarkdownUtf16Length(raw.length());
        SkillMarkdownParser.Result pm = SkillMarkdownParser.parse(raw, id);
        if (pm.nameMismatch()) {
            throw new IllegalStateException("SKILL.md frontmatter name does not match directory id " + id);
        }
        return pm.bodyForLlm();
    }
}
