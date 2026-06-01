package me.cortex.voxy.commonImpl.serverlod;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class ServerLodSectionTileCodec {
    private static final int PAYLOAD_MAGIC = 0x56534C54; // VSLT
    private static final int COMPRESSED_PAYLOAD_MAGIC = 0x56534C5A; // VSLZ
    private static final int RUST_SPARSE_PAYLOAD_MAGIC = 0x56534C52; // VSLR
    private static final int PAYLOAD_VERSION = 1;
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;
    private static final int PLAINS_BIOME_ID = 0;
    private static volatile Mapper runtimeMapper;

    private ServerLodSectionTileCodec() {}

    public static void setRuntimeMapper(Mapper mapper) {
        runtimeMapper = mapper;
    }

    public static Mapper runtimeMapper() {
        return runtimeMapper;
    }

    public static ServerLodTile createTile(String dimension, WorldSection section, Int2ObjectOpenHashMap<byte[]> mappings) {
        return createTile(
                dimension,
                section,
                mappings,
                sourceKind(section.getSourceKind()),
                confidence(section.getConfidence()),
                lightKind(section.getLightSourceKind())
        );
    }

    public static ServerLodTile createMinecraftAuthoredTile(String dimension, WorldSection section, Int2ObjectOpenHashMap<byte[]> mappings) {
        return createTile(
                dimension,
                section,
                mappings,
                ServerLodSourceKind.REAL_SERVER_CHUNK,
                ServerLodConfidence.REAL,
                ServerLodLightKind.REAL_SKY
        );
    }

    private static ServerLodTile createTile(
            String dimension,
            WorldSection section,
            Int2ObjectOpenHashMap<byte[]> mappings,
            ServerLodSourceKind sourceKind,
            ServerLodConfidence confidence,
            ServerLodLightKind lightKind) {
        byte[] payload = encodePayload(section, mappings);
        var key = new ServerLodTileKey(
                dimension,
                section.lvl,
                section.x,
                section.y,
                section.z,
                SaveLoadSystem3.STORAGE_VERSION);
        var metadata = new ServerLodTileMetadata(
                key,
                section.getDataEpoch() > 0 ? section.getDataEpoch() : System.currentTimeMillis(),
                ServerLodConstants.sha256Hex(payload),
                sourceKind,
                confidence,
                lightKind,
                section.getNonEmptyChildren() != 0,
                section.getNonEmptyChildren() == (byte) 0xFF || section.lvl == 0,
                payload.length);
        return new ServerLodTile(metadata, payload);
    }

    public static boolean loadSection(ServerLodTile tile, WorldSection into) {
        try {
            if (loadRustSparseSection(tile, into)) {
                return true;
            }
        } catch (RuntimeException exception) {
            Logger.warn("Failed to decode rust sparse Voxy LoD tile " + tile.metadata().key().stableId() + ": " + exception.getMessage());
            return false;
        }
        try {
            var decoded = decodePayload(tile.compressedPayload());
            try {
                Mapper mapper = runtimeMapper;
                if (mapper != null) {
                    mapper.importMappings(decoded.mappings());
                }
                boolean loaded = SaveLoadSystem3.deserialize(into, decoded.sectionBuffer());
                if (loaded && mapper != null) {
                    ensureMapperCoverage(mapper, into);
                }
                return loaded;
            } finally {
                decoded.sectionBuffer().free();
            }
        } catch (RuntimeException exception) {
            Logger.warn("Failed to decode synced Voxy LoD tile " + tile.metadata().key().stableId() + ": " + exception.getMessage());
            return false;
        }
    }

    public static Int2ObjectOpenHashMap<byte[]> decodeMappings(byte[] payload) {
        if (isRustSparsePayload(payload)) {
            return defaultMaterialMappings();
        }
        var decoded = decodePayload(payload);
        try {
            return decoded.mappings();
        } finally {
            decoded.sectionBuffer().free();
        }
    }

    private static boolean loadRustSparseSection(ServerLodTile tile, WorldSection into) {
        byte[] payload = tile.compressedPayload();
        if (!isRustSparsePayload(payload)) {
            return false;
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            if (magic != RUST_SPARSE_PAYLOAD_MAGIC) {
                return false;
            }
            int version = input.readInt();
            if (version != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported rust sparse payload version " + version);
            }
            int voxelCount = input.readInt();
            if (voxelCount < 0 || voxelCount > WorldSection.SECTION_VOLUME) {
                throw new IllegalArgumentException("invalid rust sparse voxel count " + voxelCount);
            }
            Arrays.fill(into._unsafeGetRawDataArray(), Mapper.AIR);
            for (int i = 0; i < voxelCount; i++) {
                int index = Short.toUnsignedInt(input.readShort());
                int material = Byte.toUnsignedInt(input.readByte());
                int light = Byte.toUnsignedInt(input.readByte());
                if (index >= WorldSection.SECTION_VOLUME) {
                    throw new IllegalArgumentException("rust sparse voxel index out of bounds " + index);
                }
                int x = index & 31;
                int z = (index >>> 5) & 31;
                int y = (index >>> 10) & 31;
                long mapping = materialMapping(material, light);
                if (!Mapper.isAir(mapping)) {
                    into.set(x, y, z, mapping);
                }
            }
            if (into.lvl == 0) {
                into.updateLvl0State();
            } else {
                into._unsafeSetNonEmptyChildren((byte) 0xFF);
            }
            into.setPublicationMetadata(VoxelizedSection.createEmpty()
                    .setSource(VoxelizedSection.SourceKind.SURFACE_PREVIEW, VoxelizedSection.Confidence.MEDIUM)
                    .setLightSourceKind(VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW));
            return true;
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid rust sparse Voxy tile payload", exception);
        }
    }

    private static boolean isRustSparsePayload(byte[] payload) {
        return payload.length >= 4
                && payload[0] == 'V'
                && payload[1] == 'S'
                && payload[2] == 'L'
                && payload[3] == 'R';
    }

    private static byte[] encodePayload(WorldSection section, Int2ObjectOpenHashMap<byte[]> mappings) {
        try {
            var sectionBytes = bytes(SaveLoadSystem3.serialize(section));
            var rawOutput = new ByteArrayOutputStream(sectionBytes.length + 4096);
            var rawData = new DataOutputStream(rawOutput);
            rawData.writeInt(PAYLOAD_MAGIC);
            rawData.writeInt(PAYLOAD_VERSION);
            rawData.writeInt(mappings.size());
            for (var entry : mappings.int2ObjectEntrySet()) {
                rawData.writeInt(entry.getIntKey());
                rawData.writeInt(entry.getValue().length);
                rawData.write(entry.getValue());
            }
            rawData.writeInt(sectionBytes.length);
            rawData.write(sectionBytes);
            rawData.flush();

            byte[] rawPayload = rawOutput.toByteArray();
            byte[] compressed = compress(rawPayload);
            var output = new ByteArrayOutputStream(compressed.length + 16);
            var data = new DataOutputStream(output);
            data.writeInt(COMPRESSED_PAYLOAD_MAGIC);
            data.writeInt(PAYLOAD_VERSION);
            data.writeInt(rawPayload.length);
            data.writeInt(compressed.length);
            data.write(compressed);
            data.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode Voxy server LoD section tile", exception);
        }
    }

    private static DecodedPayload decodePayload(byte[] payload) {
        try {
            var outerInput = new DataInputStream(new ByteArrayInputStream(payload));
            int magic = outerInput.readInt();
            if (magic == COMPRESSED_PAYLOAD_MAGIC) {
                int version = outerInput.readInt();
                if (version != PAYLOAD_VERSION) {
                    throw new IllegalArgumentException("unsupported compressed payload version " + version);
                }
                int rawLength = outerInput.readInt();
                int compressedLength = outerInput.readInt();
                if (rawLength <= 0 || rawLength > SectionPayloadLimits.MAX_RAW_BYTES) {
                    throw new IllegalArgumentException("invalid raw payload length " + rawLength);
                }
                if (compressedLength <= 0 || compressedLength > payload.length) {
                    throw new IllegalArgumentException("invalid compressed payload length " + compressedLength);
                }
                byte[] compressed = outerInput.readNBytes(compressedLength);
                if (compressed.length != compressedLength) {
                    throw new IllegalArgumentException("truncated compressed payload");
                }
                payload = decompress(compressed, rawLength);
            } else if (magic != PAYLOAD_MAGIC) {
                throw new IllegalArgumentException("invalid payload magic");
            }
            var input = new DataInputStream(new ByteArrayInputStream(payload));
            if (magic == COMPRESSED_PAYLOAD_MAGIC && input.readInt() != PAYLOAD_MAGIC) {
                throw new IllegalArgumentException("invalid decompressed payload magic");
            }
            if (magic == PAYLOAD_MAGIC) {
                // The magic was consumed from the original stream. Re-open from the full payload for v1 compatibility.
                input = new DataInputStream(new ByteArrayInputStream(payload));
                input.readInt();
            }
            int version = input.readInt();
            if (version != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported payload version " + version);
            }
            int mappingCount = input.readInt();
            var mappings = new Int2ObjectOpenHashMap<byte[]>(Math.max(16, mappingCount));
            for (int i = 0; i < mappingCount; i++) {
                int id = input.readInt();
                int length = input.readInt();
                if (length < 0 || length > 1_048_576) {
                    throw new IllegalArgumentException("invalid mapping length " + length);
                }
                byte[] mapping = input.readNBytes(length);
                if (mapping.length != length) {
                    throw new IllegalArgumentException("truncated mapping payload");
                }
                mappings.put(id, mapping);
            }
            int sectionLength = input.readInt();
            if (sectionLength <= 0 || sectionLength > 1 << 22) {
                throw new IllegalArgumentException("invalid section payload length " + sectionLength);
            }
            byte[] sectionBytes = input.readNBytes(sectionLength);
            if (sectionBytes.length != sectionLength) {
                throw new IllegalArgumentException("truncated section payload");
            }
            var sectionBuffer = new MemoryBuffer(sectionBytes.length);
            sectionBuffer.asByteBuffer().put(sectionBytes).rewind();
            return new DecodedPayload(mappings, sectionBuffer);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid Voxy server LoD section payload", exception);
        }
    }

    private static byte[] compress(byte[] raw) throws IOException {
        return ServerLodZstd.compress(raw, 1, "Voxy server LoD tile");
    }

    private static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
        return ServerLodZstd.decompress(compressed, rawLength, "Voxy server LoD tile");
    }

    private static void ensureMapperCoverage(Mapper mapper, WorldSection section) {
        int maxBlockId = 0;
        int maxBiomeId = 0;
        for (long mapping : section._unsafeGetRawDataArray()) {
            maxBlockId = Math.max(maxBlockId, Mapper.getBlockId(mapping));
            maxBiomeId = Math.max(maxBiomeId, Mapper.getBiomeId(mapping));
        }
        mapper.ensureImportedMappingCoverage(maxBlockId, maxBiomeId);
    }

    private static byte[] bytes(MemoryBuffer buffer) {
        byte[] copy = new byte[Math.toIntExact(buffer.size)];
        buffer.asByteBuffer().get(copy);
        return copy;
    }

    private static long materialMapping(int material, int light) {
        if (material <= 0 || material > 11) {
            return Mapper.AIR;
        }
        Mapper mapper = runtimeMapper;
        if (mapper == null) {
            return Mapper.composeMappingId((byte) light, material, PLAINS_BIOME_ID);
        }
        return Mapper.composeMappingId((byte) light, mapper.getIdForBlockState(materialBlockState(material)), PLAINS_BIOME_ID);
    }

    private static Int2ObjectOpenHashMap<byte[]> defaultMaterialMappings() {
        try {
            var mappings = new Int2ObjectOpenHashMap<byte[]>(16);
            for (int id = 1; id <= 11; id++) {
                mappings.put((BLOCK_STATE_TYPE << 30) | id, blockMappingBytes(id));
            }
            CompoundTag biome = new CompoundTag();
            biome.putInt("id", PLAINS_BIOME_ID);
            biome.putString("biome_id", "minecraft:plains");
            mappings.put((BIOME_TYPE << 30) | PLAINS_BIOME_ID, writeCompressed(biome));
            return mappings;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed creating default Voxy server LoD mappings", exception);
        }
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

    private static BlockState materialBlockState(int material) {
        return switch (material) {
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

    private static ServerLodSourceKind sourceKind(VoxelizedSection.SourceKind sourceKind) {
        return switch (sourceKind == null ? VoxelizedSection.SourceKind.UNKNOWN : sourceKind) {
            case REAL_CHUNK -> ServerLodSourceKind.REAL_SERVER_CHUNK;
            case SURFACE_PREVIEW -> ServerLodSourceKind.SERVER_SURFACE_PREVIEW;
            case SYNTHETIC_PREVIEW -> ServerLodSourceKind.SERVER_REFINED_PREGEN;
            case ZERO_CLEAR, UNKNOWN -> ServerLodSourceKind.CLIENT_PROVISIONAL;
        };
    }

    private static ServerLodConfidence confidence(VoxelizedSection.Confidence confidence) {
        return switch (confidence == null ? VoxelizedSection.Confidence.UNKNOWN : confidence) {
            case HIGH -> ServerLodConfidence.REAL;
            case MEDIUM -> ServerLodConfidence.MEDIUM;
            case LOW -> ServerLodConfidence.LOW;
            case UNKNOWN -> ServerLodConfidence.LOW;
        };
    }

    private static ServerLodLightKind lightKind(VoxelizedSection.LightSourceKind lightSourceKind) {
        return switch (lightSourceKind == null ? VoxelizedSection.LightSourceKind.UNKNOWN : lightSourceKind) {
            case REAL_LIGHT, VALID_DEFAULT_SKY_LIGHT -> ServerLodLightKind.REAL_SKY;
            case VALID_EMPTY_SKY_LIGHT, NO_SKY_DIMENSION -> ServerLodLightKind.REAL_ZERO_SKY;
            case SYNTHETIC_SURFACE_PREVIEW -> ServerLodLightKind.SYNTHETIC_SURFACE;
            case UNKNOWN, MISSING_SKY_LIGHT -> ServerLodLightKind.MISSING;
        };
    }

    private record DecodedPayload(Int2ObjectOpenHashMap<byte[]> mappings, MemoryBuffer sectionBuffer) {}

    private static final class SectionPayloadLimits {
        private static final int MAX_RAW_BYTES = 1 << 24;
    }
}
