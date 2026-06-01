package me.cortex.voxy.commonImpl.serverlod;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ServerLodGenerationBridge {
    private ServerLodGenerationBridge() {}

    public static ServerLodTileStore.StoreResult publishTile(ServerLodTile tile) {
        return publishTile(ServerLodSyncManager.getStore(), tile);
    }

    public static ServerLodTileStore.StoreResult publishTile(ServerLodTileStore store, ServerLodTile tile) {
        if (!mayPublishTrusted(tile.metadata())) {
            return ServerLodTileStore.StoreResult.REJECTED_LOWER_AUTHORITY;
        }
        return store.store(tile.metadata(), tile.compressedPayload());
    }

    public static ServerLodTileStore.StoreResult publishRealChunkTile(
            ResourceKey<Level> dimension,
            int lodLevel,
            int sectionX,
            int sectionY,
            int sectionZ,
            int generatorVersion,
            long epoch,
            byte[] losslessTilePayload,
            boolean parentComplete,
            boolean childComplete
    ) {
        var key = new ServerLodTileKey(dimension.location().toString(), lodLevel, sectionX, sectionY, sectionZ, generatorVersion);
        var metadata = new ServerLodTileMetadata(
                key,
                epoch,
                ServerLodConstants.sha256Hex(losslessTilePayload),
                ServerLodSourceKind.REAL_SERVER_CHUNK,
                ServerLodConfidence.REAL,
                ServerLodLightKind.REAL_SKY,
                parentComplete,
                childComplete,
                losslessTilePayload.length);
        return ServerLodSyncManager.getStore().store(metadata, losslessTilePayload);
    }

    public static ServerLodTileStore.StoreResult publishSurfacePreviewTile(
            ResourceKey<Level> dimension,
            int lodLevel,
            int sectionX,
            int sectionY,
            int sectionZ,
            int generatorVersion,
            long epoch,
            byte[] losslessTilePayload,
            ServerLodConfidence confidence,
            ServerLodLightKind lightKind,
            boolean parentComplete,
            boolean childComplete
    ) {
        var key = new ServerLodTileKey(dimension.location().toString(), lodLevel, sectionX, sectionY, sectionZ, generatorVersion);
        var metadata = new ServerLodTileMetadata(
                key,
                epoch,
                ServerLodConstants.sha256Hex(losslessTilePayload),
                ServerLodSourceKind.SERVER_SURFACE_PREVIEW,
                confidence,
                lightKind,
                parentComplete,
                childComplete,
                losslessTilePayload.length);
        return ServerLodSyncManager.getStore().store(metadata, losslessTilePayload);
    }

    public static boolean mayPublishTrusted(ServerLodTileMetadata metadata) {
        if (metadata.sourceKind().isReal()) {
            return !ServerLodTileIntegrity.hasTrustedLightViolation(metadata)
                    && metadata.confidence().precedence() >= ServerLodConfidence.MEDIUM.precedence();
        }
        return metadata.confidence().precedence() <= ServerLodConfidence.HIGH.precedence()
                && metadata.lightKind() != ServerLodLightKind.MISSING;
    }
}
