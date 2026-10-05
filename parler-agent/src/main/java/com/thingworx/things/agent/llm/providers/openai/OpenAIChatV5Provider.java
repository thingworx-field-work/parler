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

/** Built-in OpenAI Chat Completions v5 Provider ({@code docs/agent/llm-api-provider-parameters.md} §5.5). */
@ThingworxImplementedShapeDefinitions(shapes = {
        @ThingworxImplementedShapeDefinition(name = LLMAPIProviderThing.LLMAPI_PROVIDER_SHAPE_NAME) })
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = OpenAIChatV5Provider.SETTINGS_TABLE,
                description = "OpenAI Chat Completions v5 connection and model settings",
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
                                name = "maxCompletionTokens",
                                description = "Default max_completion_tokens (includes reasoning tokens on v5)",
                                baseType = "INTEGER",
                                aspects = { "defaultValue:8192" },
                                ordinal = 6),
                        @ThingworxFieldDefinition(
                                name = "reasoningEffort",
                                description = "Default reasoning_effort (minimal, low, medium, high)",
                                baseType = "STRING",
                                aspects = { "defaultValue:low" },
                                ordinal = 7) })) })
public class OpenAIChatV5Provider extends LLMAPIProviderThing {

    private static final long serialVersionUID = 1L;

    public static final String SETTINGS_TABLE = "OpenAIChatV5Settings";

    static final int CODE_DEFAULT_MAX_OUTPUT = 8192;

    static final String CODE_DEFAULT_REASONING_EFFORT = "low";

    private volatile Snapshot settings;

    @Override
    public String getApiShapeId() {
        return "openai-chat-completions-v5";
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
        if (s != null) {
            return s.model;
        }
        return settingsStr(SETTINGS_TABLE, "model");
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
        return new OpenAiChatCompletionsClient(
                s.baseUrl,
                s.apiKey,
                s.model,
                s.timeoutMs,
                true,
                s.organizationId,
                s.projectId);
    }

    private int tableMaxFromConfiguration() {
        return ProviderRequestResolution.positiveOrDefault(
                settingsInt(SETTINGS_TABLE, "maxCompletionTokens", 0), providerCodeDefaultMaxOutputTokens());
    }

    private Snapshot loadSettings() {
        String baseUrl = nonBlankOrDefault(settingsStr(SETTINGS_TABLE, "baseUrl"), "https://api.openai.com/v1");
        String apiKey = settingsStr(SETTINGS_TABLE, "apiKey");
        String model = settingsStr(SETTINGS_TABLE, "model");
        requireNonEmpty(apiKey, SETTINGS_TABLE + ".apiKey is required");
        requireNonEmpty(model, SETTINGS_TABLE + ".model is required");
        String reasoning = nonBlankOrDefault(
                settingsStr(SETTINGS_TABLE, "reasoningEffort"), CODE_DEFAULT_REASONING_EFFORT);
        return new Snapshot(
                baseUrl,
                apiKey,
                model,
                settingsStr(SETTINGS_TABLE, "organizationId"),
                settingsStr(SETTINGS_TABLE, "projectId"),
                settingsInt(SETTINGS_TABLE, "timeoutMs", 120_000),
                tableMaxFromConfiguration(),
                reasoning);
    }

    private static final class Snapshot {
        final String baseUrl;
        final String apiKey;
        final String model;
        final String organizationId;
        final String projectId;
        final int timeoutMs;
        final int maxCompletionTokens;
        final String reasoningEffort;

        Snapshot(String baseUrl, String apiKey, String model, String organizationId, String projectId, int timeoutMs,
                int maxCompletionTokens, String reasoningEffort) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.timeoutMs = timeoutMs;
            this.maxCompletionTokens = maxCompletionTokens;
            this.reasoningEffort = reasoningEffort;
        }
    }
}
