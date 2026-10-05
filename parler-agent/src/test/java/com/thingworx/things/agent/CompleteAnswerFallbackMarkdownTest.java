package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;

class CompleteAnswerFallbackMarkdownTest {

    @Test
    void empty_messages_null_placeholder() {
        String md = CompleteAnswerFallbackMarkdown.fromMessages(null);
        assertTrue(md.contains("No conversation context"));
    }

    @Test
    void picks_newest_tool_message_with_rows() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.toolResult("old", "{\"status\":\"success\",\"rows\":[{\"a\":1}]}"));
        msgs.add(ChatMessage.toolResult("new", "{\"status\":\"success\",\"rows\":[{\"b\":2}]}"));
        String md = CompleteAnswerFallbackMarkdown.fromMessages(msgs);
        assertTrue(md.contains("**1** row"));
        assertTrue(md.contains("2"));
    }

    @Test
    void more_than_twenty_five_rows_footer() {
        StringBuilder rows = new StringBuilder("\"rows\":[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"k\":").append(i).append('}');
        }
        rows.append(']');
        String body = "{\"status\":\"success\"," + rows + "}";
        List<ChatMessage> msgs = Collections.singletonList(ChatMessage.toolResult("t1", body));
        String md = CompleteAnswerFallbackMarkdown.fromMessages(msgs);
        assertTrue(md.contains("25"));
        assertTrue(md.contains("additional rows omitted"));
    }
}
