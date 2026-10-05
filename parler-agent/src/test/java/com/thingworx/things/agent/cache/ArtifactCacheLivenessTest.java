package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import com.thingworx.types.BaseTypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §5 invariant 11 / §9.2 step 4: whether a checkpoint's {@code cacheId} is still usable in <em>this</em> JVM,
 * under <em>this</em> conversation and caller principal.
 *
 * <p>Rehydrate is not proof of a restart — {@code _conversations} is instance state while the cache index is JVM
 * state — so both directions have to be real, not a conservative constant.
 */
class ArtifactCacheLivenessTest {

    private static final String CONVERSATION = "conv-liveness";

    @BeforeEach
    void installCache() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void clearCache() {
        TabularArtifactHub.clearTestState();
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(null);
    }

    /** Installs a cache whose clock the test controls, so TTL is a lever rather than a wait. */
    private static MutableClock installCacheWithClock(long nowMillis) {
        MutableClock clock = new MutableClock(nowMillis);
        TabularArtifactHub.clearTestState();
        TabularArtifactHub.setTestArtifactCache(new FileArtifactCache(
                "parler-artifact-cache-test", new InMemoryArtifactPayloadStore(), clock,
                ArtifactRef::newArtifactId));
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "parler-artifact-cache-test");
        return clock;
    }

    private static String publishWith(String conversationId, boolean complete, Long logicalExpiryOrNull)
            throws Exception {
        ArtifactCache cache = TabularArtifactHub.resolveCache();
        ArtifactAccessContext access = TabularArtifactHub.accessContext(conversationId);
        ArtifactCreateRequest.Builder b =
                ArtifactCreateRequest.builder(ArtifactKind.TEXT, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                        .producer("test").schemaHint("octet").lineage("unit").complete(complete);
        if (logicalExpiryOrNull != null) {
            b = b.logicalExpiryEpochMilli(logicalExpiryOrNull.longValue());
        }
        ArtifactWriter writer = cache.create(b.build(), access, ArtifactIoLimits.of(1_000_000, 10_000, 60_000, 64));
        writer.writeBytes("payload".getBytes(StandardCharsets.UTF_8));
        writer.close();
        return cache.publish(writer, access).artifactId();
    }

    @Test
    void anExpiredRecordIsNotLiveEvenWhileItsKeyIsStillIndexed() throws Exception {
        // §3.6 lists TTL alongside index presence. FileArtifactCache evicts lazily in open(), so a containment
        // check would keep reporting an expired handle as usable until some unrelated lookup happened to clear it.
        MutableClock clock = installCacheWithClock(1_000_000L);
        long expiry = clock.millis() + 1_000L;
        String cacheId = publishWith(CONVERSATION, true, Long.valueOf(expiry));
        assertTrue(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "fixture precondition: live before its expiry");

        clock.setMillis(expiry);
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "an expired handle must recompute, not be reported live because its key lingers");
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "and the lazy eviction leaves the answer stable");
    }

    @Test
    void anIncompleteRecordIsNotLive() throws Exception {
        // A half-written artifact is indexed but not usable; §3.6 names completeness explicitly.
        installCacheWithClock(1_000_000L);
        String cacheId = publishWith(CONVERSATION, false, null);
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId));
    }

    /** Publishes one artifact through the same access namespace the probe resolves for {@code conversationId}. */
    private static String publishFor(String conversationId) throws Exception {
        ArtifactCache cache = TabularArtifactHub.resolveCache();
        ArtifactAccessContext access = TabularArtifactHub.accessContext(conversationId);
        ArtifactWriter writer = cache.create(
                ArtifactCreateRequest.builder(ArtifactKind.TEXT, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                        .producer("test").schemaHint("octet").lineage("unit").complete(true).build(),
                access, ArtifactIoLimits.of(1_000_000, 10_000, 60_000, 64));
        writer.writeBytes("payload".getBytes(StandardCharsets.UTF_8));
        writer.close();
        return cache.publish(writer, access).artifactId();
    }

    @Test
    void anIndexedSameScopeHandleIsLive() throws Exception {
        String cacheId = publishFor(CONVERSATION);
        assertTrue(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "a handle this JVM still indexes for this conversation is usable");
    }

    @Test
    void anotherConversationsScopeIsNotLive() throws Exception {
        String cacheId = publishFor(CONVERSATION);
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation("some-other-conversation", cacheId),
                "the index is keyed by scope, so a handle from another conversation is not this one's");
    }

    @Test
    void anotherPrincipalIsNotLive() throws Exception {
        String cacheId = publishFor(CONVERSATION);
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "someone-else");
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "namespace equality is the principal/scope pair, not the scope alone");
    }

    @Test
    void aRestartedOrClearedIndexIsNotLive() throws Exception {
        String cacheId = publishFor(CONVERSATION);
        assertTrue(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId));

        // A fresh cache is what a JVM restart leaves behind: payloads may survive on disk, the index does not.
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, cacheId),
                "a persisted live must never survive on the strength of the file still existing");
    }

    @Test
    void anUnconfiguredCacheAnswersFalseRatherThanThrowing() {
        TabularArtifactHub.clearTestState();
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION,
                "11112222-3333-4444-5555-666677778888"),
                "rehydrate must never fail on a liveness question; the safe direction is historical-recompute");
    }

    /** Local copy so TTL is a lever; the shipped fixtures install a system-clock cache. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long startEpochMilli) {
            this.millis = new AtomicLong(startEpochMilli);
        }

        void setMillis(long epochMilli) {
            millis.set(epochMilli);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }

    @Test
    void aBlankCacheIdIsNotLive() {
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, null));
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, "   "));
    }
}
