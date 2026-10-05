package com.thingworx.things.agent.skillregistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable skill metadata registry (no full bodies). Built during prompt-context refresh.
 */
public final class SkillRegistrySnapshot {

    private final Instant refreshedAt;
    private final Map<String, SkillRegistryDescriptor> descriptorsByShortId;
    private final List<String> diagnostics;

    public SkillRegistrySnapshot(
            Instant refreshedAt,
            Map<String, SkillRegistryDescriptor> descriptorsByShortId,
            List<String> diagnostics) {
        this.refreshedAt = Objects.requireNonNull(refreshedAt, "refreshedAt");
        this.descriptorsByShortId = Collections.unmodifiableMap(new LinkedHashMap<>(descriptorsByShortId));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    public static SkillRegistrySnapshot empty(Instant refreshedAt) {
        return new SkillRegistrySnapshot(refreshedAt, Collections.emptyMap(), Collections.emptyList());
    }

    public Instant refreshedAt() {
        return refreshedAt;
    }

    public Map<String, SkillRegistryDescriptor> descriptorsByShortId() {
        return descriptorsByShortId;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public boolean hasSkill(String shortId) {
        return shortId != null && descriptorsByShortId.containsKey(shortId.trim());
    }

    public Optional<SkillRegistryDescriptor> descriptor(String shortId) {
        if (shortId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptorsByShortId.get(shortId.trim()));
    }

    public String formatDiagnosticsMarkdown() {
        long rep = descriptorsByShortId.values().stream().filter(d -> d.sourceKind() == SkillSourceKind.REPOSITORY).count();
        StringBuilder sb = new StringBuilder();
        sb.append("- skills: ").append(rep).append(" repository-backed\n");
        for (String line : diagnostics) {
            sb.append("- ").append(line).append('\n');
        }
        String out = sb.toString().trim();
        if (out.length() > SkillRegistryLimits.MAX_SKILL_DIAGNOSTICS_CHARS) {
            return out.substring(0, SkillRegistryLimits.MAX_SKILL_DIAGNOSTICS_CHARS) + "\n… (truncated)";
        }
        return out.isEmpty() ? "- (no diagnostics)" : out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillRegistrySnapshot)) {
            return false;
        }
        SkillRegistrySnapshot that = (SkillRegistrySnapshot) o;
        return Objects.equals(refreshedAt, that.refreshedAt)
                && Objects.equals(descriptorsByShortId, that.descriptorsByShortId)
                && Objects.equals(diagnostics, that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(refreshedAt, descriptorsByShortId, diagnostics);
    }
}
