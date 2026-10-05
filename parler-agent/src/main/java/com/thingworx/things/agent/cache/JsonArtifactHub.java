package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.BaseTypes;

/**
 * U2 M2 large-JSON / TEXT adapter over opaque {@link ArtifactCache}. Shares scope, principal,
 * budget, and descriptor-index machinery with {@link TabularArtifactHub}. Typed PASSWORD proof is
 * {@link ArtifactSchemaNode#scalar(BaseTypes) scalar STRING} (JSON body content is not a
 * {@link BaseTypes#PASSWORD} schema column).
 */
public final class JsonArtifactHub {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(JsonArtifactHub.class);

    private JsonArtifactHub() {}

    /**
     * Store UTF-8 bytes of {@code payload} as {@link ArtifactKind#JSON}.
     *
     * @return public {@code cacheId}
     */
    public static String store(String payload) throws Exception {
        return store(payload, null);
    }

    /**
     * Store UTF-8 bytes of {@code payload} as {@link ArtifactKind#JSON}.
     *
     * @return public {@code cacheId}
     */
    public static String store(String payload, SourceDescriptor descriptor) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        SourceDescriptor desc = descriptor != null ? descriptor : primaryJsonDescriptor("json.store");
        TabularArtifactHub.ensureRunInvocationBoundToConversation(AgentToolContext.getConversationId());
        ArtifactCache cache = TabularArtifactHub.resolveCache();
        ArtifactAccessContext access = TabularArtifactHub.accessContext(AgentToolContext.getConversationId());
        BudgetVector budget = TabularArtifactHub.currentBudget();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ArtifactCreateRequest req = ArtifactCreateRequest
                .builder(ArtifactKind.JSON, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                .producer(desc.sourceRouteId() != null ? desc.sourceRouteId() : "JsonArtifactHub")
                .lineage(lineageHint(desc))
                .schemaHint("application/json")
                .complete(true)
                .build();
        ArtifactWriter writer = cache.create(req, access, budget.toArtifactIoLimits());
        try {
            writer.writeBytes(bytes);
            writer.close();
            ArtifactRef ref = cache.publish(writer, access);
            String cacheId = ArtifactCacheIds.toPublicCacheId(ref);
            TabularArtifactHub.indexDescriptor(AgentToolContext.getConversationId(), cacheId, desc);
            LargeJsonCaps.SizeReport size = LargeJsonCaps.classify(payload);
            LOG.info("agent JSON cached via ArtifactCache: cacheId={} utf16Chars={} utf8Bytes={} class={}",
                    cacheId, size.utf16Chars(), size.utf8Bytes(), size.sizeClass());
            return cacheId;
        } catch (Exception e) {
            try {
                writer.abort();
            } catch (Exception ignored) {
                // best-effort
            }
            throw e;
        }
    }

    public static String lookup(String cacheId) {
        return lookupForConversation(AgentToolContext.getConversationId(), cacheId);
    }

    /**
     * @return UTF-8 payload, or {@code null} only for an ordinary {@code CACHE_MISS}
     * @throws ArtifactCacheException every non-miss cache/read fault
     */
    public static String lookupForConversation(String conversationId, String cacheId) {
        if (cacheId == null || cacheId.isEmpty()) {
            return null;
        }
        String conv = (conversationId == null || conversationId.isEmpty())
                ? AgentToolContext.getConversationId()
                : conversationId;
        ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conv, cacheId);
        if (ref == null) {
            return null;
        }
        ArtifactCache cache = TabularArtifactHub.resolveCache();
        ArtifactAccessContext access = TabularArtifactHub.accessContext(conv);
        BudgetVector budget = TabularArtifactHub.currentBudget();
        try {
            try (ArtifactReader reader = cache.open(ref, access, budget.toArtifactIoLimits())) {
                byte[] bytes = readAll(reader, budget);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.CACHE_MISS) {
                return null;
            }
            LOG.warn("JsonArtifactHub.lookup fault artifactId={} code={}: {}", ref.artifactId(), e.code(),
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            LOG.warn("JsonArtifactHub.lookup failed artifactId={}: {}", ref.artifactId(), e.toString());
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Cached JSON payload could not be read or validated", e);
        }
    }

    static SourceDescriptor primaryJsonDescriptor(String sourceRouteId) {
        SourceDescriptor.Builder b = SourceDescriptor.builder()
                .sourceRouteId(sourceRouteId)
                .completenessStatus(SourceDescriptor.CompletenessStatus.UNKNOWN)
                .requestId(AgentToolContext.getParlerRequestId());
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        if (inv != null) {
            b.executionScopeId(inv.opaqueScopeId());
        }
        return b.build();
    }

    private static String lineageHint(SourceDescriptor desc) {
        if (desc == null || desc.parentSourceCacheIds() == null || desc.parentSourceCacheIds().isEmpty()) {
            return "";
        }
        return String.join(",", desc.parentSourceCacheIds());
    }

    private static byte[] readAll(ArtifactReader reader, BudgetVector budget) throws Exception {
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        long max = budget.maxDecodeBytes();
        while (true) {
            byte[] chunk = reader.readBytes(Math.min(8192, ArtifactIoLimits.MAX_U1A_INTERNAL_BUFFER_BYTES));
            if (chunk.length == 0) {
                break;
            }
            acc.write(chunk);
            if (acc.size() > max) {
                throw new ArtifactCacheException(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                        "Decoded JSON payload exceeds maxDecodeBytes");
            }
        }
        return acc.toByteArray();
    }
}
