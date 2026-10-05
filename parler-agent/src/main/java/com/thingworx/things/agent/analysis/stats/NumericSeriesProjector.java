package com.thingworx.things.agent.analysis.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.cache.TypedTabularStream;
import com.thingworx.things.agent.transform.time.TimePoint;
import com.thingworx.things.agent.transform.time.TypedTimeSeries;

/**
 * Projects a typed tabular stream into a {@link NumericSeries} via U4 {@link TypedTimeSeries}
 * (DIK-1). No whole-artifact heap materialization beyond the stream extract U4 already performs.
 */
public final class NumericSeriesProjector {

    private NumericSeriesProjector() {}

    public static NumericSeries projectSourceOrder(TypedTabularStream stream, String timeColumn,
            String valueColumn, String valueUnit) throws Exception {
        Objects.requireNonNull(stream, "stream");
        TypedTimeSeries.SeriesExtract extract =
                TypedTimeSeries.extractSourceOrder(stream, timeColumn, valueColumn);
        List<NumericObservation> obs = new ArrayList<>(extract.points().size());
        for (TimePoint p : extract.points()) {
            obs.add(new NumericObservation(p.instant(), p.sourceOrdinal(), p.value()));
        }
        // Missing timestamps were skipped by TypedTimeSeries; surface them as missing slots is not
        // possible without inventing ordinals — count remains in extract.missingTimestampCount and
        // is echoed by callers via metrics when needed.
        return new NumericSeries(obs, valueUnit, extract.inputsFullyScanned());
    }

    public static long missingTimestampCount(TypedTabularStream stream, String timeColumn,
            String valueColumn) throws Exception {
        return TypedTimeSeries.extractSourceOrder(stream, timeColumn, valueColumn).missingTimestampCount();
    }
}
