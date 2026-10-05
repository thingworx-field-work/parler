package com.thingworx.things.agent.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.investigation.CandidateCatalog;
import com.thingworx.things.agent.investigation.InvestigationBudget;
import com.thingworx.things.agent.tools.AnalyzeCachedResultToolSchema;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;

/**
 * Locks the fleet contract baseline: fleet modes are not advertised on the tabulate / analyze tool
 * surfaces, the fleet capability catalog grants no admission, the contract types resolve, and the
 * global returned-rows default is unchanged.
 */
class Frc0BaselineCensusTest {

    @Test
    void frc0DoesNotAdvertiseFleetModesOnTabulateOrAnalyze() {
        assertFalse(TabulateCachedResultToolSchema.SERIES_MODES.contains("fleet_benchmark"));
        assertFalse(AnalyzeCachedResultToolSchema.OPERATIONS.contains("fleet_benchmark"));
    }

    @Test
    void capabilityCatalogIsNotAdmission() {
        assertTrue(Modifier.isFinal(U6FleetCapabilityCatalog.class.getModifiers()));
        assertEquals(0, U6FleetCapabilityCatalog.class.getDeclaredConstructors()[0].getParameterCount());
    }

    @Test
    void contractTypesResolve() {
        assertEquals("CohortBatchSource", CohortBatchSource.class.getSimpleName());
        assertEquals("CompetitionRank", CompetitionRank.class.getSimpleName());
        assertEquals("CandidateCatalog", CandidateCatalog.class.getSimpleName());
        assertEquals("InvestigationBudget", InvestigationBudget.class.getSimpleName());
    }

    @Test
    void noGlobalReturnedRowsDefaultChange() {
        // BudgetVector default maxReturnedRows remains 5000 — FRC-0 must not change the global.
        assertEquals(5000L, BudgetVector.defaultsForTabular().maxReturnedRows());
    }
}
