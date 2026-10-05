package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.execution.BudgetVector;

class InvestigationBudgetCompositionTest {

    @Test
    void searchLimitsProjectIntoMetricsWithoutPrivateLedger() {
        BudgetVector resources = BudgetVector.builder().maxWallTimeMillis(12_000L).build();
        InvestigationSearchLimits limits = InvestigationSearchLimits.builder()
                .relationDepth(2)
                .relationNodes(16)
                .candidateSignals(8)
                .events(40)
                .analysisCalls(6)
                .build();
        InvestigationBudget budget = InvestigationBudget.of(resources, limits);

        assertSame(resources, budget.resources());
        Map<String, String> metrics = budget.searchLimitMetrics();
        assertEquals("2", metrics.get("search.relationDepth"));
        assertEquals("16", metrics.get("search.relationNodes"));
        assertEquals("8", metrics.get("search.candidateSignals"));
        assertEquals("40", metrics.get("search.events"));
        assertEquals("6", metrics.get("search.analysisCalls"));

        AnalysisBudgetAccounting envelope = budget.toEnvelopeBudget(3, 100, 50, false);
        assertSame(resources, envelope.requested());
        assertSame(resources, envelope.effective());
        assertEquals(3, envelope.consumedRows());
        assertEquals(100, envelope.consumedBytes());
        assertEquals(50, envelope.consumedWallTimeMillis());
        assertFalse(envelope.clamped());
    }
}
