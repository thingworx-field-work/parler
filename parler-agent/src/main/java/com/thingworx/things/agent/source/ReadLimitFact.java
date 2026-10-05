package com.thingworx.things.agent.source;

import com.thingworx.types.InfoTable;

/**
 * What one platform read observed about its own limit. It is stated by the reader, from the table the platform
 * returned and the effective limit of that read, before anything is filtered or sampled. It is never inferred
 * later from the size of a cached table: a JSON cache, a promoted JSON table or an ordinary service result that
 * happens to have as many rows as some limit says nothing about a read limit.
 * <p>
 * The observation is weak on purpose. {@code rowCount >= effectiveLimit} can be true for a complete read whose
 * data has exactly that many rows, and it can be false for a cut read where the platform merges or expands stored
 * entries into rows. It therefore never changes a completeness status, and the absence of the reason is never
 * evidence of completeness. No read is enlarged or repeated to strengthen it.
 */
public final class ReadLimitFact {

    /** {@code completeness.reasons} value; registered in {@code CONTRACTS/TABULAR_INSIGHT.md} §5.2. */
    public static final String REASON = "READ_LIMIT_REACHED";
    /** Tool-result field, present only when the limit was reached. */
    public static final String FIELD_REACHED = "readLimitReached";
    /** Tool-result field with the sentence for the model, present only when the limit was reached. */
    public static final String FIELD_NOTE = "readLimitNote";
    /** Overlay only: labels of the series whose own read reached the limit. */
    public static final String FIELD_REACHED_SERIES = "readLimitReachedSeries";

    private static final ReadLimitFact NOT_REACHED = new ReadLimitFact(false, 0, 0);

    private final boolean reached;
    private final long returnedRows;
    private final long effectiveLimit;

    private ReadLimitFact(boolean reached, long returnedRows, long effectiveLimit) {
        this.reached = reached;
        this.returnedRows = returnedRows;
        this.effectiveLimit = effectiveLimit;
    }

    /** A path that states no read-limit fact. */
    public static ReadLimitFact none() {
        return NOT_REACHED;
    }

    /**
     * @param returned       the table exactly as the platform returned it
     * @param effectiveLimit the limit that was sent to the platform for this read; non-positive means unknown
     */
    public static ReadLimitFact observe(InfoTable returned, long effectiveLimit) {
        long rows = returned == null || returned.getRowCount() == null ? 0L : returned.getRowCount().longValue();
        if (effectiveLimit <= 0 || rows < effectiveLimit) {
            return NOT_REACHED;
        }
        return new ReadLimitFact(true, rows, effectiveLimit);
    }

    public boolean reached() {
        return reached;
    }

    public long returnedRows() {
        return returnedRows;
    }

    public long effectiveLimit() {
        return effectiveLimit;
    }

    /**
     * The overlay's note. It claims no row count: each listed series has its own, and the platform may return more
     * rows than asked. The series are named in {@link #FIELD_REACHED_SERIES}, not here, so the length is fixed.
     */
    public static String seriesNote(long perSeriesLimit) {
        return "For each series listed in " + FIELD_REACHED_SERIES + ", the platform read returned at least the "
                + "limit of " + perSeriesLimit + " rows per series. Rows beyond the limit may exist and are not "
                + "included, so those series may be incomplete; this is not proof that they are. Nothing was "
                + "re-queried. Say so when you report totals, trends or charts from them, or offer a narrower time "
                + "window.";
    }

    /** One sentence for the model: what was observed, what it does not prove, and that nothing is re-read. */
    public String note() {
        return "The platform read returned " + returnedRows + " rows, reaching or exceeding the limit of "
                + effectiveLimit + " rows for this read. Rows beyond the limit may exist and are not included, so "
                + "this result may be incomplete; this is not proof that it is. Nothing was re-queried. Say so when "
                + "you report totals, trends or charts from it, or offer a narrower time window.";
    }
}
