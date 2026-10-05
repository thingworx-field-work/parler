package com.thingworx.things.agent.taskstate;

import com.thingworx.things.agent.source.SourceDescriptor;

/**
 * Server-authored evidence row for v1a task state (not a wire contract).
 */
public final class AgentTaskEvidence {

    private final String evidenceId;
    private final int sequence;
    private final String correlationKey;
    private final String toolCallId;
    private final String tool;

    private String status;
    private String targetType;
    private String targetName;
    private String operation;
    private String resultKind;
    private int rowCount = -1;
    private int totalCount = -1;
    private boolean totalCountInferred;
    private String cacheId;
    private boolean sampleOnly;
    /**
     * Optional U2 source completeness carried from the producing adapter / descriptor. Null means
     * unset (aggregator must not invent {@code COMPLETE}).
     */
    private SourceDescriptor.CompletenessStatus completenessStatus;
    /** Null when {@code status=ok} without error code. */
    private TaskStateErrorCode errorCode;
    private boolean protectedOmissions;
    private final long whenEpochMillis;

    public AgentTaskEvidence(String evidenceId, int sequence, String correlationKey, String toolCallId, String tool,
            String status, long whenEpochMillis) {
        this.evidenceId = evidenceId;
        this.sequence = sequence;
        this.correlationKey = correlationKey;
        this.toolCallId = toolCallId;
        this.tool = tool;
        this.status = status;
        this.whenEpochMillis = whenEpochMillis;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public int getSequence() {
        return sequence;
    }

    public String getCorrelationKey() {
        return correlationKey;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getTool() {
        return tool;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getResultKind() {
        return resultKind;
    }

    public void setResultKind(String resultKind) {
        this.resultKind = resultKind;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public boolean isTotalCountInferred() {
        return totalCountInferred;
    }

    public void setTotalCountInferred(boolean totalCountInferred) {
        this.totalCountInferred = totalCountInferred;
    }

    public String getCacheId() {
        return cacheId;
    }

    public void setCacheId(String cacheId) {
        this.cacheId = cacheId;
    }

    public boolean isSampleOnly() {
        return sampleOnly;
    }

    public void setSampleOnly(boolean sampleOnly) {
        this.sampleOnly = sampleOnly;
    }

    /** May be {@code null} when the adapter did not prove a completeness value. */
    public SourceDescriptor.CompletenessStatus getCompletenessStatus() {
        return completenessStatus;
    }

    public void setCompletenessStatus(SourceDescriptor.CompletenessStatus completenessStatus) {
        this.completenessStatus = completenessStatus;
    }

    public TaskStateErrorCode getErrorCodeEnum() {
        return errorCode;
    }

    public void setErrorCode(TaskStateErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public boolean isProtectedOmissions() {
        return protectedOmissions;
    }

    public void setProtectedOmissions(boolean protectedOmissions) {
        this.protectedOmissions = protectedOmissions;
    }

    public long getWhenEpochMillis() {
        return whenEpochMillis;
    }
}
