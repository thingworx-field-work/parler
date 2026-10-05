package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/** In-memory batch source for FRC-1 fetcher tests. */
final class FixtureCohortBatchSource implements CohortBatchSource {

    private final List<List<CohortBatchMemberRow>> pages;
    private final List<CompletenessStatus> pageCompleteness;
    private final boolean permissionLimited;

    FixtureCohortBatchSource(
            List<List<CohortBatchMemberRow>> pages,
            List<CompletenessStatus> pageCompleteness,
            boolean permissionLimited) {
        this.pages = pages;
        this.pageCompleteness = pageCompleteness;
        this.permissionLimited = permissionLimited;
    }

    static FixtureCohortBatchSource singlePage(List<CohortBatchMemberRow> rows, boolean permissionLimited) {
        return new FixtureCohortBatchSource(
                List.of(rows), List.of(CompletenessStatus.COMPLETE), permissionLimited);
    }

    static FixtureCohortBatchSource pages(
            List<List<CohortBatchMemberRow>> pages,
            List<CompletenessStatus> completeness,
            boolean permissionLimited) {
        return new FixtureCohortBatchSource(pages, completeness, permissionLimited);
    }

    @Override
    public CohortBatchSourceResult fetchPage(
            String membershipDigest,
            HalfOpenWindow window,
            String metricProfileId,
            String pageToken,
            ArtifactAccessContext access,
            BudgetVector budget) {
        int index = pageToken == null ? 0 : Integer.parseInt(pageToken);
        List<CohortBatchMemberRow> page = index < pages.size() ? pages.get(index) : List.of();
        CompletenessStatus completeness = index < pageCompleteness.size()
                ? pageCompleteness.get(index)
                : CompletenessStatus.COMPLETE;
        boolean more = index + 1 < pages.size();
        return CohortBatchSourceResult.builder()
                .rows(new ArrayList<>(page))
                .completeness(completeness)
                .hasMorePages(more)
                .nextPageToken(more ? Integer.toString(index + 1) : null)
                .permissionLimited(permissionLimited)
                .build();
    }
}
