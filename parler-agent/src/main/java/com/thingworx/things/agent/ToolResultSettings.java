package com.thingworx.things.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Resolved {@code AgentSettings} for how a tool result reaches the model (topic round 6, R22). Modelled on
 * {@code DocumentKnowledgeSettings}: values are read live and normalized here, and a value that had to be adjusted
 * leaves a {@code CONFIG_VALUE_CLAMPED} warning.
 */
public final class ToolResultSettings {

    /** How a JSON service result is placed in the tool-result envelope. */
    public enum JsonMode {
        /** The JSON value serialized into one string field (the default and the shape before round 6). */
        STRING("string"),
        /** The JSON value as a JSON object under {@code result}. */
        NODE("node");

        private final String wireName;

        JsonMode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** One adjusted setting. */
    public static final class Warning {
        private final String code;
        private final String message;

        Warning(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }

    public static final String CONFIG_VALUE_CLAMPED = "CONFIG_VALUE_CLAMPED";

    public static final String CONFIG_PAGE_EXCEEDS_RESULT_CAP = "CONFIG_PAGE_EXCEEDS_RESULT_CAP";
    /** Envelope allowance of the page estimate; the estimate ignores escaping. */
    static final int PAGE_ENVELOPE_ESTIMATE_CHARS = 1_500;

    private final JsonMode jsonMode;
    private final int softCapChars;
    private final String softCapSource;
    private final int textCapChars;
    private final String textCapSource;
    private final List<Warning> warnings;

    private ToolResultSettings(JsonMode jsonMode, int softCapChars, String softCapSource, int textCapChars,
            String textCapSource, List<Warning> warnings) {
        this.jsonMode = jsonMode;
        this.softCapChars = softCapChars;
        this.softCapSource = softCapSource;
        this.textCapChars = textCapChars;
        this.textCapSource = textCapSource;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public static ToolResultSettings defaults() {
        return new ToolResultSettings(JsonMode.STRING, ResultBudget.DEFAULT_SOFT_CAP_CHARS, ResultBudget.SOURCE_DEFAULT,
                ResultBudget.DEFAULT_TEXT_CAP_CHARS, ResultBudget.SOURCE_DEFAULT, List.of());
    }

    public static ToolResultSettings fromAgent(AgentBaseThing agent) {
        if (agent == null) {
            return defaults();
        }
        return fromRaw(agent.getToolResultJsonModeRaw(), agent.getToolResultSoftCapCharsRaw(),
                agent.getToolResultTextCapCharsRaw());
    }

    static ToolResultSettings fromRaw(String jsonModeRaw) {
        return fromRaw(jsonModeRaw, null, null);
    }

    /**
     * @param softCapRaw {@code toolResultSoftCapChars} as configured, or {@code null} when unset
     * @param textCapRaw {@code toolResultTextCapChars} as configured, or {@code null} when unset
     */
    public static ToolResultSettings fromRaw(String jsonModeRaw, Integer softCapRaw, Integer textCapRaw) {
        List<Warning> warnings = new ArrayList<>();
        JsonMode mode = parseJsonMode(jsonModeRaw, warnings);

        int soft = ResultBudget.DEFAULT_SOFT_CAP_CHARS;
        String softSource = ResultBudget.SOURCE_DEFAULT;
        if (softCapRaw != null) {
            soft = clamp("toolResultSoftCapChars", softCapRaw, ResultBudget.MIN_SOFT_CAP_CHARS,
                    ResultBudget.MAX_SOFT_CAP_CHARS, ResultBudget.DEFAULT_SOFT_CAP_CHARS, warnings);
            softSource = soft == ResultBudget.DEFAULT_SOFT_CAP_CHARS ? ResultBudget.SOURCE_DEFAULT
                    : ResultBudget.SOURCE_SETTING;
        }

        int text = ResultBudget.DEFAULT_TEXT_CAP_CHARS;
        String textSource = ResultBudget.SOURCE_DEFAULT;
        if (textCapRaw != null) {
            text = clamp("toolResultTextCapChars", textCapRaw, ResultBudget.MIN_TEXT_CAP_CHARS,
                    ResultBudget.MAX_TEXT_CAP_CHARS, ResultBudget.DEFAULT_TEXT_CAP_CHARS, warnings);
            textSource = text == ResultBudget.DEFAULT_TEXT_CAP_CHARS ? ResultBudget.SOURCE_DEFAULT
                    : ResultBudget.SOURCE_SETTING;
        }
        int textCeiling = soft - ResultBudget.TEXT_CAP_HEADROOM_CHARS;
        if (text > textCeiling) {
            warnings.add(new Warning(CONFIG_VALUE_CLAMPED, "toolResultTextCapChars exceeded toolResultSoftCapChars"
                    + " minus " + ResultBudget.TEXT_CAP_HEADROOM_CHARS + " and was clamped."));
            text = textCeiling;
        }
        return new ToolResultSettings(mode, soft, softSource, text, textSource, warnings);
    }

    private static int clamp(String field, int value, int min, int max, int dflt, List<Warning> warnings) {
        if (value < min) {
            warnings.add(new Warning(CONFIG_VALUE_CLAMPED,
                    field + " was below the allowed minimum and was reset to the default."));
            return dflt;
        }
        if (value > max) {
            warnings.add(new Warning(CONFIG_VALUE_CLAMPED, field + " exceeded the allowed maximum and was clamped."));
            return max;
        }
        return value;
    }

    /**
     * The budget of one call: the installation's soft cap always; the tool's declared text cap when it has one
     * (clamped to the soft cap minus {@value ResultBudget#TEXT_CAP_HEADROOM_CHARS}), else the installation's.
     */
    public ResultBudget budgetFor(Integer toolTextCapChars) {
        if (toolTextCapChars != null && toolTextCapChars > 0) {
            int text = Math.min(toolTextCapChars, softCapChars - ResultBudget.TEXT_CAP_HEADROOM_CHARS);
            return new ResultBudget(softCapChars, text, ResultBudget.SOURCE_TOOL);
        }
        String source = ResultBudget.SOURCE_SETTING.equals(softCapSource)
                || ResultBudget.SOURCE_SETTING.equals(textCapSource)
                ? ResultBudget.SOURCE_SETTING : ResultBudget.SOURCE_DEFAULT;
        return new ResultBudget(softCapChars, textCapChars, source);
    }

    /**
     * The estimate warning of R24: present when the page setting plus a fixed envelope allowance exceeds the soft
     * cap. It ignores escaping, so its absence promises nothing; the result itself says when it was reduced.
     */
    public List<Warning> pageWarnings(int documentKnowledgeChunkMaxChars) {
        if (documentKnowledgeChunkMaxChars + PAGE_ENVELOPE_ESTIMATE_CHARS > softCapChars) {
            return List.of(new Warning(CONFIG_PAGE_EXCEEDS_RESULT_CAP, "documentKnowledgeChunkMaxChars ("
                    + documentKnowledgeChunkMaxChars + ") plus about " + PAGE_ENVELOPE_ESTIMATE_CHARS
                    + " characters of envelope exceeds toolResultSoftCapChars (" + softCapChars
                    + "); a page above the cap is reduced and says so."));
        }
        return List.of();
    }

    public int softCapChars() {
        return softCapChars;
    }

    public String softCapSource() {
        return softCapSource;
    }

    public int textCapChars() {
        return textCapChars;
    }

    public String textCapSource() {
        return textCapSource;
    }

    private static JsonMode parseJsonMode(String raw, List<Warning> warnings) {
        String v = raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
        if (v.isEmpty() || JsonMode.STRING.wireName().equals(v)) {
            return JsonMode.STRING;
        }
        if (JsonMode.NODE.wireName().equals(v)) {
            return JsonMode.NODE;
        }
        warnings.add(new Warning(CONFIG_VALUE_CLAMPED,
                "toolResultJsonMode was not string or node and was reset to string."));
        return JsonMode.STRING;
    }

    public JsonMode jsonMode() {
        return jsonMode;
    }

    public List<Warning> warnings() {
        return warnings;
    }
}
