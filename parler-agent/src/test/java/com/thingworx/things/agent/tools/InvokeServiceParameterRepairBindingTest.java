package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import org.junit.jupiter.api.Test;

/**
 * Pre-HITL parameter repair survives executor merge when the executor sees already-repaired args.
 */
class InvokeServiceParameterRepairBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void applyUsesBoundRepairWhenExecutorSideMergeIsEmpty() throws Exception {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("maxItems", "", BaseTypes.INTEGER));

        ObjectNode unhoisted = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\",\"maxItems\":3}");
        InvokeServiceParameterNormalizer.Repair bound =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(unhoisted, defs, MAPPER);

        ObjectNode repaired = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\",\"parameters\":{\"maxItems\":3}}");
        InvokeServiceParameterNormalizer.Repair empty =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(repaired, defs, MAPPER);

        ToolCall call = new ToolCall(
                "tc-hoist",
                "invoke_service",
                MAPPER.writeValueAsString(repaired));

        assertTrue(bound.isRepaired());
        assertTrue(empty.isEmpty());

        InvokeServiceParameterRepairBinding.bind("tc-hoist", bound);
        try {
            InvokeServiceParameterNormalizer.Repair out =
                    InvokeServiceParameterRepairBinding.applyIfAny(empty, call);
            assertTrue(out.isRepaired());
            assertEquals(1, out.getMovedFromTopLevel().size());
            assertEquals("maxItems", out.getMovedFromTopLevel().get(0));
        } finally {
            InvokeServiceParameterRepairBinding.unbind();
        }
    }

    @Test
    void applyIgnoresMismatchedToolCallId() throws Exception {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("maxItems", "", BaseTypes.INTEGER));
        ObjectNode unhoisted = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\",\"maxItems\":3}");
        InvokeServiceParameterNormalizer.Repair bound =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(unhoisted, defs, MAPPER);

        ObjectNode repaired = (ObjectNode) MAPPER.readTree(
                "{\"entityType\":\"Thing\",\"entityName\":\"X\",\"serviceName\":\"S\",\"parameters\":{\"maxItems\":3}}");
        InvokeServiceParameterNormalizer.Repair empty =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(repaired, defs, MAPPER);

        ToolCall call = new ToolCall("other-id", "invoke_service", MAPPER.writeValueAsString(repaired));
        InvokeServiceParameterRepairBinding.bind("tc-hoist", bound);
        try {
            InvokeServiceParameterNormalizer.Repair out =
                    InvokeServiceParameterRepairBinding.applyIfAny(empty, call);
            assertTrue(out.isEmpty());
        } finally {
            InvokeServiceParameterRepairBinding.unbind();
        }
    }

    @Test
    void prefersExecutorRepairWhenItHoistedFields() throws Exception {
        FieldDefinitionCollection defs = new FieldDefinitionCollection();
        defs.addFieldDefinition(new FieldDefinition("maxItems", "", BaseTypes.INTEGER));

        ObjectNode topOnly = (ObjectNode) MAPPER.readTree("{\"maxItems\":5}");
        InvokeServiceParameterNormalizer.Repair executorSide =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(topOnly, defs, MAPPER);
        assertTrue(executorSide.isRepaired());

        ObjectNode otherRoot = (ObjectNode) MAPPER.readTree("{\"maxItems\":9}");
        InvokeServiceParameterNormalizer.Repair bound =
                InvokeServiceParameterNormalizer.mergeTopLevelFromParameterDefs(otherRoot, defs, MAPPER);
        assertTrue(bound.isRepaired());

        ToolCall call = new ToolCall("same", "invoke_service", "{}");
        InvokeServiceParameterRepairBinding.bind("same", bound);
        try {
            InvokeServiceParameterNormalizer.Repair out =
                    InvokeServiceParameterRepairBinding.applyIfAny(executorSide, call);
            assertEquals(5, topOnly.path("parameters").path("maxItems").asInt());
            assertEquals(executorSide.getMovedFromTopLevel(), out.getMovedFromTopLevel());
            assertTrue(out.isRepaired());
        } finally {
            InvokeServiceParameterRepairBinding.unbind();
        }
    }
}
