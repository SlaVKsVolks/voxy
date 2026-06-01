package me.cortex.voxy.client.sodium.provider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VoxySectionInvalidationTracker {
    private final ConcurrentMap<Long, Long> invalidationVersions = new ConcurrentHashMap<>();

    public long invalidate(long sectionKey) {
        return this.invalidationVersions.merge(sectionKey, 1L, Long::sum);
    }

    public long version(long sectionKey) {
        return this.invalidationVersions.getOrDefault(sectionKey, 0L);
    }

    public boolean isStale(long sectionKey, long capturedVersion) {
        return capturedVersion != this.version(sectionKey);
    }

    public void clear() {
        this.invalidationVersions.clear();
    }
}
