package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;

public final class OverlaySectionStorage extends SectionStorage {
    private final SectionStorage writable;
    private final SectionStorage fallback;

    public OverlaySectionStorage(SectionStorage writable, SectionStorage fallback) {
        this.writable = writable;
        this.fallback = fallback;
    }

    @Override
    public int loadSection(me.cortex.voxy.common.world.WorldSection into) {
        int status = this.writable.loadSection(into);
        if (status == 0) {
            return 0;
        }
        return this.fallback.loadSection(into);
    }

    @Override
    public void saveSection(me.cortex.voxy.common.world.WorldSection section) {
        this.writable.saveSection(section);
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.writable.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        Int2ObjectOpenHashMap<byte[]> merged = this.fallback.getIdMappingsData();
        merged.putAll(this.writable.getIdMappingsData());
        return merged;
    }

    @Override
    public void flush() {
        this.writable.flush();
        this.fallback.flush();
    }

    @Override
    public void close() {
        this.writable.close();
        this.fallback.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        Set<Long> seen = new HashSet<>();
        this.writable.iteratePositions(level, pos -> {
            if (seen.add(pos)) {
                consumer.accept(pos);
            }
        });
        this.fallback.iteratePositions(level, pos -> {
            if (seen.add(pos)) {
                consumer.accept(pos);
            }
        });
    }

    public static final class Config extends SectionStorageConfig {
        public SectionStorageConfig writable;
        public SectionStorageConfig fallback;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new OverlaySectionStorage(this.writable.build(ctx), this.fallback.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Overlay";
        }
    }
}
