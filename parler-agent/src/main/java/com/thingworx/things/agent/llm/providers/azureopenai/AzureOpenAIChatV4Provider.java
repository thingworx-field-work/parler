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

/** Built-in Azure OpenAI Chat Completions v4 Provider ({@code docs/agent/llm-api-provider-parameters.md} §5.1). */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = AzureOpenAIChatV4Provider.SETTINGS_TABLE,
                description = "Azure OpenAI Chat Completions v4 settings",
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
                                name = "maxTokens",
                                description = "Default max_tokens when Agent maxTokens is <= 0",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:4096" },
                                ordinal = 5) })) })
public class AzureOpenAIChatV4Provider extends LLMAPIProviderThing {

    private static final long serialVersionUID = 1L;

    public static final String SETTINGS_TABLE = "AzureOpenAIChatV4Settings";

    static final int CODE_DEFAULT_MAX_OUTPUT = 4096;

    private volatile Snapshot settings;

    @Override
    public String getApiShapeId() {
        return "azure-openai-chat-completions-v4";
    }

    @Override
    public int providerCodeDefaultMaxOutputTokens() {
        return CODE_DEFAULT_MAX_OUTPUT;
    }

    @Override
    public String getEffectiveModelLabel() {
        Snapshot s = settings;
        return s != null ? s.deployment : settingsStr(SETTINGS_TABLE, "deployment");
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
        return new AzureOpenAILlmClient(s.endpoint, s.apiKey, s.deployment, s.apiVersion, s.timeoutMs, false);
    }

    private int tableMaxFromConfiguration() {
        return ProviderRequestResolution.positiveOrDefault(
                settingsInt(SETTINGS_TABLE, "maxTokens", 0), providerCodeDefaultMaxOutputTokens());
    }

    private Snapshot loadSettings() {
        String endpoint = settingsStr(SETTINGS_TABLE, "endpoint");
        String apiKey = settingsStr(SETTINGS_TABLE, "apiKey");
        String deployment = settingsStr(SETTINGS_TABLE, "deployment");
        String apiVersion = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "apiVersion"), "2025-01-01-preview");
        requireNonEmpty(endpoint, SETTINGS_TABLE + ".endpoint is required");
        requireNonEmpty(apiKey, SETTINGS_TABLE + ".apiKey is required");
        requireNonEmpty(deployment, SETTINGS_TABLE + ".deployment is required");
        return new Snapshot(
                endpoint,
                apiKey,
                deployment,
                apiVersion,
                settingsInt(SETTINGS_TABLE, "timeoutMs", 120_000),
                tableMaxFromConfiguration());
    }

    private static final class Snapshot {
        final String endpoint;
        final String apiKey;
        final String deployment;
        final String apiVersion;
        final int timeoutMs;
        final int maxTokens;

        Snapshot(String endpoint, String apiKey, String deployment, String apiVersion, int timeoutMs, int maxTokens) {
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.deployment = deployment;
            this.apiVersion = apiVersion;
            this.timeoutMs = timeoutMs;
            this.maxTokens = maxTokens;
        }
    }
}
