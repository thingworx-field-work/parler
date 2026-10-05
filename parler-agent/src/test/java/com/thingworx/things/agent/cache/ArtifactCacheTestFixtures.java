package com.thingworx.things.agent.cache;

/** Explicit in-memory Artifact Cache injection for unit tests; never used by production code. */
public final class ArtifactCacheTestFixtures {

    private static final String TEST_PRINCIPAL = "parler-artifact-cache-test";

    private ArtifactCacheTestFixtures() {}

    /** Reset shared Hub state, then install a fresh cache and stable test principal. */
    public static void installFreshInMemoryCache() {
        TabularArtifactHub.clearTestState();
        TabularArtifactHub.setTestArtifactCache(
                new FileArtifactCache("parler-artifact-cache-test", new InMemoryArtifactPayloadStore()));
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> TEST_PRINCIPAL);
    }

    /**
     * Same real in-memory cache behind a delegating wrapper that can run a hook when an output artifact is
     * created, and that counts publications. Lets a test make time pass, or an interrupt arrive, inside
     * the store path, after every check the producer made on its own.
     */
    public static HookedCache installFreshHookedInMemoryCache() {
        TabularArtifactHub.clearTestState();
        HookedCache hooked = new HookedCache(
                new FileArtifactCache("parler-artifact-cache-test", new InMemoryArtifactPayloadStore()));
        TabularArtifactHub.setTestArtifactCache(hooked);
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> TEST_PRINCIPAL);
        return hooked;
    }

    public static final class HookedCache implements ArtifactCache {
        private final ArtifactCache delegate;
        private volatile Runnable onCreate;
        private final java.util.concurrent.atomic.AtomicInteger publications =
                new java.util.concurrent.atomic.AtomicInteger();

        HookedCache(ArtifactCache delegate) {
            this.delegate = delegate;
        }

        /** Arm after the source table is stored, so only output creation runs the hook. */
        public void onNextCreates(Runnable hook) {
            this.onCreate = hook;
            publications.set(0);
        }

        /** Artifacts made visible since the hook was armed. */
        public int publicationsSinceArmed() {
            return publications.get();
        }

        @Override
        public ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
                ArtifactIoLimits limits) throws ArtifactCacheException {
            Runnable hook = onCreate;
            if (hook != null) {
                hook.run();
            }
            return delegate.create(request, context, limits);
        }

        @Override
        public ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context)
                throws ArtifactCacheException {
            ArtifactRef ref = delegate.publish(writer, context);
            publications.incrementAndGet();
            return ref;
        }

        @Override
        public ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits)
                throws ArtifactCacheException {
            return delegate.open(ref, context, limits);
        }

        @Override
        public void invalidate(ArtifactRef ref, ArtifactAccessContext context) {
            delegate.invalidate(ref, context);
        }

        @Override
        public void invalidateScope(ArtifactAccessContext context) {
            delegate.invalidateScope(context);
        }
    }
}
