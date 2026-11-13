package com.thingworx.things.agent;

/**
 * The size budget of one tool call's result (topic round 6, R24): the effective soft cap (the serialized size above
 * which the gateway reduces, and which the first model append never exceeds) and the effective text cap (the size
 * above which one text node is cut once the gateway reduces). Resolved once when the call starts executing and used by
 * both the formatter and the gateway.
 */
public final class ResultBudget {

    public static final int DEFAULT_SOFT_CAP_CHARS = 8_192;
    public static final int MIN_SOFT_CAP_CHARS = 8_192;
    public static final int MAX_SOFT_CAP_CHARS = 65_536;
    public static final int DEFAULT_TEXT_CAP_CHARS = 2_000;
    public static final int MIN_TEXT_CAP_CHARS = 500;
    public static final int MAX_TEXT_CAP_CHARS = 50_000;
    /** The text cap is at most the soft cap minus this many characters. */
    public static final int TEXT_CAP_HEADROOM_CHARS = 1_024;

    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_SETTING = "setting";
    public static final String SOURCE_TOOL = "tool";

    private static final ResultBudget DEFAULTS =
            new ResultBudget(DEFAULT_SOFT_CAP_CHARS, DEFAULT_TEXT_CAP_CHARS, SOURCE_DEFAULT);

    private final int softCapChars;
    private final int textCapChars;
    private final String source;

    public ResultBudget(int softCapChars, int textCapChars, String source) {
        this.softCapChars = softCapChars;
        this.textCapChars = textCapChars;
        this.source = source != null ? source : SOURCE_DEFAULT;
    }

    public static ResultBudget defaults() {
        return DEFAULTS;
    }

    public int softCapChars() {
        return softCapChars;
    }

    public int textCapChars() {
        return textCapChars;
    }

    /** Where the text cap came from: {@code default}, {@code setting} or {@code tool}. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "ResultBudget{softCapChars=" + softCapChars + ", textCapChars=" + textCapChars + ", source=" + source
                + "}";
    }
}
