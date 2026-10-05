package com.thingworx.things.agent.llm.providers.openai;

import java.util.Optional;

import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxImplementedShapeDefinitions;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.OpenAiChatCompletionsClient;
import com.thingworx.things.agent.llm.ProviderRequestResolution;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;

/** Built-in OpenAI Chat Completions v4 Provider ({@code docs/agent/llm-api-provider-parameters.md} §5.4). */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = OpenAIChatV4Provider.SETTINGS_TABLE,
                description = "OpenAI Chat Completions v4 connection and model settings",
                isMultiRow = false,
                dataShape = @ThingworxDataShapeDefinition(fields = {
                        @ThingworxFieldDefinition(
                                name = "baseUrl",
                                description = "OpenAI API root with /v1",
                                baseType = "STRING",
                                aspects = { "defaultValue:https://api.openai.com/v1" },
                                ordinal = 0),
                        @ThingworxFieldDefinition(name = "apiKey", description = "OpenAI API key", baseType = "PASSWORD", ordinal = 1),
                        @ThingworxFieldDefinition(name = "model", description = "OpenAI model id", baseType = "STRING", ordinal = 2),
                        @ThingworxFieldDefinition(
                                name = "organizationId",
                                description = "Optional OpenAI-Organization header; omit when blank",
                                baseType = "STRING",
                                ordinal = 3),
                        @ThingworxFieldDefinition(
                                name = "projectId",
                                description = "Optional OpenAI-Project header; omit when blank",
                                baseType = "STRING",
                                ordinal = 4),
                        @ThingworxFieldDefinition(
                                name = "timeoutMs",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:120000" },
                                ordinal = 5),
                        @ThingworxFieldDefinition(
                                name = "maxTokens",
                                description = "Default max_tokens when Agent maxTokens is <= 0",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:4096" },
                                ordinal = 6) })) })
public class OpenAIChatV4Provider extends LLMAPIProviderThing {

    private static final long serialVersionUID = 1L;

    public static final String SETTINGS_TABLE = "OpenAIChatV4Settings";

    static final int CODE_DEFAULT_MAX_OUTPUT = 4096;

    private volatile Snapshot settings;

    @Override
    public String getApiShapeId() {
        return "openai-chat-completions-v4";
    }

    @Override
    public int providerCodeDefaultMaxOutputTokens() {
        return CODE_DEFAULT_MAX_OUTPUT;
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
        return new OpenAiChatCompletionsClient(
                s.baseUrl, s.apiKey, s.model, s.timeoutMs, false, s.organizationId, s.projectId);
    }

    private int tableMaxFromConfiguration() {
        return ProviderRequestResolution.positiveOrDefault(
                settingsInt(SETTINGS_TABLE, "maxTokens", 0), providerCodeDefaultMaxOutputTokens());
    }

    private Snapshot loadSettings() {
        String baseUrl = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "baseUrl"), "https://api.openai.com/v1");
        String apiKey = settingsStr(SETTINGS_TABLE, "apiKey");
        String model = settingsStr(SETTINGS_TABLE, "model");
        requireNonEmpty(apiKey, SETTINGS_TABLE + ".apiKey is required");
        requireNonEmpty(model, SETTINGS_TABLE + ".model is required");
        return new Snapshot(
                baseUrl,
                apiKey,
                model,
                settingsStr(SETTINGS_TABLE, "organizationId"),
                settingsStr(SETTINGS_TABLE, "projectId"),
                settingsInt(SETTINGS_TABLE, "timeoutMs", 120_000),
                tableMaxFromConfiguration());
    }

    private static final class Snapshot {
        final String baseUrl;
        final String apiKey;
        final String model;
        final String organizationId;
        final String projectId;
        final int timeoutMs;
        final int maxTokens;

        Snapshot(String baseUrl, String apiKey, String model, String organizationId, String projectId, int timeoutMs,
                int maxTokens) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.timeoutMs = timeoutMs;
            this.maxTokens = maxTokens;
        }
    }
}
