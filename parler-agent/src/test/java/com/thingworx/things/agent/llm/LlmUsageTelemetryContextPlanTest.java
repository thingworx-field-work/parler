package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class LlmUsageTelemetryContextPlanTest {

    @Test
    void formatLlmContextPlanLine_emitsFixedFieldNamesInOrder() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("Prov", "Tpl", "openai-chat-completions-v4", "gpt-4o");
        String line = LlmUsageTelemetry.formatLlmContextPlanLine(
                ids,
                "cid-1",
                "rid-2",
                3,
                5,
                10,
                20,
                30,
                40,
                50,
                60,
                70,
                80,
                0,
                0,
                0,
                750000,
                448000,
                -100,
                1,
                0,
                90);
        assertTrue(line.startsWith("LLM_CONTEXT_PLAN "));
        assertTrue(line.contains("providerThingName=Prov"));
        assertTrue(line.contains(" provider=openai-chat-completions-v4"));
        assertTrue(line.contains(" conversationId=cid-1"));
        assertTrue(line.contains(" requestId=rid-2"));
        assertTrue(line.contains(" historyBudgetChars=-100"));
        assertTrue(line.contains(" historyClampedToZero=1"));
        assertTrue(line.contains(" unsafeDisable=0"));
        // §10.1: checkpointChars is APPENDED, never inserted, so downstream parsing stays positionally stable.
        assertTrue(line.contains(" checkpointChars=90"), line);
        assertTrue(line.indexOf(" checkpointChars=") > line.indexOf(" unsafeDisable="),
                "the new component must follow every pre-existing field: " + line);
        assertTrue(line.endsWith(" checkpointChars=90"), line);
        assertTrue(!line.contains("rounds="), "LLM_CONTEXT_PLAN v1 field set must not include rounds=");
    }

    @Test
    void formatLlmContextPlanFailLine_emitsReasonAndSameFieldTailAsPlan() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("Prov", "Tpl", "openai-chat-completions-v4", "gpt-4o");
        String fail = LlmUsageTelemetry.formatLlmContextPlanFailLine(
                "OVERHEAD_EXCEEDS_CAP",
                ids,
                "cid-1",
                "rid-2",
                3,
                5,
                10,
                20,
                30,
                40,
                50,
                60,
                70,
                80,
                0,
                0,
                0,
                750000,
                448000,
                -100,
                1,
                0,
                90);
        assertTrue(fail.startsWith("LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP "));
        assertTrue(fail.contains("providerThingName=Prov"));
        assertTrue(fail.contains(" historyBudgetChars=-100"));
        String plan = LlmUsageTelemetry.formatLlmContextPlanLine(
                ids,
                "cid-1",
                "rid-2",
                3,
                5,
                10,
                20,
                30,
                40,
                50,
                60,
                70,
                80,
                0,
                0,
                0,
                750000,
                448000,
                -100,
                1,
                0,
                90);
        String tailPlan = plan.substring("LLM_CONTEXT_PLAN ".length());
        String tailFail = fail.substring("LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP ".length());
        assertEquals(tailPlan, tailFail);
    }

    @Test
    void parlerRequestId_joins_context_plan_requestId_and_usage_parlerRequestId() {
        String parlerRid = "parler-turn-abc";
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("Prov", "Tpl", "openai-chat-completions-v4", "gpt-4o");
        String planLine = LlmUsageTelemetry.formatLlmContextPlanLine(
                ids,
                "cid",
                parlerRid,
                3,
                2,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                0,
                0,
                0,
                100,
                100,
                0,
                0,
                0,
                0);
        assertTrue(planLine.contains(" requestId=" + parlerRid), planLine);

        LlmResponse response = new LlmResponse("", Collections.emptyList(), LlmResponse.FinishReason.STOP, 1, 1, 1, 1, 0,
                0, 0, "provider-req-xyz");
        String usageLine = LlmUsageTelemetry.formatLlmUsageLine(ids, response, 2, 1, 1, null, null, null, parlerRid);
        assertTrue(usageLine.contains(" requestId=provider-req-xyz"), usageLine);
        assertTrue(usageLine.contains(" parlerRequestId=" + parlerRid), usageLine);
    }
}
