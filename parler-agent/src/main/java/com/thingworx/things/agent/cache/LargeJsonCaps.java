package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * U2 M2 large-JSON / Stream size classification. Caps are measured in Java
 * {@link String#length()} UTF-16 code units; UTF-8 bytes are reported separately and MUST NOT be
 * treated as interchangeable with the UTF-16 caps.
 *
 * <p>The Stream persistence cap is a persistence/truncation boundary only — not a replay-admission
 * threshold. Above-cap packaging remains outside LLM replay.
 */
public final class LargeJsonCaps {

    /** Invoke-result LARGE classification boundary ({@code invoke_service} / E6). */
    public static final int INVOKE_RESULT_CLASSIFY_CHAR_CAP = 65_536;

    /** {@link com.thingworx.things.agent.AgentMessageStreamAppender} content truncation boundary. */
    public static final int STREAM_PERSISTENCE_CHAR_CAP = 500_000;

    private LargeJsonCaps() {}

    public enum SizeClass {
        /** {@code utf16Chars <= INVOKE_RESULT_CLASSIFY_CHAR_CAP}. */
        WITHIN_INVOKE_CAP,
        /**
         * {@code INVOKE_RESULT_CLASSIFY_CHAR_CAP < utf16Chars <= STREAM_PERSISTENCE_CHAR_CAP}.
         * Classifies as {@code large_json} for adapter packaging.
         */
        LARGE_JSON,
        /** {@code utf16Chars > STREAM_PERSISTENCE_CHAR_CAP}. */
        OVER_STREAM_PERSISTENCE
    }

    /** Immutable size report for one string payload. */
    public static final class SizeReport {
        private final int utf16Chars;
        private final int utf8Bytes;
        private final SizeClass sizeClass;

        /** Package-visible so sibling adapters can preserve exact UTF-8 payload lengths. */
        SizeReport(int utf16Chars, int utf8Bytes, SizeClass sizeClass) {
            this.utf16Chars = utf16Chars;
            this.utf8Bytes = utf8Bytes;
            this.sizeClass = Objects.requireNonNull(sizeClass, "sizeClass");
        }

        public int utf16Chars() {
            return utf16Chars;
        }

        public int utf8Bytes() {
            return utf8Bytes;
        }

        public SizeClass sizeClass() {
            return sizeClass;
        }

        public boolean exceedsInvokeClassifyCap() {
            return utf16Chars > INVOKE_RESULT_CLASSIFY_CHAR_CAP;
        }

        public boolean exceedsStreamPersistenceCap() {
            return utf16Chars > STREAM_PERSISTENCE_CHAR_CAP;
        }
    }

    public static SizeReport classify(String payload) {
        if (payload == null) {
            return new SizeReport(0, 0, SizeClass.WITHIN_INVOKE_CAP);
        }
        int utf16 = payload.length();
        int utf8 = payload.getBytes(StandardCharsets.UTF_8).length;
        SizeClass cls;
        if (utf16 <= INVOKE_RESULT_CLASSIFY_CHAR_CAP) {
            cls = SizeClass.WITHIN_INVOKE_CAP;
        } else if (utf16 <= STREAM_PERSISTENCE_CHAR_CAP) {
            cls = SizeClass.LARGE_JSON;
        } else {
            cls = SizeClass.OVER_STREAM_PERSISTENCE;
        }
        return new SizeReport(utf16, utf8, cls);
    }

    public static SizeReport classifyBytes(byte[] utf8Payload) {
        if (utf8Payload == null) {
            return new SizeReport(0, 0, SizeClass.WITHIN_INVOKE_CAP);
        }
        String asString = new String(utf8Payload, StandardCharsets.UTF_8);
        return classify(asString);
    }
}
