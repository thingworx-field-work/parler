package com.thingworx.things.agent.configrepo;

import java.net.JarURLConnection;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Reads extension package version from the JAR {@code Package-Version} manifest entry (see {@code parler-agent/build.gradle}
 * {@code jar} / {@code shadowJar} manifest attributes).
 */
public final class ParlerPackageVersion {

    private ParlerPackageVersion() {}

    /**
     * @return trimmed version string, or {@code null} when unavailable (e.g. classes loaded from a directory in IDE tests)
     */
    public static String fromAnchorClass(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            String resource = anchor.getName().replace('.', '/') + ".class";
            URL url = anchor.getResource("/" + resource);
            if (url == null) {
                url = anchor.getResource(resource);
            }
            if (url == null) {
                return null;
            }
            if (!"jar".equals(url.getProtocol())) {
                return null;
            }
            JarURLConnection conn = (JarURLConnection) url.openConnection();
            Manifest mf = conn.getManifest();
            if (mf == null) {
                return null;
            }
            Attributes attrs = mf.getMainAttributes();
            String v = attrs.getValue("Package-Version");
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
            v = attrs.getValue("Implementation-Version");
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        } catch (Exception ignored) {
            // Fall through
        }
        return null;
    }
}
