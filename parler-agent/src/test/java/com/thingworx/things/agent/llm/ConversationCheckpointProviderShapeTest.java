package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.compaction.ContextBudgetPlanner;
import com.thingworx.things.agent.compaction.ConversationCheckpointCodec;
import com.thingworx.things.agent.compaction.ConversationCheckpointSemantic;

/**
 * §9.2 provider-shape fixtures: the rehydrated working set
 * {@code stable system + checkpoint assistant + user-led exact tail} must serialize for OpenAI, Azure OpenAI, and
 * Anthropic without producing orphan tool rows.
 *
 * <p>The design makes this a gate, not a formality: <em>"若某 provider 不接受该形态，该 provider 跳过 checkpoint
 * injection，而不是提升为 system message"</em>. Promoting the checkpoint to a system row would hand
 * model-generated navigation the authority of the server's own prompt, so a provider that could not carry it as
 * assistant prose would have to go without it. These fixtures are the evidence that none of the three does.
 */
class ConversationCheckpointProviderShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STABLE_SYSTEM = "stable system for tests";
    private static final String GOAL = "compare pump throughput across the candidate lines";
    private static final LlmUsageWireIds ANTHROPIC_IDS =
            LlmUsageWireIds.forProviderThing("P", "T", "anthropic-messages-v1", "claude-test");
    private static final LlmUsageWireIds OPENAI_IDS =
            LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
    private static final LlmUsageWireIds AZURE_IDS =
            LlmUsageWireIds.forProviderThing("P", "T", "azure-openai-chat-completions-v4", "gpt-4o");

    private static ChatMessage checkpointRow() {
        return ConversationCheckpointCodec.toInjectedAssistant(new ConversationCheckpointSemantic(
                GOAL, List.of("stay within the maintenance window"), List.of("collected last quarter's readings"),
                List.of("charting the candidates"), List.of(), List.of(), List.of("chart the candidates"),
                List.of()));
    }

    /** Exactly §9.2 step 5's published shape: stable system, injected checkpoint, then a user-led exact tail. */
    private static List<ChatMessage> restoredWorkingSet() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(STABLE_SYSTEM));
        messages.add(checkpointRow());
        messages.add(ChatMessage.user("retained question"));
        messages.add(ChatMessage.assistant("retained answer"));
        messages.add(ChatMessage.user("question after restart"));
        return messages;
    }

    private static LlmChatRequest request(List<ChatMessage> messages) {
        return new LlmChatRequest(messages, Collections.emptyList(), 0.0, 256L, false, null, null,
                LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o"), false);
    }

    // --- OpenAI and Azure share one body builder; both flag settings are exercised concretely ----------

    @Test
    void openAiAndAzureCarryTheCheckpointAsAssistantWithNoToolRows() throws Exception {
        List<ChatMessage> messages = restoredWorkingSet();

        for (boolean useMaxCompletionTokens : new boolean[] { false, true }) {
            Map<String, Object> body = ChatCompletionsApi.buildRequestBody(
                    "gpt-x", messages, null, request(messages), useMaxCompletionTokens, 256);
            String wire = JSON.writeValueAsString(body);
            JsonNode rows = JSON.readTree(wire).get("messages");

            assertFalse(wire.contains("\"role\":\"tool\""),
                    "a restored checkpoint must not introduce an orphan tool row: " + wire);
            assertEquals(Arrays.asList("system", "assistant", "user", "assistant", "user"),
                    roles(rows), wire);
            assertTrue(rows.get(1).get("content").asText().contains(GOAL),
                    "the checkpoint travels as assistant prose");
            // Azure differs from OpenAI only in the max-token field name; both must accept the same shape.
            assertTrue(body.containsKey(useMaxCompletionTokens ? "max_completion_tokens" : "max_tokens"),
                    "fixture precondition: this really is the other provider's body variant");
        }
    }

    @Test
    void openAiKeepsTheCheckpointOutOfTheSystemRow() throws Exception {
        JsonNode rows = JSON.readTree(JSON.writeValueAsString(ChatCompletionsApi.buildRequestBody(
                "gpt-x", restoredWorkingSet(), null, request(restoredWorkingSet()), false, 256))).get("messages");

        assertEquals(STABLE_SYSTEM, rows.get(0).get("content").asText(),
                "the stable system row is unchanged and does not absorb the checkpoint");
        assertFalse(rows.get(0).get("content").asText().contains(GOAL),
                "§9.2: promoting model-generated navigation to system authority is forbidden");
    }

    // --- Anthropic rejects a leading assistant message, so the checkpoint is omitted upstream ---------

    @Test
    void anthropicNeverReceivesTheCheckpointBecauseItsFirstMessageMustBeUser() throws Exception {
        // The accepted leading-stable/suffix split leaves an injected checkpoint as the first non-system message,
        // with role `assistant`; Anthropic rejects that shape. §9.2's escape therefore still applies: omit the
        // checkpoint rather than promote model-generated navigation to system authority.
        List<ChatMessage> planned = ContextBudgetPlanner.planForProviderRound(
                null, restoredWorkingSet(), Collections.emptyList(), ANTHROPIC_IDS, 1_000_000, -1, -1)
                .getOutboundMessages();

        Map<String, Object> body = AnthropicMessagesApi.buildRequestPayload(
                planned, null, "claude-test", 0.0, 256);
        String messagesWire = JSON.writeValueAsString(body.get("messages"));
        String systemWire = JSON.writeValueAsString(body.get("system"));
        JsonNode rows = JSON.readTree(messagesWire);

        assertEquals("user", rows.get(0).get("role").asText(),
                "Anthropic's first message must be a user message: " + messagesWire);
        assertTrue(rows.get(0).get("content").isTextual(),
                "the nullable serializer request is the false kind and retains ordinary user strings");
        assertFalse(messagesWire.contains(GOAL), "the checkpoint is not in messages: " + messagesWire);
        assertFalse(systemWire.contains(GOAL),
                "and it was omitted, not promoted to system authority: " + systemWire);
        assertTrue(systemWire.contains(STABLE_SYSTEM), systemWire);
        assertFalse(messagesWire.contains("\"type\":\"tool_result\""), messagesWire);
    }

    @Test
    void theOmissionIsPlannedSoCheckpointCharsDescribesTheRequestSent() throws Exception {
        // §10.1: checkpointChars is 0 when the checkpoint is omitted. A serializer-level strip would leave the
        // planner reporting a checkpoint the provider never received, and would have to be repeated in every
        // future adapter.
        ContextBudgetPlanner.PlannedOutbound anthropic = ContextBudgetPlanner.planForProviderRound(
                null, restoredWorkingSet(), Collections.emptyList(), ANTHROPIC_IDS, 1_000_000, -1, -1);
        assertEquals(0, anthropic.getPlannedMetrics().checkpointChars,
                "the metrics must describe the request actually sent");

        ContextBudgetPlanner.PlannedOutbound openAi = ContextBudgetPlanner.planForProviderRound(
                null, restoredWorkingSet(), Collections.emptyList(), OPENAI_IDS, 1_000_000, -1, -1);
        assertEquals(checkpointRow().getContent().length(), openAi.getPlannedMetrics().checkpointChars,
                "an accepting provider keeps it, so the gate is per-provider and not a blanket removal");
        assertTrue(openAi.getOutboundMessages().stream()
                        .anyMatch(m -> ConversationCheckpointCodec.isInjectedCheckpoint(m)),
                "OpenAI still carries the checkpoint");
    }

    @Test
    void carriageIsAffirmativeSoAnUnverifiedShapeOmitsRatherThanRisksTheChat() {
        // Listing known rejecters would repeat the inference that produced the Anthropic defect: absence of a
        // known rejection is not evidence of acceptance. Omitting costs a conversation its navigation aid;
        // carrying wrongly costs the user their chat, which §8.4 forbids.
        assertTrue(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(OPENAI_IDS));
        assertTrue(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(AZURE_IDS),
                "the real azure-openai-chat-completions shape id, not an OpenAI id relabelled");
        assertTrue(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(
                LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v5", "gpt-5")));

        assertFalse(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(ANTHROPIC_IDS));
        assertFalse(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(null),
                "an absent shape has no evidence behind it");
        assertFalse(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(
                LlmUsageWireIds.forProviderThing("P", "T", "", "m")));
        assertFalse(ContextBudgetPlanner.providerCarriesInjectedCheckpoint(
                LlmUsageWireIds.forProviderThing("P", "T", "some-future-adapter-v1", "m")),
                "a future adapter omits until its own fixture and vendor check promote it");
    }

    @Test
    void theProviderOmissionIsReportedExactlyOnceWithTheRightReason() {
        // Without this, the event could vanish, change reason, or report a nonzero duration and every other
        // assertion in this file would still pass.
        List<String> sink = new ArrayList<>();
        ContextBudgetPlanner.planForProviderRound(capturingLogger(sink), restoredWorkingSet(),
                Collections.emptyList(), ANTHROPIC_IDS, 1_000_000, -1, -1);

        List<String> skips = new ArrayList<>();
        for (String l : sink) {
            if (l.startsWith("CONVERSATION_CHECKPOINT_SKIP")) {
                skips.add(l);
            }
        }
        assertEquals(1, skips.size(), "exactly one skip event: " + sink);
        assertTrue(skips.get(0).contains("reason=PROVIDER_SHAPE_UNSUPPORTED"), skips.get(0));
        assertTrue(skips.get(0).contains("summaryDurationMs=0"),
                "no summary call is involved on the planner side: " + skips.get(0));
    }

    @Test
    void anAcceptingProviderEmitsNoSkipEvent() {
        List<String> sink = new ArrayList<>();
        ContextBudgetPlanner.planForProviderRound(capturingLogger(sink), restoredWorkingSet(),
                Collections.emptyList(), OPENAI_IDS, 1_000_000, -1, -1);
        for (String l : sink) {
            assertFalse(l.startsWith("CONVERSATION_CHECKPOINT_"), l);
        }
    }

    private static Logger capturingLogger(List<String> out) {
        return (Logger) java.lang.reflect.Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("is") && name.endsWith("Enabled")) {
                        return true;
                    }
                    if ((name.equals("info") || name.equals("warn") || name.equals("error")
                            || name.equals("debug")) && args != null && args.length > 0
                            && args[0] instanceof String) {
                        Object[] params = args.length == 2 && args[1] instanceof Object[]
                                ? (Object[]) args[1]
                                : java.util.Arrays.copyOfRange(args, 1, args.length);
                        String format = (String) args[0];
                        StringBuilder sb = new StringBuilder();
                        int at = 0;
                        int from = 0;
                        int idx;
                        while ((idx = format.indexOf("{}", from)) >= 0) {
                            sb.append(format, from, idx);
                            sb.append(at < params.length ? String.valueOf(params[at++]) : "{}");
                            from = idx + 2;
                        }
                        sb.append(format.substring(from));
                        out.add(sb.toString());
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    @Test
    void aConversationWithoutACheckpointIsUnaffectedOnAnthropic() throws Exception {
        List<ChatMessage> noCheckpoint = new ArrayList<>(restoredWorkingSet());
        noCheckpoint.remove(1);
        List<ChatMessage> planned = ContextBudgetPlanner.planForProviderRound(
                null, noCheckpoint, Collections.emptyList(), ANTHROPIC_IDS, 1_000_000, -1, -1)
                .getOutboundMessages();

        assertEquals(noCheckpoint, planned, "the gate touches nothing when there is no checkpoint");
    }

    // --- the injected row is ordinary prose to every serializer ----------------------------------------

    @Test
    void theCheckpointRowCarriesNoToolCallsOnAnyProvider() throws Exception {
        ChatMessage cp = checkpointRow();
        assertFalse(cp.hasToolCalls(), "fixture precondition: the injected row is prose, not a tool-call row");
        assertEquals(ChatMessage.Role.ASSISTANT, cp.getRole());

        String openAi = JSON.writeValueAsString(ChatCompletionsApiMessages.toApiMessages(restoredWorkingSet()));
        assertFalse(openAi.contains("tool_calls"), openAi);
        String anthropic = JSON.writeValueAsString(
                AnthropicMessagesApi.buildAnthropicMessages(restoredWorkingSet()));
        assertFalse(anthropic.contains("\"type\":\"tool_use\""), anthropic);
    }

    @Test
    void aCheckpointLedSetSerializesIdenticallyToTheSameSetWithAnyOtherAssistantProse() throws Exception {
        // The shape question is only ever "can this provider carry one more assistant prose row at the head".
        // Substituting equivalent prose must change nothing structural, or the checkpoint is being treated
        // specially somewhere it should not be.
        List<ChatMessage> withCheckpoint = restoredWorkingSet();
        List<ChatMessage> withPlainProse = new ArrayList<>(withCheckpoint);
        withPlainProse.set(1, ChatMessage.assistant("ordinary leading assistant prose"));

        assertEquals(roles(JSON.readTree(JSON.writeValueAsString(
                        ChatCompletionsApiMessages.toApiMessages(withPlainProse)))),
                roles(JSON.readTree(JSON.writeValueAsString(
                        ChatCompletionsApiMessages.toApiMessages(withCheckpoint)))));
        assertEquals(roles(JSON.readTree(JSON.writeValueAsString(
                        AnthropicMessagesApi.buildAnthropicMessages(withPlainProse)))),
                roles(JSON.readTree(JSON.writeValueAsString(
                        AnthropicMessagesApi.buildAnthropicMessages(withCheckpoint)))));
    }

    private static List<String> roles(JsonNode rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add(r.path("role").asText());
        }
        return out;
    }
}
