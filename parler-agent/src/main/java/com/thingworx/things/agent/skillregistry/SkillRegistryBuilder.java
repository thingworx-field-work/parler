package com.thingworx.things.agent.skillregistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * Builds {@link SkillRegistrySnapshot} during {@link com.thingworx.things.agent.AgentThing} prompt-context refresh.
 */
public final class SkillRegistryBuilder {

    private SkillRegistryBuilder() {}

    public static SkillRegistrySnapshot build(AgentThing agent, Logger log) {
        return build(agent, Set.of(), log);
    }

    public static SkillRegistrySnapshot build(AgentThing agent, Set<String> reservedPlaybookShortIds, Logger log) {
        Instant now = Instant.now();
        LinkedHashMap<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();

        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return new SkillRegistrySnapshot(now, map, trimDiagnostics(diagnostics));
        }
        Optional<FileRepositoryThing> fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            diagnostics.add(
                    "configurationRepository not available (Thing missing or not a FileRepository — see Application Log)");
            return new SkillRegistrySnapshot(now, map, trimDiagnostics(diagnostics));
        }
        return buildFromRepositoryReader(FileRepositoryRepositoryReader.forConfiguration(fr.get()), repoName.trim(),
                agent.getName(), reservedPlaybookShortIds, log);
    }

    /**
     * Scans {@code /skills} on the given reader (for example a non-default repository during validation). Missing
     * {@code /skills} is treated as zero repository skills with no diagnostics.
     */
    public static SkillRegistrySnapshot buildFromRepositoryReader(
            RepositoryReader reader,
            String repositoryThingName,
            String agentThingName,
            Logger log) {
        return buildFromRepositoryReader(reader, repositoryThingName, agentThingName, Set.of(), log);
    }

    public static SkillRegistrySnapshot buildFromRepositoryReader(
            RepositoryReader reader,
            String repositoryThingName,
            String agentThingName,
            Set<String> reservedPlaybookShortIds,
            Logger log) {
        Instant now = Instant.now();
        LinkedHashMap<String, SkillRegistryDescriptor> map = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        if (reader == null || repositoryThingName == null || repositoryThingName.isBlank()) {
            return new SkillRegistrySnapshot(now, map, trimDiagnostics(diagnostics));
        }
        try {
            RepositorySkillScanner.scanAndMerge(reader, repositoryThingName.trim(), map, diagnostics,
                    msg -> log.error(msg),
                    msg -> log.warn(msg),
                    agentThingName,
                    reservedPlaybookShortIds != null ? reservedPlaybookShortIds : Set.of());
        } catch (Exception e) {
            if (RepositoryTextLoads.isProbablyMissingFile(e)) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] configurationRepository: /skills not present ({})",
                            agentThingName, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            } else {
                log.error("[{}] configurationRepository: skill scan failed: {}", agentThingName, e.getMessage(), e);
                diagnostics.add("repository scan failed: " + truncate(e.getMessage(), 200));
            }
        }
        return new SkillRegistrySnapshot(now, map, trimDiagnostics(diagnostics));
    }

    private static List<String> trimDiagnostics(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String l : lines) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(l);
        }
        String s = joined.toString();
        if (s.length() <= SkillRegistryLimits.MAX_SKILL_DIAGNOSTICS_CHARS) {
            return new ArrayList<>(lines);
        }
        List<String> out = new ArrayList<>();
        out.add(s.substring(0, SkillRegistryLimits.MAX_SKILL_DIAGNOSTICS_CHARS) + "… (truncated)");
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
