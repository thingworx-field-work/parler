package com.thingworx.things.agent.tools;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * U1B E1: named completeness states for {@code query_entities_by_taxonomy}
 * (query-side candidate scan, listed-page match, overall intersect). Pure — no I/O.
 *
 * @see docs/agent/nearterm/cache-correctness-foundation.md §B7.1
 */
public final class BoundedQueryCompleteness {

    public enum ListingValidity {
        VALID_TABLE,
        MISSING_OR_UNRECOGNIZED
    }

    public enum TotalFieldPresence {
        ABSENT,
        PRESENT
    }

    public enum TotalParseStatus {
        OK,
        MALFORMED
    }

    public enum Completeness {
        COMPLETE,
        TRUNCATED,
        UNKNOWN
    }

    /** Platform-total carrier (not a bare {@link Long}). */
    public static final class PlatformTotal {
        public final TotalFieldPresence presence;
        public final TotalParseStatus parseStatus;
        public final Long parsedTotal;

        private PlatformTotal(TotalFieldPresence presence, TotalParseStatus parseStatus, Long parsedTotal) {
            this.presence = presence;
            this.parseStatus = parseStatus;
            this.parsedTotal = parsedTotal;
        }

        public static PlatformTotal absent() {
            return new PlatformTotal(TotalFieldPresence.ABSENT, null, null);
        }

        public static PlatformTotal malformed() {
            return new PlatformTotal(TotalFieldPresence.PRESENT, TotalParseStatus.MALFORMED, null);
        }

        public static PlatformTotal ok(long parsedTotal) {
            return new PlatformTotal(TotalFieldPresence.PRESENT, TotalParseStatus.OK, parsedTotal);
        }

        public boolean usable(int listingRows) {
            return presence == TotalFieldPresence.PRESENT && parseStatus == TotalParseStatus.OK
                    && parsedTotal != null && parsedTotal >= listingRows;
        }

        public boolean inconsistent(int listingRows) {
            if (presence == TotalFieldPresence.PRESENT && parseStatus == TotalParseStatus.MALFORMED) {
                return true;
            }
            return presence == TotalFieldPresence.PRESENT && parseStatus == TotalParseStatus.OK
                    && parsedTotal != null && parsedTotal < listingRows;
        }
    }

    public static final class Result {
        public final Completeness querySide;
        public final Completeness listedPageMatch;
        public final Completeness overallIntersect;
        public final boolean emitTruncated;
        public final Long totalUnderlyingCount;
        public final boolean emitHasMore;
        public final boolean omitHasMore;

        private Result(Completeness querySide, Completeness listedPageMatch, Completeness overallIntersect,
                boolean emitTruncated, Long totalUnderlyingCount, boolean emitHasMore, boolean omitHasMore) {
            this.querySide = querySide;
            this.listedPageMatch = listedPageMatch;
            this.overallIntersect = overallIntersect;
            this.emitTruncated = emitTruncated;
            this.totalUnderlyingCount = totalUnderlyingCount;
            this.emitHasMore = emitHasMore;
            this.omitHasMore = omitHasMore;
        }
    }

    private BoundedQueryCompleteness() {}

    /**
     * Numeric OK rule: finite, mathematically integral, non-negative, within {@code [0, Long.MAX_VALUE]}.
     * Validation is exact ({@link BigDecimal}/{@link BigInteger}) — never a lossy {@code double} range
     * check (which would accept {@code 2^63} as {@code Long.MAX_VALUE}).
     * {@code 10.0} is OK; {@code 10.5}, NaN, negatives, and magnitudes above {@code Long.MAX_VALUE}
     * are MALFORMED.
     */
    public static PlatformTotal platformTotalFromNumber(Number n) {
        if (n == null) {
            return PlatformTotal.absent();
        }
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            long v = n.longValue();
            if (v < 0L) {
                return PlatformTotal.malformed();
            }
            return PlatformTotal.ok(v);
        }
        if (n instanceof BigInteger) {
            return platformTotalFromBigInteger((BigInteger) n);
        }
        if (n instanceof BigDecimal) {
            return platformTotalFromBigDecimal((BigDecimal) n);
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                return PlatformTotal.malformed();
            }
            // BigDecimal.valueOf uses Double.toString — preserves the floating value without
            // comparing against the rounded (double) Long.MAX_VALUE sentinel.
            return platformTotalFromBigDecimal(BigDecimal.valueOf(d));
        }
        return platformTotalFromExactDecimalText(n.toString());
    }

    /**
     * Exact decimal/integral text parse for platform totals (string cells, JSON text). Avoids
     * {@link Double#valueOf(String)} which cannot represent values above {@code Long.MAX_VALUE}
     * distinctly from {@code Long.MAX_VALUE}.
     */
    public static PlatformTotal platformTotalFromExactDecimalText(String raw) {
        if (raw == null) {
            return PlatformTotal.absent();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return PlatformTotal.malformed();
        }
        try {
            return platformTotalFromBigDecimal(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return PlatformTotal.malformed();
        }
    }

    private static PlatformTotal platformTotalFromBigDecimal(BigDecimal bd) {
        if (bd == null) {
            return PlatformTotal.absent();
        }
        BigDecimal normalized = bd.stripTrailingZeros();
        // scale > 0 ⇒ non-integral fractional part remains (e.g. 10.5).
        if (normalized.scale() > 0) {
            return PlatformTotal.malformed();
        }
        try {
            return platformTotalFromBigInteger(normalized.toBigIntegerExact());
        } catch (ArithmeticException e) {
            return PlatformTotal.malformed();
        }
    }

    private static PlatformTotal platformTotalFromBigInteger(BigInteger bi) {
        if (bi == null) {
            return PlatformTotal.absent();
        }
        if (bi.signum() < 0) {
            return PlatformTotal.malformed();
        }
        if (bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return PlatformTotal.malformed();
        }
        return PlatformTotal.ok(bi.longValue());
    }

    public static Completeness resolveQuerySide(PlatformTotal total, int listingRows, int queryLimit) {
        Objects.requireNonNull(total, "total");
        if (total.inconsistent(listingRows)) {
            return Completeness.UNKNOWN;
        }
        if (total.usable(listingRows)) {
            if (total.parsedTotal == listingRows) {
                return Completeness.COMPLETE;
            }
            return Completeness.TRUNCATED; // parsedTotal > listingRows
        }
        // missing
        if (listingRows < queryLimit) {
            return Completeness.COMPLETE;
        }
        return Completeness.UNKNOWN;
    }

    /**
     * @param listingValidity must be {@link ListingValidity#VALID_TABLE}
     * @param filtered true when non-empty {@code LookupProperties} was applied
     */
    public static Result evaluate(ListingValidity listingValidity, int listingRows, PlatformTotal total,
            int queryLimit, boolean filtered, int evaluationLossCount, boolean intersectActive,
            boolean expandHasMore) {
        Objects.requireNonNull(listingValidity, "listingValidity");
        Objects.requireNonNull(total, "total");
        if (listingValidity != ListingValidity.VALID_TABLE) {
            throw new IllegalArgumentException("evaluate requires VALID_TABLE listing");
        }
        Completeness querySide = resolveQuerySide(total, listingRows, queryLimit);
        Completeness listedPage;
        if (evaluationLossCount > 0 || total.inconsistent(listingRows)) {
            listedPage = Completeness.UNKNOWN;
        } else if (!filtered) {
            listedPage = querySide;
        } else {
            listedPage = querySide == Completeness.COMPLETE ? Completeness.COMPLETE : Completeness.UNKNOWN;
        }

        Completeness overallIntersect;
        if (!intersectActive) {
            overallIntersect = listedPage;
        } else if (listedPage == Completeness.COMPLETE && !expandHasMore) {
            overallIntersect = Completeness.COMPLETE;
        } else {
            overallIntersect = Completeness.UNKNOWN;
        }

        if (intersectActive) {
            // Intersect path never emits non-intersect truncated vocabulary.
            return new Result(querySide, listedPage, overallIntersect, false, null, false, false);
        }

        // Non-intersect public emission (§B7.1 table)
        if (listedPage == Completeness.COMPLETE) {
            return new Result(querySide, listedPage, overallIntersect, false, null, false, true);
        }
        if (listedPage == Completeness.TRUNCATED) {
            return new Result(querySide, listedPage, overallIntersect, true, total.parsedTotal, true, false);
        }
        // unknown
        return new Result(querySide, listedPage, overallIntersect, false, null, true, false);
    }

    /** Non-intersect public vocabulary on a success object. */
    public static void writeNonIntersectFields(ObjectNode out, Result r) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(r, "r");
        if (r.emitTruncated) {
            out.put("truncated", true);
            if (r.totalUnderlyingCount != null) {
                out.put("totalUnderlyingCount", r.totalUnderlyingCount);
            }
            out.put("hasMore", true);
            return;
        }
        if (r.emitHasMore && !r.omitHasMore) {
            out.put("hasMore", true);
        }
        // complete: omit truncated / totalUnderlyingCount / hasMore
    }

    /** Whether intersect {@code hasMore} must OR in completeness-unknown. */
    public static boolean completenessUnknownForIntersect(Result r) {
        return r.overallIntersect == Completeness.UNKNOWN;
    }

    /** Continuity signal for intersect-only {@code queryHasMore} (not complete ⇒ may continue). */
    public static boolean queryHasMoreSignal(Completeness querySide) {
        return querySide != Completeness.COMPLETE;
    }
}
