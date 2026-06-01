package me.cortex.voxy.client.sodium.provider;

public final class VoxyProviderTerrainPassClassifier {
    private VoxyProviderTerrainPassClassifier() {
    }

    public static int passMaskForCounts(
            int translucentQuadCount,
            int cutoutQuadCount,
            int solidQuadCount
    ) {
        if (solidQuadCount > 0) {
            return VoxyProviderRenderCell.PASS_SOLID;
        }
        if (cutoutQuadCount > 0) {
            return VoxyProviderRenderCell.PASS_CUTOUT;
        }
        return 0;
    }
}
