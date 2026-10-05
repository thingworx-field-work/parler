package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Structural ordering guard for public adapters that require a ThingWorx runtime to execute. */
class ArtifactCachePublicBoundarySourceTest {

    private static final Path AGENT_THING = Path.of(
            "src/main/java/com/thingworx/things/agent/AgentThing.java");

    @Test
    void publicEntryAdaptersGateBeforeTurnConstructionOrExecution() throws Exception {
        String source = Files.readString(AGENT_THING, StandardCharsets.UTF_8);

        String chat = between(source, "public String Chat(", "// ── Asynchronous Chat");
        assertBefore(chat, "artifactCacheAdmission(\"Chat\"", "getMergedToolDefinitions()");

        String async = between(source, "public String ChatAsync(", "logHostContextDiscarded");
        assertBefore(async, "artifactCacheAdmission(", "prepareHostContextTurn(");
        assertBefore(async, "artifactCacheAdmission(", "tryExecutePlaybookSlashTurn(");

        String alwaysOn = between(source, "public String ParlerStreamToRemoteThing(", "Shared lock for a Parler");
        assertBefore(alwaysOn, "wireSessionAck(", "artifactCacheAdmission(");
        assertBefore(alwaysOn, "artifactCacheAdmission(", "prepareHostContextTurn(");
        assertBefore(alwaysOn, "artifactCacheAdmission(", "tryExecutePlaybookSlashTurn(");

        String hitl = between(source, "private void runParlerApprovalContinuation(",
                "deliverParlerApprovalExpired");
        assertBefore(hitl, "artifactCacheAdmission(", "if (\"cancel\".equalsIgnoreCase(decision))");
        assertBefore(hitl, "artifactCacheAdmission(", "wireApprovalResolved(");
        assertBefore(hitl, "artifactCacheAdmission(", "executeApprovedWrite(");
    }

    @Test
    void publicInteractiveEntriesRejectBlankMessagesBeforeAdmissionOrThreadWork() throws Exception {
        String source = Files.readString(AGENT_THING, StandardCharsets.UTF_8);

        String chat = between(source, "public String Chat(", "// ── Asynchronous Chat");
        assertBefore(chat, "ensureConversationIdInDataTable(conversationId)",
                "Chat: message is empty.");
        assertBefore(chat, "Chat: message is empty.", "artifactCacheAdmission(\"Chat\"");

        String async = between(source, "public String ChatAsync(", "logHostContextDiscarded");
        assertBefore(async, "ensureConversationIdInDataTable(conversationId)",
                "ChatAsync: message is empty.");
        assertBefore(async, "ChatAsync: message is empty.", "ChatAsync queued");
        assertBefore(async, "ChatAsync: message is empty.", "new Thread(");

        String alwaysOn = between(source, "public String ParlerStreamToRemoteThing(",
                "Shared lock for a Parler");
        assertBefore(alwaysOn, "ParlerStreamToRemoteThing: message is empty.",
                "final String requestId");
        assertBefore(alwaysOn, "ParlerStreamToRemoteThing: message is empty.", "new Thread(");
    }

    @Test
    void turnBuilderUsesTheAgentThingModelFacingUserResolver() throws Exception {
        String source = Files.readString(AGENT_THING, StandardCharsets.UTF_8);
        String builder = between(source, "private LlmTurnContext buildLlmTurnContext(",
                "private String buildSlashLoadedSkillsBlock(");
        assertBefore(builder, "SkillSlashParser.parse(", "resolveModelFacingUserContent(");
        assertBefore(builder, "resolveModelFacingUserContent(", "buildSlashLoadedSkillsBlock(");
        assertBefore(builder, "resolveModelFacingUserContent(", "ChatMessage.user(modelFacingUserContent)");
        assertTrue(source.contains("return AgentUserMessagePolicy.resolveModelFacingUserContent(rawUserMessage, slash);"));
    }

    @Test
    void cacheTerminalBranchesBypassFinalAssistantConstruction() throws Exception {
        String source = Files.readString(AGENT_THING, StandardCharsets.UTF_8);
        String sync = between(source, "private String executeChatTurn(", "// ── Asynchronous Chat");
        assertBefore(sync, "if (isArtifactCacheTerminal(result))", "ChatMessage finalAssistant");

        String async = between(source, "public String ChatAsync(", "logHostContextDiscarded");
        assertBefore(async, "if (isArtifactCacheTerminal(result))", "ChatMessage finalAssistant");

        String alwaysOn = between(source, "public String ParlerStreamToRemoteThing(",
                "Shared lock for a Parler");
        assertBefore(alwaysOn, "else if (isArtifactCacheTerminal(result))", "ChatMessage finalAssistant");
        String cacheBranch = between(alwaysOn, "else if (isArtifactCacheTerminal(result))",
                "else if (result.getStatus() == AgentLoop.AgentResult.Status.CANCELLED)");
        assertFalse(cacheBranch.contains("wireDone("));
        assertFalse(cacheBranch.contains("ChatMessage.assistant("));

        String postHitl = between(source, "private void runParlerPostToolAgentLoop(",
                "private void runParlerApprovalContinuation(");
        assertBefore(postHitl, "if (isArtifactCacheTerminal(result))", "ChatMessage finalAssistant");
    }

    @Test
    void asyncEventShapeCarriesOptionalErrorCode() throws Exception {
        String source = Files.readString(AGENT_THING, StandardCharsets.UTF_8);
        String event = between(source, "private void fireAgentResponseEvent(",
                "private static String latestUserGoalFromActiveMessages");
        assertTrue(event.contains("eventData.put(\"errorCode\""));

        String xml = Files.readString(Path.of("Entities/DataShapes/AgentResponseEventData.xml"),
                StandardCharsets.UTF_8);
        assertTrue(xml.contains("name=\"errorCode\""));
        assertTrue(xml.contains("status is ERROR"));
    }

    @Test
    void readinessUsesQuietResolverAndOwnsBoundedDiagnostics() throws Exception {
        String core = Files.readString(Path.of(
                "src/main/java/com/thingworx/things/agent/cache/ArtifactCacheCore.java"),
                StandardCharsets.UTF_8);
        String admission = Files.readString(Path.of(
                "src/main/java/com/thingworx/things/agent/cache/ArtifactCacheTurnAdmission.java"),
                StandardCharsets.UTF_8);

        assertTrue(core.contains("FileRepositoryThingResolver::resolvesQuietly"));
        assertTrue(admission.contains("ARTIFACT_CACHE_READINESS"));
        assertTrue(admission.contains("ARTIFACT_CACHE_TURN_REJECTED"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing start marker: " + start);
        assertTrue(to > from, "missing end marker: " + end);
        return source.substring(from, to);
    }

    private static void assertBefore(String source, String first, String second) {
        int firstAt = source.indexOf(first);
        int secondAt = source.indexOf(second);
        assertTrue(firstAt >= 0, "missing first marker: " + first);
        assertTrue(secondAt >= 0, "missing second marker: " + second);
        assertTrue(firstAt < secondAt, first + " must precede " + second);
    }
}
