package me.cortex.voxy.commonImpl.serverlod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Vlcp3BatchCodec {
    public static final int MAGIC = 0x564c4333; // VLC3
    public static final int VERSION = 3;

    private Vlcp3BatchCodec() {}

    public static EncodedBatch encode(List<ServerLodTile> tiles, int compressionLevel) throws IOException {
        var rawOut = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(rawOut)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(tiles.size());
            for (var tile : tiles) {
                writeMetadata(out, tile.metadata());
                out.writeInt(tile.compressedPayload().length);
                out.write(tile.compressedPayload());
            }
        }
        byte[] raw = rawOut.toByteArray();
        byte[] compressed = compress(raw, compressionLevel);
        return new EncodedBatch(raw.length, compressed.length, compressed, ServerLodConstants.sha256Hex(raw));
    }

    public static List<ServerLodTile> decode(byte[] compressed, int rawLength) throws IOException {
        byte[] raw = decompress(compressed, rawLength);
        try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported VLCP batch: magic=" + Integer.toHexString(magic) + " version=" + version);
            }
            int count = in.readInt();
            var tiles = new ArrayList<ServerLodTile>(count);
            for (int i = 0; i < count; i++) {
                var metadata = readMetadata(in);
                int length = in.readInt();
                if (length < 0 || length > ServerLodConstants.MAX_TILE_BATCH_BYTES) {
                    throw new IOException("Invalid VLCP tile payload length: " + length);
                }
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("Truncated VLCP tile payload");
                }
                tiles.add(new ServerLodTile(metadata, payload));
            }
            return tiles;
        }
    }

    private static void writeMetadata(DataOutputStream out, ServerLodTileMetadata metadata) throws IOException {
        writeString(out, metadata.key().dimension());
        out.writeInt(metadata.key().lodLevel());
        out.writeInt(metadata.key().sectionX());
        out.writeInt(metadata.key().sectionY());
        out.writeInt(metadata.key().sectionZ());
        out.writeInt(metadata.key().generatorVersion());
        out.writeLong(metadata.epoch());
        writeString(out, metadata.contentHash());
        out.writeInt(metadata.sourceKind().ordinal());
        out.writeInt(metadata.confidence().ordinal());
        out.writeInt(metadata.lightKind().ordinal());
        out.writeBoolean(metadata.parentComplete());
        out.writeBoolean(metadata.childComplete());
        out.writeInt(metadata.compressedBytes());
    }

    private static ServerLodTileMetadata readMetadata(DataInputStream in) throws IOException {
        var key = new ServerLodTileKey(readString(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        return new ServerLodTileMetadata(
                key,
                in.readLong(),
                readString(in),
                ServerLodSourceKind.values()[in.readInt()],
                ServerLodConfidence.values()[in.readInt()],
                ServerLodLightKind.values()[in.readInt()],
                in.readBoolean(),
                in.readBoolean(),
                in.readInt());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 4096) {
            throw new IOException("Invalid VLCP string length: " + length);
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static byte[] compress(byte[] raw, int level) throws IOException {
        return ServerLodZstd.compress(raw, level, "VLCP0003 network batch");
    }

    private static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
        return ServerLodZstd.decompress(compressed, rawLength, "VLCP0003 network batch");
    }

    public record EncodedBatch(int rawBytes, int compressedBytes, byte[] compressedPayload, String rawHash) {}
}
