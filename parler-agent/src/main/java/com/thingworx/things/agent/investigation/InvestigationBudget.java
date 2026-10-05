package com.thingworx.things.agent.investigation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.thingworx.things.agent.analysis.AnalysisBudgetAccounting;
import com.thingworx.things.agent.execution.BudgetVector;

/**
 * Typed U6 composition over the sole shared {@link BudgetVector} (fleet-rca D10). Search caps are
 * projected into envelope metrics; requested/effective/consumed/clamped accounting stays on
 * {@link AnalysisBudgetAccounting}.
 */
public final class InvestigationBudget {

    private final BudgetVector resources;
    private final InvestigationSearchLimits searchLimits;

    private InvestigationBudget(BudgetVector resources, InvestigationSearchLimits searchLimits) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.searchLimits = Objects.requireNonNull(searchLimits, "searchLimits");
    }

    public static InvestigationBudget of(BudgetVector resources, InvestigationSearchLimits searchLimits) {
        return new InvestigationBudget(resources, searchLimits);
    }

    public static InvestigationBudget defaults() {
        return of(BudgetVector.defaultsForTabular(), InvestigationSearchLimits.defaults());
    }

    public BudgetVector resources() {
        return resources;
    }

    public InvestigationSearchLimits searchLimits() {
        return searchLimits;
    }

    /** Project search caps into envelope metric keys (no private ledger). */
    public Map<String, String> searchLimitMetrics() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("search.relationDepth", Integer.toString(searchLimits.relationDepth()));
        m.put("search.relationNodes", Integer.toString(searchLimits.relationNodes()));
        m.put("search.candidateSignals", Integer.toString(searchLimits.candidateSignals()));
        m.put("search.events", Integer.toString(searchLimits.events()));
        m.put("search.analysisCalls", Integer.toString(searchLimits.analysisCalls()));
        return Map.copyOf(m);
    }

    /** Base envelope budget block uses the shared vector only. */
    public AnalysisBudgetAccounting toEnvelopeBudget(long consumedRows, long consumedBytes, long wallMs,
            boolean clamped) {
        return AnalysisBudgetAccounting.builder()
                .requested(resources)
                .effective(resources)
                .consumedRows(consumedRows)
                .consumedBytes(consumedBytes)
                .consumedWallTimeMillis(wallMs)
                .clamped(clamped)
                .build();
    }
}
