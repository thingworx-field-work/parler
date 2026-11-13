package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fake-registrar coverage for registration, allowlists, buckets, gauge collection, and failure latching
 * (design: Verification check 3). No platform settings or OpenTelemetry are initialized.
 */
class ParlerMetricsRegistrationTest {

    @AfterEach
    void tearDown() {
        ParlerMetrics.resetForTest();
    }

    /** Captures registrations; {@code failAt} (1-based) throws on that registration call. */
    static class FakeRegistrar implements MetricsRegistrar {
        final Map<String, Set<String>> allowlists = new LinkedHashMap<>();
        final Map<String, List<Double>> buckets = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();
        final List<String> recorded = new CopyOnWriteArrayList<>();
        Supplier<List<GaugePoint>> gauge;
        int failAt = -1;
        boolean ready = true;
        int calls;
        boolean failRecording;
        /** Simulates a platform that keeps the gauge in its JVM-wide registry and then fails the registration. */
        boolean keepGaugeThenFail;

        @Override
        public boolean platformReady() {
            return ready;
        }

        private void register(String name, String type, Set<String> keys) {
            calls++;
            if (calls == failAt) {
                throw new IllegalStateException("registration failed at " + name);
            }
            types.put(name, type);
            allowlists.put(name, keys);
        }

        @Override
        public Counter counter(String name, String description, String unit, Set<String> attributeKeys) {
            register(name, "counter", attributeKeys);
            return (value, attributes) -> {
                if (failRecording) {
                    throw new IllegalStateException("platform recording failure");
                }
                recorded.add(name + "+" + value + attributes);
            };
        }

        @Override
        public Histogram histogram(String name, String description, String unit, List<Double> bucketList,
                Set<String> attributeKeys) {
            register(name, "histogram", attributeKeys);
            buckets.put(name, new ArrayList<>(bucketList));
            return (value, attributes) -> recorded.add(name + "=" + value + attributes);
        }

        @Override
        public void asyncGauge(String name, String description, String unit, Set<String> attributeKeys,
                Supplier<List<GaugePoint>> callback) {
            if (keepGaugeThenFail) {
                gauge = callback;
                calls++;
                throw new IllegalStateException("gauge validation failed after platform registration");
            }
            register(name, "asyncGauge", attributeKeys);
            gauge = callback;
        }
    }

    @Test
    void registersEveryInstrumentWithAllowlistsAndBuckets() {
        FakeRegistrar fake = new FakeRegistrar();
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT).close();

        assertEquals(Map.of(
                "parler_turns_total", "counter",
                "parler_turn_duration_ms", "histogram",
                "parler_turns_in_flight", "asyncGauge",
                "parler_llm_attempts_total", "counter",
                "parler_llm_attempt_duration_ms", "histogram",
                "parler_llm_tokens_total", "counter",
                "parler_llm_usage_samples_total", "counter",
                "parler_tool_executions_total", "counter",
                "parler_tool_duration_ms", "histogram"), fake.types);

        assertEquals(Set.of("entry_point", "outcome"), fake.allowlists.get("parler_turns_total"));
        assertEquals(Set.of("entry_point", "outcome"), fake.allowlists.get("parler_turn_duration_ms"));
        assertEquals(Set.of("entry_point"), fake.allowlists.get("parler_turns_in_flight"));
        assertEquals(Set.of("provider_family", "call_kind", "outcome"),
                fake.allowlists.get("parler_llm_attempts_total"));
        assertEquals(Set.of("provider_family", "call_kind", "outcome"),
                fake.allowlists.get("parler_llm_attempt_duration_ms"));
        assertEquals(Set.of("provider_family", "call_kind", "token_kind"),
                fake.allowlists.get("parler_llm_tokens_total"));
        assertEquals(Set.of("provider_family", "call_kind", "usage_status"),
                fake.allowlists.get("parler_llm_usage_samples_total"));
        assertEquals(Set.of("tool_kind", "outcome"), fake.allowlists.get("parler_tool_executions_total"));
        assertEquals(Set.of("tool_kind", "outcome"), fake.allowlists.get("parler_tool_duration_ms"));

        List<Double> turnBuckets = List.of(10d, 50d, 100d, 250d, 500d, 1_000d, 2_500d, 5_000d, 10_000d, 30_000d,
                60_000d);
        assertEquals(turnBuckets, fake.buckets.get("parler_turn_duration_ms"));
        assertEquals(turnBuckets, fake.buckets.get("parler_llm_attempt_duration_ms"));
        assertEquals(List.of(1d, 5d, 10d, 25d, 50d, 100d, 250d, 500d, 1_000d, 2_500d, 5_000d, 10_000d, 30_000d),
                fake.buckets.get("parler_tool_duration_ms"));

        assertTrue(fake.recorded.contains("parler_turns_total+1{entry_point=chat, outcome=error}"));
    }

    @Test
    void registersOncePerRuntime() {
        FakeRegistrar fake = new FakeRegistrar();
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT).close();
        ParlerMetrics.startTool(ToolKind.BUILT_IN).close();
        assertEquals(9, fake.calls);
    }

    @Test
    void gaugeEmitsEveryEntryPointIncludingZero() {
        FakeRegistrar fake = new FakeRegistrar();
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerTurnScope open = ParlerMetrics.startTurn(TurnEntryPoint.STREAM);
        try {
            assertNotNull(fake.gauge);
            Map<String, Long> byEntry = new LinkedHashMap<>();
            for (MetricsRegistrar.GaugePoint p : fake.gauge.get()) {
                byEntry.put(p.attributes().get("entry_point"), p.value());
            }
            assertEquals(Set.of("chat", "chat_async", "stream", "approval_resume"), byEntry.keySet());
            long baseline = byEntry.get("chat");
            assertEquals(baseline, byEntry.get("chat_async"));
            assertTrue(byEntry.get("stream") >= 1);
        } finally {
            open.close();
        }
        for (MetricsRegistrar.GaugePoint p : fake.gauge.get()) {
            assertEquals(0L, p.value(), "in-flight after close for " + p.attributes());
        }
    }

    @Test
    void partialRegistrationFailureLatchesNoopAndPreservesOutcomes() {
        FakeRegistrar fake = new FakeRegistrar();
        fake.failAt = 4;
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerTurnScope scope = ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC);
        scope.outcome(TurnOutcome.SUCCESS);
        scope.close();
        ParlerMetrics.startTool(ToolKind.CUSTOM).close();
        assertEquals(4, fake.calls, "no retry after the latch");
        assertTrue(fake.recorded.isEmpty(), "partially registered instruments are not used");
        assertEquals(0, TurnInFlight.SHARED.current(TurnEntryPoint.CHAT_ASYNC));
    }

    @Test
    void earlyPartialFailureRegistersNoGaugeAndTurnsStillComplete() {
        FakeRegistrar fake = new FakeRegistrar();
        fake.failAt = 4;
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerTurnScope open = ParlerMetrics.startTurn(TurnEntryPoint.STREAM);
        try {
            assertEquals(null, fake.gauge, "the in-flight gauge is registered last");
            assertEquals(1, TurnInFlight.SHARED.current(TurnEntryPoint.STREAM));
        } finally {
            open.outcome(TurnOutcome.SUCCESS);
            open.close();
        }
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT).close();
        assertEquals(4, fake.calls, "failed initialization stays latched");
        assertTrue(fake.recorded.isEmpty());
        assertEquals(0, TurnInFlight.SHARED.current(TurnEntryPoint.STREAM));
    }

    @Test
    void gaugeKeptByPlatformAfterFailedRegistrationObservesNoTurns() {
        FakeRegistrar fake = new FakeRegistrar();
        fake.keepGaugeThenFail = true;
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerTurnScope open = ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC);
        try {
            assertNotNull(fake.gauge, "platform kept the gauge callback");
            assertEquals(1, TurnInFlight.SHARED.current(TurnEntryPoint.CHAT_ASYNC));
            assertTrue(fake.gauge.get().isEmpty(), "gauge must not export turns after registration failure");
        } finally {
            open.outcome(TurnOutcome.AWAITING_APPROVAL);
            open.close();
        }
        int callsAfterFailure = fake.calls;
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC).close();
        ParlerMetrics.startTool(ToolKind.BUILT_IN).close();
        assertEquals(callsAfterFailure, fake.calls, "failed initialization stays latched");
        assertTrue(fake.gauge.get().isEmpty());
        assertTrue(fake.recorded.isEmpty(), "registered counters are not used after the latch");
        assertEquals(0, TurnInFlight.SHARED.current(TurnEntryPoint.CHAT_ASYNC));
    }

    @Test
    void platformNotReadyRecordsNothingAndRetriesLater() {
        FakeRegistrar fake = new FakeRegistrar();
        fake.ready = false;
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT).close();
        assertEquals(0, fake.calls);
        fake.ready = true;
        ParlerMetrics.startTurn(TurnEntryPoint.CHAT).close();
        assertEquals(9, fake.calls);
        assertEquals(1, fake.recorded.stream().filter(r -> r.startsWith("parler_turns_total+")).count());
    }

    @Test
    void existingOrDisabledInstrumentsAllowRegistrationToContinue() {
        // The platform returns the existing same-type instrument (or a disabled no-op) as a normal handle; a
        // registrar that hands back shared no-op handles must still yield a working sink.
        FakeRegistrar fake = new FakeRegistrar() {
            @Override
            public Counter counter(String name, String description, String unit, Set<String> attributeKeys) {
                super.counter(name, description, unit, attributeKeys);
                return (value, attributes) -> { };
            }
        };
        ParlerMetrics.installRegistrarForTest(fake);
        ParlerMetrics.startTurn(TurnEntryPoint.STREAM).close();
        assertEquals(9, fake.calls);
        assertTrue(fake.recorded.stream().anyMatch(r -> r.startsWith("parler_turn_duration_ms=")));
    }

    @Test
    void recordingFailureIsIsolatedAndLoggedOnce() {
        FakeRegistrar fake = new FakeRegistrar();
        fake.failRecording = true;
        ParlerMetrics.installRegistrarForTest(fake);
        assertFalse(ParlerMetrics.recordingFailureLoggedForTest());
        ParlerTurnScope scope = ParlerMetrics.startTurn(TurnEntryPoint.APPROVAL_RESUME);
        scope.outcome(TurnOutcome.AWAITING_APPROVAL);
        scope.close();
        ParlerMetrics.startTool(ToolKind.WORKFLOW).close();
        assertTrue(ParlerMetrics.recordingFailureLoggedForTest());
        assertEquals(0, TurnInFlight.SHARED.current(TurnEntryPoint.APPROVAL_RESUME));
    }
}
