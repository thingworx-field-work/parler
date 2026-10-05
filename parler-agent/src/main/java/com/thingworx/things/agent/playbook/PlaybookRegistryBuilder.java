package com.thingworx.things.agent.playbook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads.Kind;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositorySkillScanner;
import com.thingworx.things.agent.tools.ToolRegistry;
import com.thingworx.types.InfoTable;

/**
 * Discovers {@code /playbooks/&lt;id&gt;/playbook.json} packages and loads playbook documents during prompt-context
 * refresh.
 */
public final class PlaybookRegistryBuilder {

    /** Bound diagnostics list growth when many invalid packages exist (operator-controlled repos). */
    private static final int MAX_PLAYBOOK_REGISTRY_DIAGNOSTICS = 128;

    private PlaybookRegistryBuilder() {}

    public static PlaybookRegistrySnapshot build(AgentThing agent, ToolRegistry toolRegistry, Logger log) {
        return build(agent, PlaybookToolDefinitionsMerge.merge(toolRegistry, ExtendedToolRegistrySnapshot.missing()),
                log);
    }

    public static PlaybookRegistrySnapshot build(AgentThing agent, List<ToolDefinition> playbookToolDefs,
            ExtendedToolRegistrySnapshot extendedTools, Logger log) {
        Instant now = Instant.now();
        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return PlaybookRegistrySnapshot.empty(now);
        }
        var fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            return PlaybookRegistrySnapshot.empty(now);
        }
        return buildFromReader(FileRepositoryRepositoryReader.forConfiguration(fr.get()), agent.getName(), playbookToolDefs,
                extendedTools, log);
    }

    public static PlaybookRegistrySnapshot build(AgentThing agent, List<ToolDefinition> playbookToolDefs, Logger log) {
        return build(agent, playbookToolDefs, ExtendedToolRegistrySnapshot.missing(), log);
    }

    public static PlaybookRegistrySnapshot buildFromReader(
            RepositoryReader reader,
            String agentThingName,
            List<ToolDefinition> playbookToolDefs,
            Logger log) {
        return buildFromReader(reader, agentThingName, playbookToolDefs, ExtendedToolRegistrySnapshot.missing(), log);
    }

    public static PlaybookRegistrySnapshot buildFromReader(
            RepositoryReader reader,
            String agentThingName,
            List<ToolDefinition> playbookToolDefs,
            ExtendedToolRegistrySnapshot extendedTools,
            Logger log) {
        Instant now = Instant.now();
        List<PlaybookRegistryDiagnostic> diagnostics = new ArrayList<>();
        boolean[] truncated = {false};
        if (reader == null) {
            return PlaybookRegistrySnapshot.empty(now);
        }
        try {
            InfoTable listing = reader.getFileListing(PlaybookIds.PLAYBOOK_ROOT, "");
            List<String> dirNames = RepositorySkillScanner.extractTopLevelDirectoryNames(listing);
            List<String> candidates = new ArrayList<>();
            for (String name : dirNames) {
                if (name == null || name.isEmpty() || name.charAt(0) == '.') {
                    continue;
                }
                candidates.add(name);
            }
            Collections.sort(candidates);
            Map<String, PlaybookCatalogEntry> catalog = new LinkedHashMap<>();
            Map<String, PlaybookDocument> docs = new LinkedHashMap<>();
            for (String dirId : candidates) {
                if (catalog.size() >= PlaybookIds.MAX_PACKAGED_PLAYBOOKS) {
                    appendLimited(diagnostics, truncated,
                            PlaybookRegistryDiagnostic.warning("/playbooks", "PLAYBOOK_REGISTRY_CAP",
                                    "playbook registry capped at " + PlaybookIds.MAX_PACKAGED_PLAYBOOKS
                                            + " packages; skipping further directories (next: " + dirId + ")"));
                    break;
                }
                String path = PlaybookIds.playbookPathForId(dirId);
                try {
                    RepositoryTextLoads.Result docRes = RepositoryTextLoads.loadText(reader, path);
                    if (docRes.kind() != Kind.CONTENT) {
                        if (docRes.kind() == Kind.MISSING || docRes.kind() == Kind.EMPTY) {
                            continue;
                        }
                        log.error("[{}] configurationRepository: playbook document unreadable ({}): {}", agentThingName,
                                path, docRes.errorMessage());
                        appendLimited(diagnostics, truncated, PlaybookRegistryDiagnostic.error(path,
                                "PLAYBOOK_DOCUMENT_READ", String.valueOf(docRes.kind())));
                        continue;
                    }
                    String docText = docRes.text().trim();
                    JSONObject rootProbe = new JSONObject(docText);
                    PlaybookValidator.Result rootVal = PlaybookValidator.validatePlaybookJsonRoot(rootProbe);
                    if (!rootVal.valid()) {
                        for (String err : rootVal.errors()) {
                            log.error("[{}] configurationRepository: playbook invalid ({}): {}", agentThingName, path,
                                    err);
                            appendLimited(diagnostics, truncated,
                                    PlaybookRegistryDiagnostic.error(path, "PLAYBOOK_SCHEMA", err));
                        }
                        continue;
                    }
                    PlaybookDocument doc = PlaybookDocument.parse(docText);
                    PlaybookValidator.Result docVal =
                            PlaybookValidator.validateDocument(doc, dirId, playbookToolDefs, extendedTools);
                    if (!docVal.valid()) {
                        for (String err : docVal.errors()) {
                            log.error("[{}] configurationRepository: playbook invalid ({}): {}", agentThingName, path,
                                    err);
                            appendLimited(diagnostics, truncated,
                                    PlaybookRegistryDiagnostic.error(path, "PLAYBOOK_VALIDATION", err));
                        }
                        continue;
                    }
                    PlaybookCatalogEntry entry = entryFromMergedDocument(doc, path);
                    catalog.put(entry.id(), entry);
                    docs.put(entry.id(), doc);
                } catch (Exception e) {
                    log.error("[{}] configurationRepository: playbook package load failed ({}): {}", agentThingName,
                            path, e.getMessage(), e);
                    String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    appendLimited(diagnostics, truncated,
                            PlaybookRegistryDiagnostic.error(path, "PLAYBOOK_JSON_PARSE", detail));
                }
            }
            if (catalog.isEmpty()) {
                return PlaybookRegistrySnapshot.empty(now, diagnostics);
            }
            return PlaybookRegistrySnapshot.loaded(now, catalog, docs, diagnostics);
        } catch (Exception e) {
            log.error("[{}] configurationRepository: playbook registry build failed: {}", agentThingName,
                    e.getMessage(), e);
            appendLimited(diagnostics, truncated, PlaybookRegistryDiagnostic.error("/playbooks", "PLAYBOOK_BUILD",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return PlaybookRegistrySnapshot.empty(now, diagnostics);
        }
    }

    private static void appendLimited(List<PlaybookRegistryDiagnostic> dx, boolean[] truncated,
            PlaybookRegistryDiagnostic d) {
        if (dx.size() >= MAX_PLAYBOOK_REGISTRY_DIAGNOSTICS) {
            if (!truncated[0]) {
                truncated[0] = true;
                dx.add(PlaybookRegistryDiagnostic.warning("/playbooks", "PLAYBOOK_DIAGNOSTICS_TRUNCATED",
                        "additional playbook diagnostics suppressed after " + MAX_PLAYBOOK_REGISTRY_DIAGNOSTICS
                                + " lines"));
            }
            return;
        }
        dx.add(d);
    }

    static PlaybookCatalogEntry entryFromMergedDocument(PlaybookDocument doc, String effectivePath) {
        return new PlaybookCatalogEntry(
                doc.playbookId(),
                doc.title(),
                doc.description(),
                doc.whenToUse(),
                effectivePath,
                doc.inputSchema(),
                doc.execution());
    }
}
