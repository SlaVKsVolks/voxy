package me.cortex.voxy.client.sodium.provider;

public record VoxyProviderDrawDecision(
        VoxyTerrainPass pass,
        boolean draw,
        long sectionCount,
        VoxyProviderRenderList renderList,
        String authorityVerdict,
        VoxyTerrainFailureReason reason
) {
    public static VoxyProviderDrawDecision draw(
            VoxyTerrainPass pass,
            VoxyProviderRenderList renderList
    ) {
        return new VoxyProviderDrawDecision(
                pass,
                true,
                renderList.sectionCount(),
                renderList,
                renderList.authorityVerdict(),
                VoxyTerrainFailureReason.NONE
        );
    }

    public static VoxyProviderDrawDecision skip(
            VoxyTerrainPass pass,
            long sectionCount,
            String authorityVerdict,
            VoxyTerrainFailureReason reason
    ) {
        VoxyProviderRenderList renderList = VoxyProviderRenderList.empty(pass, 0, authorityVerdict);
        return new VoxyProviderDrawDecision(
                pass,
                false,
                sectionCount,
                renderList,
                authorityVerdict,
                reason
        );
    }

    public static VoxyProviderDrawDecision skip(
            VoxyTerrainPass pass,
            VoxyProviderRenderList renderList,
            String authorityVerdict,
            VoxyTerrainFailureReason reason
    ) {
        return new VoxyProviderDrawDecision(
                pass,
                false,
                renderList.sectionCount(),
                renderList,
                authorityVerdict,
                reason
        );
    }

    public VoxyProviderDrawDecision {
        renderList = renderList == null
                ? VoxyProviderRenderList.empty(pass, 0, authorityVerdict)
                : renderList;
    }

    public int[] meshIds() {
        return this.renderList.meshIds();
    }
}
