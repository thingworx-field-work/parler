package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per user-turn state for {@code last_invoke} resolution on {@code build_chart_from_tabular_result}.
 * {@code source:last_invoke} resolves to the <b>latest</b> qualifying tabular tool success this turn
 * (last-wins on {@link #lastCacheId} / {@link #lastInlineRows}); {@link #qualifyingTabularSuccessCount} counts
 * how many such successes were recorded (telemetry / diagnostics).
 * <p>
 * Reset at the start of each {@link com.thingworx.things.agent.AgentLoop#run} while Parler/chat context is active.
 */
public final class TabularChartRoundState {

    private int qualifyingTabularSuccessCount;
    /** Latest qualifying result: cache id when result was large or from {@code fetch_cached_result}. */
    private String lastCacheId;
    /** Latest qualifying inline rows (small INFOTABLE-style JSON), when no cache id. */
    private JsonNode lastInlineRows;
    private JsonNode lastColumnsMeta;
    /**
     * Last tabular success satisfied chart-rescue completeness rules (no truncation flags, or tabulate complete).
     */
    private boolean lastChartRescueDataCompleteEnough;
    /**
     * Chart ids ({@code c1}, {@code c2}, …) are scoped to the user <b>request</b>, not to one
     * {@code AgentLoop} run. Keeping the counter here means the HITL {@link Snapshot} carries it across an
     * approval pause, so a chart built after approval does not reuse an id already sent to the client.
     */
    private int parlerChartSeq;
    /** C3b-1: the request's one chart group, or null; carried across an approval pause like the chart counter. */
    private ChartGroupState chartGroup;
    private int parlerGroupSeq;

    /** Exact request-local state held by an existing HITL pending record, independent of LLM history compaction. */
    public static final class Snapshot {
        private final int qualifyingCount;
        private final String cacheId;
        private final JsonNode inlineRows;
        private final JsonNode columnsMeta;
        private final boolean completeEnough;
        private final int chartSeq;
        private final ChartGroupState group;
        private final int groupSeq;

        private Snapshot(TabularChartRoundState state) {
            qualifyingCount = state.qualifyingTabularSuccessCount;
            cacheId = state.lastCacheId;
            inlineRows = copy(state.lastInlineRows);
            columnsMeta = copy(state.lastColumnsMeta);
            completeEnough = state.lastChartRescueDataCompleteEnough;
            chartSeq = state.parlerChartSeq;
            group = state.chartGroup != null ? state.chartGroup.copy() : null;
            groupSeq = state.parlerGroupSeq;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    /** Restores a private copy; neither later execution nor repeated continuation can mutate the pending snapshot. */
    public void restore(Snapshot snapshot) {
        reset();
        if (snapshot != null) {
            qualifyingTabularSuccessCount = snapshot.qualifyingCount;
            lastCacheId = snapshot.cacheId;
            lastInlineRows = copy(snapshot.inlineRows);
            lastColumnsMeta = copy(snapshot.columnsMeta);
            lastChartRescueDataCompleteEnough = snapshot.completeEnough;
            parlerChartSeq = snapshot.chartSeq;
            chartGroup = snapshot.group != null ? snapshot.group.copy() : null;
            parlerGroupSeq = snapshot.groupSeq;
        }
    }

    private static JsonNode copy(JsonNode node) {
        return node != null ? node.deepCopy() : null;
    }

    public int getQualifyingTabularSuccessCount() {
        return qualifyingTabularSuccessCount;
    }

    public String getLastCacheId() {
        return lastCacheId;
    }

    public JsonNode getLastInlineRows() {
        return lastInlineRows;
    }

    public JsonNode getLastColumnsMeta() {
        return lastColumnsMeta;
    }

    public boolean isLastChartRescueDataCompleteEnough() {
        return lastChartRescueDataCompleteEnough;
    }

    /** Whether {@code last_invoke} can resolve to a non-empty tabular target. */
    public boolean hasChartableLastInvokeTarget() {
        if (lastCacheId != null && !lastCacheId.isEmpty()) {
            return true;
        }
        return lastInlineRows != null && lastInlineRows.isArray() && lastInlineRows.size() > 0;
    }

    public void reset() {
        qualifyingTabularSuccessCount = 0;
        lastCacheId = null;
        lastInlineRows = null;
        lastColumnsMeta = null;
        lastChartRescueDataCompleteEnough = false;
        parlerChartSeq = 0;
        chartGroup = null;
        parlerGroupSeq = 0;
    }

    public ChartGroupState getChartGroup() {
        return chartGroup;
    }

    public void setChartGroup(ChartGroupState group) {
        this.chartGroup = group;
    }

    /** Next group id for this request ({@code g1}, …); same lifecycle as {@link #nextChartId()}. */
    public String nextGroupId() {
        return "g" + (++parlerGroupSeq);
    }

    /** Next chart id for this request; continues across an approval pause via {@link Snapshot}. */
    public String nextChartId() {
        return "c" + (++parlerChartSeq);
    }

    /**
     * Records a qualifying success whose empty result must not leave {@code last_invoke} pointing at an earlier
     * table (chart-enhancement design §7.4, the D1 distribution operators' {@code _EMPTY} kinds): the count
     * still increments, but both chartable targets are cleared so {@link #hasChartableLastInvokeTarget()} is
     * false. Only the targets change; the chart sequence and the success count are not reset.
     */
    public void recordQualifyingEmptyClearingTarget(JsonNode columnsMeta) {
        qualifyingTabularSuccessCount++;
        lastChartRescueDataCompleteEnough = false;
        lastCacheId = null;
        lastInlineRows = null;
        lastColumnsMeta = columnsMeta != null && columnsMeta.isArray() ? columnsMeta : null;
    }

    /**
     * Records one qualifying tabular tool success. {@link #qualifyingTabularSuccessCount} increments each time;
     * {@link #lastCacheId} / {@link #lastInlineRows} / {@link #lastColumnsMeta} update with last-wins semantics so
     * {@code last_invoke} always targets the most recent qualifying table.
     */
    public void recordQualifyingTabular(boolean hasCacheId, String cacheId, JsonNode inlineRows, JsonNode columnsMeta,
            boolean chartRescueDataCompleteEnough) {
        qualifyingTabularSuccessCount++;
        lastChartRescueDataCompleteEnough = chartRescueDataCompleteEnough;
        if (hasCacheId && cacheId != null && !cacheId.isEmpty()) {
            lastCacheId = cacheId;
            lastInlineRows = null;
            lastColumnsMeta = columnsMeta != null && columnsMeta.isArray() ? columnsMeta : null;
        } else if (inlineRows != null && inlineRows.isArray() && inlineRows.size() > 0) {
            lastCacheId = null;
            lastInlineRows = inlineRows;
            lastColumnsMeta = columnsMeta != null && columnsMeta.isArray() ? columnsMeta : null;
        }
    }
}
