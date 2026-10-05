package com.thingworx.things.agent.hostcontext;

import java.util.regex.Pattern;

/**
 * Validates {@code blockName} for {@code format.jsonFence} (docs/architecture/host-context.md §8.1).
 */
public final class HostContextBlockNameValidator {

    public static final int MAX_BLOCK_NAME_CHARS = 64;
    private static final Pattern KEBAB =
            Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private HostContextBlockNameValidator() {
    }

    /** @return {@code null} if valid; else human-readable reason */
    public static String validateOrReason(String blockName) {
        if (blockName == null || blockName.isEmpty()) {
            return "blockName is empty";
        }
        if (blockName.length() > MAX_BLOCK_NAME_CHARS) {
            return "blockName exceeds " + MAX_BLOCK_NAME_CHARS + " characters";
        }
        if (blockName.indexOf('\n') >= 0 || blockName.indexOf('\r') >= 0 || blockName.indexOf('`') >= 0) {
            return "blockName contains forbidden boundary character";
        }
        if (!KEBAB.matcher(blockName).matches()) {
            return "blockName must be ASCII kebab-case (^[a-z0-9]+(-[a-z0-9]+)*$)";
        }
        return null;
    }

    public static boolean isValid(String blockName) {
        return validateOrReason(blockName) == null;
    }
}
