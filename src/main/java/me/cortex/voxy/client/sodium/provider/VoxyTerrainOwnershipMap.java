package me.cortex.voxy.client.sodium.provider;

public final class VoxyTerrainOwnershipMap {
    private final VoxyFarTerrainProviderSnapshot snapshot;

    public VoxyTerrainOwnershipMap(VoxyFarTerrainProviderSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public VoxyTerrainOwnershipCell classifyChunkRadius(
            int chunkX,
            int chunkZ,
            int chunkRadius,
            boolean exactLodAvailable,
            boolean parentFallbackAvailable,
            boolean tileRejected
    ) {
        return this.classifyChunkDistanceRange(
                chunkX,
                chunkZ,
                chunkRadius,
                chunkRadius,
                exactLodAvailable,
                parentFallbackAvailable,
                tileRejected
        );
    }

    public VoxyTerrainOwnershipCell classifyChunkDistanceRange(
            int chunkX,
            int chunkZ,
            int minChunkRadius,
            int maxChunkRadius,
            boolean exactLodAvailable,
            boolean parentFallbackAvailable,
            boolean tileRejected
    ) {
        return this.classifyChunkDistanceRange(
                chunkX,
                chunkZ,
                (double) minChunkRadius,
                (double) maxChunkRadius,
                exactLodAvailable,
                parentFallbackAvailable,
                tileRejected
        );
    }

    public VoxyTerrainOwnershipCell classifyChunkDistanceRange(
            int chunkX,
            int chunkZ,
            double minChunkRadius,
            double maxChunkRadius,
            boolean exactLodAvailable,
            boolean parentFallbackAvailable,
            boolean tileRejected
    ) {
        if (maxChunkRadius < 0 || minChunkRadius > this.snapshot.voxyLodEndChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.EMPTY_OUTSIDE_DISTANCE, VoxyTerrainFailureReason.NONE);
        }
        if (tileRejected) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.REJECTED_INVALID, VoxyTerrainFailureReason.INVALID_PALETTE_ID);
        }
        int minRadius = Math.max(0, (int) Math.floor(minChunkRadius));
        int maxRadius = Math.max(minRadius, (int) Math.ceil(maxChunkRadius));
        if (maxRadius < this.snapshot.voxyLodStartChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VANILLA_EXACT, VoxyTerrainFailureReason.NONE);
        }
        if (exactLodAvailable) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VOXY_EXACT_LOD, VoxyTerrainFailureReason.NONE);
        }
        if (parentFallbackAvailable) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 1,
                    VoxyTerrainOwnership.VOXY_PARENT_FALLBACK, VoxyTerrainFailureReason.VALID_PARENT_FALLBACK);
        }
        if (minRadius <= this.snapshot.realChunkRadiusChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VANILLA_EXACT, VoxyTerrainFailureReason.MISSING_EXACT_CHILD);
        }
        return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                VoxyTerrainOwnership.REJECTED_INVALID, VoxyTerrainFailureReason.MISSING_EXACT_CHILD);
    }
}
