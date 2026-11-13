package com.thingworx.things.agent.metrics;

/**
 * Recording surface behind {@link ParlerMetrics}. Dimension values are the bounded wire values of the metrics enums;
 * implementations never receive free-form identifiers.
 */
public interface ParlerMetricsSink {

    void recordTurn(TurnEntryPoint entryPoint, TurnOutcome outcome, long durationMs);

    void recordLlmAttempt(ProviderFamily family, String callKind, ProviderOutcome outcome, long durationMs);

    void addLlmTokens(ProviderFamily family, String callKind, TokenKind tokenKind, long tokens);

    void recordLlmUsageSample(ProviderFamily family, String callKind, String usageStatus);

    void recordTool(ToolKind kind, ToolOutcome outcome, long durationMs);

    /** Sink used when registration failed or before the platform is available. */
    ParlerMetricsSink NOOP = new ParlerMetricsSink() {
        @Override
        public void recordTurn(TurnEntryPoint entryPoint, TurnOutcome outcome, long durationMs) {
        }

        @Override
        public void recordLlmAttempt(ProviderFamily family, String callKind, ProviderOutcome outcome,
                long durationMs) {
        }

        @Override
        public void addLlmTokens(ProviderFamily family, String callKind, TokenKind tokenKind, long tokens) {
        }

        @Override
        public void recordLlmUsageSample(ProviderFamily family, String callKind, String usageStatus) {
        }

        @Override
        public void recordTool(ToolKind kind, ToolOutcome outcome, long durationMs) {
        }
    };
}
