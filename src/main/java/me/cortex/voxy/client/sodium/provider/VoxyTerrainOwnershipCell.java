package me.cortex.voxy.client.sodium.provider;

public record VoxyTerrainOwnershipCell(
        int chunkX,
        int chunkZ,
        int lodLevel,
        VoxyTerrainOwnership ownership,
        VoxyTerrainFailureReason reason
) {
    public boolean rendersVoxyGeometry() {
        return this.ownership == VoxyTerrainOwnership.VOXY_EXACT_LOD
                || this.ownership == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK;
    }
}
