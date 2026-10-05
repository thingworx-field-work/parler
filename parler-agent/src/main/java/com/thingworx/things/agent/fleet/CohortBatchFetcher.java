package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Pages a {@link CohortBatchSource} under the frozen membership digest (fleet-rca §7.1 step 3).
 * Enforces shared {@link BudgetVector} row and wall-time caps (D6). Does not apply U6 gates —
 * use {@link CohortGatePipeline} next.
 */
public final class CohortBatchFetcher {

    private static final int MAX_PAGES = 64;

    private CohortBatchFetcher() {}

    public static FetchedBatch fetchAll(
            CohortBatchSource source,
            FrozenCohortMembership membership,
            ArtifactAccessContext access,
            BudgetVector budget) {
        return fetchAll(source, membership, access, budget, System::nanoTime);
    }

    /** Package/test seam with injectable clock for wall-time budget tests. */
    static FetchedBatch fetchAll(
            CohortBatchSource source,
            FrozenCohortMembership membership,
            ArtifactAccessContext access,
            BudgetVector budget,
            LongSupplier nanoTime) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(nanoTime, "nanoTime");
        // access may be null only for pure unit fixtures; production App sources require it.

        long startNanos = nanoTime.getAsLong();
        long wallLimitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, budget.maxWallTimeMillis()));
        long maxRows = budget.maxReturnedRows();

        List<CohortBatchMemberRow> rows = new ArrayList<>();
        boolean permissionLimited = false;
        CompletenessStatus completeness = CompletenessStatus.COMPLETE;
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            enforceWallTime(startNanos, wallLimitNanos, nanoTime);
            CohortBatchSourceResult result = source.fetchPage(
                    membership.membershipDigest(),
                    membership.window(),
                    membership.metricProfileId(),
                    pageToken,
                    access,
                    budget);
            if (result == null) {
                throw new IllegalStateException("batch source returned null page");
            }
            if (result.permissionLimited()) {
                permissionLimited = true;
            }
            completeness = mergeCompleteness(completeness, result.completeness());
            rows.addAll(result.rows());
            if (rows.size() > maxRows) {
                throw new CohortCollectionException(
                        CohortCollectionException.COHORT_BUDGET_EXCEEDED,
                        "cohort batch rows " + rows.size() + " exceed budget.maxReturnedRows " + maxRows);
            }
            enforceWallTime(startNanos, wallLimitNanos, nanoTime);
            if (!result.hasMorePages()) {
                return new FetchedBatch(List.copyOf(rows), permissionLimited, completeness);
            }
            pageToken = result.nextPageToken();
        }
        throw new CohortCollectionException(
                CohortCollectionException.COHORT_BUDGET_EXCEEDED,
                "batch source exceeded max pages (" + MAX_PAGES + ")");
    }

    private static void enforceWallTime(long startNanos, long wallLimitNanos, LongSupplier nanoTime) {
        long elapsed = nanoTime.getAsLong() - startNanos;
        if (elapsed > wallLimitNanos) {
            throw new CohortCollectionException(
                    CohortCollectionException.COHORT_BUDGET_EXCEEDED,
                    "cohort batch wall time exceeded budget.maxWallTimeMillis");
        }
    }

    static CompletenessStatus mergeCompleteness(CompletenessStatus aggregate, CompletenessStatus page) {
        CompletenessStatus a = aggregate == null ? CompletenessStatus.COMPLETE : aggregate;
        CompletenessStatus p = page == null ? CompletenessStatus.COMPLETE : page;
        if (a == CompletenessStatus.UNKNOWN || p == CompletenessStatus.UNKNOWN) {
            return CompletenessStatus.UNKNOWN;
        }
        if (a == CompletenessStatus.PARTIAL || p == CompletenessStatus.PARTIAL) {
            return CompletenessStatus.PARTIAL;
        }
        return CompletenessStatus.COMPLETE;
    }

    public static final class FetchedBatch {
        private final List<CohortBatchMemberRow> rows;
        private final boolean permissionLimited;
        private final CompletenessStatus completeness;

        public FetchedBatch(
                List<CohortBatchMemberRow> rows,
                boolean permissionLimited,
                CompletenessStatus completeness) {
            this.rows = rows;
            this.permissionLimited = permissionLimited;
            this.completeness = completeness == null ? CompletenessStatus.COMPLETE : completeness;
        }

        public List<CohortBatchMemberRow> rows() {
            return rows;
        }

        public boolean permissionLimited() {
            return permissionLimited;
        }

        public CompletenessStatus completeness() {
            return completeness;
        }
    }
}
