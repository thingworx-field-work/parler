package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Batch-source response shape for U6 cohort collection (FRC-0). Completeness and paging facts
 * ride beside member rows; unauthorized identities never appear as {@link CohortMemberStatus#NO_DATA}.
 */
public final class CohortBatchSourceResult {

    private final List<CohortBatchMemberRow> rows;
    private final CompletenessStatus completeness;
    private final boolean hasMorePages;
    private final String nextPageToken;
    private final boolean permissionLimited;

    private CohortBatchSourceResult(Builder b) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(b.rows, "rows")));
        this.completeness = Objects.requireNonNull(b.completeness, "completeness");
        this.hasMorePages = b.hasMorePages;
        this.nextPageToken = blankToNull(b.nextPageToken);
        this.permissionLimited = b.permissionLimited;
        if (hasMorePages && nextPageToken == null) {
            throw new IllegalArgumentException("hasMorePages requires nextPageToken");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<CohortBatchMemberRow> rows() {
        return rows;
    }

    public CompletenessStatus completeness() {
        return completeness;
    }

    public boolean hasMorePages() {
        return hasMorePages;
    }

    public String nextPageToken() {
        return nextPageToken;
    }

    public boolean permissionLimited() {
        return permissionLimited;
    }

    /**
     * Derive <em>source-stage</em> authorized-only coverage from returned rows.
     * {@link CohortMemberStatus#ELIGIBLE_VALUE} maps to {@code valuedN} (not {@code comparableN});
     * {@code comparableN} stays 0 until FRC-1 applies U6 quality gates.
     * {@link CohortMemberStatus#PERMISSION_LIMITED} rows contribute only to
     * {@code permissionLimited}, never to integer denominators.
     */
    public CohortCoverageCounts toCoverageCounts(int requestedAuthorizedN) {
        int returned = 0;
        int valued = 0;
        int noData = 0;
        int incomparable = 0;
        int insufficient = 0;
        int error = 0;
        boolean perm = permissionLimited;
        for (CohortBatchMemberRow row : rows) {
            if (row.status() == CohortMemberStatus.PERMISSION_LIMITED) {
                perm = true;
                continue;
            }
            returned++;
            switch (row.status()) {
                case ELIGIBLE_VALUE:
                    valued++;
                    break;
                case NO_DATA:
                    noData++;
                    break;
                case INCOMPARABLE:
                    incomparable++;
                    break;
                case INSUFFICIENT_EVIDENCE:
                    insufficient++;
                    break;
                case ERROR:
                    error++;
                    break;
                case PERMISSION_LIMITED:
                    // unreachable after continue
                    break;
            }
        }
        return CohortCoverageCounts.builder()
                .requestedAuthorizedN(requestedAuthorizedN)
                .returnedN(returned)
                .valuedN(valued)
                .comparableN(0)
                .noDataN(noData)
                .incomparableN(incomparable)
                .insufficientEvidenceN(insufficient)
                .errorN(error)
                .permissionLimited(perm)
                .build();
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    public static final class Builder {
        private List<CohortBatchMemberRow> rows = List.of();
        private CompletenessStatus completeness = CompletenessStatus.COMPLETE;
        private boolean hasMorePages;
        private String nextPageToken;
        private boolean permissionLimited;

        public Builder rows(List<CohortBatchMemberRow> v) {
            this.rows = v == null ? List.of() : v;
            return this;
        }

        public Builder completeness(CompletenessStatus v) {
            this.completeness = v;
            return this;
        }

        public Builder hasMorePages(boolean v) {
            this.hasMorePages = v;
            return this;
        }

        public Builder nextPageToken(String v) {
            this.nextPageToken = v;
            return this;
        }

        public Builder permissionLimited(boolean v) {
            this.permissionLimited = v;
            return this;
        }

        public CohortBatchSourceResult build() {
            return new CohortBatchSourceResult(this);
        }
    }
}
