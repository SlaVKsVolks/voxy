package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.commonImpl.serverlod.ServerLodDiagnostics;
import me.cortex.voxy.commonImpl.serverlod.ServerLodGenerationBridge;
import me.cortex.voxy.commonImpl.serverlod.ServerLodSectionTileCodec;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileStore;
import me.cortex.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

public final class ServerLodPublishingSectionStorage extends SectionStorage {
    private final SectionStorage delegate;
    private final String dimension;

    public ServerLodPublishingSectionStorage(SectionStorage delegate, String dimension) {
        this.delegate = delegate;
        this.dimension = dimension;
    }

    @Override
    public int loadSection(WorldSection into) {
        return this.delegate.loadSection(into);
    }

    @Override
    public void saveSection(WorldSection section) {
        this.delegate.saveSection(section);
        publish(section);
    }

    private void publish(WorldSection section) {
        if (!section.hasTrustedRealData()) {
            return;
        }
        var tile = ServerLodSectionTileCodec.createTile(this.dimension, section, this.delegate.getIdMappingsData());
        var result = ServerLodGenerationBridge.publishTile(tile);
        if (result == ServerLodTileStore.StoreResult.STORED) {
            ServerLodDiagnostics.serverLodTilesPublishedFromSections.incrementAndGet();
        } else {
            ServerLodDiagnostics.serverLodTilesRejectedFromSections.incrementAndGet();
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.delegate.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.delegate.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.delegate.flush();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.delegate.iteratePositions(level, consumer);
    }
}
