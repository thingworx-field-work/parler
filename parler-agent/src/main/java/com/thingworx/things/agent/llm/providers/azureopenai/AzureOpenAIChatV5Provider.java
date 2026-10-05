package com.thingworx.things.agent.llm.providers.azureopenai;

import java.util.Optional;

import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinitions;
import com.thingworx.things.agent.llm.AzureOpenAILlmClient;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.ProviderRequestResolution;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;

/** Built-in Azure OpenAI Chat Completions v5 Provider ({@code docs/agent/llm-api-provider-parameters.md} §5.2). */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = AzureOpenAIChatV5Provider.SETTINGS_TABLE,
                description = "Azure OpenAI Chat Completions v5 settings",
                isMultiRow = false,
                dataShape = @ThingworxDataShapeDefinition(fields = {
                        @ThingworxFieldDefinition(
                                name = "endpoint",
                                description = "Azure resource endpoint",
                                baseType = "STRING",
                                ordinal = 0),
                        @ThingworxFieldDefinition(name = "apiKey", description = "Azure OpenAI API key", baseType = "PASSWORD", ordinal = 1),
                        @ThingworxFieldDefinition(
                                name = "deployment",
                                description = "Azure deployment name",
                                baseType = "STRING",
                                ordinal = 2),
                        @ThingworxFieldDefinition(
                                name = "apiVersion",
                                description = "Azure api-version query param",
                                baseType = "STRING",
                                aspects = { "defaultValue:2025-01-01-preview" },
                                ordinal = 3),
                        @ThingworxFieldDefinition(
                                name = "timeoutMs",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:120000" },
                                ordinal = 4),
                        @ThingworxFieldDefinition(
                                name = "maxCompletionTokens",
                                description = "Default max_completion_tokens (includes reasoning tokens on v5)",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:8192" },
                                ordinal = 5),
                        @ThingworxFieldDefinition(
                                name = "reasoningEffort",
                                description = "Default reasoning_effort",
                                baseType = "STRING",
                                aspects = { "defaultValue:low" },
                                ordinal = 6) })) })
public class AzureOpenAIChatV5Provider extends LLMAPIProviderThing {

    private static final long serialVersionUID = 1L;

    public static final String SETTINGS_TABLE = "AzureOpenAIChatV5Settings";

    static final int CODE_DEFAULT_MAX_OUTPUT = 8192;

    static final String CODE_DEFAULT_REASONING_EFFORT = "low";

    private volatile Snapshot settings;

    @Override
    public String getApiShapeId() {
        return "azure-openai-chat-completions-v5";
    }

    @Override
    public int providerCodeDefaultMaxOutputTokens() {
        return CODE_DEFAULT_MAX_OUTPUT;
    }

    @Override
    protected String providerCodeDefaultReasoningEffort() {
        return CODE_DEFAULT_REASONING_EFFORT;
    }

    @Override
    public String getEffectiveModelLabel() {
        Snapshot s = settings;
        return s != null ? s.deployment : settingsStr(SETTINGS_TABLE, "deployment");
    }

    @Override
    public int resolveMaxOutputTokens(LlmChatRequest request) {
        Snapshot s = settings;
        int tableMax = s != null ? s.maxCompletionTokens : tableMaxFromConfiguration();
        return ProviderRequestResolution.resolveMaxOutput(
                request.getRequestedMaxOutputTokens(), tableMax, providerCodeDefaultMaxOutputTokens());
    }

    @Override
    public Optional<String> resolveReasoningEffort(LlmChatRequest request) {
        Snapshot s = settings;
        String table = s != null ? s.reasoningEffort : settingsStr(SETTINGS_TABLE, "reasoningEffort");
        return Optional.ofNullable(ProviderRequestResolution.resolveReasoning(
                request.getReasoningEffort(), table, providerCodeDefaultReasoningEffort()));
    }

    @Override
    protected LlmClient buildDelegateClient() {
        Snapshot s = loadSettings();
        this.settings = s;
        return new AzureOpenAILlmClient(s.endpoint, s.apiKey, s.deployment, s.apiVersion, s.timeoutMs, true);
    }

    private int tableMaxFromConfiguration() {
        return ProviderRequestResolution.positiveOrDefault(
                settingsInt(SETTINGS_TABLE, "maxCompletionTokens", 0), providerCodeDefaultMaxOutputTokens());
    }

    private Snapshot loadSettings() {
        String endpoint = settingsStr(SETTINGS_TABLE, "endpoint");
        String apiKey = settingsStr(SETTINGS_TABLE, "apiKey");
        String deployment = settingsStr(SETTINGS_TABLE, "deployment");
        String apiVersion = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "apiVersion"), "2025-01-01-preview");
        requireNonEmpty(endpoint, SETTINGS_TABLE + ".endpoint is required");
        requireNonEmpty(apiKey, SETTINGS_TABLE + ".apiKey is required");
        requireNonEmpty(deployment, SETTINGS_TABLE + ".deployment is required");
        String reasoning = nonBlankOrDefault(
                settingsStr(SETTINGS_TABLE, "reasoningEffort"), CODE_DEFAULT_REASONING_EFFORT);
        return new Snapshot(
                endpoint,
                apiKey,
                deployment,
                apiVersion,
                settingsInt(SETTINGS_TABLE, "timeoutMs", 120_000),
                tableMaxFromConfiguration(),
                reasoning);
    }

    private static final class Snapshot {
        final String endpoint;
        final String apiKey;
        final String deployment;
        final String apiVersion;
        final int timeoutMs;
        final int maxCompletionTokens;
        final String reasoningEffort;

        Snapshot(String endpoint, String apiKey, String deployment, String apiVersion, int timeoutMs,
                int maxCompletionTokens, String reasoningEffort) {
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.deployment = deployment;
            this.apiVersion = apiVersion;
            this.timeoutMs = timeoutMs;
            this.maxCompletionTokens = maxCompletionTokens;
            this.reasoningEffort = reasoningEffort;
        }
    }
}
