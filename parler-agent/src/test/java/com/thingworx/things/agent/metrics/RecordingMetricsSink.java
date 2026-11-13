package com.thingworx.things.agent.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** Fake {@link ParlerMetricsSink} that records every observation as a {@code kind|dim|dim...} line. */
public final class RecordingMetricsSink implements ParlerMetricsSink {

    public final List<String> events = new CopyOnWriteArrayList<>();
    public final List<Long> durations = new CopyOnWriteArrayList<>();
    public volatile boolean failRecording;

    private void maybeFail() {
        if (failRecording) {
            throw new IllegalStateException("recording failure");
        }
    }

    @Override
    public void recordTurn(TurnEntryPoint entryPoint, TurnOutcome outcome, long durationMs) {
        maybeFail();
        events.add("turn|" + entryPoint.wireValue() + "|" + outcome.wireValue());
        durations.add(durationMs);
    }

    @Override
    public void recordLlmAttempt(ProviderFamily family, String callKind, ProviderOutcome outcome, long durationMs) {
        maybeFail();
        events.add("llm|" + family.wireValue() + "|" + callKind + "|" + outcome.wireValue());
        durations.add(durationMs);
    }

    @Override
    public void addLlmTokens(ProviderFamily family, String callKind, TokenKind tokenKind, long tokens) {
        maybeFail();
        events.add("tokens|" + family.wireValue() + "|" + callKind + "|" + tokenKind.wireValue() + "|" + tokens);
    }

    @Override
    public void recordLlmUsageSample(ProviderFamily family, String callKind, String usageStatus) {
        maybeFail();
        events.add("usage|" + family.wireValue() + "|" + callKind + "|" + usageStatus);
    }

    @Override
    public void recordTool(ToolKind kind, ToolOutcome outcome, long durationMs) {
        maybeFail();
        events.add("tool|" + kind.wireValue() + "|" + outcome.wireValue());
        durations.add(durationMs);
    }

    public List<String> withPrefix(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).collect(Collectors.toCollection(ArrayList::new));
    }
}
