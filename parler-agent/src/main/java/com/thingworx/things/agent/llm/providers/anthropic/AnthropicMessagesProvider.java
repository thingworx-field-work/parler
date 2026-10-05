package com.thingworx.things.agent.llm.providers.anthropic;

import java.util.Optional;

import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinitions;
import com.thingworx.things.agent.llm.AnthropicSamplingParametersMode;
import com.thingworx.things.agent.llm.AnthropicMessagesLlmClient;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.ProviderRequestResolution;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;

/** Built-in Anthropic Messages API Provider ({@code docs/agent/llm-api-provider-parameters.md} §5.7). */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = AnthropicMessagesProvider.SETTINGS_TABLE,
                description = "Anthropic Messages API settings",
                isMultiRow = false,
                dataShape = @ThingworxDataShapeDefinition(fields = {
                        @ThingworxFieldDefinition(
                                name = "baseUrl",
                                description = "Anthropic API host without path",
                                baseType = "STRING",
                                aspects = { "defaultValue:https://api.anthropic.com" },
                                ordinal = 0),
                        @ThingworxFieldDefinition(name = "apiKey", description = "Anthropic API key", baseType = "PASSWORD", ordinal = 1),
                        @ThingworxFieldDefinition(name = "model", description = "Anthropic model id", baseType = "STRING", ordinal = 2),
                        @ThingworxFieldDefinition(
                                name = "anthropicVersion",
                                description = "anthropic-version request header",
                                baseType = "STRING",
                                aspects = { "defaultValue:2023-06-01" },
                                ordinal = 3),
                        @ThingworxFieldDefinition(
                                name = "timeoutMs",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:120000" },
                                ordinal = 4),
                        @ThingworxFieldDefinition(
                                name = "maxTokens",
                                description = "Default max_tokens when Agent maxTokens is <= 0",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:8192" },
                                ordinal = 5),
                        @ThingworxFieldDefinition(
                                name = "thinkingBudgetTokens",
                                description = "Extended thinking budget; 0 omits thinking. When positive, must be >= 1024 and < maxTokens.",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:0" },
                                ordinal = 6),
                        @ThingworxFieldDefinition(
                                name = "samplingParametersMode",
                                description = "legacy sends temperature when thinking is off; omit suppresses Anthropic sampling parameters.",
                                baseType = "STRING",
                                aspects = { "defaultValue:legacy" },
                                ordinal = 7) })) })
public class AnthropicMessagesProvider extends LLMAPIProviderThing {

    private static final long serialVersionUID = 1L;

    public static final String SETTINGS_TABLE = "AnthropicMessagesSettings";

    static final int CODE_DEFAULT_MAX_OUTPUT = 8192;

    private volatile Snapshot settings;

    @Override
    public String getApiShapeId() {
        return "anthropic-messages-v1";
    }

    @Override
    public int providerCodeDefaultMaxOutputTokens() {
        return CODE_DEFAULT_MAX_OUTPUT;
    }

    @Override
    protected String defaultTokenReserveStrategy() {
        return "input_only";
    }

    @Override
    public String getEffectiveModelLabel() {
        Snapshot s = settings;
        return s != null ? s.model : settingsStr(SETTINGS_TABLE, "model");
    }

    @Override
    public int resolveMaxOutputTokens(LlmChatRequest request) {
        Snapshot s = settings;
        int tableMax = s != null ? s.maxTokens : tableMaxFromConfiguration();
        return ProviderRequestResolution.resolveMaxOutput(
                request.getRequestedMaxOutputTokens(), tableMax, providerCodeDefaultMaxOutputTokens());
    }

    @Override
    public Optional<String> resolveReasoningEffort(LlmChatRequest request) {
        return Optional.ofNullable(ProviderRequestResolution.resolveReasoning(
                request.getReasoningEffort(), null, providerCodeDefaultReasoningEffort()));
    }

    @Override
    protected LlmClient buildDelegateClient() {
        Snapshot s = loadSettings();
        this.settings = s;
        return new AnthropicMessagesLlmClient(
                s.baseUrl, s.apiKey, s.model, s.anthropicVersion, s.timeoutMs, s.thinkingBudgetTokens,
                s.samplingParametersMode);
    }

    private int tableMaxFromConfiguration() {
        return ProviderRequestResolution.positiveOrDefault(
                settingsInt(SETTINGS_TABLE, "maxTokens", 0), providerCodeDefaultMaxOutputTokens());
    }

    private Snapshot loadSettings() {
        String baseUrl = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "baseUrl"), "https://api.anthropic.com");
        String apiKey = settingsStr(SETTINGS_TABLE, "apiKey");
        String model = settingsStr(SETTINGS_TABLE, "model");
        String anthropicVersion = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "anthropicVersion"), "2023-06-01");
        requireNonEmpty(apiKey, SETTINGS_TABLE + ".apiKey is required");
        requireNonEmpty(model, SETTINGS_TABLE + ".model is required");
        AnthropicSamplingParametersMode samplingMode;
        try {
            samplingMode = AnthropicSamplingParametersMode.parse(
                    settingsStr(SETTINGS_TABLE, "samplingParametersMode"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(SETTINGS_TABLE + "." + e.getMessage(), e);
        }
        int maxTokens = tableMaxFromConfiguration();
        int thinkingBudget = settingsInt(SETTINGS_TABLE, "thinkingBudgetTokens", 0);
        if (thinkingBudget < 0) {
            thinkingBudget = 0;
        }
        if (thinkingBudget > 0) {
            if (thinkingBudget < 1024) {
                throw new IllegalStateException(SETTINGS_TABLE
                        + ".thinkingBudgetTokens must be >= 1024 when positive (got " + thinkingBudget + ")");
            }
            if (thinkingBudget >= maxTokens) {
                throw new IllegalStateException(SETTINGS_TABLE
                        + ".thinkingBudgetTokens must be less than maxTokens when positive (got thinking="
                        + thinkingBudget + ", maxTokens=" + maxTokens + ")");
            }
        }
        return new Snapshot(
                baseUrl,
                apiKey,
                model,
                anthropicVersion,
                settingsInt(SETTINGS_TABLE, "timeoutMs", 120_000),
                maxTokens,
                thinkingBudget,
                samplingMode);
    }

    private static final class Snapshot {
        final String baseUrl;
        final String apiKey;
        final String model;
        final String anthropicVersion;
        final int timeoutMs;
        final int maxTokens;
        final int thinkingBudgetTokens;
        final AnthropicSamplingParametersMode samplingParametersMode;

        Snapshot(String baseUrl, String apiKey, String model, String anthropicVersion, int timeoutMs, int maxTokens,
                int thinkingBudgetTokens, AnthropicSamplingParametersMode samplingParametersMode) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.anthropicVersion = anthropicVersion;
            this.timeoutMs = timeoutMs;
            this.maxTokens = maxTokens;
            this.thinkingBudgetTokens = thinkingBudgetTokens;
            this.samplingParametersMode = samplingParametersMode;
        }
    }
}
