package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.ServerLodTileSectionStorage;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class VoxyLodCompilerOfflineImport {
    private static final byte[] MAGIC_V2 = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '2'};
    private static final int FORMAT_VERSION_V2 = 2;
    private static final short NO_Y = Short.MIN_VALUE;
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;
    private static final int PLAINS_BIOME_ID = 0;

    private VoxyLodCompilerOfflineImport() {
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        System.setProperty("voxy.offlineImportMinimalMapper", "true");
        if (parsed.storagePath != null) {
            Files.createDirectories(parsed.storagePath);
        }
        if (parsed.serverLodStorePath != null) {
            Files.createDirectories(parsed.serverLodStorePath);
        }
        Files.createDirectories(parsed.reportPath.getParent());

        Instant started = Instant.now();
        SectionStorage storage = createStorage(parsed);
        try {
            registerBlockMappings(storage);
            registerPlainsBiome(storage);
            WorldEngine engine = new WorldEngine(storage);
            engine.setSaveCallback((world, section, nonBlocking, sectionAlreadyAcquired) -> {
                try {
                    section.setNotDirty();
                    world.storage.saveSection(section);
                    return true;
                } finally {
                    if (sectionAlreadyAcquired) {
                        section.release();
                    }
                }
            });

            ImportStats stats = importV2(parsed.inputPath, engine, parsed.progressPath, started);
            storage.flush();
            writeReport(parsed.reportPath, parsed, stats, Duration.between(started, Instant.now()));
            System.out.println("voxy-offline-import: complete chunks="
                    + stats.chunks
                    + " sections="
                    + stats.sections
                    + " elapsed_seconds="
                    + String.format(Locale.ROOT, "%.3f", Duration.between(started, Instant.now()).toMillis() / 1000.0D));
        } finally {
            storage.close();
        }
    }

    private static SectionStorage createStorage(Args parsed) {
        if (parsed.serverLodStorePath != null) {
            return new ServerLodTileSectionStorage(parsed.serverLodStorePath, parsed.dimension);
        }
        return new SectionSerializationStorage(new CompressionStorageAdaptor(
                new ZSTDCompressor(1),
                new RocksDBStorageBackend(parsed.storagePath.toString())
        ));
    }

    private static ImportStats importV2(Path inputPath, WorldEngine engine, Path progressPath, Instant started) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(new FileInputStream(inputPath.toFile()), 1 << 20))) {
            byte[] magic = readMagic(input);
            if (!matchesMagic(magic, MAGIC_V2)) {
                throw new IOException("Unsupported offline import file magic. Expected VLCP0002.");
            }
            int version = readIntLE(input);
            if (version != FORMAT_VERSION_V2) {
                throw new IOException("Unsupported Voxy LoD compiler v2 format version: " + version);
            }
            readIntLE(input); // center_x
            readIntLE(input); // center_z
            readIntLE(input); // radius
            input.readUnsignedByte(); // shape
            input.readUnsignedByte(); // mode
            int storageProfile = input.readUnsignedByte();
            int compression = input.readUnsignedByte();
            readLongLE(input); // seed
            long totalChunks = readLongLE(input);
            if (compression != 1) {
                throw new IOException("Unsupported Voxy LoD compiler v2 compression: " + compression);
            }

            MaterialIds materialIds = MaterialIds.create();
            ImportStats stats = new ImportStats(totalChunks);
            long lastProgressNanos = System.nanoTime();
            while (true) {
                BatchHeader header = readBatchHeader(input);
                if (header == null) {
                    break;
                }
                byte[] compressed = input.readNBytes(header.compressedLength);
                if (compressed.length != header.compressedLength) {
                    throw new EOFException("Short Voxy LoD compiler v2 compressed batch");
                }
                byte[] payload = decompressZstd(compressed, header.uncompressedLength);
                try (var batchInput = new DataInputStream(new ByteArrayInputStream(payload))) {
                    for (int i = 0; i < header.chunkCount; i++) {
                        stats.sections += importV2Chunk(batchInput, engine, materialIds, storageProfile, stats.chunks + 1);
                        stats.chunks++;
                    }
                }
                long now = System.nanoTime();
                if (now - lastProgressNanos >= 1_000_000_000L) {
                    lastProgressNanos = now;
                    writeProgress(progressPath, stats, started);
                    System.out.println("voxy-offline-import: "
                            + stats.chunks
                            + "/"
                            + totalChunks
                            + " "
                            + String.format(Locale.ROOT, "%.2f", stats.percent())
                            + "% sections="
                            + stats.sections);
                }
            }
            writeProgress(progressPath, stats, started);
            return stats;
        }
    }

    private static int importV2Chunk(
            DataInputStream input,
            WorldEngine engine,
            MaterialIds materialIds,
            int storageProfile,
            long dataEpoch
    ) throws IOException {
        int cx = readIntLE(input);
        int cz = readIntLE(input);
        input.readUnsignedByte(); // chunk flags
        input.readUnsignedByte(); // reserved
        int columnCount = readUnsignedShortLE(input);
        if (columnCount != 256) {
            throw new IOException("Unsupported Voxy LoD compiler v2 column count: " + columnCount);
        }

        Map<SectionKey, VoxelizedSection> sections = new HashMap<>();
        for (int column = 0; column < columnCount; column++) {
            int lx = column / 16;
            int lz = column & 15;
            short surfaceY = readShortLE(input);
            int surfaceMaterial = input.readUnsignedByte();
            int surfaceLight = input.readUnsignedByte();
            short waterY = readShortLE(input);
            short floorY = readShortLE(input);
            int floorMaterial = input.readUnsignedByte();
            int shellDepth = input.readUnsignedByte();
            int ravineDepth = input.readUnsignedByte();
            int coverage = input.readUnsignedByte();
            input.readUnsignedByte(); // reserved

            putPreviewVoxel(sections, materialIds, cx, cz, lx, surfaceY, lz, surfaceMaterial, surfaceLight, dataEpoch);
            if (waterY != NO_Y) {
                putPreviewVoxel(sections, materialIds, cx, cz, lx, waterY, lz, surfaceY < 55 ? 6 : 5, 0xF0, dataEpoch);
                putPreviewVoxel(sections, materialIds, cx, cz, lx, floorY, lz, floorMaterial, 0xE0, dataEpoch);
            }
            int limitedShellDepth = storageProfile == 3 ? Math.min(shellDepth, 2) : shellDepth;
            for (int dy = 1; dy <= limitedShellDepth; dy++) {
                putPreviewVoxel(sections, materialIds, cx, cz, lx, surfaceY - dy, lz, 2, 0xE0, dataEpoch);
            }
            if (ravineDepth > 0) {
                for (int dy = 0; dy < ravineDepth; dy += coverage == 4 ? 1 : 2) {
                    int y = surfaceY - dy;
                    if (y <= 4) {
                        break;
                    }
                    putPreviewVoxel(sections, materialIds, cx, cz, lx, y, lz, 3, 0xD0, dataEpoch);
                }
            }
        }

        int imported = 0;
        for (VoxelizedSection section : sections.values()) {
            mipSectionSimple(section);
            WorldUpdater.insertUpdate(engine, section);
            imported++;
        }
        return imported;
    }

    private static void mipSectionSimple(VoxelizedSection section) {
        long[] data = section.section;
        int base1 = VoxelizedSection.getBaseIndexForLevel(1);
        int base2 = VoxelizedSection.getBaseIndexForLevel(2);
        int base3 = VoxelizedSection.getBaseIndexForLevel(3);
        int base4 = VoxelizedSection.getBaseIndexForLevel(4);
        for (int y = 0; y < 16; y += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    data[base1 + ((y >> 1) << 6) + ((z >> 1) << 3) + (x >> 1)] = chooseRepresentative(
                            data[index16(x, y, z)], data[index16(x + 1, y, z)], data[index16(x, y, z + 1)], data[index16(x + 1, y, z + 1)],
                            data[index16(x, y + 1, z)], data[index16(x + 1, y + 1, z)], data[index16(x, y + 1, z + 1)], data[index16(x + 1, y + 1, z + 1)]);
                }
            }
        }
        for (int y = 0; y < 8; y += 2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[base2 + ((y >> 1) << 4) + ((z >> 1) << 2) + (x >> 1)] = chooseRepresentative(
                            data[index8(base1, x, y, z)], data[index8(base1, x + 1, y, z)], data[index8(base1, x, y, z + 1)], data[index8(base1, x + 1, y, z + 1)],
                            data[index8(base1, x, y + 1, z)], data[index8(base1, x + 1, y + 1, z)], data[index8(base1, x, y + 1, z + 1)], data[index8(base1, x + 1, y + 1, z + 1)]);
                }
            }
        }
        for (int y = 0; y < 4; y += 2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[base3 + ((y >> 1) << 2) + ((z >> 1) << 1) + (x >> 1)] = chooseRepresentative(
                            data[index4(base2, x, y, z)], data[index4(base2, x + 1, y, z)], data[index4(base2, x, y, z + 1)], data[index4(base2, x + 1, y, z + 1)],
                            data[index4(base2, x, y + 1, z)], data[index4(base2, x + 1, y + 1, z)], data[index4(base2, x, y + 1, z + 1)], data[index4(base2, x + 1, y + 1, z + 1)]);
                }
            }
        }
        data[base4] = chooseRepresentative(
                data[base3], data[base3 + 1], data[base3 + 2], data[base3 + 3],
                data[base3 + 4], data[base3 + 5], data[base3 + 6], data[base3 + 7]);
    }

    private static int index16(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int index8(int base, int x, int y, int z) {
        return base + (y << 6) + (z << 3) + x;
    }

    private static int index4(int base, int x, int y, int z) {
        return base + (y << 4) + (z << 2) + x;
    }

    private static long chooseRepresentative(long... samples) {
        long best = Mapper.AIR;
        int bestScore = Integer.MIN_VALUE;
        int bestSky = -1;
        for (long sample : samples) {
            if (Mapper.isAir(sample)) {
                continue;
            }
            int blockId = Mapper.getBlockId(sample);
            int sky = Mapper.getLightId(sample) & 0x0F;
            int score = materialPriority(blockId);
            if (score > bestScore || (score == bestScore && sky > bestSky)) {
                best = sample;
                bestScore = score;
                bestSky = sky;
            }
        }
        return best;
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

    private static void putPreviewVoxel(
            Map<SectionKey, VoxelizedSection> sections,
            MaterialIds materialIds,
            int cx,
            int cz,
            int lx,
            int y,
            int lz,
            int material,
            int light,
            long dataEpoch
    ) {
        int sy = Math.floorDiv(y, 16);
        int ly = y - sy * 16;
        if (ly < 0 || ly >= 16) {
            return;
        }
        SectionKey key = new SectionKey(cx, sy, cz);
        VoxelizedSection section = sections.computeIfAbsent(key, ignored -> VoxelizedSection.createEmpty()
                .setPosition(cx, sy, cz)
                .setSource(VoxelizedSection.SourceKind.SURFACE_PREVIEW, VoxelizedSection.Confidence.MEDIUM)
                .setLightSourceKind(VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW)
                .setSyntheticPreview(true)
                .setDataEpoch(dataEpoch));
        long mapping = materialIds.mapping(material, light);
        if (Mapper.isAir(mapping)) {
            return;
        }
        int index = (ly << 8) | (lz << 4) | lx;
        if (section.section[index] == 0) {
            section.lvl0NonAirCount++;
        }
        section.section[index] = mapping;
    }

    private static void registerPlainsBiome(IMappingStorage storage) {
        try {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", PLAINS_BIOME_ID);
            tag.putString("biome_id", "minecraft:plains");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            byte[] serialized = out.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocate(serialized.length);
            buffer.put(serialized);
            buffer.flip();
            storage.putIdMapping((BIOME_TYPE << 30) | PLAINS_BIOME_ID, buffer);
        } catch (IOException error) {
            throw new RuntimeException("Failed to write offline plains biome mapping", error);
        }
    }

    private static void registerBlockMappings(IMappingStorage storage) {
        for (int id = 1; id <= 11; id++) {
            writeBlockMapping(storage, id, materialBlockName(id));
        }
    }

    private static void writeBlockMapping(IMappingStorage storage, int id, String blockName) {
        try {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", id);
            CompoundTag blockState = new CompoundTag();
            blockState.putString("Name", blockName);
            tag.put("block_state", blockState);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            byte[] serialized = out.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocate(serialized.length);
            buffer.put(serialized);
            buffer.flip();
            storage.putIdMapping((BLOCK_STATE_TYPE << 30) | id, buffer);
        } catch (IOException error) {
            throw new RuntimeException("Failed to write offline block mapping for " + blockName, error);
        }
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

    private static BatchHeader readBatchHeader(DataInputStream input) throws IOException {
        try {
            int uncompressedLength = readIntLE(input);
            int compressedLength = readIntLE(input);
            int chunkCount = readIntLE(input);
            if (uncompressedLength < 0 || compressedLength < 0 || chunkCount < 0) {
                throw new IOException("Negative Voxy LoD compiler v2 batch header value");
            }
            return new BatchHeader(uncompressedLength, compressedLength, chunkCount);
        } catch (EOFException eof) {
            return null;
        }
    }

    private static byte[] decompressZstd(byte[] compressed, int uncompressedLength) throws IOException {
        ByteBuffer source = MemoryUtil.memAlloc(compressed.length);
        ByteBuffer target = MemoryUtil.memAlloc(uncompressedLength);
        try {
            source.put(compressed).flip();
            long size = Zstd.nZSTD_decompress(
                    MemoryUtil.memAddress(target),
                    target.capacity(),
                    MemoryUtil.memAddress(source),
                    source.remaining());
            if (Zstd.ZSTD_isError(size)) {
                throw new IOException("Voxy LoD compiler v2 zstd decompression failed: " + Zstd.ZSTD_getErrorName(size));
            }
            if (size != uncompressedLength) {
                throw new IOException("Voxy LoD compiler v2 zstd size mismatch: expected " + uncompressedLength + " got " + size);
            }
            byte[] out = new byte[uncompressedLength];
            target.get(0, out);
            return out;
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(target);
        }
    }

    private static byte[] readMagic(DataInputStream input) throws IOException {
        byte[] magic = new byte[8];
        input.readFully(magic);
        return magic;
    }

    private static boolean matchesMagic(byte[] actual, byte[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
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

    private static long readLongLE(DataInputStream input) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (long) input.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    private static void writeProgress(Path path, ImportStats stats, Instant started) throws IOException {
        Duration elapsed = Duration.between(started, Instant.now());
        double seconds = Math.max(0.001D, elapsed.toMillis() / 1000.0D);
        String json = "{\n"
                + "  \"completed_chunks\": " + stats.chunks + ",\n"
                + "  \"total_chunks\": " + stats.totalChunks + ",\n"
                + "  \"percent\": " + String.format(Locale.ROOT, "%.4f", stats.percent()) + ",\n"
                + "  \"sections\": " + stats.sections + ",\n"
                + "  \"elapsed_seconds\": " + String.format(Locale.ROOT, "%.3f", seconds) + ",\n"
                + "  \"chunks_per_sec\": " + String.format(Locale.ROOT, "%.3f", stats.chunks / seconds) + "\n"
                + "}\n";
        Files.writeString(path, json);
    }

    private static void writeReport(Path path, Args args, ImportStats stats, Duration elapsed) throws IOException {
        double seconds = Math.max(0.001D, elapsed.toMillis() / 1000.0D);
        String json = "{\n"
                + "  \"schema\": \"voxy.lod.compiler.offline_import.v1\",\n"
                + "  \"input\": \"" + escape(args.inputPath.toString()) + "\",\n"
                + "  \"storage_mode\": \"" + (args.serverLodStorePath == null ? "rocksdb_sections" : "server_lod_tiles") + "\",\n"
                + "  \"storage\": \"" + escape(args.storagePath == null ? "" : args.storagePath.toString()) + "\",\n"
                + "  \"server_lod_store\": \"" + escape(args.serverLodStorePath == null ? "" : args.serverLodStorePath.toString()) + "\",\n"
                + "  \"dimension\": \"" + escape(args.dimension) + "\",\n"
                + "  \"chunks\": " + stats.chunks + ",\n"
                + "  \"total_chunks\": " + stats.totalChunks + ",\n"
                + "  \"sections\": " + stats.sections + ",\n"
                + "  \"elapsed_seconds\": " + String.format(Locale.ROOT, "%.3f", seconds) + ",\n"
                + "  \"chunks_per_sec\": " + String.format(Locale.ROOT, "%.3f", stats.chunks / seconds) + "\n"
                + "}\n";
        Files.writeString(path, json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record SectionKey(int sx, int sy, int sz) {
    }

    private record BatchHeader(int uncompressedLength, int compressedLength, int chunkCount) {
    }

    private static final class ImportStats {
        private final long totalChunks;
        private long chunks;
        private long sections;

        private ImportStats(long totalChunks) {
            this.totalChunks = totalChunks;
        }

        private double percent() {
            return this.totalChunks <= 0 ? 100.0D : this.chunks * 100.0D / this.totalChunks;
        }
    }

    private static final class MaterialIds {
        private final int[] blockIds = new int[12];

        private static MaterialIds create() {
            MaterialIds ids = new MaterialIds();
            for (int i = 1; i < ids.blockIds.length; i++) {
                ids.blockIds[i] = i;
            }
            return ids;
        }

        private long mapping(int material, int light) {
            if (material <= 0 || material >= this.blockIds.length) {
                return Mapper.AIR;
            }
            return Mapper.composeMappingId((byte) light, this.blockIds[material], PLAINS_BIOME_ID);
        }
    }

    private record Args(
            Path inputPath,
            Path storagePath,
            Path serverLodStorePath,
            String dimension,
            Path progressPath,
            Path reportPath
    ) {
        private static Args parse(String[] args) {
            Path input = null;
            Path storage = null;
            Path serverLodStore = null;
            String dimension = "minecraft:overworld";
            Path progress = null;
            Path report = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> input = Path.of(required(args, ++i, "--input"));
                    case "--storage" -> storage = Path.of(required(args, ++i, "--storage"));
                    case "--server-lod-store" -> serverLodStore = Path.of(required(args, ++i, "--server-lod-store"));
                    case "--dimension" -> dimension = required(args, ++i, "--dimension");
                    case "--progress" -> progress = Path.of(required(args, ++i, "--progress"));
                    case "--report" -> report = Path.of(required(args, ++i, "--report"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("Missing --input");
            }
            if (storage == null && serverLodStore == null) {
                throw new IllegalArgumentException("Missing --storage");
            }
            if (progress == null) {
                progress = input.resolveSibling("offline_import.progress.json");
            }
            if (report == null) {
                report = input.resolveSibling("offline_import.report.json");
            }
            return new Args(input, storage, serverLodStore, dimension, progress, report);
        }

        private static String required(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            return args[index];
        }
    }
}
