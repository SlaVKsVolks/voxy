package me.cortex.voxy.commonImpl.serverlod;

public final class ServerLodTileIntegrity {
    private ServerLodTileIntegrity() {}

    public static boolean isEmptyAdvertised(ServerLodTileMetadata metadata) {
        return metadata == null || metadata.compressedBytes() <= 0;
    }

    public static boolean isEmptyPayload(byte[] payload) {
        return payload == null || payload.length == 0;
    }

    public static boolean isSyntheticAdvertised(ServerLodTileMetadata metadata) {
        return metadata != null
                && (metadata.sourceKind() == ServerLodSourceKind.SERVER_SURFACE_PREVIEW
                || metadata.sourceKind() == ServerLodSourceKind.SERVER_REFINED_PREGEN
                || metadata.lightKind() == ServerLodLightKind.SYNTHETIC_SURFACE);
    }

    public static boolean allowSyntheticPreviewManifest() {
        return Boolean.getBoolean("voxy.serverLodAllowSyntheticPreviewManifest");
    }

    public static boolean canAdvertise(ServerLodTileMetadata metadata) {
        return !isEmptyAdvertised(metadata)
                && !hasTrustedLightViolation(metadata)
                && (!isSyntheticAdvertised(metadata) || allowSyntheticPreviewManifest());
    }

    public static boolean hasTrustedLightViolation(ServerLodTileMetadata metadata) {
        return metadata != null
                && metadata.sourceKind().isReal()
                && !metadata.lightKind().trustedForFinalData();
    }

    public static boolean hasMissingLightTrustedViolation(ServerLodTileMetadata metadata) {
        return metadata != null
                && metadata.sourceKind().isReal()
                && metadata.lightKind() == ServerLodLightKind.MISSING;
    }

    public static String validateStoredTile(ServerLodTileMetadata metadata, byte[] payload) {
        if (isEmptyAdvertised(metadata)) {
            return "empty advertised tile";
        }
        if (isEmptyPayload(payload)) {
            return "empty tile payload";
        }
        if (metadata.compressedBytes() != payload.length) {
            return "advertised tile bytes " + metadata.compressedBytes() + " != payload bytes " + payload.length;
        }
        if (hasTrustedLightViolation(metadata)) {
            return "trusted tile has untrusted light kind " + metadata.lightKind();
        }
        return "";
    }

    public static String validateAuthoredVlcp3Tile(ServerLodTile tile) {
        if (tile == null) {
            return "missing tile";
        }
        String stored = validateStoredTile(tile.metadata(), tile.compressedPayload());
        if (!stored.isEmpty()) {
            return stored;
        }
        if (!tile.metadata().sourceKind().isReal()) {
            return "authored VLCP0003 tile is not sourced from a real server chunk";
        }
        if (isSyntheticAdvertised(tile.metadata())) {
            return "authored VLCP0003 tile advertises synthetic data";
        }
        return "";
    }
}
