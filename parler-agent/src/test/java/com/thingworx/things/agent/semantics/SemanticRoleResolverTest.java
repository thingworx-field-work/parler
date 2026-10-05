package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticRoleResolverTest {

    private SemanticProfileSnapshot snapshot;

    @BeforeEach
    void loadExample() throws Exception {
        String path = "/nearterm/semantics/stacking-robot.example.json";
        try (InputStream in = SemanticRoleResolverTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            snapshot = SemanticProfileBuilder.parse(json).snapshot();
        }
    }

    @Test
    void resolve_exactRoleId() {
        SemanticResolveResult r =
                SemanticRoleResolver.resolve(snapshot, "StackingRobot", "operating_temperature");
        assertEquals(SemanticResolveStatus.RESOLVED, r.status());
        assertEquals("Wrst1", r.role().binding().propertyName());
        assertEquals("StackingRobot.propertyRole.operating_temperature", r.propertyRoleRef());
        assertEquals(snapshot.digest(), r.profileDigest());
    }

    @Test
    void resolve_declaredAlias() {
        SemanticResolveResult r =
                SemanticRoleResolver.resolve(snapshot, "StackingRobot", "robot temperature");
        assertEquals(SemanticResolveStatus.RESOLVED, r.status());
        assertEquals("operating_temperature", r.role().roleId());
    }

    @Test
    void resolve_serviceRole() {
        SemanticResolveResult r =
                SemanticRoleResolver.resolve(snapshot, "StackingRobot", "energy_per_cycle");
        assertEquals(SemanticResolveStatus.RESOLVED, r.status());
        assertEquals(SemanticBindingKind.SERVICE, r.role().binding().kind());
        assertEquals("GetEnergyPerCycle", r.role().binding().serviceName());
        assertEquals("value", r.role().binding().resultField());
    }

    @Test
    void resolve_missingRole_notFound() {
        SemanticResolveResult r = SemanticRoleResolver.resolve(snapshot, "StackingRobot", "no_such_role");
        assertEquals(SemanticResolveStatus.NOT_FOUND, r.status());
        assertNull(r.role());
    }

    @Test
    void resolve_unknownAssetType_notFound() {
        SemanticResolveResult r =
                SemanticRoleResolver.resolve(snapshot, "UnknownType", "operating_temperature");
        assertEquals(SemanticResolveStatus.NOT_FOUND, r.status());
    }

    @Test
    void resolve_nullSnapshot_unavailable() {
        SemanticResolveResult r = SemanticRoleResolver.resolve(null, "StackingRobot", "operating_temperature");
        assertEquals(SemanticResolveStatus.UNAVAILABLE, r.status());
    }

    @Test
    void resolve_doesNotGuessCaseFold() {
        SemanticResolveResult r =
                SemanticRoleResolver.resolve(snapshot, "StackingRobot", "Operating_Temperature");
        assertEquals(SemanticResolveStatus.NOT_FOUND, r.status());
    }
}
