package com.thingworx.things.agent.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.usage.LlmRecordedHttpChat;

/**
 * Anthropic <strong>Messages</strong> API ({@code POST .../v1/messages}).
 */
public class AnthropicMessagesLlmClient implements LlmClient {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AnthropicMessagesLlmClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String messagesUrl;
    private final String apiKey;
    private final String defaultModel;
    private final String anthropicVersion;
    private final int timeoutMs;
    private final int thinkingBudgetTokens;
    private final AnthropicSamplingParametersMode samplingParametersMode;

    public AnthropicMessagesLlmClient(String baseUrl, String apiKey, String model, String anthropicVersion, int timeoutMs) {
        this(baseUrl, apiKey, model, anthropicVersion, timeoutMs, 0);
    }

    public AnthropicMessagesLlmClient(
            String baseUrl,
            String apiKey,
            String model,
            String anthropicVersion,
            int timeoutMs,
            int thinkingBudgetTokens) {
        this(baseUrl, apiKey, model, anthropicVersion, timeoutMs, thinkingBudgetTokens,
                AnthropicSamplingParametersMode.legacy);
    }

    public AnthropicMessagesLlmClient(
            String baseUrl,
            String apiKey,
            String model,
            String anthropicVersion,
            int timeoutMs,
            int thinkingBudgetTokens,
            AnthropicSamplingParametersMode samplingParametersMode) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Anthropic API key is required");
        }
        String root = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com" : baseUrl.trim();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.messagesUrl = root + "/v1/messages";
        this.apiKey = apiKey;
        this.defaultModel = (model != null && !model.isBlank()) ? model.trim() : "claude-opus-4-20250514";
        this.anthropicVersion = (anthropicVersion != null && !anthropicVersion.isBlank())
                ? anthropicVersion.trim()
                : "2023-06-01";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000;
        this.thinkingBudgetTokens = thinkingBudgetTokens > 0 ? thinkingBudgetTokens : 0;
        this.samplingParametersMode = samplingParametersMode != null
                ? samplingParametersMode
                : AnthropicSamplingParametersMode.legacy;
    }

    @Override
    public LlmUsageWireIds usageWireIds() {
        return new LlmUsageWireIds("", getClass().getSimpleName(), "anthropic-messages-v1", defaultModel);
    }

    @Override
    public LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
        String m = (effectiveModel != null && !effectiveModel.isBlank()) ? effectiveModel.trim() : defaultModel;
        return usageWireIds().withModel(m);
    }

    @Override
    public LlmResponse chat(LlmChatRequest request) throws Exception {
        List<ChatMessage> messages = request.getMessages();
        List<ToolDefinition> tools = request.getTools();
        String useModel = (request.getModelOverride() != null && !request.getModelOverride().isBlank())
                ? request.getModelOverride().trim()
                : defaultModel;
        int maxOut = OpenAiChatCompletionsClient.resolvedMaxOutputTokens(request);

        int effectiveThinking = request.isProbeMode() ? 0 : thinkingBudgetTokens;
        AnthropicMessagesApi.RequestPayloadResult built = AnthropicMessagesApi.buildRequestPayloadWithDiagnostics(
                messages, tools, useModel, request.getTemperature(), maxOut, effectiveThinking, request,
                samplingParametersMode);
        Map<String, Object> body = built.getPayload();
        SuffixClassificationWarningLogger.log(LOG, "anthropic", built.getDiagnostics());
        List<?> apiMessages = (List<?>) body.get("messages");
        String requestBody = JSON.writeValueAsString(body);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        HttpPost post = new HttpPost(messagesUrl);
        post.setConfig(requestConfig);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("x-api-key", apiKey);
        post.setHeader("anthropic-version", anthropicVersion);
        post.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));

        int msgCount = messages != null ? messages.size() : 0;
        int toolCount = tools != null ? tools.size() : 0;
        LlmUsageWireIds roundWireIds = request.getUsageWireIdsOverride() != null
                ? request.getUsageWireIdsOverride().withModel(useModel)
                : usageWireIdsForEffectiveModel(useModel);
        LOG.info("Anthropic messages: model={} apiMessages={} tools={} max_tokens={}",
                useModel, apiMessages != null ? apiMessages.size() : 0, toolCount, maxOut);
        return LlmRecordedHttpChat.executeAnthropicMessages(
                request,
                roundWireIds,
                "messages",
                timeoutMs,
                () -> {
                    try (CloseableHttpClient client = HttpClients.createDefault();
                            CloseableHttpResponse response = client.execute(post)) {
                        String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                        int status = response.getStatusLine().getStatusCode();
                        return new LlmRecordedHttpChat.HttpResult(status, responseBody, response.getAllHeaders());
                    }
                });
    }

    @Override
    public boolean healthCheck() {
        long t0 = System.currentTimeMillis();
        LlmUsageWireIds probeIds = usageWireIdsForEffectiveModel(defaultModel);
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user("Reply with exactly: OK"));
            LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(
                    LlmChatRequest.copyWithProviderAugmentation(
                            LlmChatRequest.forAgentRound(messages, null, 0.0, 32, null),
                            probeIds,
                            32,
                            null),
                    true);
            LlmResponse r = chat(probe);
            boolean ok = r.getContent() != null && r.getContent().toUpperCase().contains("OK");
            long elapsed = System.currentTimeMillis() - t0;
            if (!ok) {
                LOG.warn("Anthropic healthCheck: unexpected content: {}", r.getContent());
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, messages.size(), elapsed, null);
            } else {
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, true, messages.size(), elapsed, null);
            }
            return ok;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, 0, elapsed, e);
            LOG.error("Anthropic healthCheck failed: {}", e.getMessage(), e);
            return false;
        }
    }
}
