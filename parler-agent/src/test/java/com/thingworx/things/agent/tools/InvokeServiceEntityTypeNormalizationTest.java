package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.llm.ToolCall;

class InvokeServiceEntityTypeNormalizationTest {

    @Test
    void applyUsesBoundWhenResolvedThingIsNotMarkedNormalized() {
        ToolCall call = new ToolCall("tc-1", "invoke_service", "{}");
        ServiceTargetEntityTypeResolution base = ServiceTargetEntityTypeResolution.ok(
                RelationshipTypes.ThingworxRelationshipTypes.Thing, "Thing", false, null, null);
        ServiceTargetEntityTypeResolution bound = ServiceTargetEntityTypeResolution.ok(
                RelationshipTypes.ThingworxRelationshipTypes.Thing, "DataTable", true,
                ServiceTargetEntityTypeResolver.REASON_GENERIC_THING_TEMPLATE_NAME, "DataTable");
        InvokeServiceEntityTypeNormalization.bind("tc-1", bound);
        try {
            ServiceTargetEntityTypeResolution out = InvokeServiceEntityTypeNormalization.applyIfAny(base, call);
            assertTrue(out.isNormalized());
            assertEquals("DataTable", out.getOriginalEntityTypeName());
        } finally {
            InvokeServiceEntityTypeNormalization.unbind();
        }
    }

    @Test
    void applyIgnoresMismatchedToolCallId() {
        ToolCall call = new ToolCall("other", "invoke_service", "{}");
        ServiceTargetEntityTypeResolution base = ServiceTargetEntityTypeResolution.ok(
                RelationshipTypes.ThingworxRelationshipTypes.Thing, "Thing", false, null, null);
        ServiceTargetEntityTypeResolution bound = ServiceTargetEntityTypeResolution.ok(
                RelationshipTypes.ThingworxRelationshipTypes.Thing, "DataTable", true,
                ServiceTargetEntityTypeResolver.REASON_GENERIC_THING_TEMPLATE_NAME, "DataTable");
        InvokeServiceEntityTypeNormalization.bind("tc-1", bound);
        try {
            ServiceTargetEntityTypeResolution out = InvokeServiceEntityTypeNormalization.applyIfAny(base, call);
            assertFalse(out.isNormalized());
        } finally {
            InvokeServiceEntityTypeNormalization.unbind();
        }
    }
}
