package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    // Versions before 5 can contain synthetic-light previews or snow/cave-biased
    // mip lighting from the NeoForge backport. Drop and regenerate those sections.
    public static final int STORAGE_VERSION = 5;
    private static final int SOURCE_KIND_SHIFT = 24;
    private static final int CONFIDENCE_SHIFT = 28;
    private static final int LIGHT_KIND_SHIFT = 32;
    private static final long FOUR_BIT_MASK = 0xFL;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }
    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section.data;

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        long prev = data[0]; MemoryUtil.memPutLong(ptr, prev); ptr+=8; LUT.put(prev, (short) 0);
        short mapping = 0;
        for (long block : data) {
            if (prev != block) {
                prev = block;
                mapping = LUT.putIfAbsent(block, (short) LUT.size());
                if (mapping == -1) {
                    mapping = (short) (LUT.size()-1);
                    MemoryUtil.memPutLong(ptr, block); ptr+=8;
                }
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte
        metadata |= ((long) section.getSourceKind().ordinal() & FOUR_BIT_MASK) << SOURCE_KIND_SHIFT;
        metadata |= ((long) section.getConfidence().ordinal() & FOUR_BIT_MASK) << CONFIDENCE_SHIFT;
        metadata |= ((long) section.getLightSourceKind().ordinal() & FOUR_BIT_MASK) << LIGHT_KIND_SHIFT;
        metadata |= Integer.toUnsignedLong(STORAGE_VERSION & 0xFF) << 56;

        MemoryUtil.memPutLong(metadataPtr, metadata);
        //TODO: do hash

        return buffer.subSize(ptr-buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        int storageVersion = (int) ((metadata >>> 56) & 0xFF);
        if (storageVersion != STORAGE_VERSION) {
            Logger.warn("Dropping stale Voxy section "
                    + section.lvl + ", " + section.x + ", " + section.y + ", " + section.z
                    + " from storage version " + storageVersion + " expected " + STORAGE_VERSION);
            return false;
        }
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        section.sourceKind = enumAt(
                VoxelizedSection.SourceKind.values(),
                (int) ((metadata >>> SOURCE_KIND_SHIFT) & FOUR_BIT_MASK),
                VoxelizedSection.SourceKind.UNKNOWN
        );
        section.confidence = enumAt(
                VoxelizedSection.Confidence.values(),
                (int) ((metadata >>> CONFIDENCE_SHIFT) & FOUR_BIT_MASK),
                VoxelizedSection.Confidence.UNKNOWN
        );
        section.lightSourceKind = enumAt(
                VoxelizedSection.LightSourceKind.values(),
                (int) ((metadata >>> LIGHT_KIND_SHIFT) & FOUR_BIT_MASK),
                VoxelizedSection.LightSourceKind.UNKNOWN
        );
        section.dataEpoch = 1L;
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;

        final var blockData = section.data;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            blockData[i] = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);ptr += 2;
        }

        if (section.lvl == 0) {
            int emptyBlockCount = 0;
            for (long block : blockData) {
                emptyBlockCount += Mapper.isAir(block) ? 1 : 0;
            }
            section.nonEmptyBlockCount = WorldSection.SECTION_VOLUME-emptyBlockCount;
        }

        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        return true;
    }

    private static <T> T enumAt(T[] values, int index, T fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }
}
