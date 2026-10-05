package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.ExecutionScope;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * U2 tabular adapter hub over opaque {@link ArtifactCache}. Replaces the former
 * conversation-scoped Infotable ConcurrentHashMap. Production uses the AgentThing-configured
 * cache and fails when no AgentThing is bound. Unit tests that need an in-memory payload backend
 * must install one explicitly through {@link #setTestArtifactCache(ArtifactCache)}.
 */
public final class TabularArtifactHub {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(TabularArtifactHub.class);

    private static final AtomicReference<ArtifactCache> TEST_OVERRIDE = new AtomicReference<>();
    private static final String SCOPE_NS = "parler.u2.scope:";
    /** Runtime-only descriptor index: {@code scopeId + NUL + cacheId} → descriptor. Cleared with scope. */
    private static final ConcurrentHashMap<String, SourceDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();
    /**
     * Owning artifact namespace for each descriptor entry ({@code scopeId + NUL + cacheId} →
     * {@link ArtifactAccessContext#namespaceKey()}). Used by U3E miss-proof so a cross-principal
     * conversation-scoped descriptor cannot overclaim {@code reason=NOT_FOUND}.
     */
    private static final ConcurrentHashMap<String, String> DESCRIPTOR_NAMESPACE_KEYS = new ConcurrentHashMap<>();

    private TabularArtifactHub() {}

    /** Test seam: force a specific cache (or {@code null} to clear). */
    public static void setTestArtifactCache(ArtifactCache cache) {
        TEST_OVERRIDE.set(cache);
        if (cache == null) {
            FileArtifactCacheRegistry.clearForTests();
        }
    }

    public static void clearTestState() {
        setTestArtifactCache(null);
        FileArtifactCache.clearForcedArtifactId();
        ArtifactAccessContextFactory.resetCurrentPrincipalLookup();
        DESCRIPTORS.clear();
        DESCRIPTOR_NAMESPACE_KEYS.clear();
    }

    /**
     * Core-minted opaque scope id for a conversation / single-turn request key. Deterministic so
     * store and {@link #lookupForConversation} share a namespace without App-supplied SecurityContext.
     */
    public static String opaqueScopeIdForConversation(String conversationId) {
        String conv = (conversationId == null || conversationId.isEmpty())
                ? AgentToolContext.SINGLE_TURN_CONVERSATION_ID
                : conversationId;
        String material;
        if (AgentToolContext.SINGLE_TURN_CONVERSATION_ID.equals(conv)) {
            material = SCOPE_NS + "single:" + conv;
        } else {
            material = SCOPE_NS + "conv:" + conv;
        }
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString().toLowerCase();
    }

    /**
     * Store a tabular artifact with a conservative default {@link SourceDescriptor}.
     *
     * @return public {@code cacheId} ({@link ArtifactRef} UUID text)
     */
    public static String store(InfoTable inner) throws Exception {
        return store(inner, SourceDescriptorSupport.forPrimaryStore(inner, "tabular.store"));
    }

    /**
     * Store a tabular artifact and remember its runtime {@link SourceDescriptor} for this scope.
     *
     * @return public {@code cacheId} ({@link ArtifactRef} UUID text)
     */
    public static String store(InfoTable inner, SourceDescriptor descriptor) throws Exception {
        return store(inner, descriptor, null);
    }

    /**
     * Store under the producing operation's {@link PublicationGuard}: the writer gets at most the
     * operation's remaining wall time, and the guard is consulted after encoding and again immediately
     * before the staged artifact becomes visible. A guard failure aborts the unpublished writer.
     *
     * @param guard {@code null} keeps the unguarded behaviour of {@link #store(InfoTable, SourceDescriptor)}
     */
    public static String store(InfoTable inner, SourceDescriptor descriptor, PublicationGuard guard)
            throws Exception {
        if (inner == null) {
            throw new IllegalArgumentException("inner InfoTable is null");
        }
        SourceDescriptor desc = descriptor != null
                ? descriptor
                : SourceDescriptorSupport.forPrimaryStore(inner, "tabular.store");
        ensureRunInvocationBoundToConversation(AgentToolContext.getConversationId());
        ArtifactCache cache = requireCache();
        ArtifactAccessContext access = resolveAccessContext();
        BudgetVector budget = resolveBudget();
        byte[] payload = encode(inner);
        ArtifactIoLimits limits = budget.toArtifactIoLimits();
        if (guard != null) {
            guard.check();
            limits = ArtifactIoLimits.of(limits.maxBytes(), limits.maxItems(),
                    Math.max(1L, Math.min(limits.maxWallTimeMillis(), guard.remainingWallTimeMillis())),
                    limits.maxInternalBufferBytes());
        }
        ArtifactCreateRequest req = ArtifactCreateRequest
                .builder(ArtifactKind.TABULAR, schemaForInfotable(inner))
                .producer(desc.sourceRouteId() != null ? desc.sourceRouteId() : "TabularArtifactHub")
                .lineage(lineageHint(desc))
                .schemaHint("infotable-json")
                .complete(true)
                .build();
        ArtifactWriter writer = cache.create(req, access, limits);
        try {
            writer.writeBytes(payload);
            writer.addProducerItemDelta(inner.getRowCount() == null ? 0L : inner.getRowCount().longValue());
            writer.close();
            if (guard != null) {
                guard.check();
            }
            ArtifactRef ref = cache.publish(writer, access);
            String cacheId = ArtifactCacheIds.toPublicCacheId(ref);
            rememberDescriptor(access.opaqueScopeId(), access.namespaceKey(), cacheId, desc);
            LOG.info("agent INFOTABLE cached via ArtifactCache: totalRows={} cacheId={}",
                    inner.getRowCount(), cacheId);
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

    /**
     * Historical restore under a public cacheId — <strong>retired (BP9 / U2 M4)</strong>.
     * Always returns {@code false}; transcript/compact evidence must not recreate live entries.
     */
    public static boolean restore(String conversationId, String cacheId, InfoTable inner) {
        return false;
    }

    public static InfoTable lookup(String cacheId) {
        if (cacheId == null || cacheId.isEmpty()) {
            return null;
        }
        return lookupForConversation(AgentToolContext.getConversationId(), cacheId);
    }

    /**
     * @return decoded table, or {@code null} only for an ordinary {@code CACHE_MISS}
     * @throws ArtifactCacheException every non-miss cache/read/decode fault
     */
    public static InfoTable lookupForConversation(String conversationId, String cacheId) {
        String conv = (conversationId == null || conversationId.isEmpty())
                ? AgentToolContext.getConversationId()
                : conversationId;
        ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conv, cacheId);
        if (ref == null) {
            return null;
        }
        ArtifactCache cache = requireCache();
        ArtifactAccessContext access = accessForConversation(conv);
        BudgetVector budget = resolveBudget();
        byte[] payload;
        try {
            try (ArtifactReader reader = cache.open(ref, access, budget.toArtifactIoLimits())) {
                payload = readAll(reader, budget);
            }
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.CACHE_MISS) {
                return null;
            }
            LOG.warn("TabularArtifactHub.lookup fault artifactId={} code={}: {}", ref.artifactId(), e.code(),
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            LOG.warn("TabularArtifactHub.lookup failed artifactId={}: {}", ref.artifactId(), e.toString());
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Cached tabular payload could not be read or validated", e);
        }
        try {
            return decode(payload);
        } catch (Exception e) {
            LOG.warn("TabularArtifactHub.lookup decode failed artifactId={}: {}", ref.artifactId(), e.toString());
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                    "Cached tabular payload could not be read or validated", e);
        }
    }

    /** Runtime {@link SourceDescriptor} for a cache id in the current conversation scope, or null. */
    public static SourceDescriptor lookupDescriptor(String cacheId) {
        return lookupDescriptorForConversation(AgentToolContext.getConversationId(), cacheId);
    }

    public static SourceDescriptor lookupDescriptorForConversation(String conversationId, String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            return null;
        }
        String conv = (conversationId == null || conversationId.isEmpty())
                ? AgentToolContext.getConversationId()
                : conversationId;
        String scopeId = opaqueScopeIdForConversation(conv);
        SourceDescriptor direct = DESCRIPTORS.get(descriptorKey(scopeId, cacheId.trim()));
        if (direct != null) {
            return direct;
        }
        ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conv, cacheId);
        if (ref == null) {
            return null;
        }
        return DESCRIPTORS.get(descriptorKey(scopeId, ref.artifactId()));
    }

    /** Invalidate the current invocation scope (TOKEN / terminal clear companion). */
    public static void invalidateCurrentScope() {
        try {
            ArtifactAccessContext access = resolveAccessContext();
            forgetDescriptorsForScope(access.opaqueScopeId());
            ArtifactCache cache = requireCache();
            cache.invalidateScope(access);
        } catch (Exception e) {
            LOG.warn("TabularArtifactHub.invalidateCurrentScope: {}", e.toString());
        }
    }

    /**
     * Invalidate the ArtifactCache namespace for a concrete conversation id (ClearConversation /
     * history cutoff). Uses the Core-minted opaque scope for that conversation.
     */
    public static void invalidateScopeForConversation(String conversationId) {
        try {
            ArtifactAccessContext access = accessForConversation(conversationId);
            forgetDescriptorsForScope(access.opaqueScopeId());
            ArtifactCache cache = requireCache();
            cache.invalidateScope(access);
        } catch (Exception e) {
            LOG.warn("TabularArtifactHub.invalidateScopeForConversation: {}", e.toString());
        }
    }

    /** Package access for sibling U2 adapters (JSON hub / extract_nested). */
    static ArtifactCache resolveCache() throws ArtifactCacheException {
        return requireCache();
    }

    /** Package access for sibling U2 adapters. */
    static ArtifactAccessContext accessContext(String conversationId) {
        return accessForConversation(conversationId);
    }

    /** Package access for sibling U2 adapters. */
    static BudgetVector currentBudget() {
        return resolveBudget();
    }

    /** Package access: remember a runtime descriptor under the current principal's namespace. */
    static void indexDescriptor(String conversationId, String cacheId, SourceDescriptor descriptor) {
        ArtifactAccessContext access = accessForConversation(conversationId);
        rememberDescriptor(access.opaqueScopeId(), access.namespaceKey(), cacheId, descriptor);
    }

    private static ArtifactCache requireCache() throws ArtifactCacheException {
        ArtifactCache override = TEST_OVERRIDE.get();
        if (override != null) {
            return override;
        }
        AgentThing agent = AgentToolContext.getAgentThing();
        return ArtifactCacheCore.requireCache(agent, LOG);
    }

    /** Bind / remint RunInvocationContext so opaque scope tracks the conversation namespace. */
    public static void ensureRunInvocationBoundToConversation(String conversationId) {
        String conv = (conversationId == null || conversationId.isEmpty())
                ? AgentToolContext.SINGLE_TURN_CONVERSATION_ID
                : conversationId;
        String scopeId = opaqueScopeIdForConversation(conv);
        RunInvocationContext cur = AgentToolContext.getRunInvocationContext();
        if (cur != null && scopeId.equals(cur.opaqueScopeId())) {
            return;
        }
        ExecutionScope kind = AgentToolContext.SINGLE_TURN_CONVERSATION_ID.equals(conv)
                ? ExecutionScope.REQUEST
                : ExecutionScope.CONVERSATION;
        BudgetVector budget = cur != null ? cur.budget() : BudgetVector.defaultsForTabular();
        String retryKey = cur != null ? cur.retryBudgetKey() : null;
        AgentToolContext.setRunInvocationContext(
                RunInvocationContext.of(UUID.randomUUID().toString(), kind, scopeId, budget, retryKey));
    }

    private static ArtifactAccessContext resolveAccessContext() {
        ensureRunInvocationBoundToConversation(AgentToolContext.getConversationId());
        return accessForConversation(AgentToolContext.getConversationId());
    }

    /**
     * Build access from the current ThingWorx principal (or explicitly installed test lookup)
     * and the Core-minted opaque scope for {@code conversationId}.
     */
    private static ArtifactAccessContext accessForConversation(String conversationId) {
        String scopeId = opaqueScopeIdForConversation(conversationId);
        return ArtifactAccessContextFactory.fromCurrentPrincipalWithExistingScope(scopeId);
    }

    private static BudgetVector resolveBudget() {
        RunInvocationContext inv = AgentToolContext.getRunInvocationContext();
        return inv != null ? inv.budget() : BudgetVector.defaultsForTabular();
    }

    /**
     * Typed PASSWORD proof from the InfoTable DataShape (U1A preflight). Recurses into nested
     * INFOTABLE local shapes; fails closed when an INFOTABLE column cannot prove PASSWORD absence.
     */
    static ArtifactSchemaNode schemaForInfotable(InfoTable table) throws ArtifactCacheException {
        return ArtifactSchemaNode.infotable("",
                columnsFromShape(table == null ? null : table.getDataShape(), 1));
    }

    private static List<ArtifactSchemaNode> columnsFromShape(DataShapeDefinition shape, int nestingLevel)
            throws ArtifactCacheException {
        if (nestingLevel > PasswordSchemaPreflight.MAX_DEPTH) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Schema nestingLevel exceeds maxDepth");
        }
        List<ArtifactSchemaNode> cols = new ArrayList<>();
        if (shape == null || shape.getFields() == null) {
            return cols;
        }
        for (FieldDefinition fd : shape.getFields().values()) {
            if (fd == null || fd.getName() == null || fd.getName().isEmpty()) {
                continue;
            }
            BaseTypes bt = fd.getBaseType() == null ? BaseTypes.STRING : fd.getBaseType();
            if (bt == BaseTypes.INFOTABLE) {
                DataShapeDefinition nested = fd.getLocalDataShape();
                if (nested == null) {
                    throw new ArtifactCacheException(ArtifactCacheFaultCode.PASSWORD_REJECTED,
                            "INFOTABLE column \"" + fd.getName()
                                    + "\" has no local DataShape; cannot prove PASSWORD absence");
                }
                cols.add(ArtifactSchemaNode.infotable(fd.getName(),
                        columnsFromShape(nested, nestingLevel + 1)));
            } else {
                cols.add(ArtifactSchemaNode.field(fd.getName(), bt));
            }
        }
        return cols;
    }

    private static String lineageHint(SourceDescriptor desc) {
        if (desc == null || desc.parentSourceCacheIds() == null || desc.parentSourceCacheIds().isEmpty()) {
            return "";
        }
        return String.join(",", desc.parentSourceCacheIds());
    }

    private static void rememberDescriptor(String scopeId, String namespaceKey, String cacheId,
            SourceDescriptor desc) {
        if (scopeId == null || cacheId == null || cacheId.isBlank() || desc == null) {
            return;
        }
        String key = descriptorKey(scopeId, cacheId.trim());
        DESCRIPTORS.put(key, desc);
        if (namespaceKey != null && !namespaceKey.isBlank()) {
            DESCRIPTOR_NAMESPACE_KEYS.put(key, namespaceKey);
        }
    }

    /**
     * U3E proof-classification test seam: register a descriptor owned by a stable test principal's
     * artifact namespace (without artifact bytes) so
     * {@link com.thingworx.things.agent.recovery.CacheMissClassifier} can prove {@code NOT_FOUND}.
     * Installs a principal lookup when unit tests have no ThingWorx SecurityContext (recovery
     * tests cannot call the package-private principal setter).
     */
    public static void rememberDescriptorForProofTests(String cacheId, SourceDescriptor desc) {
        if (!ArtifactCacheIds.isWellFormedPublicCacheId(cacheId)) {
            throw new IllegalArgumentException("well-formed public cacheId required");
        }
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "parler-proof-test");
        ensureRunInvocationBoundToConversation(AgentToolContext.getConversationId());
        ArtifactAccessContext access = accessForConversation(AgentToolContext.getConversationId());
        SourceDescriptor d = desc != null ? desc
                : SourceDescriptor.builder().sourceRouteId("proof-test").build();
        rememberDescriptor(access.opaqueScopeId(), access.namespaceKey(), cacheId.trim(), d);
    }

    /**
     * True when a descriptor is registered for {@code cacheId} under the current principal's
     * artifact namespace (same namespace used to open artifacts). Conversation-scoped descriptor
     * presence alone is not enough.
     */
    public static boolean descriptorOwnedByCurrentPrincipal(String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            return false;
        }
        String conv = AgentToolContext.getConversationId();
        String scopeId = opaqueScopeIdForConversation(conv);
        String key = descriptorKey(scopeId, cacheId.trim());
        if (!DESCRIPTORS.containsKey(key)) {
            ArtifactRef ref = ArtifactCacheIds.resolveStoreLookupRef(conv, cacheId);
            if (ref == null) {
                return false;
            }
            key = descriptorKey(scopeId, ref.artifactId());
            if (!DESCRIPTORS.containsKey(key)) {
                return false;
            }
        }
        String ownerNs = DESCRIPTOR_NAMESPACE_KEYS.get(key);
        if (ownerNs == null || ownerNs.isBlank()) {
            return false;
        }
        ArtifactAccessContext access = accessForConversation(conv);
        return ownerNs.equals(access.namespaceKey());
    }

    private static void forgetDescriptorsForScope(String scopeId) {
        if (scopeId == null || scopeId.isEmpty()) {
            return;
        }
        String prefix = scopeId + '\0';
        DESCRIPTORS.keySet().removeIf(k -> k.startsWith(prefix));
        DESCRIPTOR_NAMESPACE_KEYS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String descriptorKey(String scopeId, String cacheId) {
        return scopeId + '\0' + cacheId;
    }

    /**
     * Nested size accounting for M2 {@code extract_nested}: classify the hub's Infotable codec
     * payload so nested tables cannot bypass {@link LargeJsonCaps} boundaries.
     */
    public static LargeJsonCaps.SizeReport classifyEncodedSize(InfoTable table) throws Exception {
        byte[] encoded = encode(table);
        String asUtf16View = new String(encoded, StandardCharsets.UTF_8);
        LargeJsonCaps.SizeReport report = LargeJsonCaps.classify(asUtf16View);
        return new LargeJsonCaps.SizeReport(report.utf16Chars(), encoded.length, report.sizeClass());
    }

    private static byte[] encode(InfoTable table) throws Exception {
        return TabularInfotableCodec.encode(table);
    }

    private static InfoTable decode(byte[] bytes) throws Exception {
        return TabularInfotableCodec.decode(bytes);
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
                        "Decoded tabular payload exceeds maxDecodeBytes");
            }
        }
        return acc.toByteArray();
    }
}
