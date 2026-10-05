package com.thingworx.things.agent.skillregistry;

import java.util.Objects;

/**
 * Immutable registry row: metadata + source reference only (no full skill body).
 */
public final class SkillRegistryDescriptor {

    private final String shortId;
    private final String title;
    private final String whenToUse;
    private final SkillSourceKind sourceKind;
    /** Full service name for SERVICE skills ({@code _skill_<shortId>}). */
    private final String serviceName;
    private final String repositoryThingName;
    /** Repository path to SKILL.md, e.g. {@code /Foo/SKILL.md}. */
    private final String repositorySkillPath;

    public SkillRegistryDescriptor(
            String shortId,
            String title,
            String whenToUse,
            SkillSourceKind sourceKind,
            String serviceName,
            String repositoryThingName,
            String repositorySkillPath) {
        this.shortId = Objects.requireNonNull(shortId, "shortId");
        this.title = title != null ? title : shortId;
        this.whenToUse = whenToUse != null ? whenToUse : "";
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.serviceName = serviceName;
        this.repositoryThingName = repositoryThingName;
        this.repositorySkillPath = repositorySkillPath;
    }

    public String shortId() {
        return shortId;
    }

    public String title() {
        return title;
    }

    public String whenToUse() {
        return whenToUse;
    }

    public SkillSourceKind sourceKind() {
        return sourceKind;
    }

    public String serviceName() {
        return serviceName;
    }

    public String repositoryThingName() {
        return repositoryThingName;
    }

    public String repositorySkillPath() {
        return repositorySkillPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillRegistryDescriptor)) {
            return false;
        }
        SkillRegistryDescriptor that = (SkillRegistryDescriptor) o;
        return Objects.equals(shortId, that.shortId)
                && Objects.equals(title, that.title)
                && Objects.equals(whenToUse, that.whenToUse)
                && sourceKind == that.sourceKind
                && Objects.equals(serviceName, that.serviceName)
                && Objects.equals(repositoryThingName, that.repositoryThingName)
                && Objects.equals(repositorySkillPath, that.repositorySkillPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortId, title, whenToUse, sourceKind, serviceName, repositoryThingName, repositorySkillPath);
    }
}
