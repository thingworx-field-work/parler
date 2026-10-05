package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class HistoryRowLimitPrecedenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CAP = 5000;

    @Test
    void defaultWhenAbsent() {
        ObjectNode root = MAPPER.createObjectNode();
        assertEquals(1000, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
        assertEquals(1000, HistoryRowLimitPrecedence.resolveHistoryRowLimit(null, CAP));
        HistoryRowLimitPrecedence.Resolved r = HistoryRowLimitPrecedence.resolve(root, CAP);
        assertEquals("default", r.sourceField);
        assertEquals(1000, r.requested);
        assertEquals(1000, r.effective);
    }

    @Test
    void publishedMaxItemsWins() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 11);
        root.put("maxRows", 50);
        root.put("maxPoints", 200);
        assertEquals(11, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
        assertEquals("maxItems", HistoryRowLimitPrecedence.resolve(root, CAP).sourceField);
    }

    @Test
    void maxRowsAlone() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxRows", 42);
        assertEquals(42, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
    }

    @Test
    void maxPointsAlone() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxPoints", 77);
        assertEquals(77, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
    }

    @Test
    void maxRowsWinsOverMaxPoints() {
        // A value-stream overwrite would have taken maxPoints=200; the shared rule keeps maxRows=50.
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxRows", 50);
        root.put("maxPoints", 200);
        assertEquals(50, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
    }

    @Test
    void maxRowsWinsEvenWhenLowerThanMaxPoints() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxRows", 300);
        root.put("maxPoints", 10);
        assertEquals(300, HistoryRowLimitPrecedence.resolveHistoryRowLimit(root, CAP));
    }

    @Test
    void clampHighAndLow() {
        ObjectNode high = MAPPER.createObjectNode();
        high.put("maxItems", 99999);
        HistoryRowLimitPrecedence.Resolved hr = HistoryRowLimitPrecedence.resolve(high, CAP);
        assertEquals(99999, hr.requested);
        assertEquals(CAP, hr.effective);

        ObjectNode low = MAPPER.createObjectNode();
        low.put("maxPoints", 0);
        HistoryRowLimitPrecedence.Resolved lr = HistoryRowLimitPrecedence.resolve(low, CAP);
        assertEquals(0, lr.requested);
        assertEquals(1, lr.effective);
    }
}
