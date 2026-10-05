package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParlerHostScopeLogFormatterTest {

    @Test
    void discard_warn_suffix_orders_stable_keys() {
        RejectReason rr = RejectReason.structured(null, "unknown_template_key", "foo", null,
                "unknown_template_key=foo");
        String s = ParlerHostScopeLogFormatter.discardWarnSuffix(rr, "tail");
        assertTrue(s.startsWith("hostScopeField="));
        assertTrue(s.contains(" hostScopeCode=unknown_template_key"));
        assertTrue(s.contains(" hostScopeObserved=foo"));
        assertTrue(s.contains(" hostScopeLimit="));
        assertTrue(s.endsWith("detail=tail"));
    }

    @Test
    void discard_warn_suffix_without_reason_is_detail_only() {
        assertEquals("detail=x", ParlerHostScopeLogFormatter.discardWarnSuffix(null, "x"));
        assertEquals("detail=", ParlerHostScopeLogFormatter.discardWarnSuffix(null, null));
    }

    @Test
    void accepted_normalization_suffix_with_reason() {
        RejectReason rr = RejectReason.structured("key", "unknown_template_key", "demo", null,
                "unknown_template_key=demo");
        String s = ParlerHostScopeLogFormatter.acceptedNormalizationSuffix(rr, "dropped");
        assertTrue(s.contains("hostScopeField="));
        assertTrue(s.contains("hostScopeCode=unknown_template_key"));
        assertTrue(s.contains(" detail=dropped"));
    }

    @Test
    void accepted_normalization_suffix_without_reason() {
        assertEquals("detail=note", ParlerHostScopeLogFormatter.acceptedNormalizationSuffix(null, "note"));
    }
}
