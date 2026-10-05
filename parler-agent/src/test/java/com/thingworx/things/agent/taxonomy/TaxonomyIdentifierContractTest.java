package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaxonomyIdentifierContractTest {

    @Test
    void contractTruncation_requiresKnownTotalAboveCap() {
        assertTrue(TaxonomyIdentifierContract.contractTruncationFromTotals(5001L, null));
        assertFalse(TaxonomyIdentifierContract.contractTruncationFromTotals(5000L, null));
        assertFalse(TaxonomyIdentifierContract.contractTruncationFromTotals(null, null));
        assertTrue(TaxonomyIdentifierContract.contractTruncationFromTotals(null, 6000L));
    }

    @Test
    void contractTotalUnderlying_omitsWhenUnknownOrWithinCap() {
        assertEquals(6000, TaxonomyIdentifierContract.contractTotalUnderlying(null, 6000L));
        assertNull(TaxonomyIdentifierContract.contractTotalUnderlying(null, null));
        assertNull(TaxonomyIdentifierContract.contractTotalUnderlying(4000L, null));
    }

    @Test
    void scanTruncation_platformTotalWins() {
        TaxonomyIdentifierContract.ScanTruncation t =
                TaxonomyIdentifierContract.scanTruncation(8000L, 3000L);
        assertTrue(t.truncated);
        assertEquals(8000, t.totalUnderlyingCount);
    }

    @Test
    void scanTruncation_inferredOnlyWhenPlatformNull() {
        TaxonomyIdentifierContract.ScanTruncation t =
                TaxonomyIdentifierContract.scanTruncation(null, 6000L);
        assertTrue(t.truncated);
        assertEquals(6000, t.totalUnderlyingCount);
    }

    @Test
    void scanTruncation_fullPageWithoutTotal_staysNotTruncated() {
        TaxonomyIdentifierContract.ScanTruncation t =
                TaxonomyIdentifierContract.scanTruncation(null, null);
        assertFalse(t.truncated);
        assertNull(t.totalUnderlyingCount);
    }
}
