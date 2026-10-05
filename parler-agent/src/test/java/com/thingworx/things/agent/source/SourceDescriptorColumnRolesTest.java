package com.thingworx.things.agent.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.analysis.DerivedArtifactLineage;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * CM-0: optional writer-declared column roles live on the descriptor, survive same-artifact rebuilds,
 * and are never inherited by a new table.
 */
class SourceDescriptorColumnRolesTest {

    private static SourceDescriptor withRoles() {
        return SourceDescriptor.builder()
                .sourceRouteId("any.producer")
                .rowsReturned(3L)
                .completenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN)
                .timeColumn("observedAt")
                .valueColumn("reading")
                .build();
    }

    private static InfoTable table(String... names) {
        DataShapeDefinition shape = new DataShapeDefinition();
        for (String n : names) {
            FieldDefinition f = new FieldDefinition();
            f.setName(n);
            f.setBaseType(BaseTypes.STRING);
            shape.addFieldDefinition(f);
        }
        return new InfoTable(shape);
    }

    @Test
    void builder_storesRolesAndBlankMeansAbsent() {
        SourceDescriptor d = withRoles();
        assertEquals("observedAt", d.timeColumn());
        assertEquals("reading", d.valueColumn());
        SourceDescriptor blank = SourceDescriptor.builder().timeColumn("  ").valueColumn(null).build();
        assertNull(blank.timeColumn());
        assertNull(blank.valueColumn());
    }

    @Test
    void sameArtifactRebuilds_keepRoles() {
        SourceDescriptor d = withRoles();
        SourceDescriptor parented = SourceDescriptorSupport.appendParent(d, "parent-1");
        assertEquals("observedAt", parented.timeColumn());
        assertEquals("reading", parented.valueColumn());
        assertEquals(List.of("parent-1"), parented.parentSourceCacheIds());

        SourceDescriptor completed = DerivedArtifactLineage.withCompleteness(d,
                SourceDescriptor.CompletenessStatus.PARTIAL);
        assertEquals("observedAt", completed.timeColumn());
        assertEquals("reading", completed.valueColumn());
        assertEquals(SourceDescriptor.CompletenessStatus.PARTIAL, completed.completenessStatus());
    }

    @Test
    void newTables_doNotInheritRoles() {
        SourceDescriptor d = withRoles();
        assertNull(d.composeDerived("tabulate", "parent-1").timeColumn());
        assertNull(d.composeDerived("tabulate", "parent-1").valueColumn());

        SourceDescriptor derived = SourceDescriptorSupport.forDerivedStore(d, "parent-1", table("a", "b"), "tabulate");
        assertNull(derived.timeColumn());
        assertNull(derived.valueColumn());
        assertEquals(List.of("parent-1"), derived.parentSourceCacheIds());

        SourceDescriptor joined = DerivedArtifactLineage.forJoin(d, "left-1", withRoles(), "right-1",
                table("a"), "exact_join", true);
        assertNull(joined.timeColumn());
        assertNull(joined.valueColumn());

        SourceDescriptor primary = SourceDescriptorSupport.forPrimaryStore(table("observedAt", "reading"), "x");
        assertNull(primary.timeColumn());
    }

    @Test
    void withColumnRoles_validatesAgainstWrittenColumns() {
        SourceDescriptor base = SourceDescriptorSupport.forPrimaryStore(table("observedAt", "reading", "site"), "x");
        SourceDescriptor ok = SourceDescriptorSupport.withColumnRoles(base, "observedAt", "reading",
                List.of("observedAt", "reading", "site"));
        assertEquals("observedAt", ok.timeColumn());
        assertEquals("reading", ok.valueColumn());
        assertEquals("x", ok.sourceRouteId());

        SourceDescriptor absent = SourceDescriptorSupport.withColumnRoles(base, "observedAt", "missing",
                List.of("observedAt", "reading", "site"));
        assertNull(absent.timeColumn());
        assertNull(absent.valueColumn());

        SourceDescriptor same = SourceDescriptorSupport.withColumnRoles(base, "reading", "reading",
                List.of("observedAt", "reading"));
        assertNull(same.valueColumn());
    }

    @Test
    void withColumnRoles_replacesEarlierRoles_andClearsThemWhenInvalid() {
        SourceDescriptor base = SourceDescriptorSupport.withSemanticProvenance(
                SourceDescriptorSupport.appendParent(
                        SourceDescriptor.builder().sourceRouteId("x").rowsReturned(2L)
                                .propertyRoleRef("Type.propertyRole.temp").timeColumn("t").valueColumn("old").build(),
                        "parent-9"),
                com.thingworx.things.agent.semantics.SemanticResolveResult.notFound("n/a"));
        assertEquals("old", base.valueColumn());
        List<String> visible = List.of("t", "old", "reading");

        SourceDescriptor missing = SourceDescriptorSupport.withColumnRoles(base, "missing", "bad", visible);
        assertNull(missing.timeColumn(), "stale roles must not survive an invalid replacement");
        assertNull(missing.valueColumn());
        assertEquals("x", missing.sourceRouteId());
        assertEquals(Long.valueOf(2L), missing.rowsReturned());
        assertEquals(List.of("parent-9"), missing.parentSourceCacheIds());
        assertEquals("Type.propertyRole.temp", missing.propertyRoleRef());

        SourceDescriptor conflict = SourceDescriptorSupport.withColumnRoles(base, "t", "t", visible);
        assertNull(conflict.timeColumn());
        assertNull(conflict.valueColumn());

        SourceDescriptor blank = SourceDescriptorSupport.withColumnRoles(base, " ", null, visible);
        assertNull(blank.timeColumn());
        assertNull(blank.valueColumn());

        SourceDescriptor replaced = SourceDescriptorSupport.withColumnRoles(base, "t", "reading", visible);
        assertEquals("t", replaced.timeColumn());
        assertEquals("reading", replaced.valueColumn());
    }

    @Test
    void columnRoles_validate_rules() {
        List<String> visible = List.of("ts", "value", "label"); // PASSWORD columns are not visible
        assertNotNull(ColumnRoles.validate("ts", "value", visible));
        assertNull(ColumnRoles.validate("ts", "value", null));
        assertNull(ColumnRoles.validate(null, "value", visible));
        assertNull(ColumnRoles.validate("ts", "", visible));
        assertNull(ColumnRoles.validate("ts", "ts", visible));
        assertNull(ColumnRoles.validate("TS", "value", visible), "exact match only; case is not a role proof");
        assertNull(ColumnRoles.validate("ts", "secret", visible), "a protected column is not visible");
        assertNull(ColumnRoles.fromDescriptor(null, visible));
        assertNull(ColumnRoles.fromDescriptor(SourceDescriptor.builder().build(), visible));
        ColumnRoles fromDesc = ColumnRoles.fromDescriptor(
                SourceDescriptor.builder().timeColumn("ts").valueColumn("value").build(), visible);
        assertNotNull(fromDesc);
        assertEquals("value", fromDesc.valueColumn());
    }

    @Test
    void subjectIdentity_keptOnDecorations_droppedOnNewTables_replacedByDeclaration() {
        SourceDescriptor base = SourceDescriptor.builder().sourceRouteId("x").rowsReturned(2L)
                .propertyRoleRef("Type.propertyRole.temp").timeColumn("t").valueColumn("v")
                .subjectThingName("SE.Thing.A").subjectPropertyName("temperature").build();
        assertEquals("SE.Thing.A", base.subjectThingName());

        SourceDescriptor parented = SourceDescriptorSupport.appendParent(base, "parent-1");
        assertEquals("SE.Thing.A", parented.subjectThingName());
        assertEquals("temperature", parented.subjectPropertyName());
        SourceDescriptor completed = DerivedArtifactLineage.withCompleteness(base,
                SourceDescriptor.CompletenessStatus.PARTIAL);
        assertEquals("SE.Thing.A", completed.subjectThingName());

        assertNull(base.composeDerived("tabulate", "p").subjectThingName());
        assertNull(SourceDescriptorSupport.forDerivedStore(base, "p", table("a"), "tabulate").subjectThingName());
        assertNull(DerivedArtifactLineage.forJoin(base, "l", base, "r", table("a"), "exact_join", true)
                .subjectThingName(), "multi-source results never claim a single parent's subject");

        // Declaration replaces; incomplete pair clears both; roles and provenance survive either way.
        SourceDescriptor replaced = SourceDescriptorSupport.withSubjectIdentity(base, "SE.Thing.B", "pressure");
        assertEquals("SE.Thing.B", replaced.subjectThingName());
        assertEquals("pressure", replaced.subjectPropertyName());
        assertEquals("v", replaced.valueColumn());
        assertEquals("Type.propertyRole.temp", replaced.propertyRoleRef());
        SourceDescriptor cleared = SourceDescriptorSupport.withSubjectIdentity(base, "SE.Thing.B", " ");
        assertNull(cleared.subjectThingName());
        assertNull(cleared.subjectPropertyName());
        assertEquals("t", cleared.timeColumn());
        SourceDescriptor missing = SourceDescriptorSupport.withSubjectIdentity(base, null, null);
        assertNull(missing.subjectThingName());

        // Roles and identity never clear each other.
        SourceDescriptor rolesUpdated = SourceDescriptorSupport.withColumnRoles(base, "t", "v", List.of("t", "v"));
        assertEquals("SE.Thing.A", rolesUpdated.subjectThingName());
        SourceDescriptor rolesInvalid = SourceDescriptorSupport.withColumnRoles(base, "t", "nope", List.of("t", "v"));
        assertNull(rolesInvalid.valueColumn());
        assertEquals("SE.Thing.A", rolesInvalid.subjectThingName());
    }
}
