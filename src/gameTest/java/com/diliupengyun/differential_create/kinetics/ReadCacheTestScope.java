package com.diliupengyun.differential_create.kinetics;

/** Lets integration tests exercise the production tick scope without exposing it in the mod API. */
public final class ReadCacheTestScope implements AutoCloseable {
    private final KineticReadCache cache;
    public ReadCacheTestScope(boolean enabled) { cache = enabled ? KineticReadCache.open() : null; }
    @Override public void close() { if (cache != null) cache.close(); }
}
