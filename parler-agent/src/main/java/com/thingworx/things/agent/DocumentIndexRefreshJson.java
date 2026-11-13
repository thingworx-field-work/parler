package com.thingworx.things.agent;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.tools.DocumentKnowledgeIndex;
import com.thingworx.things.agent.tools.DocumentKnowledgeIndexCache;
import com.thingworx.things.agent.tools.DocumentKnowledgeSettings;

/** JSON of {@code RefreshDocumentKnowledgeIndex} and of the snapshot block {@code documentKnowledge} (round 6, R27). */
final class DocumentIndexRefreshJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DocumentIndexRefreshJson() {
    }

    /** {@code {invalidated, evictedIndexLoadedAt, ttlSeconds}}; the load time is null when nothing was removed. */
    static String refreshResult(DocumentKnowledgeIndexCache.Invalidation inv, int ttlSeconds) throws Exception {
        ObjectNode out = JSON.createObjectNode();
        out.put("invalidated", inv.invalidated());
        if (inv.evictedIndexLoadedAt() != null) {
            out.put("evictedIndexLoadedAt", inv.evictedIndexLoadedAt().toString());
        } else {
            out.putNull("evictedIndexLoadedAt");
        }
        out.put("ttlSeconds", ttlSeconds);
        return JSON.writeValueAsString(out);
    }

    /** The numeric document-index settings and {@code index {loadedAt, expiresAt}} or null, read without a load. */
    static ObjectNode snapshotBlock(DocumentKnowledgeSettings settings, String agentName) {
        ObjectNode dk = JSON.createObjectNode();
        dk.put("indexTtlSeconds", settings.indexTtlSeconds());
        dk.put("maxDocuments", settings.maxDocuments());
        dk.put("maxChunks", settings.maxChunks());
        dk.put("searchDefaultLimit", settings.searchDefaultLimit());
        dk.put("searchMaxLimit", settings.searchMaxLimit());
        dk.put("searchSnippetMaxChars", settings.searchSnippetMaxChars());
        dk.put("chunkMaxChars", settings.chunkMaxChars());
        Optional<DocumentKnowledgeIndex> idx = DocumentKnowledgeIndexCache.peek(settings, agentName);
        if (idx.isPresent()) {
            dk.putObject("index").put("loadedAt", idx.get().loadedAt().toString())
                    .put("expiresAt", idx.get().expiresAt().toString());
        } else {
            dk.putNull("index");
        }
        return dk;
    }
}
