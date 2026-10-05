package com.thingworx.things.agent.transform.time;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeBuilder;
import com.thingworx.things.agent.analysis.AnalysisMethodDescriptor;
import com.thingworx.things.agent.analysis.AnalysisOperation;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.things.agent.analysis.DerivedTabularPublisher;
import com.thingworx.things.agent.analysis.HalfOpenWindow;
import com.thingworx.things.agent.cache.TypedCell;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.things.agent.cache.TypedRow;
import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/** Shared helpers for G3 cache runners that publish derived series tables. */
final class G3SeriesSupport {

    static final List<TypedColumn> BUCKET_COLUMNS = List.of(
            new TypedColumn("bucketStart", BaseTypes.DATETIME),
            new TypedColumn("bucketEnd", BaseTypes.DATETIME),
            new TypedColumn("value", BaseTypes.NUMBER),
            new TypedColumn("synthesized", BaseTypes.BOOLEAN),
            new TypedColumn("support", BaseTypes.INTEGER));

    static final List<TypedColumn> RATE_COLUMNS = List.of(
            new TypedColumn("timestamp", BaseTypes.DATETIME),
            new TypedColumn("ratePerSecond", BaseTypes.NUMBER),
            new TypedColumn("insufficient", BaseTypes.BOOLEAN));

    private G3SeriesSupport() {}

    static List<TimePoint> inWindow(List<TimePoint> points, HalfOpenWindow window) {
        List<TimePoint> out = new ArrayList<>();
        for (TimePoint p : points) {
            if (window.contains(p.instant())) {
                out.add(p);
            }
        }
        return out;
    }

    static List<TypedRow> bucketRows(List<BucketedValue> buckets) {
        List<TypedRow> rows = new ArrayList<>(buckets.size());
        long ord = 0L;
        for (BucketedValue b : buckets) {
            rows.add(new TypedRow(ord++, List.of(
                    TypedCell.ofDatetime(b.bucketStartInclusive()),
                    TypedCell.ofDatetime(b.bucketEndExclusive()),
                    b.value() == null ? TypedCell.ofNull() : TypedCell.ofNumber(b.value()),
                    TypedCell.ofBoolean(b.synthesized()),
                    TypedCell.ofNumber(b.support()))));
        }
        return rows;
    }

    static List<TypedRow> rateRows(List<RateOfChange.Sample> samples) {
        List<TypedRow> rows = new ArrayList<>(samples.size());
        long ord = 0L;
        for (RateOfChange.Sample s : samples) {
            rows.add(new TypedRow(ord++, List.of(
                    TypedCell.ofDatetime(s.point().instant()),
                    s.ratePerSecond() == null ? TypedCell.ofNull() : TypedCell.ofNumber(s.ratePerSecond()),
                    TypedCell.ofBoolean(s.insufficient()))));
        }
        return rows;
    }

    static String publish(
            List<TypedColumn> columns,
            List<TypedRow> rows,
            SourceDescriptor parent,
            String sourceCacheId,
            String routeId,
            boolean inputsFullyScanned) throws Exception {
        InfoTable table = DerivedTabularPublisher.toInfoTable(columns, rows);
        SourceDescriptor derived = DerivedArtifactLineage.forTransform(
                parent, sourceCacheId, table, routeId, inputsFullyScanned, true);
        return DerivedTabularPublisher.publish(columns, rows, derived);
    }

    static AnalysisEnvelope envelopeSuccess(
            AnalysisOperation operation,
            String routeId,
            String sourceCacheId,
            String findingId,
            String profileDigest,
            CompletenessStatus completeness,
            boolean inputsFullyScanned,
            long rowsRead,
            long rowsOut) {
        CompletenessStatus c = completeness == null ? CompletenessStatus.UNKNOWN : completeness;
        EvidenceStatus status;
        if (c != CompletenessStatus.COMPLETE || !inputsFullyScanned) {
            status = EvidenceStatus.INSUFFICIENT_EVIDENCE;
        } else if (rowsOut <= 0L) {
            status = EvidenceStatus.NO_FINDING;
        } else {
            status = EvidenceStatus.SUCCESS;
        }
        return AnalysisEnvelopeBuilder.create()
                .status(status)
                .operation(operation)
                .addSourceCacheId(sourceCacheId)
                .findingCacheId(findingId)
                .method(AnalysisMethodDescriptor.builder()
                        .id(routeId)
                        .version("1")
                        .profileDigest(profileDigest)
                        .operation(operation)
                        .build())
                .completeness(c)
                .n(rowsOut)
                .inputsFullyScanned(inputsFullyScanned)
                .rowsRead(rowsRead)
                .rowsOutput(rowsOut)
                .build();
    }
}
