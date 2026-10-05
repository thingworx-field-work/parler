package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Custom override path for the resolver (design §3.3): the orchestration emits
 * {@code resolverSource: custom} when the override yields ≥1 valid documentId, and falls
 * through to the built-in matcher ({@code default-match}/{@code default-empty}) otherwise;
 * plus the Postel {@code documentId} parser. The override invocation itself
 * (processServiceRequestDirect) is the thin platform seam and is not unit-tested here.
 */
class DocumentKnowledgeResolveCustomPathTest {

    @Test
    @SuppressWarnings("unchecked")
    void custom_override_with_valid_ids_yields_custom_source() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        // override resolves the key to two documents (regardless of the built-in matcher)
        DocumentKnowledgeRuntime.CustomDocumentSetResolver custom =
                key -> DocumentKnowledgeRuntime.CustomResolution.of(
                        List.of("rk-t-install-spec-7318042", "fernwick-carbaq-ops-v2"));
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(
                index, "anything the override understands", false, custom);
        assertEquals("custom", body.get("resolverSource"));
        assertEquals(
                List.of(
                        Map.of("documentId", "rk-t-install-spec-7318042",
                                "alwaysInclude", false, "appliesToMany", false),
                        Map.of("documentId", "fernwick-carbaq-ops-v2",
                                "alwaysInclude", false, "appliesToMany", false)),
                body.get("documents"));
    }

    @Test
    void empty_override_falls_through_to_default_match() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeRuntime.CustomDocumentSetResolver emptyOverride =
                key -> DocumentKnowledgeRuntime.CustomResolution.empty();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(
                index, "RK&T CB 24 GT4 turbo-generator set", false, emptyOverride);
        assertEquals("default-match", body.get("resolverSource"));
        assertEquals(
                List.of(Map.of("documentId", "rk-t-operating-manual-7318042",
                        "alwaysInclude", false, "appliesToMany", false)),
                body.get("documents"));
    }

    @Test
    void empty_override_and_no_match_falls_through_to_default_empty() throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeFixtures.loadIndex();
        DocumentKnowledgeRuntime.CustomDocumentSetResolver emptyOverride =
                key -> DocumentKnowledgeRuntime.CustomResolution.empty();
        Map<String, Object> body = DocumentKnowledgeRuntime.resolveDocumentSet(
                index, "Unknown Asset 9000", false, emptyOverride);
        assertEquals("default-empty", body.get("resolverSource"));
        assertTrue(((List<?>) body.get("documents")).isEmpty());
    }

    @Test
    void parse_resolved_doc_ids_trims_drops_blanks_and_dedupes_in_order() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("documentId", "", BaseTypes.STRING));
        InfoTable table = new InfoTable(shape);
        addRow(table, "  doc-a ");
        addRow(table, "");
        addRow(table, "doc-b");
        addRow(table, "doc-a");
        assertEquals(List.of("doc-a", "doc-b"), DocumentKnowledgeRuntime.parseResolvedDocIds(table));
    }

    @Test
    void parse_resolved_doc_ids_empty_table_is_empty() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("documentId", "", BaseTypes.STRING));
        assertTrue(DocumentKnowledgeRuntime.parseResolvedDocIds(new InfoTable(shape)).isEmpty());
    }

    @Test
    void parse_resolved_documents_captures_cross_cutting_flags() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("documentId", "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition("alwaysInclude", "", BaseTypes.BOOLEAN));
        shape.addFieldDefinition(new FieldDefinition("appliesToMany", "", BaseTypes.BOOLEAN));
        InfoTable table = new InfoTable(shape);
        addRow(table, "key-doc", false, false);
        addRow(table, "safety-doc", true, true);
        DocumentKnowledgeRuntime.CustomResolution parsed =
                DocumentKnowledgeRuntime.parseResolvedDocuments(table);
        assertEquals(List.of("key-doc", "safety-doc"), parsed.documentIds());
        assertEquals(java.util.Set.of("safety-doc"), parsed.alwaysIncludeIds());
        assertEquals(java.util.Set.of("safety-doc"), parsed.appliesToManyIds());
    }

    private static void addRow(InfoTable table, String documentId) {
        ValueCollection row = new ValueCollection();
        row.put("documentId", new StringPrimitive(documentId));
        table.addRow(row);
    }

    private static void addRow(InfoTable table, String documentId, boolean alwaysInclude, boolean appliesToMany) {
        ValueCollection row = new ValueCollection();
        row.put("documentId", new StringPrimitive(documentId));
        row.put("alwaysInclude", new com.thingworx.types.primitives.BooleanPrimitive(alwaysInclude));
        row.put("appliesToMany", new com.thingworx.types.primitives.BooleanPrimitive(appliesToMany));
        table.addRow(row);
    }
}
