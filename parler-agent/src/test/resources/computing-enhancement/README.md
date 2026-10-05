# computing-enhancement reference data

Hand-checkable reference cases for the computing-enhancement operators
(`docs/agent/nearterm/computing-enhancement.md`). Tests load these files from the classpath; nothing here
needs a network, a platform or an external library.

| File | Operator | Consumer |
| --- | --- | --- |
| `counter-delta-reference.json` | CF-05 `counter_delta_v1` | `transform/time/CounterDeltaTest` |
| `rolling-stats-reference.json` | CF-52 `rolling_stats_v1` | `transform/time/RollingStatsTest` |
| `time-weighted-reference.json` | CF-01 `time_weighted_v1` | `transform/time/TimeWeightedIntegralTest` |
| `calendar-bucket-reference.json` | CF-03 `calendar_bucket_v1` | `transform/time/CalendarBucketLabelerTest` |

In `counter-delta-reference.json` every reading is `[secondsFromStart, value]`. `expected` lists one
`[classification, delta]` pair per output segment in order, with `null` where a segment carries no increment.

In `rolling-stats-reference.json` every record is `[secondsFromStart, value-or-null]`. `expected` lists one
`[value-or-null, valueStatus, support, records, warmedUp]` tuple per record in stable (time, source ordinal) order.

In `time-weighted-reference.json` every reading is `[millisFromStart, value]` and `window` is the half-open
`[startMillis, endMillis)` from the same origin. `segments` lists `[startMillis, endMillis, coverage,
integral-or-null]` per output segment in order; long cases give `segmentCount` and the totals instead.
`limited: true` stands for a source that reached a read limit or is partial.

In `calendar-bucket-reference.json` every case gives an IANA `zone`, a `calendarBucket` (`day` or `hour`) and an
`instant`, then the expected half-open bucket `[start, end)`, its `label` and its real length in `seconds`.
