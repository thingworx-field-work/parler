package com.thingworx.things.agent.hostcontext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

import org.json.JSONObject;

/**
 * Loads host-context templates from {@code src/test/resources/host-contexts/} for unit tests.
 */
public final class HostContextTestTemplates {

    private static final String[] TEST_FILES = {
            "asset_detail.current_asset.json",
            "asset_monitoring.query_scope.json"
    };

    private HostContextTestTemplates() {
    }

    public static void installBuiltInLikeTestTemplates() {
        LinkedHashMap<String, HostContextTemplate> map = new LinkedHashMap<>();
        for (String file : TEST_FILES) {
            loadClasspathTemplate(map, "/host-contexts/" + file);
        }
        HostContextTemplateRegistry.registerTestTemplatesForTests(map);
    }

    private static void loadClasspathTemplate(Map<String, HostContextTemplate> map, String path) {
        try (InputStream in = HostContextTestTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test host-context template: " + path);
            }
            String text = readUtf8(in);
            HostContextTemplate t = HostContextTemplate.fromJson(new JSONObject(text));
            map.put(t.key(), t);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test host-context template " + path, e);
        }
    }

    private static String readUtf8(InputStream in) {
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }
}
