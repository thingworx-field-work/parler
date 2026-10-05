package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.types.BaseTypes;

/** Opaque-kernel U1A evidence (§A3.1.6). */
class FileArtifactCacheTest {

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

    @Test
    void lowercaseHexUsernameEncodingMatrix() {
        assertEquals("616161", ArtifactPathLayout.encodeUsername("aaa"));
        assertEquals("616147", ArtifactPathLayout.encodeUsername("aaG"));
        assertNotEquals(ArtifactPathLayout.encodeUsername("Alice"), ArtifactPathLayout.encodeUsername("alice"));
        assertNotEquals(ArtifactPathLayout.encodeUsername("café"), ArtifactPathLayout.encodeUsername("cafe"));
        assertNotEquals(ArtifactPathLayout.encodeUsername("a/b"), ArtifactPathLayout.encodeUsername("a\\b"));
        assertNotEquals(ArtifactPathLayout.encodeUsername("é"), ArtifactPathLayout.encodeUsername("e\u0301"));
    }

    @Test
    void pathCapsAndFullComposition() throws Exception {
        String atCap = "a".repeat(96);
        String id = "11111111-1111-1111-1111-111111111111";
        String path = ArtifactPathLayout.buildRelativePath(atCap,
                ArtifactPathLayout.utcDateOfEpochMilli(0L), id);
        assertEquals(192, ArtifactPathLayout.encodeUsername(atCap).length());
        assertTrue(path.endsWith("/1970-01-01/" + id + ".payload"));
        assertTrue(path.length() <= ArtifactPathLayout.MAX_RELATIVE_PATH_BYTES);
        assertThrows(ArtifactCacheException.class,
                () -> ArtifactPathLayout.buildRelativePath("a".repeat(97),
                        ArtifactPathLayout.utcDateOfEpochMilli(0L), id));
    }

    @Test
    void opaqueMultiChunkRoundTrip() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("ab".getBytes(StandardCharsets.UTF_8));
        writer.writeBytes("cd".getBytes(StandardCharsets.UTF_8));
        writer.addProducerItemDelta(1);
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        try (ArtifactReader reader = cache.open(ref, access, limits())) {
            assertEquals(4, reader.record().byteCount());
            assertEquals(1, reader.record().itemCount());
            assertEquals("abcd", new String(readAll(reader), StandardCharsets.UTF_8));
        }
    }

    @Test
    void largeChunkLargerThanWindowRoundTrips() throws Exception {
        ArtifactIoLimits tinyWindow = ArtifactIoLimits.of(1_000_000, 10_000, 60_000, 8);
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        byte[] payload = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        ArtifactWriter writer = cache.create(opaqueRequest(), access, tinyWindow);
        writer.writeBytes(payload);
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        try (ArtifactReader reader = cache.open(ref, access, tinyWindow)) {
            assertArrayEquals(payload, readAll(reader));
        }
    }

    @Test
    void readBudgetExhaustionIsIoLimitNotEof() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("0123456789".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        ArtifactIoLimits tinyRead = ArtifactIoLimits.of(4, 10_000, 60_000, 64);
        try (ArtifactReader reader = cache.open(ref, access, tinyRead)) {
            assertEquals(4, reader.readBytes(64).length);
            ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                    () -> reader.readBytes(64));
            assertEquals(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED, ex.code());
        }
    }

    @Test
    void exactLengthNaturalEofProbe() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("xyz".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        try (ArtifactReader reader = cache.open(ref, access, limits())) {
            assertArrayEquals("xyz".getBytes(StandardCharsets.UTF_8), reader.readBytes(64));
            assertEquals(0, reader.readBytes(64).length);
        }
    }

    @Test
    void truncatedPayloadFaultsOnRead() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
        AtomicReference<String> id = new AtomicReference<>();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store, clock, () -> {
            id.set(ArtifactRef.newArtifactId());
            return id.get();
        });
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("abcdef".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ArtifactPathLayout.encodeUsername("alice") + "/2026-07-17/" + id.get() + ".payload";
        store.seed(path, "ab".getBytes(StandardCharsets.UTF_8));
        try (ArtifactReader reader = cache.open(ref, access, limits())) {
            ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                    () -> reader.readBytes(64));
            assertEquals(ArtifactCacheFaultCode.PAYLOAD_FAULT, ex.code());
        }
    }

    @Test
    void overlongPayloadFaultsOnNaturalEofProbe() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
        AtomicReference<String> id = new AtomicReference<>();
        FileArtifactCache cache = new FileArtifactCache("repo-a", store, clock, () -> {
            id.set(ArtifactRef.newArtifactId());
            return id.get();
        });
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("ab".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        String path = ArtifactPathLayout.encodeUsername("alice") + "/2026-07-17/" + id.get() + ".payload";
        store.seed(path, "abc".getBytes(StandardCharsets.UTF_8));
        try (ArtifactReader reader = cache.open(ref, access, limits())) {
            assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), reader.readBytes(64));
            ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                    () -> reader.readBytes(64));
            assertEquals(ArtifactCacheFaultCode.PAYLOAD_FAULT, ex.code());
        }
    }

    @Test
    void earlyCloseReleasesWithoutDrain() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("0123456789".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef ref = cache.publish(writer, access);
        ArtifactReader reader = cache.open(ref, access, limits());
        assertEquals(3, reader.readBytes(3).length);
        reader.close(); // must not require probe/drain of unread suffix
    }

    @Test
    void passwordPreflightCreatesZeroFiles() {
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactCreateRequest req = ArtifactCreateRequest.builder(ArtifactKind.TABULAR,
                ArtifactSchemaNode.infotable("", List.of(ArtifactSchemaNode.field("secret", BaseTypes.PASSWORD))))
                .build();
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> cache.create(req, ctx("alice", "s1"), limits()));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
        assertEquals(0, store.exclusiveCreates.get());
    }

    @Test
    void untypedProofRejectedBeforeCreate() {
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> cache.create(ArtifactCreateRequest.builder(ArtifactKind.TEXT,
                        ArtifactSchemaNode.untypedBytes()).build(), ctx("alice", "s1"), limits()));
        assertEquals(ArtifactCacheFaultCode.PASSWORD_REJECTED, ex.code());
        assertEquals(0, store.exclusiveCreates.get());
    }

    @Test
    void acceptedDepth32WithScalarChild() throws Exception {
        ArtifactSchemaNode leaf = ArtifactSchemaNode.field("v", BaseTypes.STRING);
        ArtifactSchemaNode node = leaf;
        for (int i = 0; i < 32; i++) {
            node = ArtifactSchemaNode.infotable("t" + i, List.of(node));
        }
        // deepest INFOTABLE nestingLevel = 32; scalar child inherits 32
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactWriter w = cache.create(
                ArtifactCreateRequest.builder(ArtifactKind.TABULAR, node).build(),
                ctx("alice", "s1"), limits());
        assertEquals(1, store.exclusiveCreates.get());
        w.abort();
    }

    @Test
    void rejectedDepth33Infotable() {
        ArtifactSchemaNode leaf = ArtifactSchemaNode.field("v", BaseTypes.STRING);
        ArtifactSchemaNode node = leaf;
        for (int i = 0; i < 33; i++) {
            node = ArtifactSchemaNode.infotable("t" + i, List.of(node));
        }
        final ArtifactSchemaNode deep = node;
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> cache.create(ArtifactCreateRequest.builder(ArtifactKind.TABULAR, deep).build(),
                        ctx("alice", "s1"), limits()));
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST, ex.code());
        assertEquals(0, store.exclusiveCreates.get());
    }

    @Test
    void rejectedOverMaxNodes() {
        List<ArtifactSchemaNode> cols = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            cols.add(ArtifactSchemaNode.field("c" + i, BaseTypes.STRING));
        }
        // root INFOTABLE + 4096 children = 4097 visits
        ArtifactSchemaNode wide = ArtifactSchemaNode.infotable("", cols);
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> cache.create(ArtifactCreateRequest.builder(ArtifactKind.TABULAR, wide).build(),
                        ctx("alice", "s1"), limits()));
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST, ex.code());
        assertEquals(0, store.exclusiveCreates.get());
    }

    @Test
    void acceptedExactlyMaxNodes() throws Exception {
        List<ArtifactSchemaNode> cols = new ArrayList<>();
        for (int i = 0; i < 4095; i++) {
            cols.add(ArtifactSchemaNode.field("c" + i, BaseTypes.STRING));
        }
        // root + 4095 children = 4096
        ArtifactSchemaNode wide = ArtifactSchemaNode.infotable("", cols);
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactWriter w = cache.create(
                ArtifactCreateRequest.builder(ArtifactKind.TABULAR, wide).build(),
                ctx("alice", "s1"), limits());
        assertEquals(1, store.exclusiveCreates.get());
        w.abort();
    }

    @Test
    void recordPublicApiHidesNamespaceKeyAndPath() throws Exception {
        Method[] methods = ArtifactRecord.class.getMethods();
        for (Method m : methods) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            assertNotEquals("namespaceKey", m.getName());
            assertNotEquals("relativePath", m.getName());
        }
        assertFalse(Modifier.isPublic(ArtifactRecord.class.getDeclaredMethod("namespaceKey").getModifiers()));
        assertFalse(Modifier.isPublic(ArtifactRecord.class.getDeclaredMethod("relativePath").getModifiers()));
    }

    @Test
    void abortFromOpenDeletesAndNoPublish() throws Exception {
        CountingPayloadStore store = new CountingPayloadStore(new InMemoryArtifactPayloadStore());
        FileArtifactCache cache = new FileArtifactCache("repo-a", store);
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writer.abort();
        assertTrue(writer.isAborted());
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class, () -> cache.publish(writer, access)).code());
        writer.abort(); // idempotent
    }

    @Test
    void abortFromClosedUnpublished() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writer.close();
        writer.abort();
        assertTrue(writer.isAborted());
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class, () -> cache.publish(writer, access)).code());
    }

    @Test
    void doublePublishReturnsSameRef() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writer.close();
        ArtifactRef a = cache.publish(writer, access);
        ArtifactRef b = cache.publish(writer, access);
        assertEquals(a.artifactId(), b.artifactId());
        writer.abort(); // no-op after publish
        try (ArtifactReader reader = cache.open(a, access, limits())) {
            assertEquals("x", new String(readAll(reader), StandardCharsets.UTF_8));
        }
    }

    @Test
    void publishBeforeCloseNoHandle() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class, () -> cache.publish(writer, access)).code());
        writer.close();
    }

    @Test
    void publishAbortRaceExactlyOneWinner() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cache.create(opaqueRequest(), access, limits());
        writer.writeBytes("race".getBytes(StandardCharsets.UTF_8));
        writer.close();
        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        Thread pub = new Thread(() -> {
            bothReady.countDown();
            try {
                start.await();
                cache.publish(writer, access);
                publishes.incrementAndGet();
            } catch (Exception e) {
                // refused after abort wins
            }
        });
        Thread ab = new Thread(() -> {
            bothReady.countDown();
            try {
                start.await();
                writer.abort();
                aborts.incrementAndGet();
            } catch (Exception e) {
                // unexpected
            }
        });
        pub.start();
        ab.start();
        assertTrue(bothReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        pub.join();
        ab.join();
        assertEquals(1, aborts.get());
        assertTrue(writer.isPublished() || writer.isAborted());
        if (writer.isPublished()) {
            try (ArtifactReader reader = cache.open(
                    ArtifactRef.ofValidated(((StreamingArtifactWriter) writer).publishedRef().artifactId()),
                    access, limits())) {
                assertEquals("race", new String(readAll(reader), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void crossCachePublishRejected() throws Exception {
        FileArtifactCache cacheA = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        FileArtifactCache cacheB = new FileArtifactCache("repo-b", new InMemoryArtifactPayloadStore());
        ArtifactAccessContext access = ctx("alice", "s1");
        ArtifactWriter writer = cacheA.create(opaqueRequest(), access, limits());
        writer.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writer.close();
        assertEquals(ArtifactCacheFaultCode.INVALID_REQUEST,
                assertThrows(ArtifactCacheException.class, () -> cacheB.publish(writer, access)).code());
    }

    @Test
    void coreBlankConfigFailsClosed() {
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> ArtifactCacheCore.requireConfigured("  ",
                        org.slf4j.LoggerFactory.getLogger("t"), "agent"));
        assertEquals(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, ex.code());
    }

    @Test
    void registrySharesAndIsolatesByRepoName() {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        FileArtifactCache a = FileArtifactCacheRegistry.getOrCreate("RepoOne", store);
        assertTrue(a == FileArtifactCacheRegistry.getOrCreate("RepoOne", store));
        assertFalse(a == FileArtifactCacheRegistry.getOrCreate("RepoTwo", new InMemoryArtifactPayloadStore()));
    }

    @Test
    void retainedFileCollisionRetriesWithFreshIdsThenSucceeds() throws Exception {
        InMemoryArtifactPayloadStore store = new InMemoryArtifactPayloadStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
        String collide1 = "11111111-1111-1111-1111-111111111111";
        String collide2 = "22222222-2222-2222-2222-222222222222";
        String free = "33333333-3333-3333-3333-333333333333";
        store.seed(ArtifactPathLayout.encodeUsername("alice") + "/2026-07-17/" + collide1 + ".payload",
                new byte[] {1});
        store.seed(ArtifactPathLayout.encodeUsername("alice") + "/2026-07-17/" + collide2 + ".payload",
                new byte[] {1});
        AtomicInteger n = new AtomicInteger();
        List<String> ids = List.of(collide1, collide2, free);
        FileArtifactCache cache = new FileArtifactCache("repo-a", store, clock, () -> ids.get(n.getAndIncrement()));
        ArtifactWriter writer = cache.create(opaqueRequest(), ctx("alice", "s1"), limits());
        writer.writeBytes("ok".getBytes(StandardCharsets.UTF_8));
        writer.close();
        assertEquals(free, cache.publish(writer, ctx("alice", "s1")).artifactId());
    }

    @Test
    void concurrentForcedSameIdOverlapsAtStore() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryArtifactPayloadStore mem = new InMemoryArtifactPayloadStore();
        ArtifactPayloadStore blocking = new ArtifactPayloadStore() {
            @Override
            public java.io.OutputStream exclusiveCreate(String relativePath) throws ArtifactCacheException {
                bothEntered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ArtifactCacheException(ArtifactCacheFaultCode.INTERNAL, "interrupted");
                }
                return mem.exclusiveCreate(relativePath);
            }

            @Override
            public java.io.InputStream openRead(String relativePath) throws ArtifactCacheException {
                return mem.openRead(relativePath);
            }

            @Override
            public void bestEffortDelete(String relativePath) {
                mem.bestEffortDelete(relativePath);
            }

            @Override
            public boolean exists(String relativePath) {
                return mem.exists(relativePath);
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
        String forcedId = "22222222-2222-2222-2222-222222222222";
        FileArtifactCache cache = new FileArtifactCache("repo-a", blocking, clock, () -> forcedId);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger collisions = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(new Thread(() -> {
                try {
                    ArtifactWriter w = cache.create(opaqueRequest(), ctx("alice", "s1"), limits());
                    w.writeBytes("a".getBytes(StandardCharsets.UTF_8));
                    w.close();
                    cache.publish(w, ctx("alice", "s1"));
                    successes.incrementAndGet();
                } catch (ArtifactCacheException e) {
                    if (e.code() == ArtifactCacheFaultCode.CREATE_COLLISION) {
                        collisions.incrementAndGet();
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        assertTrue(bothEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(1, successes.get());
        assertTrue(collisions.get() >= 1);
    }

    @Test
    void itemDeltaOverflowRejected() throws Exception {
        FileArtifactCache cache = new FileArtifactCache("repo-a", new InMemoryArtifactPayloadStore());
        ArtifactWriter writer = cache.create(opaqueRequest(), ctx("alice", "s1"),
                ArtifactIoLimits.of(1_000_000, 5, 60_000, 64));
        writer.addProducerItemDelta(5);
        assertEquals(ArtifactCacheFaultCode.IO_LIMIT_EXCEEDED,
                assertThrows(ArtifactCacheException.class, () -> writer.addProducerItemDelta(1)).code());
    }
}
