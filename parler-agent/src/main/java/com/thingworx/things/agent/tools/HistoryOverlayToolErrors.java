package com.thingworx.things.agent.tools;

/**
 * Error-code boundary for {@code build_history_overlay_chart} — maps reused internal helpers to
 * overlay-owned {@code HISTORY_OVERLAY_*} codes so retired PoP / multi-series tool codes never leak.
 *
 * <p>S4 (U2 M4): {@link #INVALID_TIME_WINDOW} is reserved for actual window-resolution / duration
 * failures. Non-window argument defects use distinct codes ({@link #INVALID_ANCHOR_TIME},
 * {@link #INVALID_SERIES_ENTRY}, {@link #MISSING_SERIES_LABEL}).
 */
public final class HistoryOverlayToolErrors {

    /** True window-resolution / non-positive duration failures only. */
    public static final String INVALID_TIME_WINDOW = "HISTORY_OVERLAY_INVALID_TIME_WINDOW";

    /** Root {@code anchorTime} / {@code anchorInstant} is not a valid ISO-8601 instant. */
    public static final String INVALID_ANCHOR_TIME = "HISTORY_OVERLAY_INVALID_ANCHOR_TIME";

    /** A {@code series[]} element is missing or not a JSON object. */
    public static final String INVALID_SERIES_ENTRY = "HISTORY_OVERLAY_INVALID_SERIES_ENTRY";

    /** A {@code series[]} element lacks a non-empty {@code label}. */
    public static final String MISSING_SERIES_LABEL = "HISTORY_OVERLAY_MISSING_SERIES_LABEL";

    private HistoryOverlayToolErrors() {}

    /**
     * Maps any {@link PeriodOverPeriodPeriodResolver} failure to overlay-owned window error code.
     * Upstream PoP-boundary codes (e.g. {@code POP_INVALID_PERIOD}) must not cross this boundary.
     */
    public static String windowResolutionError(PeriodOverPeriodPeriodResolver.Outcome window) {
        if (window == null || !window.isError()) {
            return BuiltInToolTimeErrorJson.error(INVALID_TIME_WINDOW, "Invalid time window.", null);
        }
        return BuiltInToolTimeErrorJson.error(INVALID_TIME_WINDOW, window.errorMessage, window.rejectedParameter);
    }

    /** True when {@code code} is a retired PoP / multi-series tool error prefix. */
    public static boolean isRetiredChartToolCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return code.startsWith("POP_") || code.startsWith("MULTI_SERIES_");
    }
}
