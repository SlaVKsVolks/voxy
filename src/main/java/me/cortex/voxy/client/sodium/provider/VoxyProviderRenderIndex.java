package me.cortex.voxy.client.sodium.provider;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VoxyProviderRenderIndex {
    private final ConcurrentHashMap<Long, VoxyProviderRenderCell> cells = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    public record RecordResult(
            boolean recorded,
            boolean suppressedIncomingParent,
            long[] suppressedAncestorSectionKeys,
            long[] retainedDescendantSectionKeys
    ) {
        public RecordResult {
            suppressedAncestorSectionKeys = suppressedAncestorSectionKeys == null
                    ? new long[0]
                    : suppressedAncestorSectionKeys.clone();
            retainedDescendantSectionKeys = retainedDescendantSectionKeys == null
                    ? new long[0]
                    : retainedDescendantSectionKeys.clone();
        }

        @Override
        public long[] suppressedAncestorSectionKeys() {
            return this.suppressedAncestorSectionKeys.clone();
        }

        @Override
        public long[] retainedDescendantSectionKeys() {
            return this.retainedDescendantSectionKeys.clone();
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

    public void retainOnly(Set<Long> sectionKeys) {
        boolean removed = false;
        for (Long existingKey : this.cells.keySet()) {
            if (!sectionKeys.contains(existingKey) && this.cells.remove(existingKey) != null) {
                removed = true;
            }
        }
        if (removed) {
            this.epoch.incrementAndGet();
        }
    }

    public RecordResult record(VoxyProviderRenderCell cell) {
        if (cell.renderOwned()) {
            VoxyProviderRenderCell existing = this.cells.get(cell.sectionKey());
            if (cell.equals(existing)) {
                return new RecordResult(true, false, new long[0], new long[0]);
            }
            if (this.hasRenderOwnedDescendant(cell.sectionKey())) {
                return this.suppressIncomingParent(cell.sectionKey());
            }
            long[] suppressedAncestors = this.removeRenderOwnedAncestors(cell.sectionKey());
            this.cells.put(cell.sectionKey(), cell);
            this.epoch.incrementAndGet();
            return new RecordResult(true, false, suppressedAncestors, new long[0]);
        } else {
            if (this.cells.remove(cell.sectionKey()) != null) {
                this.epoch.incrementAndGet();
            }
            return new RecordResult(false, false, new long[0], new long[0]);
        }
    }

    public RecordResult suppressIncomingParent(long sectionKey) {
        long[] retainedDescendants = this.renderOwnedDescendantSectionKeys(sectionKey);
        if (retainedDescendants.length == 0) {
            return new RecordResult(false, false, new long[0], new long[0]);
        }
        if (this.cells.remove(sectionKey) != null) {
            this.epoch.incrementAndGet();
        }
        return new RecordResult(false, true, new long[0], retainedDescendants);
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

    public VoxyTerrainOwnership ownershipForSection(long sectionKey) {
        VoxyProviderRenderCell cell = this.cells.get(sectionKey);
        return cell == null ? VoxyTerrainOwnership.EMPTY_OUTSIDE_DISTANCE : cell.ownership();
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

    private long[] renderOwnedDescendantSectionKeys(long sectionKey) {
        ArrayList<Long> descendants = new ArrayList<>();
        for (VoxyProviderRenderCell existing : this.cells.values()) {
            long existingKey = existing.sectionKey();
            if (existingKey != sectionKey
                    && existing.renderOwned()
                    && this.isAncestorOf(sectionKey, existingKey)) {
                descendants.add(existingKey);
            }
        }
        long[] sectionKeys = new long[descendants.size()];
        for (int i = 0; i < descendants.size(); i++) {
            sectionKeys[i] = descendants.get(i);
        }
        return sectionKeys;
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
