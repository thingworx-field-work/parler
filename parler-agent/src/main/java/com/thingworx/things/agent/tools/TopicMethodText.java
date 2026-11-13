package com.thingworx.things.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.thingworx.things.agent.llm.ToolCall;

/** Bounded plain-text carrier shared by loader recognition and current-turn retention. */
public final class TopicMethodText {
    public static final int MAX_BODY_CHARS = 7000;
    public static final int MAX_RESULT_CHARS = 8192;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private TopicMethodText() {}

    public static final class Reference {
        private final String docId;
        private final String chunkId;

        public Reference(String docId, String chunkId) {
            this.docId = docId;
            this.chunkId = chunkId;
        }

        public String docId() { return docId; }
        public String chunkId() { return chunkId; }

        public String methodId() {
            return "topic:" + docId + "/" + chunkId;
        }
    }

    public static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 256 && id.equals(id.strip())
                && !id.equals(".") && !id.contains("..") && !id.contains("/") && !id.contains("\\")
                && id.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029);
    }

    public static Optional<Reference> reference(ToolCall call) {
        if (call == null || !"load_topic".equals(call.getFunctionName()) || call.getArguments() == null) {
            return Optional.empty();
        }
        try {
            JsonNode args = JSON.readTree(call.getArguments());
            if (args == null || !args.isObject() || !args.path("docId").isTextual()
                    || !args.path("chunkId").isTextual()) {
                return Optional.empty();
            }
            String doc = args.get("docId").textValue();
            String chunk = args.get("chunkId").textValue();
            return validId(doc) && validId(chunk) ? Optional.of(new Reference(doc, chunk)) : Optional.empty();
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    public static String format(Reference ref, String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(64);
            for (byte b : digest) {
                hash.append(Character.forDigit((b & 0xff) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            return "topic: " + ref.chunkId() + "\ndocument: " + ref.docId()
                    + "\nsha256: " + hash + "   chars: " + body.length() + "\ncomplete: true\n---\n" + body;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Only a complete, bounded carrier matching the executed call can acquire recognition/protection. */
    public static Optional<Reference> successfulReference(ToolCall call, String result) {
        Optional<Reference> ref = reference(call);
        if (ref.isEmpty() || result == null || result.length() >= MAX_RESULT_CHARS) {
            return Optional.empty();
        }
        int separator = result.indexOf("\n---\n");
        if (separator < 0) {
            return Optional.empty();
        }
        String body = result.substring(separator + 5);
        return !body.isBlank() && body.length() <= MAX_BODY_CHARS && format(ref.get(), body).equals(result)
                ? ref : Optional.empty();
    }
}
