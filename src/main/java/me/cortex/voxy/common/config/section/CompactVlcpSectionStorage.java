package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;

public final class CompactVlcpSectionStorage extends SectionStorage {
    private static final byte[] MAGIC_V2 = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '2'};
    private static final byte[] INDEX_MAGIC_V2 = new byte[]{'V', 'L', 'C', 'P', 'I', '0', '0', '2'};
    private static final int FORMAT_VERSION_V2 = 2;
    private static final int INDEX_VERSION_V2 = 2;
    private static final short NO_Y = Short.MIN_VALUE;
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;
    private static final int PLAINS_BIOME_ID = 0;

    private final Path dataPath;
    private final RandomAccessFile dataFile;
    private final Header header;
    private final BatchIndexEntry[] index;
    private final Int2ObjectOpenHashMap<byte[]> idMappings = new Int2ObjectOpenHashMap<>();
    private final Object cacheLock = new Object();
    private final LinkedHashMap<Integer, Map<Long, ChunkRecord>> batchCache = new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Map<Long, ChunkRecord>> eldest) {
            return this.size() > 32;
        }
    };

    public CompactVlcpSectionStorage(Path dataPath) {
        try {
            this.dataPath = dataPath.toAbsolutePath().normalize();
            this.dataFile = new RandomAccessFile(this.dataPath.toFile(), "r");
            this.header = readHeader(this.dataFile);
            this.index = readOrBuildIndex(this.dataPath, this.dataFile, this.header);
            registerBlockMappings(this.idMappings);
            registerPlainsBiome(this.idMappings);
            Logger.info("Voxy compact VLCP storage mounted: " + this.dataPath + " chunks=" + this.header.totalChunks + " batches=" + this.index.length);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to mount compact Voxy VLCP storage: " + dataPath, exception);
        }
    }

    @Override
    public int loadSection(WorldSection into) {
        Arrays.fill(into._unsafeGetRawDataArray(), Mapper.AIR);
        int written = synthesizeSection(into);
        if (written == 0) {
            return 1;
        }
        if (into.lvl == 0) {
            into.updateLvl0State();
        } else {
            into._unsafeSetNonEmptyChildren((byte) 0xFF);
        }
        into.setPublicationMetadata(VoxelizedSection.createEmpty()
                .setSource(VoxelizedSection.SourceKind.SURFACE_PREVIEW, VoxelizedSection.Confidence.MEDIUM)
                .setLightSourceKind(VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW));
        return 0;
    }

    @Override
    public void saveSection(WorldSection section) {
        // Compact VLCP storage is read-only. OverlaySectionStorage routes saves to the writable delegate.
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        byte[] copy = new byte[data.remaining()];
        data.slice().get(copy);
        this.idMappings.put(id, copy);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return new Int2ObjectOpenHashMap<>(this.idMappings);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        try {
            this.dataFile.close();
        } catch (IOException exception) {
            Logger.warn("Failed closing compact VLCP storage " + this.dataPath, exception);
        }
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        int blockSize = 32 << level;
        int minBlockX = (this.header.centerX - this.header.radius) * 16;
        int maxBlockX = (this.header.centerX + this.header.radius) * 16 + 15;
        int minBlockZ = (this.header.centerZ - this.header.radius) * 16;
        int maxBlockZ = (this.header.centerZ + this.header.radius) * 16 + 15;
        int minSectionX = Math.floorDiv(minBlockX, blockSize);
        int maxSectionX = Math.floorDiv(maxBlockX, blockSize);
        int minSectionZ = Math.floorDiv(minBlockZ, blockSize);
        int maxSectionZ = Math.floorDiv(maxBlockZ, blockSize);
        int minSectionY = -1;
        int maxSectionY = Math.max(0, Math.floorDiv(192, blockSize));
        for (int y = minSectionY; y <= maxSectionY; y++) {
            for (int z = minSectionZ; z <= maxSectionZ; z++) {
                for (int x = minSectionX; x <= maxSectionX; x++) {
                    consumer.accept(WorldEngine.getWorldSectionId(level, x, y, z));
                }
            }
        }
    }

    private int synthesizeSection(WorldSection into) {
        int cellSize = 1 << into.lvl;
        int sectionBlockSize = 32 * cellSize;
        int minBlockX = into.x * sectionBlockSize;
        int minBlockY = into.y * sectionBlockSize;
        int minBlockZ = into.z * sectionBlockSize;
        int maxBlockX = minBlockX + sectionBlockSize - 1;
        int maxBlockZ = minBlockZ + sectionBlockSize - 1;
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
        int written = 0;
        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                ChunkRecord chunk = getChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                written += synthesizeChunkIntoSection(into, chunk, minBlockX, minBlockY, minBlockZ, cellSize);
            }
        }
        return written;
    }

    private int synthesizeChunkIntoSection(WorldSection into, ChunkRecord chunk, int minBlockX, int minBlockY, int minBlockZ, int cellSize) {
        int written = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                ColumnRecord column = chunk.columns[(lx << 4) | lz];
                int wx = chunk.cx * 16 + lx;
                int wz = chunk.cz * 16 + lz;
                written += putColumnVoxel(into, minBlockX, minBlockY, minBlockZ, cellSize, wx, column.surfaceY, wz, column.surfaceMaterial, column.surfaceLight);
                if (column.waterY != NO_Y) {
                    int waterMaterial = column.surfaceY < 55 ? 6 : 5;
                    written += putColumnVoxel(into, minBlockX, minBlockY, minBlockZ, cellSize, wx, column.waterY, wz, waterMaterial, 0xF0);
                    written += putColumnVoxel(into, minBlockX, minBlockY, minBlockZ, cellSize, wx, column.floorY, wz, column.floorMaterial, 0xE0);
                }
                for (int dy = 1; dy <= column.shellDepth; dy++) {
                    written += putColumnVoxel(into, minBlockX, minBlockY, minBlockZ, cellSize, wx, column.surfaceY - dy, wz, 2, 0xE0);
                }
                if (column.ravineDepth > 0) {
                    int step = column.coverage == 4 ? 1 : 2;
                    for (int dy = 0; dy < column.ravineDepth; dy += step) {
                        int y = column.surfaceY - dy;
                        if (y <= 4) {
                            break;
                        }
                        written += putColumnVoxel(into, minBlockX, minBlockY, minBlockZ, cellSize, wx, y, wz, 3, 0xD0);
                    }
                }
            }
        }
        return written;
    }

    private int putColumnVoxel(WorldSection into, int minBlockX, int minBlockY, int minBlockZ, int cellSize, int wx, int wy, int wz, int material, int light) {
        int localBlockX = wx - minBlockX;
        int localBlockY = wy - minBlockY;
        int localBlockZ = wz - minBlockZ;
        if (localBlockX < 0 || localBlockY < 0 || localBlockZ < 0) {
            return 0;
        }
        int x = localBlockX / cellSize;
        int y = localBlockY / cellSize;
        int z = localBlockZ / cellSize;
        if (x < 0 || x >= 32 || y < 0 || y >= 32 || z < 0 || z >= 32) {
            return 0;
        }
        long mapping = mapping(material, light);
        if (Mapper.isAir(mapping)) {
            return 0;
        }
        int idx = WorldSection.getIndex(x, y, z);
        long[] raw = into._unsafeGetRawDataArray();
        long previous = raw[idx];
        if (previous == Mapper.AIR || materialPriority(Mapper.getBlockId(mapping)) >= materialPriority(Mapper.getBlockId(previous))) {
            into.set(x, y, z, mapping);
            return previous == Mapper.AIR ? 1 : 0;
        }
        return 0;
    }

    private ChunkRecord getChunk(int cx, int cz) {
        if (!contains(cx, cz)) {
            return null;
        }
        long ordinal = chunkToOrdinal(cx, cz);
        int batchIndex = findBatchIndex(ordinal);
        if (batchIndex < 0) {
            return null;
        }
        Map<Long, ChunkRecord> batch;
        synchronized (this.cacheLock) {
            batch = this.batchCache.get(batchIndex);
        }
        if (batch == null) {
            batch = loadBatch(batchIndex);
            synchronized (this.cacheLock) {
                this.batchCache.put(batchIndex, batch);
            }
        }
        return batch.get(packChunk(cx, cz));
    }

    private Map<Long, ChunkRecord> loadBatch(int batchIndex) {
        BatchIndexEntry entry = this.index[batchIndex];
        byte[] compressed = new byte[Math.toIntExact(entry.compressedDataLength)];
        try {
            synchronized (this.dataFile) {
                this.dataFile.seek(entry.compressedDataOffset);
                this.dataFile.readFully(compressed);
            }
            byte[] payload = decompressZstd(compressed, Math.toIntExact(entry.rawPayloadLength));
            Map<Long, ChunkRecord> chunks = new java.util.HashMap<>(entry.chunkCount * 2);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                for (int i = 0; i < entry.chunkCount; i++) {
                    ChunkRecord chunk = readChunk(input);
                    chunks.put(packChunk(chunk.cx, chunk.cz), chunk);
                }
            }
            return chunks;
        } catch (IOException exception) {
            throw new RuntimeException("Failed loading compact VLCP batch " + batchIndex + " from " + this.dataPath, exception);
        }
    }

    private int findBatchIndex(long ordinal) {
        for (int i = 0; i < this.index.length; i++) {
            BatchIndexEntry entry = this.index[i];
            if (ordinal >= entry.firstChunkOrdinal && ordinal <= entry.lastChunkOrdinal) {
                return i;
            }
        }
        return -1;
    }

    private boolean contains(int cx, int cz) {
        int dx = cx - this.header.centerX;
        int dz = cz - this.header.centerZ;
        if (Math.abs(dx) > this.header.radius || Math.abs(dz) > this.header.radius) {
            return false;
        }
        return this.header.shape != 2 || ((long) dx * dx + (long) dz * dz) <= (long) this.header.radius * this.header.radius;
    }

    private long chunkToOrdinal(int cx, int cz) {
        long side = (long) this.header.radius * 2L + 1L;
        long localX = (long) cx - this.header.centerX + this.header.radius;
        long localZ = (long) cz - this.header.centerZ + this.header.radius;
        return localZ * side + localX;
    }

    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFF_FFFFL);
    }

    private static Header readHeader(RandomAccessFile input) throws IOException {
        input.seek(0L);
        byte[] magic = new byte[8];
        input.readFully(magic);
        if (!Arrays.equals(magic, MAGIC_V2)) {
            throw new IOException("Unsupported compact Voxy file magic. Expected VLCP0002.");
        }
        int version = readIntLE(input);
        if (version != FORMAT_VERSION_V2) {
            throw new IOException("Unsupported compact Voxy version: " + version);
        }
        int centerX = readIntLE(input);
        int centerZ = readIntLE(input);
        int radius = readIntLE(input);
        int shape = input.readUnsignedByte();
        input.readUnsignedByte(); // mode
        int storageProfile = input.readUnsignedByte();
        int compression = input.readUnsignedByte();
        long seed = readLongLE(input);
        long totalChunks = readLongLE(input);
        if (compression != 1) {
            throw new IOException("Unsupported compact Voxy compression: " + compression);
        }
        return new Header(centerX, centerZ, radius, shape, storageProfile, seed, totalChunks);
    }

    private static BatchIndexEntry[] readOrBuildIndex(Path dataPath, RandomAccessFile dataFile, Header header) throws IOException {
        Path indexPath = dataPath.resolveSibling(stripVlcpExtension(dataPath.getFileName().toString()) + ".vlcpi");
        if (Files.exists(indexPath)) {
            return readIndex(indexPath);
        }
        Logger.warn("Compact Voxy index missing, scanning once: " + indexPath);
        BatchIndexEntry[] entries = scanIndex(dataFile, header);
        writeIndex(indexPath, entries);
        return entries;
    }

    private static BatchIndexEntry[] readIndex(Path indexPath) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexPath)))) {
            byte[] magic = new byte[8];
            input.readFully(magic);
            if (!Arrays.equals(magic, INDEX_MAGIC_V2)) {
                throw new IOException("Unsupported compact Voxy index magic: " + indexPath);
            }
            int version = readIntLE(input);
            if (version != INDEX_VERSION_V2) {
                throw new IOException("Unsupported compact Voxy index version: " + version);
            }
            readIntLE(input); // flags
            long count = readLongLE(input);
            if (count > Integer.MAX_VALUE) {
                throw new IOException("Compact Voxy index has too many batches: " + count);
            }
            BatchIndexEntry[] entries = new BatchIndexEntry[(int) count];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new BatchIndexEntry(readLongLE(input), readLongLE(input), readIntLE(input), readIntLE(input), readLongLE(input), readLongLE(input));
            }
            return entries;
        }
    }

    private static void writeIndex(Path indexPath, BatchIndexEntry[] entries) throws IOException {
        try (var output = new java.io.DataOutputStream(Files.newOutputStream(indexPath))) {
            output.write(INDEX_MAGIC_V2);
            writeIntLE(output, INDEX_VERSION_V2);
            writeIntLE(output, 0);
            writeLongLE(output, entries.length);
            for (var entry : entries) {
                writeLongLE(output, entry.firstChunkOrdinal);
                writeLongLE(output, entry.lastChunkOrdinal);
                writeIntLE(output, entry.chunkCount);
                writeIntLE(output, entry.rawPayloadLength);
                writeLongLE(output, entry.compressedDataOffset);
                writeLongLE(output, entry.compressedDataLength);
            }
        }
    }

    private static BatchIndexEntry[] scanIndex(RandomAccessFile input, Header header) throws IOException {
        java.util.ArrayList<BatchIndexEntry> entries = new java.util.ArrayList<>();
        input.seek(44L);
        long consumedChunks = 0L;
        while (true) {
            long batchHeaderOffset = input.getFilePointer();
            BatchHeader batch = readBatchHeader(input);
            if (batch == null) {
                break;
            }
            long dataOffset = batchHeaderOffset + 12L;
            entries.add(new BatchIndexEntry(consumedChunks, consumedChunks + batch.chunkCount - 1L, batch.chunkCount, batch.rawPayloadLength, dataOffset, batch.compressedDataLength));
            consumedChunks += batch.chunkCount;
            input.seek(dataOffset + batch.compressedDataLength);
        }
        if (consumedChunks != header.totalChunks) {
            Logger.warn("Compact Voxy index scan chunk count mismatch: expected=" + header.totalChunks + " got=" + consumedChunks);
        }
        return entries.toArray(BatchIndexEntry[]::new);
    }

    private static BatchHeader readBatchHeader(RandomAccessFile input) throws IOException {
        try {
            int raw = readIntLE(input);
            int compressed = readIntLE(input);
            int chunks = readIntLE(input);
            return new BatchHeader(raw, compressed, chunks);
        } catch (EOFException eof) {
            return null;
        }
    }

    private static ChunkRecord readChunk(DataInputStream input) throws IOException {
        int cx = readIntLE(input);
        int cz = readIntLE(input);
        input.readUnsignedByte(); // flags
        input.readUnsignedByte(); // reserved
        int columnCount = readUnsignedShortLE(input);
        if (columnCount != 256) {
            throw new IOException("Unsupported compact Voxy column count: " + columnCount);
        }
        ColumnRecord[] columns = new ColumnRecord[256];
        for (int column = 0; column < 256; column++) {
            int surfaceY = readShortLE(input);
            int surfaceMaterial = input.readUnsignedByte();
            int surfaceLight = input.readUnsignedByte();
            int waterY = readShortLE(input);
            int floorY = readShortLE(input);
            int floorMaterial = input.readUnsignedByte();
            int shellDepth = input.readUnsignedByte();
            int ravineDepth = input.readUnsignedByte();
            int coverage = input.readUnsignedByte();
            input.readUnsignedByte(); // reserved
            columns[column] = new ColumnRecord(surfaceY, surfaceMaterial, surfaceLight, waterY, floorY, floorMaterial, shellDepth, ravineDepth, coverage);
        }
        return new ChunkRecord(cx, cz, columns);
    }

    private static byte[] decompressZstd(byte[] compressed, int uncompressedLength) throws IOException {
        ByteBuffer source = MemoryUtil.memAlloc(compressed.length);
        ByteBuffer target = MemoryUtil.memAlloc(uncompressedLength);
        try {
            source.put(compressed).flip();
            long size = Zstd.nZSTD_decompress(MemoryUtil.memAddress(target), target.capacity(), MemoryUtil.memAddress(source), source.remaining());
            if (Zstd.ZSTD_isError(size)) {
                throw new IOException("Compact Voxy zstd decompression failed: " + Zstd.ZSTD_getErrorName(size));
            }
            if (size != uncompressedLength) {
                throw new IOException("Compact Voxy zstd size mismatch: expected " + uncompressedLength + " got " + size);
            }
            byte[] out = new byte[uncompressedLength];
            target.get(0, out);
            return out;
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(target);
        }
    }

    private static long mapping(int material, int light) {
        if (material <= 0 || material > 11) {
            return Mapper.AIR;
        }
        return Mapper.composeMappingId((byte) light, material, PLAINS_BIOME_ID);
    }

    private static int materialPriority(int blockId) {
        return switch (blockId) {
            case 5, 6 -> 90;
            case 7, 8 -> 80;
            case 9 -> 70;
            case 1, 4, 10, 11 -> 60;
            case 2, 3 -> 50;
            default -> 10;
        };
    }

    private static void registerBlockMappings(Int2ObjectOpenHashMap<byte[]> mappings) throws IOException {
        for (int id = 1; id <= 11; id++) {
            mappings.put((BLOCK_STATE_TYPE << 30) | id, blockMappingBytes(id));
        }
    }

    private static void registerPlainsBiome(Int2ObjectOpenHashMap<byte[]> mappings) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", PLAINS_BIOME_ID);
        tag.putString("biome_id", "minecraft:plains");
        mappings.put((BIOME_TYPE << 30) | PLAINS_BIOME_ID, writeCompressed(tag));
    }

    private static byte[] blockMappingBytes(int material) throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", material);
        CompoundTag blockState = new CompoundTag();
        blockState.putString("Name", materialBlockName(material));
        tag.put("block_state", blockState);
        return writeCompressed(tag);
    }

    private static byte[] writeCompressed(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    private static String materialBlockName(int material) {
        return switch (material) {
            case 1 -> "minecraft:grass_block";
            case 2 -> "minecraft:dirt";
            case 3 -> "minecraft:stone";
            case 4 -> "minecraft:snow_block";
            case 5 -> "minecraft:water";
            case 6 -> "minecraft:ice";
            case 7 -> "minecraft:oak_leaves";
            case 8 -> "minecraft:spruce_leaves";
            case 9 -> "minecraft:oak_log";
            case 10 -> "minecraft:sand";
            case 11 -> "minecraft:gravel";
            default -> "minecraft:air";
        };
    }

    private static String stripVlcpExtension(String fileName) {
        return fileName.endsWith(".vlcp") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static short readShortLE(DataInputStream input) throws IOException {
        return (short) readUnsignedShortLE(input);
    }

    private static int readUnsignedShortLE(DataInputStream input) throws IOException {
        int b0 = input.readUnsignedByte();
        int b1 = input.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int readIntLE(DataInputStream input) throws IOException {
        int b0 = input.readUnsignedByte();
        int b1 = input.readUnsignedByte();
        int b2 = input.readUnsignedByte();
        int b3 = input.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readIntLE(RandomAccessFile input) throws IOException {
        int b0 = input.readUnsignedByte();
        int b1 = input.readUnsignedByte();
        int b2 = input.readUnsignedByte();
        int b3 = input.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readLongLE(DataInputStream input) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (long) input.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    private static long readLongLE(RandomAccessFile input) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (long) input.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    private static void writeIntLE(java.io.DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xFF);
        output.writeByte((value >>> 8) & 0xFF);
        output.writeByte((value >>> 16) & 0xFF);
        output.writeByte((value >>> 24) & 0xFF);
    }

    private static void writeLongLE(java.io.DataOutputStream output, long value) throws IOException {
        for (int i = 0; i < 8; i++) {
            output.writeByte((int) ((value >>> (i * 8)) & 0xFF));
        }
    }

    private record Header(int centerX, int centerZ, int radius, int shape, int storageProfile, long seed, long totalChunks) {
    }

    private record BatchHeader(int rawPayloadLength, int compressedDataLength, int chunkCount) {
    }

    private record BatchIndexEntry(long firstChunkOrdinal, long lastChunkOrdinal, int chunkCount, int rawPayloadLength, long compressedDataOffset, long compressedDataLength) {
    }

    private record ChunkRecord(int cx, int cz, ColumnRecord[] columns) {
    }

    private record ColumnRecord(int surfaceY, int surfaceMaterial, int surfaceLight, int waterY, int floorY, int floorMaterial, int shellDepth, int ravineDepth, int coverage) {
    }

    public static final class Config extends SectionStorageConfig {
        public String path;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            Path resolved = Path.of(ctx.substituteString(this.path));
            if (!resolved.isAbsolute()) {
                resolved = Path.of(ctx.substituteString(ConfigBuildCtx.BASE_SAVE_PATH)).resolve(resolved);
            }
            return new CompactVlcpSectionStorage(resolved);
        }

        public static String getConfigTypeName() {
            return "CompactVLCP";
        }
    }
}
