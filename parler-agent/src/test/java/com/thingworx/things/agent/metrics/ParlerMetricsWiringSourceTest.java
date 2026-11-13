package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Source census in the style of {@code cache/ArtifactCachePublicBoundarySourceTest}: reads production source without
 * instantiating {@code AgentThing} and checks where each metrics scope starts, closes, and is classified.
 */
class ParlerMetricsWiringSourceTest {

    private static final Path AGENT_THING = Path.of("src/main/java/com/thingworx/things/agent/AgentThing.java");
    private static final Path GATEWAY = Path.of("src/main/java/com/thingworx/things/agent/ParlerGateway.java");
    private static final Path HTTP_WRAPPER = Path.of(
            "src/main/java/com/thingworx/things/agent/llm/usage/LlmRecordedHttpChat.java");
    private static final Path METRICS_DIR = Path.of("src/main/java/com/thingworx/things/agent/metrics");

    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void chatStartsAfterAdmissionInsideLockAndOnSingleTurnPath() throws Exception {
        String chat = between(read(AGENT_THING), "public String Chat(", "// ── Asynchronous Chat");
        assertBefore(chat, "artifactCacheAdmission(\"Chat\"", "ParlerMetrics.startTurn(TurnEntryPoint.CHAT)");
        String locked = between(chat, "synchronized (lockForConversation(conversationId)) {",
                "HostContextTurnPrep.renderedOnly(");
        assertBefore(locked, "ParlerMetrics.startTurn(TurnEntryPoint.CHAT)", "prepareHostContextTurn(");
        assertBefore(locked, "prepareHostContextTurn(", "} finally {\n                    turnScope.close();");
        String single = after(chat, "HostContextTurnPrep.renderedOnly(");
        assertTrue(chat.indexOf("ParlerMetrics.startTurn(TurnEntryPoint.CHAT)",
                chat.indexOf("synchronized (lockForConversation(conversationId))") + 1) > 0);
        assertTrue(single.contains("executeChatTurn(") && single.contains("turnScope.close();"));
        assertEquals(2, count(chat, "ParlerMetrics.startTurn(TurnEntryPoint.CHAT)"));
        assertEquals(2, count(chat, "turnScope.close();"));
        assertBefore(chat, "ParlerMetrics.startTurn(TurnEntryPoint.CHAT);\n        try {",
                "HostContextTurnPrep.renderedOnly(");

        String turn = between(read(AGENT_THING), "private String executeChatTurn(", "// ── Asynchronous Chat");
        assertBefore(turn, "tryExecutePlaybookSlashTurn(", "TurnOutcomes.forPlaybookSlash(true)");
        assertBefore(turn, "} catch (Exception e) {", "TurnOutcomes.forPlaybookSlash(false)");
        assertBefore(turn, "if (isArtifactCacheTerminal(result))", "turnScope.outcome(TurnOutcome.ERROR);");
        assertBefore(turn, "_conversations.put(conversationId, messages);\n        }\n\n",
                "turnScope.outcome(TurnOutcomes.forResultStatus(result.getStatus()));");
    }

    @Test
    void chatAsyncStartsInsideWorkerLockImmediatelyAfterAdmission() throws Exception {
        String async = between(read(AGENT_THING), "public String ChatAsync(", "logHostContextDiscarded");
        String locked = after(async, "synchronized (convLock) {");
        assertBefore(locked, "artifactCacheAdmission(", "ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC)");
        assertBefore(locked, "fireAgentResponseEvent(effectiveConvId, artifactCacheAdmissionResult(cacheAdmission));",
                "ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC)");
        assertBefore(locked, "ParlerMetrics.startTurn(TurnEntryPoint.CHAT_ASYNC)", "prepareHostContextTurn(");
        assertBefore(locked, "TurnOutcomes.forResultStatus(result.getStatus())", "turnScope.close();");
        assertBefore(locked, "turnScope.close();", "} catch (Exception e) {");
        assertTrue(locked.contains("TurnOutcomes.forPlaybookSlash(true)"));
        assertTrue(locked.contains("TurnOutcomes.forPlaybookSlash(false)"));
    }

    @Test
    void streamStartsAfterAckAndAdmissionAndAckFailureStillReachesAdmission() throws Exception {
        String alwaysOn = between(read(AGENT_THING), "public String ParlerStreamToRemoteThing(",
                "Shared lock for a Parler");
        String locked = after(alwaysOn, "synchronized (convLock) {");
        assertBefore(locked, "wireSessionAck(", "artifactCacheAdmission(");
        assertBefore(locked, "artifactCacheAdmission(", "ParlerMetrics.startTurn(TurnEntryPoint.STREAM)");
        assertBefore(locked, "ParlerMetrics.startTurn(TurnEntryPoint.STREAM)", "prepareHostContextTurn(");
        // A failed ack only flips downlinkOk; there is no early return between the ack and admission.
        String ackToAdmission = between(locked, "wireSessionAck(", "artifactCacheAdmission(");
        assertTrue(ackToAdmission.contains("downlinkOk.set(false);"));
        assertFalse(ackToAdmission.contains("return"));

        assertTrue(locked.contains("turnScope.outcome(TurnOutcomes.forAwaitingApproval(tomb, false));"));
        assertTrue(locked.contains("turnScope.outcome(TurnOutcomes.forAwaitingApproval(false, true));"));
        assertTrue(locked.contains("turnScope.outcome(TurnOutcome.CANCELLED);"));
        assertTrue(locked.contains("turnScope.outcome(TurnOutcomes.forResultStatus(result.getStatus()));"));
        assertBefore(locked, "ParlerRunningTurnCancelRegistry.clear(effectiveConvId, requestId, turnPrincipal, thingName);\n"
                + "                    }\n                    } finally {\n                        turnScope.close();",
                "} catch (Exception e) {");
    }

    @Test
    void gatewayDelegatesToTheMeasuredStreamEntryPoint() throws Exception {
        String gateway = read(GATEWAY);
        assertTrue(gateway.contains("agent.ParlerStreamToRemoteThing(message, systemPrompt, getName(), userTimezone,"
                + " hostContext)"));
        assertFalse(gateway.contains("ParlerMetrics."), "gateway must not double-count the stream segment");
    }

    @Test
    void approvalResumeStartsAfterAdmissionBeforeDecisionBranchAndClosesInFinally() throws Exception {
        String hitl = between(read(AGENT_THING), "private void runParlerApprovalContinuation(",
                "public ResultBudget resolveResultBudget(");
        assertBefore(hitl, "artifactCacheAdmission(", "ParlerMetrics.startTurn(TurnEntryPoint.APPROVAL_RESUME)");
        assertBefore(hitl, "fireAgentResponseEvent(wireCid, rejection);",
                "ParlerMetrics.startTurn(TurnEntryPoint.APPROVAL_RESUME)");
        assertBefore(hitl, "ParlerMetrics.startTurn(TurnEntryPoint.APPROVAL_RESUME)",
                "if (\"cancel\".equalsIgnoreCase(decision))");
        assertEquals(3, count(hitl, "turnScope.outcome(runParlerPostToolAgentLoop("));
        assertEquals(2, count(hitl, "// Continuation record is incomplete without the gated call.\n"
                + "                    turnScope.outcome(TurnOutcome.ERROR);"));
        assertBefore(hitl, "turnScope.outcome(runParlerPostToolAgentLoop(rec, messages,",
                "} finally {\n                turnScope.close();");
        assertBefore(hitl, "turnScope.close();", "ThreadLocalContext.clearSecurityContext();");

        String postTool = between(read(AGENT_THING), "private TurnOutcome runParlerPostToolAgentLoop(",
                "private void runParlerApprovalContinuation(");
        // Stream-sink lambda returns are local; every method exit after the lambda returns a mapped outcome.
        String body = after(postTool, "ToolExecutor combinedExecutor");
        assertFalse(Pattern.compile("return;").matcher(body).find(), "every exit returns a mapped outcome");
        assertTrue(postTool.contains("return TurnOutcomes.forResultStatus(result.getStatus());"));
        assertTrue(postTool.contains("return pausedOutcome;"));
    }

    @Test
    void dispatcherToolSiteClassifiesBlocksAndSuppressesApprovalPause() throws Exception {
        String source = read(AGENT_THING);
        String exec = between(source, "private String executeToolCall(ToolCall toolCall)",
                "private ToolKind toolKindForDispatch(");
        assertBefore(exec, "ParlerMetrics.startTool(toolKindForDispatch(", "interceptThirdIdentical(");
        assertBefore(exec, "if (repetitionBlocked.isPresent()) {\n            toolScope.markError();",
                "dispatchExecuteToolCallWithoutRepetitionGuard(toolCall, toolScope)");
        assertBefore(exec, "catch (ApprovalPendingException e) {\n            toolScope.suppress();",
                "} finally {\n            toolScope.close();");

        String dispatch = between(source, "private String dispatchExecuteToolCallWithoutRepetitionGuard(",
                "private static String jsonText(");
        assertBefore(dispatch, "toolScope.markError();\n                String toolResult = capabilityPolicyBlockedResult(",
                "REQUIRE_HITL");
        assertEquals(2, count(dispatch,
                "toolScope.markError();\n                String blocked = ParlerHitlStreamScopedEnqueue"
                        + ".approvalRequiresParlerContextJson(fn);"));
        assertTrue(dispatch.contains("toolScope.markError();\n            return SetPropertyValueExecutor"
                + ".blockedOutsideParlerContextJson();"));
        // Playbook tool calls delegate to the measured dispatcher.
        assertTrue(source.contains("PlaybookToolExecutionResult.jsonOnly(executeToolCall(tc), tc.getId())"));
    }

    @Test
    void approvedToolSiteMeasuresDispatchOnlyAndClosesBeforeNotification() throws Exception {
        String hitl = between(read(AGENT_THING), "private void runParlerApprovalContinuation(",
                "public ResultBudget resolveResultBudget(");
        String approved = after(hitl, "ResultBudget approvedBudget = resolveResultBudget(gatedName);");
        assertBefore(approved, "ParlerMetrics.startTool(ToolKind.OTHER)", "if (\"set_property_value\".equals(gatedName))");
        assertBefore(approved, "approvedToolScope.markError();\n                        toolResult = "
                + "SetPropertyValueExecutor.staleCompareRejectedJson();", "executeApprovedWrite(");
        assertBefore(approved, "approvedToolScope.kind(ToolKind.CUSTOM);", "hitlApproveRegistryMissReason(");
        assertTrue(approved.contains("approvedToolScope.markError();\n                        toolResult = "
                + "capabilityPolicyBlockedResult(gatedName, miss.get());"));
        assertTrue(approved.contains("} catch (Exception e) {\n                    approvedToolScope.markError();"));
        assertBefore(approved, "approvedToolScope.markError();\n                toolResult = \"{\\\"status\\\":"
                + "\\\"error\\\",\\\"code\\\":\\\"UNSUPPORTED_GATE\\\"", "approvedToolScope.close();");
        assertBefore(approved, "approvedToolScope.close();", "wireApprovalResolved(wireRid, wireCid, rec.getPendingId(),\n"
                + "                                \"approved\"");
        assertBefore(approved, "approvedToolScope.close();", "ApprovedToolResultAppender.append(");
        assertBefore(approved, "approvedToolScope.close();", "runParlerPostToolAgentLoop(");

        // Cancel / reject branches open no tool scope for the gated call.
        String beforeApproved = hitl.substring(0, hitl.indexOf("ResultBudget approvedBudget"));
        assertFalse(beforeApproved.contains("startTool("));
    }

    @Test
    void providerWrapperStartsAtDispatchAndClassifiesEveryBranch() throws Exception {
        String wrapper = read(HTTP_WRAPPER);
        assertEquals(2, count(wrapper, "attempt.markDispatched();\n            metrics = startMetrics(context, roundWireIds);\n"
                + "            HttpResult result = executor.execute();\n            metrics.responseReceived();"));
        assertTrue(wrapper.contains("metrics.finish(ProviderOutcome.HTTP_ERROR, usage);"));
        assertTrue(wrapper.contains("metrics.finish(ProviderOutcome.SUCCESS, usage);"));
        assertTrue(wrapper.contains("metrics.finish(ProviderOutcome.PARSE_ERROR, usage);"));
        assertTrue(wrapper.contains("if (metrics != null && !metrics.isFinished()) {"));
    }

    @Test
    void onlyTheRegistrarImportsMetricsV2() throws Exception {
        try (var files = Files.list(METRICS_DIR)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    boolean imports = read(p).contains("import com.thingworx.metrics.v2.");
                    assertEquals(p.getFileName().toString().equals("MetricsV2Registrar.java"), imports, p.toString());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        assertFalse(read(AGENT_THING).contains("com.thingworx.metrics.v2"));
        assertFalse(read(HTTP_WRAPPER).contains("com.thingworx.metrics.v2"));
    }

    private static String between(String source, String start, String end) {
        int s = source.indexOf(start);
        assertTrue(s >= 0, "missing start marker: " + start);
        int e = source.indexOf(end, s + start.length());
        assertTrue(e > s, "missing end marker: " + end);
        return source.substring(s, e);
    }

    private static String after(String source, String start) {
        int s = source.indexOf(start);
        assertTrue(s >= 0, "missing marker: " + start);
        return source.substring(s);
    }

    private static void assertBefore(String source, String first, String second) {
        int a = source.indexOf(first);
        int b = source.indexOf(second, Math.max(a, 0) + 1);
        assertTrue(a >= 0, "missing: " + first);
        assertTrue(b > a, "expected [" + first + "] before [" + second + "]");
    }

    private static int count(String source, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
