package com.thingworx.things.agent.join;

import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;

/**
 * G8 join outcome. On failure, {@link #outputRows()} is empty and {@link #mayPublish()} is false —
 * callers MUST NOT publish a derived handle (B5).
 */
public final class ExactJoinResult {

    private final ExactJoinReason reason;
    private final List<TypedColumn> outputColumns;
    private final List<TypedRow> outputRows;
    private final long leftRowsRead;
    private final long rightRowsRead;
    private final long matchedRows;
    private final long unmatchedLeftRows;
    private final long buildRows;
    private final List<String> representativeDuplicateKeys;
    private final String detail;

    private ExactJoinResult(Builder b) {
        this.reason = Objects.requireNonNull(b.reason, "reason");
        this.outputColumns = List.copyOf(b.outputColumns == null ? List.of() : b.outputColumns);
        this.outputRows = List.copyOf(b.outputRows == null ? List.of() : b.outputRows);
        this.leftRowsRead = b.leftRowsRead;
        this.rightRowsRead = b.rightRowsRead;
        this.matchedRows = b.matchedRows;
        this.unmatchedLeftRows = b.unmatchedLeftRows;
        this.buildRows = b.buildRows;
        this.representativeDuplicateKeys = List.copyOf(
                b.representativeDuplicateKeys == null ? List.of() : b.representativeDuplicateKeys);
        this.detail = b.detail;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ExactJoinReason reason() {
        return reason;
    }

    public boolean success() {
        return reason == ExactJoinReason.SUCCESS;
    }

    /** False on any failure — no derived artifact handle may be published. */
    public boolean mayPublish() {
        return success();
    }

    public List<TypedColumn> outputColumns() {
        return outputColumns;
    }

    public List<TypedRow> outputRows() {
        return outputRows;
    }

    public long leftRowsRead() {
        return leftRowsRead;
    }

    public long rightRowsRead() {
        return rightRowsRead;
    }

    public long matchedRows() {
        return matchedRows;
    }

    public long unmatchedLeftRows() {
        return unmatchedLeftRows;
    }

    public long buildRows() {
        return buildRows;
    }

    public List<String> representativeDuplicateKeys() {
        return representativeDuplicateKeys;
    }

    public String detail() {
        return detail;
    }

    public static final class Builder {
        private ExactJoinReason reason;
        private List<TypedColumn> outputColumns = List.of();
        private List<TypedRow> outputRows = List.of();
        private long leftRowsRead;
        private long rightRowsRead;
        private long matchedRows;
        private long unmatchedLeftRows;
        private long buildRows;
        private List<String> representativeDuplicateKeys = List.of();
        private String detail;

        public Builder reason(ExactJoinReason v) {
            this.reason = v;
            return this;
        }

        public Builder outputColumns(List<TypedColumn> v) {
            this.outputColumns = v;
            return this;
        }

        public Builder outputRows(List<TypedRow> v) {
            this.outputRows = v;
            return this;
        }

        public Builder leftRowsRead(long v) {
            this.leftRowsRead = v;
            return this;
        }

        public Builder rightRowsRead(long v) {
            this.rightRowsRead = v;
            return this;
        }

        public Builder matchedRows(long v) {
            this.matchedRows = v;
            return this;
        }

        public Builder unmatchedLeftRows(long v) {
            this.unmatchedLeftRows = v;
            return this;
        }

        public Builder buildRows(long v) {
            this.buildRows = v;
            return this;
        }

        public Builder representativeDuplicateKeys(List<String> v) {
            this.representativeDuplicateKeys = v;
            return this;
        }

        public Builder detail(String v) {
            this.detail = v;
            return this;
        }

        public ExactJoinResult build() {
            ExactJoinResult r = new ExactJoinResult(this);
            if (!r.success() && !r.outputRows().isEmpty()) {
                throw new IllegalStateException("failed join must not carry output rows");
            }
            return r;
        }
    }
}
