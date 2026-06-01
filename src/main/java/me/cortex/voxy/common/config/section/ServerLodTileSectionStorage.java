package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.commonImpl.serverlod.ServerLodDiagnostics;
import me.cortex.voxy.commonImpl.serverlod.ServerLodGenerationBridge;
import me.cortex.voxy.commonImpl.serverlod.ServerLodSectionTileCodec;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileStore;
import me.cortex.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.LongConsumer;

public final class ServerLodTileSectionStorage extends SectionStorage {
    private final ServerLodTileStore store;
    private final String dimension;
    private final Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();

    public ServerLodTileSectionStorage(Path storeRoot, String dimension) {
        this.store = new ServerLodTileStore(storeRoot);
        this.dimension = dimension;
    }

    public ServerLodTileSectionStorage(ServerLodTileStore store, String dimension) {
        this.store = store;
        this.dimension = dimension;
    }

    @Override
    public int loadSection(WorldSection into) {
        return 1;
    }

    @Override
    public void saveSection(WorldSection section) {
        var tile = ServerLodSectionTileCodec.createTile(this.dimension, section, this.mappings);
        var result = ServerLodGenerationBridge.publishTile(this.store, tile);
        if (result == ServerLodTileStore.StoreResult.STORED) {
            ServerLodDiagnostics.serverLodTilesPublishedFromSections.incrementAndGet();
        } else {
            ServerLodDiagnostics.serverLodTilesRejectedFromSections.incrementAndGet();
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        byte[] copy = new byte[data.remaining()];
        data.slice().get(copy);
        this.mappings.put(id, copy);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return new Int2ObjectOpenHashMap<>(this.mappings);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
    }
}
