package com.thingworx.things.agent.playbook;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Loaded playbook registry for prompt refresh (directory packages; {@link PlaybookCatalogEntry} is derived metadata). */
public final class PlaybookRegistrySnapshot {

    private final Instant loadedAtUtc;
    private final Map<String, PlaybookCatalogEntry> catalogById;
    private final Map<String, PlaybookDocument> documentsById;
    private final List<PlaybookRegistryDiagnostic> registryDiagnostics;
    private final boolean loaded;

    private PlaybookRegistrySnapshot(Instant loadedAtUtc, Map<String, PlaybookCatalogEntry> catalogById,
            Map<String, PlaybookDocument> documentsById, List<PlaybookRegistryDiagnostic> registryDiagnostics,
            boolean loaded) {
        this.loadedAtUtc = loadedAtUtc;
        this.catalogById = Collections.unmodifiableMap(catalogById);
        this.documentsById = Collections.unmodifiableMap(documentsById);
        this.registryDiagnostics = Collections.unmodifiableList(registryDiagnostics);
        this.loaded = loaded;
    }

    public static PlaybookRegistrySnapshot empty(Instant at) {
        return empty(at, List.of());
    }

    /** Empty registry with optional diagnostics (failed discovery attempts with zero successes). */
    public static PlaybookRegistrySnapshot empty(Instant at, List<PlaybookRegistryDiagnostic> diagnostics) {
        List<PlaybookRegistryDiagnostic> d = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        return new PlaybookRegistrySnapshot(at, Map.of(), Map.of(), d, false);
    }

    /**
     * Registry after directory discovery. {@code loaded} is true iff at least one playbook was admitted (partial
     * success supported).
     */
    public static PlaybookRegistrySnapshot loaded(Instant at, Map<String, PlaybookCatalogEntry> catalog,
            Map<String, PlaybookDocument> docs, List<PlaybookRegistryDiagnostic> diagnostics) {
        boolean any = catalog != null && !catalog.isEmpty();
        return new PlaybookRegistrySnapshot(at,
                catalog != null ? catalog : Map.of(),
                docs != null ? docs : Map.of(),
                diagnostics != null ? diagnostics : List.of(),
                any);
    }

    public Instant loadedAtUtc() {
        return loadedAtUtc;
    }

    public Map<String, PlaybookCatalogEntry> catalogById() {
        return catalogById;
    }

    public Map<String, PlaybookDocument> documentsById() {
        return documentsById;
    }

    /** Structured diagnostics (severity, path, code) for authoring validation and other consumers. */
    public List<PlaybookRegistryDiagnostic> registryDiagnostics() {
        return registryDiagnostics;
    }

    /**
     * Legacy formatted lines for runtime snapshot JSON ({@code playbooks.diagnostics[]}) — derived from
     * {@link #registryDiagnostics()}.
     */
    public List<String> diagnostics() {
        return registryDiagnostics.stream().map(PlaybookRegistryDiagnostic::snapshotLine).collect(Collectors.toList());
    }

    public boolean isLoaded() {
        return loaded;
    }

    public Set<String> reservedSlashIds() {
        return loaded ? catalogById.keySet() : Set.of();
    }

    public PlaybookDocument document(String playbookId) {
        return documentsById.get(playbookId);
    }
}
