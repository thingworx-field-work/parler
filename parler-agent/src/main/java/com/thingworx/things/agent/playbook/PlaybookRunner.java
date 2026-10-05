package com.thingworx.things.agent.playbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallEvent;
import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ApprovalPendingException;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Executes validated static {@link PlaybookDocument} graphs for V1a.
 */
public final class PlaybookRunner {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(PlaybookRunner.class);

    /** Raw table retention keys use {@code <nodeId>.result}; fan_out synthetic ids are excluded to save budget. */
    private static final Pattern RAW_TABLE_RETAIN_NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private PlaybookRunner() {}

    public static PlaybookRunResult run(
            PlaybookDocument document,
            String playbookId,
            JSONObject params,
            String userGoal,
            String conversationKey,
            List<TaxonomyRow> taxonomyRows,
            PlaybookToolExecutor toolExecutor,
            LlmClient llmClient,
            double temperature,
            int maxTokens) {
        return run(document, playbookId, params, userGoal, conversationKey, taxonomyRows, toolExecutor, llmClient,
                temperature, maxTokens, null);
    }

    public static PlaybookRunResult run(
            PlaybookDocument document,
            String playbookId,
            JSONObject params,
            String userGoal,
            String conversationKey,
            List<TaxonomyRow> taxonomyRows,
            PlaybookToolExecutor toolExecutor,
            LlmClient llmClient,
            double temperature,
            int maxTokens,
            PlaybookArtifactEmitter artifactEmitter) {
        long t0 = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        String effectivePlaybookId = playbookId != null && !playbookId.isBlank()
                ? playbookId : PlaybookIds.CROSS_REGION_HEALTH_ID;
        PlaybookRunContext ctx = new PlaybookRunContext(runId, effectivePlaybookId, params, document.budgets());
        ctx.setArtifactEmitter(artifactEmitter);
        String turnRequestId = AgentToolContext.getParlerRequestId();
        String telemetryConversationId = AgentToolContext.getConversationId();
        String agentThingName = AgentToolContext.parlerRunningCancelAgentThingNameForRegistry();
        ctx.setTelemetryContext(turnRequestId, telemetryConversationId, agentThingName);
        injectTaxonomyRows(ctx, taxonomyRows);
        boolean started = PlaybookActiveRunTracker.tryStart(conversationKey, runId);
        if (!started) {
            return finish(PlaybookRunResult.Status.FAILED,
                    "Another playbook is already running in this conversation.", "playbook_already_active",
                    ctx, t0, null);
        }
        boolean turnSuccess = false;
        PlaybookTaskProgressEmitter.begin(document, ctx.playbookId());
        try {
            List<String> order = topologicalOrder(document);
            for (String nodeId : order) {
                if (ctx.isSkipped(nodeId)) {
                    continue;
                }
                JSONObject node = document.nodesById().get(nodeId);
                if (node == null) {
                    continue;
                }
                PlaybookTaskProgressEmitter.onNodeStarted(nodeId);
                JSONObject result = executeNode(nodeId, node, document, ctx, toolExecutor, llmClient, temperature,
                        maxTokens, userGoal, conversationKey);
                ctx.putNodeOutput(nodeId, result);
                String status = result.optString("status", "");
                if ("needs_clarification".equals(status)) {
                    PlaybookTaskProgressEmitter.onNodeFailed(nodeId,
                            PlaybookTaskProgress.summaryFromNodeResult(node, result));
                    return finish(PlaybookRunResult.Status.NEEDS_CLARIFICATION,
                            result.optString("message", "More information is needed."), null, ctx, t0, null);
                }
                if ("failed".equals(status)) {
                    PlaybookTaskProgressEmitter.onNodeFailed(nodeId,
                            PlaybookTaskProgress.summaryFromNodeResult(node, result));
                    return finish(PlaybookRunResult.Status.FAILED,
                            result.optString("message", "Playbook failed."), result.optString("errorCode", null),
                            ctx, t0, null);
                }
                PlaybookTaskProgressEmitter.onNodeCompleted(nodeId,
                        PlaybookTaskProgress.summaryFromNodeResult(node, result));
            }
            JSONObject finalOut = ctx.nodeOutput(document.finalNodeId());
            String text = finalOut != null ? finalOut.optString("assistantText", "") : "";
            turnSuccess = true;
            return finish(PlaybookRunResult.Status.COMPLETED, text, null, ctx, t0, null);
        } catch (PlaybookRunException e) {
            return finish(PlaybookRunResult.Status.FAILED, e.getMessage(), e.failureCode(), ctx, t0, null);
        } catch (Exception e) {
            LOG.warn("Playbook run failed: {}", e.getMessage(), e);
            return finish(PlaybookRunResult.Status.FAILED, "Playbook execution failed: " + e.getMessage(),
                    "playbook_internal_error", ctx, t0, null);
        } finally {
            PlaybookTaskProgressEmitter.end(turnSuccess);
            PlaybookActiveRunTracker.clear(conversationKey, runId);
        }
    }

    private static PlaybookRunResult finish(PlaybookRunResult.Status status, String text, String failureCode,
            PlaybookRunContext ctx, long t0, String unused) {
        ctx.clearRawTables();
        long elapsed = System.currentTimeMillis() - t0;
        StreamTokenUsage u = ctx.llmUsageAccumulator();
        boolean usageJsonPresent = u.getLlmUsageJson() != null && !u.getLlmUsageJson().isBlank();
        LOG.info(
                "LLM_PLAYBOOK_RUN playbookId={} runId={} status={} elapsedMs={} nodeCount={} toolCallCount={} "
                        + "llmCallCount={} promptTokens={} completionTokens={} llmUsageJsonPresent={} "
                        + "localAdmissionWaitMs={} finalEvidenceBytes={} failureCode={}",
                ctx.playbookId(), ctx.runId(), status.name().toLowerCase(), elapsed,
                ctx.nodeOutputSize(), ctx.toolCallCount(), ctx.llmCallCount(), u.getPromptTokens(),
                u.getCompletionTokens(), usageJsonPresent, ctx.localAdmissionWaitMs(), -1,
                failureCode != null ? failureCode : "-");
        JSONObject runOutcome = PlaybookGapObjects.buildRunOutcome(ctx, status, text, failureCode,
                ctx.toolCallCount(), ctx.llmCallCount(), elapsed);
        return new PlaybookRunResult(status, text, failureCode, ctx.toolCallCount(), ctx.llmCallCount(), elapsed, u,
                runOutcome);
    }

    private static void injectTaxonomyRows(PlaybookRunContext ctx, List<TaxonomyRow> rows) {
        JSONArray arr = new JSONArray();
        if (rows != null) {
            for (TaxonomyRow r : rows) {
                JSONObject o = new JSONObject();
                o.put("assetType", r.getAssetType());
                o.put("entityType", r.getEntityType());
                o.put("entityName", r.getEntityName());
                o.put("criticalProperties", r.getCriticalProperties());
                o.put("synonymsNormalized", new JSONArray(r.getSynonymsNormalized()));
                arr.put(o);
            }
        }
        ctx.putVar("assetTaxonomy.rows", arr);
    }

    private static List<String> topologicalOrder(PlaybookDocument doc) throws PlaybookRunException {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String id : doc.nodeIdsInOrder()) {
            indegree.put(id, 0);
        }
        for (String id : doc.nodeIdsInOrder()) {
            JSONObject node = doc.nodesById().get(id);
            JSONArray deps = node != null ? node.optJSONArray("dependsOn") : null;
            if (deps == null) {
                continue;
            }
            for (int i = 0; i < deps.length(); i++) {
                String dep = deps.optString(i, "");
                if (dep.isEmpty()) {
                    continue;
                }
                edges.computeIfAbsent(dep, k -> new ArrayList<>()).add(id);
                indegree.put(id, indegree.getOrDefault(id, 0) + 1);
                indegree.putIfAbsent(dep, 0);
            }
        }
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.remove(0);
            order.add(id);
            for (String next : edges.getOrDefault(id, List.of())) {
                int d = indegree.getOrDefault(next, 0) - 1;
                indegree.put(next, d);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != indegree.size()) {
            throw new PlaybookRunException("playbook graph is cyclic");
        }
        return order;
    }

    private static JSONObject executeNode(
            String nodeId,
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx,
            PlaybookToolExecutor toolExecutor,
            LlmClient llmClient,
            double temperature,
            int maxTokens,
            String userGoal,
            String conversationKey) throws Exception {
        String kind = node.optString("kind", "");
        if ("derive".equals(kind)) {
            JSONObject args = node.optJSONObject("args");
            JSONObject derived = PlaybookDeriveOps.execute(node.optString("op", ""), args, ctx);
            attachEvidence(nodeId, node, document, derived, ctx);
            return derived;
        }
        if ("tool_call".equals(kind)) {
            return executeToolCallNode(nodeId, node, document, ctx, toolExecutor);
        }
        if ("fan_out".equals(kind)) {
            return executeFanOut(nodeId, node, document, ctx, toolExecutor);
        }
        if ("llm_summary".equals(kind)) {
            return executeLlmSummary(node, document, ctx, llmClient, temperature, maxTokens, userGoal, conversationKey);
        }
        if ("condition".equals(kind)) {
            return executeCondition(nodeId, node, document, ctx);
        }
        throw new PlaybookRunException("unsupported node kind: " + kind);
    }

    private static JSONObject executeCondition(
            String nodeId,
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx) throws PlaybookRunException {
        boolean takeThen = PlaybookConditionEvaluator.evaluate(node.optJSONObject("if"), ctx);
        String skipRoot = takeThen ? node.optString("else", "") : node.optString("then", "");
        ctx.skipNodes(PlaybookBranchPlanner.exclusiveToSkippedBranch(document, nodeId, skipRoot));
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("branch", takeThen ? "then" : "else");
        attachEvidence(nodeId, node, document, out, ctx);
        return out;
    }

    static JSONObject executeToolCallNode(
            String nodeId,
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx,
            PlaybookToolExecutor toolExecutor) throws Exception {
        String tool = node.optString("tool", "");
        try {
            PlaybookResolvedToolArgs resolved =
                    PlaybookExpressionResolver.resolvePlaybookToolCallArgs(node.optJSONObject("args"), tool, ctx);
            ctx.incrementToolCallCount();
            PlaybookToolExecutionResult exec =
                    toolExecutor.execute(tool, resolved.jsonArgs(), resolved.infotableArgs());
            String raw = exec.toolJsonEnvelope();
            JSONObject toolOut = parseToolJson(raw);
            JSONObject out = new JSONObject();
            out.put("status", "ok");
            out.put("toolOutput", toolOut);
            PlaybookArtifactEmitter emitter = ctx.artifactEmitter();
            if (emitter != null) {
                String tcid = exec.toolCallId();
                emitter.onToolResult(ctx.playbookId(), nodeId, tool, tcid != null ? tcid : "", raw);
            }
            if (exec.rawServiceResultTable() != null && RAW_TABLE_RETAIN_NODE_ID.matcher(nodeId).matches()) {
                ctx.putRawTable(nodeId + ".result", exec.rawServiceResultTable(), "TABLE_REF_RAW_BUDGET_EXCEEDED");
            }
            attachEvidence(nodeId, node, document, out, ctx);
            return out;
        } catch (ApprovalPendingException e) {
            // Chat path: tryEnqueueParlerHitlPending stores PendingApprovalRecord before throwing.
            // Playbook has no AWAITING_APPROVAL continuation — remove the record so approvals cannot
            // execute out-of-band after a terminal failed node.
            PendingApprovalStore.remove(e.getPendingId());
            JSONObject out = new JSONObject();
            out.put("status", "failed");
            out.put("message",
                    "Tool requires human approval before execution in a static Playbook (same enqueue path as chat). "
                            + "pendingId=" + e.getPendingId()
                            + ". Use an invoke_service target covered by allow-policy bypass, chat with approval flow, "
                            + "or wait for Playbook HITL continuation support.");
            out.put("errorCode", "PLAYBOOK_HITL_REQUIRED");
            return out;
        } catch (PlaybookRunException e) {
            JSONObject out = new JSONObject();
            out.put("status", "failed");
            out.put("message", e.getMessage());
            if (e.failureCode() != null) {
                out.put("errorCode", e.failureCode());
            }
            return out;
        }
    }

    private static JSONObject executeFanOut(
            String nodeId,
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx,
            PlaybookToolExecutor toolExecutor) throws Exception {
        Object itemsObj = PlaybookExpressionResolver.resolve(node.opt("items"), ctx);
        JSONArray items = itemsToArray(itemsObj);
        int maxItems = node.optInt("maxItems", items.length());
        JSONObject childTemplate = node.getJSONObject("node");
        boolean continueOnChildGap = node.optBoolean("continueOnChildGap", false);
        String primitiveItemKey = fanOutPrimitiveItemKey(node);
        JSONArray children = new JSONArray();
        JSONArray childResults = new JSONArray();
        int count = Math.min(items.length(), maxItems);
        int okChildren = 0;
        int gapChildren = 0;
        for (int i = 0; i < count; i++) {
            Object item = items.get(i);
            JSONObject itemObj;
            if (item instanceof JSONObject) {
                itemObj = (JSONObject) item;
            } else {
                itemObj = new JSONObject().put(primitiveItemKey, String.valueOf(item));
            }
            ctx.setCurrentItem(itemObj);
            JSONObject childResult;
            if ("tool_call".equals(childTemplate.optString("kind", ""))) {
                childResult = executeToolCallNode(nodeId + "[" + i + "]", childTemplate, document, ctx, toolExecutor);
            } else {
                throw new PlaybookRunException("fan_out child must be tool_call");
            }
            if (itemObj.has("thingName")) {
                itemObj.put("name", itemObj.get("thingName"));
            }
            JSONObject entry = new JSONObject();
            entry.put("item", itemObj);
            if (itemObj.has(primitiveItemKey)) {
                entry.put(primitiveItemKey, itemObj.get(primitiveItemKey));
            }
            entry.put("status", childResult.optString("status", "ok"));
            entry.put("toolOutput", childResult.optJSONObject("toolOutput"));
            children.put(entry);

            JSONObject childSummary = buildFanOutChildResult(i, childResult);
            childResults.put(childSummary);
            String childStatus = childResult.optString("status", "ok");
            if ("ok".equals(childStatus)) {
                okChildren++;
            } else if ("gap".equals(childSummary.optString("status", ""))) {
                gapChildren++;
            }

            PlaybookTaskProgressEmitter.onFanOutProgress(nodeId, i + 1, count);
            if ("needs_clarification".equals(childStatus)) {
                JSONObject out = fanOutResult(nodeId, node, document, ctx, children, childResults, childResult);
                out.put("status", "needs_clarification");
                out.put("message", childResult.optString("message", "More information is needed."));
                return out;
            }
            if ("failed".equals(childStatus)) {
                if (continueOnChildGap) {
                    continue;
                }
                JSONObject out = fanOutResult(nodeId, node, document, ctx, children, childResults, null);
                out.put("status", "failed");
                out.put("message", "Fan-out failed at item " + i);
                if (childResult.has("errorCode")) {
                    out.put("errorCode", childResult.optString("errorCode"));
                }
                return out;
            }
        }
        JSONObject out = fanOutResult(nodeId, node, document, ctx, children, childResults, null);
        out.put("status", "ok");
        if (gapChildren > 0) {
            String ev = "fan_out: " + okChildren + " of " + count + " children ok; " + gapChildren + " gap(s) recorded.";
            PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(ev));
        }
        return out;
    }

    private static JSONObject fanOutResult(
            String nodeId,
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx,
            JSONArray children,
            JSONArray childResults,
            JSONObject terminalChild) {
        JSONObject out = new JSONObject();
        out.put("children", children);
        JSONObject output = new JSONObject().put("childResults", childResults);
        out.put("output", output);
        attachEvidence(nodeId, node, document, out, ctx);
        if (terminalChild != null) {
            if (terminalChild.has("errorCode")) {
                out.put("errorCode", terminalChild.optString("errorCode"));
            }
        }
        return out;
    }

    /**
     * Wrapper object key for primitive (non-{@link JSONObject}) fan-out items. {@code itemVar} when
     * set; otherwise {@code value}. Object items pass through unchanged.
     */
    static String fanOutPrimitiveItemKey(JSONObject fanOutNode) {
        if (fanOutNode == null) {
            return "value";
        }
        String itemVar = fanOutNode.optString("itemVar", "").trim();
        return itemVar.isEmpty() ? "value" : itemVar;
    }

    private static JSONObject buildFanOutChildResult(int index, JSONObject childResult) {
        JSONObject entry = new JSONObject();
        entry.put("childIndex", index);
        String st = childResult.optString("status", "ok");
        if ("ok".equals(st)) {
            entry.put("status", "ok");
            JSONObject toolOut = childResult.optJSONObject("toolOutput");
            if (toolOut != null) {
                int rowCount = toolOut.optInt("rowCount", toolOut.optInt("returnedRows", -1));
                if (rowCount < 0) {
                    rowCount = toolOut.optInt("pointsReturned", -1);
                }
                if (rowCount >= 0) {
                    entry.put("rowCount", rowCount);
                }
            }
            return entry;
        }
        entry.put("status", "gap");
        String code = childResult.optString("errorCode", "");
        if (code.isBlank()) {
            code = "needs_clarification".equals(st) ? "NEEDS_CLARIFICATION" : "CHILD_FAILED";
        }
        String message = childResult.optString("message", "Fan-out child did not complete.");
        entry.put("gap", PlaybookGapObjects.structured(code, message));
        return entry;
    }

    private static JSONObject executeLlmSummary(
            JSONObject node,
            PlaybookDocument document,
            PlaybookRunContext ctx,
            LlmClient llmClient,
            double temperature,
            int maxTokens,
            String userGoal,
            String conversationKey) throws Exception {
        PlaybookTaskProgressEmitter.onSummarizing();
        int maxEvidence = node.optInt("maxEvidenceBytes", 8000);
        String evidence = PlaybookEvidenceFormatter.format(document, ctx, node.optJSONArray("evidenceRefs"),
                maxEvidence);
        String prompt = node.optString("prompt", "Summarize the playbook evidence.");
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompt),
                ChatMessage.user("User goal: " + (userGoal != null ? userGoal : "") + "\n\n" + evidence));
        LlmChatRequest req = new LlmChatRequest(messages, null, temperature, maxTokens, false, null, null, null,
                false);
        LlmCallContext parent = LlmCallContext.builder(LlmCallKind.AGENT_ROUND, LlmCallEvent.newId())
                .turnRequestId(ctx.turnRequestId())
                .conversationId(ctx.telemetryConversationId())
                .agentThing(ctx.agentThingName())
                .build();
        req = LlmChatRequest.copyWithCallContext(req,
                LlmCallRecorder.subCallContext(LlmCallKind.PLAYBOOK, parent, llmClient.usageWireIds()));
        ctx.incrementLlmCallCount();
        LlmResponse resp = llmClient.chat(req);
        ctx.addLlmUsage(StreamTokenUsage.fromLlmResponse(resp, llmClient.usageWireIds()));
        String text = resp.getContent() != null ? resp.getContent() : "";
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("assistantText", text);
        return out;
    }

    private static void attachEvidence(
            String nodeId, JSONObject node, PlaybookDocument document, JSONObject result, PlaybookRunContext ctx) {
        JSONObject evSpec = node.optJSONObject("evidence");
        if (evSpec == null) {
            return;
        }
        JSONObject evidence = new JSONObject();
        evidence.put("label", evSpec.optString("label", ""));
        evidence.put("summary", compactSummary(nodeId, node, result, ctx));
        result.put("evidence", evidence);
    }

    private static String compactSummary(String nodeId, JSONObject node, JSONObject result, PlaybookRunContext ctx) {
        if ("fan_out".equals(node.optString("kind", ""))) {
            JSONArray ch = result.optJSONArray("children");
            return "Completed " + (ch != null ? ch.length() : 0) + " items";
        }
        JSONObject toolOut = result.optJSONObject("toolOutput");
        if (toolOut != null) {
            JSONObject ev = node.optJSONObject("evidence");
            boolean wantsRoot = PlaybookEvidenceFormatter.hasNonEmptyIncludeToolOutputRootFields(ev);
            String root = PlaybookEvidenceFormatter.formatIncludedToolOutputRootFields(node, toolOut);
            String projected = PlaybookEvidenceFormatter.projectTableEvidenceSummary(nodeId, node, ctx, toolOut);
            boolean hasTable = ev != null && ev.optJSONObject("table") != null;

            if (wantsRoot && root != null && !root.isBlank()) {
                String tablePart = projected;
                if ((tablePart == null || tablePart.isBlank()) && hasTable) {
                    tablePart = PlaybookEvidenceFormatter.formatEmptyTableProjectionNote(toolOut);
                }
                return PlaybookEvidenceFormatter.mergeRootFieldsAndTableBlock(root, tablePart);
            }
            if (projected != null && !projected.isBlank()) {
                return PlaybookEvidenceFormatter.mergeRootFieldsAndTableBlock(root, projected);
            }
            int rows = toolOut.optInt("rowCount", toolOut.optInt("totalCount", -1));
            if (rows >= 0) {
                return node.optString("tool", "tool") + " returned " + rows + " rows";
            }
        }
        return evSpecLabel(node);
    }

    private static String evSpecLabel(JSONObject node) {
        JSONObject ev = node.optJSONObject("evidence");
        return ev != null ? ev.optString("label", "ok") : "ok";
    }

    private static JSONObject parseToolJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw.trim());
        } catch (Exception e) {
            return new JSONObject().put("parseError", true).put("rawPreview", raw.length() > 200 ? raw.substring(0, 200) : raw);
        }
    }

    private static JSONArray itemsToArray(Object itemsObj) {
        if (itemsObj instanceof JSONArray) {
            return (JSONArray) itemsObj;
        }
        if (itemsObj instanceof List) {
            return new JSONArray((List<?>) itemsObj);
        }
        return new JSONArray();
    }
}
