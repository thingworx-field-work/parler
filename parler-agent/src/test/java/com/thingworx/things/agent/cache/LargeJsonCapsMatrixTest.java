package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * M2 acceptance: cap-1 / cap / cap+1 matrices for the 65,536 invoke-classify and 500,000
 * Stream-persistence UTF-16 boundaries, with UTF-8 bytes reported separately.
 */
class LargeJsonCapsMatrixTest {

    @Test
    void capsMatchDocumentedBoundaries() {
        assertEquals(65_536, LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP);
        assertEquals(500_000, LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP);
    }

    @Test
    void invokeClassifyBoundaryMatrix_ascii() {
        int cap = LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP;
        assertBoundary(cap - 1, LargeJsonCaps.SizeClass.WITHIN_INVOKE_CAP, false, false);
        assertBoundary(cap, LargeJsonCaps.SizeClass.WITHIN_INVOKE_CAP, false, false);
        assertBoundary(cap + 1, LargeJsonCaps.SizeClass.LARGE_JSON, true, false);
    }

    @Test
    void streamPersistenceBoundaryMatrix_ascii() {
        int cap = LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP;
        assertBoundary(cap - 1, LargeJsonCaps.SizeClass.LARGE_JSON, true, false);
        assertBoundary(cap, LargeJsonCaps.SizeClass.LARGE_JSON, true, false);
        assertBoundary(cap + 1, LargeJsonCaps.SizeClass.OVER_STREAM_PERSISTENCE, true, true);
    }

    @Test
    void utf8BytesReportedSeparatelyFromUtf16_forMultibyte() {
        // Each emoji is one UTF-16 code unit pair (length 2) and 4 UTF-8 bytes.
        String emoji = "😀";
        assertEquals(2, emoji.length());
        assertEquals(4, emoji.getBytes(StandardCharsets.UTF_8).length);
        String payload = emoji.repeat(100);
        LargeJsonCaps.SizeReport r = LargeJsonCaps.classify(payload);
        assertEquals(200, r.utf16Chars());
        assertEquals(400, r.utf8Bytes());
        assertTrue(r.utf8Bytes() != r.utf16Chars(), "UTF-8 bytes must not be treated as UTF-16 chars");
    }

    private static void assertBoundary(int utf16Len, LargeJsonCaps.SizeClass expectedClass,
            boolean exceedsInvoke, boolean exceedsStream) {
        String payload = "a".repeat(utf16Len);
        LargeJsonCaps.SizeReport r = LargeJsonCaps.classify(payload);
        assertEquals(utf16Len, r.utf16Chars());
        assertEquals(utf16Len, r.utf8Bytes(), "ASCII fixture: UTF-8 bytes equal UTF-16 length");
        assertEquals(expectedClass, r.sizeClass());
        assertEquals(exceedsInvoke, r.exceedsInvokeClassifyCap());
        assertEquals(exceedsStream, r.exceedsStreamPersistenceCap());
        if (exceedsInvoke) {
            assertTrue(r.utf16Chars() > LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP);
        } else {
            assertFalse(r.utf16Chars() > LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP);
        }
    }
}
