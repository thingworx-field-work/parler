package com.thingworx.things.agent;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.json.JSONObject;

import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ChartGroupState;

/**
 * Shared AlwaysOn chart / table / {@code tabular.tool_success} emission for one resolved UI tool message (top-level
 * chat tools and Playbook-internal tools). Extracted so {@link AgentThing} stream sinks stay identical.
 */
public final class ParlerToolArtifactWireEmitter {

    private ParlerToolArtifactWireEmitter() {}

    /**
     * Emits pending charts, numeric-history charts, table downlinks, tabular success, and optional activity — same
     * ordering as the historical {@link AgentThing} {@code streamSink} TOOL branch.
     */
    public static void emitAfterResolvedToolUi(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage mUi,
            AtomicBoolean downlinkOk,
            ChatMessage toolMessageForActivityPreview) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        sendPendingParlerChartWires(remoteConversation, wireRequestId, wireConversationId, downlinkOk);
        String bodyForWire = mUi.getContent() != null ? mUi.getContent() : "";
        Optional<JSONObject> chartOpt = ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(bodyForWire);
        if (chartOpt.isPresent()) {
            sendParlerChartWireIfPresent(remoteConversation, wireRequestId, wireConversationId, chartOpt.get(),
                    downlinkOk);
        }
        maybeSendTaxonomyTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendListEntitiesTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendQueryEntitiesTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendInvokeServiceInfotableTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi,
                downlinkOk);
        maybeSendFetchCachedResultTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendAnalyzeEntitySetTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendTabulateTableDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        maybeSendTabularToolSuccessDownlink(remoteConversation, wireRequestId, wireConversationId, mUi, downlinkOk);
        if (toolMessageForActivityPreview == null) {
            return;
        }
        String compactPreview = toolMessageForActivityPreview.getContent() != null
                ? toolMessageForActivityPreview.getContent()
                : "";
        if (downlinkOk.get() && !ParlerReceiveMessageSupport.send(remoteConversation,
                ParlerReceiveMessageSupport.wireActivity(wireRequestId, wireConversationId,
                        "Tool result (" + trunc(toolMessageForActivityPreview.getToolCallId(), 32) + "): "
                                + trunc(compactPreview, 400)))) {
            downlinkOk.set(false);
        }
    }

    /** The production sender: one JSON frame to the remote conversation; {@code null} without a remote. */
    static Predicate<String> senderFor(Thing remoteConversation) {
        return remoteConversation == null ? null : json -> ParlerReceiveMessageSupport.send(remoteConversation, json);
    }

    static void sendPendingParlerChartWires(Thing remoteConversation, String requestId, String conversationId,
            AtomicBoolean downlinkOk) {
        sendPendingParlerChartWires(senderFor(remoteConversation), requestId, conversationId, downlinkOk);
    }

    /**
     * C3b-1 / C3b-2a (design §8.5, §8.7): a dirty group (declaration, failed member, or a member's newly appended
     * shared-colour keys) goes out as one manifest revision before the pending charts, so a chart never draws
     * before the mapping it uses; each member's {@code ready} revision goes out right after its chart frame. The
     * {@code sender} seam lets tests record the frame order without a remote Thing.
     */
    static void sendPendingParlerChartWires(Predicate<String> sender, String requestId, String conversationId,
            AtomicBoolean downlinkOk) {
        sendChartGroupManifestIfDirty(sender, requestId, conversationId, downlinkOk);
        for (JSONObject chart : AgentToolContext.drainPendingParlerChartBlocks()) {
            sendParlerChartWireIfPresent(sender, requestId, conversationId, chart, downlinkOk);
        }
    }

    static void sendChartGroupManifestIfDirty(Thing remoteConversation, String requestId, String conversationId,
            AtomicBoolean downlinkOk) {
        sendChartGroupManifestIfDirty(senderFor(remoteConversation), requestId, conversationId, downlinkOk);
    }

    /** Downlinks the next full manifest revision when the request's chart group changed since the last one. */
    static void sendChartGroupManifestIfDirty(Predicate<String> sender, String requestId, String conversationId,
            AtomicBoolean downlinkOk) {
        ChartGroupState group = AgentToolContext.tabularChartRoundState().getChartGroup();
        if (group == null || !group.isDirty() || sender == null || !downlinkOk.get()) {
            return;
        }
        JSONObject manifest = group.nextManifest(false);
        if (!sender.test(ParlerReceiveMessageSupport.wireChartGroup(requestId, conversationId, manifest))) {
            downlinkOk.set(false);
        }
    }

    static void sendParlerChartWireIfPresent(Thing remoteConversation, String requestId, String conversationId,
            JSONObject chartOrNull, AtomicBoolean downlinkOk) {
        sendParlerChartWireIfPresent(senderFor(remoteConversation), requestId, conversationId, chartOrNull, downlinkOk);
    }

    static void sendParlerChartWireIfPresent(Predicate<String> sender, String requestId, String conversationId,
            JSONObject chartOrNull, AtomicBoolean downlinkOk) {
        if (chartOrNull == null || sender == null || !downlinkOk.get()) {
            return;
        }
        String chartId = chartOrNull.optString("chartId", null);
        ChartGroupState group = AgentToolContext.tabularChartRoundState().getChartGroup();
        if (!sender.test(ParlerReceiveMessageSupport.wireChart(requestId, conversationId, chartOrNull))) {
            downlinkOk.set(false);
            if (group != null) {
                group.onChartDownlinkFailed(chartId);
            }
            return;
        }
        JSONObject chartSource = chartOrNull.optJSONObject("source");
        AgentToolContext.markParlerChartWireEmitted(
                chartSource != null ? chartSource.optString("sourceCacheId", null) : null);
        if (group != null && group.onChartDownlinked(chartId)) {
            sendChartGroupManifestIfDirty(sender, requestId, conversationId, downlinkOk);
        }
    }

    private static void sendWireTableWithOptionalFileExport(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            JSONObject table,
            String toolBodyJson,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null || table == null) {
            return;
        }
        ParlerTableExportSidecar.mergeToolRootSidecarIntoTable(toolBodyJson, table);
        ParlerTableFileExportHook.apply(remoteConversation, wireRequestId, wireConversationId, table);
        if (!ParlerReceiveMessageSupport.send(remoteConversation,
                ParlerReceiveMessageSupport.wireTable(wireRequestId, wireConversationId, table))) {
            downlinkOk.set(false);
        }
    }

    private static void maybeSendFetchCachedResultTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerFetchCachedResultTableWire.tableBlockFromFetchCachedResultJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendInvokeServiceInfotableTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        String etn = toolMessage.getExecutedToolName();
        JSONObject table = etn == null || etn.isEmpty()
                ? ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(body)
                : ParlerInvokeServiceInfotableTableWire.tableBlockFromInvokeServiceInfotableJson(body, etn);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendQueryEntitiesTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerQueryEntitiesEntityListTableWire.tableBlockFromQueryEntitiesToolSuccessJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendListEntitiesTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerListEntitiesEntityListTableWire.tableBlockFromListEntitiesToolSuccessJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendTaxonomyTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerTaxonomyEntityListTableWire.tableBlockFromTaxonomyToolSuccessJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendTabulateTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerTabulateEntityListTableWire.tableBlockFromTabulateToolSuccessJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendAnalyzeEntitySetTableDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject table = ParlerAnalyzeEntitySetEntityListTableWire.tableBlockFromAnalyzeEntitySetToolSuccessJson(body);
        if (table == null) {
            return;
        }
        sendWireTableWithOptionalFileExport(remoteConversation, wireRequestId, wireConversationId, table, body,
                downlinkOk);
    }

    private static void maybeSendTabularToolSuccessDownlink(
            Thing remoteConversation,
            String wireRequestId,
            String wireConversationId,
            ChatMessage toolMessage,
            AtomicBoolean downlinkOk) {
        if (!downlinkOk.get() || remoteConversation == null) {
            return;
        }
        String body = toolMessage.getContent() != null ? toolMessage.getContent() : "";
        JSONObject compact;
        try {
            JSONObject root = new JSONObject(body);
            compact = ParlerTabularToolSuccessWire.compactPayload(root);
        } catch (Exception e) {
            return;
        }
        if (compact == null) {
            return;
        }
        if (!ParlerReceiveMessageSupport.send(remoteConversation,
                ParlerTabularToolSuccessWire.toWireJson(wireRequestId, wireConversationId, compact))) {
            downlinkOk.set(false);
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
