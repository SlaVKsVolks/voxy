package me.cortex.voxy.client.sodium.provider;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VoxyProviderRenderIndex {
    private final ConcurrentHashMap<Long, VoxyProviderRenderCell> cells = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    public record RecordResult(
            boolean recorded,
            boolean suppressedIncomingParent,
            long[] suppressedAncestorSectionKeys
    ) {
        public RecordResult {
            suppressedAncestorSectionKeys = suppressedAncestorSectionKeys == null
                    ? new long[0]
                    : suppressedAncestorSectionKeys.clone();
        }

        @Override
        public long[] suppressedAncestorSectionKeys() {
            return this.suppressedAncestorSectionKeys.clone();
        }

        public int suppressedAncestorCount() {
            return this.suppressedAncestorSectionKeys.length;
        }
    }

    public void clear() {
        if (!this.cells.isEmpty()) {
            this.cells.clear();
            this.epoch.incrementAndGet();
        }
    }

    public void remove(long sectionKey) {
        if (this.cells.remove(sectionKey) != null) {
            this.epoch.incrementAndGet();
        }
    }

    public RecordResult record(VoxyProviderRenderCell cell) {
        if (cell.renderOwned()) {
            if (this.hasRenderOwnedDescendant(cell.sectionKey())) {
                this.cells.remove(cell.sectionKey());
                this.epoch.incrementAndGet();
                return new RecordResult(false, true, new long[0]);
            }
            long[] suppressedAncestors = this.removeRenderOwnedAncestors(cell.sectionKey());
            this.cells.put(cell.sectionKey(), cell);
            this.epoch.incrementAndGet();
            return new RecordResult(true, false, suppressedAncestors);
        } else {
            if (this.cells.remove(cell.sectionKey()) != null) {
                this.epoch.incrementAndGet();
            }
            return new RecordResult(false, false, new long[0]);
        }
    }

    public long size() {
        return this.cells.size();
    }

    public long renderOwnedSections() {
        return this.cells.values().stream().filter(VoxyProviderRenderCell::renderOwned).count();
    }

    public long sectionsForPass(VoxyTerrainPass pass) {
        return this.cells.values().stream()
                .filter(VoxyProviderRenderCell::renderOwned)
                .filter(cell -> cell.supports(pass))
                .count();
    }

    public int[] meshIdsForPass(VoxyTerrainPass pass) {
        return this.cells.values().stream()
                .filter(VoxyProviderRenderCell::renderOwned)
                .filter(cell -> cell.supports(pass))
                .mapToInt(VoxyProviderRenderCell::meshId)
                .sorted()
                .toArray();
    }

    public VoxyProviderRenderList snapshotForPass(VoxyTerrainPass pass) {
        int[] meshIds = this.meshIdsForPass(pass);
        return new VoxyProviderRenderList(
                pass,
                meshIds,
                this.epoch.get(),
                meshIds.length,
                VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS
        );
    }

    public long parentFallbackSections() {
        return this.cells.values().stream()
                .filter(VoxyProviderRenderCell::renderOwned)
                .filter(cell -> cell.ownership() == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK)
                .count();
    }

    public int passMaskForSection(long sectionKey, int fallbackPassMask) {
        VoxyProviderRenderCell cell = this.cells.get(sectionKey);
        return cell == null ? fallbackPassMask : cell.passMask();
    }

    private long[] removeRenderOwnedAncestors(long sectionKey) {
        ArrayList<Long> suppressed = new ArrayList<>();
        for (Long existingKey : this.cells.keySet()) {
            if (existingKey.longValue() != sectionKey
                    && this.isAncestorOf(existingKey, sectionKey)
                    && this.cells.remove(existingKey) != null) {
                suppressed.add(existingKey);
            }
        }
        long[] sectionKeys = new long[suppressed.size()];
        for (int i = 0; i < suppressed.size(); i++) {
            sectionKeys[i] = suppressed.get(i);
        }
        return sectionKeys;
    }

    private boolean hasRenderOwnedDescendant(long sectionKey) {
        for (VoxyProviderRenderCell existing : this.cells.values()) {
            long existingKey = existing.sectionKey();
            if (existingKey != sectionKey
                    && existing.renderOwned()
                    && this.isAncestorOf(sectionKey, existingKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAncestorOf(long possibleAncestor, long possibleDescendant) {
        int ancestorLevel = WorldEngine.getLevel(possibleAncestor);
        int descendantLevel = WorldEngine.getLevel(possibleDescendant);
        if (ancestorLevel <= descendantLevel) {
            return false;
        }
        int shift = ancestorLevel - descendantLevel;
        return WorldEngine.getX(possibleAncestor) == (WorldEngine.getX(possibleDescendant) >> shift)
                && WorldEngine.getY(possibleAncestor) == (WorldEngine.getY(possibleDescendant) >> shift)
                && WorldEngine.getZ(possibleAncestor) == (WorldEngine.getZ(possibleDescendant) >> shift);
    }
}
