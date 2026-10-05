package com.thingworx.things.agent.cache;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.BaseTypes;

/**
 * TQJ-1 typed projected row stream over a TABULAR {@link ArtifactCache} payload. Parses with a
 * Jackson streaming parser over {@link ArtifactReader} chunks — does not materialize an
 * {@link com.thingworx.types.InfoTable}. Supports projection and early-stop.
 */
public final class TypedTabularStream implements AutoCloseable {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final ArtifactReader reader;
    private final ArtifactReaderInputStream input;
    private final JsonParser parser;
    private final List<TypedColumn> schema;
    private final long maxRowsToScan;
    private final SourceDescriptor sourceDescriptor;
    private long nextOrdinal;
    private long rowsRead;
    private boolean exhausted;
    private boolean stoppedEarly;
    private boolean closed;

    private TypedTabularStream(ArtifactReader reader, ArtifactReaderInputStream input, JsonParser parser,
            List<TypedColumn> schema, long maxRowsToScan, SourceDescriptor sourceDescriptor) {
        this.reader = reader;
        this.input = input;
        this.parser = parser;
        this.schema = List.copyOf(schema);
        this.maxRowsToScan = maxRowsToScan;
        this.sourceDescriptor = sourceDescriptor;
    }

    /**
     * Open a projected stream for {@code cacheId} in the current conversation scope.
     *
     * @param projectedColumns column names to keep (order preserved); empty/null = all non-PASSWORD
     *        columns in source order
     * @param maxRowsToScan stop after this many source rows ({@code <= 0} means unbounded under
     *        budget only)
     */
    public static TypedTabularStream open(String cacheId, List<String> projectedColumns, long maxRowsToScan)
            throws Exception {
        if (cacheId == null || cacheId.isBlank()) {
            throw new IllegalArgumentException("cacheId required");
        }
        String conv = AgentToolContext.getConversationId();
        ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conv, cacheId);
        if (ref == null) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.CACHE_MISS, "cache miss: " + cacheId);
        }
        ArtifactCache cache = TabularArtifactHub.resolveCache();
        ArtifactAccessContext access = TabularArtifactHub.accessContext(conv);
        BudgetVector budget = TabularArtifactHub.currentBudget();
        ArtifactReader reader = cache.open(ref, access, budget.toArtifactIoLimits());
        try {
            ArtifactReaderInputStream input = new ArtifactReaderInputStream(reader,
                    Math.min(8192, ArtifactIoLimits.MAX_U1A_INTERNAL_BUFFER_BYTES));
            JsonParser parser = JSON_FACTORY.createParser(input);
            List<TypedColumn> fullSchema = readSchemaAndPositionAtRows(parser);
            // CM-2: descriptor before projection so a projection miss can carry schema and roles.
            SourceDescriptor desc = TabularArtifactHub.lookupDescriptorForConversation(conv, cacheId);
            List<TypedColumn> projected = projectSchema(fullSchema, projectedColumns, desc);
            return new TypedTabularStream(reader, input, parser, projected, maxRowsToScan, desc);
        } catch (Exception e) {
            try {
                reader.close();
            } catch (Exception ignored) {
                // best-effort
            }
            throw e;
        }
    }

    public List<TypedColumn> schema() {
        return schema;
    }

    public SourceDescriptor sourceDescriptor() {
        return sourceDescriptor;
    }

    public long rowsRead() {
        return rowsRead;
    }

    public boolean exhausted() {
        return exhausted;
    }

    /** True when the caller-imposed {@code maxRowsToScan} stopped the scan before natural EOF. */
    public boolean stoppedEarly() {
        return stoppedEarly;
    }

    /**
     * Whether every source row was observed. False when early-stopped; otherwise true after natural
     * EOF (including empty tables). Exact {@code maxRowsToScan == source row count} is a full scan.
     */
    public boolean inputsFullyScanned() {
        return exhausted && !stoppedEarly;
    }

    /** Opaque payload bytes pulled from the artifact reader so far (for early-stop evidence). */
    public long bytesReadFromArtifact() {
        return input.bytesReadFromArtifact();
    }

    public TypedBatch readBatch(int maxBatchRows) throws Exception {
        if (closed) {
            throw new IllegalStateException("stream closed");
        }
        if (maxBatchRows <= 0) {
            throw new IllegalArgumentException("maxBatchRows must be positive");
        }
        if (exhausted) {
            return new TypedBatch(List.of());
        }
        List<TypedRow> batch = new ArrayList<>(Math.min(maxBatchRows, 64));
        while (batch.size() < maxBatchRows) {
            if (maxRowsToScan > 0L && rowsRead >= maxRowsToScan) {
                resolveCapBoundaryWithoutDrainingSuffix();
                break;
            }
            TypedRow row = readNextRow();
            if (row == null) {
                exhausted = true;
                stoppedEarly = false;
                break;
            }
            batch.add(row);
            rowsRead++;
        }
        return new TypedBatch(batch);
    }

    /**
     * Probe one token past the cap: {@code END_ARRAY} means the source ended exactly at the cap
     * (full scan); {@code START_OBJECT} means unread rows remain (early stop). Does not drain the
     * unread suffix — {@link #close()} releases the U1A reader without forcing a full read.
     */
    private void resolveCapBoundaryWithoutDrainingSuffix() throws IOException {
        exhausted = true;
        JsonToken next = parser.nextToken();
        if (next == null || next == JsonToken.END_ARRAY) {
            stoppedEarly = false;
            return;
        }
        if (next == JsonToken.START_OBJECT || next == JsonToken.START_ARRAY) {
            stoppedEarly = true;
            // Leave the unread suffix unparsed; close() releases the reader without drain.
            return;
        }
        // Unexpected trailing token after rows — treat as not fully scanned.
        stoppedEarly = true;
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            parser.close();
        } finally {
            reader.close();
        }
    }

    private TypedRow readNextRow() throws IOException {
        JsonToken t = parser.nextToken();
        if (t == null || t == JsonToken.END_ARRAY) {
            return null;
        }
        if (t != JsonToken.START_OBJECT) {
            throw new IllegalStateException("TABULAR row must be an object");
        }
        Map<String, TypedCell> byName = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            byName.put(field, readCell(parser));
        }
        List<TypedCell> cells = new ArrayList<>(schema.size());
        for (TypedColumn col : schema) {
            TypedCell cell = byName.get(col.name());
            cells.add(cell == null ? TypedCell.ofNull() : coerce(cell, col.baseType()));
        }
        return new TypedRow(nextOrdinal++, cells);
    }

    private static List<TypedColumn> readSchemaAndPositionAtRows(JsonParser parser) throws IOException {
        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_OBJECT) {
            throw new IllegalStateException("TABULAR payload must be a JSON object");
        }
        List<TypedColumn> cols = null;
        while ((t = parser.nextToken()) != null && t != JsonToken.END_OBJECT) {
            if (t != JsonToken.FIELD_NAME) {
                continue;
            }
            String name = parser.currentName();
            t = parser.nextToken();
            if ("fields".equals(name)) {
                cols = parseFieldsArray(parser, t);
            } else if ("rows".equals(name)) {
                if (t != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("rows must be an array");
                }
                if (cols == null) {
                    throw new IllegalStateException("TABULAR fields must precede rows");
                }
                return cols;
            } else if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
        }
        throw new IllegalStateException("TABULAR payload missing rows array");
    }

    private static List<TypedColumn> parseFieldsArray(JsonParser parser, JsonToken start) throws IOException {
        if (start != JsonToken.START_ARRAY) {
            throw new IllegalStateException("fields must be an array");
        }
        List<TypedColumn> cols = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("field entry must be an object");
            }
            String colName = null;
            BaseTypes bt = BaseTypes.STRING;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                parser.nextToken();
                if ("name".equals(f)) {
                    colName = parser.getValueAsString();
                } else if ("baseType".equals(f)) {
                    bt = parseBaseType(parser.getValueAsString());
                } else if (parser.currentToken() == JsonToken.START_OBJECT
                        || parser.currentToken() == JsonToken.START_ARRAY) {
                    parser.skipChildren();
                }
            }
            if (colName != null && !colName.isBlank() && bt != BaseTypes.PASSWORD) {
                cols.add(new TypedColumn(colName.trim(), bt));
            }
        }
        return cols;
    }

    private static List<TypedColumn> projectSchema(List<TypedColumn> full, List<String> projected,
            SourceDescriptor descriptor) {
        if (projected == null || projected.isEmpty()) {
            return full;
        }
        Map<String, TypedColumn> byName = new LinkedHashMap<>();
        for (TypedColumn c : full) {
            byName.put(c.name(), c);
        }
        List<TypedColumn> out = new ArrayList<>();
        for (int i = 0; i < projected.size(); i++) {
            String name = projected.get(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            TypedColumn col = byName.get(name.trim());
            if (col == null) {
                throw new UnknownProjectedColumnException(name, i, full, descriptor);
            }
            out.add(col);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("projection resolved to zero columns");
        }
        return out;
    }

    private static BaseTypes parseBaseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return BaseTypes.STRING;
        }
        try {
            return BaseTypes.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BaseTypes.STRING;
        }
    }

    private static TypedCell readCell(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == null || t == JsonToken.VALUE_NULL) {
            return TypedCell.ofNull();
        }
        if (t == JsonToken.VALUE_STRING) {
            return TypedCell.ofString(parser.getValueAsString());
        }
        if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
            if (parser.getNumberType() == JsonParser.NumberType.LONG
                    || parser.getNumberType() == JsonParser.NumberType.INT) {
                return TypedCell.ofNumber(parser.getLongValue());
            }
            return TypedCell.ofNumber(parser.getDoubleValue());
        }
        if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
            return TypedCell.ofBoolean(parser.getBooleanValue());
        }
        if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
            parser.skipChildren();
            return TypedCell.ofNull();
        }
        return TypedCell.ofString(parser.getValueAsString());
    }

    private static TypedCell coerce(TypedCell cell, BaseTypes baseType) {
        if (cell == null || cell.isNull() || baseType == null) {
            return cell == null ? TypedCell.ofNull() : cell;
        }
        if (baseType == BaseTypes.DATETIME) {
            if (cell.kind() == TypedCell.Kind.DATETIME) {
                return cell;
            }
            if (cell.kind() == TypedCell.Kind.NUMBER) {
                return TypedCell.ofDatetime(Instant.ofEpochMilli((long) cell.numberValue()));
            }
            if (cell.kind() == TypedCell.Kind.STRING) {
                Instant parsed = tryParseInstant(cell.stringValue());
                return parsed == null ? TypedCell.ofNull() : TypedCell.ofDatetime(parsed);
            }
        }
        return cell;
    }

    private static Instant tryParseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
