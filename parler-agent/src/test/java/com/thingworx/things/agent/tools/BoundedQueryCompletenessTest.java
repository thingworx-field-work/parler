package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.tools.BoundedQueryCompleteness.Completeness;
import com.thingworx.things.agent.tools.BoundedQueryCompleteness.ListingValidity;
import com.thingworx.things.agent.tools.BoundedQueryCompleteness.PlatformTotal;
import com.thingworx.things.agent.tools.BoundedQueryCompleteness.Result;

class BoundedQueryCompletenessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int QIT = 5000;

    @Test
    void numericOkAcceptsIntegralDoubleAndRejectsFractional() {
        assertEquals(10L, BoundedQueryCompleteness.platformTotalFromNumber(10.0).parsedTotal);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromNumber(10.5).parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromNumber(-1).parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromNumber(Double.NaN).parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromNumber(Double.POSITIVE_INFINITY).parseStatus);
    }

    @Test
    void numericOkExactLongBoundsWithoutDoubleRounding() {
        assertEquals(Long.MAX_VALUE,
                BoundedQueryCompleteness.platformTotalFromNumber(Long.MAX_VALUE).parsedTotal);
        assertEquals(Long.MAX_VALUE,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("9223372036854775807").parsedTotal);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.OK,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("10.0").parseStatus);
        assertEquals(10L, BoundedQueryCompleteness.platformTotalFromExactDecimalText("10.0").parsedTotal);

        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("9223372036854775808").parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("9223372036854775809").parseStatus);
        // Lossy double path that previously accepted 2^63 as Long.MAX_VALUE must stay MALFORMED.
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromNumber(Double.valueOf("9223372036854775808")).parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("10.5").parseStatus);
        assertEquals(BoundedQueryCompleteness.TotalParseStatus.MALFORMED,
                BoundedQueryCompleteness.platformTotalFromExactDecimalText("-1").parseStatus);
    }

    @Test
    void querySideTable_usableMissingInconsistent() {
        assertEquals(Completeness.COMPLETE,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.ok(0), 0, QIT));
        assertEquals(Completeness.TRUNCATED,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.ok(6000), 12, QIT));
        assertEquals(Completeness.COMPLETE,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.absent(), 12, QIT));
        assertEquals(Completeness.UNKNOWN,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.absent(), QIT, QIT));
        assertEquals(Completeness.UNKNOWN,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.malformed(), 12, QIT));
        assertEquals(Completeness.UNKNOWN,
                BoundedQueryCompleteness.resolveQuerySide(PlatformTotal.ok(5), 12, QIT));
    }

    @Test
    void nonIntersectUnfilteredTruncatedEmitsVocabulary() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 12, PlatformTotal.ok(6000), QIT, false, 0, false, false);
        assertEquals(Completeness.TRUNCATED, r.listedPageMatch);
        assertTrue(r.emitTruncated);
        assertEquals(6000L, r.totalUnderlyingCount);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("totalCount", 12);
        BoundedQueryCompleteness.writeNonIntersectFields(out, r);
        assertTrue(out.path("truncated").asBoolean(false));
        assertEquals(6000, out.path("totalUnderlyingCount").asInt(-1));
        assertTrue(out.path("hasMore").asBoolean(false));
    }

    @Test
    void nonIntersectCompleteOmitsHasMore() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 0, PlatformTotal.absent(), QIT, false, 0, false, false);
        ObjectNode out = MAPPER.createObjectNode();
        BoundedQueryCompleteness.writeNonIntersectFields(out, r);
        assertFalse(out.has("hasMore"));
        assertFalse(out.has("truncated"));
        assertFalse(out.has("totalUnderlyingCount"));
    }

    @Test
    void filteredTruncatedIsUnknownNotTruncatedClaim() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 12, PlatformTotal.ok(6000), QIT, true, 0, false, false);
        assertEquals(Completeness.UNKNOWN, r.listedPageMatch);
        assertFalse(r.emitTruncated);
        assertNull(r.totalUnderlyingCount);
        ObjectNode out = MAPPER.createObjectNode();
        BoundedQueryCompleteness.writeNonIntersectFields(out, r);
        assertFalse(out.has("truncated"));
        assertTrue(out.path("hasMore").asBoolean(false));
    }

    @Test
    void evaluationLossForcesUnknown() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 12, PlatformTotal.ok(12), QIT, false, 1, false, false);
        assertEquals(Completeness.UNKNOWN, r.listedPageMatch);
        assertFalse(r.emitTruncated);
        assertTrue(r.emitHasMore);
    }

    @Test
    void intersectOverallUnknownOrsIntoHasMoreSignal() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 12, PlatformTotal.ok(12), QIT, false, 1, true, false);
        assertEquals(Completeness.UNKNOWN, r.overallIntersect);
        assertTrue(BoundedQueryCompleteness.completenessUnknownForIntersect(r));
        assertFalse(r.emitTruncated);
    }

    @Test
    void intersectCompleteWhenListedCompleteAndExpandFalse() {
        Result r = BoundedQueryCompleteness.evaluate(
                ListingValidity.VALID_TABLE, 3, PlatformTotal.ok(3), QIT, false, 0, true, false);
        assertEquals(Completeness.COMPLETE, r.overallIntersect);
        assertFalse(BoundedQueryCompleteness.completenessUnknownForIntersect(r));
    }

    @Test
    void evaluateRejectsMalformedListing() {
        assertThrows(IllegalArgumentException.class, () -> BoundedQueryCompleteness.evaluate(
                ListingValidity.MISSING_OR_UNRECOGNIZED, 0, PlatformTotal.absent(), QIT, false, 0, false, false));
    }
}
