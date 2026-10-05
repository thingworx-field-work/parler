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
 * Azure OpenAI Chat Completions client.
 */
public class AzureOpenAILlmClient implements LlmClient {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AzureOpenAILlmClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String deploymentName;
    private final String apiVersion;
    private final int timeoutMs;
    private final boolean useMaxCompletionTokens;

    public AzureOpenAILlmClient(
            String endpoint,
            String apiKey,
            String deploymentName,
            String apiVersion,
            int timeoutMs,
            boolean useMaxCompletionTokens) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("Azure OpenAI endpoint is required");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Azure OpenAI API key is required");
        }
        String base = endpoint.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base + "/openai/deployments/" + deploymentName + "/chat/completions?api-version=" + apiVersion;
        this.apiKey = apiKey;
        this.deploymentName = deploymentName;
        this.apiVersion = apiVersion;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000;
        this.useMaxCompletionTokens = useMaxCompletionTokens;
    }

    @Override
    public LlmUsageWireIds usageWireIds() {
        return new LlmUsageWireIds("", getClass().getSimpleName(), "", deploymentName);
    }

    @Override
    public LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
        String m = (effectiveModel != null && !effectiveModel.isEmpty()) ? effectiveModel : deploymentName;
        return usageWireIds().withModel(m);
    }

    @Override
    public LlmResponse chat(LlmChatRequest request) throws Exception {
        List<ChatMessage> messages = request.getMessages();
        List<ToolDefinition> tools = request.getTools();
        String useModel = (request.getModelOverride() != null && !request.getModelOverride().isEmpty())
                ? request.getModelOverride()
                : deploymentName;
        int maxTok = OpenAiChatCompletionsClient.resolvedMaxOutputTokens(request);
        String maxTokenField = useMaxCompletionTokens ? "max_completion_tokens" : "max_tokens";

        ChatCompletionsApi.RequestBodyResult built = ChatCompletionsApi.buildRequestBodyWithDiagnostics(
                useModel, messages, tools, request, useMaxCompletionTokens, maxTok);
        Map<String, Object> body = built.getBody();
        SuffixClassificationWarningLogger.log(LOG, "azure-openai", built.getDiagnostics());

        String requestBody = JSON.writeValueAsString(body);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        HttpPost post = new HttpPost(baseUrl);
        post.setConfig(requestConfig);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("api-key", apiKey);
        post.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));

        int msgCount = messages != null ? messages.size() : 0;
        int toolCount = tools != null ? tools.size() : 0;
        LlmUsageWireIds roundWireIds = request.getUsageWireIdsOverride() != null
                ? request.getUsageWireIdsOverride().withModel(useModel)
                : usageWireIdsForEffectiveModel(useModel);
        LOG.info("Azure OpenAI request: deployment={} model={} messages={} tools={} {}={} timeoutMs={}",
                deploymentName, useModel, msgCount, toolCount, maxTokenField, maxTok, timeoutMs);
        return LlmRecordedHttpChat.executeChatCompletions(
                request,
                roundWireIds,
                "Azure OpenAI",
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

    @Override
    public boolean healthCheck() {
        long t0 = System.currentTimeMillis();
        LlmUsageWireIds probeIds = usageWireIdsForEffectiveModel(deploymentName);
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user("Please respond with exactly: OK"));
            LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(
                    LlmChatRequest.copyWithProviderAugmentation(
                            LlmChatRequest.forAgentRound(messages, null, 0.0, 50, deploymentName, probeIds),
                            probeIds,
                            50,
                            null),
                    true);
            LlmResponse r = chat(probe);
            boolean ok = r.getContent() != null && r.getContent().trim().toUpperCase().contains("OK");
            long elapsed = System.currentTimeMillis() - t0;
            if (!ok) {
                LOG.warn("Azure OpenAI healthCheck: unexpected response content: {}", r.getContent());
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, messages.size(), elapsed, null);
            } else {
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, true, messages.size(), elapsed, null);
            }
            return ok;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, 0, elapsed, e);
            LOG.error("Azure OpenAI healthCheck failed: {}", e.getMessage(), e);
            return false;
        }
    }

}
