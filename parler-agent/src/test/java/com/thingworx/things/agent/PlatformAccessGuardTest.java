package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every platform lookup and service call honors ThingWorx permissions: the permission-free {@code *Direct} APIs are
 * reachable only through {@link PlatformAccess}, and its Visibility-free policy lookup only from the PASSWORD guards.
 */
class PlatformAccessGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final String PLATFORM_ACCESS = "com/thingworx/things/agent/PlatformAccess.java";
    private static final Set<String> POLICY_CHECK_CALLERS = Set.of(
            "com/thingworx/things/agent/tools/ProtectedValuePolicy.java",
            "com/thingworx/things/agent/tools/InvokeServiceNamedVtqProtection.java",
            "com/thingworx/things/agent/taxonomy/TaxonomyPropertyProjection.java");

    @Test
    void directPlatformApisAppearOnlyInPlatformAccess() throws Exception {
        for (Path source : productionSources()) {
            if (relative(source).equals(PLATFORM_ACCESS)) {
                continue;
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("findEntityDirect"), relative(source));
            assertFalse(text.contains("processServiceRequestDirect"), relative(source));
        }
    }

    @Test
    void noReflectiveReadAcceptsTheSystemUser() throws Exception {
        for (Path source : productionSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("getMethod(\"getPropertyValue\""), relative(source));
            assertFalse(text.contains("getMethod(\"getProperty\""), relative(source));
            assertFalse(text.contains("getMethod(\"processServiceRequest\""), relative(source));
        }
    }

    @Test
    void policyCheckLookupIsUsedOnlyByPasswordGuards() throws Exception {
        for (Path source : productionSources()) {
            String name = relative(source);
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (text.contains("PlatformAccess.findForPolicyCheck(")) {
                assertTrue(POLICY_CHECK_CALLERS.contains(name), name + " uses the Visibility-free policy lookup");
            }
        }
    }

    @Test
    void hitlGatesRefuseWhenNoParlerContextCanHoldTheApproval() throws Exception {
        String agent = Files.readString(SOURCE_ROOT.resolve("com/thingworx/things/agent/AgentThing.java"),
                StandardCharsets.UTF_8);
        assertEquals(2, count(agent, "ParlerHitlStreamScopedEnqueue.approvalRequiresParlerContextJson(fn)"),
                "extended-tool and invoke_service HITL gates must return the blocked result after enqueue returns");
        assertTrue(agent.contains("return SetPropertyValueExecutor.blockedOutsideParlerContextJson();"));
    }

    private static List<Path> productionSources() throws Exception {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            return sources.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String relative(Path source) {
        return SOURCE_ROOT.relativize(source).toString().replace('\\', '/');
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
