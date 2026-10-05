package com.thingworx.things.agent.skillregistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.thingworx.things.agent.SkillShortIdGrammar;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;

/**
 * Discovers repository-backed skills under {@code /skills} per {@code docs/agent/configuration-repository.md}.
 */
public final class RepositorySkillScanner {

    /** Loads raw {@code SKILL.md} for a skill directory id (e.g. {@code my_skill} → file {@code /skills/my_skill/SKILL.md}). */
    @FunctionalInterface
    public interface SkillMarkdownLoader {
        String load(String directoryShortId) throws Exception;
    }

    private RepositorySkillScanner() {}

    /**
     * Merges repository skills into {@code map}. When a short id is already present in {@code map}, the repository
     * directory with the same id is skipped (duplicate) with diagnostics.
     *
     * @return number of repository descriptors added
     */
    public static int scanAndMerge(
            RepositoryReader reader,
            String repositoryThingName,
            Map<String, SkillRegistryDescriptor> map,
            List<String> diagnostics,
            Consumer<String> onError,
            Consumer<String> onWarn,
            String agentThingName)
            throws Exception {
        return scanAndMerge(reader, repositoryThingName, map, diagnostics, onError, onWarn, agentThingName, Set.of());
    }

    public static int scanAndMerge(
            RepositoryReader reader,
            String repositoryThingName,
            Map<String, SkillRegistryDescriptor> map,
            List<String> diagnostics,
            Consumer<String> onError,
            Consumer<String> onWarn,
            String agentThingName,
            Set<String> reservedPlaybookShortIds)
            throws Exception {
        InfoTable listing = reader.getFileListing("/skills", "");
        List<String> dirNames = extractTopLevelDirectoryNames(listing);
        if (dirNames.isEmpty()) {
            return 0;
        }
        Collections.sort(dirNames);
        return scanAndMergeSortedDirectories(dirNames, repositoryThingName,
                dir -> reader.loadText("/skills/" + dir + "/SKILL.md"),
                map, diagnostics, onError, onWarn, agentThingName, reservedPlaybookShortIds);
    }

    /** Top-level directory names from a FileRepository {@code BrowseDirectory} listing (fileType {@code D}). */
    public static List<String> extractTopLevelDirectoryNames(InfoTable listing) {
        List<String> dirNames = new ArrayList<>();
        if (listing == null || listing.getRowCount() == 0) {
            return dirNames;
        }
        for (int i = 0; i < listing.getRowCount(); i++) {
            ValueCollection row = (ValueCollection) listing.getRow(i);
            if (row == null) {
                continue;
            }
            String fileType = cellString(row, CommonPropertyNames.PROP_FILETYPE);
            if (fileType == null || fileType.isEmpty() || !"D".equalsIgnoreCase(fileType)) {
                continue;
            }
            String name = cellString(row, CommonPropertyNames.PROP_NAME);
            if (name == null || name.isEmpty()) {
                continue;
            }
            dirNames.add(name);
        }
        return dirNames;
    }

    /**
     * Core merge loop for sorted top-level directory names. Used by {@link #scanAndMerge} and by JUnit (avoids
     * constructing {@link InfoTable} rows in tests).
     *
     * @return number of repository descriptors added
     */
    public static int scanAndMergeSortedDirectories(
            List<String> sortedDirectoryNames,
            String repositoryThingName,
            SkillMarkdownLoader loader,
            Map<String, SkillRegistryDescriptor> map,
            List<String> diagnostics,
            Consumer<String> onError,
            Consumer<String> onWarn,
            String agentThingName)
            throws Exception {
        return scanAndMergeSortedDirectories(sortedDirectoryNames, repositoryThingName, loader, map, diagnostics,
                onError, onWarn, agentThingName, Set.of());
    }

    public static int scanAndMergeSortedDirectories(
            List<String> sortedDirectoryNames,
            String repositoryThingName,
            SkillMarkdownLoader loader,
            Map<String, SkillRegistryDescriptor> map,
            List<String> diagnostics,
            Consumer<String> onError,
            Consumer<String> onWarn,
            String agentThingName,
            Set<String> reservedPlaybookShortIds)
            throws Exception {
        Map<String, String> lowerToAcceptedRepoDir = new HashMap<>();
        int accepted = 0;
        int processed = 0;
        int skippedBeyondCap = 0;
        for (String dir : sortedDirectoryNames) {
            if (dir.startsWith(".")) {
                diagnostics.add("skipped hidden directory: /" + dir);
                continue;
            }
            if (!SkillShortIdGrammar.isValid(dir)) {
                diagnostics.add("skipped invalid directory name: /" + dir);
                emit(onError, "[" + agentThingName + "] configurationRepository: invalid skill directory id: " + dir);
                continue;
            }
            if (reservedPlaybookShortIds != null && reservedPlaybookShortIds.contains(dir)) {
                String path = "/skills/" + dir + "/SKILL.md";
                diagnostics.add("skipped skill reserved by playbook: " + dir);
                emit(onError, "PLAYBOOK_SKILL_NAME_CONFLICT skillPath=" + path + " ignoredSkillId=" + dir
                        + " reservedByPlaybook=" + dir);
                continue;
            }
            String lower = dir.toLowerCase(Locale.ROOT);
            boolean caseConflict = false;
            for (String existingId : map.keySet()) {
                if (existingId.toLowerCase(Locale.ROOT).equals(lower) && !existingId.equals(dir)) {
                    diagnostics.add("skipped repository skill /" + dir + " (case conflicts with registered id " + existingId + ")");
                    emit(onError, "[" + agentThingName + "] configurationRepository: case conflict between /skills/" + dir + " and " + existingId);
                    caseConflict = true;
                    break;
                }
            }
            if (caseConflict) {
                continue;
            }
            String priorDir = lowerToAcceptedRepoDir.get(lower);
            if (priorDir != null && !priorDir.equals(dir)) {
                diagnostics.add("skipped case-only duplicate repository skill directory: /skills/" + dir
                        + " (conflicts with /skills/" + priorDir + ")");
                emit(onError, "[" + agentThingName + "] configurationRepository: case-only collision for directory " + dir);
                continue;
            }
            if (map.containsKey(dir)) {
                diagnostics.add("skipped duplicate repository skill: " + dir + " (already registered)");
                emit(onWarn, "[" + agentThingName + "] configurationRepository: skipped duplicate repository skill " + dir);
                continue;
            }
            if (processed >= SkillRegistryLimits.MAX_REPOSITORY_SKILLS) {
                skippedBeyondCap++;
                continue;
            }
            processed++;
            String path = "/skills/" + dir + "/SKILL.md";
            String raw;
            try {
                raw = loader.load(dir);
            } catch (Exception e) {
                diagnostics.add("skipped missing or unreadable SKILL.md: " + path + " (" + truncate(e.getMessage(), 120) + ")");
                emit(onError, "[" + agentThingName + "] configurationRepository: could not load " + path + ": " + e.getMessage());
                continue;
            }
            if (raw == null) {
                raw = "";
            }
            if (raw.length() > SkillRegistryLimits.MAX_SKILL_MD_CHARS) {
                diagnostics.add("skipped oversized SKILL.md: " + path + " (length " + raw.length() + ")");
                emit(onError, "[" + agentThingName + "] configurationRepository: SKILL.md too large: " + path);
                continue;
            }
            SkillMarkdownParser.Result parsed = SkillMarkdownParser.parse(raw, dir);
            if (parsed.nameMismatch()) {
                diagnostics.add("skipped SKILL.md name mismatch: " + path);
                emit(onError, "[" + agentThingName + "] configurationRepository: frontmatter name mismatch for " + path);
                continue;
            }
            String title = cap(parsed.title(), SkillRegistryLimits.MAX_SKILL_TITLE_CHARS);
            String when = cap(parsed.whenToUse(), SkillRegistryLimits.MAX_SKILL_WHEN_TO_USE_CHARS);
            if (when.isEmpty()) {
                diagnostics.add("warning: repository skill " + dir + " has no when_to_use / description in frontmatter");
                emit(onWarn, "[" + agentThingName + "] configurationRepository: skill " + dir + " missing routing hint (when_to_use/description)");
            }
            SkillRegistryDescriptor desc = new SkillRegistryDescriptor(
                    dir,
                    title,
                    when,
                    SkillSourceKind.REPOSITORY,
                    null,
                    repositoryThingName,
                    path);
            map.put(dir, desc);
            lowerToAcceptedRepoDir.put(lower, dir);
            accepted++;
        }
        if (skippedBeyondCap > 0) {
            diagnostics.add("skipped " + skippedBeyondCap + " repository skill directories beyond scan cap "
                    + SkillRegistryLimits.MAX_REPOSITORY_SKILLS
                    + " (cap counts each LoadText attempt; failed loads burn budget)");
            emit(onError, "[" + agentThingName + "] configurationRepository: " + skippedBeyondCap + " directories beyond MAX_REPOSITORY_SKILLS");
        }
        return accepted;
    }

    private static void emit(Consumer<String> sink, String line) {
        if (sink != null) {
            sink.accept(line);
        }
    }

    private static String cellString(ValueCollection row, String col) {
        Object v = row.getValue(col);
        if (v == null) {
            return null;
        }
        if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            try {
                Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
                return inner != null ? String.valueOf(inner) : "";
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

    private static String cap(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
