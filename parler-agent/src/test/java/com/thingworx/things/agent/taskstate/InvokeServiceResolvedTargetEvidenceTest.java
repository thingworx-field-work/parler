package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

/**
 * {@link com.thingworx.things.agent.AgentThing#executeToolCall} must call
 * {@link com.thingworx.things.agent.taskstate.AgentTaskStateHooks#beforeExecution} only after
 * {@link com.thingworx.things.agent.tools.ServiceTargetEntityTypeResolver#prepareInvokeServiceToolCall}, so evidence
 * {@code targetType}/{@code targetName}/{@code operation} match resolver-corrected arguments (e.g. GenericThing
 * template {@code DataTable} → root {@code Thing}).
 *
 * <p>Calling {@code prepareInvokeServiceToolCall} in plain {@code test} tasks pulls in {@code EntityUtilities} static
 * init (ThingWorx security / logging) and is not classpath-stable here; this test locks the ledger contract: the row
 * mirrors whatever {@link ToolCall} is passed to {@link AgentTaskState#beginTrackedTool}.
 */
class InvokeServiceResolvedTargetEvidenceTest {

    @Test
    void begin_tracked_tool_reflects_entity_type_from_tool_call_arguments() {
        String preCorrection =
                "{\"entityType\":\"DataTable\",\"entityName\":\"E1\",\"serviceName\":\"S1\",\"parameters\":{}}";
        ToolCall pre = new ToolCall("same-id", "invoke_service", preCorrection);
        AgentTaskState beforeResolver = new AgentTaskState("r", "c", "");
        beforeResolver.beginTrackedTool(pre, "invoke_service");
        assertEquals("DataTable", beforeResolver.getEvidenceRows().get(0).getTargetType());

        String postCorrection =
                "{\"entityType\":\"Thing\",\"entityName\":\"E1\",\"serviceName\":\"S1\",\"parameters\":{}}";
        ToolCall post = new ToolCall("same-id", "invoke_service", postCorrection);
        AgentTaskState afterResolver = new AgentTaskState("r", "c", "");
        afterResolver.beginTrackedTool(post, "invoke_service");
        assertEquals("Thing", afterResolver.getEvidenceRows().get(0).getTargetType());
        assertEquals("E1", afterResolver.getEvidenceRows().get(0).getTargetName());
        assertEquals("S1", afterResolver.getEvidenceRows().get(0).getOperation());
    }
}
