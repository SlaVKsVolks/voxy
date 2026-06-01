package me.cortex.voxy.commonImpl.serverlod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class ServerLodPayloads {
    private ServerLodPayloads() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("voxy", path);
    }

    private static void writeKey(RegistryFriendlyByteBuf buf, ServerLodTileKey key) {
        buf.writeUtf(key.dimension(), 256);
        buf.writeVarInt(key.lodLevel());
        buf.writeVarInt(key.sectionX());
        buf.writeVarInt(key.sectionY());
        buf.writeVarInt(key.sectionZ());
        buf.writeVarInt(key.generatorVersion());
    }

    private static ServerLodTileKey readKey(RegistryFriendlyByteBuf buf) {
        return new ServerLodTileKey(
                buf.readUtf(256),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }

    private static void writeMetadata(RegistryFriendlyByteBuf buf, ServerLodTileMetadata metadata) {
        writeKey(buf, metadata.key());
        buf.writeLong(metadata.epoch());
        buf.writeUtf(metadata.contentHash(), 96);
        buf.writeVarInt(metadata.sourceKind().ordinal());
        buf.writeVarInt(metadata.confidence().ordinal());
        buf.writeVarInt(metadata.lightKind().ordinal());
        buf.writeBoolean(metadata.parentComplete());
        buf.writeBoolean(metadata.childComplete());
        buf.writeVarInt(metadata.compressedBytes());
    }

    private static ServerLodTileMetadata readMetadata(RegistryFriendlyByteBuf buf) {
        return new ServerLodTileMetadata(
                readKey(buf),
                buf.readLong(),
                buf.readUtf(96),
                ServerLodSourceKind.values()[buf.readVarInt()],
                ServerLodConfidence.values()[buf.readVarInt()],
                ServerLodLightKind.values()[buf.readVarInt()],
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt());
    }

    private static <T> void writeList(RegistryFriendlyByteBuf buf, List<T> list, Writer<T> writer) {
        buf.writeVarInt(list.size());
        for (T value : list) {
            writer.write(buf, value);
        }
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buf, Reader<T> reader) {
        int count = buf.readVarInt();
        var list = new ArrayList<T>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            list.add(reader.read(buf));
        }
        return list;
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(RegistryFriendlyByteBuf buf, T value);
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(RegistryFriendlyByteBuf buf);
    }

    public record ClientHello(String protocolVersion, long clientSyncId, String cacheManifestHash, int requestedRadius, int bandwidthMbps, List<String> cachedHashes) implements CustomPacketPayload {
        public static final Type<ClientHello> TYPE = new Type<>(id("server_lod/client_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientHello> STREAM_CODEC = StreamCodec.ofMember(ClientHello::write, ClientHello::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(this.protocolVersion, 64);
            buf.writeLong(this.clientSyncId);
            buf.writeUtf(this.cacheManifestHash, 96);
            buf.writeVarInt(this.requestedRadius);
            buf.writeVarInt(this.bandwidthMbps);
            var limited = this.cachedHashes.size() > ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES
                    ? this.cachedHashes.subList(0, ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES)
                    : this.cachedHashes;
            writeList(buf, limited, (b, value) -> b.writeUtf(value, 96));
        }

        private static ClientHello read(RegistryFriendlyByteBuf buf) {
            return new ClientHello(
                    buf.readUtf(64),
                    buf.readLong(),
                    buf.readUtf(96),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    readList(buf, b -> b.readUtf(96)));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerManifest(String protocolVersion, long clientSyncId, long serverEpoch, List<ServerLodTileMetadata> visibleTiles, String message) implements CustomPacketPayload {
        public static final Type<ServerManifest> TYPE = new Type<>(id("server_lod/server_manifest"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerManifest> STREAM_CODEC = StreamCodec.ofMember(ServerManifest::write, ServerManifest::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(this.protocolVersion, 64);
            buf.writeLong(this.clientSyncId);
            buf.writeLong(this.serverEpoch);
            writeList(buf, this.visibleTiles, ServerLodPayloads::writeMetadata);
            buf.writeUtf(this.message, 256);
        }

        private static ServerManifest read(RegistryFriendlyByteBuf buf) {
            return new ServerManifest(buf.readUtf(64), buf.readLong(), buf.readLong(), readList(buf, ServerLodPayloads::readMetadata), buf.readUtf(256));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TileRequest(long clientSyncId, List<ServerLodTileKey> keys, int maxBatchBytes) implements CustomPacketPayload {
        public static final Type<TileRequest> TYPE = new Type<>(id("server_lod/tile_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TileRequest> STREAM_CODEC = StreamCodec.ofMember(TileRequest::write, TileRequest::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(this.clientSyncId);
            writeList(buf, this.keys, ServerLodPayloads::writeKey);
            buf.writeVarInt(this.maxBatchBytes);
        }

        private static TileRequest read(RegistryFriendlyByteBuf buf) {
            return new TileRequest(buf.readLong(), readList(buf, ServerLodPayloads::readKey), buf.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TileBatch(long clientSyncId, long batchId, int rawBytes, List<ServerLodTileMetadata> metadata, byte[] compressedPayload) implements CustomPacketPayload {
        public static final Type<TileBatch> TYPE = new Type<>(id("server_lod/tile_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TileBatch> STREAM_CODEC = StreamCodec.ofMember(TileBatch::write, TileBatch::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(this.clientSyncId);
            buf.writeLong(this.batchId);
            buf.writeVarInt(this.rawBytes);
            writeList(buf, this.metadata, ServerLodPayloads::writeMetadata);
            buf.writeByteArray(this.compressedPayload);
        }

        private static TileBatch read(RegistryFriendlyByteBuf buf) {
            return new TileBatch(buf.readLong(), buf.readLong(), buf.readVarInt(), readList(buf, ServerLodPayloads::readMetadata), buf.readByteArray(ServerLodConstants.MAX_TILE_BATCH_BYTES));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TileAck(long clientSyncId, long batchId, int importedTiles, int rejectedTiles, String cacheManifestHash) implements CustomPacketPayload {
        public static final Type<TileAck> TYPE = new Type<>(id("server_lod/tile_ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TileAck> STREAM_CODEC = StreamCodec.ofMember(TileAck::write, TileAck::read);

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(this.clientSyncId);
            buf.writeLong(this.batchId);
            buf.writeVarInt(this.importedTiles);
            buf.writeVarInt(this.rejectedTiles);
            buf.writeUtf(this.cacheManifestHash, 96);
        }

        private static TileAck read(RegistryFriendlyByteBuf buf) {
            return new TileAck(buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(96));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ClientCandidateUpload(ServerLodTileMetadata metadata, byte[] compressedPayload) implements CustomPacketPayload {
        public static final Type<ClientCandidateUpload> TYPE = new Type<>(id("server_lod/client_candidate_upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientCandidateUpload> STREAM_CODEC = StreamCodec.ofMember(ClientCandidateUpload::write, ClientCandidateUpload::read);

        private void write(RegistryFriendlyByteBuf buf) {
            writeMetadata(buf, this.metadata);
            buf.writeByteArray(this.compressedPayload);
        }

        private static ClientCandidateUpload read(RegistryFriendlyByteBuf buf) {
            return new ClientCandidateUpload(readMetadata(buf), buf.readByteArray(ServerLodConstants.MAX_TILE_BATCH_BYTES));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerInvalidate(List<ServerLodTileKey> keys, long epoch, String reason) implements CustomPacketPayload {
        public static final Type<ServerInvalidate> TYPE = new Type<>(id("server_lod/server_invalidate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInvalidate> STREAM_CODEC = StreamCodec.ofMember(ServerInvalidate::write, ServerInvalidate::read);

        private void write(RegistryFriendlyByteBuf buf) {
            writeList(buf, this.keys, ServerLodPayloads::writeKey);
            buf.writeLong(this.epoch);
            buf.writeUtf(this.reason, 128);
        }

        private static ServerInvalidate read(RegistryFriendlyByteBuf buf) {
            return new ServerInvalidate(readList(buf, ServerLodPayloads::readKey), buf.readLong(), buf.readUtf(128));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
