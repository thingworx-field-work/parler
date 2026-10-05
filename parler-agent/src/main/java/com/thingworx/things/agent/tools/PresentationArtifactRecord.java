package com.thingworx.things.agent.tools;

/**
 * One presentation-ready tabular artifact for Answer Presentation Phase (see
 * {@code docs/agent/multi-chart-and-thrashing-safeguards.md}). {@code cacheId} is the sole artifact key.
 */
public final class PresentationArtifactRecord {

    private final String cacheId;
    private final String sourceTool;
    private final String resultKind;
    private final boolean complete;
    private final int rowCount;
    private final String columnsJson;

    public PresentationArtifactRecord(String cacheId, String sourceTool, String resultKind, boolean complete,
            int rowCount, String columnsJson) {
        this.cacheId = cacheId != null ? cacheId : "";
        this.sourceTool = sourceTool != null ? sourceTool : "";
        this.resultKind = resultKind != null ? resultKind : "";
        this.complete = complete;
        this.rowCount = rowCount;
        this.columnsJson = columnsJson != null ? columnsJson : "[]";
    }

    public String getCacheId() {
        return cacheId;
    }

    public String getSourceTool() {
        return sourceTool;
    }

    public String getResultKind() {
        return resultKind;
    }

    public boolean isComplete() {
        return complete;
    }

    public int getRowCount() {
        return rowCount;
    }

    public String getColumnsJson() {
        return columnsJson;
    }
}
