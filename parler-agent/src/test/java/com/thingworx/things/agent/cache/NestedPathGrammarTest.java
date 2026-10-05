package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NestedPathGrammarTest {

    @Test
    void parsesDottedKeysAndIndexes() throws Exception {
        List<NestedPathGrammar.Segment> segs = NestedPathGrammar.parse("[0].children[1].detail");
        assertEquals(4, segs.size());
        assertEquals(NestedPathGrammar.SegmentKind.INDEX, segs.get(0).kind());
        assertEquals(0, segs.get(0).index());
        assertEquals("children", segs.get(1).fieldName());
        assertEquals(1, segs.get(2).index());
        assertEquals("detail", segs.get(3).fieldName());
    }

    @Test
    void rejectsMaliciousAndWideForms() {
        assertRejects("a/b");
        assertRejects("a\\b");
        assertRejects("a..b");
        assertRejects("a*b");
        assertRejects("$['a']");
        assertRejects("a[?(@.x)]");
        assertRejects("a**b");
    }

    @Test
    void rejectsOverDeepPath() {
        StringBuilder sb = new StringBuilder("[0]");
        for (int i = 0; i < NestedPathGrammar.MAX_SEGMENTS; i++) {
            sb.append(".f").append(i);
        }
        NestedPathGrammar.ParseException ex = assertThrows(NestedPathGrammar.ParseException.class,
                () -> NestedPathGrammar.parse(sb.toString()));
        assertEquals("PATH_TOO_DEEP", ex.code());
    }

    @Test
    void rejectsIndexAboveMax() {
        NestedPathGrammar.ParseException ex = assertThrows(NestedPathGrammar.ParseException.class,
                () -> NestedPathGrammar.parse("[" + (NestedPathGrammar.MAX_INDEX + 1) + "].x"));
        assertEquals("INDEX_TOO_LARGE", ex.code());
    }

    private static void assertRejects(String path) {
        NestedPathGrammar.ParseException ex = assertThrows(NestedPathGrammar.ParseException.class,
                () -> NestedPathGrammar.parse(path));
        assertTrue(ex.code() != null && !ex.code().isEmpty(), path);
    }
}
