package com.thingworx.things.agent.playbook;

/** Formatter / runner evidence features shipped in the current extension (operator snapshot). */
public final class PlaybookRuntimeEvidenceCapabilities {

    private final boolean nodeEvidenceLines;
    private final boolean toolTableProjection;
    private final boolean rootScalarProjection;
    private final boolean artifactForwarding;

    public PlaybookRuntimeEvidenceCapabilities(boolean nodeEvidenceLines, boolean toolTableProjection,
            boolean rootScalarProjection, boolean artifactForwarding) {
        this.nodeEvidenceLines = nodeEvidenceLines;
        this.toolTableProjection = toolTableProjection;
        this.rootScalarProjection = rootScalarProjection;
        this.artifactForwarding = artifactForwarding;
    }

    public static PlaybookRuntimeEvidenceCapabilities current() {
        return new PlaybookRuntimeEvidenceCapabilities(true, true, true, true);
    }

    public boolean nodeEvidenceLines() {
        return nodeEvidenceLines;
    }

    public boolean toolTableProjection() {
        return toolTableProjection;
    }

    public boolean rootScalarProjection() {
        return rootScalarProjection;
    }

    public boolean artifactForwarding() {
        return artifactForwarding;
    }
}
