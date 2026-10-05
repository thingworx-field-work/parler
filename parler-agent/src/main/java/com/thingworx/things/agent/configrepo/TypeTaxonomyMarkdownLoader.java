package com.thingworx.things.agent.configrepo;

import java.util.Objects;

import org.slf4j.Logger;

import com.thingworx.things.agent.skillregistry.RepositoryReader;

/** Loads {@code /taxonomies/type-taxonomy.md} with v1 size and BOM rules. */
public final class TypeTaxonomyMarkdownLoader {

    public static final int MAX_CHARS = 32 * 1024;

    public enum Status {
        /** Path absent or classified as missing by the reader. */
        MISSING,
        /** Present but blank after trim. */
        EMPTY,
        /** Loaded within the size cap. */
        LOADED,
        /** Present but exceeds {@link #MAX_CHARS} after BOM strip. */
        OVERSIZED,
        /** Reader reported a non-missing failure. */
        READ_ERROR
    }

    /**
     * Outcome of loading type taxonomy markdown for diagnostics and prompt assembly.
     *
     * @param promptText file text after UTF-8 decode and BOM strip when {@link Status#LOADED} (not normalized with
     *                   {@code String#trim} except for the empty-after-strip case); otherwise empty
     * @param measuredCharCount for {@link Status#LOADED} the length of {@code promptText}; for {@link Status#OVERSIZED}
     *                          the length before the cap (after BOM strip)
     */
    public static final class TypeTaxonomyMarkdownOutcome {
        private final Status status;
        private final String promptText;
        private final int measuredCharCount;

        public TypeTaxonomyMarkdownOutcome(Status status, String promptText, int measuredCharCount) {
            this.status = Objects.requireNonNull(status, "status");
            this.promptText = promptText != null ? promptText : "";
            this.measuredCharCount = measuredCharCount;
        }

        public Status status() {
            return status;
        }

        public String promptText() {
            return promptText;
        }

        public int measuredCharCount() {
            return measuredCharCount;
        }
    }

    private TypeTaxonomyMarkdownLoader() {}

    /**
     * @return file text after UTF-8 decode and BOM strip (leading/trailing whitespace preserved for non-empty
     *         {@link Status#LOADED} content; blank-after-strip is classified as {@link Status#EMPTY}); empty string when
     *         missing; {@code null} when file exists but exceeds cap (caller should log and treat as empty for prompt)
     */
    public static String loadOrNullIfOversized(RepositoryReader reader) throws Exception {
        return loadOrNullIfOversized(reader, null);
    }

    /**
     * @param log when non-null, actual read failures (not missing files) are logged at ERROR
     */
    public static String loadOrNullIfOversized(RepositoryReader reader, Logger log) throws Exception {
        TypeTaxonomyMarkdownOutcome o = loadWithStatus(reader, log);
        if (o.status() == Status.OVERSIZED) {
            return null;
        }
        if (o.status() == Status.READ_ERROR) {
            return "";
        }
        return o.promptText();
    }

    /**
     * Distinguishes missing vs empty vs oversized vs read errors for operator snapshots and validation.
     */
    public static TypeTaxonomyMarkdownOutcome loadWithStatus(RepositoryReader reader, Logger log) {
        if (reader == null) {
            return new TypeTaxonomyMarkdownOutcome(Status.MISSING, "", 0);
        }
        RepositoryTextLoads.Result r =
                RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.TAXONOMY_TYPE_MARKDOWN);
        switch (r.kind()) {
            case MISSING:
                return new TypeTaxonomyMarkdownOutcome(Status.MISSING, "", 0);
            case EMPTY:
                return new TypeTaxonomyMarkdownOutcome(Status.EMPTY, "", 0);
            case READ_ERROR:
                if (log != null) {
                    log.error("configurationRepository: type taxonomy markdown read failed: {}", r.errorMessage());
                }
                return new TypeTaxonomyMarkdownOutcome(Status.READ_ERROR, "", 0);
            case CONTENT:
            default:
                break;
        }
        String raw = r.text();
        if (raw == null) {
            return new TypeTaxonomyMarkdownOutcome(Status.EMPTY, "", 0);
        }
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1);
        }
        if (raw.trim().isEmpty()) {
            return new TypeTaxonomyMarkdownOutcome(Status.EMPTY, "", 0);
        }
        int len = raw.length();
        if (len > MAX_CHARS) {
            return new TypeTaxonomyMarkdownOutcome(Status.OVERSIZED, "", len);
        }
        return new TypeTaxonomyMarkdownOutcome(Status.LOADED, raw, len);
    }
}
