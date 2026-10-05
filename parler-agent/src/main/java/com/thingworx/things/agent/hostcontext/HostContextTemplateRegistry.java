package com.thingworx.things.agent.hostcontext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.repository.FileRepositoryThing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;

/**
 * Loads {@code host-contexts/*.json} from the configured ConfigurationRepository only.
 * Classpath built-in templates are not runtime fallbacks (host-context-generic-fallback).
 */
public final class HostContextTemplateRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(HostContextTemplateRegistry.class);
    public static final String REPO_PREFIX = "host-contexts/";

    /** Test-only overlay merged before repository templates (repo wins on duplicate key). */
    private static volatile Map<String, HostContextTemplate> testOverlay;

    private HostContextTemplateRegistry() {
    }

    public static Map<String, HostContextTemplate> builtInTemplates() {
        return Collections.emptyMap();
    }

    /**
     * Repository templates plus optional test overlay (repo wins on duplicate key).
     */
    public static Map<String, HostContextTemplate> resolveForAgent(AgentThing agent) {
        LinkedHashMap<String, HostContextTemplate> merged = new LinkedHashMap<>();
        Map<String, HostContextTemplate> overlay = testOverlay;
        if (overlay != null) {
            merged.putAll(overlay);
        }
        if (agent == null) {
            return Collections.unmodifiableMap(merged);
        }
        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return Collections.unmodifiableMap(merged);
        }
        Optional<FileRepositoryThing> fr = FileRepositoryThingResolver.resolve(repoName.trim(), LOG, agent.getName());
        if (fr.isEmpty()) {
            return Collections.unmodifiableMap(merged);
        }
        mergeRepositoryTemplates(merged, FileRepositoryRepositoryReader.forConfiguration(fr.get()), repoName.trim());
        return Collections.unmodifiableMap(merged);
    }

    public static HostContextTemplate find(String key, AgentThing agent) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return resolveForAgent(agent).get(key.trim());
    }

    /** Test-only: clears overlay so tests start from an empty registry. */
    public static void resetBuiltInCacheForTests() {
        testOverlay = null;
    }

    /** Test-only: registers templates without a ConfigurationRepository. */
    static void registerTestTemplatesForTests(Map<String, HostContextTemplate> templates) {
        testOverlay = templates != null && !templates.isEmpty()
                ? Collections.unmodifiableMap(new LinkedHashMap<>(templates))
                : null;
    }

    private static void mergeRepositoryTemplates(
            Map<String, HostContextTemplate> map,
            RepositoryReader reader,
            String repoName) {
        if (reader == null) {
            return;
        }
        InfoTable listing;
        try {
            listing = reader.getFileListing("/host-contexts", "");
        } catch (Exception e) {
            if (!RepositoryTextLoads.isProbablyMissingFile(e)) {
                LOG.warn("[{}] host-contexts list failed: {}", repoName, e.getMessage());
            }
            return;
        }
        List<String> paths = extractJsonFilePaths(listing);
        for (String fileName : paths) {
            String path = REPO_PREFIX + fileName;
            try {
                RepositoryTextLoads.Result r = RepositoryTextLoads.loadText(reader, path);
                if (r.kind() != RepositoryTextLoads.Kind.CONTENT) {
                    continue;
                }
                HostContextTemplate t = HostContextTemplate.fromJson(new JSONObject(r.text()));
                map.put(t.key(), t);
            } catch (Exception e) {
                LOG.warn("[{}] host-context template {} invalid: {}", repoName, path, e.getMessage());
            }
        }
    }

    static List<String> extractJsonFilePaths(InfoTable listing) {
        List<String> files = new ArrayList<>();
        if (listing == null || listing.getRowCount() == 0) {
            return files;
        }
        for (int i = 0; i < listing.getRowCount(); i++) {
            ValueCollection row = (ValueCollection) listing.getRow(i);
            if (row == null) {
                continue;
            }
            String name = cellString(row, CommonPropertyNames.PROP_NAME);
            if (name == null || !name.endsWith(".json")) {
                continue;
            }
            String fileType = cellString(row, CommonPropertyNames.PROP_FILETYPE);
            if (fileType != null && "D".equalsIgnoreCase(fileType)) {
                continue;
            }
            files.add(name);
        }
        return files;
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
}
