package com.thingworx.things.agent.compaction;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Effective replay compaction policy ({@code docs/agent/context-compaction.md} §6, §16 Slice B/F): Tier A / Tier 0 /
 * Tier B run unless the JVM diagnostic property disables them or a test override is set. The retired
 * {@value #STALE_AGENT_SETTING_ENABLE_LLM_REPLAY_COMPACTION} AgentSetting is ignored (Slice F).
 */
public final class LlmReplayCompactionGate {

    /**
     * Retired {@code AgentSettings} field name (Slice F). When still present in a deployed ConfigurationTable,
     * {@link com.thingworx.things.agent.AgentBaseThing} logs one startup {@code INFO} and ignores the value.
     */
    public static final String STALE_AGENT_SETTING_ENABLE_LLM_REPLAY_COMPACTION = "enableLlmReplayCompaction";

    /**
     * When {@code true}, Tier A / Tier 0 / Tier B replay compaction is suppressed for the process (diagnostic only).
     *
     * @see ContextBudgetPlanner — {@code LLM_CONTEXT_PLAN} emits {@code unsafeDisable=1} while active
     */
    public static final String UNSAFE_DIAGNOSTICS_PROPERTY = "com.thingworx.parler.llmReplayCompaction.disableUnsafe";

    private static final AtomicReference<Boolean> TEST_EFFECTIVE_OVERRIDE = new AtomicReference<>(null);
    private static final AtomicBoolean TEST_FORCE_UNSAFE_DISABLE = new AtomicBoolean(false);

    private LlmReplayCompactionGate() {}

    /**
     * @return {@code true} when JVM property {@value #UNSAFE_DIAGNOSTICS_PROPERTY} is {@code true}, or when a unit
     *         test forces unsafe mode via {@link #setUnsafeDiagnosticsDisableForcedForTest(boolean)}.
     */
    public static boolean isUnsafeDiagnosticsDisable() {
        if (TEST_FORCE_UNSAFE_DISABLE.get()) {
            return true;
        }
        return Boolean.parseBoolean(System.getProperty(UNSAFE_DIAGNOSTICS_PROPERTY, "false"));
    }

    /**
     * @return {@code true} when replay compaction (Tier A / 0 / B) should run for this JVM instant.
     */
    public static boolean isReplayCompactionEffective() {
        Boolean o = TEST_EFFECTIVE_OVERRIDE.get();
        if (o != null) {
            return o.booleanValue();
        }
        return !isUnsafeDiagnosticsDisable();
    }

    /**
     * Test-only: force {@link #isReplayCompactionEffective()} result; {@code null} clears.
     * <p>
     * Do not toggle the JVM {@code com.thingworx.parler.llmReplayCompaction.disableUnsafe} property in shared JVM test
     * runs; use {@link #setUnsafeDiagnosticsDisableForcedForTest(boolean)} instead.
     */
    public static void setReplayCompactionEffectiveForTest(Boolean enabledOrNull) {
        TEST_EFFECTIVE_OVERRIDE.set(enabledOrNull);
    }

    /** Test-only: make {@link #isUnsafeDiagnosticsDisable()} return true without setting the JVM property. */
    public static void setUnsafeDiagnosticsDisableForcedForTest(boolean forced) {
        TEST_FORCE_UNSAFE_DISABLE.set(forced);
    }

    /** Clears all test hooks (call from {@code @AfterEach} in tests that use the gate). */
    public static void clearAllTestHooks() {
        TEST_EFFECTIVE_OVERRIDE.set(null);
        TEST_FORCE_UNSAFE_DISABLE.set(false);
    }
}
