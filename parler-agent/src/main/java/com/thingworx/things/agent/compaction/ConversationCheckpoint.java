package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A complete, server-assembled {@code parler.conversation_checkpoint.v1} envelope
 * ({@code docs/core/advanced-compact.md} §6.1).
 *
 * <p>Ownership is the point of this type (§6.2): a model may author {@link #semantic()} and nothing else. Identity,
 * evidence references, the retained tail, and generation metadata are written by the server after parsing, so a model
 * cannot forge a handle, an identity, or a liveness claim by emitting a field of the same name.
 */
public final class ConversationCheckpoint {

    /** Server-written identity. {@code throughAssistantMessageId} is the §6.1.1 watermark, not a prefix cutoff. */
    public static final class Source {
        private final String conversationId;
        private final String agentThing;
        private final String throughAssistantMessageId;

        public Source(String conversationId, String agentThing, String throughAssistantMessageId) {
            this.conversationId = conversationId != null ? conversationId : "";
            this.agentThing = agentThing != null ? agentThing : "";
            this.throughAssistantMessageId = throughAssistantMessageId != null ? throughAssistantMessageId : "";
        }

        public String conversationId() {
            return conversationId;
        }

        public String agentThing() {
            return agentThing;
        }

        public String throughAssistantMessageId() {
            return throughAssistantMessageId;
        }

        boolean isComplete() {
            return !conversationId.isBlank() && !agentThing.isBlank() && !throughAssistantMessageId.isBlank();
        }
    }

    /**
     * A pointer to server-authored evidence that supported progress in the covered prefix — never a copy of it.
     *
     * <p>{@code liveness} is re-decided at every rehydrate from a current-JVM lookup (§5 invariant 11); a persisted
     * {@code live} never survives a restart on the strength of the file still existing.
     */
    public static final class EvidenceRef {
        /** Current-JVM lookup succeeded in this conversation/principal scope. */
        public static final String LIVENESS_LIVE = "live";
        /** Not resolvable now; the model must re-fetch or recompute from a safe source. */
        public static final String LIVENESS_HISTORICAL_RECOMPUTE = "historical-recompute";

        /**
         * Closed set of §6.2 admitting families. A ref records which one let it in, because carry-forward must
         * re-run the format allowlist long after the originating body is gone.
         */
        public static final String FAMILY_INFOTABLE_SUMMARY = "infotable-summary";
        public static final String FAMILY_ENTITY_METADATA_SUMMARY = "entity-metadata-summary";
        public static final String FAMILY_INFOTABLE_MATRIX = "infotable-matrix";
        public static final String FAMILY_COHORT_BUNDLE = "cohort-bundle";
        public static final String FAMILY_COMPACT_FETCH = "compact-fetch";

        public static boolean isKnownFamily(String evidenceFormat) {
            return FAMILY_INFOTABLE_SUMMARY.equals(evidenceFormat)
                    || FAMILY_ENTITY_METADATA_SUMMARY.equals(evidenceFormat)
                    || FAMILY_INFOTABLE_MATRIX.equals(evidenceFormat)
                    || FAMILY_COHORT_BUNDLE.equals(evidenceFormat)
                    || FAMILY_COMPACT_FETCH.equals(evidenceFormat);
        }

        private final String toolCallId;
        private final String tool;
        private final String evidenceFormat;
        private final String resultKind;
        private final String cacheId;
        private final String completeness;
        private final boolean sampleOnly;
        private final String liveness;
        private final String recomputeTool;

        public EvidenceRef(String toolCallId, String tool, String evidenceFormat, String resultKind, String cacheId,
                String completeness, boolean sampleOnly, String liveness, String recomputeTool) {
            this.toolCallId = toolCallId != null ? toolCallId : "";
            this.tool = tool != null ? tool : "";
            this.evidenceFormat = evidenceFormat != null ? evidenceFormat : "";
            this.resultKind = resultKind != null ? resultKind : "";
            this.cacheId = cacheId != null ? cacheId : "";
            this.completeness = completeness != null ? completeness : "";
            this.sampleOnly = sampleOnly;
            this.liveness = LIVENESS_LIVE.equals(liveness) ? LIVENESS_LIVE : LIVENESS_HISTORICAL_RECOMPUTE;
            this.recomputeTool = recomputeTool != null ? recomputeTool : "";
        }

        public String toolCallId() {
            return toolCallId;
        }

        public String tool() {
            return tool;
        }

        /** Which §6.2 family admitted this ref; one of the {@code FAMILY_*} constants. */
        public String evidenceFormat() {
            return evidenceFormat;
        }

        public String resultKind() {
            return resultKind;
        }

        public String cacheId() {
            return cacheId;
        }

        public String completeness() {
            return completeness;
        }

        public boolean sampleOnly() {
            return sampleOnly;
        }

        public String liveness() {
            return liveness;
        }

        public String recomputeTool() {
            return recomputeTool;
        }

        /** A ref without pairing identity cannot be attributed to a tool call and is not admissible (§6.2). */
        boolean hasPairingIdentity() {
            return !toolCallId.isBlank() && !tool.isBlank();
        }

        /** Identity plus a recognized admitting family — the minimum for a ref to be persisted or carried. */
        boolean isAdmissible() {
            return hasPairingIdentity() && isKnownFamily(evidenceFormat);
        }
    }

    /** One exact, unrewritten working row frozen at generation time (§6.1, §6.2). */
    public static final class RetainedRow {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";

        /** A user's own words. */
        public static final String PROVENANCE_TRANSCRIPT = "transcript";
        /** A final prose answer the agent produced. */
        public static final String PROVENANCE_FINAL_ASSISTANT = "final-assistant";
        /** Already-approved compact evidence carrying assistant provenance after Stage-2 rehydrate. */
        public static final String PROVENANCE_COMPACT_EVIDENCE = "compact-evidence";

        private final String role;
        private final String content;
        private final String provenance;

        public RetainedRow(String role, String content, String provenance) {
            this.role = role != null ? role : "";
            this.content = content != null ? content : "";
            this.provenance = provenance != null ? provenance : "";
        }

        public String role() {
            return role;
        }

        public String content() {
            return content;
        }

        public String provenance() {
            return provenance;
        }

        int chars() {
            return content.length();
        }
    }

    /** Server-written generation metadata. {@code modelGenerated} is always true for a v1 checkpoint. */
    public static final class Generated {
        private final String at;
        private final String provider;
        private final String model;

        public Generated(String at, String provider, String model) {
            this.at = at != null ? at : "";
            this.provider = provider != null ? provider : "";
            this.model = model != null ? model : "";
        }

        public String at() {
            return at;
        }

        public String provider() {
            return provider;
        }

        public String model() {
            return model;
        }
    }

    private final Source source;
    private final ConversationCheckpointSemantic semantic;
    private final List<EvidenceRef> evidenceRefs;
    private final List<RetainedRow> retainedTail;
    private final Generated generated;

    public ConversationCheckpoint(Source source, ConversationCheckpointSemantic semantic,
            List<EvidenceRef> evidenceRefs, List<RetainedRow> retainedTail, Generated generated) {
        this.source = source;
        this.semantic = semantic;
        this.evidenceRefs = evidenceRefs != null
                ? Collections.unmodifiableList(new ArrayList<>(evidenceRefs))
                : Collections.emptyList();
        this.retainedTail = retainedTail != null
                ? Collections.unmodifiableList(new ArrayList<>(retainedTail))
                : Collections.emptyList();
        this.generated = generated;
    }

    public Source source() {
        return source;
    }

    public ConversationCheckpointSemantic semantic() {
        return semantic;
    }

    public List<EvidenceRef> evidenceRefs() {
        return evidenceRefs;
    }

    public List<RetainedRow> retainedTail() {
        return retainedTail;
    }

    public Generated generated() {
        return generated;
    }
}
