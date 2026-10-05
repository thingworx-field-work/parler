package com.thingworx.things.agent.llm.usage;

import org.apache.http.Header;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatCompletionsResponseParser;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmHttpDiagnostics;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Shared HTTP execute wrapper that records call lifecycle before response parsing (CC-7 M0a).
 */
public final class LlmRecordedHttpChat {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmRecordedHttpChat() {}

    public static LlmResponse executeChatCompletions(
            LlmChatRequest request,
            LlmUsageWireIds roundWireIds,
            String providerLabel,
            String apiLabel,
            int timeoutMs,
            HttpExecutor executor) throws Exception {
        LlmCallContext context = resolveContext(request, roundWireIds);
        LlmCallRecorder.LlmCallAttempt attempt = beginAttempt(context, roundWireIds);
        long t0 = System.currentTimeMillis();
        try {
            attempt.markDispatched();
            HttpResult result = executor.execute();
            long elapsed = System.currentTimeMillis() - t0;
            return handleHttpResult(attempt, roundWireIds, providerLabel, apiLabel, timeoutMs, t0, result, elapsed,
                    body -> ChatCompletionsResponseParser.parse(body, LlmHttpDiagnostics.extractProviderRequestId(result.headers)));
        } catch (Exception e) {
            handleTransportFailure(attempt, roundWireIds, timeoutMs, t0, e);
            throw e;
        }
    }

    public static LlmResponse executeAnthropicMessages(
            LlmChatRequest request,
            LlmUsageWireIds roundWireIds,
            String apiLabel,
            int timeoutMs,
            HttpExecutor executor) throws Exception {
        LlmCallContext context = resolveContext(request, roundWireIds);
        LlmCallRecorder.LlmCallAttempt attempt = beginAttempt(context, roundWireIds);
        long t0 = System.currentTimeMillis();
        try {
            attempt.markDispatched();
            HttpResult result = executor.execute();
            long elapsed = System.currentTimeMillis() - t0;
            return handleHttpResult(attempt, roundWireIds, "Anthropic", apiLabel, timeoutMs, t0, result, elapsed,
                    body -> AnthropicMessagesApi.parseResponse(body,
                            LlmHttpDiagnostics.extractProviderRequestId(result.headers)));
        } catch (Exception e) {
            handleTransportFailure(attempt, roundWireIds, timeoutMs, t0, e);
            throw e;
        }
    }

    private static LlmResponse handleHttpResult(
            LlmCallRecorder.LlmCallAttempt attempt,
            LlmUsageWireIds roundWireIds,
            String providerLabel,
            String apiLabel,
            int timeoutMs,
            long t0,
            HttpResult result,
            long elapsed,
            ResponseParser parser) throws Exception {
        String providerRequestId = LlmHttpDiagnostics.extractProviderRequestId(result.headers);
        ResponseIdentity identity = extractResponseIdentity(result.body);
        LlmUsageSnapshot usage = LlmUsageCapture.captureFromResponseBody(result.body, roundWireIds);
        if (result.status < 200 || result.status >= 300) {
            attempt.finishError(roundWireIds, usage, result.status, providerRequestId, identity.providerResponseId,
                    identity.responseModel, "http_" + result.status, apiLabel + " http failure", elapsed);
            throw LlmHttpDiagnostics.httpFailureException(providerLabel, result.status, result.body, result.headers);
        }
        try {
            LlmResponse parsed = parser.parse(result.body);
            attempt.finishSuccess(roundWireIds, usage, parsed, result.status, providerRequestId,
                    identity.providerResponseId, identity.responseModel, elapsed);
            return parsed;
        } catch (Exception parseError) {
            attempt.finishError(roundWireIds, usage, result.status, providerRequestId, identity.providerResponseId,
                    identity.responseModel, "content_parse_error", safeSummary(parseError), elapsed);
            throw parseError;
        }
    }

    private static void handleTransportFailure(
            LlmCallRecorder.LlmCallAttempt attempt,
            LlmUsageWireIds roundWireIds,
            int timeoutMs,
            long t0,
            Exception e) {
        if (attempt.isActive() && !attempt.isFinished() && !isAlreadyFinished(e)) {
            long elapsed = System.currentTimeMillis() - t0;
            if (e instanceof java.net.SocketTimeoutException || isTimeout(e, elapsed, timeoutMs)) {
                attempt.finishTimeout(roundWireIds, null, elapsed);
            } else if (!(e instanceof RuntimeException && e.getMessage() != null
                    && e.getMessage().contains("http failure"))) {
                attempt.finishError(roundWireIds, LlmUsageSnapshot.unavailable(), null, null, null, null,
                        "transport_error", safeSummary(e), elapsed);
            }
        }
    }

    private static ResponseIdentity extractResponseIdentity(String body) {
        if (body == null || body.isBlank()) {
            return ResponseIdentity.EMPTY;
        }
        try {
            JsonNode root = JSON.readTree(body);
            return new ResponseIdentity(textOrNull(root.path("id")), textOrNull(root.path("model")));
        } catch (Exception ignored) {
            return ResponseIdentity.EMPTY;
        }
    }

    private static LlmCallContext resolveContext(LlmChatRequest request, LlmUsageWireIds wireIds) {
        LlmCallContext ctx = request != null ? request.getCallContext() : null;
        if (ctx != null) {
            return ctx.withWireIds(wireIds);
        }
        LlmCallKind kind = request != null && request.isProbeMode() ? LlmCallKind.PROBE : LlmCallKind.AGENT_ROUND;
        return LlmCallRecorder.subCallContext(kind, wireIds);
    }

    private static LlmCallRecorder.LlmCallAttempt beginAttempt(
            LlmCallContext context,
            LlmUsageWireIds wireIds) {
        if (context == null) {
            return LlmCallRecorder.ensureAttempt(LlmCallRecorder.subCallContext(LlmCallKind.AGENT_ROUND, wireIds));
        }
        return LlmCallRecorder.ensureAttempt(context.withWireIds(wireIds));
    }

    private static boolean isTimeout(Exception e, long elapsed, int timeoutMs) {
        return elapsed >= timeoutMs && e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out");
    }

    private static boolean isAlreadyFinished(Exception e) {
        return e instanceof RuntimeException && e.getMessage() != null
                && e.getMessage().contains("http failure");
    }

    private static String safeSummary(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text != null && !text.isBlank() ? text : null;
    }

    @FunctionalInterface
    private interface ResponseParser {
        LlmResponse parse(String body) throws Exception;
    }

    private static final class ResponseIdentity {
        private static final ResponseIdentity EMPTY = new ResponseIdentity(null, null);

        private final String providerResponseId;
        private final String responseModel;

        private ResponseIdentity(String providerResponseId, String responseModel) {
            this.providerResponseId = providerResponseId;
            this.responseModel = responseModel;
        }
    }

    @FunctionalInterface
    public interface HttpExecutor {
        HttpResult execute() throws Exception;
    }

    public static final class HttpResult {
        public final int status;
        public final String body;
        public final Header[] headers;

        public HttpResult(int status, String body, Header[] headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }
}
