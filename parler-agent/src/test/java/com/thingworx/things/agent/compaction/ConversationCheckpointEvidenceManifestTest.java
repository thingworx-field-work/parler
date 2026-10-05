package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.BuiltInTools;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.ToolRegistry;

/**
 * §6.2 of {@code docs/core/advanced-compact.md}: refs come only from complete, id-paired tool rows carrying one of
 * five accepted formats; identity comes from execution rather than the body; nothing invents liveness.
 */
class ConversationCheckpointEvidenceManifestTest {

    private static List<ChatMessage> batch(String tool, String toolCallId, String body) {
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(ChatMessage.user("please look"));
        rows.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall(toolCallId, tool, "{}"))));
        rows.add(ChatMessage.toolResult(toolCallId, body, tool));
        return rows;
    }

    private static List<ConversationCheckpoint.EvidenceRef> refsFor(String tool, String body) {
        return ConversationCheckpointEvidenceManifest.build(batch(tool, "call-1", body), null);
    }

    /** A real Tier B summary envelope: success, result kind, row count, columns. */
    private static String summary() {
        return "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                + "\",\"resultKind\":\"tabular\",\"cacheId\":\"cache-9\",\"completeness\":\"complete\","
                + "\"sampleOnly\":false,\"rowCount\":42,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}]}";
    }

    /**
     * A sealed matrix shaped as the encoder emits one: an eligible result kind, columns carrying both name and
     * baseType, and array rows whose width matches the column count.
     */
    private static String matrix() {
        return "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"resultKind\":\"INFOTABLE\",\"cacheId\":\"cache-m\","
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"NUMBER\"}],\"rows\":[[1],[2]]}";
    }

    private static String entitySummary() {
        return "{\"status\":\"success\",\"$format\":\""
                + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1
                + "\",\"entityType\":\"Thing\",\"entityName\":\"Pump17\",\"cacheId\":\"c1\","
                + "\"properties\":[{\"name\":\"flow\",\"baseType\":\"NUMBER\"}]}";
    }

    // --- accepted formats ---------------------------------------------------------------------------

    @Test
    void acceptsInfotableSummaryAndExtractsBoundedMetadataOnly() {
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("query_entities", summary());

        assertEquals(1, refs.size());
        ConversationCheckpoint.EvidenceRef r = refs.get(0);
        assertEquals("call-1", r.toolCallId());
        assertEquals("query_entities", r.tool());
        assertEquals("tabular", r.resultKind());
        assertEquals("cache-9", r.cacheId());
        assertEquals("complete", r.completeness());
        assertFalse(r.sampleOnly());
        // A ref is a pointer, never a copy: no body field beyond the schema's bounded metadata rides along.
        assertFalse((r.resultKind() + r.cacheId() + r.completeness() + r.recomputeTool()).contains("42"));
    }

    @Test
    void acceptsEntityMetadataSummary() {
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("describe_entity_schema", entitySummary());
        assertEquals(1, refs.size());
        assertEquals(ConversationCheckpoint.EvidenceRef.FAMILY_ENTITY_METADATA_SUMMARY,
                refs.get(0).evidenceFormat());
    }

    @Test
    void acceptsASampledMatrixUsingSampleRows() {
        // The large-table producer writes sampleRows rather than rows; both are the sealed shape.
        String sampled = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"resultKind\":\"INFOTABLE_LARGE\",\"cacheId\":\"c-s\","
                + "\"columns\":[{\"name\":\"n\",\"baseType\":\"STRING\"}],"
                + "\"sampleRows\":[[\"a\"],[\"b\"]]}";
        assertEquals(1, refsFor("query_entities", sampled).size());
    }

    @Test
    void acceptsValidatedMatrix() {
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("query_entities", matrix());
        assertEquals(1, refs.size());
        assertEquals("cache-m", refs.get(0).cacheId());
    }

    @Test
    void acceptsCohortBundleWrappingAValidatedResult() {
        String bundle = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\",\"cacheId\":\"c-b\","
                + "\"members\":[\"a\",\"b\"],\"result\":" + matrix() + "}";
        assertEquals(1, refsFor("query_entities", bundle).size());

        String bundleWithSummary = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\","
                + "\"members\":[\"a\"],\"result\":" + summary() + "}";
        assertEquals(1, refsFor("query_entities", bundleWithSummary).size());
    }

    @Test
    void acceptsCompactFetchSuccessFamily() {
        // The shipped policy defines legacy structural compact-fetch success as status=success + cacheId +
        // sampleOnly/rowsOmitted. The manifest delegates rather than re-deriving it.
        String legacyStructural = "{\"status\":\"success\",\"resultKind\":\"tabular\",\"cacheId\":\"c-f\","
                + "\"sampleOnly\":true,\"columns\":[{\"name\":\"ts\",\"baseType\":\"DATETIME\"}],"
                + "\"rowCount\":3}";
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("fetch_cached_result", legacyStructural);
        assertEquals(1, refs.size(), "the shipped Stage-2 acceptance policy decides this family");
        assertEquals("c-f", refs.get(0).cacheId());
        assertTrue(refs.get(0).sampleOnly(), "sample status is bounded metadata the ref must carry");
    }

    @Test
    void aCompactFetchBodyThePolicyRejectsProducesNoRef() {
        // Same shape without the sample/omitted marker: not compact-fetch success, so not a ref. The manifest must
        // never be more permissive than Stage-2 rehydrate already is.
        String notCompact = "{\"status\":\"success\",\"resultKind\":\"tabular\",\"cacheId\":\"c-f\","
                + "\"columns\":[{\"name\":\"ts\",\"baseType\":\"DATETIME\"}],\"rowCount\":3}";
        assertTrue(refsFor("fetch_cached_result", notCompact).isEmpty());
    }

    @Test
    void everyAcceptedRefRecordsItsAdmittingFamily() {
        assertEquals(ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                refsFor("query_entities", summary()).get(0).evidenceFormat());
        assertEquals(ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_MATRIX,
                refsFor("query_entities", matrix()).get(0).evidenceFormat());
        String bundle = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\","
                + "\"members\":[\"a\"],\"result\":" + matrix() + "}";
        assertEquals(ConversationCheckpoint.EvidenceRef.FAMILY_COHORT_BUNDLE,
                refsFor("query_entities", bundle).get(0).evidenceFormat());
    }

    // --- entity summaries must match their producer -------------------------------

    @Test
    void entitySummaryWithoutAMemberPayloadMintsNoRef() {
        // The producer returns null unless properties, services, or events survive; identity alone is unproducible.
        String identityOnly = "{\"status\":\"success\",\"$format\":\""
                + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1
                + "\",\"entityType\":\"Thing\",\"entityName\":\"Pump17\"}";
        assertTrue(refsFor("describe_entity_schema", identityOnly).isEmpty());

        String emptyMembers = identityOnly.replace("\"Pump17\"", "\"Pump17\",\"properties\":[]");
        assertTrue(refsFor("describe_entity_schema", emptyMembers).isEmpty());

        String unnamedMembers = identityOnly.replace("\"Pump17\"",
                "\"Pump17\",\"properties\":[{\"baseType\":\"NUMBER\"}]");
        assertTrue(refsFor("describe_entity_schema", unnamedMembers).isEmpty());
    }

    @Test
    void entitySummaryWithAPasswordPropertyMintsNoRef() {
        // Base types live under properties[], not columns; a columns-only gate never sees this.
        String withPassword = "{\"status\":\"success\",\"$format\":\""
                + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1
                + "\",\"entityType\":\"Thing\",\"entityName\":\"Pump17\","
                + "\"properties\":[{\"name\":\"flow\",\"baseType\":\"NUMBER\"},"
                + "{\"name\":\"pw\",\"baseType\":\"PASSWORD\"}]}";
        assertTrue(refsFor("describe_entity_schema", withPassword).isEmpty());
    }

    @Test
    void entitySummaryAcceptsServicesOrEventsAlone() {
        String servicesOnly = "{\"status\":\"success\",\"$format\":\""
                + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1
                + "\",\"entityType\":\"Thing\",\"entityName\":\"Pump17\","
                + "\"services\":[{\"name\":\"Restart\"}]}";
        assertEquals(1, refsFor("describe_entity_schema", servicesOnly).size());
    }

    // --- relabelled raw content ---------------------------------------------------

    @Test
    void aFullyPopulatedSummaryCarryingRowsMintsNoRef() {
        // Every required summary field present, plus raw rows the summary producer never emits.
        String relabelled = summary().replace("\"rowCount\":42",
                "\"rowCount\":42,\"rows\":[{\"secret\":\"raw\"}]");
        assertTrue(refsFor("query_entities", relabelled).isEmpty());

        String withSampleRows = summary().replace("\"rowCount\":42",
                "\"rowCount\":42,\"sampleRows\":[[\"a\"]]");
        assertTrue(refsFor("query_entities", withSampleRows).isEmpty());
    }

    // --- matrix shape comes from the producer, not a second interpretation -----------------

    /** Feeds a real tool body through the real encoder, so the manifest sees exactly what ships. */
    private static String sealed(String rowsKey, String resultKind) {
        StringBuilder rows = new StringBuilder();
        String pad = "q".repeat(400);
        for (int r = 0; r < 40; r++) {
            rows.append(r > 0 ? "," : "")
                    .append("{\"name\":\"Thing").append(r).append("\",\"region\":\"US\",\"notes\":\"")
                    .append(pad).append(r % 3).append("\"}");
        }
        String body = "{\"status\":\"success\",\"resultKind\":\"" + resultKind + "\",\"cacheId\":\"c-enc\","
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                + "\"" + rowsKey + "\":[" + rows + "]}";
        String encoded = InfoTableMatrixCodec.encodeIfEligible(body, null);
        assertTrue(encoded.contains(InfoTableMatrixCodec.FORMAT_MATRIX_V1),
                "fixture precondition: the encoder must actually seal this body");
        return encoded;
    }

    @Test
    void acceptsRealEncoderOutputForEveryRowKey() {
        // rootEntityList/sampleRootEntityList are the taxonomy paths behind the allowlisted
        // query_entities_by_taxonomy tool; rejecting them would drop valid evidence.
        assertEquals(1, refsFor("query_entities", sealed("rows", "INFOTABLE")).size());
        assertEquals(1, refsFor("query_entities", sealed("sampleRows", "INFOTABLE_LARGE")).size());
        assertEquals(1, refsFor("query_entities_by_taxonomy",
                sealed("rootEntityList", "ENTITY_TAXONOMY_QUERY_INLINE")).size());
        assertEquals(1, refsFor("query_entities_by_taxonomy",
                sealed("sampleRootEntityList", "ENTITY_TAXONOMY_QUERY_LARGE")).size());
    }

    @Test
    void acceptsRealEncoderOutputWithMixedAndAllConstantColumnElision() {
        // Mixed: one column constant across rows, so it moves to `constants` and leaves narrower cell arrays.
        StringBuilder mixed = new StringBuilder();
        String pad = "m".repeat(400);
        for (int r = 0; r < 40; r++) {
            mixed.append(r > 0 ? "," : "")
                    .append("{\"name\":\"T").append(r).append("\",\"region\":\"US\",\"notes\":\"")
                    .append(pad).append(r).append("\"}");
        }
        String mixedBody = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"cacheId\":\"c-mix\","
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[" + mixed + "]}";
        String mixedEncoded = InfoTableMatrixCodec.encodeIfEligible(mixedBody, null);
        assertTrue(mixedEncoded.contains("\"constants\""), "fixture precondition: a column was elided");
        assertEquals(1, refsFor("query_entities", mixedEncoded).size());

        // All-constant: every column elided, so the encoder emits columns:[] and empty cell arrays.
        StringBuilder allConst = new StringBuilder();
        String big = "z".repeat(600);
        for (int r = 0; r < 40; r++) {
            allConst.append(r > 0 ? "," : "")
                    .append("{\"region\":\"US\",\"notes\":\"").append(big).append("\"}");
        }
        String allConstBody = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"cacheId\":\"c-const\","
                + "\"columns\":[{\"name\":\"region\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[" + allConst + "]}";
        String allConstEncoded = InfoTableMatrixCodec.encodeIfEligible(allConstBody, null);
        assertTrue(allConstEncoded.contains(InfoTableMatrixCodec.FORMAT_MATRIX_V1)
                        && allConstEncoded.contains("\"columns\":[]"),
                "fixture precondition: the encoder seals this and elides every column");
        assertEquals(1, refsFor("query_entities", allConstEncoded).size(),
                "an all-constant matrix is a valid sealed shape, not a malformed one");
    }

    /** Parses real encoder output so a mutation targets the actual field rather than a hoped-for substring. */
    private static ObjectNode mutable(String encoded) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(encoded);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mutatingRealEncoderOutputIntoAProducerImpossibleShapeMintsNoRef() {
        String encoded = sealed("rows", "INFOTABLE");
        assertEquals(1, refsFor("query_entities", encoded).size(), "control: the unmutated body is admitted");

        // The encoder refuses a present resultKind outside ELIGIBLE_RESULT_KINDS.
        ObjectNode ineligibleKind = mutable(encoded);
        ineligibleKind.put("resultKind", "NOT_AN_ELIGIBLE_KIND");
        assertTrue(refsFor("query_entities", ineligibleKind.toString()).isEmpty());

        // Every surviving column is written with a textual baseType, defaulting to STRING rather than omitted.
        ObjectNode noBaseType = mutable(encoded);
        ((ObjectNode) noBaseType.get("columns").get(0)).remove("baseType");
        assertTrue(refsFor("query_entities", noBaseType.toString()).isEmpty(),
                "a column without a type is producer-impossible and would slip past the PASSWORD gate");

        // shallowCopyWithoutRows strips all four row keys, so a second one — even explicitly null — cannot appear.
        ObjectNode nullSecondRowKey = mutable(encoded);
        nullSecondRowKey.putNull("sampleRows");
        assertTrue(refsFor("query_entities", nullSecondRowKey.toString()).isEmpty());
    }

    @Test
    void anAllConstantMatrixWithoutConstantsMintsNoRef() {
        // columns:[] is legitimate only as the all-constant representation, which moved at least one column into a
        // non-empty constants object.
        StringBuilder allConst = new StringBuilder();
        String big = "z".repeat(600);
        for (int r = 0; r < 40; r++) {
            allConst.append(r > 0 ? "," : "").append("{\"region\":\"US\",\"notes\":\"").append(big).append("\"}");
        }
        String encoded = InfoTableMatrixCodec.encodeIfEligible(
                "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"cacheId\":\"c-const\","
                        + "\"columns\":[{\"name\":\"region\",\"baseType\":\"STRING\"},"
                        + "{\"name\":\"notes\",\"baseType\":\"STRING\"}],"
                        + "\"rows\":[" + allConst + "]}", null);
        assertEquals(1, refsFor("query_entities", encoded).size(), "control: the real all-constant output is admitted");

        ObjectNode stripped = mutable(encoded);
        assertTrue(stripped.has("constants"), "fixture precondition: the encoder emitted constants");
        stripped.remove("constants");
        assertTrue(refsFor("query_entities", stripped.toString()).isEmpty());

        ObjectNode emptied = mutable(encoded);
        emptied.putObject("constants");
        assertTrue(refsFor("query_entities", emptied.toString()).isEmpty());
    }

    @Test
    void anEmptyMatrixRowCollectionMintsNoRef() {
        // The encoder returns the original body unsealed for an empty source row array, so a sealed matrix with
        // zero rows is a shape it cannot emit.
        String emptyRows = matrix().replace("\"rows\":[[1],[2]]", "\"rows\":[]");
        assertTrue(refsFor("query_entities", emptyRows).isEmpty());
    }

    @Test
    void aMatrixWithTwoRowCollectionsMintsNoRef() {
        String dual = matrix().replace("\"rows\":[[1],[2]]",
                "\"rows\":[[1],[2]],\"sampleRows\":[{\"raw\":1}]");
        assertTrue(refsFor("query_entities", dual).isEmpty(),
                "a second collection alongside the validated one would ride along unchecked");

        String withPageField = matrix().replace("\"rows\":[[1],[2]]",
                "\"rows\":[[1],[2]],\"rootEntityList\":[[9]]");
        assertTrue(refsFor("query_entities", withPageField).isEmpty(),
                "two collections mean the body was not sealed by the codec, which drops all four keys");
    }

    @Test
    void aMatrixRowWidthMustMatchTheDeclaredColumns() {
        String twoCols = "\"resultKind\":\"INFOTABLE\",\"columns\":[{\"name\":\"a\",\"baseType\":\"NUMBER\"},"
                + "{\"name\":\"b\",\"baseType\":\"NUMBER\"}]";
        String wrongWidth = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\"," + twoCols + ",\"rows\":[[1]]}";
        assertTrue(refsFor("query_entities", wrongWidth).isEmpty());

        String rightWidth = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\"," + twoCols + ",\"rows\":[[1,2]]}";
        assertEquals(1, refsFor("query_entities", rightWidth).size());
    }

    // --- a format marker is not a success envelope --------------------------------

    @Test
    void markerOnlyBodiesMintNoRef() {
        assertTrue(refsFor("query_entities",
                "{\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1 + "\"}").isEmpty());
        assertTrue(refsFor("describe_entity_schema",
                "{\"$format\":\"" + EntityMetadataSummaryCodec.FORMAT_ENTITY_METADATA_SUMMARY_V1 + "\"}")
                .isEmpty());
        assertTrue(refsFor("query_entities",
                "{\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1 + "\"}").isEmpty());
    }

    @Test
    void errorStatusUnderAnAcceptedFormatMintsNoRef() {
        assertTrue(refsFor("query_entities", summary().replace("\"success\"", "\"error\"")).isEmpty());
        assertTrue(refsFor("query_entities", matrix().replace("\"success\"", "\"error\"")).isEmpty());
        assertTrue(refsFor("describe_entity_schema", entitySummary().replace("\"success\"", "\"error\""))
                .isEmpty());
        // A missing status is equally not an accepted success envelope.
        assertTrue(refsFor("query_entities", summary().replace("\"status\":\"success\",", "")).isEmpty());
    }

    @Test
    void rawContentRelabelledWithAnAcceptedFormatMintsNoRef() {
        String relabelledRaw = matrix().replace("\"rows\":[[1],[2]]", "\"rows\":[{\"a\":1},{\"a\":2}]");
        assertTrue(refsFor("query_entities", relabelledRaw).isEmpty());

        String summaryMissingRequiredFields = "{\"status\":\"success\",\"$format\":\""
                + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1 + "\",\"rows\":[{\"a\":1}]}";
        assertTrue(refsFor("query_entities", summaryMissingRequiredFields).isEmpty());
    }

    @Test
    void matrixWithMissingNullOrOversizedRowsMintsNoRef() {
        String noRows = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"a\"}]}";
        assertTrue(refsFor("query_entities", noRows).isEmpty());

        String nullRows = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"a\"}],\"rows\":null}";
        assertTrue(refsFor("query_entities", nullRows).isEmpty());

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i <= ConversationCheckpointEvidenceManifest.MAX_EVIDENCE_ROWS; i++) {
            rows.append(i > 0 ? "," : "").append("[").append(i).append("]");
        }
        String oversized = "{\"status\":\"success\",\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1
                + "\",\"columns\":[{\"name\":\"a\"}],\"rows\":[" + rows + "]}";
        assertTrue(refsFor("query_entities", oversized).isEmpty(), "row count must be bounded");
    }

    @Test
    void aBundleWrappingAnInvalidInnerBodyMintsNoRef() {
        for (String inner : new String[] {
                "{\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1 + "\"}",
                matrix().replace("\"success\"", "\"error\""),
                matrix().replace("\"rows\":[[1],[2]]", "\"rows\":[{\"a\":1}]"),
                "{\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1 + "\"}" }) {
            String bundle = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\","
                    + "\"members\":[\"a\"],\"result\":" + inner + "}";
            assertTrue(refsFor("query_entities", bundle).isEmpty(),
                    "an inner body must meet the same standard as a standalone one");
        }
    }

    @Test
    void aBundleWithoutMembersMintsNoRef() {
        String noMembers = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\",\"result\":"
                + matrix() + "}";
        assertTrue(refsFor("query_entities", noMembers).isEmpty());
    }

    // --- rejected inputs ----------------------------------------------------------------------------

    @Test
    void rejectsUnknownAndUnmarkedBodies() {
        assertTrue(refsFor("query_entities", "{\"$format\":\"parler.something.else.v1\"}").isEmpty());
        assertTrue(refsFor("query_entities", "{\"rows\":[{\"a\":1}]}").isEmpty(),
                "unmarked generic JSON must not become a ref");
        assertTrue(refsFor("query_entities", "{}").isEmpty());
        assertTrue(refsFor("query_entities", "not json").isEmpty());
        assertTrue(refsFor("query_entities", "[]").isEmpty());
        assertTrue(refsFor("query_entities", "").isEmpty());
    }

    @Test
    void rejectsErrorShellsAndPasswordBearingBodies() {
        assertTrue(refsFor("fetch_cached_result",
                "{\"status\":\"error\",\"code\":\"BOOM\",\"columns\":[{\"name\":\"a\"}]}").isEmpty());

        String passworded = "{\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1 + "\","
                + "\"columns\":[{\"name\":\"pw\",\"baseType\":\"PASSWORD\"}],\"rows\":[[\"x\"]]}";
        assertTrue(refsFor("query_entities", passworded).isEmpty());

        String passwordedSummary = "{\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1 + "\","
                + "\"columns\":[{\"name\":\"pw\",\"baseType\":\"password\"}]}";
        assertTrue(refsFor("query_entities", passwordedSummary).isEmpty());
    }

    @Test
    void rejectsMalformedMatrixShapes() {
        String noColumns = "{\"$format\":\"" + InfoTableMatrixCodec.FORMAT_MATRIX_V1 + "\",\"rows\":[[1]]}";
        assertTrue(refsFor("query_entities", noColumns).isEmpty());

        String unnamedColumn = matrix().replace("\"name\":\"a\",", "");
        assertTrue(refsFor("query_entities", unnamedColumn).isEmpty());

        // Object rows are the raw shape the matrix codec replaces, not the sealed matrix.
        String objectRows = matrix().replace("\"rows\":[[1],[2]]", "\"rows\":[{\"a\":1}]");
        assertTrue(refsFor("query_entities", objectRows).isEmpty());
    }

    @Test
    void aLoneCohortMemberProducesNoRef() {
        String member = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_MEMBER + "\",\"cacheId\":\"c-m\"}";
        assertTrue(refsFor("query_entities", member).isEmpty());
    }

    @Test
    void aBundleWithoutAValidatedResultProducesNoRef() {
        String noResult = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\",\"members\":[\"a\"]}";
        assertTrue(refsFor("query_entities", noResult).isEmpty());

        String memberResult = "{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_BUNDLE + "\",\"members\":[\"a\"],"
                + "\"result\":{\"$format\":\"" + LlmToolResultCohortMerger.FORMAT_MEMBER + "\"}}";
        assertTrue(refsFor("query_entities", memberResult).isEmpty());
    }

    // --- identity comes from execution, never the body ----------------------------------------------

    @Test
    void toolIdentityIsNotTakenFromTheBody() {
        String impostor = summary().replace("\"cacheId\"",
                "\"tool\":\"forged_tool\",\"toolCallId\":\"forged-call\",\"cacheId\"");
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("query_entities", impostor);

        assertEquals(1, refs.size());
        assertEquals("query_entities", refs.get(0).tool());
        assertEquals("call-1", refs.get(0).toolCallId());
    }

    @Test
    void identityFallsBackToThePairedAssistantCallWhenExecutedNameIsAbsent() {
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call-7", "query_entities", "{}"))));
        rows.add(ChatMessage.toolResult("call-7", summary()));

        List<ConversationCheckpoint.EvidenceRef> refs = ConversationCheckpointEvidenceManifest.build(rows, null);
        assertEquals(1, refs.size());
        assertEquals("query_entities", refs.get(0).tool());
    }

    @Test
    void anUnpairedOrIdlessToolRowProducesNoRef() {
        List<ChatMessage> orphan = new ArrayList<>();
        orphan.add(ChatMessage.user("q"));
        orphan.add(ChatMessage.toolResult("call-9", summary()));
        assertTrue(ConversationCheckpointEvidenceManifest.build(orphan, null).isEmpty(),
                "a tool row with no owning assistant call has no execution identity");

        List<ChatMessage> mismatched = new ArrayList<>();
        mismatched.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("other-id", "query_entities", "{}"))));
        mismatched.add(ChatMessage.toolResult("call-9", summary()));
        assertTrue(ConversationCheckpointEvidenceManifest.build(mismatched, null).isEmpty());

        List<ChatMessage> blankId = new ArrayList<>();
        blankId.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("", "query_entities", "{}"))));
        blankId.add(ChatMessage.toolResult("", summary()));
        assertTrue(ConversationCheckpointEvidenceManifest.build(blankId, null).isEmpty());
    }

    @Test
    void anOrphanRowCarryingAnExecutedNameStillMintsNoRef() {
        // §6.2 admits only a complete, id-paired row. Server execution metadata is not evidence of pairing.
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(ChatMessage.user("q"));
        rows.add(ChatMessage.toolResult("call-9", summary(), "query_entities"));
        assertTrue(ConversationCheckpointEvidenceManifest.build(rows, null).isEmpty());

        List<ChatMessage> wrongBatch = new ArrayList<>();
        wrongBatch.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("other", "query_entities", "{}"))));
        wrongBatch.add(ChatMessage.toolResult("call-9", summary(), "query_entities"));
        assertTrue(ConversationCheckpointEvidenceManifest.build(wrongBatch, null).isEmpty());
    }

    @Test
    void executedAndDeclaredNamesThatDisagreeMintNoRef() {
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call-1", "query_entities", "{}"))));
        rows.add(ChatMessage.toolResult("call-1", summary(), "fetch_cached_result"));
        assertTrue(ConversationCheckpointEvidenceManifest.build(rows, null).isEmpty(),
                "two disagreeing server identities for one call cannot be resolved by guessing");

        List<ChatMessage> agreeing = new ArrayList<>();
        agreeing.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call-1", "query_entities", "{}"))));
        agreeing.add(ChatMessage.toolResult("call-1", summary(), "query_entities"));
        assertEquals(1, ConversationCheckpointEvidenceManifest.build(agreeing, null).size());
    }

    @Test
    void stageTwoFramedAssistantEvidenceCreatesNoRef() {
        // §6.2: framed assistant provenance may enter the tail and the summary input, but without its original
        // paired tool row it cannot mint a new ref.
        List<ChatMessage> rows = new ArrayList<>();
        rows.add(ChatMessage.user("q"));
        rows.add(ChatMessage.assistant(
                CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX + summary()));
        assertTrue(ConversationCheckpointEvidenceManifest.build(rows, null).isEmpty());
    }

    // --- liveness and recompute ---------------------------------------------------------------------

    @Test
    void livenessIsHistoricalRecomputeWithoutACurrentJvmLookup() {
        ConversationCheckpoint.EvidenceRef r = refsFor("query_entities", summary()).get(0);
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, r.liveness());
    }

    @Test
    void onlyACurrentJvmLookupCanSayLive() {
        List<ConversationCheckpoint.EvidenceRef> live = ConversationCheckpointEvidenceManifest.build(
                batch("query_entities", "call-1", summary()), cacheId -> "cache-9".equals(cacheId));
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, live.get(0).liveness());

        List<ConversationCheckpoint.EvidenceRef> dead = ConversationCheckpointEvidenceManifest.build(
                batch("query_entities", "call-1", summary()), cacheId -> false);
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, dead.get(0).liveness());
    }

    @Test
    void recomputeToolComesFromTheServerAllowlist() {
        assertEquals("query_entities", refsFor("query_entities", summary()).get(0).recomputeTool());
        // A write is not a recompute; the ref survives, the suggestion does not.
        List<ConversationCheckpoint.EvidenceRef> write = refsFor("set_property_value", summary());
        assertEquals(1, write.size());
        assertEquals("", write.get(0).recomputeTool());
        assertEquals("", refsFor("invoke_service", summary()).get(0).recomputeTool());
        assertEquals("", refsFor("some_deployment_specific_tool", summary()).get(0).recomputeTool());
    }

    // --- carry-forward revalidation -----------------------------------------------------------------

    @Test
    void carriedForwardRefsAreRevalidatedAndLivenessIsRedecided() {
        ConversationCheckpoint.EvidenceRef prior = new ConversationCheckpoint.EvidenceRef(
                "call-1", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "tabular", "cache-9", "complete", false, "live", "query_entities");

        // A persisted "live" never survives on its own authority.
        List<ConversationCheckpoint.EvidenceRef> dead =
                ConversationCheckpointEvidenceManifest.revalidateCarriedForward(List.of(prior), cacheId -> false);
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, dead.get(0).liveness());

        List<ConversationCheckpoint.EvidenceRef> stillLive =
                ConversationCheckpointEvidenceManifest.revalidateCarriedForward(List.of(prior), c -> true);
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_LIVE, stillLive.get(0).liveness());

        List<ConversationCheckpoint.EvidenceRef> noResolver =
                ConversationCheckpointEvidenceManifest.revalidateCarriedForward(List.of(prior), null);
        assertEquals(ConversationCheckpoint.EvidenceRef.LIVENESS_HISTORICAL_RECOMPUTE, noResolver.get(0).liveness());
    }

    @Test
    void carriedForwardRefsWithoutIdentityOrWithAWriteToolAreCorrected() {
        ConversationCheckpoint.EvidenceRef noIdentity = new ConversationCheckpoint.EvidenceRef(
                "", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "tabular", "c", "complete", false, "live", "query_entities");
        assertTrue(ConversationCheckpointEvidenceManifest
                .revalidateCarriedForward(List.of(noIdentity), null).isEmpty());

        ConversationCheckpoint.EvidenceRef forgedRecompute = new ConversationCheckpoint.EvidenceRef(
                "call-1", "set_property_value", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "tabular", "c", "complete", false, "live", "query_entities");
        assertEquals("", ConversationCheckpointEvidenceManifest
                .revalidateCarriedForward(List.of(forgedRecompute), null).get(0).recomputeTool(),
                "a recompute tool that does not match the producing tool's allowlist status is dropped");
    }

    @Test
    void carriedRefsWithoutARecognizedFamilyAreDropped() {
        for (String family : new String[] { "", "made-up-family", "infotable_summary" }) {
            ConversationCheckpoint.EvidenceRef forged = new ConversationCheckpoint.EvidenceRef(
                    "call-1", "query_entities", family, "tabular", "c", "complete", false, "live",
                    "query_entities");
            assertTrue(ConversationCheckpointEvidenceManifest
                    .revalidateCarriedForward(List.of(forged), null).isEmpty(),
                    "carry-forward must re-run the format allowlist, not trust the prior envelope");
        }
    }

    @Test
    void carriedRefsWithOverCapMetadataAreDroppedNotTruncated() {
        String over = "z".repeat(ConversationCheckpointCodec.MAX_ITEM_CHARS + 1);
        for (int field = 0; field < 5; field++) {
            ConversationCheckpoint.EvidenceRef forged = new ConversationCheckpoint.EvidenceRef(
                    field == 0 ? over : "call-1",
                    field == 1 ? over : "query_entities",
                    ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                    field == 2 ? over : "tabular",
                    field == 3 ? over : "c",
                    field == 4 ? over : "complete",
                    false, "live", "query_entities");
            assertTrue(ConversationCheckpointEvidenceManifest
                    .revalidateCarriedForward(List.of(forged), null).isEmpty(),
                    "over-cap carried field " + field + " must be dropped");
        }
    }

    @Test
    void carriedRefsThatSurviveAreStillSerializable() {
        ConversationCheckpoint.EvidenceRef prior = new ConversationCheckpoint.EvidenceRef(
                "call-1", "query_entities", ConversationCheckpoint.EvidenceRef.FAMILY_INFOTABLE_SUMMARY,
                "tabular", "cache-9", "complete", false, "live", "query_entities");
        List<ConversationCheckpoint.EvidenceRef> carried =
                ConversationCheckpointEvidenceManifest.revalidateCarriedForward(List.of(prior), null);
        assertEquals(1, carried.size());
        assertFalse(ConversationCheckpointCodec.serialize(new ConversationCheckpoint(
                new ConversationCheckpoint.Source("conv", "Agent", "amid"),
                new ConversationCheckpointSemantic("goal", List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                carried,
                List.of(new ConversationCheckpoint.RetainedRow("user", "q",
                        ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT)),
                new ConversationCheckpoint.Generated("t", "p", "m"))) == null);
    }

    // --- recompute names must be model-callable ----------------------------------

    @Test
    void everyRecomputeNameIsAdvertisedToTheModel() {
        // Parity against the real registry, not a hand-maintained list: an executor-only or gate-withdrawn name
        // would be advice the model cannot act on.
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry);
        Set<String> advertised = new HashSet<>();
        for (ToolDefinition d : registry.getAllDefinitions()) {
            advertised.add(d.getName());
        }
        for (String name : ConversationCheckpointEvidenceManifest.RECOMPUTE_TOOL_ALLOWLIST) {
            assertTrue(advertised.contains(name), "recompute name not advertised to the model: " + name);
        }
    }

    @Test
    void executorOnlyNamesAreNotOfferedAsRecompute() {
        for (String executorOnly : new String[] { "get_entity", "exact_join_cached_result",
                "quality_cached_result", "resample_cached_result", "rolling_cached_result",
                "rate_of_change_cached_result", "period_compare_cached_result" }) {
            assertEquals("", refsFor(executorOnly, summary()).get(0).recomputeTool(),
                    executorOnly + " is executor-only and must not be recommended");
        }
    }

    // --- bounds --------------------------------------------------------------------------------------

    @Test
    void refCountIsBounded() {
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 0; i < ConversationCheckpointEvidenceManifest.MAX_REFS + 20; i++) {
            rows.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c" + i, "query_entities", "{}"))));
            rows.add(ChatMessage.toolResult("c" + i, summary(), "query_entities"));
        }
        List<ConversationCheckpoint.EvidenceRef> refs = ConversationCheckpointEvidenceManifest.build(rows, null);
        assertEquals(ConversationCheckpointEvidenceManifest.MAX_REFS, refs.size());
    }

    @Test
    void overCapMetadataMintsNoRefRatherThanATruncatedOne() {
        String huge = "k".repeat(5_000);
        for (String field : new String[] { "resultKind", "cacheId", "completeness" }) {
            String body = "{\"status\":\"success\",\"$format\":\"" + LlmToolResultTierBPromoter.FORMAT_SUMMARY_V1
                    + "\",\"resultKind\":\"" + ("resultKind".equals(field) ? huge : "tabular")
                    + "\",\"cacheId\":\"" + ("cacheId".equals(field) ? huge : "c")
                    + "\",\"completeness\":\"" + ("completeness".equals(field) ? huge : "complete")
                    + "\",\"rowCount\":1,\"columns\":[{\"name\":\"a\"}]}";
            assertTrue(refsFor("query_entities", body).isEmpty(), "over-cap " + field + " must mint no ref");
        }
    }

    @Test
    void overCapExecutionIdentityMintsNoRef() {
        // Truncating an id would no longer equal either paired message and could collide with another truncated id.
        String hugeId = "i".repeat(ConversationCheckpointCodec.MAX_ITEM_CHARS + 1);
        assertTrue(ConversationCheckpointEvidenceManifest
                .build(batch("query_entities", hugeId, summary()), null).isEmpty());

        String hugeTool = "t".repeat(ConversationCheckpointCodec.MAX_ITEM_CHARS + 1);
        assertTrue(ConversationCheckpointEvidenceManifest
                .build(batch(hugeTool, "call-1", summary()), null).isEmpty());
    }

    @Test
    void builtRefsAreSerializableByTheCodec() {
        // The manifest must never produce something the envelope then refuses.
        List<ConversationCheckpoint.EvidenceRef> refs = refsFor("query_entities", summary());
        List<ConversationCheckpoint.RetainedRow> tail = List.of(new ConversationCheckpoint.RetainedRow(
                "user", "q", ConversationCheckpoint.RetainedRow.PROVENANCE_TRANSCRIPT));
        String json = ConversationCheckpointCodec.serialize(new ConversationCheckpoint(
                new ConversationCheckpoint.Source("conv", "Agent", "amid"),
                new ConversationCheckpointSemantic("goal", List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                refs, tail, new ConversationCheckpoint.Generated("t", "p", "m")));
        assertFalse(json == null || json.isEmpty());
    }
}
