package com.thingworx.things.agent.configrepo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SPR-0 lock for how G13 extends {@code /tools/extended_tools.json} (D2 / D6).
 *
 * <p>New U7 fields are additive optional keys under the existing {@code version:1} root.
 * Unknown keys on an independent tool entry are ignored (compatible with the current
 * {@link ExtendedToolsManifest} JsonNode reader). Structural defects (bad root, wrong version,
 * non-array {@code tools}) still invalidate the whole package per existing S9 / LKG policy.
 * A future breaking change may introduce {@code version:2}; SPR-1 must not invent a second
 * registry or a parallel manifest family.
 */
public final class ServiceCapabilityManifestPolicy {

    /** Supported root {@code version} for extended tools + G13 additive fields. */
    public static final int SUPPORTED_MANIFEST_VERSION = 1;

    /** When true, U7 descriptor fields land as optional keys without a version bump. */
    public static final boolean ADDITIVE_FIELDS_UNDER_VERSION_1 = true;

    /**
     * Unknown keys on one tool entry: {@code IGNORE} (do not skip the entry solely for unknown
     * keys). Known-key type/semantic errors still skip that independent entry (S9).
     */
    public static final String UNKNOWN_ENTRY_KEY_POLICY = "IGNORE";

    /**
     * Field names reserved for U8/U11 consumers. SPR-1 MUST reject or ignore these as
     * non-authoritative if present; U7 never implements their semantics.
     */
    public static final Set<String> FORBIDDEN_U7_SEMANTIC_FIELDS;

    static {
        Set<String> forbidden = new LinkedHashSet<>();
        forbidden.add("approvalWorkflow");
        forbidden.add("compensation");
        forbidden.add("serviceCost");
        forbidden.add("latencyClass");
        forbidden.add("costClass");
        FORBIDDEN_U7_SEMANTIC_FIELDS = Collections.unmodifiableSet(forbidden);
    }

    private ServiceCapabilityManifestPolicy() {}
}
