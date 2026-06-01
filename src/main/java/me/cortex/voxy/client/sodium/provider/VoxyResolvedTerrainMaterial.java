package me.cortex.voxy.client.sodium.provider;

public record VoxyResolvedTerrainMaterial(
        int sourceBlockStateId,
        int sourceBiomeId,
        int colorArgb,
        int packedLight,
        VoxyTerrainPass pass,
        String modelKey,
        boolean valid,
        VoxyTerrainFailureReason failureReason
) {
    public boolean canMesh() {
        return this.valid && this.failureReason == VoxyTerrainFailureReason.NONE;
    }
}
