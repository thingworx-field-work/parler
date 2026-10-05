package com.thingworx.things.agent.playbook;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * Parses structured playbook slash commands (e.g. {@code /cross_region_health {...}}) before skill slash parsing.
 */
public final class PlaybookSlashParser {

    private static final Pattern STANDALONE_SLASH = Pattern.compile(
            "(?<=\\A|[\\s\\u00A0\\u3000])(/([A-Za-z][A-Za-z0-9_-]*))(?:\\s+(\\{.*\\}))?(?=[\\s\\u00A0\\u3000.,:;!?\\)\\]\\}，。！？；：、]|\\z)",
            Pattern.DOTALL);

    private PlaybookSlashParser() {}

    public enum Kind {
        NONE,
        STRUCTURED,
        FREE_TEXT
    }

    public static final class Result {
        private final Kind kind;
        private final String playbookId;
        private final JSONObject params;
        private final String cleanedMessage;
        private final String clarificationMessage;

        private Result(Kind kind, String playbookId, JSONObject params, String cleanedMessage,
                String clarificationMessage) {
            this.kind = kind;
            this.playbookId = playbookId;
            this.params = params;
            this.cleanedMessage = cleanedMessage;
            this.clarificationMessage = clarificationMessage;
        }

        public static Result none(String message) {
            return new Result(Kind.NONE, null, null, message, null);
        }

        public static Result structured(String playbookId, JSONObject params, String cleanedMessage) {
            return new Result(Kind.STRUCTURED, playbookId, params, cleanedMessage, null);
        }

        public static Result freeText(String playbookId, String clarification) {
            return new Result(Kind.FREE_TEXT, playbookId, null, "", clarification);
        }

        public Kind kind() {
            return kind;
        }

        public String playbookId() {
            return playbookId;
        }

        public JSONObject params() {
            return params;
        }

        public String cleanedMessage() {
            return cleanedMessage;
        }

        public String clarificationMessage() {
            return clarificationMessage;
        }
    }

    public static Result parse(String message, Set<String> validPlaybookIds) {
        if (message == null || message.isBlank() || validPlaybookIds == null || validPlaybookIds.isEmpty()) {
            return Result.none(message != null ? message : "");
        }
        Matcher m = STANDALONE_SLASH.matcher(message.trim());
        if (!m.find()) {
            return Result.none(message);
        }
        String id = m.group(2);
        if (!validPlaybookIds.contains(id)) {
            return Result.none(message);
        }
        String jsonPart = m.group(3);
        if (jsonPart == null || jsonPart.isBlank()) {
            return Result.freeText(id,
                    "To run /" + id + " directly, supply JSON parameters after the command, for example:\n"
                            + structuredExampleJson(id) + "\n"
                            + "Or ask in natural language and I will start the playbook with structured parameters.");
        }
        try {
            JSONObject params = new JSONObject(jsonPart.trim());
            String cleaned = (message.substring(0, m.start(1)) + message.substring(m.end(1))).replaceAll("\\s+", " ")
                    .trim();
            return Result.structured(id, params, cleaned);
        } catch (Exception e) {
            return Result.freeText(id,
                    "Could not parse JSON parameters for /" + id + ". Use a single JSON object such as:\n"
                            + structuredExampleJson(id)
                            + "\nOr ask in natural language.");
        }
    }

    static String structuredExampleJson(String playbookId) {
        if (PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID.equals(playbookId)) {
            return "/" + playbookId
                    + " {\"assetIdentifierA\":\"ORD JetDryer 02\",\"assetIdentifierB\":\"AC JetDryer 01\","
                    + "\"timeWindow\":\"24h\",\"assetType\":\"Jet Dryer\"}";
        }
        return "/" + playbookId
                + " {\"assetType\":\"Stacking Robot\",\"regions\":[\"USA\",\"Germany\"],\"timeWindow\":\"current\"}";
    }
}
