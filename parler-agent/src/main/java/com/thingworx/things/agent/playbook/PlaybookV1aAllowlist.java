package com.thingworx.things.agent.playbook;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** @deprecated Use {@link PlaybookToolAllowlist}; retained for tests referencing V1a triple. */
@Deprecated
public final class PlaybookV1aAllowlist {

    public static final Set<String> TOOL_NAMES = PlaybookToolAllowlist.TOOL_NAMES;

    private PlaybookV1aAllowlist() {}

    public static boolean isAllowed(String toolName) {
        return PlaybookToolAllowlist.isAllowed(toolName);
    }
}
