package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Versioned U6 relation/event candidate catalog (FRC-0). Core owns schema/validation/ordering;
 * Apps populate entries. Invalid data fails closed — no model-name fallback.
 */
public final class CandidateCatalog {

    private final String catalogId;
    private final String version;
    private final int maxRelationDepth;
    private final int maxRelationNodes;
    private final List<CatalogRelationEntry> relations;
    private final List<CatalogSignalEntry> signals;
    private final List<CatalogServiceBinding> serviceBindings;

    private CandidateCatalog(Builder b) {
        this.catalogId = requireNonBlank(b.catalogId, "catalogId");
        this.version = requireNonBlank(b.version, "version");
        this.maxRelationDepth = requirePositive(b.maxRelationDepth, "maxRelationDepth");
        this.maxRelationNodes = requirePositive(b.maxRelationNodes, "maxRelationNodes");
        this.relations = sortedCopyRelations(b.relations);
        this.signals = sortedCopySignals(b.signals);
        this.serviceBindings = sortedCopyBindings(b.serviceBindings);
        CandidateCatalogValidator.validateOrThrow(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String catalogId() {
        return catalogId;
    }

    public String version() {
        return version;
    }

    public int maxRelationDepth() {
        return maxRelationDepth;
    }

    public int maxRelationNodes() {
        return maxRelationNodes;
    }

    public List<CatalogRelationEntry> relations() {
        return relations;
    }

    public List<CatalogSignalEntry> signals() {
        return signals;
    }

    public List<CatalogServiceBinding> serviceBindings() {
        return serviceBindings;
    }

    private static List<CatalogRelationEntry> sortedCopyRelations(List<CatalogRelationEntry> in) {
        List<CatalogRelationEntry> out = new ArrayList<>(in == null ? List.of() : in);
        out.sort(Comparator
                .comparing(CatalogRelationEntry::relationTypeId)
                .thenComparing(CatalogRelationEntry::fromAssetSemanticId)
                .thenComparing(CatalogRelationEntry::toAssetSemanticId)
                .thenComparingInt(CatalogRelationEntry::declaredDistance));
        return Collections.unmodifiableList(out);
    }

    private static List<CatalogSignalEntry> sortedCopySignals(List<CatalogSignalEntry> in) {
        List<CatalogSignalEntry> out = new ArrayList<>(in == null ? List.of() : in);
        out.sort(Comparator
                .comparing(CatalogSignalEntry::assetTypeKey)
                .thenComparing(CatalogSignalEntry::signalSemanticId));
        return Collections.unmodifiableList(out);
    }

    private static List<CatalogServiceBinding> sortedCopyBindings(List<CatalogServiceBinding> in) {
        List<CatalogServiceBinding> out = new ArrayList<>(in == null ? List.of() : in);
        out.sort(Comparator
                .comparing((CatalogServiceBinding b) -> b.kind().name())
                .thenComparing(CatalogServiceBinding::thingName)
                .thenComparing(CatalogServiceBinding::serviceName));
        return Collections.unmodifiableList(out);
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }

    private static int requirePositive(int v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return v;
    }

    public static final class Builder {
        private String catalogId;
        private String version = "1";
        private int maxRelationDepth = 2;
        private int maxRelationNodes = 32;
        private List<CatalogRelationEntry> relations = List.of();
        private List<CatalogSignalEntry> signals = List.of();
        private List<CatalogServiceBinding> serviceBindings = List.of();

        public Builder catalogId(String v) {
            this.catalogId = v;
            return this;
        }

        public Builder version(String v) {
            this.version = v;
            return this;
        }

        public Builder maxRelationDepth(int v) {
            this.maxRelationDepth = v;
            return this;
        }

        public Builder maxRelationNodes(int v) {
            this.maxRelationNodes = v;
            return this;
        }

        public Builder relations(List<CatalogRelationEntry> v) {
            this.relations = v;
            return this;
        }

        public Builder signals(List<CatalogSignalEntry> v) {
            this.signals = v;
            return this;
        }

        public Builder serviceBindings(List<CatalogServiceBinding> v) {
            this.serviceBindings = v;
            return this;
        }

        public CandidateCatalog build() {
            Objects.requireNonNull(catalogId, "catalogId");
            return new CandidateCatalog(this);
        }
    }
}
