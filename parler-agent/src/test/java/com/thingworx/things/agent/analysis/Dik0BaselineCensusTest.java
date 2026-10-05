package com.thingworx.things.agent.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.HistoryOverlayChartBuilder;
import com.thingworx.things.agent.HistorySeriesComposerSupport;
import com.thingworx.things.agent.tools.BuildHistoryOverlayChartExecutor;
import com.thingworx.things.agent.tools.CachedTabularGroupMetricExecutor;
import com.thingworx.things.agent.tools.PeriodOverPeriodPeriodResolver;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;

/**
 * Locks the deterministic-insight-kernel baseline: the live history overlay anchors resolve, retired
 * period-over-period builder classes stay deleted, analysis methods are not advertised as tabulate
 * series modes, and the method capability catalog grants no admission.
 */
class Dik0BaselineCensusTest {

    @Test
    void liveHistoryOverlayAnchorsResolve() {
        assertEquals("BuildHistoryOverlayChartExecutor", BuildHistoryOverlayChartExecutor.class.getSimpleName());
        assertEquals("HistoryOverlayChartBuilder", HistoryOverlayChartBuilder.class.getSimpleName());
        assertEquals("HistorySeriesComposerSupport", HistorySeriesComposerSupport.class.getSimpleName());
        assertEquals("PeriodOverPeriodPeriodResolver", PeriodOverPeriodPeriodResolver.class.getSimpleName());
        assertEquals("CachedTabularGroupMetricExecutor", CachedTabularGroupMetricExecutor.class.getSimpleName());
    }

    @Test
    void retiredPopBuilderNamesAbsent() {
        for (String banned : new String[] {
                "PeriodOverPeriodChartBuilder",
                "MultiSeriesChartBuilder",
                "PeriodOverPeriodChartExecutor"
        }) {
            assertFalse(classExists("com.thingworx.things.agent." + banned),
                    "retired class must stay deleted: " + banned);
            assertFalse(classExists("com.thingworx.things.agent.tools." + banned),
                    "retired class must stay deleted: " + banned);
        }
    }

    @Test
    void dik0DoesNotAdvertiseU5AsTabulateModes() {
        // U5 stays off tabulate SERIES_MODES; DIK-5 advertises analyze_cached_result instead.
        assertFalse(TabulateCachedResultToolSchema.SERIES_MODES.contains("outlier"));
        assertFalse(TabulateCachedResultToolSchema.SERIES_MODES.contains("trend"));
        assertFalse(TabulateCachedResultToolSchema.SERIES_MODES.contains("threshold_crossing"));
        assertFalse(TabulateCachedResultToolSchema.SERIES_MODES.contains("relationship"));
    }

    @Test
    void capabilityCatalogIsNotAdmission() {
        // Catalog class is a plain final utility — no ToolDefinition / BuiltInTools binding.
        assertTrue(Modifier.isFinal(U5MethodCapabilityCatalog.class.getModifiers()));
        assertEquals(0, U5MethodCapabilityCatalog.class.getDeclaredConstructors()[0].getParameterCount());
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
