package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.commonImpl.serverlod.ServerLodDiagnostics;
import me.cortex.voxy.commonImpl.serverlod.ServerLodSectionTileCodec;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileKey;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileStore;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;

public final class ServerLodCacheSectionStorage extends SectionStorage {
    private static final boolean SYNTHESIZE_MISSING_PARENTS = Boolean.parseBoolean(
            System.getProperty("voxy.serverLodCacheSynthesizeMissingParents", "true"));
    private static final int SYNTHESIS_DEPTH = Math.max(
            0,
            Integer.getInteger("voxy.serverLodCacheSynthesisDepth", WorldEngine.MAX_LOD_LAYER));

    private final ServerLodTileStore store;
    private final String dimension;

    public ServerLodCacheSectionStorage(Path cacheRoot, String dimension) {
        this.store = new ServerLodTileStore(cacheRoot, false);
        this.dimension = dimension;
        ServerLodDiagnostics.serverLodCacheIndexedTiles.set(this.store.localIndexedTileCount());
    }

    @Override
    public int loadSection(WorldSection into) {
        if (this.loadExact(into, true)) {
            return 0;
        }
        if (SYNTHESIZE_MISSING_PARENTS && this.synthesizeFromChildren(into, SYNTHESIS_DEPTH)) {
            ServerLodDiagnostics.serverLodCacheRenderLoads.incrementAndGet();
            ServerLodDiagnostics.serverLodCacheSynthesizedParents.incrementAndGet();
            ServerLodDiagnostics.recordBoundaryLoad(into, true);
            ServerLodDiagnostics.firstLodVisibleMs.compareAndSet(-1, System.currentTimeMillis());
            return 0;
        }
        return 1;
    }

    private boolean loadExact(WorldSection into, boolean recordDiagnostics) {
        var key = new ServerLodTileKey(this.dimension, into.lvl, into.x, into.y, into.z, SaveLoadSystem3.STORAGE_VERSION);
        var tile = this.store.read(key);
        if (tile.isEmpty()) {
            if (recordDiagnostics) {
                ServerLodDiagnostics.serverLodCacheRenderMisses.incrementAndGet();
                ServerLodDiagnostics.recordBoundaryLoad(into, false);
            }
            return false;
        }
        if (ServerLodSectionTileCodec.loadSection(tile.get(), into)) {
            if (recordDiagnostics) {
                ServerLodDiagnostics.serverLodCacheRenderLoads.incrementAndGet();
                ServerLodDiagnostics.recordBoundaryLoad(into, true);
                ServerLodDiagnostics.firstLodVisibleMs.compareAndSet(-1, System.currentTimeMillis());
            }
            return true;
        }
        if (recordDiagnostics) {
            ServerLodDiagnostics.serverLodCacheDecodeFailures.incrementAndGet();
            ServerLodDiagnostics.serverLodCacheRenderRejected.incrementAndGet();
            ServerLodDiagnostics.recordBoundaryRejected(into);
        }
        return false;
    }

    private boolean synthesizeFromChildren(WorldSection into, int remainingDepth) {
        Mapper mapper = ServerLodSectionTileCodec.runtimeMapper();
        if (mapper == null || into.lvl <= 0 || remainingDepth <= 0) {
            return false;
        }

        WorldSection[] children = new WorldSection[8];
        byte childMask = 0;
        boolean anyChild = false;
        for (int childX = 0; childX < 2; childX++) {
            for (int childY = 0; childY < 2; childY++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = WorldSection.getChildIndex(childX, childY, childZ);
                    WorldSection child = WorldSection._createRawUntrackedUnsafeSection(
                            into.lvl - 1,
                            (into.x << 1) + childX,
                            (into.y << 1) + childY,
                            (into.z << 1) + childZ);
                    if (this.loadExact(child, false) || this.synthesizeFromChildren(child, remainingDepth - 1)) {
                        children[childIndex] = child;
                        anyChild = true;
                        if (child.getNonEmptyChildren() != 0 || containsNonAir(child)) {
                            childMask = (byte) (childMask | (1 << childIndex));
                        }
                    }
                }
            }
        }
        if (!anyChild) {
            return false;
        }

        Arrays.fill(into._unsafeGetRawDataArray(), Mapper.AIR);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    into.set(x, y, z, Mipper.mip(
                            childSample(children, x, y, z, 0, 0, 0),
                            childSample(children, x, y, z, 1, 0, 0),
                            childSample(children, x, y, z, 0, 0, 1),
                            childSample(children, x, y, z, 1, 0, 1),
                            childSample(children, x, y, z, 0, 1, 0),
                            childSample(children, x, y, z, 1, 1, 0),
                            childSample(children, x, y, z, 0, 1, 1),
                            childSample(children, x, y, z, 1, 1, 1),
                            mapper,
                            VoxelizedSection.SourceKind.REAL_CHUNK,
                            VoxelizedSection.Confidence.HIGH,
                            VoxelizedSection.LightSourceKind.REAL_LIGHT));
                }
            }
        }
        into._unsafeSetNonEmptyChildren(childMask);
        into.setPublicationMetadata(VoxelizedSection.createEmpty()
                .setSource(VoxelizedSection.SourceKind.REAL_CHUNK, VoxelizedSection.Confidence.HIGH)
                .setLightSourceKind(VoxelizedSection.LightSourceKind.REAL_LIGHT));
        return childMask != 0;
    }

    private static boolean containsNonAir(WorldSection section) {
        for (long value : section._unsafeGetRawDataArray()) {
            if (value != Mapper.AIR) {
                return true;
            }
        }
        return false;
    }

    private static long childSample(WorldSection[] children, int parentX, int parentY, int parentZ, int bitX, int bitY, int bitZ) {
        int fineX = (parentX << 1) | bitX;
        int fineY = (parentY << 1) | bitY;
        int fineZ = (parentZ << 1) | bitZ;
        int childIndex = WorldSection.getChildIndex(fineX >> 5, fineY >> 5, fineZ >> 5);
        WorldSection child = children[childIndex];
        if (child == null) {
            return Mapper.AIR;
        }
        return child._unsafeGetRawDataArray()[WorldSection.getIndex(fineX & 31, fineY & 31, fineZ & 31)];
    }

    @Override
    public void saveSection(WorldSection section) {
        // Synced server LoD cache is read-only. OverlaySectionStorage routes saves to local writable storage.
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        // Read-only fallback storage. Mapping writes belong to the local writable storage.
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        // Do not bulk-read server LoD tiles during Mapper startup. Large VLCP3
        // regions can contain hundreds of thousands of tiles; mappings are
        // imported lazily from each tile payload as sections are loaded.
        return new Int2ObjectOpenHashMap<>();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        Set<Long> seen = new HashSet<>();
        for (var metadata : this.store.manifest(Integer.MAX_VALUE)) {
            var key = metadata.key();
            if (key.lodLevel() == level && key.dimension().equals(this.dimension)) {
                long packed = WorldEngine.getWorldSectionId(level, key.sectionX(), key.sectionY(), key.sectionZ());
                if (seen.add(packed)) {
                    ServerLodDiagnostics.serverLodCacheIteratedPositions.incrementAndGet();
                    consumer.accept(packed);
                }
            }
        }
    }
}
