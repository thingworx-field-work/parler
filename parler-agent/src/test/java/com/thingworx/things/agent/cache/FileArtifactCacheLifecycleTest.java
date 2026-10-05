package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.types.BaseTypes;

/**
 * Cache lifecycle evidence: lazy TTL, invalidation, scope invalidation,
 * restart-empty, shutdown, open/remove race, and the uniform miss matrix.
 */
class FileArtifactCacheLifecycleTest {

    @AfterEach
    void clearRegistry() {
        FileArtifactCacheRegistry.clearForTests();
    }

    private static ArtifactAccessContext ctx(String principal, String scope) {
        return ArtifactAccessContextFactory.of(principal, scope);
    }

    private static ArtifactCreateRequest opaqueRequest() {
        return ArtifactCreateRequest.builder(ArtifactKind.TEXT, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                .producer("test").schemaHint("octet").lineage("unit").complete(true).build();
    }

    private static ArtifactCreateRequest opaqueRequest(long logicalExpiryEpochMilli) {
        return ArtifactCreateRequest.builder(ArtifactKind.TEXT, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                .producer("test").schemaHint("octet").lineage("unit").complete(true)
                .logicalExpiryEpochMilli(logicalExpiryEpochMilli).build();
    }

    private static ArtifactIoLimits limits() {
        return ArtifactIoLimits.of(1_000_000, 10_000, 60_000, 64);
    }

    private static byte[] readAll(ArtifactReader reader) throws Exception {
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        while (true) {
            byte[] chunk = reader.readBytes(32);
            if (chunk.length == 0) {
                break;
            }
            acc.write(chunk);
        }
        return acc.toByteArray();
    }

    private static ArtifactRef publishBytes(FileArtifactCache cache, ArtifactAccessContext access,
            ArtifactCreateRequest request, byte[] payload) throws Exception {
        ArtifactWriter writer = cache.create(request, access, limits());
        writer.writeBytes(payload);
        writer.close();
        return cache.publish(writer, access);
    }

    @Test
    void lazyTtlExpiresOnLookupAndDoesNotResurrect() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T12:00:00Z").toEpochMilli());
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store, clock, ArtifactRef::newArtifactId);
        ArtifactAccessContext access = ctx("alice", "s1");
        long expiry = clock.millis() + 1_000L;
        ArtifactWriter writer = cache.create(opaqueRequest(expiry), access, limits());
        writer.writeBytes("ttl".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ((StreamingArtifactWriter) writer).relativePath();

        try (ArtifactReader reader = cache.open(ref, access, limits())) {
            assertEquals("ttl", new String(readAll(reader), StandardCharsets.UTF_8));
        }

        clock.setMillis(expiry);
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
        assertFalse(cache.isIndexed(access, ref));
        assertTrue(cache.payloadExists(path));

        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
        assertTrue(cache.payloadExists(path), "logical expiry must not delete payloads");
    }

    @Test
    void invalidateRemovesIndexOnly() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ((StreamingArtifactWriter) writer).relativePath();

        cache.invalidate(ref, access);
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
        assertFalse(cache.isIndexed(access, ref));
        assertTrue(store.exists(path));
    }

    @Test
    void invalidateScopeRemovesOnlyMatchingNamespace() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext scope1 = ctx("alice", "s1");
        ArtifactAccessContext scope2 = ctx("alice", "s2");
        ArtifactRef ref1 = publishBytes(cache, scope1, opaqueRequest(), "one".getBytes(StandardCharsets.UTF_8));
        ArtifactRef ref2 = publishBytes(cache, scope2, opaqueRequest(), "two".getBytes(StandardCharsets.UTF_8));

        cache.invalidateScope(scope1);
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref1, scope1, limits())).code());
        try (ArtifactReader reader = cache.open(ref2, scope2, limits())) {
            assertEquals("two", new String(readAll(reader), StandardCharsets.UTF_8));
        }
    }

    @Test
    void restartEmptyIgnoresRetainedPayloadFiles() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache first = FileArtifactCacheRegistry.getOrCreate("RepoLife", store);
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = first.create(opaqueRequest(), access, limits());
        writer.writeBytes("keep".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = first.publish(writer, access);
        String path = ((StreamingArtifactWriter) writer).relativePath();
        assertTrue(store.exists(path));

        FileArtifactCacheRegistry.clearForTests();
        FileArtifactCache restarted = FileArtifactCacheRegistry.getOrCreate("RepoLife", store);
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> restarted.open(ref, access, limits())).code());
        assertTrue(store.exists(path), "restart must not scan or delete retained payloads");
    }

    @Test
    void shutdownRejectsNewOpsAndClearsIndexWithoutDeletingPayloads() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("z".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ((StreamingArtifactWriter) writer).relativePath();

        cache.shutdown();
        assertTrue(cache.isShutDown());
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class,
                        () -> cache.create(opaqueRequest(), access, limits())).code());
        assertTrue(store.exists(path));
    }

    @Test
    void admittedReaderFinishesAfterInvalidate() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactRef ref = publishBytes(cache, access, opaqueRequest(),
                "abcdefgh".getBytes(StandardCharsets.UTF_8));

        ArtifactReader reader = cache.open(ref, access, limits());
        assertEquals(4, reader.readBytes(4).length);
        cache.invalidate(ref, access);
        assertEquals("efgh", new String(readAll(reader), StandardCharsets.UTF_8));
        reader.close();

        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
    }

    @Test
    void openVersusInvalidateRaceAdmittedOrMiss() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactRef ref = publishBytes(cache, access, opaqueRequest(),
                "race-payload".getBytes(StandardCharsets.UTF_8));

        List<String> outcomes = new ArrayList<>();
        Thread opener = new Thread(() -> {
            bothReady.countDown();
            try {
                start.await();
                try (ArtifactReader reader = cache.open(ref, access, limits())) {
                    outcomes.add("admitted:" + new String(readAll(reader), StandardCharsets.UTF_8));
                }
            } catch (ArtifactCacheException e) {
                outcomes.add("miss:" + e.code().name());
            } catch (Exception e) {
                outcomes.add("error:" + e.getClass().getSimpleName());
            }
        });
        Thread invalidator = new Thread(() -> {
            bothReady.countDown();
            try {
                start.await();
                cache.invalidate(ref, access);
                outcomes.add("invalidated");
            } catch (Exception e) {
                outcomes.add("invalidate-error");
            }
        });
        opener.start();
        invalidator.start();
        assertTrue(bothReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        opener.join();
        invalidator.join();

        assertTrue(outcomes.contains("invalidated"));
        assertTrue(outcomes.stream().anyMatch(o -> o.startsWith("admitted:") || o.equals("miss:CACHE_MISS")));
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class, () -> cache.open(ref, access, limits())).code());
    }

    @Test
    void uniformMissMatrixSharesCacheMissCode() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T12:00:00Z").toEpochMilli());
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store, clock, ArtifactRef::newArtifactId);
        ArtifactAccessContext alice = ctx("alice", "s1");
        ArtifactAccessContext bob = ctx("bob", "s1");

        ArtifactRef present = publishBytes(cache, alice, opaqueRequest(clock.millis() + 60_000L),
                "present".getBytes(StandardCharsets.UTF_8));
        ArtifactRef expired = publishBytes(cache, alice, opaqueRequest(clock.millis() + 1L),
                "exp".getBytes(StandardCharsets.UTF_8));
        ArtifactRef doomed = publishBytes(cache, alice, opaqueRequest(),
                "doom".getBytes(StandardCharsets.UTF_8));
        ArtifactRef scoped = publishBytes(cache, alice, opaqueRequest(),
                "scope".getBytes(StandardCharsets.UTF_8));

        List<ArtifactCacheFaultCode> codes = new ArrayList<>();
        // missing well-formed id
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(ArtifactRef.ofValidated("11111111-1111-1111-1111-111111111111"), alice, limits()))
                .code());
        // malformed id syntax
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(ArtifactRef.ofValidated("not-a-uuid"), alice, limits())).code());
        // foreign namespace
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(present, bob, limits())).code());
        // lazy TTL
        clock.advanceMillis(2L);
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(expired, alice, limits())).code());
        // explicit invalidate
        cache.invalidate(doomed, alice);
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(doomed, alice, limits())).code());
        // scope invalidate (also removes present)
        cache.invalidateScope(alice);
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(scoped, alice, limits())).code());
        codes.add(assertThrows(ArtifactCacheException.class,
                () -> cache.open(present, alice, limits())).code());

        for (ArtifactCacheFaultCode code : codes) {
            assertEquals(ArtifactCacheFaultCode.CACHE_MISS, code);
        }

        // restart-empty path
        FileArtifactCacheRegistry.clearForTests();
        FileArtifactCache restarted = new FileArtifactCache("repo-a", store, clock, ArtifactRef::newArtifactId);
        assertEquals(ArtifactCacheFaultCode.CACHE_MISS,
                assertThrows(ArtifactCacheException.class,
                        () -> restarted.open(present, alice, limits())).code());
    }

    @Test
    void deletedButStillIndexedPayloadIsPayloadFaultNotCacheMiss() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("live-index".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ((StreamingArtifactWriter) writer).relativePath();

        assertTrue(cache.isIndexed(access, ref));
        store.bestEffortDelete(path);
        assertFalse(store.exists(path));

        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> cache.open(ref, access, limits()));
        assertEquals(ArtifactCacheFaultCode.PAYLOAD_FAULT, ex.code());
        assertTrue(cache.isIndexed(access, ref), "storage fault must not silently drop the index entry");
    }

    @Test
    void passwordKitStillCreatesZeroFiles() {
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactCreateRequest req = ArtifactCreateRequest.builder(ArtifactKind.TABULAR,
                ArtifactSchemaNode.infotable("", List.of(ArtifactSchemaNode.field("secret", BaseTypes.PASSWORD))))
                .build();
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED,
                assertThrows(ArtifactCacheException.class,
                        () -> cache.create(req, ctx("alice", "s1"), limits())).code());
        assertEquals(0, store.exclusiveCreates.get());
    }

    @Test
    void unavailableRepositoryFailClosed() {
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> ArtifactCacheCore.requireConfigured("  ",
                        org.slf4j.LoggerFactory.getLogger("lifecycle"), "agent"));
        assertEquals(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, ex.code());
    }

    /** Controllable UTC clock for lazy-TTL fixtures. */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableClock(long startEpochMilli) {
            this.millis = new AtomicLong(startEpochMilli);
        }

        void setMillis(long epochMilli) {
            millis.set(epochMilli);
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
