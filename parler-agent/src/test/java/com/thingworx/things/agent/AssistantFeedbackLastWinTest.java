package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AssistantFeedbackLastWinTest {

    private static String fb(String aid, String rating) {
        return "{\"type\":\"assistant_feedback\",\"assistantMessageId\":\"" + aid + "\",\"rating\":\"" + rating + "\"}";
    }

    @Test
    void lastWin_sameAssistantId_laterRatingWins() {
        Map<String, String> m = AssistantFeedbackLastWin.lastWinRatingsChronological(Arrays.asList(
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", fb("a1", "down")),
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", fb("a1", "up"))));
        assertEquals("up", m.get("a1"));
    }

    @Test
    void lastWin_skipsNonAssistantFeedbackType() {
        Map<String, String> m = AssistantFeedbackLastWin.lastWinRatingsChronological(List.of(
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", "{\"type\":\"other\",\"assistantMessageId\":\"x\",\"rating\":\"up\"}")));
        assertTrue(m.isEmpty());
    }

    @Test
    void lastWin_skipsInvalidRating() {
        Map<String, String> m = AssistantFeedbackLastWin.lastWinRatingsChronological(List.of(
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", fb("a1", "maybe"))));
        assertTrue(m.isEmpty());
    }

    @Test
    void lastWin_skipsEmptyAssistantId() {
        Map<String, String> m = AssistantFeedbackLastWin.lastWinRatingsChronological(List.of(
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", "{\"type\":\"assistant_feedback\",\"assistantMessageId\":\"\",\"rating\":\"up\"}")));
        assertTrue(m.isEmpty());
    }

    @Test
    void lastWin_ignoresNonUiFeedbackRoles() {
        Map<String, String> m = AssistantFeedbackLastWin.lastWinRatingsChronological(Arrays.asList(
                new AssistantFeedbackLastWin.RoleContentRow("assistant", "hello"),
                new AssistantFeedbackLastWin.RoleContentRow("ui_feedback", fb("a1", "down"))));
        assertEquals("down", m.get("a1"));
        assertEquals(1, m.size());
    }
}
