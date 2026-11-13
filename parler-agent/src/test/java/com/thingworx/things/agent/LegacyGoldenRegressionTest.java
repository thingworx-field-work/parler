package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.tools.ResultFixtures;

/**
 * P41 (topic round 6): the legacy goldens of step 0 differ only where the expectation matrix (design section 5A)
 * says, for the slices implemented so far (S1 and S2). Observes the gateway output (the first model append) at the
 * default budget.
 */
class LegacyGoldenRegressionTest {

    /** Byte-identical to the golden. */
    static final Set<String> UNCHANGED = Set.of("r-a-node", "r-a0-string", "r-a0-node", "r-b-string", "r-f", "r-g");
    /** Cut at baseline, whole (the raw result byte for byte) now. */
    static final Set<String> NOW_WHOLE = Set.of("r-a-string", "r-e", "page-0001");
    /** The golden plus {@code completenessNote}, nothing else. */
    static final Set<String> PLUS_NOTE = Set.of("r-b-node", "r-d");
    /** Reduced differently (whole rows, or pass 1 at the effective text cap), within the cap, with the note. */
    static final Set<String> REDUCED_WITH_NOTE = Set.of("r-c", "r-h", "page-0002");
    /** The budgeted overflow response, within the cap. */
    static final Set<String> OVERFLOW = Set.of("r-i", "r-j");

    static String legacy(String name) {
        try (InputStream in = LegacyGoldenRegressionTest.class.getResourceAsStream(
                "/tool-result/legacy/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    @Test
    void goldens_differ_only_where_the_matrix_says() {
        Map<String, String> inputs = LegacyGoldenCaptureTest.inputs();
        Set<String> classified = new HashSet<>();
        for (Set<String> s : java.util.List.of(UNCHANGED, NOW_WHOLE, PLUS_NOTE, REDUCED_WITH_NOTE, OVERFLOW)) {
            for (String k : s) {
                assertTrue(classified.add(k), "classified once: " + k);
            }
        }
        assertEquals(inputs.keySet(), classified, "every fixture is classified");
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            String key = e.getKey();
            String out = ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "call-1", e.getValue(), null)
                    .getLlmContent();
            String golden = legacy(key + ".gateway");
            if (UNCHANGED.contains(key)) {
                assertEquals(golden, out, key);
                continue;
            }
            if (NOW_WHOLE.contains(key)) {
                assertTrue(!golden.equals(e.getValue()), key + ": cut at baseline");
                assertEquals(e.getValue(), out, key);
                continue;
            }
            JsonNode n = ResultFixtures.read(out);
            assertTrue(n.has("completenessNote"), key);
            if (key.startsWith("r-") && !key.equals("r-f") && !key.equals("r-g")) {
                assertTrue(out.length() <= 8_192, key + " within the cap: " + out.length());
            }
            if (PLUS_NOTE.contains(key)) {
                ObjectNode without = (ObjectNode) n.deepCopy();
                without.remove("completenessNote");
                assertEquals(ResultFixtures.read(golden), without, key);
            } else if (REDUCED_WITH_NOTE.contains(key)) {
                assertFalse(golden.equals(out), key);
                assertTrue(out.length() <= 8_192, key);
            } else {
                assertEquals("RESULT_OVERFLOW", n.path("resultKind").asText(), key);
            }
        }
    }
}
