package com.thingworx.things.agent.llm.providers;

import java.util.Optional;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinitions;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.system.ContextType;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.ProviderBridgeContext;
import com.thingworx.things.agent.llm.ProviderLlmClientBridge;
import com.thingworx.things.agent.llm.ratecontrol.LLMAPIProviderRateGate;
import com.thingworx.things.agent.llm.ratecontrol.RateControlConfig;
import com.thingworx.things.agent.llm.ratecontrol.RateControlMode;
import com.thingworx.things.agent.llm.ratecontrol.RateControlTokenEstimator;
import com.thingworx.things.agent.llm.ratecontrol.RateEstimate;
import com.thingworx.things.agent.llm.ratecontrol.RateGateOwner;
import com.thingworx.things.agent.llm.ratecontrol.TokenReserveStrategy;
import com.thingworx.types.InfoTable;

/**
 * Abstract base for LLM API Provider Things. Per-template settings tables and runtime contract:
 * {@code docs/agent/llm-api-provider-parameters.md}; rate control: {@code docs/agent/rate-control.md}.
 */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = LLMAPIProviderRateGate.RATE_CONTROL_TABLE,
                description = "Provider-local token/RPM/concurrency admission control",
                isMultiRow = false,
                dataShape = @ThingworxDataShapeDefinition(fields = {
                        @ThingworxFieldDefinition(
                                name = "rateControlMode",
                                description = "disabled, observe, or enforce",
                                baseType = "STRING",
                                aspects = { "defaultValue:disabled" },
                                ordinal = 0),
                        @ThingworxFieldDefinition(
                                name = "tokensPerMinuteLimit",
                                description = "Token bucket capacity; 0 disables token checks",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 1),
                        @ThingworxFieldDefinition(
                                name = "requestsPerMinuteLimit",
                                description = "Request bucket capacity; 0 disables RPM checks",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 2),
                        @ThingworxFieldDefinition(
                                name = "maxConcurrentRequests",
                                description = "Max in-flight upstream calls; 0 disables concurrency checks",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 3),
                        @ThingworxFieldDefinition(
                                name = "maxLocalWaitMs",
                                description = "Bounded wait for capacity; 0 means fail fast",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 4),
                        @ThingworxFieldDefinition(
                                name = "tokenReserveStrategy",
                                description = "Blank uses Provider template default: input_only or input_plus_requested_output",
                                baseType = "STRING",
                                ordinal = 5),
                        @ThingworxFieldDefinition(
                                name = "estimateSafetyMultiplier",
                                description = "Multiplier on estimated tokens before admission (minimum 1.0)",
                                baseType = "NUMBER",
                                aspects = { "defaultValue:1.15" },
                                ordinal = 6),
                        @ThingworxFieldDefinition(
                                name = "maxSingleRequestTokens",
                                description = "Hard cap per reservation; 0 means tokensPerMinuteLimit when TPM enabled",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 7),
                        @ThingworxFieldDefinition(
                                name = "logAdmissions",
                                description = "Log every allow/wait decision; rejections are always logged",
                                baseType = "BOOLEAN",
                                aspects = { "defaultValue:false" },
                                ordinal = 8) })) })
public abstract class LLMAPIProviderThing extends Thing implements RateGateOwner, ProviderBridgeContext {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(LLMAPIProviderThing.class);

    public static final String LLMAPI_PROVIDER_SHAPE_NAME = "LLMAPIProviderShape";

    private volatile LlmClient boundClient;
    private volatile LLMAPIProviderRateGate rateGate;

    public abstract String getApiShapeId();

    public abstract String getEffectiveModelLabel();

    public abstract int resolveMaxOutputTokens(LlmChatRequest request);

    public abstract Optional<String> resolveReasoningEffort(LlmChatRequest request);

    /** Positive fallback when Agent {@code maxTokens <= 0} and the Provider table max-output field is unset. */
    public abstract int providerCodeDefaultMaxOutputTokens();

    /** Non-blank fallback when request and table reasoning are blank; default {@code null} (omit). */
    protected String providerCodeDefaultReasoningEffort() {
        return null;
    }

    /**
     * Default token reservation strategy when {@code tokenReserveStrategy} is blank
     * ({@code docs/agent/rate-control.md} §4).
     */
    protected String defaultTokenReserveStrategy() {
        return TokenReserveStrategy.input_plus_requested_output.name();
    }

    protected abstract LlmClient buildDelegateClient();

    public final LLMAPIProviderRateGate getRateGate() {
        LLMAPIProviderRateGate g = rateGate;
        if (g == null) {
            synchronized (this) {
                if (rateGate == null) {
                    rateGate = new LLMAPIProviderRateGate(this);
                }
                g = rateGate;
            }
        }
        return g;
    }

    public final RateControlConfig loadRateControlConfig() {
        RateControlMode mode = RateControlMode.parse(settingsStr(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "rateControlMode"));
        int tpm = Math.max(0, settingsInt(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "tokensPerMinuteLimit", 0));
        int rpm = Math.max(0, settingsInt(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "requestsPerMinuteLimit", 0));
        int concurrency = Math.max(0, settingsInt(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "maxConcurrentRequests", 0));
        int maxWait = Math.max(0, settingsInt(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "maxLocalWaitMs", 0));
        String strategyRaw = settingsStr(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "tokenReserveStrategy");
        TokenReserveStrategy strategy = TokenReserveStrategy.parse(
                strategyRaw,
                TokenReserveStrategy.parse(defaultTokenReserveStrategy(), TokenReserveStrategy.input_plus_requested_output));
        double mult = settingsDouble(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "estimateSafetyMultiplier", 1.15);
        if (mult < 1.0) {
            mult = 1.0;
        }
        int maxSingle = Math.max(0, settingsInt(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "maxSingleRequestTokens", 0));
        boolean logAdmissions = settingsBool(LLMAPIProviderRateGate.RATE_CONTROL_TABLE, "logAdmissions", false);
        return new RateControlConfig(mode, tpm, rpm, concurrency, maxWait, strategy, mult, maxSingle, logAdmissions);
    }

    public final RateEstimate estimateRateUsage(LlmChatRequest augmented) {
        return RateControlTokenEstimator.estimate(augmented, loadRateControlConfig());
    }

    @ThingworxServiceDefinition(
            name = "GetRateControlState",
            description = "Returns current in-memory rate gate state for this Provider Thing")
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "One row with mode, limits, remaining buckets, and counters")
    public InfoTable GetRateControlState() {
        return getRateGate().getStateInfoTable();
    }

    @ThingworxServiceDefinition(
            name = "ResetRateControlState",
            description = "Clears in-memory buckets and counters when no upstream call is in flight")
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "success, message, inflight")
    public InfoTable ResetRateControlState() {
        return getRateGate().resetState();
    }

    @ThingworxServiceDefinition(
            name = "TestRateControlAdmission",
            description = "Evaluates whether the gate would admit a synthetic reservation")
    @ThingworxServiceResult(name = "result", baseType = "INFOTABLE",
            description = "wouldAllow, reason, estimates, and remaining capacity")
    public InfoTable TestRateControlAdmission(
            @ThingworxServiceParameter(name = "estimatedInputTokens", baseType = "LONG") Long estimatedInputTokens,
            @ThingworxServiceParameter(name = "requestedMaxOutputTokens", baseType = "LONG") Long requestedMaxOutputTokens,
            @ThingworxServiceParameter(name = "dryRun", baseType = "BOOLEAN") Boolean dryRun) {
        long in = estimatedInputTokens != null ? Math.max(0L, estimatedInputTokens) : 0L;
        long out = requestedMaxOutputTokens != null ? Math.max(0L, requestedMaxOutputTokens) : 0L;
        boolean dry = dryRun == null || dryRun;
        return getRateGate().testAdmission(in, out, dry);
    }

    @ThingworxServiceDefinition(
            name = "TestConnection",
            description = "Probe upstream LLM connectivity for this Provider Thing. On a successful client build, "
                    + "healthCheck emits LLM_PROVIDER_TEST_CONNECTION (docs/agent/llm-api-provider.md §10.3). "
                    + "Configuration errors before a client is built return false without that log line.")
    @ThingworxServiceResult(name = "result", baseType = "BOOLEAN",
            description = "true when the vendor returns an OK probe response")
    public Boolean TestConnection() {
        try {
            return getLlmClient().healthCheck();
        } catch (Exception e) {
            LOG.warn("[{}] TestConnection failed: {}", getName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void initializeThing(ContextType contextType) throws Exception {
        super.initializeThing(contextType);
        synchronized (this) {
            boundClient = null;
            rateGate = new LLMAPIProviderRateGate(this);
            boundClient = buildBoundClient();
        }
    }

    public final LlmClient getLlmClient() {
        LlmClient c = boundClient;
        if (c == null) {
            synchronized (this) {
                if (boundClient == null) {
                    if (rateGate == null) {
                        rateGate = new LLMAPIProviderRateGate(this);
                    }
                    boundClient = buildBoundClient();
                }
                c = boundClient;
            }
        }
        return c;
    }

    private LlmClient buildBoundClient() {
        LlmClient inner = buildDelegateClient();
        return new ProviderLlmClientBridge(this, inner, getEffectiveModelLabel());
    }

    protected final String settingsStr(String tableName, String field) {
        Object v = getConfigurationData().getValue(tableName, field);
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    protected final int settingsInt(String tableName, String field, int defaultValue) {
        Object v = getConfigurationData().getValue(tableName, field);
        return v instanceof Number ? ((Number) v).intValue() : defaultValue;
    }

    protected final double settingsDouble(String tableName, String field, double defaultValue) {
        Object v = getConfigurationData().getValue(tableName, field);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultValue;
    }

    protected final boolean settingsBool(String tableName, String field, boolean defaultValue) {
        Object v = getConfigurationData().getValue(tableName, field);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v != null) {
            return Boolean.parseBoolean(String.valueOf(v));
        }
        return defaultValue;
    }

    protected static String nonBlankOrDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value.trim() : defaultValue;
    }

    protected static void requireNonEmpty(String value, String message) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(message);
        }
    }
}
