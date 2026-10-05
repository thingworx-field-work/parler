package com.thingworx.things.agent.fleet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Demo App peer/batch adapter for FRC-4. Supplies typed {@link CohortBatchMemberRow} pages for a
 * frozen authorized membership. Real site Apps replace the in-memory map with a governed batch
 * Service; they MUST preserve statuses/coverage and MUST NOT invent unauthorized identities.
 */
public final class DemoPeerCohortBatchAdapter implements CohortBatchSource {

    public static final String PROFILE_DIGEST = "u6-demo-fleet-peer-v1";

    private final Map<String, CohortBatchMemberRow> byAssetId;
    private final boolean permissionLimited;
    private final CompletenessStatus completeness;
    private final int pageSize;
    private int fetchCount;

    public DemoPeerCohortBatchAdapter(
            List<CohortBatchMemberRow> authorizedRows,
            boolean permissionLimited,
            CompletenessStatus completeness,
            int pageSize) {
        Objects.requireNonNull(authorizedRows, "authorizedRows");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0");
        }
        Map<String, CohortBatchMemberRow> map = new LinkedHashMap<>();
        for (CohortBatchMemberRow row : authorizedRows) {
            if (row == null) {
                continue;
            }
            if (row.status() == CohortMemberStatus.PERMISSION_LIMITED) {
                throw new IllegalArgumentException(
                        "demo adapter stores only authorized rows; permissionLimited is a page flag");
            }
            map.put(row.semanticAssetId(), row);
        }
        this.byAssetId = Map.copyOf(map);
        this.permissionLimited = permissionLimited;
        this.completeness = completeness == null ? CompletenessStatus.COMPLETE : completeness;
        this.pageSize = pageSize;
    }

    public static DemoPeerCohortBatchAdapter of(List<CohortBatchMemberRow> rows) {
        return new DemoPeerCohortBatchAdapter(rows, false, CompletenessStatus.COMPLETE, 500);
    }

    public static DemoPeerCohortBatchAdapter permissionLimited(List<CohortBatchMemberRow> rows) {
        return new DemoPeerCohortBatchAdapter(rows, true, CompletenessStatus.COMPLETE, 500);
    }

    public String profileDigest() {
        return PROFILE_DIGEST;
    }

    /** How many {@link #fetchPage} calls occurred (proves batch path, not per-member model loops). */
    public int fetchCount() {
        return fetchCount;
    }

    @Override
    public CohortBatchSourceResult fetchPage(
            String membershipDigest,
            HalfOpenWindow window,
            String metricProfileId,
            String pageToken,
            ArtifactAccessContext access,
            BudgetVector budget) {
        fetchCount++;
        // Offline demos may pass null access; production Apps supply Core-minted context.
        List<CohortBatchMemberRow> all = new ArrayList<>(byAssetId.values());
        int index = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
        int from = index * pageSize;
        if (from >= all.size()) {
            return CohortBatchSourceResult.builder()
                    .rows(List.of())
                    .completeness(completeness)
                    .hasMorePages(false)
                    .nextPageToken(null)
                    .permissionLimited(permissionLimited)
                    .build();
        }
        int to = Math.min(all.size(), from + pageSize);
        boolean more = to < all.size();
        return CohortBatchSourceResult.builder()
                .rows(new ArrayList<>(all.subList(from, to)))
                .completeness(completeness)
                .hasMorePages(more)
                .nextPageToken(more ? Integer.toString(index + 1) : null)
                .permissionLimited(permissionLimited)
                .build();
    }
}
