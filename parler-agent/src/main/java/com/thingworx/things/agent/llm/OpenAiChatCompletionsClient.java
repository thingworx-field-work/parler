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
 * OpenAI-compatible <strong>Chat Completions</strong> API ({@code POST .../v1/chat/completions}).
 */
public class OpenAiChatCompletionsClient implements LlmClient {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(OpenAiChatCompletionsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String chatCompletionsUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int timeoutMs;
    private final boolean useMaxCompletionTokens;
    private final String organizationId;
    private final String projectId;

    public OpenAiChatCompletionsClient(
            String baseUrl,
            String apiKey,
            String model,
            int timeoutMs,
            boolean useMaxCompletionTokens,
            String organizationId,
            String projectId) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key is required");
        }
        String root = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.trim();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.chatCompletionsUrl = root + "/chat/completions";
        this.apiKey = apiKey;
        this.defaultModel = (model != null && !model.isBlank()) ? model.trim() : "gpt-4o";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000;
        this.useMaxCompletionTokens = useMaxCompletionTokens;
        this.organizationId = organizationId;
        this.projectId = projectId;
    }

    @Override
    public LlmUsageWireIds usageWireIds() {
        return new LlmUsageWireIds("", getClass().getSimpleName(), "", defaultModel);
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
        int maxTok = resolvedMaxOutputTokens(request);
        String maxTokenField = useMaxCompletionTokens ? "max_completion_tokens" : "max_tokens";

        ChatCompletionsApi.RequestBodyResult built = ChatCompletionsApi.buildRequestBodyWithDiagnostics(
                useModel, messages, tools, request, useMaxCompletionTokens, maxTok);
        Map<String, Object> body = built.getBody();
        SuffixClassificationWarningLogger.log(LOG, "openai", built.getDiagnostics());

        String requestBody = JSON.writeValueAsString(body);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        HttpPost post = new HttpPost(chatCompletionsUrl);
        post.setConfig(requestConfig);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        if (organizationId != null && !organizationId.isBlank()) {
            post.setHeader("OpenAI-Organization", organizationId.trim());
        }
        if (projectId != null && !projectId.isBlank()) {
            post.setHeader("OpenAI-Project", projectId.trim());
        }
        post.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));

        int msgCount = messages != null ? messages.size() : 0;
        int toolCount = tools != null ? tools.size() : 0;
        LlmUsageWireIds roundWireIds = request.getUsageWireIdsOverride() != null
                ? request.getUsageWireIdsOverride().withModel(useModel)
                : usageWireIdsForEffectiveModel(useModel);
        LOG.info("OpenAI chat/completions: model={} messages={} tools={} {}={} url={}",
                useModel, msgCount, toolCount, maxTokenField, maxTok, chatCompletionsUrl);
        return LlmRecordedHttpChat.executeChatCompletions(
                request,
                roundWireIds,
                "OpenAI",
                "chat/completions",
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

    static int resolvedMaxOutputTokens(LlmChatRequest request) {
        return ProviderRequestResolution.toPositiveResolvedMaxOutput(request.getRequestedMaxOutputTokens());
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
                            LlmChatRequest.forAgentRound(messages, null, 0.0, 32, null, probeIds),
                            probeIds,
                            32,
                            null),
                    true);
            LlmResponse r = chat(probe);
            boolean ok = r.getContent() != null && r.getContent().toUpperCase().contains("OK");
            long elapsed = System.currentTimeMillis() - t0;
            if (!ok) {
                LOG.warn("OpenAI healthCheck: unexpected content: {}", r.getContent());
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, messages.size(), elapsed, null);
            } else {
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, true, messages.size(), elapsed, null);
            }
            return ok;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, 0, elapsed, e);
            LOG.error("OpenAI healthCheck failed: {}", e.getMessage(), e);
            return false;
        }
    }

}
