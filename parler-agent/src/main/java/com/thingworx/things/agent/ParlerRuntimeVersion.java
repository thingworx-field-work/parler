package com.thingworx.things.agent;

import java.io.InputStream;
import java.util.Properties;

import com.thingworx.things.agent.configrepo.ParlerPackageVersion;

/**
 * ThingWorx import / Composer-facing extension version from build-time {@code parler-runtime-version.properties}
 * (Gradle {@code artifact_version}), with optional raw {@code implementationVersion} and manifest fallback.
 */
public final class ParlerRuntimeVersion {

    private static final String RESOURCE = "/parler-runtime-version.properties";
    private static final Properties CACHED = load();

    private ParlerRuntimeVersion() {}

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = ParlerRuntimeVersion.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception ignored) {
            // leave empty — fall back to manifest reader
        }
        return p;
    }

    /**
     * @return {@code artifactVersion} from generated properties, else {@link ParlerPackageVersion} from
     *         {@link AgentThing} anchor class, else empty string
     */
    public static String displayVersion() {
        return displayVersionForProperties(CACHED);
    }

    /**
     * Same semantics as {@link #displayVersion()} using arbitrary properties (unit tests).
     *
     * @param props non-null; use empty {@link Properties} to exercise manifest-only fallback
     */
    public static String displayVersionForProperties(Properties props) {
        String v = props.getProperty("artifactVersion");
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        String manifest = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        return manifest != null ? manifest.trim() : "";
    }

    /** Raw build / snapshot line when present (may equal {@link #displayVersion()} on release builds). */
    public static String implementationVersion() {
        return implementationVersionForProperties(CACHED);
    }

    /** @see #implementationVersion() — parameterized for unit tests */
    public static String implementationVersionForProperties(Properties props) {
        String v = props.getProperty("implementationVersion");
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return displayVersionForProperties(props);
    }
}