package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tool classification: thrown and structured errors, terminal blocks, approval pause (design: Verification check 2). */
class ParlerToolScopeTest {

    private static List<String> run(ToolKind kind, java.util.function.Consumer<ParlerToolScope> body) {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        ParlerToolScope scope = new ParlerToolScope(kind, sink, null);
        try {
            body.accept(scope);
        } catch (RuntimeException expected) {
            // thrown tool exception
        } finally {
            scope.close();
            scope.close();
        }
        return sink.events;
    }

    @Test
    void completedResultIsSuccess() {
        assertEquals(List.of("tool|built_in|success"),
                run(ToolKind.BUILT_IN, s -> s.completed("{\"status\":\"success\",\"rows\":[]}")));
        assertEquals(List.of("tool|custom|success"), run(ToolKind.CUSTOM, s -> s.completed("plain text body")));
        assertEquals(List.of("tool|other|success"), run(null, s -> s.completed("")));
    }

    @Test
    void structuredErrorEnvelopeIsError() {
        assertEquals(List.of("tool|built_in|error"),
                run(ToolKind.BUILT_IN, s -> s.completed("{\"status\":\"error\",\"message\":\"nope\"}")));
    }

    @Test
    void thrownExceptionIsError() {
        assertEquals(List.of("tool|workflow|error"), run(ToolKind.WORKFLOW, s -> {
            throw new IllegalStateException("dispatcher threw");
        }));
    }

    @Test
    void knownTerminalBlockIsErrorEvenWhenNotErrorShaped() {
        // approvalRequiresParlerContextJson / blockedOutsideParlerContextJson use status "blocked".
        assertEquals(List.of("tool|built_in|error"), run(ToolKind.BUILT_IN, s -> {
            s.markError();
            s.completed("{\"status\":\"blocked\",\"code\":\"APPROVAL_REQUIRES_PARLER_CONTEXT\"}");
        }));
        // Repetition block before dispatch: no completion call at all.
        assertEquals(List.of("tool|custom|error"), run(ToolKind.CUSTOM, ParlerToolScope::markError));
    }

    @Test
    void approvedDispatchBlocksAreErrors() {
        // stale compare, registry miss, unsupported gate
        assertEquals(List.of("tool|built_in|error"), run(ToolKind.OTHER, s -> {
            s.kind(ToolKind.BUILT_IN);
            s.markError();
            s.completed("{\"status\":\"stale\"}");
        }));
        assertEquals(List.of("tool|custom|error"), run(ToolKind.OTHER, s -> {
            s.kind(ToolKind.CUSTOM);
            s.markError();
            s.completed("{\"status\":\"error\",\"code\":\"CAPABILITY_POLICY_BLOCKED\"}");
        }));
        assertEquals(List.of("tool|other|error"), run(ToolKind.OTHER, s -> {
            s.markError();
            s.completed("{\"status\":\"error\",\"code\":\"UNSUPPORTED_GATE\"}");
        }));
    }

    @Test
    void approvalPauseProducesNoSample() {
        assertTrue(run(ToolKind.BUILT_IN, ParlerToolScope::suppress).isEmpty());
    }

    @Test
    void providerFamilyMapping() {
        assertEquals(ProviderFamily.AZURE_OPENAI, ProviderFamily.fromApiShapeId("azure-openai-chat-completions-v4"));
        assertEquals(ProviderFamily.OPENAI, ProviderFamily.fromApiShapeId("openai-chat-completions-v4"));
        assertEquals(ProviderFamily.ANTHROPIC, ProviderFamily.fromApiShapeId("anthropic-messages-v1"));
        assertEquals(ProviderFamily.OTHER, ProviderFamily.fromApiShapeId("gemini-v1"));
        assertEquals(ProviderFamily.OTHER, ProviderFamily.fromApiShapeId(null));
    }
}
