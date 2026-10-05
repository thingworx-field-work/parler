package com.thingworx.things.agent.taskstate;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.evidence.EvidenceAssessment;
import com.thingworx.things.agent.evidence.EvidenceAssessmentAggregator;

/**
 * Compact Markdown for LLM injection (structural fields only; Goal carve-out per task-state.md).
 */
public final class AgentTaskStateRenderer {

    private AgentTaskStateRenderer() {}

    public static String render(AgentTaskState state) {
        if (state == null) {
            return "";
        }
        int maxGoal = AgentTaskState.DEFAULT_MAX_GOAL_CHARS;
        int maxRows = AgentTaskState.DEFAULT_MAX_EVIDENCE_ROWS;
        int maxBlock = AgentTaskState.DEFAULT_MAX_TASK_STATE_CHARS - TaskStateLlmInjector.FRAMING_PREFIX.length();

        StringBuilder sb = new StringBuilder();
        sb.append("## Recent Tool Evidence\n\n");
        String goal = state.getGoalCleanedText();
        if (goal != null && !goal.isEmpty()) {
            String g = goal.length() > maxGoal ? goal.substring(0, maxGoal) + "... truncated" : goal;
            sb.append("Goal: ").append(g).append("\n\n");
        }
        List<AgentTaskEvidence> rows = state.getEvidenceRows();
        List<AgentTaskEvidence> tail = rows;
        int dropped = 0;
        if (rows.size() > maxRows) {
            dropped = rows.size() - maxRows;
            tail = new ArrayList<>(rows.subList(rows.size() - maxRows, rows.size()));
        }
        int maxLine = AgentTaskState.DEFAULT_MAX_SUMMARY_CHARS;
        for (AgentTaskEvidence e : tail) {
            sb.append("- ").append(capLine(renderEvidenceLine(e), maxLine)).append("\n");
        }
        if (dropped > 0) {
            sb.append("\nOlder evidence omitted: ").append(dropped).append(" rows.\n");
        }
        EvidenceAssessment assessment = EvidenceAssessmentAggregator.fromTaskState(state);
        sb.append("\nEvidenceAssessment: status=").append(assessment.status().name())
                .append(" completeness=").append(assessment.completeness().name())
                .append(" n=").append(assessment.n()).append('\n');
        String out = sb.toString();
        if (out.length() > maxBlock) {
            String suffix = "\n... truncated";
            return out.substring(0, maxBlock - suffix.length()) + suffix;
        }
        return out;
    }

    static String capLine(String line, int maxChars) {
        if (line == null) {
            return "";
        }
        if (line.length() <= maxChars) {
            return line;
        }
        String suffix = "... truncated";
        int take = Math.max(0, maxChars - suffix.length());
        return line.substring(0, take) + suffix;
    }

    static String renderEvidenceLine(AgentTaskEvidence e) {
        StringBuilder l = new StringBuilder();
        l.append(e.getEvidenceId()).append(" ").append(e.getTool());
        if (e.getTargetType() != null) {
            l.append(" ").append(e.getTargetType());
        }
        if (e.getTargetName() != null) {
            l.append(" ").append(e.getTargetName());
        }
        if (e.getOperation() != null) {
            l.append(" ").append(e.getOperation());
        }
        l.append(": ").append(e.getStatus());
        if ("ok".equals(e.getStatus())) {
            if (e.getRowCount() >= 0) {
                l.append(", ").append(e.getRowCount()).append(" rows");
            }
            if (e.isSampleOnly()) {
                l.append(", sample-only");
            }
            if (e.getCacheId() != null && !e.getCacheId().isEmpty()) {
                l.append(", cacheId=").append(e.getCacheId());
            }
            if (e.isProtectedOmissions()) {
                l.append(", protected-omissions");
            }
        } else if ("error".equals(e.getStatus()) && e.getErrorCodeEnum() != null) {
            l.append(", ").append(e.getErrorCodeEnum().name());
        } else if ("blocked-by-approval".equals(e.getStatus())) {
            l.append(", awaiting approval");
        }
        return l.toString();
    }
}
