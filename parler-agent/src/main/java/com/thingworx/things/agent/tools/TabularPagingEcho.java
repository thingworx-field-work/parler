package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * E16/B5 + S3: shared paging clamp + requested/effective echo for sibling tools
 * ({@code fetch_cached_result}, list-style envelopes). Pure — no I/O.
 */
public final class TabularPagingEcho {

    private TabularPagingEcho() {}

    /** Resolved page window after clamping offset/limit against {@code totalRows}. */
    public static final class Page {
        public final int offsetRequested;
        public final int limitRequested;
        public final int offsetEffective;
        public final int limitEffective;
        public final int totalRows;
        public final int returnedRows;
        public final boolean hasMore;

        Page(int offsetRequested, int limitRequested, int offsetEffective, int limitEffective,
                int totalRows, int returnedRows, boolean hasMore) {
            this.offsetRequested = offsetRequested;
            this.limitRequested = limitRequested;
            this.offsetEffective = offsetEffective;
            this.limitEffective = limitEffective;
            this.totalRows = totalRows;
            this.returnedRows = returnedRows;
            this.hasMore = hasMore;
        }

        /** Inclusive start index into the source table. */
        public int startIndex() {
            return offsetEffective;
        }

        /** Exclusive end index into the source table. */
        public int endIndex() {
            return offsetEffective + returnedRows;
        }
    }

    /**
     * Clamp {@code offset}/{@code limit} against {@code totalRows}.
     *
     * @param offsetRequested raw offset (may be negative)
     * @param limitRequested  raw limit (may be &lt; 1)
     * @param defaultLimit    used when limitRequested &lt; 1
     * @param maxLimit        hard cap
     * @param totalRows       source row count (&gt;= 0)
     */
    public static Page resolve(int offsetRequested, int limitRequested, int defaultLimit, int maxLimit,
            int totalRows) {
        int total = Math.max(0, totalRows);
        int def = defaultLimit < 1 ? 1 : defaultLimit;
        int cap = maxLimit < 1 ? 1 : maxLimit;
        int limitEff = limitRequested < 1 ? def : limitRequested;
        limitEff = Math.min(Math.max(1, limitEff), cap);
        int offsetEff = Math.max(0, offsetRequested);
        offsetEff = Math.min(offsetEff, total);
        int end = Math.min(offsetEff + limitEff, total);
        int returned = Math.max(0, end - offsetEff);
        boolean hasMore = end < total;
        return new Page(offsetRequested, limitRequested, offsetEff, limitEff, total, returned, hasMore);
    }

    /** Write shared count / hasMore / clamp-echo fields onto a success envelope. */
    public static void putOn(ObjectNode out, Page page) {
        if (out == null || page == null) {
            return;
        }
        out.put("offset", page.offsetEffective);
        out.put("limit", page.limitEffective);
        out.put("offsetRequested", page.offsetRequested);
        out.put("limitRequested", page.limitRequested);
        out.put("offsetEffective", page.offsetEffective);
        out.put("limitEffective", page.limitEffective);
        out.put("returnedRows", page.returnedRows);
        out.put("totalRows", page.totalRows);
        out.put("hasMore", page.hasMore);
    }
}
