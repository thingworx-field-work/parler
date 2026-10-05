package com.thingworx.things.agent;

/**
 * Message-list indices of per-turn ephemeral system injections for Parler AlwaysOn (matches
 * {@code AgentThing.LlmTurnContext} field order plus optional {@link #taskStateIdx}). Immutable value type (explicit
 * class, not {@code record}) so the
 * extension stays aligned with the **Java&nbsp;11** source baseline enforced by ThingWorx packaging, even though the
 * Gradle toolchain may be newer. Used by {@link com.thingworx.things.agent.tools.AgentToolContext} and
 * {@link EphemeralSystemStripHelper}.
 */
public final class ParlerEphemeralSystemIndices {

    /**
     * Sentinel: every slot {@code -1}. Catalog and time-anchor slots are retained for positional compatibility but
     * are always absent after anthropic-breakpoint Slice A; the catalog is stable-row content and time values are
     * injected per round by {@code AgentLoop}.
     * Tests and callers may use this for an explicit empty bundle; a normal {@link AgentThing} turn builds concrete
     * indices via {@code LlmTurnContext#ephemeralIndices()}.
     */
    public static final ParlerEphemeralSystemIndices NONE =
            new ParlerEphemeralSystemIndices(-1, -1, -1, -1, -1, -1, -1);

    private final int catalogIdx;
    private final int slashIdx;
    private final int timeAnchorIdx;
    private final int taxonomyIdx;
    private final int alertIdx;
    private final int hostScopeIdx;
    /** Reserved for static task-state slot; v1a injects per LLM round in {@link com.thingworx.things.agent.AgentLoop}. */
    private final int taskStateIdx;

    public ParlerEphemeralSystemIndices(
            int catalogIdx,
            int slashIdx,
            int timeAnchorIdx,
            int taxonomyIdx,
            int alertIdx,
            int hostScopeIdx,
            int taskStateIdx) {
        this.catalogIdx = catalogIdx;
        this.slashIdx = slashIdx;
        this.timeAnchorIdx = timeAnchorIdx;
        this.taxonomyIdx = taxonomyIdx;
        this.alertIdx = alertIdx;
        this.hostScopeIdx = hostScopeIdx;
        this.taskStateIdx = taskStateIdx;
    }

    public int catalogIdx() {
        return catalogIdx;
    }

    public int slashIdx() {
        return slashIdx;
    }

    public int timeAnchorIdx() {
        return timeAnchorIdx;
    }

    public int taxonomyIdx() {
        return taxonomyIdx;
    }

    public int alertIdx() {
        return alertIdx;
    }

    public int hostScopeIdx() {
        return hostScopeIdx;
    }

    public int taskStateIdx() {
        return taskStateIdx;
    }

    @Override
    public String toString() {
        return "ParlerEphemeralSystemIndices{catalogIdx=" + catalogIdx + ", slashIdx=" + slashIdx
                + ", timeAnchorIdx=" + timeAnchorIdx + ", taxonomyIdx=" + taxonomyIdx + ", alertIdx=" + alertIdx
                + ", hostScopeIdx=" + hostScopeIdx + ", taskStateIdx=" + taskStateIdx + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ParlerEphemeralSystemIndices that = (ParlerEphemeralSystemIndices) o;
        return catalogIdx == that.catalogIdx && slashIdx == that.slashIdx && timeAnchorIdx == that.timeAnchorIdx
                && taxonomyIdx == that.taxonomyIdx && alertIdx == that.alertIdx && hostScopeIdx == that.hostScopeIdx
                && taskStateIdx == that.taskStateIdx;
    }

    @Override
    public int hashCode() {
        int result = catalogIdx;
        result = 31 * result + slashIdx;
        result = 31 * result + timeAnchorIdx;
        result = 31 * result + taxonomyIdx;
        result = 31 * result + alertIdx;
        result = 31 * result + hostScopeIdx;
        result = 31 * result + taskStateIdx;
        return result;
    }
}
