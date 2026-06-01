package me.cortex.voxy.client.sodium.provider;

public record VoxyFarTerrainProviderSnapshot(
        String renderDistanceSliderMode,
        int visualTerrainDistanceChunks,
        int realChunkRadiusChunks,
        int handoffOverlapChunks,
        int voxyLodStartChunks,
        int voxyLodEndChunks,
        boolean sodiumChunkRenderingEnabled,
        boolean irisShaderPackEnabled
) {
    public boolean hasMergedDistanceOwnership() {
        return this.visualTerrainDistanceChunks >= this.realChunkRadiusChunks
                && this.voxyLodStartChunks <= this.realChunkRadiusChunks
                && this.voxyLodEndChunks == this.visualTerrainDistanceChunks;
    }

    public boolean boundaryCanOverlapVanilla() {
        return this.voxyLodStartChunks <= this.realChunkRadiusChunks;
    }
}
