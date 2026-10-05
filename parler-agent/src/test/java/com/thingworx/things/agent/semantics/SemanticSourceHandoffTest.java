package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;

class SemanticSourceHandoffTest {

    private SemanticProfileSnapshot profile;

    @BeforeEach
    void load() throws Exception {
        String path = "/nearterm/semantics/stacking-robot.example.json";
        try (InputStream in = SemanticSourceHandoffTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            profile = SemanticProfileBuilder.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).snapshot();
        }
    }

    @Test
    void resolveUniquePropertyBinding_matchesWrst1() {
        SemanticResolveResult r = SemanticSourceHandoff.resolveUniquePropertyBinding(profile, "Wrst1");
        assertEquals(SemanticResolveStatus.RESOLVED, r.status());
        assertEquals("StackingRobot.propertyRole.operating_temperature", r.propertyRoleRef());
        assertEquals("Cel", r.role().unit());
        assertEquals("PT5S", r.role().expectedCadence());
    }

    @Test
    void resolveUniquePropertyBinding_unknown_notFound() {
        SemanticResolveResult r = SemanticSourceHandoff.resolveUniquePropertyBinding(profile, "NoSuchProp");
        assertEquals(SemanticResolveStatus.NOT_FOUND, r.status());
    }

    @Test
    void resolvePropertyBindingUnderAssetType_matchesWithinProvenKey() {
        SemanticResolveResult r =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "StackingRobot", "Wrst1");
        assertEquals(SemanticResolveStatus.RESOLVED, r.status());
        assertEquals("StackingRobot.propertyRole.operating_temperature", r.propertyRoleRef());
        assertEquals("Cel", r.role().unit());
    }

    @Test
    void resolvePropertyBindingUnderAssetType_crossAssetTypeCollision_noProvenanceForWrongType()
            throws Exception {
        // Same propertyName under StackingRobot (temperature/Cel) and Sealing (length/mm). A Thing
        // proven as Sealing must not inherit StackingRobot provenance even though the global
        // reverse-lookup would be ambiguous / wrong.
        String json = "{"
                + "\"schema\":\"parler-semantic-profile-v1\","
                + "\"profileId\":\"cross-type\","
                + "\"version\":\"1\","
                + "\"assetTypes\":{"
                + "\"StackingRobot\":{\"propertyRoles\":{"
                + "\"operating_temperature\":{\"binding\":{\"kind\":\"PROPERTY\",\"propertyName\":\"Wrst1\"},"
                + "\"unit\":\"Cel\",\"dimension\":\"temperature\",\"grain\":\"sample\"}}},"
                + "\"Sealing\":{\"propertyRoles\":{"
                + "\"wrist_position\":{\"binding\":{\"kind\":\"PROPERTY\",\"propertyName\":\"Wrst1\"},"
                + "\"unit\":\"mm\",\"dimension\":\"length\",\"grain\":\"sample\"}}}"
                + "}}";
        SemanticProfileSnapshot multi = SemanticProfileBuilder.parse(json).snapshot();
        assertEquals(SemanticResolveStatus.AMBIGUOUS,
                SemanticSourceHandoff.resolveUniquePropertyBinding(multi, "Wrst1").status());

        SemanticResolveResult underSealing =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(multi, "Sealing", "Wrst1");
        assertEquals(SemanticResolveStatus.RESOLVED, underSealing.status());
        assertEquals("Sealing.propertyRole.wrist_position", underSealing.propertyRoleRef());
        assertEquals("mm", underSealing.role().unit());

        SemanticResolveResult sealingThingAgainstStackingOnlyProfile =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "Sealing", "Wrst1");
        assertEquals(SemanticResolveStatus.NOT_FOUND, sealingThingAgainstStackingOnlyProfile.status());
    }

    @Test
    void attachPropertyProvenance_nullThing_leavesBaseUnchanged() {
        SourceDescriptor base = SourceDescriptor.builder().sourceRouteId("query_numeric_property_history").build();
        SourceDescriptor out = SemanticSourceHandoff.attachPropertyProvenance(base, null, "Wrst1");
        assertNull(out.propertyRoleRef());
        assertEquals(base.sourceRouteId(), out.sourceRouteId());
    }

    @Test
    void withSemanticProvenance_appliesSp5FieldsAndDerivePreservesThem() {
        SemanticResolveResult r =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "StackingRobot", "Wrst1");
        SourceDescriptor base = SourceDescriptor.builder()
                .sourceRouteId("query_numeric_property_history")
                .rowsReturned(3L)
                .completenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN)
                .build();
        SourceDescriptor enriched = SourceDescriptorSupport.withSemanticProvenance(base, r);
        assertEquals("StackingRobot.propertyRole.operating_temperature", enriched.propertyRoleRef());
        assertEquals("Cel", enriched.unitRef());
        assertEquals("sample", enriched.grainRef());
        assertEquals("PT5S", enriched.cadenceRef());
        assertEquals(profile.profileId(), enriched.semanticProfileId());
        assertEquals(profile.digest(), enriched.semanticProfileDigest());

        SourceDescriptor derived = enriched.composeDerived("tabulate", "parent-cache-1");
        assertEquals(enriched.propertyRoleRef(), derived.propertyRoleRef());
        assertEquals(enriched.semanticProfileDigest(), derived.semanticProfileDigest());
        assertEquals(1, derived.parentSourceCacheIds().size());
    }

    @Test
    void withSemanticProvenance_ignoresNonResolved() {
        SourceDescriptor base = SourceDescriptor.builder().sourceRouteId("x").build();
        SourceDescriptor out = SourceDescriptorSupport.withSemanticProvenance(base,
                SemanticResolveResult.notFound("nope"));
        assertNull(out.propertyRoleRef());
        assertNotNull(out);
    }

    @Test
    void withSemanticProvenance_keepsColumnRolesWhetherResolvedOrNot() {
        SemanticResolveResult r =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "StackingRobot", "Wrst1");
        SourceDescriptor base = SourceDescriptor.builder()
                .sourceRouteId("query_numeric_property_history")
                .rowsReturned(3L)
                .timeColumn("timestamp")
                .valueColumn("value")
                .build();
        SourceDescriptor enriched = SourceDescriptorSupport.withSemanticProvenance(base, r);
        assertEquals("StackingRobot.propertyRole.operating_temperature", enriched.propertyRoleRef());
        assertEquals("timestamp", enriched.timeColumn());
        assertEquals("value", enriched.valueColumn());

        SourceDescriptor unresolved = SourceDescriptorSupport.withSemanticProvenance(base,
                SemanticResolveResult.notFound("nope"));
        assertEquals("timestamp", unresolved.timeColumn());
        assertEquals("value", unresolved.valueColumn());

        SourceDescriptor derived = enriched.composeDerived("tabulate", "parent-cache-1");
        assertEquals(enriched.propertyRoleRef(), derived.propertyRoleRef());
        assertNull(derived.timeColumn(), "a new table never inherits column roles");
        assertNull(derived.valueColumn());
    }

    @Test
    void withSemanticProvenance_keepsSubjectIdentityWhetherResolvedOrNot() {
        SemanticResolveResult r =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "StackingRobot", "Wrst1");
        SourceDescriptor base = SourceDescriptor.builder().sourceRouteId("query_numeric_property_history")
                .timeColumn("timestamp").valueColumn("value")
                .subjectThingName("SE.Robot.1").subjectPropertyName("Wrst1").build();
        SourceDescriptor enriched = SourceDescriptorSupport.withSemanticProvenance(base, r);
        assertEquals("SE.Robot.1", enriched.subjectThingName());
        assertEquals("Wrst1", enriched.subjectPropertyName());
        assertEquals("value", enriched.valueColumn());
        SourceDescriptor unresolved = SourceDescriptorSupport.withSemanticProvenance(base,
                SemanticResolveResult.notFound("nope"));
        assertEquals("SE.Robot.1", unresolved.subjectThingName());
        assertNull(enriched.composeDerived("tabulate", "p").subjectThingName());
    }
}
