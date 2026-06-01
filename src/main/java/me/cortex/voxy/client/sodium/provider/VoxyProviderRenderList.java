package me.cortex.voxy.client.sodium.provider;

public record VoxyProviderRenderList(
        VoxyTerrainPass pass,
        int[] meshIds,
        long epoch,
        long sectionCount,
        String authorityVerdict
) {
    public static VoxyProviderRenderList empty(VoxyTerrainPass pass, long epoch, String authorityVerdict) {
        return new VoxyProviderRenderList(pass, new int[0], epoch, 0, authorityVerdict);
    }

    public VoxyProviderRenderList {
        meshIds = meshIds == null ? new int[0] : meshIds.clone();
        sectionCount = meshIds.length;
        authorityVerdict = authorityVerdict == null
                ? VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS
                : authorityVerdict;
    }

    @Override
    public int[] meshIds() {
        return this.meshIds.clone();
    }

    public VoxyProviderRenderList withAuthorityVerdict(String authorityVerdict) {
        return new VoxyProviderRenderList(
                this.pass,
                this.meshIds,
                this.epoch,
                this.sectionCount,
                authorityVerdict
        );
    }
}
