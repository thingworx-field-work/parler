package com.thingworx.things.agent.taskstate;

import java.util.Set;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * v1b.2 mid-turn dynamic skill checklist merge after successful {@code get_agent_skill}.
 *
 * <p>Normative: {@code docs/agent/task-state.md} § v1b.2.
 */
public final class TaskProgressV1bDynamicMerge {

    private static final Logger LOG = Logger.getLogger(TaskProgressV1bDynamicMerge.class.getName());

    private TaskProgressV1bDynamicMerge() {}

    /**
     * Runs after {@link TaskProgressV1bHooks#afterTrackedTool}; no-op unless context and v1b progress exist.
     */
    public static void afterGetAgentSkill(ToolCall toolCall, String toolResult) {
        if (AgentToolContext.isPlaybookTaskProgressActive()) {
            return;
        }
        if (toolCall == null) {
            return;
        }
        if (!"get_agent_skill".equals(toolCall.getFunctionName())) {
            return;
        }
        if (NoEvidenceToolOutcome.isExplicitJsonErrorEnvelope(toolResult)) {
            return;
        }
        AgentTaskState st = AgentToolContext.getAgentTaskState();
        if (st == null) {
            return;
        }
        TaskProgressV1b v = st.getTaskProgressV1b();
        if (v == null) {
            return;
        }
        String logName = agentNameForLog();

        String shortId = normalizedSkillNameArgument(toolCall);
        if (shortId == null) {
            return;
        }

        if (st.getDynamicSkillShortNamesInOrder().contains(shortId)) {
            LOG.log(Level.INFO, "[{0}] v1b.2 DYNAMIC_SKILL_ALREADY_MERGED skill={1}", new Object[] {logName, shortId});
            return;
        }

        String body = toolResult != null ? toolResult : "";
        int fences = SkillChecklistParser.countChecklistFences(body);
        if (fences == 0) {
            LOG.log(Level.INFO, "[{0}] v1b.2 DYNAMIC_SKILL_NO_CHECKLIST skill={1}", new Object[] {logName, shortId});
            return;
        }
        if (fences > 1) {
            LOG.log(Level.WARNING, "[{0}] v1b.2 DYNAMIC_SKILL_CHECKLIST_INVALID skill={1} (multiple fences)",
                    new Object[] {logName, shortId});
            v.patchLatestAdHocToolSummary("get_agent_skill", "DYNAMIC_SKILL_CHECKLIST_INVALID");
            TaskProgressWireEmitter.flushSnapshot(st);
            return;
        }

        JSONObject parsed;
        try {
            parsed = SkillChecklistParser.parseExactlyOneChecklistFence(body);
        } catch (SkillChecklistParseException e) {
            LOG.log(Level.WARNING, "[{0}] v1b.2 DYNAMIC_SKILL_CHECKLIST_INVALID skill={1}: {2}",
                    new Object[] {logName, shortId, e.getMessage()});
            v.patchLatestAdHocToolSummary("get_agent_skill", "DYNAMIC_SKILL_CHECKLIST_INVALID");
            TaskProgressWireEmitter.flushSnapshot(st);
            return;
        }

        JSONArray req = parsed.getJSONArray("requiredEvidence");
        Set<String> existing = v.collectSkillEvidenceIds();
        for (int j = 0; j < req.length(); j++) {
            String id = req.getJSONObject(j).getString("id");
            if (existing.contains(id)) {
                LOG.log(Level.WARNING, "[{0}] v1b.2 DYNAMIC_SKILL_DUPLICATE_ID skill={1} id={2}",
                        new Object[] {logName, shortId, id});
                v.patchLatestAdHocToolSummary("get_agent_skill", "DYNAMIC_SKILL_DUPLICATE_ID");
                TaskProgressWireEmitter.flushSnapshot(st);
                return;
            }
        }
        if (existing.size() + req.length() > SkillChecklistParser.MAX_SKILL_ITEMS) {
            LOG.log(Level.WARNING, "[{0}] v1b.2 DYNAMIC_SKILL_BUDGET_EXCEEDED skill={1} (would total {2})",
                    new Object[] {logName, shortId, existing.size() + req.length()});
            v.patchLatestAdHocToolSummary("get_agent_skill", "DYNAMIC_SKILL_BUDGET_EXCEEDED");
            TaskProgressWireEmitter.flushSnapshot(st);
            return;
        }

        v.appendSkillRowsFromDynamicChecklist(parsed);
        st.recordMergedDynamicSkillShortName(shortId);
        TaskProgressWireEmitter.flushSnapshot(st);
    }

    private static String agentNameForLog() {
        try {
            com.thingworx.things.agent.AgentThing ag = AgentToolContext.getAgentThing();
            return ag != null ? ag.getName() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    static String normalizedSkillNameArgument(ToolCall toolCall) {
        if (toolCall == null) {
            return null;
        }
        String raw = toolCall.getArguments();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            String sn = o.optString("skill_name", "").trim();
            return sn.isEmpty() ? null : sn;
        } catch (Exception e) {
            return null;
        }
    }
}
