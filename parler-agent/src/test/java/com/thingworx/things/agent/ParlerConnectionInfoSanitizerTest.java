package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ParlerConnectionInfoSanitizerTest {

    @Test
    void sanitizeTrimsAndStripsControls() {
        assertEquals("0.1.77", ParlerConnectionInfoSanitizer.sanitizeWidgetEcho("  0.1.77  "));
        assertEquals("a b", ParlerConnectionInfoSanitizer.sanitizeWidgetEcho("a\n\rb"));
    }

    @Test
    void sanitizeCapsLength() {
        String longIn = "x".repeat(200);
        assertEquals(ParlerConnectionInfoSanitizer.MAX_WIDGET_ECHO_LEN,
                ParlerConnectionInfoSanitizer.sanitizeWidgetEcho(longIn).length());
    }
}
