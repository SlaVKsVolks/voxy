package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

public class VoxyLodCompilerImporter implements IDataImporter {
    private static final byte[] MAGIC_V1 = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '1'};
    private static final byte[] MAGIC_V2 = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '2'};
    private static final int FORMAT_VERSION_V1 = 1;
    private static final int FORMAT_VERSION_V2 = 2;
    private static final short NO_Y = Short.MIN_VALUE;

    private final File file;
    private final WorldEngine engine;
    private final Holder<Biome> fallbackBiome;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private volatile boolean running;
    private volatile Thread worker;

    public VoxyLodCompilerImporter(File file, WorldEngine engine, Level level) {
        this.file = file;
        this.engine = engine;
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.fallbackBiome = biomeRegistry.getHolder(Biomes.PLAINS).orElseThrow();
    }

    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.running) {
            throw new IllegalStateException("Voxy LoD compiler import is already running");
        }
        this.running = true;
        this.engine.acquireRef();
        this.worker = new Thread(() -> {
            int imported = 0;
            try {
                imported = this.importFile(updateCallback);
            } catch (Exception error) {
                Logger.error("Voxy LoD compiler import failed", error);
            } finally {
                this.running = false;
                this.engine.releaseRef();
                completionCallback.onCompletion(imported);
            }
        }, "Voxy LoD compiler import");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.engine;
    }

    @Override
    public void shutdown() {
        this.shutdown.set(true);
        Thread thread = this.worker;
        if (thread != null) {
            try {
                thread.join(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    private int importFile(IUpdateCallback updateCallback) throws IOException {
        if (!this.file.isFile() || !this.file.canRead()) {
            throw new IOException("Voxy LoD compiler file is not readable: " + this.file.getAbsolutePath());
        }
        try (var input = new DataInputStream(new BufferedInputStream(new FileInputStream(this.file), 1 << 20))) {
            byte[] magic = readMagic(input);
            if (matchesMagic(magic, MAGIC_V2)) {
                return this.importV2(input, updateCallback);
            }
            if (!matchesMagic(magic, MAGIC_V1)) {
                throw new IOException("Invalid Voxy LoD compiler file magic");
            }
            return this.importV1(input, updateCallback);
        } catch (EOFException eof) {
            throw new IOException("Unexpected end of Voxy LoD compiler file: " + this.file.getAbsolutePath(), eof);
        }
    }

    private int importV1(DataInputStream input, IUpdateCallback updateCallback) throws IOException {
            int version = readIntLE(input);
            if (version != FORMAT_VERSION_V1) {
                throw new IOException("Unsupported Voxy LoD compiler format version: " + version);
            }
            // Header fields retained for diagnostics and future storage-direct import.
            readIntLE(input); // center_x
            readIntLE(input); // center_z
            readIntLE(input); // radius
            input.readUnsignedByte(); // shape
            input.readUnsignedByte(); // mode
            input.skipBytes(2);
            readLongLE(input); // seed
            long totalChunks = readLongLE(input);
            long totalSections = readLongLE(input);

            int imported = 0;
            Mapper mapper = this.engine.getMapper();
            int biomeId = mapper.getIdForBiome(this.fallbackBiome);
            while (!this.shutdown.get() && imported < totalSections) {
                VoxelizedSection section = readSection(input, mapper, biomeId);
                WorldVoxilizedSectionMipper.mipSection(section, mapper);
                if (this.engine.instanceIn != null
                        && this.engine.instanceIn.getIngestService().queueVoxelizedSection(this.engine, section, "lod_compiler_import")) {
                    imported++;
                    if ((imported & 0x3FF) == 0) {
                        updateCallback.onUpdate(imported, (int)Math.min(Integer.MAX_VALUE, totalSections));
                    }
                }
            }
            updateCallback.onUpdate(imported, (int)Math.min(Integer.MAX_VALUE, totalSections));
            Logger.info("Imported Voxy LoD compiler sections: " + imported + "/" + totalSections + " source_chunks=" + totalChunks);
            return imported;
    }

    private int importV2(DataInputStream input, IUpdateCallback updateCallback) throws IOException {
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

        int importedSections = 0;
        long importedChunks = 0;
        long lastProgressLogNanos = System.nanoTime();
        Mapper mapper = this.engine.getMapper();
        int biomeId = mapper.getIdForBiome(this.fallbackBiome);
        while (!this.shutdown.get()) {
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
                for (int i = 0; i < header.chunkCount && !this.shutdown.get(); i++) {
                    importedSections += importV2Chunk(batchInput, mapper, biomeId, storageProfile);
                    importedChunks++;
                }
            }
            if ((importedChunks & 0x3FF) == 0) {
                updateCallback.onUpdate((int)Math.min(Integer.MAX_VALUE, importedChunks), (int)Math.min(Integer.MAX_VALUE, totalChunks));
            }
            long now = System.nanoTime();
            if (now - lastProgressLogNanos >= 5_000_000_000L) {
                lastProgressLogNanos = now;
                double percent = totalChunks <= 0 ? 100.0D : importedChunks * 100.0D / totalChunks;
                Logger.info("Voxy LoD compiler v2 import progress: "
                        + importedChunks
                        + "/"
                        + totalChunks
                        + " chunks ("
                        + String.format(java.util.Locale.ROOT, "%.2f", percent)
                        + "%), sections="
                        + importedSections);
            }
        }
        updateCallback.onUpdate((int)Math.min(Integer.MAX_VALUE, importedChunks), (int)Math.min(Integer.MAX_VALUE, totalChunks));
        Logger.info("Imported Voxy LoD compiler v2 sections: " + importedSections + " source_chunks=" + importedChunks + "/" + totalChunks);
        return importedSections;
    }

    private int importV2Chunk(DataInputStream input, Mapper mapper, int biomeId, int storageProfile) throws IOException {
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

            putPreviewVoxel(sections, mapper, biomeId, cx, cz, lx, surfaceY, lz, surfaceMaterial, surfaceLight);
            if (waterY != NO_Y) {
                putPreviewVoxel(sections, mapper, biomeId, cx, cz, lx, waterY, lz, surfaceY < 55 ? 6 : 5, 0xF0);
                putPreviewVoxel(sections, mapper, biomeId, cx, cz, lx, floorY, lz, floorMaterial, 0xE0);
            }
            int limitedShellDepth = storageProfile == 3 ? Math.min(shellDepth, 2) : shellDepth;
            for (int dy = 1; dy <= limitedShellDepth; dy++) {
                putPreviewVoxel(sections, mapper, biomeId, cx, cz, lx, surfaceY - dy, lz, 2, 0xE0);
            }
            if (ravineDepth > 0) {
                for (int dy = 0; dy < ravineDepth; dy += coverage == 4 ? 1 : 2) {
                    int y = surfaceY - dy;
                    if (y <= 4) {
                        break;
                    }
                    putPreviewVoxel(sections, mapper, biomeId, cx, cz, lx, y, lz, 3, 0xD0);
                }
            }
        }

        int imported = 0;
        for (VoxelizedSection section : sections.values()) {
            WorldVoxilizedSectionMipper.mipSection(section, mapper);
            if (this.engine.instanceIn != null
                    && this.engine.instanceIn.getIngestService().queueVoxelizedSection(this.engine, section, "lod_compiler_import_v2")) {
                imported++;
            }
        }
        return imported;
    }

    private static VoxelizedSection readSection(DataInputStream input, Mapper mapper, int biomeId) throws IOException {
        int sx = readIntLE(input);
        int sy = readIntLE(input);
        int sz = readIntLE(input);
        VoxelizedSection.SourceKind sourceKind = sourceKind(input.readUnsignedByte());
        VoxelizedSection.Confidence confidence = confidence(input.readUnsignedByte());
        VoxelizedSection.LightSourceKind lightSourceKind = lightSourceKind(input.readUnsignedByte());
        input.readUnsignedByte(); // coverage kind, currently diagnostic-only.
        int voxelCount = readUnsignedShortLE(input);
        var section = VoxelizedSection.createEmpty()
                .setPosition(sx, sy, sz)
                .setSource(sourceKind, confidence)
                .setLightSourceKind(lightSourceKind)
                .setSyntheticPreview(sourceKind != VoxelizedSection.SourceKind.REAL_CHUNK);
        int nonAir = 0;
        for (int i = 0; i < voxelCount; i++) {
            int index = readUnsignedShortLE(input);
            int material = input.readUnsignedByte();
            int light = input.readUnsignedByte();
            BlockState state = materialState(material);
            if (!state.isAir() && index >= 0 && index < 4096) {
                section.section[index] = Mapper.composeMappingId((byte) light, mapper.getIdForBlockState(state), biomeId);
                nonAir++;
            }
        }
        section.lvl0NonAirCount = nonAir;
        return section;
    }

    private static void putPreviewVoxel(Map<SectionKey, VoxelizedSection> sections, Mapper mapper, int biomeId, int cx, int cz, int lx, int y, int lz, int material, int light) {
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
                .setSyntheticPreview(true));
        BlockState state = materialState(material);
        if (state.isAir()) {
            return;
        }
        int index = (ly << 8) | (lz << 4) | lx;
        if (section.section[index] == 0) {
            section.lvl0NonAirCount++;
        }
        section.section[index] = Mapper.composeMappingId((byte) light, mapper.getIdForBlockState(state), biomeId);
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

    private record SectionKey(int sx, int sy, int sz) {}

    private record BatchHeader(int uncompressedLength, int compressedLength, int chunkCount) {}

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
        for (int shift = 0; shift < 64; shift += 8) {
            value |= (long) input.readUnsignedByte() << shift;
        }
        return value;
    }

    private static VoxelizedSection.SourceKind sourceKind(int id) {
        return switch (id) {
            case 1 -> VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW;
            case 2 -> VoxelizedSection.SourceKind.SURFACE_PREVIEW;
            case 3 -> VoxelizedSection.SourceKind.REAL_CHUNK;
            case 4 -> VoxelizedSection.SourceKind.ZERO_CLEAR;
            default -> VoxelizedSection.SourceKind.UNKNOWN;
        };
    }

    private static VoxelizedSection.Confidence confidence(int id) {
        return switch (id) {
            case 1 -> VoxelizedSection.Confidence.LOW;
            case 2 -> VoxelizedSection.Confidence.MEDIUM;
            case 3 -> VoxelizedSection.Confidence.HIGH;
            default -> VoxelizedSection.Confidence.UNKNOWN;
        };
    }

    private static VoxelizedSection.LightSourceKind lightSourceKind(int id) {
        return switch (id) {
            case 1 -> VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW;
            case 2 -> VoxelizedSection.LightSourceKind.VALID_DEFAULT_SKY_LIGHT;
            case 3 -> VoxelizedSection.LightSourceKind.REAL_LIGHT;
            case 4 -> VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT;
            case 5 -> VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW;
            default -> VoxelizedSection.LightSourceKind.UNKNOWN;
        };
    }

    private static BlockState materialState(int id) {
        return switch (id) {
            case 1 -> Blocks.GRASS_BLOCK.defaultBlockState();
            case 2 -> Blocks.DIRT.defaultBlockState();
            case 3 -> Blocks.STONE.defaultBlockState();
            case 4 -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 5 -> Blocks.WATER.defaultBlockState();
            case 6 -> Blocks.ICE.defaultBlockState();
            case 7 -> Blocks.OAK_LEAVES.defaultBlockState();
            case 8 -> Blocks.SPRUCE_LEAVES.defaultBlockState();
            case 9 -> Blocks.OAK_LOG.defaultBlockState();
            case 10 -> Blocks.SAND.defaultBlockState();
            case 11 -> Blocks.GRAVEL.defaultBlockState();
            default -> Blocks.AIR.defaultBlockState();
        };
    }
}
