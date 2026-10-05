package com.thingworx.things.agent.analysis.outlier;

/**
 * Explicit policy when MAD or IQR is zero (§7.1). Echoed in envelope metrics.
 */
public enum ZeroDispersionPolicy {
    /** Fall back from robust-z to IQR when MAD is zero. */
    FALLBACK_IQR,
    /** Return insufficient evidence with outcome {@code ZERO_DISPERSION}. */
    INSUFFICIENT
}
