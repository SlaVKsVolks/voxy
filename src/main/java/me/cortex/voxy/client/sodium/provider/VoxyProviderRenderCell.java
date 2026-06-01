package me.cortex.voxy.client.sodium.provider;

public record VoxyProviderRenderCell(
        long sectionKey,
        int meshId,
        VoxyTerrainOwnership ownership,
        VoxyTerrainFailureReason reason,
        long requestEpoch,
        int passMask,
        boolean sourceTrusted,
        boolean materialValid,
        boolean parentSuppressed
) {
    public static final int PASS_SOLID = 1;
    public static final int PASS_CUTOUT = 1 << 1;

    public boolean renderOwned() {
        return (this.ownership == VoxyTerrainOwnership.VOXY_EXACT_LOD
                || this.ownership == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK)
                && this.meshId >= 0
                && this.sourceTrusted
                && this.materialValid
                && this.passMask != 0
                && Integer.bitCount(this.passMask) == 1;
    }

    public boolean supports(VoxyTerrainPass pass) {
        return switch (pass) {
            case SOLID -> (this.passMask & PASS_SOLID) != 0;
            case CUTOUT -> (this.passMask & PASS_CUTOUT) != 0;
            case TRANSLUCENT, FLUID, DEBUG -> false;
        };
    }
}
