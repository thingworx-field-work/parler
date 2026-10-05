package com.thingworx.things.agent.evidence;

/**
 * Optional method lineage on {@link EvidenceAssessment}. Absent fields stay null — U3 does not
 * invent a method registry (U4 owns analysis methods).
 */
public final class EvidenceMethodRef {

    private final String id;
    private final String version;
    private final String semanticProfileDigest;

    private EvidenceMethodRef(String id, String version, String semanticProfileDigest) {
        this.id = blankToNull(id);
        this.version = blankToNull(version);
        this.semanticProfileDigest = blankToNull(semanticProfileDigest);
    }

    public static EvidenceMethodRef of(String id, String version, String semanticProfileDigest) {
        EvidenceMethodRef ref = new EvidenceMethodRef(id, version, semanticProfileDigest);
        if (ref.id == null && ref.version == null && ref.semanticProfileDigest == null) {
            return null;
        }
        return ref;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String semanticProfileDigest() {
        return semanticProfileDigest;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
