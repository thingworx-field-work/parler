package com.thingworx.things.agent.fleet;

import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.execution.BudgetVector;

/**
 * U6-authored cohort batch-source adapter contract (FRC-0). App Services implement this; they
 * MUST NOT invent alternate member statuses or omit coverage/completeness facts.
 */
public interface CohortBatchSource {

    /**
     * Fetch one page of cohort member outcomes for the frozen authorized membership reference.
     *
     * @param membershipDigest opaque digest of the authorized eligible set frozen at execution start
     * @param window comparison window
     * @param metricProfileId validated metric/KPI profile id
     * @param pageToken null for first page
     * @param access current-principal access context
     * @param budget shared resource budget (never a private ledger)
     */
    CohortBatchSourceResult fetchPage(
            String membershipDigest,
            HalfOpenWindow window,
            String metricProfileId,
            String pageToken,
            ArtifactAccessContext access,
            BudgetVector budget);
}
